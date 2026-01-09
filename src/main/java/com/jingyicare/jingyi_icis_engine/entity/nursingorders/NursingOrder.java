package com.jingyicare.jingyi_icis_engine.entity.nursingorders;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "nursing_orders", indexes = {
    @Index(name = "idx_nursing_orders_pid_order_time", columnList = "pid, order_time")
})
public class NursingOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增id

    @Column(name = "pid", nullable = false)
    private Long pid;  // 患者id

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 部门id

    @Column(name = "order_template_id", nullable = false)
    private Integer orderTemplateId;  // 模板id，外键nursing_order_templates.id

    @Column(name = "name", nullable = false)
    private String name;  // 护理计划名称

    @Column(name = "duration_type", nullable = false)
    private Integer durationType;  // 0: 临时护嘱, 1: 长期护嘱

    @Column(name = "medication_freq_code", nullable = false)
    private String medicationFreqCode;  // 频次编码，外键：medication_frequencies.code

    @Column(name = "order_by")
    private String orderBy;  // 开立人

    @Column(name = "order_time", nullable = false)
    private LocalDateTime orderTime;  // 开立时间

    @Column(name = "stop_by")
    private String stopBy;  // 停止人

    @Column(name = "stop_time")
    private LocalDateTime stopTime;  // 停止时间

    @Column(name = "note")
    private String note;  // 备注

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
