package com.jingyicare.jingyi_icis_engine.utils;

import java.time.LocalDateTime;

public class Consts {
    public static final Long VIRTUAL_RECORD_ID = -1L;

    // 数字常量
    public static final double EPS = 1e-6;
    public static final int MED_ML_DECIMAL_PLACES = 2; // 用药量 ml 保留两位小数

    // 字符串常量
    public static final String VOID_ROLE = "void-role"; // 无效角色

    // 时间常量
    public static final LocalDateTime MIN_TIME = TimeUtils.getLocalTime(1900, 1, 1);
    public static final LocalDateTime MAX_TIME = TimeUtils.getLocalTime(9999, 1, 1);
    public static final Integer DEFAULT_SHIFT_START_HOUR = 8;

    // 单位常量
    public static final String UNIT_MG = "mg";

    // 权限ID
    public static final String ADMIN_ROLE = "admin"; // 管理员角色名称
    public static final Integer PERM_ID_CONFIG_CHECKLIST = 8; // 配置组长质控组和质控项
    public static final Integer PERM_ID_CONFIG_ACCOUNT = 9; // 账号管理

    // ExtUrl常量，对应于icis_config.proto:Config.url.predefined_param_encoder.id
    public static final Integer EXT_URL_BASE64_ENCODE_ID = 2;
    public static final Integer EXT_URL_MD5_ENCODE_ID = 3;

    // 管道
    public static final String DRAINAGE_TUBE_COLOR_CODE = "color"; // 引流管颜色观察项编码

    // 报表
    public static final String REPORT_TEMPLATE_AH2 = "ah2"; // AH2 护理记录单
    public static final String REPORT_TEMPLATE_AH2_HALF_DAY_SUMMARY = "小计"; // AH2 护理记录单半日小计
    public static final String REPORT_TEMPLATE_AH2_FULL_DAY_SUMMARY = "总计"; // AH2 护理记录单全天总计

    // 组长质控代码
    public static final String ICU_1_ICU_BED_UTILIZATION_RATE = "icu_bed_utilization_rate";  // ICU 床位使用率
    public static final String ICU_2_ICU_DOCTOR_BED_RATIO = "icu_doctor_bed_ratio";  // ICU 医师床位比
    public static final String ICU_3_ICU_NURSE_BED_RATIO = "icu_nurse_bed_ratio";  // ICU 护士床位比
    public static final String ICU_4_APACHE2_OVER15_ADMISSION_RATE = "apache2_over15_admission_rate";  // APACHEⅡ评分 ≥ 15分患者收治率
    public static final String ICU_5_SEPTIC_SHOCK_BUNDLE_COMPLETION_RATE = "septic_shock_bundle_completion_rate";  // 感染性休克患者集束化治疗（bundle）完成率
    public static final String ICU_6_PRE_ANTIBIOTIC_PATHOGEN_TEST_RATE = "pre_antibiotic_pathogen_test_rate";  // 抗菌药物治疗前病原学送检率
    public static final String ICU_7_DVT_PREVENTION_RATE = "dvt_prevention_rate";  // 深静脉血栓（DVT）预防率
    public static final String ICU_8_ARDS_PRONE_POSITION_RATE = "ards_prone_position_rate";  // 中重度急性呼吸窘迫综合征（ARDS）患者俯卧位通气实施率
    public static final String ICU_9_PAIN_ASSESSMENT_RATE = "pain_assessment_rate";  // ICU 镇痛评估率
    public static final String ICU_10_SEDATION_ASSESSMENT_RATE = "sedation_assessment_rate";  // ICU 镇静评估率
    public static final String ICU_11_STANDARDIZED_MORTALITY_RATIO = "standardized_mortality_ratio";  // ICU 患者标化病死指数
    public static final String ICU_12_UNPLANNED_EXTUBATION_RATE = "unplanned_extubation_rate";  // ICU 非计划气管插管拔管率
    public static final String ICU_13_REINTUBATION_WITHIN_48H_RATE = "reintubation_within_48h_rate";  // ICU 气管插管拔管后 48h 再插管率
    public static final String ICU_14_UNPLANNED_ICU_ADMISSION_RATE = "unplanned_icu_admission_rate";  // 非计划转入 ICU 率
    public static final String ICU_15_ICU_READMISSION_WITHIN_48H_RATE = "icu_readmission_within_48h_rate";  // 转出 ICU 后 48h 内重返率
    public static final String ICU_16_VAP_INCIDENCE_RATE = "vap_incidence_rate";  // ICU 呼吸机相关肺炎（VAP）发病率
    public static final String ICU_17_CRBSI_INCIDENCE_RATE = "crbsi_incidence_rate";  // ICU 血管导管相关血流感染（CRBSI）发病率
    public static final String ICU_18_BRAIN_INJURY_CONSCIOUSNESS_ASSESSMENT_RATE = "brain_injury_consciousness_assessment_rate";  // ICU 急性脑损伤患者意识评估率
    public static final String ICU_19_ENTERAL_NUTRITION_WITHIN_48H_RATE = "enteral_nutrition_within_48h_rate";  // 48h 内肠内营养（EN）启动率
}