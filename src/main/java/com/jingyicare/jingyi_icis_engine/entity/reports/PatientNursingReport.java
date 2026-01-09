package com.jingyicare.jingyi_icis_engine.entity.reports;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_nursing_reports", indexes = {
    @Index(name = "idx_patient_nursing_reports_pid_effective_time", columnList = "pid, effective_time_midnight")
})
public class PatientNursingReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增主键

    @Column(name = "pid", nullable = false)
    private Long pid;  // 病人ID

    @Column(name = "effective_time_midnight", nullable = false)
    private LocalDateTime effectiveTimeMidnight;  // 护理单对应的时间（本地时间当天0点对应的UTC时间）

    @Column(name = "data_pb", columnDefinition = "TEXT")
    private String dataPb;  // 护理单数据，pb的Base64字节码

    @Column(name = "last_processed_at")
    private LocalDateTime lastProcessedAt;  // 最后处理时间

    @Column(name = "latest_data_time")
    private LocalDateTime latestDataTime;  // 最新数据时间，结合last_processed_at用于判断data_pb是否需要更新
}