package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParamHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MonitoringParamHistoryRepository extends JpaRepository<MonitoringParamHistory, Integer> {
    List<MonitoringParamHistory> findByCode(String code);

    List<MonitoringParamHistory> findByDeptId(String deptId);

    List<MonitoringParamHistory> findByIsDeleted(Boolean isDeleted);

    List<MonitoringParamHistory> findByCodeAndDeptId(String code, String deptId);
}