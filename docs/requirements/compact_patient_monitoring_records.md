# Compact 报表 patient_monitoring_records 表格绘制需求

## 背景

当前 compact 重症监护记录单已经具备 JFK 模板加载、流式 container、弹性表格分页和 PDF 渲染的基础能力。本轮要继续补齐 `patient_monitoring_records` 数据源，使 `report_compact.pb.txt` 中数据源为 `patient_monitoring_records` 的表格可以按模板配置的观察项顺序渲染。

本文档只整理需求、已确认决策和实现前检查结论，不做 Java/proto/pbtxt 实现。

## 当前上下文

当前工作区已有未暂存改动：

1. `src/main/resources/config/pbtxt/icis_config.pb.txt`
   - 已新增 `monitoring_param_codes`、`col_widths`、`table_id`、`font_size`、`char_spacing`、`h_padding` 输入字段。
   - 已新增 `patient_monitoring_records` JFK 数据源元数据。
   - 输出字段包含 `param_name`、`param_unit`、`hour1` 到 `hour24`。
   - 这些输出字段当前配置为 `val_type: 9`，即 `STRINGS`。

2. `src/main/resources/config/pbtxt/report_compact.pb.txt`
   - 已新增 `table-28`，其 `data_source_meta_id` 为 `patient_monitoring_records`。
   - `table-28` 有 26 列，字段顺序为 `param_name`、`param_unit`、`hour1` 到 `hour24`。
   - `mon_group` 已配置 `table_id: "table-28"`，观察项为：
     - `temperature`
     - `pr`
     - `hr`
     - `respiration_rate`
     - `nibp_s`
     - `nibp_d`
     - `Spo2`
   - 另一个 `mon_group.table_id` 仍为空，后续再绑定其他表格。

3. 测试已随模板和数据源实现更新：
   - `CompactReportTemplateLoaderTests` 已预期 container 中有 3 个 `ac_tables`。
   - `JfkPdfRendererTests` 已加入 synthetic `patient_monitoring_records` 数据，验证渲染器可以消费该表格数据。
   - `CompactReportDataSourceBuilderTests` 覆盖 `table_id`、`font_size`、`char_spacing`、`h_padding` 等表格专属输入。
   - `PatientMonitoringRecordsDataSourceHandlerTests` 覆盖 24 小时窗口、整点过滤、缺失观察项跳过、`paramValue` 回退格式化和重复整点取最新记录。

4. 本轮实现已处理：
   - `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources` 下已新增 `patient_monitoring_records` 对应的 `JfkDataSourceHandler`。
   - `CompactReportDataSourceBuilder` 已针对 `table_id` 补充 `monitoring_param_codes`、`col_widths`、`font_size`、`char_spacing`、`h_padding`。
   - renderer 已扩展表格专属数据源绑定，支持一个模板中多个 `meta_id == "patient_monitoring_records"` 的表格。

## 已确认决策

1. `request.dept_id` 与根据 `pid` 查到的患者 `dept_id` 不一致时，记录 `log.warn`，后续逻辑始终以患者 `dept_id` 为准。
2. 未找到 `balance_stats_shifts` 时，沿用 `MonitoringTimeRangeDataSourceHandler` 的行为，返回 `BALANCE_STATS_SHIFT_NOT_FOUND`。
3. `mon_start_hour` 为空或非法时，回退到 `start_hour`。
4. 某个 `monitoring_param_code` 不存在于 `MonitoringConfig.getMonitoringParams(deptId)` 时，记录 `log.warn`，跳过该观察项，不生成也不绘制这一行。
5. `paramValueStr` 为空但 `paramValue` 存在时，需要解码 `paramValue`，并用 `ValueMetaUtils` 重新格式化。
6. `patient_monitoring_records` 的所有输出字段统一使用 `JfkValPB.strs_val`；`icis_config.pb.txt` 中 `param_unit` 的 `val_type: 9` 注释已统一为 `# STRINGS`。
7. 折行所需的 `font_size`、`char_spacing`、`h_padding` 从对应的 `JfkTablePB` 获取。
8. 一个模板中可以有多个 `meta_id == "patient_monitoring_records"` 的表格。若 `ReportMonGroupPB.mon_group[].table_id` 能找到对应的 `JfkTablePB.id`，则渲染该表格；否则因输入参数不齐，跳过该表格，不渲染。
9. 同一参数同一整点如果实际查出多条有效记录，选择 `is_deleted = false` 且 `modified_at` 最新的一条。
10. 整点过滤严格要求 `minute == 0 && second == 0 && nano == 0`。

## 目标

实现 compact 报表中数据源为 `patient_monitoring_records` 的 JFK 表格绘制流程：

1. 根据 `pid` 定位患者所属 `dept_id`。
2. 根据 `query_start` 和科室观察项班次配置计算观察项查询窗口。
3. 根据 `table_id` 从 compact 模板的 `mon_group` 取出该表格需要展示的观察项编码。
4. 查询 `patient_monitoring_records`，按观察项和 24 个整点小时组织为 26 列数据。
5. 对过长文本按列宽折行，并写入 `JfkValPB.strs_val`。
6. 交给现有 JFK renderer 绘制弹性表格。

## 非目标

本次实现不扩展新的 PDF 布局能力，不修改流式表格分页规则，不实现未绑定 `table_id` 的 `mon_group`，也不实现非整点观察项的渲染。

## 输入与数据结构

### 数据源输入

`patient_monitoring_records` 处理器需要从 `JfkDataSourcePB.input_data` 读取：

1. `pid`: `INT64`
2. `query_start`: ISO8601 字符串，UTC
3. `query_end`: ISO8601 字符串，UTC
4. `monitoring_param_codes`: 字符串数组，来自 `CompactReportTemplatePB.mon_group.param_code`
5. `col_widths`: double 数组，来自 `JfkTablePB.cell_widths`
6. `table_id`: 字符串，来自对应 `JfkTablePB.id`，用于同一模板内区分多个 `patient_monitoring_records` 表格
7. `font_size`: double，来自对应 `JfkTablePB.font_size`
8. `char_spacing`: double，来自对应 `JfkTablePB.char_spacing`
9. `h_padding`: double，来自对应 `JfkTablePB.h_padding`

`query_end` 可作为传入字段保留，但观察项窗口计算按本需求以 `query_start + 24h` 为准，避免 request 中传入范围和班次窗口出现双重口径。

### 数据源输出

`JfkDataSourcePB.output_data` 固定输出 26 个字段：

1. `param_name`: 每行观察项名称
2. `param_unit`: 每行观察项单位
3. `hour1` 到 `hour24`: 从 `monStartUtc` 开始连续 24 个整点的观察值

每个输出字段的 `vals.length` 必须等于有效观察项数量。有效观察项数量是 `monitoring_param_codes` 中能在 `MonitoringConfig.getMonitoringParams(deptId)` 找到配置的参数数量；找不到配置的参数行会被跳过。若列长度不一致，渲染器现有校验会返回错误，但数据源处理器应在构造阶段保证一致。

由于当前元数据把这些字段都配置为 `STRINGS`，输出值必须写入 `JfkValPB.strs_val`。没有换行时也使用单元素 `strs_val`，这样渲染器统一使用 `STRINGS` 的显式多行逻辑。

## 处理流程

### 1. 根据 pid 获取 dept_id

使用现有患者服务能力按 `pid` 获取患者记录，并取 `dept_id`。

处理要求：

1. `pid` 缺失或 `pid <= 0`：返回 `JFK_MISSING_REQUIRED_FIELD` 或 `INVALID_PARAM_VALUE`。
2. 患者不存在：返回 `PATIENT_NOT_FOUND`。
3. 患者存在但 `dept_id` 为空：返回 `DEPT_NOT_FOUND` 或 `INVALID_PARAM_VALUE`，错误信息包含 `pid`。

`CompactReportDataSourceBuilder` 当前也会传入 request 中的 `dept_id`。本需求以“根据 `pid` 查询患者所属 `dept_id`”为准；如果 request `dept_id` 与患者 `dept_id` 不一致，记录 `log.warn`，后续逻辑始终以患者 `dept_id` 为准。

### 2. 计算观察项查询窗口

给定输入 `query_start`：

1. `utcStart = TimeUtils.fromIso8601String(query_start, "UTC")`
2. `utcEnd = utcStart.plusHours(24)`
3. 查询 `balance_stats_shifts`：
   - 条件：`dept_id = patient.dept_id`
   - 条件：`effective_time < utcEnd`
   - 条件：`is_deleted = false`
   - 排序：`effective_time desc`
   - 取第一条
4. 从该班次记录读取 `mon_start_hour`。
   - 如果 `mon_start_hour` 为空、`< 0` 或 `> 23`，回退到 `start_hour`。
   - 如果 `start_hour` 也为空或非法，返回 `INVALID_PARAM_VALUE`，错误信息包含 `dept_id` 和班次记录 id。
5. 计算本地日期的午夜：
   - `utcMiddle = utcStart.plusHours(12)`
   - `localMiddle = TimeUtils.getLocalDateTimeFromUtc(utcMiddle, ConfigProtoService.getConfig().getZoneId())`
   - `localMidnight = localMiddle.toLocalDate().atStartOfDay()`
6. 计算观察项查询窗口：
   - `monStartLocal = localMidnight.plusHours(mon_start_hour)`
   - `monStartUtc = TimeUtils.getUtcFromLocalDateTime(monStartLocal, zoneId)`
   - `monEndUtc = monStartUtc.plusHours(24)`

如果没有找到 `balance_stats_shifts`，沿用 `MonitoringTimeRangeDataSourceHandler` 的行为，返回 `BALANCE_STATS_SHIFT_NOT_FOUND`。

### 3. 根据 table_id 获取 monitoring_param_codes

在 `compactreport` 侧加载 `ReportProperties.jingyi.report.compact.template` 对应的 `CompactReportTemplatePB` 后，需要针对每个 `JfkTablePB` 判断：

```text
table.data_source_meta_id == "patient_monitoring_records"
```

命中后：

1. 取该表格的 `table.id`。
2. 在 `CompactReportTemplatePB.mon_group[]` 中查找 `table_id == table.id` 的 `ReportMonGroupPB`。
3. 将 `ReportMonGroupPB.param_code[]` 作为 `monitoring_param_codes` 输入。

如果找不到匹配 `mon_group`，该表格输入参数不齐，不生成数据源输入，也不渲染该表格。当前模板中 `table-28` 已配置 `mon_group`，另一个空 `table_id` 的 `mon_group` 本期不处理。

### 4. 计算 col_widths

针对 `patient_monitoring_records` 表格：

1. 如果 `JfkTablePB.cell_widths.length == JfkDataSourceMetaPB.output_fields.length`，则传入完整 `col_widths`。
2. 否则传入空数组 `col_widths = []`。

当前 `table-28` 为 26 列，`patient_monitoring_records` 输出字段也是 26 个，因此应传入 26 个列宽。

`col_widths` 保持为 `JfkTablePB.cell_widths` 的原始列宽。折行时在 handler 内计算可用文本宽度：

```text
available_width[i] = max(0, col_widths[i] - 2 * h_padding)
```

折行需要同时使用 `JfkTablePB.cell_widths[i]`、`JfkTablePB.h_padding`、`JfkTablePB.font_size`、`JfkTablePB.char_spacing` 和实际 PDF 字体。实现时由 `CompactReportDataSourceBuilder` 从对应 `JfkTablePB` 提取这些值，作为 `patient_monitoring_records` 数据源输入传给 handler。`icis_config.pb.txt` 的 JFK input 元数据已补充 `table_id`、`font_size`、`char_spacing`、`h_padding`，数据源输入保持自描述。

### 5. 查询 patient_monitoring_records

新增 `PatientMonitoringRecordsDataSourceHandler`，放在：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources
```

建议注入：

1. `JfkDataSourceSupport`
2. `PatientMonitoringRecordRepository`
3. `BalanceStatsShiftRepository`
4. `MonitoringConfig`
5. 如需在 handler 内换行，额外注入 `ReportProperties`、`ResourceLoader` 或从 support 暴露 compact 字体数据

查询记录：

```text
recordRepo.findByPidAndParamCodesAndEffectiveTimeRange(pid, monitoring_param_codes, monStartUtc, monEndUtc)
```

然后过滤非整点记录。这里的“整点”建议定义为：

```text
effective_time.minute == 0
effective_time.second == 0
effective_time.nano == 0
```

注意不应要求 `effective_time.hour == 0`，否则只会保留午夜记录。

记录索引建议：

```text
Map<param_code, Map<hour_index, PatientMonitoringRecord>>
hour_index = Duration.between(monStartUtc, record.effectiveTime).toHours()
```

仅接受 `0 <= hour_index < 24` 的记录。

同一参数同一整点如果实际查出多条有效记录，只考虑 `is_deleted = false` 的记录，并选择 `modified_at` 最新的一条；同时记录 warning，方便后续排查数据异常。

### 6. 获取观察项元数据

使用：

```text
MonitoringConfig.getMonitoringParams(deptId)
```

获得：

```text
Map<String, MonitoringParamPB> paramMap
```

按 `monitoring_param_codes` 的顺序输出行。若某个参数不在 `paramMap` 中，记录 `log.warn`，跳过该参数，不生成该行对应的数据。

每个参数行：

1. `param_name` = `paramMap.get(code).name`
2. `param_unit` = `paramMap.get(code).value_meta.unit`
3. `hourN` = 对应 `param_code` 在 `monStartUtc.plusHours(N - 1)` 的观察值字符串

观察值字符串生成规则：

1. 优先使用 `PatientMonitoringRecord.paramValueStr`。
2. 如果 `paramValueStr` 为空但 `paramValue` 存在，则解码 `paramValue`，并用 `ValueMetaUtils` 根据该参数的 `ValueMetaPB` 重新格式化。
3. 如果两者都不可用，则输出空字符串。

### 7. 长文本折行

目标：如果 `param_val_str` 内容太长，且该列有可用 `col_widths[i]`，且该输出字段类型为 `STRINGS`，则将折行结果写入：

```text
JfkValPB.strs_val
```

建议复用：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/common/JfkPdfUtils.getWrappedLines(...)
```

折行发生在数据源 handler 中。`CompactReportDataSourceBuilder` 负责从对应 `JfkTablePB` 读取 `font_size`、`char_spacing`、`h_padding` 和 `cell_widths` 并传入 handler；handler 加载 compact 字体后调用 `JfkPdfUtils.getWrappedLines(...)`，最终将结果写入 `JfkValPB.strs_val`。

`STRINGS` 字段仍然只认显式多行，不在 renderer 中按宽度自动折行。

### 8. compactreport 数据源输入构造

当前 `CompactReportDataSourceBuilder.buildInputs` 是按 `meta_id` 去重生成一个输入：

```text
Set<String> metaIds = collectDataSourceMetaIds(template)
```

`patient_monitoring_records` 与普通数据源不同，它依赖具体 `table_id`，因此需要扩展构造逻辑：

1. 遍历 `JfkTemplatePB.pages[].tables[]` 和 `containers[].ac_tables[].tbl`。
2. 对每个 `data_source_meta_id == "patient_monitoring_records"` 的表格，查找 `mon_group.table_id == table.id`。
3. 如果找到匹配 `mon_group`，生成一份表格专属的数据源输入，输入中包含 `table_id`、`monitoring_param_codes`、`col_widths`、`font_size`、`char_spacing`、`h_padding`。
4. 如果找不到匹配 `mon_group`，不生成该表格的数据源输入，该表格在渲染阶段跳过。
5. 生成的 `JfkDataSourcePB.meta_id` 保持为 `patient_monitoring_records`，`JfkDataSourcePB.id` 使用表格维度的稳定值，例如 `compact-patient_monitoring_records-${table_id}`。

当前 renderer 通过 `meta_id` 查找数据源，不能区分同一 `meta_id` 的多个表格输入。因此实现时需要同步扩展渲染数据绑定：

1. `JfkRenderData` 同时建立 `dataSourceByMetaId` 和 `dataSourceById`。
2. 表格 cell 解析时，如果 `table.data_source_meta_id == "patient_monitoring_records"`，优先通过约定的表格专属 data source id 查找数据源。
3. 未找到表格专属 data source 时，该 `patient_monitoring_records` 表格应整体跳过，而不是报缺失字段错误。
4. 普通数据源仍保持现有按 `meta_id` 查找的行为，避免影响 `patient_info`、`monitoring_time_range` 等既有模板。

## 错误处理

建议错误处理口径：

1. 必填输入缺失：`JFK_MISSING_REQUIRED_FIELD`，错误信息包含字段名。
2. `query_start` 解析失败：`INVALID_TIME_FORMAT`。
3. 患者不存在：`PATIENT_NOT_FOUND`。
4. 班次配置不存在：`BALANCE_STATS_SHIFT_NOT_FOUND`。
5. `mon_group` 不存在或为空：不生成该表格输入，渲染时跳过该表格，并记录 `log.warn`，信息包含 `table_id`。
6. `monitoring_param_codes` 为空：不生成该表格输入，渲染时跳过该表格，并记录 `log.warn`。
7. `col_widths` 长度不匹配：不阻断，按空列宽处理；如果需要精确折行则记录 warning。
8. 查询无记录：不报错，输出空字符串单元格。
9. 某个参数不在 `MonitoringConfig.getMonitoringParams(deptId)` 中：记录 `log.warn`，跳过该参数行。

## 测试需求

测试目录：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport
```

本轮下一步实现时建议增加以下测试：

1. `CompactReportDataSourceBuilder` 单元测试
   - 模板中存在 `table-28`。
   - 输出 `patient_monitoring_records` 的表格专属输入。
   - 输入中包含 `pid`、`query_start`、`query_end`、`table_id`、`monitoring_param_codes`、`col_widths`、`font_size`、`char_spacing`、`h_padding`。
   - `monitoring_param_codes` 顺序与 `mon_group.param_code` 一致。
   - `col_widths` 长度为 26。
   - 多个 `patient_monitoring_records` 表格均有匹配 `mon_group` 时，生成多份表格专属输入。
   - 某个 `patient_monitoring_records` 表格找不到匹配 `mon_group` 时，不生成该表格输入。

2. `PatientMonitoringRecordsDataSourceHandler` 单元测试
   - 给定 `query_start` 和 `balance_stats_shifts.mon_start_hour`，能算出正确 `monStartUtc`、`monEndUtc`。
   - `mon_start_hour` 为空或非法时回退到 `start_hour`。
   - `request.dept_id` 与患者 `dept_id` 不一致时，以患者 `dept_id` 为准。
   - 只保留整点记录，过滤非整点记录。
   - 按 `monitoring_param_codes` 顺序输出行。
   - 缺失 `MonitoringParamPB` 的参数会被跳过。
   - `param_name`、`param_unit` 来自 `MonitoringConfig.getMonitoringParams(deptId)`。
   - `hour1` 到 `hour24` 对应连续 24 小时的观察值。
   - `paramValueStr` 为空但 `paramValue` 存在时，能解码并重新格式化。
   - 同一参数同一整点多条记录时，选择 `modified_at` 最新的一条。
   - 没有记录的位置输出空字符串。
   - 长文本在 `STRINGS` 字段中输出多行 `strs_val`。

3. compact report 集成级测试
   - 使用 compact 模板加载真实 `table-28`。
   - mock `JfkDataService` 返回 `patient_monitoring_records` 的表格专属输出。
   - 调用 renderer 后 PDF 能生成。
   - 同一模板存在多个 `patient_monitoring_records` 表格时，每张表使用各自 `table_id` 绑定的数据源。
   - 没有匹配 `mon_group` 的 `patient_monitoring_records` 表格会被跳过，不影响其他表格渲染。
   - 重点验证不会因为新增表格导致列长度不一致或分页异常。

4. 回归测试
   - 已有 `MonitoringTimeRangeDataSourceHandlerTests` 不应受影响。
   - 已有 `JfkPdfRendererTests` 继续覆盖 `STRINGS` 多行和弹性表格行高。

## 验收标准

1. compact 报表生成时，`table-28` 能展示 `mon_group.table_id == "table-28"` 配置的观察项。
2. 每个观察项一行，行顺序与 `mon_group.param_code` 完全一致。
3. 每行包含观察项名称、单位和 24 小时整点值。
4. 查询窗口使用观察项班次 `mon_start_hour`，而不是直接使用 `query_start` 到 `query_end`。
5. 非整点 `patient_monitoring_records` 不显示。
6. 长文本可以通过 `strs_val` 多行渲染，并触发表格弹性行高。
7. 多个 `patient_monitoring_records` 表格可以各自按 `table_id` 匹配 `mon_group` 并渲染。
8. 未匹配 `mon_group` 的 `patient_monitoring_records` 表格会被跳过，不阻断整份 compact 报表生成。
9. 所有新增/调整测试通过。

## 实现前检查结论

上述 10 个业务选择已经明确，可以进入实现。实现时需要特别注意两个技术点：

1. 多个同 `meta_id` 表格需要扩展 renderer 的数据源绑定方式，否则当前 `JfkRenderData.dataSourceByMetaId` 只会取第一份 `patient_monitoring_records` 输出。
2. `font_size`、`char_spacing`、`h_padding` 来自 `JfkTablePB`，对应 JFK input 元数据已补齐，由 `CompactReportDataSourceBuilder` 放入 `JfkDataSourcePB.input_data`。
