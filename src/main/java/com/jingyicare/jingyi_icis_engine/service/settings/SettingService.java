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
        @Autowired DeptSystemSettingsRepository settingsRepo
    ) {
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();

        this.protoService = protoService;
        this.userService = userService;
        this.medConfig = medConfig;
        this.monitoringConfig = monitoringConfig;
        this.nursingRecordConfig = nursingRecordConfig;
        this.scoreConfig = scoreConfig;
        this.settingsRepo = settingsRepo;
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

        // 获取请求参数
        String deptId = req.getDeptId();

        // 获取医嘱信息
        MedOrderGroupSettingsPB medSettingsPb = medConfig.getMedOrderGroupSettings(deptId);
        boolean enableMedSpeed = medSettingsPb == null ? false : medSettingsPb.getEnableMedicationSpeed();

        // 获取观察项信息
        DeptMonitoringSettingsPB monitoringSettingsPb = monitoringConfig.getDeptMonitoringSettings(deptId);
        int getHeadCustomTimePointGraceMinutes = monitoringSettingsPb.getHeadCustomTimePointGraceMinutes();
        int getTailCustomTimePointGraceMinutes = monitoringSettingsPb.getTailCustomTimePointGraceMinutes();

        // 获取护理记录信息
        NursingRecordSettingsPB nursingRecordSettingsPb = nursingRecordConfig.getNursingRecordSettings(deptId);
        boolean enableUpdatingCreatedBy = nursingRecordSettingsPb.getEnableUpdatingCreatedBy();

        // 获取评分记录信息
        ScoreSettingsPB scoreSettingsPb = scoreConfig.getDeptScoreSettings(deptId);
        boolean allowEditRecordedBy = scoreSettingsPb.getAllowEditRecordedBy();

        // 获取通用设置信息
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_APP_SETTINGS.getNumber());
        DeptSystemSettings settingsEntity = settingsRepo.findById(settingsId).orElse(null);
        AppGeneralSettingsPB generalSettingsPb = null;
        if (settingsEntity != null) {
            generalSettingsPb = ProtoUtils.decodeAppGeneralSettings(settingsEntity.getSettingsPb());
        }
        if (generalSettingsPb == null) {
            generalSettingsPb = AppGeneralSettingsPB.newBuilder()
                .setJfkUseNativePrint(false)
                .setPrintAgentIpPort("127.0.0.1:9123")
                .build();
        }

        return GetAppSettingsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setSettings(AppSettingsPB.newBuilder()
                .setEnableMedicationSpeed(enableMedSpeed)
                .setHeadCustomTimeGraceMinutes(getHeadCustomTimePointGraceMinutes)
                .setTailCustomTimeGraceMinutes(getTailCustomTimePointGraceMinutes)
                .setNursingRecordOverwriteCreatedBy(enableUpdatingCreatedBy)
                .setScoreAllowEditRecordedBy(allowEditRecordedBy)
                .setJfkUseNativePrint(generalSettingsPb.getJfkUseNativePrint())
                .setPrintAgentIpPort(generalSettingsPb.getPrintAgentIpPort())
                .build()
            )
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

        // 设置通用信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_GENERAL)) {
            AppGeneralSettingsPB generalSettingsPb = AppGeneralSettingsPB.newBuilder()
                .setJfkUseNativePrint(appSettingsPb.getJfkUseNativePrint())
                .setPrintAgentIpPort(appSettingsPb.getPrintAgentIpPort())
                .build();

            DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
                deptId, SystemSettingFunctionId.GET_DEPT_APP_SETTINGS.getNumber());
            DeptSystemSettings entity = settingsRepo.findById(settingsId).orElse(null);
            String settingsStr = ProtoUtils.encodeAppGeneralSettings(generalSettingsPb);

            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            if (entity == null) entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, accountId);
            else {
                entity.setSettingsPb(settingsStr);
                entity.setModifiedAt(nowUtc);
                entity.setModifiedBy(accountId);
            }
            settingsRepo.save(entity);
        }

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
    private final DeptSystemSettingsRepository settingsRepo;
}
