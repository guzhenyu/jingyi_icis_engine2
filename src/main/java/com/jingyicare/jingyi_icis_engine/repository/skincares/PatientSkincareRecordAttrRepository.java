package com.jingyicare.jingyi_icis_engine.repository.skincares;

import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincareRecordAttr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatientSkincareRecordAttrRepository extends JpaRepository<PatientSkincareRecordAttr, Long> {
    List<PatientSkincareRecordAttr> findByPatientSkincareRecordIdAndIsDeletedFalse(Long patientSkincareRecordId);

    List<PatientSkincareRecordAttr> findByPatientSkincareRecordIdInAndIsDeletedFalse(List<Long> patientSkincareRecordIds);

    Optional<PatientSkincareRecordAttr> findByIdAndIsDeletedFalse(Long id);

    Optional<PatientSkincareRecordAttr> findByPatientSkincareRecordIdAndSkincareAttrIdAndIsDeletedFalse(
        Long patientSkincareRecordId, Integer skincareAttrId
    );
}
