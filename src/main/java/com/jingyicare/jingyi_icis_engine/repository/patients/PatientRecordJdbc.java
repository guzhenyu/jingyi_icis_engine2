package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;

public final class PatientRecordJdbc {
    private PatientRecordJdbc() {}

    // 预读和锁后复查共用查询；只在旧档匹配且待入科档唯一时才计划删档。
    public static List<Long> findReadmissionCandidates(
        JdbcTemplate jdbcTemplate, String deptId, String hisMrn, int pendingStatus, long lastPid
    ) {
        return jdbcTemplate.queryForList("""
            SELECT pending.id
            FROM patient_records pending
            WHERE pending.dept_id = ? AND pending.his_mrn = ? AND pending.admission_status = ?
                AND EXISTS (
                    SELECT 1 FROM patient_records previous
                    WHERE previous.id = ? AND COALESCE(previous.his_mrn, '') = pending.his_mrn
                )
            ORDER BY pending.id
            LIMIT 2
            """, Long.class, deptId, hisMrn, pendingStatus, lastPid);
    }
}
