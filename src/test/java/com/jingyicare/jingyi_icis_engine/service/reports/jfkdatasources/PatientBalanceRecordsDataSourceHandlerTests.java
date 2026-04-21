package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringCodeRecordsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupRecordsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringRecordValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.BalanceCalculator;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.monitorings.PatientMonitoringService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.tubes.PatientTubeImpl;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public class PatientBalanceRecordsDataSourceHandlerTests {
    @Test
    public void handleBuildsRowsFromBalanceCalculatorAndMatchesStrictHours() {
        TestContext ctx = new TestContext();
        LocalDateTime balanceStart = LocalDateTime.of(2026, 4, 16, 7, 0);
        ctx.withShift(7);
        ctx.withParams();
        ctx.withBalanceRecords(List.of(
            codeRecords("intravenous_intake",
                recordVal("100", balanceStart),
                recordVal("ignored", balanceStart.plusMinutes(30)),
                recordVal("150", null)),
            codeRecords("hourly_intake",
                recordVal("100", balanceStart),
                recordVal("50", balanceStart.plusHours(1)),
                recordVal("150", null)),
            codeRecords("total_intake",
                recordVal("100", balanceStart),
                recordVal("150", balanceStart.plusHours(1)),
                recordVal("150", null))
        ));

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input(
            List.of("intravenous_intake", "missing", "hourly_intake", "total_intake")));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("param_name")).containsExactly(List.of("静脉入量"), List.of("每小时入量"), List.of("累计入量"));
        assertThat(output.get("acc_ml")).containsExactly(List.of("150"), List.of("150"), List.of("150"));
        assertThat(output.get("hour1")).containsExactly(List.of("100"), List.of("100"), List.of("100"));
        assertThat(output.get("hour2")).containsExactly(List.of(""), List.of("50"), List.of("150"));
        assertThat(output.get("hour24")).hasSize(3);

        verify(ctx.patientTubeImpl).getMonitoringParamCodes(10001L, balanceStart, balanceStart.plusHours(24));
        verify(ctx.patientMonitoringService).getMonitoringRecords(
            eq(10001L), eq("patient-dept"), eq(1), eq(balanceStart), eq(balanceStart.plusHours(24)),
            eq(false), eq(List.of()), eq("42")
        );
        verify(ctx.balanceCalculator).storeGroupRecordsStats(any(BalanceCalculator.GetGroupRecordsListArgs.class));
    }

    @Test
    public void handleRendersConfiguredRowsWithBlankValuesWhenNoRecordsExist() {
        TestContext ctx = new TestContext();
        ctx.withShift(7);
        ctx.withParams();
        ctx.withBalanceRecords(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input(
            List.of("intravenous_intake", "hourly_intake", "total_intake")));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("param_name")).containsExactly(List.of("静脉入量"), List.of("每小时入量"), List.of("累计入量"));
        assertThat(output.get("acc_ml")).containsExactly(List.of(""), List.of(""), List.of(""));
        assertThat(output.get("hour1")).containsExactly(List.of(""), List.of(""), List.of(""));
    }

    @Test
    public void handleExpandsDrainageTubePlaceholderInPlace() {
        TestContext ctx = new TestContext();
        LocalDateTime balanceStart = LocalDateTime.of(2026, 4, 16, 7, 0);
        List<String> tubeParamCodes = List.of("drain_tube_a", "drain_tube_b");
        ctx.withShift(7);
        ctx.withParams();
        when(ctx.patientTubeImpl.getMonitoringParamCodes(10001L, balanceStart, balanceStart.plusHours(24)))
            .thenReturn(tubeParamCodes);
        ctx.withBalanceRecords(List.of(
            codeRecords("urine_output", recordVal("10", balanceStart), recordVal("20", null)),
            codeRecords("drain_tube_a", recordVal("5", balanceStart), recordVal("5", null)),
            codeRecords("drain_tube_b", recordVal("8", balanceStart), recordVal("8", null)),
            codeRecords("hourly_output", recordVal("23", balanceStart), recordVal("23", null))
        ));

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input(
            List.of("urine_output", "compactreportdrainagetubeparams", "hourly_output")));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<List<String>>> output = toOutputMap(result.getSecond());
        assertThat(output.get("param_name")).containsExactly(
            List.of("尿量"), List.of("腹腔引流"), List.of("胸腔引流"), List.of("每小时出量"));
        assertThat(output.get("acc_ml")).containsExactly(List.of("20"), List.of("5"), List.of("8"), List.of("23"));
        assertThat(output.get("hour1")).containsExactly(List.of("10"), List.of("5"), List.of("8"), List.of("23"));

        verify(ctx.monitoringConfig).getMonitoringGroups(
            eq(10001L), eq("patient-dept"), eq(1), eq(tubeParamCodes), eq("42"));
    }

    @Test
    public void handleReturnsErrorWhenStartHourIsInvalid() {
        TestContext ctx = new TestContext();
        ctx.withShift(24);

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input(
            List.of("intravenous_intake")));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.INVALID_PARAM_VALUE.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    @Test
    public void handleReturnsErrorWhenBalanceStatsShiftIsMissing() {
        TestContext ctx = new TestContext();

        Pair<ReturnCode, JfkDataSourcePB> result = ctx.handler().handle(input(
            List.of("intravenous_intake")));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    private static JfkDataSourcePB input(List<String> paramCodes) {
        return JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_BALANCE_RECORDS, "table-228"))
            .setMetaId(JfkDataSourceIds.PATIENT_BALANCE_RECORDS)
            .addInputData(int64Input("pid", 10001L))
            .addInputData(strInput("dept_id", "request-dept"))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .addInputData(strInput("table_id", "table-228"))
            .addInputData(strArrayInput("balance_param_codes", paramCodes))
            .addInputData(doubleArrayInput("col_widths", testColWidths()))
            .addInputData(doubleInput("font_size", 8d))
            .addInputData(doubleInput("char_spacing", 0d))
            .addInputData(doubleInput("h_padding", 2d))
            .build();
    }

    private static MonitoringCodeRecordsPB codeRecords(String code, MonitoringRecordValPB... vals) {
        return MonitoringCodeRecordsPB.newBuilder()
            .setParamCode(code)
            .addAllRecordValue(List.of(vals))
            .build();
    }

    private static MonitoringRecordValPB recordVal(String value, LocalDateTime recordedAtUtc) {
        return MonitoringRecordValPB.newBuilder()
            .setId(recordedAtUtc == null ? -1L : 1L)
            .setValue(GenericValuePB.newBuilder().setFloatVal(Float.parseFloat(value.equals("ignored") ? "0" : value)).build())
            .setValueStr(value)
            .setRecordedAtIso8601(TimeUtils.toIso8601String(recordedAtUtc, "UTC"))
            .build();
    }

    private static MonitoringParamPB param(String code, String name, int balanceType) {
        return MonitoringParamPB.newBuilder()
            .setCode(code)
            .setName(name)
            .setBalanceType(balanceType)
            .setValueMeta(ValueMetaPB.newBuilder()
                .setValueType(TypeEnumPB.FLOAT)
                .setDecimalPlaces(1)
                .setUnit("ml")
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

    private static JfkFieldDataPB strArrayInput(String id, List<String> values) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(values.stream()
                .map(value -> JfkValPB.newBuilder().setStrVal(value).build())
                .toList())
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
        List<Double> widths = new java.util.ArrayList<>(Collections.nCopies(26, 40d));
        widths.set(0, 80d);
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
        private final JfkDataSourceSupport support = mockSupport();
        private final BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        private final MonitoringConfig monitoringConfig = mock(MonitoringConfig.class);
        private final PatientMonitoringService patientMonitoringService = mock(PatientMonitoringService.class);
        private final BalanceCalculator balanceCalculator = mock(BalanceCalculator.class);
        private final PatientTubeImpl patientTubeImpl = mock(PatientTubeImpl.class);
        private final UserService userService = mock(UserService.class);
        private final ConfigProtoService configProtoService = mock(ConfigProtoService.class);

        private TestContext() {
            when(userService.getAccountWithAutoId()).thenReturn(new Pair<>("42", "护士"));
            when(configProtoService.getConfig()).thenReturn(Config.newBuilder()
                .setMonitoring(MonitoringPB.newBuilder()
                    .setParamCodeSummary("all")
                    .setParamCodeSummaryTxt("合计")
                    .build())
                .build());
            when(monitoringConfig.getBalanceGroupTypeId()).thenReturn(1);
            when(patientTubeImpl.getMonitoringParamCodes(any(), any(), any())).thenReturn(List.of());
            when(monitoringConfig.getMonitoringGroups(any(), any(), any(Integer.class), any(), any())).thenReturn(List.of());
        }

        private PatientBalanceRecordsDataSourceHandler handler() {
            return new PatientBalanceRecordsDataSourceHandler(
                support,
                balanceStatsShiftRepo,
                monitoringConfig,
                patientMonitoringService,
                balanceCalculator,
                patientTubeImpl,
                userService,
                configProtoService,
                new ReportProperties(),
                new DefaultResourceLoader()
            );
        }

        private void withShift(int startHour) {
            when(balanceStatsShiftRepo
                .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                    "patient-dept", LocalDateTime.of(2026, 4, 17, 0, 0)))
                .thenReturn(Optional.of(BalanceStatsShift.builder()
                    .id(1L)
                    .deptId("patient-dept")
                    .startHour(startHour)
                    .monStartHour(8)
                    .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                    .isDeleted(false)
                    .build()));
        }

        private void withParams() {
            when(monitoringConfig.getMonitoringParams("patient-dept")).thenReturn(Map.of(
                "intravenous_intake", param("intravenous_intake", "静脉入量", 1),
                "hourly_intake", param("hourly_intake", "每小时入量", 3),
                "total_intake", param("total_intake", "累计入量", 0),
                "urine_output", param("urine_output", "尿量", 2),
                "drain_tube_a", param("drain_tube_a", "腹腔引流", 2),
                "drain_tube_b", param("drain_tube_b", "胸腔引流", 2),
                "hourly_output", param("hourly_output", "每小时出量", 3),
                "total_output", param("total_output", "累计出量", 0)
            ));
        }

        private void withBalanceRecords(List<MonitoringCodeRecordsPB> codeRecords) {
            PatientMonitoringService.GetMonitoringRecordsResult recordsResult =
                new PatientMonitoringService.GetMonitoringRecordsResult();
            recordsResult.recordsQueryStartUtc = LocalDateTime.of(2026, 4, 16, 7, 0);
            recordsResult.recordsQueryEndUtc = LocalDateTime.of(2026, 4, 17, 7, 0);
            recordsResult.balanceStatTimeUtcHistory = List.of(LocalDateTime.of(2026, 4, 16, 7, 0));
            recordsResult.groupBetaList = List.of();
            recordsResult.recordList = List.of();
            when(patientMonitoringService.getMonitoringRecords(any(), any(), any(Integer.class), any(), any(), any(Boolean.class), any(), any()))
                .thenReturn(recordsResult);
            when(balanceCalculator.getGroupRecordsList(any(BalanceCalculator.GetGroupRecordsListArgs.class)))
                .thenReturn(List.of(MonitoringGroupRecordsPB.newBuilder()
                    .setDeptMonitoringGroupId(1)
                    .addAllCodeRecords(codeRecords)
                    .build()));
        }

        private static JfkDataSourceSupport mockSupport() {
            PatientService patientService = mock(PatientService.class);
            PatientRecord patient = new PatientRecord();
            patient.setId(10001L);
            patient.setDeptId("patient-dept");
            when(patientService.getPatientRecord(10001L)).thenReturn(patient);

            JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
            when(support.getStatusMsgList()).thenReturn(Collections.nCopies(400, "status"));
            when(support.getZoneId()).thenReturn("UTC");
            when(support.getPatientService()).thenReturn(patientService);
            when(support.newOutputBuilder(any(JfkDataSourcePB.class))).thenAnswer(invocation -> {
                JfkDataSourcePB input = invocation.getArgument(0);
                return JfkDataSourcePB.newBuilder()
                    .setId(input.getId())
                    .setMetaId(input.getMetaId())
                    .addAllInputData(input.getInputDataList());
            });
            return support;
        }
    }
}
