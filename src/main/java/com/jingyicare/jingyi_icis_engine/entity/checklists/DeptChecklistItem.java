package com.jingyicare.jingyi_icis_engine.entity.checklists;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dept_checklist_items")
public class DeptChecklistItem {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "group_id", nullable = false)
    private Integer groupId;

    @Column(name = "item_name", nullable = false)
    private String itemName;

    @Column(name = "is_critical", nullable = false)
    private Boolean isCritical;

    @Column(name = "comments")
    private String comments;

    @Column(name = "has_note", nullable = false)
    private Boolean hasNote;

    @Column(name = "default_note")
    private String defaultNote;

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