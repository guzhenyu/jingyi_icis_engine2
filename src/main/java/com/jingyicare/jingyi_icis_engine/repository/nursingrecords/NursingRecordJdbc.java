package com.jingyicare.jingyi_icis_engine.repository.nursingrecords;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;

public final class NursingRecordJdbc {
    private NursingRecordJdbc() {}

    // 直接读取当前事务可见的数据，绕过 JPA 一级缓存；写入互斥由调用方的业务锁保证。
    public static List<NursingRecord> findActiveRecords(JdbcTemplate jdbcTemplate, List<Long> ids) {
        Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) return List.of();

        String placeholders = String.join(", ", Collections.nCopies(ids.size(), "?"));
        String sql = """
            SELECT id, patient_id, content, effective_time, is_deleted,
                   deleted_by, deleted_at, source, patient_critical_lis_handling_id,
                   reviewed_by, reviewed_by_account_name, reviewed_at,
                   modified_by, modified_by_account_name, modified_at,
                   created_by, created_by_account_name, created_at
            FROM nursing_records
            WHERE is_deleted = FALSE AND id IN (%s)
            ORDER BY id
            """.formatted(placeholders);

        return jdbcTemplate.query(sql, statement -> {
            for (int i = 0; i < ids.size(); i++) statement.setLong(i + 1, ids.get(i));
        }, (rs, rowNum) -> NursingRecord.builder()
            .id(rs.getLong("id"))
            .patientId(rs.getLong("patient_id"))
            .content(rs.getString("content"))
            .effectiveTime(rs.getObject("effective_time", LocalDateTime.class))
            .isDeleted(rs.getBoolean("is_deleted"))
            .deletedBy(rs.getString("deleted_by"))
            .deletedAt(rs.getObject("deleted_at", LocalDateTime.class))
            .source(rs.getString("source"))
            .patientCriticalLisHandlingId(rs.getObject("patient_critical_lis_handling_id",
                Integer.class))
            .reviewedBy(rs.getString("reviewed_by"))
            .reviewedByAccountName(rs.getString("reviewed_by_account_name"))
            .reviewedAt(rs.getObject("reviewed_at", LocalDateTime.class))
            .modifiedBy(rs.getString("modified_by"))
            .modifiedByAccountName(rs.getString("modified_by_account_name"))
            .modifiedAt(rs.getObject("modified_at", LocalDateTime.class))
            .createdBy(rs.getString("created_by"))
            .createdByAccountName(rs.getString("created_by_account_name"))
            .createdAt(rs.getObject("created_at", LocalDateTime.class))
            .build());
    }
}
