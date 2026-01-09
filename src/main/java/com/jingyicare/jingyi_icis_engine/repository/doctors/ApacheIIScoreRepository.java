package com.jingyicare.jingyi_icis_engine.repository.doctors;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingyicare.jingyi_icis_engine.entity.doctors.ApacheIIScore;

@Repository
public interface ApacheIIScoreRepository extends JpaRepository<ApacheIIScore, Long> {
    List<ApacheIIScore> findByPidAndScoreTimeBetweenAndIsDeletedFalse(Long pid, LocalDateTime startScoreTime, LocalDateTime endScoreTime);
    List<ApacheIIScore> findByPidInAndIsDeletedFalse(List<Long> pids);

    Optional<ApacheIIScore> findByIdAndIsDeletedFalse(Long id);
    Optional<ApacheIIScore> findByPidAndScoreTimeAndIsDeletedFalse(Long pid, LocalDateTime scoreTime);
}
