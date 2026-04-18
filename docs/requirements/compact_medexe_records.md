# 精简重症监护记录单 medexe_records 表格绘制需求说明

更新时间：2026-04-18

## 背景

精简重症监护记录单当前已经有执行用药时间轴数据源 `medexe_time_range`，用于输出：

- `med_order_txt`：用药医嘱
- `route_txt`：用药途径
- `acc_ml`：累计量
- `hour1` 至 `hour24`：24 小时时间列

本次目标是新增执行用药明细数据源 `medexe_records`，并在 `report_compact.pb.txt` 中新增一组执行用药表格：

- `table-235`：分段标题，标题文本为 `静脉药物（ml）`
- `table-236`：执行用药明细表，数据源为 `medexe_records`

`table-236` 与其上方时间轴 `table-234` 的关系，应类似出入量区域中明细表与时间轴表的关系：上方时间轴提供固定 24 小时列，明细表按同样列结构输出每条执行用药在对应小时内的用量。

## 当前代码现状

本地代码中已经存在以下 proto 结构：

```proto
message ReportMedExeTablePB {
    string table_id = 1;
    int32 intake_type_id = 2;
}
```

```proto
message CompactReportTemplatePB {
    JfkTemplatePB template = 1;
    LogoMetaPB logo = 2;
    repeated ReportMonGroupPB mon_group = 3;
    string bga_table_id = 4;
    repeated ReportMonGroupPB balance_group = 5;
    repeated ReportMedExeTablePB med_exe_tables = 6;
}
```

因此本需求中的 proto 改动在当前工作区看起来已经完成。后续实现时只需要确认生成代码是否已更新，并补齐 `report_compact.pb.txt`、`icis_config.pb.txt`、数据源 handler、table-scoped 输入构造和测试。

当前本地 `src/main/resources/config/pbtxt/report_compact.pb.txt` 中没有搜到 `table-331`、`table-332`、`table-334`。已确认改为使用当前执行用药时间轴：

- `table-233`：标题 `执行用药`
- `table-234`：数据源 `medexe_time_range`

并在 `table-234` 后追加：

- `table-235`：标题 `静脉药物（ml）`
- `table-236`：数据源 `medexe_records`

## 目标

新增 compact 报表数据源 `medexe_records`。

实现范围包括：

- 在 `src/main/resources/config/pbtxt/icis_config.pb.txt` 新增 `medexe_records` 数据源元数据。
- 在 `src/main/resources/config/pbtxt/report_compact.pb.txt` 新增 `table-235` 和 `table-236`。
- 在 `report_compact.pb.txt` 追加配置：

```pbtxt
med_exe_tables {
  table_id: "table-236"
  intake_type_id: 1
}
```

- 在 `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources` 下新增 `MedexeRecordsDataSourceHandler`。
- 在 `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport` 中把 `medexe_records` 作为 table-scoped 数据源处理。
- 在 `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/JfkDataSourceIds.java` 中增加 `MEDEXE_RECORDS = "medexe_records"`，并纳入 `isCompactTableScoped(...)`。
- 补充单元测试和必要的模板加载测试，避免测试绑定生产模板的具体行数或表格数量。

## 已确认决策

1. 表号使用 `table-235/table-236`，不再使用 `table-335/table-336`。
2. 当前 `table-233/table-234` 视为执行用药时间轴，在其后追加 `table-235/table-236`。
3. `table-236` 的列宽与上方执行用药时间轴完全一致：`129.5, 50, 25 * 25`。
4. `medexe_records` 的实际时间窗与 `medexe_time_range` 完全一致，使用 `balance_stats_shifts.start_hour`；`query_end` 只保留输入，不参与时间窗计算。
5. 业务保证一个医嘱分组不会跨 route，因此一行按 `medication_order_group.id` 聚合。
6. 判断连续/非连续时参考 `MedMonitoringService`：当 `medication_execution_records.is_continuous` 非空时优先使用执行记录值，否则使用 `administration_routes.is_continuous`。
7. 如果 `medication_execution_record_stats` 缺失，需要在报表生成时临时调用 `MedMonitoringService.calcFluidIntakeImpl(...)` 计算。
8. 非连续用药同一行同一小时内如果有多个 action，取最早 action 的本地 `HH:mm`；如果多个执行记录同一小时合并，仍取最早 action。
9. 没有任何匹配 `intake_type_id` 的执行用药记录时，`medexe_records` 返回 0 行，数据源为 `medexe_records` 的明细表（如 `table-236`、`table-238`）不渲染。
10. `consumed_ml` 格式化规则：整数不带 `.0`，非整数保留 1 位小数。
11. `consumed_ml` 小数位数放入 `application.properties`，建议配置名为 `jingyi.report.compact.medication-ml-decimal-places=1`。
12. `intake_type_id = 1` 确认对应 `静脉药物（ml）`，实现时不校验 `intake_types.id = 1` 的名称，只按 ID 过滤。
13. `table-235/table-236` 的插入位置在 `table-234` 之后。
14. 未来可能有多组 `med_exe_tables`，每组都需要一对标题表和明细表。
15. `medexe_records` 不在报表生成过程中持久化统计记录；缺失统计只做内存计算并用于本次报表输出。
16. 如果复用现有用药计算逻辑需要 `accountId`，使用当前登录用户；但不因报表生成写入 `medication_execution_record_stats`。
17. “统计缺失”触发临时计算的粒度为：某个 `exe_record_id` 在窗口内完全没有 `medication_execution_record_stats` 时才计算；不是某个小时缺值就补算该小时。
18. 临时计算出的 `MedicationExecutionRecordStat` 不持久化到 `medication_execution_record_stats`。
19. 多组 `med_exe_tables` 时，接受标题表仍由模板静态配置；`ReportMedExeTablePB` 暂不新增标题文本字段。
20. 0 行输出时仍返回 `med_order_txt/route_txt/acc_ml/hour1..hour24` 这些输出字段，但每个字段的 `vals` 长度为 0。
21. `jingyi.report.compact.medication-ml-decimal-places` 作为最终配置名，默认值为 `1`；实现时加入 `application.properties` 和 `ReportProperties.Compact`。
22. `consumed_ml` 非整数保留 1 位小数时使用四舍五入。
23. 业务保证一个 `medication_order_group.id` 不会跨 route；如果实际遇到多个有效 route code，`log.warn` 后使用第一条有效 route code。
24. 多组 `med_exe_tables` 时，实现只负责 `medexe_records` 数据，不自动生成、匹配或修改标题表，标题由模板静态配置承担。
25. `medexe_time_range` 和 `medexe_records` 的科室选择规则保持一致：输入里有有效 `pid` 时，优先使用患者实际 `dept_id`；没有有效 `pid` 时，再回退到输入 `dept_id`。

## 表格结构

### table-235

`table-235` 是分段标题表，建议配置：

- `id: "table-235"`
- `rows: 1`
- `cols: 1`
- 静态文本：`静脉药物（ml）`
- 样式仿照现有标题行 `table-233`。
- `offset_top: 0`

### table-236

`table-236` 是执行用药明细表，与执行用药时间轴 `table-234` 对齐。

建议列结构：

| 列 | 字段 | 说明 |
| --- | --- | --- |
| 1 | `med_order_txt` | 用药医嘱 |
| 2 | `route_txt` | 用药途径 |
| 3 | `acc_ml` | 24 小时合计量 |
| 4-27 | `hour1` 至 `hour24` | 每小时用量 |

`table-236` 建议配置：

- `data_source_meta_id: "medexe_records"`
- `rows: 1`
- `cols: 27`
- `cell_heights: 12`
- `cell_widths` 与上方执行用药时间轴 `table-234` 完全一致：`129.5, 50, 25 * 25`。
- `font_size`、`char_spacing`、`h_padding` 需要从 `JfkTablePB` 传入数据源，用于 `med_order_txt` 和小时列多行文本折行。
- 所有输出字段统一使用 `STRINGS/strs_val`，便于显式换行和可变行高渲染。

## CompactReportTemplatePB 配置

使用现有结构：

```proto
message ReportMedExeTablePB {
    string table_id = 1;
    int32 intake_type_id = 2;
}
```

在 `CompactReportTemplatePB` 中：

```proto
repeated ReportMedExeTablePB med_exe_tables = 6;
```

模板配置：

```pbtxt
med_exe_tables {
  table_id: "table-236"
  intake_type_id: 1
}
```

含义：

- `table_id` 对应 `JfkTablePB.id`。
- `intake_type_id` 用于过滤 `administration_routes.intake_type_id`。
- 当 `table.data_source_meta_id == "medexe_records"` 时，`CompactReportDataSourceBuilder` 需要根据 `table.id` 查找 `CompactReportTemplatePB.med_exe_tables[]`。
- 找到配置后，给该表构造 table-scoped 输入。
- 找不到配置时，建议静默不渲染该表，行为与 `patient_balance_records` 找不到 `balance_group.table_id` 时保持一致。
- 未来如果存在多组 `med_exe_tables`，每组都需要在模板里显式配置一对标题表和明细表；当前 proto 只记录明细表 `table_id` 和 `intake_type_id`，标题表仍由模板静态表承担。

## icis_config.pb.txt 数据源元数据

新增数据源：

```text
id: "medexe_records"
name: "执行用药记录"
```

输入字段至少包含：

- `pid`
- `query_start`
- `query_end`
- `intake_type_id`

建议同时声明 compact table-scoped 通用输入：

- `dept_id`
- `table_id`
- `col_widths`
- `font_size`
- `char_spacing`
- `h_padding`

原因：

- `dept_id` 可先从 request 传入，但最终应以患者实际 `dept_id` 为准。
- `table_id` 用于错误提示和日志定位。
- `col_widths`、`font_size`、`char_spacing`、`h_padding` 用于按表格实际宽度折行。

新增配置项：

```properties
jingyi.report.compact.medication-ml-decimal-places=1
```

对应 `ReportProperties.Compact.medicationMlDecimalPlaces`，用于格式化 `consumed_ml` 和 `acc_ml`。当前确认规则是整数不带 `.0`，非整数按配置保留小数位；该配置默认值为 `1`。

输出字段：

- `med_order_txt`
- `route_txt`
- `acc_ml`
- `hour1` 至 `hour24`

所有输出字段建议：

- `val_type: 9 # STRINGS`
- `content_type: 4 # JFK_DATA_SOURCE`
- `is_array: true`
- 使用 `JfkValPB.strs_val` 输出每个单元格内容。

## 时间窗

`medexe_records` 应与上方执行用药时间轴使用同一 24 小时时间窗。

确认使用 `medexe_time_range` / `balance_time_range` 的窗口算法：

1. 读取 `query_start`。
2. `utcStart = TimeUtils.fromIso8601String(query_start, "UTC")`。
3. `utcEnd = utcStart + 24h`。
4. 通过患者实际 `dept_id` 查询 `effective_time < utcEnd` 的最后一条未删除 `balance_stats_shifts`。
5. 取 `balance_stats_shifts.start_hour`。
6. 基于 `utcStart + 12h` 换算到本地日期，取得本地午夜 `localMidnight`。
7. `medStartLocal = localMidnight + start_hour`。
8. `medStartUtc = TimeUtils.getUtcFromLocalDateTime(medStartLocal, zoneId)`。
9. `medEndUtc = medStartUtc + 24h`。

`query_end` 保留为输入，但不参与窗口计算，与当前 compact 其他 24 小时数据源保持一致。

实现注意：

- 不直接复用 `MonitoringWindowResolver`。该 resolver 会优先使用 `balance_stats_shifts.mon_start_hour`，而本需求确认 `medexe_records` 要和 `medexe_time_range` 一致，使用 `balance_stats_shifts.start_hour`。
- 推荐抽取或复用一个明确命名的 `MedexeWindow` / `startHourWindow` 逻辑，避免和观察项窗口混淆。
- `medexe_time_range` 也需要同步调整科室选择规则：输入里有有效 `pid` 时，优先使用患者实际 `dept_id`；没有有效 `pid` 时，再回退到输入 `dept_id`。这样 `table-234` 时间轴与 `table-236` 明细不会因 request `dept_id` 不一致而错位。

错误处理建议：

- `query_start`、`table_id`、`intake_type_id` 缺失：返回 `JFK_MISSING_REQUIRED_FIELD`。
- `pid` 缺失但 `dept_id` 存在：用输入 `dept_id` 计算时间窗，并输出 0 行。
- `pid` 和 `dept_id` 都缺失：返回 `JFK_MISSING_REQUIRED_FIELD`。
- `query_start` 非法：返回 `INVALID_TIME_FORMAT`。
- 找不到患者：返回 `PATIENT_NOT_FOUND`。
- 找不到患者科室：返回 `DEPT_NOT_FOUND`。
- 找不到 `balance_stats_shifts`：返回 `BALANCE_STATS_SHIFT_NOT_FOUND`。
- `start_hour` 为空或非法：返回 `INVALID_PARAM_VALUE`。

## 数据查询与过滤

### 患者与科室

1. 输入里有有效 `pid` 时，根据 `pid` 查询 `patient_records`，得到患者实际 `dept_id`。
2. 如果 request 传入的 `dept_id` 与患者实际 `dept_id` 不一致，建议 `log.warn`，并始终以患者实际 `dept_id` 为准。
3. 如果没有有效 `pid`，则回退到输入 `dept_id`；`medexe_records` 在这种情况下无法查询执行用药明细，只输出 0 行。

### 医嘱分组

根据执行记录反查或根据 `pid` 查询该患者所有 `medication_order_groups`。实现建议优先从纳入时间窗的 `medication_execution_records` 出发，再批量查询对应的 `medication_order_groups`，减少无关医嘱分组处理。

需要读取：

- `id`
- `patient_id`
- `dept_id`
- `medication_dosage_group`
- `administration_route_code`
- `administration_route_name`

`medication_dosage_group` 需要通过 `ProtoUtils.decodeDosageGroup(...)` 得到 `MedicationDosageGroupPB`，第 1 列显示：

```text
MedicationDosageGroupPB.display_name
```

并按照 `table-236` 第 1 列宽度折行。

### 给药途径

通过患者 `dept_id` 和有效 route code 查询 `administration_routes`。

有效 route code 规则：

1. 业务保证同一医嘱分组不会跨 route，因此行聚合按 `medication_order_group.id`。
2. `medication_execution_records.administration_route_code` 不为空时，优先使用执行记录上的 route code。
3. 否则使用 `medication_order_groups.administration_route_code`。

通过有效 route code 得到：

- `administration_routes.name`：第 2 列显示值。
- `administration_routes.intake_type_id`：用于匹配输入 `intake_type_id`。
- `administration_routes.is_continuous`：用于决定小时格显示形式。

只渲染 `administration_routes.intake_type_id == input.intake_type_id` 的记录。

连续/非连续判断规则：

1. 当 `medication_execution_records.is_continuous` 非空时，优先使用执行记录值。
2. 否则使用 `administration_routes.is_continuous`。

### 执行记录

根据 `medication_order_groups.id` 查询对应 `medication_execution_records`。

纳入范围建议与 `MedMonitoringService.getPatientMedMonitoringRecords(...)` 保持一致：

- 未完成记录：`start_time IS NOT NULL AND end_time IS NULL AND start_time < medEndUtc`
- 已完成记录：`start_time IS NOT NULL AND end_time IS NOT NULL AND end_time >= medStartUtc AND start_time < medEndUtc`
- `is_deleted = false`

未开始记录不产生入量，不渲染。

实现建议：

1. 查询未完成执行记录：`findInProgressRecordsByPatientId(pid, medEndUtc)`。
2. 查询已完成执行记录：`findCompletedRecordsByPatientId(pid, medStartUtc, medEndUtc)`。
3. 合并后按 `id` 去重。
4. 根据 `medication_order_group_id` 批量查询 `MedicationOrderGroup`。
5. 只保留可解析 dosage group、可解析有效 route、且 route 的 `intake_type_id == input.intake_type_id` 的记录。

### 用量统计

优先读取 `medication_execution_record_stats`：

- `stats_time >= medStartUtc`
- `stats_time < medEndUtc`
- 按 `exe_record_id + stats_time` 聚合
- 同一行同一小时如果存在多条记录，`consumed_ml` 加总

小时列严格按整点 `stats_time` 匹配：

- `hour1` 对应 `medStartUtc`
- `hour2` 对应 `medStartUtc + 1h`
- ...
- `hour24` 对应 `medStartUtc + 23h`

如果某个 `exe_record_id` 在窗口内完全没有 `medication_execution_record_stats`，需要在报表生成时临时调用 `MedMonitoringService.calcFluidIntakeImpl(...)` 计算；如果只是某个小时缺值，不补算该小时。

临时计算出的 `MedicationExecutionRecordStat` 不持久化到 `medication_execution_record_stats`，只用于本次报表内存输出。这样避免报表生成过程产生业务写入副作用。

临时计算建议：

1. 对每个执行记录，先收集其在 `[medStartUtc, medEndUtc)` 内的 stats。
2. 如果该 `exe_record_id` 在窗口内至少有一条 stats，只使用 stats，不调用 `calcFluidIntakeImpl(...)`。
3. 如果该 `exe_record_id` 在窗口内完全没有 stats，查询该执行记录的未删除 actions，并按 action `id` 或 `created_at` 升序传给 `calcFluidIntakeImpl(isContinuous, dosageGroup, actions, medEndUtc)`。
4. `calcFluidIntakeImpl(...)` 可能算出窗口外小时值，最终输出前必须再次过滤到 `[medStartUtc, medEndUtc)`。
5. 多个执行记录同属一个 `medication_order_group.id` 时，同一小时的 `consumed_ml` 加总。

### 非持续用药 action 时间

当最终 `is_continuous == false` 时，对应小时格显示两行：

```text
5.5
11:38
```

含义：

- 第 1 行：该小时内 `medication_execution_record_stats.consumed_ml` 的合计值。
- 第 2 行：该小时内对应 `medication_execution_actions` 中第一个有效 action 的开始时间，转换为本地时间后格式化为 `HH:mm`。

建议 action 选择规则：

1. 只取 `is_deleted = false`。
2. action 所属执行记录必须属于当前行。
3. action `created_at` 落在当前小时窗口 `[hourStartUtc, hourEndUtc)`。
4. 如果同一小时有多个 action，取 `created_at` 最早的一条。
5. 如果多个执行记录在同一小时合并，仍取所有候选 action 中最早的一条。

## 行聚合粒度

已确认一行代表一个 `medication_order_group.id`：

```text
rowKey = medication_order_group.id
```

原因：

- 业务上保证同一医嘱分组不会跨 route。
- 如果执行记录上 route code 非空，仍按执行记录覆盖 group route 的规则确定该组的有效 route。
- 如果实际遇到同一 `medication_order_group.id` 下多个有效 route code，`log.warn` 后使用第一条有效 route code。

## 字段生成规则

### med_order_txt

来源：

```text
MedicationDosageGroupPB.display_name
```

输出：

- 按 `table-236` 第 1 列宽度折行。
- 使用 `strs_val`。

### route_txt

来源：

```text
administration_routes.name
```

输出：

- 使用 `strs_val`。

### acc_ml

来源：

```text
sum(hour1 到 hour24 的 consumed_ml)
```

说明：

- 只统计当前行、当前 24 小时时间窗内的值。
- 连续和非连续用药都按 `consumed_ml` 加总。
- 格式化规则：整数不带 `.0`，非整数按 `jingyi.report.compact.medication-ml-decimal-places` 保留小数位。当前默认和业务确认值为 `1`，舍入方式为四舍五入。

### hour1-hour24

连续用药：

```text
consumed_ml
```

非连续用药：

```text
consumed_ml
HH:mm
```

空值：

- 当前小时无统计值时输出空 `strs_val`。
- 非整数保留 1 位小数时使用四舍五入。

无匹配记录：

- 没有任何匹配 `intake_type_id` 的执行用药记录时，输出 0 行，使数据源为 `medexe_records` 的明细表不渲染。
- 0 行输出时所有输出字段仍存在，但 `vals` 长度均为 0。

## compactreport 输入构造

`medexe_records` 应作为 table-scoped 数据源处理，类似：

- `patient_monitoring_records`
- `patient_bga_records`
- `patient_balance_records`

实现策略：

1. `CompactReportDataSourceBuilder.collectDataSourceMetaIds(...)` 收集到 `medexe_records` 后，不生成普通公共输入。
2. 新增 `buildMedexeRecordsInputs(...)`。
3. 遍历模板中所有 `JfkTablePB`。
4. 对 `table.data_source_meta_id == "medexe_records"` 的表：
   - 根据 `table.id` 查找 `compactTemplate.med_exe_tables[]`。
   - 找不到配置时，跳过该表。
   - 找到配置时，构造 table-scoped input：

```java
.setId(JfkDataSourceIds.compactTableScoped("medexe_records", table.getId()))
.addInputData(strInput("table_id", table.getId()))
.addInputData(int32Input("intake_type_id", medExeTable.getIntakeTypeId()))
.addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
.addInputData(doubleInput("font_size", table.getFontSize()))
.addInputData(doubleInput("char_spacing", table.getCharSpacing()))
.addInputData(doubleInput("h_padding", table.getHPadding()))
```

同时保留 `commonInputBuilder(...)` 已有的：

- `pid`
- `dept_id`
- `shift_time`
- `query_start`
- `query_end`

`medexe_records` 的 `intake_type_id` 建议使用 `int32Input` 或等价的 `JfkValPB.int32_val`。如果当前 builder 只有 `int64Input`，可以新增 `int32Input`，避免把 ID 当字符串传递。

## 与 MedMonitoringService 的关系

应重点复用或参考：

- `MedMonitoringService.getPatientMedMonitoringRecords(...)`
- `MedMonitoringService.getDosageGroupPB(...)`
- `MedMonitoringService.getDosageGroupPBOrDefault(...)`
- `MedMonitoringService.calcFluidIntakeImpl(...)`
- `AdministrationRouteRepository.findRouteDetailsByDeptId(...)`
- `MedicationExecutionRecordStatRepository.findAllByPatientIdAndStatsTimeRange(...)`

实现目标不是写入 `patient_monitoring_records`，也不是写入 `medication_execution_record_stats`，而是直接为 JFK 报表输出 `medexe_records`。如果需要临时计算缺失的统计值，应尽量复用 `MedMonitoringService` 已有纯计算逻辑。

`MedMonitoringService.calcFluidIntakeImpl(...)` 当前可作为纯计算入口使用：

- 连续用药：根据 action 的速度和时间段计算小时消耗量。
- 非连续用药：根据 action 的 `intake_vol_ml` 记录到 action 所在整点小时。
- 返回的 `FluidIntakeData.intakeMap` 以 UTC 整点小时为 key，可以用于构造 `hour1` 至 `hour24`。

## report_compact.pb.txt 配置草案

```pbtxt
ac_tables {
  offset_top: 0
  tbl {
    id: "table-235"
    type: "table"
    rows: 1
    cols: 1
    column_metas {
      id: "table-235-col-1"
      element_meta {
        content_type: 1
      }
      static_vals: "静脉药物（ml）"
    }
    # 样式仿照 table-233
  }
}

ac_tables {
  offset_top: 0
  tbl {
    id: "table-236"
    type: "table"
    rows: 1
    cols: 27
    data_source_meta_id: "medexe_records"
    column_metas {
      id: "table-236-col-1"
      element_meta {
        content_type: 4
        data_source_meta_id: "medexe_records"
        data_source_field_id: "med_order_txt"
      }
    }
    column_metas {
      id: "table-236-col-2"
      element_meta {
        content_type: 4
        data_source_meta_id: "medexe_records"
        data_source_field_id: "route_txt"
      }
    }
    column_metas {
      id: "table-236-col-3"
      element_meta {
        content_type: 4
        data_source_meta_id: "medexe_records"
        data_source_field_id: "acc_ml"
      }
    }
    # 第 4 至 27 列绑定 hour1 至 hour24
  }
}

med_exe_tables {
  table_id: "table-236"
  intake_type_id: 1
}
```

## 测试需求

建议新增或更新以下测试：

1. `CompactReportTemplateLoaderTests`
   - 使用独立测试模板，不依赖生产 `report_compact.pb.txt` 的表格数量。
   - 验证 `med_exe_tables { table_id, intake_type_id }` 能解析。

2. `CompactReportDataSourceBuilderTests`
   - 验证 `medexe_records` 被识别为 table-scoped。
   - 验证 `table-236` 能根据 `med_exe_tables` 构造输入。
   - 验证缺少 `med_exe_tables.table_id` 时跳过。
   - 验证 `intake_type_id`、`col_widths`、`font_size`、`char_spacing`、`h_padding` 被传入。

3. `MedexeRecordsDataSourceHandlerTests`
   - 连续用药：小时格只输出 `consumed_ml`。
   - 非连续用药：小时格输出两行 `consumed_ml` 和本地 `HH:mm`。
   - `acc_ml` 等于 24 个小时值合计。
   - 执行记录 route 覆盖 group route。
   - 执行记录 `is_continuous` 覆盖 route `is_continuous`。
   - 只渲染匹配 `intake_type_id` 的 route。
   - 多条执行记录同一小时合并。
   - 无匹配记录时输出 0 行，明细表不渲染。
   - 某个 `exe_record_id` 在窗口内完全没有 stats 时，通过 `MedMonitoringService.calcFluidIntakeImpl(...)` 临时计算。
   - 临时计算结果不持久化，只用于本次报表输出。
   - 缺少患者、缺少班次、非法 `start_hour` 等错误分支。
   - request `dept_id` 与患者实际 `dept_id` 不一致时，优先使用患者实际 `dept_id`。

4. `MedexeTimeRangeDataSourceHandlerTests`
   - 输入里有有效 `pid` 时，优先使用患者实际 `dept_id` 查询 `balance_stats_shifts.start_hour`。
   - 没有有效 `pid` 时，回退输入 `dept_id`。

5. `JfkPdfRendererTests`
   - 使用 synthetic `medexe_records` 数据验证 `table-236` 可以渲染多行单元格和可变行高。

## 实现前仍需确认的问题

当前需求已收敛，暂无阻塞实现的问题。

实现时若发现现有 `MedMonitoringService.calcFluidIntakeImpl(...)` 无法直接复用到“只内存计算、不持久化”的场景，应优先抽取纯计算逻辑或在 handler 内组合已有纯计算方法，不应为了报表输出写入 `medication_execution_record_stats`。
