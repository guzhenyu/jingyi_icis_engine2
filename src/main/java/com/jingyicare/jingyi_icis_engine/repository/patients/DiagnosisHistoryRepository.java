package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;

public interface DiagnosisHistoryRepository extends JpaRepository<DiagnosisHistory, Integer> {
    List<DiagnosisHistory> findByPatientIdAndIsDeletedFalse(Long patientId);
}
