package com.jingyicare.jingyi_icis_engine.repository.scores;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DeptScoreGroupJdbc {
    private DeptScoreGroupJdbc() {}

    public record DeptScoreGroupKey(Integer id, String deptId, String scoreGroupCode) {}

    // 锁计划只读取资源标识，绕过 JPA 一级缓存；软删除后仍保留原资源映射。
    public static Optional<DeptScoreGroupKey> findKeyById(JdbcTemplate jdbcTemplate, Integer id) {
        return jdbcTemplate.query("""
            SELECT id, dept_id, score_group_code
            FROM dept_score_groups
            WHERE id = ?
            """, (rs, rowNum) -> new DeptScoreGroupKey(
                rs.getInt("id"), rs.getString("dept_id"), rs.getString("score_group_code")),
            id).stream().findFirst();
    }

    public static List<DeptScoreGroupKey> findActiveKeysByIds(
        JdbcTemplate jdbcTemplate, List<Integer> ids
    ) {
        if (ids.isEmpty()) return List.of();

        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        String sql = """
            SELECT id, dept_id, score_group_code
            FROM dept_score_groups
            WHERE is_deleted = FALSE AND id IN (%s)
            ORDER BY id
            """.formatted(placeholders);
        return jdbcTemplate.query(sql, statement -> {
            for (int i = 0; i < ids.size(); i++) statement.setInt(i + 1, ids.get(i));
        }, (rs, rowNum) -> new DeptScoreGroupKey(
            rs.getInt("id"), rs.getString("dept_id"), rs.getString("score_group_code")));
    }
}
