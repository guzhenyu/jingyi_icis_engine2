package com.jingyicare.jingyi_icis_engine.repository.doctors;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingyicare.jingyi_icis_engine.entity.doctors.SofaScore;

@Repository
public interface SofaScoreRepository extends JpaRepository<SofaScore, Long> {
    Optional<SofaScore> findByIdAndIsDeletedFalse(Long id);
    Optional<SofaScore> findByPidAndScoreTimeAndIsDeletedFalse(Long pid, LocalDateTime scoreTime);
    List<SofaScore> findByPidAndScoreTimeBetweenAndIsDeletedFalse(
        Long pid, LocalDateTime startTime, LocalDateTime endTime
    );
}