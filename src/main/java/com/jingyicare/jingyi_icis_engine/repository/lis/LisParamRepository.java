package com.jingyicare.jingyi_icis_engine.repository.lis;

import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.lis.LisParam;

public interface LisParamRepository extends JpaRepository<LisParam, String> {
    List<LisParam> findAll();
    Optional<LisParam> findByParamCode(String paramCode);
}
