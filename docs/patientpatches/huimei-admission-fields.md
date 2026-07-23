# 惠美入科相关字段来源与同步

本文覆盖：

- `his_patient_records.admission_source_dept_name`
- `his_patient_records.admission_status`
- `his_patient_records.icu_admission_time`
- 目标侧补充字段 `patient_records.admission_source_dept_id`

## 1. 惠美事件来源

| 惠美事件 | `admission_source_dept_name` | `admission_status` | `icu_admission_time` |
| --- | --- | --- | --- |
| `InPatientInfoAdd` | 无映射 | 解析器固定生成 `1` | 跳过更新 |
| `InPatientInfoUpdate` | 无映射 | 解析器固定生成 `1` | 跳过更新 |
| `TransferInfoAdd` | `message.body.patiTran.t_out_dept_name` | 解析器固定生成 `1` | 跳过更新；报文的 `t_in_date` 当前没有映射为 ICU 入科时间 |
| `TransferInfoUpdate` | 同上 | 解析器固定生成 `1` | 同上 |
| `AdmitDept` | 无映射，保留已有值 | 解析器固定生成 `1` | `message.body.admit_dept_time` |
| `DischargeInfoAdd` | 无映射，保留已有值 | 解析器固定生成 `3` | 无映射 |
| `DischargeInfoUpdate` | 同上 | 解析器固定生成 `3` | 无映射 |

配置依据见 [`xacph_xml_parser.pb.txt`](../../../icis_j3/src/main/resources/config/xml-platforms/huimei/xacph_xml_parser.pb.txt)：转科来源名称在第 56 行，`AdmitDept` 入科时间在第 76 行，各事件的状态解析器在第 28、57、75、99 行。

### 1.1 `admission_source_dept_name`

惠美转科报文同时包含：

- `t_out_dept_code/name`：转科前科室；
- `t_in_dept_code/name`：转科后科室。

当前配置仅将 `t_out_dept_name` 写入 `admission_source_dept_name`，没有保存 `t_out_dept_code`。因此患者从骨科转入 ICU 时，源表可得到“骨科”这个名称，但得不到对应科室编码。

`icis_j3` 更新已有 `his_patient_records` 时只用非空字符串覆盖，所以不携带来源字段的 `AdmitDept` 不会清空先前由 `TransferInfo` 写入的来源名称；后续另一条 `TransferInfo` 则会把它更新为新的 `t_out_dept_name`。

### 1.2 `admission_status`

该字段不是读取惠美 XML 中某个状态码，而是由事件类型生成：

- `DefaultAdmissionStatusParser` 固定设置为 `1`；
- `DefaultDischargedAdmissionStatusParser` 固定设置为 `3`。

实现见 [`HuimeiAdtXmlFieldParser`](../../../icis_j3/src/main/java/com/jingyicare/icis_j3/xml/platform/huimei/adt/HuimeiAdtXmlFieldParser.java#L25)。

对惠美而言，`1` 表示 HIS 侧仍在院/在科，并不表示患者一定正在 ICU。例如转出 ICU 到普通科室后，`TransferInfo` 仍写 `1`，只是 `dept_code` 已变成普通科室编码。

### 1.3 `icu_admission_time`

只有 `AdmitDept` 的 `message.body.admit_dept_time` 是当前认可的 ICU 入科时间。`InPatientInfo*` 和 `TransferInfo*` 被明确放入 `SKIP_ICU_ADMISSION_TIME_EVENTS`，避免用住院时间或普通转科事件错误覆盖 ICU 入科时间。

`AdmitDept` 被当作已有记录补丁：

1. 按 `his_encounter_id` 找最新的 `his_patient_records`；
2. 更新床位、当前科室/病区、主管医生、`icu_admission_time` 和状态；
3. 如果找不到已有记录则不插入一条字段残缺的新记录。

示例测试中，惠美本地时间 `2026-05-09 10:25:00` 先解析为 `2026-05-09T10:25+08:00`，写库时转换为 UTC `2026-05-09 02:25:00`。参考 [`XmlParserTests`](../../../icis_j3/src/test/test/java/com/jingyicare/icis_j3/xml/huimei/XmlParserTests.java) 和 [`PatientRecordSyncServiceTests`](../../../icis_j3/src/test/test/java/com/jingyicare/icis_j3/data/write/PatientRecordSyncServiceTests.java)。

## 2. 写入 `his_patient_records` 的规则

惠美使用 `his_encounter_id` 定位最新记录，科室变化时不新建记录。实现见 [`HuimeiPatientRecordWritePolicy`](../../../icis_j3/src/main/java/com/jingyicare/icis_j3/data/write/HuimeiPatientRecordWritePolicy.java#L20)。

相关写入点位于 [`icis_j3 PatientRecordSyncService.setEntityByProto()`](../../../icis_j3/src/main/java/com/jingyicare/icis_j3/data/write/PatientRecordSyncService.java#L286)：

| `HisPatientPB` | `his_patient_records` |
| --- | --- |
| `admission_source_dept_name` | `admission_source_dept_name` |
| 事件解析后的 `admission_status` | `admission_status` |
| `icu_admission_time_iso8601` | `icu_admission_time` |

`AdmitDept` 的时间补丁逻辑见同一文件的 `applyIcuAdmissionTimePatch()`。

## 3. 同步到 `patient_records`

### 3.1 同步前置条件

引擎只把以下源记录作为 ICU 活动患者：

```text
his_patient_records.admission_status = 引擎“在科”枚举值（当前为 1）
AND dept_code IN 重症系统科室配置
AND discharge_time IS NULL
```

新建 `patient_records` 还要求源床号存在于该科室的床位配置中，并且不是临时授权床。

### 3.2 字段规则

| 源字段 | 目标字段 | 当前规则 |
| --- | --- | --- |
| `admission_source_dept_name` | `admission_source_dept_name` | 仅当目标为空且源非空时复制，属于“首次填充”而不是强制同步 |
| `admission_status` | `admission_status` | 不直接复制，按引擎患者流程转换，见下节 |
| `icu_admission_time` | `admission_time` | 仅当源非空且目标为空时复制 |
| 不存在 | `admission_source_dept_id` | 不同步 |

字段代码见 [`PatientSyncService.updatePatientRecord()`](../../src/main/java/com/jingyicare/jingyi_icis_engine/service/patients/PatientSyncService.java#L557)。

### 3.3 状态不是一对一复制

当前新安配置中的目标状态为：

| ID | `patient_records` 含义 |
| --- | --- |
| `0` | 待入科 |
| `1` | 在科 |
| `2` | 待出科 |
| `3` | 已出科 |

源记录以 `admission_status=1` 进入同步集合后：

- 目标记录不存在：创建为 `0/待入科`，等待重症系统确认入科；
- 目标已经是 `0/待入科` 或 `1/在科`：保留原状态；
- 目标与源科室或 HIS 患者 ID 不一致：改为 `2/待出科`；
- 原目标活动患者不再出现在源活动集合：改为 `2/待出科`。

因此 `his_patient_records.admission_status=3` 不会直接变成 `patient_records.admission_status=3`。它先导致源记录离开活动集合，目标进入 `2/待出科`，最终由重症系统确认出科后进入 `3/已出科`。

## 4. `admission_source_dept_id` 是否漏同步

是，而且缺口覆盖整条链路：

1. 惠美 XML 有 `patiTran.t_out_dept_code`；
2. 惠美解析配置没有将它映射到任何患者字段；
3. `HisPatientPB` 只有 `admission_source_dept_name`，没有 `admission_source_dept_id`；
4. `his_patient_records` 实体和表结构没有 `admission_source_dept_id`；
5. 引擎 `HisPatientRecord` 也没有该字段；
6. `PatientSyncService.updatePatientRecord()` 只设置来源科室名称，从未调用 `setAdmissionSourceDeptId()`。

目标实体虽然有 [`PatientRecord.admissionSourceDeptId`](../../src/main/java/com/jingyicare/jingyi_icis_engine/entity/patients/PatientRecord.java#L379)，但在当前主代码中没有任何自动赋值点。

## 5. 建议补丁

若要求稳定同步来源科室编码，建议保留惠美原始编码，而不是仅靠名称反查：

1. 在两边兼容的 `HisPatientPB` 中追加新字段号 `admission_source_dept_id`，不要复用或修改已有字段号；
2. 在 `j3_parser.proto` 的患者解析配置中追加对应 XML path 配置项；
3. 惠美 `TransferInfoAdd/Update` 映射 `message.body.patiTran.t_out_dept_code`；
4. 给 `his_patient_records` schema 和两边 `HisPatientRecord` 实体增加 `admission_source_dept_id`；
5. 在 `icis_j3 PatientRecordSyncService` 中落库；
6. 在引擎 `PatientSyncService` 中同步到 `patient_records.admission_source_dept_id`；
7. 增加“转入 ICU 后名称和编码同时落源表、同时首次填充目标表”的测试。

不建议只按科室名称去 `RbacDepartment` 反查编码：重症系统的科室配置通常只覆盖 ICU 科室，而来源可能是骨科、胸外科等非 ICU 科室，名称也可能存在别名。

