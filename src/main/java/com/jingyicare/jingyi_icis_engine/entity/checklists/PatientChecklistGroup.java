package com.jingyicare.jingyi_icis_engine.entity.checklists;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_checklist_groups", indexes = {
    @Index(name = "idx_patient_checklist_groups_group", columnList = "group_id")
})
public class PatientChecklistGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "record_id", nullable = false)
    private Integer recordId;

    @Column(name = "group_id", nullable = false)
    private Integer groupId;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "modified_by")
    private String modifiedBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;
}