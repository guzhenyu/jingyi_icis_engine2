package com.jingyicare.jingyi_icis_engine.repository.snapshots;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

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
            if (!StrUtils.isBlank(deptId) && !deptId.isBlank()) {
                departments.put(rs.getLong("id"), deptId);
            }
        });
        return departments;
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }
}
