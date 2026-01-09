package com.jingyicare.jingyi_icis_engine.repository.medications;

import java.time.LocalDateTime;
import java.util.List;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecordStat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MedicationExecutionRecordStatRepository extends JpaRepository<MedicationExecutionRecordStat, Long> {
    List<MedicationExecutionRecordStat> findByGroupIdAndExeRecordId(Long groupId, Long exeRecordId);

    void deleteByExeRecordId(Long exeRecordId);

    /*
    select stats from medication_execution_record_stats stats 
    join medication_execution_records records on stats.exe_record_id = records.id
    join medication_execution_groups groups on records.medication_order_group_id = groups.id
    join patient_records patients on groups.patient_id = patients.id
    where patients.id = :patientId
    and stats.stats_time >= :startUtc and stats.stats_time < :endUtc
    */
    @Query("SELECT stats FROM MedicationExecutionRecordStat stats " +
        "JOIN MedicationOrderGroup groups ON stats.groupId = groups.id " +
        "JOIN PatientRecord patients ON groups.patientId = patients.id " +
        "WHERE patients.id = :patientId " +
        "AND stats.statsTime >= :startUtc " +
        "AND stats.statsTime < :endUtc")
    List<MedicationExecutionRecordStat> findAllByPatientIdAndStatsTimeRange(
         @Param("patientId") Long patientId,
         @Param("startUtc") LocalDateTime startUtc,
         @Param("endUtc") LocalDateTime endUtc);
}
