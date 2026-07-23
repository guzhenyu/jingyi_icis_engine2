# 惠美患者入出科字段来源与同步结论

## 1. 范围

本文档基于 2026-07-23 工作区中的以下实现整理：

- `icis_j3`：惠美 XML 解析、`HisPatientPB` 组装以及 `his_patient_records` 写入。
- `jingyi_icis_engine2`（Java 包名仍为 `jingyi_icis_engine`）：`PatientSyncService` 从 `his_patient_records` 同步到 `patient_records`。

详细说明分为两组：

- [惠美入科相关字段](huimei-admission-fields.md)
- [惠美出科相关字段](huimei-discharge-fields.md)

## 2. 总体数据链路

```text
惠美 ADT XML
  -> HuimeiAdtXmlParser / HuimeiAdtXmlFieldParser
  -> HisPatientPB / UpdateHisPatientReq
  -> icis_j3 PatientRecordSyncService
  -> his_patient_records
  -> jingyi_icis_engine PatientSyncService（定时同步）
  -> patient_records
```

惠美患者在 `icis_j3` 中以 `his_encounter_id` 定位；同一次住院发生科室变化时更新同一条最新记录，不按科室拆分新记录。惠美记录无论当前科室是否为 ICU 都可写入 `his_patient_records`，而是否进入 `patient_records` 由引擎根据“源状态 + ICU 科室配置 + 出科时间”再次筛选。

## 3. 结论总表

| `his_patient_records` 字段 | 惠美来源 | 写入 `his_patient_records` | 当前同步到 `patient_records` 的实际结果 |
| --- | --- | --- | --- |
| `admission_source_dept_name` | `TransferInfoAdd/Update` 的 `patiTran.t_out_dept_name` | 有值时写入/覆盖 | 同步到 `admission_source_dept_name`，但仅在目标字段为空时写入 |
| `admission_status` | 不是 XML 原始值；按事件生成：普通住院/转科/入科事件为 `1`，出院事件为 `3` | 写入/更新 | 不直接复制。源记录满足 ICU 在科筛选时，目标新记录为“待入科”；源记录从活动集合消失时，目标改为“待出科” |
| `icu_admission_time` | `AdmitDept.body.admit_dept_time` | 按 `his_encounter_id` 对已有记录做 ICU 入科补丁 | 同步到 `patient_records.admission_time`，仅当目标入科时间为空 |
| `discharged_type` | 当前惠美配置没有 XML 来源，也没有按事件赋值 | 新记录通常是 Protobuf 默认值 `0`；后续无有效更新 | **完全没有同步代码** |
| `discharged_dept_id` | `DischargeInfoAdd/Update` 的 `patiDisch.dept_code` | 有值时写入/覆盖 | 代码表面存在复制逻辑，但正常出院链路中源记录先被筛除，实际通常不同步 |
| `discharged_dept_name` | `DischargeInfoAdd/Update` 的 `patiDisch.dept_name` | 有值时写入/覆盖 | 同上，正常出院链路中通常不同步 |
| `discharge_time` | `DischargeInfoAdd/Update` 的 `patiDisch.disch_date` | 转为 UTC 后写入 | 正常出院链路中不会复制 HIS 时间；引擎在发现患者离开活动集合时写同步执行时刻 |

补充字段 `patient_records.admission_source_dept_id` 的结论很明确：**当前没有同步**。不仅 `PatientSyncService` 没有 setter，`HisPatientPB`、惠美解析配置和 `his_patient_records` 也都没有对应的来源科室编码字段，因此是端到端缺失，而不是单独漏了一行目标赋值。

## 4. 为什么出科字段“代码存在但实际不同步”

引擎首先只查询满足以下全部条件的 `his_patient_records`：

1. `admission_status` 等于引擎配置的“在科”值，当前新安配置为 `1`；
2. `dept_code` 属于重症系统配置的科室；
3. `discharge_time IS NULL`。

参见 [`PatientSyncService.getInIcuHisPatientRecords()`](../../src/main/java/com/jingyicare/jingyi_icis_engine/service/patients/PatientSyncService.java#L253)。

惠美 `DischargeInfoAdd/Update` 会同时写入 `admission_status=3` 和非空 `discharge_time`。因此该记录不会进入 `updatePatientRecord()`，后面的 `discharged_dept_id`、`discharged_dept_name`、`discharge_time` 复制代码在正常出院场景下不可达。引擎只是通过活动集合差异把原 `patient_records` 标记为“待出科”，并把 `discharge_time` 设置为当前时间。

惠美转出 ICU 也存在类似结果：`TransferInfoAdd/Update` 将同一条源记录的 `dept_code` 更新为 `t_in_dept_code`。当目标科室不是 ICU 时，源记录因科室条件被排除，引擎同样只会把目标患者标记为“待出科”。

## 5. 当前缺口优先级

| 优先级 | 缺口 | 影响 |
| --- | --- | --- |
| P0 | `patient_records.discharge_time` 不使用惠美出院时间 | 出科时间会变成同步发现时间，影响在科时长、质控和报表 |
| P0 | ICU 转出目的科室没有按出科语义保存和同步 | `patient_records.discharged_dept_id/name` 无法自动得到真实转入科室 |
| P1 | `discharged_type` 无惠美来源且目标无同步 | 需要护士确认时手工选择“转出/死亡/出院” |
| P1 | `admission_source_dept_id` 端到端缺失 | 只有来源科室名称，无法稳定按编码关联 |
| P2 | 入科来源名称和入科时间都是目标为空才填 | HIS 后续更正不能覆盖目标已有值 |

## 6. 主要代码依据

- 惠美事件字段配置：[`xacph_xml_parser.pb.txt`](../../../icis_j3/src/main/resources/config/xml-platforms/huimei/xacph_xml_parser.pb.txt)
- 惠美事件状态常量解析：[`HuimeiAdtXmlFieldParser`](../../../icis_j3/src/main/java/com/jingyicare/icis_j3/xml/platform/huimei/adt/HuimeiAdtXmlFieldParser.java)
- 惠美 ICU 入科补丁标志：[`HuimeiAdtXmlParser`](../../../icis_j3/src/main/java/com/jingyicare/icis_j3/xml/platform/huimei/adt/HuimeiAdtXmlParser.java)
- 惠美记录定位和科室变更策略：[`HuimeiPatientRecordWritePolicy`](../../../icis_j3/src/main/java/com/jingyicare/icis_j3/data/write/HuimeiPatientRecordWritePolicy.java)
- `HisPatientPB -> his_patient_records`：[`icis_j3 PatientRecordSyncService`](../../../icis_j3/src/main/java/com/jingyicare/icis_j3/data/write/PatientRecordSyncService.java)
- `his_patient_records -> patient_records`：[`jingyi_icis_engine PatientSyncService`](../../src/main/java/com/jingyicare/jingyi_icis_engine/service/patients/PatientSyncService.java)

