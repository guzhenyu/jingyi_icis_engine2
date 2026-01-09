package com.jingyicare.jingyi_icis_engine.entity.reports;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dragable_forms", indexes = {
    @Index(name = "idx_dragable_forms_pid_tid_doctime", columnList = "pid, template_id, documented_at")
})
public class DragableForm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增id

    @Column(name = "pid", nullable = false)
    private Long pid;  // 病人id

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 部门id

    @Column(name = "template_id", nullable = false)
    private Integer templateId;  // 模板id, 外键dragable_form_templates.id

    @Column(name = "form_pb", nullable = false, columnDefinition = "TEXT")
    private String formPb;  // 表单内容, JfkFormInstancePB 序列化后的字符串

    @Column(name = "documented_at", nullable = false)
    private LocalDateTime documentedAt;  // 报表时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 最后修改时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间
}
