package com.jingyicare.jingyi_icis_engine.repository.medications;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionAction;

public interface MedicationExecutionActionRepository extends JpaRepository<MedicationExecutionAction, Long> {
    List<MedicationExecutionAction> findAll();

    Optional<MedicationExecutionAction> findById(Long Id);

    List<MedicationExecutionAction> findByMedicationExecutionRecordIdInAndIsDeletedFalse(
        List<Long> medicationExecutionRecordIds
    );

    @Query(value = "SELECT ea.* FROM medication_execution_actions ea " +
        "WHERE ea.medication_execution_record_id = :exeRecId " +
        "AND ea.is_deleted = false", nativeQuery = true)
    List<MedicationExecutionAction> findByMedicationExecutionRecordId(Long exeRecId);
}