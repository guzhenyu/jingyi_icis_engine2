package com.jingyicare.jingyi_icis_engine.entity.patients;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "his_patient_records", indexes = {
    @Index(name = "idx_his_patient_records_mrn_admission_status", columnList = "mrn, admission_status")
})
public class HisPatientRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "pid")
    private String pid;

    @Column(name = "mrn", nullable = false)
    private String mrn;

    @Column(name = "index_id")
    private String indexId;

    @Column(name = "his_encounter_id")
    private String hisEncounterId;

    @Column(name = "admission_count")
    private Integer admissionCount;

    @Column(name = "admission_time")
    private LocalDateTime admissionTime;

    @Column(name = "admission_diagnosis", columnDefinition = "TEXT")
    private String admissionDiagnosis;

    @Column(name = "admission_diagnosis_code", length = 1000)
    private String admissionDiagnosisCode;

    @Column(name = "bed_number", length = 50)
    private String bedNumber;

    @Column(name = "name", length = 100)
    private String name;

    @Column(name = "gender")
    private Integer gender;

    @Column(name = "date_of_birth")
    private LocalDateTime dateOfBirth;

    @Column(name = "height")
    private Float height;

    @Column(name = "weight")
    private Float weight;

    @Column(name = "blood_type", length = 50)
    private String bloodType;

    @Column(name = "blood_rh", length = 50)
    private String bloodRh;

    @Column(name = "past_medical_history", length = 255)
    private String pastMedicalHistory;

    @Column(name = "allergies", length = 255)
    private String allergies;

    @Column(name = "phone", length = 50)
    private String phone;

    @Column(name = "home_address", length = 255)
    private String homeAddress;

    @Column(name = "document_type", length = 50)
    private String documentType;

    @Column(name = "id_card_number", length = 100)
    private String idCardNumber;

    @Column(name = "nation", length = 50)
    private String nation;

    @Column(name = "native_place", length = 50)
    private String nativePlace;

    @Column(name = "occupation", length = 50)
    private String occupation;

    @Column(name = "emergency_contact_name", length = 50)
    private String emergencyContactName;

    @Column(name = "emergency_contact_relation", length = 50)
    private String emergencyContactRelation;

    @Column(name = "emergency_contact_phone", length = 50)
    private String emergencyContactPhone;

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;

    @Column(name = "insurance_type", length = 50)
    private String insuranceType;

    @Column(name = "insurance_number", length = 50)
    private String insuranceNumber;

    @Column(name = "medical_card_number", length = 50)
    private String medicalCardNumber;

    @Column(name = "is_vip_patient")
    private Boolean isVipPatient;

    @Column(name = "illness_severity_level", length = 50)
    private String illnessSeverityLevel;

    @Column(name = "chief_complaint", length = 255)
    private String chiefComplaint;

    @Column(name = "dept_code", length = 50)
    private String deptCode;

    @Column(name = "dept_name", length = 50)
    private String deptName;

    @Column(name = "ward_code", length = 50)
    private String wardCode;

    @Column(name = "ward_name", length = 255)
    private String wardName;

    @Column(name = "attending_doctor_name", length = 255)
    private String attendingDoctorName;

    @Column(name = "from_dept_name", length = 255)
    private String fromDeptName;

    @Column(name = "from_dept_id", length = 255)
    private String fromDeptId;

    @Column(name = "admission_status")
    private Integer admissionStatus;

    @Column(name = "dept_admission_time")
    private LocalDateTime deptAdmissionTime;

    @Column(name = "diagnosis_time")
    private LocalDateTime diagnosisTime;

    @Column(name = "diagnosis_code", length = 1000)
    private String diagnosisCode;

    @Column(name = "diagnosis", columnDefinition = "TEXT")
    private String diagnosis;

    @Column(name = "to_dept_id", length = 255)
    private String toDeptId;

    @Column(name = "to_dept_name", length = 255)
    private String toDeptName;

    @Column(name = "discharge_time")
    private LocalDateTime dischargeTime;

    @Column(name = "operation", length = 255)
    private String operation;

    @Column(name = "operation_time")
    private LocalDateTime operationTime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;
}
