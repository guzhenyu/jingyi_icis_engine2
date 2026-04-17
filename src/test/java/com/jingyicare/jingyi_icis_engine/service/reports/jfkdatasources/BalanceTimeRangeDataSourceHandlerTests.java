package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
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

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

public class BalanceTimeRangeDataSourceHandlerTests {
    @Test
    public void handleReturnsTwentyFourHourLabelsFromBalanceStartHour() {
        JfkDataSourceSupport support = mockSupport();
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        BalanceTimeRangeDataSourceHandler handler =
            new BalanceTimeRangeDataSourceHandler(support, balanceStatsShiftRepo);

        LocalDateTime utcEnd = LocalDateTime.of(2026, 4, 17, 0, 0);
        when(balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("10042", utcEnd))
            .thenReturn(Optional.of(BalanceStatsShift.builder()
                .deptId("10042")
                .startHour(8)
                .monStartHour(7)
                .effectiveTime(LocalDateTime.of(2026, 4, 1, 0, 0))
                .isDeleted(false)
                .build()));

        Pair<ReturnCode, JfkDataSourcePB> result = handler.handle(JfkDataSourcePB.newBuilder()
            .setId("request-1")
            .setMetaId("balance_time_range")
            .addInputData(strInput("dept_id", "10042"))
            .addInputData(strInput("query_start", "2026-04-16T00:00Z"))
            .build());

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Map<String, List<String>> output = toOutputMap(result.getSecond());
        assertThat(output.get("time_txt")).containsExactly("出入量");
        assertThat(output.get("unit_txt")).containsExactly("单位");
        assertThat(output.get("hour1")).containsExactly("8:00");
        assertThat(output.get("hour2")).containsExactly("9:00");
        assertThat(output.get("hour17")).containsExactly("0:00");
        assertThat(output.get("hour24")).containsExactly("7:00");
        assertThat(output).containsKeys("hour1", "hour24").hasSize(26);

        verify(balanceStatsShiftRepo)
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc("10042", utcEnd);
    }

    @Test
    public void handleReturnsBalanceStatsShiftNotFoundWhenShiftIsMissing() {
        JfkDataSourceSupport support = mockSupport();
        BalanceStatsShiftRepository balanceStatsShiftRepo = mock(BalanceStatsShiftRepository.class);
        BalanceTimeRangeDataSourceHandler handler =
            new BalanceTimeRangeDataSourceHandler(support, balanceStatsShiftRepo);

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

        assertThat(result.getFirst().getCode()).isEqualTo(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND.ordinal());
        assertThat(result.getSecond()).isNull();
    }

    private JfkDataSourceSupport mockSupport() {
        JfkDataSourceSupport support = mock(JfkDataSourceSupport.class);
        when(support.getStatusMsgList()).thenReturn(Collections.nCopies(400, "status"));
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
