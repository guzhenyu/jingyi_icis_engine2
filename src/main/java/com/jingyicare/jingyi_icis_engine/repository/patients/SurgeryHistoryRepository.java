package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;

public interface SurgeryHistoryRepository extends JpaRepository<SurgeryHistory, Integer> {
    List<SurgeryHistory> findByPatientId(Long patientId);
}
