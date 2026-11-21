package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;

public interface MonitoringParamRepository extends JpaRepository<MonitoringParam, String> {
    List<MonitoringParam> findAll();
    List<MonitoringParam> findByNameContaining(String name);
    List<MonitoringParam> findByCodeIn(Set<String> codes);

    Optional<MonitoringParam> findByCode(String code);

    Optional<MonitoringParam> findTopByOrderByDisplayOrderDesc();
}
