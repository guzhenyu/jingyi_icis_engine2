package com.jingyicare.jingyi_icis_engine.service.settings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettings;
import com.jingyicare.jingyi_icis_engine.entity.settings.DeptSystemSettingsId;
import com.jingyicare.jingyi_icis_engine.entity.settings.SystemSettings;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedOrderGroupSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.DeptMonitoringSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.NursingRecordSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.ScoreSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.DisplayFieldSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.AppGeneralSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.DeptArchiveSettings;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.SystemSettingFunctionId;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.MenuConfigPB;
import com.jingyicare.jingyi_icis_engine.repository.settings.DeptSystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.repository.settings.SystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

/**
 * 设置的类型化存取；患者显示配置读取包含默认回退、列名生成及校验。
 * 其他设置的默认值和业务校验，以及写入的修改人和锁事务由调用方编排。
 */
@Component
@Slf4j
public class SettingsStore {
    public static final int GET_PATIENTS_IN_ICU =
        SystemSettingFunctionId.GET_PATIENTS_IN_ICU_VALUE;
    public static final int GET_PATIENTS_PENDING_ADMISSION =
        SystemSettingFunctionId.GET_PATIENTS_PENDING_ADMISSION_VALUE;
    public static final int GET_PATIENTS_PENDING_DISCHARGED =
        SystemSettingFunctionId.GET_PATIENTS_PENDING_DISCHARGED_VALUE;
    public static final int GET_PATIENTS_DISCHARGED =
        SystemSettingFunctionId.GET_PATIENTS_DISCHARGED_VALUE;
    public static final int GET_MEDICAL_ORDER_SETTINGS =
        SystemSettingFunctionId.GET_MEDICAL_ORDER_SETTINGS_VALUE;
    public static final int GET_DEPT_MENU_SETTINGS =
        SystemSettingFunctionId.GET_DEPT_MENU_SETTINGS_VALUE;
    public static final int GET_DEPT_MONITORING_SETTINGS =
        SystemSettingFunctionId.GET_DEPT_MONITORING_SETTINGS_VALUE;
    public static final int GET_DEPT_NURSING_RECORD_SETTINGS =
        SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS_VALUE;
    public static final int GET_DEPT_NURSING_SCORE_SETTINGS =
        SystemSettingFunctionId.GET_DEPT_NURSING_SCORE_SETTINGS_VALUE;
    public static final int GET_DEPT_APP_SETTINGS =
        SystemSettingFunctionId.GET_DEPT_APP_SETTINGS_VALUE;
    public static final int GET_APP_LOGO = SystemSettingFunctionId.GET_APP_LOGO_VALUE;
    public static final int GET_ARCHIVE_CONFIG = SystemSettingFunctionId.GET_ARCHIVE_CONFIG_VALUE;

    public SettingsStore(
        DeptSystemSettingsRepository deptSettingsRepo,
        SystemSettingsRepository systemSettingsRepo,
        ConfigurableApplicationContext context,
        ProtoConfig protoConfig
    ) {
        this.deptSettingsRepo = deptSettingsRepo;
        this.systemSettingsRepo = systemSettingsRepo;
        this.context = context;
        this.protoConfig = protoConfig;
    }

    /** 仅检查数据库记录是否存在，不应用默认回退，也不解码已有配置。 */
    public boolean hasDeptSettings(String deptId, int functionId) {
        return deptSettingsRepo.existsById(new DeptSystemSettingsId(deptId, functionId));
    }

    public DisplayFieldSettingsPB getPatientsInIcuSettingsPB(String deptId) {
        return getPatientSettingsPB(deptId, GET_PATIENTS_IN_ICU);
    }

    public void savePatientsInIcuSettingsPB(
        String deptId, DisplayFieldSettingsPB settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_PATIENTS_IN_ICU, settings, modifiedBy,
            ProtoUtils::encodeDisplayFieldSettings);
    }

    public DisplayFieldSettingsPB getPatientsPendingAdmissionSettingsPB(String deptId) {
        return getPatientSettingsPB(deptId, GET_PATIENTS_PENDING_ADMISSION);
    }

    public void savePatientsPendingAdmissionSettingsPB(
        String deptId, DisplayFieldSettingsPB settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_PATIENTS_PENDING_ADMISSION, settings, modifiedBy,
            ProtoUtils::encodeDisplayFieldSettings);
    }

    public DisplayFieldSettingsPB getPatientsPendingDischargedSettingsPB(String deptId) {
        return getPatientSettingsPB(deptId, GET_PATIENTS_PENDING_DISCHARGED);
    }

    public void savePatientsPendingDischargedSettingsPB(
        String deptId, DisplayFieldSettingsPB settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_PATIENTS_PENDING_DISCHARGED, settings, modifiedBy,
            ProtoUtils::encodeDisplayFieldSettings);
    }

    public DisplayFieldSettingsPB getPatientsDischargedSettingsPB(String deptId) {
        return getPatientSettingsPB(deptId, GET_PATIENTS_DISCHARGED);
    }

    public void savePatientsDischargedSettingsPB(
        String deptId, DisplayFieldSettingsPB settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_PATIENTS_DISCHARGED, settings, modifiedBy,
            ProtoUtils::encodeDisplayFieldSettings);
    }

    public MenuConfigPB getMenuConfigPB(String deptId) {
        return getDeptSettings(deptId, GET_DEPT_MENU_SETTINGS, ProtoUtils::decodeMenuConfig);
    }

    public void saveMenuConfigPB(String deptId, MenuConfigPB config, String modifiedBy) {
        saveDeptSettings(deptId, GET_DEPT_MENU_SETTINGS, config, modifiedBy,
            ProtoUtils::encodeMenuConfig);
    }

    public MedOrderGroupSettingsPB getMedOrderGroupSettingsPB(String deptId) {
        return getDeptSettings(deptId, GET_MEDICAL_ORDER_SETTINGS,
            ProtoUtils::decodeMedOrderGroupSettings);
    }

    public void saveMedOrderGroupSettingsPB(
        String deptId, MedOrderGroupSettingsPB settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_MEDICAL_ORDER_SETTINGS, settings, modifiedBy,
            ProtoUtils::encodeMedOrderGroupSettings);
    }

    public DeptMonitoringSettingsPB getDeptMonitoringSettingsPB(String deptId) {
        return getDeptSettings(deptId, GET_DEPT_MONITORING_SETTINGS,
            ProtoUtils::decodeDeptMonitoringSettings);
    }

    public void saveDeptMonitoringSettingsPB(
        String deptId, DeptMonitoringSettingsPB settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_DEPT_MONITORING_SETTINGS, settings, modifiedBy,
            ProtoUtils::encodeDeptMonitoringSettings);
    }

    public NursingRecordSettingsPB getNursingRecordSettingsPB(String deptId) {
        return getDeptSettings(deptId, GET_DEPT_NURSING_RECORD_SETTINGS,
            ProtoUtils::decodeNursingRecordSettings);
    }

    public void saveNursingRecordSettingsPB(
        String deptId, NursingRecordSettingsPB settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_DEPT_NURSING_RECORD_SETTINGS, settings, modifiedBy,
            ProtoUtils::encodeNursingRecordSettings);
    }

    public ScoreSettingsPB getScoreSettingsPB(String deptId) {
        return getDeptSettings(deptId, GET_DEPT_NURSING_SCORE_SETTINGS,
            ProtoUtils::decodeScoreSettings);
    }

    public void saveScoreSettingsPB(String deptId, ScoreSettingsPB settings, String modifiedBy) {
        saveDeptSettings(deptId, GET_DEPT_NURSING_SCORE_SETTINGS, settings, modifiedBy,
            ProtoUtils::encodeScoreSettings);
    }

    public AppGeneralSettingsPB getAppGeneralSettingsPB(String deptId) {
        return getDeptSettings(deptId, GET_DEPT_APP_SETTINGS, ProtoUtils::decodeAppGeneralSettings);
    }

    public void saveAppGeneralSettingsPB(
        String deptId, AppGeneralSettingsPB settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_DEPT_APP_SETTINGS, settings, modifiedBy,
            ProtoUtils::encodeAppGeneralSettings);
    }

    public DeptArchiveSettings getArchiveSettings(String deptId) {
        return getDeptSettings(deptId, GET_ARCHIVE_CONFIG, ProtoUtils::decodeDeptArchiveSettings);
    }

    public void saveArchiveSettings(
        String deptId, DeptArchiveSettings settings, String modifiedBy
    ) {
        saveDeptSettings(deptId, GET_ARCHIVE_CONFIG, settings, modifiedBy,
            ProtoUtils::encodeDeptArchiveSettings);
    }

    public String getLogoB64() {
        SystemSettings entity = systemSettingsRepo.findById(GET_APP_LOGO).orElse(null);
        return entity == null ? null : entity.getSettingsPb();
    }

    public void saveLogoB64(String logoB64, String modifiedBy) {
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        SystemSettings entity = systemSettingsRepo.findById(GET_APP_LOGO).orElse(null);
        if (entity == null) entity = SystemSettings.builder().functionId(GET_APP_LOGO).build();
        entity.setFunctionName(SystemSettingFunctionId.GET_APP_LOGO.name());
        entity.setSettingsPb(logoB64);
        entity.setModifiedAt(nowUtc);
        entity.setModifiedBy(modifiedBy);
        systemSettingsRepo.save(entity);
    }

    private DisplayFieldSettingsPB getPatientSettingsPB(String deptId, int functionId) {
        DisplayFieldSettingsPB settings;
        try {
            settings = getDeptSettings(
                deptId, functionId, ProtoUtils::decodeDisplayFieldSettings);
        } catch (Exception e) {
            log.error("Failed to load patient settings: deptId={}, functionId={}",
                deptId, functionId, e);
            LogUtils.flushAndQuit(context);
            return null;
        }
        if (settings == null) {
            settings = protoConfig.getConfig().getPatient().getDefaultDisplayFieldSettingsList()
                .stream().filter(value -> value.getFunctionIdValue() == functionId)
                .findFirst().orElse(null);
        }
        if (settings == null) {
            log.error("Missing default patient settings: functionId={}", functionId);
            LogUtils.flushAndQuit(context);
            return null;
        }

        List<String> tableNames = new ArrayList<>();
        Map<String, DisplayFieldSettingsPB.Table> tableMap = new HashMap<>();
        for (DisplayFieldSettingsPB.Table table : settings.getTableList()) {
            Map<String, String> columnNames = new HashMap<>();
            for (DisplayFieldSettingsPB.HeaderColumn column : table.getRequiredColList()) {
                columnNames.put(column.getColumnId(), column.getColumnName());
            }
            for (DisplayFieldSettingsPB.HeaderColumn column : table.getOptionalColList()) {
                columnNames.put(column.getColumnId(), column.getColumnName());
            }

            // 列名是派生字段，每次按显示列顺序重建，避免读后保存导致重复追加。
            DisplayFieldSettingsPB.Table.Builder tableBuilder = table.toBuilder()
                .clearColNameToDisplay();
            Set<String> displayedColumnIds = new HashSet<>(table.getColIdToDisplayList());
            for (String columnId : table.getColIdToDisplayList()) {
                String columnName = columnNames.get(columnId);
                if (columnName == null) {
                    log.error("Column ID not found in patient settings: {}", columnId);
                    LogUtils.flushAndQuit(context);
                    return null;
                }
                tableBuilder.addColNameToDisplay(columnName);
            }
            for (DisplayFieldSettingsPB.HeaderColumn column : table.getRequiredColList()) {
                if (!displayedColumnIds.contains(column.getColumnId())) {
                    log.error("Required patient column is not displayed: {}", column.getColumnId());
                    LogUtils.flushAndQuit(context);
                    return null;
                }
            }
            tableNames.add(table.getTableName());
            tableMap.put(table.getTableName(), tableBuilder.build());
        }

        DisplayFieldSettingsPB.Builder builder = settings.toBuilder().clearTable();
        for (String tableName : tableNames) builder.addTable(tableMap.get(tableName));
        return builder.build();
    }

    private <T> T getDeptSettings(String deptId, int functionId, Function<String, T> decoder) {
        DeptSystemSettingsId id = new DeptSystemSettingsId(deptId, functionId);
        DeptSystemSettings entity = deptSettingsRepo.findById(id).orElse(null);
        if (entity == null) return null;
        T settings = decoder.apply(entity.getSettingsPb());
        if (settings == null) throw new DecodeException(deptId, functionId);
        return settings;
    }

    private <T> void saveDeptSettings(
        String deptId, int functionId, T settings, String modifiedBy, Function<T, String> encoder
    ) {
        DeptSystemSettingsId id = new DeptSystemSettingsId(deptId, functionId);
        DeptSystemSettings entity = deptSettingsRepo.findById(id).orElse(null);
        String settingsPb = encoder.apply(settings);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (entity == null) entity = DeptSystemSettings.builder().id(id).build();
        entity.setSettingsPb(settingsPb);
        entity.setModifiedAt(nowUtc);
        entity.setModifiedBy(modifiedBy);
        deptSettingsRepo.save(entity);
    }

    /** 已有设置解码失败，调用方据此保留原有的错误或默认值策略。 */
    public static final class DecodeException extends RuntimeException {
        private DecodeException(String deptId, int functionId) {
            super("Invalid settings PB: deptId=" + deptId + ", functionId=" + functionId);
        }
    }

    private final DeptSystemSettingsRepository deptSettingsRepo;
    private final SystemSettingsRepository systemSettingsRepo;
    private final ConfigurableApplicationContext context;
    private final ProtoConfig protoConfig;
}
