package com.jingyicare.jingyi_icis_engine.repository.scores;

import java.time.LocalDateTime;

import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;

public final class PatientScoreJdbc {
    private PatientScoreJdbc() {}

    // 动态锁预读和锁后复查绕过 JPA 一级缓存；业务写入仍通过 Repository 完成。
    public static PatientScore findActiveRecord(JdbcTemplate jdbcTemplate, long id) {
        return jdbcTemplate.query("""
            SELECT id, pid, score_group_code, score, score_str, effective_time, note,
                   modified_by, modified_by_account_name, modified_at
            FROM patient_scores WHERE id = ? AND is_deleted = FALSE
            """, statement -> statement.setLong(1, id), (rs, rowNum) -> PatientScore.builder()
                .id(rs.getLong("id"))
                .pid(rs.getLong("pid"))
                .scoreGroupCode(rs.getString("score_group_code"))
                .score(rs.getString("score"))
                .scoreStr(rs.getString("score_str"))
                .effectiveTime(rs.getObject("effective_time", LocalDateTime.class))
                .note(rs.getString("note"))
                .modifiedBy(rs.getString("modified_by"))
                .modifiedByAccountName(rs.getString("modified_by_account_name"))
                .modifiedAt(rs.getObject("modified_at", LocalDateTime.class))
                .isDeleted(false).build())
            .stream().findFirst().orElse(null);
    }
}
