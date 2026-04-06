package com.jingyicare.jingyi_icis_engine.entity.skincares;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_skincare_records", indexes = {
    @Index(name = "idx_patient_skincare_records_pid_plan_created", columnList = "pid, patient_skincare_plan_id, created_at"),
    @Index(name = "idx_patient_skincare_records_dept_pid", columnList = "dept_id, pid")
})
public class PatientSkincareRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "pid", nullable = false)
    private Long pid;

    @Column(name = "patient_skincare_plan_id", nullable = false)
    private Long patientSkincarePlanId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "modified_by")
    private String modifiedBy;

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;
}
