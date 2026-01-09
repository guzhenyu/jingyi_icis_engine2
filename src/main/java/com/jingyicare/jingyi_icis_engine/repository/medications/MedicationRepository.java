package com.jingyicare.jingyi_icis_engine.repository.medications;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.medications.Medication;

public interface MedicationRepository extends JpaRepository<Medication, String> {
    List<Medication> findAll();

    Optional<Medication> findByCode(String code);

    List<Medication> findByConfirmed(Boolean confirmed);
}