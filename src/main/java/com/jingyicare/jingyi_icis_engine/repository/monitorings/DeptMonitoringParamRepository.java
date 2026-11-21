package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParamId;

public interface DeptMonitoringParamRepository
        extends JpaRepository<DeptMonitoringParam, DeptMonitoringParamId> {
    List<DeptMonitoringParam> findAll();
    List<DeptMonitoringParam> findByIdDeptId(String deptId);
    List<DeptMonitoringParam> findByIdCode(String code);
    boolean existsByIdCode(String code);
    List<DeptMonitoringParam> findByIdDeptIdAndIdCodeIn(String deptId, Set<String> codes);

    Optional<DeptMonitoringParam> findByIdDeptIdAndIdCode(String deptId, String code);
}
