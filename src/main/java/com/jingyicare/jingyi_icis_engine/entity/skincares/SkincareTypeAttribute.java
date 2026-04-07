package com.jingyicare.jingyi_icis_engine.entity.skincares;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "skincare_type_attributes", indexes = {
    @Index(name = "idx_skincare_type_attrs_type_attr_code", columnList = "skincare_type_id, attr_code"),
    @Index(name = "idx_skincare_type_attrs_type_attr_name", columnList = "skincare_type_id, attr_name")
})
public class SkincareTypeAttribute {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "skincare_type_id", nullable = false)
    private Integer skincareTypeId;

    @Column(name = "attr_code", nullable = false)
    private String attrCode;

    @Column(name = "attr_name", nullable = false)
    private String attrName;

    @Column(name = "category_id")
    private Integer categoryId;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;

    @Column(name = "attr_type_pb", nullable = false, columnDefinition = "TEXT")
    private String attrTypePb;

    @Column(name = "is_initial", nullable = false)
    private Boolean isInitial;

    @Column(name = "is_maintenance", nullable = false)
    private Boolean isMaintenance;

    @Column(name = "show_in_table", nullable = false)
    private Boolean showInTable;

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
