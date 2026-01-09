package com.jingyicare.jingyi_icis_engine.repository.medications;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationHistory;

public interface MedicationHistoryRepository extends JpaRepository<MedicationHistory, Long> {
    List<MedicationHistory> findAll();

    Optional<MedicationHistory> findById(Long id);

    @Query(value = "SELECT * FROM medications_history WHERE code = :code ORDER BY id DESC",
        nativeQuery = true)
    List<MedicationHistory> findByCode(@Param("code") String code);
}