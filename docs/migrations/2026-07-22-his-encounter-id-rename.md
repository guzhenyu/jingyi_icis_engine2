# HIS Encounter ID 字段重命名迁移方案

## 1. 目标

统一 HIS 就诊标识在数据库、Java 实体和 protobuf 中的命名：

| 范围 | 旧名称 | 新名称 |
| --- | --- | --- |
| `his_patient_records` 数据库列 | `patient_serial_number` | `his_encounter_id` |
| `patient_records` 数据库列 | `his_patient_serial_number` | `his_encounter_id` |
| Java 领域属性 | `patientSerialNumber` / `hisPatientSerialNumber` | `hisEncounterId` |
| `HisPatientPB` protobuf 字段 | `patient_serial_number` | `his_encounter_id` |

本次迁移只重命名并保留现有值，不改变数据来源、患者同步条件或字段业务逻辑。

> 注意：当前 `jingyi_icis_engine2` 的患者同步仍按 MRN 合并患者，`hisEncounterId` 只是从 `his_patient_records` 复制到 `patient_records`。如果后续要改成按 encounter ID 匹配患者，应作为独立需求设计和迁移。

## 2. 项目范围

### 2.1 需要修改和发布

- `icis_j3`
- `jingyi_icis_engine2`

### 2.2 不需要修改或停机

- `icis_jd2`
  - 只访问 `device_data`、`device_data_hourly`、`device_data_hourly_approx`、`device_infos`、`raw_bga_records`、`raw_bga_record_details` 等设备数据表。
  - 不依赖 `patient_records`、`his_patient_records` 或本次重命名的字段。
- `jingyi_icis_frontend`
  - 当前 `src/api/WebApi.ts` 中不存在 `patientSerialNumber`、`hisPatientSerialNumber` 或 `hisEncounterId` 显式字段。
  - 患者列表通过通用的 `colId/value` 结构传输，默认不需要修改前端类型。

### 2.3 已废弃项目

- 旧 `icis_jd` 已移动到仓库根目录的 `archives/icis_jd`，不纳入修改和发布范围。
- `icis_bridge` 已废弃，不纳入代码修改范围；上线前仍需确认没有旧实例、守护进程或旧 jar 在运行。

## 3. 关键兼容性原则

### 3.1 protobuf field number 必须保持不变

protobuf 二进制数据保存 field number 和 wire type，不保存字段名。以下改名必须保持原编号和类型：

```proto
// before
string patient_serial_number = 4;

// after
string his_encounter_id = 4;
```

在编号、类型、`repeated`/单值结构不变的前提下：

- 旧版本序列化的 protobuf 二进制可以被新版本读取。
- 新版本序列化的 protobuf 二进制可以被旧版本读取。
- protobuf 二进制再编码为 Base64 也保持上述兼容性。

禁止删除 field 4 后使用新编号重新添加 `his_encounter_id`。

### 3.2 字段名敏感的格式不具备上述兼容性

以下场景按字段名工作，必须同步修改：

- protobuf JSON
- protobuf TextFormat / `.pb.txt`
- Java 生成代码的 getter/setter
- `Descriptors.findFieldByName()` 等反射调用
- 日志中的 protobuf 文本
- 把数据库列名保存为普通字符串的配置

### 3.3 `DisplayFieldSettingsPB` 不需要数据迁移

Base64 只是 protobuf 二进制的文本表示。理论上，如果 protobuf 的字符串值本身保存了旧列名，该字符串不会因为 protobuf 字段改名而自动变化。

本项目中的 `DisplayFieldSettingsPB` 虽然包含 `column_id` 和
`col_id_to_display` 字符串，但经代码和配置审计：

- `icis_web_api.proto` 没有暴露 `DisplayFieldSettingsPB` 的修改接口。
- 前端的患者设置接口只读写护理报表起始页码，与患者列表显示字段无关。
- `PatientConfig` 只从 `default_display_field_settings` 初始化缺失记录，没有更新显示字段设置的业务入口。
- 当前四份患者配置及其 Git 历史均未出现 `patient_serial_number` 或
  `his_patient_serial_number` 显示列。

因此正常代码路径不会在 `dept_system_settings.settings_pb` 中产生本次旧列名，
无需 Base64 数据迁移、启动迁移参数或旧 `colId` 兼容 alias。

## 4. protobuf 修改清单

| 项目 | 文件 | message | 旧字段 | 新字段 | 编号 |
| --- | --- | --- | --- | --- | ---: |
| `icis_j3` | `src/main/proto/data_bridge_service.proto` | `HisPatientPB` | `patient_serial_number` | `his_encounter_id` | 4 |
| `icis_j3` | `src/main/proto/j3_parser.proto` | `HisPatientParserPB` | `patient_serial_number_xml_path` | `his_encounter_id_xml_path` | 7 |
| `icis_j3` | `src/main/proto/j3_parser.proto` | `HisPatientParserPB` | `patient_serial_number_parser_name` | `his_encounter_id_parser_name` | 8 |
| `jingyi_icis_engine2` | `src/main/proto/grpc/data_bridge_service.proto` | `HisPatientPB` | `patient_serial_number` | `his_encounter_id` | 4 |

`jingyi_icis_engine2` 中的 `data_bridge_service.proto` 当前未发现 Java 业务代码直接调用 `HisPatientPB`，但仍应同步定义，避免两个项目维护不同字段名。

### 4.1 `j3_parser.pb.txt` 配套修改

所有配置中的：

```text
patient_serial_number_xml_path: "message.body.xxx"
```

改成：

```text
his_encounter_id_xml_path: "message.body.xxx"
```

需要覆盖：

- `icis_j3/src/main/resources/config/xml-platforms/huimei/xacph_xml_parser.pb.txt`
- `icis_j3/src/test/test/java/com/jingyicare/icis_j3/xml/huimei/XmlParserTests.java` 中的内嵌 pbtxt
- 生产服务器通过 `--jingyi.xml.config=...` 指定的 jar 外部配置文件

`ConfigDrivenXmlMapper` 会从 `<field>_xml_path` 提取 `<field>`，再用 `findFieldByName()` 查找 `HisPatientPB` 字段。因此 `HisPatientPB` 和 `HisPatientParserPB` 必须在同一版本中一起改名。

旧 pbtxt 配置不能直接交给新 descriptor 解析，否则 `icis_j3` 可能在启动阶段失败。

## 5. `icis_j3` 代码修改清单

### 5.1 数据实体与查询

- `data/dao/HisPatientRecord.java`
  - `@Column(name = "his_encounter_id")`
  - 属性 `patientSerialNumber` 改为 `hisEncounterId`
- `data/dao/HisPatientRecordRepository.java`
  - `findByPatientSerialNumberOrderByIdDesc` 改为 `findByHisEncounterIdOrderByIdDesc`

### 5.2 protobuf 到实体的转换

以下代码中的 protobuf getter 改为 `getHisEncounterId()`，实体 getter/setter 改为 `getHisEncounterId()` / `setHisEncounterId()`：

- `data/write/PatientRecordSyncService.java`
- `data/write/HuimeiPatientRecordWritePolicy.java`
- `xml/platform/huimei/adt/HuimeiAdtXmlParser.java`
- `hl7/provider/winningsoft/WinningHis.java`

惠每和卫宁的原始字段来源不需要改变，只改变其进入领域模型后的名称。

### 5.3 嘉禾美康内部模型

建议同步修改以下内部 Java 名称，保持领域命名一致：

- `JiaheMeikangPatientPatch.patientSerialNumber` 改为 `hisEncounterId`
- `JiaheMeikangAdtXmlParser.patientSerialNumber(...)` 改为 `hisEncounterId(...)`
- `JiaheMeikangPatientRecordWriter` 中对应查找、写入和日志名称

这部分不是 protobuf wire compatibility 问题，但不修改会继续在业务代码中混用旧语义。

### 5.4 测试、日志和排查工具

同步修改：

- `PatientRecordSyncServiceTests`
- `XmlParserTests`
- `JiaheMeikangXmlParserTests`
- 日志中的 `PatientSerialNumber` / `patientSerialNumber`
- `py_tools/huimei_adt_patient_trace.py`
- 其他依赖 protobuf TextFormat 字段名的脚本

protobuf 文本日志会从：

```text
patient_serial_number: "..."
```

变成：

```text
his_encounter_id: "..."
```

## 6. `jingyi_icis_engine2` 代码修改清单

### 6.1 schema 与实体

- `src/main/resources/config/db/schema.postgresql.sql`
  - `his_patient_records.patient_serial_number` 改为 `his_encounter_id`
  - `patient_records.his_patient_serial_number` 改为 `his_encounter_id`
  - 更新两列的 COMMENT
- `entity/patients/HisPatientRecord.java`
  - `patientSerialNumber` 改为 `hisEncounterId`
- `entity/patients/PatientRecord.java`
  - `hisPatientSerialNumber` 改为 `hisEncounterId`

### 6.2 患者同步

`PatientSyncService.updatePatientRecord(...)` 中保持原有“目标为空时复制”的行为，仅修改字段名称：

```java
if (StrUtils.isBlank(patientRecord.getHisEncounterId())
    && !StrUtils.isBlank(hisRecord.getHisEncounterId())) {
    patientRecord.setHisEncounterId(hisRecord.getHisEncounterId());
    isUpdated = true;
}
```

本次不改变按 MRN 匹配患者的同步算法。

### 6.3 动态患者列表字段

`PatientService.getPatientRecordValue(...)` 通过字符串 `colId` 查找字段。新列名使用：

```java
case "his_encounter_id":
    return patientRec.getHisEncounterId();
```

由于受支持的配置和接口均不会保存旧列名，不保留
`his_patient_serial_number` alias。

### 6.4 测试数据

同步修改：

- `src/test/resources/db/patient_testdata.sql`
- `src/main/resources/config/db/testdata/*.sql`
- `PatientTestUtils`
- `PatientSyncServiceTests`
- 所有使用旧 getter/setter 或旧 SQL 列名的测试

## 7. 前端与 Web API

### 7.1 当前迁移不需要修改 `WebApi.ts`

当前前端没有旧字段的显式 TypeScript 定义。患者列表通过以下通用结构传输：

```ts
export interface PatientTableDataCellPB {
    colId: string;
    value: string;
}
```

后端返回 `colId: "his_encounter_id"` 时，前端会把它作为普通列 ID 处理，不需要增加专用字段。

### 7.2 如果未来需要在患者详情接口暴露 encounter ID

`config/icis_patient.proto` 的 `PatientInfoPB` 当前没有旧字段，因此这不是改名，而是新增字段。现有最大 field number 为 70，建议：

```proto
string his_encounter_id = 71;
```

同时在 `jingyi_icis_frontend/src/api/WebApi.ts` 的 `PatientInfoPB` 中增加：

```ts
hisEncounterId?: string;
```

并补充 `PatientService` 构造 `PatientInfoPB` 的赋值。若前端没有展示或编辑需求，本次迁移不要增加该字段，以缩小变更范围。

Web API 使用 protobuf JSON 而不是二进制 protobuf。任何已暴露 HTTP 字段的改名都会改变 JSON key，并需要前后端同步发布；本次目标字段当前未暴露，因此不存在该风险。

## 8. 显示设置结论

本次不修改 `dept_system_settings.settings_pb`，也不提供一次性 Java 迁移器。
如果生产环境曾经绕过现有接口人工写入患者显示设置，可在上线前执行以下只读检查：

```sql
SELECT dept_id, function_id
FROM dept_system_settings
WHERE function_id IN (1, 2, 3, 4)
  AND POSITION(
      convert_to('his_patient_serial_number', 'UTF8')
      IN decode(settings_pb, 'base64')
  ) > 0;
```

正常情况下结果应为 0 行。如果存在命中记录，应先确认其来源，再另行制定数据修复，
而不是为所有环境引入常驻启动参数。

## 9. 数据语义检查

字段重命名前应确认现有值确实可以统一称为 HIS encounter ID：

- 惠每 XML 当前来源为 `inhosp_no`。
- 卫宁 HL7 当前来源为 PV1-19。
- 嘉禾美康可能取住院号或就诊号，并存在降级逻辑。

改名不会转换历史值，也不会自动提高唯一性。现有实现允许同一个标识命中多条 `his_patient_records`，并选择 `id` 最大的记录，因此本次不要增加唯一约束。

建议迁移前统计：

```sql
SELECT
    COUNT(*) AS total_rows,
    COUNT(patient_serial_number) AS non_null_rows,
    COUNT(*) FILTER (WHERE BTRIM(patient_serial_number) = '') AS blank_rows
FROM his_patient_records;

SELECT patient_serial_number, COUNT(*)
FROM his_patient_records
WHERE NULLIF(BTRIM(patient_serial_number), '') IS NOT NULL
GROUP BY patient_serial_number
HAVING COUNT(*) > 1
ORDER BY COUNT(*) DESC
LIMIT 100;

SELECT
    COUNT(*) AS total_rows,
    COUNT(his_patient_serial_number) AS non_null_rows,
    COUNT(*) FILTER (WHERE BTRIM(his_patient_serial_number) = '') AS blank_rows
FROM patient_records;
```

## 10. 数据库迁移 SQL

`schema.postgresql.sql` 是完整建库脚本，不能作为生产增量迁移脚本执行。
本次已提供：

- `src/main/resources/config/db/migrations/2026-07-22-rename-his-encounter-id.sql`
- `src/main/resources/config/db/migrations/2026-07-22-rollback-his-encounter-id.sql`

正向脚本包含旧/新列状态检查、名称冲突保护、事务和迁移后列检查。核心 SQL 为：

```sql
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE his_patient_records
    RENAME COLUMN patient_serial_number TO his_encounter_id;

ALTER TABLE patient_records
    RENAME COLUMN his_patient_serial_number TO his_encounter_id;

COMMENT ON COLUMN his_patient_records.his_encounter_id
    IS 'HIS就诊ID';

COMMENT ON COLUMN patient_records.his_encounter_id
    IS 'HIS就诊ID';

COMMIT;
```

执行脚本时必须启用 `ON_ERROR_STOP`，保证任意语句失败后不会继续执行。两个列重命名必须在同一事务内完成。

### 10.1 可选索引优化

`icis_j3` 会按 encounter ID 查询并按 `id DESC` 获取最新记录。如果生产数据量较大，可评估增加：

```sql
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_his_patient_records_his_encounter_id_id
    ON his_patient_records (his_encounter_id, id DESC);
```

对应脚本为
`src/main/resources/config/db/migrations/2026-07-22-optional-his-encounter-id-index.sql`。
`CREATE INDEX CONCURRENTLY` 不得放在事务中执行。

不要为 `his_encounter_id` 添加唯一约束。

## 11. 上线前准备

1. 完成 `icis_j3` 和 `jingyi_icis_engine2` 的代码、proto、配置和测试修改。
2. 重新生成并编译 Java protobuf 类。
3. 准备并校验新版 jar。
4. 准备生产外置的新版 `jingyi.xml.config`。
5. 在生产数据库副本上执行完整演练：
   - 数据库列重命名
   - engine production profile 启动验证
   - `icis_j3` XML/HL7 入库验证
6. 准备数据库备份、旧 jar、旧外置 pbtxt、正向 SQL 和反向 SQL。
7. 确认 HIS/XML 上游在维护窗口内能够暂停，或具备可靠补发机制。
8. 确认废弃的 `icis_bridge` 没有运行。

## 12. 推荐上线步骤

1. 暂停 HIS HL7/XML 上游发送。
2. 等待 `icis_j3` 当前消息和线程池队列处理完成。
3. 优雅停止 `icis_j3`。
4. 优雅停止 `jingyi_icis_engine2`。
5. 保持 `icis_jd2` 运行。
6. 检查数据库中没有仍在访问目标表的长事务或旧服务连接。
7. 使用 `psql -v ON_ERROR_STOP=1` 执行正向数据库迁移脚本。
8. 验证：
    - 两个新列存在。
    - 两个旧列不存在。
    - 表行数和非空值数量与迁移前一致。
9. 启动新版 `jingyi_icis_engine2`。
10. 确认 production profile 的 Hibernate schema validation 成功，并检查健康接口。
11. 启动新版 `icis_j3`，确保加载的是新版外置 pbtxt。
12. 发送一条受控 ADT 消息并验证：
    - `his_patient_records.his_encounter_id` 写入正确。
    - engine 同步后 `patient_records.his_encounter_id` 一致。
    - 患者列表接口和页面正常。
13. 恢复上游发送。
14. 监控日志和数据库指标。

## 13. 验证清单

### 13.1 `icis_j3`

- 惠每 XML 能从 `his_encounter_id_xml_path` 写入 `HisPatientPB.his_encounter_id`。
- 卫宁 HL7 的 PV1-19 能写入 `HisPatientPB.his_encounter_id`。
- 嘉禾美康 encounter ID 查找、插入和更新逻辑通过测试。
- 同一 encounter ID 存在多条记录时仍选择 `id` 最大的记录。
- protobuf TextFormat 日志输出使用新字段名。
- 使用旧 descriptor 生成的 field 4 二进制测试数据可以被新 descriptor 正确读取。

### 13.2 `jingyi_icis_engine2`

- production profile 启动时 schema validation 通过。
- `his_patient_records.his_encounter_id` 正确同步到 `patient_records.his_encounter_id`。
- `his_encounter_id` 列值可以被患者列表通用 `colId/value` 逻辑读取。
- `patient_testdata.sql` 和患者模块测试通过。

### 13.3 前端

- 在科、待入科、待出科、已出科患者列表均可正常打开。
- 如果 encounter ID 被配置为显示列，表头和单元格值正确。
- 当前未增加 `PatientInfoPB.hisEncounterId` 时，`WebApi.ts` 不需要修改。

## 14. 回滚方案

发生以下情况时执行回滚：

- engine schema validation 失败。
- `icis_j3` 无法加载 XML parser pbtxt。
- HIS 患者写入或患者同步持续失败。
- 患者列表不可用。

### 14.1 回滚步骤

1. 再次暂停上游发送。
2. 停止新版 `icis_j3` 和 `jingyi_icis_engine2`。
3. 使用
   `src/main/resources/config/db/migrations/2026-07-22-rollback-his-encounter-id.sql`
   执行反向数据库迁移；其核心 SQL 为：

```sql
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '60s';

ALTER TABLE his_patient_records
    RENAME COLUMN his_encounter_id TO patient_serial_number;

ALTER TABLE patient_records
    RENAME COLUMN his_encounter_id TO his_patient_serial_number;

COMMENT ON COLUMN his_patient_records.patient_serial_number
    IS '病人流水号';

COMMENT ON COLUMN patient_records.his_patient_serial_number
    IS 'HIS系统中的病人记录表中的病人流水号';

COMMIT;
```

4. 恢复旧版外置 `jingyi.xml.config`。旧 jar 无法解析新字段名的 pbtxt。
5. 启动旧版 engine 和 `icis_j3`。
6. 验证后恢复上游发送。

由于数据库操作是列重命名，字段数据不会因正向或反向迁移丢失。protobuf field number 4 保持不变，因此二进制消息本身不需要反向数据转换。

## 15. 完成标准

- 仓库非归档运行时代码中不存在数据库旧列名引用。
- 三个目标 protobuf 定义完成改名且 field number 不变。
- 所有生产和测试 pbtxt 使用新 parser 字段名。
- 当前及历史患者显示配置中不存在旧列名，不需要迁移 `settings_pb`。
- `icis_j3`、`jingyi_icis_engine2` 测试和 production profile 启动验证通过。
- 生产库两列完成重命名，迁移前后统计一致。
- 受控 ADT 写入、engine 同步和前端患者列表验证通过。
- 上游恢复后无 `unknown field`、`column does not exist`、schema validation 或患者同步错误。
