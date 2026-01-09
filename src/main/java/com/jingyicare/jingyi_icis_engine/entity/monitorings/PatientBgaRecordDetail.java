package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_bga_record_details",
       indexes = @Index(name = "idx_patient_bga_record_details_record_param",
                        columnList = "record_id, monitoring_param_code"))
public class PatientBgaRecordDetail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "record_id", nullable = false)
    private Long recordId;                       // 外键 patient_bga_records.id

    @Column(name = "monitoring_param_code", nullable = false)
    private String monitoringParamCode;          // 监测参数编码

    @Column(name = "param_value", nullable = false, length = 255)
    private String paramValue;                        // GenericValuePB(base64)

    @Column(name = "param_value_str")
    private String paramValueStr;                     // 原始字符串值

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;                     // 是否已删除

    @Column(name = "deleted_by", length = 255)
    private String deletedBy;                      // 删除人账号

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;          // 删除时间

    @Column(name = "modified_by", length = 255)
    private String modifiedBy;                     // 最后修改人账号

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;         // 最后修改时间
}