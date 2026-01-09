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
    name = "readmission_history",
    indexes = {
        @Index(name = "idx_readmission_history_patient_id", columnList = "patientId"),
        @Index(name = "idx_readmission_history_icu_discharging_account_id", columnList = "icuDischargingAccountId"),
        @Index(name = "idx_readmission_history_icu_admitting_account_id", columnList = "icuAdmittingAccountId")
    }
)
public class ReadmissionHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;  // 自增主键

    @Column(name = "patient_id", nullable = false)
    private Long patientId;  // 病人ID

    @Column(name = "readmission_reason")
    private String readmissionReason;  // 重返ICU原因

    @Column(name = "icu_discharge_time")
    private LocalDateTime icuDischargeTime;  // ICU出科时间

    @Column(name = "icu_discharge_edit_time")
    private LocalDateTime icuDischargeEditTime;  // ICU出科时间修改时间

    @Column(name = "icu_discharging_account_id")
    private String icuDischargingAccountId;  // ICU出科操作员

    @Column(name = "icu_admission_time")
    private LocalDateTime icuAdmissionTime;  // ICU入科时间

    @Column(name = "icu_admission_edit_time")
    private LocalDateTime icuAdmissionEditTime;  // ICU入科时间修改时间

    @Column(name = "icu_admitting_account_id")
    private String icuAdmittingAccountId;  // ICU入科操作员
}