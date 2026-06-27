# 重症质控统计 Generic ICU QC 需求

## 背景

当前重症质控统计分散在以下位置：

- 后端计算：`src/main/java/com/jingyicare/jingyi_icis_engine/service/qcs/`
- 协议配置：`src/main/proto/config/icis_quality_control.proto`
- Web API：`src/main/proto/icis_web_api.proto` 的 `/api/nhcqc/getqcitems`、`/api/nhcqc/getqcdata`
- 前端页面：`jingyi_icis_frontend/src/pages/home/tabs/statistics/QualityControlStatistics/`
- 质控配置：`src/main/resources/config/pbtxt/icis_qc_config.pb.txt`

现有接口按“先取质控项列表，再按单个 `item_code` 取月度数据”的模式工作。返回结构 `QcMonthDataPB` 同时包含数值、单位、分子表格、分母表格、表头和行数据，导致后端承担了表格列定义、行文本、日期格式化等前端展示职责。前端为了展示所有指标，又需要循环调用同一个接口，接口交互和数据结构都偏冗余。

参考手麻 Generic AQI 的方式，本需求规划重症质控统计改为“一个通用查询接口返回所有指标的结构化结果”，每个指标有独立配置、月度项、总计项和结构化明细；前端负责表格列、格式化、筛选、CSV 导出和图表展示。

本文档只整理需求，不做 Java、proto、pbtxt 或前端实现。

## 范围

本期重写范围限定为当前已经实现的 10 个重症质控项：

1. `icu_bed_utilization_rate` ICU 床位使用率
2. `icu_doctor_bed_ratio` ICU 医师床位比
3. `icu_nurse_bed_ratio` ICU 护士床位比
4. `apache2_over15_admission_rate` APACHEII 评分 >= 15 分患者收治率
5. `dvt_prevention_rate` 深静脉血栓（DVT）预防率
6. `pain_assessment_rate` ICU 镇痛评估率
7. `sedation_assessment_rate` ICU 镇静评估率
8. `standardized_mortality_ratio` ICU 患者标化病死指数
9. `unplanned_icu_admission_rate` 非计划转入 ICU 率
10. `icu_readmission_within_48h_rate` 转出 ICU 后 48h 内重返率

以下 9 个已配置但当前未实现的指标仅预留 slot，不要求本期计算：

1. `septic_shock_bundle_completion_rate`
2. `pre_antibiotic_pathogen_test_rate`
3. `ards_prone_position_rate`
4. `unplanned_extubation_rate`
5. `reintubation_within_48h_rate`
6. `vap_incidence_rate`
7. `crbsi_incidence_rate`
8. `brain_injury_consciousness_assessment_rate`
9. `enteral_nutrition_within_48h_rate`

## 目标

建设一个通用重症质控统计接口和前端页面：

1. 前端一次请求指定科室和时间范围，后端返回所有已配置指标的月度数据和总计数据。
2. 所有指标响应都包含 `id`、`month_item`、`total_item`，其中 `id` 来自配置，`month_item` 按本地自然月切分，`total_item` 按请求整体时间段计算。
3. 后端返回结构化明细，不返回前端表格头、表格行 map、展示用日期文本或分子/分母表格。
4. 前端根据指标 code 本地定义列、格式化、明细筛选、图表和 CSV 导出。
5. 未实现指标也返回配置 `id` 和空数据，前端可显示为“未实现/待扩展”，不需要额外接口获取菜单。
6. 新接口上线后，旧的 `/api/nhcqc/getqcitems`、`/api/nhcqc/getqcdata` 只作为迁移兼容，新的统计页面不再依赖它们。

## 非目标

1. 本期不补齐 9 个未实现 slot 的计算逻辑。
2. 本期不新建感染、呼吸机、导管、抗菌药、营养等复杂数据源。
3. 本期不要求修改国家质控指标口径，仅在文档中标出当前实现与公式不一致或不确定的地方。
4. 本期不做实际代码重构、接口删除或前端改造。

## 当前问题

1. `GetNHCQCItemsResp` 只返回 `StrKeyValPB`，前端还要用 `item_code` 再请求明细，所有指标查询会造成 N 次调用。
2. `GetNHCQCDataResp.month_data` 是通用表格结构，后端把列名、行 key、日期格式化、保留小数等展示逻辑写死在计算器中。
3. 前端图表统一按 `numerator / denominator * 100` 展示百分比，不适合医师床位比、护士床位比、标化病死指数、VAP/CRBSI 千分率等指标。
4. 患者类指标当前构造了患者明细，但 `PatientRatioCalc.buildQcPatientStats` 没有把 `qcPatientList` 加入响应，导致分子/分母表格实际为空。
5. 患者类指标共用 `QcPatientInfoPB`，不同指标把 `numerator_notes`、`numerator_value`、`predicted_mortality_rate` 等字段复用为不同语义，前端难以可靠解释。
6. 时间范围缺少统一半开区间约定。现有前端传月末 `23:59:59`，部分后端逻辑又截断到日，容易出现月末日是否计入的不一致。

## 通用约定

### 时间

1. 请求和响应时间均使用 ISO8601 字符串。
2. 后端按 `ConfigPB.zone_id` 解释本地自然日和自然月边界。
3. 后端内部统计区间建议统一为半开区间 `[stats_start_utc, stats_end_utc)`。
4. 月度切分按本地自然月与请求区间取交集。例如请求 `2025-03-20 00:00:00` 到 `2025-05-20 00:00:00`，月度项为：
   - `2025-03-20 00:00:00` 到 `2025-04-01 00:00:00`
   - `2025-04-01 00:00:00` 到 `2025-05-01 00:00:00`
   - `2025-05-01 00:00:00` 到 `2025-05-20 00:00:00`
5. 前端选择月份时，提交本地开始月第一天 `00:00:00` 和结束月后一月第一天 `00:00:00`，避免月末 `23:59:59` 与后端截断混用。

### 科室

1. ICIS 现有 `dept_id` 为字符串，新增协议继续使用 `string dept_id`。
2. 单科室查询时，`dept_id` 必填且必须存在。
3. 支持全院/多科室统计，约定 `dept_id = "0"` 表示所有允许统计的 ICU 科室；允许统计的科室由配置控制。

### 数值

1. 每个统计项都返回 `numerator`、`denominator`、`ratio`。
2. `numerator`、`denominator` 保存公式直接使用的原始分子和分母。
3. `ratio` 由后端计算，前端不得再用 `numerator / denominator` 自行推导业务结果。
4. `denominator = 0` 时，`ratio = 0`，前端展示为 `-`。
5. 指标配置需要声明展示类型，前端据此格式化：
   - `ICU_QC_VALUE_KIND_PERCENT`：百分比指标，展示 `ratio * 100%`
   - `ICU_QC_VALUE_KIND_RATIO`：普通比值，展示 `ratio`
   - `ICU_QC_VALUE_KIND_INDEX`：指数，展示 `ratio`
   - `ICU_QC_VALUE_KIND_PER_MILLE`：千分率，展示 `ratio * 1000‰`
   - `ICU_QC_VALUE_KIND_BED_DAY`：床日类汇总，图表仍按 `ratio` 展示，明细展示床日数

### 明细

1. 后端返回业务字段，不返回 `QcHeaderPB`、`QcRowPB` 或 map 型表格。
2. 分子和分母不再拆成两张后端表格；每条明细用布尔字段标记是否属于分子、分母，前端按需要筛选。
3. 对于可能“分母按患者去重、明细按出入科记录展开”的指标，需要保留 `is_in_numerator`、`shown_in_numerator`、`is_in_denominator` 三个语义：
   - `is_in_numerator`：该明细实际贡献分子计数。
   - `shown_in_numerator`：该明细需要出现在分子详情中，但不一定重复贡献分子计数。
   - `is_in_denominator`：该明细实际贡献分母计数。

## Proto 规划

建议在 `src/main/proto/config/icis_quality_control.proto` 中把现有 `Qc*` 表格协议迁移为结构化协议。旧消息可保留到迁移完成后再删除。

### 通用消息

```proto
enum IcuQcValueKindPB {
    ICU_QC_VALUE_KIND_UNSPECIFIED = 0;
    ICU_QC_VALUE_KIND_PERCENT = 1;
    ICU_QC_VALUE_KIND_RATIO = 2;
    ICU_QC_VALUE_KIND_INDEX = 3;
    ICU_QC_VALUE_KIND_PER_MILLE = 4;
    ICU_QC_VALUE_KIND_BED_DAY = 5;
}

message IcuQcIdPB {
    string code = 1;          // 现有稳定 code，如 icu_bed_utilization_rate
    string seq_code = 2;      // 展示序号，如 ICU-QC-01
    string name = 3;
    string description = 4;
    string calc_formula = 5;
    bool implemented = 6;
    IcuQcValueKindPB value_kind = 7;
    string display_unit = 8;  // %, 空字符串, 指数, ‰ 等
}

message IcuQcMetricItemPB {
    string stats_start_iso8601 = 1;
    string stats_end_iso8601 = 2;
    double numerator = 3;
    double denominator = 4;
    double ratio = 5;
}

message IcuQcPatientPB {
    int64 patient_id = 1;
    string his_mrn = 2;
    string patient_name = 3;
    string admission_time_iso8601 = 4;
    string discharge_time_iso8601 = 5;
    int32 admission_status = 6;
    string admission_source_dept_name = 7;
    string admission_types = 8;
    bool is_dead = 9;
    string death_time_iso8601 = 10;
}

message IcuQcPatientMetricDetailPB {
    IcuQcPatientPB patient = 1;
    bool is_in_numerator = 2;
    bool shown_in_numerator = 3;
    bool is_in_denominator = 4;
    string numerator_time_iso8601 = 5;
    string denominator_time_iso8601 = 6;
    string evidence_type = 7;       // apache2, vte_caprini, vte_padua, pain score group code 等
    double evidence_value = 8;      // 分数、预计病死率等数值证据
    string evidence_text = 9;       // 非结构化补充说明
    int32 apache2_score = 10;
    double predicted_mortality_rate = 11;
    string readmission_time_iso8601 = 12;
}

message IcuQcPatientMetricItemPB {
    string stats_start_iso8601 = 1;
    string stats_end_iso8601 = 2;
    repeated IcuQcPatientMetricDetailPB detail = 3;
    double numerator = 4;
    double denominator = 5;
    double ratio = 6;
}

message IcuQcGenericPB {
    IcuQcIdPB id = 1;
    repeated IcuQcMetricItemPB month_item = 2;
    IcuQcMetricItemPB total_item = 3;
    string slot_note = 4;
}
```

### 床位使用率

```proto
message IcuQcBedUsagePB {
    string display_bed_number = 1;
    int64 patient_id = 2;
    string patient_name = 3;
    string start_time_iso8601 = 4;
    string end_time_iso8601 = 5;
    double bed_days = 6;
    string exception = 7;
}

message IcuQcBedAvailablePB {
    string start_time_iso8601 = 1;
    string end_time_iso8601 = 2;
    int32 bed_count = 3;
    double bed_days = 4;
}

message IcuQcBedUtilizationItemPB {
    string stats_start_iso8601 = 1;
    string stats_end_iso8601 = 2;
    repeated IcuQcBedUsagePB usage = 3;
    repeated IcuQcBedAvailablePB available = 4;
    double numerator = 5;    // ICU 实际占用总床日数
    double denominator = 6;  // 同期 ICU 实际开放总床日数
    double ratio = 7;
}

message IcuQcBedUtilizationPB {
    IcuQcIdPB id = 1;
    repeated IcuQcBedUtilizationItemPB month_item = 2;
    IcuQcBedUtilizationItemPB total_item = 3;
}
```

### 医师/护士床位比

```proto
message IcuQcStaffRoleItemPB {
    int64 employee_id = 1;
    string account_id = 2;
    string account_name = 3;
    string dept_id = 4;
    string primary_role_name = 5;
    string start_time_iso8601 = 6;
    string end_time_iso8601 = 7;
    double active_days = 8;
}

message IcuQcStaffBedRatioItemPB {
    string stats_start_iso8601 = 1;
    string stats_end_iso8601 = 2;
    repeated IcuQcStaffRoleItemPB staff = 3;
    int32 bed_count = 4;
    double numerator = 5;    // 医师/护士人数
    double denominator = 6;  // ICU 实际开放床位数
    double ratio = 7;
}

message IcuQcStaffBedRatioPB {
    IcuQcIdPB id = 1;
    repeated IcuQcStaffBedRatioItemPB month_item = 2;
    IcuQcStaffBedRatioItemPB total_item = 3;
}
```

### 患者类已实现指标

以下指标使用 `IcuQcPatientMetricItemPB` 作为月度项和总计项：

```proto
message IcuQcApache2Over15AdmissionRatePB {
    IcuQcIdPB id = 1;
    repeated IcuQcPatientMetricItemPB month_item = 2;
    IcuQcPatientMetricItemPB total_item = 3;
}

message IcuQcDvtPreventionRatePB {
    IcuQcIdPB id = 1;
    repeated IcuQcPatientMetricItemPB month_item = 2;
    IcuQcPatientMetricItemPB total_item = 3;
}

message IcuQcPainAssessmentRatePB {
    IcuQcIdPB id = 1;
    repeated IcuQcPatientMetricItemPB month_item = 2;
    IcuQcPatientMetricItemPB total_item = 3;
}

message IcuQcSedationAssessmentRatePB {
    IcuQcIdPB id = 1;
    repeated IcuQcPatientMetricItemPB month_item = 2;
    IcuQcPatientMetricItemPB total_item = 3;
}

message IcuQcStandardizedMortalityRatioPB {
    IcuQcIdPB id = 1;
    repeated IcuQcPatientMetricItemPB month_item = 2;
    IcuQcPatientMetricItemPB total_item = 3;
}

message IcuQcUnplannedIcuAdmissionRatePB {
    IcuQcIdPB id = 1;
    repeated IcuQcPatientMetricItemPB month_item = 2;
    IcuQcPatientMetricItemPB total_item = 3;
}

message IcuQcReadmissionWithin48hRatePB {
    IcuQcIdPB id = 1;
    repeated IcuQcPatientMetricItemPB month_item = 2;
    IcuQcPatientMetricItemPB total_item = 3;
}
```

### 配置

配置根消息为 `IcuQcConfigPB`，默认配置文件为 `src/main/resources/config/pbtxt/icis_qc_config.pb.txt`。运行时通过 `application.properties` 中的 `jingyi.textresources.icis_qc_config` 指定默认配置路径。

启动时先读取默认文件，再读取 `SystemSettings(function_id = GET_IQC_CONFIG)`；DB 有值时整体覆盖默认文件。保存后写回 DB，并刷新内存中的 `IcuQcConfigService`。

```proto
message IcuQcConfigPB {
    repeated QcItemPB item = 1;
    repeated int32 doctor_role_id = 2;
    repeated int32 nurse_role_id = 3;
    repeated string pain_score_group_code = 4;
    repeated string sedation_score_group_code = 5;
    repeated string stats_dept_id = 6;

    IcuQcBedUtilizationConfigPB bed_utilization = 11;
    IcuQcStaffBedRatioConfigPB doctor_bed_ratio = 12;
    IcuQcStaffBedRatioConfigPB nurse_bed_ratio = 13;
    IcuQcApache2Over15AdmissionRateConfigPB apache2_over15_admission_rate = 14;
    SepsisSepticShockDiagnosisConfigPB sepsis_septic_shock_diagnosis = 30;
    // 其余指标均保留独立配置字段，未实现指标先允许空配置。
}

message IcuQcApache2Over15AdmissionRateConfigPB {
    bool count_by_admission_time = 1;
}

message SepsisSepticShockDiagnosisConfigPB {
    int32 age_lower_bound = 1;
    int32 grace_period_hours_from_admission_time = 2;
    repeated string diagnosis_keyword = 3;
}
```

`count_by_admission_time = true` 表示 APACHEII >= 15 收治率按入科时间归属统计期；`false` 表示按入 ICU 后首次有效 APACHEII 评分时间归属统计期。

`SepsisSepticShockDiagnosisConfigPB` 默认值为：`age_lower_bound = 18`、`grace_period_hours_from_admission_time = 24`、`diagnosis_keyword = ["脓毒症", "感染性休克"]`。`patient_records.diagnosis` 不做时间限制；`patient_diagnoses.diagnosis` 按 `patient_records.admission_time + grace_period_hours_from_admission_time` 限制。

### Web API

建议新增接口：

```proto
/*
 * /api/nhcqc/getgenericicuqc
 * input: GetGenericIcuQcReq
 * output: GetGenericIcuQcResp
 */
message GetGenericIcuQcReq {
    string dept_id = 1;
    string query_start_iso8601 = 2;
    string query_end_iso8601 = 3;
    repeated string item_code = 4;  // 为空表示返回所有配置项；前端默认传空
}

message GetGenericIcuQcResp {
    shared.ReturnCode rt = 1;

    config.IcuQcBedUtilizationPB bed_utilization = 2;
    config.IcuQcStaffBedRatioPB doctor_bed_ratio = 3;
    config.IcuQcStaffBedRatioPB nurse_bed_ratio = 4;
    config.IcuQcApache2Over15AdmissionRatePB apache2_over15_admission_rate = 5;
    config.IcuQcGenericPB septic_shock_bundle_completion_rate = 6;
    config.IcuQcGenericPB pre_antibiotic_pathogen_test_rate = 7;
    config.IcuQcDvtPreventionRatePB dvt_prevention_rate = 8;
    config.IcuQcGenericPB ards_prone_position_rate = 9;
    config.IcuQcPainAssessmentRatePB pain_assessment_rate = 10;
    config.IcuQcSedationAssessmentRatePB sedation_assessment_rate = 11;
    config.IcuQcStandardizedMortalityRatioPB standardized_mortality_ratio = 12;
    config.IcuQcGenericPB unplanned_extubation_rate = 13;
    config.IcuQcGenericPB reintubation_within_48h_rate = 14;
    config.IcuQcUnplannedIcuAdmissionRatePB unplanned_icu_admission_rate = 15;
    config.IcuQcReadmissionWithin48hRatePB icu_readmission_within_48h_rate = 16;
    config.IcuQcGenericPB vap_incidence_rate = 17;
    config.IcuQcGenericPB crbsi_incidence_rate = 18;
    config.IcuQcGenericPB brain_injury_consciousness_assessment_rate = 19;
    config.IcuQcGenericPB enteral_nutrition_within_48h_rate = 20;
}
```

设置接口独立于现有 `getAppSettings/updateAppSettings`：

```proto
// /api/settings/getiqcconfig
message GetIqcConfigReq {}
message GetIqcConfigResp {
    shared.ReturnCode rt = 1;
    config.IcuQcConfigPB iqc_config = 2;
}

// /api/settings/updateiqcconfig
message UpdateIqcConfigReq {
    config.IcuQcConfigPB iqc_config = 1;
}
```

## 指标口径

### ICU-QC-01 ICU 床位使用率

- code：`icu_bed_utilization_rate`
- 状态：已实现，需要结构化重写。
- 展示类型：`PERCENT`
- 分子：统计期内 ICU 实际占用总床日数。
- 分母：统计期内 ICU 实际开放总床日数。
- 当前数据源：
  - `patient_records`：患者入科、出科、当前 HIS 床号。
  - `patient_bed_histories`：患者床位切换历史。
  - `bed_configs`：HIS 床号到显示床号映射。
  - `bed_counts`：科室开放床位数生效时间线。
- 明细：
  - `usage` 返回患者、显示床号、使用起止时间、床日数、异常说明。
  - `available` 返回开放床位数生效区间、床位数和床日数。
- 口径要求：
  - 开放床日按床位数时间线与统计区间交集计算。
  - 占用床日按患者床位使用区间与统计区间交集计算。
  - 同一床同一自然日被多个患者使用时，默认按 `1 / 当日使用人数` 分摊床日；配置中预留开关，支持“每个患者均计 1 床日”的院内口径。

### ICU-QC-02 ICU 医师床位比

- code：`icu_doctor_bed_ratio`
- 状态：已实现，需要结构化重写。
- 展示类型：`RATIO`
- 分子：统计时间点 ICU 医师人数。
- 分母：统计时间点 ICU 实际开放床位数。
- 当前数据源：
  - `department_accounts`：账号-科室-主角色关系和有效期。
  - `accounts`：账号姓名。
  - `rbac_roles`：角色名称。
  - `bed_counts`：床位数时间线。
  - `quality_control.doctor_role_id`：医生角色 ID 配置。
- 明细：返回每个计入医师的工号、姓名、主角色、有效起止时间、科室。
- 口径要求：
  - 月度项按该月统计结束时刻的有效人员和床位数计算。
  - 总计项按请求整体结束时刻计算。
  - 已软删除但统计期内有效的账号-科室关系应参与历史统计，不能只查当前未删除关系。

### ICU-QC-03 ICU 护士床位比

- code：`icu_nurse_bed_ratio`
- 状态：已实现，需要结构化重写。
- 展示类型：`RATIO`
- 分子：统计时间点 ICU 护士人数。
- 分母：统计时间点 ICU 实际开放床位数。
- 当前数据源同 ICU-QC-02，角色配置改用 `quality_control.nurse_role_id`。
- 明细同 ICU-QC-02。
- 口径要求同 ICU-QC-02。

### ICU-QC-04 APACHEII 评分 >= 15 分患者收治率

- code：`apache2_over15_admission_rate`
- 状态：已实现，需要结构化重写。
- 展示类型：`PERCENT`
- 分子：由配置决定归属统计期的 ICU 患者中，入 ICU 后首次有效 APACHEII 评分 >= 15 分的人数。
- 分母：由配置决定归属统计期的 ICU 患者总数。
- 当前数据源：
  - `patient_records`：ICU 患者住院区间。
  - `apache_ii_scores`：APACHEII 评分。
- 明细：
  - 每条患者明细返回患者基础信息、是否分子、是否分母、APACHEII 分值、评分时间。
- 口径要求：
  - `apache2_over15_admission_rate.count_by_admission_time = true` 时，按入科时间落在统计期 `[start, end)` 内的患者记录计数，当前默认配置采用该口径。
  - `apache2_over15_admission_rate.count_by_admission_time = false` 时，按首次有效 APACHEII 评分时间落在统计期 `[start, end)` 内的患者记录计数。
  - 首次有效 APACHEII 评分必须满足：`score_time` 非空、`apache_ii_score` 非空、`score_time >= admission_time`，且未出科患者不限制出科时间，已出科患者要求 `score_time < discharge_time`。
  - 如果医院历史报表已经按最高分使用，需要在配置中显式声明口径。

### ICU-QC-05 感染性休克患者 bundle 完成率

- code：`septic_shock_bundle_completion_rate`
- 状态：slot，未实现。
- 展示类型：`PERCENT`
- 分子：入 ICU 诊断为感染性休克并完成 bundle 的患者人数。
- 分母：同期入 ICU 诊断为感染性休克的患者总人数。
- 预留：`IcuQcGenericPB septic_shock_bundle_completion_rate`。
- 待补数据源：诊断、感染性休克识别规则、bundle 完成记录或结构化表单。

### ICU-QC-06 抗菌药物治疗前病原学送检率

- code：`pre_antibiotic_pathogen_test_rate`
- 状态：slot，未实现。
- 展示类型：`PERCENT`
- 分子：以治疗为目的使用抗菌药物前完成病原学送检的 ICU 患者人数。
- 分母：同期使用抗菌药物治疗的 ICU 患者总人数。
- 预留：`IcuQcGenericPB pre_antibiotic_pathogen_test_rate`。
- 待补数据源：抗菌药物医嘱/执行、治疗目的识别、病原学检验申请和采样时间。

### ICU-QC-07 深静脉血栓（DVT）预防率

- code：`dvt_prevention_rate`
- 状态：已实现，需要结构化重写。
- 展示类型：`PERCENT`
- 分子：统计期内执行 DVT 预防措施的 ICU 患者人数。
- 分母：同期 ICU 患者总人数。
- 当前数据源：
  - `patient_records`：统计期在 ICU 患者。
  - `vte_caprini_scores`、`vte_padua_scores`：DVT 风险评估及预防措施执行字段。
- 明细：
  - 患者基础信息。
  - `evidence_type`：`vte_caprini` 或 `vte_padua`。
  - `numerator_time_iso8601`：预防措施对应评分时间。
  - `is_in_numerator`、`is_in_denominator`。
- 口径要求：
  - 分母为统计期内与 ICU 在科区间有交集的患者。
  - 分子为统计期内任一 Caprini/Padua 记录存在抗凝、物理或抗凝+物理预防执行的患者。

### ICU-QC-08 中重度 ARDS 患者俯卧位通气实施率

- code：`ards_prone_position_rate`
- 状态：slot，未实现。
- 展示类型：`PERCENT`
- 分子：ICU 中重度 ARDS 患者中实施俯卧位通气治疗的人数。
- 分母：同期 ICU 应实施俯卧位通气治疗的中重度 ARDS 患者总人数。
- 预留：`IcuQcGenericPB ards_prone_position_rate`。
- 待补数据源：ARDS 诊断/严重程度、通气方式、俯卧位治疗记录。

### ICU-QC-09 ICU 镇痛评估率

- code：`pain_assessment_rate`
- 状态：已实现，需要结构化重写。
- 展示类型：`PERCENT`
- 分子：统计期内完成镇痛评估的 ICU 患者人数。
- 分母：同期 ICU 患者总人数。
- 当前数据源：
  - `patient_records`：统计期在 ICU 患者。
  - `patient_scores`：护理评分。
  - `quality_control.pain_score_group_code`：镇痛评分分组配置，当前包含 `cpot`、`bps`、`nrs`、`pain_assessment`。
- 明细：
  - 患者基础信息。
  - `evidence_type`：评分分组 code。
  - `numerator_time_iso8601`：评分生效时间。
- 口径要求：
  - 分母应为统计期内与 ICU 在科区间有交集的患者。
  - 分子为统计期内存在任一镇痛评分记录的患者。
  - 当前实现会漏掉没有任何镇痛评分记录的患者分母，需要在重写时修正。

### ICU-QC-10 ICU 镇静评估率

- code：`sedation_assessment_rate`
- 状态：已实现，需要结构化重写。
- 展示类型：`PERCENT`
- 分子：统计期内完成镇静评估的 ICU 患者人数。
- 分母：同期 ICU 患者总人数。
- 当前数据源：
  - `patient_records`：统计期在 ICU 患者。
  - `patient_scores`：护理评分。
  - `quality_control.sedation_score_group_code`：镇静评分分组配置，当前包含 `rass`、`sas_sedation_agitation_assessment`。
- 明细和口径同 ICU-QC-09。
- 当前实现同样会漏掉没有任何镇静评分记录的患者分母，需要在重写时修正。

### ICU-QC-11 ICU 患者标化病死指数

- code：`standardized_mortality_ratio`
- 状态：已实现，需要结构化重写。
- 展示类型：`INDEX`
- 分子：统计期 ICU 患者实际病死率。
- 分母：同期 ICU 患者预计病死率。
- 当前数据源：
  - `patient_records`：在 ICU 患者、出科类型、死亡时间。
  - `apache_ii_scores`：预计病死率。
  - `patient.enums_v2.discharge_type` 配置：死亡出科类型。
- 明细：
  - 患者基础信息、是否死亡、死亡时间。
  - APACHEII 预计病死率、评分时间。
- 口径要求：
  - 实际病死率 = 死亡患者数 / 同期 ICU 患者数。
  - 预计病死率 = 有有效预计病死率患者的预计病死率均值。
  - 标化病死指数 = 实际病死率 / 预计病死率。
  - 预计病死率字段需要统一为 0-1 小数；如果历史数据为百分数，需要迁移或计算时标准化。

### ICU-QC-12 ICU 非计划气管插管拔管率

- code：`unplanned_extubation_rate`
- 状态：slot，未实现。
- 展示类型：`PERCENT`
- 分子：ICU 患者非计划气管插管拔管例数。
- 分母：同期 ICU 患者气管插管拔管总例数。
- 预留：`IcuQcGenericPB unplanned_extubation_rate`。
- 待补数据源：气管插管/拔管事件、计划/非计划标记。

### ICU-QC-13 ICU 气管插管拔管后 48h 再插管率

- code：`reintubation_within_48h_rate`
- 状态：slot，未实现。
- 展示类型：`PERCENT`
- 分子：ICU 患者气管插管拔管后 48h 内再插管例数。
- 分母：同期 ICU 患者气管插管拔管总例数。
- 预留：`IcuQcGenericPB reintubation_within_48h_rate`。
- 待补数据源：拔管事件、再插管事件、事件时间关联规则。

### ICU-QC-14 非计划转入 ICU 率

- code：`unplanned_icu_admission_rate`
- 状态：已实现，需要结构化重写。
- 展示类型：`PERCENT`
- 分子：统计期非计划转入 ICU 的手术患者人数。
- 分母：同期转入 ICU 的手术患者总人数。
- 当前数据源：
  - `patient_records`：入科时间、入科类型、入科来源科室、是否计划入科。
  - `PatientConfig.admission_type_surgery_id`：手术入科类型。
- 明细：
  - 患者基础信息。
  - 是否计划入科。
  - 手术患者识别证据：入科类型为手术，或入科来源科室名称包含手术室/复苏室/麻醉恢复室/PACU。
- 口径要求：
  - 分母按入科时间落在统计期内、且识别为手术相关转入 ICU 的患者计数。
  - 分子为分母中 `is_planned_admission = false` 的患者。
  - 当前通过科室名称关键字识别手术来源，建议改为配置化关键字或来源科室 code。

### ICU-QC-15 转出 ICU 后 48h 内重返率

- code：`icu_readmission_within_48h_rate`
- 状态：已实现，需要结构化重写。
- 展示类型：`PERCENT`
- 分子：统计期转出 ICU 后 48h 内重返 ICU 的患者人数。
- 分母：同期转出 ICU 的患者总人数。
- 当前数据源：
  - `patient_records`：同一 HIS MRN 的多次入科/出科记录。
  - `PatientConfig.discharged_id`：已出科状态。
- 明细：
  - 每次出科记录的患者基础信息、出科时间。
  - 若 48h 内再入科，返回 `readmission_time_iso8601`。
  - 同一患者同月多次 48h 内重返时，第一条贡献分子，其余可 `shown_in_numerator = true` 用于详情展示。
- 口径要求：
  - 以 `his_mrn` 识别同一患者。
  - 分母按患者在统计期内有转出 ICU 记录去重计数。
  - 分子按患者在转出后 48h 内有下一次 ICU 入科记录去重计数。

### ICU-QC-16 ICU 呼吸机相关肺炎（VAP）发病率

- code：`vap_incidence_rate`
- 状态：slot，未实现。
- 展示类型：`PER_MILLE`
- 分子：ICU 呼吸机相关肺炎新发病例例次数。
- 分母：同期 ICU 患者有创呼吸机累计使用天数。
- 预留：`IcuQcGenericPB vap_incidence_rate`。
- 待补数据源：VAP 新发病例、呼吸机使用起止时间、有创/无创区分。

### ICU-QC-17 ICU 血管导管相关血流感染（CRBSI）发病率

- code：`crbsi_incidence_rate`
- 状态：slot，未实现。
- 展示类型：`PER_MILLE`
- 分子：ICU 血管导管相关血流感染新发病例例次数。
- 分母：同期 ICU 患者血管导管累计使用天数。
- 预留：`IcuQcGenericPB crbsi_incidence_rate`。
- 待补数据源：CRBSI 新发病例、血管导管留置起止时间。

### ICU-QC-18 ICU 急性脑损伤患者意识评估率

- code：`brain_injury_consciousness_assessment_rate`
- 状态：slot，未实现。
- 展示类型：`PERCENT`
- 分子：ICU 内完成意识评估的急性脑损伤患者人数。
- 分母：同期 ICU 急性脑损伤患者总人数。
- 预留：`IcuQcGenericPB brain_injury_consciousness_assessment_rate`。
- 待补数据源：急性脑损伤诊断、意识评估评分或结构化记录。

### ICU-QC-19 48h 内肠内营养（EN）启动率

- code：`enteral_nutrition_within_48h_rate`
- 状态：slot，未实现。
- 展示类型：`PERCENT`
- 分子：入住 ICU 超过 48h 的患者中 48h 内启动 EN 的患者人数。
- 分母：同期入住 ICU 超过 48h 的患者总人数。
- 预留：`IcuQcGenericPB enteral_nutrition_within_48h_rate`。
- 待补数据源：入科时间、肠内营养医嘱/执行、EN 识别规则。

## 后端需求

1. 新增通用服务入口，例如 `GenericIcuQcService.getGenericIcuQc`。
2. 服务入口负责：
   - 解析请求。
   - 校验时间范围。
   - 解析科室范围。
   - 读取 `IcuQcConfigPB`。
   - 按本地自然月切分统计段。
   - 调用各指标 calculator。
   - 对未实现 slot 返回 `IcuQcGenericPB`，包含 `id`、空 `month_item`、空 `total_item`、`slot_note`。
3. 每个已实现指标使用独立 calculator，calculator 只负责数据查询、业务口径和结构化 PB 组装。
4. calculator 不允许构造前端表格头、表格行 map、展示用日期、前端文案。
5. 患者数据、床位数据、人员数据查询应尽量在每个统计请求内批量完成，避免按月重复查库。
6. 旧 `QualityControlService` 可保留适配旧接口；新页面不再使用旧接口。

## 前端需求

1. 统计页面加载时不再调用 `getqcitems`。
2. 点击查询时调用一次 `/api/nhcqc/getgenericicuqc`。
3. 页面展示：
   - 顶部筛选：科室、月份范围、查询按钮。
   - 总述表格：每行一个指标，每列一个月份，另有总计、分子/分母、状态。
   - 明细区域：选择指标和月份/总计后展示结构化明细。
   - 图表：使用后端 `ratio`，结合 `value_kind` 展示百分比、比值、指数或千分率。
4. 前端本地维护 `METRIC_DEFINITIONS`，把响应字段、code、默认名称、明细列定义关联起来。
5. 未实现 slot 显示为禁用或“待实现”，不展示空图表误导用户。
6. CSV 导出由前端基于当前总述表格或当前明细表格生成。
7. 前端日期选择仍可使用月份范围，但提交给后端时应使用半开区间。
8. 基础功能配置页在“医嘱配置”和“归档配置”之间增加“质控配置”。
9. “质控配置”只允许编辑安全配置字段：`stats_dept_id`、医师/护士角色 ID、镇痛/镇静评分分组 code、床日分摊模式、APACHEII 统计归属、脓毒症/感染性休克诊断配置。
10. 19 个质控项的 code、名称、公式、描述只展示不编辑。

## 迁移要求

1. 第一阶段仅新增文档和新协议，不删除旧协议。
2. 第二阶段实现新后端接口，旧接口继续可用。
3. 第三阶段前端页面切到新接口，删除 `QUALITY_CONTROL_GET_ALL_DATA` 下循环请求旧接口的逻辑。
4. 第四阶段确认没有调用方后，再考虑删除旧 `QcTablePB`、`QcRowPB`、`QcMonthDataPB` 和旧接口。

## 验收标准

1. 新需求对应的协议能表达 19 个配置指标，其中 10 个已实现指标有结构化明细，9 个未实现指标有 slot。
2. 新接口一次请求即可返回所有指标，不需要前端按 `item_code` 循环调用。
3. 后端响应不包含前端表格头、表格行 map 或展示格式化文本。
4. 前端可以仅依赖结构化字段渲染总述表格、图表、指标明细和 CSV。
5. 患者类指标的明细必须随响应返回，不再出现汇总有数值但明细为空的问题。
6. 百分比、普通比值、指数、千分率指标能按各自 `value_kind` 正确展示。
7. 未实现指标不会触发计算错误，前端能明确展示为待实现。

## 已决策

1. 时间区间统一为半开区间 `[start, end)`。
   - 前端月份范围提交结束月后一月第一天 `00:00:00`。
   - 后端所有计算不再接收月末 `23:59:59` 作为业务边界。

2. `dept_id = "0"` 表示所有允许统计的 ICU 科室。
   - 需要增加可统计科室配置。
   - 单科室仍使用现有字符串 `dept_id`。

3. ICU-QC-04 APACHEII 评分使用入 ICU 后首次有效 APACHEII 评分，统计期归属由 `apache2_over15_admission_rate.count_by_admission_time` 控制。
   - 当前默认配置为 `true`，即恢复到按入科时间只计入入科所在统计段。
   - 如需按首次有效评分时间归属统计期，将该配置改为 `false`。

4. ICU-QC-01 同一床同一日多个患者保留当前默认 `1/x` 分摊床日。
   - 在配置中预留开关，支持“每个患者均计 1 床日”的院内口径。

5. ICU-QC-02/03 医护床位比本期按统计段结束时间点快照。
   - 后续如需要人日均值，再增加 query mode。

6. ICU-QC-09/10 镇痛、镇静评估率分母按公式使用所有同期 ICU 患者。
   - 没有评分记录的患者必须进入分母且不进入分子。

7. ICU-QC-11 预计病死率协议统一为 0-1 小数。
   - 实现前抽样核查历史数据，必要时增加标准化处理。

8. ICU-QC-14 手术相关转入 ICU 短期保留当前科室名称关键字作为默认识别方式。
   - 长期改为配置手术来源科室 code、入科类型或上游手术关联字段。

9. 未实现 slot 本期只保留 `IcuQcGenericPB` slot。
   - 每个指标真正实现时再补专属 detail 消息，避免提前设计错误字段。

10. `GetGenericIcuQcReq.item_code` 保留为可选过滤字段。
    - 前端总述查询默认不传。
    - 后续单指标刷新或性能优化时再使用。

11. 新配置拆分为独立 `icis_qc_config.pb.txt`。
    - `application.properties` 通过 `jingyi.textresources.icis_qc_config` 指定默认配置路径。
    - 医院扩展时覆盖该路径配置，减少主 `icis_config.pb.txt` 膨胀。

12. `IcuQcConfigPB` 在 DB 中存储为 `SystemSettings(function_id = GET_IQC_CONFIG)`。
    - `GET_IQC_CONFIG = 13`。
    - 默认文件先加载，DB 有值时覆盖默认文件。
    - 更新配置时写回 DB，并刷新内存配置。

13. 质控配置使用独立设置接口。
    - 查询：`/api/settings/getiqcconfig`。
    - 更新：`/api/settings/updateiqcconfig`。

14. 脓毒症/感染性休克诊断配置命名为 `SepsisSepticShockDiagnosisConfigPB sepsis_septic_shock_diagnosis`。
    - 年龄下限默认 18。
    - 入科后诊断宽限期默认 24 小时。
    - 诊断关键词默认 `脓毒症`、`感染性休克`。

15. 诊断来源口径：
    - `patient_records.diagnosis` 不做时间限制。
    - `patient_diagnoses.diagnosis` 按入科后诊断宽限期限制。

16. 本次不实现脓毒症和感染性休克 bundle，仅提供配置，为后续实现准备。

## 待决策
