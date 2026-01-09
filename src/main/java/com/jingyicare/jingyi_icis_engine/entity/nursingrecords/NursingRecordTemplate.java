package com.jingyicare.jingyi_icis_engine.entity.nursingrecords;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "nursing_record_templates", indexes = {
    @Index(name = "idx_nursing_record_templates_name", columnList = "entity_type, entity_id, name")
})
public class NursingRecordTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "entity_type", nullable = false)
    private Integer entityType;  // 模板类型： 1：部门模板；0： 个人模板；

    @Column(name = "entity_id", nullable = false)
    private String entityId;  // 实体id：如果type为1，则为deptId；如果type为0，则为accountId

    @Column(name = "name", nullable = false)
    private String name;  // 模板名称

    @Column(name = "content", nullable = false)
    private String content;  // 模板内容

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;  // 显示顺序

    @Column(name = "is_common", nullable = false)
    private Integer isCommon;  // 1: 常用模板 0：非常用模板

    @Column(name = "group_id", nullable = false)
    private Integer groupId;  // 模板分组id, 外键nursing_record_template_groups.id

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