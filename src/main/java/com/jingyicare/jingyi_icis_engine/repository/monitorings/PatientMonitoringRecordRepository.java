package com.jingyicare.jingyi_icis_engine.repository.monitorings;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;

public interface PatientMonitoringRecordRepository extends JpaRepository<PatientMonitoringRecord, Long> {
    @Query("SELECT p FROM PatientMonitoringRecord p WHERE p.pid = :pid " +
        "AND p.monitoringParamCode = :monitoringParamCode " +
        "AND p.effectiveTime >= :start AND p.effectiveTime < :end AND p.isDeleted = false"
    )
    List<PatientMonitoringRecord> findByPidAndParamCodeAndEffectiveTimeRange(
        @Param("pid") Long pid,
        @Param("monitoringParamCode") String monitoringParamCode, 
        @Param("start") LocalDateTime start, 
        @Param("end") LocalDateTime end
    );

    @Query("SELECT p FROM PatientMonitoringRecord p WHERE p.pid = :pid " +
        "AND p.monitoringParamCode = :code AND p.effectiveTime >= :time AND p.isDeleted = false")
    List<PatientMonitoringRecord> findPidAndParamCodeAndActiveAfterTime(
        @Param("pid") Long pid, 
        @Param("code") String monitoringParamCode, 
        @Param("time") LocalDateTime effectiveTime
    );

    @Query("SELECT p FROM PatientMonitoringRecord p WHERE p.pid = :pid " +
        "AND p.effectiveTime >= :start AND p.effectiveTime < :end AND p.isDeleted = false")
    List<PatientMonitoringRecord> findByPidAndEffectiveTimeRange(
        @Param("pid") Long pid, 
        @Param("start") LocalDateTime start, 
        @Param("end") LocalDateTime end
    );

    @Query("SELECT p FROM PatientMonitoringRecord p WHERE p.pid = :pid " +
        "AND p.effectiveTime >= :start AND p.effectiveTime < :end")
    List<PatientMonitoringRecord> findAllByPidAndEffectiveTimeRange(
        @Param("pid") Long pid, 
        @Param("start") LocalDateTime start, 
        @Param("end") LocalDateTime end
    );

    @Query("SELECT p FROM PatientMonitoringRecord p WHERE p.pid = :pid " +
        "AND p.monitoringParamCode IN :paramCodes " +
        "AND p.effectiveTime >= :start AND p.effectiveTime < :end AND p.isDeleted = false")
    List<PatientMonitoringRecord> findByPidAndParamCodesAndEffectiveTimeRange(
        @Param("pid") Long pid,
        @Param("paramCodes") List<String> paramCodes,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );

    List<PatientMonitoringRecord> findByPidAndEffectiveTimeAndIsDeletedFalse(Long pid, LocalDateTime effectiveTime);

    List<PatientMonitoringRecord> findByPidAndIsDeletedFalse(Long pid);  // for testing
    List<PatientMonitoringRecord> findByPidAndIsDeletedTrue(Long pid);  // for testing

    Optional<PatientMonitoringRecord> findByIdAndIsDeletedFalse(Long id);
    Optional<PatientMonitoringRecord> findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(
        Long pid, String monitoringParamCode, LocalDateTime effectiveTime
    );
}