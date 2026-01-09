package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatientMonitoringGroupRepository extends JpaRepository<PatientMonitoringGroup, Integer> {
    List<PatientMonitoringGroup> findAll();

    Optional<PatientMonitoringGroup> findByIdAndIsDeletedFalse(Integer id);

    List<PatientMonitoringGroup> findByDeptMonitoringGroupIdAndIsDeletedFalse(Integer deptMonitoringGroupId);

    List<PatientMonitoringGroup> findByPidAndIsDeletedFalse(Long pid);

    List<PatientMonitoringGroup> findByPid(Long pid);

    List<PatientMonitoringGroup> findByDeptMonitoringGroupIdInAndIsDeletedFalse(List<Integer> deptMonitoringGroupIds);

    Optional<PatientMonitoringGroup> findByDeptMonitoringGroupIdAndPidAndIsDeletedFalse(Integer deptMonitoringGroupId, Long pid);
}