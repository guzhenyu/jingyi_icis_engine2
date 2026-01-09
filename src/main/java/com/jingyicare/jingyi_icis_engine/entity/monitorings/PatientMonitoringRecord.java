package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(
    name = "patient_monitoring_records",
    indexes = {
        @Index(
            name = "idx_pmr_pid_param_code_effective_time",
            columnList = "pid, monitoring_param_code, effective_time"
        )
    }
)
public class PatientMonitoringRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // 主键

    @Column(name = "pid", nullable = false)
    private Long pid; // 病人ID

    @Column(name = "dept_id", nullable = false)
    private String deptId; // 科室ID

    @Column(name = "monitoring_param_code", nullable = false)
    private String monitoringParamCode; // 观察参数编码，关联到monitoring_params.code

    @Column(name = "effective_time", nullable = false)
    private LocalDateTime effectiveTime; // paramValue的有效时间

    @Column(name = "param_value", nullable = false, length = 1000)
    private String paramValue; // 将MonitoringValuePB序列化字符串并进行base64编码后的字符串

    @Column(name = "param_value_str", length = 255)
    private String paramValueStr; // 记录时的值对应的字符串

    @Column(name = "unit", length = 100)
    private String unit; // 单位

    @Column(name = "device_id")
    private Integer deviceId; // 设备ID

    @Column(name = "source", length = 255, nullable = false)
    private String source; // 数据来源 (监护仪/人工/...)

    @Column(name = "note", length = 1000)
    private String note; // 备注

    @Column(name = "status", length = 255)
    private String status; // （备用字段）记录状态：已录入，已审核等

    @Column(name = "modified_by", length = 255)
    private String modifiedBy; // 记录人

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt; // 记录的时间

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean isDeleted = false; // 是否已删除

    @Column(name = "delete_reason", length = 255)
    private String deleteReason; // 删除理由

    @Column(name = "deleted_by", length = 255)
    private String deletedBy; // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间
}