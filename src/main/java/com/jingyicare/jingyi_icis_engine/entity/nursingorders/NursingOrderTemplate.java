package com.jingyicare.jingyi_icis_engine.entity.nursingorders;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "nursing_order_templates", indexes = {
    @Index(name = "idx_nursing_order_templates_dept_name", columnList = "dept_id, group_id, name")
})
public class NursingOrderTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 部门id

    @Column(name = "group_id", nullable = false)
    private Integer groupId;  // 模板分组id，外键：nursing_order_template_groups.id

    @Column(name = "name", nullable = false)
    private String name;  // 模板名称

    @Column(name = "duration_type", nullable = false)
    private Integer durationType;  // 0: 临时护嘱, 1: 长期护嘱

    @Column(name = "medication_freq_code", nullable = false)
    private String medicationFreqCode;  // 频次编码，外键：medication_frequencies.code

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;  // 显示顺序

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间
}