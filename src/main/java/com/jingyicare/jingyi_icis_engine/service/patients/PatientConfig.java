package com.jingyicare.jingyi_icis_engine.service.patients;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientConfig {
    public PatientConfig(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired DeptSystemSettingsRepository systemSettingsRepo,
        @Autowired RbacDepartmentRepository rbacDeptRepo
    ) {
        this.context = context;
        this.protoService = protoService;

        PatientEnumsV2 enumsV2 = protoService.getConfig().getPatient().getEnumsV2();
        this.ADMISSION_TYPE_SURGERY_VAL = enumsV2.getAdmissionTypeList().stream()
            .filter(e -> e.getName().equals("手术"))
            .findFirst()
            .get()
            .getId();
        this.DISCHARGE_TYPE_DEAD_VAL = enumsV2.getDischargeTypeList().stream()
            .filter(e -> e.getName().equals("死亡"))
            .findFirst()
            .get()
            .getId();

        this.webMsg = protoService.getConfig().getText().getWebApiMessage();
        this.patientCfg = protoService.getConfig().getPatient();
        this.settingsRepo = systemSettingsRepo;
        this.rbacDeptRepo = rbacDeptRepo;
    }

    public Integer getAdmissionTypeSurgeryId() {
        return ADMISSION_TYPE_SURGERY_VAL;
    }

    public Integer getDischargeTypeDeadId() {
        return DISCHARGE_TYPE_DEAD_VAL;
    }

    public static void clearDischargeFields(PatientRecord patient) {
        patient.setDischargeType(null);
        patient.setDeathTime(null);
        patient.setHisDischargeTime(null);
        patient.setDischargeDiagnosis(null);
        patient.setDischargeDiagnosisCode(null);
        patient.setToDeptName(null);
        patient.setToDeptId(null);
        patient.setDischargeTime(null);
        patient.setDischargeEditTime(null);
        patient.setDischargeAccountId(null);
    }

    @Transactional
    public void initialize() {
        initializeSystemSettings();
    }

    @Transactional
    public void checkIntegrity() {
        if (!checkSystemSettings() || !checkDeviceCascade()) {
            log.error("Patient config integrity check failed. Exiting...");
            LogUtils.flushAndQuit(context);
        }
    }

    @Transactional
    public void refresh() {
        refreshSystemSettings();
    }

    public String getAdmissionTypeStr(Integer typeId) {
        for (EnumValue enumVal : protoService.getConfig().getPatient().getEnumsV2().getAdmissionTypeList()) {
            if (enumVal.getId() == typeId) {
                return enumVal.getName();
            }
        }
        return "";
    }

    public String getDischargeTypeStr(Integer typeId) {
        for (EnumValue enumVal : protoService.getConfig().getPatient().getEnumsV2().getDischargeTypeList()) {
            if (enumVal.getId() == typeId) {
                return enumVal.getName();
            }
        }
        return "";
    }

    private void initializeSystemSettings() {
        try {
            List<String> deptIds = rbacDeptRepo.findAll()
                .stream()
                .map(RbacDepartment::getDeptId)
                .toList();

            for (String deptId : deptIds) {
                for (DisplayFieldSettingsPB settings : patientCfg.getDefaultDisplayFieldSettingsList()) {
                    Integer functionId = settings.getFunctionId().getNumber();
                    DeptSystemSettingsId id = DeptSystemSettingsId.builder()
                        .deptId(deptId)
                        .functionId(functionId)
                        .build();
                    if (settingsRepo.findById(id).orElse(null) != null) continue;
                    DeptSystemSettings systemSettings = DeptSystemSettings.builder()
                        .id(id)
                        .settingsPb(Base64.getEncoder().encodeToString(settings.toByteArray()))
                        .modifiedAt(TimeUtils.getNowUtc())
                        .modifiedBy("system")
                        .build();
                    settingsRepo.save(systemSettings);
                }
            }
        } catch (Exception e) {
            log.error("Failed to initialize system settings", e);
            LogUtils.flushAndQuit(context);
        }
    }

    private boolean checkSystemSettings() {
        return true;
    }

    private boolean checkDeviceCascade() {
        DeviceCascadePB cascade = protoService.getConfig().getDevice().getDeviceCascade();
        for (DeviceTypeEntryPB entry : cascade.getEntryList()) {
            if (!DeviceEnums.isValidDeviceTypeId(entry.getDeviceTypeId())) {
                log.error("Unknown device_cascade device_type_id {} for '{}'",
                    entry.getDeviceTypeId(), entry.getDeviceTypeName());
                return false;
            }
        }
        return true;
    }

    private void refreshSystemSettings() {
    }

    @Transactional(readOnly = true)
    public DisplayFieldSettingsPB getPatientSettingsPB(String deptId, Integer functionId) {
        DisplayFieldSettingsPB pb = patientCfg.getDefaultDisplayFieldSettingsList()
            .stream()
            .filter(s -> s.getFunctionId().getNumber() == functionId)
            .findFirst()
            .orElse(null);
        try {
            // 获取在科病人信息显示字段设置
            DeptSystemSettingsId id = DeptSystemSettingsId.builder()
                .deptId(deptId)
                .functionId(functionId)
                .build();
            DeptSystemSettings settings = settingsRepo.findById(id).orElse(null);
            if (settings != null) {
                pb = DisplayFieldSettingsPB.parseFrom(Base64.getDecoder().decode(settings.getSettingsPb()));
            }
        } catch (Exception e) {
            log.error("Failed to parse settingsPb(GET_PATIENTS_IN_ICU)", e);
            LogUtils.flushAndQuit(context);
            return null;
        }

        List<String> tableNames = new ArrayList<>();
        Map<String, DisplayFieldSettingsPB.Table> tableMap = new HashMap<>();
        for (DisplayFieldSettingsPB.Table table : pb.getTableList()) {
            // 构建表头列名映射
            Map<String, String> colMap = new HashMap<>();
            for (DisplayFieldSettingsPB.HeaderColumn col : table.getRequiredColList()) {
                colMap.put(col.getColumnId(), col.getColumnName());
            }
            for (DisplayFieldSettingsPB.HeaderColumn col : table.getOptionalColList()) {
                colMap.put(col.getColumnId(), col.getColumnName());
            }

            // 构建表格列名
            DisplayFieldSettingsPB.Table.Builder tableBuilder = table.toBuilder();
            Set<String> colIdToDisplaySet = new HashSet<>();
            for (String colId : table.getColIdToDisplayList()) {
                colIdToDisplaySet.add(colId);
                if (colMap.containsKey(colId)) {
                    tableBuilder.addColNameToDisplay(colMap.get(colId));
                } else {
                    log.error("Column ID not found in colMap: " + colId);
                    LogUtils.flushAndQuit(context);
                }
            }
            for (DisplayFieldSettingsPB.HeaderColumn col : table.getRequiredColList()) {
                if (!colIdToDisplaySet.contains(col.getColumnId())) {
                    log.error("Required column not found in colMap: " + col.getColumnId());
                    LogUtils.flushAndQuit(context);
                }
            }

            // 构建表名列表和表映射
            tableNames.add(table.getTableName());
            tableMap.put(table.getTableName(), tableBuilder.build());
        }

        // 构建新的DisplayFieldSettingsPB
        DisplayFieldSettingsPB.Builder builder = pb.toBuilder();
        builder.clearTable();
        for (String tableName : tableNames) {
            DisplayFieldSettingsPB.Table table = tableMap.get(tableName);
            if (table == null) {
                log.error("Table not found in tableMap: {}", tableName);
                LogUtils.flushAndQuit(context);
            }
            if (table.getColIdToDisplayCount() != table.getColNameToDisplayCount()) {
                log.error("Column ID count not equal to column name count in table: {}", tableName);
                LogUtils.flushAndQuit(context);
            }
            builder.addTable(tableMap.get(tableName));
        }
        return builder.build();
    }

    private final Integer ADMISSION_TYPE_SURGERY_VAL;
    private final Integer DISCHARGE_TYPE_DEAD_VAL;

    private ConfigurableApplicationContext context;
    private ConfigProtoService protoService;
    private WebApiMessage webMsg;
    private PatientConfigPB patientCfg;

    private DeptSystemSettingsRepository settingsRepo;

    private RbacDepartmentRepository rbacDeptRepo;
}
