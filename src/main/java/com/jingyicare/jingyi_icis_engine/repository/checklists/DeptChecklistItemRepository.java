package com.jingyicare.jingyi_icis_engine.repository.checklists;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.checklists.DeptChecklistItem;

public interface DeptChecklistItemRepository extends JpaRepository<DeptChecklistItem, Integer> {
    List<DeptChecklistItem> findAllByIsDeletedFalse();
    List<DeptChecklistItem> findByGroupIdInAndIsDeletedFalse(List<Integer> groupIds);
    List<DeptChecklistItem> findByGroupIdAndIsDeletedFalse(Integer groupId);
    List<DeptChecklistItem> findByIdInAndIsDeletedFalse(List<Integer> ids);
    List<DeptChecklistItem> findByIdIn(List<Integer> ids);

    Optional<DeptChecklistItem> findByIdAndIsDeletedFalse(Integer id);
}