# 精简重症监护记录单 patient_skincare_records 表格需求

## 目标

在 compact 重症监护记录单中新增皮肤护理区域，参考当前 `table-254/table-255/table-256` 护理记录区域，新增：

- `table-257`：静态标题，文本为 `皮肤护理`
- `table-258`：静态表头，3 列：`时间 / 皮肤护理内容 / 记录人`
- `table-259`：动态明细表，数据源为 `patient_skincare_records`

本文档记录需求、已确认决策与实现要点。

## 已确认决策

1. `table-258/table-259` 按 3 列处理。
2. 数据源名确认为 `patient_skincare_records`。
3. `recorded_by` 使用 `patient_skincare_records.modified_by` 转 `accounts.id` 后显示 `accounts.name`。
4. `attr-code-whitelist` 按 `skincare_type_attributes.attr_code` 过滤。
5. 不需要遵循 `skincare_type_attributes.show_in_table = true`。
6. 找不到账号时，`recorded_by` 回退显示原始账号字段。
7. 无皮肤护理记录时，静态 `table-257/table-258` 保留，仅隐藏动态 `table-259`。
8. 中间列表头使用 `皮肤护理内容`。
9. 中间列内容按 `skincare_types.name + " " + plan attrs + "; " + record attrs` 合并输出。

## 表格配置

### table-257

静态标题表：

```text
id: "table-257"
static_vals: "皮肤护理"
```

建议插入在 `table-256` 之后，并继续放在同一个 flow container 的 `ac_tables` 中。

### table-258

静态表头表，3 列：

| 列 | 字段 | 标题 | 宽度 |
| --- | --- | --- | --- |
| 1 | `record_time` | 时间 | 100 |
| 2 | `content` | 皮肤护理内容 | 656.5 |
| 3 | `recorded_by` | 记录人 | 60 |

列宽总和：

```text
100 + 656.5 + 60 = 816.5
```

### table-259

动态明细表，数据源为：

```text
data_source_meta_id: "patient_skincare_records"
```

字段映射：

| 列 | data_source_field_id | 说明 |
| --- | --- | --- |
| 1 | `record_time` | 本地时间文本 |
| 2 | `content` | 皮肤护理内容 |
| 3 | `recorded_by` | 记录人 |

所有字段统一输出 `STRINGS/strs_val`，便于折行和动态行高计算。

## 数据源定义

新增 JFK 数据源：

```text
id: "patient_skincare_records"
name: "皮肤护理记录"
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

输出字段建议：

- `record_time`
- `content`
- `recorded_by`

## 时间窗口

复用 compact 已有时间窗口口径，与 `patient_nursing_records`、`patient_tube_records` 保持一致：

1. 从输入 `query_start` 解析 `utcStart`。
2. `utcEnd = utcStart + 24h`。
3. 根据 `pid` 查询患者，优先使用患者实际 `dept_id`；如果请求 `dept_id` 与患者科室不一致，`log.warn`。
4. 查询 `effective_time < utcEnd` 的最后一条未删除 `balance_stats_shifts`。
5. 优先使用 `mon_start_hour`，为空或非法时回退 `start_hour`。
6. 根据本地午夜和 `mon_start_hour` 得到：

```text
monStartUtc
monEndUtc = monStartUtc + 24h
```

建议实现时复用现有 `MonitoringWindowResolver`。

## 数据查询

查询 `patient_skincare_records`：

```text
patient_skincare_records.pid = pid
patient_skincare_records.created_at >= monStartUtc
patient_skincare_records.created_at < monEndUtc
patient_skincare_records.is_deleted = false
```

排序：

```text
created_at asc, id asc
```

然后批量加载关联数据：

1. 根据 `patient_skincare_records.patient_skincare_plan_id` 查询 `patient_skincare_plans`。
2. 根据 `patient_skincare_plans.skincare_type_id` 查询 `skincare_types`。
3. 根据 plan id 查询 `patient_skincare_plan_attrs`。
4. 根据 record id 查询 `patient_skincare_record_attrs`。
5. 根据 attr id 查询 `skincare_type_attributes`。
6. 根据记录人字段批量查询 `accounts`，显示 `accounts.name`。

所有关联表都应过滤 `is_deleted = false`。

## 字段生成规则

### record_time

来源：

```text
patient_skincare_records.created_at
```

显示格式建议：

```text
yyyy-MM-dd HH:mm
```

时间需要从 UTC 转为 `ConfigProtoService.getConfig().getZoneId()` 对应本地时间。

### content

中间列合并显示皮肤护理类型、计划属性和本次记录属性。

```text
skincare_types.name + " " + planAttrsText + "; " + recordAttrsText
```

拼接时需要避免空片段产生多余分隔符：

```text
typeName + (planAttrsText 为空 ? "" : " " + planAttrsText)
         + (recordAttrsText 为空 ? "" : "; " + recordAttrsText)
```

`planAttrsText` 为 `patient_skincare_plan_attrs` 对应属性文本：

```text
skincare_type_attributes.attr_name + ": " + formattedValue
```

多条属性用 `, ` 拼接，并按：

```text
skincare_type_attributes.display_order asc, skincare_type_attributes.id asc
```

排序。

`recordAttrsText` 为 `patient_skincare_record_attrs` 对应属性文本：

```text
skincare_type_attributes.attr_name + ": " + formattedValue
```

多条属性用 `, ` 拼接，并按：

```text
skincare_type_attributes.display_order asc, skincare_type_attributes.id asc
```

排序。

### recorded_by

使用：

```text
patient_skincare_records.modified_by
```

处理规则：

1. `modified_by` 为空时，输出空字符串。
2. `modified_by` 非空时，尝试转为 `Long` 并匹配 `accounts.id`。
3. 找到账号时，显示 `accounts.name`。
4. 无法解析或找不到账号时，`log.warn`，并回退显示原始 `modified_by`。

## 属性过滤

新增配置：

```properties
jingyi.report.compact.skincare.attr-code-whitelist=
```

建议语义：

1. 配置为空时，不按白名单过滤。
2. 配置非空时，按英文逗号切分、trim 后得到白名单。
3. 仅保留 `skincare_type_attributes.attr_code` 在白名单中的属性。

不使用 `skincare_type_attributes.show_in_table` 作为过滤条件。

## value 解码与格式化

`patient_skincare_plan_attrs.value` 和 `patient_skincare_record_attrs.value` 都是序列化的 `GenericValuePB`。

`skincare_type_attributes.attr_type_pb` 是序列化的 `ValueMetaPB`。

实现时应：

1. 用现有 `ProtoUtils` 解码 `GenericValuePB`。
2. 用现有 `ProtoUtils` 解码 `ValueMetaPB`。
3. 用 `ValueMetaUtils` 按 `ValueMetaPB` 将 `GenericValuePB` 格式化为展示文本。
4. 格式化结果为空时，跳过该属性。

不建议直接显示 `value` 原始字符串。

## 折行

`table-259` 每列需要根据对应 `JfkTablePB` 提取：

- `cell_widths`
- `font_size`
- `char_spacing`
- `h_padding`

折行实现复用现有 `JfkPdfUtils.getWrappedLines(...)` 逻辑，所有输出统一写入 `JfkValPB.strs_val`。

如果属性文本本身包含换行符，建议保留原始换行，并在每个原始行内再按列宽折行。

## 无数据行为

建议与护理记录、管道记录保持一致：

- 无皮肤护理记录时，静态 `table-257/table-258` 仍显示。
- 动态明细 `table-259` 不渲染。

## 错误处理

建议复用 JFK 数据源现有错误码：

- 缺少 `pid/query_start/table_id`：`JFK_MISSING_REQUIRED_FIELD`
- `query_start` 非法：`INVALID_TIME_FORMAT`
- 找不到患者：`PATIENT_NOT_FOUND`
- 找不到 `balance_stats_shifts`：`BALANCE_STATS_SHIFT_NOT_FOUND`
- `mon_start_hour/start_hour` 都为空或非法：`INVALID_PARAM_VALUE`

关联数据处理建议：

- 找不到 plan/type/attribute：`log.warn` 后跳过对应行或属性。
- 找不到账号：`log.warn` 后回退显示原始账号字段。
- 属性 value 解码失败：`log.warn` 后跳过该属性。

## 预计实现范围

实现时预计需要修改：

- `src/main/resources/config/pbtxt/icis_config.pb.txt`
  - 新增 `patient_skincare_records` 数据源元数据。
- `src/main/resources/config/pbtxt/report_compact.pb.txt`
  - 新增 `table-257/table-258/table-259`。
- `src/main/resources/application.properties`
  - 新增 `jingyi.report.compact.skincare.attr-code-whitelist=`。
- `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/ReportProperties.java`
  - 新增 compact skincare 配置。
- `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/JfkDataSourceIds.java`
  - 新增 `PATIENT_SKINCARE_RECORDS`。
- `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources`
  - 新增 `PatientSkincareRecordsDataSourceHandler`。
- `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport/CompactReportDataSourceBuilder.java`
  - 为 `patient_skincare_records` 表格补齐 `table_id/col_widths/font_size/char_spacing/h_padding` 输入。
- 相关 repository 如现有查询不满足 `[start, end)`，需要新增显式查询方法。

测试建议：

- `PatientSkincareRecordsDataSourceHandlerTests`
  - 时间窗解析。
  - plan attrs / record attrs 合并输出到 `content`。
  - 白名单过滤。
  - 属性按 `display_order/id` 排序。
  - 记录人账号解析。
  - 无数据返回 0 行。
- `CompactReportDataSourceBuilderTests`
  - 表格 scoped 输入补齐。
- pbtxt/proto 解析测试。

## 待确认问题

暂无。
