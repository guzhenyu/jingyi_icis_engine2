package com.jingyicare.jingyi_icis_engine.entity.snapshots;

import java.time.LocalDate;
import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_snapshots", indexes = {
    @Index(name = "idx_patient_snapshots_pid_statistics_date",
        columnList = "pid, statistics_date", unique = true)
})
public class PatientSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增主键

    @Column(name = "pid", nullable = false)
    private Long pid;  // 病人ID，对应 patient_records.id

    @Column(name = "statistics_date", nullable = false)
    private LocalDate statisticsDate;  // 统计日期，不含时间和时区

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;  // PatientSnapshotPB 的 Base64 字节码，snapshotAt 为空时失效

    @Column(name = "last_modified_at")
    private LocalDateTime lastModifiedAt;  // 相关业务增删改时记录的 UTC 时间

    @Column(name = "snapshot_at")
    private LocalDateTime snapshotAt;  // 快照时点（UTC），持续计算内容的实际截止
}
