package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientBedHistory;

public interface PatientBedHistoryRepository extends JpaRepository<PatientBedHistory, Long> {
    List<PatientBedHistory> findAll();

    List<PatientBedHistory> findByPatientId(Long patientId);

    List<PatientBedHistory> findByPatientIdAndSwitchTimeAfter(Long patientId, LocalDateTime admissionTime);

    List<PatientBedHistory> findByPatientIdIn(List<Long> patientIds);
}