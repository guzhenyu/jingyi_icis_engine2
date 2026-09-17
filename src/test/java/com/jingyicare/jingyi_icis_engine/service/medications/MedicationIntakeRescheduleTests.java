package com.jingyicare.jingyi_icis_engine.service.medications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.annotation.DirtiesContext;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.PatientMonitoringService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

/** Exercises committed API calls so JPA identity sharing and JDBC balance writes match production. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:medication_reschedule_test")
@DirtiesContext
class MedicationIntakeRescheduleTests extends TestsBase {
    private static final String DEPT_ID = "med_reschedule";
    private static final AtomicLong NEXT_PID = new AtomicLong(95000);
    private static final LocalDateTime DAY = LocalDateTime.of(2025, 9, 17, 0, 0);

    @Autowired private ConfigProtoService protoService;
    @Autowired private MedicationService medService;
    @Autowired private PatientMonitoringService monitoringService;
    @Autowired private MonitoringConfig monitoringConfig;
    @Autowired private PatientRecordRepository patientRepo;
    @Autowired private AdministrationRouteRepository routeRepo;
    @Autowired private MedicationOrderGroupRepository groupRepo;
    @Autowired private MedicationExecutionRecordRepository recordRepo;
    @Autowired private MedicationExecutionRecordStatRepository statRepo;
    @Autowired private PatientMonitoringRecordRepository monitoringRepo;
    @Autowired private PatientMonitoringRecordStatsDailyRepository dailyStatsRepo;
    @Autowired private MonitoringParamRepository paramRepo;
    @Autowired private DeptMonitoringGroupRepository monitoringGroupRepo;
    @Autowired private DeptMonitoringGroupParamRepository groupParamRepo;

    private PatientRecord patient;
    private MEnums enums;

    @BeforeEach
    void initialize() {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
            "admin", null, AuthorityUtils.createAuthorityList("ROLE_1")));
        enums = protoService.getConfig().getMedication().getEnums();
        patient = PatientTestUtils.newPatientRecord(NEXT_PID.incrementAndGet(), 1, DEPT_ID);
        patient.setAdmissionTime(DAY.minusDays(1));
        patient = patientRepo.save(patient);
        monitoringConfig.initDeptMonitoringParams(DEPT_ID);
        // Match the reported ward's hourly and accumulated balance columns.
        DeptMonitoringGroup netGroup = monitoringGroupRepo.findByDeptIdAndIsDeletedFalse(DEPT_ID).stream()
            .filter(group -> group.getName().equals(protoService.getConfig().getMonitoring().getBalanceNetGroupName()))
            .findFirst().orElseThrow();
        MonitoringParam balanceParam = paramRepo.findByCode("hourly_balance").orElseThrow();
        for (String code : List.of("hourly_intake", "total_intake")) {
            if (paramRepo.findByCode(code).isEmpty()) {
                paramRepo.save(MonitoringParam.builder().code(code).name(code)
                    .typePb(balanceParam.getTypePb()).balanceType(balanceParam.getBalanceType())
                    .displayOrder(balanceParam.getDisplayOrder()).build());
            }
            if (groupParamRepo.findByDeptMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(
                netGroup.getId(), code).isEmpty()) {
                groupParamRepo.save(DeptMonitoringGroupParam.builder().deptMonitoringGroupId(netGroup.getId())
                    .monitoringParamCode(code).displayOrder(code.equals("hourly_intake") ? 2 : 3)
                    .isDeleted(false).modifiedBy("admin").modifiedAt(DAY).build());
            }
        }
        int intakeType = protoService.getConfig().getMedication().getIntakeTypes().getIntakeTypeList()
            .stream().filter(t -> t.getMonitoringParamCode().equals("intravenous_intake"))
            .findFirst().orElseThrow().getId();
        for (boolean continuous : List.of(false, true)) {
            String code = routeCode(continuous);
            if (routeRepo.findByDeptIdAndCode(DEPT_ID, code).isEmpty()) {
                routeRepo.save(MedicationTestUtils.newAdministrationRoute(
                    DEPT_ID, code, code, continuous,
                    enums.getAdministrationRouteGroupOthers().getId(), intakeType, true));
            }
        }
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @ParameterizedTest
    @ValueSource(ints = {13, 15})
    void movingBolusToAnotherHourRemovesOriginalIntake(int newHour) {
        long recordId = newExecution(false, 3000);
        int complete = enums.getMedicationExecutionActionTypeComplete().getId();
        ExecutionRecordPB initial = saveAction(recordId, 0, complete, at(14, 21), 100, 0);
        assertBalance(Map.of(at(14, 0), 100.0));

        saveAction(recordId, lastActionId(initial), complete, at(newHour, 21), 100, 0);

        assertBalance(Map.of(at(newHour, 0), 100.0));
        assertThat(statRepo.findByExeRecordId(recordId))
            .extracting(stat -> stat.getStatsTime()).containsExactly(at(newHour, 0));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void shorteningContinuousExecutionRemovesTrailingHours(boolean complete) {
        long recordId = newExecution(true, 3000);
        saveAction(recordId, 0, enums.getMedicationExecutionActionTypeStart().getId(), at(12, 0), 0, 60);
        int stopType = complete ? enums.getMedicationExecutionActionTypeComplete().getId()
            : enums.getMedicationExecutionActionTypePause().getId();
        ExecutionRecordPB initial = saveAction(recordId, 0, stopType, at(15, 21), 0, 0);
        assertBalance(Map.of(at(12, 0), 60.0, at(13, 0), 60.0, at(14, 0), 60.0, at(15, 0), 21.0));

        saveAction(recordId, lastActionId(initial), stopType, at(13, 21), 0, 0);

        assertBalance(Map.of(at(12, 0), 60.0, at(13, 0), 21.0));
        if (complete) {
            assertThat(statRepo.findByExeRecordId(recordId)).extracting(stat -> stat.getStatsTime())
                .containsExactlyInAnyOrder(at(12, 0), at(13, 0));
        }
    }

    @Test
    void movingContinuousStartRemovesPreviousHours() {
        long recordId = newExecution(true, 60);
        int start = enums.getMedicationExecutionActionTypeStart().getId();
        ExecutionRecordPB initial = saveAction(recordId, 0, start, at(12, 0), 0, 60);
        assertBalance(Map.of(at(12, 0), 60.0));

        saveAction(recordId, lastActionId(initial), start, at(14, 0), 0, 60);

        assertBalance(Map.of(at(14, 0), 60.0));
    }

    @Test
    void reschedulingPreservesOtherMedicationInOriginalHour() {
        int complete = enums.getMedicationExecutionActionTypeComplete().getId();
        long recordId = newExecution(false, 3000);
        ExecutionRecordPB initial = saveAction(recordId, 0, complete, at(14, 21), 100, 0);
        saveAction(newExecution(false, 3000), 0, complete, at(14, 30), 50, 0);
        assertBalance(Map.of(at(14, 0), 150.0));

        saveAction(recordId, lastActionId(initial), complete, at(13, 21), 100, 0);

        assertBalance(Map.of(at(13, 0), 100.0, at(14, 0), 50.0));
    }

    @Test
    void changingCompletionToFastPushClearsCompletedStatistics() {
        long recordId = newExecution(false, 3000);
        ExecutionRecordPB initial = saveAction(recordId, 0,
            enums.getMedicationExecutionActionTypeComplete().getId(), at(14, 21), 100, 0);
        assertBalance(Map.of(at(14, 0), 100.0));

        saveAction(recordId, lastActionId(initial),
            enums.getMedicationExecutionActionTypeFastPush().getId(), at(13, 21), 80, 0);

        assertBalance(Map.of(at(13, 0), 80.0));
        assertThat(statRepo.findByExeRecordId(recordId)).isEmpty();
    }

    @Test
    void movingBolusAcrossDaysRemovesPreviousDayIntake() {
        long recordId = newExecution(false, 3000);
        int complete = enums.getMedicationExecutionActionTypeComplete().getId();
        ExecutionRecordPB initial = saveAction(recordId, 0, complete, at(14, 21), 100, 0);
        assertBalance(Map.of(at(14, 0), 100.0));

        saveAction(recordId, lastActionId(initial), complete, at(13, 21).plusDays(1), 100, 0);

        assertBalance(Map.of(at(13, 0).plusDays(1), 100.0));
    }

    @Test
    void repeatedEditWithinSameHourDoesNotDuplicateIntake() {
        long recordId = newExecution(false, 3000);
        int complete = enums.getMedicationExecutionActionTypeComplete().getId();
        ExecutionRecordPB initial = saveAction(recordId, 0, complete, at(14, 21), 100, 0);
        assertBalance(Map.of(at(14, 0), 100.0));

        for (int edit = 0; edit < 2; edit++) {
            saveAction(recordId, lastActionId(initial), complete, at(14, 40), 80, 0);
            assertBalance(Map.of(at(14, 0), 80.0));
        }
    }

    @Test
    void bolusExactlyAtShiftStartIsRefreshedBeforeNextQuery() {
        saveAction(newExecution(false, 3000), 0,
            enums.getMedicationExecutionActionTypeComplete().getId(), at(8, 0), 100, 0);

        assertBalance(Map.of(at(8, 0), 100.0));
    }

    private long newExecution(boolean continuous, double volume) {
        String route = routeCode(continuous);
        MedicationOrderGroup group = groupRepo.save(MedicationOrderGroup.builder()
            .patientId(patient.getId()).hisPatientId(patient.getHisPatientId())
            .deptId(DEPT_ID).groupId("reschedule_" + patient.getId() + "_" + recordRepo.count())
            .orderType("西药").status("已审核").orderTime(DAY).planTime(DAY)
            .orderValidity(enums.getMedicationOrderValidityTypeManualEntry().getId())
            .orderDurationType(enums.getOrderDurationTypeManualEntry().getId())
            .freqCode(protoService.getConfig().getMedication().getFreqSpec().getOnceCode())
            .administrationRouteCode(route).administrationRouteName(route)
            .medicationDosageGroup(ProtoUtils.encodeDosageGroup(MedicationDosageGroupPB.newBuilder()
                .addMd(MedicationDosagePB.newBuilder().setCode("reschedule_drug")
                    .setName("测试输液").setIntakeVolMl(volume)).build()))
            .createdAt(DAY).build());
        return recordRepo.save(MedicationExecutionRecord.builder()
            .patientId(patient.getId()).medicationOrderGroupId(group.getId()).hisOrderGroupId(group.getGroupId())
            .planTime(DAY).isContinuous(continuous).isDeleted(false).userTouched(false)
            .createAccountId("admin").createdAt(DAY).build()).getId();
    }

    private ExecutionRecordPB saveAction(long recordId, long actionId, int type,
        LocalDateTime time, double volume, double rate
    ) {
        SaveOrderExeActionResp response = medService.saveOrderExeAction(ProtoUtils.protoToJson(
            SaveOrderExeActionReq.newBuilder().setMedExeRecId(recordId).setActionId(actionId)
                .setActionType(type).setCreatedAtIso8601(TimeUtils.toIso8601String(time, "UTC"))
                .setIntakeVolMl(volume).setAdministrationRate(rate).build()));
        assertThat(response.getRt().getCode()).as(response.getRt().getMsg()).isEqualTo(StatusCode.OK.ordinal());
        return response.getExeRecord();
    }

    private long lastActionId(ExecutionRecordPB record) {
        return record.getMedExeAction(record.getMedExeActionCount() - 1).getId();
    }

    private void assertBalance(Map<LocalDateTime, Double> expected) {
        // Saving must update stored data immediately, before a read can trigger another refresh.
        assertStoredBalance(expected);
        GetPatientMonitoringRecordsResp response = monitoringService.getPatientMonitoringRecords(
            ProtoUtils.protoToJson(GetPatientMonitoringGroupsReq.newBuilder()
                .setPid(patient.getId()).setDeptId(DEPT_ID)
                .setGroupType(protoService.getConfig().getMonitoring().getEnums().getGroupTypeBalance().getId())
                .setQueryStartIso8601(TimeUtils.toIso8601String(DAY, "UTC"))
                .setQueryEndIso8601(TimeUtils.toIso8601String(DAY.plusDays(2), "UTC")).build()));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertStoredBalance(expected);
        Map<LocalDateTime, Double> cumulative = new TreeMap<>();
        double total = 0;
        for (var entry : new TreeMap<>(expected).entrySet()) {
            total += entry.getValue();
            cumulative.put(entry.getKey(), total);
        }
        for (String code : List.of("intravenous_intake", "hourly_intake", "total_intake")) {
            var values = response.getGroupRecordsList().stream().flatMap(group -> group.getCodeRecordsList().stream())
                .filter(record -> record.getParamCode().equals(code)).findFirst().orElseThrow().getRecordValueList();
            Map<LocalDateTime, Double> actual = values.stream()
                .filter(value -> !value.getRecordedAtIso8601().isEmpty())
                .collect(Collectors.toMap(value -> TimeUtils.fromIso8601String(value.getRecordedAtIso8601(), "UTC"),
                    value -> Double.valueOf(value.getValueStr())));
            assertThat(actual).as("API " + code)
                .containsExactlyInAnyOrderEntriesOf(code.equals("total_intake") ? cumulative : expected);
        }
    }

    private void assertStoredBalance(Map<LocalDateTime, Double> expected) {
        // Both the balance table and nursing reports use these persisted hourly records.
        List<PatientMonitoringRecord> records = monitoringRepo.findByPidAndEffectiveTimeRange(
            patient.getId(), DAY, DAY.plusDays(4));
        for (String code : List.of("intravenous_intake", "hourly_intake")) {
            Map<LocalDateTime, Double> actual = records.stream()
                .filter(record -> record.getMonitoringParamCode().equals(code))
                .collect(Collectors.toMap(PatientMonitoringRecord::getEffectiveTime,
                    record -> Double.valueOf(record.getParamValueStr())));
            assertThat(actual).as(code).containsExactlyInAnyOrderEntriesOf(expected);
        }
        double dailyTotal = dailyStatsRepo.findByPidAndIsDeletedFalse(patient.getId()).stream()
            .filter(stat -> stat.getMonitoringParamCode().equals("daily_total_intake"))
            .mapToDouble(stat -> Double.parseDouble(stat.getParamValueStr())).sum();
        assertThat(dailyTotal).as("daily intake total").isEqualTo(
            expected.values().stream().mapToDouble(Double::doubleValue).sum());
    }

    private static String routeCode(boolean continuous) {
        return continuous ? "reschedule_infusion" : "reschedule_bolus";
    }

    private static LocalDateTime at(int hour, int minute) {
        return TimeUtils.fromIso8601String(String.format("2025-09-17T%02d:%02d+08:00", hour, minute), "UTC");
    }
}
