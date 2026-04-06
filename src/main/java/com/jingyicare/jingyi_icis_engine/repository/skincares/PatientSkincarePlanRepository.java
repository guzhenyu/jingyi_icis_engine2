package com.jingyicare.jingyi_icis_engine.repository.skincares;

import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincarePlan;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PatientSkincarePlanRepository extends JpaRepository<PatientSkincarePlan, Long> {
    List<PatientSkincarePlan> findByDeptIdAndPidAndIsDeletedFalse(String deptId, Long pid);

    List<PatientSkincarePlan> findByPidAndIsDeletedFalse(Long pid);

    List<PatientSkincarePlan> findByPidAndSkincareTypeIdAndIsDeletedFalse(Long pid, Integer skincareTypeId);

    List<PatientSkincarePlan> findByPidAndCreatedAtBetweenAndIsDeletedFalse(
        Long pid, LocalDateTime startTime, LocalDateTime endTime
    );

    Optional<PatientSkincarePlan> findByIdAndIsDeletedFalse(Long id);
}
