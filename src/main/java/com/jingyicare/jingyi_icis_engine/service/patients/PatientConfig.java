package com.jingyicare.jingyi_icis_engine.service.patients;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import java.util.function.Function;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.users.UserConfig;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientConfig {
    public static BedConfigPB getBedConfigPB(BedConfig bedConfig, PatientRecord patient) {
        BedConfigPB.Builder bedConfigBuilder = BedConfigPB.newBuilder()
            .setId(bedConfig.getId())
            .setDepartmentId(bedConfig.getDepartmentId())
            .setHisBedNumber(bedConfig.getHisBedNumber())
            .setDeviceBedNumber(bedConfig.getDeviceBedNumber())
            .setDisplayBedNumber(bedConfig.getDisplayBedNumber())
            .setBedType(bedConfig.getBedType())
            .setNote(bedConfig.getNote() == null ? "" : bedConfig.getNote());
        if (patient != null) {
            bedConfigBuilder
                .setPid(patient.getId())
                .setPatientName(patient.getIcuName())
                .setHisMrn(patient.getHisMrn());
        }
        return bedConfigBuilder.build();
    }

    public PatientConfig(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired DeptSystemSettingsRepository systemSettingsRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired BedConfigRepository bedConfigRepo,
        @Autowired DeviceInfoRepository devRepo,
        @Autowired PatientDeviceRepository patientDevRepo,
        @Autowired RbacDepartmentRepository rbacDeptRepo
    ) {
        this.context = context;
        this.protoService = protoService;

        this.ZONE_ID = protoService.getConfig().getZoneId();

        PatientEnumsV2 enumsV2 = protoService.getConfig().getPatient().getEnumsV2();
        this.PENDING_ADMISSION_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getPendingAdmissionName()))
            .findFirst()
            .get()
            .getId();
        this.IN_ICU_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .get()
            .getId();
        this.PENDING_DISCHARGED_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getPendingDischargedName()))
            .findFirst()
            .get()
            .getId();
        this.DISCHARGED_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getDischargedName()))
            .findFirst()
            .get()
            .getId();
        this.ADMISSION_TYPE_SURGERY_VAL = enumsV2.getAdmissionTypeList().stream()
            .filter(e -> e.getName().equals("手术"))
            .findFirst()
            .get()
            .getId();
        this.DISCHARGE_TYPE_DEAD_VAL = enumsV2.getDischargedTypeList().stream()
            .filter(e -> e.getName().equals("死亡"))
            .findFirst()
            .get()
            .getId();

        this.webMsg = protoService.getConfig().getText().getWebApiMessage();
        this.patientCfg = protoService.getConfig().getPatient();
        this.settingsRepo = systemSettingsRepo;
        this.patientRepo = patientRepo;
        this.bedConfigRepo = bedConfigRepo;
        this.devRepo = devRepo;
        this.patientDevRepo = patientDevRepo;
        this.rbacDeptRepo = rbacDeptRepo;
    }

    public Integer getPendingAdmissionId() {
        return PENDING_ADMISSION_VAL;
    }

    public Integer getInIcuId() {
        return IN_ICU_VAL;
    }

    public Integer getPendingDischargedId() {
        return PENDING_DISCHARGED_VAL;
    }

    public Integer getDischargedId() {
        return DISCHARGED_VAL;
    }

    public Integer getAdmissionTypeSurgeryId() {
        return ADMISSION_TYPE_SURGERY_VAL;
    }

    public Integer getDischargeTypeDeadId() {
        return DISCHARGE_TYPE_DEAD_VAL;
    }

    public static void clearDischargeFields(PatientRecord patient) {
        patient.setDischargedType(null);
        patient.setDischargedDeathTime(null);
        patient.setDischargedHospitalExitTime(null);
        patient.setDischargedDiagnosis(null);
        patient.setDischargedDiagnosisCode(null);
        patient.setDischargedDeptName(null);
        patient.setDischargedDeptId(null);
        patient.setDischargeTime(null);
        patient.setDischargeEditTime(null);
        patient.setDischargingAccountId(null);
    }

    @Transactional
    public void initialize() {
        initializeSystemSettings();
    }

    @Transactional
    public void checkIntegrity() {
        if (!checkSystemSettings()) {
            log.error("Patient config integrity check failed. Exiting...");
            LogUtils.flushAndQuit(context);
        }
    }

    @Transactional
    public void refresh() {
        refreshSystemSettings();
    }

    public String getAdmissionStatusStr(Integer statusId) {
        for (EnumValue enumVal : protoService.getConfig().getPatient().getEnumsV2().getAdmissionStatusList()) {
            if (enumVal.getId() == statusId) {
                return enumVal.getName();
            }
        }
        return "";
    }

    public String getAdmissionTypeStr(Integer typeId) {
        for (EnumValue enumVal : protoService.getConfig().getPatient().getEnumsV2().getAdmissionTypeList()) {
            if (enumVal.getId() == typeId) {
                return enumVal.getName();
            }
        }
        return "";
    }

    public String getDischargedTypeStr(Integer typeId) {
        for (EnumValue enumVal : protoService.getConfig().getPatient().getEnumsV2().getDischargedTypeList()) {
            if (enumVal.getId() == typeId) {
                return enumVal.getName();
            }
        }
        return "";
    }

    public BedConfigPB findBedConfigByHisBedNumber(String deptId, String hisBedNumber) {
        BedConfig bedConfig = bedConfigRepo
            .findByDepartmentIdAndHisBedNumberAndIsDeletedFalse(deptId, hisBedNumber)
            .orElse(null);
        if (bedConfig == null) return null;

        Map<String/*his_bed_number*/, List<PatientRecord>> patientMap = patientRepo
            .findByDeptIdAndAdmissionStatusIn(deptId, List.of(PENDING_ADMISSION_VAL, IN_ICU_VAL))
            .stream()
            .collect(Collectors.groupingBy(PatientRecord::getHisBedNumber));
        List<PatientRecord> patients = patientMap.get(hisBedNumber);
        if (patients == null || patients.size() == 0) return getBedConfigPB(bedConfig, null);
        if (patients.size() > 1) {
            log.error("Multiple patients found for hisBedNumber: " + hisBedNumber +
                ", using the first one.");
        }
        PatientRecord patient = patients.get(0);

        return getBedConfigPB(bedConfig, patient);
    }

    public List<DeviceInfoPB> getDeviceInfo(
        String deptId, Integer devId, String devBedNum, Integer devType, String devName
    ) {
        return devRepo
            .findAll(
                DeviceInfoSpecification.hasDepartmentId(deptId)
                    .and(DeviceInfoSpecification.hasDeviceId(devId))
                    .and(DeviceInfoSpecification.hasDeviceBedNumer(devBedNum))
                    .and(DeviceInfoSpecification.hasDeviceType(devType))
                    .and(DeviceInfoSpecification.hasDeviceName(devName))
                    .and(DeviceInfoSpecification.isDeleted(false))
            )
            .stream()
            .sorted(Comparator.comparing(DeviceInfo::getDeviceType).thenComparing(DeviceInfo::getDeviceName))
            .map(dev -> toDeviceInfoPB(dev))
            .toList();
    }

    public Map<Integer/*device_id*/, DeviceBindingPB> getBindedDevices() {
        List<PatientDevice> patientDevList = patientDevRepo.findAllByIsDeletedFalseAndUnbindingTimeIsNull();
        Map<Long, PatientRecord> patientNameMap = getPatientMap(patientDevList);
        Map<String/*hisBed*/, String/*displayBed*/> bedNumberMap = getBedNumberMap(patientNameMap);

        return patientDevList
            .stream()
            .map(pd -> {
                PatientRecord patient = patientNameMap.get(pd.getPatientId());
                String patientName = patient == null ? "" : patient.getIcuName();
                String displayBed = patient == null ? "" : bedNumberMap.getOrDefault(patient.getHisBedNumber(), patient.getHisBedNumber());

                return DeviceBindingPB.newBuilder()
                    .setId(pd.getId())
                    .setPatientId(pd.getPatientId())
                    .setPatientName(patientName)
                    .setDisplayBedNumber(displayBed)
                    .setDeviceId(pd.getDeviceId())
                    .setBindingTimeIso8601(TimeUtils.toIso8601String(pd.getBindingTime(), ZONE_ID))
                    .setUnbindingTimeIso8601(pd.getUnbindingTime() == null ?
                        "" : TimeUtils.toIso8601String(pd.getUnbindingTime(), ZONE_ID)
                    )
                    .build();
            })
            .collect(Collectors.toMap(DeviceBindingPB::getDeviceId, Function.identity()));
    }

    public Pair<List<DeviceBindingPB>, Boolean> getDeviceBindingHistory(Integer deviceId, LocalDateTime from, LocalDateTime to) {
        if (from == null) from = TimeUtils.getLocalTime(1900, 1, 1);
        if (to == null) to = TimeUtils.getLocalTime(9999, 1, 1);

        List<PatientDevice> patientDevList = new ArrayList<>();
        patientDevList.addAll(patientDevRepo
            .findByDeviceIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
                deviceId, from, to
            ));

        List<PatientDevice> inUseDeviceList = patientDevRepo
            .findByDeviceIdAndUnbindingTimeIsNullAndBindingTimeBefore(
                deviceId, to
            );
        Boolean isBinding = inUseDeviceList.size() > 0;
        patientDevList.addAll(inUseDeviceList);

        Map<Long, PatientRecord> patientNameMap = getPatientMap(patientDevList);
        Map<String/*hisBed*/, String/*displayBed*/> bedNumberMap = getBedNumberMap(patientNameMap);

        return new Pair<List<DeviceBindingPB>, Boolean>(patientDevList
            .stream()
            .sorted(Comparator.comparing(PatientDevice::getBindingTime))
            .map(pd -> {
                PatientRecord patient = patientNameMap.get(pd.getPatientId());
                String patientName = patient == null ? "" : patient.getIcuName();
                String displayBed = patient == null ? "" : bedNumberMap.getOrDefault(patient.getHisBedNumber(), patient.getHisBedNumber());

                return DeviceBindingPB.newBuilder()
                    .setId(pd.getId())
                    .setPatientId(pd.getPatientId())
                    .setPatientName(patientName)
                    .setDisplayBedNumber(displayBed)
                    .setDeviceId(pd.getDeviceId())
                    .setBindingTimeIso8601(TimeUtils.toIso8601String(pd.getBindingTime(), ZONE_ID))
                    .setUnbindingTimeIso8601(pd.getUnbindingTime() == null ?
                        "" : TimeUtils.toIso8601String(pd.getUnbindingTime(), ZONE_ID)
                    )
                    .build();
            })
            .toList(),
            isBinding);
    }

    public List<DeviceInfo> getCurrentBindedDevices(Long patientId, Integer devType) {
        LocalDateTime from = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime to = TimeUtils.getLocalTime(9999, 1, 1);

        // 获取病人绑定设备信息
        List<PatientDevice> patientDevList = patientDevRepo
            .findByPatientIdAndUnbindingTimeIsNullAndBindingTimeBefore(
                patientId, TimeUtils.getLocalTime(9999, 1, 1)
            );

        List<Integer> devIds = patientDevList.stream().map(PatientDevice::getDeviceId).distinct().toList();
        List<DeviceInfo> deviceList = new ArrayList<>(devRepo
            .findAll(DeviceInfoSpecification.hasDeviceType(devType)
                .and(DeviceInfoSpecification.hasDeviceIdIn(devIds))
            ).stream()
            .sorted(Comparator.comparing(DeviceInfo::getDeviceType).thenComparing(DeviceInfo::getDeviceName))
            .toList());

        // 获取固定设备
        PatientRecord patient = patientRepo.findById(patientId).orElse(null);
        List<DeviceInfo> fixedDevices = getFixedDevices(patient).stream()
            .filter(dev -> (devType == null || devType == 0 || dev.getDeviceType().equals(devType)))
            .collect(Collectors.toList());
        deviceList.addAll(fixedDevices);
        return deviceList;
    }

    public LocalDateTime getDeviceLatestUnbindedTime(Long patientId, Integer devType) {
        // 获取病人绑定设备信息
        List<PatientDevice> patientDevList = patientDevRepo.findByPatientIdAndUnbindingTimeIsNotNullAndIsDeletedFalse(patientId);
        Map<Integer, List<PatientDevice>> devIdToUnbindedMap = patientDevList
            .stream()
            .collect(Collectors.groupingBy(PatientDevice::getDeviceId));

        List<Integer> devIds = patientDevList.stream().map(PatientDevice::getDeviceId).distinct().toList();
        List<Integer> devIdsWithType = devRepo
            .findAll(DeviceInfoSpecification.hasDeviceType(devType)
                .and(DeviceInfoSpecification.hasDeviceIdIn(devIds))
            ).stream()
            .map(DeviceInfo::getId)
            .distinct()
            .toList();

        LocalDateTime latestUnbindedTime = null;
        for (Integer devId : devIdsWithType) {
            List<PatientDevice> unbindedList = devIdToUnbindedMap.get(devId);
            if (unbindedList == null || unbindedList.size() == 0) continue;
            LocalDateTime unbindedTime = unbindedList.stream()
                .map(PatientDevice::getUnbindingTime)
                .max(LocalDateTime::compareTo)
                .orElse(null);
            if (unbindedTime != null && (latestUnbindedTime == null || latestUnbindedTime.isBefore(unbindedTime))) {
                latestUnbindedTime = unbindedTime;
            }
        }
        return latestUnbindedTime;
    }

    public List<DeviceInfoWithBindingPB> getPatientDeviceBindingHistory(
        Long patientId, String patientName, Integer devType, LocalDateTime from, LocalDateTime to
    ) {
        PatientRecord patient = patientRepo.findById(patientId).orElse(null);
        if (patient == null) return Collections.emptyList();

        if (from == null) from = TimeUtils.getMin();
        if (to == null) to = TimeUtils.getMax();

        // 获取病人绑定设备信息
        List<PatientDevice> patientDevList = new ArrayList<>();
        patientDevList.addAll(patientDevRepo
            .findByPatientIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
                patientId, from, to
            ));

        List<PatientDevice> inUseDeviceList = patientDevRepo
            .findByPatientIdAndUnbindingTimeIsNullAndBindingTimeBefore(
                patientId, to
            );
        Set<Integer> inUseDevIds = new HashSet<>(inUseDeviceList.stream().map(PatientDevice::getDeviceId).collect(Collectors.toSet()));
        patientDevList.addAll(inUseDeviceList);

        // 获取固定设备绑定信息
        List<DeviceInfo> fixedDevices = getFixedDevices(patient);
        List<PatientDevice> fixedPatientDevices = new ArrayList<>();
        for (DeviceInfo dev : fixedDevices) {
            inUseDevIds.add(dev.getId());
            fixedPatientDevices.add(PatientDevice.builder().id(0L).patientId(patientId)
                .deviceId(dev.getId()).bindingTime(patient.getAdmissionTime())
                .unbindingTime(null).isDeleted(false).build()
            );
        }
        patientDevList.addAll(fixedPatientDevices);

        // 组装绑定信息
        List<DeviceBindingPB> devBindingList = patientDevList.stream()
            .sorted(Comparator.comparing(PatientDevice::getBindingTime))
            .map(pd -> DeviceBindingPB.newBuilder()
                .setId(pd.getId())
                .setPatientId(pd.getPatientId())
                .setPatientName(patientName)
                .setDeviceId(pd.getDeviceId())
                .setBindingTimeIso8601(TimeUtils.toIso8601String(pd.getBindingTime(), ZONE_ID))
                .setUnbindingTimeIso8601(TimeUtils.toIso8601String(pd.getUnbindingTime(), ZONE_ID))
                .build()
            )
            .toList();
        Map<Integer, List<DeviceBindingPB>> devBindingMap = devBindingList.stream()
            .collect(Collectors.groupingBy(DeviceBindingPB::getDeviceId));

        // 获取相关设备信息
        List<Integer> devIds = patientDevList.stream().map(PatientDevice::getDeviceId).distinct().toList();
        List<DeviceInfo> devList = devRepo
            .findAll(DeviceInfoSpecification.hasDeviceType(devType)
                .and(DeviceInfoSpecification.hasDeviceIdIn(devIds))
            ).stream()
            .sorted(Comparator.comparing(DeviceInfo::getDeviceType).thenComparing(DeviceInfo::getDeviceName))
            .toList();

        return devList.stream().map(dev -> {
            List<DeviceBindingPB> bindingList = devBindingMap.getOrDefault(dev.getId(), new ArrayList<>());
            return DeviceInfoWithBindingPB.newBuilder()
                .setDevice(toDeviceInfoPB(dev))
                .setIsBinding(inUseDevIds.contains(dev.getId()))
                .addAllBinding(bindingList)
                .build();
        }).toList();
    }

    public DeviceInfoPB toDeviceInfoPB(DeviceInfo dev) {
        return DeviceInfoPB.newBuilder()
            .setId(dev.getId())
            .setDepartmentId(dev.getDepartmentId())
            .setDeviceSn(dev.getDeviceSn())
            .setDeviceBedNumber(dev.getDeviceBedNumber() == null ? "" : dev.getDeviceBedNumber())
            .setDeviceType(dev.getDeviceType())
            .setDeviceName(dev.getDeviceName())
            .setDeviceIp(dev.getDeviceIp() == null ? "" : dev.getDeviceIp())
            .setDevicePort(dev.getDevicePort() == null ? "" : dev.getDevicePort())
            .setDeviceDriverCode(dev.getDeviceDriverCode() == null ? "" : dev.getDeviceDriverCode())
            .setNetworkProtocol(dev.getNetworkProtocol() == null ? 0 : dev.getNetworkProtocol())
            .setSerialProtocol(dev.getSerialProtocol() == null ? 0 : dev.getSerialProtocol())
            .setModel(dev.getModel() == null ? "" : dev.getModel())
            .setManufacturer(dev.getManufacturer() == null ? "" : dev.getManufacturer())
            .build();
    }

    public DeviceInfo toDeviceInfo(DeviceInfoPB devPB) {
        return DeviceInfo.builder()
            .departmentId(devPB.getDepartmentId())
            .deviceSn(devPB.getDeviceSn())
            .deviceBedNumber(devPB.getDeviceBedNumber().isEmpty() ? null : devPB.getDeviceBedNumber())
            .deviceType(devPB.getDeviceType())
            .deviceName(devPB.getDeviceName())
            .deviceIp(devPB.getDeviceIp().isEmpty() ? null : devPB.getDeviceIp())
            .devicePort(devPB.getDevicePort().isEmpty() ? null : devPB.getDevicePort())
            .deviceDriverCode(devPB.getDeviceDriverCode().isEmpty() ? null : devPB.getDeviceDriverCode())
            .networkProtocol(devPB.getNetworkProtocol() == 0 ? null : devPB.getNetworkProtocol())
            .serialProtocol(devPB.getSerialProtocol() == 0 ? null : devPB.getSerialProtocol())
            .model(devPB.getModel().isEmpty() ? null : devPB.getModel())
            .manufacturer(devPB.getManufacturer().isEmpty() ? null : devPB.getManufacturer())
            .isDeleted(false)
            .build();
    }

    public Map<String, BedConfig> getBedConfigMap(String deptId) {
        return bedConfigRepo
            .findByDepartmentIdAndIsDeletedFalse(deptId)
            .stream()
            .collect(Collectors.toMap(BedConfig::getHisBedNumber, Function.identity()));
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

    private Map<Long, PatientRecord> getPatientMap(List<PatientDevice> patientDevList) {
        return patientRepo
            .findByIdIn(patientDevList.stream().map(PatientDevice::getPatientId).toList())
            .stream()
            .collect(Collectors.toMap(PatientRecord::getId, Function.identity()));
    }

    private Map<String/*hisBed*/, String/*displayBed*/> getBedNumberMap(Map<Long, PatientRecord> patientMap) {
        List<String> hisBedList = patientMap.values().stream().map(PatientRecord::getHisBedNumber).toList();
        return bedConfigRepo
            .findByHisBedNumberInAndIsDeletedFalse(hisBedList)
            .stream()
            .collect(Collectors.toMap(BedConfig::getHisBedNumber, BedConfig::getDisplayBedNumber));
    }

    private List<DeviceInfo> getFixedDevices(PatientRecord patient) {
        // 查找患者信息
        List<DeviceInfo> devices = new ArrayList<>();
        if (patient == null) return devices;

        // 查找患者设备床号
        BedConfig bedConfig = bedConfigRepo.findByDepartmentIdAndHisBedNumberAndIsDeletedFalse(
            patient.getDeptId(), patient.getHisBedNumber()
        ).orElse(null);
        if (bedConfig == null) return devices;
        String deviceBedNumber = bedConfig.getDeviceBedNumber();

        // 查找设备信息
        List<DeviceInfo> devList = devRepo.findByDepartmentIdAndDeviceBedNumberAndIsDeletedFalse(
            patient.getDeptId(), deviceBedNumber
        );
        devices.addAll(devList);
        return devices;
    }

    private final String ZONE_ID;
    private final Integer PENDING_ADMISSION_VAL;
    private final Integer IN_ICU_VAL;
    private final Integer PENDING_DISCHARGED_VAL;
    private final Integer DISCHARGED_VAL;
    private final Integer ADMISSION_TYPE_SURGERY_VAL;
    private final Integer DISCHARGE_TYPE_DEAD_VAL;

    private ConfigurableApplicationContext context;
    private ConfigProtoService protoService;
    private WebApiMessage webMsg;
    private Patient patientCfg;

    private DeptSystemSettingsRepository settingsRepo;

    private PatientRecordRepository patientRepo;
    private BedConfigRepository bedConfigRepo;
    private DeviceInfoRepository devRepo;
    private PatientDeviceRepository patientDevRepo;
    private RbacDepartmentRepository rbacDeptRepo;
}