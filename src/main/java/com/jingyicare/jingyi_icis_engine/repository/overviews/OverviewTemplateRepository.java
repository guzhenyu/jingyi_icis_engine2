package com.jingyicare.jingyi_icis_engine.repository.overviews;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.overviews.OverviewTemplate;

public interface OverviewTemplateRepository extends JpaRepository<OverviewTemplate, Long> {
    List<OverviewTemplate> findByDeptIdAndIsDeletedFalse(String deptId);
    List<OverviewTemplate> findByAccountIdAndIsDeletedFalse(String accountId);

    Optional<OverviewTemplate> findByDeptIdAndAccountIdAndTemplateNameAndIsDeletedFalse(
        String deptId, String accountId, String templateName
    );

    Optional<OverviewTemplate> findByIdAndIsDeletedFalse(Long id);
}