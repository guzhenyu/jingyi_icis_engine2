package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.LocalDateTime;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class MonitoringWindowResolver {
    public MonitoringWindowResolver(
        JfkDataSourceSupport support,
        BalanceStatsShiftRepository balanceStatsShiftRepo
    ) {
        this.support = support;
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
    }

    public Pair<ReturnCode, MonitoringWindow> resolve(Long pid, String queryStartIso) {
        PatientRecord patient = support.getPatientService().getPatientRecord(pid);
        if (patient == null) {
            return error(StatusCode.PATIENT_NOT_FOUND, "pid: " + pid);
        }

        String deptId = patient.getDeptId();
        if (StrUtils.isBlank(deptId)) {
            return error(StatusCode.DEPT_NOT_FOUND, "pid: " + pid);
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

        Integer monStartHour = normalizeStartHour(shift);
        if (monStartHour == null) {
            return error(
                StatusCode.INVALID_PARAM_VALUE,
                "deptId: " + deptId + ", balanceStatsShiftId: " + shift.getId()
            );
        }

        LocalDateTime utcMiddle = utcStart.plusHours(12);
        LocalDateTime localMiddle = TimeUtils.getLocalDateTimeFromUtc(utcMiddle, support.getZoneId());
        LocalDateTime localMidnight = localMiddle.toLocalDate().atStartOfDay();
        LocalDateTime monStartLocal = localMidnight.plusHours(monStartHour);
        LocalDateTime monStartUtc = TimeUtils.getUtcFromLocalDateTime(monStartLocal, support.getZoneId());
        LocalDateTime monEndUtc = monStartUtc.plusHours(24);
        LocalDateTime monEndLocal = TimeUtils.getLocalDateTimeFromUtc(monEndUtc, support.getZoneId());

        return new Pair<>(
            ReturnCodeUtils.getReturnCode(support.getStatusMsgList(), StatusCode.OK),
            new MonitoringWindow(patient, deptId, monStartUtc, monEndUtc, monStartLocal, monEndLocal, monStartHour)
        );
    }

    private Integer normalizeStartHour(BalanceStatsShift shift) {
        if (shift.getMonStartHour() != null && TimeUtils.isValidHour(shift.getMonStartHour())) {
            return shift.getMonStartHour();
        }
        if (shift.getStartHour() != null && TimeUtils.isValidHour(shift.getStartHour())) {
            return shift.getStartHour();
        }
        return null;
    }

    private Pair<ReturnCode, MonitoringWindow> error(StatusCode statusCode, String detail) {
        return new Pair<>(
            ReturnCodeUtils.getReturnCode(support.getStatusMsgList(), statusCode, detail),
            null
        );
    }

    private static final String FIELD_QUERY_START = "query_start";

    private final JfkDataSourceSupport support;
    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
}
