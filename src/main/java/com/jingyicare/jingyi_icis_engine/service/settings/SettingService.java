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
import com.jingyicare.jingyi_icis_engine.entity.reports.DragableFormTemplate;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTemplatePB;
import com.jingyicare.jingyi_icis_engine.repository.reports.DragableFormTemplateRepository;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
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
        @Autowired DeptSystemSettingsRepository deptSettingsRepo,
        @Autowired SystemSettingsRepository systemSettingsRepo,
        @Autowired DragableFormTemplateRepository formTemplateRepo,
        @Autowired LockExecutor lockExecutor
    ) {
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();

        this.protoService = protoService;
        this.userService = userService;
        this.medConfig = medConfig;
        this.monitoringConfig = monitoringConfig;
        this.deptSettingsRepo = deptSettingsRepo;
        this.systemSettingsRepo = systemSettingsRepo;
        this.formTemplateRepo = formTemplateRepo;
        this.lockExecutor = lockExecutor;
    }

    @Transactional(readOnly = true)
    public GetArchiveConfigResp getArchiveConfig(String requestJson) {
        final GetArchiveConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, GetArchiveConfigReq.newBuilder());
        } catch (Exception e) {
            return GetArchiveConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED)).build();
        }
        if (StrUtils.isBlank(req.getDeptId())) {
            return GetArchiveConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_IS_EMPTY)).build();
        }

        DeptSystemSettingsId id = new DeptSystemSettingsId(
            req.getDeptId(), SystemSettingFunctionId.GET_ARCHIVE_CONFIG_VALUE);
        DeptSystemSettings entity = deptSettingsRepo.findById(id).orElse(null);
        if (entity == null) {
            return GetArchiveConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .setSettings(DeptArchiveSettings.newBuilder().setDeptId(req.getDeptId())).build();
        }
        DeptArchiveSettings settings = ProtoUtils.decodeDeptArchiveSettings(entity.getSettingsPb());
        if (settings == null || !req.getDeptId().equals(settings.getDeptId())) {
            return GetArchiveConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ARCHIVE_CONFIG_INVALID)).build();
        }
        return resolveArchiveConfig(settings);
    }

    public GenericResp updateArchiveConfig(String requestJson) {
        final UpdateArchiveConfigReq req;
        try {
            req = ProtoUtils.parseJsonToProto(requestJson, UpdateArchiveConfigReq.newBuilder());
        } catch (Exception e) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED)).build();
        }
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND)).build();
        }
        if (!req.hasSettings()) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ARCHIVE_CONFIG_INVALID)).build();
        }
        DeptArchiveSettings settings = req.getSettings();
        if (StrUtils.isBlank(settings.getDeptId())) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_IS_EMPTY)).build();
        }
        String resourceKey = LockKeys.deptSystemSettings(
            settings.getDeptId(), SystemSettingFunctionId.GET_ARCHIVE_CONFIG_VALUE);
        return lockExecutor.execute(List.of(resourceKey),
            () -> saveArchiveConfig(settings, account.getFirst()));
    }

    private GenericResp saveArchiveConfig(DeptArchiveSettings settings, String accountId) {
        GetArchiveConfigResp resolved = resolveArchiveConfig(settings);
        if (resolved.getRt().getCode() != StatusCode.OK_VALUE) {
            return GenericResp.newBuilder().setRt(resolved.getRt()).build();
        }

        DeptSystemSettingsId id = new DeptSystemSettingsId(
            settings.getDeptId(), SystemSettingFunctionId.GET_ARCHIVE_CONFIG_VALUE);
        DeptSystemSettings entity = deptSettingsRepo.findById(id).orElse(null);
        if (entity == null) entity = DeptSystemSettings.builder().id(id).build();
        entity.setSettingsPb(ProtoUtils.encodeDeptArchiveSettings(settings));
        entity.setModifiedAt(TimeUtils.getNowUtc());
        entity.setModifiedBy(accountId);
        deptSettingsRepo.save(entity);
        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK)).build();
    }

    // 每次读取重新解析模板，确保模板改名、修改、删除或变更科室后使用最新结果。
    private GetArchiveConfigResp resolveArchiveConfig(DeptArchiveSettings settings) {
        GetArchiveConfigResp.Builder response = GetArchiveConfigResp.newBuilder().setSettings(settings);
        if (settings.getWardReportTemplateId() <= 0) {
            return response.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.WARD_REPORT_TEMPLATE_REQUIRED)).build();
        }
        DragableFormTemplate entity = formTemplateRepo
            .findByIdAndIsDeletedFalse(settings.getWardReportTemplateId()).orElse(null);
        if (entity == null) {
            return response.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_FOUND)).build();
        }
        if (!settings.getDeptId().equals(entity.getDeptId())) {
            return response.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.FORM_TEMPLATE_NOT_BELONG_TO_DEPT)).build();
        }
        JfkTemplatePB template = ProtoUtils.decodeJfkTemplate(entity.getTemplatePb());
        if (template == null || template.getPagesCount() == 0) {
            return response.setRt(ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.WARD_REPORT_TEMPLATE_INVALID)).build();
        }
        return response.setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setWardReportTemplate(template.toBuilder()
                .setId(entity.getId()).setDeptId(entity.getDeptId()).setName(entity.getName()))
            .build();
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
        NursingRecordSettingsPB nursingRecordSettingsPb = getNursingRecordSettings(deptId);
        ScoreSettingsPB scoreSettingsPb = getDeptScoreSettings(deptId);
        AppGeneralSettingsPB generalSettingsPb = loadGeneralSettings(deptId);

        return AppSettingsPB.newBuilder()
            .setEnableMedicationSpeed(medSettingsPb != null && medSettingsPb.getEnableMedicationSpeed())
            .setHeadCustomTimeGraceMinutes(
                monitoringSettingsPb == null ? 0 : monitoringSettingsPb.getHeadCustomTimePointGraceMinutes())
            .setTailCustomTimeGraceMinutes(
                monitoringSettingsPb == null ? 0 : monitoringSettingsPb.getTailCustomTimePointGraceMinutes())
            .setNursingRecordOverwriteCreatedBy(
                nursingRecordSettingsPb.getEnableUpdatingCreatedBy())
            .setScoreAllowEditRecordedBy(scoreSettingsPb.getAllowEditRecordedBy())
            .setJfkUseNativePrint(generalSettingsPb.getJfkUseNativePrint())
            .setPrintAgentIpPort(generalSettingsPb.getPrintAgentIpPort())
            .setCheckFutureTime(generalSettingsPb.getCheckFutureTime())
            .setEnableCa(generalSettingsPb.getEnableCa())
            .build();
    }

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
        Set<AppSettingTypeEnum> settingTypes = new HashSet<>(req.getChangedTypeList());
        AppSettingsPB appSettingsPb = req.getSettings();
        Set<Integer> functionIds = settingTypes.stream().map(type -> switch (type) {
            case AST_ENABLE_MEDICATION_SPEED ->
                SystemSettingFunctionId.GET_MEDICAL_ORDER_SETTINGS_VALUE;
            case AST_HEAD_CUSTOM_TIME_GRACE_MINUTES, AST_TAIL_CUSTOM_TIME_GRACE_MINUTES ->
                SystemSettingFunctionId.GET_DEPT_MONITORING_SETTINGS_VALUE;
            case AST_NURSING_RECORD_OVERWRITE_CREATED_BY ->
                SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS_VALUE;
            case AST_SCORE_ALLOW_EDIT_RECORDED_BY ->
                SystemSettingFunctionId.GET_DEPT_NURSING_SCORE_SETTINGS_VALUE;
            case AST_GENERAL, AST_ENABLE_CA -> SystemSettingFunctionId.GET_DEPT_APP_SETTINGS_VALUE;
            default -> 0;
        }).filter(functionId -> functionId > 0).collect(Collectors.toSet());
        if (functionIds.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK)).build();
        }
        if (StrUtils.isBlank(deptId)) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.DEPT_IS_EMPTY))
                .build();
        }
        List<String> resourceKeys = functionIds.stream()
            .map(functionId -> LockKeys.deptSystemSettings(deptId, functionId)).toList();
        return lockExecutor.execute(resourceKeys,
            () -> saveAppSettings(deptId, settingTypes, appSettingsPb, accountId));
    }

    private GenericResp saveAppSettings(
        String deptId, Set<AppSettingTypeEnum> settingTypes, AppSettingsPB appSettingsPb,
        String accountId
    ) {
        // 设置医嘱信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_ENABLE_MEDICATION_SPEED)) {
            MedOrderGroupSettingsPB medSettingsPb = medConfig.getMedOrderGroupSettings(deptId);
            if (medSettingsPb == null) {
                log.error("MedOrderGroupSettings not found for deptId {}", deptId);
                medSettingsPb = MedOrderGroupSettingsPB.newBuilder()
                    .setEnableMedicationSpeed(appSettingsPb.getEnableMedicationSpeed()).build();
            } else {
                medSettingsPb = medSettingsPb.toBuilder()
                    .setEnableMedicationSpeed(appSettingsPb.getEnableMedicationSpeed()).build();
            }
            medConfig.setMedOrderGroupSettings(deptId, medSettingsPb, accountId);
        }

        // 设置观察项信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_HEAD_CUSTOM_TIME_GRACE_MINUTES)
            || settingTypes.contains(AppSettingTypeEnum.AST_TAIL_CUSTOM_TIME_GRACE_MINUTES)
        ) {
            DeptMonitoringSettingsPB monitoringSettingsPb = monitoringConfig
                .getDeptMonitoringSettings(deptId);
            if (monitoringSettingsPb == null) {
                monitoringSettingsPb = DeptMonitoringSettingsPB.newBuilder().build();
            }
            DeptMonitoringSettingsPB.Builder monitoringSettingsPbBuilder = monitoringSettingsPb
                .toBuilder();
            if (settingTypes.contains(AppSettingTypeEnum.AST_HEAD_CUSTOM_TIME_GRACE_MINUTES)) {
                monitoringSettingsPbBuilder.setHeadCustomTimePointGraceMinutes(
                    appSettingsPb.getHeadCustomTimeGraceMinutes());
            }
            if (settingTypes.contains(AppSettingTypeEnum.AST_TAIL_CUSTOM_TIME_GRACE_MINUTES)) {
                monitoringSettingsPbBuilder.setTailCustomTimePointGraceMinutes(
                    appSettingsPb.getTailCustomTimeGraceMinutes());
            }
            monitoringConfig.setDeptMonitoringSettings(
                deptId, monitoringSettingsPbBuilder.build(), accountId);
        }

        // 设置护理记录信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_NURSING_RECORD_OVERWRITE_CREATED_BY)) {
            boolean enableUpdatingCreatedBy = appSettingsPb.getNursingRecordOverwriteCreatedBy();
            NursingRecordSettingsPB nursingRecordSettingsPb = getNursingRecordSettings(deptId)
                .toBuilder().setEnableUpdatingCreatedBy(enableUpdatingCreatedBy).build();
            setNursingRecordSettings(deptId, nursingRecordSettingsPb, accountId);
        }

        // 设置评分记录信息
        if (settingTypes.contains(AppSettingTypeEnum.AST_SCORE_ALLOW_EDIT_RECORDED_BY)) {
            ScoreSettingsPB scoreSettingsPb = getDeptScoreSettings(deptId).toBuilder()
                .setAllowEditRecordedBy(appSettingsPb.getScoreAllowEditRecordedBy()).build();
            saveDeptScoreSettings(deptId, scoreSettingsPb, accountId);
        }

        // 通用设置与CA开关共用同一个PB，使用读改写避免互相覆盖。
        if (settingTypes.contains(AppSettingTypeEnum.AST_GENERAL)
            || settingTypes.contains(AppSettingTypeEnum.AST_ENABLE_CA)) {
            AppGeneralSettingsPB.Builder generalSettingsBuilder = loadGeneralSettings(deptId)
                .toBuilder();
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
            if (entity == null) {
                entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, accountId);
            } else {
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

    public NursingRecordSettingsPB getNursingRecordSettings(String deptId) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingsRepo.findById(settingsId).orElse(null);

        NursingRecordSettingsPB settingsPb = null;
        if (entity != null) {
            settingsPb = ProtoUtils.decodeNursingRecordSettings(entity.getSettingsPb());
        }
        if (settingsPb == null) settingsPb = NursingRecordSettingsPB.newBuilder().build();

        return settingsPb;
    }

    public void setNursingRecordSettings(
        String deptId, NursingRecordSettingsPB settingsPb, String modifiedBy
    ) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingsRepo.findById(settingsId).orElse(null);
        String settingsStr = ProtoUtils.encodeNursingRecordSettings(settingsPb);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (entity == null) {
            entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, modifiedBy);
        } else {
            entity.setSettingsPb(settingsStr);
            entity.setModifiedAt(nowUtc);
            entity.setModifiedBy(modifiedBy);
        }
        deptSettingsRepo.save(entity);
    }

    public ScoreSettingsPB getDeptScoreSettings(String deptId) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_SCORE_SETTINGS_VALUE);
        DeptSystemSettings entity = deptSettingsRepo.findById(settingsId).orElse(null);
        ScoreSettingsPB settingsPb = entity == null
            ? null : ProtoUtils.decodeScoreSettings(entity.getSettingsPb());
        return settingsPb == null ? ScoreSettingsPB.getDefaultInstance() : settingsPb;
    }

    public void setDeptScoreSettings(String deptId, ScoreSettingsPB settingsPb, String modifiedBy) {
        String resourceKey = LockKeys.deptSystemSettings(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_SCORE_SETTINGS_VALUE);
        lockExecutor.execute(List.of(resourceKey), () -> {
            saveDeptScoreSettings(deptId, settingsPb, modifiedBy);
            return null;
        });
    }

    // 独立写入和批量设置更新共用此方法，调用方已持有评分设置锁及事务。
    private void saveDeptScoreSettings(
        String deptId, ScoreSettingsPB settingsPb, String modifiedBy
    ) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_SCORE_SETTINGS_VALUE);
        DeptSystemSettings entity = deptSettingsRepo.findById(settingsId).orElse(null);
        String settingsStr = ProtoUtils.encodeScoreSettings(settingsPb);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (entity == null) {
            entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, modifiedBy);
        } else {
            entity.setSettingsPb(settingsStr);
            entity.setModifiedAt(nowUtc);
            entity.setModifiedBy(modifiedBy);
        }
        deptSettingsRepo.save(entity);
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
        final String customLogoB64 = req.getCustomLogoB64().trim();
        return lockExecutor.execute(List.of(LockKeys.systemSettings(functionId)),
            () -> saveLogo(functionId, customLogoB64, accountId));
    }

    private GenericResp saveLogo(int functionId, String customLogoB64, String accountId) {
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();

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
    private final DeptSystemSettingsRepository deptSettingsRepo;
    private final SystemSettingsRepository systemSettingsRepo;
    private final DragableFormTemplateRepository formTemplateRepo;
    private final LockExecutor lockExecutor;
}
