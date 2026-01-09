package com.jingyicare.jingyi_icis_engine.entity.doctors;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dept_doctor_score_types", indexes = {
    @Index(name = "idx_dept_doctor_score_types_dept_id_code", columnList = "dept_id, code")
})
public class DeptDoctorScoreType {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 部门ID

    @Column(name = "code", nullable = false)
    private String code;  // 评分类型编码

    @Column(name = "name", nullable = false)
    private String name;  // 评分类型名称

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;  // 评分类型的显示顺序

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