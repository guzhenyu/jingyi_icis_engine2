package com.jingyicare.jingyi_icis_engine.repository.doctors;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.jingyicare.jingyi_icis_engine.entity.doctors.DeptDoctorScoreType;

public interface DeptDoctorScoreTypeRepository extends JpaRepository<DeptDoctorScoreType, Integer> {
    List<DeptDoctorScoreType> findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(String deptId);

    Optional<DeptDoctorScoreType> findByDeptIdAndCodeAndIsDeletedFalse(String deptId, String code);
}