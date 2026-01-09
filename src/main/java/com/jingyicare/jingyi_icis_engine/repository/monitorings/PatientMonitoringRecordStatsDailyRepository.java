package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecordStatsDaily;

public interface PatientMonitoringRecordStatsDailyRepository extends JpaRepository<PatientMonitoringRecordStatsDaily, Long> {
    List<PatientMonitoringRecordStatsDaily> findByIsDeletedFalse();

    List<PatientMonitoringRecordStatsDaily> findByPidAndIsDeletedFalse(Long pid);

    List<PatientMonitoringRecordStatsDaily> findByEffectiveTimeBetweenAndIsDeletedFalse(LocalDateTime startTime, LocalDateTime endTime);

    @Query("SELECT p FROM PatientMonitoringRecordStatsDaily p WHERE p.pid = :pid AND " +
        "p.monitoringParamCode = :monitoringParamCode AND " +
        "p.effectiveTime >= :startTime AND p.effectiveTime < :endTime AND " +
        "p.isDeleted = false"
    )
    List<PatientMonitoringRecordStatsDaily> findByPidAndParamCodeAndTimeBetween(
        @Param("pid") Long pid, 
        @Param("monitoringParamCode") String monitoringParamCode, 
        @Param("startTime") LocalDateTime startTime, 
        @Param("endTime") LocalDateTime endTime
    );

    Optional<PatientMonitoringRecordStatsDaily> findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(
        Long pid, String monitoringParamCode, LocalDateTime effectiveTime
    );
}