package com.jingyicare.jingyi_icis_engine.repository.medications;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationType;

public interface MedicationTypeRepository  extends JpaRepository<MedicationType, Long> {
    List<MedicationType> findAll();
}
