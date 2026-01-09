package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.LocalDateTime;

import org.springframework.data.jpa.domain.Specification;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;


public class PatientRecordSpecification {

    public static Specification<PatientRecord> hasDeptId(String deptId) {
        return (root, query, criteriaBuilder) -> 
            deptId == null ? criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("deptId"), deptId);
    }

    public static Specification<PatientRecord> hasAdmissionStatus(Integer admissionStatus) {
        return (root, query, criteriaBuilder) -> 
            admissionStatus == null ? criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("admissionStatus"), admissionStatus);
    }

    public static Specification<PatientRecord> hasHisMrn(String hisMrn) {
        return (root, query, criteriaBuilder) -> 
            hisMrn == null ? criteriaBuilder.conjunction() : criteriaBuilder.like(root.get("hisMrn"), "%" + hisMrn + "%");
    }

    public static Specification<PatientRecord> hasPatientName(String patientName) {
        return (root, query, criteriaBuilder) -> 
            patientName == null ? criteriaBuilder.conjunction() : criteriaBuilder.like(root.get("icuName"), "%" + patientName + "%");
    }

    public static Specification<PatientRecord> hasDischargeQueryStart(LocalDateTime queryStart) {
        return (root, query, criteriaBuilder) -> 
            queryStart == null ? criteriaBuilder.conjunction() : criteriaBuilder.greaterThanOrEqualTo(root.get("dischargeTime"), queryStart);
    }

    public static Specification<PatientRecord> hasDischargeQueryEnd(LocalDateTime queryEnd) {
        return (root, query, criteriaBuilder) -> 
            queryEnd == null ? criteriaBuilder.conjunction() : criteriaBuilder.lessThanOrEqualTo(root.get("dischargeTime"), queryEnd);
    }
}