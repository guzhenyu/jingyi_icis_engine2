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
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.shifts.StatisticsDayWindowResolver;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public class BalanceTimeRangeDataSourceHandlerTests {
    @ParameterizedTest
    @CsvSource({"8, 9:00, 0:00, 7:00", "0, 1:00, 16:00, 23:00"})
    public void handleReturnsTwentyFourHourLabelsUsingLegacyMiddleDate(
        int startHour, String hour2, String hour17, String hour24
    ) {
        JfkDataSourceSupport support = mockSupport();
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        BalanceTimeRangeDataSourceHandler handler =
            new BalanceTimeRangeDataSourceHandler(support);
        ReflectionTestUtils.setField(support, "statisticsDayWindowResolver",
            new StatisticsDayWindowResolver(balanceStatsShiftRepo));

        LocalDateTime utcEnd = LocalDateTime.of(2026, 4, 17, 16, 0);
        when(balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("10042", utcEnd))
            .thenReturn(Optional.of(BalanceStatsShift.builder()
                .deptId("10042")
                .startHour(startHour)
                .monStartHour(7)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(JfkDataSourcePB.newBuilder()
            .setId("request-1")
            .setMetaId("balance_time_range")
            .addInputData(strInput("dept_id", "10042"))
            .addInputData(strInput("query_start", "2026-04-16T06:00Z"))
            .build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<String>> output = toOutputMap(result.getSecond());
        assertThat(output.get("time_txt")).containsExactly("时间");
        assertThat(output.get("acc_ml")).containsExactly("累计量ml");
        assertThat(output.get("hour1")).containsExactly(startHour + ":00");
        assertThat(output.get("hour2")).containsExactly(hour2);
        assertThat(output.get("hour17")).containsExactly(hour17);
        assertThat(output.get("hour24")).containsExactly(hour24);
        assertThat(output).containsKeys("hour1", "hour24").hasSize(26);

        verify(balanceStatsShiftRepo)
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("10042", utcEnd);
    }

    @Test
    public void handleUsesEightHourLabelsWhenShiftIsMissing() {
        JfkDataSourceSupport support = mockSupport();
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        BalanceTimeRangeDataSourceHandler handler =
            new BalanceTimeRangeDataSourceHandler(support);
        ReflectionTestUtils.setField(support, "statisticsDayWindowResolver",
            new StatisticsDayWindowResolver(balanceStatsShiftRepo));

        when(balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                anyString(), any(LocalDateTime.class)))
            .thenReturn(Optional.empty());

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(JfkDataSourcePB.newBuilder()
            .setId("request-1")
            .setMetaId("balance_time_range")
            .addInputData(strInput("dept_id", "10042"))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(toOutputMap(result.getSecond())).containsEntry("hour1", List.of("8:00"))
            .containsEntry("hour24", List.of("7:00")).hasSize(26);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 24})
    public void handleRejectsInvalidStartHourWithoutPid(Integer startHour) {
        JfkDataSourceSupport support = mockSupport();
        BalanceStatsShiftRepository repo = mock(BalanceStatsShiftRepository.class);
        ReflectionTestUtils.setField(support, "statisticsDayWindowResolver",
            new StatisticsDayWindowResolver(repo));
        when(repo.findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                "10042", LocalDateTime.of(2026, 4, 16, 16, 0)))
            .thenReturn(Optional.of(BalanceStatsShift.builder().startHour(startHour).monStartHour(7)
                .build()));

        var result = new BalanceTimeRangeDataSourceHandler(support).handle(JfkDataSourcePB
            .newBuilder()
            .addInputData(strInput("dept_id", "10042"))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z")).build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.INVALID_PARAM_VALUE.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    @ParameterizedTest
    @CsvSource({"'', 2026-04-16T00:00Z, JFK_MISSING_REQUIRED_FIELD",
        "10042, '', JFK_MISSING_REQUIRED_FIELD", "10042, invalid, INVALID_TIME_FORMAT"})
    public void handlePreservesRequiredFieldAndTimeErrors(String deptId, String queryStart,
        StatusCode expected) {
        JfkDataSourcePB.Builder input = JfkDataSourcePB.newBuilder();
        if (!deptId.isEmpty()) input.addInputData(strInput("dept_id", deptId));
        if (!queryStart.isEmpty()) input.addInputData(strInput("query_start", queryStart));
        var result = new BalanceTimeRangeDataSourceHandler(mockSupport()).handle(input.build());

        assertThat(result.getFirst().getCode()).isEqualTo(expected.ordinal());
        assertThat(result.getSecond()).isNull();
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

    private static JfkFieldDataPB strInput(String id, String value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setStrVal(value).build())
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
