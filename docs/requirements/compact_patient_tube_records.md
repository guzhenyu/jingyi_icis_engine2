# 精简重症监护记录单 patient_tube_records 表格需求

更新时间：2026-04-18

## 背景

精简重症监护记录单 `report_compact.pb.txt` 中已经存在管道分段标题：

- `table-251`：静态分段标题，当前显示 `管道`

本次目标是在 `table-251` 后追加管道维护记录表格：

- `table-252`：静态表头，显示 `管道名称 / 置管时间 / 持续天数 / 管道维护状态`
- `table-253`：动态明细表，数据源为 `patient_tube_records`

本文件只整理需求和待确认问题，不做代码实现。

## 目标

新增 compact 报表数据源：

```text
id: "patient_tube_records"
name: "管道维护记录"
```

`table-252/table-253` 均为 4 列，列宽一致：

| 列 | 字段 | 标题 | 宽度 |
| --- | --- | --- | --- |
| 1 | `tube_name` | 管道名称 | 100 |
| 2 | `inserted_at` | 置管时间 | 100 |
| 3 | `duration_days` | 持续天数 | 80 |
| 4 | `maintenance_status` | 管道维护状态 | 536 |

列宽总和为 `816`。如果继续沿用当前模板其他表格的 `x: 12`、`line_width: 0.5`，实现时需要确认整体宽度与页面右边界一致。

## 已确认决策

1. 管道行筛选口径：`[inserted_at, removed_at]` 与 `[monStartUtc, monEndUtc)` 有交集的管道都选进来；`removed_at == null` 视为持续到未来。
2. `table-252` 是静态表头；动态明细表改名为 `table-253`。
3. 数据源不输出表头行，只返回数据行。
4. `duration_days` 的计算截止点为 `min(monStartLocal, removed_at == null ? 9999-01-01 : removedAtLocal)`。
5. 如果一条管道是换管链中的中间/后续记录，维护状态只取当前 `patient_tube_record_id` 的状态，不取 root 链上的其他管道状态。
6. 如果 `root_tube_record_id` 指向的根记录已删除或查不到，`log.warn` 后回退当前记录 `inserted_at`；如果 `root_tube_record_id` 与当前记录 `id` 相同，不需要额外查询。
7. `first-record-in-shift` 使用 Spring 风格 boolean 配置。
8. 一个班次没有任何状态记录时，`maintenance_status` 跳过该班次。
9. `tube_status_id` 找不到 `tube_type_statuses` 时，跳过该状态并 `log.warn`。
10. `patient_tube_status_records.value` 为空时跳过该状态。
11. `inserted_at` 显示格式使用 `yyyy-MM-dd HH:mm`。
12. 无管道记录时，只隐藏动态明细表 `table-253`；静态表头 `table-252` 仍显示。

## 非目标

本次不实现：

- 管道录入、换管、拔管、删除等业务流程修改。
- `patient_tube_records` / `patient_tube_status_records` 写入逻辑。
- PDF renderer 的新布局能力。`table-253` 应复用现有 JFK 弹性表格能力。

## 相关数据表

### patient_tube_records

来源：

```text
src/main/resources/config/db/schema.postgresql.sql:1161
```

关键字段：

| 字段 | 用途 |
| --- | --- |
| `id` | 管道记录 ID |
| `pid` | 患者 ID |
| `tube_type_id` | 管道类型，用于查状态元数据 |
| `tube_name` | 管道名称 |
| `inserted_at` | 当前管道记录的置管时间，UTC |
| `removed_at` | 拔管时间，UTC，可为空 |
| `prev_tube_record_id` | 换管链上一条 |
| `root_tube_record_id` | 换管链根记录 |
| `is_deleted` | 逻辑删除 |

现有 repository：

```text
PatientTubeRecordRepository
```

已有方法可复用：

- `findByPidAndDateRange(pid, queryStart, queryEnd)`：现有 JPQL 条件接近本需求口径：`insertedAt < queryEnd AND (removedAt IS NULL OR removedAt >= queryStart)`。
- `findByRootTubeRecordIdInAndIsDeletedFalse(rootIds)`：查找同一 root 链记录。
- `findByIdInAndIsDeletedFalse(ids)`：按 ID 批量查记录。

本期查询口径建议显式使用：

```text
pid = pid
is_deleted = false
inserted_at < monEndUtc
and (removed_at is null or removed_at >= monStartUtc)
```

说明：

- 这是 `[inserted_at, removed_at]` 与 `[monStartUtc, monEndUtc)` 有交集的判断。
- 因为左侧管道区间右端是闭区间，`removed_at == monStartUtc` 仍算有交集。

### patient_tube_status_records

来源：

```text
src/main/resources/config/db/schema.postgresql.sql:1226
```

关键字段：

| 字段 | 用途 |
| --- | --- |
| `patient_tube_record_id` | 对应 `patient_tube_records.id` |
| `tube_status_id` | 对应 `tube_type_statuses.id` |
| `value` | 状态值 |
| `recorded_at` | 记录时间，UTC |
| `is_deleted` | 逻辑删除 |

现有 repository：

```text
PatientTubeStatusRecordRepository
```

已有方法：

- `findByPatientTubeRecordIdInAndIsDeletedFalseAndRecordedAtBetween(recordIds, start, end)`

注意：Spring Data 的 `Between` 通常是闭区间语义。实现本需求时建议新增显式 `@Query`：

```text
patient_tube_record_id in (:recordIds)
is_deleted = false
recorded_at >= :monStartUtc
recorded_at < :monEndUtc
```

### tube_type_statuses

来源：

```text
src/main/resources/config/db/schema.postgresql.sql:1131
```

关键字段：

| 字段 | 用途 |
| --- | --- |
| `id` | 状态 ID |
| `tube_type_id` | 管道类型 ID |
| `name` | 状态展示名 |
| `display_order` | 状态显示顺序 |
| `is_deleted` | 逻辑删除 |

实现 `maintenance_status` 时需要 `id/name/display_order`。建议新增批量查询方法，一次性取回这些字段，避免 N+1 查询。

## 时间窗口

### 输入

`patient_tube_records` 至少包含输入：

- `pid`
- `query_start`
- `query_end`

由于 `table-253` 的第 4 列可能很长，需要折行，建议沿用其他 compact table-scoped 数据源输入：

- `dept_id`
- `table_id`
- `col_widths`
- `font_size`
- `char_spacing`
- `h_padding`

### monStartUtc / monEndUtc

时间窗口采用观察项窗口口径：

1. `query_start` 通过 `TimeUtils.fromIso8601String(query_start, "UTC")` 转成 `utcStart`。
2. `utcEnd = utcStart + 24h`。
3. 根据 `pid` 查询患者实际 `dept_id`；如果 request `dept_id` 与患者实际 `dept_id` 不一致，`log.warn` 后以患者 `dept_id` 为准。
4. 查询 `effective_time < utcEnd` 的最后一条有效 `balance_stats_shifts`。
5. 优先使用 `balance_stats_shifts.mon_start_hour`；为空或非法时回退 `start_hour`。
6. 根据 `zoneId` 得到本地午夜 `localMidnight`。
7. `monStartLocal = localMidnight + mon_start_hour`。
8. `monStartUtc = TimeUtils.getUtcFromLocalDateTime(monStartLocal, zoneId)`。
9. `monEndUtc = monStartUtc + 24h`。

`query_end` 只保留为标准输入，不参与窗口计算。

如果找不到 `balance_stats_shifts`，沿用现有 `BALANCE_STATS_SHIFT_NOT_FOUND`。

## 输出字段

所有输出字段统一使用：

```text
val_type: 9 # STRINGS
content_type: 4 # JFK_DATA_SOURCE
is_array: true
is_options: false
```

原因：

- `maintenance_status` 可能需要按第 4 列宽度折行。
- 统一使用 `strs_val` 可复用现有 JFK 弹性表格多行渲染。

字段定义：

| 字段 | 说明 |
| --- | --- |
| `tube_name` | 管道名称 |
| `inserted_at` | 置管时间，本地时间文本，格式 `yyyy-MM-dd HH:mm` |
| `duration_days` | 持续天数，例如 `1天`、`2天` |
| `maintenance_status` | 管道维护状态拼接文本 |

数据源只返回数据行，不返回表头行。

无管道记录时：

- 输出字段仍存在。
- 每个输出字段的 `vals` 长度为 0。
- 动态表格 `table-253` 不渲染。

## 行筛选与排序

行来源：

```text
pid = pid
is_deleted = false
inserted_at < monEndUtc
and (removed_at is null or removed_at >= monStartUtc)
```

对每条命中的 `patient_tube_records`：

1. 计算 root 置管时间。
2. 输出 `tube_name` 使用当前记录的 `tube_name`。
3. 输出 `inserted_at` 使用 root 置管时间。
4. 输出 `maintenance_status` 只使用当前记录 `id` 对应的状态。
5. 输出行按照 root 置管时间升序排序；root 置管时间相同时，按当前记录 `id` 升序稳定排序。

root 置管时间规则：

- 如果 `root_tube_record_id` 为空，则把当前记录 `id` 视为 root id。
- 如果 root id 等于当前记录 id，则 root 置管时间直接使用当前记录 `inserted_at`，不用额外查询。
- 如果 root id 与当前记录 id 不同，批量查询 root 记录或 root 链记录。
- 如果 root 记录已删除或查不到，`log.warn` 后回退当前记录 `inserted_at`。
- 如果同一 root 链查到多条未删除记录，以最早的 `inserted_at` 作为 root 置管时间。

## 字段生成规则

### tube_name

来源：

```text
patient_tube_records.tube_name
```

### inserted_at

来源：

```text
root_patient_tube_records.inserted_at
```

格式：

```text
yyyy-MM-dd HH:mm
```

转换：

```text
TimeUtils.getLocalDateTimeFromUtc(rootInsertedAt, zoneId)
```

### duration_days

持续天数按 `mon_start_hour` 轮转。

用户给定示例：

```text
mon_start_hour = 7
当地时间 2026-04-17 06:40 置入一根管道
2026-04-17 06:59 仍算 1 天
2026-04-17 07:00 算 2 天
```

计算口径：

1. `rootInsertedLocal = rootInsertedAt` 转本地时间。
2. `removedAtLocal = removed_at == null ? 9999-01-01T00:00 : removed_at` 转本地时间。
3. `cutoffLocal = min(monStartLocal, removedAtLocal)`。
4. 找到 `rootInsertedLocal` 所属的护理日边界 `insertedShiftStartLocal`：
   - 如果 `rootInsertedLocal.hour < mon_start_hour`，边界为前一天 `mon_start_hour`。
   - 否则边界为当天 `mon_start_hour`。
5. `durationDays = floor(Duration.between(insertedShiftStartLocal, cutoffLocal).toHours() / 24) + 1`。
6. 输出为 `durationDays + "天"`。

边界说明：

- 如果 cutoff 恰好等于下一个 `mon_start_hour`，持续天数加 1。
- 如果 `removed_at` 早于 `monStartLocal`，以拔管时间作为截止点。

### maintenance_status

目标格式：

```text
A班 2026-03-29 16:38 置入长度: 24cm, 部位情况: 正常, 导管护理: 顺畅
```

如果同一管道在多个班次各有一条维护记录，使用 `strs_val` 多行输出：

```text
A班 2026-03-29 08:20 ...
P班 2026-03-29 16:38 ...
N班 2026-03-30 01:10 ...
```

每个班次最多选择一个 `recorded_at` 时间组：

1. 查询状态：

```text
patient_tube_record_id in (:currentRecordIds)
recorded_at >= monStartUtc
recorded_at < monEndUtc
is_deleted = false
```

2. 只使用当前 `patient_tube_record_id` 的状态，不合并 root 链其他记录。
3. 通过 `ConfigShiftUtils.getShiftByDeptId(deptId)` 获取 `ShiftSettingsPB`。
4. 使用 `ShiftSettingsPB.shift[].default_shift` 定义各班次时间段，忽略 `Shift.name == "全天"` 的配置项。
5. 将每条 `status_record.recorded_at` 转成本地时间后分配到对应班次范围。
6. 同一个班次内，按 `recorded_at` 分组，得到：

```text
Map<(shiftName, shiftRange), Map<recordedAt, List<statusRecords>>>
```

7. 根据配置选择每个班次的一个时间组：
   - `jingyi.report.compact.tube-policy.first-record-in-shift=true`：选最小 `recorded_at`
   - `jingyi.report.compact.tube-policy.first-record-in-shift=false`：选最大 `recorded_at`
8. 一个班次没有任何状态记录时，跳过该班次。
9. 对选中的 `List<statusRecords>`：
   - `value` 为空时跳过。
   - 根据 `tube_status_id` 查询 `tube_type_statuses.name/display_order`。
   - 找不到状态元数据时，`log.warn` 后跳过该状态。
   - 组成 `(status_name, display_order, value)`。
   - 按 `display_order` 升序，再按 `tube_status_id` 升序排序。
   - 每条状态拼接为 `status_name + ": " + value`。
   - 多条状态用 `, ` 拼接。
10. 最终每个班次一条文本：

```text
ShiftSettingsPB.Shift.name + " " + localRecordedAt + " " + statusText
```

11. 多个班次之间按选中的 `recorded_at` 升序排序，作为 `strs_val` 多行输出。

## 班次分配规则

`ShiftSettingsPB.Shift.default_shift`：

```proto
message TimeRange {
    int32 start_hour = 1;
    int32 hours = 2;
}
```

实现建议：

1. 将 `monStartUtc/monEndUtc` 转成本地时间。
2. 对每个 shift 的 `default_shift.start_hour/hours`，构造覆盖 `[monStartLocal, monEndLocal)` 的一个或多个本地时间段；`Shift.name == "全天"` 时跳过。
3. 如果 `start_hour + hours > 24`，班次跨午夜，直接使用 `LocalDateTime start + hours` 处理，不要只比较小时。
4. 只保留与 `[monStartLocal, monEndLocal)` 有交集的班次时间段。
5. `recorded_at` 分配规则使用左闭右开：

```text
shiftStartLocal <= recordedAtLocal < shiftEndLocal
```

6. 如果一个 `recorded_at` 没有落入任何班次，`log.warn` 后跳过该条状态。

## 配置需求

### application.properties

新增：

```properties
# true: 每个班次取最早一组管道维护记录; false: 每个班次取最晚一组管道维护记录
jingyi.report.compact.tube-policy.first-record-in-shift=true
```

### ReportProperties

建议新增：

```java
@Data
public static class Compact {
    private TubePolicy tubePolicy = new TubePolicy();
}

@Data
public static class TubePolicy {
    private boolean firstRecordInShift = true;
}
```

## JFK 元数据需求

### icis_config.pb.txt

新增数据源：

```pbtxt
data_sources {
    id: "patient_tube_records"
    name: "管道维护记录"
    input_field_ids: "pid"
    input_field_ids: "dept_id"
    input_field_ids: "query_start"
    input_field_ids: "query_end"
    input_field_ids: "table_id"
    input_field_ids: "col_widths"
    input_field_ids: "font_size"
    input_field_ids: "char_spacing"
    input_field_ids: "h_padding"
    output_fields {
        id: "tube_name"
        name: "管道名称"
        val_meta { val_type: 9 # STRINGS }
        content_type: 4 # JFK_DATA_SOURCE
        is_array: true
        is_options: false
    }
    output_fields {
        id: "inserted_at"
        name: "置管时间"
        val_meta { val_type: 9 # STRINGS }
        content_type: 4 # JFK_DATA_SOURCE
        is_array: true
        is_options: false
    }
    output_fields {
        id: "duration_days"
        name: "持续天数"
        val_meta { val_type: 9 # STRINGS }
        content_type: 4 # JFK_DATA_SOURCE
        is_array: true
        is_options: false
    }
    output_fields {
        id: "maintenance_status"
        name: "管道维护状态"
        val_meta { val_type: 9 # STRINGS }
        content_type: 4 # JFK_DATA_SOURCE
        is_array: true
        is_options: false
    }
}
```

### JfkDataSourceIds

新增：

```java
public static final String PATIENT_TUBE_RECORDS = "patient_tube_records";
```

并纳入 table-scoped 数据源：

```java
isCompactTableScoped(...)
```

原因：需要按 `table-253` 的列宽、字体和 padding 对 `maintenance_status` 折行。

## report_compact.pb.txt 需求

在 `table-251` 后新增静态表头 `table-252`：

```pbtxt
ac_tables {
  offset_top: 0
  tbl {
    id: "table-252"
    type: "table"
    x: 12
    y: 0
    rows: 1
    cols: 4
    cell_widths: 100
    cell_widths: 100
    cell_widths: 80
    cell_widths: 536
    cell_heights: 12
    line_width: 0.5
    line_color: "#000000"
    column_metas {
      id: "table-252-col-1"
      element_meta { content_type: 1 }
      static_vals: "管道名称"
    }
    column_metas {
      id: "table-252-col-2"
      element_meta { content_type: 1 }
      static_vals: "置管时间"
    }
    column_metas {
      id: "table-252-col-3"
      element_meta { content_type: 1 }
      static_vals: "持续天数"
    }
    column_metas {
      id: "table-252-col-4"
      element_meta { content_type: 1 }
      static_vals: "管道维护状态"
    }
    v_align_id: 2
    h_align_id: 5
    font: "Microsoft YaHei"
    font_size: 6
    font_color: "#000000"
    char_spacing: 0
    h_padding: 2
  }
}
```

再新增动态明细表 `table-253`：

```pbtxt
ac_tables {
  offset_top: 0
  tbl {
    id: "table-253"
    type: "table"
    x: 12
    y: 0
    rows: 1
    cols: 4
    cell_widths: 100
    cell_widths: 100
    cell_widths: 80
    cell_widths: 536
    cell_heights: 12
    line_width: 0.5
    line_color: "#000000"
    column_metas {
      id: "table-253-col-1"
      name: "管道名称"
      element_meta {
        content_type: 4
        data_source_meta_id: "patient_tube_records"
        data_source_field_id: "tube_name"
      }
    }
    column_metas {
      id: "table-253-col-2"
      name: "置管时间"
      element_meta {
        content_type: 4
        data_source_meta_id: "patient_tube_records"
        data_source_field_id: "inserted_at"
      }
    }
    column_metas {
      id: "table-253-col-3"
      name: "持续天数"
      element_meta {
        content_type: 4
        data_source_meta_id: "patient_tube_records"
        data_source_field_id: "duration_days"
      }
    }
    column_metas {
      id: "table-253-col-4"
      name: "管道维护状态"
      element_meta {
        content_type: 4
        data_source_meta_id: "patient_tube_records"
        data_source_field_id: "maintenance_status"
      }
    }
    v_align_id: 2
    h_align_id: 5
    font: "Microsoft YaHei"
    font_size: 6
    font_color: "#000000"
    char_spacing: 0
    h_padding: 2
    data_source_meta_id: "patient_tube_records"
  }
}
```

备注：当前 `table-251` 的 column meta id 似乎仍是 `table-237-col-1`，实现时建议顺手修正为 `table-251-col-1`，避免模板可读性混乱。

## compactreport 输入构造

在 `CompactReportDataSourceBuilder` 中：

1. 收集模板中 `data_source_meta_id == "patient_tube_records"` 的表格。
2. 对每个动态明细表生成 table-scoped input：

```java
.setId(JfkDataSourceIds.compactTableScoped("patient_tube_records", table.getId()))
.setMetaId("patient_tube_records")
.addInputData(pid)
.addInputData(dept_id)
.addInputData(query_start)
.addInputData(query_end)
.addInputData(table_id)
.addInputData(col_widths)
.addInputData(font_size)
.addInputData(char_spacing)
.addInputData(h_padding)
```

3. 当前仅 `table-253` 需要该输入。

## 数据源实现建议

新增：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientTubeRecordsDataSourceHandler.java
```

依赖建议：

- `JfkDataSourceSupport`
- `BalanceStatsShiftRepository`
- `PatientTubeRecordRepository`
- `PatientTubeStatusRecordRepository`
- `TubeTypeStatusRepository`
- `ConfigShiftUtils`
- `ReportProperties`
- `ResourceLoader`

处理流程：

1. 解析输入。
2. 根据 `pid` 和患者实际科室解析 `monStartUtc/monEndUtc/monStartHour`。
3. 查询与窗口有交集的管道记录。
4. 批量补齐 root 管道置管时间。
5. 查询当前记录自身在窗口内的状态记录。
6. 查询状态元数据 `name/display_order`。
7. 按班次和 `first-record-in-shift` 选择状态时间组。
8. 生成 4 个输出字段。
9. 对长文本按 `col_widths/font_size/char_spacing/h_padding` 折行，写入 `JfkValPB.strs_val`。

## 错误处理建议

- `pid` 缺失或非法：返回 `JFK_MISSING_REQUIRED_FIELD`。
- `query_start` 缺失：返回 `JFK_MISSING_REQUIRED_FIELD`。
- `query_start` 非法：返回 `INVALID_TIME_FORMAT`。
- 找不到患者：返回 `PATIENT_NOT_FOUND`。
- 患者 `dept_id` 为空且输入 `dept_id` 也为空：返回 `JFK_MISSING_REQUIRED_FIELD`。
- 找不到 `balance_stats_shifts`：返回 `BALANCE_STATS_SHIFT_NOT_FOUND`。
- `mon_start_hour/start_hour` 均为空或非法：返回 `INVALID_PARAM_VALUE`。
- 找不到班次配置或 `ShiftSettingsPB.shift` 为空：返回 `NO_SHIFT_SETTING`。
- 某个 `tube_status_id` 找不到 `tube_type_statuses`：`log.warn` 后跳过该状态。
- 状态 `value` 为空：跳过该状态。
- 无管道记录：输出 0 行，使 `table-253` 不渲染。

## 测试建议

新增单元测试：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientTubeRecordsDataSourceHandlerTests.java
```

建议覆盖：

1. 能按 `mon_start_hour` 计算 `monStartUtc/monEndUtc`。
2. `mon_start_hour` 为空时回退 `start_hour`。
3. 找不到 `balance_stats_shifts` 返回 `BALANCE_STATS_SHIFT_NOT_FOUND`。
4. `[inserted_at, removed_at]` 与 `[monStartUtc, monEndUtc)` 有交集时纳入管道。
5. `removed_at == monStartUtc` 时纳入管道。
6. `inserted_at == monEndUtc` 时不纳入管道。
7. root 链置管时间取最早记录。
8. root id 等于当前记录 id 时不额外查 root。
9. root 查不到时 warn 并回退当前记录 `inserted_at`。
10. 按 root 置管时间排序输出。
11. `duration_days` 在 `mon_start_hour` 边界前后分别为 `1天/2天`。
12. `duration_days` 截止点使用 `min(monStartLocal, removedAtLocal)`。
13. 状态记录只取当前 `patient_tube_record_id`。
14. 状态记录按 `default_shift` 分配到班次。
15. `first-record-in-shift=true` 取班次内最早 `recorded_at`。
16. `first-record-in-shift=false` 取班次内最晚 `recorded_at`。
17. 无状态记录的班次被跳过。
18. 状态项按 `tube_type_statuses.display_order` 排序。
19. 找不到状态元数据时跳过并 warn。
20. 空 `value` 被跳过。
21. `maintenance_status` 多班次输出为多行 `strs_val`。
22. 状态文本按第 4 列宽度折行。
23. 无管道记录时输出字段存在但 `vals` 长度为 0。

也建议补充：

- `CompactReportDataSourceBuilderTests`：确认 `patient_tube_records` 被构造成 table-scoped input，并带入 `table_id/col_widths/font_size/char_spacing/h_padding`。
- `JfkPdfRendererTests`：使用 synthetic 0 行数据验证 `table-253` 不渲染且不影响后续流式布局；`table-252` 静态表头仍显示。

## 待确认问题

暂无阻塞实现的问题。
