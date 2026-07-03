# 告警模块接入 getInlinePatientsV2 需求

## 本次范围

本阶段只整理需求，不实现 Java、proto、前端或生成代码。

目标是在现有 `/api/patient/getinlinepatientsv2` 响应中携带告警列表，让右上角小铃铛从患者列表刷新链路中直接获得告警数量和告警明细。第一版告警来源仅包含脓毒症与感染性休克集束告警。

## 背景

`PageLayout` 当前会按 `INLINE_PATIENTS_REFRESH_SECONDS` 周期调用 `getInlinePatientsV2({ deptId })`，并把返回的 `inIcu`、`pendingAdmission`、`pendingDischarged` 写入 `patient` store。右上角 `PageHeaderRight` 小铃铛目前只显示静态 `0`，没有接入真实告警数据。

脓毒症与感染性休克集束模块已有 `AlarmPB`、`AlarmTypePB`、`SepsisAndSepticShockBundleService.buildSepticShockCases` 和 `SepsisAndSepticShockBundleService.buildSepticShockAlarms`。本需求只把这些能力接入在院患者列表接口和右上角告警入口。

## 相关文件

后端：

```text
jingyi_icis_engine2/src/main/proto/icis_web_api.proto
jingyi_icis_engine2/src/main/proto/config/icis_alarm.proto
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/patients/PatientService.java
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/therapies/SepsisAndSepticShockBundleService.java
```

前端：

```text
jingyi_icis_frontend/src/api/WebApi.ts
jingyi_icis_frontend/src/store/slices/PatientSlice.ts
jingyi_icis_frontend/src/components/PageLayout/components/PageHeaderRight/index.tsx
jingyi_icis_frontend/src/components/PageLayout/components/PageHeaderRight/index.module.scss
jingyi_icis_frontend/src/pages/home/tabs/patients/components/InIcuPatientsPanel/index.tsx
```

## 已确认需求

1. `/api/patient/getinlinepatientsv2` 是告警列表的承载接口，不新增单独的右上角告警查询接口。
2. 告警只根据 `in_icu.basics` 中的在院患者 `pid` 计算，不包含待入科、待出科或已出科患者。
3. `SepsisAndSepticShockBundleService.isSepticShockAlarmEnabled()` 为 `true` 时才返回脓毒症与感染性休克集束告警；为 `false` 时不返回告警。
4. 小铃铛数字显示告警数量。数量为 `0` 时使用普通色；数量大于 `0` 时使用红色。
5. 点击小铃铛展示告警明细，第一版展示为简洁 Popover/List/Table。
6. 点击单条告警且 `pid` 不为空时，前端切换 store 中的 `curPatient`，并打开 `SepticShockBundle` 页面。
7. `PatientService` 不直接获取完整 `diagnosisConfig`；在 `SepsisAndSepticShockBundleService` 增加 `isSepticShockAlarmEnabled()`，由 `PatientService` 调用。
8. `enable_alarm=false` 时后端不写入 `alarm` 字段，前端统一用 `action.payload.alarm || []` 兜底。
9. 小铃铛为 `0` 时不隐藏数字角标，仍显示 `0`，但使用普通色。
10. `PatientTablePB.basics` 的床号排序规则扩展到所有调用点，不只限于 `inIcu.basics`。
11. 点击告警时，如果 `pid` 有效但当前 `state.patient.inIcu.basics` 中找不到对应患者，前端不构造残缺 `PatientBasicsPB`，不切换 `curPatient`，提示“患者列表已刷新，请稍后重试”或静默不跳转。
12. 点击告警时，如果当前用户没有 function 51 权限或菜单中不存在 `SepticShockBundle`，仍调用 `openTab(51)` 并复用现有菜单/权限逻辑；如果现有逻辑无法打开，则展示轻量提示“无权限打开感染性休克集束页面”。
13. 点击告警打开 `SepticShockBundle` 前，第一版不在告警入口增加全局二次确认，保持和其他 `openTab` 行为一致；若某个业务页面已有未保存拦截，由该页面自己的机制处理。
14. 第一版前端告警列表 key 使用 `${alarmType}-${pid}-${index}`；点击行为只按 `pid` 切换患者并打开对应页面。后续新增多种告警目标页面时，再建立 `AlarmTypePB -> tab id` 显式映射。
15. 小铃铛数量直接显示 `alarms.length`，不做 `99+` 封顶。后续如果告警类型增加导致数量过大，再补封顶规则。
16. 小铃铛 `0` 状态使用蓝灰普通色，并在 `PageHeaderRight` CSS module 中独立成 normal class。后续如果设计系统有统一 token，再替换为 token。
17. 告警列表床号展示床位配置后的 display bed number，不展示原始 `his_bed_number`。在 `AlarmPB` 中新增 `display_bed_number` 字段，避免前端做床位映射。
18. 告警计算失败时第一版只记录后端 `log.error`，响应仍返回患者列表且告警为空，不新增局部错误字段。
19. 告警排序改用 `display_bed_number`，如果全部 `display_bed_number` 都可转数字则按数字排序，否则按字符串排序。
20. `display_bed_number` 本需求只加到 `AlarmPB`，不扩展到其他非告警接口或患者基础结构。
21. 第一版告警 Popover 不按告警类型做视觉区分，只展示告警内容。
22. 第一版告警 Popover 不增加手动刷新按钮，告警刷新跟随 `getInlinePatientsV2` 现有轮询。
23. 第一版告警 Popover 不展示更新时间。
24. 第一版告警 Popover 不展示 `his_bed_number` 辅助信息；`his_bed_number` 仅保留在数据中用于追踪和排查。
25. `display_bed_number` 和 `his_bed_number` 都为空时仍显示该告警，床号展示 `-`。
26. 后端第一版按 `buildSepticShockAlarms` 的输出返回，不额外做通用去重。当前告警来源只有一种，且病例按 pid 生成；后续增加多类型告警时再定义去重键。
27. 告警文案过长时第一版不加单独 tooltip 或展开控件，使用紧凑列表中的自然换行或 CSS ellipsis。
28. Popover 打开期间轮询刷新导致告警消失时，第一版不主动关闭 Popover，内容自动更新为空状态。
29. 点击告警打开 `SepticShockBundle` 后，第一版不传递高亮信息，只切换患者并打开页面；`SepticShockBundle` 页面按当前患者重新查询病例。
30. 移动端第一版继续使用 Popover，并通过宽度和最大高度限制适配小屏；若后续移动端体验不足，再单独做 Drawer 方案。
31. 告警 Popover 标题显示“告警”，右侧显示总数。
32. 告警条目整行可点击，并通过 hover 背景和鼠标样式提示可交互，不额外增加按钮或链接。
33. 告警 Popover 第一版支持基础键盘可访问性：铃铛按钮可 focus，Enter/Space 可打开 Popover；可点击告警条目也可通过键盘触发。
34. 告警列表第一版不做复杂固定列宽配置，采用紧凑列表布局；如果实现为表格，床号和姓名使用固定短宽，告警内容自适应。
35. 告警空状态第一版只展示“暂无告警”，不增加说明文案。
36. 告警 Popover 第一版不显示科室名称；右上角告警跟随当前科室的 `getInlinePatientsV2` 数据刷新。
37. 点击告警切换患者第一版不增加二次确认，保持工作台快捷跳转效率；未保存修改仍由当前页面自己的机制处理。
38. 告警数量为 `0` 时点击小铃铛仍打开 Popover，并展示“暂无告警”。
39. 告警 Popover 不记住上次打开/关闭状态，状态只由当前点击和外部关闭控制，不持久化。
40. 告警 Popover 打开后再次点击小铃铛时关闭，按常规 Popover 行为切换开关。
41. 告警条目被键盘触发后，行为和鼠标点击一致；成功触发跳转后关闭 Popover。
42. 第一版告警 Popover 不展示患者住院号，只展示床号、姓名、告警内容。
43. 本需求不新增告警确认、已读、忽略、静音、分级、声音或持久化状态。

## 后端需求

### Proto

`icis_web_api.proto` 当前已经 import `config/icis_alarm.proto`。在 `GetInlinePatientsV2Resp` 中新增字段：

```proto
message GetInlinePatientsV2Resp {
    shared.ReturnCode rt = 1;
    PatientTablePB in_icu = 3;
    PatientTablePB pending_admission = 4;
    PatientTablePB pending_discharged = 5;
    repeated config.AlarmPB alarm = 6;
}
```

说明：

1. 用户侧需求写作 `repeated AlarmPB alarm = 6`；由于当前 `icis_web_api.proto` 使用 `config.*` 引用导入类型，实际实现建议写为 `repeated config.AlarmPB alarm = 6`。
2. 前端 JSON 字段名保持 `alarm`，对应 TypeScript 建议为 `alarm?: AlarmPB[]` 或 `alarm: AlarmPB[]`。
3. 后端 proto 改动后需要同步重新生成 Java proto 类；前端 `WebApi.ts` 也要补齐 `GetInlinePatientsV2Resp.alarm` 字段。

`config/icis_alarm.proto` 中的 `AlarmPB` 需要新增展示床号字段：

```proto
message AlarmPB {
    int64 pid = 1;
    string his_bed_number = 2;
    string patient_name = 3;
    AlarmTypePB alarm_type = 4;
    string alarm_message = 5;
    string display_bed_number = 6;
}
```

说明：

1. `his_bed_number` 保留为原始 HIS 床号，用于后端追踪。
2. `display_bed_number` 是前端展示字段，后端根据床位配置填充。
3. 若床位配置缺失或 display bed number 为空，`display_bed_number` fallback 为 `his_bed_number`，避免前端展示空床号。

### PatientService.getInlinePatientsV2

在 `PatientService.getInlinePatientsV2` 构造完 `inIcu`、`pendingAdmission`、`pendingDischarged` 后，返回前追加告警逻辑：

1. 从 `builder.getInIcu().getBasicsList()` 提取在院患者 `pid`：

```text
List<Long> pids = builder.getInIcu().getBasicsList()
    .stream()
    .map(PatientBasicsPB::getId)
    .map(Long::valueOf)
    .toList();
```

2. 当 `pids` 为空时，不调用集束服务，直接返回原响应。
3. 调用 `SepsisAndSepticShockBundleService.isSepticShockAlarmEnabled()` 判断是否启用告警。
4. 如果 `isSepticShockAlarmEnabled() == false`，不调用 `buildSepticShockCases`，不向响应中写入 `alarm` 字段；前端按空数组处理。
5. 如果 `isSepticShockAlarmEnabled() == true`，调用 `buildSepticShockCases(pids)` 得到 `List<SepsisAndSepticShockCasePB>`。
6. 对 cases 调用 `buildSepticShockAlarms(cases)` 得到 `List<AlarmPB>` 并写入 `GetInlinePatientsV2Resp.alarm`。
7. 接入方式应避免把治疗模块的复杂逻辑写入 `PatientService`，`PatientService` 只负责提取在院 pid、判断开关、调用服务、写响应。

建议实现形态：

```text
PatientService
  -> build inline patient tables
  -> collect inIcu pids
  -> if sepsisAndSepticShockBundleService.isSepticShockAlarmEnabled():
       cases = sepsisAndSepticShockBundleService.buildSepticShockCases(pids)
       alarms = sepsisAndSepticShockBundleService.buildSepticShockAlarms(cases)
       builder.addAllAlarm(alarms)
  -> builder.setRt(OK).build()
```

### 告警排序

`SepsisAndSepticShockBundleService.buildSepticShockAlarms` 返回的 `List<AlarmPB>` 需要按 `AlarmPB.display_bed_number` 升序排序。

排序规则：

1. 对本次返回的所有 alarm，先判断 `display_bed_number` 是否都能转换为数字。
2. 如果全部能转换为数字，按数字大小升序排序。例如 `2`、`10`、`12` 排为 `2, 10, 12`。
3. 如果任一 `display_bed_number` 不能转换为数字，整体按字符串升序排序。例如 `A01`、`B02`、`床10` 按字符串排序。
4. 转数字前应先 `trim`；空字符串、null、包含非数字字符的床号都视为不能转换为数字。
5. 若排序主键相同，建议用 `pid` 升序作为稳定兜底排序。

可复用或抽取 `PatientService` 中已有的床号解析思路：`parseBedNumberStr(String)` 使用 `BigDecimal` 判断是否可转数字。

### 告警床号展示

`buildSepticShockAlarms` 构造 `AlarmPB` 时需要同时填充：

1. `his_bed_number`：`PatientRecord.hisBedNumber` 原始床号。
2. `display_bed_number`：优先使用床位配置中的 display bed number；找不到床位配置或 display bed number 为空时 fallback 为 `his_bed_number`。

实现要求：

1. 根据患者 `deptId` 和 `hisBedNumber` 复用现有床位配置读取能力，避免前端自行映射床位。
2. 告警排序按 `display_bed_number` 执行；前端展示也使用 `display_bed_number`。
3. 若同一批告警跨多个科室，后端按患者 `deptId` 分别取床位配置。当前 `getinlinepatientsv2` 按单个 `deptId` 查询，正常情况下不会跨科室。

### PatientTablePB.basics 排序

`PatientService.addPatientTableRows` 当前已经对 `row` 按床号实现了“全部可转数字则数字排序，否则字符串排序”的规则。`addPatientBasics` 当前只按 `PatientBasicsPB.bedNumberStr` 字符串排序。

本需求要求所有 `PatientTablePB.basics` 也按同一规则排序，排序字段为 `PatientBasicsPB.bed_number_str`。

要求：

1. `addPatientBasics` 的排序统一改为“全部可转数字则数字排序，否则字符串排序”。
2. 该规则覆盖 `inIcu`、`pendingAdmission`、`pendingDischarged`、`dischargedPatients` 等所有经 `addPatientBasics` 生成的 `PatientTablePB.basics`。
3. `row` 与 `basics` 的排序口径应保持一致，避免下拉表格显示顺序和选中患者基础列表顺序不一致。

### 依赖注入

`PatientService` 需要能调用 `SepsisAndSepticShockBundleService`。建议通过构造器注入，保持现有 service 风格，不在方法内手动获取 Spring bean。

`PatientService` 不直接获取完整 `diagnosisConfig`。`SepsisAndSepticShockBundleService` 需要新增只读方法：

```text
boolean isSepticShockAlarmEnabled()
```

该方法内部读取 `diagnosisConfig.enable_alarm` 并返回布尔值。这样 `PatientService` 只关心告警是否开启，不需要知道乳酸、诊断关键词等配置细节。

### 错误处理

1. `getinlinepatientsv2` 的主功能是返回患者列表。告警计算异常不应导致整个接口失败。
2. 如果告警计算失败，建议记录 `log.error`，响应仍返回 `rt = OK` 和患者列表，告警为空。
3. JSON 解析失败等既有错误处理保持不变。
4. 第一版不对告警列表做通用去重，直接返回 `buildSepticShockAlarms` 的输出。

## 前端需求

### 类型和 store

`WebApi.ts` 已有 `AlarmTypePB`、`AlarmPB` 类型，但 `GetInlinePatientsV2Resp` 需要补 `alarm` 字段。

`AlarmPB` 类型需要同步新增 `displayBedNumber?: string`：

```ts
export interface AlarmPB {
    pid?: number;
    hisBedNumber?: string;
    patientName?: string;
    alarmType?: AlarmTypePB;
    alarmMessage?: string;
    displayBedNumber?: string;
}
```

建议：

```ts
export interface GetInlinePatientsV2Resp {
    rt: StatusPB;
    inIcu: PatientTablePB;
    pendingAdmission: PatientTablePB;
    pendingDischarged: PatientTablePB;
    alarm?: AlarmPB[];
}
```

`PatientSlice` 新增告警状态：

```ts
alarms: AlarmPB[];
```

`getInlinePatientsV2.fulfilled` 中写入：

```ts
state.alarms = action.payload.alarm || [];
```

要求：

1. 初始值为空数组。
2. 接口未返回 `alarm` 字段时，前端按空数组处理。
3. 告警刷新节奏跟随 `getInlinePatientsV2` 现有轮询，不新增额外 timer。

### PageHeaderRight 小铃铛

`PageHeaderRight` 从 Redux 读取：

```ts
const alarms = useSelector((state: RootState) => state.patient.alarms);
```

渲染规则：

1. `alarmCount = alarms.length`。
2. `pagelayoutHeaderRNoticeCount` 的文本从静态 `0` 改为 `alarmCount`。
3. `alarmCount === 0` 时使用普通色。
4. `alarmCount > 0` 时使用红色。
5. 数字直接显示 `alarms.length`，不做 `99+` 封顶。
6. 小铃铛区域需要 `cursor: pointer`，并支持点击打开告警 Popover。

建议样式：

```text
pagelayoutHeaderRNoticeCount
pagelayoutHeaderRNoticeCountActive
pagelayoutHeaderRNoticeCountNormal
pagelayoutHeaderRNoticePopover
pagelayoutHeaderRNoticeList
pagelayoutHeaderRNoticeItem
```

普通色使用蓝灰，并独立成 normal class；红色沿用当前 `#DF3826` 或抽成 active class。后续如果设计系统有统一 token，再把 normal/active class 替换为 token。

### 告警展示

点击小铃铛后展示告警明细。可以参考 `InIcuPatientsPanel` 的 Popover + 内容组件模式，但告警展示应更简洁。

建议新增内部组件：

```text
jingyi_icis_frontend/src/components/PageLayout/components/PageHeaderRight/components/AlarmPopoverContent
```

也可以先在 `PageHeaderRight/index.tsx` 内部实现一个小组件，后续再拆分。

展示内容建议：

1. 标题：`告警`，右侧显示总数。总数应与铃铛数字一致。
2. 空状态：`暂无告警`，不增加额外说明文案。
3. 有数据时按后端返回顺序展示，不强制前端再次排序。
4. 每条告警展示：
   - 床号：`alarm.displayBedNumber || '-'`
   - 姓名：`alarm.patientName || '-'`
   - 内容：`alarm.alarmMessage || '-'`
5. 有 `pid` 的告警条目整行可点击，并使用可点击样式，例如 hover 背景、`cursor: pointer`。
6. 布局使用紧凑列表或小表格，宽度建议 `320px - 420px`，最大高度建议 `360px`，超出滚动。
   - 第一版不做复杂固定列宽配置。
   - 如果实现为表格，床号和姓名使用固定短宽，告警内容自适应。
7. 不在列表中展示内部 enum 值；如需展示类型，前端映射为中文，如 `脓毒症与感染性休克`。
8. 不展示更新时间。
9. 不展示 `hisBedNumber` 辅助信息。
10. `displayBedNumber` 为空时仍显示告警，床号展示 `-`。
11. 告警文案过长时不增加单独 tooltip 或展开控件，使用自然换行或 CSS ellipsis；当前固定文案较短，不需要额外交互。
12. 移动端仍使用 Popover，通过宽度、最大高度和滚动控制适配小屏，不在第一版切换为 Drawer。
13. 不展示科室名称。

交互要求：

1. 点击铃铛打开/关闭 Popover。
   - 即使 `alarmCount === 0`，点击小铃铛仍打开 Popover，并展示“暂无告警”。
   - Popover 打开后再次点击小铃铛时关闭。
   - 不记住上次打开/关闭状态，不做持久化。
2. 点击 Popover 外部关闭。
3. 数据刷新后 Popover 内容自动更新。
4. Popover 打开期间轮询刷新导致告警消失时，不主动关闭 Popover，内容更新为空状态。
5. 点击单条告警且 `alarm.pid` 存在且大于 `0` 时：
   - 从 `state.patient.inIcu.basics` 中查找 `id === alarm.pid` 的 `PatientBasicsPB`。
   - 找到后 dispatch `setCurPatient(patient)`。
   - dispatch `openTab(51)`，打开或激活 `SepticShockBundle` 页面。
   - 成功触发后关闭告警 Popover。
6. 打开 `SepticShockBundle` 时不传递来源告警高亮信息；页面按当前患者重新查询病例。
7. `openTab(51)` 复用现有 tab 打开逻辑；如果页面已打开，则激活已有 tab。
8. 如果 `alarm.pid` 存在且大于 `0`，但 `state.patient.inIcu.basics` 中找不到对应患者：
   - 不构造残缺的 `PatientBasicsPB`。
   - 不 dispatch `setCurPatient`。
   - 不 dispatch `openTab(51)`。
   - 允许展示轻量提示“患者列表已刷新，请稍后重试”，也允许静默不跳转；推荐展示轻量提示，便于用户理解点击无响应的原因。
9. 如果当前用户没有 function 51 权限或菜单中不存在 `SepticShockBundle`：
   - 告警模块仍调用 `openTab(51)`，复用现有菜单/权限逻辑。
   - 如果现有逻辑无法打开页面，展示轻量提示“无权限打开感染性休克集束页面”。
   - 告警模块不重复实现一套权限判断。
10. 点击告警前不增加全局未保存修改二次确认，也不增加告警跳转二次确认，保持和其他 `openTab` 行为一致。若当前业务页面已有未保存拦截，由该页面自己的机制处理。
11. 前端告警列表 key 第一版使用 `${alarmType}-${pid}-${index}`。点击行为只按 `pid` 切换患者并打开 `SepticShockBundle`；未来新增多种告警目标页面时，再维护 `AlarmTypePB -> tab id` 显式映射。
12. `alarm.pid` 为空或小于等于 `0` 时，该条告警不可点击，仅展示内容。
13. 基础键盘可访问性：
   - 铃铛按钮可 focus。
   - focus 在铃铛按钮上时，Enter/Space 可打开 Popover。
   - 可点击告警条目可 focus，并可通过 Enter/Space 触发与点击相同的行为。
   - 告警条目被键盘触发后，成功触发跳转时关闭 Popover。

## 验收标准

### 后端

1. `GetInlinePatientsV2Resp` proto 新增 `repeated config.AlarmPB alarm = 6`，Java 生成代码可正常编译。
2. `SepsisAndSepticShockBundleService` 提供 `isSepticShockAlarmEnabled()`，`PatientService` 通过该方法判断是否计算告警。
3. `getInlinePatientsV2` 在 `enable_alarm=false` 时不写入 `alarm` 字段。
4. `getInlinePatientsV2` 在 `enable_alarm=true` 且存在 `need_bundle=true` 的在院病例时返回对应 `AlarmPB`。
5. 告警只来自 `in_icu.basics` 的 pid，不包含待入科、待出科、已出科患者。
6. `buildSepticShockAlarms` 按 `display_bed_number` 完成数字/字符串排序。
7. `AlarmPB` 包含 `display_bed_number`，并按床位配置填充展示床号；床位配置缺失时 fallback 为 `his_bed_number`。
8. 所有经 `addPatientBasics` 生成的 `PatientTablePB.basics` 按 `bed_number_str` 完成同样的数字/字符串排序。
9. 告警计算异常不影响患者列表主响应，不新增响应局部错误字段。

### 前端

1. `GetInlinePatientsV2Resp` 类型包含 `alarm`，`PatientSlice` 保存 `state.patient.alarms`。
2. 右上角铃铛数字等于 `state.patient.alarms.length`，不做 `99+` 封顶。
3. 数量为 `0` 时计数样式为蓝灰普通色；数量大于 `0` 时为红色。
4. 点击铃铛能展示告警列表。
5. 无告警时展示空状态，文案只显示“暂无告警”。
6. 有告警时展示 `displayBedNumber`、姓名、告警内容，布局紧凑且不遮挡右上角用户菜单。
7. 点击 `pid` 有效且能在 `inIcu.basics` 中找到患者的告警后，前端会 dispatch `setCurPatient(patient)` 并 dispatch `openTab(51)`。
8. `alarm.pid` 有效但在 `inIcu.basics` 中找不到患者时，前端不切换患者、不打开页面，并提示“患者列表已刷新，请稍后重试”或静默不跳转。
9. 当前用户没有 function 51 权限或菜单中不存在 `SepticShockBundle` 时，告警模块仍调用 `openTab(51)`；如果页面无法打开，展示“无权限打开感染性休克集束页面”。
10. 点击告警前不增加全局未保存修改二次确认，也不增加告警跳转二次确认。
11. 告警列表 key 使用 `${alarmType}-${pid}-${index}`。
12. `alarm.pid` 无效时告警条目不可点击。
13. 告警 Popover 不按告警类型做视觉区分，不展示类型分色。
14. 告警 Popover 不提供手动刷新按钮。
15. 告警 Popover 不展示更新时间。
16. 告警 Popover 不展示 `hisBedNumber` 辅助信息。
17. 告警床号为空时仍显示该告警，床号展示 `-`。
18. 告警文案过长时不提供单独 tooltip 或展开控件，使用自然换行或 CSS ellipsis。
19. 轮询刷新导致告警消失时，已打开的 Popover 不主动关闭，而是展示空状态。
20. 点击告警打开 `SepticShockBundle` 后不高亮来源告警。
21. 移动端第一版仍使用 Popover，不切换 Drawer。
22. 告警 Popover 标题显示“告警”和总数。
23. 告警条目整行可点击，不额外增加按钮或链接。
24. 铃铛按钮和可点击告警条目支持基础键盘访问，Enter/Space 可触发对应操作。
25. 告警列表采用紧凑列表布局；若使用表格，床号和姓名固定短宽，告警内容自适应。
26. 告警 Popover 不展示科室名称。
27. 告警数量为 `0` 时点击小铃铛仍打开 Popover，并展示“暂无告警”。
28. 告警 Popover 不记住上次打开/关闭状态。
29. 告警 Popover 打开后再次点击小铃铛时关闭。
30. 告警条目被键盘触发并成功跳转后关闭 Popover。
31. 告警 Popover 不展示患者住院号。
32. 现有第三方入口按钮、分割线、用户头像下拉和修改密码/退出功能不受影响。

## 建议测试

后端：

1. proto 生成与 Java 编译测试。
2. `PatientService.getInlinePatientsV2` 单元测试或集成测试：
   - `enable_alarm=false` 不调用 `buildSepticShockCases`，且不写入 `alarm` 字段。
   - `enable_alarm=true` 且在院患者命中集束病例时返回告警。
   - 待入科或待出科患者命中时不返回告警。
   - 告警计算抛异常时，接口仍返回患者列表，告警为空，并记录 `log.error`。
3. 告警排序测试使用 `display_bed_number`：
   - `["2", "10", "1"]` 按 `1, 2, 10`。
   - `["A2", "A10", "A1"]` 按字符串排序。
   - `["2", "A1"]` 整体按字符串排序。
   - 空床号参与时触发字符串排序。
4. `display_bed_number` 填充测试：
   - 床位配置存在时返回 display bed number。
   - 床位配置缺失或 display bed number 为空时 fallback 为 `his_bed_number`。

前端：

1. `PatientSlice` reducer 测试或手工验证 `alarm` 写入 `state.patient.alarms`。
2. `PageHeaderRight` 手工验证：
   - `alarms=[]` 显示 `0` 和普通色。
   - `alarms.length > 0` 显示正确数量和红色。
   - `alarms.length > 99` 时仍显示真实数量，不显示 `99+`。
   - 点击小铃铛展示列表。
   - `alarms=[]` 时点击小铃铛仍打开 Popover，并展示“暂无告警”。
   - Popover 打开后再次点击小铃铛时关闭。
   - 关闭后再次打开不依赖上次状态持久化。
   - Popover 标题显示“告警”和总数，且总数与铃铛数字一致。
   - 列表为空时显示空状态，且只有“暂无告警”文案。
   - Popover 不展示科室名称。
   - 告警列表床号优先展示 `displayBedNumber`。
   - `displayBedNumber` 为空时仍显示告警，床号为 `-`。
   - 告警列表不展示更新时间、`hisBedNumber` 或类型分色。
   - 告警文案过长时不出现额外 tooltip/展开控件，文本按自然换行或 ellipsis 处理。
   - Popover 打开期间轮询刷新到空告警时，Popover 不主动关闭并展示空状态。
   - 点击有效告警后切换当前患者并打开 `SepticShockBundle` 页面。
   - 告警条目整行可点击，无需额外按钮。
   - 键盘 focus 到铃铛按钮时，Enter/Space 可打开 Popover；focus 到可点击告警条目时，Enter/Space 可触发打开页面。
   - 键盘触发告警条目并成功跳转后关闭 Popover。
   - 点击告警切换患者不出现二次确认。
   - 告警列表不展示患者住院号。
   - 打开 `SepticShockBundle` 后不传递来源告警高亮信息。
   - 点击 `pid` 有效但找不到患者的告警时，不切换患者、不打开页面。
   - 模拟无 function 51 权限或菜单缺失时，点击告警不引入新的权限判断分支，并能给出轻量提示。
3. 小屏和普通桌面宽度下检查 Popover 不溢出、不遮挡用户下拉主要操作；移动端第一版仍使用 Popover，不切换 Drawer。
4. 告警列表布局检查：
   - 紧凑列表布局下文案不与床号、姓名重叠。
   - 如果实现为表格，床号和姓名固定短宽，告警内容自适应。

## 待决策

当前需求已收敛，暂无阻塞实现的待决策项。

后续如果新增更多告警类型、已读/确认状态、移动端专用体验或多科室并行展示，再另行补充需求。
