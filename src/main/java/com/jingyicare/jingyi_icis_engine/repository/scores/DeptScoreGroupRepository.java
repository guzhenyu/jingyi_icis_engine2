package com.jingyicare.jingyi_icis_engine.repository.scores;

import java.util.Optional;
import java.util.List;
import java.util.Set;

import com.jingyicare.jingyi_icis_engine.entity.scores.DeptScoreGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface DeptScoreGroupRepository extends JpaRepository<DeptScoreGroup, Integer> {
    Optional<DeptScoreGroup> findByIdAndIsDeletedFalse(Integer id);

    List<DeptScoreGroup> findAllByIsDeletedFalse();

    List<DeptScoreGroup> findByDeptIdAndIsDeletedFalse(String deptId);

    Optional<DeptScoreGroup> findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(String deptId, String scoreGroupCode);

    @Query("SELECT DISTINCT d.deptId FROM DeptScoreGroup d WHERE d.isDeleted = false")
    Set<String> findAllDeptIds();

    boolean existsByDeptIdAndScoreGroupCodeAndIsDeletedFalse(String deptId, String scoreGroupCode);
}