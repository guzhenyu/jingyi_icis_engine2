package com.jingyicare.jingyi_icis_engine.entity.patients;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_settings")
public class PatientSettings {

    @Id
    @Column(name = "pid", nullable = false)
    private Long pid;  // 病人ID

    @Column(name = "report_cfg", nullable = false, columnDefinition = "TEXT")
    private String reportCfg;  // PatientReportConfigPB 的 Base64字节码

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 最后修改时间

    @Column(name = "modified_by", nullable = false)
    private String modifiedBy;  // 最后修改人
}
