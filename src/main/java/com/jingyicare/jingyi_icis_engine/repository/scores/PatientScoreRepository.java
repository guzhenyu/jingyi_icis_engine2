package com.jingyicare.jingyi_icis_engine.repository.scores;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.List;

import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PatientScoreRepository extends JpaRepository<PatientScore, Long> {
    List<PatientScore> findByPidAndIsDeletedFalse(Long pid);
    List<PatientScore> findByPidAndScoreGroupCodeAndEffectiveTimeBetweenAndIsDeletedFalse(
        Long pid, String scoreGroupCode, LocalDateTime start, LocalDateTime end
    );
    List<PatientScore> findByPidInAndScoreGroupCodeInAndIsDeletedFalse(
        List<Long> pids, List<String> scoreGroupCodes
    );
    List<PatientScore> findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(
        Long pid, LocalDateTime start, LocalDateTime end
    );
    List<PatientScore> findByPidAndScoreGroupCodeAndIsDeletedFalse(
        Long pid, String scoreGroupCode
    );

    @Query("SELECT ps FROM PatientScore ps " +
        "WHERE ps.isDeleted = false " +
        "AND ps.pid = :pid " +
        "AND ps.scoreGroupCode = :scoreGroupCode " +
        "AND ps.effectiveTime BETWEEN :startTime AND :endTime")
    List<PatientScore> findPatientScores(
        @Param("pid") Long pid,
        @Param("scoreGroupCode") String scoreGroupCode,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );

    // 全部评分类型的 UTC 半开窗口 [startUtc, endUtc)，按生效时间、ID 升序。
    @Query("SELECT ps FROM PatientScore ps " +
        "WHERE ps.isDeleted = false " +
        "AND ps.pid = :pid " +
        "AND ps.effectiveTime >= :startUtc " +
        "AND ps.effectiveTime < :endUtc " +
        "ORDER BY ps.effectiveTime ASC, ps.id ASC")
    List<PatientScore> findPatientScoresInWindow(
        @Param("pid") Long pid,
        @Param("startUtc") LocalDateTime startUtc,
        @Param("endUtc") LocalDateTime endUtc
    );

    Optional<PatientScore> findByIdAndIsDeletedFalse(Long id);
    Optional<PatientScore> findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
        Long pid, String scoreGroupCode, LocalDateTime effectiveTime
    );
}