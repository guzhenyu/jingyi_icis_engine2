package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class MonitoringTimeRangeDataSourceHandler extends AbstractJfkDataSourceHandler {
    public MonitoringTimeRangeDataSourceHandler(JfkDataSourceSupport support) {
        super(support);
    }

    @Override
    public String getMetaId() {
        return META_ID;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, FIELD_PID);
        boolean hasPatient = pid != null && pid > 0;
        String deptId = getDeptIdInput(input, FIELD_DEPT_ID);
        String queryStartIso = getStringInput(input, FIELD_QUERY_START);
        if ((!hasPatient && StrUtils.isBlank(deptId)) || StrUtils.isBlank(queryStartIso)) {
            List<String> missingFields = new ArrayList<>();
            if (!hasPatient && StrUtils.isBlank(deptId)) missingFields.add(FIELD_DEPT_ID);
            if (StrUtils.isBlank(queryStartIso)) missingFields.add(FIELD_QUERY_START);
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        final int monStartHour;
        if (hasPatient) {
            Pair<ReturnCode, JfkDataSourceSupport.PatientDayContext> windowResult = support
                .resolvePatientDayContext(pid, queryStartIso);
            if (windowResult.getFirst().getCode() != StatusCode.OK.ordinal()) {
                return new Pair<>(windowResult.getFirst(), null);
            }
            monStartHour = windowResult.getSecond().dayWindow().monStartHour();
        } else {
            LocalDateTime utcStart = TimeUtils.fromIso8601String(queryStartIso, "UTC");
            if (utcStart == null) {
                return error(StatusCode.INVALID_TIME_FORMAT, FIELD_QUERY_START);
            }
            try {
                monStartHour = support.resolveStatisticsDayWindow(deptId, utcStart).monStartHour();
            } catch (IllegalArgumentException e) {
                return error(StatusCode.INVALID_PARAM_VALUE, e.getMessage());
            }
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        support.addArrayOutput(outputBuilder, FIELD_TIME_TXT, List.of(TIME_TEXT));
        support.addArrayOutput(outputBuilder, FIELD_UNIT_TXT, List.of(UNIT_TEXT));
        for (int i = 0; i < 24; i++) {
            support.addArrayOutput(outputBuilder, "hour" + (i + 1), List.of(formatHour(monStartHour + i)));
        }

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private String formatHour(int hour) {
        return Math.floorMod(hour, 24) + ":00";
    }

    private static final String META_ID = "monitoring_time_range";
    private static final String FIELD_PID = "pid";
    private static final String FIELD_DEPT_ID = "dept_id";
    private static final String FIELD_QUERY_START = "query_start";
    private static final String FIELD_TIME_TXT = "time_txt";
    private static final String FIELD_UNIT_TXT = "unit_txt";
    private static final String TIME_TEXT = "时间";
    private static final String UNIT_TEXT = "单位";
}
