package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.*;
import java.util.*;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;

public interface DiagnosisHistoryRepository extends JpaRepository<DiagnosisHistory, Integer> {
    List<DiagnosisHistory> findByPatientIdAndIsDeletedFalse(Long patientId);

    Optional<DiagnosisHistory> findByPatientIdAndDiagnosisTimeAndIsDeletedFalse(
        Long patientId, LocalDateTime diagnosisTime);
}
