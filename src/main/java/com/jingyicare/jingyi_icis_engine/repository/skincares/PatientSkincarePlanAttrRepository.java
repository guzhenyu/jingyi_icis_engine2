package com.jingyicare.jingyi_icis_engine.repository.skincares;

import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincarePlanAttr;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PatientSkincarePlanAttrRepository extends JpaRepository<PatientSkincarePlanAttr, Long> {
    List<PatientSkincarePlanAttr> findByPatientSkincarePlanIdAndIsDeletedFalse(Long patientSkincarePlanId);

    List<PatientSkincarePlanAttr> findByPatientSkincarePlanIdInAndIsDeletedFalse(List<Long> patientSkincarePlanIds);

    Optional<PatientSkincarePlanAttr> findByIdAndIsDeletedFalse(Long id);

    Optional<PatientSkincarePlanAttr> findByPatientSkincarePlanIdAndSkincareAttrIdAndIsDeletedFalse(
        Long patientSkincarePlanId, Integer skincareAttrId
    );
}
