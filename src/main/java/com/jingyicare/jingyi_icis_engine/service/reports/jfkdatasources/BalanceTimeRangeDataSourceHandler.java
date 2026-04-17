package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class BalanceTimeRangeDataSourceHandler extends AbstractJfkDataSourceHandler {
    public BalanceTimeRangeDataSourceHandler(
        JfkDataSourceSupport support,
        BalanceStatsShiftRepository balanceStatsShiftRepo
    ) {
        super(support);
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
    }

    @Override
    public String getMetaId() {
        return META_ID;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        String deptId = getDeptIdInput(input, FIELD_DEPT_ID);
        String queryStartIso = getStringInput(input, FIELD_QUERY_START);
        if (StrUtils.isBlank(deptId) || StrUtils.isBlank(queryStartIso)) {
            List<String> missingFields = new ArrayList<>();
            if (StrUtils.isBlank(deptId)) missingFields.add(FIELD_DEPT_ID);
            if (StrUtils.isBlank(queryStartIso)) missingFields.add(FIELD_QUERY_START);
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        LocalDateTime utcStart = TimeUtils.fromIso8601String(queryStartIso, "UTC");
        if (utcStart == null) {
            return error(StatusCode.INVALID_TIME_FORMAT, FIELD_QUERY_START);
        }
        LocalDateTime utcEnd = utcStart.plusHours(24);

        BalanceStatsShift shift = balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId, utcEnd)
            .orElse(null);
        if (shift == null) {
            return error(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND, "deptId: " + deptId);
        }

        int balanceStartHour = shift.getStartHour();
        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        support.addArrayOutput(outputBuilder, FIELD_TIME_TXT, List.of(TIME_TEXT));
        support.addArrayOutput(outputBuilder, FIELD_ACC_ML, List.of(ACC_ML_TEXT));
        for (int i = 0; i < 24; i++) {
            support.addArrayOutput(outputBuilder, "hour" + (i + 1), List.of(formatHour(balanceStartHour + i)));
        }

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private String formatHour(int hour) {
        return Math.floorMod(hour, 24) + ":00";
    }

    private static final String META_ID = "balance_time_range";
    private static final String FIELD_DEPT_ID = "dept_id";
    private static final String FIELD_QUERY_START = "query_start";
    private static final String FIELD_TIME_TXT = "time_txt";
    private static final String FIELD_ACC_ML = "acc_ml";
    private static final String TIME_TEXT = "时间";
    private static final String ACC_ML_TEXT = "累计量ml";

    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
}
