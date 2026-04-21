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
    @Index(name = "idx_nursing_orders_pid_order_time", columnList = "pid, order_time"),
    @Index(name = "idx_nursing_orders_medical_order_id", columnList = "medical_order_id")
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

    @Column(name = "order_template_id")
    private Integer orderTemplateId;  // 模板id，外键nursing_order_templates.id；HIS同步护嘱可为空

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

    @Column(name = "source")
    private String source;  // 来源，medical_orders 表示由HIS医嘱同步

    @Column(name = "medical_order_id")
    private String medicalOrderId;  // 来源 medical_orders.order_id

    @Column(name = "medical_order_group_id")
    private String medicalOrderGroupId;  // 来源 medical_orders.group_id

    @Column(name = "synced_at")
    private LocalDateTime syncedAt;  // 最近一次同步时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间
}
