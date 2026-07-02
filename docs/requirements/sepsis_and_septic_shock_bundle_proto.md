# 脓毒症与感染性休克集束模块需求

## 本次范围

本阶段只整理需求，不实现 Java、proto、SQL 或前端代码。

后续目标类建议新增在：

```text
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/therapies/SepsisAndSepticShockBundleService.java
```

原因：bundle 属于治疗流程，也会被医生工作台、质控统计和预警复用。放入 `service/therapies` 能避免继续扩大 `service/doctors` 的职责，也不会把可操作治疗状态混入 `service/qcs` 这类统计服务。

## 规范依据

1. [国家卫健委《重症医学专业医疗质量控制指标（2024 年版）》](https://www.nhc.gov.cn/yzygj/c100068/202409/58a544b123a640e995840459fe811f8f/files/1733999818552_85009.pdf)将 ICU-05 定义为“感染性休克患者集束化治疗（bundle）完成率”，统计对象为入 ICU 诊断为感染性休克的成人患者，并说明可分别计算 1 小时、3 小时、6 小时完成率。
2. ICU-05 国内指标仍引用 SSC 2021 版作为对应时间节点口径；同时 [SCCM SSC adult guidelines](https://sccm.org/survivingsepsiscampaign/guidelines-and-resources/surviving-sepsis-campaign-adult-guidelines) 已更新到 2026，核心措施仍强调早期识别、及时抗感染、乳酸测定、血培养尽量在抗菌药前完成、低灌注或感染性休克时早期晶体液复苏、休克时升压药维持 MAP 目标等。必要时可对照 [SSC 2021 页面](https://www.sccm.org/clinical-resources/guidelines/guidelines/surviving-sepsis-guidelines-2021)。
3. 本系统需求只做质控和提醒，不替代临床诊断。自动判定值必须保留证据来源，并允许人工复核/覆盖。

## 总体模型

建议把模块拆成三层数据：

1. `SepsisAndSepticShockBundlePB`：集束任务状态、完成时间、人工说明、灌注再评估详情。
2. `SepsisAndSepticShockInfoPB`：T0 到 T0+6h 的自动取证快照，包括血压、乳酸、血培养、用药历史。
3. `SepsisAndSepticShockCasePB`：单个患者的组合响应，包含 `bundle` 和 `info`。

`SepsisAndSepticShockInfoPB` 中应删除 `all_lis_items`。血培养只需要输出命中血培养关键词后的 `culture_lis_items`，不再把全部 LIS 项目返回给前端。若已存在字段号 30 且担心兼容性，建议在 proto 中 `reserved 30; reserved "all_lis_items";`。

已确认口径：

1. 模块名保留“脓毒症与感染性休克”，但 ICU-05 默认诊断关键词只纳入“感染性休克”；若科室希望“脓毒症”也触发治疗提醒，通过配置追加关键词。
2. 数据库以 `pid` 作为业务唯一键，允许 `t0` 为 null。典型场景是系统自动探测为需要集束治疗后，用户人工改为 `need_bundle=false` 并填写 `no_bundle_reason`，用于修正自动探测误判。
3. 年龄用于质控和历史病例时按入科时间计算；入科时间缺失时按 T0；二者都缺失则不纳入并给出原因。
4. 主口径使用 `DiagnosisHistory` 计算 T0；如果该患者没有任何诊断历史，且 `patient_records.diagnosis` 命中诊断关键词，可用 `admission_time` 作为 T0 fallback。
5. 乳酸和血培养完成时间第一版使用 `auth_time`；上线前若确认 LIS 可提供标本采集时间，再优先切换为采集时间。
6. 第一版 `fluid_30mlkg` 只识别晶体液执行记录，不核算 30 ml/kg，自动点亮时必须在 note 中明确“未核算 30 ml/kg”。
7. 第一版低血压只使用 MAP/SBP 绝对阈值，不启用 `decrease_sbp_threshold`。
8. 不新增 `t0_source_note` 字段；如需记录 T0 来源、系统误判修正原因或人工说明，第一版复用 `no_bundle_reason`。
9. `FieldMask` patch 第一版只支持设置值，不支持清空为 null。
10. `pid` 作为业务唯一键后，第一版接受同一住院记录只允许一个集束病例；未来如需同一 pid 多次 T0，再恢复 `(pid, t0)` 或引入 episode id。

## 前置协议调整

### icis_quality_control.proto

`SepsisSepticShockDiagnosisConfigPB` 需要补充：

```proto
repeated string lac_external_param_code = 9;  // 乳酸 LIS 外部参数编码，匹配 patient_lis_results.external_param_code
int32 diagnosis_lookback_hours_before_admission_time = 10;  // 诊断时间允许早于入科时间的小时数，默认 0
bool enable_alarm = 20;  // 是否启用脓毒症/感染性休克集束告警
```

同时建议修正注释：

1. `HypotensionCriteriaPB.sbp_threshold` 注释应为“收缩压小于这个值”。
2. `abx_board_keyword` 语义是广谱抗菌药关键词，字段名疑似应为 `abx_broad_keyword`。为避免破坏已有配置，本阶段建议保留字段名，只修正注释和前端展示文案。
3. `age_lower_bound` 的计算口径建议改为“入科时或 T0 时年龄”，不要用 `TimeUtils.getNowUtc()` 计算历史病例年龄。

后端 `IcuQcConfigService` 需要同步补默认值和 normalize 逻辑：

1. `lac_external_param_code` 为空时，建议先 fallback 到 `lis_params.param_code = 'lis_Lac'` 对应的外部编码；如果 fallback 不可用，则返回空列表但不报错。
2. `diagnosis_lookback_hours_before_admission_time` 默认 0。若 HIS 会带入入科前感染性休克诊断，可由配置页设置允许回看小时数。
3. `enable_alarm` 默认建议为 `false`，上线后由配置页打开，避免新版本自动弹出未验收告警。

### 前端 QualityControlConfig

位置：

```text
jingyi_icis_frontend/src/pages/home/tabs/settings/BasicFeaturesConfig/components/QualityControlConfig
```

需要补齐：

1. `IcuQcConfigFormValues.sepsisLacExternalParamCode: string[]`。
2. `IcuQcConfigFormValues.sepsisDiagnosisLookbackHoursBeforeAdmissionTime: number`。
3. `IcuQcConfigFormValues.sepsisEnableAlarm: boolean`。
4. `getConfigFormValues` 读取 `config.sepsisSepticShockDiagnosis.lacExternalParamCode`、`diagnosisLookbackHoursBeforeAdmissionTime` 和 `enableAlarm`。
5. `buildConfigFromFormValues` 写回 `lacExternalParamCode`、`diagnosisLookbackHoursBeforeAdmissionTime` 和 `enableAlarm`。
6. UI 在“脓毒症/感染性休克诊断”配置区增加“乳酸 LIS 外部编码” tags select/input、“入科前诊断回看小时” input number，以及“启用集束告警” switch。

### icis_septic_shock.proto

需要新增：

```proto
import "google/protobuf/field_mask.proto";
```

`SepsisAndSepticShockBundlePB` 的 proto 仍保留 ISO8601 字符串字段，作为 API 展示和前端传输字段。数据库中对应字段使用 UTC `TIMESTAMP` 保存：

```text
t0_iso8601                         -> t0
h1_lactate_initial_iso8601         -> h1_lactate_initial_time
h1_culture_before_abx_iso8601      -> h1_culture_before_abx_time
h1_abx_broad_iso8601               -> h1_abx_broad_time
fluid_30mlkg_iso8601               -> fluid_30mlkg_time
vasopressor_iso8601                -> vasopressor_time
relactate_iso8601                  -> relactate_time
```

`SepsisAndSepticShockInfoPB` 建议结构保留：

```proto
message SepsisAndSepticShockInfoPB {
    int64 pid = 1;
    string t0_iso8601 = 2;
    string window_start_iso8601 = 3;
    string window_end_iso8601 = 4;

    repeated SepsisBloodPressureTimePointPB blood_pressure = 10;
    repeated SepsisLactateEvidencePB lactate = 20;
    repeated SepsisLisItemEvidencePB culture_lis_items = 31;
    repeated SepsisMedicationEvidencePB all_medications = 40;
    repeated SepsisMedicationEvidencePB abx_history = 41;
    repeated SepsisMedicationEvidencePB fluid_history = 42;
    repeated SepsisMedicationEvidencePB vasopressor_history = 43;
}
```

保存接口使用 patch 语义，建议在 `icis_septic_shock.proto` 新增：

```proto
message SepsisAndSepticShockBundlePatchPB {
    SepsisAndSepticShockBundlePB bundle = 1;
    google.protobuf.FieldMask update_mask = 2;
}
```

`update_mask.paths` 使用 `SepsisAndSepticShockBundlePB` 的字段名。字段出现在 mask 中表示人工明确修改，允许把 bool 改为 false；字段不在 mask 中表示不修改数据库对应字段。第一版不支持把数据库字段清空为 null；如后续需要清空语义，再新增 `repeated string clear_mask_path` 或单独 patch 消息。

证据消息应保留源 ID：

1. 乳酸：`pid`、`auth_time_iso8601`、`result_str`、`lactate_mmol_l`、`lactate_numeric_parsed`、`unit`、`report_id`、`external_param_code`。
2. 血培养：`time_iso8601`、`lis_item_name`、`report_id`、`matched_keyword`。
3. 用药：`started_at_iso8601`、`medication_display_name`、`medication_order_group_id`、`medication_execution_record_id`、`matched_keyword`。

### icis_alarm.proto

新增：

```proto
syntax = "proto3";

package com.jingyicare.jingyi_icis_engine.proto.config;

enum AlarmTypePB {
    ALARM_TYPE_UNSPECIFIED = 0;
    ALARM_TYPE_SEPSIS_AND_SEPTIC_SHOCK = 1;
}

message AlarmPB {
    int64 pid = 1;
    string his_bed_number = 2;
    string patient_name = 3;
    AlarmTypePB alarm_type = 4;
    string alarm_message = 5;
}
```

`patient_name` 映射 `PatientRecord.icuName`。静态告警文案常量命名为：

```java
SEPTIC_SHOCK_ALARM_MESSAGE = "需要脓毒症与感染性休克集束治疗";
```

## 数据库表

`sepsis_and_septic_shock_bundles` 保存 bundle 的可确认状态。字段需要对应 `SepsisAndSepticShockBundlePB`，其中 `pid`、`need_bundle` 非空，`t0` 允许为空，其他字段均可为 null。

`pid` 是该表的业务唯一键。表可以继续保留 `id BIGSERIAL PRIMARY KEY` 作为技术主键，但必须增加 `pid` 唯一索引；后续自动扫描、单病人查询和保存接口均按 `pid` 查找同一条 bundle entity。

时间字段统一为 UTC `TIMESTAMP`，注释统一写“xxx的utc时间”。`perfusion_reassessment_details` 类型为 `TEXT`，保存 `PerfusionReassessmentPB` 的 pbtxt。`ProtoUtils.java` 中提供 `decodePerfusionReassessmentPB(String txt)` 和 `encodePerfusionReassessmentPB(PerfusionReassessmentPB pb)`。

不新增 `t0_source_note` 数据库字段。`no_bundle_reason` 第一版复用为人工不纳入理由和系统判定说明字段，例如记录 `patient_records.diagnosis fallback` 来源。

自动扫描程序写库原则：

1. 只写入非 null 的字段。
2. 字符串只写入非空字符串。
3. nullable `BOOLEAN` 只有规则明确计算出值时才写入；未进入计算阶段的字段保持 null。
4. 已存在的非 null 人工/历史字段不能被自动扫描覆盖。
5. 唯一索引使用 `(pid)`，不再使用 `(pid, t0)`。
6. 当用户把系统自动探测出的 `need_bundle=true` 改为 `need_bundle=false` 时，保存 `no_bundle_reason`，并允许 `t0` 置空或保留原 T0，具体由保存接口的 patch 决定。

## Service 职责

类名：

```java
@Service
@Slf4j
public class SepsisAndSepticShockBundleService
```

建议使用构造器注入 repository/service。涉及写库的方法加 `@Transactional`，纯查询方法加 `@Transactional(readOnly = true)`。

建议拆分以下子功能：

1. `getDiagnosisConfig()`：从 `system_settings.function_id == GET_IQC_CONFIG` 读取 `IcuQcConfigPB.sepsis_septic_shock_diagnosis`。
2. `buildSepticShockCases(List<Long> pids)`：批量生成并合并 `SepsisAndSepticShockCasePB`。
3. `evaluateBundleEligibility(PatientRecord patient, List<DiagnosisHistory> diagnoses, SepsisSepticShockDiagnosisConfigPB config)`：计算 T0、`need_bundle` 和不纳入原因。
4. `collectInfo(PatientRecord patient, LocalDateTime t0, LocalDateTime windowEnd, SepsisSepticShockDiagnosisConfigPB config)`：收集 `SepsisAndSepticShockInfoPB`。
5. `calculateAutoBundle(PatientRecord patient, LocalDateTime t0, SepsisAndSepticShockInfoPB info, SepsisSepticShockDiagnosisConfigPB config)`：从证据快照派生自动 bundle。
6. `mergeWithPersistedBundle(SepsisAndSepticShockBundlePB autoBundle, SepsisAndSepticShockBundle entity)`：用数据库非 null 值覆盖自动值，生成对前端展示的有效 bundle。
7. `persistAutoBundleIfNeeded(...)`：按自动扫描原则创建或补全数据库记录。
8. `getSepticShockCase(long pid)`：查询单个患者，内部调用批量接口。
9. `saveSepticShockCase(SepsisAndSepticShockBundlePatchPB patch)`：按 `FieldMask` 保存单个患者人工确认值，并返回重新计算后的 case。
10. `buildSepticShockAlarms(List<SepsisAndSepticShockCasePB> cases)`：生成告警列表。

## T0 与 need_bundle

对每个 pid：

1. `PatientRecord` 不存在时，不生成 case。
2. `patient_records.icu_date_of_birth` 为空时，`need_bundle=false`，`t0=null`，`t0_iso8601=""`。
3. 年龄按 `admission_time` 计算；`admission_time` 缺失且后续能得到 T0 时按 T0 计算；二者都缺失则 `need_bundle=false` 并记录原因。年龄小于 `diagnosisConfig.age_lower_bound` 时，`need_bundle=false`，`t0=null`，`t0_iso8601=""`。
4. 从 `DiagnosisHistory.patient_id == pid` 且 `is_deleted=false` 读取诊断历史，生成 `{diagnosis_time, diagnosis}`。
5. 用下列窗口过滤诊断历史，并按 `diagnosis_time` 升序：

```text
diagnosis_time >= admission_time - diagnosis_lookback_hours_before_admission_time
diagnosis_time <= admission_time + grace_period_hours_from_admission_time
```

`diagnosis_lookback_hours_before_admission_time` 默认 0。

6. 在过滤后诊断中查找 `diagnosis` 包含任一 `diagnosisConfig.diagnosis_keyword` 的记录。字符串匹配建议 trim；中文按子串匹配，英文大小写不敏感。
7. 若没有命中，`need_bundle=false`。
8. 若命中，T0 使用最早命中记录的 `diagnosis_time`，`t0_iso8601 = TimeUtils.toIso8601String(t0, ConfigProtoService.getConfig().getZoneId())`，`need_bundle=true`。
9. 如果诊断历史为空，且 `patient_records.diagnosis` 命中任一诊断关键词，则使用 `admission_time` 作为 T0 fallback。如需要向前端展示来源，第一版在 `no_bundle_reason` 中记录 `patient_records.diagnosis fallback`。如果 `admission_time` 为空，则不纳入并记录原因。

窗口统一为闭区间：

```text
window_start = t0
window_end = t0 + 6h
```

1 小时、3 小时、6 小时判断均包含右端点。

## 证据收集

### 血压

数据来源：`patient_monitoring_records`。

筛选条件：

```sql
pid = :pid
and monitoring_param_code in ('nibp_s', 'nibp_m', 'nibp_d', 'ibp_s', 'ibp_m', 'ibp_d')
and effective_time >= :t0
and effective_time <= :t0_plus_6h
and is_deleted = false
```

值解析：

```text
patient_monitoring_records.param_value.MonitoringValuePB.value.float_val
```

聚合规则：

1. 按 `effective_time` 精确分组。
2. 同一时间点保留全部原始 `param_code/param_value`。
3. 同一时间点同时存在 IBP 和 NIBP 时，低血压判定采用 IBP。
4. 同一来源存在 `*_s` 和 `*_d`，但缺少 `*_m` 时，派生 MAP：

```text
map = sbp * 1 / 3 + dbp * 2 / 3
```

5. 派生 MAP 要标记 `selected_map_derived=true`，避免前端误解为原始监护值。

### 乳酸

数据来源：`patient_records`、`patient_lis_items`、`patient_lis_results`。

建议查询：

```sql
select
    pli.auth_time,
    plr.result_str,
    plr.unit,
    pli.report_id,
    plr.external_param_code
from patient_records pr
join patient_lis_items pli on pr.his_patient_id = pli.his_pid
join patient_lis_results plr on pli.report_id = plr.report_id
where pr.id = :pid
  and plr.external_param_code in (:diagnosisConfig.lac_external_param_code)
  and pli.auth_time >= :t0
  and pli.auth_time <= :t0_plus_6h
  and plr.is_deleted = false
order by pli.auth_time;
```

`result_str` 需要做容错解析，例如 `>4`、`4.2 mmol/L`、中文单位、空值。无法解析时保留原始字符串，`lactate_numeric_parsed=false`，但不能参与 `>=4` 或 `>2` 的自动判定。

### 血培养

数据来源：`patient_records`、`patient_lis_items`。

建议查询：

```sql
select
    pli.auth_time,
    pli.lis_item_name,
    pli.report_id
from patient_records pr
join patient_lis_items pli on pr.his_patient_id = pli.his_pid
where pr.id = :pid
  and pli.auth_time >= :t0
  and pli.auth_time <= :t0_plus_6h
  and pli.lis_item_name is not null
order by pli.auth_time;
```

服务层筛选 `lis_item_name` 包含任一 `diagnosisConfig.blood_culture_keyword` 的记录，输出 `culture_lis_items`。`matched_keyword` 记录命中的关键词。

### 用药历史

数据来源：`medication_order_groups`、`medication_execution_records`。

建议查询：

```sql
select
    mer.start_time,
    mog.medication_dosage_group,
    mer.medication_dosage_group,
    mog.id as medication_order_group_id,
    mer.id as medication_execution_record_id
from medication_order_groups mog
join medication_execution_records mer on mog.id = mer.medication_order_group_id
where mer.start_time is not null
  and mer.start_time >= :t0
  and mer.start_time <= :t0_plus_6h
  and mog.patient_id = :pid
  and mer.is_deleted = false
order by mer.start_time;
```

`MedicationDosageGroupPB.display_name` 优先使用执行记录中的 dosage group；为空时 fallback 到医嘱组 dosage group；再为空时可 fallback 到药品名拼接。

分类规则：

1. `all_medications` 保存所有用药执行记录。
2. `abx_history`：`medication_display_name` 包含任一 `diagnosisConfig.abx_board_keyword`。
3. `fluid_history`：`medication_display_name` 包含任一 `diagnosisConfig.fluid_keyword`。
4. `vasopressor_history`：`medication_display_name` 包含任一 `diagnosisConfig.vasopressor_keyword`。
5. 关键词重叠时同一条用药可进入多个分类，配置页负责避免歧义。

## 自动 Bundle 计算

常量：

```java
private static final double LACTATE_FLUID_THRESHOLD_MMOL_L = 4.0;
private static final double INITIAL_LACTATE_REMEASURE_THRESHOLD_MMOL_L = 2.0;
```

### 1 小时任务

在 `[t0, t0+1h]` 内计算：

1. `h1_lactate_initial=true`：存在至少一条可作为初始乳酸的 `info.lactate`。完成时间取最早乳酸时间。
2. `h1_abx_broad=true`：存在至少一条 `info.abx_history`。完成时间取最早广谱抗菌药执行时间。
3. `h1_culture_before_abx=true`：`[t0, t0+1h]` 内存在至少一条 `info.culture_lis_items`。第一版不因血培养晚于抗菌药而自动判失败；未来前端可基于 culture/abx 时间关系给出提醒，也允许人工重载。

原需求中多处写成：

```text
h1_lactate_initial == true && h1_lactate_initial == true && h1_abx_broad == true
```

应修正为：

```text
h1_lactate_initial && h1_culture_before_abx && h1_abx_broad
```

### 3 小时补液任务

只有 1 小时三项均完成后，才进入补液任务计算；否则补液相关字段保持 null，不写入数据库。

在 `[t0, t0+3h]` 内计算：

1. 低血压：根据 `diagnosisConfig.hypotension_criteria` 判断血压证据。第一版只使用绝对阈值：`MAP < mbp_threshold` 或 `SBP < sbp_threshold`，不启用 `decrease_sbp_threshold`。
2. 乳酸异常：存在可解析乳酸值 `>= 4.0 mmol/L`。
3. `fluid_qualified=true`：低血压或乳酸异常任一成立。
4. `fluid_30mlkg=true`：`fluid_qualified=true` 且 `[t0, t0+3h]` 内存在晶体液记录。完成时间建议取最早晶体液执行时间。

注意：仅凭晶体液执行记录不能严格证明“30 ml/kg”剂量达标。第一版自动识别到晶体液执行记录并点亮 `fluid_30mlkg` 时，如果 `fluid_30mlkg_note` 为空，应自动写入“未核算 30 ml/kg”。

### 6 小时任务

只有 1 小时三项均完成，且 `!fluid_qualified || fluid_30mlkg` 成立后，才进入升压药和复测乳酸计算；否则相关字段保持 null。

升压药：

1. 在 `[t0, t0+6h]` 内取最后一条可判定血压记录；如果该记录仍符合低血压标准，则 `vasopressor_qualified=true`。第一版以该规则近似“复苏中或复苏后仍低血压”。
2. 若 `vasopressor_qualified=true`，在 `[t0, t0+6h]` 内过滤 `info.vasopressor_history`。
3. 存在记录时 `vasopressor=true`，完成时间按原需求取最后一条升压药执行时间。

复测乳酸：

1. 在 `[t0, t0+6h]` 内按时间升序过滤可解析乳酸。
2. 第一条乳酸值 `> 2.0 mmol/L` 时，`relactate_qualified=true`。
3. 若 `relactate_qualified=true` 且第一条之后至少还有一条乳酸记录，`relactate=true`，完成时间取最后一条乳酸时间。

## 数据库合并规则

合并前先按 `pid` 查询唯一 entity，不再按 `(pid, t0)` 查询。entity 中的 `need_bundle` 是人工/历史确认后的最终纳入标记；如果 entity 已存在且 `need_bundle=false`，后续自动扫描即使命中诊断，也不能自动改回 true，只能返回数据库中的 false 状态和 `no_bundle_reason`。

### need_bundle=false

1. `bundleEntity == null`：不写数据库。
2. `bundleEntity != null`：只更新 `need_bundle=false`，其他字段不清空。

### need_bundle=true 且 entity 不存在

从自动 bundle 初始化 entity：

1. 必填：`pid`、`t0`、`need_bundle=true`。
2. 1 小时任务：只有自动值为 true 时，初始化对应 boolean 和完成时间。
3. 3 小时任务：只有 1 小时三项均完成后，才按规则初始化 `fluid_qualified`、`fluid_30mlkg`、`fluid_30mlkg_time`。
4. 6 小时任务：只有 1 小时三项均完成，且 `!fluid_qualified || fluid_30mlkg` 后，才按规则初始化 `vasopressor_qualified`、`vasopressor`、`vasopressor_time`、`relactate_qualified`、`relactate`、`relactate_time`。
5. 不符合初始化条件的字段保持 null。

### need_bundle=true 且 entity 已存在

1. entity 中非 null 字段反向覆盖自动 bundle，用于前端展示；其中 `need_bundle=false` 是强覆盖，表示用户已确认该患者不需要纳入本轮集束。
2. 自动扫描只能补齐 entity 中仍为 null 的字段。
3. 后续阶段的 gating 条件应基于“entity 覆盖后的有效 bundle”判断。例如人工确认了 `h1_abx_broad=true`，则可以继续计算补液阶段。
4. note 字段、`no_bundle_reason`、`perfusion_reassessment_details` 不被自动扫描覆盖。

## 告警接口

服务接口输入：

```java
List<SepsisAndSepticShockCasePB>
```

处理逻辑：

1. 如果 `diagnosisConfig.enable_alarm=false`，返回空列表。
2. 过滤 `case.bundle.need_bundle == true`。
3. 根据 pid 批量读取 `PatientRecord`。
4. 返回 `AlarmPB`：

```text
pid = PatientRecord.id
his_bed_number = PatientRecord.hisBedNumber
patient_name = PatientRecord.icuName
alarm_type = ALARM_TYPE_SEPSIS_AND_SEPTIC_SHOCK
alarm_message = SEPTIC_SHOCK_ALARM_MESSAGE
```

病人记录不存在时跳过该告警。

## Web API

`icis_web_api.proto` 需要 import：

```proto
import "config/icis_septic_shock.proto";
import "config/icis_alarm.proto";
```

新增接口建议放在 `/api/therapy/`：

```proto
/*
 * /api/therapy/getsepticshockcase
 * input: GetSepticShockCaseReq
 * output: GetSepticShockCaseResp
 */
message GetSepticShockCaseReq {
    int64 pid = 1;
}

message GetSepticShockCaseResp {
    shared.ReturnCode rt = 1;
    config.SepsisAndSepticShockCasePB septic_shock_case = 2;
}

/*
 * /api/therapy/savesepticshockcase
 * input: SaveSepticShockCaseReq
 * output: SaveSepticShockCaseResp
 */
message SaveSepticShockCaseReq {
    config.SepsisAndSepticShockBundlePatchPB bundle_patch = 1;
}

message SaveSepticShockCaseResp {
    shared.ReturnCode rt = 1;
    config.SepsisAndSepticShockCasePB septic_shock_case = 2;
}
```

`IcisController` 中新增：

```java
@PostMapping("/therapy/getsepticshockcase")
@PostMapping("/therapy/savesepticshockcase")
```

`WebApiService` 构造器注入 `SepsisAndSepticShockBundleService`，对应方法只做 JSON/proto 转发、记录 metric，并调用 therapy service。

保存接口语义：

1. 输入是单个 `SepsisAndSepticShockBundlePatchPB`。
2. 根据 `bundle_patch.bundle.pid` 更新或创建对应 entity。
3. 保存后重新调用单病人查询接口，返回合并后的 `SepsisAndSepticShockCasePB`。
4. 保存接口属于人工确认/编辑入口，只有出现在 `update_mask.paths` 中的字段会更新数据库；bool 默认值 false 因为有 FieldMask 可以表达为“明确保存 false”。
5. 单病人查询找不到患者时返回非 OK `ReturnCode`，优先使用现有 `StatusCode.PATIENT_NOT_FOUND`，`msg` 为“患者不存在”或更具体的 patient not found 文案；批量接口仍跳过不存在 pid。

## 错误码与公共文本配置

传统新增 `StatusCode` 需要同步修改：

```text
jingyi_icis_engine2/src/main/proto/icis_web_api.proto
jingyi_icis_engine2/src/main/resources/config/pbtxt/hospitals/ah2_icis_config.pb.txt
jingyi_icis_engine2/src/main/resources/config/pbtxt/hospitals/xaxrmyy_icis_config.pb.txt
jingyi_icis_engine2/src/main/resources/config/pbtxt/hospitals/xnxrmyy_icis_config.pb.txt
jingyi_icis_engine2/src/main/resources/config/pbtxt/icis_config.pb.txt
jingyi_icis_engine2/src/test/resources/text_resources/icis_config.pb.txt
```

这会导致 `text { status_code_msg: ... }` 与 `user { ... }` 在多份 pbtxt 中重复维护。下一阶段建议做公共配置抽取：

1. 新增 `jingyi_icis_engine2/src/main/resources/config/pbtxt/common_text.pb.txt`，内容类型为 `config.Text`，承载 `status_code_msg`、`units`、`web_api_message`。
2. 新增 `common_user.pb.txt`，内容类型为 `config.UserConfigPB`；医院配置默认复用该公共 user 配置。测试资源存在菜单/权限差异，单独抽到 `src/test/resources/text_resources/test_user.pb.txt`。
3. 从 `icis_config.pb.txt`、三个医院级 `*_icis_config.pb.txt`、测试用 `src/test/resources/text_resources/icis_config.pb.txt` 中移除顶层 `text { ... }` 与 `user { ... }`。
4. `ConfigProtoService` 增加 `common_text` 与 `user_config` 资源加载；启动时分别解析为 `Text` 与 `UserConfigPB`，在主配置缺失对应字段时写回 `Config`。
5. `application-prod.properties` 增加：

```properties
jingyi.textresources.common_text=classpath:/config/pbtxt/common_text.pb.txt
jingyi.textresources.user_config=classpath:/config/pbtxt/common_user.pb.txt
```

6. `application-test.properties` 显式配置 `common_text.pb.txt` 与 `classpath:/text_resources/test_user.pb.txt`，让测试和正式错误码文案保持一致，同时保留测试菜单/权限差异，且不把测试专用 user 配置放入主资源。
7. 新增错误码时，只修改 `icis_web_api.proto:StatusCode` 和 `common_text.pb.txt` 的 `status_code_msg`，不再多处复制。
8. `ConfigProtoService.getReturnCode(StatusCode code)` 仍按 `status_code_msg(code.getNumber())` 取文案，所以 `StatusCode` number 与 `status_code_msg` 下标必须保持一致。新增枚举值只能追加，不能插入重排。

兼容要求：

1. 如果某个外部 `config_pb_txt` 仍包含完整 `text { ... }`，启动时优先使用外部配置中的完整 `text`；没有 text 时才使用 `common_text.pb.txt`。
2. 如果某个外部 `config_pb_txt` 仍包含完整 `user { ... }`，启动时优先使用外部配置中的完整 `user`；没有 user 时才使用 `jingyi.textresources.user_config` 指向的独立配置。
3. 不支持“部分 text/user override”，避免 repeated 字段被 protobuf merge 追加后出现下标错位或菜单权限重复。

## 验收口径

1. 传入多个 pid 时，`PatientRecord` 不存在的 pid 不生成 case。
2. 未成年、生日缺失、无入科宽限期内命中诊断的患者，返回 `need_bundle=false`，且不创建新数据库记录。
3. 命中诊断时，T0 使用最早命中的诊断时间。
4. 血压同时间点 IBP 优先于 NIBP；缺 MAP 时能由 SBP/DBP 派生并标记。
5. 乳酸按 `lac_external_param_code` 匹配，并保留原始 `result_str`。
6. 1 小时内存在血培养记录即可自动设置 `h1_culture_before_abx=true`；若血培养晚于抗菌药，第一版只保留证据，未来由前端提醒或人工重载。
7. 晶体液、升压药、复测乳酸的完成字段只在前置 gating 满足后写库；自动识别晶体液时 `fluid_30mlkg_note` 明确“未核算 30 ml/kg”。
8. 自动扫描不覆盖数据库已有非 null 字段。
9. `perfusion_reassessment_details` 能通过 `ProtoUtils` pbtxt encode/decode 往返。
10. `pid` 是 `sepsis_and_septic_shock_bundles` 的业务唯一键，用户保存的 `need_bundle=false` 能覆盖后续自动扫描的 `need_bundle=true`。
11. `enable_alarm=false` 时不产生告警；打开后只对 `need_bundle=true` 病例产生静态文案告警。
12. 新增错误码文案只需要维护 `common_text.pb.txt`，不再在每个医院配置 pbtxt 中复制 `text { ... }`。

## 待决策

暂无新的待决策项。
