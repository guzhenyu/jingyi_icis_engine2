# 脓毒症与感染性休克集束前端需求

## 本次范围

本阶段只整理前端需求，不实现代码。

目标是在医生工作台新增“感染性休克集束”页面，用于展示并人工确认单个患者的 `SepsisAndSepticShockCasePB`。页面读取后端自动生成的 bundle 状态和 6 小时窗口内证据，允许医生编辑关键人工确认字段，并通过 `SepsisAndSepticShockBundlePatchPB + FieldMask` 保存。

后端 proto、数据库、自动计算口径见：

```text
jingyi_icis_engine2/docs/requirements/sepsis_and_septic_shock_bundle_proto.md
```

## 菜单与页面入口

当前 `common_user.pb.txt` 已新增菜单项：

```text
function_id: 51
name: "感染性休克集束"
```

前端需要在 `PageContent` 接入该功能：

```text
jingyi_icis_frontend/src/components/PageLayout/components/PageContent/index.tsx
```

要求：

1. 新增 `SepticShockBundle` 页面 import。
2. 在医生“日常操作”注释区补充 `//------51. 感染性休克集束------`。
3. 在 `renderTabContent` 的 switch 中新增 `case 51`，渲染该页面组件。

建议页面目录按用户指定路径创建：

```text
jingyi_icis_frontend/src/pages/home/tabs/doctors/SepticShockBundle
```

页面组件命名：

1. 目录保留 `SepticShockBundle`，与现有 function 名称和较短路径一致。
2. React 组件导出名使用 `SepsisAndSepticShockBundle`。
3. 页面标题展示为“脓毒症与感染性休克集束”，菜单名可继续保留“感染性休克集束”。

## 接口与类型

### WebApi.ts

`jingyi_icis_frontend/src/api/WebApi.ts` 需要包含以下 endpoint：

```ts
GET_SEPTIC_SHOCK_CASE: '/api/therapy/getsepticshockcase'
SAVE_SEPTIC_SHOCK_CASE: '/api/therapy/savesepticshockcase'
```

并补齐与后端 proto 对应的 TypeScript interface：

1. `AlarmTypePB`、`AlarmPB`。
2. `FieldMaskPB`。
3. `PerfusionReassessmentPB` 及相关 enum。
4. `SepsisAndSepticShockBundlePB`。
5. `SepsisBloodPressureTimePointPB`、`SepsisMonitoringParamValuePB`。
6. `SepsisLactateEvidencePB`。
7. `SepsisLisItemEvidencePB`。
8. `SepsisMedicationEvidencePB`。
9. `SepsisAndSepticShockInfoPB`。
10. `SepsisAndSepticShockCasePB`。
11. `SepsisAndSepticShockBundlePatchPB`。
12. `GetSepticShockCaseReq/Resp`、`SaveSepticShockCaseReq/Resp`。

前端字段使用现有 JSON camelCase，例如 `t0Iso8601`、`needBundle`、`fluid30MlkgIso8601`。`FieldMask.paths` 建议统一传 proto 字段名 snake_case，例如 `need_bundle`、`t0_iso8601`、`fluid_30mlkg_iso8601`。后端当前会把 camelCase mask path 归一化为 snake_case，但前端统一 snake_case 更方便排查请求。

### Redux/请求层

建议新增：

```text
jingyi_icis_frontend/src/store/slices/TherapyApi.ts
```

包含两个 thunk：

1. `getSepticShockCase(GetSepticShockCaseReq)`。
2. `saveSepticShockCase(SaveSepticShockCaseReq)`。

页面内部保存 `case` 和 `draftBundle` 即可，不强制新增全局 slice，避免切换患者或多标签时出现陈旧 case。若后续多个页面复用 therapy 数据，再抽全局状态。

## 数据加载

1. 页面从 Redux 读取 `patient.curPatient` 和 `patient.curPatientDetails`。
2. 没有选中患者时，页面展示空状态，不请求接口，保存按钮禁用。
3. 选中患者变化时，调用 `/api/therapy/getsepticshockcase`，参数为 `{ pid: curPatient.id }`。
4. 返回 `rt.code != OK` 时展示错误消息；`PATIENT_NOT_FOUND` 不应渲染成“不需要 bundle”。
5. 返回成功后：
   - `serverCase = resp.septicShockCase`
   - `draftBundle = deepClone(serverCase.bundle)`
   - `dirtyPaths = []`
6. 点击“刷新”时重新查询接口，并丢弃本地未保存修改。若存在未保存修改，应二次确认。

## 保存语义

页面只提交被用户修改过的字段：

```ts
{
  bundlePatch: {
    bundle: draftBundle,
    updateMask: {
      paths: dirtyPaths
    }
  }
}
```

要求：

1. `draftBundle.pid` 必须存在。
2. 用户编辑字段时，将对应 proto snake_case 字段加入 `dirtyPaths`。
3. 保存成功后使用响应中的 `septicShockCase` 刷新页面状态，并清空 `dirtyPaths`。
4. `bool` 字段必须依赖 FieldMask 表达“用户明确设置 false”，不能靠 truthy 判断。
5. 第一版不支持清空数据库字段为 null。时间字段传空字符串即使在 mask 中，后端也不会清空原时间；前端不要提供“清空为 null”的承诺。
6. 若用户把 `needBundle` 改为 `false`，保存时至少提交：
   - `need_bundle`
   - `no_bundle_reason`
   - 如用户修改了 T0，则提交 `t0_iso8601`
7. 若 `needBundle=false`，`t0Iso8601` 输入控件灰化，不允许继续编辑；已有值仍按后端返回展示。

常用字段路径：

```text
need_bundle
t0_iso8601
no_bundle_reason
h1_lactate_initial
h1_lactate_initial_iso8601
h1_lactate_initial_note
h1_culture_before_abx
h1_culture_before_abx_iso8601
h1_culture_before_abx_note
h1_abx_broad
h1_abx_broad_iso8601
h1_abx_broad_note
fluid_30mlkg
fluid_qualified
fluid_30mlkg_iso8601
fluid_30mlkg_note
vasopressor_qualified
vasopressor
vasopressor_iso8601
vasopressor_note
relactate_qualified
relactate
relactate_iso8601
relactate_note
perfusion_reassessment_details
```

`fluid_qualified`、`vasopressor_qualified`、`relactate_qualified` 允许人工覆盖。前端编辑这些适应证字段时，必须将对应字段加入 `dirtyPaths`，并立即按人工覆盖后的值更新后续任务的灰化/点亮状态。

## 页面布局

整体风格沿用现有医生页面：Ant Design、`ToolbarWrap`、`GenericTitleCardWrap`、CSS module。页面应是工作台式布局，信息密度高、状态清晰，不做 landing page 或营销式大标题。

建议结构：

```text
SepticShockBundle/
  index.tsx
  index.module.scss
  components/
    BundlePatientHeader/
    BundleTaskPanel/
    BundleTaskGroup/
    BundleTaskRow/
    PerfusionReassessmentForm/
    EvidencePanel/
    EvidenceList/
    BloodPressureSummary/
```

### 顶部

顶部信息区展示并编辑当前病例基础状态：

1. 床号：来自 `curPatientDetails.bedNumberStr`，缺失用 `curPatient.bedNumberStr` 或 `-`。
2. 姓名：来自 `curPatientDetails.icuName`。
3. 入科时间：`curPatientDetails.admissionTimeIso8601`，格式化为本地 `YYYY-MM-DD HH:mm`。
4. 诊断：`curPatientDetails.diagnosis`，长文本允许换行或 tooltip。
5. 是否需要集束：`Switch` 或 `Radio.Group`，绑定 `draftBundle.needBundle`。
6. T0：`DatePicker showTime`，绑定 `draftBundle.t0Iso8601`；`needBundle=false` 时 disabled。
7. 说明：`TextArea`，绑定 `draftBundle.noBundleReason`。当 `needBundle=false` 时文案偏向“不需要集束理由”；当 `needBundle=true` 时文案偏向“系统判定/人工说明”。

顶部右侧放操作按钮：

1. `保存`：有 `dirtyPaths`、非 loading、已选中患者时可点。
2. `刷新`：非 loading、已选中患者时可点。

### 主面板

主面板左右分栏：

1. 左栏：集束任务确认，建议宽度 55%-60%。
2. 右栏：自动取证信息，建议宽度 40%-45%。
3. 小屏下改为上下堆叠，左栏在上，右栏在下。
4. 两栏均使用内部滚动，顶部患者信息和底部操作区保持可见。

### 底部

底部固定或跟随主容器底部展示：

1. `保存`：主按钮，和顶部按钮行为一致。
2. `刷新`：次按钮。底部不再设置“重置”按钮。
3. 可显示“已保存 / 未保存修改 / 保存中”的轻量状态。

如果顶部已经放置保存/刷新，底部仍保留一组按钮，方便长页面操作；两组按钮状态必须一致。

## 左栏：集束任务

左栏根据 `SepsisAndSepticShockBundlePB` 分为 1 小时、3 小时、6 小时三组。每行统一展示：

1. 分类：1 小时任务、3 小时任务、6 小时任务。
2. 项目名称。
3. 是否已完成：`Switch` 或 `Checkbox`。
4. 完成时间：`DatePicker showTime`。
5. 说明：短输入框或可展开 `TextArea`。
6. 状态：已完成、未完成、未触发、待开始、超时未完成、已自动识别。

### 1 小时任务

行定义：

| 项目 | 完成字段 | 时间字段 | 说明字段 |
| --- | --- | --- | --- |
| 初始乳酸测定 | `h1LactateInitial` | `h1LactateInitialIso8601` | `h1LactateInitialNote` |
| 血培养/病原学送检 | `h1CultureBeforeAbx` | `h1CultureBeforeAbxIso8601` | `h1CultureBeforeAbxNote` |
| 广谱抗菌药 | `h1AbxBroad` | `h1AbxBroadIso8601` | `h1AbxBroadNote` |

规则：

1. `needBundle=false` 或 T0 为空时整组灰化。
2. 当前时间早于 T0 时整组灰化，状态显示“待开始”。
3. 当前时间在 `[T0, T0+1h]` 内时点亮，未完成项显示待处理色。
4. 当前时间晚于 `T0+1h` 且未完成时标红。
5. 1 小时任务是否全部完成的 gating 使用：

```text
h1LactateInitial && h1CultureBeforeAbx && h1AbxBroad
```

### 3 小时任务

行定义：

| 项目 | 完成字段 | 时间字段 | 说明字段 |
| --- | --- | --- | --- |
| 是否符合补液适应证 | `fluidQualified` | 无 | 无 |
| 晶体液补充 | `fluid30Mlkg` | `fluid30MlkgIso8601` | `fluid30MlkgNote` |

规则：

1. `needBundle=false`、T0 为空、当前时间早于 T0 时整组灰化。
2. 1 小时任务未全部完成时整组灰化，状态显示“等待 1 小时任务完成”。
3. 1 小时任务完成后，`fluidQualified` 按后端返回初始值展示，并允许人工覆盖。
4. 编辑 `fluidQualified` 时加入 `dirtyPaths: ["fluid_qualified"]`，并立即按人工值更新“晶体液补充”的可编辑状态。
5. 人工将 `fluidQualified` 从 true 改为 false 时，不清空本地 draft 和数据库中的 `fluid30Mlkg`、`fluid30MlkgIso8601`、`fluid30MlkgNote`；UI 按“未触发”隐藏或灰化晶体液补充值。如果后续再改回 true，恢复展示原有完成值。
6. 人工将 `fluidQualified` 从 false 改为 true 时，不自动带入当前时间，只点亮“晶体液补充”任务，由医生明确勾选完成并选择完成时间。
7. `fluidQualified=false` 时，“晶体液补充”显示为“未触发”，不标红，完成状态、时间、说明不可编辑。
8. `fluidQualified=true` 时，“晶体液补充”的完成状态、时间、说明可编辑。
9. 当前时间晚于 `T0+3h` 且 `fluidQualified=true && !fluid30Mlkg` 时标红。
10. `fluid30MlkgNote` 若包含“未核算 30 ml/kg”，应原样显示，提示第一版仅识别晶体液执行记录。

### 6 小时任务

行定义：

| 项目 | 完成字段 | 时间字段 | 说明字段 |
| --- | --- | --- | --- |
| 是否符合升压药适应证 | `vasopressorQualified` | 无 | 无 |
| 升压药 | `vasopressor` | `vasopressorIso8601` | `vasopressorNote` |
| 是否需要复测乳酸 | `relactateQualified` | 无 | 无 |
| 复测乳酸 | `relactate` | `relactateIso8601` | `relactateNote` |
| 灌注/容量反应性再评估 | `perfusionReassessmentDetails` | `assessmentTimeIso8601` | `note` |

规则：

1. `needBundle=false`、T0 为空、当前时间早于 T0 时整组灰化。
2. 1 小时任务未全部完成时整组灰化。
3. 若 `fluidQualified=true && !fluid30Mlkg`，6 小时任务灰化，状态显示“等待 3 小时补液任务完成”。
4. 若 `!fluidQualified || fluid30Mlkg`，6 小时任务点亮。
5. 6 小时任务点亮后，`vasopressorQualified` 和 `relactateQualified` 按后端返回初始值展示，并允许人工覆盖。
6. 编辑 `vasopressorQualified` 时加入 `dirtyPaths: ["vasopressor_qualified"]`；编辑 `relactateQualified` 时加入 `dirtyPaths: ["relactate_qualified"]`。
7. 人工将 `vasopressorQualified` 从 true 改为 false 时，不清空本地 draft 和数据库中的 `vasopressor`、`vasopressorIso8601`、`vasopressorNote`；UI 按“未触发”隐藏或灰化升压药值。如果后续再改回 true，恢复展示原有完成值。
8. 人工将 `relactateQualified` 从 true 改为 false 时，不清空本地 draft 和数据库中的 `relactate`、`relactateIso8601`、`relactateNote`；UI 按“未触发”隐藏或灰化复测乳酸值。如果后续再改回 true，恢复展示原有完成值。
9. 人工将 `vasopressorQualified` 或 `relactateQualified` 从 false 改为 true 时，不自动带入当前时间，只点亮对应下游任务，由医生明确勾选完成并选择完成时间。
10. `vasopressorQualified=false` 时，“升压药”显示“未触发”，不标红，完成状态、时间、说明不可编辑；`true` 时可编辑 `vasopressor`、`vasopressorIso8601`、`vasopressorNote`。
11. `relactateQualified=false` 时，“复测乳酸”显示“未触发”，不标红，完成状态、时间、说明不可编辑；`true` 时可编辑 `relactate`、`relactateIso8601`、`relactateNote`。
12. 当前时间晚于 `T0+6h` 且适应证为 true 但对应完成字段为 false 时标红。
13. `perfusionReassessmentDetails` 需要展示明细，并允许在 6 小时组点亮时编辑；第一版不作为 6 小时必填任务，不参与完成率或红色超时判定。

### 灌注再评估明细

`perfusionReassessmentDetails` 建议放在 6 小时任务组内，用可展开表单展示：

1. 评估方法：`assessmentMethod`，下拉选择。
2. 容量反应性：`fluidResponsiveness`，下拉选择。
3. 灌注改善情况：`perfusionAssessment`，下拉选择。
4. 医生结论：`physicianConclusion`，文本域。
5. 评估时间：`assessmentTimeIso8601`，`DatePicker showTime`。
6. MAP：`mapMmhg`，数字输入，单位 mmHg。
7. 乳酸：`lactateMmolL`，数字输入，单位 mmol/L。
8. 尿量：`urineMlKgH`，数字输入，单位 ml/kg/h。
9. 毛细血管再充盈时间：`capillaryRefillTimeSeconds`，数字输入，单位秒。
10. 皮肤花斑、四肢湿冷、意识改变：checkbox。
11. 下一步处理：`nextAction`，下拉选择。
12. 其他说明：`note`，文本域。

编辑任一字段时，`dirtyPaths` 加入 `perfusion_reassessment_details`。第一版整体保存整个 details 对象，不做内部 FieldMask。

## 右栏：自动取证

右栏只展示 `SepsisAndSepticShockInfoPB` 中的证据，不直接编辑证据源。

建议分为以下卡片或折叠区：

1. 血压摘要。
2. 乳酸。
3. 血培养/病原学。
4. 广谱抗菌药。
5. 晶体液补充。
6. 升压药历史。

`allMedications` 第一版不展示，右栏只展示三类命中用药，避免信息噪声。

### 血压摘要

根据 `info.bloodPressure` 生成 1 小时、3 小时、6 小时窗口摘要：

1. 窗口使用闭区间：
   - 1 小时：`[T0, T0+1h]`
   - 3 小时：`[T0, T0+3h]`
   - 6 小时：`[T0, T0+6h]`
2. 只使用 `selectedMapMmhg > 0` 的血压点参与排序。
3. 按 `selectedMapMmhg` 升序排序，最低值取第一条，最高值取最后一条。
4. MAP 相同时，建议最低值取更早时间，最高值取更晚时间。
5. 每个窗口展示“最低 MAP”和“最高 MAP”两条；无数据时显示空状态。
6. 每条格式按临床常用顺序展示：

```text
${时间}: ${收缩压}/${舒张压}(${平均压}) mmHg
```

示例：

```text
10:32: 82/48(59) mmHg
```

标签写成 `SBP/DBP(MAP)`。

补充展示：

1. `selectedSource` 显示为 IBP/NIBP badge。
2. `selectedMapDerived=true` 时显示“MAP 由 SBP/DBP 计算”提示。
3. 可提供“查看全部血压点”折叠表格，列为时间、来源、SBP、DBP、MAP、是否派生。

### 乳酸

展示 `info.lactate`：

1. 时间：`authTimeIso8601`。
2. 原始结果：`resultStr`。
3. 解析值：`lactateMmolL`，单位 `unit` 或 mmol/L。
4. 是否成功解析：`lactateNumericParsed`。
5. 外部参数编码：`externalParamCode`。
6. 报告 ID：`reportId`。

排序按时间升序。`lactateMmolL >= 4` 可用危险色标记；第一条 `>2` 时可提示“需要复测乳酸”。

### 血培养/病原学

展示 `info.cultureLisItems`：

1. 时间：`timeIso8601`。
2. 项目名：`lisItemName`。
3. 命中关键词：`matchedKeyword`。
4. 报告 ID：`reportId`。

如果同时存在 `h1CultureBeforeAbxIso8601` 和 `h1AbxBroadIso8601`，且血培养时间晚于抗菌药时间，可显示轻量提醒“血培养时间晚于抗菌药”，但第一版不改变完成状态。

### 用药证据

三类用药分别展示：

1. 广谱抗菌药：`info.abxHistory`。
2. 晶体液补充：`info.fluidHistory`。
3. 升压药历史：`info.vasopressorHistory`。

列：

1. 开始时间：`startedAtIso8601`。
2. 药品/医嘱显示名：`medicationDisplayName`。
3. 命中关键词：`matchedKeyword`。
4. 医嘱组 ID：`medicationOrderGroupId`。
5. 执行记录 ID：`medicationExecutionRecordId`。

排序按时间升序。列表为空时显示“未识别到相关记录”，不要显示为接口错误。

## 状态与颜色

建议统一状态：

1. 已完成：绿色。
2. 未触发：灰色。
3. 待处理：蓝色或默认色。
4. 灰化不可编辑：低对比灰。
5. 超时未完成：红色。
6. 自动识别：小标签，不抢主视觉。
7. 人工修改未保存：字段旁显示轻量标记或底部显示统一提示。

红色只用于需要医生处理的失败/超时状态，不用于普通空数据。

## 时间处理

1. 所有接口字段继续使用 ISO8601 字符串。
2. 展示使用本地时区，格式建议 `YYYY-MM-DD HH:mm`；同一日期内的证据列表可简化为 `HH:mm`，但 tooltip 保留完整时间。
3. 保存时使用 `dayjs(value).toISOString()` 或项目既有 ISO8601 工具，保证后端能解析。
4. 当前时间相关状态用页面渲染时的 `dayjs()` 计算。
5. 第一版不设置自动刷新定时器，也不每 60 秒重算状态。状态在页面打开、患者切换、保存成功、点击“刷新”、以及用户编辑 T0/任务字段时重新计算。

## 文案与国际化

建议在 `TEXT` 中补齐固定文案，避免页面内散落中文硬编码。至少包括：

1. 脓毒症与感染性休克集束。
2. 是否需要集束。
3. T0。
4. 不需要集束理由/说明。
5. 1 小时任务、3 小时任务、6 小时任务。
6. 初始乳酸测定、血培养/病原学送检、广谱抗菌药、晶体液补充、升压药、复测乳酸、灌注/容量反应性再评估。
7. 血压摘要、乳酸、血培养、广谱抗菌药、晶体液、升压药历史。
8. 未触发、待开始、超时未完成、未识别到相关记录。
9. 保存、刷新、未保存修改、保存中。

## 验收口径

1. 点击菜单 function_id 51 后能打开新页面。
2. 切换患者时页面自动查询 `/api/therapy/getsepticshockcase`，患者不存在或未选中时不会误显示为“不需要 bundle”。
3. 顶部能展示床号、姓名、入科时间、诊断，并能编辑 `needBundle`、`t0Iso8601`、`noBundleReason`。
4. `needBundle=false` 时 T0 输入灰化，1/3/6 小时任务全部灰化。
5. 1 小时任务未完成时标红逻辑只在超过 `T0+1h` 后出现。
6. 3 小时任务受 1 小时任务 gating 控制；`fluidQualified` 可人工覆盖，`fluidQualified=true` 时才能编辑晶体液补充字段。
7. 6 小时任务受 1 小时任务和补液 gating 控制；`vasopressorQualified`、`relactateQualified` 可人工覆盖，适应证为 true 且超时未完成时标红。
8. `perfusionReassessmentDetails` 能完整展示并作为整体对象保存。
9. 右栏能展示乳酸、血培养、广谱抗菌药、晶体液补充、升压药历史。
10. 右栏不展示 `allMedications`。
11. 血压摘要能按 MAP 在 1/3/6 小时窗口内分别展示最低值和最高值，并按 `SBP/DBP(MAP)` 格式显示。
12. 保存请求只提交用户改动字段对应的 `updateMask.paths`；bool=false 能正确保存。
13. 保存成功后页面使用返回的 `septicShockCase` 刷新，并清空未保存状态。
14. 点击“刷新”会丢弃未保存修改并重新拉取后端自动/人工合并后的 case。
15. 页面不会自动定时刷新；长时间停留时由用户点击“刷新”更新证据和当前时间状态。
16. 人工把适应证从 true 改为 false 后，不清空下游已有完成值；UI 按“未触发”隐藏或灰化，后续改回 true 时恢复展示原值。
17. 人工把适应证从 false 改为 true 后，不自动带入完成时间，必须由医生明确勾选完成并选择完成时间。

## 待决策

暂无新的待决策项。
