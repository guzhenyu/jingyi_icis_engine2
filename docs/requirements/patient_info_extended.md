# patient_info_extended JFK 数据源需求

## 背景

当前 `patient_info` 数据源只输出 compact/standard 报表中常用的基础患者信息，元数据位于 `src/main/resources/config/pbtxt/icis_config.pb.txt:9485-9588`，实现位于 `PatientInfoDataSourceHandler`。

精简重症监护记录单需要额外展示身高、体重、入科日期、病情分级、术后天数、记录日期、诊断及手术、交班信息等字段，因此新增 `patient_info_extended` 数据源。

本文档整理 `patient_info_extended` 的配置、数据查询、格式化和测试要求。

## 目标

新增 JFK 数据源：

```text
id: "patient_info_extended"
name: "扩展病人信息"
```

位置要求：

```text
src/main/resources/config/pbtxt/icis_config.pb.txt:9588-9589
```

即插入在现有 `patient_info` 数据源之后、`test_data_source1` 之前。

实现类建议：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientInfoExtendedDataSourceHandler.java
```

## 输入字段

`patient_info_extended` 输入字段：

1. `pid`
2. `query_start`
3. `query_end`

输入校验：

1. `pid` 缺失或 `pid <= 0`：返回 `JFK_MISSING_REQUIRED_FIELD` 或 `INVALID_PARAM_VALUE`。
2. `query_start` 缺失：返回 `JFK_MISSING_REQUIRED_FIELD`。
3. `query_start` 非法：返回 `INVALID_TIME_FORMAT`。
4. `query_end` 为可选输入，不做必填校验；即使传入，也不参与监测窗口计算。

## 输出字段

所有输出字段均为 `STRING`，`content_type = JFK_DATA_SOURCE`，非数组。

| 字段 ID | 名称 | 来源与格式 |
| --- | --- | --- |
| `dept_name` | 科室 | 与现有 `patient_info` 基本一致 |
| `bed_no` | 床号 | 与现有 `patient_info` 的床位历史逻辑一致，取值时间点使用 `monEndUtc` |
| `patient_name` | 姓名 | `patient_records.icu_name` |
| `gender` | 性别 | 与现有 `patient_info` 一致：`icu_gender == 1` 显示男，`0` 显示女，其他显示未知 |
| `age` | 年龄 | 按 `monEndLocal` 对应日期计算 |
| `mrn` | 住院号 | `patient_records.his_mrn` |
| `diagnosis` | 诊断 | `patient_records.diagnosis` |
| `height` | 身高 | `patient_records.height`，整数 + `cm`，例：`179cm` |
| `weight` | 体重 | `patient_records.weight`，整数 + `kg`，例：`70kg` |
| `admission_time_yyyymmdd` | 入科日期 | `patient_records.admission_time` 转为配置 `zoneId` 所在本地日期，格式 `yyyyMMdd`，例：`20260324` |
| `illness_severity_level` | 病情分级 | `patient_records.illness_severity_level` 匹配 `ConfigProtoService.getConfig().getPatient().getEnumsV2().getIllnessSeverityLevelList()` 后显示枚举名 |
| `days_after_surgery` | 术后天数 | 根据最新手术 `end_time` 和 `monEndUtc` 计算，满 24 小时算 1 天，例：`9天`；找不到手术显示 `天` |
| `mon_record_day_range` | 记录日期 | 根据 `monStartLocal` 到 `monEndLocal` 显示，例：`20260401-20260402` |
| `diagnosis_and_surgery` | 诊断及手术 | `diagnosis`、手术名称组合，具体拼接规则见下文 |
| `patient_shift_info` | 交班信息 | 查询 `patient_shift_records`，按 `shift_start` 排序后拼接 |

## 监测窗口计算

本数据源需要复用 `patient_monitoring_records` 的监测窗口口径，计算 `monStartUtc`、`monEndUtc`、`monStartLocal`、`monEndLocal`。

给定 `query_start`：

1. `utcStart = TimeUtils.fromIso8601String(query_start, "UTC")`
2. `utcEnd = utcStart.plusHours(24)`
3. 根据 `pid` 获取 `patient_records`，以患者自己的 `dept_id` 为准。
4. 查询 `balance_stats_shifts`：
   - `dept_id = patient.dept_id`
   - `effective_time < utcEnd`
   - `is_deleted = false`
   - `order by effective_time desc`
   - 取第一条
5. 若未找到，沿用 `MonitoringTimeRangeDataSourceHandler` 的行为，返回 `BALANCE_STATS_SHIFT_NOT_FOUND`。
6. 读取班次中的 `mon_start_hour`：
   - 若 `mon_start_hour` 合法，使用它。
   - 若为空或非法，回退到 `start_hour`。
   - 若 `start_hour` 也为空或非法，返回 `INVALID_PARAM_VALUE`，错误信息包含 `dept_id` 和 `balance_stats_shift.id`。
7. 计算本地午夜：
   - `utcMiddle = utcStart.plusHours(12)`
   - `localMiddle = TimeUtils.getLocalDateTimeFromUtc(utcMiddle, zoneId)`
   - `localMidnight = localMiddle.toLocalDate().atStartOfDay()`
8. 计算监测窗口：
   - `monStartLocal = localMidnight.plusHours(monStartHour)`
   - `monStartUtc = TimeUtils.getUtcFromLocalDateTime(monStartLocal, zoneId)`
   - `monEndUtc = monStartUtc.plusHours(24)`
   - `monEndLocal = TimeUtils.getLocalDateTimeFromUtc(monEndUtc, zoneId)`

本次新增公共 `MonitoringWindowResolver` 承载这套窗口计算，避免 `patient_info_extended` 再复制一份。

## 基础患者信息

按 `pid` 查询 `patient_records`。

处理要求：

1. 患者不存在：返回 `PATIENT_NOT_FOUND`，错误信息包含 `pid`。
2. 患者 `dept_id` 为空：返回 `DEPT_NOT_FOUND` 或 `INVALID_PARAM_VALUE`，错误信息包含 `pid`。
3. `dept_name` 参考现有 `patient_info` 逻辑：通过 `RbacDepartmentRepository.findByDeptId(patient.dept_id)` 获取配置科室名；找不到科室时返回 `DEPT_NOT_FOUND`。
4. `bed_no` 参考现有 `JfkDataSourceSupport` 的床位历史逻辑，时间点使用 `monEndUtc`。
5. `age` 按 `monEndLocal` 对应日期计算，格式为 `N岁`；生日为空时输出空字符串。

## 身高与体重

### height

来源：

```text
patient_records.height
```

格式：

```text
整数高度 + "cm"
```

例：

```text
179cm
```

规则：

1. `height == null` 或 `height <= 0` 时输出空字符串。
2. 有小数时按四舍五入为整数。

### weight

来源：

```text
patient_records.weight
```

格式：

```text
整数体重 + "kg"
```

例：

```text
70kg
```

规则：

1. `weight == null` 或 `weight <= 0` 时输出空字符串。
2. 有小数时按四舍五入为整数。

## 入科日期

字段：

```text
admission_time_yyyymmdd
```

来源：

```text
patient_records.admission_time
```

处理：

1. 将 UTC `admission_time` 转为 `ConfigProtoService.getConfig().getZoneId()` 对应的本地时间。
2. 取本地日期。
3. 格式化为 `yyyyMMdd`。

例：

```text
20260324
```

空值处理：

```text
admission_time == null -> ""
```

## 病情分级

字段：

```text
illness_severity_level
```

来源：

```text
patient_records.illness_severity_level
```

枚举来源：

```java
ConfigProtoService.getConfig()
    .getPatient()
    .getEnumsV2()
    .getIllnessSeverityLevelList()
```

当前配置参考：

```text
1 -> 轻度
2 -> 中度
3 -> 重度
4 -> 危重
5 -> 极危重
```

匹配规则：

1. 将 `patient_records.illness_severity_level` 解析为整数 id。
2. 在枚举列表中按 `id` 匹配。
3. 匹配成功输出枚举 `name`。
4. 解析失败或匹配失败时 `log.warn`，输出空字符串。

## 术后天数

字段：

```text
days_after_surgery
```

来源：

```text
surgery_history
```

查询：

```text
patient_id = pid
```

处理：

1. 查询该患者所有手术记录。
2. 过滤 `end_time != null`。
3. 按 `end_time desc` 排序。
4. 取最大 `end_time`。
5. 根据 `monEndUtc` 计算术后天数：

```text
days = floor(Duration.between(latestSurgeryEndTime, monEndUtc).toHours() / 24)
```

输出：

```text
days + "天"
```

例：

```text
9天
```

如果找不到手术记录或没有有效 `end_time`：

```text
"天"
```

补充规则：

1. 若最新手术 `end_time > monEndUtc`，输出 `0天`。
2. `end_time` 是 UTC 存储，按 UTC 直接与 `monEndUtc` 比较。

## 记录日期

字段：

```text
mon_record_day_range
```

来源：

```text
monStartLocal
monEndLocal
```

格式：

```text
yyyyMMdd-yyyyMMdd
```

例：

```text
20260401-20260402
```

说明：

1. `monStartLocal` 来自监测窗口开始时间。
2. `monEndLocal` 来自 `monEndUtc` 转本地时间。
3. 结束日期使用 `monEndLocal` 的日期，不展示小时、分钟、秒。

## 诊断及手术

字段：

```text
diagnosis_and_surgery
```

数据来源：

1. `patient_records.diagnosis`
2. `surgery_history.name`

手术查询：

```text
patient_id = pid
```

手术排序：

```text
end_time desc
```

手术名称拼接：

```text
name1 + " + " + name2 + ...
```

拼接规则：

1. 收集非空 `diagnosis`。
2. 若手术名称列表非空，追加：

```text
手术: name1 + name2 + ...
```

3. 最终使用 `"; "` 连接。

例：

```text
ICU诊断; 手术: 手术A + 手术B
```

若 `diagnosis` 为空但存在手术名称，直接展示 `手术: ...`。

## 交班信息

字段：

```text
patient_shift_info
```

来源：

```text
patient_shift_records
```

查询范围：

```text
pid = pid
is_deleted = false
shift_start >= monStartUtc
shift_end <= monEndUtc
```

排序：

```text
shift_start asc
```

显示：

```text
shift_name + ": " + shift_nurse_name
```

多条记录之间使用两个空格连接：

```text
早班: 张三  晚班: 李四
```

补充规则：

1. 时间范围使用“完整包含”：`shift_start >= monStartUtc && shift_end <= monEndUtc`。
2. 只展示 `shift_name + ": " + shift_nurse_name`，不包含 `content`。

## 元数据变更

在 `icis_config.pb.txt` 中新增 `data_sources` 块。

输入：

```text
input_field_ids: "pid"
input_field_ids: "query_start"
input_field_ids: "query_end"
```

输出字段：

```text
dept_name
bed_no
patient_name
gender
age
mrn
diagnosis
height
weight
admission_time_yyyymmdd
illness_severity_level
days_after_surgery
mon_record_day_range
diagnosis_and_surgery
patient_shift_info
```

每个输出字段使用：

```text
val_type: 4 # STRING
default_val { str_val: "" }
content_type: 4 # JFK_DATA_SOURCE
is_array: false
is_options: false
```

## 实现设计

### Handler

新增：

```text
PatientInfoExtendedDataSourceHandler extends AbstractJfkDataSourceHandler
```

`getMetaId()` 返回：

```text
patient_info_extended
```

依赖：

1. `JfkDataSourceSupport`
2. `MonitoringWindowResolver`
3. `SurgeryHistoryRepository`
4. `PatientShiftRecordRepository`
5. `ConfigProtoService`

`JfkDataSourceSupport` 已提供：

1. `getPatientService()`
2. `getDeptRepo()`
3. `getZoneId()`
4. `getStatusMsgList()`
5. `addStrOutput(...)`

### 公共监测窗口 helper

`patient_monitoring_records` 和 `patient_info_extended` 都需要从 `pid + query_start` 计算 `monStartUtc/monEndUtc`。本次新增公共 helper：

```text
MonitoringWindowResolver
```

返回：

```java
record MonitoringWindow(
    PatientRecord patient,
    String deptId,
    LocalDateTime monStartUtc,
    LocalDateTime monEndUtc,
    LocalDateTime monStartLocal,
    LocalDateTime monEndLocal,
    int monStartHour
)
```

好处：

1. 避免两个 handler 复制 `balance_stats_shifts` 查询和 `mon_start_hour` 回退逻辑。
2. 保证 `patient_monitoring_records`、`monitoring_time_range`、`patient_info_extended` 的 24 小时窗口口径一致。
3. 后续 compact 其他数据源可以复用。

## 测试计划

新增测试目录：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources
```

新增测试：

```text
PatientInfoExtendedDataSourceHandlerTests
```

建议覆盖：

1. 缺少 `pid/query_start` 时返回缺字段错误；`query_end` 可为空。
2. `query_start` 非法时返回 `INVALID_TIME_FORMAT`。
3. 患者不存在时返回 `PATIENT_NOT_FOUND`。
4. 无 `balance_stats_shifts` 时返回 `BALANCE_STATS_SHIFT_NOT_FOUND`。
5. `mon_start_hour` 为空时回退 `start_hour`。
6. 正常输出基础字段：`dept_name`、`bed_no`、`patient_name`、`gender`、`age`、`mrn`、`diagnosis`。
7. `height/weight` 格式化为整数 + 单位。
8. `admission_time_yyyymmdd` 使用配置 `zoneId` 转本地日期。
9. `illness_severity_level` 能按 config enum id 转显示名。
10. 有手术记录时，`days_after_surgery` 按满 24 小时计算；无手术时输出 `天`。
11. `mon_record_day_range` 按 `monStartLocal~monEndLocal` 格式化。
12. `diagnosis_and_surgery` 按 `end_time desc` 拼接手术名称。
13. `patient_shift_info` 按 `shift_start asc` 拼接。

## 已确认口径

1. `query_end` 不做必填校验；为空时不报错，实际时间窗固定按 `query_start + 24h` 派生。
2. `bed_no` 沿用现有 `patient_info` 的床位历史逻辑，时间点使用 `monEndUtc`。
3. `age` 按 `monEndLocal` 对应日期计算。
4. `height/weight` 为空或数据库值为 `0` 时输出空字符串。
5. `illness_severity_level` 匹配不到枚举时 `log.warn`，输出空字符串。
6. `days_after_surgery` 如果手术 `end_time > monEndUtc`，输出 `0天`。
7. `mon_record_day_range` 的结束日期使用 `monEndLocal` 日期。
8. `diagnosis` 为空但有手术时，直接展示 `手术: xxx`。
9. `patient_shift_info` 只显示 `班次: 护士`，不包含 `content`。
10. `patient_shift_records` 的时间条件是整条交班记录完整落在 `[monStartUtc, monEndUtc)` 内。
