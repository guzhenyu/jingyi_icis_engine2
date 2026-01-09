package com.jingyicare.jingyi_icis_engine.entity.tubes;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "tube_type_attributes", indexes = {
    @Index(name = "idx_tt_attributes_tube_type_id_attribute", columnList = "tube_type_id, attribute")
})
public class TubeTypeAttribute {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "tube_type_id", nullable = false)
    private Integer tubeTypeId;  // tube_types.id

    @Column(name = "attribute", nullable = false)
    private String attribute;  // 管道属性编码

    @Column(name = "name", nullable = false)
    private String name;  // 管道属性名称（展示）

    @Column(name = "ui_type", nullable = false)
    private Integer uiType;  // UI 类型

    @Column(name = "opt_value", nullable = false)
    private String optValue;  // 可选值

    @Column(name = "default_value", nullable = false)
    private String defaultValue;  // 默认值

    @Column(name = "unit")
    private String unit;  // 单位

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;  // 展示顺序

    @Column(name = "is_deleted")
    private Boolean isDeleted;  // 是否删除

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间
}