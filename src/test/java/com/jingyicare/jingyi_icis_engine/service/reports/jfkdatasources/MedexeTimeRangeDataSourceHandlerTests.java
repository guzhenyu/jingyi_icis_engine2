package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindowResolver;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public class MedexeTimeRangeDataSourceHandlerTests {
    @ParameterizedTest
    @CsvSource({"7, 8:00, 0:00, 6:00", "0, 1:00, 17:00, 23:00"})
    public void handleReturnsTwentyFourHourLabelsUsingLegacyMiddleDate(
        int startHour, String hour2, String hour18, String hour24
    ) {
        JfkDataSourceSupport support = mockSupport();
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        MedexeTimeRangeDataSourceHandler handler =
            new MedexeTimeRangeDataSourceHandler(support);
        ReflectionTestUtils.setField(support, "statisticsDayWindowResolver",
            new StatisticsDayWindowResolver(balanceStatsShiftRepo));

        LocalDateTime utcEnd = LocalDateTime.of(2026, 4, 17, 16, 0);
        when(balanceStatsShiftRepo
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc("10042"))
            .thenReturn(List.of(
                BalanceStatsShift.builder().startHour(23).monStartHour(23)
                    .effectiveTime(utcEnd).build(),
                BalanceStatsShift.builder()
                    .id(1L)
                    .deptId("10042")
                    .startHour(startHour)
                    .monStartHour(8)
                    .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                    .isDeleted(false)
                    .build()));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input("10042",
            "2026-04-16T06:00Z"));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<String>> output = toOutputMap(result.getSecond());
        assertThat(output.get("med_order_txt")).containsExactly("用药医嘱");
        assertThat(output.get("route_txt")).containsExactly("用药途径");
        assertThat(output.get("acc_ml")).containsExactly("累计量");
        assertThat(output.get("hour1")).containsExactly(startHour + ":00");
        assertThat(output.get("hour2")).containsExactly(hour2);
        assertThat(output.get("hour18")).containsExactly(hour18);
        assertThat(output.get("hour24")).containsExactly(hour24);
        assertThat(output).containsKeys("hour1", "hour24").hasSize(27);

        verify(balanceStatsShiftRepo)
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc("10042");
    }

    @Test
    public void handleReturnsMissingRequiredFieldWhenDeptIdIsMissing() {
        MedexeTimeRangeDataSourceHandler handler =
            new MedexeTimeRangeDataSourceHandler(mockSupport());

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input("", "2026-04-16T00:00Z"));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.JFK_MISSING_REQUIRED_FIELD.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    @Test
    public void handleReturnsMissingRequiredFieldWhenQueryStartIsMissing() {
        MedexeTimeRangeDataSourceHandler handler =
            new MedexeTimeRangeDataSourceHandler(mockSupport());

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input("10042", ""));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.JFK_MISSING_REQUIRED_FIELD.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    @Test
    public void handleReturnsInvalidTimeFormatWhenQueryStartIsInvalid() {
        MedexeTimeRangeDataSourceHandler handler =
            new MedexeTimeRangeDataSourceHandler(mockSupport());

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input("10042", "bad-time"));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.INVALID_TIME_FORMAT.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    @Test
    public void handleUsesEightHourLabelsWhenShiftIsMissing() {
        JfkDataSourceSupport support = mockSupport();
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        MedexeTimeRangeDataSourceHandler handler =
            new MedexeTimeRangeDataSourceHandler(support);
        ReflectionTestUtils.setField(support, "statisticsDayWindowResolver",
            new StatisticsDayWindowResolver(balanceStatsShiftRepo));

        when(balanceStatsShiftRepo
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(anyString()))
            .thenReturn(List.of());

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input("10042", "2026-04-16T00:00Z"));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(toOutputMap(result.getSecond())).containsEntry("hour1", List.of("8:00"))
            .containsEntry("hour24", List.of("7:00")).hasSize(27);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 24})
    public void handleReturnsInvalidParamValueWhenStartHourIsInvalid(Integer startHour) {
        JfkDataSourceSupport support = mockSupport();
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        MedexeTimeRangeDataSourceHandler handler =
            new MedexeTimeRangeDataSourceHandler(support);
        ReflectionTestUtils.setField(support, "statisticsDayWindowResolver",
            new StatisticsDayWindowResolver(balanceStatsShiftRepo));

        when(balanceStatsShiftRepo
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(anyString()))
            .thenReturn(List.of(BalanceStatsShift.builder()
                .id(1L)
                .deptId("10042")
                .startHour(startHour)
                .monStartHour(8)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input("10042", "2026-04-16T00:00Z"));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.INVALID_PARAM_VALUE.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    @Test
    public void handleUsesPatientDeptWhenPidIsPresent() {
        PatientService patientService = mock(PatientService.class);
        PatientRecord patient = new PatientRecord();
        patient.setId(10001L);
        patient.setDeptId("patient-dept");
        when(patientService.getPatientRecord(10001L)).thenReturn(patient);

        JfkDataSourceSupport support = mockSupport();
        when(support.getPatientService()).thenReturn(patientService);
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        MedexeTimeRangeDataSourceHandler handler =
            new MedexeTimeRangeDataSourceHandler(support);
        ReflectionTestUtils.setField(support, "statisticsDayWindowResolver",
            new StatisticsDayWindowResolver(balanceStatsShiftRepo));

        when(balanceStatsShiftRepo
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc("patient-dept"))
            .thenReturn(List.of(BalanceStatsShift.builder()
                .id(1L)
                .deptId("patient-dept")
                .startHour(7)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(input(10001L, "request-dept", "2026-04-16T00:00Z"));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        verify(balanceStatsShiftRepo)
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc("patient-dept");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    public void handleFallsBackToInputDeptWhenPatientOrPatientDeptIsMissing(boolean patientExists) {
        JfkDataSourceSupport support = mockSupport();
        PatientService patientService = mock(PatientService.class);
        when(support.getPatientService()).thenReturn(patientService);
        when(patientService.getPatientRecord(10001L)).thenReturn(patientExists ? new PatientRecord(
            ) : null);
        BalanceStatsShiftRepository repo = mock(BalanceStatsShiftRepository.class);
        ReflectionTestUtils.setField(support, "statisticsDayWindowResolver",
            new StatisticsDayWindowResolver(repo));

        var result = new MedexeTimeRangeDataSourceHandler(support)
            .handle(input(10001L, "request-dept", "2026-04-16T00:00Z"));

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(toOutputMap(result.getSecond()).get("hour1")).containsExactly("8:00");
        verify(repo)
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc("request-dept");
    }

    private JfkDataSourceSupport mockSupport() {
        JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
        when(support.getStatusMsgList()).thenReturn(Collections.nCopies(400, "status"));
        when(support.getZoneId()).thenReturn("Asia/Shanghai");
        doCallRealMethod().when(support).resolveStatisticsDayWindow(any(), any());
        when(support.newOutputBuilder(any(JfkDataSourcePB.class))).thenAnswer(invocation -> {
            JfkDataSourcePB input = invocation.getArgument(0);
            return JfkDataSourcePB.newBuilder()
                .setId(input.getId())
                .setMetaId(input.getMetaId())
                .addAllInputData(input.getInputDataList());
        });
        doAnswer(invocation -> {
            JfkDataSourcePB.Builder builder = invocation.getArgument(0);
            String id = invocation.getArgument(1);
            List<String> values = invocation.getArgument(2);
            builder.addOutputData(JfkFieldDataPB.newBuilder()
                .setId(id)
                .addAllVals(values.stream()
                    .map(value -> JfkValPB.newBuilder().setStrVal(value).build())
                    .toList())
                .build());
            return null;
        }).when(support).addArrayOutput(any(JfkDataSourcePB.Builder.class), anyString(), anyList());
        return support;
    }

    private static JfkDataSourcePB input(String deptId, String queryStart) {
        return JfkDataSourcePB.newBuilder()
            .setId("request-1")
            .setMetaId("medexe_time_range")
            .addInputData(strInput("dept_id", deptId))
            .addInputData(strInput("query_start", queryStart))
            .build();
    }

    private static JfkDataSourcePB input(long pid, String deptId, String queryStart) {
        return JfkDataSourcePB.newBuilder()
            .setId("request-1")
            .setMetaId("medexe_time_range")
            .addInputData(int64Input("pid", pid))
            .addInputData(strInput("dept_id", deptId))
            .addInputData(strInput("query_start", queryStart))
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

    private static Map<String, List<String>> toOutputMap(JfkDataSourcePB output) {
        return output.getOutputDataList().stream()
            .collect(Collectors.toMap(
                JfkFieldDataPB::getId,
                field -> field.getValsList().stream().map(JfkValPB::getStrVal).toList()
            ));
    }
}
