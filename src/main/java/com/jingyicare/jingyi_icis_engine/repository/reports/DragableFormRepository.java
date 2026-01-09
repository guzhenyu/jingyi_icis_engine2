package com.jingyicare.jingyi_icis_engine.repository.reports;

import com.jingyicare.jingyi_icis_engine.entity.reports.DragableForm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DragableFormRepository extends JpaRepository<DragableForm, Long> {
    List<DragableForm> findByPidAndIsDeletedFalse(Long pid);

    List<DragableForm> findByPidAndDocumentedAt(Long pid, LocalDateTime documentedAt);

    Optional<DragableForm> findByIdAndIsDeletedFalse(Long id);

    List<DragableForm> findByPidAndDeptIdAndTemplateIdAndDocumentedAtBetweenAndIsDeletedFalse(
        Long pid, String deptId, Integer templateId, LocalDateTime start, LocalDateTime end
    );

    Optional<DragableForm> findByPidAndTemplateIdAndDocumentedAtAndIsDeletedFalse(
        Long pid, Integer templateId, LocalDateTime documentedAt
    );
}
