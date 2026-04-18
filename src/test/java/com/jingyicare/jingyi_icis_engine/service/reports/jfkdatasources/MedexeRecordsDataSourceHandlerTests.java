package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.entity.medications.AdministrationRoute;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionAction;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecord;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecordStat;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationOrderGroup;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedicationDosageGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedicationDosagePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.medications.AdministrationRouteRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionActionRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionRecordStatRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationOrderGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.medications.MedMonitoringService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public class MedexeRecordsDataSourceHandlerTests {
    @Test
    public void handleBuildsContinuousAndNoncontinuousRowsFromStats() {
        TestContext ctx = new TestContext();
        ctx.withShift(7);

        MedicationOrderGroup infusionGroup = group(201L, "iv");
        MedicationOrderGroup pushGroup = group(202L, "push");
        MedicationExecutionRecord infusionRecord = record(301L, 201L, "iv", true, LocalDateTime.of(2026, 4, 15, 23, 10));
        MedicationExecutionRecord pushRecord = record(302L, 202L, "push", false, LocalDateTime.of(2026, 4, 16, 0, 5));
        ctx.withRecords(List.of(infusionRecord, pushRecord), List.of(infusionGroup, pushGroup));
        ctx.withRoutes(route("iv", "静脉滴注", true, 1), route("push", "静脉推注", false, 1));
        ctx.withStats(List.of(
            stat(401L, 201L, 301L, START_UTC, 10.25),
            stat(402L, 201L, 301L, START_UTC.plusHours(1), 5d),
            stat(403L, 202L, 302L, START_UTC.plusHours(1), 5.5)
        ));
        ctx.withActions(List.of(
            action(501L, 302L, START_UTC.plusHours(1).plusMinutes(38), 5.5)
        ));
        ctx.withDosage(infusionGroup, infusionRecord, "持续补液");
        ctx.withDosage(pushGroup, pushRecord, "静推药物");

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("med_order_txt")).containsExactly(List.of("持续补液"), List.of("静推药物"));
        assertThat(output.get("route_txt")).containsExactly(List.of("静脉滴注"), List.of("静脉推注"));
        assertThat(output.get("acc_ml")).containsExactly(List.of("15.3"), List.of("5.5"));
        assertThat(output.get("hour1")).containsExactly(List.of("10.3"), List.of(""));
        assertThat(output.get("hour2")).containsExactly(List.of("5"), List.of("5.5", "8:38"));
        assertThat(output.get("hour24")).hasSize(2);
    }

    @Test
    public void handleCalculatesInMemoryWhenRecordHasNoStats() {
        TestContext ctx = new TestContext();
        ctx.withShift(7);

        MedicationOrderGroup group = group(201L, "iv");
        MedicationExecutionRecord record = record(301L, 201L, "iv", true, LocalDateTime.of(2026, 4, 15, 23, 10));
        MedicationDosageGroupPB dosage = dosage("临时计算补液");
        ctx.withRecords(List.of(record), List.of(group));
        ctx.withRoutes(route("iv", "静脉滴注", true, 1));
        ctx.withStats(List.of());
        ctx.withActions(List.of(action(501L, 301L, START_UTC.plusHours(2), 0d)));
        when(ctx.medMonitoringService.getDosageGroupPB(group, record)).thenReturn(dosage);
        MedMonitoringService.FluidIntakeData intake =
            new MedMonitoringService.FluidIntakeData(100d, END_UTC, "UTC");
        intake.intakeMap = Map.of(START_UTC.plusHours(2), 7d);
        when(ctx.medMonitoringService.calcFluidIntakeImpl(eq(true), eq(dosage), any(), eq(END_UTC)))
            .thenReturn(intake);

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("med_order_txt")).containsExactly(List.of("临时计算补液"));
        assertThat(output.get("hour3")).containsExactly(List.of("7"));
        verify(ctx.medMonitoringService).calcFluidIntakeImpl(eq(true), eq(dosage), any(), eq(END_UTC));
    }

    @Test
    public void handleReturnsEmptyRowsWhenNoRouteMatchesIntakeType() {
        TestContext ctx = new TestContext();
        ctx.withShift(7);

        MedicationOrderGroup group = group(201L, "enteral");
        MedicationExecutionRecord record = record(301L, 201L, "enteral", false, LocalDateTime.of(2026, 4, 15, 23, 10));
        ctx.withRecords(List.of(record), List.of(group));
        ctx.withRoutes(route("enteral", "鼻饲", false, 2));
        ctx.withStats(List.of());
        ctx.withActions(List.of());
        ctx.withDosage(group, record, "鼻饲营养液");

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("med_order_txt")).isEmpty();
        assertThat(output.get("route_txt")).isEmpty();
        assertThat(output.get("acc_ml")).isEmpty();
        assertThat(output.get("hour1")).isEmpty();
    }

    @Test
    public void handleDoesNotFilterRoutesWhenIntakeTypeIsNonPositive() {
        TestContext ctx = new TestContext();
        ctx.withShift(7);

        MedicationOrderGroup group = group(201L, "enteral");
        MedicationExecutionRecord record = record(301L, 201L, "enteral", false, LocalDateTime.of(2026, 4, 15, 23, 10));
        ctx.withRecords(List.of(record), List.of(group));
        ctx.withRoutes(route("enteral", "鼻饲", false, 2));
        ctx.withStats(List.of(stat(401L, 201L, 301L, START_UTC, 8d)));
        ctx.withActions(List.of(action(501L, 301L, START_UTC.plusMinutes(20), 8d)));
        ctx.withDosage(group, record, "鼻饲营养液");

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(inputWithIntakeType(0L));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("med_order_txt")).containsExactly(List.of("鼻饲营养液"));
        assertThat(output.get("route_txt")).containsExactly(List.of("鼻饲"));
        assertThat(output.get("hour1")).containsExactly(List.of("8", "7:20"));
    }

    @Test
    public void handleReturnsErrorWhenStartHourIsInvalid() {
        TestContext ctx = new TestContext();
        ctx.withShift(24);

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.INVALID_PARAM_VALUE.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    @Test
    public void handleUsesPatientDeptWhenRequestDeptDiffers() {
        TestContext ctx = new TestContext();
        ctx.withShift(7);
        ctx.withRecords(List.of(), List.of());
        ctx.withRoutes();
        ctx.withStats(List.of());
        ctx.withActions(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        verify(ctx.balanceStatsShiftRepo)
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("patient-dept", QUERY_UTC_END);
    }

    @Test
    public void handleFallsBackToInputDeptWhenPidIsMissing() {
        TestContext ctx = new TestContext();
        when(ctx.balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                "request-dept", QUERY_UTC_END))
            .thenReturn(Optional.of(BalanceStatsShift.builder()
                .id(1L)
                .deptId("request-dept")
                .startHour(7)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(inputWithoutPid());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("med_order_txt")).isEmpty();
        verify(ctx.balanceStatsShiftRepo)
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("request-dept", QUERY_UTC_END);
    }

    private static JfkDataSourcePB input() {
        return inputWithIntakeType(1L);
    }

    private static JfkDataSourcePB inputWithIntakeType(long intakeTypeId) {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.MEDEXE_RECORDS, "table-236"))
            .setMetaId(JfkDataSourceIds.MEDEXE_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .addInputData(strInput("query_end", "2026-04-17T00:00Z"))
            .addInputData(strInput("table_id", "table-236"))
            .addInputData(int64Input("intake_type_id", intakeTypeId))
            .addInputData(doubleArrayInput("col_widths", testColWidths()))
            .addInputData(doubleInput("font_size", 6d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build();
    }

    private static JfkDataSourcePB inputWithoutPid() {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.MEDEXE_RECORDS, "table-236"))
            .setMetaId(JfkDataSourceIds.MEDEXE_RECORDS)
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .addInputData(strInput("query_end", "2026-04-17T00:00Z"))
            .addInputData(strInput("table_id", "table-236"))
            .addInputData(int64Input("intake_type_id", 1L))
            .addInputData(doubleArrayInput("col_widths", testColWidths()))
            .addInputData(doubleInput("font_size", 6d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build();
    }

    private static MedicationOrderGroup group(Long id, String routeCode) {
        return MedicationOrderGroup.builder()
            .id(id)
            .patientId(10001L)
            .deptId("patient-dept")
            .administrationRouteCode(routeCode)
            .administrationRouteName(routeCode)
            .medicationDosageGroup("")
            .build();
    }

    private static MedicationExecutionRecord record(
        Long id,
        Long groupId,
        String routeCode,
        Boolean continuous,
        LocalDateTime startTime
    ) {
        return MedicationExecutionRecord.builder()
            .id(id)
            .medicationOrderGroupId(groupId)
            .patientId(10001L)
            .administrationRouteCode(routeCode)
            .isContinuous(continuous)
            .startTime(startTime)
            .endTime(startTime.plusHours(1))
            .isDeleted(false)
            .build();
    }

    private static AdministrationRoute route(String code, String name, boolean continuous, int intakeTypeId) {
        return AdministrationRoute.builder()
            .deptId("patient-dept")
            .code(code)
            .name(name)
            .isContinuous(continuous)
            .intakeTypeId(intakeTypeId)
            .isValid(true)
            .build();
    }

    private static MedicationExecutionRecordStat stat(
        Long id,
        Long groupId,
        Long recordId,
        LocalDateTime statsTime,
        double consumedMl
    ) {
        return MedicationExecutionRecordStat.builder()
            .id(id)
            .groupId(groupId)
            .exeRecordId(recordId)
            .statsTime(statsTime)
            .consumedMl(consumedMl)
            .isFinal(false)
            .build();
    }

    private static MedicationExecutionAction action(Long id, Long recordId, LocalDateTime createdAt, double intakeVolMl) {
        return MedicationExecutionAction.builder()
            .id(id)
            .medicationExecutionRecordId(recordId)
            .createdAt(createdAt)
            .intakeVolMl(intakeVolMl)
            .actionType(1)
            .isDeleted(false)
            .build();
    }

    private static MedicationDosageGroupPB dosage(String displayName) {
        return MedicationDosageGroupPB.newBuilder()
            .setDisplayName(displayName)
            .addMd(MedicationDosagePB.newBuilder()
                .setName(displayName)
                .setIntakeVolMl(100d)
                .build())
            .build();
    }

    private static JfkFieldDataPB strInput(String id, String value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setStrVal(value).build())
            .build();
    }

    private static JfkFieldDataPB int64Input(String id, long value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setInt64Val(value).build())
            .build();
    }

    private static JfkFieldDataPB doubleInput(String id, double value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setDoubleVal(value).build())
            .build();
    }

    private static JfkFieldDataPB doubleArrayInput(String id, List<Double> values) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(values.stream()
                .map(value -> JfkValPB.newBuilder().setDoubleVal(value).build())
                .toList())
            .build();
    }

    private static List<Double> testColWidths() {
        java.util.ArrayList<Double> widths = new java.util.ArrayList<>(Collections.nCopies(27, 40d));
        widths.set(0, 200d);
        widths.set(1, 100d);
        widths.set(2, 60d);
        return widths;
    }

    private static Map<String, List<List<String>>> toOutputMap(JfkDataSourcePB output) {
        return output.getOutputDataList().stream()
            .collect(Collectors.toMap(
                JfkFieldDataPB::getId,
                field -> field.getValsList().stream()
                    .map(val -> List.copyOf(val.getStrsValList()))
                    .toList()
            ));
    }

    private static class TestContext {
        private final JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
        private final PatientService patientService = mock(PatientService.class);
        private final BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        private final MedicationExecutionRecordRepository exeRecordRepo = mock(MedicationExecutionRecordRepository.class);
        private final MedicationExecutionRecordStatRepository statRepo = mock(MedicationExecutionRecordStatRepository.class);
        private final MedicationOrderGroupRepository orderGroupRepo = mock(MedicationOrderGroupRepository.class);
        private final MedicationExecutionActionRepository actionRepo = mock(MedicationExecutionActionRepository.class);
        private final AdministrationRouteRepository routeRepo = mock(AdministrationRouteRepository.class);
        private final MedMonitoringService medMonitoringService = mock(MedMonitoringService.class);

        private TestContext() {
            PatientRecord patient = new PatientRecord();
            patient.setId(10001L);
            patient.setDeptId("patient-dept");
            when(patientService.getPatientRecord(10001L)).thenReturn(patient);
            when(support.getStatusMsgList()).thenReturn(Collections.nCopies(400, "status"));
            when(support.getZoneId()).thenReturn("Asia/Shanghai");
            when(support.getPatientService()).thenReturn(patientService);
            when(support.newOutputBuilder(any(JfkDataSourcePB.class))).thenAnswer(invocation -> {
                JfkDataSourcePB input = invocation.getArgument(0);
                return JfkDataSourcePB.newBuilder()
                    .setId(input.getId())
                    .setMetaId(input.getMetaId())
                    .addAllInputData(input.getInputDataList());
            });
        }

        private MedexeRecordsDataSourceHandler handler() {
            ReportProperties properties = new ReportProperties();
            properties.getCompact().setFont("classpath:/fonts/msyh.ttf");
            properties.getCompact().setMedicationMlDecimalPlaces(1);
            return new MedexeRecordsDataSourceHandler(
                support,
                balanceStatsShiftRepo,
                exeRecordRepo,
                statRepo,
                orderGroupRepo,
                actionRepo,
                routeRepo,
                medMonitoringService,
                properties,
                new DefaultResourceLoader()
            );
        }

        private void withShift(int startHour) {
            when(balanceStatsShiftRepo
                .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                    "patient-dept", QUERY_UTC_END))
                .thenReturn(Optional.of(BalanceStatsShift.builder()
                    .id(1L)
                    .deptId("patient-dept")
                    .startHour(startHour)
                    .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                    .isDeleted(false)
                    .build()));
        }

        private void withRecords(List<MedicationExecutionRecord> records, List<MedicationOrderGroup> groups) {
            when(exeRecordRepo.findInProgressRecordsByPatientId(10001L, END_UTC)).thenReturn(List.of());
            when(exeRecordRepo.findCompletedRecordsByPatientId(10001L, START_UTC, END_UTC)).thenReturn(records);
            when(orderGroupRepo.findByIds(groups.stream().map(MedicationOrderGroup::getId).toList()))
                .thenReturn(groups);
        }

        private void withRoutes(AdministrationRoute... routes) {
            List<String> codes = List.of(routes).stream().map(AdministrationRoute::getCode).toList();
            when(routeRepo.findByDeptIdAndCodeIn("patient-dept", codes)).thenReturn(List.of(routes));
        }

        private void withStats(List<MedicationExecutionRecordStat> stats) {
            when(statRepo.findAllByPatientIdAndStatsTimeRange(10001L, START_UTC, END_UTC)).thenReturn(stats);
        }

        private void withActions(List<MedicationExecutionAction> actions) {
            when(actionRepo.findByMedicationExecutionRecordIdInAndIsDeletedFalse(any())).thenReturn(actions);
        }

        private void withDosage(
            MedicationOrderGroup group,
            MedicationExecutionRecord record,
            String displayName
        ) {
            when(medMonitoringService.getDosageGroupPB(group, record)).thenReturn(dosage(displayName));
        }
    }

    private static final LocalDateTime QUERY_UTC_END = LocalDateTime.of(2026, 4, 17, 0, 0);
    private static final LocalDateTime START_UTC = LocalDateTime.of(2026, 4, 15, 23, 0);
    private static final LocalDateTime END_UTC = START_UTC.plusHours(24);
}
