package com.jingyicare.jingyi_icis_engine.entity.nursingrecords;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_critical_lis_handlings")
public class PatientCriticalLisHandling {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "pid", nullable = false)
    private Long pid;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "actions", nullable = false, length = 1000)
    private String actions;

    @Column(name = "effective_time", nullable = false)
    private LocalDateTime effectiveTime;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "modified_by")
    private String modifiedBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;
}