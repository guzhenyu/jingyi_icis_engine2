package com.jingyicare.jingyi_icis_engine.entity.patients;

import java.time.*;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "diagnosis_history", indexes = {
    @Index(name = "idx_diagnosis_history_patient_id", columnList = "patient_id")
})
public class DiagnosisHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "patient_id")
    private Long patientId;

    @Column(name = "diagnosis", columnDefinition = "TEXT")
    private String diagnosis;

    @Column(name = "diagnosis_code", length = 1000)
    private String diagnosisCode;

    @Column(name = "diagnosis_tcm", columnDefinition = "TEXT")
    private String diagnosisTcm;

    @Column(name = "diagnosis_tcm_code", length = 1000)
    private String diagnosisTcmCode;

    @Column(name = "diagnosis_time", nullable = false)
    private LocalDateTime diagnosisTime;

    @Column(name = "diagnosis_account_id", length = 255, nullable = false)
    private String diagnosisAccountId;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "deleted_by", length = 255)
    private String deletedBy;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", length = 255)
    private String modifiedBy;

    @Column(name = "modified_by_account_name", length = 255)
    private String modifiedByAccountName;
}