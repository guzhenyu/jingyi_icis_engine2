# Compact 报表 `patient_nursing_records` 数据源收缩需求

## 背景

当前 `PatientNursingRecordsDataSourceHandler` 会把三类数据合并后统一输出到 `patient_nursing_records`：

1. `nursing_records`
2. `nursing_execution_records + nursing_orders.name` 拼出的护理计划执行记录
3. 非整点 `patient_monitoring_records` 汇总记录

本次需求不做实现，只整理拆分后的目标边界：

1. 非整点观察记录拆到独立数据源和独立表格，见 `docs/requirements/compact_patient_non_hourly_monitoring_records.md`
2. 护理计划执行记录拆到独立数据源 `patient_nursing_orders`，见 `docs/requirements/compact_patient_nursing_orders.md`
3. `patient_nursing_records` 收缩为“只负责护理记录本身”

本文档用于覆盖旧版“一个数据源合并三类记录”的需求描述。

## 本次需求范围

保留当前 compact 报表里的护理记录区域：

- `table-254`：标题 `护理记录`
- `table-255`：表头 `时间 / 护理记录 / 记录人 / 审核人`
- `table-256`：动态明细，`data_source_meta_id = "patient_nursing_records"`

本次收缩后：

- `table-256` 仍绑定 `patient_nursing_records`
- `patient_nursing_records` 只返回 `nursing_records`
- 不再返回非整点观察项汇总
- 不再返回当前由 `nursing_execution_records` 生成的护理计划执行记录

## 已确认决策

1. `report_compact.pb.txt` 中 `table-254/table-255/table-256` 的表格结构不是本次需求重点，不要求重新设计。
2. `patient_nursing_records` 的时间窗口继续复用 `MonitoringWindowResolver`。
3. `patient_nursing_records` 只查询 `nursing_records`，查询口径保持 `[monStartUtc, monEndUtc)`。
4. `record_time`、`content` 继续使用 `STRINGS/strs_val`。
5. `recorded_by`、`reviewed_by` 继续沿用签名图片口径，即 `val_type: 7 # NURSING_SIGN_PIC`。
6. `nursing_records.content` 保留原始换行，并在每个逻辑行内按列宽折行。
7. 无数据时继续保持静态标题和静态表头显示，仅动态明细为空。

## 当前代码现状

涉及路径：

- `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientNursingRecordsDataSourceHandler.java`
- `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport/CompactReportDataSourceBuilder.java`
- `src/main/resources/config/pbtxt/icis_config.pb.txt`
- `src/main/resources/config/pbtxt/report_compact.pb.txt`

当前实现中：

1. `CompactReportDataSourceBuilder` 已按 table-scoped 方式构造 `patient_nursing_records` 输入。
2. `JfkDataSourceIds.PATIENT_NURSING_RECORDS` 已存在，且已纳入 `isCompactTableScoped(...)`。
3. `icis_config.pb.txt` 中 `patient_nursing_records.recorded_by/reviewed_by` 当前已是 `NURSING_SIGN_PIC`，不是纯字符串字段。
4. 单测 `PatientNursingRecordsDataSourceHandlerTests` 当前显式验证了“三类数据合并输出”的旧行为，后续实现时必须同步拆分测试。

## 数据源定义

沿用现有数据源：

```text
id: "patient_nursing_records"
name: "护理记录"
```

输入字段保持不变：

- `pid`
- `dept_id`
- `query_start`
- `query_end`
- `table_id`
- `col_widths`
- `font_size`
- `char_spacing`
- `h_padding`

输出字段保持不变：

- `record_time`
- `content`
- `recorded_by`
- `reviewed_by`

但输出来源调整为仅来自 `nursing_records`。

## 查询与字段映射

### 数据来源

仅保留 `nursing_records`：

```text
nursing_records.patient_id = pid
nursing_records.effective_time >= monStartUtc
nursing_records.effective_time < monEndUtc
nursing_records.is_deleted = false
order by effective_time asc, id asc
```

建议继续复用已有 repository 方法：

```java
NursingRecordRepository.findReportNursingRecords(...)
```

### 输出映射

| 输出字段 | 来源 | 备注 |
| --- | --- | --- |
| `record_time` | `nursing_records.effective_time` | 转本地时间，格式 `yyyy-MM-dd HH:mm` |
| `content` | `nursing_records.content` | 保留原始换行，再按列宽折行 |
| `recorded_by` | `created_by / created_by_account_name` | 通过 `JfkSignatureValueResolver` 输出签名或回退值 |
| `reviewed_by` | `reviewed_by / reviewed_by_account_name` | 通过 `JfkSignatureValueResolver` 输出签名或回退值 |

## 对现有实现的影响

`PatientNursingRecordsDataSourceHandler` 后续实现时应删除两段逻辑：

1. `nursingExecutionRecordRepo.findReportNursingExecutionRecords(...)`
2. `monitoringRecordRepo.findByPidAndEffectiveTimeRange(...)` 以及后续 `monitoringRows(...)`

同时需要移除：

1. `accountNameByCompletedBy(...)`
2. `accountNameByModifiedBy(...)`
3. `monitoringParamOrder(...)`
4. `monitoringRecordComparator(...)`
5. 当前 `SOURCE_ORDER_NURSING_EXECUTION`、`SOURCE_ORDER_MONITORING` 相关排序逻辑

收缩后的排序规则将简化为：

1. 按 `nursing_records.effective_time` 升序
2. 同时刻按 `nursing_records.id` 升序

## 测试调整建议

后续真正实现时，建议把现有测试拆成三块：

1. `PatientNursingRecordsDataSourceHandlerTests`
   - 只验证 `nursing_records`
   - 验证签名解析
   - 验证换行和折行
   - 验证空数据输出
2. 新增“非整点观察记录” handler 测试
3. 新增“护理计划执行记录” handler 测试

当前以下旧断言应从 `PatientNursingRecordsDataSourceHandlerTests` 移除：

- 护理执行记录内容 `吸痰护理(备注: 痰液较多)`
- 非整点观察汇总内容 `体温: 36.8 ℃; 心率: 88 次/分`
- 三类来源按同一时间混排输出

## 执行卡点

1. 拆分后的 `patient_nursing_orders` 将使用独立的 3 列表格 `record_time/content/recorded_by`，相关测试和 renderer synthetic 数据需要同步从 `patient_nursing_records` 迁出，不能只改 handler。
2. 当前 `patient_nursing_records` 的签名字段已经接入 `compact_signature_image_fields` 相关约束。若后续有人想把 `recorded_by/reviewed_by` 改成纯文本，必须同步更新对应横向需求文档。
