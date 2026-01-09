package com.jingyicare.jingyi_icis_engine.repository.doctors;

import java.time.*;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingyicare.jingyi_icis_engine.entity.doctors.VTECapriniScore;

@Repository
public interface VTECapriniScoreRepository extends JpaRepository<VTECapriniScore, Long> {
    List<VTECapriniScore> findByPidAndScoreTimeBetweenAndIsDeletedFalse(Long pid, LocalDateTime startTime, LocalDateTime endTime);
    List<VTECapriniScore> findByPidInAndIsDeletedFalse(List<Long> pids);
    Optional<VTECapriniScore> findByIdAndIsDeletedFalse(Long id);
    Optional<VTECapriniScore> findByPidAndScoreTimeAndIsDeletedFalse(Long pid, LocalDateTime scoreTime);
}