package com.jingyicare.jingyi_icis_engine.repository.settings;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.settings.*;

public interface DeptSystemSettingsRepository extends JpaRepository<DeptSystemSettings, DeptSystemSettingsId> {
    List<DeptSystemSettings> findAll();
    Optional<DeptSystemSettings> findById(DeptSystemSettingsId id);
    List<DeptSystemSettings> findByIdDeptId(String deptId);
    List<DeptSystemSettings> findByIdFunctionId(Integer functionId);
}