# Compact 报表“非整点观察记录”需求

## 背景

用户要求仿照 `src/main/resources/config/pbtxt/report_compact.pb.txt:2519-2687` 的“血气分析”区块，新增一组“非整点观察记录”表格，并把当前混在 `PatientNursingRecordsDataSourceHandler` 里的非整点观察项数据独立出来。

本次仅产出需求文档，不做 Java / proto / pbtxt 实现。

## 已确认决策

1. 新数据源 `meta_id` 确定为 `patient_non_hourly_monitoring_records`。
2. `table-40-1` 和 `table-40-2` 列宽调整为 `66 / 683.5 / 60`，与同区域标题宽度 `809.5` 对齐。
3. 第三列“记录者”继续沿用 `NURSING_SIGN_PIC`。
4. 同一 `effective_time` 下如果多条有效记录的 `modified_by` 不同，`recorded_by` 取该组内按修改时间倒序的第一条记录。
5. 新表格只保留真实 3 列，不复制参考段落里历史遗留的“列定义数量与 cols 不一致”问题。

## 目标

在 compact 报表对应 flow container 中新增：

1. `table-39`：静态标题，文本为 `非整点观察记录`
2. `table-40-1`：静态表头，3 列：`时间 / 观察内容 / 记录者`
3. `table-40-2`：动态明细表，绑定新的非整点观察项数据源

同时把 `PatientNursingRecordsDataSourceHandler` 中的非整点观察项汇总逻辑完整迁出，不再由 `patient_nursing_records` 返回。

## 表格配置

### 落位方式

建议直接参考 `table-37 / table-38-1 / table-38-2` 的布局风格：

- 与现有血气区块放在同一个 flow container 内
- 插入在 `table-38-2` 后面
- `x/y/font/font_size/line_width/h_padding` 延续同页已有风格

### table-39

静态标题表：

```text
id: "table-39"
static_vals: "非整点观察记录"
```

建议继续使用单列表头样式，宽度与同区域标题保持一致。

### table-40-1

静态表头表，3 列：

| 列 | 标题 | 宽度 |
| --- | --- | --- |
| 1 | 时间 | 66 |
| 2 | 观察内容 | 683.5 |
| 3 | 记录者 | 60 |

实现时注意：

1. `cols` 应写成 `3`。
2. 不要机械照抄参考段落里的 `cols: 5` 旧写法。

### table-40-2

动态明细表：

```text
id: "table-40-2"
data_source_meta_id: "patient_non_hourly_monitoring_records"
cols: 3
cell_widths: 66
cell_widths: 683.5
cell_widths: 60
```

字段映射建议：

| 列 | data_source_field_id | 说明 |
| --- | --- | --- |
| 1 | `record_time` | 非整点记录时间 |
| 2 | `content` | 同一时刻观察项拼接内容 |
| 3 | `recorded_by` | 记录者签名或回退显示值 |

## 新数据源定义

新数据源 `meta_id` 已确认：

```text
id: "patient_non_hourly_monitoring_records"
name: "非整点观察记录"
```

### 输入字段

建议沿用 compact table-scoped 数据源输入：

- `pid`
- `dept_id`
- `query_start`
- `query_end`
- `table_id`
- `col_widths`
- `font_size`
- `char_spacing`
- `h_padding`

### 输出字段

建议输出 3 个字段：

- `record_time`
- `content`
- `recorded_by`

字段类型建议：

- `record_time`：`val_type: 9 # STRINGS`
- `content`：`val_type: 9 # STRINGS`
- `recorded_by`：`val_type: 7 # NURSING_SIGN_PIC`

原因：

1. 当前 `PatientNursingRecordsDataSourceHandler` 中非整点观察记录的记录者已经走 `JfkSignatureValueResolver`。
2. 如果继续沿用签名图片口径，拆分时对 renderer 的影响最小。

## 数据来源与处理规则

### 查询口径

建议复用当前 `PatientNursingRecordsDataSourceHandler` 中已经跑通的时间窗口口径：

```text
MonitoringWindowResolver.resolve(pid, query_start)
```

得到：

```text
monStartUtc
monEndUtc = monStartUtc + 24h
```

查询：

```text
patient_monitoring_records.pid = pid
patient_monitoring_records.effective_time >= monStartUtc
patient_monitoring_records.effective_time < monEndUtc
patient_monitoring_records.is_deleted = false
```

再在 Java 里过滤“严格非整点”：

```text
minute != 0 || second != 0 || nano != 0
```

### 同一时刻多条观察项合并规则

对同一 `effective_time` 下的多条记录：

1. 先按观察项配置顺序排序
2. 每条记录生成：

```text
{观察项名称}: {格式化后的值}
```

3. 多条记录用 `; ` 拼接

示例：

```text
血压: 120/80; 心率: 88
```

### 观察项排序

建议直接复用当前实现口径：

1. `MonitoringConfig.getMonitoringParams(deptId)` 获取参数定义
2. `MonitoringConfig.getMonitoringGroups(...)` 获取组内顺序
3. 先按组顺序，再按参数 `display_order`
4. 未出现在分组里的参数排在已配置参数之后，再按 `display_order + code` 稳定排序

### 值格式化

对单条 `patient_monitoring_records`：

1. 优先使用 `param_value_str`
2. `param_value_str` 为空但 `param_value` 存在时，复用 `PatientMonitoringRecordsDataSourceHandler` 的 `MonitoringValuePB` 解码与 `ValueMetaUtils.extractAndFormatParamValue(...)`
3. 最终无值则跳过该条记录

### 记录者生成

建议沿用当前实现：

1. 批量把 `modified_by` 解析为 `accounts.id`
2. 查询 `Account.name / sign_pic`
3. 用 `JfkSignatureValueResolver` 输出签名
4. 没有签名时回退账号名或原始 `modified_by`
5. 同一 `effective_time` 下如存在多条有效记录，`recorded_by` 取该组内按 `modified_at` 倒序、`id` 倒序排序后的第一条记录作为来源

## 排序与折行

### 行排序

最终输出行按：

1. `record_time` 升序
2. 同时刻按该组最小 `patient_monitoring_records.id` 升序

### 折行

`content` 列宽为 `683.5`，需要使用：

- `col_widths`
- `font_size`
- `char_spacing`
- `h_padding`

建议继续复用 `JfkPdfUtils.getWrappedLines(...)`，并统一输出到 `JfkValPB.strs_val`。

## compact 接入点

后续真正实现时，涉及以下位置：

1. `JfkDataSourceIds`
   - 新增 `PATIENT_NON_HOURLY_MONITORING_RECORDS`
   - 纳入 `isCompactTableScoped(...)`
2. `CompactReportDataSourceBuilder`
   - 新增 `buildPatientNonHourlyMonitoringRecordsInputs(...)`
3. `icis_config.pb.txt`
   - 新增 `patient_non_hourly_monitoring_records` 元数据
4. `report_compact.pb.txt`
   - 新增 `table-39 / table-40-1 / table-40-2`
5. `PatientNursingRecordsDataSourceHandler`
   - 删除非整点观察项输出逻辑
6. 新增独立 handler
   - 建议命名 `PatientNonHourlyMonitoringRecordsDataSourceHandler`

## 测试建议

建议新增：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientNonHourlyMonitoringRecordsDataSourceHandlerTests.java
```

至少覆盖：

1. 整点记录被过滤
2. 同一时刻多条观察项合并
3. `param_value_str` 为空时回退解码 `param_value`
4. 观察项排序与现有配置顺序一致
5. `recorded_by` 走签名或回退值
6. 长文本在 `content` 列正确折行

## 执行卡点

当前本需求中的关键口径已确认，暂无新增执行卡点。
