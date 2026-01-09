package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "patient_monitoring_group_params",
    indexes = {
        @Index(
            name = "idx_patient_monitoring_group_params_patient_id_param_code",
            columnList = "patient_monitoring_group_id, monitoring_param_code"
        )
    }
)
public class PatientMonitoringGroupParam {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id; // 主键

    @Column(name = "patient_monitoring_group_id", nullable = false)
    private Integer patientMonitoringGroupId; // 病人观察分组ID

    @Column(name = "monitoring_param_code", nullable = false)
    private String monitoringParamCode; // 监测项参数编码

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder; // 参数显示顺序

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy; // 最后修改人

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt; // 最后修改时间
}