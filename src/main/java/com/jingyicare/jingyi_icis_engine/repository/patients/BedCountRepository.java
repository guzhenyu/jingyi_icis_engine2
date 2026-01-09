package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedCount;

public interface BedCountRepository extends JpaRepository<BedCount, Long> {
    List<BedCount> findByIsDeletedFalseOrderByDeptIdAscEffectiveTimeDesc();
    List<BedCount> findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(String deptId);
    List<BedCount> findByDeptIdAndIsDeletedFalse(String deptId);

    Optional<BedCount> findByDeptIdAndEffectiveTimeAndIsDeletedFalse(String deptId, LocalDateTime effectiveTime);
    Optional<BedCount> findByIdAndIsDeletedFalse(Integer id);
}