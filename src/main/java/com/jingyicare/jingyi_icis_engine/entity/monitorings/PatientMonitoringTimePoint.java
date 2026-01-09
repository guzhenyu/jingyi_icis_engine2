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
    name = "patient_monitoring_time_points",
    indexes = {
        @Index(
            name = "idx_patient_monitoring_time_points_pid_time_point",
            columnList = "pid, time_point"
        )
    }
)
public class PatientMonitoringTimePoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // 主键

    @Column(name = "pid", nullable = false)
    private Long pid; // 病人ID

    @Column(name = "dept_id", nullable = false)
    private String deptId; // 科室ID

    @Column(name = "time_point", nullable = false)
    private LocalDateTime timePoint; // 观察时刻

    @Column(name = "modified_by", length = 255)
    private String modifiedBy; // 记录人

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt; // 记录的时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否已删除

    @Column(name = "deleted_by", length = 255)
    private String deletedBy; // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间
}