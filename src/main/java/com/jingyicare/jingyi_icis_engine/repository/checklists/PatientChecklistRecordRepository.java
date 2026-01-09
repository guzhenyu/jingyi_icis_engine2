package com.jingyicare.jingyi_icis_engine.repository.checklists;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.checklists.PatientChecklistRecord;

public interface PatientChecklistRecordRepository extends JpaRepository<PatientChecklistRecord, Integer> {
    List<PatientChecklistRecord> findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(
        Long pid, 
        LocalDateTime startTime, 
        LocalDateTime endTime
    );

    Optional<PatientChecklistRecord> findByPidAndEffectiveTimeAndIsDeletedFalse(
        Long pid, 
        LocalDateTime effectiveTime
    );
    Optional<PatientChecklistRecord> findByIdAndIsDeletedFalse(Integer id);
}