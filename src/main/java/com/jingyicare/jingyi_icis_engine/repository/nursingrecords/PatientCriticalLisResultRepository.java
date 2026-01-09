package com.jingyicare.jingyi_icis_engine.repository.nursingrecords;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.PatientCriticalLisResult;

public interface PatientCriticalLisResultRepository extends JpaRepository<PatientCriticalLisResult, Integer> {
    List<PatientCriticalLisResult> findAllByIsDeletedFalse();

    List<PatientCriticalLisResult> findByPidAndAuthTimeBetweenAndIsDeletedFalse(
        Long pid, LocalDateTime start, LocalDateTime end
    );

    List<PatientCriticalLisResult> findByPidAndHandlingIdAndIsDeletedFalse(Long pid, Integer handlingId);

    Optional<PatientCriticalLisResult> findByPidAndIsDeletedFalse(Long pid);
    Optional<PatientCriticalLisResult> findByIdAndPidAndIsDeletedFalse(Integer id, Long pid);
}