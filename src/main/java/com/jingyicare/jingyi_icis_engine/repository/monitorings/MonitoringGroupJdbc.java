package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import org.springframework.jdbc.core.JdbcTemplate;

public final class MonitoringGroupJdbc {
    private MonitoringGroupJdbc() {}

    public static String findActiveGroupDeptId(JdbcTemplate jdbcTemplate, int groupId) {
        return jdbcTemplate.query("""
            SELECT dept_id FROM dept_monitoring_groups
            WHERE id = ? AND is_deleted = FALSE
            """, (rs, rowNum) -> rs.getString("dept_id"), groupId)
            .stream().findFirst().orElse(null);
    }

    public static String findGroupParamDeptId(JdbcTemplate jdbcTemplate, int paramId) {
        // 历史分组的有效参数仍可按组名映射到当前版本，保持原删除行为。
        return jdbcTemplate.query("""
            SELECT g.dept_id FROM dept_monitoring_group_params p
            JOIN dept_monitoring_groups g ON g.id = p.dept_monitoring_group_id
            WHERE p.id = ? AND p.is_deleted = FALSE
            """, (rs, rowNum) -> rs.getString("dept_id"), paramId)
            .stream().findFirst().orElse(null);
    }
}
