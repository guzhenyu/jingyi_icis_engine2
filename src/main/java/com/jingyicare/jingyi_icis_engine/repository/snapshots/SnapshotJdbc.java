package com.jingyicare.jingyi_icis_engine.repository.snapshots;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

/** 只用于动态锁计划及锁后复查，绕过 JPA 一级缓存。 */
public final class SnapshotJdbc {
    private SnapshotJdbc() {}

    public static Map<Long, String> findPatientDepartments(
        JdbcTemplate jdbcTemplate, Collection<Long> patientIds
    ) {
        if (patientIds.isEmpty()) return Map.of();
        String sql = "SELECT id, dept_id FROM patient_records WHERE id IN (%s)"
            .formatted(placeholders(patientIds.size()));
        Map<Long, String> departments = new LinkedHashMap<>();
        jdbcTemplate.query(sql, statement -> {
            int index = 1;
            for (Long pid : patientIds) statement.setLong(index++, pid);
        }, rs -> {
            String deptId = rs.getString("dept_id");
            if (!StrUtils.isBlank(deptId)) departments.put(rs.getLong("id"), deptId);
        });
        return departments;
    }

    public static Map<String, List<BalanceStatsShift>> findActiveBalanceStatsShifts(
        JdbcTemplate jdbcTemplate, Collection<String> departmentIds
    ) {
        if (departmentIds.isEmpty()) return Map.of();
        String sql = """
            SELECT dept_id, effective_time, start_hour, mon_start_hour, half_day_shift_hours
            FROM balance_stats_shifts
            WHERE is_deleted = FALSE AND dept_id IN (%s)
            ORDER BY effective_time DESC
            """.formatted(placeholders(departmentIds.size()));
        List<BalanceStatsShift> shifts = jdbcTemplate.query(sql, statement -> {
            int index = 1;
            for (String deptId : departmentIds) statement.setString(index++, deptId);
        }, (rs, rowNum) -> BalanceStatsShift.builder()
            .deptId(rs.getString("dept_id"))
            .effectiveTime(rs.getObject("effective_time", LocalDateTime.class))
            .startHour(rs.getObject("start_hour", Integer.class))
            .monStartHour(rs.getObject("mon_start_hour", Integer.class))
            .halfDayShiftHours(rs.getObject("half_day_shift_hours", Integer.class))
            .build());
        return shifts.stream().collect(Collectors.groupingBy(BalanceStatsShift::getDeptId));
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
