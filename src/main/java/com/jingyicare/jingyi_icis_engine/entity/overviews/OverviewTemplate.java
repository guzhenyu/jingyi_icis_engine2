package com.jingyicare.jingyi_icis_engine.entity.overviews;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "overview_templates", indexes = {
    @Index(name = "idx_overview_templates_name", columnList = "dept_id, account_id, template_name")
})
public class OverviewTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // 自增主键

    @Column(name = "dept_id")
    private String deptId; // 所属科室ID

    @Column(name = "account_id")
    private String accountId; // 所属用户账号ID

    @Column(name = "created_by", nullable = false)
    private String createdBy; // 创建人账号

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt; // 创建时间

    @Column(name = "template_name", nullable = false)
    private String templateName; // 模板名称

    @Lob
    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes; // 备注

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder; // 模板显示顺序

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