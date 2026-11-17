package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

public final class BedJdbc {
    private BedJdbc() {}

    public record ConfigLocation(String deptId) {}
    public record CountLocation(String deptId, LocalDateTime effectiveTimeUtc) {}

    public static Optional<ConfigLocation> findConfigLocation(JdbcTemplate jdbc, long configId) {
        return jdbc.query("""
            SELECT department_id FROM bed_configs WHERE id = ? AND is_deleted = FALSE
            """, (rs, rowNum) -> new ConfigLocation(rs.getString("department_id")), configId)
            .stream().findFirst();
    }

    public static Optional<CountLocation> findActiveCountLocation(JdbcTemplate jdbc, int countId) {
        return jdbc.query("""
            SELECT dept_id, effective_time FROM bed_counts WHERE id = ? AND is_deleted = FALSE
            """, (rs, rowNum) -> new CountLocation(
                rs.getString("dept_id"), rs.getObject("effective_time", LocalDateTime.class)),
            countId).stream().findFirst();
    }
}
