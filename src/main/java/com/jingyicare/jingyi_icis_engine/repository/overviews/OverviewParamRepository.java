package com.jingyicare.jingyi_icis_engine.repository.overviews;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.overviews.OverviewParam;

public interface OverviewParamRepository extends JpaRepository<OverviewParam, Long> {
    List<OverviewParam> findByGroupIdAndIsDeletedFalse(Long groupId);

    Optional<OverviewParam> findByGroupIdAndParamNameAndIsDeletedFalse(Long groupId, String paramName);

    Optional<OverviewParam> findByIdAndIsDeletedFalse(Long id);

    Optional<OverviewParam> findByGroupIdAndParamCodeAndIsDeletedFalse(Long groupId, String paramCode);
}