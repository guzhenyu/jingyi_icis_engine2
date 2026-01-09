package com.jingyicare.jingyi_icis_engine.entity.shifts;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "balance_stats_shifts", indexes = {
    @Index(name = "idx_balance_stats_shifts_dept_start_hour", columnList = "dept_id, effective_time")
})
public class BalanceStatsShift {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "start_hour", nullable = false)
    private Integer startHour;

    @Column(name = "effective_time", nullable = false)
    private LocalDateTime effectiveTime;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "modified_by")
    private String modifiedBy;

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;
}