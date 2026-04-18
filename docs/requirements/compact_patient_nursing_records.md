# 精简重症监护记录单 patient_nursing_records 表格需求

## 目标

在 compact 重症监护记录单中新增护理记录区域，参考当前 `table-251/table-252/table-253` 管道维护记录区域，新增：

- `table-254`：静态标题，文本为 `护理记录`
- `table-255`：静态表头，4 列：`时间 / 护理记录 / 记录人 / 审核人`
- `table-256`：动态明细表，数据源为 `patient_nursing_records`

本文档记录实现目标、已确认决策与实现要点。

## 表格配置

### table-254

静态标题表：

```text
id: "table-254"
static_vals: "护理记录"
```

建议插入在 `table-253` 之后，并继续放在同一个 flow container 的 `ac_tables` 中。

### table-255

静态表头表，4 列：

| 列 | 字段 | 标题 | 宽度 |
| --- | --- | --- | --- |
| 1 | `record_time` | 时间 | 100 |
| 2 | `content` | 护理记录 | 596 |
| 3 | `recorded_by` | 记录人 | 60 |
| 4 | `reviewed_by` | 审核人 | 60 |

列宽总和为 `816`，与当前 compact 报表主体区域宽度一致。

### table-256

动态明细表：

```text
id: "table-256"
data_source_meta_id: "patient_nursing_records"
cols: 4
cell_widths: 100
cell_widths: 596
cell_widths: 60
cell_widths: 60
```

字段映射：

| 列 | data_source_field_id | 说明 |
| --- | --- | --- |
| 1 | `record_time` | 本地时间文本 |
| 2 | `content` | 护理记录内容 |
| 3 | `recorded_by` | 记录人 |
| 4 | `reviewed_by` | 审核人 |

`content` 需要根据第 2 列宽度、表格字体、字符间距和 padding 折行，输出 `JfkValPB.strs_val`。

## 已确认决策

1. `table-255/table-256` 宽度为 `100 + 596 + 60 + 60 = 816`。
2. `patient_monitoring_records` 生成的护理记录行中，`recorded_by` 使用 `patient_monitoring_records.modified_by` 转 `accounts.id` 后查到的 `accounts.name`，`reviewed_by` 为空。
3. 非整点观察项同一时间多条记录的内部排序，按观察项配置的分组顺序和组内 `display_order`。
4. 同一时间既有 `nursing_records` 又有非整点 `patient_monitoring_records` 时，不合并为一行；先显示护理记录行，再显示观察项汇总行。
5. `nursing_records.content` 可能包含换行符；保留原始换行，并在每个原始行内再按列宽折行。
6. 无数据时保持 `table-254/table-255` 静态显示，仅隐藏动态明细 `table-256`。

## 数据源定义

新增 JFK 数据源：

```text
id: "patient_nursing_records"
name: "护理记录"
```

输入至少包含：

- `pid`
- `dept_id`
- `query_start`
- `query_end`
- `table_id`
- `col_widths`
- `font_size`
- `char_spacing`
- `h_padding`

`query_end` 与其他 compact 数据源一致，作为输入保留；实际窗口由 `query_start + 24h` 和 `balance_stats_shifts.mon_start_hour` 推导。

输出字段全部使用 `STRINGS/strs_val`：

- `record_time`
- `content`
- `recorded_by`
- `reviewed_by`

## 时间窗口

使用与 `patient_monitoring_records`、`patient_tube_records` 一致的观察项窗口：

1. 从输入 `query_start` 解析 `utcStart`。
2. `utcEnd = utcStart + 24h`。
3. 根据 `pid` 查询患者，始终以患者实际 `dept_id` 为准；如果请求 `dept_id` 与患者科室不一致，`log.warn`。
4. 查询 `effective_time < utcEnd` 的最后一条未删除 `balance_stats_shifts`。
5. 优先使用 `mon_start_hour`，为空或非法时回退 `start_hour`。
6. 根据本地午夜和 `mon_start_hour` 得到：

```text
monStartUtc
monEndUtc = monStartUtc + 24h
```

错误处理建议复用 `MonitoringWindowResolver`：

- 缺少 `pid/query_start/table_id`：`JFK_MISSING_REQUIRED_FIELD`
- `query_start` 非法：`INVALID_TIME_FORMAT`
- 找不到患者：`PATIENT_NOT_FOUND`
- 找不到 `balance_stats_shifts`：`BALANCE_STATS_SHIFT_NOT_FOUND`
- `mon_start_hour/start_hour` 都为空或非法：`INVALID_PARAM_VALUE`

## 数据来源一：nursing_records

查询条件：

```text
nursing_records.patient_id = pid
nursing_records.effective_time >= monStartUtc
nursing_records.effective_time < monEndUtc
nursing_records.is_deleted = false
```

输出映射：

| 输出字段 | 来源 |
| --- | --- |
| `record_time` | `effective_time` 转本地时间 |
| `content` | `content` |
| `recorded_by` | `created_by_account_name`，为空时用 entity 当前 getter 回退 `created_by` |
| `reviewed_by` | `reviewed_by_account_name`，为空时用 entity 当前 getter 回退 `reviewed_by` |

时间格式建议：

```text
yyyy-MM-dd HH:mm
```

## 数据来源二：非整点 patient_monitoring_records

查询条件：

```text
patient_monitoring_records.pid = pid
patient_monitoring_records.effective_time >= monStartUtc
patient_monitoring_records.effective_time < monEndUtc
patient_monitoring_records.is_deleted = false
effective_time 不是严格整点
```

非整点过滤严格口径：

```text
minute != 0 || second != 0 || nano != 0
```

监测记录输出映射：

| 输出字段 | 来源 |
| --- | --- |
| `record_time` | `effective_time` 转本地时间 |
| `content` | 同一 `effective_time` 下的观察项拼接文本 |
| `recorded_by` | `modified_by` 转 `Long` 匹配 `accounts.id` 后获得的 `accounts.name` |
| `reviewed_by` | 空字符串 |

### 监测记录 content 生成规则

1. 根据患者 `dept_id` 调用 `MonitoringConfig.getMonitoringParams(deptId)`，得到 `Map<paramCode, MonitoringParamPB>`。
2. 对每条非整点 `patient_monitoring_records`：
   - 如果 `monitoring_param_code` 找不到对应 `MonitoringParamPB`，`log.warn` 后跳过该条记录。
   - 优先使用 `param_value_str`。
   - 如果 `param_value_str` 为空但 `param_value` 存在，则复用 `PatientMonitoringRecordsDataSourceHandler` 的 `MonitoringValuePB` 解码与 `ValueMetaUtils.extractAndFormatParamValue(...)` 格式化逻辑。
   - 如果最终值为空，跳过该条记录。
3. 同一 `effective_time` 下，按观察项配置顺序拼接多条观察项：

```text
MonitoringParamPB.name + ": " + paramValue
```

多条记录用 `; ` 拼接：

```text
血压: 120/80; 心率: 88
```

排序口径：

1. 使用 `MonitoringConfig.getMonitoringGroups(pid, deptId, monitoringGroupTypeId, tubeParamCodes, accountId)` 获取观察项分组配置。
2. `monitoringGroupTypeId` 使用 `ConfigProtoService.getConfig().getMonitoring().getEnums().getGroupTypeMonitoring().getId()`。
3. 按 `MonitoringGroupBetaPB.display_order` 升序、组内 `MonitoringParamPB.display_order` 升序排序。
4. 如果某个参数不在观察项分组中，但存在于 `MonitoringConfig.getMonitoringParams(deptId)`，则排在已配置参数之后，再按 `MonitoringParamPB.display_order`、`monitoring_param_code` 稳定排序。
5. 由于 `getMonitoringGroups(...)` 需要 `accountId`，实现中应通过 `UserService.getAccountWithAutoId()` 获取当前用户；获取失败时建议返回 `ACCOUNT_NOT_FOUND`。
6. `recorded_by` 的显示值按非整点观察项记录的 `modified_by` 批量转换：将 `modified_by` 解析为 `Long`，匹配 `accounts.id`，显示 `accounts.name`。无法解析或查不到账号时记录 `warn`，实现可回退显示原 `modified_by`，避免历史数据导致整行记录人为空。

## 合并与排序

数据源最终把两类记录合并成统一行模型：

```text
record_time_utc
content
recorded_by
reviewed_by
source_order
source_id
```

排序：

1. `record_time_utc` 升序。
2. 同一时间时，不把 `nursing_records` 与 `patient_monitoring_records` 合并；先输出 `nursing_records` 行，再输出 `patient_monitoring_records` 汇总行。
3. 同一来源同一时间内按 `id` 升序，保证稳定。

## 折行

`table-256` 的 `content` 列宽为 `596`，必须从对应 `JfkTablePB` 提取：

- `cell_widths`
- `font_size`
- `char_spacing`
- `h_padding`

折行实现复用现有 `JfkPdfUtils.getWrappedLines(...)` 逻辑，所有输出统一写入 `JfkValPB.strs_val`。

`nursing_records.content` 的换行处理：

1. 保留原始换行符语义。
2. 先按原始换行拆成逻辑行。
3. 对每个逻辑行再按列宽折行。
4. 最终按顺序合并为 `strs_val` 多行。

如果 `record_time`、`recorded_by`、`reviewed_by` 超出列宽，也建议按各自列宽折行，避免 PDF 文字溢出。

## compact 接入点

### JfkDataSourceIds

新增：

```java
public static final String PATIENT_NURSING_RECORDS = "patient_nursing_records";
```

并纳入 `isCompactTableScoped(...)`。

### CompactReportDataSourceBuilder

参考 `patient_tube_records`：

1. 遍历模板中 `data_source_meta_id == "patient_nursing_records"` 的表格。
2. 为每个表格构造 table-scoped input：

```text
id = compact-patient_nursing_records-${table_id}
meta_id = patient_nursing_records
table_id
col_widths
font_size
char_spacing
h_padding
```

### 渲染器

`patient_nursing_records` 属于 table-scoped 数据源。现有 renderer 对 table-scoped meta id 的查找逻辑应能复用；若当前白名单只包含已有几个 meta id，需要加入 `patient_nursing_records`。

无护理记录且无非整点观察项时，数据源输出 0 行，使 `table-256` 不渲染；`table-254/table-255` 静态标题和表头仍显示，除非后续新增“静态表头跟随数据源隐藏”的能力。

## 配置文件变更

### icis_config.pb.txt

在 JFK 数据源配置中新增 `patient_nursing_records`：

- input fields:
  - `pid`
  - `dept_id`
  - `query_start`
  - `query_end`
  - `table_id`
  - `col_widths`
  - `font_size`
  - `char_spacing`
  - `h_padding`
- output fields:
  - `record_time`
  - `content`
  - `recorded_by`
  - `reviewed_by`

所有 output `val_type` 使用 `9 # STRINGS`。

### report_compact.pb.txt

参考 `table-251/table-252/table-253`，在其后追加：

- `table-254`：标题 `护理记录`
- `table-255`：表头
- `table-256`：动态明细，`data_source_meta_id: "patient_nursing_records"`

## Repository 需求

### NursingRecordRepository

现有方法：

```java
findByPatientIdAndEffectiveTimeBetweenAndIsDeletedFalse(...)
```

该方法语义通常为闭区间，不适合 `[monStartUtc, monEndUtc)`。建议新增显式 JPQL：

```java
findReportNursingRecords(pid, monStartUtc, monEndUtc)
```

条件：

```text
patientId = pid
effectiveTime >= monStartUtc
effectiveTime < monEndUtc
isDeleted = false
order by effectiveTime asc, id asc
```

### PatientMonitoringRecordRepository

现有：

```java
findByPidAndEffectiveTimeRange(pid, start, end)
```

可以复用后在 handler 内过滤非整点；也可以新增更明确的报表查询方法。考虑 JPQL 对 `minute/second/nano` 的跨数据库兼容性，建议先复用现有查询并在 Java 中严格过滤非整点。

## 单元测试建议

新增：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientNursingRecordsDataSourceHandlerTests.java
```

覆盖：

1. 正确解析 `monStartUtc/monEndUtc`。
2. 查询 `nursing_records` 并输出时间、内容、记录人、审核人。
3. 查询非整点 `patient_monitoring_records`，整点记录被过滤。
4. 同一非整点时间多条观察项合并成一行，格式为 `名称: 值; 名称: 值`。
5. `param_value_str` 为空时解码 `param_value` 并格式化。
6. 找不到 `MonitoringParamPB` 时 warn 并跳过。
7. 两类记录合并后按时间升序。
8. 同一时间 nursing 记录与 monitoring 汇总行的输出顺序稳定。
9. `content` 按列宽折行，输出 `strs_val` 多行。
10. 无数据时输出字段存在但 `vals` 长度为 0。

补充：

- `CompactReportDataSourceBuilderTests`：验证 `patient_nursing_records` 的 table-scoped input 带入 `table_id/col_widths/font_size/char_spacing/h_padding`。
- `CompactReportTemplateLoaderTests`：如使用独立测试模板，增加 `table-254/table-255/table-256`。
- `JfkPdfRendererTests`：使用 synthetic `patient_nursing_records` 数据验证可变行高与长文本折行。

## 待确认问题

暂无阻塞实现的问题。
