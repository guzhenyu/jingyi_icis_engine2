# 惠美出科相关字段来源与同步

本文覆盖：

- `his_patient_records.discharged_type`
- `his_patient_records.discharged_dept_id`
- `his_patient_records.discharged_dept_name`
- `his_patient_records.discharge_time`

## 1. 必须区分两类“离开 ICU”事件

### 1.1 转出 ICU，但仍在医院

惠美通过 `TransferInfoAdd/Update` 表达转科：

- `t_out_dept_code/name`：转出前科室；
- `t_in_dept_code/name`：转入目标科室；
- `t_in_date`、`t_out_date`：转科时间字段。

当前配置的处理是：

| 惠美字段 | 当前落库字段 |
| --- | --- |
| `t_in_dept_code/name` | 当前科室 `dept_code/name` |
| `t_out_dept_name` | `admission_source_dept_name` |
| `t_out_dept_code` | 未保存 |
| `t_in_date`、`t_out_date` | 未保存为 `discharge_time` |
| 事件类型 | `admission_status` 固定为 `1` |
| 目标科室和事件类型 | 未生成 `discharged_dept_id/name/type` |

由于惠美科室变化更新同一条 `his_patient_records`，患者从 ICU 转到普通科室后，源记录仍是 `admission_status=1`，但 `dept_code` 已变成普通科室。引擎下一次同步时会因科室不在 ICU 配置中将它排除。

这意味着 ICU 转出场景当前没有在源表保留下列业务数据：

- 出科类型“转出”；
- ICU 转入的目标科室编码和名称；
- 精确 ICU 转出时间。

### 1.2 从医院出院

惠美通过 `DischargeInfoAdd/Update` 表达真实出院。当前配置见 [`xacph_xml_parser.pb.txt`](../../../icis_j3/src/main/resources/config/xml-platforms/huimei/xacph_xml_parser.pb.txt#L80)：

| 惠美字段/事件 | `his_patient_records` |
| --- | --- |
| 事件类型 | `admission_status=3` |
| `message.body.patiDisch.dept_code` | `dept_code` 和 `discharged_dept_id` |
| `message.body.patiDisch.dept_name` | `dept_name` 和 `discharged_dept_name` |
| `message.body.patiDisch.disch_date` | `discharge_time` |
| 无配置来源 | `discharged_type` |

这里的 `patiDisch.dept_code/name` 是出院事件携带的科室，也就是患者离院时所在科室。它不是 `TransferInfo` 中的 `t_in_dept_code/name`，不能直接理解为 ICU 转出的“目标科室”。

## 2. `his_patient_records` 的实际写入值

### 2.1 `discharged_type`

虽然 `HisPatientPB` 和 `his_patient_records` 都定义了 `discharged_type`，惠美解析配置没有设置 `discharged_type_xml_path`，字段解析器也没有按事件生成出科类型。

因此当前结果是：

- 新插入源记录时，通常写入 Protobuf `int32` 默认值 `0`；
- 后续惠美事件仍提供 `0`，已有记录不会被有效更新；
- `0` 不属于引擎出科类型的有效值；当前有效值为 `1/转出`、`2/死亡`、`3/出院`。

写入点见 [`icis_j3 PatientRecordSyncService.setEntityByProto()`](../../../icis_j3/src/main/java/com/jingyicare/icis_j3/data/write/PatientRecordSyncService.java#L365)。

### 2.2 出院科室和时间

`DischargeInfoAdd/Update` 提供的科室编码、科室名称和出院时间会正常进入 `HisPatientPB`，再写到 `his_patient_records`：

- `discharged_dept_id <- patiDisch.dept_code`；
- `discharged_dept_name <- patiDisch.dept_name`；
- `discharge_time <- patiDisch.disch_date`。

时间先按医院时区解析为带偏移的 ISO-8601，再按代码约定转成 UTC `LocalDateTime` 保存。

## 3. 当前同步到 `patient_records` 的实际行为

### 3.1 同步代码表面映射

[`PatientSyncService.updatePatientRecord()`](../../src/main/java/com/jingyicare/jingyi_icis_engine/service/patients/PatientSyncService.java#L634) 中存在：

| 源字段 | 目标字段 | 赋值条件 |
| --- | --- | --- |
| `discharged_dept_name` | `discharged_dept_name` | 目标为空且源非空 |
| `discharged_dept_id` | `discharged_dept_id` | 目标为空且源非空 |
| `discharge_time` | `discharge_time` | 源非空且与目标不同 |

但是该方法完全没有 `patientRecord.setDischargedType(hisRecord.getDischargedType())`，所以 `discharged_type` 即使未来在源表有有效值，目前也不会同步。

### 3.2 正常出院时上述代码不可达

引擎传给 `updatePatientRecord()` 的源记录已经经过活动患者过滤：

```text
admission_status = 1
AND dept_code 属于 ICU 配置
AND discharge_time IS NULL
```

而惠美出院事件写入的是 `admission_status=3` 和非空 `discharge_time`。所以正常 `DischargeInfoAdd/Update` 记录不会进入字段复制逻辑。源码对 `discharge_time` 也有“此条件为假，因为取得是在科的 HIS 记录”的直接注释。

### 3.3 引擎实际执行的是活动集合差异

当原来处于 `0/待入科` 或 `1/在科` 的目标患者不再出现在源活动集合中时，引擎会：

```text
patient_records.admission_status = 2  // 待出科
patient_records.discharge_time = TimeUtils.getNowUtc()  // 同步发现时间
```

相关分支见 [`PatientSyncService.syncPatientRecords()`](../../src/main/java/com/jingyicare/jingyi_icis_engine/service/patients/PatientSyncService.java#L168)。因此当前自动结果如下：

| 目标字段 | 自动结果 |
| --- | --- |
| `admission_status` | 变为 `2/待出科`，不是直接变为 `3/已出科` |
| `discharge_time` | 同步执行时刻，不是惠美 `disch_date` 或转科时间 |
| `discharged_type` | 不设置 |
| `discharged_dept_id` | 不设置 |
| `discharged_dept_name` | 不设置 |

之后护士确认出科时，`PatientService` 会根据请求设置出科时间和出科类型；选择“转出”时要求并设置目标科室名称。当前确认出科流程仍不设置 `discharged_dept_id`。

另有一个边界行为：定时任务以 `forceSync=false` 调用时，如果整个 `his_patient_records` 查询不到任何 ICU 活动记录，方法会提前返回，不执行集合差异；强制同步时才会继续。这可能让最后一名 ICU 患者的自动待出科延迟或缺失。

## 4. 为什么不能直接把所有 `TransferInfo` 映射成出科字段

不能无条件增加以下配置：

```text
TransferInfo.t_in_dept_* -> discharged_dept_*
TransferInfo.t_in_date   -> discharge_time
```

原因是 `TransferInfo` 同时覆盖“转入 ICU”和“转出 ICU”。如果转入 ICU 时也给同一条源记录写入非空 `discharge_time`，该记录会被引擎的 `discharge_time IS NULL` 条件过滤，患者反而无法进入 ICU 同步集合。

必须结合科室迁移方向判断：

```text
原 dept_code 在 ICU 集合 && 新 t_in_dept_code 不在 ICU 集合
    => ICU 转出
```

## 5. 建议补丁方向

### 5.1 在 `icis_j3` 保留 ICU 转出元数据

在惠美 `TransferInfo` 更新已有记录前，使用“旧科室 + 新科室 + ICU 科室集合”识别 ICU 转出：

- `discharged_type = 1/转出`；
- `discharged_dept_id = t_in_dept_code`；
- `discharged_dept_name = t_in_dept_name`；
- `discharge_time =` 明确选定的惠美转科时间，优先建议与医院确认 `t_in_date` 和 `t_out_date` 的业务含义后再定。

为避免把普通转科或转入 ICU 误判为出科，不应只靠事件名赋值。

真实 `DischargeInfoAdd/Update` 可按事件生成：

- `discharged_type = 3/出院`；
- 保留 `patiDisch.dept_code/name` 作为“离院科室”；
- `discharge_time = patiDisch.disch_date`。

死亡类型当前没有明确的惠美字段或独立事件依据，不应凭空推断为 `2/死亡`。

### 5.2 在引擎退出分支读取最新源记录

仅在 `updatePatientRecord()` 中补三四行 setter 不够，因为离开 ICU 的源记录不会进入该方法。建议在“目标患者不再出现在活动源集合”的分支：

1. 优先按 `patient_records.his_encounter_id` 查最新 `his_patient_records`，必要时再以 MRN 兜底；
2. 若最新源记录有有效出科元数据，则复制 `discharged_type/dept_id/dept_name/discharge_time`；
3. 只有源时间为空时才用 `TimeUtils.getNowUtc()`；
4. 目标状态仍可保持 `2/待出科`，由现有人工确认流程决定何时变成 `3/已出科`。

不建议只把活动源查询扩大为所有状态后继续按 MRN 归并：同一 MRN 可能有多次住院记录，容易把旧住院的出科信息合并到当前患者。`his_encounter_id` 更适合作为退出详情定位键。

### 5.3 必测场景

| 场景 | 预期 |
| --- | --- |
| 普通科室转入 ICU | 创建/更新 ICU 活动源记录；不能产生出科时间 |
| ICU 转到普通科室 | 目标待出科；类型为转出；目标科室编码、名称和准确时间可用 |
| ICU 直接出院 | 目标待出科；类型为出院；使用惠美 `disch_date` |
| 普通科室之间转科 | 不应误触发某个 ICU 患者出科 |
| 同 MRN 多次住院 | 必须按 `his_encounter_id` 取本次住院退出详情 |
| ICU 仅剩最后一名患者出科 | 定时同步也应执行退出差异，不能因活动源集合为空提前返回 |

