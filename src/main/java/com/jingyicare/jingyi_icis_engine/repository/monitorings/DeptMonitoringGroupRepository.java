package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface DeptMonitoringGroupRepository extends JpaRepository<DeptMonitoringGroup, Integer> {
    
    List<DeptMonitoringGroup> findAll();

    List<DeptMonitoringGroup> findByIdIn(List<Integer> ids);

    List<DeptMonitoringGroup> findByIdInAndGroupType(List<Integer> ids, Integer groupType);

    List<DeptMonitoringGroup> findAllByIsDeletedFalse();

    List<DeptMonitoringGroup> findByDeptIdAndIsDeletedFalse(String deptId);

    List<DeptMonitoringGroup> findByDeptIdAndGroupTypeAndIsDeletedFalse(String deptId, Integer groupType);

    @Query("SELECT DISTINCT d.deptId FROM DeptMonitoringGroup d WHERE d.isDeleted = false")
    Set<String> findAllDeptIds();

    Optional<DeptMonitoringGroup> findByIdAndIsDeletedFalse(Integer id);

    Optional<DeptMonitoringGroup> findById(Integer id);

    Optional<DeptMonitoringGroup> findByDeptIdAndNameAndIsDeletedFalse(String deptId, String name);

    Optional<DeptMonitoringGroup> findByIdAndDeptId(Integer id, String deptId);

    Boolean existsByDeptIdAndIsDeletedFalse(String deptId);
}
