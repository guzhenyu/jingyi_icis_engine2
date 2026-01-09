package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import java.time.LocalDateTime;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class NursingRecordUtils {
    public StatusCode updateNursingRecord(
        long entityId, String content, LocalDateTime effectiveTimeUtc,
        String modifiedBy, String modifiedByAccountName, boolean enableUpdatingCreatedBy
    ) {
        if (entityId <= 0 || StrUtils.isBlank(content)) {
            return StatusCode.INVALID_PARAM_VALUE;
        }

        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        NursingRecord lockedOld = null;

        // ---------- 阶段1：锁定并逻辑删除旧记录 ----------
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            // 1.1 锁定旧行（仅锁活动行）
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT id, patient_id, content, effective_time, is_deleted,
                           deleted_by, deleted_at, source, patient_critical_lis_handling_id,
                           reviewed_by, reviewed_by_account_name, reviewed_at,
                           modified_by, modified_by_account_name, modified_at,
                           created_by, created_by_account_name, created_at
                    FROM nursing_records
                    WHERE id = ? AND is_deleted = FALSE
                    FOR UPDATE
                """)
            ) {
                ps.setLong(1, entityId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        conn.rollback();
                        return StatusCode.NURSING_RECORD_NOT_EXISTS;
                    }
                    lockedOld = mapRow(rs);
                }
            }

            // 1.2 幂等：内容与时间均未变，直接返回 OK
            if (Objects.equals(lockedOld.getContent(), content)) {
                conn.rollback();
                return StatusCode.OK;
            }

            // 1.3 逻辑删除旧行
            try (PreparedStatement ps = conn.prepareStatement("""
                    UPDATE nursing_records
                    SET is_deleted = TRUE,
                        deleted_by = ?,
                        deleted_at = ?
                    WHERE id = ? AND is_deleted = FALSE
                """)) {
                ps.setString(1, modifiedBy);
                ps.setTimestamp(2, Timestamp.valueOf(nowUtc));
                ps.setLong(3, lockedOld.getId());
                ps.executeUpdate();
            }

            conn.commit(); // 提交阶段1（释放唯一索引占用）
        } catch (Exception e) {
            log.error("updateNursingRecord stage-1 failed (logical delete). entityId={}", entityId, e);
            return StatusCode.INTERNAL_EXCEPTION;
        }

        // ---------- 阶段2：插入新行 ----------
        NursingRecord inserted = null;
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);

            NursingRecord toInsert = NursingRecord.builder()
                    .patientId(lockedOld.getPatientId())
                    .content(content)
                    .effectiveTime(effectiveTimeUtc == null ? lockedOld.getEffectiveTime() : effectiveTimeUtc)
                    .isDeleted(false)
                    .deletedBy(null)
                    .deletedAt(null)
                    .source(lockedOld.getSource())
                    .patientCriticalLisHandlingId(lockedOld.getPatientCriticalLisHandlingId())
                    // 审核信息：按需保留；若变更即作废审核，请在此清空
                    .reviewedBy(lockedOld.getReviewedBy())
                    .reviewedByAccountName(lockedOld.getReviewedByAccountName())
                    .reviewedAt(lockedOld.getReviewedAt())
                    // 修改信息
                    .modifiedBy(modifiedBy)
                    .modifiedByAccountName(modifiedByAccountName)
                    .modifiedAt(nowUtc)
                    // 创建信息：可选刷新
                    .createdBy(enableUpdatingCreatedBy ? modifiedBy : lockedOld.getCreatedBy())
                    .createdByAccountName(enableUpdatingCreatedBy ? modifiedByAccountName : lockedOld.getCreatedByAccountName())
                    .createdAt(enableUpdatingCreatedBy ? nowUtc : lockedOld.getCreatedAt())
                    .build();

            insertRecord(conn, toInsert);
            conn.commit();
        } catch (Exception e) {
            log.error("updateNursingRecord stage-2 failed (insert). old entityId={}", entityId, e);
            return StatusCode.INTERNAL_EXCEPTION;
        }

        // 阶段3：如果lockedOld.content为空，删除lockedOld
        if (StrUtils.isBlank(lockedOld.getContent())) {
            try (Connection conn = dataSource.getConnection()) {
                conn.setAutoCommit(false);
                try (PreparedStatement ps = conn.prepareStatement(
                        "DELETE FROM nursing_records WHERE id = ?")) {
                    ps.setLong(1, lockedOld.getId());
                    ps.executeUpdate();
                }
                conn.commit();
            } catch (Exception e) {
                log.error("updateNursingRecord stage-3 failed (delete). old entityId={}", entityId, e);
                return StatusCode.INTERNAL_EXCEPTION;
            }
        }

        return StatusCode.OK;
    }

    private NursingRecord mapRow(ResultSet rs) throws SQLException {
        NursingRecord r = new NursingRecord();
        r.setId(rs.getLong("id"));
        r.setPatientId(rs.getLong("patient_id"));
        r.setContent(rs.getString("content"));
        r.setEffectiveTime(rs.getTimestamp("effective_time").toLocalDateTime());
        r.setIsDeleted(rs.getBoolean("is_deleted"));
        r.setDeletedBy(rs.getString("deleted_by"));
        r.setDeletedAt(getNullableLocalDateTime(rs, "deleted_at"));
        r.setSource(rs.getString("source"));
        int lisId = rs.getInt("patient_critical_lis_handling_id");
        r.setPatientCriticalLisHandlingId(rs.wasNull() ? null : lisId);
        r.setReviewedBy(rs.getString("reviewed_by"));
        r.setReviewedByAccountName(rs.getString("reviewed_by_account_name"));
        r.setReviewedAt(getNullableLocalDateTime(rs, "reviewed_at"));
        r.setModifiedBy(rs.getString("modified_by"));
        r.setModifiedByAccountName(rs.getString("modified_by_account_name"));
        r.setModifiedAt(getNullableLocalDateTime(rs, "modified_at"));
        r.setCreatedBy(rs.getString("created_by"));
        r.setCreatedByAccountName(rs.getString("created_by_account_name"));
        r.setCreatedAt(getNullableLocalDateTime(rs, "created_at"));
        return r;
    }

    private LocalDateTime getNullableLocalDateTime(ResultSet rs, String col) throws SQLException {
        Timestamp ts = rs.getTimestamp(col);
        return (ts == null) ? null : ts.toLocalDateTime();
    }

    private void insertRecord(Connection conn, NursingRecord r) throws SQLException {
        final String sql = """
            INSERT INTO nursing_records (
                patient_id,
                content,
                effective_time,
                is_deleted,
                deleted_by,
                deleted_at,
                source,
                patient_critical_lis_handling_id,
                reviewed_by,
                reviewed_by_account_name,
                reviewed_at,
                modified_by,
                modified_by_account_name,
                modified_at,
                created_by,
                created_by_account_name,
                created_at
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
        """;

        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            ps.setLong(i++, r.getPatientId());
            ps.setString(i++, r.getContent());
            ps.setTimestamp(i++, Timestamp.valueOf(r.getEffectiveTime()));
            ps.setBoolean(i++, Boolean.TRUE.equals(r.getIsDeleted()));
            setNullableString(ps, i++, r.getDeletedBy());
            setNullableTimestamp(ps, i++, r.getDeletedAt());
            setNullableString(ps, i++, r.getSource());
            setNullableInteger(ps, i++, r.getPatientCriticalLisHandlingId());
            setNullableString(ps, i++, r.getReviewedBy());
            setNullableString(ps, i++, r.getReviewedByAccountName());
            setNullableTimestamp(ps, i++, r.getReviewedAt());
            setNullableString(ps, i++, r.getModifiedBy());
            setNullableString(ps, i++, r.getModifiedByAccountName());
            setNullableTimestamp(ps, i++, r.getModifiedAt());
            setNullableString(ps, i++, r.getCreatedBy());
            setNullableString(ps, i++, r.getCreatedByAccountName());
            setNullableTimestamp(ps, i++, r.getCreatedAt());

            ps.executeUpdate();
            return;
        }
    }

    private void setNullableString(PreparedStatement ps, int idx, String v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.VARCHAR); else ps.setString(idx, v);
    }
    private void setNullableInteger(PreparedStatement ps, int idx, Integer v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.INTEGER); else ps.setInt(idx, v);
    }
    private void setNullableTimestamp(PreparedStatement ps, int idx, LocalDateTime v) throws SQLException {
        if (v == null) ps.setNull(idx, Types.TIMESTAMP); else ps.setTimestamp(idx, Timestamp.valueOf(v));
    }

    @Autowired
    private DataSource dataSource;
}
