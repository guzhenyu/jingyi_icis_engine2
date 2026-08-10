package com.jingyicare.jingyi_icis_engine.service.settings;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.service.scores.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class SettingService {
    public SettingService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired MedicationConfig medConfig,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired NursingRecordConfig nursingRecordConfig,
        @Autowired ScoreConfig scoreConfig,  // nursing score
        @Autowired DeptSystemSettingsRepository deptSettingsRepo,
        @Autowired SystemSettingsRepository systemSettingsRepo
    ) {
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();

        this.protoService = protoService;
        this.userService = userService;
        this.medConfig = medConfig;
        this.monitoringConfig = monitoringConfig;
        this.nursingRecordConfig = nursingRecordConfig;
        this.scoreConfig = scoreConfig;
        this.deptSettingsRepo = deptSettingsRepo;
        this.systemSettingsRepo = systemSettingsRepo;
    }

    @Transactional
    public GetAppSettingsResp getAppSettings(String getAppSettingsReqJson) {
        final GetAppSettingsReq req;
        try {
            GetAppSettingsReq.Builder builder = GetAppSettingsReq.newBuilder();
            JsonFormat.parser().merge(getAppSettingsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetAppSettingsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        return GetAppSettingsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setSettings(getAppSettingsForService(req.getDeptId()))
            .build();
    }

    @Transactional(readOnly = true)
    public AppSettingsPB getAppSettingsForService(String deptId) {
        MedOrderGroupSettingsPB medSettingsPb = medConfig.getMedOrderGroupSettings(deptId);
        DeptMonitoringSettingsPB monitoringSettingsPb = monitoringConfig.getDeptMonitoringSettings(deptId);
        NursingRecordSettingsPB nursingRecordSettingsPb = nursingRecordConfig.getNursingRecordSettings(deptId);
        ScoreSettingsPB scoreSettingsPb = scoreConfig.getDeptScoreSettings(deptId);
        AppGeneralSettingsPB generalSettingsPb = loadGeneralSettings(deptId);

        return AppSettingsPB.newBuilder()
            .setEnableMedicationSpeed(medSettingsPb != null && medSettingsPb.getEnableMedicationSpeed())
            .setHeadCustomTimeGraceMinutes(
                monitoringSettingsPb == null ? 0 : monitoringSettingsPb.getHeadCustomTimePointGraceMinutes())
            .setTailCustomTimeGraceMinutes(
                monitoringSettingsPb == null ? 0 : monitoringSettingsPb.getTailCustomTimePointGraceMinutes())
            .setNursingRecordOverwriteCreatedBy(
                nursingRecordSettingsPb != null && nursingRecordSettingsPb.getEnableUpdatingCreatedBy())
            .setScoreAllowEditRecordedBy(
                scoreSettingsPb != null && scoreSettingsPb.getAllowEditRecordedBy())
            .setJfkUseNativePrint(generalSettingsPb.getJfkUseNativePrint())
            .setPrintAgentIpPort(generalSettingsPb.getPrintAgentIpPort())
            .setCheckFutureTime(generalSettingsPb.getCheckFutureTime())
            .setEnableCa(generalSettingsPb.getEnableCa())
            .build();
    }

    @Transactional
    public GenericResp updateAppSettings(String updateAppSettingsReqJson) {
        final UpdateAppSettingsReq req;
        try {
            UpdateAppSettingsReq.Builder builder = UpdateAppSettingsReq.newBuilder();
            JsonFormat.parser().merge(updateAppSettingsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();

        // 获取请求参数
        String deptId = req.getDeptId();
        Set<AppSettingTypeEnum> settingTypes = req.getChangedTypeList().stream().collect(Collectors.toSet());
        AppSettingsPB appSettingsPb = req.getSettings();

        // 设置医嘱信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_ENABLE_MEDICATION_SPEED)) {
            MedOrderGroupSettingsPB medSettingsPb = medConfig.getMedOrderGroupSettings(deptId);
            if (medSettingsPb == null) {
                log.error("MedOrderGroupSettings not found for deptId {}", deptId);
                medSettingsPb = MedOrderGroupSettingsPB.newBuilder().setEnableMedicationSpeed(appSettingsPb.getEnableMedicationSpeed()).build();
            } else {
                medSettingsPb = medSettingsPb.toBuilder().setEnableMedicationSpeed(appSettingsPb.getEnableMedicationSpeed()).build();
            }
            medConfig.setMedOrderGroupSettings(deptId, medSettingsPb, accountId);
        }

        // 设置观察项信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_HEAD_CUSTOM_TIME_GRACE_MINUTES)
            || settingTypes.contains(AppSettingTypeEnum.AST_TAIL_CUSTOM_TIME_GRACE_MINUTES)
        ) {
            DeptMonitoringSettingsPB monitoringSettingsPb = monitoringConfig.getDeptMonitoringSettings(deptId);
            if (monitoringSettingsPb == null) monitoringSettingsPb = DeptMonitoringSettingsPB.newBuilder().build();
            DeptMonitoringSettingsPB.Builder monitoringSettingsPbBuilder = monitoringSettingsPb.toBuilder();
            if (settingTypes.contains(AppSettingTypeEnum.AST_HEAD_CUSTOM_TIME_GRACE_MINUTES)) {
                monitoringSettingsPbBuilder.setHeadCustomTimePointGraceMinutes(appSettingsPb.getHeadCustomTimeGraceMinutes());
            }
            if (settingTypes.contains(AppSettingTypeEnum.AST_TAIL_CUSTOM_TIME_GRACE_MINUTES)) {
                monitoringSettingsPbBuilder.setTailCustomTimePointGraceMinutes(appSettingsPb.getTailCustomTimeGraceMinutes());
            }
            monitoringConfig.setDeptMonitoringSettings(deptId, monitoringSettingsPbBuilder.build(), accountId);
        }

        // 设置护理记录信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_NURSING_RECORD_OVERWRITE_CREATED_BY)) {
            boolean enableUpdatingCreatedBy = appSettingsPb.getNursingRecordOverwriteCreatedBy();
            NursingRecordSettingsPB nursingRecordSettingsPb = nursingRecordConfig.getNursingRecordSettings(deptId);
            if (nursingRecordSettingsPb == null) {
                log.error("NursingRecordSettings not found for deptId {}", deptId);
                nursingRecordSettingsPb = NursingRecordSettingsPB.newBuilder().setEnableUpdatingCreatedBy(enableUpdatingCreatedBy).build();
            } else {
                nursingRecordSettingsPb = nursingRecordSettingsPb.toBuilder().setEnableUpdatingCreatedBy(enableUpdatingCreatedBy).build();
            }
            nursingRecordConfig.setNursingRecordSettings(deptId, nursingRecordSettingsPb, accountId);
        }

        // 设置评分记录信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_SCORE_ALLOW_EDIT_RECORDED_BY)) {
            boolean allowEditRecordedBy = appSettingsPb.getScoreAllowEditRecordedBy();
            ScoreSettingsPB scoreSettingsPb = scoreConfig.getDeptScoreSettings(deptId);
            if (scoreSettingsPb == null) {
                log.error("ScoreSettings not found for deptId {}", deptId);
                scoreSettingsPb = ScoreSettingsPB.newBuilder().setDeptId(deptId).setAllowEditRecordedBy(allowEditRecordedBy).build();
            } else {
                scoreSettingsPb = scoreSettingsPb.toBuilder().setAllowEditRecordedBy(allowEditRecordedBy).build();
            }
            scoreConfig.setDeptScoreSettings(deptId, scoreSettingsPb, accountId);
        }

        // 通用设置与CA开关共用同一个PB，使用读改写避免互相覆盖。
        if (settingTypes.contains(AppSettingTypeEnum.AST_GENERAL)
            || settingTypes.contains(AppSettingTypeEnum.AST_ENABLE_CA)) {
            AppGeneralSettingsPB.Builder generalSettingsBuilder = loadGeneralSettings(deptId).toBuilder();
            if (settingTypes.contains(AppSettingTypeEnum.AST_GENERAL)) {
                generalSettingsBuilder
                    .setJfkUseNativePrint(appSettingsPb.getJfkUseNativePrint())
                    .setPrintAgentIpPort(appSettingsPb.getPrintAgentIpPort())
                    .setCheckFutureTime(appSettingsPb.getCheckFutureTime());
            }
            if (settingTypes.contains(AppSettingTypeEnum.AST_ENABLE_CA)) {
                generalSettingsBuilder.setEnableCa(appSettingsPb.getEnableCa());
            }
            AppGeneralSettingsPB generalSettingsPb = generalSettingsBuilder.build();

            DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
                deptId, SystemSettingFunctionId.GET_DEPT_APP_SETTINGS.getNumber());
            DeptSystemSettings entity = deptSettingsRepo.findById(settingsId).orElse(null);
            String settingsStr = ProtoUtils.encodeAppGeneralSettings(generalSettingsPb);

            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            if (entity == null) entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, accountId);
            else {
                entity.setSettingsPb(settingsStr);
                entity.setModifiedAt(nowUtc);
                entity.setModifiedBy(accountId);
            }
            deptSettingsRepo.save(entity);
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    private AppGeneralSettingsPB loadGeneralSettings(String deptId) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_APP_SETTINGS.getNumber());
        DeptSystemSettings settingsEntity = deptSettingsRepo.findById(settingsId).orElse(null);
        AppGeneralSettingsPB settings = settingsEntity == null
            ? null
            : ProtoUtils.decodeAppGeneralSettings(settingsEntity.getSettingsPb());
        if (settings != null) return settings;
        return AppGeneralSettingsPB.newBuilder()
            .setJfkUseNativePrint(false)
            .setPrintAgentIpPort("127.0.0.1:9123")
            .setCheckFutureTime(false)
            .setEnableCa(false)
            .build();
    }

    @Transactional(readOnly = true)
    public GetLogoResp getLogo(String getLogoReqJson) {
        try {
            GetLogoReq.Builder builder = GetLogoReq.newBuilder();
            JsonFormat.parser().merge(getLogoReqJson, builder);
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetLogoResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        SystemSettings settingsEntity = systemSettingsRepo
            .findById(SystemSettingFunctionId.GET_APP_LOGO.getNumber())
            .orElse(null);

        return GetLogoResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setCustomLogoB64(settingsEntity == null ? "" : settingsEntity.getSettingsPb())
            .build();
    }

    @Transactional
    public GenericResp updateLogo(String updateLogoReqJson) {
        final UpdateLogoReq req;
        try {
            UpdateLogoReq.Builder builder = UpdateLogoReq.newBuilder();
            JsonFormat.parser().merge(updateLogoReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final Integer functionId = SystemSettingFunctionId.GET_APP_LOGO.getNumber();
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        final String customLogoB64 = req.getCustomLogoB64().trim();

        SystemSettings entity = systemSettingsRepo.findById(functionId).orElse(null);
        if (entity == null) {
            entity = SystemSettings.builder()
                .functionId(functionId)
                .functionName(SystemSettingFunctionId.GET_APP_LOGO.name())
                .settingsPb(customLogoB64)
                .modifiedAt(nowUtc)
                .modifiedBy(accountId)
                .build();
        } else {
            entity.setFunctionName(SystemSettingFunctionId.GET_APP_LOGO.name());
            entity.setSettingsPb(customLogoB64);
            entity.setModifiedAt(nowUtc);
            entity.setModifiedBy(accountId);
        }
        systemSettingsRepo.save(entity);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    private final List<String> statusCodeMsgList;

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final MedicationConfig medConfig;
    private final MonitoringConfig monitoringConfig;
    private final NursingRecordConfig nursingRecordConfig;
    private final ScoreConfig scoreConfig;
    private final DeptSystemSettingsRepository deptSettingsRepo;
    private final SystemSettingsRepository systemSettingsRepo;
}
