package com.jingyicare.jingyi_icis_engine.repository.medications;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationFrequency;

public interface MedicationFrequencyRepository extends JpaRepository<MedicationFrequency, Integer> {
    List<MedicationFrequency> findAll();
    List<MedicationFrequency> findByIsDeletedFalse();

    Optional<MedicationFrequency> findById(Integer id);
    Optional<MedicationFrequency> findByIdAndIsDeletedFalse(Integer id);

    Optional<MedicationFrequency> findByCode(String code);

    Optional<MedicationFrequency> findByCodeAndIsDeletedFalse(String code);

    List<MedicationFrequency> findByCodeIn(Set<String> codes);
}