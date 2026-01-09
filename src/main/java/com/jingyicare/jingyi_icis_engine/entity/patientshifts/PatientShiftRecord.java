package com.jingyicare.jingyi_icis_engine.entity.patientshifts;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "patient_shift_records",
    indexes = {
        @Index(name = "idx_patient_shift_records_pid_shift_name_shift_start", columnList = "pid, shift_name, shift_start")
    }
)
public class PatientShiftRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // 主键

    @Column(name = "pid", nullable = false)
    private Long pid; // 病人id

    @Column(name = "content", nullable = false)
    private String content; // 交班内容

    @Column(name = "shift_nurse_id", nullable = false)
    private String shiftNurseId; // 交班护士ID

    @Column(name = "shift_nurse_name", nullable = false)
    private String shiftNurseName; // 交班护士姓名

    @Column(name = "shift_name", nullable = false)
    private String shiftName; // 班次名称

    @Column(name = "shift_start", nullable = false)
    private LocalDateTime shiftStart; // 班次开始时间

    @Column(name = "shift_end", nullable = false)
    private LocalDateTime shiftEnd; // 班次结束时间

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt; // 最后修改时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间
}