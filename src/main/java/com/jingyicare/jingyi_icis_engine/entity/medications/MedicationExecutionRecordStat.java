package com.jingyicare.jingyi_icis_engine.entity.medications;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "medication_execution_record_stats",
    indexes = {
        @Index(
            name = "idx_med_execution_record_stats_record_time",
            columnList = "exe_record_id, stats_time",
            unique = true
        )
    }
)
public class MedicationExecutionRecordStat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增id

    @Column(name = "group_id", nullable = false)
    private Long groupId;  // 药物订单组id

    @Column(name = "exe_record_id", nullable = false)
    private Long exeRecordId;  // 药物执行记录id

    @Column(name = "stats_time", nullable = false)
    private LocalDateTime statsTime;  // 统计时间

    @Column(name = "consumed_ml", nullable = false)
    private Double consumedMl;  // 消耗液体量的毫升数

    @Column(name = "is_final", nullable = false)
    private Boolean isFinal;  // 是否是对应的execution_record_id的最后一条统计记录

    @Column(name = "remain_ml")
    private Double remainMl;  // 剩余液体量的毫升数
}
