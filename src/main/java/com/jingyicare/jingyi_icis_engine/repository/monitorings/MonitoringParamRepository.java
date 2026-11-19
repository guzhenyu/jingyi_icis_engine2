package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;

public interface MonitoringParamRepository extends JpaRepository<MonitoringParam, String> {
    List<MonitoringParam> findAll();
    List<MonitoringParam> findByNameContaining(String name);
    List<MonitoringParam> findByCodeIn(Set<String> codes);

    Optional<MonitoringParam> findByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_READ)
    Optional<MonitoringParam> findForShareByCode(String code);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MonitoringParam> findForUpdateByCode(String code);

    Optional<MonitoringParam> findTopByOrderByDisplayOrderDesc();
}
