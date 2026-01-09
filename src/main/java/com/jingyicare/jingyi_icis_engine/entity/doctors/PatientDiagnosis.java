package com.jingyicare.jingyi_icis_engine.entity.doctors;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "patient_diagnoses",
    indexes = {
        @Index(name = "idx_patient_diagnoses_pid_disease_code", columnList = "pid, disease_code, confirmed_at")
    }
)
public class PatientDiagnosis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // 自增ID

    @Column(name = "pid", nullable = false)
    private Long pid; // 病人ID

    @Column(name = "dept_id", nullable = false)
    private String deptId; // 科室ID

    @Column(name = "disease_code", nullable = false)
    private String diseaseCode; // 疾病编码

    @Lob
    @Column(name = "disease_pbtxt", nullable = false, columnDefinition = "TEXT")
    private String diseasePbtxt; // 将DiseasePB序列化并base64后的字符串

    @Column(name = "eval_start_at", nullable = false)
    private LocalDateTime evalStartAt; // 评估开始时间

    @Column(name = "eval_end_at", nullable = false)
    private LocalDateTime evalEndAt; // 评估结束时间

    @Column(name = "confirmed_by", nullable = false)
    private String confirmedBy; // 确诊人

    @Column(name = "confirmed_by_account_name", nullable = false)
    private String confirmedByAccountName; // 确诊人姓名

    @Column(name = "confirmed_at", nullable = false)
    private LocalDateTime confirmedAt; // 确诊时间

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted; // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy; // 删除人

    @Column(name = "deleted_by_account_name")
    private String deletedByAccountName; // 删除人姓名

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt; // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy; // 记录人

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt; // 记录时间
}
