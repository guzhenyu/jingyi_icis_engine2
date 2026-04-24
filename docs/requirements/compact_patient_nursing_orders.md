# Compact 报表 `patient_nursing_orders` 表格与数据源需求

## 背景

本需求中的“护理计划数据”已明确为“护理计划执行记录”。

当前 `PatientNursingRecordsDataSourceHandler` 会把护理计划执行记录混在 `patient_nursing_records` 中输出。后续实现需要把这部分能力拆到独立数据源 `patient_nursing_orders`，并接入 compact 报表独立表格。

本次仅更新需求文档，不做代码实现。

## 已确认决策

1. `patient_nursing_orders` 承接的是护理计划执行记录，不是原始 `NursingOrder` 列表。
2. `patient_nursing_orders` 的 compact 表格为 3 列结构：`record_time / content / recorded_by`。
3. `recorded_by` 继续沿用 `NURSING_SIGN_PIC`。
4. 需要在 `src/main/resources/config/pbtxt/report_compact.pb.txt:5554-5555` 之间插入新的护理计划执行记录区块。
5. 新增表格使用以下编号：
   - `table-260-1`：标题 `护理计划执行记录`
   - `table-260-2`：表头 `时间 / 内容 / 记录者`
   - `table-260-3`：动态数据表，绑定 `patient_nursing_orders`
6. `table-260-2/table-260-3` 列宽固定为 `66 / 683.5 / 60`。

## 表格配置

### 插入位置

在：

```text
src/main/resources/config/pbtxt/report_compact.pb.txt:5554-5555
```

之间插入新的 `ac_tables` 区块，即位于当前 `medexe_records` 区域之后、`table-251` 管道区域之前。

### table-260-1

静态标题表：

```text
id: "table-260-1"
static_vals: "护理计划执行记录"
```

建议沿用同区域标题表风格：

- `cols: 1`
- `cell_widths: 809.5`
- `font_size: 8`

### table-260-2

静态表头表，3 列：

| 列 | 标题 | 宽度 |
| --- | --- | --- |
| 1 | 时间 | 66 |
| 2 | 内容 | 683.5 |
| 3 | 记录者 | 60 |

实现要求：

1. `cols` 必须为 `3`
2. 不新增 `reviewed_by` 列

### table-260-3

动态明细表：

```text
id: "table-260-3"
data_source_meta_id: "patient_nursing_orders"
cols: 3
cell_widths: 66
cell_widths: 683.5
cell_widths: 60
```

字段映射：

| 列 | data_source_field_id | 说明 |
| --- | --- | --- |
| 1 | `record_time` | 执行完成时间 |
| 2 | `content` | 护理计划执行内容 |
| 3 | `recorded_by` | 执行者签名或回退值 |

## 数据源定义

新增数据源：

```text
id: "patient_nursing_orders"
name: "护理计划执行记录"
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

输出 3 个字段：

- `record_time`
- `content`
- `recorded_by`

字段类型建议：

- `record_time`：`val_type: 9 # STRINGS`
- `content`：`val_type: 9 # STRINGS`
- `recorded_by`：`val_type: 7 # NURSING_SIGN_PIC`

## 数据来源与处理规则

### 查询条件

继续沿用当前 repository 口径：

```text
nursing_execution_records.pid = pid
nursing_execution_records.completed_time >= monStartUtc
nursing_execution_records.completed_time < monEndUtc
nursing_execution_records.completed_time is not null
nursing_execution_records.is_deleted = false
nursing_orders.id = nursing_execution_records.nursing_order_id
nursing_orders.is_deleted = false
```

现有可复用方法：

```java
NursingExecutionRecordRepository.findReportNursingExecutionRecords(...)
```

### 输出映射

| 输出字段 | 来源 | 备注 |
| --- | --- | --- |
| `record_time` | `completed_time` | 转本地时间，格式 `yyyy-MM-dd HH:mm` |
| `content` | `nursing_orders.name` + `note` | `note` 为空时不追加备注括号 |
| `recorded_by` | `completed_by` | 通过 `JfkSignatureValueResolver` 输出签名或回退值 |

### content 生成规则

1. `nursing_orders.name` 为空时记录 `warn`，该行仍可输出。
2. `note` 非空时：

```text
{nursing_orders.name}(备注: {note})
```

3. `note` 为空时只输出 `nursing_orders.name`。

### recorded_by 生成规则

1. 使用 `completed_by` 作为签名账号来源。
2. 优先通过 `JfkSignatureValueResolver` 输出签名。
3. 无签名或无法解析时，回退账号名或原始 `completed_by`。

## 折行与排序

### 排序

输出行按：

1. `completed_time` 升序
2. 同时刻按 `nursing_execution_records.id` 升序

### 折行

`content` 列宽为 `683.5`，需要使用：

- `col_widths`
- `font_size`
- `char_spacing`
- `h_padding`

建议继续复用 `JfkPdfUtils.getWrappedLines(...)`，统一输出 `JfkValPB.strs_val`。

## compact 接入点

后续真正实现时，需要同步改以下位置：

1. `JfkDataSourceIds`
   - 新增 `PATIENT_NURSING_ORDERS`
   - 纳入 `isCompactTableScoped(...)`
2. `CompactReportDataSourceBuilder`
   - 新增 `buildPatientNursingOrdersInputs(...)`
   - 为 `table-260-3` 构造 table-scoped 输入
3. `icis_config.pb.txt`
   - 新增 `patient_nursing_orders` 元数据
4. `report_compact.pb.txt`
   - 新增 `table-260-1 / table-260-2 / table-260-3`
5. `PatientNursingRecordsDataSourceHandler`
   - 删除 `nursingExecutionRows(...)`
6. 新增独立 handler
   - 建议命名 `PatientNursingOrdersDataSourceHandler`

## 对现有需求的影响

拆分后：

1. `patient_nursing_records` 只保留护理记录本身
2. 原来混在 `patient_nursing_records` 中的护理计划执行记录迁移到 `patient_nursing_orders`
3. 相关表格不再复用护理记录的 4 列结构，而是单独使用 3 列结构

## 测试建议

建议新增：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientNursingOrdersDataSourceHandlerTests.java
```

至少覆盖：

1. 只返回 `completed_time` 非空的记录
2. `note` 为空时只显示护理计划名称
3. `note` 非空时拼接备注
4. `recorded_by` 走签名或回退姓名
5. 软删除 `nursing_orders` 不进入报表
6. `content` 按 `683.5` 列宽正确折行

同时补充：

1. `CompactReportDataSourceBuilderTests`
   - 验证会为 `table-260-3` 构造 `patient_nursing_orders` table-scoped 输入
2. `JfkPdfRendererTests`
   - 增加 synthetic `patient_nursing_orders` 数据，验证 3 列表格渲染

## 当前无阻塞问题

护理计划执行记录的业务口径、三列表格结构和插入位置均已确认，当前文档层面无新增阻塞问题。
