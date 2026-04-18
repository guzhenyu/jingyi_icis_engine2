package com.jingyicare.jingyi_icis_engine.repository.nursingrecords;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface NursingRecordRepository extends JpaRepository<NursingRecord, Long> {
    List<NursingRecord> findByPatientIdAndEffectiveTimeBetweenAndIsDeletedFalse(
        Long patientId, LocalDateTime startUtc, LocalDateTime endUtc);

    @Query("SELECT n FROM NursingRecord n WHERE n.patientId = :patientId " +
        "AND n.effectiveTime >= :startUtc AND n.effectiveTime < :endUtc " +
        "AND n.isDeleted = false ORDER BY n.effectiveTime ASC, n.id ASC")
    List<NursingRecord> findReportNursingRecords(
        @Param("patientId") Long patientId,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc);

    List<NursingRecord> findByPatientIdAndPatientCriticalLisHandlingIdInAndIsDeletedFalse(
        Long patientId, List<Integer> patientCriticalLisHandlingIds);

    Optional<NursingRecord> findByPatientIdAndEffectiveTimeAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
        Long patientId, LocalDateTime effectiveTime, Integer patientCriticalLisHandlingId);

    Optional<NursingRecord> findByPatientIdAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
        Long patientId, Integer patientCriticalLisHandlingId);

    Optional<NursingRecord> findByIdAndIsDeletedFalse(Long id);

    List<NursingRecord> findByIdInAndIsDeletedFalse(List<Long> ids);
}
