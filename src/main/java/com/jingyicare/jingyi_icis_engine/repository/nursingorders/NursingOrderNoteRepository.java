package com.jingyicare.jingyi_icis_engine.repository.nursingorders;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.jingyicare.jingyi_icis_engine.entity.nursingorders.NursingOrderNote;

@Repository
public interface NursingOrderNoteRepository extends JpaRepository<NursingOrderNote, Integer> {
    @Query("SELECT non FROM NursingOrderNote non " +
        "WHERE non.deptId = :deptId AND non.isDeleted = false " +
        "ORDER BY CASE WHEN non.displayOrder IS NULL THEN 1 ELSE 0 END, non.displayOrder ASC, non.id ASC")
    List<NursingOrderNote> findByDeptIdAndIsDeletedFalseOrdered(@Param("deptId") String deptId);

    Optional<NursingOrderNote> findByIdAndIsDeletedFalse(Integer id);

    Optional<NursingOrderNote> findByDeptIdAndContentAndIsDeletedFalse(String deptId, String content);

    @Query("SELECT COALESCE(MAX(non.displayOrder), 0) FROM NursingOrderNote non " +
        "WHERE non.deptId = :deptId AND non.isDeleted = false")
    Integer findMaxDisplayOrderByDeptId(@Param("deptId") String deptId);
}
