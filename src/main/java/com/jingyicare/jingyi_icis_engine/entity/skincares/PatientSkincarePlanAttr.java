package com.jingyicare.jingyi_icis_engine.entity.skincares;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_skincare_plan_attrs", indexes = {
    @Index(name = "idx_patient_skincare_plan_attrs_plan_attr", columnList = "patient_skincare_plan_id, skincare_attr_id")
})
public class PatientSkincarePlanAttr {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "patient_skincare_plan_id", nullable = false)
    private Long patientSkincarePlanId;

    @Column(name = "skincare_attr_id", nullable = false)
    private Integer skincareAttrId;

    @Column(name = "\"value\"", nullable = false, columnDefinition = "TEXT")
    private String value;

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
