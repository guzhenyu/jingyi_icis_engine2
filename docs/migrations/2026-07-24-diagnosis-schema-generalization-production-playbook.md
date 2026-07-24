# 诊断表结构通用化——生产迁移 Playbook

## 1. 使用范围

本文用于把以下组件从旧诊断字段契约切换到新契约：

- PostgreSQL：
  - `his_patient_records`
  - `patient_records`
  - `diagnosis_history`
- `icis_j3`：HIS/HL7/XML 患者诊断采集和 HIS 中间表写入
- `jingyi_icis_engine2`：患者同步、诊断历史 API、患者 API、报表和内置前端
- `jingyi_icis_frontend`：患者详情和诊断历史页面

代码改动范围和字段契约见
`2026-07-24-diagnosis-schema-generalization-minimal-change-plan.md`。

本文假定：

1. 三工程已经按配套计划完成修改并通过测试。
2. 八个 `diagnosis_history` 新字段本次只占位，官方代码不会写入。
3. 旧中医诊断和旧字符串 `patient_records.diagnosis_type` 不转换到新字段。
4. 采用有维护窗口的整版切换，不做滚动发布。

## 2. 安全原则

1. 先暂停 HIS/HL7/XML 输入、排空 `icis_j3` 队列，再停止全部 `icis_j3` 和 engine 实例。
2. 停写后完成整库快照和三张行级备份表，再执行删列。
3. 正向 DDL 必须在一个事务中完成，并配置 `ON_ERROR_STOP`、`lock_timeout`。
4. DDL 禁止使用 `CASCADE`，任何未知依赖都应让迁移失败并回到评审。
5. `schema.postgresql.sql` 是完整建库脚本，禁止在生产库直接执行。
6. 待删列存在非空数据时，必须有业务影响确认和可验证备份；否则 No-Go。
7. 新增字段不回填、不加默认值、索引、外键或检查约束。
8. 前端、engine jar、engine 内置 static、J3 jar 和 J3 外置 pbtxt 必须来自同一冻结版本。
9. 新旧版本不能混跑：
   - 旧 JPA 映射在删列后会产生 SQL 错误；
   - 旧浏览器的 Proto JSON key 与新 engine 不兼容；
   - 旧惠每 pbtxt 与新 J3 parser descriptor 不兼容。
10. 任一硬门槛失败立即停止，不在生产现场临时改 SQL、Proto 或 pbtxt。

## 3. 迁移策略概览

```text
发布前代码/制品冻结
  -> 生产只读盘点与脱敏副本演练
  -> 维护窗口：暂停 HIS/HL7/XML 输入
  -> 排空 icis_j3 队列
  -> 从负载均衡移除并停止全部 engine / icis_j3
  -> 核对连接和长事务
  -> 整库快照 + 三张 legacy 列行级备份
  -> 必要时备份并重置命中旧列 ID 的动态显示设置
  -> 单事务执行三表 DDL
  -> 数据库结构与数据核对
  -> 启动携带新版前端的 engine
  -> 启动携带新版外置 pbtxt 的 icis_j3
  -> 受控诊断冒烟
  -> 恢复上游
  -> 观察期
```

## 4. 角色与上线记录

上线单必须在执行前填写：

| 角色 | 姓名/联系方式 | 职责 |
| --- | --- | --- |
| 发布负责人 | `<待填写>` | Go/No-Go、步骤推进、时间记录 |
| DBA | `<待填写>` | 快照、行级备份、DDL、核对、回滚 |
| `icis_j3` 负责人 | `<待填写>` | 暂停上游、排空、配置、发布、消息回放 |
| engine 负责人 | `<待填写>` | engine 发布、schema validation、API 和报表核对 |
| 前端负责人 | `<待填写>` | production build、static 入包、缓存刷新、页面验证 |
| 业务验收人 | `<待填写>` | 旧中医数据影响确认、通用诊断功能验收 |
| 现场/HIS 接口人 | `<待填写>` | 消息暂停、恢复、补发和接口确认 |

同时记录：

- 医院/环境：`<待填写>`
- 数据库实例/库名/schema：`<待填写>`
- PostgreSQL 版本：`<待填写>`
- 维护窗口：`<开始>` 至 `<结束>`
- 预计业务中断：`<待填写>`
- 回滚最晚决策点：`<待填写>`
- 观察期结束：`<待填写>`
- 数据备份批准位置：`<待填写>`
- 旧中医诊断数据处理审批单：`<待填写>`

## 5. 发布制品清单

### 5.1 必备制品

- `icis_j3`：
  - 新旧 jar
  - 新旧 commit
  - SHA-256
- `jingyi_icis_engine2`：
  - 新旧 jar
  - 新旧 commit
  - SHA-256
- `jingyi_icis_frontend`：
  - 新旧 commit
  - production build 日志
  - `dist` SHA-256 manifest
- 已把新版 `dist` 完整复制到 static 后构建的 engine jar。
- 每家医院实际使用的新版/旧版 J3 外置 `jingyi.xml.config` pbtxt。
- 正向 DDL、反向 DDL及其 SHA-256。
- 三张 legacy 列备份 SQL 和核对 SQL。
- 若生产动态显示设置命中旧列 ID：
  - 设置备份/重置/恢复 SQL
  - 业务批准记录
- 数据库一致性快照或 PITR/WAL 恢复信息。
- 脱敏副本完整正向和反向演练记录。
- 受控测试患者、科室、床位、账号和 HIS 消息。

### 5.2 制品 manifest

至少记录：

```text
icis_j3 commit / artifact sha256
engine commit / artifact sha256
frontend commit / build time / dist manifest sha256
engine jar 内 static 文件 sha256
engine 使用的 icis_config.pb.txt 路径和 sha256
J3 使用的 jingyi.xml.config 路径和 sha256
forward DDL sha256
rollback DDL sha256
backup/verify SQL sha256
```

### 5.3 前端入包核对

固定顺序：

1. 在 `jingyi_icis_frontend` 执行 `npm run build`。
2. 将 `dist/` 全量同步到
   `jingyi_icis_engine2/src/main/resources/static/`。
3. 清理不再由本次 build 产生的旧静态文件。
4. 在 engine 执行 `mvn clean package`。
5. 解包最终 jar，确认 HTML 和 bundle 均来自本次 build。
6. 对最终 jar 计算 SHA-256。

不得手工修改压缩 bundle，也不得把新版 frontend 与旧 engine jar 组合发布。

## 6. Go/No-Go 硬门槛

以下任一项不满足，不得上线：

- 三工程没有基于同一字段矩阵完成修改和评审。
- Proto 删除字段未 reserve 原 field number 和字段名。
- J3/engine 的 `HisPatientPB` 镜像不一致。
- 任一实际使用的惠每外置 pbtxt 仍含 `diagnosis_tcm*` 配置键。
- engine jar 内仍是旧前端 static。
- 浏览器、反向代理或 CDN 缓存没有可执行的刷新方案。
- 三工程构建或测试失败。
- 未在生产同版本 PostgreSQL 脱敏副本完成正向迁移和整版回滚。
- 不能暂停上游，且没有可靠的消息缓存/补发机制。
- 未停止或未盘点旧 `icis_j3`、engine、bridge、ETL、脚本、报表和第三方直连。
- 三张 legacy 行级备份未完成、行数不一致或逐值核对失败。
- 待删列存在非空值，但没有业务负责人书面确认。
- 数据库存在引用待删列的未知视图、物化视图、函数、触发器、策略或自定义索引。
- 生产动态患者显示设置含 `diagnosis_tcm/diagnosis_type`，但没有批准的重置/恢复方案。
- 没有数据库快照/PITR，或没有验证过反向 DDL。
- 部分迁移环境与本文“旧列存在、新列不存在”的前置状态不一致。

## 7. 上线前代码和配置核对

### 7.1 运行时代码旧名称检查

旧名称只能保留在迁移文档、Proto `reserved`、legacy 兼容测试和回滚 SQL 中。必须检查：

```text
diagnosis_tcm_time
diagnosis_tcm_code
diagnosis_tcm
diagnosisTcmTime
diagnosisTcmCode
diagnosisTcm
patient_records.diagnosis_type
PatientRecord.diagnosisType
```

重点确认：

- J3 JPA 实体和写入服务无旧 getter/setter。
- engine 三个 JPA 实体无待删列映射。
- `PatientSyncUtils` 不再创建中医历史。
- `PatientService` 不再读写患者/历史中医字段。
- 报表 handler 不再读取 `PatientRecord.getDiagnosisTcm()`。
- frontend 页面和类型无旧 JSON key。
- engine static 中无旧中医诊断表单和 API key。

### 7.2 J3 pbtxt

检查所有来源：

- jar 内置惠每配置
- 生产启动参数 `jingyi.xml.config`
- 容器挂载文件
- 配置中心导出文件
- 备用节点和灾备节点

新版文件不得包含：

```text
diagnosis_tcm_time_iso8601_xml_path
diagnosis_tcm_time_iso8601_parser_name
diagnosis_tcm_code_xml_path
diagnosis_tcm_code_parser_name
diagnosis_tcm_xml_path
diagnosis_tcm_parser_name
```

不要只检查 Git 内文件。`TextFormat` 按字段名解析，旧键会导致新版 J3 启动失败。

### 7.3 Proto descriptor

构建测试必须确认：

| message | 删除编号 | 删除名称 |
| --- | --- | --- |
| `HisPatientPB` | `47–49` | `diagnosis_tcm_time_iso8601`、`diagnosis_tcm_code`、`diagnosis_tcm` |
| `HisPatientParserPB` | `93–98` | 六个 `diagnosis_tcm*` parser 配置名 |
| `PatientInfoPB` | `57–58` | `diagnosis_tcm`、`diagnosis_type` |
| `DiagnosisHistoryPB` | `6–7` | `diagnosis_tcm`、`diagnosis_tcm_code` |

这些编号必须保留为 unknown/reserved，不能重新分配。

## 8. 生产数据库只读盘点

以下查询默认 schema 为 `public`。实际 schema 不同时，先把脚本中的 `public` 全部显式替换，
不要依赖 `search_path`。

### 8.1 表和列状态

```sql
SELECT
    table_schema,
    table_name,
    ordinal_position,
    column_name,
    data_type,
    character_maximum_length,
    is_nullable,
    column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN (
      'his_patient_records',
      'patient_records',
      'diagnosis_history'
  )
  AND column_name IN (
      'patient_id',
      'diagnosis_tcm_time',
      'diagnosis_tcm_code',
      'diagnosis_tcm',
      'diagnosis_type',
      'patient_key_type',
      'his_encounter_id',
      'his_mrn',
      'his_admission_count',
      'his_patient_id',
      'dept_admission_time',
      'diagnosis_category'
  )
ORDER BY table_name, ordinal_position;
```

迁移前必须满足：

- `his_patient_records` 三个旧中医列存在。
- `patient_records.diagnosis_tcm` 和字符串 `diagnosis_type` 存在。
- `diagnosis_history` 两个旧中医列存在。
- `diagnosis_history.patient_id` 为 `NOT NULL`。
- `diagnosis_history` 八个目标占位列均不存在。

注意 `his_encounter_id/his_mrn/...` 在另外两张表中本来就存在，判断冲突时必须带
`table_name = 'diagnosis_history'`。

### 8.2 自动列前置检查

以下两个查询预期均返回零行：

```sql
WITH expected_old(table_name, column_name) AS (
    VALUES
        ('his_patient_records', 'diagnosis_tcm_time'),
        ('his_patient_records', 'diagnosis_tcm_code'),
        ('his_patient_records', 'diagnosis_tcm'),
        ('patient_records', 'diagnosis_tcm'),
        ('patient_records', 'diagnosis_type'),
        ('diagnosis_history', 'diagnosis_tcm'),
        ('diagnosis_history', 'diagnosis_tcm_code')
)
SELECT e.*
FROM expected_old e
LEFT JOIN information_schema.columns c
  ON c.table_schema = 'public'
 AND c.table_name = e.table_name
 AND c.column_name = e.column_name
WHERE c.column_name IS NULL;

WITH conflicting_new(column_name) AS (
    VALUES
        ('patient_key_type'),
        ('his_encounter_id'),
        ('his_mrn'),
        ('his_admission_count'),
        ('his_patient_id'),
        ('dept_admission_time'),
        ('diagnosis_type'),
        ('diagnosis_category')
)
SELECT c.column_name
FROM information_schema.columns c
JOIN conflicting_new n ON n.column_name = c.column_name
WHERE c.table_schema = 'public'
  AND c.table_name = 'diagnosis_history';
```

再确认：

```sql
SELECT is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name = 'diagnosis_history'
  AND column_name = 'patient_id';
```

预期为 `NO`。任何部分迁移状态都应停止并单独制定修复脚本。

### 8.3 待删数据基线

空字符串也属于需要盘点的数据：

```sql
SELECT
    COUNT(*) AS total_rows,
    COUNT(diagnosis_tcm_time) AS tcm_time_non_null,
    COUNT(diagnosis_tcm_code) AS tcm_code_non_null,
    COUNT(*) FILTER (
        WHERE NULLIF(BTRIM(diagnosis_tcm_code), '') IS NOT NULL
    ) AS tcm_code_non_blank,
    COUNT(diagnosis_tcm) AS tcm_non_null,
    COUNT(*) FILTER (
        WHERE NULLIF(BTRIM(diagnosis_tcm), '') IS NOT NULL
    ) AS tcm_non_blank
FROM public.his_patient_records;

SELECT
    COUNT(*) AS total_rows,
    COUNT(diagnosis_tcm) AS tcm_non_null,
    COUNT(*) FILTER (
        WHERE NULLIF(BTRIM(diagnosis_tcm), '') IS NOT NULL
    ) AS tcm_non_blank,
    COUNT(diagnosis_type) AS old_type_non_null,
    COUNT(*) FILTER (
        WHERE NULLIF(BTRIM(diagnosis_type), '') IS NOT NULL
    ) AS old_type_non_blank
FROM public.patient_records;

SELECT
    COUNT(*) AS total_rows,
    COUNT(diagnosis_tcm) AS tcm_non_null,
    COUNT(*) FILTER (
        WHERE NULLIF(BTRIM(diagnosis_tcm), '') IS NOT NULL
    ) AS tcm_non_blank,
    COUNT(diagnosis_tcm_code) AS tcm_code_non_null,
    COUNT(*) FILTER (
        WHERE NULLIF(BTRIM(diagnosis_tcm_code), '') IS NOT NULL
    ) AS tcm_code_non_blank,
    COUNT(*) FILTER (
        WHERE NULLIF(BTRIM(diagnosis), '') IS NOT NULL
          AND (
              NULLIF(BTRIM(diagnosis_tcm), '') IS NOT NULL
              OR NULLIF(BTRIM(diagnosis_tcm_code), '') IS NOT NULL
          )
    ) AS generic_and_tcm_rows,
    COUNT(*) FILTER (
        WHERE NULLIF(BTRIM(diagnosis), '') IS NULL
          AND NULLIF(BTRIM(diagnosis_code), '') IS NULL
          AND (
              NULLIF(BTRIM(diagnosis_tcm), '') IS NOT NULL
              OR NULLIF(BTRIM(diagnosis_tcm_code), '') IS NOT NULL
          )
    ) AS tcm_only_rows
FROM public.diagnosis_history;
```

保存旧 `patient_records.diagnosis_type` 值分布：

```sql
SELECT diagnosis_type, COUNT(*)
FROM public.patient_records
GROUP BY diagnosis_type
ORDER BY diagnosis_type NULLS FIRST;
```

结果可能包含医疗数据，只能保存在批准位置。业务确认必须明确：

- 新版不再显示/采集这些中医值。
- 本次不做分类转换。
- `tcm_only_rows` 将成为不对页面返回的遗留审计壳。
- 旧字符串 `diagnosis_type` 不复制到新整数列。

### 8.4 行数和约束基线

```sql
SELECT 'his_patient_records' AS table_name, COUNT(*) AS row_count
FROM public.his_patient_records
UNION ALL
SELECT 'patient_records', COUNT(*)
FROM public.patient_records
UNION ALL
SELECT 'diagnosis_history', COUNT(*)
FROM public.diagnosis_history;

SELECT COUNT(*) AS null_patient_id_count
FROM public.diagnosis_history
WHERE patient_id IS NULL;
```

迁移前 `null_patient_id_count` 必须为 `0`。

### 8.5 索引和约束

```sql
SELECT schemaname, tablename, indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename IN (
      'his_patient_records',
      'patient_records',
      'diagnosis_history'
  )
  AND indexdef ~* '(diagnosis_tcm|diagnosis_type|patient_id)'
ORDER BY tablename, indexname;

SELECT
    n.nspname AS schema_name,
    c.relname AS table_name,
    con.conname,
    pg_get_constraintdef(con.oid) AS definition
FROM pg_constraint con
JOIN pg_class c ON c.oid = con.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE n.nspname = 'public'
  AND c.relname IN (
      'his_patient_records',
      'patient_records',
      'diagnosis_history'
  )
  AND pg_get_constraintdef(con.oid) ~* '(diagnosis_tcm|diagnosis_type|patient_id)';
```

预期只保留 `idx_diagnosis_history_patient_id` 等已知 `patient_id` 索引，不存在待删列索引或
约束。禁止用 `CASCADE` 绕过未知结果。

### 8.6 视图、函数、触发器和物化视图

```sql
SELECT schemaname, viewname
FROM pg_views
WHERE definition ~* '(diagnosis_tcm|diagnosis_type)';

SELECT schemaname, matviewname
FROM pg_matviews
WHERE definition ~* '(diagnosis_tcm|diagnosis_type)';

SELECT
    n.nspname AS schema_name,
    p.proname AS routine_name,
    p.prokind
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE p.prokind IN ('f', 'p')
  AND pg_get_functiondef(p.oid) ~* '(diagnosis_tcm|diagnosis_type)';

SELECT
    event_object_schema,
    event_object_table,
    trigger_name,
    action_statement
FROM information_schema.triggers
WHERE action_statement ~* '(diagnosis_tcm|diagnosis_type)';
```

还须人工盘点：

- BI/ETL
- 自定义 SQL 报表
- 第三方数据交换
- 运维脚本
- 审计导出
- 只读副本任务
- 旧 bridge/J3/engine 实例

仓库零命中不能证明线上不存在外部消费者。

## 9. 存量配置和模板专项盘点

### 9.1 患者动态显示设置

仓库四份标准配置只包含通用 `diagnosis`，不含待删列。生产
`dept_system_settings.settings_pb` 可能保留自定义列 ID，可做字节扫描：

```sql
WITH old_names(name) AS (
    VALUES
        ('diagnosis_tcm'),
        ('diagnosis_type')
)
SELECT
    d.dept_id,
    d.function_id,
    STRING_AGG(o.name, ',' ORDER BY o.name) AS matched_old_names
FROM public.dept_system_settings d
JOIN old_names o
  ON POSITION(convert_to(o.name, 'UTF8') IN decode(d.settings_pb, 'base64')) > 0
WHERE d.function_id IN (1, 2, 3, 4)
GROUP BY d.dept_id, d.function_id
ORDER BY d.dept_id, d.function_id;
```

预期零行。如果命中：

1. 解码确认确实是患者列 ID，不是无关文本。
2. 记录受影响科室和功能。
3. 备份命中行。
4. 取得“重置为新版默认显示设置、丢弃该行自定义顺序”的业务批准。
5. 维护窗口内删除命中行，由新版 engine 按标准配置重建。

没有批准时必须 No-Go，不能保留会触发 `Unexpected patient_records' column name` 的旧设置。

### 9.2 命中设置备份

仅在第 9.1 节有命中时执行，且表名已存在必须停止：

```sql
CREATE TABLE public.migration_backup_20260724_diagnosis_display_settings AS
SELECT d.*
FROM public.dept_system_settings d
WHERE d.function_id IN (1, 2, 3, 4)
  AND (
      POSITION(convert_to('diagnosis_tcm', 'UTF8') IN decode(d.settings_pb, 'base64')) > 0
      OR
      POSITION(convert_to('diagnosis_type', 'UTF8') IN decode(d.settings_pb, 'base64')) > 0
  );

ALTER TABLE public.migration_backup_20260724_diagnosis_display_settings
    ADD PRIMARY KEY (dept_id, function_id);
```

核对备份行数和 Base64 值。观察期结束前不得删除。

### 9.3 JFK/拖拽模板

内置 JFK 模板使用稳定数据源 ID，`diagnosis_and_surgery` 的实现变化不要求改模板。生产自定义
模板仍需扫描：

```sql
WITH old_names(name) AS (
    VALUES
        ('diagnosis_tcm'),
        ('diagnosis_type')
)
SELECT
    t.id,
    STRING_AGG(o.name, ',' ORDER BY o.name) AS matched_old_names
FROM public.dragable_form_templates t
JOIN old_names o
  ON POSITION(convert_to(o.name, 'UTF8') IN decode(t.template_pb, 'base64')) > 0
WHERE COALESCE(t.is_deleted, false) = false
GROUP BY t.id
ORDER BY t.id;
```

快速扫描零命中不能替代完整解码和自定义数据源代码盘点。任何直连旧数据库列的模板或 handler
必须先修改并在预发布打印验证。

## 10. 备份

### 10.1 必备恢复能力

维护窗口停写后至少完成：

1. 数据库一致性快照或可恢复到停写时点的 PITR/WAL。
2. 三张目标表 schema 和数据备份。
3. 三张 legacy 列行级备份表。
4. 若第 9.1 节命中，动态显示设置行级备份。
5. 新旧 jar、前端 static、pbtxt 和配置 manifest。

行级备份用于快速整版回滚；整库快照用于处理迁移后发生的删行、并发写入或其他无法仅靠列
备份恢复的情况。

### 10.2 legacy 列行级备份

以下 SQL 必须在所有写入者停止后执行。同名表存在时 CTAS 会失败，不能覆盖。

```sql
BEGIN;

CREATE TABLE public.migration_backup_20260724_his_diagnosis_legacy AS
SELECT
    id,
    diagnosis_tcm_time,
    diagnosis_tcm_code,
    diagnosis_tcm
FROM public.his_patient_records;

ALTER TABLE public.migration_backup_20260724_his_diagnosis_legacy
    ADD PRIMARY KEY (id);

CREATE TABLE public.migration_backup_20260724_patient_diagnosis_legacy AS
SELECT
    id,
    diagnosis_tcm,
    diagnosis_type
FROM public.patient_records;

ALTER TABLE public.migration_backup_20260724_patient_diagnosis_legacy
    ADD PRIMARY KEY (id);

CREATE TABLE public.migration_backup_20260724_history_diagnosis_legacy AS
SELECT
    id,
    diagnosis_tcm,
    diagnosis_tcm_code
FROM public.diagnosis_history;

ALTER TABLE public.migration_backup_20260724_history_diagnosis_legacy
    ADD PRIMARY KEY (id);

COMMIT;
```

备份表含医疗数据，访问控制、加密、导出和销毁必须遵守生产数据规范。

### 10.3 行数核对

```sql
SELECT
    (SELECT COUNT(*) FROM public.his_patient_records) AS source_rows,
    (SELECT COUNT(*) FROM public.migration_backup_20260724_his_diagnosis_legacy) AS backup_rows;

SELECT
    (SELECT COUNT(*) FROM public.patient_records) AS source_rows,
    (SELECT COUNT(*) FROM public.migration_backup_20260724_patient_diagnosis_legacy) AS backup_rows;

SELECT
    (SELECT COUNT(*) FROM public.diagnosis_history) AS source_rows,
    (SELECT COUNT(*) FROM public.migration_backup_20260724_history_diagnosis_legacy) AS backup_rows;
```

每组必须完全相等。

### 10.4 逐值核对

每个查询预期返回零：

```sql
SELECT COUNT(*) AS diff_rows
FROM (
    SELECT id, diagnosis_tcm_time, diagnosis_tcm_code, diagnosis_tcm
    FROM public.his_patient_records
    EXCEPT
    SELECT id, diagnosis_tcm_time, diagnosis_tcm_code, diagnosis_tcm
    FROM public.migration_backup_20260724_his_diagnosis_legacy
) d;

SELECT COUNT(*) AS diff_rows
FROM (
    SELECT id, diagnosis_tcm, diagnosis_type
    FROM public.patient_records
    EXCEPT
    SELECT id, diagnosis_tcm, diagnosis_type
    FROM public.migration_backup_20260724_patient_diagnosis_legacy
) d;

SELECT COUNT(*) AS diff_rows
FROM (
    SELECT id, diagnosis_tcm, diagnosis_tcm_code
    FROM public.diagnosis_history
    EXCEPT
    SELECT id, diagnosis_tcm, diagnosis_tcm_code
    FROM public.migration_backup_20260724_history_diagnosis_legacy
) d;
```

再反向交换 source/backup 各执行一次，防止只检查一个方向漏掉额外行。

## 11. 预发布演练

在生产脱敏副本完整执行：

1. 恢复与生产相同 PostgreSQL 版本、schema、代表性数据和配置。
2. 放入生产实际外置 J3 pbtxt、动态显示设置和自定义模板副本。
3. 执行第 8、9 节盘点并保存结果。
4. 停旧服务，执行第 10 节备份。
5. 执行第 13 节正向 DDL。
6. 启动新版 engine，确认 Hibernate schema validation 通过。
7. 启动新版 J3，确认实际外置 pbtxt 解析通过。
8. 执行第 15 节所有冒烟场景。
9. 在迁移后的系统运行代表性读写一段时间。
10. 执行第 18 节完整回滚。
11. 启动旧版 J3/engine/frontend，再次执行通用诊断读写。
12. 对恢复后的 legacy 列与备份逐值比对。

只验证 DDL 语法、只启动单个服务或只验证正向迁移均不算演练完成。

## 12. 维护窗口：停写与连接核对

### 12.1 停写顺序

1. 通知用户进入维护窗口，阻止患者资料和诊断历史编辑。
2. 暂停 HIS/HL7/XML 上游发送。
3. 记录 J3 队列长度和最后消息 ID/时间。
4. 等待队列排空；不能排空时记录持久化位置和补发策略。
5. 从负载均衡移除所有 engine 实例。
6. 停止全部 engine 实例和定时患者同步。
7. 停止全部 J3 实例。
8. 确认备用节点、灾备节点和旧守护进程没有自动拉起。

### 12.2 数据库会话

```sql
SELECT
    pid,
    usename,
    application_name,
    client_addr,
    state,
    xact_start,
    query_start,
    LEFT(query, 300) AS query
FROM pg_stat_activity
WHERE datname = current_database()
  AND pid <> pg_backend_pid()
  AND (
      xact_start IS NOT NULL
      OR query ~* '(his_patient_records|patient_records|diagnosis_history)'
  )
ORDER BY xact_start NULLS LAST, query_start;
```

出现未知长事务、活动写入或未停止应用连接时暂停。终止连接必须由 DBA 按现场流程人工决定，
迁移脚本不得无条件杀会话。

### 12.3 最终备份

确认停写后：

1. 记录第 8.3、8.4 节最终基线。
2. 创建一致性快照/PITR 恢复点。
3. 执行第 10.2–10.4 节。
4. 若动态设置命中，执行第 9.2 节。
5. 由 DBA 和发布负责人共同签字确认备份可用。

## 13. 正向 DDL

### 13.1 执行方式

用受控 SQL 文件执行，客户端启用遇错停止。示例 psql 文件首行：

```sql
\set ON_ERROR_STOP on
```

不要复制 `schema.postgresql.sql` 到生产执行。

### 13.2 事务脚本

```sql
\set ON_ERROR_STOP on

BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

LOCK TABLE
    public.his_patient_records,
    public.patient_records,
    public.diagnosis_history
IN ACCESS EXCLUSIVE MODE;

ALTER TABLE public.his_patient_records
    DROP COLUMN diagnosis_tcm_time,
    DROP COLUMN diagnosis_tcm_code,
    DROP COLUMN diagnosis_tcm;

ALTER TABLE public.patient_records
    DROP COLUMN diagnosis_tcm,
    DROP COLUMN diagnosis_type;

ALTER TABLE public.diagnosis_history
    ALTER COLUMN patient_id DROP NOT NULL,
    DROP COLUMN diagnosis_tcm,
    DROP COLUMN diagnosis_tcm_code,
    ADD COLUMN patient_key_type INTEGER,
    ADD COLUMN his_encounter_id VARCHAR(255),
    ADD COLUMN his_mrn VARCHAR(255),
    ADD COLUMN his_admission_count INTEGER,
    ADD COLUMN his_patient_id VARCHAR(255),
    ADD COLUMN dept_admission_time TIMESTAMP,
    ADD COLUMN diagnosis_type INTEGER,
    ADD COLUMN diagnosis_category INTEGER;

COMMENT ON COLUMN public.diagnosis_history.patient_id IS
    '病人ID；允许为空，未来可使用其他患者标识';
COMMENT ON COLUMN public.diagnosis_history.patient_key_type IS
    '患者标识类型；枚举值待后续定义';
COMMENT ON COLUMN public.diagnosis_history.his_encounter_id IS
    'HIS就诊ID';
COMMENT ON COLUMN public.diagnosis_history.his_mrn IS
    'HIS病历号，Medical Record Number';
COMMENT ON COLUMN public.diagnosis_history.his_admission_count IS
    'HIS住院次数';
COMMENT ON COLUMN public.diagnosis_history.his_patient_id IS
    'HIS病人记录ID';
COMMENT ON COLUMN public.diagnosis_history.dept_admission_time IS
    '科室入科时间';
COMMENT ON COLUMN public.diagnosis_history.diagnosis_type IS
    '诊断类型：1-入院诊断，2-科室诊断';
COMMENT ON COLUMN public.diagnosis_history.diagnosis_category IS
    '诊断类别：1-中医，2-西医';

COMMIT;
```

说明：

- 不使用 `IF EXISTS/IF NOT EXISTS`，部分迁移状态应显式失败。
- 不使用 `CASCADE`。
- nullable 且无默认值的新增列通常是元数据操作；实际锁等待仍受并发和依赖影响。
- 生产新增列物理位置会在表尾，不为列序重建表。

### 13.3 动态显示设置重置（仅命中时）

保持 engine 停止。在第 9.2 节备份和审批完成后：

```sql
BEGIN;

DELETE FROM public.dept_system_settings d
USING public.migration_backup_20260724_diagnosis_display_settings b
WHERE d.dept_id = b.dept_id
  AND d.function_id = b.function_id;

COMMIT;
```

新版 engine 启动后会按新版默认配置初始化缺失行。未命中时不执行本节。

## 14. DDL 后数据库核对

### 14.1 字段状态

```sql
SELECT
    table_name,
    ordinal_position,
    column_name,
    data_type,
    character_maximum_length,
    is_nullable,
    column_default
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN (
      'his_patient_records',
      'patient_records',
      'diagnosis_history'
  )
  AND (
      column_name ~ '^diagnosis_tcm'
      OR column_name IN (
          'patient_id',
          'patient_key_type',
          'his_encounter_id',
          'his_mrn',
          'his_admission_count',
          'his_patient_id',
          'dept_admission_time',
          'diagnosis_type',
          'diagnosis_category'
      )
  )
ORDER BY table_name, ordinal_position;
```

确认：

- 三张表没有任何 `diagnosis_tcm*` 列。
- `patient_records` 没有 `diagnosis_type`。
- `diagnosis_history.patient_id` 为 nullable。
- 八个新列存在、类型正确、nullable、无默认值。

### 14.2 行数与值

重复第 8.4 节行数查询，三张表行数应与停写基线一致。

```sql
SELECT
    COUNT(*) FILTER (WHERE patient_key_type IS NOT NULL) AS patient_key_type_non_null,
    COUNT(*) FILTER (WHERE his_encounter_id IS NOT NULL) AS his_encounter_id_non_null,
    COUNT(*) FILTER (WHERE his_mrn IS NOT NULL) AS his_mrn_non_null,
    COUNT(*) FILTER (WHERE his_admission_count IS NOT NULL) AS his_admission_count_non_null,
    COUNT(*) FILTER (WHERE his_patient_id IS NOT NULL) AS his_patient_id_non_null,
    COUNT(*) FILTER (WHERE dept_admission_time IS NOT NULL) AS dept_admission_time_non_null,
    COUNT(*) FILTER (WHERE diagnosis_type IS NOT NULL) AS diagnosis_type_non_null,
    COUNT(*) FILTER (WHERE diagnosis_category IS NOT NULL) AS diagnosis_category_non_null,
    COUNT(*) FILTER (WHERE patient_id IS NULL) AS patient_id_null
FROM public.diagnosis_history;
```

刚完成 DDL 时九个结果都应为 `0`。

### 14.3 索引

```sql
SELECT indexname, indexdef
FROM pg_indexes
WHERE schemaname = 'public'
  AND tablename = 'diagnosis_history'
ORDER BY indexname;
```

确认 `idx_diagnosis_history_patient_id` 仍存在且有效。

### 14.4 备份完整

备份表行数和第 10.4 节核对结果仍须保留在上线记录中。DDL 后不要删除备份表。

## 15. 发布和冒烟

### 15.1 启动新版 engine

1. 只启动一个新版实例，暂不加入负载均衡。
2. 确认使用正确数据库、医院 pbtxt 和新版 static。
3. 检查日志：
   - Hibernate schema validation 成功；
   - 没有 unknown column；
   - 没有 Proto JSON descriptor 错误；
   - 没有动态患者列 ID 异常。
4. 检查健康接口。
5. 若第 13.3 节重置设置，确认缺失设置已经按默认值重建。

### 15.2 engine API 冒烟

使用受控患者执行：

1. 获取患者详情 V2：
   - 有 `diagnosis`
   - JSON 无 `diagnosisTcm/diagnosisType`
2. 保存患者详情：
   - 返回成功
   - 不产生已删列 SQL
3. 获取诊断历史：
   - 只返回通用 `diagnosis/diagnosisCode`
   - 不显示遗留中医专用空壳
4. 新增通用诊断。
5. 编辑该诊断的时间、编码和内容。
6. 逻辑删除该诊断。
7. 为同一病人在同一时间连续新增两条通用诊断，确认两条都成功并按 `id` 倒序展示，且可分别编辑、删除。
8. Ward Report 插入诊断历史，确认同一时间的两条记录可分别选中且内容正确。
9. 生成使用 `patient_info_extended:diagnosis_and_surgery` 的护理文书：
   - 通用诊断存在
   - 手术拼接正确
   - 无中医诊断残留分隔符
10. 执行脓毒症/感染性休克诊断相关查询，确认通用诊断判断不回归。

### 15.3 前端冒烟

- 强制刷新并确认加载新版 bundle。
- 患者详情只显示一个通用诊断入口。
- 诊断历史卡片和表单无中医专用字段。
- 新增、编辑、删除和刷新正常。
- 浏览器网络请求中无：
  - `diagnosisTcm`
  - `diagnosisTcmCode`
  - `diagnosisType`
- 浏览器控制台无 React/TypeScript 运行时错误。

### 15.4 启动新版 `icis_j3`

1. 确认 jar 和实际外置 pbtxt SHA-256 与 manifest 一致。
2. 启动单个 J3 实例。
3. 确认：
   - TextFormat 配置加载成功；
   - JPA 初始化成功；
   - 无旧 setter/column 错误；
   - 健康接口正常。

### 15.5 J3 受控消息

依次回放：

| 来源/场景 | 预期 |
| --- | --- |
| 惠每入院，含通用 `diags` | 通用诊断进入 `his_patient_records.diagnosis*` |
| 惠每入院，同时含 `tcm_diags` | 消息成功；中医节点被忽略；无旧列 SQL |
| 惠每出院，含通用诊断 | 通用诊断和出院状态正常 |
| 惠每转科 | 患者标识、来源/当前科室不回归 |
| 卫宁 DG1 | 通用诊断编码和内容正常 |
| 嘉禾美康 ADT | 通用诊断正常，取消入科不访问旧 setter |

每条消息记录原始消息 ID、J3 日志、目标 `his_patient_records.id` 和 engine 同步结果。

### 15.6 加入流量和多实例

单实例全部通过后：

1. 启动其余新版 engine/J3 实例。
2. 逐个确认版本、配置 SHA-256 和健康状态。
3. engine 加回负载均衡。
4. 再次通过不同实例请求患者详情和诊断历史。
5. 清理反向代理/CDN/浏览器旧缓存。

## 16. 恢复上游与观察

### 16.1 恢复前最终门槛

- 数据库核对全部通过。
- engine/J3/前端冒烟全部通过。
- 没有旧实例在线。
- 所有实际 pbtxt 为新版。
- 动态显示设置无旧列 ID。
- 备份和回滚制品可立即使用。

### 16.2 恢复顺序

1. 通知现场恢复 HIS/HL7/XML。
2. 低速恢复或先发送少量可追踪消息。
3. 核对 J3 队列和写库。
4. 核对 engine 患者同步。
5. 恢复正常流量。
6. 对维护窗口期间缓存消息按既定顺序补发。

### 16.3 观察指标

观察期至少监控：

- J3 消息成功/失败、队列长度、重试和 dead-letter。
- `his_patient_records` INSERT/UPDATE 错误。
- engine schema、SQL grammar、unknown column 错误。
- Proto JSON parse failure。
- J3 pbtxt unknown field/启动失败。
- 患者同步异常。
- 诊断历史接口错误率。
- 患者详情保存错误率。
- 动态患者列未知 ID。
- 护理文书生成失败。
- 新占位字段出现非 NULL 值。
- `diagnosis_history.patient_id IS NULL` 数量变化。

建议定时只读检查：

```sql
SELECT
    COUNT(*) FILTER (WHERE patient_id IS NULL) AS patient_id_null,
    COUNT(*) FILTER (
        WHERE patient_key_type IS NOT NULL
           OR his_encounter_id IS NOT NULL
           OR his_mrn IS NOT NULL
           OR his_admission_count IS NOT NULL
           OR his_patient_id IS NOT NULL
           OR dept_admission_time IS NOT NULL
           OR diagnosis_type IS NOT NULL
           OR diagnosis_category IS NOT NULL
    ) AS placeholder_used
FROM public.diagnosis_history;
```

本版本预期两项持续为 `0`。非零表示范围外写入，不要自动清理。

## 17. 回滚触发条件

出现以下任一情况，进入整版回滚评估：

- 新版 J3 无法加载实际外置 pbtxt。
- 持续出现目标三表 unknown column/SQL grammar 错误。
- 诊断历史 CRUD 或患者详情无法完成。
- 通用 HIS 诊断停止同步。
- 旧前端缓存无法清理，持续发送旧 JSON key。
- 动态显示设置导致患者列表不可用且无法在窗口内安全重置。
- 护理文书关键模板无法生成。
- 三工程版本或配置出现混用。
- 发现未盘点的外部消费者依赖待删列。
- 备份验证异常。

不要通过临时给新代码重新加旧列、临时修改 bundle 或允许新旧实例混跑来规避回滚。

## 18. 整版回滚

### 18.1 回滚前停止

1. 再次进入维护模式。
2. 暂停上游并排空队列。
3. 从负载均衡移除并停止全部新版 engine/J3。
4. 记录迁移后新增/修改的业务数据。
5. 检查数据库会话和长事务。

### 18.2 新占位字段和 nullable 数据门槛

旧版本要求 `diagnosis_history.patient_id NOT NULL`，且回滚会删除八个新字段。先执行：

```sql
SELECT COUNT(*) AS null_patient_id_count
FROM public.diagnosis_history
WHERE patient_id IS NULL;

SELECT
    COUNT(*) FILTER (WHERE patient_key_type IS NOT NULL) AS patient_key_type_non_null,
    COUNT(*) FILTER (WHERE his_encounter_id IS NOT NULL) AS his_encounter_id_non_null,
    COUNT(*) FILTER (WHERE his_mrn IS NOT NULL) AS his_mrn_non_null,
    COUNT(*) FILTER (WHERE his_admission_count IS NOT NULL) AS his_admission_count_non_null,
    COUNT(*) FILTER (WHERE his_patient_id IS NOT NULL) AS his_patient_id_non_null,
    COUNT(*) FILTER (WHERE dept_admission_time IS NOT NULL) AS dept_admission_time_non_null,
    COUNT(*) FILTER (WHERE diagnosis_type IS NOT NULL) AS diagnosis_type_non_null,
    COUNT(*) FILTER (WHERE diagnosis_category IS NOT NULL) AS diagnosis_category_non_null
FROM public.diagnosis_history;
```

预期全部为 `0`。若任一非零：

- 不得直接执行反向 DDL；
- 先导出并确认范围外数据的归属；
- `patient_id IS NULL` 无法恢复旧 `NOT NULL` 时，选择业务映射、删除审批或整库/PITR 恢复；
- 新占位字段已有业务数据时，必须先由未来功能负责人确认如何保存。

即使全部为零，也建议先创建迁移后状态的数据库快照。

### 18.3 反向 DDL 和数据恢复

```sql
\set ON_ERROR_STOP on

BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '5min';

LOCK TABLE
    public.his_patient_records,
    public.patient_records,
    public.diagnosis_history
IN ACCESS EXCLUSIVE MODE;

ALTER TABLE public.his_patient_records
    ADD COLUMN diagnosis_tcm_time TIMESTAMP,
    ADD COLUMN diagnosis_tcm_code VARCHAR(1000),
    ADD COLUMN diagnosis_tcm TEXT;

ALTER TABLE public.patient_records
    ADD COLUMN diagnosis_tcm TEXT,
    ADD COLUMN diagnosis_type VARCHAR(100);

ALTER TABLE public.diagnosis_history
    ADD COLUMN diagnosis_tcm TEXT,
    ADD COLUMN diagnosis_tcm_code VARCHAR(1000);

UPDATE public.his_patient_records h
SET
    diagnosis_tcm_time = b.diagnosis_tcm_time,
    diagnosis_tcm_code = b.diagnosis_tcm_code,
    diagnosis_tcm = b.diagnosis_tcm
FROM public.migration_backup_20260724_his_diagnosis_legacy b
WHERE h.id = b.id;

UPDATE public.patient_records p
SET
    diagnosis_tcm = b.diagnosis_tcm,
    diagnosis_type = b.diagnosis_type
FROM public.migration_backup_20260724_patient_diagnosis_legacy b
WHERE p.id = b.id;

UPDATE public.diagnosis_history h
SET
    diagnosis_tcm = b.diagnosis_tcm,
    diagnosis_tcm_code = b.diagnosis_tcm_code
FROM public.migration_backup_20260724_history_diagnosis_legacy b
WHERE h.id = b.id;

ALTER TABLE public.diagnosis_history
    DROP COLUMN patient_key_type,
    DROP COLUMN his_encounter_id,
    DROP COLUMN his_mrn,
    DROP COLUMN his_admission_count,
    DROP COLUMN his_patient_id,
    DROP COLUMN dept_admission_time,
    DROP COLUMN diagnosis_type,
    DROP COLUMN diagnosis_category,
    ALTER COLUMN patient_id SET NOT NULL;

COMMENT ON COLUMN public.his_patient_records.diagnosis_tcm_time IS
    '中医诊断时间';
COMMENT ON COLUMN public.his_patient_records.diagnosis_tcm_code IS
    '中医临床诊断编码';
COMMENT ON COLUMN public.his_patient_records.diagnosis_tcm IS
    '中医临床诊断';
COMMENT ON COLUMN public.patient_records.diagnosis_tcm IS
    '中医诊断, Traditional Chinese Medicine';
COMMENT ON COLUMN public.patient_records.diagnosis_type IS
    '诊断类型，较少使用，reserved';
COMMENT ON COLUMN public.diagnosis_history.patient_id IS
    '病人ID';
COMMENT ON COLUMN public.diagnosis_history.diagnosis_tcm IS
    '中医诊断';
COMMENT ON COLUMN public.diagnosis_history.diagnosis_tcm_code IS
    '中医诊断编码';

COMMIT;
```

说明：

- 迁移后新插入的行在恢复的 legacy 列中为 `NULL`，旧 schema 允许这些列为空。
- 如果迁移后发生物理删行，行级列备份不能重建整行，必须使用整库快照/PITR。
- 恢复列的物理顺序与迁移前可能不同，不影响按列名工作的 JPA；不要为列序重建表。

### 18.4 恢复动态显示设置（仅执行过重置时）

```sql
BEGIN;

DELETE FROM public.dept_system_settings d
USING public.migration_backup_20260724_diagnosis_display_settings b
WHERE d.dept_id = b.dept_id
  AND d.function_id = b.function_id;

INSERT INTO public.dept_system_settings (
    dept_id,
    function_id,
    settings_pb,
    modified_at,
    modified_by
)
SELECT
    dept_id,
    function_id,
    settings_pb,
    modified_at,
    modified_by
FROM public.migration_backup_20260724_diagnosis_display_settings;

COMMIT;
```

### 18.5 回滚后核对

1. 第 8.1 节旧列全部存在。
2. 八个占位列全部不存在。
3. `diagnosis_history.patient_id` 为 `NOT NULL`。
4. 三个旧索引/表行数符合回滚基线。
5. 对上线前已存在的主键逐值验证 legacy 列。
6. 对迁移后新增行确认 legacy 列为 `NULL`。

迁移后可能有新行，因此不能直接要求源表与上线前备份做全表双向 `EXCEPT`。应以备份主键为
范围检查，以下查询预期均为 `0`：

```sql
SELECT COUNT(*) AS missing_or_different
FROM public.migration_backup_20260724_his_diagnosis_legacy b
LEFT JOIN public.his_patient_records h ON h.id = b.id
WHERE h.id IS NULL
   OR h.diagnosis_tcm_time IS DISTINCT FROM b.diagnosis_tcm_time
   OR h.diagnosis_tcm_code IS DISTINCT FROM b.diagnosis_tcm_code
   OR h.diagnosis_tcm IS DISTINCT FROM b.diagnosis_tcm;

SELECT COUNT(*) AS missing_or_different
FROM public.migration_backup_20260724_patient_diagnosis_legacy b
LEFT JOIN public.patient_records p ON p.id = b.id
WHERE p.id IS NULL
   OR p.diagnosis_tcm IS DISTINCT FROM b.diagnosis_tcm
   OR p.diagnosis_type IS DISTINCT FROM b.diagnosis_type;

SELECT COUNT(*) AS missing_or_different
FROM public.migration_backup_20260724_history_diagnosis_legacy b
LEFT JOIN public.diagnosis_history h ON h.id = b.id
WHERE h.id IS NULL
   OR h.diagnosis_tcm IS DISTINCT FROM b.diagnosis_tcm
   OR h.diagnosis_tcm_code IS DISTINCT FROM b.diagnosis_tcm_code;
```

若出现 `h.id/p.id IS NULL`，说明上线前存在的整行已在迁移后被物理删除，列级备份无法恢复，
必须按快照/PITR 方案处理。

### 18.6 恢复旧制品

必须成套恢复：

1. 旧 engine jar 和旧内置 static。
2. 旧 J3 jar。
3. 与旧 J3 descriptor 对应的旧外置 pbtxt。
4. 旧反向代理/CDN/浏览器缓存版本。
5. 所有旧实例健康后再恢复上游。

恢复后重新验证中医诊断采集、患者详情、诊断历史和护理文书。

## 19. 观察期结束与清理

观察期结束前不得删除：

- 三张 legacy 行级备份表
- 动态显示设置备份表（如有）
- 数据库快照/PITR 恢复点
- 新旧 jar/static/pbtxt/SQL manifest
- 上线和冒烟记录

清理前必须：

1. 业务验收签字。
2. 确认无需整版回滚。
3. 将备份转入符合医疗数据规范的长期归档，或取得销毁审批。
4. DBA 以显式表名删除备份，不使用通配、动态变量或 `CASCADE`。
5. 记录删除时间、执行人和可恢复性。

## 20. 上线记录模板

| 检查项 | 结果 | 时间 | 执行人/证据 |
| --- | --- | --- | --- |
| 三工程 commit 和 SHA-256 已冻结 |  |  |  |
| 外置 J3 pbtxt 无旧键 |  |  |  |
| engine jar 内 static 为新版 |  |  |  |
| 生产只读列盘点通过 |  |  |  |
| 待删数据统计和业务确认完成 |  |  |  |
| 外部数据库依赖盘点通过 |  |  |  |
| 动态显示设置扫描通过/已批准重置 |  |  |  |
| 自定义模板和报表盘点通过 |  |  |  |
| 脱敏副本正向/回滚演练通过 |  |  |  |
| 上游已暂停、J3 已排空 |  |  |  |
| 所有旧服务和连接已停止 |  |  |  |
| 整库快照/PITR 已建立 |  |  |  |
| 三张 legacy 备份逐值一致 |  |  |  |
| 正向 DDL 成功 |  |  |  |
| 新列 nullable、无默认、全 NULL |  |  |  |
| engine schema validation 成功 |  |  |  |
| 前端和诊断历史冒烟成功 |  |  |  |
| J3 实际 pbtxt 启动和消息回放成功 |  |  |  |
| 护理文书/质控回归成功 |  |  |  |
| 上游已恢复 |  |  |  |
| 观察期无异常 |  |  |  |

## 21. 现场不得临时决定的事项

以下事项必须在发布前确定：

- 实际 schema 名称。
- 如何暂停和补发每个 HIS/HL7/XML 来源。
- 如何确认 J3 队列排空。
- 所有实例和旧守护进程清单。
- 浏览器、反向代理和 CDN 缓存刷新方式。
- 动态显示设置命中时是否允许重置。
- 旧中医诊断数据影响审批。
- 备份保存位置、权限和保留期限。
- 回滚最晚决策点。
- PostgreSQL 同版本恢复演练证据。

如果这些信息仍为 `<待填写>`，发布负责人应判定 No-Go。
