package com.jingyicare.jingyi_icis_engine.service.patients;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSepticShock.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.certs.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.therapies.SepsisAndSepticShockBundleService;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientService {
    @AllArgsConstructor
    public static class PatientShift {
        public StatusCode statusCode;
        public PatientRecord patient;
        public ShiftSettingsPB shiftSettings;
    }

    public PatientService(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired PatientConfig patientConfig,
        @Autowired PatientSyncService patientSyncService,
        @Autowired PatientReadmissionService patientReadmissionService,
        @Autowired PatientDeviceService patientDeviceService,
        @Autowired UserService userService,
        @Autowired CertificateService certService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired PatientRecordRepository repository,
        @Autowired BedConfigRepository bedConfigRepository,
        @Autowired DiagnosisHistoryRepository diagnosisHistoryRepository,
        @Autowired PatientDeviceRepository patientDeviceRepository,
        @Autowired ReadmissionHistoryRepository readmissionHistoryRepository,
        @Autowired PatientSettingsRepository patientSettingsRepository,
        @Autowired SepsisAndSepticShockBundleService sepsisAndSepticShockBundleService
    ) {
        this.enumsV2 = protoService.getConfig().getPatient().getEnumsV2();
        this.ZONE_ID = protoService.getConfig().getZoneId();
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
        this.GRACE_PERIOD_HOURS_TO_READMIT = protoService.getConfig().getPatient().getGracePeriodHoursToReadmit();

        EnumValue dischargeType = enumsV2.getDischargeTypeList().stream()
            .filter(e -> e.getName().equals("转出"))
            .findFirst()
            .orElse(null);
        if (dischargeType == null) {
            log.error("Failed to find discharge type '转出' in enumsV2");
            LogUtils.flushAndQuit(context);
        }
        this.DISCHARGE_TYPE_TRANSFER_ID = dischargeType.getId();

        dischargeType = enumsV2.getDischargeTypeList().stream()
            .filter(e -> e.getName().equals("死亡"))
            .findFirst()
            .orElse(null);
        if (dischargeType == null) {
            log.error("Failed to find discharge type '死亡' in enumsV2");
            LogUtils.flushAndQuit(context);
        }
        this.DISCHARGE_TYPE_DEATH_ID = dischargeType.getId();

        dischargeType = enumsV2.getDischargeTypeList().stream()
            .filter(e -> e.getName().equals("出院"))
            .findFirst()
            .orElse(null);
        if (dischargeType == null) {
            log.error("Failed to find discharge type '出院' in enumsV2");
            LogUtils.flushAndQuit(context);
        }
        this.DISCHARGE_TYPE_EXIT_HOSPITAL_ID = dischargeType.getId();

        this.protoService = protoService;
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.shiftUtils = shiftUtils;
        this.patientConfig = patientConfig;
        this.patientSyncService = patientSyncService;
        this.patientReadmissionService = patientReadmissionService;
        this.patientDeviceService = patientDeviceService;
        this.userService = userService;
        this.certService = certService;
        this.monitoringConfig = monitoringConfig;
        this.patientRecordRepository = repository;
        this.bedConfigRepository = bedConfigRepository;
        this.diagnosisHistoryRepository = diagnosisHistoryRepository;
        this.patientDeviceRepository = patientDeviceRepository;
        this.readmissionHistoryRepository = readmissionHistoryRepository;
        this.patientSettingsRepository = patientSettingsRepository;
        this.sepsisAndSepticShockBundleService = sepsisAndSepticShockBundleService;
    }

    @Transactional
    public GetInlinePatientsV2Resp getInlinePatientsV2(String getInlinePatientsV2ReqJson) {
        final GetInlinePatientsV2Req req;
        try {
            req = ProtoUtils.parseJsonToProto(getInlinePatientsV2ReqJson, GetInlinePatientsV2Req.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetInlinePatientsV2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        String deptId = req.getDeptId();

        GetInlinePatientsV2Resp.Builder builder = GetInlinePatientsV2Resp.newBuilder();
        Map<String, BedConfig> bedConfigMap = patientConfig.getBedConfigMap(deptId);

        // Simplify by calling a common method for each patient status
        addPatientTableForStatus(
            deptId, IN_ICU_VAL, patientConfig.getPatientSettingsPB(deptId, 1), bedConfigMap, builder);
        addPatientTableForStatus(
            deptId, PENDING_ADMISSION_VAL, patientConfig.getPatientSettingsPB(deptId, 2), bedConfigMap, builder);
        addPatientTableForStatus(
            deptId, PENDING_DISCHARGED_VAL, patientConfig.getPatientSettingsPB(deptId, 3), bedConfigMap, builder);

        appendSepticShockAlarms(builder);

        return builder.setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private void appendSepticShockAlarms(GetInlinePatientsV2Resp.Builder builder) {
        try {
            if (!sepsisAndSepticShockBundleService.isSepticShockAlarmEnabled()) return;

            List<Long> pids = builder.getInIcu().getBasicsList().stream()
                .map(PatientBasicsPB::getId)
                .filter(pid -> pid > 0)
                .toList();
            if (pids.isEmpty()) return;

            List<SepsisAndSepticShockCasePB> cases = sepsisAndSepticShockBundleService.buildSepticShockCases(pids);
            builder.addAllAlarm(sepsisAndSepticShockBundleService.buildSepticShockAlarms(cases));
        } catch (Exception e) {
            log.error("Failed to build septic shock alarms for getInlinePatientsV2", e);
        }
    }

    private void addPatientTableForStatus(
        String deptId, Integer admissionStatus, DisplayFieldSettingsPB settings,
        Map<String, BedConfig> bedConfigMap, GetInlinePatientsV2Resp.Builder builder
    ) {
        PatientTablePB.Builder patientTableBuilder = PatientTablePB.newBuilder();

        // 设置表头
        addPatientTableHeader(settings, patientTableBuilder);

        // 在线患者只展示当前有效床位配置中的患者
        List<PatientRecord> patients = patientRecordRepository.findByDeptIdAndAdmissionStatus(deptId, admissionStatus);
        patients = patients.stream()
            .filter(patient -> bedConfigMap.containsKey(patient.getHisBedNumber()))
            .toList();

        // 设置表格行
        addPatientTableRows(patients, settings, patientTableBuilder, bedConfigMap);

        // 设置病人基本信息
        addPatientBasics(patients, patientTableBuilder, bedConfigMap);

        // 根据状态设置响应
        if (IN_ICU_VAL.equals(admissionStatus)) {
            builder.setInIcu(patientTableBuilder.build());
        } else if (PENDING_ADMISSION_VAL.equals(admissionStatus)) {
            // 查找对应的48h内出科记录
            PatientTablePB pendingAdmissionTable = patientTableBuilder.build();
            pendingAdmissionTable = appendReadmissionInfo(pendingAdmissionTable);
            // 填充结果
            builder.setPendingAdmission(pendingAdmissionTable);
        } else if (PENDING_DISCHARGED_VAL.equals(admissionStatus)) {
            builder.setPendingDischarged(patientTableBuilder.build());
        }
    }

    @Transactional
    public GetDischargedPatientsV2Resp getDischargedPatientsV2(String getDischargedPatientsV2ReqJson) {
        final GetDischargedPatientsV2Req req;
        try {
            req = ProtoUtils.parseJsonToProto(getDischargedPatientsV2ReqJson, GetDischargedPatientsV2Req.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetDischargedPatientsV2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        String deptId = req.getDeptId();

        PatientTablePB.Builder patientTableBuilder = PatientTablePB.newBuilder();
        final DisplayFieldSettingsPB settings = patientConfig.getPatientSettingsPB(deptId, 4);

        // 获取床位映射
        Map<String, BedConfig> bedConfigMap = patientConfig.getBedConfigMap(req.getDeptId());

        // 设置表头
        addPatientTableHeader(settings, patientTableBuilder);

        LocalDateTime queryStart = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEnd = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");

        // 查找病人信息，生成表格内容
        List<PatientRecord> patients = patientRecordRepository
            .findAll(
                Specification.where(PatientRecordSpecification.hasDeptId(req.getDeptId()))
                    .and(PatientRecordSpecification.hasAdmissionStatus(DISCHARGED_VAL))
                    .and(PatientRecordSpecification.hasDischargeQueryStart(queryStart))
                    .and(PatientRecordSpecification.hasDischargeQueryEnd(queryEnd))
                    .and(PatientRecordSpecification.hasHisMrn(req.getHisMrn()))
                    .and(PatientRecordSpecification.hasPatientName(req.getPatientName()))
            )
            .stream()
            .sorted(Comparator.comparing(PatientRecord::getDischargeTime).reversed())
            .toList();
        addPatientTableRows(patients, settings, patientTableBuilder, bedConfigMap);

        // 设置病人基本信息
        addPatientBasics(patients, patientTableBuilder, bedConfigMap);

        return GetDischargedPatientsV2Resp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setPatient(patientTableBuilder.build())
            .build();
    }

    private void addPatientTableHeader(
        DisplayFieldSettingsPB settings, PatientTablePB.Builder builder
    ) {
        for (DisplayFieldSettingsPB.Table table : settings.getTableList()) {
            for (int i = 0; i < table.getColIdToDisplayCount(); ++i) {
                builder.addCol(
                    PatientTableHeaderColumnPB.newBuilder()
                        .setColId(table.getColIdToDisplay(i))
                        .setName(table.getColNameToDisplay(i))
                        .build()
                );
            }
        }
    }

    private void addPatientTableRows(
        List<PatientRecord> patients, DisplayFieldSettingsPB settings, PatientTablePB.Builder builder,
        Map<String, BedConfig> bedConfigMap
    ) {
        final Integer rowNum = patients.size();
        List<PatientTableDataRowPB> rows = new ArrayList<>();
        for (int i = 0; i < rowNum; ++i) {
            PatientTableDataRowPB.Builder rowBuilder = PatientTableDataRowPB.newBuilder();
            addPatientTableDataRow(patients.get(i), settings, rowBuilder, bedConfigMap);
            rows.add(rowBuilder.build());
        }

        rows = rows.stream()
            .sorted(bedNumberComparator(rows, this::getBedNumberStr))
            .toList();

        builder.addAllRow(rows);
    }

    private String getBedNumberStr(PatientTableDataRowPB row) {
        for (PatientTableDataCellPB cell : row.getCellList()) {
            if (cell.getColId().equals("bed_number_str")) {
                return cell.getValue();
            }
        }
        return "";
    }

    private Optional<BigDecimal> parseBedNumberStr(String bedNumberStr) {
        if (StrUtils.isBlank(bedNumberStr)) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(bedNumberStr.trim()));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private <T> Comparator<T> bedNumberComparator(List<T> items, java.util.function.Function<T, String> bedNumberGetter) {
        // 如果所有床号都可以转成数字，则按数字排序；否则按字符串排序。
        boolean allBedNumbersNumeric = items.stream()
            .allMatch(item -> parseBedNumberStr(bedNumberGetter.apply(item)).isPresent());
        return allBedNumbersNumeric
            ? Comparator.comparing(item -> parseBedNumberStr(bedNumberGetter.apply(item)).orElseThrow())
            : Comparator.comparing(item -> Optional.ofNullable(bedNumberGetter.apply(item)).orElse(""));
    }

    private void addPatientTableDataRow(
        PatientRecord patient, DisplayFieldSettingsPB settings, PatientTableDataRowPB.Builder rowBuilder,
        Map<String, BedConfig> bedConfigMap
    ) {
        for (DisplayFieldSettingsPB.Table table : settings.getTableList()) {
            final String tableName = table.getTableName();
            if (tableName.equals("patient_records")) {
                for (String colId : table.getColIdToDisplayList()) {
                    String colValue = getPatientRecordValue(patient, colId, bedConfigMap);
                    rowBuilder.addCell(
                        PatientTableDataCellPB.newBuilder()
                            .setColId(colId)
                            .setValue(colValue)
                            .build()
                    );
                }
            }
        }
    }

    private void addPatientBasics(
        List<PatientRecord> patients, PatientTablePB.Builder builder,
        Map<String, BedConfig> bedConfigMap
    ) {
        List<PatientBasicsPB> basicsList = new ArrayList<>();
        for (PatientRecord patient : patients) {
            PatientBasicsPB.Builder basicsBuilder = PatientBasicsPB.newBuilder();
            basicsBuilder.setId(patient.getId());
            basicsBuilder.setIcuName(patient.getIcuName());
            basicsBuilder.setGender(patient.getIcuGender());
            basicsBuilder.setHisMrn(patient.getHisMrn());

            // 床位号显示
            final String hisBedNumber = patient.getHisBedNumber();
            BedConfig bedConfig = bedConfigMap.get(hisBedNumber);
            basicsBuilder.setBedNumberStr(bedConfig == null ? hisBedNumber : bedConfig.getDisplayBedNumber());

            basicsBuilder.setAdmissionStatus(patient.getAdmissionStatus());
            basicsBuilder.setAdmissionTime(
                TimeUtils.toIso8601String(patient.getAdmissionTime(), ZONE_ID));
            basicsBuilder.setDischargeTime(
                TimeUtils.toIso8601String(patient.getDischargeTime(), ZONE_ID));

            basicsBuilder.setDateOfBirthIso8601(
                TimeUtils.toIso8601String(patient.getIcuDateOfBirth(), ZONE_ID));
            basicsBuilder.setAge(
                patient.getIcuDateOfBirth() == null ? -1 : TimeUtils.getAge(patient.getIcuDateOfBirth()));
            basicsList.add(basicsBuilder.build());
        }
        basicsList.sort(bedNumberComparator(basicsList, PatientBasicsPB::getBedNumberStr));
        builder.addAllBasics(basicsList);
    }

    @Transactional
    public GenericResp newPatient(String newPatientReqJson) {
        final NewPatientReq req;
        try {
            NewPatientReq.Builder builder = NewPatientReq.newBuilder();
            JsonFormat.parser().merge(newPatientReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 根据mrn查找是否已经存在该病人
        List<PatientRecord> patients = patientRecordRepository.findByMrnAndAdmissionStatusNotEquals(
            req.getDeptId(), req.getHisMrn(), DISCHARGED_VAL);
        // 如果病人已存在，且状态不是DISCHARGED(已出科)，返回错误码
        if (!patients.isEmpty()) {
            return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.PATIENT_ALREADY_EXISTS))
            .build();
        }

        // 根据显示床号查找his床号
        BedConfig bedConfig = bedConfigRepository.findByDepartmentIdAndDisplayBedNumberAndIsDeletedFalse(
            req.getDeptId(), req.getBedNumberStr()).orElse(null);
        if (bedConfig == null) {
            log.error("BedConfig not found for deptId: {}, displayBedNumber: {}", req.getDeptId(), req.getBedNumberStr());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_NOT_EXISTS))
                .build();
        }

        // 新增病人，返回成功码
        PatientRecord record = new PatientRecord();
        record.setIcuName(req.getIcuName());
        record.setHisMrn(req.getHisMrn());
        record.setHisBedNumber(bedConfig.getHisBedNumber());
        record.setIcuManualEntry(true);
        record.setIcuManualEntryAccountId(accountId);
        record.setIcuGender(req.getIcuGender());
        record.setIcuDateOfBirth(TimeUtils.fromIso8601String(req.getIcuDateOfBirth(), "UTC"));
        record.setAdmissionTime(TimeUtils.fromIso8601String(req.getAdmissionTime(), "UTC"));
        setAdmissionTypeValues(record, req.getAdmissionTypeList());
        record.setFromDeptName(req.getFromDeptName());
        record.setPrimaryCareDoctorId(req.getPrimaryCareDoctorId());
        record.setIsPlannedAdmission(req.getIsPlannedAdmission() > 0);
        record.setUnplannedAdmissionReason(req.getUnplannedAdmissionReason());
        record.setDiagnosis(req.getDiagnosis());
        record.setHisPatientId(req.getHisPid());
        record.setDeptId(req.getDeptId());
        record.setAdmissionStatus(IN_ICU_VAL);
        record.setCreatedAt(LocalDateTime.now());
        patientRecordRepository.save(record);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp admitPatient(String admitPatientReqJson) {
        final AdmitPatientReq req;
        try {
            AdmitPatientReq.Builder builder = AdmitPatientReq.newBuilder();
            JsonFormat.parser().merge(admitPatientReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("Account not found");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 提取关键信息
        LocalDateTime admissionTime = TimeUtils.fromIso8601String(req.getAdmissionTime(), "UTC");

        // 根据his_mrn查找病人
        List<PatientRecord> patients = patientRecordRepository.findByMrnAndAdmissionStatus(
            req.getDeptId(), req.getHisMrn(), PENDING_ADMISSION_VAL);
        if (patients.isEmpty()) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        } else if (patients.size() > 1) {
            log.error("Multiple patients found for his_mrn: {}", req.getHisMrn());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INTERNAL_EXCEPTION))
                .build();
        }
        PatientRecord patient = patients.get(0);

        // 是否合并记录
        Boolean merged = false;
        if (req.getToMerge()) {  // 合并逻辑
            PatientRecord lastDischargedPatient = patientRecordRepository.findById(req.getLastPid()).orElse(null);
            if (lastDischargedPatient != null &&
                lastDischargedPatient.getHisMrn().equals(patient.getHisMrn())
            ) {
                // 重返历史记录
                ReadmissionHistory readmissionHistory = ReadmissionHistory.builder()
                    .patientId(lastDischargedPatient.getId())
                    .readmissionReason(req.getUnplannedAdmissionReason())
                    .icuDischargeTime(lastDischargedPatient.getDischargeTime())
                    .icuDischargeEditTime(lastDischargedPatient.getDischargeEditTime())
                    .icuDischargingAccountId(lastDischargedPatient.getDischargeAccountId())
                    .icuAdmissionTime(admissionTime)
                    .icuAdmissionEditTime(nowUtc)
                    .icuAdmittingAccountId(accountId)
                    .build();
                readmissionHistoryRepository.save(readmissionHistory);

                // 详细记录床位切换历史
                StatusCode statusCode = patientDeviceService.switchBedImpl(
                    lastDischargedPatient, patient.getHisBedNumber(), patient.getAdmissionTime(), true,
                    accountId, accountName
                );
                if (statusCode != StatusCode.OK) {
                    log.error("Failed to switch bed: {}", statusCode);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(statusCode))
                        .build();
                }
                // 合并病人记录
                statusCode = patientReadmissionService.readmitPatient(lastDischargedPatient, patient);
                if (statusCode != StatusCode.OK) {
                    log.error("Failed to readmit patient: {}", statusCode);
                    return GenericResp.newBuilder()
                        .setRt(protoService.getReturnCode(statusCode))
                        .build();
                }
                merged = true;
            }
        }
        final String deptId = patient.getDeptId();
        final String hisBedNumber = patient.getHisBedNumber();

        // 校验当前床位配置数量，患者入科本身不新增床位配置
        if (!certService.checkBedAvailable(deptId)) {
            log.error("No available bed in deptId: {}", deptId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED))
                .build();
        }

        // 检查床位配置是否存在
        BedConfig bedConfig = bedConfigRepository.findByDepartmentIdAndHisBedNumberAndIsDeletedFalse(
            deptId, hisBedNumber).orElse(null);
        if (bedConfig == null) {
            log.error("BedConfig not found for deptId: {}, hisBedNumber: {}", deptId, hisBedNumber);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.BED_CONFIG_NOT_EXISTS))
                .build();
        }

        if (!merged) {
            patient.setDeptId(req.getDeptId());
            patient.setAdmissionTime(admissionTime);
            setAdmissionTypeValues(patient, req.getAdmissionTypeList());
            patient.setFromDeptName(req.getFromDeptName());
            patient.setPrimaryCareDoctorId(req.getPrimaryCareDoctorId());
            patient.setIsPlannedAdmission(req.getIsPlannedAdmission() > 0);
            patient.setUnplannedAdmissionReason(req.getUnplannedAdmissionReason());
            patient.setAdmissionStatus(IN_ICU_VAL);
            patientRecordRepository.save(patient);

            // 初始化观察项(部门+病人)
            monitoringConfig.getMonitoringGroups(
                patient.getId(), patient.getDeptId(),
                monitoringConfig.getBalanceGroupTypeId(),
                new ArrayList<>(), accountId
            );
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp dischargePatient(String dischargePatientReqJson) {
        final DischargePatientReq req;
        try {
            DischargePatientReq.Builder builder = DischargePatientReq.newBuilder();
            JsonFormat.parser().merge(dischargePatientReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}\n", dischargePatientReqJson);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("Account not found");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();

        // 获取病人信息
        PatientRecord patient = patientRecordRepository.findById(req.getId()).orElse(null);
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        } else if (patient.getAdmissionStatus() != PENDING_DISCHARGED_VAL) {
            log.error("Invalid patient admission status: {}, id {}", patient.getAdmissionStatus(), patient.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INTERNAL_EXCEPTION))
                .build();
        }

        final LocalDateTime dischargeTime = TimeUtils.fromIso8601String(req.getDischargeTime(), "UTC");
        if (dischargeTime == null) {
            log.error("Discharge time is null");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT)).build();
        }
        if (dischargeTime.isAfter(nowUtc)) {
            log.error("Discharge time is after now");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DISCHARGE_TIME_IS_AFTER_NOW))
                .build();
        }
        patient.setDischargeTime(dischargeTime);

        patient.setDischargeType(req.getDischargeType());
        if (req.getDischargeType() == DISCHARGE_TYPE_TRANSFER_ID) {
            String deptName = req.getToDeptName();
            if (StrUtils.isBlank(deptName)) {
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DISCHARGE_TYPE_TRANSFER_NEEDS_TARGET_DEPT_NAME))
                    .build();
            }
            patient.setToDeptName(deptName);
        } else if (req.getDischargeType() == DISCHARGE_TYPE_DEATH_ID) {
            LocalDateTime deathTime = TimeUtils.fromIso8601String(req.getDeathTime(), "UTC");
            if (deathTime == null) {
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DISCHARGE_TYPE_DEATH_NEEDS_DEATH_TIME))
                    .build();
            }
            patient.setDeathTime(deathTime);
        } else if (req.getDischargeType() == DISCHARGE_TYPE_EXIT_HOSPITAL_ID) {
            LocalDateTime exitTime = TimeUtils.fromIso8601String(
                req.getHisDischargeTimeIso8601(), "UTC"
            );
            if (exitTime == null) {
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DISCHARGE_TYPE_EXIT_HOSPITAL_NEEDS_EXIT_TIME))
                    .build();
            }
            patient.setHisDischargeTime(exitTime);
        } else {
            log.error("Invalid discharge type: {}", req.getDischargeType());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_DISCHARGE_TYPE))
                .build();
        }

        patient.setAdmissionStatus(DISCHARGED_VAL);
        patientRecordRepository.save(patient);

        // 解绑所有已经绑定设备
        List<PatientDevice> patientDevs = patientDeviceRepository
            .findByPatientIdAndUnbindingTimeNullAndIsDeletedFalse(patient.getId());
        StatusCode statusCode = StatusCode.OK;
        for (PatientDevice patientDev : patientDevs) {
            statusCode = PatientDevUtils.unbindDevice(patientDev, dischargeTime, accountId, accountName, nowUtc);
            if (statusCode != StatusCode.OK) {
                log.error("Failed to unbind device: {}", patientDev.getId());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(statusCode))
                    .build();
            }
        }
        patientDeviceRepository.saveAll(patientDevs);

        // 床位记录表中更新换床时间（终止时间）

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetPatientInfoResp getPatientInfo(Long patientId) {
        PatientRecord patient = patientRecordRepository.findById(patientId).
            orElse(null);
        if (patient == null) {
            ReturnCode rt = protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND);
            return GetPatientInfoResp.newBuilder().setRt(rt).build();
        }

        // todo(guzhenyu): 病人基本信息的来源不限于PatientRecord，还有其他表

        final String bedNumberStr = patient.getHisBedNumber();
        BedConfig bedConfig = patientConfig.getBedConfigMap(patient.getDeptId()).get(bedNumberStr);

        Integer age = patient.getIcuDateOfBirth() == null ? -1 : TimeUtils.getAge(patient.getIcuDateOfBirth());
        String hisAdmissionTimeStr = patient.getHisAdmissionTime() == null ? "" :
            TimeUtils.toIso8601String(patient.getHisAdmissionTime(), ZONE_ID);
        String primaryCareDoctorId = patient.getPrimaryCareDoctorId();
        String primaryCareDoctor = primaryCareDoctorId == null ?
            "" : userService.getNameByAccountId(primaryCareDoctorId);
        if (primaryCareDoctor == null) primaryCareDoctor = "";
        String allergies = patient.getAllergies();
        Integer admissionStatus = patient.getAdmissionStatus();
        String admissionStatusStr = admissionStatus == null ? "未知" :
            ((admissionStatus == IN_ICU_VAL || admissionStatus == PENDING_ADMISSION_VAL) ?
                "在科" :
                ((admissionStatus == PENDING_DISCHARGED_VAL || admissionStatus == DISCHARGED_VAL) ?
                    "已出科" :
                    "未知")
            );

        return GetPatientInfoResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(patient.getId())
            .setIcuName(patient.getIcuName())
            .setIcuGender(patient.getIcuGender())
            .setIcuDateOfBirth(TimeUtils.toIso8601String(patient.getIcuDateOfBirth(), ZONE_ID))
            .setHisMrn(patient.getHisMrn())
            .setBedNumberStr(bedConfig == null ? bedNumberStr : bedConfig.getDisplayBedNumber())
            .setAge(age)
            .setHisAdmissionTime(hisAdmissionTimeStr)
            .setPrimaryCareDoctor(primaryCareDoctor)
            .setAllergies(allergies == null ? "" : allergies)
            .setAdmissionStatus(admissionStatus)
            .setAdmissionStatusStr(admissionStatusStr)
            .setAdmissionTime(TimeUtils.toIso8601String(patient.getAdmissionTime(), ZONE_ID))
            .addAllAdmissionType(getAdmissionTypeValues(patient))
            .setIsPlannedAdmission(patient.getIsPlannedAdmission() ? 1 : 0)
            .setUnplannedAdmissionReason(patient.getUnplannedAdmissionReason())
            .setDiagnosis(patient.getDiagnosis())
            .setDischargeTime(TimeUtils.toIso8601String(patient.getDischargeTime(), ZONE_ID))
            .setDischargeType(patient.getDischargeType())
            .setToDeptName(patient.getToDeptName())
            .setDeathTime(TimeUtils.toIso8601String(patient.getDeathTime(), ZONE_ID))
            .build();
    }

    @Transactional
    public GenericResp updatePatientInfo(String updatePatientInfoReqJson) {
        final UpdatePatientInfoReq req;
        try {
            UpdatePatientInfoReq.Builder builder = UpdatePatientInfoReq.newBuilder();
            JsonFormat.parser().merge(updatePatientInfoReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto, {} ", updatePatientInfoReqJson);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        PatientRecord patient = patientRecordRepository.findById(req.getId()).
            orElse(null);
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        if (patient.getAdmissionStatus() != DISCHARGED_VAL &&
            (!StrUtils.isBlank(req.getDischargeTime()) ||
             req.getDischargeType() > 0 ||
             !StrUtils.isBlank(req.getToDeptName()) ||
             !StrUtils.isBlank(req.getDeathTime()))
        ) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_IS_NOT_DISCHARGED))
                .build();
        }

        patient.setAdmissionTime(TimeUtils.fromIso8601String(req.getAdmissionTime(), "UTC"));
        setAdmissionTypeValues(patient, req.getAdmissionTypeList());
        patient.setIsPlannedAdmission(req.getIsPlannedAdmission() > 0);
        patient.setUnplannedAdmissionReason(req.getUnplannedAdmissionReason());

        if (patient.getAdmissionStatus() != DISCHARGED_VAL) {
            patient.setDischargeTime(null);
            patient.setDischargeType(null);
            patient.setToDeptName("");
            patient.setDeathTime(null);
        } else {
            patient.setDischargeTime(TimeUtils.fromIso8601String(req.getDischargeTime(), "UTC"));
            patient.setDischargeType(req.getDischargeType());
            patient.setToDeptName(req.getToDeptName());
            patient.setDeathTime(TimeUtils.fromIso8601String(req.getDeathTime(), "UTC"));
        }
        patientRecordRepository.save(patient);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetPatientInfoV2Resp getPatientInfoV2(String getPatientInfoV2ReqJson) {
        final GetPatientInfoV2Req req;
        try {
            GetPatientInfoV2Req.Builder builder = GetPatientInfoV2Req.newBuilder();
            JsonFormat.parser().merge(getPatientInfoV2ReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto, {} ", getPatientInfoV2ReqJson);
            return GetPatientInfoV2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        PatientRecord patient = patientRecordRepository.findById(req.getId()).
            orElse(null);
        if (patient == null) {
            ReturnCode rt = protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND);
            return GetPatientInfoV2Resp.newBuilder().setRt(rt).build();
        }

        Map<String, BedConfig> bedConfigMap = patientConfig.getBedConfigMap(patient.getDeptId());
        PatientInfoPB patientInfo = toPatientInfoPB(patient, bedConfigMap);
        return GetPatientInfoV2Resp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setPatientInfo(patientInfo)
            .build();
    }

    @Transactional
    public GenericResp updatePatientInfoV2(String updatePatientInfoV2ReqJson) {
        final UpdatePatientInfoV2Req req;
        try {
            UpdatePatientInfoV2Req.Builder builder = UpdatePatientInfoV2Req.newBuilder();
            JsonFormat.parser().merge(updatePatientInfoV2ReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto, {} ", updatePatientInfoV2ReqJson);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        PatientRecord patient = patientRecordRepository.findById(req.getPatientInfo().getPid()).
            orElse(null);
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        updatePatientRecord(patient, req.getPatientInfo());

        if (!patient.getAdmissionStatus().equals(DISCHARGED_VAL) &&
            (patient.getDischargeTime() != null ||
             !StrUtils.isBlank(patient.getToDeptName()) ||
             patient.getDeathTime() != null)
        ) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_IS_NOT_DISCHARGED))
                .build();
        }
        patientRecordRepository.save(patient);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetPatientSettingsResp getPatientSettings(String getPatientSettingsReqJson) {
        final GetPatientSettingsPB req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientSettingsReqJson, GetPatientSettingsPB.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetPatientSettingsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Long pid = req.getPid();
        PatientRecord patient = patientRecordRepository.findById(pid).orElse(null);
        if (patient == null) {
            return GetPatientSettingsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        PatientSettings settings = patientSettingsRepository.findByPid(pid).orElse(null);
        PatientReportConfigPB reportCfgPb = null;
        if (settings != null) {
            reportCfgPb = ProtoUtils.decodePatientReportConfigPB(settings.getReportCfg());
        }

        PatientReportConfigPB.Builder reportCfgBuilder = reportCfgPb == null
            ? PatientReportConfigPB.newBuilder().setNursingReportStartId(1)
            : reportCfgPb.toBuilder();
        reportCfgBuilder.setPid(pid);

        return GetPatientSettingsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setReportCfg(reportCfgBuilder.build())
            .build();
    }

    @Transactional
    public GenericResp updatePatientSettings(String updatePatientSettingsReqJson) {
        final UpdatePatientSettingsReq req;
        try {
            UpdatePatientSettingsReq.Builder builder = UpdatePatientSettingsReq.newBuilder();
            JsonFormat.parser().merge(updatePatientSettingsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();

        Long pid = req.getPid();
        PatientRecord patient = patientRecordRepository.findById(pid).orElse(null);
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        PatientSettings settings = patientSettingsRepository.findByPid(pid).orElse(null);
        PatientReportConfigPB reportCfgPb = null;
        if (settings != null) {
            reportCfgPb = ProtoUtils.decodePatientReportConfigPB(settings.getReportCfg());
        }

        PatientReportConfigPB.Builder reportCfgBuilder = reportCfgPb == null
            ? PatientReportConfigPB.newBuilder().setPid(pid)
            : reportCfgPb.toBuilder();
        reportCfgBuilder.setNursingReportStartId(req.getNursingReportStartId());

        String reportCfgBase64 = ProtoUtils.encodePatientReportConfigPB(reportCfgBuilder.build());
        if (settings == null) {
            settings = PatientSettings.builder()
                .pid(pid)
                .reportCfg(reportCfgBase64)
                .modifiedAt(nowUtc)
                .modifiedBy(accountId)
                .build();
        } else {
            settings.setReportCfg(reportCfgBase64);
            settings.setModifiedAt(nowUtc);
            settings.setModifiedBy(accountId);
        }
        patientSettingsRepository.save(settings);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private boolean hasGenericDiagnosisContent(DiagnosisHistory diagnosisHistory) {
        return !StrUtils.isBlank(diagnosisHistory.getDiagnosis()) ||
            !StrUtils.isBlank(diagnosisHistory.getDiagnosisCode());
    }

    private Comparator<DiagnosisHistory> diagnosisHistoryNewestFirst() {
        return Comparator.comparing(DiagnosisHistory::getDiagnosisTime)
            .reversed()
            .thenComparing(DiagnosisHistory::getId, Comparator.reverseOrder());
    }

    @Transactional
    public GetDiagnosisHistoryResp getDiagnosisHistory(String getDiagnosisHistoryReqJson) {
        final GetDiagnosisHistoryReq req;
        try {
            GetDiagnosisHistoryReq.Builder builder = GetDiagnosisHistoryReq.newBuilder();
            JsonFormat.parser().merge(getDiagnosisHistoryReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDiagnosisHistoryResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找病人信息
        Long pid = req.getPid();
        PatientRecord patient = patientRecordRepository.findById(pid).
            orElse(null);
        if (patient == null) {
            ReturnCode rt = protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND);
            return GetDiagnosisHistoryResp.newBuilder().setRt(rt).build();
        }

        // 获取所有诊断历史
        List<DiagnosisHistory> diagnosisHistories = diagnosisHistoryRepository
            .findByPatientIdAndIsDeletedFalse(pid)
            .stream()
            .filter(this::hasGenericDiagnosisContent)
            .sorted(diagnosisHistoryNewestFirst())
            .toList();

        DiagnosisHistory latestDiagnosis = diagnosisHistories.stream()
            .filter(dh -> !StrUtils.isBlank(dh.getDiagnosis()))
            .findFirst()
            .orElse(null);

        // 如果诊断历史最新的记录和当前病人记录的诊断不一致，更新病人记录
        Boolean updatePatient = false;
        if (StrUtils.isBlank(patient.getDiagnosis()) != (latestDiagnosis == null)) {
            if (latestDiagnosis != null) {
                patient.setDiagnosis(latestDiagnosis.getDiagnosis());
            } else {
                patient.setDiagnosis("");
            }
            updatePatient = true;
        } else if (latestDiagnosis != null && !latestDiagnosis.getDiagnosis().equals(patient.getDiagnosis())) {
            patient.setDiagnosis(latestDiagnosis.getDiagnosis());
            updatePatient = true;
        }
        if (updatePatient) patientRecordRepository.save(patient);

        // 返回诊断历史
        List<DiagnosisHistoryPB> diagnosisHistoryList = new ArrayList<>();
        for (DiagnosisHistory dh : diagnosisHistories) {
            DiagnosisHistoryPB.Builder dhBuilder = DiagnosisHistoryPB.newBuilder();
            dhBuilder.setId(dh.getId());
            dhBuilder.setDiagnosisTimeIso8601(TimeUtils.toIso8601String(dh.getDiagnosisTime(), ZONE_ID));
            dhBuilder.setDiagnosisDoctorId(dh.getDiagnosisAccountId());
            dhBuilder.setDiagnosis(StrUtils.getStringOrDefault(dh.getDiagnosis(), ""));
            dhBuilder.setDiagnosisCode(StrUtils.getStringOrDefault(dh.getDiagnosisCode(), ""));
            diagnosisHistoryList.add(dhBuilder.build());
        }

        return GetDiagnosisHistoryResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllDiagnosisHistory(diagnosisHistoryList)
            .build();
    }

    @Transactional
    public AddDiagnosisHistoryResp addDiagnosisHistory(String addDiagnosisHistoryReqJson) {
        final AddDiagnosisHistoryReq req;
        try {
            AddDiagnosisHistoryReq.Builder builder = AddDiagnosisHistoryReq.newBuilder();
            JsonFormat.parser().merge(addDiagnosisHistoryReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddDiagnosisHistoryResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找病人信息
        Long pid = req.getPid();
        PatientRecord patient = patientRecordRepository.findById(pid).
            orElse(null);
        if (patient == null) {
            ReturnCode rt = protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND);
            return AddDiagnosisHistoryResp.newBuilder().setRt(rt).build();
        }

        // 转换诊断时间
        LocalDateTime diagnosisTime = TimeUtils.fromIso8601String(
            req.getDiagnosisHistory().getDiagnosisTimeIso8601(), "UTC"
        );
        if (diagnosisTime == null) {
            return AddDiagnosisHistoryResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddDiagnosisHistoryResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取最新诊断历史，检查新增记录时间是否为最新，如果为最新，则更新病人记录
        DiagnosisHistory latestDiagnosis = diagnosisHistoryRepository
            .findByPatientIdAndIsDeletedFalse(pid)
            .stream()
            .filter(dh -> !StrUtils.isBlank(dh.getDiagnosis()))
            .sorted(diagnosisHistoryNewestFirst())
            .findFirst()
            .orElse(null);
        if (latestDiagnosis == null || !latestDiagnosis.getDiagnosisTime().isAfter(diagnosisTime)) {
            // 更新病人记录
            if (!StrUtils.isBlank(req.getDiagnosisHistory().getDiagnosis())) {
                patient.setDiagnosis(req.getDiagnosisHistory().getDiagnosis());
            }
            patientRecordRepository.save(patient);
        }

        // 新增诊断历史
        DiagnosisHistory diagnosisHistory = DiagnosisHistory.builder()
            .patientId(pid)
            .diagnosisTime(diagnosisTime)
            .diagnosisAccountId(accountId)
            .diagnosis(req.getDiagnosisHistory().getDiagnosis())
            .diagnosisCode(req.getDiagnosisHistory().getDiagnosisCode())
            .isDeleted(false)
            .modifiedAt(TimeUtils.getNowUtc())
            .modifiedBy(accountId)
            .modifiedByAccountName(accountName)
            .build();
        diagnosisHistory = diagnosisHistoryRepository.save(diagnosisHistory);

        return AddDiagnosisHistoryResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(diagnosisHistory.getId())
            .build();
    }

    @Transactional
    public GenericResp updateDiagnosisHistory(String updateDiagnosisHistoryReqJson) {
        final UpdateDiagnosisHistoryReq req;
        try {
            UpdateDiagnosisHistoryReq.Builder builder = UpdateDiagnosisHistoryReq.newBuilder();
            JsonFormat.parser().merge(updateDiagnosisHistoryReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 检查诊断时间
        LocalDateTime diagnosisTime = TimeUtils.fromIso8601String(
            req.getDiagnosisHistory().getDiagnosisTimeIso8601(), "UTC"
        );
        if (diagnosisTime == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 查找诊断历史记录
        DiagnosisHistory diagnosisHistory = diagnosisHistoryRepository
            .findById(req.getId())
            .orElse(null);
        if (diagnosisHistory == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.DIAGNOSIS_HISTORY_NOT_FOUND))
                .build();
        }
        DiagnosisHistory latestDiagnosis = diagnosisHistoryRepository
            .findByPatientIdAndIsDeletedFalse(diagnosisHistory.getPatientId())
            .stream()
            .filter(dh -> !Objects.equals(dh.getId(), diagnosisHistory.getId()))
            .sorted(diagnosisHistoryNewestFirst())
            .filter(dh -> !StrUtils.isBlank(dh.getDiagnosis()))
            .findFirst()
            .orElse(null);

        // 如果待更新的诊断历史的时间是最新时间，更新对应的记录
        String latestDiagnosisStr = latestDiagnosis == null ?  "" :  latestDiagnosis.getDiagnosis();
        boolean updatedDiagnosisIsLatest = latestDiagnosis == null ||
            diagnosisTime.isAfter(latestDiagnosis.getDiagnosisTime()) ||
            (diagnosisTime.equals(latestDiagnosis.getDiagnosisTime()) &&
                diagnosisHistory.getId() > latestDiagnosis.getId());
        if (updatedDiagnosisIsLatest &&
            !StrUtils.isBlank(req.getDiagnosisHistory().getDiagnosis())
        ) {
            latestDiagnosisStr = req.getDiagnosisHistory().getDiagnosis();
        }

        // 更新病人记录
        PatientRecord patient = patientRecordRepository.findById(diagnosisHistory.getPatientId()).
            orElse(null);
        if (patient == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        Boolean updatePatient = false;
        // 如果诊断历史最新的记录和当前病人记录的诊断不一致，更新病人记录
        if (!latestDiagnosisStr.equals(patient.getDiagnosis())) {
            patient.setDiagnosis(latestDiagnosisStr);
            updatePatient = true;
        }
        if (updatePatient) patientRecordRepository.save(patient);

        // 更新诊断历史记录
        diagnosisHistory.setDiagnosisTime(diagnosisTime);
        diagnosisHistory.setDiagnosisAccountId(accountId);
        diagnosisHistory.setDiagnosis(req.getDiagnosisHistory().getDiagnosis());
        diagnosisHistory.setDiagnosisCode(req.getDiagnosisHistory().getDiagnosisCode());
        diagnosisHistory.setModifiedAt(TimeUtils.getNowUtc());
        diagnosisHistory.setModifiedBy(accountId);
        diagnosisHistory.setModifiedByAccountName(accountName);
        diagnosisHistoryRepository.save(diagnosisHistory);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteDiagnosisHistory(String deleteDiagnosisHistoryReqJson) {
        final DeleteDiagnosisHistoryReq req;
        try {
            DeleteDiagnosisHistoryReq.Builder builder = DeleteDiagnosisHistoryReq.newBuilder();
            JsonFormat.parser().merge(deleteDiagnosisHistoryReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 查找诊断历史记录
        DiagnosisHistory diagnosisHistory = diagnosisHistoryRepository
            .findById(req.getId())
            .orElse(null);
        if (diagnosisHistory == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }
        List<DiagnosisHistory> diagnosisHistories = diagnosisHistoryRepository
            .findByPatientIdAndIsDeletedFalse(diagnosisHistory.getPatientId())
            .stream()
            .sorted(diagnosisHistoryNewestFirst())
            .toList();

        // 逻辑删除
        diagnosisHistory.setIsDeleted(true);
        diagnosisHistory.setDeletedAt(TimeUtils.getNowUtc());
        diagnosisHistory.setDeletedBy(accountId);
        diagnosisHistoryRepository.save(diagnosisHistory);

        // 查找病人信息
        Long pid = diagnosisHistory.getPatientId();
        PatientRecord patient = patientRecordRepository.findById(pid).
            orElse(null);
        if (patient == null) {
            ReturnCode rt = protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND);
            return GenericResp.newBuilder().setRt(rt).build();
        }

        // 更新病人记录
        DiagnosisHistory latestDiagnosis = diagnosisHistories.stream()
            .filter(dh -> !Objects.equals(dh.getId(), diagnosisHistory.getId()))
            .filter(dh -> !StrUtils.isBlank(dh.getDiagnosis()))
            .findFirst()
            .orElse(null);

        Boolean updatePatient = false;
        if (StrUtils.isBlank(patient.getDiagnosis()) != (latestDiagnosis == null)) {
            if (latestDiagnosis != null) {
                patient.setDiagnosis(latestDiagnosis.getDiagnosis());
            } else {
                patient.setDiagnosis("");
            }
            updatePatient = true;
        } else if (latestDiagnosis != null && !latestDiagnosis.getDiagnosis().equals(patient.getDiagnosis())) {
            patient.setDiagnosis(latestDiagnosis.getDiagnosis());
            updatePatient = true;
        }
        if (updatePatient) patientRecordRepository.save(patient);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public SyncHisPatientResp syncHisPatient(String syncHisPatientReqJson) {
        if (!patientSyncService.isSyncEnabled()) {
            log.warn("Patient synchronization request rejected because synchronization is disabled");
            return SyncHisPatientResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_SYNC_DISABLED))
                .setAdmittedHisPatientCount(0)
                .build();
        }

        final SyncHisPatientReq req;
        try {
            SyncHisPatientReq.Builder builder = SyncHisPatientReq.newBuilder();
            JsonFormat.parser().merge(syncHisPatientReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return SyncHisPatientResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .setAdmittedHisPatientCount(0)
                .build();
        }

        // 执行同步
        patientSyncService.syncPatientRecords(req.getForceSync());

        // 获取已入科病人数量
        Integer admittedCount = patientSyncService.getInIcuHisPatientRecords().size();

        return SyncHisPatientResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setAdmittedHisPatientCount(admittedCount)
            .build();
    }

    @Transactional
    public PatientRecord getPatientRecordInIcu(Long patientId) {
        Optional<PatientRecord> optPatient = patientRecordRepository.findById(patientId);
        if (!optPatient.isPresent()) {
            log.error("Patient record not found for patientId: {}", patientId);
            return null;
        }

        PatientRecord patient = optPatient.get();
        if (patient.getAdmissionStatus() != IN_ICU_VAL) {
            log.error("Patient is not in ICU for patientId: {}", patientId);
            return null;
        }

        if (patient.getAdmissionTime() == null) {
            log.error("Admission time not found for patientId: {}", patientId);
            return null;
        }

        if (StrUtils.isBlank(patient.getDeptId())) {
            log.error("Dept id not found for patientId: {}", patientId);
            return null;
        }

        return patient;
    }

    @Transactional
    public List<PatientRecord> getAllPatientsInIcu(String deptId) {
        List<PatientRecord> patients = patientRecordRepository.findByDeptIdAndAdmissionStatus(
            deptId, IN_ICU_VAL);
        if (patients.isEmpty()) {
            log.warn("No patients found in ICU");
        }
        return patients;
    }

    @Transactional
    public PatientRecord getPatientRecord(Long patientId) {
        Optional<PatientRecord> optPatient = patientRecordRepository.findById(patientId);
        if (!optPatient.isPresent()) {
            log.error("Patient record not found for patientId: {}", patientId);
            return null;
        }

        PatientRecord patient = optPatient.get();
        if (patient.getAdmissionTime() == null) {
            log.error("Admission time not found for patientId: {}", patientId);
            return null;
        }

        if (StrUtils.isBlank(patient.getDeptId())) {
            log.error("Dept id not found for patientId: {}", patientId);
            return null;
        }

        return patient;
    }

    @Transactional
    public List<PatientRecord> getPatientRecords(String deptId, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc) {
        // 查找条件
        // - 科室ID
        // - 科室状态
        //      - 在科 / 待出科 && 入科时间在queryEndUtc之前
        //      - 出科 && （出科时间大于等于查询开始时间，且入科时间小于等于查询结束时间）
        List<PatientRecord> inIcuPatients = patientRecordRepository.findByDeptIdAndAdmissionStatusInAndAdmissionTimeBefore(
            deptId, Arrays.asList(IN_ICU_VAL, PENDING_ADMISSION_VAL), queryEndUtc
        );
        List<PatientRecord> dischargedPatients = patientRecordRepository.findDischargedPatients(
            deptId, queryEndUtc, queryStartUtc
        );

        // 合并两组病人记录并返回
        inIcuPatients.addAll(dischargedPatients);
        return inIcuPatients;
    }

    public Integer getAdmissionStatusInIcuId() {
        return IN_ICU_VAL;
    }

    public Integer getAdmissionStatusPendingDischargedId() {
        return PENDING_DISCHARGED_VAL;
    }

    public Integer getAdmissionStatusDischargedId() {
        return DISCHARGED_VAL;
    }

    public StatusCode validateTimeRange(
        PatientRecord patientRecord, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        if (startUtc == null || endUtc == null || startUtc.isAfter(endUtc)) {
            log.error("Invalid time range: {} - {}", startUtc, endUtc);
            return StatusCode.INVALID_SHIFT_TIME;
        }
        if (patientRecord.getAdmissionTime() == null || endUtc.isBefore(patientRecord.getAdmissionTime())) {
            log.error("Start time is before admission time: {} - {}", startUtc, patientRecord.getAdmissionTime());
            return StatusCode.SHIFT_START_BEFORE_ADMISSION;
        }
        if (patientRecord.getDischargeTime() != null && startUtc.isAfter(patientRecord.getDischargeTime())) {
            log.error("End time is after discharge time: {} - {}", endUtc, patientRecord.getDischargeTime());
            return StatusCode.SHIFT_END_AFTER_DISCHARGE;
        }
        return StatusCode.OK;
    }

    public StatusCode validateTime(PatientRecord patientRecord, LocalDateTime timepoint) {
        if (timepoint == null) {
            log.error("Timepoint is null");
            return StatusCode.INVALID_SHIFT_TIME;
        }
        if (patientRecord.getAdmissionTime() == null || timepoint.isBefore(patientRecord.getAdmissionTime())) {
            log.error("Timepoint is before admission time: {} - {}", timepoint, patientRecord.getAdmissionTime());
            return StatusCode.EFFECTIVE_TIME_BEFORE_ADMISSION;
        }
        if (patientRecord.getDischargeTime() != null && timepoint.isAfter(patientRecord.getDischargeTime())) {
            log.error("Timepoint is after discharge time: {} - {}", timepoint, patientRecord.getDischargeTime());
            return StatusCode.EFFECTIVE_TIME_AFTER_DISCHARGE;
        }
        return StatusCode.OK;
    }

    public PatientShift getShiftSettings(Long patientId) {
        final PatientRecord patientRecord = getPatientRecord(patientId);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", patientId);
            return new PatientShift(StatusCode.PATIENT_NOT_FOUND, null, null);
        }
        final String deptId = patientRecord.getDeptId();
        final ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(deptId);
        if (shiftSettings == null) {
            log.error("ShiftSettings not found for deptId: ", deptId);
            return new PatientShift(StatusCode.NO_SHIFT_SETTING, patientRecord, null);
        }
        return new PatientShift(StatusCode.OK, patientRecord, shiftSettings);
    }

    private String getPatientRecordValue(
        PatientRecord patientRec, String colId, Map<String, BedConfig> bedConfigMap
    ) {
        switch (colId) {
            case "id":
                return String.valueOf(patientRec.getId());

            case "his_mrn":
                if (patientRec.getHisMrn() == null) return "";
                return patientRec.getHisMrn();

            case "his_patient_id":
                if (patientRec.getHisPatientId() == null) return "";
                return patientRec.getHisPatientId();

            case "his_index_id":
                if (patientRec.getHisIndexId() == null) return "";
                return patientRec.getHisIndexId();

            case "his_encounter_id":
                if (patientRec.getHisEncounterId() == null) return "";
                return patientRec.getHisEncounterId();

            case "his_admission_count":
                if (patientRec.getHisAdmissionCount() == null) return "";
                return String.valueOf(patientRec.getHisAdmissionCount());

            case "his_admission_time":
                if (patientRec.getHisAdmissionTime() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getHisAdmissionTime(), ZONE_ID);

            case "his_admission_diagnosis":
                if (patientRec.getHisAdmissionDiagnosis() == null) return "";
                return patientRec.getHisAdmissionDiagnosis();

            case "his_admission_diagnosis_code":
                if (patientRec.getHisAdmissionDiagnosisCode() == null) return "";
                return patientRec.getHisAdmissionDiagnosisCode();


            case "his_bed_number":
                if (patientRec.getHisBedNumber() == null) return "";
                return patientRec.getHisBedNumber();

            case "icu_manual_entry":
                if (patientRec.getIcuManualEntry() == null) return "";
                return protoService.getBoolStr(patientRec.getIcuManualEntry());

            case "icu_manual_entry_account_id":
                if (patientRec.getIcuManualEntryAccountId() == null) return "";
                return patientRec.getIcuManualEntryAccountId();

            case "icu_name":
                if (patientRec.getIcuName() == null) return "";
                return patientRec.getIcuName();

            case "icu_gender":
                if (patientRec.getIcuGender() == null) return "";
                return protoService.getGenderStr(patientRec.getIcuGender());

            case "icu_date_of_birth":
                if (patientRec.getIcuDateOfBirth() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getIcuDateOfBirth(), ZONE_ID);

            case "icu_age":
                if (patientRec.getIcuDateOfBirth() == null) return "";
                return String.valueOf(TimeUtils.getAge(patientRec.getIcuDateOfBirth()));

            case "height":
                if (patientRec.getHeight() == null) return "";
                return floatToStr(patientRec.getHeight());

            case "weight":
                if (patientRec.getWeight() == null) return "";
                return floatToStr(patientRec.getWeight());

            case "weight_type":
                if (patientRec.getWeightType() == null) return "";
                return patientRec.getWeightType();

            case "weight_ibw":
                if (patientRec.getWeightIbw() == null) return "";
                return patientRec.getWeightIbw();

            case "blood_type":
                if (patientRec.getBloodType() == null) return "";
                return patientRec.getBloodType();

            case "blood_rh":
                if (patientRec.getBloodRh() == null) return "";
                return patientRec.getBloodRh();

            case "bsa":
                if (patientRec.getBsa() == null) return "";
                return floatToStr(patientRec.getBsa());

            case "past_medical_history":
                if (patientRec.getPastMedicalHistory() == null) return "";
                return patientRec.getPastMedicalHistory();

            case "allergies":
                if (patientRec.getAllergies() == null) return "";
                return patientRec.getAllergies();

            case "phone":
                if (patientRec.getPhone() == null) return "";
                return patientRec.getPhone();

            case "home_phone":
                if (patientRec.getHomePhone() == null) return "";
                return patientRec.getHomePhone();

            case "home_address":
                if (patientRec.getHomeAddress() == null) return "";
                return patientRec.getHomeAddress();

            case "document_type":
                if (patientRec.getDocumentType() == null) return "";
                return patientRec.getDocumentType();

            case "id_card_number":
                if (patientRec.getIdCardNumber() == null) return "";
                return patientRec.getIdCardNumber();

            case "nation":
                if (patientRec.getNation() == null) return "";
                return patientRec.getNation();

            case "native_place":
                if (patientRec.getNativePlace() == null) return "";
                return patientRec.getNativePlace();

            case "occupation":
                if (patientRec.getOccupation() == null) return "";
                return patientRec.getOccupation();

            case "marital_status":
                if (patientRec.getMaritalStatus() == null) return "";
                return protoService.getMaritalStatusStr(patientRec.getMaritalStatus());

            case "emergency_contact_name":
                if (patientRec.getEmergencyContactName() == null) return "";
                return patientRec.getEmergencyContactName();

            case "emergency_contact_relation":
                if (patientRec.getEmergencyContactRelation() == null) return "";
                return patientRec.getEmergencyContactRelation();

            case "emergency_contact_phone":
                if (patientRec.getEmergencyContactPhone() == null) return "";
                return patientRec.getEmergencyContactPhone();

            case "emergency_contact_address":
                if (patientRec.getEmergencyContactAddress() == null) return "";
                return patientRec.getEmergencyContactAddress();

            case "payment_method":
                if (patientRec.getPaymentMethod() == null) return "";
                return patientRec.getPaymentMethod();

            case "insurance_type":
                if (patientRec.getInsuranceType() == null) return "";
                return patientRec.getInsuranceType();

            case "insurance_number":
                if (patientRec.getInsuranceNumber() == null) return "";
                return patientRec.getInsuranceNumber();

            case "medical_card_number":
                if (patientRec.getMedicalCardNumber() == null) return "";
                return patientRec.getMedicalCardNumber();

            case "is_vip_patient":
                if (patientRec.getIsVipPatient() == null) return "";
                return protoService.getBoolStr(patientRec.getIsVipPatient());

            case "bed_number_str":
                String hisBedNumber = patientRec.getHisBedNumber();
                BedConfig bedConfig = bedConfigMap.get(hisBedNumber);
                return bedConfig == null ? hisBedNumber : bedConfig.getDisplayBedNumber();

            case "wristband_location":
                if (patientRec.getWristbandLocation() == null) return "";
                return patientRec.getWristbandLocation();

            case "patient_pose":
                if (patientRec.getPatientPose() == null) return "";
                return patientRec.getPatientPose();

            case "nursing_care_level":
                if (patientRec.getNursingCareLevel() == null) return "";
                return patientRec.getNursingCareLevel();

            case "illness_severity_level":
                if (patientRec.getIllnessSeverityLevel() == null) return "";
                return patientRec.getIllnessSeverityLevel();

            case "diet_type":
                if (patientRec.getDietType() == null) return "";
                return patientRec.getDietType();

            case "isolation_precaution":
                if (patientRec.getIsolationPrecaution() == null) return "";
                return patientRec.getIsolationPrecaution();

            case "chief_complaint":
                if (patientRec.getChiefComplaint() == null) return "";
                return patientRec.getChiefComplaint();

            case "dept_id":
                if (patientRec.getDeptId() == null) return "";
                return patientRec.getDeptId();

            case "dept_name":
                if (patientRec.getDeptName() == null) return "";
                return patientRec.getDeptName();

            case "ward_code":
                if (patientRec.getWardCode() == null) return "";
                return patientRec.getWardCode();

            case "ward_name":
                if (patientRec.getWardName() == null) return "";
                return patientRec.getWardName();

            case "attending_doctor_id":
                if (patientRec.getAttendingDoctorId() == null) return "";
                return patientRec.getAttendingDoctorId();

            case "primary_care_doctor_id":
                if (patientRec.getPrimaryCareDoctorId() == null) return "";
                return userService.getNameByAccountId(patientRec.getPrimaryCareDoctorId());

            case "admitting_doctor_id":
                if (patientRec.getAdmittingDoctorId() == null) return "";
                return patientRec.getAdmittingDoctorId();

            case "responsible_nurse_id":
                if (patientRec.getResponsibleNurseId() == null) return "";
                return userService.getNameByAutoId(patientRec.getResponsibleNurseId());

            case "from_dept_name":
                if (patientRec.getFromDeptName() == null) return "";
                return patientRec.getFromDeptName();

            case "from_dept_id":
                if (patientRec.getFromDeptId() == null) return "";
                return patientRec.getFromDeptId();

            case "admission_type":
                return getAdmissionTypeDisplay(patientRec);

            case "is_planned_admission":
                if (patientRec.getIsPlannedAdmission() == null) return "";
                return protoService.getBoolStr(patientRec.getIsPlannedAdmission());

            case "unplanned_admission_reason":
                if (patientRec.getUnplannedAdmissionReason() == null) return "";
                return patientRec.getUnplannedAdmissionReason();

            case "admission_status":
                if (patientRec.getAdmissionStatus() == null) return "";
                return patientConfig.getAdmissionStatusStr(patientRec.getAdmissionStatus());

            case "admission_time":
                if (patientRec.getAdmissionTime() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getAdmissionTime(), ZONE_ID);

            case "admission_edit_time":
                if (patientRec.getAdmissionEditTime() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getAdmissionEditTime(), ZONE_ID);

            case "admitting_account_id":
                if (patientRec.getAdmittingAccountId() == null) return "";
                return patientRec.getAdmittingAccountId();

            case "diagnosis":
                if (patientRec.getDiagnosis() == null) return "";
                return patientRec.getDiagnosis();

            case "discharge_type":
                if (patientRec.getDischargeType() == null) return "";
                return patientConfig.getDischargeTypeStr(patientRec.getDischargeType());

            case "death_time":
                if (patientRec.getDeathTime() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getDeathTime(), ZONE_ID);

            case "his_discharge_time":
                if (patientRec.getHisDischargeTime() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getHisDischargeTime(), ZONE_ID);

            case "discharge_diagnosis":
                if (patientRec.getDischargeDiagnosis() == null) return "";
                return patientRec.getDischargeDiagnosis();

            case "discharge_diagnosis_code":
                if (patientRec.getDischargeDiagnosisCode() == null) return "";
                return patientRec.getDischargeDiagnosisCode();

            case "to_dept_name":
                if (patientRec.getToDeptName() == null) return "";
                return patientRec.getToDeptName();

            case "to_dept_id":
                if (patientRec.getToDeptId() == null) return "";
                return patientRec.getToDeptId();

            case "discharge_time":
                if (patientRec.getDischargeTime() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getDischargeTime(), ZONE_ID);

            case "admission_days":
                LocalDateTime admissionTime = patientRec.getAdmissionTime();
                LocalDateTime dischargeTime = patientRec.getDischargeTime();
                if (admissionTime == null || dischargeTime == null) return "";
                // 计算住院天数，向上取整
                // 如果出院时间晚于入院时间的同一时刻，则天数+1
                long days = ChronoUnit.DAYS.between(admissionTime, dischargeTime);
                if (dischargeTime.toLocalTime().isAfter(admissionTime.toLocalTime())) {
                    days++;
                }
                return String.valueOf(days);

            case "discharge_edit_time":
                if (patientRec.getDischargeEditTime() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getDischargeEditTime(), ZONE_ID);

            case "discharge_account_id":
                if (patientRec.getDischargeAccountId() == null) return "";
                return patientRec.getDischargeAccountId();

            case "created_at":
                if (patientRec.getCreatedAt() == null) return "";
                return TimeUtils.toIso8601String(patientRec.getCreatedAt(), ZONE_ID);

            default:
                throw new IllegalArgumentException("Unexpected patient_records' column name: " + colId);
        }
    }

    private void processPatientRecordsV2(
        List<PatientRecord> patients, String colId, Integer cols,
        Integer colOffset, List<String> values
    ) {
        /*
        import java.util.HashMap;
        import java.util.Map;
        import java.util.function.Consumer;
        Map<String, Consumer<PatientRecord>> columnProcessors = new HashMap<>();
        columnProcessors.put("his_mrn", (patient, i) -> {
            if (patient.getHisMrn() != null) {
                values.set(i * cols + colOffset, patient.getHisMrn());
            }
        });

        // Retrieve the appropriate processor from the map and apply it
        Consumer<PatientRecord> processor = columnProcessors.get(colId);
        if (processor != null) {
            patients.forEach(processor);
        } else {
            throw new IllegalArgumentException("Unexpected patient_records' column name: " + colId);
        }
        */
    }

    private PatientInfoPB toPatientInfoPB(PatientRecord patient, Map<String, BedConfig> bedConfigMap) {
        PatientInfoPB.Builder builder = PatientInfoPB.newBuilder();

        builder.setPid(patient.getId() != null ? patient.getId() : 0);
        builder.setHisMrn(patient.getHisMrn());
        builder.setHisPatientId(patient.getHisPatientId());
        builder.setHisAdmissionTimeIso8601(patient.getHisAdmissionTime() != null 
            ? TimeUtils.toIso8601String(patient.getHisAdmissionTime(), "UTC") : "");
        builder.setHisAdmissionDiagnosis(patient.getHisAdmissionDiagnosis());
        builder.setHisBedNumber(patient.getHisBedNumber());
        builder.setIcuName(patient.getIcuName());
        builder.setIcuGender(patient.getIcuGender());
        builder.setIcuDateOfBirthIso8601(patient.getIcuDateOfBirth() != null 
            ? TimeUtils.toIso8601String(patient.getIcuDateOfBirth(), "UTC") : "");
        builder.setAge(TimeUtils.getAge(patient.getIcuDateOfBirth()));
        builder.setHeight(patient.getHeight());
        builder.setWeight(patient.getWeight());
        builder.setWeightType(patient.getWeightType());
        builder.setWeightIbw(patient.getWeightIbw());
        // 计算BMI
        if (patient.getHeight() != null && patient.getHeight() > 0 &&
            patient.getWeight() != null && patient.getWeight() > 0
        ) {
            // height的单位是cm，因此 * 10000.0
            float bmi = patient.getWeight() * 10000.0f / (patient.getHeight() * patient.getHeight());
            builder.setBmi(String.format("%.1f", bmi));
        }

        Integer bloodTypeId = enumsV2.getBloodTypeList().stream()
            .filter(bloodType -> bloodType.getName().equals(patient.getBloodType()))
            .map(EnumValue::getId)
            .findFirst()
            .orElse(0);
        builder.setBloodType(bloodTypeId);
        Integer bloodRhId = enumsV2.getBloodRhList().stream()
            .filter(bloodRh -> bloodRh.getName().equals(patient.getBloodRh()))
            .map(EnumValue::getId)
            .findFirst()
            .orElse(0);
        builder.setBloodRh(bloodRhId);
        builder.setBsa(patient.getBsa());
        builder.setPastMedicalHistory(patient.getPastMedicalHistory());
        builder.setAllergies(patient.getAllergies());
        builder.setPhone(patient.getPhone());
        builder.setHomePhone(patient.getHomePhone());
        builder.setHomeAddress(patient.getHomeAddress());
        builder.setDocumentType(patient.getDocumentType());
        builder.setIdCardNumber(patient.getIdCardNumber());
        builder.setNation(patient.getNation());
        builder.setNativePlace(patient.getNativePlace());
        builder.setEducationLevel(patient.getEducationLevel());
        builder.setOccupation(patient.getOccupation());
        builder.setMaritalStatus(patient.getMaritalStatus());
        builder.setEmergencyContactName(patient.getEmergencyContactName());
        builder.setEmergencyContactRelation(patient.getEmergencyContactRelation());
        builder.setEmergencyContactPhone(patient.getEmergencyContactPhone());
        builder.setEmergencyContactAddress(patient.getEmergencyContactAddress());
        builder.setPaymentMethod(patient.getPaymentMethod());
        builder.setInsuranceType(patient.getInsuranceType());
        builder.setInsuranceNumber(patient.getInsuranceNumber());
        builder.setMedicalCardNumber(patient.getMedicalCardNumber());
        builder.setIsVipPatient(patient.getIsVipPatient());

        String displayBedNumber = bedConfigMap.get(patient.getHisBedNumber()) != null 
            ? bedConfigMap.get(patient.getHisBedNumber()).getDisplayBedNumber() : "";
        builder.setBedNumberStr(displayBedNumber);

        builder.setWristbandLocation(patient.getWristbandLocation());
        builder.setPatientPose(patient.getPatientPose());
        builder.setNursingCareLevel(patient.getNursingCareLevel());
        builder.setIllnessSeverityLevel(patient.getIllnessSeverityLevel());
        builder.setDietType(patient.getDietType());
        builder.setIsolationPrecaution(patient.getIsolationPrecaution());
        builder.setChiefComplaint(patient.getChiefComplaint());

        // Doctor and nurse names resolved via userService
        builder.setAttendingDoctorId(patient.getAttendingDoctorId());
        builder.setAttendingDoctorName(patient.getAttendingDoctorId() != null 
            ? userService.getNameByAutoId(patient.getAttendingDoctorId()) : "");
        builder.setPrimaryCareDoctorId(patient.getPrimaryCareDoctorId());
        builder.setPrimaryCareDoctorName(patient.getPrimaryCareDoctorId() != null 
            ? userService.getNameByAccountId(patient.getPrimaryCareDoctorId()) : "");
        builder.setAdmittingDoctorId(patient.getAdmittingDoctorId());
        builder.setAdmittingDoctorName(patient.getAdmittingDoctorId() != null 
            ? userService.getNameByAutoId(patient.getAdmittingDoctorId()) : "");
        builder.setResponsibleNurseId(patient.getResponsibleNurseId());
        builder.setResponsibleNurseName(patient.getResponsibleNurseId() != null 
            ? userService.getNameByAutoId(patient.getResponsibleNurseId()) : "");

        builder.setDiagnosis(patient.getDiagnosis());

        builder.setAdmissionStatus(patient.getAdmissionStatus());

        builder.setFromDeptName(patient.getFromDeptName());
        builder.addAllAdmissionType(getAdmissionTypeValues(patient));
        builder.setIsPlannedAdmission(patient.getIsPlannedAdmission());
        builder.setUnplannedAdmissionReason(patient.getUnplannedAdmissionReason());
        builder.setAdmissionTimeIso8601(patient.getAdmissionTime() != null 
            ? TimeUtils.toIso8601String(patient.getAdmissionTime(), "UTC") : "");

        builder.setDischargeType(patient.getDischargeType());
        builder.setDeathTimeIso8601(patient.getDeathTime() != null
            ? TimeUtils.toIso8601String(patient.getDeathTime(), "UTC") : "");
        builder.setHisDischargeTimeIso8601(patient.getHisDischargeTime() != null
            ? TimeUtils.toIso8601String(patient.getHisDischargeTime(), "UTC") : "");
        builder.setToDeptName(patient.getToDeptName());
        builder.setDischargeTimeIso8601(patient.getDischargeTime() != null 
            ? TimeUtils.toIso8601String(patient.getDischargeTime(), "UTC") : "");
        builder.setDischargeDiagnosis(StrUtils.isBlank(patient.getDischargeDiagnosis()) ? "" : patient.getDischargeDiagnosis());

        return builder.build();
    }

    private void updatePatientRecord(PatientRecord patient, PatientInfoPB patientInfo) {
        // 以下字段不可更新
        // patient.setHisMrn(patientInfo.getHisMrn().isEmpty() ? null : patientInfo.getHisMrn());
        // patient.setHisPatientId(patientInfo.getHisPatientId().isEmpty() ? null : patientInfo.getHisPatientId());
        patient.setHisAdmissionTime(patientInfo.getHisAdmissionTimeIso8601().isEmpty() 
            ? null : TimeUtils.fromIso8601String(patientInfo.getHisAdmissionTimeIso8601(), "UTC"));
        patient.setHisAdmissionDiagnosis(patientInfo.getHisAdmissionDiagnosis().isEmpty() 
            ? null : patientInfo.getHisAdmissionDiagnosis());
        // patient.setHisBedNumber(patientInfo.getHisBedNumber().isEmpty() ? null : patientInfo.getHisBedNumber());
        // patient.setIcuName(patientInfo.getIcuName().isEmpty() ? null : patientInfo.getIcuName());
        // patient.setIcuGender(patientInfo.getIcuGender() != 0 ? patientInfo.getIcuGender() : null);

        patient.setIcuDateOfBirth(patientInfo.getIcuDateOfBirthIso8601().isEmpty() 
            ? null : TimeUtils.fromIso8601String(patientInfo.getIcuDateOfBirthIso8601(), "UTC"));
        patient.setHeight(patientInfo.getHeight() != 0.0f ? patientInfo.getHeight() : null);
        patient.setWeight(patientInfo.getWeight() != 0.0f ? patientInfo.getWeight() : null);
        patient.setWeightType(patientInfo.getWeightType().isEmpty() ? null : patientInfo.getWeightType());
        patient.setWeightIbw(patientInfo.getWeightIbw().isEmpty() ? null : patientInfo.getWeightIbw());

        String bloodType = enumsV2.getBloodTypeList().stream()
            .filter(bldType -> bldType.getId() == patientInfo.getBloodType())
            .map(EnumValue::getName)
            .findFirst()
            .orElse("");
        patient.setBloodType(bloodType);
        String bloodRh = enumsV2.getBloodRhList().stream()
            .filter(bldRh -> bldRh.getId() == patientInfo.getBloodRh())
            .map(EnumValue::getName)
            .findFirst()
            .orElse("");
        patient.setBloodRh(bloodRh);
        patient.setBsa(patientInfo.getBsa() != 0.0f ? patientInfo.getBsa() : null);
        patient.setPastMedicalHistory(patientInfo.getPastMedicalHistory().isEmpty() 
            ? null : patientInfo.getPastMedicalHistory());
        patient.setAllergies(patientInfo.getAllergies().isEmpty() ? null : patientInfo.getAllergies());
        patient.setPhone(patientInfo.getPhone().isEmpty() ? null : patientInfo.getPhone());
        patient.setHomePhone(patientInfo.getHomePhone().isEmpty() ? null : patientInfo.getHomePhone());
        patient.setHomeAddress(patientInfo.getHomeAddress().isEmpty() ? null : patientInfo.getHomeAddress());
        patient.setDocumentType(patientInfo.getDocumentType().isEmpty() ? null : patientInfo.getDocumentType());
        patient.setIdCardNumber(patientInfo.getIdCardNumber().isEmpty() ? null : patientInfo.getIdCardNumber());
        patient.setNation(patientInfo.getNation().isEmpty() ? null : patientInfo.getNation());
        patient.setNativePlace(patientInfo.getNativePlace().isEmpty() ? null : patientInfo.getNativePlace());
        patient.setEducationLevel(patientInfo.getEducationLevel() == 0 ? 1 : patientInfo.getEducationLevel());
        patient.setOccupation(patientInfo.getOccupation().isEmpty() ? null : patientInfo.getOccupation());
        patient.setMaritalStatus(patientInfo.getMaritalStatus() != 0 ? patientInfo.getMaritalStatus() : null);
        patient.setEmergencyContactName(patientInfo.getEmergencyContactName().isEmpty() 
            ? null : patientInfo.getEmergencyContactName());
        patient.setEmergencyContactRelation(patientInfo.getEmergencyContactRelation().isEmpty() 
            ? null : patientInfo.getEmergencyContactRelation());
        patient.setEmergencyContactPhone(patientInfo.getEmergencyContactPhone().isEmpty() 
            ? null : patientInfo.getEmergencyContactPhone());
        patient.setEmergencyContactAddress(patientInfo.getEmergencyContactAddress().isEmpty() 
            ? null : patientInfo.getEmergencyContactAddress());
        patient.setPaymentMethod(patientInfo.getPaymentMethod().isEmpty() ? null : patientInfo.getPaymentMethod());
        patient.setInsuranceType(patientInfo.getInsuranceType().isEmpty() ? null : patientInfo.getInsuranceType());
        patient.setInsuranceNumber(patientInfo.getInsuranceNumber().isEmpty() ? null : patientInfo.getInsuranceNumber());
        patient.setMedicalCardNumber(patientInfo.getMedicalCardNumber().isEmpty() 
            ? null : patientInfo.getMedicalCardNumber());
        patient.setIsVipPatient(patientInfo.getIsVipPatient());
        patient.setWristbandLocation(patientInfo.getWristbandLocation().isEmpty() 
            ? null : patientInfo.getWristbandLocation());
        patient.setPatientPose(patientInfo.getPatientPose().isEmpty() ? null : patientInfo.getPatientPose());
        patient.setNursingCareLevel(patientInfo.getNursingCareLevel().isEmpty() 
            ? null : patientInfo.getNursingCareLevel());
        patient.setIllnessSeverityLevel(patientInfo.getIllnessSeverityLevel().isEmpty() 
            ? null : patientInfo.getIllnessSeverityLevel());
        patient.setDietType(patientInfo.getDietType().isEmpty() ? null : patientInfo.getDietType());
        patient.setIsolationPrecaution(patientInfo.getIsolationPrecaution().isEmpty() 
            ? null : patientInfo.getIsolationPrecaution());
        patient.setChiefComplaint(patientInfo.getChiefComplaint().isEmpty() ? null : patientInfo.getChiefComplaint());

        // Doctor and nurse IDs
        patient.setAttendingDoctorId(patientInfo.getAttendingDoctorId().isEmpty() 
            ? null : patientInfo.getAttendingDoctorId());
        patient.setPrimaryCareDoctorId(patientInfo.getPrimaryCareDoctorId().isEmpty() 
            ? null : patientInfo.getPrimaryCareDoctorId());
        patient.setAdmittingDoctorId(patientInfo.getAdmittingDoctorId().isEmpty() 
            ? null : patientInfo.getAdmittingDoctorId());
        patient.setResponsibleNurseId(patientInfo.getResponsibleNurseId().isEmpty() 
            ? null : patientInfo.getResponsibleNurseId());

        patient.setDiagnosis(patientInfo.getDiagnosis().isEmpty() ? null : patientInfo.getDiagnosis());

        patient.setFromDeptName(patientInfo.getFromDeptName().isEmpty()
            ? null : patientInfo.getFromDeptName());
        setAdmissionTypeValues(patient, patientInfo.getAdmissionTypeList());
        patient.setIsPlannedAdmission(patientInfo.getIsPlannedAdmission());
        patient.setUnplannedAdmissionReason(patientInfo.getUnplannedAdmissionReason().isEmpty() 
            ? null : patientInfo.getUnplannedAdmissionReason());
        patient.setAdmissionTime(patientInfo.getAdmissionTimeIso8601().isEmpty() 
            ? null : TimeUtils.fromIso8601String(patientInfo.getAdmissionTimeIso8601(), "UTC"));

        patient.setDischargeType(patientInfo.getDischargeType() != 0 ? patientInfo.getDischargeType() : null);
        patient.setDeathTime(patientInfo.getDeathTimeIso8601().isEmpty()
            ? null : TimeUtils.fromIso8601String(patientInfo.getDeathTimeIso8601(), "UTC"));
        patient.setHisDischargeTime(patientInfo.getHisDischargeTimeIso8601().isEmpty()
            ? null : TimeUtils.fromIso8601String(patientInfo.getHisDischargeTimeIso8601(), "UTC"));
        patient.setToDeptName(patientInfo.getToDeptName().isEmpty()
            ? null : patientInfo.getToDeptName());
        patient.setDischargeTime(patientInfo.getDischargeTimeIso8601().isEmpty() 
            ? null : TimeUtils.fromIso8601String(patientInfo.getDischargeTimeIso8601(), "UTC"));
        patient.setDischargeDiagnosis(patientInfo.getDischargeDiagnosis().isEmpty()
            ? null : patientInfo.getDischargeDiagnosis());
    }

    private void setAdmissionTypeValues(PatientRecord patient, List<Integer> admissionTypeValues) {
        List<Integer> validAdmissionTypes = new ArrayList<>();
        for (Integer admissionType : admissionTypeValues) {
            if (admissionType != null && admissionType > 0) {
                validAdmissionTypes.add(admissionType);
            }
        }
        patient.setAdmissionTypes(admissionTypeValuesToString(validAdmissionTypes));
        patient.setAdmissionType(validAdmissionTypes.isEmpty() ? null : validAdmissionTypes.get(0));
    }

    private String admissionTypeValuesToString(List<Integer> admissionTypeValues) {
        if (admissionTypeValues == null || admissionTypeValues.isEmpty()) return null;

        StringJoiner joiner = new StringJoiner(",");
        for (Integer admissionType : admissionTypeValues) {
            joiner.add(String.valueOf(admissionType));
        }
        return joiner.toString();
    }

    private List<Integer> getAdmissionTypeValues(PatientRecord patient) {
        List<Integer> admissionTypes = parseAdmissionTypes(patient.getAdmissionTypes());
        if (!admissionTypes.isEmpty()) return admissionTypes;

        Integer legacyAdmissionType = patient.getAdmissionTypeRaw();
        if (legacyAdmissionType == null || legacyAdmissionType == 0) return Collections.emptyList();
        return List.of(legacyAdmissionType);
    }

    private List<Integer> parseAdmissionTypes(String admissionTypes) {
        if (StrUtils.isBlank(admissionTypes)) return Collections.emptyList();

        String[] tokens = admissionTypes.split(",");
        List<Integer> parsed = new ArrayList<>();
        for (String token : tokens) {
            String trimmedToken = token.trim();
            if (trimmedToken.isEmpty()) return Collections.emptyList();
            try {
                parsed.add(Integer.parseInt(trimmedToken));
            } catch (NumberFormatException e) {
                return Collections.emptyList();
            }
        }
        return parsed.isEmpty() ? Collections.emptyList() : parsed;
    }

    private String getAdmissionTypeDisplay(PatientRecord patient) {
        List<String> admissionTypeNames = new ArrayList<>();
        for (Integer admissionType : getAdmissionTypeValues(patient)) {
            String admissionTypeName = patientConfig.getAdmissionTypeStr(admissionType);
            if (!StrUtils.isBlank(admissionTypeName)) {
                admissionTypeNames.add(admissionTypeName);
            }
        }
        return String.join(",", admissionTypeNames);
    }

    PatientTablePB appendReadmissionInfo(PatientTablePB table) {
        List<PatientBasicsPB> pendingPatients = table.getBasicsList();

        // 查找对应的48h内出科记录
        Map<String/*hisMrn*/, Pair<Long/*pid*/, LocalDateTime/*dischargeTime*/>> pendingAdmissionTable = new HashMap<>();
        List<String> hisMrns = pendingPatients.stream().map(PatientBasicsPB::getHisMrn).toList();
        List<Integer> dischargedStatuses = List.of(PENDING_DISCHARGED_VAL, DISCHARGED_VAL);
        for (PatientRecord patient : patientRecordRepository.findByHisMrnInAndAdmissionStatusIn(hisMrns, dischargedStatuses)) {
            LocalDateTime dischargeTime = patient.getDischargeTime();

            Pair<Long/*pid*/, LocalDateTime/*dischargeTime*/> pair = pendingAdmissionTable.get(patient.getHisMrn());
            if (pair != null) {
                // 如果已经存在，则比较时间，保留最新的
                if (dischargeTime.isAfter(pair.getSecond())) {
                    pendingAdmissionTable.put(patient.getHisMrn(), new Pair<>(patient.getId(), dischargeTime));
                }
            } else {
                // 如果不存在，则直接添加
                pendingAdmissionTable.put(patient.getHisMrn(), new Pair<>(patient.getId(), dischargeTime));
            }
        }

        // 填充结果
        List<PatientBasicsPB> newPendingPatients = new ArrayList<>();
        for (PatientBasicsPB patient : pendingPatients) {
            String hisMrn = patient.getHisMrn();
            LocalDateTime admissionTime = TimeUtils.fromIso8601String(patient.getAdmissionTime(), "UTC");

            // 判断是否符合合并的要求
            Pair<Long, LocalDateTime> pair = pendingAdmissionTable.get(hisMrn);
            LocalDateTime dischargeTime = pair != null ? pair.getSecond() : null;
            boolean readmitEligible = admissionTime != null && dischargeTime != null && 
                    dischargeTime.isBefore(admissionTime) &&
                    !dischargeTime.plusHours(GRACE_PERIOD_HOURS_TO_READMIT).isBefore(admissionTime);
            if (readmitEligible) {
                PatientBasicsPB.Builder builder = patient.toBuilder();
                builder.setLastDischargeTime(TimeUtils.toIso8601String(dischargeTime, ZONE_ID));
                builder.setLastPid(pair.getFirst());
                newPendingPatients.add(builder.build());
            } else {
                newPendingPatients.add(patient);
            }
        }
        return table.toBuilder().clearBasics().addAllBasics(newPendingPatients).build();
    }

    private String floatToStr(Float f) {
        return String.format("%.2f", f);
    }

    private final String ZONE_ID;
    private final Integer PENDING_ADMISSION_VAL;
    private final Integer IN_ICU_VAL;
    private final Integer PENDING_DISCHARGED_VAL;
    private final Integer DISCHARGED_VAL;
    private final Integer DISCHARGE_TYPE_TRANSFER_ID;
    private final Integer DISCHARGE_TYPE_DEATH_ID;
    private final Integer DISCHARGE_TYPE_EXIT_HOSPITAL_ID;
    private final Integer GRACE_PERIOD_HOURS_TO_READMIT;

    private ConfigProtoService protoService;
    private List<String> statusCodeMsgs;
    private ConfigShiftUtils shiftUtils;
    private UserService userService;
    private CertificateService certService;
    private MonitoringConfig monitoringConfig;

    private PatientEnumsV2 enumsV2;
    private PatientConfig patientConfig;
    private PatientSyncService patientSyncService;
    private PatientReadmissionService patientReadmissionService;
    private PatientDeviceService patientDeviceService;
    private PatientRecordRepository patientRecordRepository;
    private BedConfigRepository bedConfigRepository;
    private DiagnosisHistoryRepository diagnosisHistoryRepository;
    private PatientDeviceRepository patientDeviceRepository;
    private ReadmissionHistoryRepository readmissionHistoryRepository;
    private PatientSettingsRepository patientSettingsRepository;
    private SepsisAndSepticShockBundleService sepsisAndSepticShockBundleService;
}
