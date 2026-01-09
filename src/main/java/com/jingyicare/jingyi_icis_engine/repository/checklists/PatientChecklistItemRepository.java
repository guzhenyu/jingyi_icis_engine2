package com.jingyicare.jingyi_icis_engine.repository.checklists;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.checklists.PatientChecklistItem;

public interface PatientChecklistItemRepository extends JpaRepository<PatientChecklistItem, Long> {
    List<PatientChecklistItem> findByGroupIdInAndIsDeletedFalse(List<Long> groupIds);

    Optional<PatientChecklistItem> findByIdAndIsDeletedFalse(Long id);
}