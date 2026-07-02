package com.jingyicare.jingyi_icis_engine.service.therapies;

import java.time.LocalDateTime;
import java.time.Period;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.FieldMask;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.lis.*;
import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.therapies.SepsisAndSepticShockBundle;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisAlarm.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSepticShock.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.lis.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.therapies.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.qcs.IcuQcConfigService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class SepsisAndSepticShockBundleService {
    public static final String SEPTIC_SHOCK_ALARM_MESSAGE = "需要脓毒症与感染性休克集束治疗";

    private static final String SYSTEM_MODIFIED_BY = "system";
    private static final String FLUID_30MLKG_NOT_CALCULATED_NOTE = "未核算 30 ml/kg";
    private static final double LACTATE_FLUID_THRESHOLD_MMOL_L = 4.0;
    private static final double INITIAL_LACTATE_REMEASURE_THRESHOLD_MMOL_L = 2.0;
    private static final List<String> BP_PARAM_CODES = List.of(
        "nibp_s", "nibp_m", "nibp_d", "ibp_s", "ibp_m", "ibp_d");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    public SepsisAndSepticShockBundleService(
        ConfigProtoService protoService,
        IcuQcConfigService icuQcConfigService,
        UserService userService,
        PatientRecordRepository patientRecordRepo,
        DiagnosisHistoryRepository diagnosisHistoryRepo,
        PatientMonitoringRecordRepository patientMonitoringRecordRepo,
        PatientLisItemRepository patientLisItemRepo,
        PatientLisResultRepository patientLisResultRepo,
        LisParamRepository lisParamRepo,
        MedicationExecutionRecordRepository medicationExecutionRecordRepo,
        MedicationOrderGroupRepository medicationOrderGroupRepo,
        SepsisAndSepticShockBundleRepository bundleRepo
    ) {
        this.protoService = protoService;
        this.icuQcConfigService = icuQcConfigService;
        this.userService = userService;
        this.patientRecordRepo = patientRecordRepo;
        this.diagnosisHistoryRepo = diagnosisHistoryRepo;
        this.patientMonitoringRecordRepo = patientMonitoringRecordRepo;
        this.patientLisItemRepo = patientLisItemRepo;
        this.patientLisResultRepo = patientLisResultRepo;
        this.lisParamRepo = lisParamRepo;
        this.medicationExecutionRecordRepo = medicationExecutionRecordRepo;
        this.medicationOrderGroupRepo = medicationOrderGroupRepo;
        this.bundleRepo = bundleRepo;
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.zoneId = protoService.getConfig().getZoneId();
    }

    @Transactional
    public List<SepsisAndSepticShockCasePB> buildSepticShockCases(List<Long> pids) {
        if (pids == null || pids.isEmpty()) return List.of();

        List<Long> distinctPids = pids.stream()
            .filter(Objects::nonNull)
            .distinct()
            .collect(Collectors.toList());
        if (distinctPids.isEmpty()) return List.of();

        SepsisSepticShockDiagnosisConfigPB config = getDiagnosisConfig();
        Map<Long, PatientRecord> patientByPid = patientRecordRepo.findByIdIn(distinctPids).stream()
            .collect(Collectors.toMap(PatientRecord::getId, Function.identity(), (a, b) -> a));
        Map<Long, SepsisAndSepticShockBundle> entityByPid = bundleRepo.findByPidIn(distinctPids).stream()
            .collect(Collectors.toMap(SepsisAndSepticShockBundle::getPid, Function.identity(), (a, b) -> a));

        List<SepsisAndSepticShockCasePB> cases = new ArrayList<>();
        for (Long pid : distinctPids) {
            PatientRecord patient = patientByPid.get(pid);
            if (patient == null) continue;

            List<DiagnosisHistory> diagnoses = diagnosisHistoryRepo.findByPatientIdAndIsDeletedFalse(pid);
            EligibilityResult eligibility = evaluateBundleEligibility(patient, diagnoses, config);
            SepsisAndSepticShockInfoPB info = SepsisAndSepticShockInfoPB.newBuilder()
                .setPid(pid)
                .build();
            SepsisAndSepticShockBundlePB autoBundle = buildBaseBundle(pid, eligibility);

            if (eligibility.needBundle() && eligibility.t0() != null) {
                LocalDateTime windowEnd = eligibility.t0().plusHours(6);
                info = collectInfo(patient, eligibility.t0(), windowEnd, config);
                autoBundle = calculateAutoBundle(autoBundle, eligibility.t0(), info, config);
            }

            SepsisAndSepticShockBundle entity = entityByPid.get(pid);
            if (!autoBundle.getNeedBundle()) {
                if (entity != null && Boolean.TRUE.equals(entity.getNeedBundle())) {
                    entity.setNeedBundle(false);
                    entity.setModifiedAt(TimeUtils.getNowUtc());
                    entity.setModifiedBy(SYSTEM_MODIFIED_BY);
                    bundleRepo.save(entity);
                }
                cases.add(SepsisAndSepticShockCasePB.newBuilder()
                    .setBundle(mergeWithPersistedBundle(autoBundle, entity))
                    .setInfo(info)
                    .build());
                continue;
            }

            entity = persistAutoBundleIfNeeded(autoBundle, entity);
            cases.add(SepsisAndSepticShockCasePB.newBuilder()
                .setBundle(mergeWithPersistedBundle(autoBundle, entity))
                .setInfo(info)
                .build());
        }

        return cases;
    }

    @Transactional(readOnly = true)
    public List<AlarmPB> buildSepticShockAlarms(List<SepsisAndSepticShockCasePB> cases) {
        if (!getDiagnosisConfig().getEnableAlarm() || cases == null || cases.isEmpty()) return List.of();

        List<Long> pids = cases.stream()
            .filter(c -> c.hasBundle() && c.getBundle().getNeedBundle())
            .map(c -> c.getBundle().getPid())
            .filter(pid -> pid > 0)
            .distinct()
            .collect(Collectors.toList());
        if (pids.isEmpty()) return List.of();

        Map<Long, PatientRecord> patientByPid = patientRecordRepo.findByIdIn(pids).stream()
            .collect(Collectors.toMap(PatientRecord::getId, Function.identity(), (a, b) -> a));

        List<AlarmPB> alarms = new ArrayList<>();
        for (Long pid : pids) {
            PatientRecord patient = patientByPid.get(pid);
            if (patient == null) continue;
            alarms.add(AlarmPB.newBuilder()
                .setPid(pid)
                .setHisBedNumber(patient.getHisBedNumber())
                .setPatientName(patient.getIcuName())
                .setAlarmType(AlarmTypePB.ALARM_TYPE_SEPSIS_AND_SEPTIC_SHOCK)
                .setAlarmMessage(SEPTIC_SHOCK_ALARM_MESSAGE)
                .build());
        }
        return alarms;
    }

    @Transactional
    public GetSepticShockCaseResp getSepticShockCase(String reqJson) {
        final GetSepticShockCaseReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, GetSepticShockCaseReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse GetSepticShockCaseReq", e);
            return GetSepticShockCaseResp.newBuilder()
                .setRt(returnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        if (req.getPid() <= 0 || patientRecordRepo.findById(req.getPid()).isEmpty()) {
            return GetSepticShockCaseResp.newBuilder()
                .setRt(returnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        List<SepsisAndSepticShockCasePB> cases = buildSepticShockCases(List.of(req.getPid()));
        if (cases.isEmpty()) {
            return GetSepticShockCaseResp.newBuilder()
                .setRt(returnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        return GetSepticShockCaseResp.newBuilder()
            .setRt(returnCode(StatusCode.OK))
            .setSepticShockCase(cases.get(0))
            .build();
    }

    @Transactional
    public SaveSepticShockCaseResp saveSepticShockCase(String reqJson) {
        final SaveSepticShockCaseReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, SaveSepticShockCaseReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse SaveSepticShockCaseReq", e);
            return SaveSepticShockCaseResp.newBuilder()
                .setRt(returnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        if (!req.hasBundlePatch() || !req.getBundlePatch().hasBundle()) {
            return SaveSepticShockCaseResp.newBuilder()
                .setRt(returnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        SepsisAndSepticShockBundlePatchPB patch = req.getBundlePatch();
        long pid = patch.getBundle().getPid();
        if (pid <= 0 || patientRecordRepo.findById(pid).isEmpty()) {
            return SaveSepticShockCaseResp.newBuilder()
                .setRt(returnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return SaveSepticShockCaseResp.newBuilder()
                .setRt(returnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        SepsisAndSepticShockBundle entity = bundleRepo.findByPid(pid).orElseGet(() ->
            SepsisAndSepticShockBundle.builder()
                .pid(pid)
                .needBundle(false)
                .modifiedAt(TimeUtils.getNowUtc())
                .modifiedBy(account.getFirst())
                .build());
        applyPatch(entity, patch, account.getFirst());
        bundleRepo.save(entity);

        List<SepsisAndSepticShockCasePB> cases = buildSepticShockCases(List.of(pid));
        SaveSepticShockCaseResp.Builder resp = SaveSepticShockCaseResp.newBuilder()
            .setRt(returnCode(StatusCode.OK));
        if (!cases.isEmpty()) resp.setSepticShockCase(cases.get(0));
        return resp.build();
    }

    private SepsisSepticShockDiagnosisConfigPB getDiagnosisConfig() {
        if (diagnosisConfigOverrideForTest != null) return diagnosisConfigOverrideForTest;

        IcuQcConfigPB config = icuQcConfigService.getIcuQcConfigPb();
        if (config != null && config.hasSepsisSepticShockDiagnosis()) {
            return config.getSepsisSepticShockDiagnosis();
        }
        return SepsisSepticShockDiagnosisConfigPB.newBuilder()
            .setAgeLowerBound(18)
            .setGracePeriodHoursFromAdmissionTime(24)
            .addDiagnosisKeyword("感染性休克")
            .setHypotensionCriteria(HypotensionCriteriaPB.newBuilder()
                .setMbpThreshold(65)
                .setSbpThreshold(90)
                .build())
            .build();
    }

    public void setDiagnosisConfigOverrideForTest(SepsisSepticShockDiagnosisConfigPB diagnosisConfigOverrideForTest) {
        this.diagnosisConfigOverrideForTest = diagnosisConfigOverrideForTest;
    }

    private EligibilityResult evaluateBundleEligibility(
        PatientRecord patient,
        List<DiagnosisHistory> diagnoses,
        SepsisSepticShockDiagnosisConfigPB config
    ) {
        Long pid = patient.getId();
        if (patient.getIcuDateOfBirth() == null) {
            return EligibilityResult.no(pid, "生日缺失");
        }

        List<String> keywords = normalizeKeywords(config.getDiagnosisKeywordList());
        if (keywords.isEmpty()) return EligibilityResult.no(pid, "感染性休克诊断关键词为空");

        LocalDateTime admissionTime = patient.getAdmissionTime();
        LocalDateTime t0 = findT0FromDiagnosisHistory(diagnoses, admissionTime, config, keywords);
        String reason = "";
        if (t0 == null && (diagnoses == null || diagnoses.isEmpty()) && containsAny(patient.getDiagnosis(), keywords)) {
            t0 = admissionTime;
            reason = "patient_records.diagnosis fallback";
        }

        if (t0 == null) return EligibilityResult.no(pid, "未命中感染性休克诊断");

        LocalDateTime ageReference = admissionTime != null ? admissionTime : t0;
        Integer age = ageAt(patient.getIcuDateOfBirth(), ageReference);
        if (age == null || age < config.getAgeLowerBound()) {
            return EligibilityResult.no(pid, "年龄不满足纳入条件");
        }

        return new EligibilityResult(pid, t0, true, reason);
    }

    private LocalDateTime findT0FromDiagnosisHistory(
        List<DiagnosisHistory> diagnoses,
        LocalDateTime admissionTime,
        SepsisSepticShockDiagnosisConfigPB config,
        List<String> keywords
    ) {
        if (diagnoses == null || diagnoses.isEmpty()) return null;

        LocalDateTime lowerBound = admissionTime == null
            ? null
            : admissionTime.minusHours(Math.max(0, config.getDiagnosisLookbackHoursBeforeAdmissionTime()));
        LocalDateTime upperBound = admissionTime == null
            ? null
            : admissionTime.plusHours(Math.max(0, config.getGracePeriodHoursFromAdmissionTime()));

        return diagnoses.stream()
            .filter(d -> d.getDiagnosisTime() != null)
            .filter(d -> lowerBound == null || !d.getDiagnosisTime().isBefore(lowerBound))
            .filter(d -> upperBound == null || !d.getDiagnosisTime().isAfter(upperBound))
            .filter(d -> containsAny(d.getDiagnosis(), keywords))
            .map(DiagnosisHistory::getDiagnosisTime)
            .min(LocalDateTime::compareTo)
            .orElse(null);
    }

    private SepsisAndSepticShockBundlePB buildBaseBundle(Long pid, EligibilityResult eligibility) {
        SepsisAndSepticShockBundlePB.Builder builder = SepsisAndSepticShockBundlePB.newBuilder()
            .setPid(pid)
            .setNeedBundle(eligibility.needBundle());
        if (eligibility.t0() != null) {
            builder.setT0Iso8601(toIso(eligibility.t0()));
        }
        if (!StrUtils.isBlank(eligibility.reason())) {
            builder.setNoBundleReason(eligibility.reason());
        }
        return builder.build();
    }

    private SepsisAndSepticShockInfoPB collectInfo(
        PatientRecord patient,
        LocalDateTime t0,
        LocalDateTime windowEnd,
        SepsisSepticShockDiagnosisConfigPB config
    ) {
        SepsisAndSepticShockInfoPB.Builder builder = SepsisAndSepticShockInfoPB.newBuilder()
            .setPid(patient.getId())
            .setT0Iso8601(toIso(t0))
            .setWindowStartIso8601(toIso(t0))
            .setWindowEndIso8601(toIso(windowEnd))
            .addAllBloodPressure(collectBloodPressure(patient.getId(), t0, windowEnd))
            .addAllLactate(collectLactate(patient, t0, windowEnd, config))
            .addAllCultureLisItems(collectCultureLisItems(patient, t0, windowEnd, config));

        List<SepsisMedicationEvidencePB> allMeds = collectMedications(patient.getId(), t0, windowEnd);
        builder.addAllAllMedications(allMeds);
        builder.addAllAbxHistory(filterMedications(allMeds, config.getAbxBoardKeywordList()));
        builder.addAllFluidHistory(filterMedications(allMeds, config.getFluidKeywordList()));
        builder.addAllVasopressorHistory(filterMedications(allMeds, config.getVasopressorKeywordList()));
        return builder.build();
    }

    private List<SepsisBloodPressureTimePointPB> collectBloodPressure(Long pid, LocalDateTime start, LocalDateTime end) {
        List<PatientMonitoringRecord> records =
            patientMonitoringRecordRepo.findByPidAndParamCodesAndEffectiveTimeClosedRange(pid, BP_PARAM_CODES, start, end);
        Map<LocalDateTime, List<PatientMonitoringRecord>> byTime = records.stream()
            .filter(r -> r.getEffectiveTime() != null)
            .collect(Collectors.groupingBy(PatientMonitoringRecord::getEffectiveTime, TreeMap::new, Collectors.toList()));

        List<SepsisBloodPressureTimePointPB> result = new ArrayList<>();
        for (Map.Entry<LocalDateTime, List<PatientMonitoringRecord>> entry : byTime.entrySet()) {
            SepsisBloodPressureTimePointPB.Builder builder = SepsisBloodPressureTimePointPB.newBuilder()
                .setTimeIso8601(toIso(entry.getKey()));
            Map<String, Double> values = new HashMap<>();
            for (PatientMonitoringRecord record : entry.getValue()) {
                Double value = monitoringFloatValue(record);
                if (value == null) continue;
                values.put(record.getMonitoringParamCode(), value);
                builder.addParamValue(SepsisMonitoringParamValuePB.newBuilder()
                    .setParamCode(record.getMonitoringParamCode())
                    .setParamValue(value)
                    .setParamValueStr(record.getParamValueStr() == null ? "" : record.getParamValueStr())
                    .setUnit(record.getUnit() == null ? "" : record.getUnit())
                    .build());
            }

            String source = values.keySet().stream().anyMatch(k -> k.startsWith("ibp_")) ? "ibp"
                : values.keySet().stream().anyMatch(k -> k.startsWith("nibp_")) ? "nibp" : "";
            if (!source.isEmpty()) {
                Double sbp = values.get(source + "_s");
                Double dbp = values.get(source + "_d");
                Double map = values.get(source + "_m");
                boolean derived = false;
                if (map == null && sbp != null && dbp != null) {
                    map = sbp / 3.0 + dbp * 2.0 / 3.0;
                    derived = true;
                }
                builder.setSelectedSource(source);
                if (sbp != null) builder.setSelectedSbpMmhg(sbp);
                if (dbp != null) builder.setSelectedDbpMmhg(dbp);
                if (map != null) builder.setSelectedMapMmhg(map);
                builder.setSelectedMapDerived(derived);
            }
            result.add(builder.build());
        }
        return result;
    }

    private Double monitoringFloatValue(PatientMonitoringRecord record) {
        MonitoringValuePB valuePb = ProtoUtils.decodeMonitoringValue(record.getParamValue());
        if (valuePb == null) return null;
        return (double) valuePb.getValue().getFloatVal();
    }

    private List<SepsisLactateEvidencePB> collectLactate(
        PatientRecord patient,
        LocalDateTime start,
        LocalDateTime end,
        SepsisSepticShockDiagnosisConfigPB config
    ) {
        List<String> lacCodes = lactateExternalParamCodes(config);
        if (lacCodes.isEmpty() || StrUtils.isBlank(patient.getHisPatientId())) return List.of();

        List<PatientLisItem> items = patientLisItemRepo.findByHisPidAndAuthTimeBetween(
            patient.getHisPatientId(), start, end);
        if (items.isEmpty()) return List.of();

        Map<String, PatientLisItem> itemByReportId = items.stream()
            .collect(Collectors.toMap(PatientLisItem::getReportId, Function.identity(), (a, b) -> a));
        List<PatientLisResult> results = patientLisResultRepo
            .findByReportIdInAndExternalParamCodeInAndIsDeletedFalse(new ArrayList<>(itemByReportId.keySet()), lacCodes);

        return results.stream()
            .map(result -> lactateEvidence(patient.getId(), itemByReportId.get(result.getReportId()), result))
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(SepsisLactateEvidencePB::getAuthTimeIso8601))
            .collect(Collectors.toList());
    }

    private SepsisLactateEvidencePB lactateEvidence(Long pid, PatientLisItem item, PatientLisResult result) {
        if (item == null || item.getAuthTime() == null) return null;
        ParsedLactate parsed = parseLactate(result.getResultStr());
        SepsisLactateEvidencePB.Builder builder = SepsisLactateEvidencePB.newBuilder()
            .setPid(pid)
            .setAuthTimeIso8601(toIso(item.getAuthTime()))
            .setResultStr(result.getResultStr() == null ? "" : result.getResultStr())
            .setUnit(result.getUnit() == null ? "" : result.getUnit())
            .setReportId(result.getReportId() == null ? "" : result.getReportId())
            .setExternalParamCode(result.getExternalParamCode() == null ? "" : result.getExternalParamCode())
            .setLactateNumericParsed(parsed.parsed());
        if (parsed.parsed()) builder.setLactateMmolL(parsed.value());
        return builder.build();
    }

    private List<String> lactateExternalParamCodes(SepsisSepticShockDiagnosisConfigPB config) {
        List<String> codes = normalizeKeywords(config.getLacExternalParamCodeList());
        if (!codes.isEmpty()) return codes;
        return lisParamRepo.findByParamCode("lis_Lac")
            .map(LisParam::getExternalParamCode)
            .map(this::splitCommaList)
            .orElse(List.of());
    }

    private List<SepsisLisItemEvidencePB> collectCultureLisItems(
        PatientRecord patient,
        LocalDateTime start,
        LocalDateTime end,
        SepsisSepticShockDiagnosisConfigPB config
    ) {
        List<String> keywords = normalizeKeywords(config.getBloodCultureKeywordList());
        if (keywords.isEmpty() || StrUtils.isBlank(patient.getHisPatientId())) return List.of();

        return patientLisItemRepo.findByHisPidAndAuthTimeBetween(patient.getHisPatientId(), start, end).stream()
            .filter(item -> item.getAuthTime() != null && containsAny(item.getLisItemName(), keywords))
            .map(item -> {
                List<String> matched = matchedKeywords(item.getLisItemName(), keywords);
                return SepsisLisItemEvidencePB.newBuilder()
                    .setTimeIso8601(toIso(item.getAuthTime()))
                    .setLisItemName(item.getLisItemName() == null ? "" : item.getLisItemName())
                    .setReportId(item.getReportId() == null ? "" : item.getReportId())
                    .addAllMatchedKeyword(matched)
                    .build();
            })
            .sorted(Comparator.comparing(SepsisLisItemEvidencePB::getTimeIso8601))
            .collect(Collectors.toList());
    }

    private List<SepsisMedicationEvidencePB> collectMedications(Long pid, LocalDateTime start, LocalDateTime end) {
        List<MedicationExecutionRecord> records =
            medicationExecutionRecordRepo.findByPatientIdAndIsDeletedFalseAndStartTimeIsNotNullAndStartTimeBetween(
                pid, start, end);
        if (records.isEmpty()) return List.of();

        Map<Long, MedicationOrderGroup> groupById = medicationOrderGroupRepo.findByIds(records.stream()
                .map(MedicationExecutionRecord::getMedicationOrderGroupId)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toList()))
            .stream()
            .collect(Collectors.toMap(MedicationOrderGroup::getId, Function.identity(), (a, b) -> a));

        return records.stream()
            .filter(record -> record.getStartTime() != null)
            .map(record -> medicationEvidence(record, groupById.get(record.getMedicationOrderGroupId()), List.of()))
            .sorted(Comparator.comparing(SepsisMedicationEvidencePB::getStartedAtIso8601))
            .collect(Collectors.toList());
    }

    private SepsisMedicationEvidencePB medicationEvidence(
        MedicationExecutionRecord record,
        MedicationOrderGroup group,
        List<String> matchedKeywords
    ) {
        String displayName = medicationDisplayName(record, group);
        return SepsisMedicationEvidencePB.newBuilder()
            .setStartedAtIso8601(toIso(record.getStartTime()))
            .setMedicationDisplayName(displayName)
            .setMedicationOrderGroupId(record.getMedicationOrderGroupId() == null ? 0L : record.getMedicationOrderGroupId())
            .setMedicationExecutionRecordId(record.getId() == null ? 0L : record.getId())
            .addAllMatchedKeyword(matchedKeywords)
            .build();
    }

    private String medicationDisplayName(MedicationExecutionRecord record, MedicationOrderGroup group) {
        MedicationDosageGroupPB dosageGroup = ProtoUtils.decodeDosageGroup(record.getMedicationDosageGroup());
        if (dosageGroup == null && group != null) {
            dosageGroup = ProtoUtils.decodeDosageGroup(group.getMedicationDosageGroup());
        }
        if (dosageGroup == null) return "";
        if (!StrUtils.isBlank(dosageGroup.getDisplayName())) return dosageGroup.getDisplayName();
        return dosageGroup.getMdList().stream()
            .map(MedicationDosagePB::getName)
            .filter(name -> !StrUtils.isBlank(name))
            .collect(Collectors.joining(" + "));
    }

    private List<SepsisMedicationEvidencePB> filterMedications(
        List<SepsisMedicationEvidencePB> medications,
        List<String> keywords
    ) {
        List<String> normalized = normalizeKeywords(keywords);
        if (normalized.isEmpty()) return List.of();
        return medications.stream()
            .filter(med -> containsAny(med.getMedicationDisplayName(), normalized))
            .map(med -> med.toBuilder()
                .clearMatchedKeyword()
                .addAllMatchedKeyword(matchedKeywords(med.getMedicationDisplayName(), normalized))
                .build())
            .collect(Collectors.toList());
    }

    private SepsisAndSepticShockBundlePB calculateAutoBundle(
        SepsisAndSepticShockBundlePB baseBundle,
        LocalDateTime t0,
        SepsisAndSepticShockInfoPB info,
        SepsisSepticShockDiagnosisConfigPB config
    ) {
        SepsisAndSepticShockBundlePB.Builder builder = baseBundle.toBuilder();
        LocalDateTime h1End = t0.plusHours(1);
        LocalDateTime h3End = t0.plusHours(3);
        LocalDateTime h6End = t0.plusHours(6);

        List<SepsisLactateEvidencePB> h1Lactates = info.getLactateList().stream()
            .filter(l -> within(parseIso(l.getAuthTimeIso8601()), t0, h1End))
            .collect(Collectors.toList());
        if (!h1Lactates.isEmpty()) {
            builder.setH1LactateInitial(true)
                .setH1LactateInitialIso8601(h1Lactates.get(0).getAuthTimeIso8601());
        }

        List<SepsisLisItemEvidencePB> h1Cultures = info.getCultureLisItemsList().stream()
            .filter(c -> within(parseIso(c.getTimeIso8601()), t0, h1End))
            .collect(Collectors.toList());
        if (!h1Cultures.isEmpty()) {
            builder.setH1CultureBeforeAbx(true)
                .setH1CultureBeforeAbxIso8601(h1Cultures.get(0).getTimeIso8601());
        }

        List<SepsisMedicationEvidencePB> h1Abx = info.getAbxHistoryList().stream()
            .filter(m -> within(parseIso(m.getStartedAtIso8601()), t0, h1End))
            .collect(Collectors.toList());
        if (!h1Abx.isEmpty()) {
            builder.setH1AbxBroad(true)
                .setH1AbxBroadIso8601(h1Abx.get(0).getStartedAtIso8601());
        }

        boolean h1Done = builder.getH1LactateInitial()
            && builder.getH1CultureBeforeAbx()
            && builder.getH1AbxBroad();
        if (!h1Done) return builder.build();

        boolean hypotension3h = info.getBloodPressureList().stream()
            .filter(bp -> within(parseIso(bp.getTimeIso8601()), t0, h3End))
            .anyMatch(bp -> isHypotension(bp, config.getHypotensionCriteria()));
        boolean lactateAbnormal = info.getLactateList().stream()
            .filter(l -> within(parseIso(l.getAuthTimeIso8601()), t0, h3End))
            .anyMatch(l -> l.getLactateNumericParsed() && l.getLactateMmolL() >= LACTATE_FLUID_THRESHOLD_MMOL_L);
        boolean fluidQualified = hypotension3h || lactateAbnormal;
        builder.setFluidQualified(fluidQualified);
        if (fluidQualified) {
            List<SepsisMedicationEvidencePB> fluids = info.getFluidHistoryList().stream()
                .filter(m -> within(parseIso(m.getStartedAtIso8601()), t0, h3End))
                .collect(Collectors.toList());
            if (!fluids.isEmpty()) {
                builder.setFluid30Mlkg(true)
                    .setFluid30MlkgIso8601(fluids.get(0).getStartedAtIso8601())
                    .setFluid30MlkgNote(FLUID_30MLKG_NOT_CALCULATED_NOTE);
            }
        }

        if (fluidQualified && !builder.getFluid30Mlkg()) return builder.build();

        Optional<SepsisBloodPressureTimePointPB> lastBp = info.getBloodPressureList().stream()
            .filter(bp -> within(parseIso(bp.getTimeIso8601()), t0, h6End))
            .max(Comparator.comparing(SepsisBloodPressureTimePointPB::getTimeIso8601));
        boolean vasopressorQualified = lastBp
            .map(bp -> isHypotension(bp, config.getHypotensionCriteria()))
            .orElse(false);
        builder.setVasopressorQualified(vasopressorQualified);
        if (vasopressorQualified) {
            List<SepsisMedicationEvidencePB> vasopressors = info.getVasopressorHistoryList().stream()
                .filter(m -> within(parseIso(m.getStartedAtIso8601()), t0, h6End))
                .collect(Collectors.toList());
            if (!vasopressors.isEmpty()) {
                SepsisMedicationEvidencePB last = vasopressors.get(vasopressors.size() - 1);
                builder.setVasopressor(true)
                    .setVasopressorIso8601(last.getStartedAtIso8601());
            }
        }

        List<SepsisLactateEvidencePB> lactates6h = info.getLactateList().stream()
            .filter(l -> l.getLactateNumericParsed())
            .filter(l -> within(parseIso(l.getAuthTimeIso8601()), t0, h6End))
            .collect(Collectors.toList());
        boolean relactateQualified = !lactates6h.isEmpty()
            && lactates6h.get(0).getLactateMmolL() > INITIAL_LACTATE_REMEASURE_THRESHOLD_MMOL_L;
        builder.setRelactateQualified(relactateQualified);
        if (relactateQualified && lactates6h.size() >= 2) {
            SepsisLactateEvidencePB last = lactates6h.get(lactates6h.size() - 1);
            builder.setRelactate(true)
                .setRelactateIso8601(last.getAuthTimeIso8601());
        }

        return builder.build();
    }

    private SepsisAndSepticShockBundle persistAutoBundleIfNeeded(
        SepsisAndSepticShockBundlePB autoBundle,
        SepsisAndSepticShockBundle entity
    ) {
        if (entity != null && Boolean.FALSE.equals(entity.getNeedBundle())) return entity;

        boolean isNew = entity == null;
        if (isNew) {
            entity = SepsisAndSepticShockBundle.builder()
                .pid(autoBundle.getPid())
                .needBundle(true)
                .modifiedBy(SYSTEM_MODIFIED_BY)
                .modifiedAt(TimeUtils.getNowUtc())
                .build();
        }

        fillEntityFromAuto(entity, autoBundle);
        if (isNew || entity.getId() != null) {
            entity.setModifiedAt(TimeUtils.getNowUtc());
            if (StrUtils.isBlank(entity.getModifiedBy())) entity.setModifiedBy(SYSTEM_MODIFIED_BY);
            entity = bundleRepo.save(entity);
        }
        return entity;
    }

    private void fillEntityFromAuto(SepsisAndSepticShockBundle entity, SepsisAndSepticShockBundlePB autoBundle) {
        if (entity.getT0() == null) entity.setT0(parseIso(autoBundle.getT0Iso8601()));
        entity.setNeedBundle(autoBundle.getNeedBundle());
        if (entity.getH1LactateInitial() == null && autoBundle.getH1LactateInitial()) {
            entity.setH1LactateInitial(true);
            entity.setH1LactateInitialTime(parseIso(autoBundle.getH1LactateInitialIso8601()));
        }
        if (entity.getH1CultureBeforeAbx() == null && autoBundle.getH1CultureBeforeAbx()) {
            entity.setH1CultureBeforeAbx(true);
            entity.setH1CultureBeforeAbxTime(parseIso(autoBundle.getH1CultureBeforeAbxIso8601()));
        }
        if (entity.getH1AbxBroad() == null && autoBundle.getH1AbxBroad()) {
            entity.setH1AbxBroad(true);
            entity.setH1AbxBroadTime(parseIso(autoBundle.getH1AbxBroadIso8601()));
        }

        boolean h1Done = effectiveBool(entity.getH1LactateInitial(), autoBundle.getH1LactateInitial())
            && effectiveBool(entity.getH1CultureBeforeAbx(), autoBundle.getH1CultureBeforeAbx())
            && effectiveBool(entity.getH1AbxBroad(), autoBundle.getH1AbxBroad());
        if (h1Done) {
            if (entity.getFluidQualified() == null) entity.setFluidQualified(autoBundle.getFluidQualified());
            if (autoBundle.getFluidQualified() && entity.getFluid30mlkg() == null && autoBundle.getFluid30Mlkg()) {
                entity.setFluid30mlkg(true);
                entity.setFluid30mlkgTime(parseIso(autoBundle.getFluid30MlkgIso8601()));
                if (StrUtils.isBlank(entity.getFluid30mlkgNote())) {
                    entity.setFluid30mlkgNote(autoBundle.getFluid30MlkgNote());
                }
            }
        }

        boolean fluidQualified = entity.getFluidQualified() != null ? entity.getFluidQualified() : autoBundle.getFluidQualified();
        boolean fluidDone = entity.getFluid30mlkg() != null ? entity.getFluid30mlkg() : autoBundle.getFluid30Mlkg();
        if (h1Done && (!fluidQualified || fluidDone)) {
            if (entity.getVasopressorQualified() == null) entity.setVasopressorQualified(autoBundle.getVasopressorQualified());
            if (autoBundle.getVasopressorQualified() && entity.getVasopressor() == null && autoBundle.getVasopressor()) {
                entity.setVasopressor(true);
                entity.setVasopressorTime(parseIso(autoBundle.getVasopressorIso8601()));
            }
            if (entity.getRelactateQualified() == null) entity.setRelactateQualified(autoBundle.getRelactateQualified());
            if (autoBundle.getRelactateQualified() && entity.getRelactate() == null && autoBundle.getRelactate()) {
                entity.setRelactate(true);
                entity.setRelactateTime(parseIso(autoBundle.getRelactateIso8601()));
            }
        }
    }

    private SepsisAndSepticShockBundlePB mergeWithPersistedBundle(
        SepsisAndSepticShockBundlePB autoBundle,
        SepsisAndSepticShockBundle entity
    ) {
        if (entity == null) return autoBundle;
        SepsisAndSepticShockBundlePB.Builder builder = autoBundle.toBuilder();
        if (entity.getPid() != null) builder.setPid(entity.getPid());
        if (entity.getT0() != null) builder.setT0Iso8601(toIso(entity.getT0()));
        if (entity.getNeedBundle() != null) builder.setNeedBundle(entity.getNeedBundle());
        if (!StrUtils.isBlank(entity.getNoBundleReason())) builder.setNoBundleReason(entity.getNoBundleReason());
        if (entity.getH1LactateInitial() != null) builder.setH1LactateInitial(entity.getH1LactateInitial());
        if (entity.getH1LactateInitialTime() != null) builder.setH1LactateInitialIso8601(toIso(entity.getH1LactateInitialTime()));
        if (!StrUtils.isBlank(entity.getH1LactateInitialNote())) builder.setH1LactateInitialNote(entity.getH1LactateInitialNote());
        if (entity.getH1CultureBeforeAbx() != null) builder.setH1CultureBeforeAbx(entity.getH1CultureBeforeAbx());
        if (entity.getH1CultureBeforeAbxTime() != null) builder.setH1CultureBeforeAbxIso8601(toIso(entity.getH1CultureBeforeAbxTime()));
        if (!StrUtils.isBlank(entity.getH1CultureBeforeAbxNote())) builder.setH1CultureBeforeAbxNote(entity.getH1CultureBeforeAbxNote());
        if (entity.getH1AbxBroad() != null) builder.setH1AbxBroad(entity.getH1AbxBroad());
        if (entity.getH1AbxBroadTime() != null) builder.setH1AbxBroadIso8601(toIso(entity.getH1AbxBroadTime()));
        if (!StrUtils.isBlank(entity.getH1AbxBroadNote())) builder.setH1AbxBroadNote(entity.getH1AbxBroadNote());
        if (entity.getFluidQualified() != null) builder.setFluidQualified(entity.getFluidQualified());
        if (entity.getFluid30mlkg() != null) builder.setFluid30Mlkg(entity.getFluid30mlkg());
        if (entity.getFluid30mlkgTime() != null) builder.setFluid30MlkgIso8601(toIso(entity.getFluid30mlkgTime()));
        if (!StrUtils.isBlank(entity.getFluid30mlkgNote())) builder.setFluid30MlkgNote(entity.getFluid30mlkgNote());
        if (entity.getVasopressorQualified() != null) builder.setVasopressorQualified(entity.getVasopressorQualified());
        if (entity.getVasopressor() != null) builder.setVasopressor(entity.getVasopressor());
        if (entity.getVasopressorTime() != null) builder.setVasopressorIso8601(toIso(entity.getVasopressorTime()));
        if (!StrUtils.isBlank(entity.getVasopressorNote())) builder.setVasopressorNote(entity.getVasopressorNote());
        if (entity.getRelactateQualified() != null) builder.setRelactateQualified(entity.getRelactateQualified());
        if (entity.getRelactate() != null) builder.setRelactate(entity.getRelactate());
        if (entity.getRelactateTime() != null) builder.setRelactateIso8601(toIso(entity.getRelactateTime()));
        if (!StrUtils.isBlank(entity.getRelactateNote())) builder.setRelactateNote(entity.getRelactateNote());
        PerfusionReassessmentPB perfusion = ProtoUtils.decodePerfusionReassessmentPB(entity.getPerfusionReassessmentDetails());
        if (perfusion != null) builder.setPerfusionReassessmentDetails(perfusion);
        return builder.build();
    }

    private void applyPatch(SepsisAndSepticShockBundle entity, SepsisAndSepticShockBundlePatchPB patch, String accountId) {
        SepsisAndSepticShockBundlePB bundle = patch.getBundle();
        Set<String> paths = normalizedMaskPaths(patch.getUpdateMask());
        if (paths.contains("t0_iso8601") && !StrUtils.isBlank(bundle.getT0Iso8601())) {
            entity.setT0(parseIso(bundle.getT0Iso8601()));
        }
        if (paths.contains("need_bundle")) entity.setNeedBundle(bundle.getNeedBundle());
        if (paths.contains("no_bundle_reason")) entity.setNoBundleReason(bundle.getNoBundleReason());
        if (paths.contains("h1_lactate_initial")) entity.setH1LactateInitial(bundle.getH1LactateInitial());
        if (paths.contains("h1_lactate_initial_iso8601") && !StrUtils.isBlank(bundle.getH1LactateInitialIso8601())) {
            entity.setH1LactateInitialTime(parseIso(bundle.getH1LactateInitialIso8601()));
        }
        if (paths.contains("h1_lactate_initial_note")) entity.setH1LactateInitialNote(bundle.getH1LactateInitialNote());
        if (paths.contains("h1_culture_before_abx")) entity.setH1CultureBeforeAbx(bundle.getH1CultureBeforeAbx());
        if (paths.contains("h1_culture_before_abx_iso8601") && !StrUtils.isBlank(bundle.getH1CultureBeforeAbxIso8601())) {
            entity.setH1CultureBeforeAbxTime(parseIso(bundle.getH1CultureBeforeAbxIso8601()));
        }
        if (paths.contains("h1_culture_before_abx_note")) entity.setH1CultureBeforeAbxNote(bundle.getH1CultureBeforeAbxNote());
        if (paths.contains("h1_abx_broad")) entity.setH1AbxBroad(bundle.getH1AbxBroad());
        if (paths.contains("h1_abx_broad_iso8601") && !StrUtils.isBlank(bundle.getH1AbxBroadIso8601())) {
            entity.setH1AbxBroadTime(parseIso(bundle.getH1AbxBroadIso8601()));
        }
        if (paths.contains("h1_abx_broad_note")) entity.setH1AbxBroadNote(bundle.getH1AbxBroadNote());
        if (paths.contains("fluid_qualified")) entity.setFluidQualified(bundle.getFluidQualified());
        if (paths.contains("fluid_30mlkg")) entity.setFluid30mlkg(bundle.getFluid30Mlkg());
        if (paths.contains("fluid_30mlkg_iso8601") && !StrUtils.isBlank(bundle.getFluid30MlkgIso8601())) {
            entity.setFluid30mlkgTime(parseIso(bundle.getFluid30MlkgIso8601()));
        }
        if (paths.contains("fluid_30mlkg_note")) entity.setFluid30mlkgNote(bundle.getFluid30MlkgNote());
        if (paths.contains("vasopressor_qualified")) entity.setVasopressorQualified(bundle.getVasopressorQualified());
        if (paths.contains("vasopressor")) entity.setVasopressor(bundle.getVasopressor());
        if (paths.contains("vasopressor_iso8601") && !StrUtils.isBlank(bundle.getVasopressorIso8601())) {
            entity.setVasopressorTime(parseIso(bundle.getVasopressorIso8601()));
        }
        if (paths.contains("vasopressor_note")) entity.setVasopressorNote(bundle.getVasopressorNote());
        if (paths.contains("relactate_qualified")) entity.setRelactateQualified(bundle.getRelactateQualified());
        if (paths.contains("relactate")) entity.setRelactate(bundle.getRelactate());
        if (paths.contains("relactate_iso8601") && !StrUtils.isBlank(bundle.getRelactateIso8601())) {
            entity.setRelactateTime(parseIso(bundle.getRelactateIso8601()));
        }
        if (paths.contains("relactate_note")) entity.setRelactateNote(bundle.getRelactateNote());
        if (paths.contains("perfusion_reassessment_details") && bundle.hasPerfusionReassessmentDetails()) {
            entity.setPerfusionReassessmentDetails(ProtoUtils.encodePerfusionReassessmentPB(
                bundle.getPerfusionReassessmentDetails()));
        }
        if (entity.getNeedBundle() == null) entity.setNeedBundle(false);
        entity.setModifiedAt(TimeUtils.getNowUtc());
        entity.setModifiedBy(accountId);
    }

    private Set<String> normalizedMaskPaths(FieldMask mask) {
        return mask.getPathsList().stream()
            .map(this::toSnakeCase)
            .collect(Collectors.toSet());
    }

    private String toSnakeCase(String path) {
        if (path == null) return "";
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isUpperCase(c)) {
                if (i > 0) builder.append('_');
                builder.append(Character.toLowerCase(c));
            } else {
                builder.append(c);
            }
        }
        return builder.toString();
    }

    private boolean isHypotension(SepsisBloodPressureTimePointPB bp, HypotensionCriteriaPB criteria) {
        if (StrUtils.isBlank(bp.getSelectedSource())) return false;
        boolean mapLow = bp.getSelectedMapMmhg() > 0
            && criteria.getMbpThreshold() > 0
            && bp.getSelectedMapMmhg() < criteria.getMbpThreshold();
        boolean sbpLow = bp.getSelectedSbpMmhg() > 0
            && criteria.getSbpThreshold() > 0
            && bp.getSelectedSbpMmhg() < criteria.getSbpThreshold();
        return mapLow || sbpLow;
    }

    private ParsedLactate parseLactate(String resultStr) {
        if (StrUtils.isBlank(resultStr)) return new ParsedLactate(false, 0);
        Matcher matcher = NUMBER_PATTERN.matcher(resultStr);
        if (!matcher.find()) return new ParsedLactate(false, 0);
        try {
            return new ParsedLactate(true, Double.parseDouble(matcher.group()));
        } catch (NumberFormatException e) {
            return new ParsedLactate(false, 0);
        }
    }

    private boolean effectiveBool(Boolean persisted, boolean autoValue) {
        return persisted != null ? persisted : autoValue;
    }

    private Integer ageAt(LocalDateTime birth, LocalDateTime reference) {
        if (birth == null || reference == null || birth.isAfter(reference)) return null;
        return Period.between(birth.toLocalDate(), reference.toLocalDate()).getYears();
    }

    private List<String> normalizeKeywords(List<String> values) {
        if (values == null) return List.of();
        return values.stream()
            .filter(v -> !StrUtils.isBlank(v))
            .map(String::trim)
            .distinct()
            .collect(Collectors.toList());
    }

    private List<String> splitCommaList(String value) {
        if (StrUtils.isBlank(value)) return List.of();
        return Arrays.stream(value.split(","))
            .map(String::trim)
            .filter(s -> !s.isEmpty())
            .distinct()
            .collect(Collectors.toList());
    }

    private boolean containsAny(String value, List<String> keywords) {
        if (StrUtils.isBlank(value) || keywords == null || keywords.isEmpty()) return false;
        String lowerValue = value.toLowerCase(Locale.ROOT);
        return keywords.stream().anyMatch(keyword -> lowerValue.contains(keyword.toLowerCase(Locale.ROOT)));
    }

    private List<String> matchedKeywords(String value, List<String> keywords) {
        if (StrUtils.isBlank(value)) return List.of();
        String lowerValue = value.toLowerCase(Locale.ROOT);
        return keywords.stream()
            .filter(keyword -> lowerValue.contains(keyword.toLowerCase(Locale.ROOT)))
            .collect(Collectors.toList());
    }

    private boolean within(LocalDateTime time, LocalDateTime start, LocalDateTime end) {
        return time != null && !time.isBefore(start) && !time.isAfter(end);
    }

    private String toIso(LocalDateTime utc) {
        return TimeUtils.toIso8601String(utc, zoneId);
    }

    private LocalDateTime parseIso(String iso) {
        return TimeUtils.fromIso8601String(iso, "UTC");
    }

    private ReturnCode returnCode(StatusCode code) {
        return ReturnCodeUtils.getReturnCode(statusCodeMsgList, code);
    }

    private record EligibilityResult(Long pid, LocalDateTime t0, boolean needBundle, String reason) {
        static EligibilityResult no(Long pid, String reason) {
            return new EligibilityResult(pid, null, false, reason);
        }
    }

    private record ParsedLactate(boolean parsed, double value) {}

    private final ConfigProtoService protoService;
    private final IcuQcConfigService icuQcConfigService;
    private final UserService userService;
    private final PatientRecordRepository patientRecordRepo;
    private final DiagnosisHistoryRepository diagnosisHistoryRepo;
    private final PatientMonitoringRecordRepository patientMonitoringRecordRepo;
    private final PatientLisItemRepository patientLisItemRepo;
    private final PatientLisResultRepository patientLisResultRepo;
    private final LisParamRepository lisParamRepo;
    private final MedicationExecutionRecordRepository medicationExecutionRecordRepo;
    private final MedicationOrderGroupRepository medicationOrderGroupRepo;
    private final SepsisAndSepticShockBundleRepository bundleRepo;
    private final List<String> statusCodeMsgList;
    private final String zoneId;
    private volatile SepsisSepticShockDiagnosisConfigPB diagnosisConfigOverrideForTest;
}
