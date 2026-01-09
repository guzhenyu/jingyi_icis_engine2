package com.jingyicare.jingyi_icis_engine.entity.tubes;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_tube_status_records", indexes = {
    @Index(name = "idx_pt_status_records_record_status", columnList = "patient_tube_record_id, tube_status_id, recorded_at")
})
public class PatientTubeStatusRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "patient_tube_record_id", nullable = false)
    private Long patientTubeRecordId;  // 病人插管记录ID

    @Column(name = "tube_status_id", nullable = false)
    private Integer tubeStatusId;  // 管道状态ID

    @Column(name = "\"value\"", nullable = false)  // value是保留字
    private String value;  // 管道状态值

    @Column(name = "recorded_by", nullable = false)
    private String recordedBy;  // 记录人/账户ID

    public String getRecordedByAccountName() {
        if (recordedByAccountName == null) {
            return recordedBy;
        }
        return recordedByAccountName;
    }
    @Column(name = "recorded_by_account_name")
    private String recordedByAccountName;  // 记录人姓名

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;  // 记录时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 记录是否被人工删除

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人/账户ID

    @Column(name = "delete_reason")
    private String deleteReason;  // 删除理由

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;  // 创建时间
}
