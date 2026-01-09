package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeptMonitoringGroupParamRepository extends JpaRepository<DeptMonitoringGroupParam, Integer> {
    List<DeptMonitoringGroupParam> findAll();

    List<DeptMonitoringGroupParam> findAllByIsDeletedFalse();

    List<DeptMonitoringGroupParam> findByDeptMonitoringGroupIdAndIsDeletedFalse(Integer deptMonitoringGroupId);

    List<DeptMonitoringGroupParam> findByMonitoringParamCodeAndIsDeletedFalse(String monitoringParamCode);

    List<DeptMonitoringGroupParam> findByDeptMonitoringGroupIdInAndIsDeletedFalse(List<Integer> groupIds);

    Optional<DeptMonitoringGroupParam> findByDeptMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(Integer deptMonitoringGroupId, String monitoringParamCode);

    Optional<DeptMonitoringGroupParam> findByIdAndIsDeletedFalse(Integer id);

    Boolean existsByDeptMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(Integer deptMonitoringGroupId, String monitoringParamCode);
}