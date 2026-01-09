package com.jingyicare.jingyi_icis_engine.entity.nursingorders;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "nursing_execution_records", indexes = {
    @Index(name = "idx_nursing_execution_records_pid_nursing_order_id_plan_time", columnList = "pid, nursing_order_id, plan_time")
})
public class NursingExecutionRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增id

    @Column(name = "pid", nullable = false)
    private Long pid;  // 患者id

    @Column(name = "nursing_order_id", nullable = false)
    private Long nursingOrderId;  // 护理计划id，外键nursing_orders.id

    @Column(name = "plan_time", nullable = false)
    private LocalDateTime planTime;  // 计划执行时间

    @Column(name = "completed_by")
    private String completedBy;  // 执行人

    @Column(name = "completed_time")
    private LocalDateTime completedTime;  // 执行完成时间

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