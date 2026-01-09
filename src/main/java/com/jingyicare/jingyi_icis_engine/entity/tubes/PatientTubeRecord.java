package com.jingyicare.jingyi_icis_engine.entity.tubes;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_tube_records", indexes = {
    @Index(name = "idx_pt_records_pid_tube_type_root", columnList = "pid, tube_type_id, root_tube_record_id")
})
public class PatientTubeRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "pid", nullable = false)
    private Long pid;  // 病人ID

    @Column(name = "tube_type_id", nullable = false)
    private Integer tubeTypeId;  // 管道类型ID

    @Column(name = "tube_name", nullable = false)
    private String tubeName;  // 管道名称

    @Column(name = "inserted_at", nullable = false)
    private LocalDateTime insertedAt;  // 插管时间

    @Column(name = "inserted_by", nullable = false)
    private String insertedBy;  // 插管人/账户ID

    public String getInsertedByAccountName() {
        if (insertedByAccountName == null) {
            return insertedBy;
        }
        return insertedByAccountName;
    }

    @Column(name = "inserted_by_account_name")
    private String insertedByAccountName;  // 插管人姓名

    @Column(name = "planned_removal_at")
    private LocalDateTime plannedRemovalAt;  // 计划拔管时间

    @Column(name = "is_unplanned_removal")
    private Boolean isUnplannedRemoval;  // 是否非计划拔管

    @Column(name = "removal_reason")
    private String removalReason;  // 拔管原因

    @Column(name = "removed_at")
    private LocalDateTime removedAt;  // 拔管时间

    @Column(name = "removed_by")
    private String removedBy;  // 拔管人/账户ID

    public String getRemovedByAccountName() {
        if (removedByAccountName == null) {
            return removedBy == null ? "" : removedBy;
        }
        return removedByAccountName;
    }
    @Column(name = "removed_by_account_name")
    private String removedByAccountName;  // 拔管人姓名

    @Column(name = "prev_tube_record_id")
    private Long prevTubeRecordId;  // 前一根管道ID（换管时用）

    @Column(name = "root_tube_record_id")
    private Long rootTubeRecordId;  // 置换链的根管道ID

    @Column(name = "is_retained_on_discharge")
    private Boolean isRetainedOnDischarge;  // 是否带管出科

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 记录是否被人工删除

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人/账户ID

    @Column(name = "delete_reason")
    private String deleteReason;  // 删除理由

    @Column(name = "note", columnDefinition = "TEXT")
    private String note;  // 备注

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;  // 创建时间
}
