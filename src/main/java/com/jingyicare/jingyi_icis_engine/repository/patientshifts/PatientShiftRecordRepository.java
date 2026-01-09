package com.jingyicare.jingyi_icis_engine.repository.patientshifts;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.patientshifts.PatientShiftRecord;

public interface PatientShiftRecordRepository extends JpaRepository<PatientShiftRecord, Long> {
    // 根据id查找一条记录
    Optional<PatientShiftRecord> findByIdAndIsDeletedFalse(Long id);

    // 根据pid, shift_name, shift_start查找一条记录
    Optional<PatientShiftRecord> findByPidAndShiftNameAndShiftStartAndIsDeletedFalse(Long pid, String shiftName, LocalDateTime shiftStart);

    // 根据pid查找所有记录（is_deleted = false）
    List<PatientShiftRecord> findByPidAndIsDeletedFalse(Long pid);

    // 根据pid, query_start, query_end查找记录
    List<PatientShiftRecord> findByPidAndShiftStartBetweenAndIsDeletedFalse(Long pid, LocalDateTime queryStart, LocalDateTime queryEnd);

    // 根据pid和时间范围查找重叠的记录
    @Query("SELECT p FROM PatientShiftRecord p WHERE p.pid = :pid AND" +
        " :queryStart < p.shiftEnd AND :queryEnd > p.shiftStart" +
        " AND p.isDeleted = false")
    List<PatientShiftRecord> findByPidAndOverlappingTimeRange(@Param("pid") Long pid, @Param("queryStart") LocalDateTime queryStart, @Param("queryEnd") LocalDateTime queryEnd);
}