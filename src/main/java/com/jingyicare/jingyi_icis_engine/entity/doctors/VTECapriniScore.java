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
    name = "vte_caprini_scores",
    indexes = {
        // Index based on the naming convention and common fields, matching the previous example's structure.
        // NOTE: The index name 'idx_vte_caprini_scores_pid_time' was mentioned in the first prompt but applied incorrectly to 'sofa_scores'.
        // Assuming this is the intended index for vte_caprini_scores:
        @Index(name = "idx_vte_caprini_scores_pid_time", columnList = "pid, score_time")
    }
)
public class VTECapriniScore {

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

    // --- 1 Point Risk Factors ---
    @Column(name = "age_41_60")
    private Boolean age4160;  // 年龄为41-60岁

    @Column(name = "lower_limb_edema")
    private Boolean lowerLimbEdema;  // 下肢水肿

    @Column(name = "varicose_veins")
    private Boolean varicoseVeins;  // 静脉曲张

    @Column(name = "obesity_bmi_25")
    private Boolean obesityBmi25;  // 肥胖(BMI≥25)

    @Column(name = "planned_minor_surgery")
    private Boolean plannedMinorSurgery;  // 计划小手术

    @Column(name = "inflammatory_history")
    private Boolean inflammatoryHistory;  // 炎症性病史

    @Column(name = "oral_contraceptives_hrt")
    private Boolean oralContraceptivesHrt;  // 口服避孕药或激素替代治疗

    @Column(name = "pregnancy_or_postpartum")
    private Boolean pregnancyOrPostpartum;  // 妊娠期或产后(1个月内)

    @Column(name = "acute_myocardial_infarction")
    private Boolean acuteMyocardialInfarction;  // 急性心肌梗塞

    @Column(name = "congestive_heart_failure")
    private Boolean congestiveHeartFailure;  // 充血性心力衰竭

    @Column(name = "bedridden_medical_patient")
    private Boolean bedriddenMedicalPatient;  // 卧床的内科患者

    @Column(name = "pulmonary_dysfunction")
    private Boolean pulmonaryDysfunction;  // 肺功能异常

    @Column(name = "major_surgery_history")
    private Boolean majorSurgeryHistory;  // 大手术史(1个月内)

    @Column(name = "sepsis")
    private Boolean sepsis;  // 脓毒症

    @Column(name = "severe_lung_disease_pneumonia")
    private Boolean severeLungDiseasePneumonia;  // 严重肺部疾病，含肺炎(1个月内)

    @Column(name = "unexplained_or_recurrent_miscarriage")
    private Boolean unexplainedOrRecurrentMiscarriage;  // 不明原因或习惯性流产

    @Column(name = "other_risk_factors", columnDefinition = "TEXT")
    private String otherRiskFactors;  // 其他风险因素（字符串输入）

    // --- 2 Point Risk Factors ---
    @Column(name = "age_61_74")
    private Boolean age6174;  // 年龄为61-74岁

    @Column(name = "central_venous_catheter")
    private Boolean centralVenousCatheter;  // 中心静脉置管

    @Column(name = "arthroscopic_surgery")
    private Boolean arthroscopicSurgery;  // 关节镜手术

    @Column(name = "major_surgery_over_45min")
    private Boolean majorSurgeryOver45min;  // 大手术(＞45分钟)

    @Column(name = "bed_rest_over_72h")
    private Boolean bedRestOver72h;  // 患者需要卧床(>72小时)

    @Column(name = "malignant_tumor")
    private Boolean malignantTumor;  // 恶性肿瘤(既往或现患)

    @Column(name = "laparoscopic_surgery_over_45min")
    private Boolean laparoscopicSurgeryOver45min;  // 腹腔镜手术(＞45分钟)

    @Column(name = "cast_immobilization")
    private Boolean castImmobilization;  // 石膏固定(1个月内)

    // --- 3 Point Risk Factors ---
    @Column(name = "age_75_or_older")
    private Boolean age75OrOlder;  // 年龄≥75岁

    @Column(name = "thrombosis_family_history")
    private Boolean thrombosisFamilyHistory;  // 血栓家族病史

    @Column(name = "dvt_pe_history")
    private Boolean dvtPeHistory;  // DVT/PE患者

    @Column(name = "prothrombin_20210a_positive")
    private Boolean prothrombin20210aPositive;  // 凝血酶原20210A阳性

    @Column(name = "factor_v_leiden_positive")
    private Boolean factorVLeidenPositive;  // 因子V Leiden阳性

    @Column(name = "lupus_anticoagulant_positive")
    private Boolean lupusAnticoagulantPositive;  // 狼疮抗凝物阳性

    @Column(name = "elevated_homocysteine")
    private Boolean elevatedHomocysteine;  // 血清同型半胱氨酸升高

    @Column(name = "antiphospholipid_antibodies")
    private Boolean antiphospholipidAntibodies;  // 抗心磷脂抗体升高

    @Column(name = "heparin_induced_thrombocytopenia")
    private Boolean heparinInducedThrombocytopenia;  // 肝素引起的血小板减少(HIT)

    @Column(name = "other_congenital_or_acquired_thrombosis", columnDefinition = "TEXT")
    private String otherCongenitalOrAcquiredThrombosis;  // 其他先天性或后天血栓疾病（字符串）

    // --- 5 Point Risk Factors ---
    @Column(name = "stroke_within_month")
    private Boolean strokeWithinMonth;  // 脑卒中(1个月内)

    @Column(name = "multiple_trauma_within_month")
    private Boolean multipleTraumaWithinMonth;  // 多发性创伤(1个月内)

    @Column(name = "acute_spinal_cord_injury")
    private Boolean acuteSpinalCordInjury;  // 急性骨髓损伤(瘫痪)(1个月内)

    @Column(name = "hip_pelvis_lower_limb_fracture")
    private Boolean hipPelvisLowerLimbFracture;  // 髋关节、骨盆或下肢骨折

    @Column(name = "elective_lower_limb_joint_replacement")
    private Boolean electiveLowerLimbJointReplacement;  // 选择性下肢关节置换术

    // --- Total Score ---
    @Column(name = "total_score", nullable = false)
    private Integer totalScore;  // Caprini 评分总分

    // --- Bleeding Risk - General ---
    @Column(name = "active_bleeding")
    private Boolean activeBleeding;  // 活动性出血

    @Column(name = "bleeding_event_within_3months")
    private Boolean bleedingEventWithin3months;  // 3个月内有出血事件

    @Column(name = "severe_renal_or_liver_failure")
    private Boolean severeRenalOrLiverFailure;  // 严重肾功能或肝功能衰竭

    @Column(name = "platelet_count_below_50")
    private Boolean plateletCountBelow50;  // 血小板计数<50*10^9/L

    @Column(name = "uncontrolled_hypertension")
    private Boolean uncontrolledHypertension;  // 未控制的高血压

    @Column(name = "lumbar_epidural_or_spinal_anesthesia")
    private Boolean lumbarEpiduralOrSpinalAnesthesia;  // 腰穿、硬膜外或椎管内麻醉术前4h-术后12h

    @Column(name = "anticoagulant_antiplatelet_or_thrombolytic")
    private Boolean anticoagulantAntiplateletOrThrombolytic;  // 同时使用抗凝药、抗血小板治疗或溶栓药物

    @Column(name = "coagulation_disorder")
    private Boolean coagulationDisorder;  // 凝血功能障碍

    @Column(name = "active_gi_ulcer")
    private Boolean activeGiUlcer;  // 活动性消化道溃疡

    @Column(name = "known_untreated_bleeding_disorder")
    private Boolean knownUntreatedBleedingDisorder;  // 已知、未治疗的出血疾病

    // --- Bleeding Risk - Surgery-Related ---
    @Column(name = "abdominal_surgery_malignant_male_anemia_complex")
    private Boolean abdominalSurgeryMalignantMaleAnemiaComplex;  // 腹部手术：恶性肿瘤男性患者，术前贫血，复杂手术

    @Column(name = "pancreaticoduodenectomy_sepsis_fistula_bleeding")
    private Boolean pancreaticoduodenectomySepsisFistulaBleeding;  // 胰十二指肠切除术：败血症、胰瘘、手术部位出血

    @Column(name = "liver_resection_pri_liver_cancer_low_hemoglobin_platelets")
    private Boolean liverResectionPriLiverCancerLowHemoglobinPlatelets;  // 肝切除术：原发性肝癌、术前血红蛋白和血小板计数低

    @Column(name = "cardiac_surgery_long_cp_time")
    private Boolean cardiacSurgeryLongCpTime;  // 心脏手术：体外循环时间较长

    @Column(name = "thoracic_surgery_pneumonectomy_or_extended")
    private Boolean thoracicSurgeryPneumonectomyOrExtended;  // 胸部手术：全肺切除术或扩张切除术

    // --- Bleeding Risk - High-Risk Surgery ---
    @Column(name = "craniotomy")
    private Boolean craniotomy;  // 开颅手术

    @Column(name = "spinal_surgery")
    private Boolean spinalSurgery;  // 脊柱手术

    @Column(name = "spinal_trauma")
    private Boolean spinalTrauma;  // 脊柱创伤

    @Column(name = "free_flap_reconstruction")
    private Boolean freeFlapReconstruction;  // 游离皮瓣重建手术

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
    private Boolean deepBreathingOrBalloon;  // 深呼吸或吹气球

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
    private String otherBasicMeasures;  // 其他护理措施（字符串）

    // --- Nursing Measures - Mechanical ---
    @Column(name = "intermittent_pneumatic_compression")
    private Boolean intermittentPneumaticCompression;  // 使用间歇性气压装置

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
    private String otherPharmacologicalMeasures;  // 其他药物措施（字符串）

    // --- Soft Delete & Audit Info ---
    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 疾病排除人

    @Column(name = "deleted_by_account_name")
    private String deletedByAccountName;  // 疾病排除人姓名

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 排除时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 记录人

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 记录时间
}