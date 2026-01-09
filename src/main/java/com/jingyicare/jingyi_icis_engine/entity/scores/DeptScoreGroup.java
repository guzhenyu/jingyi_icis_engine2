package com.jingyicare.jingyi_icis_engine.entity.scores;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dept_score_groups", indexes = {
    @Index(name = "idx_dept_score_groups_dept_id_score_group_code", columnList = "dept_id, score_group_code")
})
public class DeptScoreGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 部门ID

    @Column(name = "score_group_code", nullable = false)
    private String scoreGroupCode;  // 评分类型编码

    @Column(name = "score_group_meta", nullable = false, length = 4000)
    private String scoreGroupMeta;  // 将 ScoreGroupMetaPB 序列化字符串并进行base64编码后的字符串

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
