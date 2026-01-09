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
    name = "apache_ii_scores",
    indexes = {
        @Index(name = "idx_apache_ii_scores_pid_time", columnList = "pid, score_time")
    }
)
public class ApacheIIScore {

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

    // === ApsParams 展开 ===
    @Column(name = "body_temperature")
    private Float bodyTemperature;  // 体温(°C)

    @Column(name = "mean_arterial_pressure")
    private Float meanArterialPressure;  // 平均动脉压(MAP, mmHg)

    @Column(name = "heart_rate")
    private Float heartRate;  // 心率(次/分钟)

    @Column(name = "respiratory_rate")
    private Float respiratoryRate;  // 呼吸频率(次/分钟)

    @Column(name = "fio2")
    private Float fio2;  // 吸入氧浓度(0.21 ~ 1.0)

    @Column(name = "a_a_do2")
    private Float aADo2;  // A-aDO2(在 FiO2 < 0.5 时使用)

    @Column(name = "pao2")
    private Float pao2;  // PaO2(在 FiO2 >= 0.5 时使用)

    @Column(name = "ph")
    private Float ph;  // 动脉血 pH

    @Column(name = "hco3")
    private Float hco3;  // 血碳酸氢根 HCO3(mmol/L, 无pH时使用)

    @Column(name = "sodium")
    private Float sodium;  // 血钠 Na+(mmol/L)

    @Column(name = "potassium")
    private Float potassium;  // 血钾 K+(mmol/L)

    @Column(name = "creatinine")
    private Float creatinine;  // 血肌酐 Cr(mg/dL)

    @Column(name = "has_acute_renal_failure")
    private Boolean hasAcuteRenalFailure;  // 是否为急性肾衰竭

    @Column(name = "hematocrit")
    private Float hematocrit;  // 血球压积(如: %)

    @Column(name = "white_blood_cell_count")
    private Float whiteBloodCellCount;  // 白细胞 WBC(10^9/L)

    @Column(name = "glasgow_coma_scale")
    private Integer glasgowComaScale;  // GCS(3~15)

    @Column(name = "age", nullable = false)
    private Integer age;  // 年龄

    // === ChcParams 展开 ===
    @Column(name = "has_chronic_conditions")
    private Boolean hasChronicConditions;  // 是否存在慢性疾病

    @Column(name = "chc_cardio")
    private Boolean chcCardio;  // 心血管系统慢性病

    @Column(name = "chc_resp")
    private Boolean chcResp;  // 呼吸系统慢性病

    @Column(name = "chc_liver")
    private Boolean chcLiver;  // 肝脏慢性病

    @Column(name = "chc_kidney")
    private Boolean chcKidney;  // 肾脏慢性病

    @Column(name = "chc_immune")
    private Boolean chcImmune;  // 免疫功能障碍

    @Column(name = "non_operative_or_emergency_surgery", nullable = false)
    private Boolean nonOperativeOrEmergencySurgery;  // 非手术或急诊手术

    // Apache II 评分结果
    @Column(name = "aps_score", nullable = false)
    private Integer apsScore;  // Apache II Aps评分

    @Column(name = "age_score", nullable = false)
    private Integer ageScore;  // Apache II 年龄评分

    @Column(name = "chc_score", nullable = false)
    private Integer chcScore;  // Apache II Chc评分

    @Column(name = "apache_ii_score", nullable = false)
    private Integer apacheIiScore;  // Apache II 总分

    // 预计病死率
    @Column(name = "predicted_mortality_rate", nullable = false)
    private Float predictedMortalityRate;  // 预测死亡率

    @Column(name = "coeff")
    private String coeff;  // proto消息ApacheIIScoreMetaPB(只包含系数*_coef)实例序列化后的base64编码

    @Column(name = "is_operative", nullable = false)
    private Boolean isOperative;  // 是否为手术患者

    @Column(name = "is_emergency_operation")
    private Boolean isEmergencyOperation;  // 是否为急诊手术患者

    @Column(name = "mortality_factor_code", nullable = false)
    private String mortalityFactorCode;  // （非）手术患者诊断主因编码

    @Column(name = "mortality_factor_name")
    private String mortalityFactorName;  // （非）手术患者诊断主因名称


    // 软删除及修改信息
    @Column(name = "is_deleted", nullable = false, columnDefinition = "BOOLEAN DEFAULT FALSE")
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人账号

    @Column(name = "deleted_by_account_name")
    private String deletedByAccountName;  // 删除人姓名

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "created_by")
    private String createdBy;  // 创建人账号

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;  // 创建时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人账号

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 最后修改时间
}