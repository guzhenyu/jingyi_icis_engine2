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
    name = "vte_padua_scores",
    indexes = {
        // Index based on the naming convention and common fields
        @Index(name = "idx_vte_padua_scores_pid_time", columnList = "pid, score_time")
    }
)
public class VTEPaduaScore {
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

    // --- VTE Risk Factors (1 Point) ---
    @Column(name = "age_70_or_older")
    private Boolean age70OrOlder;  // 年龄≥70岁

    @Column(name = "obesity_bmi_30")
    private Boolean obesityBmi30;  // 肥胖(体重指数≥30kg/m²)

    @Column(name = "acute_infection_rheumatic")
    private Boolean acuteInfectionRheumatic;  // 急性感染和(或)风湿性疾病

    @Column(name = "acute_mi_or_stroke")
    private Boolean acuteMiOrStroke;  // 急性心肌梗死和(或)缺血性脑卒中

    @Column(name = "hormone_therapy")
    private Boolean hormoneTherapy;  // 正在进行激素治疗

    @Column(name = "heart_or_respiratory_failure")
    private Boolean heartOrRespiratoryFailure;  // 心脏和(或)呼吸衰竭

    // --- VTE Risk Factors (2 Points) ---
    @Column(name = "recent_trauma_or_surgery")
    private Boolean recentTraumaOrSurgery;  // 近期(≤1个月)创伤或外科手术

    // --- VTE Risk Factors (3 Points) ---
    @Column(name = "thrombophilic_condition")
    private Boolean thrombophilicCondition;  // 有血栓形成倾向（易栓症筛查异常）

    @Column(name = "active_malignancy")
    private Boolean activeMalignancy;  // 活动性恶性肿瘤 (VTE Risk Factor)

    @Column(name = "prior_vte")
    private Boolean priorVte;  // 既往静脉血栓栓塞症

    @Column(name = "immobilization")
    private Boolean immobilization;  // 制动卧床≥3天

    // --- Total Score ---
    @Column(name = "total_score", nullable = false)
    private Integer totalScore;  // Padua总分

    // --- High Bleeding Risk (Single Factor) ---
    @Column(name = "active_gi_ulcer")
    private Boolean activeGiUlcer;  // 活动性胃肠道溃疡

    @Column(name = "bleeding_event_within_3months")
    private Boolean bleedingEventWithin3months;  // 入院前3个月内有出血事件

    @Column(name = "platelet_count_below_50")
    private Boolean plateletCountBelow50;  // 血小板计数<50*10^9/L

    // --- High Bleeding Risk (≥3 Factors Required) ---
    @Column(name = "age_85_or_older")
    private Boolean age85OrOlder;  // 年龄≥85岁

    @Column(name = "liver_dysfunction_inr_15")
    private Boolean liverDysfunctionInr15;  // 肝功能不全(INR > 1.5)

    @Column(name = "severe_renal_failure")
    private Boolean severeRenalFailure;  // 严重肾功能不全

    @Column(name = "icu_or_ccu_admission")
    private Boolean icuOrCcuAdmission;  // 入住ICU或CCU

    @Column(name = "central_venous_catheter")
    private Boolean centralVenousCatheter;  // 中心静脉置管

    @Column(name = "has_active_malignancy") // Corrected name
    private Boolean hasActiveMalignancy;  // 现患恶性肿瘤 (Bleeding Risk Factor)

    @Column(name = "rheumatic_disease")
    private Boolean rheumaticDisease;  // 风湿性疾病

    @Column(name = "male_gender")
    private Boolean maleGender;  // 男性

    // --- Bleeding Risk - Prevention Assessment ---
    @Column(name = "prevention_anticoagulant_only_assess")
    private Boolean preventionAnticoagulantOnlyAssess;  // 评估：抗凝药物预防

    @Column(name = "prevention_physical_only_assess")
    private Boolean preventionPhysicalOnlyAssess;  // 评估：物理预防

    @Column(name = "prevention_anticoagulant_physical_assess")
    private Boolean preventionAnticoagulantPhysicalAssess;  // 评估：抗凝药物+物理预防

    @Column(name = "prevention_unavailable_assess")
    private Boolean preventionUnavailableAssess;  // 评估：预防措施不可用

    // --- Bleeding Risk - Prevention Execution ---
    @Column(name = "prevention_anticoagulant_only_exec")
    private Boolean preventionAnticoagulantOnlyExec;  // 执行：抗凝药物预防

    @Column(name = "prevention_physical_only_exec")
    private Boolean preventionPhysicalOnlyExec;  // 执行：物理预防

    @Column(name = "prevention_anticoagulant_physical_exec")
    private Boolean preventionAnticoagulantPhysicalExec;  // 执行：抗凝药物+物理预防

    @Column(name = "prevention_unavailable_exec")
    private Boolean preventionUnavailableExec;  // 执行：预防措施不可用

    // --- Nursing Measures - Basic ---
    @Column(name = "elevate_limbs")
    private Boolean elevateLimbs;  // 抬高患者肢体

    @Column(name = "ankle_exercise")
    private Boolean ankleExercise;  // 踝关节活动

    @Column(name = "quadriceps_contraction")
    private Boolean quadricepsContraction;  // 股四头肌收缩

    @Column(name = "deep_breathing_or_balloon")
    private Boolean deepBreathingOrBalloon;  // 做深呼吸或吹气球

    @Column(name = "quit_smoking_alcohol")
    private Boolean quitSmokingAlcohol;  // 戒烟戒酒

    @Column(name = "drink_more_water")
    private Boolean drinkMoreWater;  // 多饮水

    @Column(name = "maintain_bowel_regular")
    private Boolean maintainBowelRegular;  // 保持大便通畅

    @Column(name = "turn_every_2h_or_leg_movement")
    private Boolean turnEvery2hOrLegMovement;  // 每2小时翻身或主动屈伸下肢

    @Column(name = "get_out_of_bed")
    private Boolean getOutOfBed;  // 下床活动

    @Column(name = "other_basic_measures", columnDefinition = "TEXT")
    private String otherBasicMeasures;  // 其他基础护理措施

    // --- Nursing Measures - Mechanical ---
    @Column(name = "intermittent_pneumatic_compression")
    private Boolean intermittentPneumaticCompression;  // 间歇性气压装置

    @Column(name = "graded_compression_stockings")
    private Boolean gradedCompressionStockings;  // 分级加压弹力袜

    @Column(name = "foot_vein_pump")
    private Boolean footVeinPump;  // 足底静脉泵

    // --- Nursing Measures - Pharmacological ---
    @Column(name = "low_molecular_heparin_injection")
    private Boolean lowMolecularHeparinInjection;  // 低分子肝素注射

    @Column(name = "rivaroxaban")
    private Boolean rivaroxaban;  // 利伐沙班

    @Column(name = "warfarin")
    private Boolean warfarin;  // 华法林

    @Column(name = "other_pharmacological_measures", columnDefinition = "TEXT")
    private String otherPharmacologicalMeasures;  // 其他药物措施

    // --- Soft Delete & Audit Info ---
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人账号

    @Column(name = "deleted_by_account_name")
    private String deletedByAccountName;  // 删除人姓名

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 最后修改人账号

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 最后修改时间
}