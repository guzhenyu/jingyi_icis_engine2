package com.jingyicare.jingyi_icis_engine.repository.settings;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.settings.BalanceStatsShift;

public final class BalanceStatsShiftJdbc {
    private BalanceStatsShiftJdbc() {}

    public static List<BalanceStatsShift> findActiveByDept(
        JdbcTemplate jdbcTemplate, String deptId
    ) {
        return jdbcTemplate.query("""
            SELECT id, dept_id, start_hour, half_day_shift_hours, mon_start_hour, effective_time,
                   deleted_by, deleted_at, modified_by, modified_by_account_name, modified_at
            FROM balance_stats_shifts
            WHERE dept_id = ? AND is_deleted = FALSE
            ORDER BY effective_time DESC
            """, (rs, rowNum) -> BalanceStatsShift.builder()
                .id(rs.getLong("id"))
                .deptId(rs.getString("dept_id"))
                .startHour(rs.getObject("start_hour", Integer.class))
                .halfDayShiftHours(rs.getObject("half_day_shift_hours", Integer.class))
                .monStartHour(rs.getObject("mon_start_hour", Integer.class))
                .effectiveTime(rs.getObject("effective_time", LocalDateTime.class))
                .isDeleted(false)
                .deletedBy(rs.getString("deleted_by"))
                .deletedAt(rs.getObject("deleted_at", LocalDateTime.class))
                .modifiedBy(rs.getString("modified_by"))
                .modifiedByAccountName(rs.getString("modified_by_account_name"))
                .modifiedAt(rs.getObject("modified_at", LocalDateTime.class))
                .build(), deptId);
    }

    public static String findActiveDeptId(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForList("""
            SELECT dept_id
            FROM balance_stats_shifts
            WHERE id = ? AND is_deleted = FALSE
            """, String.class, id)
            .stream().findFirst().orElse(null);
    }
}
