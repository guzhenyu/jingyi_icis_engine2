package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "patient_monitoring_groups",
    indexes = {
        @Index(name = "idx_patient_monitoring_groups_dept_id_name", columnList = "dept_monitoring_group_id, pid")
    }
)
public class PatientMonitoringGroup {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id; // 主键

    @Column(name = "dept_monitoring_group_id", nullable = false)
    private Integer deptMonitoringGroupId; // 科室观察分组ID

    @Column(name = "pid", nullable = false)
    private Long pid; // 病人ID

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