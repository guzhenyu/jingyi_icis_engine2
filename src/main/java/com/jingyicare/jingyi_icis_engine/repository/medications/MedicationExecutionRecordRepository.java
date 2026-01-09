package com.jingyicare.jingyi_icis_engine.repository.medications;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecord;

public interface MedicationExecutionRecordRepository extends JpaRepository<MedicationExecutionRecord, Long> {
    List<MedicationExecutionRecord> findAll();

    List<MedicationExecutionRecord> findByPatientIdAndMedicationOrderGroupId(Long patientId, Long orderGroupId);

    Optional<MedicationExecutionRecord> findById(Long Id);

    @Query("SELECT r FROM MedicationExecutionRecord r WHERE r.hisOrderGroupId = :hisOrdGroupId AND r.planTime >= :startUtcTime AND r.planTime < :endUtcTime")
    List<MedicationExecutionRecord> findByHisGroupIdAndTimeRange(
        @Param("hisOrdGroupId") String hisOrdGroupId, @Param("startUtcTime") LocalDateTime startUtcTime, @Param("endUtcTime") LocalDateTime endUtcTime);

    @Query("SELECT r FROM MedicationExecutionRecord r WHERE r.medicationOrderGroupId = :medOrdGroupId AND r.planTime >= :startUtcTime AND r.planTime < :endUtcTime")
    List<MedicationExecutionRecord> findByMedGroupIdAndTimeRange(
        @Param("medOrdGroupId") Long medOrdGroupId, @Param("startUtcTime") LocalDateTime startUtcTime, @Param("endUtcTime") LocalDateTime endUtcTime);

    @Query(value = "SELECT er.* FROM medication_execution_records er " +
        "JOIN medication_order_groups og ON er.medication_order_group_id = og.id " +
        "JOIN patient_records pr ON og.patient_id = pr.id " +
        "WHERE pr.admission_status = 1 " +  // 1: config.patient.enums.admission_status_in_icu.id
        "AND pr.id = :patientId AND er.plan_time >= :startUtcTime AND er.plan_time < :endUtcTime " +
        "AND er.is_deleted = true", nativeQuery = true)
    List<MedicationExecutionRecord> findDeletedRecordsByPatientIdAndTimeRange(
        @Param("patientId") Long patientId, @Param("startUtcTime") LocalDateTime startUtcTime, @Param("endUtcTime") LocalDateTime endUtcTime);

    @Query(value = "SELECT * FROM medication_execution_records  WHERE his_order_group_id = :hisOrdGroupId AND is_deleted = false ORDER BY plan_time DESC LIMIT 1", nativeQuery = true)
    Optional<MedicationExecutionRecord> findLatestValidRecord(@Param("hisOrdGroupId") String hisOrdGroupId);

    /*
     * 根据start_time和end_time查询执行记录
     */
    // start_time is null && end_time is null, 则根据plan_time查询
    @Query("SELECT r FROM MedicationExecutionRecord r" +
        " WHERE r.patientId = :patientId AND r.isDeleted = false" +
        " AND r.planTime >= :startUtcTime AND r.planTime < :endUtcTime" +
        " AND r.startTime IS NULL AND r.endTime IS NULL")
    List<MedicationExecutionRecord> findNotStartedRecordsByPatientId(
        @Param("patientId") Long patientId,
        @Param("startUtcTime") LocalDateTime startUtcTime,
        @Param("endUtcTime") LocalDateTime endUtcTime
    );

    @Query("SELECT r FROM MedicationExecutionRecord r" +
        " WHERE r.medicationOrderGroupId = :medicationOrderGroupId AND r.isDeleted = false" +
        " AND r.planTime >= :startUtcTime AND r.planTime < :endUtcTime" +
        " AND r.startTime IS NULL AND r.endTime IS NULL")
    List<MedicationExecutionRecord> findNotStartedRecordsByMedicationOrderGroupId(
        @Param("medicationOrderGroupId") Long medicationOrderGroupId,
        @Param("startUtcTime") LocalDateTime startUtcTime,
        @Param("endUtcTime") LocalDateTime endUtcTime
    );

    // start_time is not null && end_time is null, 则返回全部相关记录
    @Query("SELECT r FROM MedicationExecutionRecord r" +
        " WHERE r.patientId = :patientId AND r.isDeleted = false" +
        " AND r.startTime IS NOT NULL AND r.endTime IS NULL" +
        " AND r.startTime < :endUtcTime")
    List<MedicationExecutionRecord> findInProgressRecordsByPatientId(
        @Param("patientId") Long patientId, @Param("endUtcTime") LocalDateTime endUtcTime
    );

    @Query("SELECT r FROM MedicationExecutionRecord r" +
        " WHERE r.medicationOrderGroupId = :medicationOrderGroupId AND r.isDeleted = false" +
        " AND r.startTime IS NOT NULL AND r.endTime IS NULL" +
        " AND (r.planTime < :endUtcTime OR r.startTime < :endUtcTime)")
    List<MedicationExecutionRecord> findInProgressRecordsByMedicationOrderGroupId(
        @Param("medicationOrderGroupId") Long medicationOrderGroupId,
        @Param("endUtcTime") LocalDateTime endUtcTime
    );

    // start_time is not null && end_time is not null, 则找出交叉的集合
    @Query("SELECT r FROM MedicationExecutionRecord r" +
        " WHERE r.patientId = :patientId AND r.isDeleted = false" +
        " AND r.startTime IS NOT NULL AND r.endTime IS NOT NULL" +
        " AND r.endTime >= :startUtcTime AND r.startTime < :endUtcTime")
    List<MedicationExecutionRecord> findCompletedRecordsByPatientId(
        @Param("patientId") Long patientId,
        @Param("startUtcTime") LocalDateTime startUtcTime,
        @Param("endUtcTime") LocalDateTime endUtcTime
    );

    @Query("SELECT r FROM MedicationExecutionRecord r" +
        " WHERE r.medicationOrderGroupId = :medicationOrderGroupId AND r.isDeleted = false" +
        " AND r.startTime IS NOT NULL AND r.endTime IS NOT NULL" +
        " AND r.endTime >= :startUtcTime AND r.startTime < :endUtcTime")
    List<MedicationExecutionRecord> findCompletedRecordsByMedicationOrderGroupId(
        @Param("medicationOrderGroupId") Long medicationOrderGroupId,
        @Param("startUtcTime") LocalDateTime startUtcTime,
        @Param("endUtcTime") LocalDateTime endUtcTime
    );

    @Query("SELECT r FROM MedicationExecutionRecord r" +
        " WHERE r.patientId = :patientId AND r.isDeleted = false AND r.startTime IS NOT NULL " +
        " AND r.startTime >= :startUtcTime AND r.startTime < :endUtcTime")
    List<MedicationExecutionRecord> findStartedRecordsByPatientId(
        @Param("patientId") Long patientId,
        @Param("startUtcTime") LocalDateTime startUtcTime,
        @Param("endUtcTime") LocalDateTime endUtcTime
    );

    List<MedicationExecutionRecord> findByPatientIdAndIsDeletedFalseAndStartTimeIsNotNullAndStartTimeBetween(
        Long patientId, LocalDateTime startTime, LocalDateTime endTime
    );

    // For test
    @Query("SELECT r FROM MedicationExecutionRecord r WHERE r.hisOrderGroupId = :hisOrdGroupId")
    List<MedicationExecutionRecord> findByHisGroupId(@Param("hisOrdGroupId") String hisOrdGroupId);

    // For test
    @Query("SELECT r FROM MedicationExecutionRecord r WHERE r.medicationOrderGroupId = :medOrdGroupId")
    List<MedicationExecutionRecord> findByMedGroupId(@Param("medOrdGroupId") Long medOrdGroupId);

    // For test
    List<MedicationExecutionRecord> findByPatientId(Long patientId);
}