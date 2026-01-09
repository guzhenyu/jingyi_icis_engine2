package com.jingyicare.jingyi_icis_engine.repository.overviews;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.overviews.OverviewGroup;

public interface OverviewGroupRepository extends JpaRepository<OverviewGroup, Long> {
    List<OverviewGroup> findByTemplateIdAndIsDeletedFalse(Long templateId);

    Optional<OverviewGroup> findByTemplateIdAndGroupNameAndIsDeletedFalse(Long templateId, String groupName);
    Optional<OverviewGroup> findByIdAndIsDeletedFalse(Long id);
}