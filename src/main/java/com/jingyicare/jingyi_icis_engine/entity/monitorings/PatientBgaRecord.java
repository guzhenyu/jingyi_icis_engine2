package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import java.time.LocalDateTime;
import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_bga_records",
       indexes = @Index(name = "idx_patient_bga_records_pid_cateid_time",
                        columnList = "pid, bga_category_id, effective_time"))
public class PatientBgaRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "pid", nullable = false)
    private Long pid;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "bga_category_id", nullable = false)
    private Integer bgaCategoryId;

    @Column(name = "bga_category_name")
    private String bgaCategoryName;

    @Column(name = "lis_category_code")
    private String lisCategoryCode;

    @Column(name = "recorded_by")
    private String recordedBy;

    @Column(name = "recorded_by_account_name")
    private String recordedByAccountName;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @Column(name = "effective_time", nullable = false)
    private LocalDateTime effectiveTime;

    @Column(name = "raw_record_id")
    private Long rawRecordId;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_by_account_name")
    private String deletedByAccountName;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "modified_by")
    private String modifiedBy;

    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "reviewed_by")
    private String reviewedBy;

    @Column(name = "reviewed_by_account_name")
    private String reviewedByAccountName;

    @Column(name = "reviewed_at")
    private LocalDateTime reviewedAt;
}