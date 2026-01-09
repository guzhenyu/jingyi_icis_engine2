package com.jingyicare.jingyi_icis_engine.repository.doctors;

import java.time.*;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.jingyicare.jingyi_icis_engine.entity.doctors.VTEPaduaScore;

@Repository
public interface VTEPaduaScoreRepository extends JpaRepository<VTEPaduaScore, Long> {
    List<VTEPaduaScore> findByPidAndScoreTimeBetweenAndIsDeletedFalse(Long pid, LocalDateTime startTime, LocalDateTime endTime);
    List<VTEPaduaScore> findByPidInAndIsDeletedFalse(List<Long> pids);
    Optional<VTEPaduaScore> findByIdAndIsDeletedFalse(Long id);
    Optional<VTEPaduaScore> findByPidAndScoreTimeAndIsDeletedFalse(Long pid, LocalDateTime scoreTime);
}