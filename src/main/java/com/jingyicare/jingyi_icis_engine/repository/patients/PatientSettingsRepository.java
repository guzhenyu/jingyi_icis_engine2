package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientSettings;

public interface PatientSettingsRepository extends JpaRepository<PatientSettings, Long> {
    Optional<PatientSettings> findByPid(Long pid);
}
