# 诊断表结构通用化——代码最小化改动计划

## 1. 文档状态

- 日期：2026-07-24
- 适用工程：`icis_j3`、`jingyi_icis_engine2`、`jingyi_icis_frontend`
- 配套文档：`2026-07-24-diagnosis-schema-generalization-production-playbook.md`
- 本文性质：后续实现、评审、测试和发布的需求基线
- 当前实施状态：仅完成代码调研和方案文档；**尚未修改业务代码、Proto、配置或数据库**

本次调研基线：

| 工程 | 分支 | commit |
| --- | --- | --- |
| `icis_j3` | `master` | `0ca6e08c7bcaac120cf9c01aea0b3d4afe2a8357` |
| `jingyi_icis_engine2` | `main` | `a333b5a548863651719178d647fdaf803b365a45` |
| `jingyi_icis_frontend` | `dev` | `496a70d53f848b319219f0f4faa683169b6e968d` |

后续实现前若基线已变化，必须重新搜索本文列出的旧字段，并复核新增调用点。

## 2. 目标

诊断数据不再通过三张表中的专用“中医诊断”列和患者主表中的旧字符串类型列扩展。数据库
结构按以下要求调整：

1. `his_patient_records` 删除：
   - `diagnosis_tcm_time`
   - `diagnosis_tcm_code`
   - `diagnosis_tcm`
2. `patient_records` 删除：
   - `diagnosis_tcm`
   - `diagnosis_type`
3. `diagnosis_history`：
   - 删除 `diagnosis_tcm`
   - 删除 `diagnosis_tcm_code`
   - `patient_id` 改为允许 `NULL`
   - 新增八个可空占位字段：
     - `patient_key_type INTEGER`
     - `his_encounter_id VARCHAR(255)`
     - `his_mrn VARCHAR(255)`
     - `his_admission_count INTEGER`
     - `his_patient_id VARCHAR(255)`
     - `dept_admission_time TIMESTAMP`
     - `diagnosis_type INTEGER`：`1` 入院诊断，`2` 科室诊断
     - `diagnosis_category INTEGER`：`1` 中医，`2` 西医

本次只建立未来扩展所需的数据库槽位，不让现有业务代码读取、填写、筛选或校验这八个新增
字段。

## 3. 最小化原则

这里的“最小化”指**最小完整改动**，不是只让代码勉强编译：

1. 所有仍会读写已删除列的 JPA 映射、同步逻辑、API 映射、报表和页面必须移除。
2. 对外 Proto 中删除的字段必须保留 field number 和字段名为 `reserved`，不得复用。
3. 新增占位列本次只进入完整建库脚本和生产 DDL：
   - 不加入 `DiagnosisHistory` JPA 实体；
   - 不加入 Repository 查询；
   - 不加入 Web API / gRPC Proto；
   - 不加入前端 TypeScript 类型或页面；
   - 不回填、不设置默认值、不增加索引、外键或检查约束。
4. 保留现有通用字段及其行为：
   - `his_patient_records.diagnosis_time/diagnosis_code/diagnosis`
   - `patient_records.diagnosis`
   - `diagnosis_history.diagnosis/diagnosis_code/diagnosis_time`
5. 不把旧中医诊断值或旧 `patient_records.diagnosis_type` 值自动搬进新字段。
6. 不顺带重做患者匹配、诊断去重、诊断分类、HIS 诊断同步策略或诊断页面交互模型。

Hibernate 生产配置使用 `ddl-auto=validate`。Hibernate 会验证已映射列，但不会因为数据库存在
额外的未映射列而失败，因此八个占位列无需为了本次发布加入实体。

## 4. 最终数据库契约

### 4.1 字段变更矩阵

| 表 | 操作 | 字段 | 目标类型/空值 | 备注 |
| --- | --- | --- | --- | --- |
| `his_patient_records` | 删除 | `diagnosis_tcm_time` | — | 删除专用中医诊断时间 |
| `his_patient_records` | 删除 | `diagnosis_tcm_code` | — | 删除专用中医诊断编码 |
| `his_patient_records` | 删除 | `diagnosis_tcm` | — | 删除专用中医诊断内容 |
| `patient_records` | 删除 | `diagnosis_tcm` | — | 页面和报表不再从患者主表读取中医诊断 |
| `patient_records` | 删除 | `diagnosis_type` | — | 旧列为 `VARCHAR(100)`，不迁移 |
| `diagnosis_history` | 删除 | `diagnosis_tcm` | — | 历史值只备份，不自动转换 |
| `diagnosis_history` | 删除 | `diagnosis_tcm_code` | — | 历史值只备份，不自动转换 |
| `diagnosis_history` | 改约束 | `patient_id` | `BIGINT NULL` | 现有索引保留 |
| `diagnosis_history` | 新增 | `patient_key_type` | `INTEGER NULL` | 枚举语义待未来需求定义 |
| `diagnosis_history` | 新增 | `his_encounter_id` | `VARCHAR(255) NULL` | 占位 |
| `diagnosis_history` | 新增 | `his_mrn` | `VARCHAR(255) NULL` | 占位 |
| `diagnosis_history` | 新增 | `his_admission_count` | `INTEGER NULL` | 占位 |
| `diagnosis_history` | 新增 | `his_patient_id` | `VARCHAR(255) NULL` | 占位 |
| `diagnosis_history` | 新增 | `dept_admission_time` | `TIMESTAMP NULL` | 与现有项目 UTC `LocalDateTime` 存储口径一致 |
| `diagnosis_history` | 新增 | `diagnosis_type` | `INTEGER NULL` | `1` 入院诊断，`2` 科室诊断 |
| `diagnosis_history` | 新增 | `diagnosis_category` | `INTEGER NULL` | `1` 中医，`2` 西医 |

### 4.2 `diagnosis_history` 完整目标片段

完整建库脚本中的逻辑顺序应为：

```sql
CREATE TABLE diagnosis_history (
    id SERIAL PRIMARY KEY,
    patient_id BIGINT,
    patient_key_type INTEGER,
    his_encounter_id VARCHAR(255),
    his_mrn VARCHAR(255),
    his_admission_count INTEGER,
    his_patient_id VARCHAR(255),
    dept_admission_time TIMESTAMP,
    diagnosis_type INTEGER,
    diagnosis_category INTEGER,

    diagnosis TEXT,
    diagnosis_code VARCHAR(1000),

    diagnosis_time TIMESTAMP NOT NULL,
    diagnosis_account_id VARCHAR(255) NOT NULL,

    is_deleted BOOLEAN NOT NULL DEFAULT false,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255),
    modified_at TIMESTAMP,
    modified_by VARCHAR(255),
    modified_by_account_name VARCHAR(255)
);
```

### 4.3 列顺序说明

PostgreSQL 不支持 `ADD COLUMN ... AFTER patient_id`。因此：

- `schema.postgresql.sql` 按第 4.2 节顺序放置，确保新建库符合“在 `patient_id` 后面”的逻辑
  排列要求。
- 生产库采用原地 `ALTER TABLE ADD COLUMN`，新增列会位于物理列序末尾。
- 不允许仅为了列的物理顺序重建 `diagnosis_history`；重建会引入不必要的数据复制、长锁、
  序列、索引、权限和依赖对象风险。
- 应用查询必须显式列名，不能依赖 `SELECT *` 的列序。

### 4.4 约束、默认值和索引

本次不新增：

- `NOT NULL`
- `DEFAULT`
- `CHECK`
- 外键
- 新索引

保留 `idx_diagnosis_history_patient_id`。该索引继续服务当前按 `patient_id` 查询的路径；未来
确定 `patient_key_type` 和 HIS 标识查询模型后，再单独设计组合索引。

### 4.5 两个 `diagnosis_type` 不是同一字段

必须明确区分：

| 字段 | 旧/新 | 类型 | 含义 |
| --- | --- | --- | --- |
| `patient_records.diagnosis_type` | 删除 | `VARCHAR(100)` | 现有代码注释为“较少使用，reserved” |
| `diagnosis_history.diagnosis_type` | 新增 | `INTEGER` | `1` 入院诊断，`2` 科室诊断 |

禁止重命名旧列或把旧字符串值复制到新整数列。生产迁移中所有新列初始值均为 `NULL`。

## 5. 当前代码链路调研结论

```text
惠每 XML tcm_diags
  -> icis_j3 HisPatientParserPB / HisPatientPB
  -> his_patient_records.diagnosis_tcm_*
  -> engine PatientSyncUtils
  -> patient_records.diagnosis_tcm
  -> diagnosis_history.diagnosis_tcm_*
  -> 患者详情 / 诊断历史 / patient_info_extended 护理文书
```

因此直接先删数据库列会造成：

- 旧 `icis_j3` 保存 `HisPatientRecord` 时 SQL 失败；
- 旧 engine 查询或保存三个实体时 SQL 失败；
- 旧前端继续发送 `diagnosisTcm*` JSON key 时，新 Proto JSON 解析失败；
- 旧外置惠每 pbtxt 交给删除字段后的 parser descriptor 时启动失败；
- 护理文书 `diagnosis_and_surgery` 仍访问已删除的 Java 属性而无法编译。

### 5.1 `icis_j3`

已确认的运行时引用：

- `data/dao/HisPatientRecord.java` 映射三个待删列。
- `data/write/PatientRecordSyncService.java` 从 `HisPatientPB` 解析并写入三个字段。
- `data_bridge_service.proto` 的 `HisPatientPB` 使用 field `47/48/49`。
- `j3_parser.proto` 的配置字段使用 field `93–98`。
- 惠每内置 `xacph_xml_parser.pb.txt` 在入院和出院事件解析 `tcm_diags`。
- `HuimeiAdtXmlFieldParser` 根据字段名分支解析中医/西医诊断。
- 嘉禾美康 patch/writer 保留三个中医字段，虽然当前 parser 没有给它们赋实际来源。
- 卫宁实现只有三个“na”注释，没有实际 builder 写入。

`icis_j3` 的轻量 `PatientRecord` 实体只映射 `id/his_mrn/his_patient_id`，不映射本次
`patient_records` 待删列，因此不需要修改该实体。

### 5.2 `jingyi_icis_engine2`

已确认的运行时引用：

- 三个 JPA 实体均映射相应待删列。
- `PatientSyncUtils` 将 HIS 中医诊断写到患者主表，并创建单独的中医
  `DiagnosisHistory` 记录。
- `PatientService`：
  - 维护患者主表中西医两个“最新诊断”；
  - 在诊断历史 CRUD 中读写两个中医字段；
  - 在患者动态列表中支持 `diagnosis_tcm/diagnosis_type` 列 ID；
  - 在 `PatientInfoPB` 构造和更新中读写 `diagnosisTcm/diagnosisType`。
- `PatientInfoExtendedDataSourceHandler` 把 `diagnosis`、`diagnosis_tcm` 和手术名称拼入
  `diagnosis_and_surgery`。
- `PatientInfoPB` 使用 field `57/58` 暴露患者中医诊断和旧诊断类型。
- `DiagnosisHistoryPB` 使用 field `6/7` 暴露中医诊断和编码。
- engine 内镜像 `HisPatientPB` 使用 field `47/48/49`。
- `patient_testdata.sql`、`PatientTestUtils` 和报表测试包含旧字段。
- engine 内置静态前端 bundle 仍包含旧页面和 JSON key。

不受结构删除影响、但必须回归：

- `DiagnosisHistoryRepository` 当前按 `patient_id` 查询，索引仍保留。
- `PatientReadmissionService` 只迁移诊断历史的 `patient_id`。
- 脓毒症/感染性休克判断只读取通用 `diagnosis`。
- Ward Report 的“插入诊断历史”当前只使用 `DiagnosisHistoryPB.diagnosis`。
- JFK 模板使用稳定的 `patient_info:diagnosis` 或
  `patient_info_extended:diagnosis_and_surgery` 数据源 ID，不直接绑定待删数据库列。

### 5.3 `jingyi_icis_frontend`

已确认的引用：

- `src/api/WebApi.ts`
  - `PatientInfoPB.diagnosisTcm/diagnosisType`
  - `DiagnosisHistoryPB.diagnosisTcm/diagnosisTcmCode`
- 患者详情展示一块只读“中医诊断”表单。
- 诊断历史弹窗同时展示和编辑西医、中医两组内容/编码。
- `Text.ts` 包含四个只服务于上述页面的中医诊断文本。

`PatientApi.ts` 的四个诊断历史 endpoint 和 Ward Report 插入诊断组件只传输通用对象或读取
`diagnosis`，接口路径无需改名。

## 6. 最小完整代码改动

### 6.1 `icis_j3`

#### 6.1.1 JPA 与写入

修改：

- `src/main/java/com/jingyicare/icis_j3/data/dao/HisPatientRecord.java`
  - 删除 `diagnosisTcmTime/diagnosisTcmCode/diagnosisTcm`。
- `src/main/java/com/jingyicare/icis_j3/data/write/PatientRecordSyncService.java`
  - 删除中医时间解析；
  - 删除三个 Proto getter 到实体 setter 的写入。

#### 6.1.2 Proto

`src/main/proto/data_bridge_service.proto` 的 `HisPatientPB` 删除并保留：

```proto
reserved 47 to 49;
reserved "diagnosis_tcm_time_iso8601", "diagnosis_tcm_code", "diagnosis_tcm";
```

`src/main/proto/j3_parser.proto` 的 `HisPatientParserPB` 删除并保留：

```proto
reserved 93 to 98;
reserved
    "diagnosis_tcm_time_iso8601_xml_path",
    "diagnosis_tcm_time_iso8601_parser_name",
    "diagnosis_tcm_code_xml_path",
    "diagnosis_tcm_code_parser_name",
    "diagnosis_tcm_xml_path",
    "diagnosis_tcm_parser_name";
```

不得把这些编号分配给未来的通用诊断字段。

#### 6.1.3 XML/HL7 适配

- `src/main/resources/config/xml-platforms/huimei/xacph_xml_parser.pb.txt`
  - 删除入院、出院事件中的 `diagnosis_tcm_xml_path/parser_name`。
- 生产服务器由 `--jingyi.xml.config` 指定的所有外置 pbtxt 做同样修改。
- `HuimeiAdtXmlFieldParser`
  - 保留通用 `diagnosis/diagnosis_code` 解析；
  - 删除依赖 `diagnosis_tcm*` 字段名的分支。
- `JiaheMeikangPatientPatch`
  - 删除三个无来源的中医属性。
- `JiaheMeikangPatientRecordWriter`
  - 删除取消入科时的三个清空调用；
  - 删除 patch 写入调用。
- `WinningHis`
  - 删除三个过时的 `na` 列注释；通用 DG1 诊断解析保持不变。

#### 6.1.4 测试

- 扩展 `HisPatientProtoCompatibilityTests`：
  - descriptor 中三个名称不存在；
  - wire field `47/48/49` 被作为 unknown field 保留。
- `XmlParserTests`：
  - 通用诊断继续解析；
  - 含 `tcm_diags` 的惠每样本不会导致解析或写库失败；
  - 内置及测试 pbtxt 不再包含已删除配置键。
- 嘉禾美康取消入科/更新测试确认不再调用已删除 setter。

### 6.2 `jingyi_icis_engine2`

#### 6.2.1 完整建库脚本

修改 `src/main/resources/config/db/schema.postgresql.sql`：

- 删除七个旧列及其注释。
- `diagnosis_history.patient_id` 去掉 `NOT NULL`。
- 按第 4.2 节顺序增加八个占位列和注释。
- 保留 `idx_diagnosis_history_patient_id`。

该文件是新建库基线，不是生产迁移脚本，禁止在线上直接执行整个文件。

#### 6.2.2 实体

- `HisPatientRecord.java`：删除三个中医属性。
- `PatientRecord.java`：删除 `diagnosisTcm`、`diagnosisType` 和自定义 getter。
- `DiagnosisHistory.java`：删除 `diagnosisTcm`、`diagnosisTcmCode`。

以下内容本次**不修改**：

- `DiagnosisHistory.patientId` 已经是可空包装类型 `Long`，且 `@Column` 没有
  `nullable=false`，无需机械改动。
- 八个占位字段不加入 `DiagnosisHistory` 实体。

#### 6.2.3 患者同步

`PatientSyncUtils.updatePatientRecord(...)`：

- 保留通用 `hisRecord.diagnosis -> patientRecord.diagnosis`；
- 保留创建通用 `DiagnosisHistory(diagnosis, diagnosisCode, diagnosisTime)`；
- 删除中医诊断同步和中医历史记录创建；
- 注释由“三字段诊断”改成通用诊断。

这意味着新版上线后，惠每 `tcm_diags` 不再进入 ICIS 数据库。本次不建立替代采集链路。

#### 6.2.4 诊断历史 CRUD

`PatientService` 的诊断历史接口保留原 endpoint 和通用字段：

- `diagnosis`
- `diagnosis_code`
- `diagnosis_time`
- `diagnosis_account_id`

删除：

- 最新中医诊断查找和 `patient_records` 回写；
- `DiagnosisHistoryPB` 中医字段构造；
- add/update 的中医字段读取和实体赋值；
- delete 后的最新中医诊断重算。

旧 HIS 同步会为中医诊断创建独立的“仅 `diagnosis_tcm*` 有值”历史行。删列后这些行会成为
没有通用诊断内容的审计壳。新版同时允许同一病人、同一诊断时间保存多条有效诊断，行为是：

1. `getDiagnosisHistory` 只返回 `diagnosis` 或 `diagnosis_code` 至少一个非空的行。
2. add/update 不再按 `patient_id + diagnosis_time` 判重。
3. 同一时间的记录按 `id` 倒序展示，后创建的记录优先作为患者主表的最新诊断。
4. 不物理删除或修改遗留空壳；其原中医值只存在于迁移备份。

这项过滤不读取任何新增占位字段。

#### 6.2.5 患者详情和动态列表

`PatientService`：

- `getPatientRecordValue` 删除 `diagnosis_tcm`、`diagnosis_type` case。
- `PatientInfoPB` builder 不再写入两个字段。
- `updatePatientRecord` 不再读取两个字段。

仓库内四份标准患者显示配置当前只使用 `diagnosis`，没有使用待删列。生产
`dept_system_settings.settings_pb` 仍须按 Playbook 做字节扫描；如果存在自定义旧列 ID，
必须先备份并按批准方案重置，否则动态列表会抛出未知列异常。

#### 6.2.6 报表

`PatientInfoExtendedDataSourceHandler.diagnosisAndSurgery(...)` 改为只拼接：

1. 非空 `patient_records.diagnosis`
2. `手术: ...`

数据源 ID `diagnosis_and_surgery` 不变，JFK 模板无需机械修改。同步更新
`PatientInfoExtendedDataSourceHandlerTests` 和既有需求文档中关于
`diagnosis_tcm` 的拼接说明。

#### 6.2.7 Proto

`src/main/proto/config/icis_patient.proto`：

```proto
// PatientInfoPB
reserved 57, 58;
reserved "diagnosis_tcm", "diagnosis_type";
```

`src/main/proto/icis_web_api.proto`：

```proto
// DiagnosisHistoryPB
reserved 6, 7;
reserved "diagnosis_tcm", "diagnosis_tcm_code";
```

`src/main/proto/grpc/data_bridge_service.proto` 与 `icis_j3` 镜像保持一致：

```proto
reserved 47 to 49;
reserved "diagnosis_tcm_time_iso8601", "diagnosis_tcm_code", "diagnosis_tcm";
```

#### 6.2.8 测试数据和测试

需要修改：

- `src/test/java/.../testutils/PatientTestUtils.java`
- `src/test/resources/db/patient_testdata.sql`
- `PatientProtoCompatibilityTests`
- `PatientInfoExtendedDataSourceHandlerTests`
- 患者同步测试
- 诊断历史 CRUD 测试

`patient_testdata.sql` 当前还含有若干早于当前 schema 的其他历史列漂移。实现本需求时至少必须
移除本次待删字段及对应值，并保证被实际执行的测试 SQL 与目标 schema 列数一致；其他无关漂移
如需修复，应独立提交，避免混入本次生产 DDL。

新增/补强用例：

1. 通用 HIS 诊断仍写入患者主表和历史表。
2. HIS 消息携带中医节点时不访问任何已删列。
3. 诊断历史 add/get/update/delete 只使用通用字段。
4. 遗留空壳不返回给前端；同一病人同一时间允许新增、编辑多条通用诊断。
5. 患者详情 V2 JSON 不再包含 `diagnosisTcm/diagnosisType`。
6. `diagnosis_and_surgery` 只拼通用诊断和手术。
7. 脓毒症诊断匹配和重返 ICU 的 `patient_id` 迁移不回归。
8. Proto 删除编号全部作为 unknown/reserved，未被复用。

### 6.3 `jingyi_icis_frontend`

#### 6.3.1 类型

`src/api/WebApi.ts`：

- `PatientInfoPB` 删除 `diagnosisTcm/diagnosisType`。
- `DiagnosisHistoryPB` 删除 `diagnosisTcm/diagnosisTcmCode`。

endpoint、请求包裹结构和 `diagnosis/diagnosisCode` 保持不变。

#### 6.3.2 页面

患者详情：

- 删除只读“中医诊断”块。
- 通用诊断块从当前 13 栅格扩展到完整行，避免删除后留下空白布局。

诊断历史弹窗：

- 卡片只展示通用诊断编码和内容。
- 新增/编辑表单删除中医诊断编码和内容输入。
- 时间线条目以诊断历史 `id` 作为唯一 key，同一诊断时间的多条记录分别展示和操作。
- 删除不再使用的 `ScheduleOutlined` import。

Ward Report 的插入诊断历史组件继续只取 `selectedRow.diagnosis`，但表格行 key 从
`diagnosisTimeIso8601` 改为诊断历史 `id`，避免同一时间的多条记录互相覆盖或联动选中。

`Text.ts` 中四个已无调用者的中医诊断常量可以随本次删除，避免误导未来开发；这属于无风险
清理，不扩展业务范围。

#### 6.3.3 构建和内置静态资源

1. 在 frontend 执行 `npm run build`。
2. 将完整 `dist/` 同步到
   `jingyi_icis_engine2/src/main/resources/static/`。
3. 不手工编辑压缩后的 `home.bundle.js/common.bundle.js`。
4. engine 必须在静态资源同步后重新执行 `mvn clean package`。

## 7. 明确不做的事项

本次不做：

- 不定义 `patient_key_type` 枚举值。
- 不让新字段参与患者匹配、查询、过滤、排序、去重或 API 返回。
- 不回填八个新字段。
- 不拆分一行中同时存在的西医/中医诊断。
- 不把旧中医诊断复制到通用 `diagnosis`，否则当前代码会把它误当作患者最新通用诊断。
- 不把旧字符串 `patient_records.diagnosis_type` 转换成新整数类型。
- 不新增诊断表唯一约束或复合索引。
- 不改变 `diagnosis_time`、诊断医生、逻辑删除和修改审计字段。
- 不改变入院诊断、出科诊断或手术历史表。
- 不改造 `DiagnosisHistory` 接口为多种 patient key 查询。
- 不处理 `patient_id IS NULL` 记录的 UI/CRUD；当前官方 API 仍只创建带 `patient_id` 的记录。
- 不修改 `icis_jd2` 或归档工程；上线前只盘点是否存在旧实例或外部 SQL。

## 8. 数据处理决策

### 8.1 不做语义回填

本次删除会让旧中医诊断在新版应用中不可见。原因是现有历史行可能同时包含通用诊断和中医
诊断，也可能是中医专用行；在新分类字段尚未接入代码时，自动合并或拆行会改变“最新诊断”
选择、质控和报表结果。

因此生产策略是：

1. 上线前统计所有待删列的 NULL、空串和非空值。
2. 停写后按主键完整备份待删列。
3. 由业务负责人签字确认不在本次版本转换这些值。
4. 执行删列。
5. 观察期内保留行级备份和整库恢复能力。

非空数据不是自动禁止上线的条件，但**没有数据影响确认和可验证备份时必须 No-Go**。

### 8.2 `patient_id` 可空的当前效果

- 官方 add diagnosis API 仍先校验患者并写入非空 `patient_id`。
- 当前按患者查询、重返 ICU 和质控路径继续只处理有 `patient_id` 的记录。
- 新版发布后官方代码不应产生 `patient_id IS NULL`。
- 未来外部写入的无 patient ID 记录不会自动出现在现有患者页面。
- 未来扩展前不得假设 update/delete endpoint 能安全操作无 `patient_id` 记录。

### 8.3 新字段全部为 `NULL`

上线后应验证八个新字段的非空计数均为零。任何非零值表示存在本次范围外的写入者，必须先
盘点再决定是否继续观察或回滚。

## 9. 兼容性与发布约束

### 9.1 Proto 二进制

删除并 reserve field number 后：

- 旧二进制中的字段会作为 unknown fields 被新版保留/忽略；
- 不得复用编号；
- 这不代表 JSON 或 TextFormat 兼容。

### 9.2 Proto JSON

`PatientInfoPB` 和 `DiagnosisHistoryPB` 通过 HTTP JSON 使用 camelCase key。旧浏览器会发送
`diagnosisTcm/diagnosisType/diagnosisTcmCode`，新 engine 的严格 parser 会返回解析失败。

因此：

- 前端和 engine 必须同一维护窗口切换。
- engine jar 内必须携带新版静态资源。
- 必须刷新浏览器、反向代理和 CDN 缓存。
- 不允许新旧 engine 实例滚动混跑。

### 9.3 Proto TextFormat / pbtxt

删除 `HisPatientParserPB` 字段后，仍含旧键的惠每外置 pbtxt 会使 `icis_j3` 启动失败。所有
实际使用的外置配置必须与新版 jar 一起交付并校验 SHA-256。

### 9.4 数据库

数据库删列后，任何旧 JPA 实体的普通查询或保存都可能失败。DDL 前必须停止：

- 所有 `icis_j3` 实例；
- 所有 engine 实例和定时患者同步；
- 未盘点的旧 bridge、脚本、ETL、报表和第三方直连写入者。

## 10. 推荐实现顺序

1. 冻结本文字段矩阵和 Proto reserved 编号。
2. 修改 `icis_j3` Proto、配置、实体、写入和测试。
3. 修改 engine Proto、schema 基线、实体、同步、服务、报表和测试。
4. 修改 frontend 类型和页面。
5. 前端 production build，完整同步 engine static。
6. 三工程执行完整构建和测试。
7. 在生产脱敏副本执行配套 Playbook 的正向迁移、冒烟和完整回滚。
8. 冻结三个 commit、jar/static/pbtxt/SQL 的制品清单。
9. 维护窗口内停写、备份、DDL、整版发布。

## 11. 验证命令与验收

### 11.1 构建

```bash
# icis_j3
mvn clean test

# frontend
npm run build

# engine（已同步新版 static）
mvn clean test
mvn clean package
```

### 11.2 源码静态检查

允许旧名称仅出现在：

- 本迁移文档；
- Proto `reserved` 名称；
- 专门验证 legacy unknown field 的测试名称/数据；
- 迁移备份和回滚 SQL。

运行时代码、pbtxt、前端源码和新建库列定义不得再出现：

```text
diagnosis_tcm_time
diagnosis_tcm_code
diagnosis_tcm
patient_records.diagnosis_type
diagnosisTcmTime
diagnosisTcmCode
diagnosisTcm
PatientRecord.diagnosisType
```

### 11.3 功能验收

- HIS 通用诊断采集和患者同步正常。
- 含 `tcm_diags` 的惠每消息不会导致启动、解析或 SQL 错误。
- 患者详情只展示通用诊断。
- 诊断历史通用诊断新增、编辑、删除、排序正常。
- Ward Report 可插入通用诊断。
- 护理文书“诊断及手术”只展示通用诊断和手术。
- 脓毒症/感染性休克质控结果不回归。
- 三张表行数迁移前后不变，除明确批准的业务操作外不修改现有行。
- 八个占位列全部为 `NULL`。
- `diagnosis_history.patient_id` 为 nullable，现有非空值保持不变。

## 12. 完成定义

同时满足以下条件才算实现完成：

1. 三工程代码按本文修改并通过评审。
2. Proto 编号已 reserve，镜像定义一致。
3. 内置和所有外置惠每 pbtxt 不含已删除键。
4. frontend static 已进入最终 engine jar。
5. 生产 DDL 和反向 DDL 在同版本 PostgreSQL 脱敏副本演练成功。
6. 待删数据已统计、签字确认并完成可验证备份。
7. 存量动态显示设置、JFK 模板、外部 SQL 和旧实例盘点完成。
8. 新版运行时代码不再读写任何已删除列。
9. 新增字段没有被本次业务代码使用，值保持 `NULL`。
10. 发布和回滚按配套 Playbook 完成记录。
