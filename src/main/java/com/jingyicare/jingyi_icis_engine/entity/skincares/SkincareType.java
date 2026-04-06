package com.jingyicare.jingyi_icis_engine.entity.skincares;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "skincare_types", indexes = {
    @Index(name = "idx_skincare_types_dept_type_name", columnList = "dept_id, type, name"),
    @Index(name = "idx_skincare_types_dept_type", columnList = "dept_id, type")
})
public class SkincareType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "type", nullable = false)
    private String type;

    @Column(name = "name", nullable = false)
    private String name;

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
