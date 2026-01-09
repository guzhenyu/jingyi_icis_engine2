package com.jingyicare.jingyi_icis_engine.repository.checklists;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.checklists.DeptChecklistGroup;

public interface DeptChecklistGroupRepository extends JpaRepository<DeptChecklistGroup, Integer> {
    List<DeptChecklistGroup> findAllByIsDeletedFalse();
    List<DeptChecklistGroup> findByDeptId(String deptId);
    List<DeptChecklistGroup> findByDeptIdAndIsDeletedFalse(String deptId);
    List<DeptChecklistGroup> findByIdInAndIsDeletedFalse(List<Integer> ids);
    List<DeptChecklistGroup> findByIdIn(List<Integer> ids);

    Optional<DeptChecklistGroup> findByIdAndIsDeletedFalse(Integer id);
}