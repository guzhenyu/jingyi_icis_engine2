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
public class MedexeTimeRangeDataSourceHandler extends AbstractJfkDataSourceHandler {
    public MedexeTimeRangeDataSourceHandler(
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
        String deptId = getStringInput(input, FIELD_DEPT_ID);
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

        Integer startHour = shift.getStartHour();
        if (startHour == null || !TimeUtils.isValidHour(startHour)) {
            return error(
                StatusCode.INVALID_PARAM_VALUE,
                "deptId: " + deptId + ", balanceStatsShiftId: " + shift.getId() + ", start_hour: " + startHour
            );
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        support.addArrayOutput(outputBuilder, FIELD_MED_ORDER_TXT, List.of(MED_ORDER_TEXT));
        support.addArrayOutput(outputBuilder, FIELD_ROUTE_TXT, List.of(ROUTE_TEXT));
        support.addArrayOutput(outputBuilder, FIELD_ACC_ML, List.of(ACC_TEXT));
        for (int i = 0; i < 24; i++) {
            support.addArrayOutput(outputBuilder, "hour" + (i + 1), List.of(formatHour(startHour + i)));
        }

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private String formatHour(int hour) {
        return Math.floorMod(hour, 24) + ":00";
    }

    private static final String META_ID = "medexe_time_range";
    private static final String FIELD_DEPT_ID = "dept_id";
    private static final String FIELD_QUERY_START = "query_start";
    private static final String FIELD_MED_ORDER_TXT = "med_order_txt";
    private static final String FIELD_ROUTE_TXT = "route_txt";
    private static final String FIELD_ACC_ML = "acc_ml";
    private static final String MED_ORDER_TEXT = "用药医嘱";
    private static final String ROUTE_TEXT = "用药途径";
    private static final String ACC_TEXT = "累计量";

    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
}
