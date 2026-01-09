package com.jingyicare.jingyi_icis_engine.entity.reports;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "ward_reports", indexes = {
    @Index(name = "idx_ward_reports_dept_shift_start", columnList = "dept_id, shift_start_time", unique = true)
})
public class WardReport {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增主键

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 科室ID

    @Column(name = "shift_start_time", nullable = false)
    private LocalDateTime shiftStartTime;  // 班次开始时间

    @Column(name = "ward_report_pb", nullable = false, columnDefinition = "TEXT")
    private String wardReportPb;  // 病区报告数据，WardReportPB的Base64字节码

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 最后修改时间

    @Column(name = "modified_by", nullable = false)
    private String modifiedBy;  // 最后修改人

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间
}
