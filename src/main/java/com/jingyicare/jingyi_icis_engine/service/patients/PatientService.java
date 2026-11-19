package com.jingyicare.jingyi_icis_engine.service.patients;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.Message;
import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSepticShock.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientAdmissionService;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientDiagnosisHistoryService;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientDiagnosisHistoryService.AddDiagnosisResult;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientDiagnosisHistoryService.DiagnosisHistoryResult;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientDischargeService;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientEditService;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientRecordMapper;
import com.jingyicare.jingyi_icis_engine.service.patients.write.PatientSyncService;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingsStore;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.therapies.SepsisAndSepticShockBundleService;
import com.jingyicare.jingyi_icis_engine.service.users.*;
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
        ConfigProtoService protoService, PatientEnums patientEnums, ConfigShiftUtils shiftUtils,
        SettingsStore settingsStore,
        PatientAdmissionService admissionService, PatientDischargeService dischargeService,
        PatientEditService editService, PatientDiagnosisHistoryService diagnosisHistoryService,
        PatientSyncService patientSyncService, PatientRecordMapper patientRecordMapper,
        UserQuery userQuery, PatientRecordRepository patientRecordRepository,
        PatientReader patientReader, PatientSettingsRepository patientSettingsRepository,
        SepsisAndSepticShockBundleService sepsisAndSepticShockBundleService
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.GRACE_PERIOD_HOURS_TO_READMIT =
            protoService.getConfig().getPatient().getGracePeriodHoursToReadmit();
        this.protoService = protoService;
        this.settingsStore = settingsStore;
        this.patientEnums = patientEnums;
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.shiftUtils = shiftUtils;
        this.admissionService = admissionService;
        this.dischargeService = dischargeService;
        this.editService = editService;
        this.diagnosisHistoryService = diagnosisHistoryService;
        this.patientSyncService = patientSyncService;
        this.patientRecordMapper = patientRecordMapper;
        this.userQuery = userQuery;
        this.patientRecordRepository = patientRecordRepository;
        this.patientReader = patientReader;
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
        Map<String, BedConfig> bedConfigMap = patientReader.getBedConfigMap(deptId);

        // Simplify by calling a common method for each patient status
        addPatientTableForStatus(
            deptId, ADMISSION_STATUS_ADMITTED,
            settingsStore.getPatientsInIcuSettingsPB(deptId),
            bedConfigMap, builder);
        addPatientTableForStatus(
            deptId, ADMISSION_STATUS_PRE_ADMISSION,
            settingsStore.getPatientsPendingAdmissionSettingsPB(deptId),
            bedConfigMap, builder);
        addPatientTableForStatus(
            deptId, ADMISSION_STATUS_PRE_DISCHARGE,
            settingsStore.getPatientsPendingDischargedSettingsPB(deptId),
            bedConfigMap, builder);

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
        if (ADMISSION_STATUS_ADMITTED.equals(admissionStatus)) {
            builder.setInIcu(patientTableBuilder.build());
        } else if (ADMISSION_STATUS_PRE_ADMISSION.equals(admissionStatus)) {
            // 查找对应的48h内出科记录
            PatientTablePB pendingAdmissionTable = patientTableBuilder.build();
            pendingAdmissionTable = appendReadmissionInfo(pendingAdmissionTable);
            // 填充结果
            builder.setPendingAdmission(pendingAdmissionTable);
        } else if (ADMISSION_STATUS_PRE_DISCHARGE.equals(admissionStatus)) {
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
        final DisplayFieldSettingsPB settings =
            settingsStore.getPatientsDischargedSettingsPB(deptId);

        // 获取床位映射
        Map<String, BedConfig> bedConfigMap = patientReader.getBedConfigMap(req.getDeptId());

        // 设置表头
        addPatientTableHeader(settings, patientTableBuilder);

        LocalDateTime queryStart = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEnd = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");

        // 查找病人信息，生成表格内容
        List<PatientRecord> patients = patientRecordRepository
            .findAll(
                Specification.where(PatientRecordSpecification.hasDeptId(req.getDeptId()))
                    .and(PatientRecordSpecification.hasAdmissionStatus(ADMISSION_STATUS_DISCHARGED))
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

    public GenericResp newPatient(String json) {
        NewPatientReq req = parseRequest(json, NewPatientReq.newBuilder());
        if (req == null) return writeResponse(StatusCode.PARSE_JSON_FAILED);

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return writeResponse(StatusCode.ACCOUNT_NOT_FOUND);
        return writeResponse(admissionService.createManual(req, account));
    }

    public GenericResp admitPatient(String json) {
        AdmitPatientReq req = parseRequest(json, AdmitPatientReq.newBuilder());
        if (req == null) return writeResponse(StatusCode.PARSE_JSON_FAILED);

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return writeResponse(StatusCode.ACCOUNT_NOT_FOUND);
        return writeResponse(admissionService.admitPending(req, account));
    }

    public GenericResp dischargePatient(String json) {
        DischargePatientReq req = parseRequest(json, DischargePatientReq.newBuilder());
        if (req == null) return writeResponse(StatusCode.PARSE_JSON_FAILED);

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) return writeResponse(StatusCode.ACCOUNT_NOT_FOUND);
        return writeResponse(dischargeService.discharge(req, account));
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

        Map<String, BedConfig> bedConfigMap = patientReader.getBedConfigMap(patient.getDeptId());
        PatientRecordPB patientInfo = patientRecordMapper.toPatientRecordPB(patient, bedConfigMap);
        return GetPatientInfoV2Resp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setPatientInfo(patientInfo)
            .build();
    }

    public GenericResp updatePatientInfoV2(String json) {
        UpdatePatientInfoV2Req req = parseRequest(json, UpdatePatientInfoV2Req.newBuilder());
        if (req == null) return writeResponse(StatusCode.PARSE_JSON_FAILED);

        return writeResponse(editService.update(req.getPatientInfo()));
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

        CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
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
                .modifiedBy(account.autoAcctId())
                .build();
        } else {
            settings.setReportCfg(reportCfgBase64);
            settings.setModifiedAt(nowUtc);
            settings.setModifiedBy(account.autoAcctId());
        }
        patientSettingsRepository.save(settings);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GetDiagnosisHistoryResp getDiagnosisHistory(String json) {
        GetDiagnosisHistoryReq req = parseRequest(json, GetDiagnosisHistoryReq.newBuilder());
        if (req == null) {
            return GetDiagnosisHistoryResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        DiagnosisHistoryResult result = diagnosisHistoryService.getAndRefresh(req.getPid());
        return GetDiagnosisHistoryResp.newBuilder()
            .setRt(protoService.getReturnCode(result.status()))
            .addAllDiagnosisHistory(result.history())
            .build();
    }

    public AddDiagnosisHistoryResp addDiagnosisHistory(String json) {
        AddDiagnosisHistoryReq req = parseRequest(json, AddDiagnosisHistoryReq.newBuilder());
        if (req == null) {
            return AddDiagnosisHistoryResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        AddDiagnosisResult result = diagnosisHistoryService.add(req, userQuery.getCurrentAccount());
        return AddDiagnosisHistoryResp.newBuilder()
            .setRt(protoService.getReturnCode(result.status()))
            .setId(result.id())
            .build();
    }

    public GenericResp updateDiagnosisHistory(String json) {
        UpdateDiagnosisHistoryReq req = parseRequest(json, UpdateDiagnosisHistoryReq.newBuilder());
        if (req == null) return writeResponse(StatusCode.PARSE_JSON_FAILED);

        return writeResponse(diagnosisHistoryService.update(req, userQuery.getCurrentAccount()));
    }

    public GenericResp deleteDiagnosisHistory(String json) {
        DeleteDiagnosisHistoryReq req = parseRequest(json, DeleteDiagnosisHistoryReq.newBuilder());
        if (req == null) return writeResponse(StatusCode.PARSE_JSON_FAILED);

        return writeResponse(diagnosisHistoryService.delete(
            req.getId(), userQuery.getCurrentAccount()));
    }

    public SyncHisPatientResp syncHisPatient(String json) {
        SyncHisPatientReq req = parseRequest(json, SyncHisPatientReq.newBuilder());
        if (req == null) {
            return SyncHisPatientResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        int admittedCount = patientSyncService.syncPatientRecords(req.getForceSync());
        return SyncHisPatientResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setAdmittedHisPatientCount(admittedCount)
            .build();
    }

    private <T extends Message> T parseRequest(String json, Message.Builder builder) {
        try {
            return ProtoUtils.parseJsonToProto(json, builder);
        } catch (Exception e) {
            log.warn("解析患者请求失败，类型={}", builder.getDescriptorForType().getName(), e);
            return null;
        }
    }

    private GenericResp writeResponse(StatusCode status) {
        return ReturnCodeUtils.genericResponse(statusCodeMsgs, status);
    }

    @Transactional
    public PatientRecord getPatientRecordInIcu(Long patientId) {
        Optional<PatientRecord> optPatient = patientRecordRepository.findById(patientId);
        if (!optPatient.isPresent()) {
            log.error("Patient record not found for patientId: {}", patientId);
            return null;
        }

        PatientRecord patient = optPatient.get();
        if (patient.getAdmissionStatus() != ADMISSION_STATUS_ADMITTED) {
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
            deptId, ADMISSION_STATUS_ADMITTED);
        if (patients.isEmpty()) {
            log.warn("No patients found in ICU");
        }
        return patients;
    }

    @Transactional
    public List<PatientRecord> getPatientRecords(String deptId, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc) {
        // 查找条件
        // - 科室ID
        // - 科室状态
        //      - 在科 / 待入科 && 入科时间在queryEndUtc之前
        //      - 出科 && （出科时间大于等于查询开始时间，且入科时间小于等于查询结束时间）
        List<PatientRecord> inIcuPatients = patientRecordRepository.findByDeptIdAndAdmissionStatusInAndAdmissionTimeBefore(
            deptId, Arrays.asList(ADMISSION_STATUS_ADMITTED, ADMISSION_STATUS_PRE_ADMISSION),
            queryEndUtc
        );
        List<PatientRecord> dischargedPatients = patientRecordRepository.findDischargedPatients(
            deptId, queryEndUtc, queryStartUtc
        );

        // 合并两组病人记录并返回
        inIcuPatients.addAll(dischargedPatients);
        return inIcuPatients;
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
        final PatientRecord patientRecord = patientReader.findById(patientId).orElse(null);
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

    // Department-level display settings are persisted in the database and can
    // outlive a deployment. Keep settings written before the patient field
    // rename readable so an upgrade does not break patient-list queries.
    static String normalizePatientRecordColumnId(String colId) {
        return switch (colId) {
            case "admission_source_dept_name" -> "from_dept_name";
            case "admission_source_dept_id" -> "from_dept_id";
            case "discharged_type" -> "discharge_type";
            case "discharged_death_time" -> "death_time";
            case "discharged_hospital_exit_time" -> "his_discharge_time";
            case "discharged_dept_name" -> "to_dept_name";
            case "discharged_dept_id" -> "to_dept_id";
            default -> colId;
        };
    }

    private String getPatientRecordValue(
        PatientRecord patientRec, String colId, Map<String, BedConfig> bedConfigMap
    ) {
        colId = normalizePatientRecordColumnId(colId);

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
                return userQuery.getNameByAccountId(patientRec.getPrimaryCareDoctorId());

            case "admitting_doctor_id":
                if (patientRec.getAdmittingDoctorId() == null) return "";
                return patientRec.getAdmittingDoctorId();

            case "responsible_nurse_id":
                if (patientRec.getResponsibleNurseId() == null) return "";
                return userQuery.getNameByAutoId(patientRec.getResponsibleNurseId());

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
                return patientEnums.getAdmissionStatusName(patientRec.getAdmissionStatus());

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
                return patientEnums.getDischargeTypeName(patientRec.getDischargeType());

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

    private String getAdmissionTypeDisplay(PatientRecord patient) {
        List<String> admissionTypeNames = new ArrayList<>();
        for (Integer admissionType : PatientRecordMapper.admissionTypeValues(patient)) {
            String admissionTypeName = patientEnums.getAdmissionTypeName(admissionType);
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
        List<Integer> dischargedStatuses = List.of(
            ADMISSION_STATUS_PRE_DISCHARGE, ADMISSION_STATUS_DISCHARGED);
        for (PatientRecord patient : patientRecordRepository.findByHisMrnInAndAdmissionStatusIn(hisMrns, dischargedStatuses)) {
            LocalDateTime dischargeTime = patient.getDischargeTime();
            // 出科时间缺失时无法判断再入科窗口，也不能参与最新出科时间的比较。
            if (dischargeTime == null) continue;

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
    private final Integer GRACE_PERIOD_HOURS_TO_READMIT;
    private final List<String> statusCodeMsgs;

    private final ConfigProtoService protoService;
    private final SettingsStore settingsStore;
    private final ConfigShiftUtils shiftUtils;
    private final UserQuery userQuery;
    private final PatientEnums patientEnums;
    private final PatientRecordRepository patientRecordRepository;
    private final PatientReader patientReader;
    private final PatientSettingsRepository patientSettingsRepository;
    private final SepsisAndSepticShockBundleService sepsisAndSepticShockBundleService;
    private final PatientAdmissionService admissionService;
    private final PatientDischargeService dischargeService;
    private final PatientEditService editService;
    private final PatientDiagnosisHistoryService diagnosisHistoryService;
    private final PatientSyncService patientSyncService;
    private final PatientRecordMapper patientRecordMapper;
}
