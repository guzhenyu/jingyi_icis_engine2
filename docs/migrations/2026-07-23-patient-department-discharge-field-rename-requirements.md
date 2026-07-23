# 患者科室与出科字段统一命名——代码修改需求

## 1. 文档状态

- 日期：2026-07-23
- 适用工程：`icis_j3`、`jingyi_icis_engine2`、`jingyi_icis_frontend`
- 本文性质：后续实现、联调、测试和发布的需求基线
- 实施状态：三工程代码、内置配置与前端静态资源已按本文实现；生产数据库 DDL、服务发布和
  现场配置重置仍须按配套 Playbook 在维护窗口执行
- 配套文档：`2026-07-23-patient-department-discharge-production-playbook.md`

## 2. 背景与目标

当前患者链路同时使用 `admission_source_*`、`discharged_*`、`icu_admission_time`
等名称。同一字段在 HIS 源记录、ICU 患者记录、Protobuf、JSON、pbtxt 和前端中又有
snake_case、camelCase 两套表现，容易把“住院”“入科”“出科”“出院”混为一谈。

本需求的目标是：

1. 按本文件定义的映射统一两张患者表的字段名。
2. 保留所有被重命名字段的现有数据、数据库类型、空值和业务含义。
3. 同步修改 Java 属性、Protobuf、Web API JSON、pbtxt、前端类型与页面引用。
4. 为 `his_patient_records` 新增来源科室编码，并贯通已确认有来源的采集链路。
5. 删除未被引擎消费、且当前惠每链路通常只写入默认值 `0` 的
   `his_patient_records.discharged_type`。
6. 不改变患者状态机、人工确认入出科流程或现有同步覆盖规则，除非本文明确要求。

## 3. 非目标

本需求不包括：

- 不重做患者按 MRN / HIS encounter ID 匹配和合并的算法。
- 不改变 `admission_status` 的枚举值和“待入科/在科/待出科/已出科”状态机。
- 不自动推断 HIS 未提供的死亡、出院或转科类型。
- 不顺带修复当前“源记录离开活动集合后，目的科室和 HIS 出院时间通常未同步到
  `patient_records`”的既有业务缺口；该问题应独立立项。
- 不把 `readmission_history.icu_admission_time`、
  `readmission_history.icu_discharging_account_id` 等重返 ICU 字段一起改名。
- 不把 `GET_PATIENTS_DISCHARGED`、`pending_discharged_name`、`discharged_name` 等
  表示患者生命周期状态的名称机械改掉。
- 不修改归档工程 `archives/icis_bridge`、`archives/icis_jd`；上线前只需确认线上没有旧实例。

## 4. 数据库字段需求

### 4.1 `his_patient_records`

| 操作 | 旧字段 | 新字段 | 当前类型 | 目标说明 |
| --- | --- | --- | --- | --- |
| 重命名 | `icu_admission_time` | `dept_admission_time` | `TIMESTAMP` | 科室入科时间；值与时区处理不变 |
| 重命名 | `admission_source_dept_name` | `from_dept_name` | `VARCHAR(255)` | 来源科室名称 |
| 新增 | — | `from_dept_id` | `VARCHAR(255) NULL` | 来源科室编码；历史行允许为空 |
| 删除 | `discharged_type` | — | `INTEGER` | 源表不再承载出科类型 |
| 重命名 | `discharged_dept_id` | `to_dept_id` | `VARCHAR(255)` | 去向科室编码 |
| 重命名 | `discharged_dept_name` | `to_dept_name` | `VARCHAR(255)` | 去向科室名称 |

`schema.postgresql.sql` 中的列定义、列注释和被注释掉的外键示例必须同步更新。
新增 `from_dept_id` 默认不加 `NOT NULL`、默认值、唯一约束或外键。

### 4.2 `patient_records`

| 旧字段 | 新字段 | 当前类型 | 目标说明 |
| --- | --- | --- | --- |
| `admission_source_dept_name` | `from_dept_name` | `VARCHAR(255)` | 来源科室名称 |
| `admission_source_dept_id` | `from_dept_id` | `VARCHAR(255)` | 来源科室编码 |
| `discharged_type` | `discharge_type` | `INTEGER` | 出科类型 |
| `discharged_death_time` | `death_time` | `TIMESTAMP` | 死亡时间 |
| `discharged_hospital_exit_time` | `his_discharge_time` | `TIMESTAMP` | HIS 出院时间 |
| `discharged_diagnosis` | `discharge_diagnosis` | `TEXT` | 出科诊断 |
| `discharged_diagnosis_code` | `discharge_diagnosis_code` | `VARCHAR(1000)` | 出科诊断编码 |
| `discharged_dept_name` | `to_dept_name` | `VARCHAR(255)` | 去向科室名称 |
| `discharged_dept_id` | `to_dept_id` | `VARCHAR(255)` | 去向科室编码 |
| `discharging_account_id` | `discharge_account_id` | `VARCHAR(255)` | 出科操作员账号 ID |

所有确认后的重命名必须使用 PostgreSQL `RENAME COLUMN`，不能通过新增列后删除旧列的方式
搬运数据。列类型、空值、值内容和现有索引语义应保持不变。

### 4.3 注释口径

建议统一使用以下注释：

| 字段 | 注释 |
| --- | --- |
| `his_patient_records.dept_admission_time` | `科室入科时间` |
| `*.from_dept_id` | `来源科室编码` |
| `*.from_dept_name` | `来源科室名称` |
| `*.to_dept_id` | `去向科室编码` |
| `*.to_dept_name` | `去向科室名称` |
| `patient_records.discharge_type` | `出科类型：转出、死亡、出院` |
| `patient_records.death_time` | `死亡时间；出科类型为死亡时有效` |
| `patient_records.his_discharge_time` | `HIS 出院时间；出科类型为出院时有效` |
| `patient_records.discharge_diagnosis` | `出科诊断` |
| `patient_records.discharge_diagnosis_code` | `出科诊断编码` |
| `patient_records.discharge_account_id` | `出科操作员账号ID` |

## 5. 命名规则

数据库和 Protobuf 使用 snake_case，Java 与 TypeScript 使用 camelCase：

| 数据库 / Protobuf | Java / TypeScript |
| --- | --- |
| `dept_admission_time` | `deptAdmissionTime` |
| `dept_admission_time_iso8601` | `deptAdmissionTimeIso8601` |
| `from_dept_name` | `fromDeptName` |
| `from_dept_id` | `fromDeptId` |
| `discharge_type` | `dischargeType` |
| `death_time` / `death_time_iso8601` | `deathTime` / `deathTimeIso8601` |
| `his_discharge_time` / `his_discharge_time_iso8601` | `hisDischargeTime` / `hisDischargeTimeIso8601` |
| `discharge_diagnosis` | `dischargeDiagnosis` |
| `discharge_diagnosis_code` | `dischargeDiagnosisCode` |
| `to_dept_name` | `toDeptName` |
| `to_dept_id` | `toDeptId` |
| `discharge_account_id` | `dischargeAccountId` |

与字段直接绑定的 getter、setter、builder、局部变量、方法名、日志参数名和测试名称应同步改名。
仅表示状态的 `DISCHARGED_VAL`、`GET_PATIENTS_DISCHARGED` 等不在机械替换范围内。

## 6. Protobuf 兼容性要求

### 6.1 保留 field number 和类型

字段重命名时必须保留原 field number、wire type 和 repeated/singular 结构。这样旧二进制
Protobuf 数据仍可被新 descriptor 读取，新二进制数据也能被旧 descriptor 按原编号读取。

禁止删除旧编号后用新编号重新添加同一业务字段。

### 6.2 删除字段必须 reserved

从 `HisPatientPB` 删除 `discharged_type = 50` 时，两份定义都必须保留：

```proto
reserved 50;
reserved "discharged_type";
```

`HisPatientParserPB` 中对应的 `discharged_type_xml_path = 99` 和
`discharged_type_parser_name = 100` 也必须 reserve 编号和名称。

### 6.3 新字段使用新编号

`HisPatientPB.from_dept_id` 使用当前最大编号之后的 `57`：

```proto
string from_dept_id = 57;
```

`HisPatientParserPB` 的配置对使用 `113/114`：

```proto
string from_dept_id_xml_path = 113;
string from_dept_id_parser_name = 114;
```

不得复用已删除的 `50`、`99`、`100`。

### 6.4 JSON、TextFormat 与反射不兼容

Protobuf JSON、TextFormat/pbtxt、生成代码 getter/setter 和
`findFieldByName()` 都依赖字段名，不具备上述二进制兼容性。因此：

- Web 前后端必须按同一版 JSON key 联动发布。
- `HisPatientPB` 与 `HisPatientParserPB` 必须在同一版本一起修改。
- 所有 jar 外置 pbtxt 必须与加载它的 descriptor 同版本。
- 浏览器缓存中的旧前端不能继续向新后端发送旧 JSON key。

## 7. `icis_j3` 修改需求

### 7.1 数据桥接 Proto

`src/main/proto/data_bridge_service.proto` 的 `HisPatientPB`：

| 原字段 | 目标字段 | 编号 |
| --- | --- | ---: |
| `admission_source_dept_name` | `from_dept_name` | 41 |
| `icu_admission_time_iso8601` | `dept_admission_time_iso8601` | 43 |
| `discharged_type` | 删除并 reserved | 50 |
| `discharged_dept_id` | `to_dept_id` | 51 |
| `discharged_dept_name` | `to_dept_name` | 52 |
| — | `from_dept_id` | 57 |

`UpdateHisPatientReq.skipping_icu_admission_time = 2` 应同步改为
`skipping_dept_admission_time = 2`，以免控制标志继续使用旧语义名称。

### 7.2 XML parser Proto 与 pbtxt

`src/main/proto/j3_parser.proto` 的 `HisPatientParserPB`：

| 原字段 | 目标字段 | 编号 |
| --- | --- | ---: |
| `admission_source_dept_name_xml_path` | `from_dept_name_xml_path` | 81 |
| `admission_source_dept_name_parser_name` | `from_dept_name_parser_name` | 82 |
| `icu_admission_time_iso8601_xml_path` | `dept_admission_time_iso8601_xml_path` | 85 |
| `icu_admission_time_iso8601_parser_name` | `dept_admission_time_iso8601_parser_name` | 86 |
| `discharged_type_xml_path` | 删除并 reserved | 99 |
| `discharged_type_parser_name` | 删除并 reserved | 100 |
| `discharged_dept_id_xml_path` | `to_dept_id_xml_path` | 101 |
| `discharged_dept_id_parser_name` | `to_dept_id_parser_name` | 102 |
| `discharged_dept_name_xml_path` | `to_dept_name_xml_path` | 103 |
| `discharged_dept_name_parser_name` | `to_dept_name_parser_name` | 104 |
| — | `from_dept_id_xml_path` | 113 |
| — | `from_dept_id_parser_name` | 114 |

`src/main/resources/config/xml-platforms/huimei/xacph_xml_parser.pb.txt` 必须：

- 将所有配置键替换为新 parser 字段名。
- 删除内置或外置 pbtxt 中可能存在的 `discharged_type_xml_path/parser_name`，不得继续向已删除字段赋值。
- 在 `TransferInfoAdd/TransferInfoUpdate` 中增加：

```text
from_dept_id_xml_path: "message.body.patiTran.t_out_dept_code"
```

- 保持原始 XML path 不变：仅配置键改名。
- 将 `patiDisch.dept_code/name` 继续映射到 `to_dept_id/name`。

生产服务器通过 `--jingyi.xml.config` 指定的 jar 外置配置不一定在 Git 中，必须纳入发布包
和上线核对清单。`XmlParserTests` 中的内嵌 pbtxt 也必须同步修改。

### 7.3 Java 实体与写入链路

必须修改：

- `data/dao/HisPatientRecord.java`
  - JPA 列名和属性按第 4、5 节调整。
  - 新增 `fromDeptId`。
  - 删除 `dischargedType`。
- `data/write/PatientRecordSyncService.java`
  - Proto 到实体的 getter/setter 和时间解析 helper 全部改名。
  - 写入 `fromDeptId`。
  - 删除 `dischargedType` 写入。
- `hl7/provider/winningsoft/WinningHis.java`
  - builder、局部变量、注释和文本日志改名。
  - 上游没有来源科室编码时不得用当前科室编码伪造 `fromDeptId`。
- `xml/platform/huimei/adt/HuimeiAdtXmlParser.java`
  - `skippingDeptAdmissionTime` 相关命名同步修改。
- `xml/platform/jiahemeikang/adt/JiaheMeikangPatientPatch.java`
- `xml/platform/jiahemeikang/adt/JiaheMeikangAdtXmlParser.java`
- `xml/platform/jiahemeikang/adt/JiaheMeikangPatientRecordWriter.java`
  - 入科、来源科室、去向科室字段改名。
  - 删除源表出科类型的 patch、清空和写入逻辑。

惠每的 `fromDeptId` 有明确的 `t_out_dept_code` 来源。嘉禾美康本次仅明确
`fromDeptId` 保持 `NULL`，`fromDeptName` 沿用现有行为；卫宁的 `fromDeptId/fromDeptName`
均保持 `NULL`。不得按名称反查或复用当前科室编码伪造来源。

### 7.4 工具、日志与测试

同步修改：

- `py_tools/huimei_adt_patient_trace.py`
- `PatientRecordSyncServiceTests`
- `XmlParserTests`
- 嘉禾美康相关解析、取消入科、取消出科测试
- 所有依赖 Proto TextFormat 字段名的诊断脚本和日志断言

测试必须覆盖：

1. 惠每转入 ICU 时 `t_out_dept_code/name` 写入 `from_dept_id/name`。
2. 科室入科时间继续按医院时区解析并以 UTC `LocalDateTime` 落库。
3. 出院事件的科室编码/名称写入 `to_dept_id/name`。
4. 取消入科/取消出科逻辑清空的是新字段。
5. 旧 field number 的二进制消息可由新 descriptor 正确读取。
6. 删除后的 field 50 被安全忽略，不能被分配给其他字段。
7. 嘉禾美康 `fromDeptId` 保持空值，`fromDeptName` 沿用现有行为。
8. 卫宁 HL7 `fromDeptId/fromDeptName` 均保持空值。

## 8. `jingyi_icis_engine2` 修改需求

### 8.1 Schema、实体与重复 Proto

必须修改：

- `src/main/resources/config/db/schema.postgresql.sql`
- `entity/patients/HisPatientRecord.java`
- `entity/patients/PatientRecord.java`
- `src/main/proto/grpc/data_bridge_service.proto`

engine 中重复维护的 `HisPatientPB` 必须与 `icis_j3` 的字段名、编号、类型、reserved 和
新增字段保持一致，即使当前业务代码没有直接调用该 message，也不能继续形成漂移定义。

### 8.2 HIS 到 ICU 患者同步

`PatientSyncService.updatePatientRecord(...)` 必须保持现有“目标为空时首次填充”的规则：

| HIS 源记录 | ICU 患者记录 | 规则 |
| --- | --- | --- |
| `fromDeptName` | `fromDeptName` | 目标为空且源非空时复制 |
| `fromDeptId` | `fromDeptId` | 目标为空且源非空时复制 |
| `deptAdmissionTime` | `admissionTime` | 目标为空且源非空时复制 |
| `toDeptName` | `toDeptName` | 保持现有条件与可达性，不借改名改变业务 |
| `toDeptId` | `toDeptId` | 保持现有条件与可达性，不借改名改变业务 |

`his_patient_records` 删除 `discharged_type` 后，engine 不应新增任何从 HIS 源表推断
`patient_records.discharge_type` 的逻辑。

### 8.3 患者业务服务

`PatientService` 中所有以下边界必须同步使用新名称：

- 新增患者、确认入科、确认出科。
- 旧版 `getPatientInfo/updatePatientInfo` 接口。
- V2 `PatientInfoPB` 读写。
- 患者列表 `getPatientRecordValue(...)` 的字符串 `case`。
- 表单校验、错误日志、清空出科字段逻辑。
- 构造和解析 Protobuf JSON 的 getter/setter。

同时清理 `ProtoUtils` 中与旧患者字段直接绑定的示例/注释，避免后续复制旧名称。

与字段直接绑定的内部常量和返回码也应统一：

- `DISCHARGED_TYPE_TRANSFER_ID/DEATH_ID/EXIT_HOSPITAL_ID` 改为
  `DISCHARGE_TYPE_TRANSFER_ID/DEATH_ID/EXIT_HOSPITAL_ID`。
- `icis_web_api.proto` 中 `INVALID_DISCHARGED_TYPE = 203` 改为
  `INVALID_DISCHARGE_TYPE = 203`，保留枚举数值 203。前端 `StatusCodePB` 当前只声明通用
  `OK/LAST_CODE`，没有该细分成员，因此不需要增加或改名前端状态码常量。

动态列表至少支持以下新列 ID，并移除对旧 ID 的依赖：

```text
from_dept_name
from_dept_id
discharge_type
death_time
his_discharge_time
discharge_diagnosis
discharge_diagnosis_code
to_dept_name
to_dept_id
discharge_account_id
```

本次不保留旧列 ID alias。生产切换时删除函数 1–4 的存量患者显示设置，由新版默认 pbtxt
重新初始化；重建后 `dept_system_settings.settings_pb` 中不得残留旧列 ID。

### 8.4 患者配置、文本与枚举

`PatientEnumsV2.discharged_type = 3` 改为 `discharge_type = 3`，并同步修改：

- `PatientConfig` 中的 `getDischargedTypeList()`、`getDischargedTypeStr()` 及相关变量。
- 前端 `PatientEnumsV2` 和 `ConfigSlice` 的 `dischargedType` 状态。
- 四份生产 pbtxt 中的 `discharged_type { ... }` 配置块。

为保持同一契约的命名一致，以下直接相关定义也应改名并保留编号：

- `icis_text.proto`: `discharged_type_str = 6` -> `discharge_type_str = 6`
- `common_text.pb.txt` 中对应配置键
- `icis_quality_control.proto`: `IcuQcPatientPB.admission_source_dept_name = 7`
  -> `from_dept_name = 7`
- `GenericIcuQcService` 和前端 `IcuQcPatientPB` 类型中的对应 getter/属性

### 8.5 `icis_patient.proto`

`PatientInfoPB` 中已有字段按下表改名，field number 和类型不变：

| 原字段 | 目标字段 | 编号 |
| --- | --- | ---: |
| `admission_source_dept_name` | `from_dept_name` | 60 |
| `discharged_type` | `discharge_type` | 65 |
| `discharged_death_time_iso8601` | `death_time_iso8601` | 66 |
| `discharged_hospital_exit_time_iso8601` | `his_discharge_time_iso8601` | 67 |
| `discharged_dept_name` | `to_dept_name` | 68 |
| `discharged_diagnosis` | `discharge_diagnosis` | 70 |

本次明确不向 `PatientInfoPB` 新增字段。数据库中已有但当前没有暴露的 `from_dept_id`、
`to_dept_id`、`discharge_account_id`、`discharge_diagnosis_code` 继续只保留在数据库/领域模型，
不占用 71 之后的新 field number，也不增加对应前端详情字段。

### 8.6 `icis_web_api.proto`

Web API 使用 Protobuf JSON，因此下列字段也必须按业务模型同步改名并保留编号：

| message | 需要修改的 field number |
| --- | --- |
| `NewPatientReq` | 8: `admission_source_dept_name` -> `from_dept_name` |
| `AdmitPatientReq` | 4: `admission_source_dept_name` -> `from_dept_name` |
| `DischargePatientReq` | 4 `discharge_type`；5 `to_dept_name`；6 `death_time`；8 `his_discharge_time_iso8601` |
| `GetPatientInfoResp` | 20 `discharge_type`；21 `to_dept_name`；22 `death_time` |
| `UpdatePatientInfoReq` | 7 `discharge_type`；8 `to_dept_name`；9 `death_time` |

这里的改名会改变 JSON key。例如 `dischargedDeptName` 会变为 `toDeptName`。仅保留
field number 不能让旧浏览器与新后端的 JSON 自动兼容。

### 8.7 pbtxt 默认配置

以下四份配置必须全文更新，不能只修改需求中提示的原行号；代码变化后行号会漂移：

- `src/main/resources/config/pbtxt/icis_config.pb.txt`
- `src/main/resources/config/pbtxt/hospitals/ah2_icis_config.pb.txt`
- `src/main/resources/config/pbtxt/hospitals/xaxrmyy_icis_config.pb.txt`
- `src/main/resources/config/pbtxt/hospitals/xnxrmyy_icis_config.pb.txt`

必须覆盖三类位置：

1. `PatientEnumsV2` 中的 `discharged_type { ... }` -> `discharge_type { ... }`。
2. `required_col/optional_col.column_id`：
   - `admission_source_dept_name` -> `from_dept_name`
   - `discharged_type` -> `discharge_type`
   - `discharged_dept_name` -> `to_dept_name`
3. 每个表下的 `col_id_to_display` 做同样替换。

特别需要覆盖用户指出的默认配置原 445、461、507、511、523、524 行，以及同文件后续
待入科/待出科/已出科表中的重复项。三份医院配置的所有重复项也必须一起更新。

测试配置 `src/test/resources/text_resources/icis_config.pb.txt` 以及
`src/main/resources/config/db/testdata/*.sql`、`src/test/resources/db/patient_testdata.sql`
必须同步更新。

### 8.8 存量部门显示设置重置

四份 pbtxt 只用于初始化缺失的 `dept_system_settings`，现有行不会自动刷新；
`PatientConfig.refreshSystemSettings()` 当前也是空实现。本次已经决定不迁移、不保留旧动态列 ID，
而是在停机窗口删除患者列表函数 1–4 的存量设置：

```sql
DELETE FROM dept_system_settings
WHERE function_id IN (1, 2, 3, 4);
```

删除前必须备份这些行以支持整版回滚。新版 engine 启动时，`SystemInitializer.init()` 会调用
`PatientConfig.initialize()`；后者遍历所有科室并从本次实际加载的
`patient.default_display_field_settings` 为缺失的函数 1–4 记录重新初始化默认值。

该方案会主动放弃各科室在函数 1–4 上已有的自定义显示列和排序，这是本次已确认的迁移口径。
不得保留旧列 ID alias，也不需要开发 `DisplayFieldSettingsPB` 逐行转换工具。启动后必须核对：

- `rbac_departments` 中每个科室均重新生成函数 1、2、3、4 的记录；
- 新记录来自该医院实际选择的新版 pbtxt，而不是错误的默认医院配置；
- Base64 内容可解析，且其中不再包含旧列 ID；
- 患者列表四种状态均能正常打开。

### 8.9 JFK 模板影响结论

仓库内置 JFK 模板不直接引用本次改名的数据库列或患者动态列表列 ID。现有
`patient_info` / `patient_info_extended` 数据源对模板暴露的是稳定字段 ID，例如
`dept_name`、`bed_no`、`patient_name`、`diagnosis`、`admission_time_yyyymmdd`；本次没有修改
这些 data source meta ID / field ID，也没有修改 `JfkTemplatePB`，因此内置 JFK 模板无需迁移。

只有以下情况会受影响：

1. 生产中的自定义 JFK 数据源 handler、插件或脚本绕过标准数据源，直接使用旧数据库列名查询
   `his_patient_records` / `patient_records`。
2. 生产 `dragable_form_templates.template_pb` 中存在仓库未包含的自定义模板，且其
   `data_source_meta_id` / `data_source_field_id` 被自定义实现定义成了本次旧字段名。
3. JFK 之外的打印代理、ETL 或报表服务把模板字段与旧 Web JSON key 或旧动态患者列 ID 直接绑定。

标准模板只引用上述稳定 JFK 数据源 ID 时不受影响。上线前仍需解码并盘点生产有效模板以及所有
自定义 JFK 数据源；这是对外置数据的核对，不要求机械修改内置模板。

## 9. `jingyi_icis_frontend` 修改需求

### 9.1 类型契约

`src/api/WebApi.ts` 必须与 engine 新版 Proto JSON key 保持一致，包括：

- `PatientEnumsV2.dischargeType`
- `NewPatientReq.fromDeptName`
- `AdmitPatientReq.fromDeptName`
- `DischargePatientReq.dischargeType/toDeptName/deathTime/hisDischargeTimeIso8601`
- `PatientInfoPB.fromDeptName/dischargeType/deathTimeIso8601/`
  `hisDischargeTimeIso8601/toDeptName/dischargeDiagnosis`
- 旧版 `UpdatePatientInfoReq` 的对应字段
- `IcuQcPatientPB.fromDeptName`

### 9.2 页面与状态

必须修改：

- `src/store/slices/ConfigSlice.ts`
- `InlinePatients/modals/NewPatientModal.tsx`
- `InlinePatients/modals/AdmitPatientModal/index.tsx`
- `InlinePatients/modals/DischargePatientModal.tsx`
- `PatientInfo/index.tsx`

表单字段名、初始值、条件显示、请求对象、响应读取和 Redux selector 必须全部使用新名称。
中文界面文案可以继续显示“入科来源”“出科类型”“转出科室”“死亡时间”“出院时间”，
除非另有产品文案要求。

### 9.3 构建产物

完成源码修改后必须执行前端类型检查和 production build。`jingyi_icis_frontend/dist`
当前不受 Git 跟踪，而 engine 的 `src/main/resources/static` 受 Git 跟踪。本次交付方式确定为：

1. 在 `jingyi_icis_frontend` 执行 `npm run build` 生成完整 `dist`。
2. 将 `dist` 下的全部内容复制到
   `jingyi_icis_engine2/src/main/resources/static`，覆盖同名文件，并确认没有遗留失效文件。
3. 在 `jingyi_icis_engine2` 执行 `mvn clean package`。
4. 解包或列出最终 jar 内容，确认携带的是本次前端构建产物。

不能手工修改压缩后的 `common.bundle.js` 或 `home.bundle.js`。

HTML/JS 仍使用固定文件名时，发布必须处理浏览器、反向代理和 CDN 缓存，避免旧 bundle
继续发送旧 JSON key。

## 10. 代码实现顺序

建议按以下顺序实施：

1. 按本文“已决策”冻结最终映射表和停机发布方案。
2. 修改两端 `HisPatientPB`、`HisPatientParserPB` 和 `icis_patient.proto`，保留编号。
3. 修改 `icis_j3` 实体、解析、写入和测试。
4. 修改 engine schema、实体、同步、配置、Web API、质控和测试。
5. 修改前端类型、状态和页面。
6. 构建前端，将完整 `dist` 复制到 engine `static`，再执行 `mvn clean package`。
7. 准备独立、版本化且带列状态前置检查的正向/回滚数据库脚本，以及函数 1–4
   设置删除/恢复脚本。
8. 完成三工程联调和生产副本演练。

## 11. 测试需求

### 11.1 静态检查

在排除归档目录、历史文档、生成目录和本需求明确不改的 `readmission_history` 后，运行时代码、
Proto、pbtxt 和测试数据中不应残留下列旧数据模型标识：

```text
admission_source_dept_name / admissionSourceDeptName
admission_source_dept_id / admissionSourceDeptId
discharged_type / dischargedType
discharged_death_time / dischargedDeathTime
discharged_hospital_exit_time / dischargedHospitalExitTime
discharged_diagnosis / dischargedDiagnosis
discharged_diagnosis_code / dischargedDiagnosisCode
discharged_dept_name / dischargedDeptName
discharged_dept_id / dischargedDeptId
discharging_account_id / dischargingAccountId
```

`icu_admission_time` 的检查必须限定到 `his_patient_records` 链路，不能误报重返 ICU 模块。
`HisPatientPB`/`HisPatientParserPB` 的 `reserved "discharged_type"`、版本化迁移 SQL、兼容性
测试样本属于允许的例外。本次不保留运行时 alias。

### 11.2 `icis_j3`

- `mvn test` 通过，并重新生成 Java Protobuf 类。
- 惠每、卫宁、嘉禾美康患者写入和取消事件回归通过。
- 新老 Protobuf 二进制兼容性测试通过。
- 新 descriptor 能解析所有新版内置/外置 pbtxt；旧配置应在发布前被阻止使用。

### 11.3 `jingyi_icis_engine2`

- `mvn test` 通过，并重新生成 Java Protobuf 类。
- `PatientTestUtils`、患者 service 测试和所有测试数据构造器使用新 Java 属性名。
- production profile 在迁移后 schema 上启动并通过 Hibernate schema validation。
- 新增患者、确认入科、确认出科、患者详情 V1/V2、患者列表四种状态、质控接口回归通过。
- HIS `fromDeptId/fromDeptName/deptAdmissionTime` 按既有首次填充规则同步。
- 清空出科字段、重返 ICU、历史患者、人工修改患者信息测试通过。
- 删除函数 1–4 的存量设置后，engine 启动能按所选医院新版 pbtxt 为所有科室重建默认设置。

### 11.4 `jingyi_icis_frontend`

- `npm run build` 通过。
- 新增患者、确认入科、确认出科、患者详情查看/编辑均发送新 JSON key。
- 转出、死亡、出院三种条件表单分别回归。
- 在科、待入科、待出科、已出科列表表头和值正确。
- 浏览器网络请求与响应中不再出现旧字段名。
- engine jar 中实际携带的是本次构建后的静态资源。

### 11.5 数据与配置迁移测试

- 所有数据库重命名列迁移前后总行数、NULL/非 NULL 数和抽样值一致。
- `his_patient_records.from_dept_id` 历史数据允许为空，新惠每转科消息可写入编码。
- 删除前的函数 1–4 设置备份完整，能够用于整版回滚。
- 默认设置重建后，`rbac_departments` 中每个科室都有函数 1–4 的记录，所有旧列 ID 为零命中。
- 重建出的新 ID 能被 `PatientService` 解析，四类患者列表使用默认列和默认顺序。
- DDL、设置删除/默认重建和设置恢复均可在生产脱敏副本上完整演练。

## 12. 验收标准

满足以下条件才可认为代码改造完成：

1. 第 4 节所有已确认数据库字段完成重命名/新增/删除，数据未丢失。
2. 三个工程的源代码、Proto、配置、测试和构建产物使用同一套名称。
3. 所有重命名 Proto 字段保留原 field number；删除字段已 reserved；新增字段不复用旧编号。
4. 四份生产 pbtxt 和测试 pbtxt 中所有相关重复项均已修改。
5. 存量 `dept_system_settings` 函数 1–4 已备份并删除，由新版 pbtxt 重建默认设置；
   本次明确不保留科室原有自定义列和排序。
6. HIS 写入、engine 同步、患者 Web 操作、患者列表和质控链路通过联调。
7. 旧前端缓存、旧外置 pbtxt、旧服务实例不会在切换后继续使用旧契约。
8. 三工程构建与测试通过，生产副本演练和回滚演练通过。

## 已决策

1. `discharged_diagnosis_code` 无损重命名为 `discharge_diagnosis_code`，类型保持
   `VARCHAR(1000)`，业务含义继续是“出科诊断编码”。
2. 本次不向 `PatientInfoPB` 新增 `from_dept_id`、`to_dept_id`、`discharge_account_id` 或
   `discharge_diagnosis_code`，也不新增对应前端详情字段。
3. 嘉禾美康本次不实现 `from_dept_id` 来源映射，保持空值；不得用当前科室编码代填。
4. 卫宁 HL7 本次不实现 `from_dept_id/name` 来源映射，相关字段保持空值；不得猜测 PV1/Z 段。
5. 本次采用停机发布，不设计零停机、滚动混跑、数据库双列或 JSON 双写。
6. 不保留旧动态列 ID alias。停机后备份并删除 `dept_system_settings` 中函数 1–4 的记录，
   由新版 engine 使用所选医院新版 pbtxt 的默认值重新初始化。
7. `his_patient_records.discharged_type` 在业务中没有实际用途且未同步到 `patient_records`，
   本次正向 DDL 直接删除；回滚依靠上线前 `id/value` 备份恢复。
8. 前端执行 production build 后，将完整 `dist` 复制到 engine `static`，再执行
   `mvn clean package` 生成发布 jar。

## 待决策

1. **生产外部消费者清单。** 仓库无法确认 BI、ETL、JFK 模板、临时 SQL、第三方直连和
   线上残留旧服务是否引用旧列名；上线前需逐项给出负责人和处置结论。
2. **各生产环境实际配置。** 需确认每家医院实际选择的 engine pbtxt、`icis_j3` 外置 pbtxt、
   服务实例拓扑和缓存层，避免按错误默认配置重建函数 1–4 的患者列表设置。
3. **生产缓存失效方式。** 前端产物进入 engine jar 的步骤已确定，但浏览器、反向代理或 CDN
   的具体刷新命令、责任人和验收方法无法从仓库确认，需按现场架构补齐。
