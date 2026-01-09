package com.jingyicare.jingyi_icis_engine.entity.reports;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dragable_form_templates", indexes = {
    @Index(name = "idx_dragable_form_templates_dept_id", columnList = "dept_id")
})
public class DragableFormTemplate {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;  // 自增id

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 部门id

    @Column(name = "name", nullable = false)
    private String name;  // 模板名称

    @Column(name = "template_pb", nullable = false, columnDefinition = "TEXT")
    private String templatePb;  // 模板内容, JfkTemplatePB 序列化后的字符串

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间
}
