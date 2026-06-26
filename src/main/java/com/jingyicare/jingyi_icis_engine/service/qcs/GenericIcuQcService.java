package com.jingyicare.jingyi_icis_engine.service.qcs;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.scores.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class GenericIcuQcService {
    public GenericIcuQcService(
        @Autowired ConfigProtoService protoService,
        @Autowired IcuQcConfigService icuQcConfigService,
        @Autowired PatientService patientService,
        @Autowired PatientConfig patientConfig,
        @Autowired BedUtilizationCalc bedUtilizationCalc,
        @Autowired DepartmentRepository deptRepo,
        @Autowired DepartmentAccountRepository deptAcctRepo,
        @Autowired AccountRepository accountRepo,
        @Autowired RbacRoleRepository roleRepo,
        @Autowired BedCountRepository bedCountRepo,
        @Autowired ApacheIIScoreRepository apacheRepo,
        @Autowired VTECapriniScoreRepository vteCapriniRepo,
        @Autowired VTEPaduaScoreRepository vtePaduaRepo,
        @Autowired PatientScoreRepository patientScoreRepo
    ) {
        this.zoneId = protoService.getConfig().getZoneId();
        this.statusMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.qcConfig = icuQcConfigService.getIcuQcConfigPb();
        this.qcItemMap = qcConfig.getItemList().stream()
            .collect(Collectors.toMap(QcItemPB::getCode, item -> item, (a, b) -> a));
        this.patientService = patientService;
        this.patientConfig = patientConfig;
        this.bedUtilizationCalc = bedUtilizationCalc;
        this.deptRepo = deptRepo;
        this.deptAcctRepo = deptAcctRepo;
        this.accountRepo = accountRepo;
        this.roleRepo = roleRepo;
        this.bedCountRepo = bedCountRepo;
        this.apacheRepo = apacheRepo;
        this.vteCapriniRepo = vteCapriniRepo;
        this.vtePaduaRepo = vtePaduaRepo;
        this.patientScoreRepo = patientScoreRepo;
    }

    @Transactional(readOnly = true)
    public GetGenericIcuQcResp getGenericIcuQc(String reqJson) {
        final GetGenericIcuQcReq req;
        try {
            req = ProtoUtils.parseJsonToProto(reqJson, GetGenericIcuQcReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse getGenericIcuQc request json: {}", e.getMessage(), e);
            return errorResp(StatusCode.PARSE_JSON_FAILED);
        }

        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            return errorResp(StatusCode.INVALID_TIME_FORMAT);
        }
        if (!queryEndUtc.isAfter(queryStartUtc)) {
            return errorResp(StatusCode.INVALID_TIME_RANGE);
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (queryEndUtc.isAfter(nowUtc)) queryEndUtc = nowUtc;
        if (!queryEndUtc.isAfter(queryStartUtc)) {
            return errorResp(StatusCode.INVALID_TIME_RANGE);
        }

        DeptScope deptScope = resolveDeptScope(req.getDeptId());
        if (deptScope.errorStatus != null) return errorResp(deptScope.errorStatus);

        Set<String> requestedCodes = req.getItemCodeList().stream()
            .filter(code -> !StrUtils.isBlank(code))
            .collect(Collectors.toSet());
        List<PeriodSegment> monthSegments = splitByLocalMonth(queryStartUtc, queryEndUtc);
        Map<String, List<PatientRecord>> patientsByDept = loadPatientsByDept(
            deptScope.deptIds, queryStartUtc, queryEndUtc);
        List<PatientRecord> allPatients = patientsByDept.values().stream()
            .flatMap(Collection::stream)
            .sorted(Comparator.comparing(PatientRecord::getAdmissionTime, Comparator.nullsLast(Comparator.naturalOrder())))
            .toList();

        GetGenericIcuQcResp.Builder builder = GetGenericIcuQcResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK));

        if (shouldCalculate(requestedCodes, Consts.ICU_1_ICU_BED_UTILIZATION_RATE)) {
            builder.setBedUtilization(calculateBedUtilization(
                monthSegments, queryStartUtc, queryEndUtc, deptScope.deptIds, patientsByDept));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_2_ICU_DOCTOR_BED_RATIO)) {
            builder.setDoctorBedRatio(calculateStaffBedRatio(
                Consts.ICU_2_ICU_DOCTOR_BED_RATIO, monthSegments, queryStartUtc, queryEndUtc, deptScope.deptIds));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_3_ICU_NURSE_BED_RATIO)) {
            builder.setNurseBedRatio(calculateStaffBedRatio(
                Consts.ICU_3_ICU_NURSE_BED_RATIO, monthSegments, queryStartUtc, queryEndUtc, deptScope.deptIds));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE)) {
            builder.setApache2Over15AdmissionRate(calculateApache2(
                monthSegments, queryStartUtc, queryEndUtc, allPatients));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_5_SEPTIC_SHOCK_BUNDLE_COMPLETION_RATE)) {
            builder.setSepticShockBundleCompletionRate(slot(Consts.ICU_5_SEPTIC_SHOCK_BUNDLE_COMPLETION_RATE));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_6_PRE_ANTIBIOTIC_PATHOGEN_TEST_RATE)) {
            builder.setPreAntibioticPathogenTestRate(slot(Consts.ICU_6_PRE_ANTIBIOTIC_PATHOGEN_TEST_RATE));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_7_DVT_PREVENTION_RATE)) {
            builder.setDvtPreventionRate(calculateDvt(monthSegments, queryStartUtc, queryEndUtc, allPatients));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_8_ARDS_PRONE_POSITION_RATE)) {
            builder.setArdsPronePositionRate(slot(Consts.ICU_8_ARDS_PRONE_POSITION_RATE));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_9_PAIN_ASSESSMENT_RATE)) {
            builder.setPainAssessmentRate(calculatePainAssessment(
                qcConfig.getPainScoreGroupCodeList(),
                monthSegments, queryStartUtc, queryEndUtc, allPatients));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_10_SEDATION_ASSESSMENT_RATE)) {
            builder.setSedationAssessmentRate(calculateSedationAssessment(
                qcConfig.getSedationScoreGroupCodeList(),
                monthSegments, queryStartUtc, queryEndUtc, allPatients));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO)) {
            builder.setStandardizedMortalityRatio(calculateStandardizedMortality(
                monthSegments, queryStartUtc, queryEndUtc, allPatients));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_12_UNPLANNED_EXTUBATION_RATE)) {
            builder.setUnplannedExtubationRate(slot(Consts.ICU_12_UNPLANNED_EXTUBATION_RATE));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_13_REINTUBATION_WITHIN_48H_RATE)) {
            builder.setReintubationWithin48HRate(slot(Consts.ICU_13_REINTUBATION_WITHIN_48H_RATE));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE)) {
            builder.setUnplannedIcuAdmissionRate(calculateUnplannedIcuAdmission(
                monthSegments, queryStartUtc, queryEndUtc, allPatients));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE)) {
            builder.setIcuReadmissionWithin48HRate(calculateReadmissionWithin48h(
                monthSegments, queryStartUtc, queryEndUtc, allPatients));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_16_VAP_INCIDENCE_RATE)) {
            builder.setVapIncidenceRate(slot(Consts.ICU_16_VAP_INCIDENCE_RATE));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_17_CRBSI_INCIDENCE_RATE)) {
            builder.setCrbsiIncidenceRate(slot(Consts.ICU_17_CRBSI_INCIDENCE_RATE));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_18_BRAIN_INJURY_CONSCIOUSNESS_ASSESSMENT_RATE)) {
            builder.setBrainInjuryConsciousnessAssessmentRate(
                slot(Consts.ICU_18_BRAIN_INJURY_CONSCIOUSNESS_ASSESSMENT_RATE));
        }
        if (shouldCalculate(requestedCodes, Consts.ICU_19_ENTERAL_NUTRITION_WITHIN_48H_RATE)) {
            builder.setEnteralNutritionWithin48HRate(slot(Consts.ICU_19_ENTERAL_NUTRITION_WITHIN_48H_RATE));
        }

        return builder.build();
    }

    private boolean shouldCalculate(Set<String> requestedCodes, String code) {
        return requestedCodes.isEmpty() || requestedCodes.contains(code);
    }

    private GetGenericIcuQcResp errorResp(StatusCode statusCode) {
        return GetGenericIcuQcResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, statusCode))
            .build();
    }

    private DeptScope resolveDeptScope(String deptId) {
        if (StrUtils.isBlank(deptId)) return DeptScope.error(StatusCode.DEPARTMENT_NOT_FOUND);

        Set<String> configuredDeptIds = qcConfig.getStatsDeptIdList().stream()
            .filter(id -> !StrUtils.isBlank(id))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if ("0".equals(deptId)) {
            List<String> deptIds = configuredDeptIds.isEmpty()
                ? deptRepo.findByIsDeletedFalse().stream().map(Department::getDeptId).toList()
                : new ArrayList<>(configuredDeptIds);
            return DeptScope.ok(deptIds);
        }

        Department dept = deptRepo.findByDeptIdAndIsDeletedFalse(deptId).orElse(null);
        if (dept == null) return DeptScope.error(StatusCode.DEPARTMENT_NOT_FOUND);
        if (!configuredDeptIds.isEmpty() && !configuredDeptIds.contains(deptId)) {
            return DeptScope.error(StatusCode.DEPARTMENT_NOT_FOUND);
        }
        return DeptScope.ok(List.of(deptId));
    }

    private Map<String, List<PatientRecord>> loadPatientsByDept(
        List<String> deptIds, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        Map<String, List<PatientRecord>> patientsByDept = new LinkedHashMap<>();
        for (String deptId : deptIds) {
            List<PatientRecord> patients = patientService.getPatientRecords(deptId, queryStartUtc, queryEndUtc);
            patients.sort(Comparator.comparing(PatientRecord::getAdmissionTime, Comparator.nullsLast(Comparator.naturalOrder())));
            patientsByDept.put(deptId, patients);
        }
        return patientsByDept;
    }

    private List<PeriodSegment> splitByLocalMonth(LocalDateTime queryStartUtc, LocalDateTime queryEndUtc) {
        List<PeriodSegment> segments = new ArrayList<>();
        LocalDateTime queryStartLocal = TimeUtils.getLocalDateTimeFromUtc(queryStartUtc, zoneId);
        LocalDateTime queryEndLocal = TimeUtils.getLocalDateTimeFromUtc(queryEndUtc, zoneId);
        LocalDateTime currentLocal = queryStartLocal;
        while (currentLocal.isBefore(queryEndLocal)) {
            LocalDateTime nextMonthLocal = TimeUtils.truncateToMonthStart(currentLocal).plusMonths(1);
            LocalDateTime segmentEndLocal = nextMonthLocal.isBefore(queryEndLocal) ? nextMonthLocal : queryEndLocal;
            LocalDateTime segmentStartUtc = TimeUtils.getUtcFromLocalDateTime(currentLocal, zoneId);
            LocalDateTime segmentEndUtc = TimeUtils.getUtcFromLocalDateTime(segmentEndLocal, zoneId);
            segments.add(new PeriodSegment(segmentStartUtc, segmentEndUtc));
            currentLocal = segmentEndLocal;
        }
        return segments;
    }

    private IcuQcIdPB idFor(String code, boolean implemented) {
        QcItemPB item = qcItemMap.getOrDefault(code, QcItemPB.newBuilder().setCode(code).setName(code).build());
        int idx = 0;
        for (int i = 0; i < qcConfig.getItemCount(); i++) {
            if (Objects.equals(qcConfig.getItem(i).getCode(), code)) {
                idx = i + 1;
                break;
            }
        }
        return IcuQcIdPB.newBuilder()
            .setCode(code)
            .setSeqCode(idx > 0 ? String.format("ICU-QC-%02d", idx) : "")
            .setName(item.getName())
            .setDescription(StrUtils.isBlank(item.getDescription()) ? item.getName() : item.getDescription())
            .setCalcFormula(item.getCalcFormula())
            .setImplemented(implemented)
            .setValueKind(valueKindFor(code))
            .setDisplayUnit(displayUnitFor(code))
            .build();
    }

    private IcuQcValueKindPB valueKindFor(String code) {
        if (Objects.equals(code, Consts.ICU_2_ICU_DOCTOR_BED_RATIO)
            || Objects.equals(code, Consts.ICU_3_ICU_NURSE_BED_RATIO)) {
            return IcuQcValueKindPB.ICU_QC_VALUE_KIND_RATIO;
        }
        if (Objects.equals(code, Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO)) {
            return IcuQcValueKindPB.ICU_QC_VALUE_KIND_INDEX;
        }
        if (Objects.equals(code, Consts.ICU_16_VAP_INCIDENCE_RATE)
            || Objects.equals(code, Consts.ICU_17_CRBSI_INCIDENCE_RATE)) {
            return IcuQcValueKindPB.ICU_QC_VALUE_KIND_PER_MILLE;
        }
        return IcuQcValueKindPB.ICU_QC_VALUE_KIND_PERCENT;
    }

    private String displayUnitFor(String code) {
        return switch (valueKindFor(code)) {
            case ICU_QC_VALUE_KIND_PERCENT -> "%";
            case ICU_QC_VALUE_KIND_PER_MILLE -> "‰";
            default -> "";
        };
    }

    private IcuQcGenericPB slot(String code) {
        return IcuQcGenericPB.newBuilder()
            .setId(idFor(code, false))
            .setTotalItem(IcuQcMetricItemPB.newBuilder().build())
            .setSlotNote("待实现")
            .build();
    }

    private IcuQcBedUtilizationPB calculateBedUtilization(
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<String> deptIds,
        Map<String, List<PatientRecord>> patientsByDept
    ) {
        IcuQcBedUtilizationPB.Builder builder = IcuQcBedUtilizationPB.newBuilder()
            .setId(idFor(Consts.ICU_1_ICU_BED_UTILIZATION_RATE, true));
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateBedItem(segment.startUtc, segment.endUtc, deptIds, patientsByDept));
        }
        builder.setTotalItem(calculateBedItem(queryStartUtc, queryEndUtc, deptIds, patientsByDept));
        return builder.build();
    }

    private IcuQcBedUtilizationItemPB calculateBedItem(
        LocalDateTime statsStartUtc,
        LocalDateTime statsEndUtc,
        List<String> deptIds,
        Map<String, List<PatientRecord>> patientsByDept
    ) {
        IcuQcBedUtilizationItemPB.Builder builder = IcuQcBedUtilizationItemPB.newBuilder()
            .setStatsStartIso8601(TimeUtils.toIso8601String(statsStartUtc, zoneId))
            .setStatsEndIso8601(TimeUtils.toIso8601String(statsEndUtc, zoneId));

        double numerator = 0;
        double denominator = 0;
        IcuQcBedSharedDayModePB bedSharedDayMode = qcConfig.getBedUtilization().getSharedDayMode();
        for (String deptId : deptIds) {
            List<QcICUBedUtilizationRatioPB> rates = bedUtilizationCalc.calcRates(
                deptId,
                patientsByDept.getOrDefault(deptId, Collections.emptyList()),
                statsStartUtc,
                statsEndUtc,
                zoneId,
                bedSharedDayMode
            );
            for (QcICUBedUtilizationRatioPB rate : rates) {
                numerator += rate.getUsedBedDays();
                denominator += rate.getTotalBedDays();
                for (QcICUBedUsagePB usage : rate.getUsageList()) {
                    builder.addUsage(IcuQcBedUsagePB.newBuilder()
                        .setDisplayBedNumber(usage.getDisplayBedNumber())
                        .setPatientId(usage.getPatientId())
                        .setPatientName(usage.getPatientIcuName())
                        .setStartTimeIso8601(usage.getStartDateIso8601())
                        .setEndTimeIso8601(usage.getEndDateIso8601())
                        .setBedDays(usage.getBedDays())
                        .setException(usage.getException())
                        .build());
                }
                for (QcICUBedAvailablePB available : rate.getAvailableList()) {
                    builder.addAvailable(IcuQcBedAvailablePB.newBuilder()
                        .setStartTimeIso8601(available.getStartDateIso8601())
                        .setEndTimeIso8601(available.getEndDateIso8601())
                        .setBedCount(available.getBedCount())
                        .setBedDays(available.getBedDays())
                        .build());
                }
            }
        }
        builder.setNumerator(numerator)
            .setDenominator(denominator)
            .setRatio(denominator == 0 ? 0 : numerator / denominator);
        return builder.build();
    }

    private IcuQcStaffBedRatioPB calculateStaffBedRatio(
        String code,
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<String> deptIds
    ) {
        IcuQcStaffBedRatioPB.Builder builder = IcuQcStaffBedRatioPB.newBuilder()
            .setId(idFor(code, true));
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateStaffBedRatioItem(code, segment.startUtc, segment.endUtc, deptIds));
        }
        builder.setTotalItem(calculateStaffBedRatioItem(code, queryStartUtc, queryEndUtc, deptIds));
        return builder.build();
    }

    private IcuQcStaffBedRatioItemPB calculateStaffBedRatioItem(
        String code, LocalDateTime statsStartUtc, LocalDateTime statsEndUtc, List<String> deptIds
    ) {
        IcuQcStaffBedRatioItemPB.Builder builder = IcuQcStaffBedRatioItemPB.newBuilder()
            .setStatsStartIso8601(TimeUtils.toIso8601String(statsStartUtc, zoneId))
            .setStatsEndIso8601(TimeUtils.toIso8601String(statsEndUtc, zoneId));
        List<Integer> roleIds = Objects.equals(code, Consts.ICU_2_ICU_DOCTOR_BED_RATIO)
            ? qcConfig.getDoctorRoleIdList()
            : qcConfig.getNurseRoleIdList();
        if (roleIds.isEmpty()) return builder.build();

        Map<Integer, String> roleMap = roleRepo.findAll().stream()
            .collect(Collectors.toMap(RbacRole::getId, RbacRole::getName, (a, b) -> a));
        List<DepartmentAccount> activeDeptAccts = new ArrayList<>();
        int bedCount = 0;
        for (String deptId : deptIds) {
            bedCount += bedCountAt(deptId, statsEndUtc);
            for (DepartmentAccount deptAcct : deptAcctRepo.findByDeptIdAndPrimaryRoleIdIn(deptId, roleIds)) {
                if (deptAcct.getStartDate() == null) continue;
                LocalDateTime endDate = deptAcct.getDeletedAt();
                boolean active = deptAcct.getStartDate().isBefore(statsEndUtc)
                    && (endDate == null || !endDate.isBefore(statsEndUtc));
                if (active) activeDeptAccts.add(deptAcct);
            }
        }

        Map<Long, String> accountNameMap = accountRepo.findByIdIn(activeDeptAccts.stream()
                .map(DepartmentAccount::getEmployeeId)
                .filter(Objects::nonNull)
                .distinct()
                .toList())
            .stream()
            .collect(Collectors.toMap(Account::getId, Account::getName, (a, b) -> a));

        activeDeptAccts.sort(Comparator
            .comparing(DepartmentAccount::getDeptId, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(DepartmentAccount::getAccountId, Comparator.nullsLast(Comparator.naturalOrder())));
        for (DepartmentAccount deptAcct : activeDeptAccts) {
            builder.addStaff(IcuQcStaffRoleItemPB.newBuilder()
                .setEmployeeId(deptAcct.getEmployeeId() == null ? 0 : deptAcct.getEmployeeId())
                .setAccountId(nullToEmpty(deptAcct.getAccountId()))
                .setAccountName(accountNameMap.getOrDefault(deptAcct.getEmployeeId(), ""))
                .setDeptId(nullToEmpty(deptAcct.getDeptId()))
                .setPrimaryRoleName(roleMap.getOrDefault(deptAcct.getPrimaryRoleId(), ""))
                .setStartTimeIso8601(TimeUtils.toIso8601String(deptAcct.getStartDate(), zoneId))
                .setEndTimeIso8601(TimeUtils.toIso8601String(deptAcct.getDeletedAt(), zoneId))
                .build());
        }

        double numerator = activeDeptAccts.size();
        double denominator = bedCount;
        builder.setBedCount(bedCount)
            .setNumerator(numerator)
            .setDenominator(denominator)
            .setRatio(denominator == 0 ? 0 : numerator / denominator);
        return builder.build();
    }

    private int bedCountAt(String deptId, LocalDateTime statsEndUtc) {
        int bedCount = 0;
        for (BedCount change : bedCountRepo.findByDeptIdAndIsDeletedFalse(deptId).stream()
            .sorted(Comparator.comparing(BedCount::getEffectiveTime))
            .toList()) {
            if (change.getEffectiveTime() != null && change.getEffectiveTime().isBefore(statsEndUtc)) {
                bedCount = change.getBedCount() == null ? 0 : change.getBedCount();
            }
        }
        return bedCount;
    }

    private IcuQcApache2Over15AdmissionRatePB calculateApache2(
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<PatientRecord> patients
    ) {
        Map<Long, List<ApacheIIScore>> apacheMap = apacheScoreMap(patients);
        IcuQcApache2Over15AdmissionRatePB.Builder builder = IcuQcApache2Over15AdmissionRatePB.newBuilder()
            .setId(idFor(Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE, true));
        IcuQcApache2Over15AdmissionRateConfigPB config = qcConfig.getApache2Over15AdmissionRate();
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateApache2Item(segment.startUtc, segment.endUtc, patients, apacheMap, config));
        }
        builder.setTotalItem(calculateApache2Item(queryStartUtc, queryEndUtc, patients, apacheMap, config));
        return builder.build();
    }

    private IcuQcPatientMetricItemPB calculateApache2Item(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc,
        List<PatientRecord> patients, Map<Long, List<ApacheIIScore>> apacheMap,
        IcuQcApache2Over15AdmissionRateConfigPB config
    ) {
        if (config.getCountByAdmissionTime()) {
            return calculateApache2ByAdmissionTimeItem(statsStartUtc, statsEndUtc, patients, apacheMap);
        }
        return calculateApache2ByFirstScoreTimeItem(statsStartUtc, statsEndUtc, patients, apacheMap);
    }

    private IcuQcPatientMetricItemPB calculateApache2ByFirstScoreTimeItem(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc,
        List<PatientRecord> patients, Map<Long, List<ApacheIIScore>> apacheMap
    ) {
        IcuQcPatientMetricItemPB.Builder builder = patientMetricItemBuilder(statsStartUtc, statsEndUtc);
        double numerator = 0;
        double denominator = 0;
        for (PatientRecord patient : patients) {
            ApacheIIScore score = firstValidApacheScoreInPeriod(
                patient, apacheMap.getOrDefault(patient.getId(), List.of()), statsStartUtc, statsEndUtc);
            if (score == null) continue;
            int apacheScore = score.getApacheIiScore();
            boolean isInNumerator = apacheScore >= 15;
            if (isInNumerator) numerator += 1;
            denominator += 1;
            builder.addDetail(patientDetailBuilder(patient)
                .setIsInDenominator(true)
                .setIsInNumerator(isInNumerator)
                .setShownInNumerator(isInNumerator)
                .setEvidenceType("apache2")
                .setEvidenceValue(apacheScore)
                .setApache2Score(apacheScore)
                .setNumeratorTimeIso8601(TimeUtils.toIso8601String(score.getScoreTime(), zoneId))
                .setDenominatorTimeIso8601(TimeUtils.toIso8601String(score.getScoreTime(), zoneId))
                .build());
        }
        return builder.setNumerator(numerator)
            .setDenominator(denominator)
            .setRatio(denominator == 0 ? 0 : numerator / denominator)
            .build();
    }

    private IcuQcPatientMetricItemPB calculateApache2ByAdmissionTimeItem(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc,
        List<PatientRecord> patients, Map<Long, List<ApacheIIScore>> apacheMap
    ) {
        IcuQcPatientMetricItemPB.Builder builder = patientMetricItemBuilder(statsStartUtc, statsEndUtc);
        double numerator = 0;
        double denominator = 0;
        for (PatientRecord patient : patients) {
            if (!admittedInPeriod(patient, statsStartUtc, statsEndUtc)) continue;
            ApacheIIScore score = firstValidApacheScoreBeforeEnd(
                patient, apacheMap.getOrDefault(patient.getId(), List.of()), statsEndUtc);
            int apacheScore = score == null ? 0 : score.getApacheIiScore();
            boolean isInNumerator = apacheScore >= 15;
            if (isInNumerator) numerator += 1;
            denominator += 1;
            builder.addDetail(patientDetailBuilder(patient)
                .setIsInDenominator(true)
                .setIsInNumerator(isInNumerator)
                .setShownInNumerator(isInNumerator)
                .setEvidenceType("apache2")
                .setEvidenceValue(apacheScore)
                .setApache2Score(apacheScore)
                .setNumeratorTimeIso8601(TimeUtils.toIso8601String(score == null ? null : score.getScoreTime(), zoneId))
                .setDenominatorTimeIso8601(TimeUtils.toIso8601String(patient.getAdmissionTime(), zoneId))
                .build());
        }
        return builder.setNumerator(numerator)
            .setDenominator(denominator)
            .setRatio(denominator == 0 ? 0 : numerator / denominator)
            .build();
    }

    private IcuQcDvtPreventionRatePB calculateDvt(
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<PatientRecord> patients
    ) {
        Map<Long, List<VTECapriniScore>> capriniMap = vteCapriniMap(patients);
        Map<Long, List<VTEPaduaScore>> paduaMap = vtePaduaMap(patients);
        IcuQcDvtPreventionRatePB.Builder builder = IcuQcDvtPreventionRatePB.newBuilder()
            .setId(idFor(Consts.ICU_7_DVT_PREVENTION_RATE, true));
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateDvtItem(segment.startUtc, segment.endUtc, patients, capriniMap, paduaMap));
        }
        builder.setTotalItem(calculateDvtItem(queryStartUtc, queryEndUtc, patients, capriniMap, paduaMap));
        return builder.build();
    }

    private IcuQcPatientMetricItemPB calculateDvtItem(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc,
        List<PatientRecord> patients,
        Map<Long, List<VTECapriniScore>> capriniMap,
        Map<Long, List<VTEPaduaScore>> paduaMap
    ) {
        IcuQcPatientMetricItemPB.Builder builder = patientMetricItemBuilder(statsStartUtc, statsEndUtc);
        double numerator = 0;
        double denominator = 0;
        for (PatientRecord patient : patients) {
            if (!overlapsPeriod(patient, statsStartUtc, statsEndUtc)) continue;
            DvtEvidence evidence = firstDvtEvidence(
                capriniMap.getOrDefault(patient.getId(), List.of()),
                paduaMap.getOrDefault(patient.getId(), List.of()),
                statsStartUtc,
                statsEndUtc);
            boolean isInNumerator = evidence != null;
            if (isInNumerator) numerator += 1;
            denominator += 1;
            builder.addDetail(patientDetailBuilder(patient)
                .setIsInDenominator(true)
                .setIsInNumerator(isInNumerator)
                .setShownInNumerator(isInNumerator)
                .setEvidenceType(evidence == null ? "" : evidence.type)
                .setNumeratorTimeIso8601(TimeUtils.toIso8601String(evidence == null ? null : evidence.time, zoneId))
                .build());
        }
        return builder.setNumerator(numerator)
            .setDenominator(denominator)
            .setRatio(denominator == 0 ? 0 : numerator / denominator)
            .build();
    }

    private IcuQcPainAssessmentRatePB calculatePainAssessment(
        List<String> scoreGroupCodes,
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<PatientRecord> patients
    ) {
        Map<Long, List<PatientScore>> scoreMap = patientScoreMap(patients, scoreGroupCodes);
        IcuQcPainAssessmentRatePB.Builder builder = IcuQcPainAssessmentRatePB.newBuilder()
            .setId(idFor(Consts.ICU_9_PAIN_ASSESSMENT_RATE, true));
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateScoreAssessmentItem(
                segment.startUtc, segment.endUtc, patients, scoreMap));
        }
        builder.setTotalItem(calculateScoreAssessmentItem(queryStartUtc, queryEndUtc, patients, scoreMap));
        return builder.build();
    }

    private IcuQcSedationAssessmentRatePB calculateSedationAssessment(
        List<String> scoreGroupCodes,
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<PatientRecord> patients
    ) {
        Map<Long, List<PatientScore>> scoreMap = patientScoreMap(patients, scoreGroupCodes);
        IcuQcSedationAssessmentRatePB.Builder builder = IcuQcSedationAssessmentRatePB.newBuilder()
            .setId(idFor(Consts.ICU_10_SEDATION_ASSESSMENT_RATE, true));
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateScoreAssessmentItem(
                segment.startUtc, segment.endUtc, patients, scoreMap));
        }
        builder.setTotalItem(calculateScoreAssessmentItem(queryStartUtc, queryEndUtc, patients, scoreMap));
        return builder.build();
    }

    private IcuQcPatientMetricItemPB calculateScoreAssessmentItem(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc,
        List<PatientRecord> patients, Map<Long, List<PatientScore>> scoreMap
    ) {
        IcuQcPatientMetricItemPB.Builder builder = patientMetricItemBuilder(statsStartUtc, statsEndUtc);
        double numerator = 0;
        double denominator = 0;
        for (PatientRecord patient : patients) {
            if (!overlapsPeriod(patient, statsStartUtc, statsEndUtc)) continue;
            PatientScore score = firstPatientScore(scoreMap.getOrDefault(patient.getId(), List.of()), statsStartUtc, statsEndUtc);
            boolean isInNumerator = score != null;
            if (isInNumerator) numerator += 1;
            denominator += 1;
            builder.addDetail(patientDetailBuilder(patient)
                .setIsInDenominator(true)
                .setIsInNumerator(isInNumerator)
                .setShownInNumerator(isInNumerator)
                .setEvidenceType(score == null ? "" : score.getScoreGroupCode())
                .setEvidenceText(score == null ? "" : score.getScoreStr())
                .setNumeratorTimeIso8601(TimeUtils.toIso8601String(score == null ? null : score.getEffectiveTime(), zoneId))
                .build());
        }
        return builder.setNumerator(numerator)
            .setDenominator(denominator)
            .setRatio(denominator == 0 ? 0 : numerator / denominator)
            .build();
    }

    private IcuQcStandardizedMortalityRatioPB calculateStandardizedMortality(
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<PatientRecord> patients
    ) {
        Map<Long, List<ApacheIIScore>> apacheMap = apacheScoreMap(patients);
        IcuQcStandardizedMortalityRatioPB.Builder builder = IcuQcStandardizedMortalityRatioPB.newBuilder()
            .setId(idFor(Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO, true));
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateMortalityItem(segment.startUtc, segment.endUtc, patients, apacheMap));
        }
        builder.setTotalItem(calculateMortalityItem(queryStartUtc, queryEndUtc, patients, apacheMap));
        return builder.build();
    }

    private IcuQcPatientMetricItemPB calculateMortalityItem(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc,
        List<PatientRecord> patients, Map<Long, List<ApacheIIScore>> apacheMap
    ) {
        IcuQcPatientMetricItemPB.Builder builder = patientMetricItemBuilder(statsStartUtc, statsEndUtc);
        double denominatorPatientCnt = 0;
        double deadCnt = 0;
        double predictedMortalitySum = 0;
        double predictedCnt = 0;
        for (PatientRecord patient : patients) {
            if (!overlapsPeriod(patient, statsStartUtc, statsEndUtc)) continue;
            denominatorPatientCnt += 1;
            boolean isDead = Objects.equals(patient.getDischargedType(), patientConfig.getDischargeTypeDeadId());
            if (isDead) deadCnt += 1;
            ApacheIIScore score = firstApacheScore(patient, apacheMap.getOrDefault(patient.getId(), List.of()), statsEndUtc);
            double predicted = score == null || score.getPredictedMortalityRate() == null
                ? 0
                : normalizeMortalityRate(score.getPredictedMortalityRate());
            if (predicted > 0) {
                predictedMortalitySum += predicted;
                predictedCnt += 1;
            }
            builder.addDetail(patientDetailBuilder(patient)
                .setIsInDenominator(predicted > 0)
                .setIsInNumerator(true)
                .setShownInNumerator(isDead)
                .setDenominatorTimeIso8601(TimeUtils.toIso8601String(score == null ? null : score.getScoreTime(), zoneId))
                .setPredictedMortalityRate(predicted)
                .setEvidenceType("apache2")
                .setEvidenceValue(predicted)
                .build());
        }
        double actualMortality = denominatorPatientCnt == 0 ? 0 : deadCnt / denominatorPatientCnt;
        double predictedMortality = predictedCnt == 0 ? 0 : predictedMortalitySum / predictedCnt;
        return builder.setNumerator(actualMortality)
            .setDenominator(predictedMortality)
            .setRatio(predictedMortality == 0 ? 0 : actualMortality / predictedMortality)
            .build();
    }

    private IcuQcUnplannedIcuAdmissionRatePB calculateUnplannedIcuAdmission(
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<PatientRecord> patients
    ) {
        IcuQcUnplannedIcuAdmissionRatePB.Builder builder = IcuQcUnplannedIcuAdmissionRatePB.newBuilder()
            .setId(idFor(Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE, true));
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateUnplannedIcuAdmissionItem(segment.startUtc, segment.endUtc, patients));
        }
        builder.setTotalItem(calculateUnplannedIcuAdmissionItem(queryStartUtc, queryEndUtc, patients));
        return builder.build();
    }

    private IcuQcPatientMetricItemPB calculateUnplannedIcuAdmissionItem(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc, List<PatientRecord> patients
    ) {
        IcuQcPatientMetricItemPB.Builder builder = patientMetricItemBuilder(statsStartUtc, statsEndUtc);
        double numerator = 0;
        double denominator = 0;
        for (PatientRecord patient : patients) {
            if (!admittedInPeriod(patient, statsStartUtc, statsEndUtc)) continue;
            if (!isSurgicalAdmission(patient)) continue;
            boolean isUnplanned = patient.getIsPlannedAdmission() != null && !patient.getIsPlannedAdmission();
            if (isUnplanned) numerator += 1;
            denominator += 1;
            builder.addDetail(patientDetailBuilder(patient)
                .setIsInDenominator(true)
                .setIsInNumerator(isUnplanned)
                .setShownInNumerator(isUnplanned)
                .setEvidenceType("surgical_admission")
                .setEvidenceText(patient.getIsPlannedAdmission() ? "planned" : "unplanned")
                .build());
        }
        return builder.setNumerator(numerator)
            .setDenominator(denominator)
            .setRatio(denominator == 0 ? 0 : numerator / denominator)
            .build();
    }

    private IcuQcReadmissionWithin48hRatePB calculateReadmissionWithin48h(
        List<PeriodSegment> monthSegments,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc,
        List<PatientRecord> patients
    ) {
        IcuQcReadmissionWithin48hRatePB.Builder builder = IcuQcReadmissionWithin48hRatePB.newBuilder()
            .setId(idFor(Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE, true));
        for (PeriodSegment segment : monthSegments) {
            builder.addMonthItem(calculateReadmissionWithin48hItem(segment.startUtc, segment.endUtc, patients));
        }
        builder.setTotalItem(calculateReadmissionWithin48hItem(queryStartUtc, queryEndUtc, patients));
        return builder.build();
    }

    private IcuQcPatientMetricItemPB calculateReadmissionWithin48hItem(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc, List<PatientRecord> patients
    ) {
        IcuQcPatientMetricItemPB.Builder builder = patientMetricItemBuilder(statsStartUtc, statsEndUtc);
        Map<String, List<PatientRecord>> recordsByMrn = patients.stream()
            .filter(patient -> !StrUtils.isBlank(patient.getHisMrn()))
            .collect(Collectors.groupingBy(PatientRecord::getHisMrn));
        double numerator = 0;
        double denominator = 0;
        for (List<PatientRecord> records : recordsByMrn.values()) {
            records.sort(Comparator.comparing(PatientRecord::getAdmissionTime, Comparator.nullsLast(Comparator.naturalOrder())));
            boolean countedDenominator = false;
            boolean countedNumerator = false;
            for (int i = 0; i < records.size(); i++) {
                PatientRecord record = records.get(i);
                if (!Objects.equals(record.getAdmissionStatus(), patientConfig.getDischargedId())) continue;
                LocalDateTime dischargeTime = record.getDischargeTime();
                if (dischargeTime == null || dischargeTime.isBefore(statsStartUtc) || !dischargeTime.isBefore(statsEndUtc)) {
                    continue;
                }
                LocalDateTime readmissionTime = i + 1 < records.size() ? records.get(i + 1).getAdmissionTime() : null;
                boolean in48h = readmissionTime != null
                    && !readmissionTime.isBefore(dischargeTime)
                    && readmissionTime.isBefore(dischargeTime.plusHours(48));

                boolean contributesDenominator = !countedDenominator;
                boolean contributesNumerator = in48h && !countedNumerator;
                if (contributesDenominator) {
                    denominator += 1;
                    countedDenominator = true;
                }
                if (contributesNumerator) {
                    numerator += 1;
                    countedNumerator = true;
                }
                builder.addDetail(patientDetailBuilder(record)
                    .setIsInDenominator(contributesDenominator)
                    .setIsInNumerator(contributesNumerator)
                    .setShownInNumerator(in48h)
                    .setReadmissionTimeIso8601(TimeUtils.toIso8601String(readmissionTime, zoneId))
                    .setNumeratorTimeIso8601(TimeUtils.toIso8601String(readmissionTime, zoneId))
                    .build());
            }
        }
        return builder.setNumerator(numerator)
            .setDenominator(denominator)
            .setRatio(denominator == 0 ? 0 : numerator / denominator)
            .build();
    }

    private IcuQcPatientMetricItemPB.Builder patientMetricItemBuilder(
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc
    ) {
        return IcuQcPatientMetricItemPB.newBuilder()
            .setStatsStartIso8601(TimeUtils.toIso8601String(statsStartUtc, zoneId))
            .setStatsEndIso8601(TimeUtils.toIso8601String(statsEndUtc, zoneId));
    }

    private IcuQcPatientMetricDetailPB.Builder patientDetailBuilder(PatientRecord patient) {
        return IcuQcPatientMetricDetailPB.newBuilder()
            .setPatient(toPatientPb(patient));
    }

    private IcuQcPatientPB toPatientPb(PatientRecord patient) {
        boolean isDead = Objects.equals(patient.getDischargedType(), patientConfig.getDischargeTypeDeadId());
        return IcuQcPatientPB.newBuilder()
            .setPatientId(patient.getId() == null ? 0 : patient.getId())
            .setHisMrn(patient.getHisMrn())
            .setPatientName(patient.getIcuName())
            .setAdmissionTimeIso8601(TimeUtils.toIso8601String(patient.getAdmissionTime(), zoneId))
            .setDischargeTimeIso8601(TimeUtils.toIso8601String(patient.getDischargeTime(), zoneId))
            .setAdmissionStatus(patient.getAdmissionStatus())
            .setAdmissionSourceDeptName(patient.getAdmissionSourceDeptName())
            .setAdmissionTypes(patient.getAdmissionTypes())
            .setIsDead(isDead)
            .setDeathTimeIso8601(TimeUtils.toIso8601String(patient.getDischargedDeathTime(), zoneId))
            .build();
    }

    private boolean admittedInPeriod(PatientRecord patient, LocalDateTime startUtc, LocalDateTime endUtc) {
        LocalDateTime admissionTime = patient.getAdmissionTime();
        return admissionTime != null && !admissionTime.isBefore(startUtc) && admissionTime.isBefore(endUtc);
    }

    private boolean overlapsPeriod(PatientRecord patient, LocalDateTime startUtc, LocalDateTime endUtc) {
        LocalDateTime admissionTime = patient.getAdmissionTime();
        if (admissionTime == null || !admissionTime.isBefore(endUtc)) return false;
        LocalDateTime dischargeTime = patient.getDischargeTime();
        return dischargeTime == null || dischargeTime.isAfter(startUtc);
    }

    private Map<Long, List<ApacheIIScore>> apacheScoreMap(List<PatientRecord> patients) {
        List<Long> pids = pids(patients);
        if (pids.isEmpty()) return Collections.emptyMap();
        return apacheRepo.findByPidInAndIsDeletedFalse(pids).stream()
            .collect(Collectors.groupingBy(ApacheIIScore::getPid));
    }

    private Map<Long, List<VTECapriniScore>> vteCapriniMap(List<PatientRecord> patients) {
        List<Long> pids = pids(patients);
        if (pids.isEmpty()) return Collections.emptyMap();
        return vteCapriniRepo.findByPidInAndIsDeletedFalse(pids).stream()
            .collect(Collectors.groupingBy(VTECapriniScore::getPid));
    }

    private Map<Long, List<VTEPaduaScore>> vtePaduaMap(List<PatientRecord> patients) {
        List<Long> pids = pids(patients);
        if (pids.isEmpty()) return Collections.emptyMap();
        return vtePaduaRepo.findByPidInAndIsDeletedFalse(pids).stream()
            .collect(Collectors.groupingBy(VTEPaduaScore::getPid));
    }

    private Map<Long, List<PatientScore>> patientScoreMap(List<PatientRecord> patients, List<String> scoreGroupCodes) {
        List<Long> pids = pids(patients);
        if (pids.isEmpty() || scoreGroupCodes.isEmpty()) return Collections.emptyMap();
        return patientScoreRepo.findByPidInAndScoreGroupCodeInAndIsDeletedFalse(pids, scoreGroupCodes).stream()
            .collect(Collectors.groupingBy(PatientScore::getPid));
    }

    private List<Long> pids(List<PatientRecord> patients) {
        return patients.stream()
            .map(PatientRecord::getId)
            .filter(Objects::nonNull)
            .distinct()
            .toList();
    }

    private ApacheIIScore firstApacheScore(
        PatientRecord patient, List<ApacheIIScore> scores, LocalDateTime statsEndUtc
    ) {
        LocalDateTime admissionTime = patient.getAdmissionTime();
        if (admissionTime == null) return null;
        return scores.stream()
            .filter(score -> score.getScoreTime() != null)
            .filter(score -> !score.getScoreTime().isBefore(admissionTime))
            .filter(score -> score.getScoreTime().isBefore(statsEndUtc))
            .min(Comparator.comparing(ApacheIIScore::getScoreTime))
            .orElse(null);
    }

    private ApacheIIScore firstValidApacheScoreBeforeEnd(
        PatientRecord patient, List<ApacheIIScore> scores, LocalDateTime statsEndUtc
    ) {
        LocalDateTime admissionTime = patient.getAdmissionTime();
        if (admissionTime == null || !admissionTime.isBefore(statsEndUtc)) return null;
        LocalDateTime dischargeTime = patient.getDischargeTime();
        return scores.stream()
            .filter(score -> score.getScoreTime() != null)
            .filter(score -> score.getApacheIiScore() != null)
            .filter(score -> !score.getScoreTime().isBefore(admissionTime))
            .filter(score -> score.getScoreTime().isBefore(statsEndUtc))
            .filter(score -> dischargeTime == null || score.getScoreTime().isBefore(dischargeTime))
            .min(Comparator.comparing(ApacheIIScore::getScoreTime))
            .orElse(null);
    }

    private ApacheIIScore firstValidApacheScoreInPeriod(
        PatientRecord patient, List<ApacheIIScore> scores,
        LocalDateTime statsStartUtc, LocalDateTime statsEndUtc
    ) {
        LocalDateTime admissionTime = patient.getAdmissionTime();
        if (admissionTime == null || !admissionTime.isBefore(statsEndUtc)) return null;
        LocalDateTime dischargeTime = patient.getDischargeTime();
        ApacheIIScore firstScore = scores.stream()
            .filter(score -> score.getScoreTime() != null)
            .filter(score -> score.getApacheIiScore() != null)
            .filter(score -> !score.getScoreTime().isBefore(admissionTime))
            .filter(score -> dischargeTime == null || score.getScoreTime().isBefore(dischargeTime))
            .min(Comparator.comparing(ApacheIIScore::getScoreTime))
            .orElse(null);
        if (firstScore == null) return null;
        LocalDateTime scoreTime = firstScore.getScoreTime();
        if (scoreTime.isBefore(statsStartUtc) || !scoreTime.isBefore(statsEndUtc)) return null;
        return firstScore;
    }

    private PatientScore firstPatientScore(
        List<PatientScore> scores, LocalDateTime statsStartUtc, LocalDateTime statsEndUtc
    ) {
        return scores.stream()
            .filter(score -> score.getEffectiveTime() != null)
            .filter(score -> !score.getEffectiveTime().isBefore(statsStartUtc))
            .filter(score -> score.getEffectiveTime().isBefore(statsEndUtc))
            .min(Comparator.comparing(PatientScore::getEffectiveTime))
            .orElse(null);
    }

    private DvtEvidence firstDvtEvidence(
        List<VTECapriniScore> capriniScores,
        List<VTEPaduaScore> paduaScores,
        LocalDateTime statsStartUtc,
        LocalDateTime statsEndUtc
    ) {
        List<DvtEvidence> evidences = new ArrayList<>();
        for (VTECapriniScore score : capriniScores) {
            if (score.getScoreTime() == null || score.getScoreTime().isBefore(statsStartUtc)
                || !score.getScoreTime().isBefore(statsEndUtc)) continue;
            if (hasPreventionExec(score.getPreventionAnticoagulantOnlyExec(),
                    score.getPreventionPhysicalOnlyExec(), score.getPreventionAnticoagulantPhysicalExec())) {
                evidences.add(new DvtEvidence("vte_caprini", score.getScoreTime()));
            }
        }
        for (VTEPaduaScore score : paduaScores) {
            if (score.getScoreTime() == null || score.getScoreTime().isBefore(statsStartUtc)
                || !score.getScoreTime().isBefore(statsEndUtc)) continue;
            if (hasPreventionExec(score.getPreventionAnticoagulantOnlyExec(),
                    score.getPreventionPhysicalOnlyExec(), score.getPreventionAnticoagulantPhysicalExec())) {
                evidences.add(new DvtEvidence("vte_padua", score.getScoreTime()));
            }
        }
        return evidences.stream()
            .min(Comparator.comparing(evidence -> evidence.time))
            .orElse(null);
    }

    private boolean hasPreventionExec(Boolean anticoagulantOnly, Boolean physicalOnly, Boolean anticoagulantPhysical) {
        return Boolean.TRUE.equals(anticoagulantOnly)
            || Boolean.TRUE.equals(physicalOnly)
            || Boolean.TRUE.equals(anticoagulantPhysical);
    }

    private boolean isSurgicalAdmission(PatientRecord patient) {
        if (hasAdmissionType(patient, patientConfig.getAdmissionTypeSurgeryId())) return true;
        String src = patient.getAdmissionSourceDeptName();
        return src != null && (
            src.contains("手术室") || src.contains("复苏室") || src.contains("麻醉恢复室") || src.contains("PACU")
        );
    }

    private boolean hasAdmissionType(PatientRecord patient, Integer admissionType) {
        if (admissionType == null) return false;
        List<Integer> admissionTypes = parseAdmissionTypes(patient.getAdmissionTypes());
        if (admissionTypes.isEmpty()) {
            return Objects.equals(patient.getAdmissionTypeRaw(), admissionType);
        }
        return admissionTypes.contains(admissionType);
    }

    private List<Integer> parseAdmissionTypes(String admissionTypes) {
        if (StrUtils.isBlank(admissionTypes)) return Collections.emptyList();
        List<Integer> parsed = new ArrayList<>();
        for (String token : admissionTypes.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) return Collections.emptyList();
            try {
                parsed.add(Integer.parseInt(trimmed));
            } catch (NumberFormatException e) {
                return Collections.emptyList();
            }
        }
        return parsed;
    }

    private double normalizeMortalityRate(double rate) {
        return rate > 1 ? rate / 100.0 : rate;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    @AllArgsConstructor
    private static class DeptScope {
        StatusCode errorStatus;
        List<String> deptIds;

        static DeptScope error(StatusCode statusCode) {
            return new DeptScope(statusCode, List.of());
        }

        static DeptScope ok(List<String> deptIds) {
            return new DeptScope(null, deptIds);
        }
    }

    @AllArgsConstructor
    private static class PeriodSegment {
        LocalDateTime startUtc;
        LocalDateTime endUtc;
    }

    @AllArgsConstructor
    private static class DvtEvidence {
        String type;
        LocalDateTime time;
    }

    private final String zoneId;
    private final List<String> statusMsgList;
    private final IcuQcConfigPB qcConfig;
    private final Map<String, QcItemPB> qcItemMap;

    private final PatientService patientService;
    private final PatientConfig patientConfig;
    private final BedUtilizationCalc bedUtilizationCalc;
    private final DepartmentRepository deptRepo;
    private final DepartmentAccountRepository deptAcctRepo;
    private final AccountRepository accountRepo;
    private final RbacRoleRepository roleRepo;
    private final BedCountRepository bedCountRepo;
    private final ApacheIIScoreRepository apacheRepo;
    private final VTECapriniScoreRepository vteCapriniRepo;
    private final VTEPaduaScoreRepository vtePaduaRepo;
    private final PatientScoreRepository patientScoreRepo;
}
