package com.jingyicare.jingyi_icis_engine.entity.overviews;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "overview_groups", indexes = {
    @Index(name = "idx_overview_groups_name", columnList = "template_id, group_name")
})
public class OverviewGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // 自增主键

    @Column(name = "template_id", nullable = false)
    private Long templateId; // 所属模板ID，外键 overview_templates.id

    @Column(name = "group_name", nullable = false)
    private String groupName; // 分组名称

    @Column(name = "is_balance_group", nullable = false)
    private Boolean isBalanceGroup; // 是否平衡组

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder; // 分组显示顺序

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否删除

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人账号

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy; // 最后修改人账号

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName; // 最后修改人姓名

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt; // 最后修改时间
}