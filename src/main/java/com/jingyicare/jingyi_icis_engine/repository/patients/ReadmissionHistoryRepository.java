package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.LocalDateTime;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.ReadmissionHistory;

public interface ReadmissionHistoryRepository extends JpaRepository<ReadmissionHistory, Integer> {
    List<ReadmissionHistory> findByPatientId(Long patientId);
}