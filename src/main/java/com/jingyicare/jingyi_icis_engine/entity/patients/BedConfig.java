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
    name = "bed_configs",
    indexes = {
        @Index(name = "idx_bed_configs_deptid_hisbed", columnList = "departmentId, hisBedNumber")
    }
)
public class BedConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 对应 BIGSERIAL

    @Column(name = "department_id", nullable = false)
    private String departmentId;  // 科室编码

    @Column(name = "his_bed_number", nullable = false)
    private String hisBedNumber;  // his床位号

    @Column(name = "device_bed_number", nullable = false)
    private String deviceBedNumber;  // 设备床位号

    @Column(name = "display_bed_number", nullable = false)
    private String displayBedNumber;  // 显示床位号

    @Column(name = "bed_type", nullable = false)
    private Integer bedType;  // 床位类型，icis_device.proto:DeviceEnums.bed_type

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

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;  // 修改人姓名

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间
}