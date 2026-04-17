# Compact 报表 patient_bga_records 表格绘制需求

## 背景

`report_compact.pb.txt` 中已经新增血气分析相关表格：

1. `table-37`：静态标题，显示 `血气分析`。
2. `table-38`：动态数据表，`data_source_meta_id = "patient_bga_records"`。

`table-38` 当前为 5 列：

| 字段 | 表头 | 数据来源 |
| --- | --- | --- |
| `date_str` | 时间 | `patient_bga_records.effective_time` |
| `category_name` | 血气类别 | `patient_bga_records.bga_category_id` 映射配置枚举 |
| `bga_details` | 血气记录 | `patient_bga_record_details` 明细拼接 |
| `recorded_by` | 记录人 | `patient_bga_records.recorded_by_account_name` |
| `reviewed_by` | 审核人 | `patient_bga_records.reviewed_by_account_name` |

本需求目标是补齐 JFK 数据源元数据、compact 数据源输入构造、`jfkdatasources` handler 和渲染器表格绑定，使 compact 报表可以绘制 `patient_bga_records` 表格。

## 当前上下文

当前工作区已有未暂存改动：

1. `src/main/java/com/jingyicare/jingyi_icis_engine/service/monitorings/BgaService.java`
2. `src/main/resources/config/pbtxt/report_compact.pb.txt`
3. `src/main/resources/static/home.bundle.js`

后续实现时不应覆盖这些改动。

## 目标

新增 JFK 数据源：

```text
id: "patient_bga_records"
name: "血气记录"
```

配置位置：

```text
src/main/resources/config/pbtxt/icis_config.pb.txt:10825 后
```

即插入在当前 `patient_monitoring_records` 数据源之后、`jfk.nursing_role_ids` 之前。

新增实现类建议：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientBgaRecordsDataSourceHandler.java
```

新增 compact 模板字段：

```proto
message CompactReportTemplatePB {
    JfkTemplatePB template = 1;
    LogoMetaPB logo = 2;
    repeated ReportMonGroupPB mon_group = 3;
    string bga_table_id = 4;
}
```

`report_compact.pb.txt` 中配置：

```text
bga_table_id: "table-38"
```

## 非目标

1. 本次不改变流式 container 或弹性表格分页规则。
2. 本次不修改 BGA 录入、同步、审核业务流程。
3. 本次不使用 `raw_bga_records` / `raw_bga_record_details` 直接出报表；报表只读取已归档到 `patient_bga_records` / `patient_bga_record_details` 的数据。

## 数据表

### patient_bga_records

来源：

```text
src/main/resources/config/db/schema.postgresql.sql:3396
```

关键字段：

| 字段 | 用途 |
| --- | --- |
| `id` | 关联明细表 |
| `pid` | 患者 ID |
| `dept_id` | 科室 ID |
| `bga_category_id` | 血气类别 ID |
| `bga_category_name` | 血气类别名称，可作为配置缺失时的候选回退 |
| `effective_time` | 检测时间，UTC |
| `recorded_by_account_name` | 记录人姓名 |
| `reviewed_by_account_name` | 审核人姓名 |
| `is_deleted` | 逻辑删除 |
| `modified_at` | 最近修改时间 |

建议查询范围：

```text
pid = pid
effective_time >= monStartUtc
effective_time < monEndUtc
is_deleted = false
order by effective_time asc
```

### patient_bga_record_details

来源：

```text
src/main/resources/config/db/schema.postgresql.sql:3457
```

关键字段：

| 字段 | 用途 |
| --- | --- |
| `record_id` | 对应 `patient_bga_records.id` |
| `monitoring_param_code` | 观察项编码 |
| `param_value_str` | 已格式化后的文本值 |
| `param_value` | `GenericValuePB` base64 编码值，本期默认不使用 |
| `is_deleted` | 逻辑删除 |

明细查询建议：

```text
record_id in (:recordIds)
is_deleted = false
```

再按 `record_id` 分组。

## 输入字段

用户要求基础输入为：

1. `pid`
2. `query_start`
3. `query_end`

由于需要按 `table-38` 的列宽做 `bga_details` 折行，compact 构造数据源输入时还需要表格作用域参数：

1. `table_id`
2. `col_widths`
3. `font_size`
4. `char_spacing`
5. `h_padding`

这些字段沿用 `patient_monitoring_records` 已经补齐的 JFK input 元数据。`patient_bga_records` 的 `icis_config.pb.txt` 数据源元数据也显式声明这些表格作用域输入字段。

## 输出字段

`patient_bga_records` 固定输出 5 个字段：

1. `date_str`
2. `category_name`
3. `bga_details`
4. `recorded_by`
5. `reviewed_by`

每个字段都是数组。有 BGA 记录时，第 1 行为表头，第 2 行开始为实际 BGA 数据：

```text
date_str      -> ["时间", "2026-04-01 7:20", ...]
category_name -> ["血气类别", "动脉血", ...]
bga_details   -> ["血气记录", "pH: 7.35; PaO2: 86", ...]
recorded_by   -> ["记录人", "张护士", ...]
reviewed_by   -> ["审核人", "李医生", ...]
```

所有输出字段统一使用：

```text
val_type: 9 # STRINGS
content_type: 4 # JFK_DATA_SOURCE
is_array: true
is_options: false
```

原因：

1. `bga_details` 需要按列宽折行，必须使用 `JfkValPB.strs_val`。
2. 其他列虽然通常是单行，但统一使用 `strs_val` 能简化 renderer 的多行处理。
3. 表头也作为第一行数据返回，便于 `table-38` 使用同一套弹性表格逻辑。

## 监测窗口计算

本数据源需要获得 `monStartUtc` 和 `monEndUtc`。

建议复用已经新增的 compact 监测窗口口径：

```text
MonitoringWindowResolver.resolve(pid, query_start)
```

口径：

1. `query_start` 解析为 UTC：`TimeUtils.fromIso8601String(query_start, "UTC")`。
2. `utcEnd = utcStart.plusHours(24)`。
3. 根据 `pid` 查询患者，使用患者自己的 `dept_id`。
4. 查询 `balance_stats_shifts` 中 `effective_time < utcEnd` 的最后一条有效记录。
5. `mon_start_hour` 合法时使用 `mon_start_hour`，否则回退 `start_hour`。
6. 根据配置 `zoneId` 计算 `monStartLocal`、`monStartUtc`、`monEndUtc`。

`query_end` 只作为标准输入保留，不参与窗口计算。

## 字段生成规则

### date_str

来源：

```text
patient_bga_records.effective_time
```

处理：

1. `effective_time` 按 UTC 存储。
2. 使用 `TimeUtils.getLocalDateTimeFromUtc(effective_time, zoneId)` 转成本地时间。
3. 格式化为：

```text
yyyy-MM-dd H:mm
```

示例：

```text
2026-04-01 7:20
```

### category_name

来源：

```text
patient_bga_records.bga_category_id
```

配置枚举：

```java
ConfigProtoService.getConfig()
    .getBga()
    .getEnums()
    .getBgaCategoryList()
```

当前配置参考：

```text
1 -> 动脉血
2 -> 静脉血
3 -> 脑脊液
```

规则：

1. 按 `bga_category_id` 匹配枚举 `id`。
2. 匹配成功输出枚举 `name`。
3. 匹配失败时 `log.warn`。
4. 匹配失败后回退到 `patient_bga_records.bga_category_name`。

### bga_details

来源：

```text
patient_bga_record_details
```

处理流程：

1. 根据 `patient_bga_records.id` 批量查询有效明细。
2. 明细按 `record_id` 分组。
3. 对每条明细取 `monitoring_param_code`。
4. 通过类似 `compact_patient_monitoring_records.md` 的方式获取 `MonitoringParamPB`：

```java
MonitoringConfig.getMonitoringParams(deptId)
```

5. 优先使用 `param_value_str` 非空的明细值：

```java
!StrUtils.isBlank(detail.getParamValueStr())
```

6. 如果 `param_value_str` 为空但 `param_value` 存在，则解码 `GenericValuePB`，并使用 `ValueMetaUtils` 根据该参数的 `ValueMetaPB` 重新格式化。
7. 格式化后仍为空的明细跳过。
8. 每条明细格式化为：

```text
MonitoringParamPB.getName() + ": " + param_value_str
```

9. 多条明细使用：

```text
"; "
```

拼接示例：

```text
pH: 7.35; PaO2: 86; PaCO2: 42
```

明细中的 `monitoring_param_code` 如果在 `MonitoringConfig.getMonitoringParams(deptId)` 中找不到，建议 `log.warn` 后跳过该明细。

明细排序规则：

1. 按 `bga_params` 中同科室、同 `monitoring_param_code` 对应的 `display_order` 升序。
2. 同一 `display_order` 时按 `patient_bga_record_details.id asc`。
3. 没有对应 `bga_params` 配置的明细排在最后，再按 `detail.id asc`。

### recorded_by

来源：

```text
patient_bga_records.recorded_by_account_name
```

空值输出空字符串。

### reviewed_by

来源：

```text
patient_bga_records.reviewed_by_account_name
```

空值输出空字符串。

## 折行规则

`bga_details` 可能较长，需要按 `table-38` 的列宽折行。

模板变更：

1. 在 `CompactReportTemplatePB` 中新增 `bga_table_id`。
2. `report_compact.pb.txt` 设置 `bga_table_id: "table-38"`。

compact 数据源构造逻辑：

1. 遍历 `JfkTemplatePB` 中所有表格。
2. 找到 `table.data_source_meta_id == "patient_bga_records"` 且 `table.id == compactTemplate.bga_table_id` 的表格。
3. 为该表格构造表格作用域数据源输入：

```text
id: compact-patient_bga_records-table-38
meta_id: patient_bga_records
input: pid
input: query_start
input: query_end
input: table_id = table-38
input: col_widths = table.cell_widths
input: font_size = table.font_size
input: char_spacing = table.char_spacing
input: h_padding = table.h_padding
```

handler 内：

1. 如果 `col_widths` 长度与输出字段数一致，使用对应列宽折行。
2. `bga_details` 使用第 3 列宽，即 `col_widths[2]`。
3. 可用宽度：

```text
available_width = max(0, col_widths[2] - 2 * h_padding)
```

4. 复用 `JfkPdfUtils.getWrappedLines(...)`。
5. 折行结果写入 `JfkValPB.strs_val`。

若没有传入列宽，`bga_details` 作为单行 `strs_val` 输出。

没有任何 BGA 记录时，所有输出字段返回空数组，不输出表头，表格不绘制任何行。

## compactreport 集成

需要修改：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport/CompactReportDataSourceBuilder.java
```

处理逻辑：

1. `collectDataSourceMetaIds` 当前已经能收集 `table.data_source_meta_id`。
2. `buildInputs` 中需要像 `patient_monitoring_records` 一样跳过通用输入，改为构造表格作用域输入。
3. 新增 `buildPatientBgaRecordsInputs(...)`。
4. 只在 `table.id == compactTemplate.getBgaTableId()` 且 `table.data_source_meta_id == "patient_bga_records"` 时构造输入。
5. 如果找不到 `bga_table_id` 对应表格，不构造输入，不渲染该表格，并建议 `log.warn`。

renderer 需要修改：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkrenderer/JfkRenderData.java
```

当前 `dataSourceForTable` 只对 `patient_monitoring_records` 使用 table-scoped id。需要扩展为：

```text
patient_monitoring_records
patient_bga_records
```

都通过：

```text
JfkDataSourceIds.compactTableScoped(metaId, tableId)
```

查找对应数据源。

## 数据源实现

新增：

```text
PatientBgaRecordsDataSourceHandler extends AbstractJfkDataSourceHandler
```

`getMetaId()` 返回：

```text
patient_bga_records
```

建议依赖：

1. `JfkDataSourceSupport`
2. `MonitoringWindowResolver`
3. `PatientBgaRecordRepository`
4. `PatientBgaRecordDetailRepository`
5. `MonitoringConfig`
6. `ConfigProtoService`
7. `ReportProperties`
8. `ResourceLoader`

处理步骤：

1. 校验 `pid`、`query_start`。
2. 通过 `MonitoringWindowResolver` 获取 `patient`、`deptId`、`monStartUtc`、`monEndUtc`。
3. 查询 `patient_bga_records`。
4. 批量查询对应 `patient_bga_record_details`。
5. 如果没有 BGA 记录，构造 5 个空数组输出字段。
6. 如果有 BGA 记录，构造 5 个输出字段，每个字段第一行是表头。
7. 对 `bga_details` 按列宽折行。
8. 返回 `JfkDataSourcePB`。

## Repository 建议

`PatientBgaRecordRepository` 建议新增闭开区间查询，避免 Spring Data `Between` 的闭区间语义带来边界重复：

```java
List<PatientBgaRecord> findByPidAndEffectiveTimeGreaterThanEqualAndEffectiveTimeLessThanAndIsDeletedFalseOrderByEffectiveTimeAsc(
    Long pid,
    LocalDateTime startTime,
    LocalDateTime endTime
);
```

`PatientBgaRecordDetailRepository` 已有：

```java
List<PatientBgaRecordDetail> findByRecordIdInAndIsDeletedFalse(List<Long> recordIds);
```

可直接复用。

## 测试计划

新增测试：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientBgaRecordsDataSourceHandlerTests.java
```

覆盖：

1. 缺少 `pid/query_start` 返回缺字段错误。
2. `query_start` 非法返回 `INVALID_TIME_FORMAT`。
3. 找不到患者或找不到 `balance_stats_shifts` 时沿用 `MonitoringWindowResolver` 错误。
4. 能按 `query_start` 计算 `monStartUtc/monEndUtc` 并查询 BGA 记录。
5. `date_str` 从 UTC 转本地时间并格式化为 `yyyy-MM-dd H:mm`。
6. `category_name` 能按 BGA 配置枚举映射。
7. `bga_details` 只拼接 `param_value_str` 非空的明细。
8. 缺失 `MonitoringParamPB` 的明细被跳过并记录 warning。
9. 输出第一行为固定表头。
10. `bga_details` 过长时能按第 3 列列宽写入多行 `strs_val`。
11. 没有 BGA 记录时输出空数组，不输出表头。

`compactreport` 测试：

1. `CompactReportDataSourceBuilderTests` 增加 `bga_table_id` 场景，验证只为 `table-38` 构造 table-scoped `patient_bga_records` 输入。
2. `JfkPdfRendererTests` 增加 synthetic `patient_bga_records` 数据，验证表格可以通过 table-scoped 数据源渲染。

## 已确认口径

1. `query_end` 和现有 compact 数据源一样只保留输入，不参与窗口计算。
2. `patient_bga_records` 元数据输入声明 `pid/query_start/query_end`，也声明 `table_id/col_widths/font_size/char_spacing/h_padding`。
3. `bga_category_id` 配置枚举找不到时，回退到 `patient_bga_records.bga_category_name`。
4. 明细排序按 `bga_params.monitoring_param_code` 对应的 `display_order` 排序。
5. `param_value_str` 为空但 `param_value` 存在时，做解码格式化。
6. BGA 记录按 `effective_time asc` 排序，报表从早到晚显示。
7. 没有 BGA 记录时，表头也不输出。
8. 输出字段统一使用 `STRINGS/strs_val`。
