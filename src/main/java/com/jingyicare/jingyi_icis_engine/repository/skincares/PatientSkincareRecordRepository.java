package com.jingyicare.jingyi_icis_engine.repository.skincares;

import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincareRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PatientSkincareRecordRepository extends JpaRepository<PatientSkincareRecord, Long> {
    List<PatientSkincareRecord> findByDeptIdAndPidAndIsDeletedFalse(String deptId, Long pid);

    List<PatientSkincareRecord> findByPidAndIsDeletedFalse(Long pid);

    List<PatientSkincareRecord> findByPatientSkincarePlanIdAndIsDeletedFalse(Long patientSkincarePlanId);

    List<PatientSkincareRecord> findByPidAndCreatedAtBetweenAndIsDeletedFalse(
        Long pid, LocalDateTime startTime, LocalDateTime endTime
    );

    @Query("""
        select record
        from PatientSkincareRecord record
        where record.pid = :pid
          and record.createdAt >= :startTime
          and record.createdAt < :endTime
          and record.isDeleted = false
        order by record.createdAt asc, record.id asc
        """)
    List<PatientSkincareRecord> findReportSkincareRecords(
        @Param("pid") Long pid,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );

    Optional<PatientSkincareRecord> findByIdAndIsDeletedFalse(Long id);
}
