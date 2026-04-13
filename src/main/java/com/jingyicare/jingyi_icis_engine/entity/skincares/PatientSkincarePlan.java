package com.jingyicare.jingyi_icis_engine.entity.skincares;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_skincare_plans", indexes = {
    @Index(name = "idx_patient_skincare_plans_pid_type_created", columnList = "pid, skincare_type_id, created_at"),
    @Index(name = "idx_patient_skincare_plans_dept_pid", columnList = "dept_id, pid")
})
public class PatientSkincarePlan {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "pid", nullable = false)
    private Long pid;

    @Column(name = "skincare_type_id", nullable = false)
    private Integer skincareTypeId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by")
    private String createdBy;

    @Column(name = "audited_by")
    private String auditedBy;

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
