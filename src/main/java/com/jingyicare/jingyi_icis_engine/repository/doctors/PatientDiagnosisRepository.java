package com.jingyicare.jingyi_icis_engine.repository.doctors;

import java.time.*;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.doctors.PatientDiagnosis;

public interface PatientDiagnosisRepository extends JpaRepository<PatientDiagnosis, Long> {
    List<PatientDiagnosis> findAll();

    Optional<PatientDiagnosis> findById(Long id);

    Optional<PatientDiagnosis> findByPidAndDiseaseCodeAndConfirmedAt(Long pid, String diseaseCode, LocalDateTime confirmedAt);

    List<PatientDiagnosis> findByPidAndIsDeletedFalse(Long pid);

    List<PatientDiagnosis> findByPidAndConfirmedAtBetweenAndIsDeleted(Long pid, LocalDateTime start, LocalDateTime end, Boolean isDeleted);

    List<PatientDiagnosis> findByConfirmedAtBetweenAndIsDeletedFalse(LocalDateTime start, LocalDateTime end);
    List<PatientDiagnosis> findByDeletedAtBetweenAndIsDeletedTrue(LocalDateTime start, LocalDateTime end);

    List<PatientDiagnosis> findByPidAndDiseaseCodeAndIsDeletedFalse(Long pid, String diseaseCode);
}
