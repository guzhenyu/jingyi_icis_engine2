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
    name = "patient_bed_history",
    indexes = {
        @Index(name = "idx_patient_bed_history_patient_id", columnList = "patientId")
    }
)
public class PatientBedHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 对应 BIGSERIAL

    @Column(name = "patient_id")
    private Long patientId;  // 病人id（外键 patient_records.id）- 普通字段处理

    @Column(name = "his_bed_number")
    private String hisBedNumber;  // his床位号

    @Column(name = "device_bed_number")
    private String deviceBedNumber;  // 设备床位号

    @Column(name = "display_bed_number")
    private String displayBedNumber;  // 显示床位号

    @Column(name = "switch_time", nullable = false)
    private LocalDateTime switchTime;  // 换床时间

    @Column(name = "switch_type", nullable = false)
    private Integer switchType;  // 换床类型：0-普通换床，1-重返出科，2-重返入科

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;  // 修改人姓名

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间

}