package com.jingyicare.jingyi_icis_engine.repository.shifts;

import org.springframework.jdbc.core.JdbcTemplate;

public final class BalanceStatsShiftJdbc {
    private BalanceStatsShiftJdbc() {}

    public static String findActiveDeptId(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.queryForList("""
            SELECT dept_id
            FROM balance_stats_shifts
            WHERE id = ? AND is_deleted = FALSE
            """, String.class, id)
            .stream().findFirst().orElse(null);
    }
}
