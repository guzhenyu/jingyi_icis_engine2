package com.jingyicare.jingyi_icis_engine.service.patients;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;

import java.time.*;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMapper;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMetaReader;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceMapper;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientConfig {
    public static BedConfigPB getBedConfigPB(BedConfig bedConfig, PatientRecord patient) {
        return BedMapper.toBedConfigPB(bedConfig, patient);
    }

    public PatientConfig(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired DeptSystemSettingsRepository systemSettingsRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired BedMetaReader bedMetaReader,
        @Autowired DeviceService deviceService,
        @Autowired PatientDeviceRepository patientDevRepo,
        @Autowired RbacDepartmentRepository rbacDeptRepo
    ) {
        this.context = context;
        this.protoService = protoService;

        this.ZONE_ID = protoService.getConfig().getZoneId();

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
        this.patientRepo = patientRepo;
        this.bedMetaReader = bedMetaReader;
        this.deviceService = deviceService;
        this.patientDevRepo = patientDevRepo;
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

    public BedConfigPB findBedConfigByHisBedNumber(String deptId, String hisBedNumber) {
        BedConfig bedConfig = bedMetaReader.findActiveBedByHisNumber(deptId, hisBedNumber)
            .orElse(null);
        if (bedConfig == null) return null;

        Map<String/*his_bed_number*/, List<PatientRecord>> patientMap = patientRepo
            .findByDeptIdAndAdmissionStatusIn(
                deptId, List.of(ADMISSION_STATUS_PRE_ADMISSION, ADMISSION_STATUS_ADMITTED))
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

    public Pair<StatusCode, Map<Integer/*device_id*/, DeviceBindingPB>> getBindedDevices() {
        List<PatientDevice> patientDevList = patientDevRepo.findAllByIsDeletedFalseAndUnbindingTimeIsNull();
        Map<Long, PatientRecord> patientNameMap = getPatientMap(patientDevList);
        Pair<StatusCode, Map<String/*deptId + hisBed*/, String/*displayBed*/>> bedNumberResult =
            getBedNumberMap(patientNameMap);
        if (bedNumberResult.getFirst() != StatusCode.OK) {
            return new Pair<>(bedNumberResult.getFirst(), Collections.emptyMap());
        }
        Map<String/*deptId + hisBed*/, String/*displayBed*/> bedNumberMap = bedNumberResult.getSecond();

        Map<Integer, DeviceBindingPB> bindingMap = patientDevList
            .stream()
            .map(pd -> {
                PatientRecord patient = patientNameMap.get(pd.getPatientId());
                String patientName = patient == null ? "" : patient.getIcuName();
                String displayBed = patient == null ? "" : bedNumberMap.getOrDefault(
                    getBedNumberKey(patient.getDeptId(), patient.getHisBedNumber()), patient.getHisBedNumber());

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
        return new Pair<>(StatusCode.OK, bindingMap);
    }

    public Pair<StatusCode, Pair<List<DeviceBindingPB>, Boolean>> getDeviceBindingHistory(
        Integer deviceId, LocalDateTime from, LocalDateTime to
    ) {
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
        Pair<StatusCode, Map<String/*deptId + hisBed*/, String/*displayBed*/>> bedNumberResult =
            getBedNumberMap(patientNameMap);
        if (bedNumberResult.getFirst() != StatusCode.OK) {
            return new Pair<>(bedNumberResult.getFirst(), null);
        }
        Map<String/*deptId + hisBed*/, String/*displayBed*/> bedNumberMap = bedNumberResult.getSecond();

        return new Pair<>(StatusCode.OK, new Pair<List<DeviceBindingPB>, Boolean>(patientDevList
            .stream()
            .sorted(Comparator.comparing(PatientDevice::getBindingTime))
            .map(pd -> {
                PatientRecord patient = patientNameMap.get(pd.getPatientId());
                String patientName = patient == null ? "" : patient.getIcuName();
                String displayBed = patient == null ? "" : bedNumberMap.getOrDefault(
                    getBedNumberKey(patient.getDeptId(), patient.getHisBedNumber()), patient.getHisBedNumber());

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
            isBinding));
    }

    public List<DeviceInfo> getCurrentBindedDevices(Long patientId, Integer devType) {
        List<Integer> devIds = patientDevRepo
            .findByPatientIdAndUnbindingTimeIsNullAndBindingTimeBefore(
                patientId, TimeUtils.getMax())
            .stream()
            .filter(binding -> !Boolean.TRUE.equals(binding.getIsDeleted()))
            .map(PatientDevice::getDeviceId)
            .distinct()
            .toList();
        List<DeviceInfo> devices = deviceService.findDevicesByIds(devIds).stream()
            .filter(device -> !Boolean.TRUE.equals(device.getIsDeleted()))
            .filter(device -> matchesDeviceType(device, devType))
            .sorted(DEVICE_ORDER)
            .collect(Collectors.toCollection(ArrayList::new));

        PatientRecord patient = patientRepo.findById(patientId).orElse(null);
        getFixedDevices(patient).stream()
            .filter(device -> matchesDeviceType(device, devType))
            .forEach(devices::add);
        return devices;
    }

    public LocalDateTime getDeviceLatestUnbindedTime(Long patientId, Integer devType) {
        Map<Integer, LocalDateTime> latestByDevice = patientDevRepo
            .findByPatientIdAndUnbindingTimeIsNotNullAndIsDeletedFalse(patientId).stream()
            .collect(Collectors.toMap(PatientDevice::getDeviceId, PatientDevice::getUnbindingTime,
                (first, second) -> first.isAfter(second) ? first : second));
        return deviceService.findDevicesByIds(latestByDevice.keySet()).stream()
            .filter(device -> matchesDeviceType(device, devType))
            .map(device -> latestByDevice.get(device.getId()))
            .max(LocalDateTime::compareTo)
            .orElse(null);
    }

    public List<DeviceInfoWithBindingPB> getPatientDeviceBindingHistory(
        PatientRecord patient, Integer devType, LocalDateTime from, LocalDateTime to
    ) {
        Long patientId = patient.getId();
        if (from == null) from = TimeUtils.getMin();
        if (to == null) to = TimeUtils.getMax();

        List<PatientDevice> bindings = new ArrayList<>(patientDevRepo
            .findByPatientIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
                patientId, from, to));
        List<PatientDevice> currentBindings = patientDevRepo
            .findByPatientIdAndUnbindingTimeIsNullAndBindingTimeBefore(patientId, to);
        Set<Integer> inUseDevIds = currentBindings.stream()
            .map(PatientDevice::getDeviceId).collect(Collectors.toSet());
        bindings.addAll(currentBindings);

        // 固定设备从入科时起计入绑定历史，已加载的设备无需再按 ID 查询。
        Set<Integer> remainingDevIds = bindings.stream()
            .map(PatientDevice::getDeviceId).collect(Collectors.toSet());
        List<DeviceInfo> devices = new ArrayList<>(getFixedDevices(patient));
        for (DeviceInfo device : devices) {
            int id = device.getId();
            remainingDevIds.remove(id);
            inUseDevIds.add(id);
            bindings.add(PatientDevice.builder().id(0L).patientId(patientId)
                .deviceId(id).bindingTime(patient.getAdmissionTime()).build());
        }
        devices.addAll(deviceService.findDevicesByIds(remainingDevIds));

        Map<Integer, List<DeviceBindingPB>> bindingMap = bindings.stream()
            .sorted(Comparator.comparing(PatientDevice::getBindingTime))
            .map(binding -> DeviceBindingPB.newBuilder()
                .setId(binding.getId())
                .setPatientId(patientId)
                .setPatientName(patient.getIcuName())
                .setDeviceId(binding.getDeviceId())
                .setBindingTimeIso8601(TimeUtils.toIso8601String(binding.getBindingTime(), ZONE_ID))
                .setUnbindingTimeIso8601(
                    TimeUtils.toIso8601String(binding.getUnbindingTime(), ZONE_ID))
                .build())
            .collect(Collectors.groupingBy(DeviceBindingPB::getDeviceId));

        return devices.stream()
            .filter(device -> matchesDeviceType(device, devType))
            .sorted(DEVICE_ORDER)
            .map(device -> DeviceInfoWithBindingPB.newBuilder()
                .setDevice(DeviceMapper.toDeviceInfoPB(device))
                .setIsBinding(inUseDevIds.contains(device.getId()))
                .addAllBinding(bindingMap.get(device.getId()))
                .build())
            .toList();
    }

    private static boolean matchesDeviceType(DeviceInfo device, Integer type) {
        return type == null || type <= 0 || type.equals(device.getDeviceType());
    }

    public Map<String, BedConfig> getBedConfigMap(String deptId) {
        return bedMetaReader.findActiveBedsByDepartment(deptId)
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

    private Map<Long, PatientRecord> getPatientMap(List<PatientDevice> patientDevList) {
        return patientRepo
            .findByIdIn(patientDevList.stream().map(PatientDevice::getPatientId).toList())
            .stream()
            .collect(Collectors.toMap(PatientRecord::getId, Function.identity()));
    }

    private Pair<StatusCode, Map<String/*deptId + hisBed*/, String/*displayBed*/>> getBedNumberMap(
        Map<Long, PatientRecord> patientMap
    ) {
        Map<String, PatientRecord> activePatientByBed = new HashMap<>();
        for (PatientRecord patient : patientMap.values()) {
            if (StrUtils.isBlank(patient.getDeptId()) || StrUtils.isBlank(patient.getHisBedNumber())) continue;
            if (!Objects.equals(patient.getAdmissionStatus(), ADMISSION_STATUS_PRE_ADMISSION) &&
                !Objects.equals(patient.getAdmissionStatus(), ADMISSION_STATUS_ADMITTED)
            ) continue;

            String bedKey = getBedNumberKey(patient.getDeptId(), patient.getHisBedNumber());
            PatientRecord existingPatient = activePatientByBed.putIfAbsent(bedKey, patient);
            if (existingPatient == null ||
                Objects.equals(existingPatient.getAdmissionStatus(), patient.getAdmissionStatus())
            ) continue;

            PatientRecord inIcuPatient = Objects.equals(
                patient.getAdmissionStatus(), ADMISSION_STATUS_ADMITTED)
                ? patient : existingPatient;
            PatientRecord pendingAdmissionPatient = Objects.equals(
                patient.getAdmissionStatus(), ADMISSION_STATUS_PRE_ADMISSION)
                ? patient : existingPatient;
            log.error(
                "Bed occupancy conflict: in-ICU patient and pending-admission patient share the same bed; " +
                "deptId={}, hisBedNumber={}, inIcuPatientId={}, pendingAdmissionPatientId={}",
                patient.getDeptId(), patient.getHisBedNumber(), inIcuPatient.getId(),
                pendingAdmissionPatient.getId()
            );
            return new Pair<>(
                StatusCode.IN_ICU_AND_PENDING_ADMISSION_PATIENTS_SHARE_BED,
                Collections.emptyMap()
            );
        }

        if (patientMap.isEmpty()) return new Pair<>(StatusCode.OK, Collections.emptyMap());

        List<String> hisBedList = patientMap.values().stream()
            .map(PatientRecord::getHisBedNumber)
            .filter(bedNumber -> !StrUtils.isBlank(bedNumber))
            .distinct()
            .toList();
        Set<String> patientBedKeys = patientMap.values().stream()
            .filter(patient -> !StrUtils.isBlank(patient.getDeptId()) &&
                !StrUtils.isBlank(patient.getHisBedNumber()))
            .map(patient -> getBedNumberKey(patient.getDeptId(), patient.getHisBedNumber()))
            .collect(Collectors.toSet());
        Map<String, String> bedNumberMap = new HashMap<>();
        for (BedConfig bedConfig : bedMetaReader.findActiveBedsByHisNumbers(hisBedList)) {
            String bedKey = getBedNumberKey(bedConfig.getDepartmentId(), bedConfig.getHisBedNumber());
            if (!patientBedKeys.contains(bedKey)) continue;

            String existingDisplayBedNumber = bedNumberMap.putIfAbsent(
                bedKey, bedConfig.getDisplayBedNumber());
            if (existingDisplayBedNumber != null) {
                log.error(
                    "Duplicate active bed configurations: deptId={}, hisBedNumber={}, " +
                    "displayBedNumbers=[{}, {}]",
                    bedConfig.getDepartmentId(), bedConfig.getHisBedNumber(), existingDisplayBedNumber,
                    bedConfig.getDisplayBedNumber()
                );
                return new Pair<>(StatusCode.INTERNAL_EXCEPTION, Collections.emptyMap());
            }
        }
        return new Pair<>(StatusCode.OK, bedNumberMap);
    }

    private String getBedNumberKey(String deptId, String hisBedNumber) {
        return deptId + "\u0000" + hisBedNumber;
    }

    private List<DeviceInfo> getFixedDevices(PatientRecord patient) {
        if (patient == null) return List.of();
        BedConfig bed = bedMetaReader.findActiveBedByHisNumber(
            patient.getDeptId(), patient.getHisBedNumber()).orElse(null);
        if (bed == null) return List.of();
        return deviceService.findDevicesAtBed(patient.getDeptId(), bed.getDeviceBedNumber());
    }

    private static final Comparator<DeviceInfo> DEVICE_ORDER = Comparator
        .comparing(DeviceInfo::getDeviceType).thenComparing(DeviceInfo::getDeviceName);

    private final String ZONE_ID;
    private final Integer ADMISSION_TYPE_SURGERY_VAL;
    private final Integer DISCHARGE_TYPE_DEAD_VAL;

    private ConfigurableApplicationContext context;
    private ConfigProtoService protoService;
    private WebApiMessage webMsg;
    private Patient patientCfg;

    private DeptSystemSettingsRepository settingsRepo;

    private PatientRecordRepository patientRepo;
    private final BedMetaReader bedMetaReader;
    private final DeviceService deviceService;
    private PatientDeviceRepository patientDevRepo;
    private RbacDepartmentRepository rbacDeptRepo;
}
