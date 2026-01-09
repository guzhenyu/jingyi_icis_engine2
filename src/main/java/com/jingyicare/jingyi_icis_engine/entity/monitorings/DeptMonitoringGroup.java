package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(
    name = "dept_monitoring_groups",
    indexes = {
        @Index(name = "idx_dept_monitoring_groups_dept_id_name", columnList = "dept_id, name")
    }
)
public class DeptMonitoringGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id; // 主键

    @Column(name = "dept_id", nullable = false)
    private String deptId; // 科室ID

    @Column(name = "name", nullable = false)
    private String name; // 观察量分组名称，比如 "生命体征"

    @Column(name = "group_type")
    private Integer groupType; // 分组类型：0：出入量，1：观察项

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder; // 分组间显示顺序

    @Column(name = "sum_type_pb", nullable = false)
    private String sumTypePb; // 将ValueMeta序列化并用base64编码的字符串

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy; // 最后修改人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt; // 最后修改时间
}
