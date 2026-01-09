package com.jingyicare.jingyi_icis_engine.repository.exturls;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.exturls.ExtUrlConfig;

public interface ExtUrlConfigRepository extends JpaRepository<ExtUrlConfig, Integer> {
    List<ExtUrlConfig> findAllByIsDeletedFalse();
    List<ExtUrlConfig> findByDeptIdAndIsDeletedFalse(String deptId);

    Optional<ExtUrlConfig> findByDeptIdAndDisplayNameAndIsDeletedFalse(String deptId, String displayName);
    Optional<ExtUrlConfig> findByIdAndIsDeletedFalse(Integer id);
}