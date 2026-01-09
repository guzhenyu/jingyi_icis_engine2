package com.jingyicare.jingyi_icis_engine.repository.tubes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.tubes.PatientTubeRecord;

public interface PatientTubeRecordRepository extends JpaRepository<PatientTubeRecord, Long> {
    Optional<PatientTubeRecord> findByIdAndIsDeletedFalse(Long id);  // 按病人管道ID查询未删除的插管记录

    List<PatientTubeRecord> findAll();  // 查询所有病人插管记录

    // 你可以根据需求添加其他查询方法，例如按 pid 查询
    List<PatientTubeRecord> findByPid(Long pid);  // 按病人ID查询

    @Query("SELECT ptr FROM PatientTubeRecord ptr " +
           "JOIN PatientRecord pr ON ptr.pid = pr.id " +
           "JOIN TubeType tt ON ptr.tubeTypeId = tt.id " +
           "WHERE pr.id = :pid AND ptr.isDeleted = :isDeleted")
    List<PatientTubeRecord> findByPidAndIsDeleted(@Param("pid") Long pid, @Param("isDeleted") Boolean isDeleted);

    // 按病人ID和管道名称查询未删除的插管记录
    List<PatientTubeRecord> findByPidAndTubeNameAndIsDeletedFalseAndRemovedAtNull(Long pid, String tubeName);

    // 按病人ID和管道类型查询未删除的插管记录
    List<PatientTubeRecord> findByIdInAndIsDeletedFalse(List<Long> ids);

    // 按病人ID和日期范围查询插管记录
    @Query("SELECT ptr FROM PatientTubeRecord ptr " +
           "WHERE ptr.pid = :pid " +
           "AND ptr.insertedAt < :queryEnd " +
           "AND (ptr.removedAt IS NULL OR ptr.removedAt >= :queryStart) " +
           "AND ptr.isDeleted = false")
    List<PatientTubeRecord> findByPidAndDateRange(
        @Param("pid") Long pid,
        @Param("queryStart") LocalDateTime queryStart,
        @Param("queryEnd") LocalDateTime queryEnd);

    List<PatientTubeRecord> findByRootTubeRecordIdInAndIsDeletedFalse(List<Long> rootTubeRecordIds);

    List<PatientTubeRecord> findByPidAndIsDeletedFalseAndRemovedAtNotNullAndRemovedAtBetween(
        Long pid, LocalDateTime start, LocalDateTime end);
    List<PatientTubeRecord> findByPidAndIsDeletedFalseAndRemovedAtNull(Long pid);
}