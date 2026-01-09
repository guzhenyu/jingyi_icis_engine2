package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "patient_target_daily_balances",
    indexes = {
        @Index(name = "idx_patient_target_daily_balances_pid", columnList = "pid")
    }
)
public class PatientTargetDailyBalance {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增ID

    @Column(name = "pid", nullable = false)
    private Long pid;  // 病人ID

    @Column(name = "shift_start_time", nullable = false)
    private LocalDateTime shiftStartTime;  // 班次开始时间

    @Column(name = "target_balance_ml", nullable = false)
    private Double targetBalanceMl;  // 目标平衡量

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 记录的时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 记录人

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;  // 记录人姓名

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间
}