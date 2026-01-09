package com.jingyicare.jingyi_icis_engine.entity.patients;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "patient_devices",
    indexes = {
        @Index(name = "idx_patient_devices_pid_did_btime", columnList = "patientId, deviceId, bindingTime")
    }
)
public class PatientDevice {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 对应 BIGSERIAL

    @Column(name = "patient_id", nullable = false)
    private Long patientId;  // 病人id（外键 patient_records.id）

    @Column(name = "device_id")
    private Integer deviceId;  // 设备id（外键 device_infos.device_id）

    @Column(name = "binding_time", nullable = false)
    private LocalDateTime bindingTime;  // 设备绑定时间

    @Column(name = "unbinding_time")
    private LocalDateTime unbindingTime;  // 设备解绑时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;  // 修改人姓名

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间
}