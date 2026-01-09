package com.jingyicare.jingyi_icis_engine.repository.nursingrecords;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NursingRecordRepository extends JpaRepository<NursingRecord, Long> {
    List<NursingRecord> findByPatientIdAndEffectiveTimeBetweenAndIsDeletedFalse(
        Long patientId, LocalDateTime startUtc, LocalDateTime endUtc);

    List<NursingRecord> findByPatientIdAndPatientCriticalLisHandlingIdInAndIsDeletedFalse(
        Long patientId, List<Integer> patientCriticalLisHandlingIds);

    Optional<NursingRecord> findByPatientIdAndEffectiveTimeAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
        Long patientId, LocalDateTime effectiveTime, Integer patientCriticalLisHandlingId);

    Optional<NursingRecord> findByPatientIdAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
        Long patientId, Integer patientCriticalLisHandlingId);

    Optional<NursingRecord> findByIdAndIsDeletedFalse(Long id);
}