package com.jingyicare.jingyi_icis_engine.repository.skincares;

import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincareRecord;
import org.springframework.data.jpa.repository.JpaRepository;

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

    Optional<PatientSkincareRecord> findByIdAndIsDeletedFalse(Long id);
}
