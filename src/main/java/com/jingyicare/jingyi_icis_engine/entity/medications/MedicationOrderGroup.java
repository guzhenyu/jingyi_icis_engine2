package com.jingyicare.jingyi_icis_engine.entity.medications;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "medication_order_groups", indexes = {
    @Index(name = "idx_medication_order_groups_patient_id_group_id", columnList = "patient_id, group_id")
})
public class MedicationOrderGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "his_patient_id", nullable = false)
    private String hisPatientId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "group_id", nullable = false)
    private String groupId;

    public String getMedicalOrderIds() {
        return medicalOrderIds == null ? "" : medicalOrderIds;
    }
    @Column(name = "medical_order_ids", length = 1000)
    private String medicalOrderIds;

    public String getHisMrn() {
        return hisMrn == null ? "" : hisMrn;
    }
    @Column(name = "his_mrn")
    private String hisMrn;

    public String getOrderingDoctor() {
        return orderingDoctor == null ? "" : orderingDoctor;
    }
    @Column(name = "ordering_doctor")
    private String orderingDoctor;

    public String getOrderingDoctorId() {
        return orderingDoctorId == null ? "" : orderingDoctorId;
    }
    @Column(name = "ordering_doctor_id")
    private String orderingDoctorId;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "order_type", nullable = false)
    private String orderType;

    @Column(name = "status", nullable = false)
    private String status;

    @Column(name = "order_time", nullable = false)
    private LocalDateTime orderTime;

    @Column(name = "stop_time")
    private LocalDateTime stopTime;

    @Column(name = "cancel_time")
    private LocalDateTime cancelTime;

    @Column(name = "order_validity", nullable = false)
    private Integer orderValidity;

    public String getInconsistencyExplanation() {
        return inconsistencyExplanation == null ? "" : inconsistencyExplanation;
    }
    @Column(name = "inconsistency_explanation", length = 500)
    private String inconsistencyExplanation;

    public String getMedicationDosageGroup() {
        return medicationDosageGroup == null ? "" : medicationDosageGroup;
    }
    @Column(name = "medication_dosage_group", length = 5000)
    private String medicationDosageGroup;

    @Column(name = "order_duration_type", nullable = false)
    private Integer orderDurationType;

    @Column(name = "freq_code", nullable = false)
    private String freqCode;

    @Column(name = "plan_time", nullable = false)
    private LocalDateTime planTime;

    @Column(name = "first_day_exe_count")
    private Integer firstDayExeCount;

    @Column(name = "administration_route_code", nullable = false)
    private String administrationRouteCode;

    @Column(name = "administration_route_name", nullable = false)
    private String administrationRouteName;

    @Column(name = "note")
    private String note;

    public String getReviewer() {
        return reviewer == null ? "" : reviewer;
    }
    @Column(name = "reviewer")
    private String reviewer;

    public String getReviewerId() {
        return reviewerId == null ? "" : reviewerId;
    }
    @Column(name = "reviewer_id")
    private String reviewerId;

    @Column(name = "review_time")
    private LocalDateTime reviewTime;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}