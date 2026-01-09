package com.jingyicare.jingyi_icis_engine.repository.tubes;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.tubes.*;

public interface PatientTubeStatusRecordRepository extends JpaRepository<PatientTubeStatusRecord, Long> {
    List<PatientTubeStatusRecord> findAll();  // 查询所有病人管道状态记录

    // 根据病人插管记录ID查询状态记录
    List<PatientTubeStatusRecord> findByPatientTubeRecordId(Long patientTubeRecordId);  // 按病人插管记录ID查询

    // 按病人插管记录ID和时间段查询
    /* @Query("SELECT r FROM PatientTubeStatusRecord r WHERE r.patientTubeRecordId = :patientTubeRecordId " +
        "AND r.isDeleted = false " +
        "AND r.recordedAt >= :startTime " +
        "AND r.recordedAt < :endTime)"
    ) */
    List<PatientTubeStatusRecord> findByPatientTubeRecordIdAndIsDeletedFalseAndRecordedAtBetween(
        @Param("patientTubeRecordId") Long patientTubeRecordId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    );
    /* 
    @Query("SELECT r FROM PatientTubeStatusRecord r WHERE r.patientTubeRecordId = :patientTubeRecordId " +
        "AND r.isDeleted = false " +
        "AND (:start IS NULL OR :start <= r.recordedAt) " +
        "AND (:end IS NULL OR :end > r.recordedAt)"
    )
    List<PatientTubeStatusRecord> findByPatientTubeRecordIdAndIsDeletedFalseAndRecordedAtBetween(
        @Param("patientTubeRecordId") Long patientTubeRecordId,
        @Param("start") LocalDateTime start,
        @Param("end") LocalDateTime end
    ); */

    @Query("SELECT DISTINCT r.recordedAt FROM PatientTubeStatusRecord r WHERE r.patientTubeRecordId = :patientTubeRecordId " +
        "AND r.isDeleted = false " +
        "AND :startTime <= r.recordedAt " +
        "AND :endTime > r.recordedAt " +
        "ORDER BY r.recordedAt ASC"
    )
    List<LocalDateTime> findRecordingTimes(
        @Param("patientTubeRecordId") Long patientTubeRecordId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );

    // 按病人插管记录ID和状态ID查询
    boolean existsByPatientTubeRecordIdAndTubeStatusIdAndRecordedAtAndIsDeletedFalse(Long patientTubeRecordId, Integer tubeStatusId, LocalDateTime recordedAt);

    // 根据病人插管记录和时间查询
    List<PatientTubeStatusRecord> findByPatientTubeRecordIdAndRecordedAt(Long patientTubeRecordId, LocalDateTime recordedAt);

    List<PatientTubeStatusRecord> findByPatientTubeRecordIdInAndIsDeletedFalseAndRecordedAtBetween(
        List<Long> patientTubeRecordIds,
        LocalDateTime start,
        LocalDateTime end);

    /*
    select ptr.tube_name, tts.name, ptsr.recorded_at, ptsr.value
    from patient_tube_status_records ptsr
    join patient_tube_records ptr on ptsr.patient_tube_record_id = ptr.id 
    join tube_type_statuses tts on ptsr.tube_status_id = tts.id
    where ptr.pid = :pid
    and ptr.is_deleted = false
    and ptsr.recorded_at >= :queryStartUtc
    and ptsr.recorded_at < :queryEndUtc
    and ptsr.is_deleted = false;
    */
    @Query("SELECT ptr.tubeName AS tubeName, tts.name AS statusName, ptsr.recordedAt AS recordedAt, ptsr.value AS value " +
        "FROM PatientTubeStatusRecord ptsr " +
        "JOIN PatientTubeRecord ptr ON ptsr.patientTubeRecordId = ptr.id " +
        "JOIN TubeTypeStatus tts ON ptsr.tubeStatusId = tts.id " +
        "WHERE ptr.pid = :pid " +
        "AND ptr.isDeleted = false " +
        "AND ptsr.recordedAt >= :queryStartUtc " +
        "AND ptsr.recordedAt < :queryEndUtc " +
        "AND ptsr.isDeleted = false"
    )
    List<PatientTubeStatusBrief> findPatientTubeStatusBrief(
        @Param("pid") Long pid,
        @Param("queryStartUtc") LocalDateTime queryStartUtc,
        @Param("queryEndUtc") LocalDateTime queryEndUtc
    );
}
