package com.jingyicare.jingyi_icis_engine.entity.checklists;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dept_checklist_groups", indexes = {
    @Index(name = "idx_dept_checklist_groups_name", columnList = "dept_id, group_name")
})
public class DeptChecklistGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "group_name", nullable = false)
    private String groupName;

    @Column(name = "comments")
    private String comments;

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