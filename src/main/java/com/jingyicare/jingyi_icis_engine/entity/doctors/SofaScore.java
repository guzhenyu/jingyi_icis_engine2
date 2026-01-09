package com.jingyicare.jingyi_icis_engine.entity.doctors;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(
    name = "sofa_scores",
    indexes = {
        @Index(name = "idx_sofa_scores_pid_time", columnList = "pid, score_time")
    }
)
public class SofaScore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;  // 自增主键ID

    @Column(name = "pid", nullable = false)
    private Long pid;  // 病人ID

    @Column(name = "dept_id", nullable = false)
    private String deptId;  // 所属科室ID

    @Column(name = "score_time", nullable = false)
    private LocalDateTime scoreTime;  // 评分时间

    @Column(name = "scored_by", nullable = false)
    private String scoredBy;  // 评分人账号

    @Column(name = "scored_by_account_name")
    private String scoredByAccountName;  // 评分人姓名

    @Column(name = "eval_start_at")
    private LocalDateTime evalStartAt;  // 评估开始时间

    @Column(name = "eval_end_at")
    private LocalDateTime evalEndAt;  // 评估结束时间

    @Column(name = "pao2_fio2_ratio")
    private Float pao2Fio2Ratio;  // PaO2/FiO2 比率 (mmHg)

    @Column(name = "respiratory_support")
    private Boolean respiratorySupport;  // 是否有呼吸支持

    @Column(name = "platelet_count")
    private Float plateletCount;  // 血小板计数 (x10^3/μL)

    @Column(name = "bilirubin")
    private Float bilirubin;  // 胆红素 (mg/dL)

    @Column(name = "circulation_mean_arterial_pressure")
    private Float circulationMeanArterialPressure;  // 平均动脉压 (mmHg)

    @Column(name = "circulation_dopamine_dose")
    private Float circulationDopamineDose;  // 多巴胺 (μg/kg/min)

    @Column(name = "circulation_epinephrine_dose")
    private Float circulationEpinephrineDose;  // 肾上腺素 (μg/kg/min)

    @Column(name = "circulation_norepinephrine_dose")
    private Float circulationNorepinephrineDose;  // 去甲肾上腺素 (μg/kg/min)

    @Column(name = "circulation_dobutamine_is_used")
    private Boolean circulationDobutamineIsUsed;  // 是否使用多巴酚丁胺

    @Column(name = "glasgow_coma_scale")
    private Integer glasgowComaScale;  // 格拉斯哥昏迷评分 (GCS, 3-15)

    @Column(name = "renal_creatinine")
    private Float renalCreatinine;  // 血肌酐 (mg/dL)

    @Column(name = "renal_urine_output")
    private Float renalUrineOutput;  // 尿量 (mL/day)

    @Column(name = "sofa_score", nullable = false)
    private Integer sofaScore;  // SOFA 总分

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 疾病排除人

    @Column(name = "deleted_by_account_name")
    private String deletedByAccountName;  // 疾病排除人姓名

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 排除时间

    @Column(name = "created_by", nullable = false)
    private String createdBy;  // 创建人

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;  // 创建时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 记录人

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 记录时间
}