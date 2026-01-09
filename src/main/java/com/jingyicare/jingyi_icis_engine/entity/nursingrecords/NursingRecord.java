package com.jingyicare.jingyi_icis_engine.entity.nursingrecords;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(name = "nursing_records", indexes = {
    @Index(name = "idx_nursing_records_patient_effective_time", columnList = "patient_id, effective_time")
})
public class NursingRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;  // 患者id

    @Column(name = "content", nullable = false)
    private String content;  // 护理记录的内容

    @Column(name = "effective_time", nullable = false)
    private LocalDateTime effectiveTime;  // 护理记录的记录时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "source")
    private String source;  // 数据来源，比如template_id&params，等

    @Column(name = "patient_critical_lis_handling_id")
    private Integer patientCriticalLisHandlingId;  // 患者危急值处理ID

    @Column(name = "reviewed_by")
    private String reviewedBy;  // 审核人的account_id

    public String getReviewedByAccountName() {
        if (reviewedByAccountName == null) {
            return reviewedBy;
        }
        return reviewedByAccountName;
    }
    @Column(name = "reviewed_by_account_name")
    private String reviewedByAccountName;  // 审核人的account_name

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;  // 审核时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人

    public String getModifiedByAccountName() {
        if (modifiedByAccountName == null) {
            return modifiedBy;
        }
        return modifiedByAccountName;
    }
    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;  // 最后修改人的account_name

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 最后修改时间

    @Column(name = "created_by")
    private String createdBy;  // 创建人

    public String getCreatedByAccountName() {
        if (createdByAccountName == null) {
            return createdBy;
        }
        return createdByAccountName;
    }

    @Column(name = "created_by_account_name")
    private String createdByAccountName;  // 创建人的account_name

    @Column(name = "created_at")
    private LocalDateTime createdAt;  // 创建时间
}
