package com.jingyicare.jingyi_icis_engine.repository.lis;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.lis.ExternalLisParam;

public interface ExternalLisParamRepository extends JpaRepository<ExternalLisParam, String> {
    List<ExternalLisParam> findAll();
    Optional<ExternalLisParam> findByParamCode(String paramCode);
}