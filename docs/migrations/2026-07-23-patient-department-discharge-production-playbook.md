# 患者科室与出科字段统一命名——线上数据库及服务迁移 Playbook

## 1. 使用范围

本文用于将以下组件从旧字段契约切换到新字段契约：

- PostgreSQL：`his_patient_records`、`patient_records`、存量患者显示设置
- `icis_j3`：HIS/HL7/XML 患者数据采集和 `his_patient_records` 写入
- `jingyi_icis_engine2`：患者同步、患者 API、配置和内置前端静态资源
- `jingyi_icis_frontend`：患者页面使用的 Protobuf JSON key

代码改造范围和字段清单见
`2026-07-23-patient-department-discharge-field-rename-requirements.md`。

本文默认采用**有维护窗口的原地重命名方案**。数据库列名和 Web JSON key 对新旧版本均互斥，
在没有额外双写/alias 代码时不允许滚动混跑。

## 2. 安全原则

1. 先暂停上游、排空队列并停止所有目标表写入者，再执行 DDL。
2. 两张表的列重命名、新增和删除放在同一事务中，并启用 `ON_ERROR_STOP`、`lock_timeout`。
3. `schema.postgresql.sql` 是完整建库脚本，禁止在生产库直接执行。
4. `his_patient_records.discharged_type` 已确认无实际业务用途且未同步到 `patient_records`，本次
   停机 DDL 直接删除；删除前必须独立备份 `id/value`，供整版回滚恢复。
5. 不迁移 `dept_system_settings.settings_pb` 内的旧动态列 ID：备份函数 1–4 后删除对应记录，
   由新版 engine 首次启动按新版 pbtxt 默认值重新初始化。该决策会丢弃原科室自定义显示顺序。
6. 所有外置 pbtxt、engine 内置静态前端和后端 jar 必须来自同一发布版本。
7. 任一硬门槛失败立即 No-Go，不在生产现场临时修改 SQL 或二进制配置。

## 3. 迁移策略概览

```text
发布前演练与备份
  -> 暂停 HIS/HL7/XML 输入
  -> 排空 icis_j3 队列
  -> 停止 icis_j3 与 engine
  -> 检查数据库连接和长事务
  -> 事务内重命名两张表字段、新增 his.from_dept_id、删除 his.discharged_type
  -> 删除 dept_system_settings 函数 1–4 的旧设置
  -> 启动新版 engine（携带新版前端）
  -> engine 按新版 pbtxt 默认值重建函数 1–4 设置
  -> 启动新版 icis_j3（加载新版外置 pbtxt）
  -> 受控冒烟与数据核对
  -> 恢复上游
  -> 观察期
```

## 4. 角色与联络信息

上线单必须在执行前填完下表：

| 角色 | 姓名/联系方式 | 职责 |
| --- | --- | --- |
| 发布负责人 | `<待填写>` | Go/No-Go、步骤推进、时间记录 |
| DBA | `<待填写>` | 备份、DDL、数据库验证、回滚 |
| `icis_j3` 负责人 | `<待填写>` | 暂停上游、排空、发布、受控消息 |
| engine 负责人 | `<待填写>` | jar、默认配置初始化、健康检查、日志 |
| 前端负责人 | `<待填写>` | 构建产物、缓存失效、页面验证 |
| 业务验收人 | `<待填写>` | 入科/转出/死亡/出院场景确认 |
| 现场/医院接口人 | `<待填写>` | HIS 暂停、恢复、补发和消息核对 |

同时记录：

- 目标医院/环境：`<待填写>`
- 数据库实例/库名：`<待填写>`
- 维护窗口：`<开始时间>` 至 `<结束时间>`
- 预计业务中断：`<待填写>`
- 回滚最晚决策点：`<待填写>`
- 观察期结束时间：`<待填写>`

## 5. 发布制品清单

### 5.1 必备制品

- `icis_j3` 新旧 jar、对应 Git commit 和 SHA-256。
- `jingyi_icis_engine2` 新旧 jar、对应 Git commit 和 SHA-256。
- `jingyi_icis_frontend` 新版源码 commit、production build 记录。
- 已复制新版前端静态资源的 engine jar；需验证 jar 内文件而非只验证 frontend `dist`。
- 四份新版 engine pbtxt：默认、`ah2`、`xaxrmyy`、`xnxrmyy`。
- 每家医院实际使用的新版和旧版 `icis_j3 --jingyi.xml.config` 外置 pbtxt。
- 独立正向 DDL 脚本、反向 DDL 脚本、显示设置删除/恢复脚本。
- `his_patient_records(id, discharged_type)` 独立备份及恢复脚本。
- 数据库备份位置、恢复命令和最近一次恢复演练记录。
- 冒烟测试用患者/科室/床位和可控 ADT 消息。

### 5.2 制品一致性检查

发布包中应附一份 manifest，至少记录：

```text
icis_j3 commit / artifact sha256
engine commit / artifact sha256
frontend commit / build time / artifact sha256
engine 使用的 icis_config.pb.txt 路径和 sha256
j3 外置 XML pbtxt 路径和 sha256
数据库正向/反向脚本 sha256
显示设置删除/恢复脚本 sha256
```

必须解包 engine jar，确认其中的 `home.bundle.js`、`common.bundle.js` 和 HTML 是本次前端
构建产物。固定文件名的静态资源还要确认反向代理/CDN 缓存刷新方案。

前端正式交付顺序固定为：

1. 在 `jingyi_icis_frontend` 执行 production build，生成 `dist`。
2. 将 `dist/` 的完整内容复制到
   `jingyi_icis_engine2/src/main/resources/static/`，并清理不再由本次 build 生成的旧静态文件。
3. 在 `jingyi_icis_engine2` 执行 `mvn clean package`。
4. 解包并逐项核对 jar 内静态资源，再对最终 jar 计算 SHA-256 并发布。

不得直接发布 frontend `dist` 后再复用旧 engine jar，也不得只覆盖部分 bundle。

## 6. Go/No-Go 硬门槛

以下任一项不满足则不得上线：

- 三工程代码和 Protobuf 未从同一冻结映射表构建。
- Proto 重命名字段改变了 field number 或类型。
- `HisPatientPB.discharged_type = 50`、parser 的 99/100 未 reserved。
- 新旧 jar、前端、外置 pbtxt、DDL、显示设置删除/恢复脚本或备份脚本缺失。
- 三工程构建/测试或生产脱敏副本演练失败。
- 无法暂停上游且没有可靠消息缓存/补发机制。
- 生产存在未盘点的旧 `icis_bridge`、旧 `icis_j3`、脚本、ETL、报表或其他写入者。
- 没有可验证的数据库备份或没有反向脚本。
- 删除显示设置前未完成函数 1–4 的备份、行数核对或恢复演练。
- 无法在维护窗口清理旧前端缓存并强制客户端加载新版静态资源。

## 7. 上线前数据库盘点

以下 SQL 均先在生产只读执行，并把结果保存到上线记录。示例默认表位于 `public` schema；
若实际 schema 不同必须显式替换。

### 7.1 当前列状态

```sql
SELECT
    table_schema,
    table_name,
    ordinal_position,
    column_name,
    data_type,
    character_maximum_length,
    is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('his_patient_records', 'patient_records')
  AND column_name IN (
      'icu_admission_time', 'dept_admission_time',
      'admission_source_dept_name', 'admission_source_dept_id',
      'from_dept_name', 'from_dept_id',
      'discharged_type', 'discharge_type',
      'discharged_death_time', 'death_time',
      'discharged_hospital_exit_time', 'his_discharge_time',
      'discharged_diagnosis', 'discharge_diagnosis',
      'discharged_diagnosis_code', 'discharge_diagnosis_code',
      'discharged_dept_name', 'discharged_dept_id',
      'to_dept_name', 'to_dept_id',
      'discharging_account_id', 'discharge_account_id'
  )
ORDER BY table_name, ordinal_position;
```

预期：执行前只有旧列存在，且不存在会与目标重名冲突的新列；重复执行/部分执行的环境必须先
按实际状态处置，不能继续跑普通脚本。

### 7.2 数据基线

```sql
SELECT
    COUNT(*) AS total_rows,
    COUNT(icu_admission_time) AS icu_admission_time_non_null,
    COUNT(admission_source_dept_name) AS source_name_non_null,
    COUNT(discharged_dept_id) AS discharged_dept_id_non_null,
    COUNT(discharged_dept_name) AS discharged_dept_name_non_null,
    COUNT(discharged_type) AS discharged_type_non_null
FROM his_patient_records;

SELECT discharged_type, COUNT(*)
FROM his_patient_records
GROUP BY discharged_type
ORDER BY discharged_type;

SELECT
    COUNT(*) AS total_rows,
    COUNT(admission_source_dept_name) AS source_name_non_null,
    COUNT(admission_source_dept_id) AS source_id_non_null,
    COUNT(discharged_type) AS discharged_type_non_null,
    COUNT(discharged_death_time) AS death_time_non_null,
    COUNT(discharged_hospital_exit_time) AS his_discharge_time_non_null,
    COUNT(discharged_diagnosis) AS diagnosis_non_null,
    COUNT(discharged_diagnosis_code) AS diagnosis_code_non_null,
    COUNT(discharged_dept_name) AS to_name_non_null,
    COUNT(discharged_dept_id) AS to_id_non_null,
    COUNT(discharging_account_id) AS account_non_null
FROM patient_records;
```

另保存固定患者 ID 的脱敏抽样结果，供迁移后逐值核对。抽样至少覆盖 NULL、非 NULL、
转出、死亡、出院和多次入科患者。

### 7.3 数据库对象依赖

```sql
SELECT schemaname, viewname
FROM pg_views
WHERE definition ~* '(admission_source_dept|icu_admission_time|discharged_|discharging_account)';

SELECT schemaname, matviewname
FROM pg_matviews
WHERE definition ~* '(admission_source_dept|icu_admission_time|discharged_|discharging_account)';

SELECT
    n.nspname AS schema_name,
    p.proname AS routine_name,
    p.prokind
FROM pg_proc p
JOIN pg_namespace n ON n.oid = p.pronamespace
WHERE p.prokind IN ('f', 'p')
  AND pg_get_functiondef(p.oid) ~* '(admission_source_dept|icu_admission_time|discharged_|discharging_account)';

SELECT
    event_object_schema,
    event_object_table,
    trigger_name,
    action_statement
FROM information_schema.triggers
WHERE action_statement ~* '(admission_source_dept|icu_admission_time|discharged_|discharging_account)';
```

任何命中对象都必须有同步修改或明确排除结论。还要人工盘点数据库外的 BI、ETL、JFK 报表、
运维脚本和第三方直连 SQL；仓库搜索不能证明线上不存在外部消费者。

### 7.4 JFK 模板专项盘点

仓库内置 JFK 模板使用 `patient_info` / `patient_info_extended` 等稳定数据源 ID，当前输出字段为
`dept_name`、`bed_no`、`patient_name`、`diagnosis`、`admission_time_yyyymmdd` 等，不引用本次旧列名，
因此不需要随数据库列改名。生产仍需完成以下核对：

1. 解码所有未删除的 `dragable_form_templates.template_pb`，列出每个模板使用的
   `data_source_meta_id` 和 `data_source_field_id`。
2. 确认这些 ID 均存在于新版 `jfk.data_sources`，且没有使用本次旧字段名作为自定义 ID。
3. 盘点所有自定义 JFK data source handler、插件、打印代理和外部 SQL，确认没有直接查询旧列。
4. 对每类生产模板至少选一份，在预发布迁移库上完成取数、预览和打印冒烟。

Base64 中的 Protobuf 字符串值仍以 UTF-8 字节保存，可用下面的查询做快速初筛；查询无命中不能
替代完整解码，因为自定义数据源实现和外部脚本不在模板二进制中：

```sql
WITH old_names(name) AS (
    VALUES
        ('admission_source_dept_name'),
        ('admission_source_dept_id'),
        ('discharged_type'),
        ('discharged_death_time'),
        ('discharged_hospital_exit_time'),
        ('discharged_diagnosis'),
        ('discharged_diagnosis_code'),
        ('discharged_dept_name'),
        ('discharged_dept_id'),
        ('discharging_account_id')
)
SELECT t.id, t.dept_id, t.name, o.name AS matched_old_name
FROM dragable_form_templates t
JOIN old_names o
  ON POSITION(convert_to(o.name, 'UTF8') IN decode(t.template_pb, 'base64')) > 0
WHERE t.is_deleted = FALSE
ORDER BY t.dept_id, t.id, o.name;
```

预期仓库标准模板为零命中。任何生产命中都必须先解码确认用途，再决定修改模板、自定义数据源或
判定为无关静态文本。

## 8. 存量显示设置盘点、备份与重置

### 8.1 行数和旧 ID 扫描

`DisplayFieldSettingsPB` 以 Base64 编码的二进制存储。虽然可按字节扫描旧 ID 用于盘点，
本次不修改二进制内容，也不保留旧 ID alias；函数 1–4 的记录将在停机期间整体删除并按新版
默认配置重建。可用下列只读查询记录受影响范围：

```sql
WITH old_names(name) AS (
    VALUES
        ('admission_source_dept_name'),
        ('admission_source_dept_id'),
        ('discharged_type'),
        ('discharged_death_time'),
        ('discharged_hospital_exit_time'),
        ('discharged_diagnosis'),
        ('discharged_diagnosis_code'),
        ('discharged_dept_name'),
        ('discharged_dept_id'),
        ('discharging_account_id')
)
SELECT
    d.dept_id,
    d.function_id,
    STRING_AGG(o.name, ',' ORDER BY o.name) AS matched_old_names
FROM dept_system_settings d
JOIN old_names o
  ON POSITION(convert_to(o.name, 'UTF8') IN decode(d.settings_pb, 'base64')) > 0
WHERE d.function_id IN (1, 2, 3, 4)
GROUP BY d.dept_id, d.function_id
ORDER BY d.dept_id, d.function_id;
```

还需记录：

```sql
SELECT function_id, COUNT(*)
FROM dept_system_settings
WHERE function_id IN (1, 2, 3, 4)
GROUP BY function_id
ORDER BY function_id;
```

### 8.2 数据库内备份

由 DBA 使用本次发布唯一名称创建一次性备份表；若表已存在应报错停止，不能覆盖：

```sql
CREATE TABLE migration_backup_20260723_patient_display_settings AS
SELECT *
FROM dept_system_settings
WHERE function_id IN (1, 2, 3, 4);

ALTER TABLE migration_backup_20260723_patient_display_settings
    ADD PRIMARY KEY (dept_id, function_id);
```

核对备份行数与源查询一致，并把备份表纳入数据库快照。观察期结束后按数据保留规范处理，
不要在切换当天删除。

### 8.3 重置方案和影响确认

在变更单中明确记录以下已接受影响：

- 函数 1–4 的所有科室级患者列表显示设置都会被删除。
- 新版 engine 启动时通过 `SystemInitializer.init()` 调用 `PatientConfig.initialize()`，按实际选择的
  新版医院 pbtxt 为缺失记录写入默认值。
- 旧设置只用于整版回滚，不会尝试映射到新字段；既有科室自定义列、显示顺序和宽度不会保留。
- 不提供旧动态列 ID alias，也不编写 `settings_pb` 迁移工具。

预发布环境必须验证：删除函数 1–4 后启动新版 engine，所有目标科室都重新生成四类设置，
生成内容可由新版 Protobuf 解析，且旧列 ID 扫描为 0 行。

## 9. 服务与消息链路盘点

### 9.1 必须停止或暂停

- 所有 `icis_j3` 实例，包括守护进程自动拉起的实例。
- 所有 `jingyi_icis_engine2` 实例，包括负载均衡后的其他节点。
- HIS HL7/XML 主动推送，或 `icis_j3` 的消费入口。
- 任何直接读写 `his_patient_records` / `patient_records` 的批处理和同步任务。

### 9.2 可保持运行的组件

`icis_jd2` 当前只使用设备相关表，理论上可保持运行；上线前仍需对实际部署版本和数据库
访问日志做一次确认。若存在未知版本或共享进程，宁可纳入维护窗口停止。

### 9.3 消息保护

上游暂停前必须确定以下之一：

- 上游在维护窗口内可靠缓存并可按原顺序补发；或
- 下游入口持久化原始消息，服务恢复后可重放。

记录暂停前最后一条消息的业务 ID/时间，恢复后以此检查是否丢失、重复或乱序。不能只依赖
应用日志中的“已停止”判断队列已经排空。

## 10. 备份与恢复准备

至少准备：

1. 数据库一致性快照或可恢复到上线前时间点的 PITR/WAL。
2. `his_patient_records` 和 `patient_records` 的 schema 与数据备份。
3. `dept_system_settings` 函数 1–4 的行级备份。
4. `his_patient_records(id, discharged_type)` 的独立备份，供本次直接删列后的整版回滚恢复。
5. 旧 jar、旧前端静态资源、旧 engine pbtxt 和旧 j3 外置 pbtxt。
6. 已在生产同版本 PostgreSQL 副本上验证过的反向 DDL。

删除 `his_patient_records.discharged_type` 前创建独立备份表；若同名表已存在则停止，不能覆盖：

```sql
CREATE TABLE migration_backup_20260723_his_discharged_type AS
SELECT id, discharged_type
FROM his_patient_records;

ALTER TABLE migration_backup_20260723_his_discharged_type
    ADD PRIMARY KEY (id);
```

核对源表总行数、备份表总行数和 `discharged_type` 非空数一致，并将结果写入上线记录。

备份完成不等于可恢复。上线前必须至少完成一次：恢复到隔离库、启动旧版服务、读取样本患者
和显示设置的演练。

## 11. 预发布演练

在生产脱敏副本上按正式顺序完整执行，不接受只验证 DDL 语法：

1. 恢复生产相同 schema 和代表性数据。
2. 放入生产实际的 `dept_system_settings.settings_pb` 副本。
3. 备份函数 1–4 设置和 `his_patient_records(id, discharged_type)`，核对行数和校验值。
4. 停旧服务、执行正向 DDL、删除函数 1–4 设置。
5. 启动新版 engine，确认 schema validation 与配置解析通过。
6. 启动新版 `icis_j3`，加载实际医院外置 pbtxt。
7. 回放入院、转入 ICU、转出 ICU、死亡、出院样本。
8. 验证患者列表四种状态、患者详情 V1/V2、确认入科/出科和质控接口。
9. 执行完整回滚，恢复被删除的设置与 `discharged_type` 数据，启动旧版服务并再次验证。

演练记录必须包含耗时，作为维护窗口和回滚最晚决策点依据。

## 12. 正式上线步骤

### 12.1 T-30 分钟：冻结与确认

1. 发布负责人宣布变更冻结。
2. 核对第 4–11 节的记录、制品 SHA-256 和负责人在线状态。
3. 确认数据库备份成功且恢复负责人可用。
4. 核对函数 1–4 设置和 `his_patient_records.discharged_type` 的备份脚本、恢复脚本已演练；
   生产最终行级备份将在写入全部停止后执行。
5. 确认负载均衡、守护进程、容器编排不会自动启动旧实例。

### 12.2 T-15 分钟：暂停输入并排空

1. 暂停 HIS HL7/XML 推送或关闭 `icis_j3` 消费入口。
2. 记录最后一条已接收/已提交消息 ID 和时间。
3. 等待 `icis_j3` 工作队列、重试队列和数据库事务归零。
4. 优雅停止所有 `icis_j3` 实例。
5. 将 engine 从负载均衡摘除，等待进行中的患者写请求结束。
6. 优雅停止所有 engine 实例。
7. 再次确认没有旧 `icis_bridge` 或运维脚本在写目标表。

### 12.3 T-5 分钟：数据库连接和锁检查

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
      OR query ~* '(his_patient_records|patient_records|dept_system_settings)'
  )
ORDER BY xact_start NULLS LAST, query_start;
```

出现未知长事务、未停止应用连接或目标表活跃写入时暂停操作。终止连接属于 DBA 受控动作，
不能由脚本无条件执行。

确认所有写入均已停止后，执行第 8.2 节和第 10 节的生产行级备份 SQL。这两张表才是本次发布
的最终回滚基线；必须核对行数、主键和非空统计，完成前不得执行 DDL。若示例备份表名已存在，
应停止并换用经变更单登记的唯一名称，同时同步修改全部恢复 SQL，禁止覆盖旧备份。

### 12.4 执行数据库 DDL

正式脚本必须包含旧/新列存在状态检查，并通过以下方式执行：

```bash
psql -v ON_ERROR_STOP=1 -f <forward-migration.sql> <database>
```

核心 SQL 如下。`discharged_diagnosis_code` 已确认仍为编码语义，目标列名为
`discharge_diagnosis_code`，类型保持 `VARCHAR(1000)`：

```sql
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE his_patient_records
    RENAME COLUMN icu_admission_time TO dept_admission_time;
ALTER TABLE his_patient_records
    RENAME COLUMN admission_source_dept_name TO from_dept_name;
ALTER TABLE his_patient_records
    ADD COLUMN from_dept_id VARCHAR(255);
ALTER TABLE his_patient_records
    DROP COLUMN discharged_type;
ALTER TABLE his_patient_records
    RENAME COLUMN discharged_dept_id TO to_dept_id;
ALTER TABLE his_patient_records
    RENAME COLUMN discharged_dept_name TO to_dept_name;

ALTER TABLE patient_records
    RENAME COLUMN admission_source_dept_name TO from_dept_name;
ALTER TABLE patient_records
    RENAME COLUMN admission_source_dept_id TO from_dept_id;
ALTER TABLE patient_records
    RENAME COLUMN discharged_type TO discharge_type;
ALTER TABLE patient_records
    RENAME COLUMN discharged_death_time TO death_time;
ALTER TABLE patient_records
    RENAME COLUMN discharged_hospital_exit_time TO his_discharge_time;
ALTER TABLE patient_records
    RENAME COLUMN discharged_diagnosis TO discharge_diagnosis;
ALTER TABLE patient_records
    RENAME COLUMN discharged_diagnosis_code TO discharge_diagnosis_code;

ALTER TABLE patient_records
    RENAME COLUMN discharged_dept_name TO to_dept_name;
ALTER TABLE patient_records
    RENAME COLUMN discharged_dept_id TO to_dept_id;
ALTER TABLE patient_records
    RENAME COLUMN discharging_account_id TO discharge_account_id;

COMMENT ON COLUMN his_patient_records.dept_admission_time IS '科室入科时间';
COMMENT ON COLUMN his_patient_records.from_dept_name IS '来源科室名称';
COMMENT ON COLUMN his_patient_records.from_dept_id IS '来源科室编码';
COMMENT ON COLUMN his_patient_records.to_dept_id IS '去向科室编码';
COMMENT ON COLUMN his_patient_records.to_dept_name IS '去向科室名称';

COMMENT ON COLUMN patient_records.from_dept_name IS '来源科室名称，也可以是护士手动输入';
COMMENT ON COLUMN patient_records.from_dept_id IS '来源科室编码；名称不是HIS科室名称时可为空';
COMMENT ON COLUMN patient_records.discharge_type IS '出科类型：转出、死亡、出院';
COMMENT ON COLUMN patient_records.death_time IS '死亡时间；出科类型为死亡时有效';
COMMENT ON COLUMN patient_records.his_discharge_time IS 'HIS出院时间；出科类型为出院时有效';
COMMENT ON COLUMN patient_records.discharge_diagnosis IS '出科诊断';
COMMENT ON COLUMN patient_records.discharge_diagnosis_code IS '出科诊断编码';
COMMENT ON COLUMN patient_records.to_dept_name IS '去向科室名称';
COMMENT ON COLUMN patient_records.to_dept_id IS '去向科室编码';
COMMENT ON COLUMN patient_records.discharge_account_id IS '出科操作员账号ID';

COMMIT;
```

如果获取锁超时或任意语句失败，`ON_ERROR_STOP` 必须使脚本停止，事务回滚。此时不要继续启动
新服务，应先核对实际列状态并决定重试或回滚。

### 12.5 立即验证数据库结构和数据

1. 重新执行第 7.1 节列查询。
2. 确认所有目标列存在、所有旧列不存在；特别确认 `his_patient_records.discharged_type` 已删除，
   `patient_records.discharge_diagnosis_code` 类型仍为 `character varying(1000)`。
3. 用新列名重跑第 7.2 节对应统计，逐项对比所有重命名列；被删除的 HIS `discharged_type`
   改为核对独立备份表行数和非空数。
4. 核对固定患者 ID 抽样值完全一致。
5. 确认 `from_dept_id` 为 nullable，历史行为空不属于失败。

### 12.6 删除存量显示设置

保持 engine 停止，先再次确认第 8.2 节备份表的行数和校验结果，再执行：

```sql
BEGIN;

DELETE FROM dept_system_settings
WHERE function_id IN (1, 2, 3, 4);

COMMIT;
```

删除后必须验证：

```sql
SELECT COUNT(*) AS remaining_rows
FROM dept_system_settings
WHERE function_id IN (1, 2, 3, 4);
```

预期 `remaining_rows = 0`。若不为 0，不得启动 engine。不要直接修改或替换 Base64 内容；
上线方案是清空后按默认配置初始化，不是保留并迁移科室自定义设置。

### 12.7 启动新版 engine

1. 确认 engine 使用正确医院 pbtxt 和正确数据库。
2. 启动单个实例，暂不加入负载均衡。
3. 检查：
   - Hibernate schema validation 成功；
   - 四份默认配置中实际选择的那份可解析；
   - 启动初始化已为所有目标科室创建函数 1–4 的默认设置；
   - 患者配置完整性检查没有旧列 ID、unknown field 或列名错误；
   - 数据库连接池和健康接口正常；
   - 无 `column does not exist`、`Unexpected patient_records' column name`、
     `PARSE_JSON_FAILED`。
4. 通过直连方式执行只读冒烟：患者列表四种状态、患者详情 V1/V2、质控患者列表。
5. 确认静态资源响应的是新版 bundle，并检查缓存响应头。
6. 执行第 8.1 节行数查询和旧 ID 扫描：函数 1–4 行数符合目标科室数，旧 ID 为 0 命中；
   抽样反序列化四类设置并与所选医院 pbtxt 默认值核对。
7. 单实例通过后再启动其余新版实例，确认没有旧实例混入。

### 12.8 启动新版 `icis_j3`

1. 检查命令行实际加载的 `--jingyi.xml.config` 文件 SHA-256。
2. 启动单个新版实例，保持上游暂停。
3. 确认所有 pbtxt 可被新 descriptor 解析，无 unknown field。
4. 确认数据库写入实体与新列匹配，无 schema validation/SQL 错误。
5. 若有多个实例，全部替换为同版本后再恢复入口。

### 12.9 受控业务冒烟

按医院实际接入协议发送受控消息，至少验证：

| 场景 | 核对点 |
| --- | --- |
| 惠每转入 ICU | `his.from_dept_id/name` 来自 `t_out_dept_code/name`；`dept_admission_time` 正确 |
| 嘉禾美康患者更新 | 本次不实现真实来源科室编码映射，`his.from_dept_id` 保持 NULL |
| 卫宁 HL7 患者更新 | 本次不实现来源科室映射，`his.from_dept_id/name` 保持 NULL |
| HIS 到 ICU 同步 | `patient.from_dept_id/name` 仅在目标为空时首次填充；入科时间不被意外覆盖 |
| 人工确认入科 | 前端发送 `fromDeptName`，后端正常解析并保存 |
| 转出 | `dischargeType/toDeptName` 正确保存和回显 |
| 死亡 | `dischargeType/deathTime` 正确保存和回显 |
| 出院 | `dischargeType/hisDischargeTimeIso8601` 正确保存和回显 |
| 患者详情 V2 | 仅验证既有字段改名后的 JSON key；不要求新增 `fromDeptId`、`toDeptId`、`dischargeAccountId` 或诊断编码字段 |
| 四类患者列表 | 新列 ID 的表头和值正常，无 500 |
| ICU 质控 | 来源科室与死亡统计结果不因改名变化 |

需要同时查看浏览器网络请求、engine 日志、`icis_j3` 日志和数据库行，不能只以页面提示“成功”
作为通过标准。

### 12.10 恢复流量与上游

1. 将新版 engine 全部加入负载均衡。
2. 清理/刷新反向代理和 CDN 缓存；要求现场浏览器重新加载新版静态资源。
3. 恢复 `icis_j3` 消费入口和 HIS 推送。
4. 从暂停前最后消息 ID 向后核对补发序列。
5. 观察至少一个完整同步周期后，再宣布维护窗口结束。

## 13. 上线后验证 SQL

### 13.1 列状态

```sql
SELECT table_name, column_name, data_type, is_nullable
FROM information_schema.columns
WHERE table_schema = 'public'
  AND table_name IN ('his_patient_records', 'patient_records')
  AND column_name IN (
      'dept_admission_time', 'from_dept_name', 'from_dept_id',
      'discharge_type', 'death_time', 'his_discharge_time',
      'discharge_diagnosis', 'discharge_diagnosis_code',
      'to_dept_name', 'to_dept_id', 'discharge_account_id'
  )
ORDER BY table_name, column_name;
```

### 13.2 新字段写入抽查

```sql
SELECT
    id,
    his_encounter_id,
    mrn,
    from_dept_id,
    from_dept_name,
    dept_admission_time,
    to_dept_id,
    to_dept_name,
    discharge_time,
    created_at
FROM his_patient_records
ORDER BY id DESC
LIMIT 50;

SELECT
    id,
    his_encounter_id,
    his_mrn,
    from_dept_id,
    from_dept_name,
    admission_time,
    admission_status,
    discharge_type,
    death_time,
    his_discharge_time,
    discharge_diagnosis,
    discharge_diagnosis_code,
    to_dept_id,
    to_dept_name,
    discharge_time,
    discharge_account_id
FROM patient_records
ORDER BY id DESC
LIMIT 50;
```

查询结果包含医疗数据，只能存入获批的上线记录位置并按脱敏规范处理。

### 13.3 旧显示列 ID 零命中

重复第 8.1 节旧 ID 扫描，结果必须为 0 行。还要通过 API 获取每个函数 1–4 至少一个科室
的设置，确认 `column_id` 与 `col_id_to_display` 使用新版 pbtxt 的默认值和新名称。此处验证的是
默认重建结果，不要求保留上线前的科室自定义显示顺序。

用下列查询确认每个 `rbac_departments` 科室都具有函数 1–4 的设置；预期返回 0 行：

```sql
WITH function_ids(function_id) AS (
    VALUES (1), (2), (3), (4)
), expected AS (
    SELECT d.dept_id, f.function_id
    FROM rbac_departments d
    CROSS JOIN function_ids f
)
SELECT e.dept_id, e.function_id
FROM expected e
LEFT JOIN dept_system_settings s
  ON s.dept_id = e.dept_id
 AND s.function_id = e.function_id
WHERE s.dept_id IS NULL
ORDER BY e.dept_id, e.function_id;
```

## 14. 监控与观察期

### 14.1 首小时

前 15 分钟持续观察，其后每 15 分钟记录一次：

- engine 和 `icis_j3` 实例数、健康状态、重启次数。
- HTTP 4xx/5xx，特别是患者新增、入科、出科、详情更新接口。
- `PARSE_JSON_FAILED`、unknown field、unknown column、schema validation 错误。
- `Unexpected patient_records' column name` 和患者列表 500。
- HIS 消息积压、失败、重试、重复和延迟。
- 数据库锁等待、长事务、连接数和目标表写入速率。
- 新增/更新患者数量与暂停前基线的合理性。

### 14.2 观察期

建议至少覆盖 7 个自然日和一次完整的转入、转出、死亡、出院业务周期。在观察期内：

- 保留旧 jar、旧配置、备份表和反向脚本。
- 每日扫描一次旧列 ID 和关键错误日志。
- 记录 `from_dept_id` 新增写入率和各上游平台 NULL 分布；嘉禾美康编码、卫宁 HL7 编码及名称
  本次预期为空，不应作为上线失败判据。

## 15. 回滚触发条件

出现以下任一情况，且无法在回滚最晚决策点前安全修复，应立即回滚：

- engine 或 `icis_j3` 无法稳定启动。
- 新版 pbtxt 无法解析，或实际加载了旧外置配置。
- 持续出现列不存在、unknown field、JSON 解析失败。
- 患者新增/入科/出科主流程不可用。
- 四类患者列表因旧动态列 ID 大面积失败。
- HIS 消息持续失败、丢失或无法可靠补发。
- 数据迁移前后统计或抽样值不一致。
- 前端缓存无法清理，且大量客户端仍使用旧 JSON key。
- 发现未盘点的关键外部消费者依赖旧列名。

单条可重试消息失败不必自动回滚，但必须先隔离消息并确认不是契约性故障。

## 16. 回滚步骤

### 16.1 重新冻结

1. 再次暂停 HIS/HL7/XML 输入，记录最后消息 ID。
2. 从负载均衡摘除新版 engine。
3. 排空后停止所有新版 `icis_j3`。
4. 停止所有新版 engine。
5. 确认没有目标表写入和长事务。

### 16.2 恢复显示设置

在启动旧版 engine 前，从备份恢复函数 1–4：

```sql
BEGIN;

DELETE FROM dept_system_settings
WHERE function_id IN (1, 2, 3, 4);

INSERT INTO dept_system_settings (
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
FROM migration_backup_20260723_patient_display_settings;

COMMIT;
```

执行前核对备份表没有被修改，执行后核对行数和 Base64 内容与上线前一致。

### 16.3 反向数据库重命名

反向脚本同样使用 `psql -v ON_ERROR_STOP=1`。核心 SQL：

```sql
BEGIN;

SET LOCAL lock_timeout = '10s';
SET LOCAL statement_timeout = '120s';

ALTER TABLE his_patient_records
    RENAME COLUMN dept_admission_time TO icu_admission_time;
ALTER TABLE his_patient_records
    RENAME COLUMN from_dept_name TO admission_source_dept_name;
ALTER TABLE his_patient_records
    ADD COLUMN discharged_type INTEGER;
UPDATE his_patient_records h
SET discharged_type = b.discharged_type
FROM migration_backup_20260723_his_discharged_type b
WHERE h.id = b.id;
COMMENT ON COLUMN his_patient_records.discharged_type IS '出科类型：转出、死亡、出院';
ALTER TABLE his_patient_records
    RENAME COLUMN to_dept_id TO discharged_dept_id;
ALTER TABLE his_patient_records
    RENAME COLUMN to_dept_name TO discharged_dept_name;

-- from_dept_id 是新增列。默认保留，旧服务会忽略额外列，避免回滚期间丢失新写入值。

ALTER TABLE patient_records
    RENAME COLUMN from_dept_name TO admission_source_dept_name;
ALTER TABLE patient_records
    RENAME COLUMN from_dept_id TO admission_source_dept_id;
ALTER TABLE patient_records
    RENAME COLUMN discharge_type TO discharged_type;
ALTER TABLE patient_records
    RENAME COLUMN death_time TO discharged_death_time;
ALTER TABLE patient_records
    RENAME COLUMN his_discharge_time TO discharged_hospital_exit_time;
ALTER TABLE patient_records
    RENAME COLUMN discharge_diagnosis TO discharged_diagnosis;
ALTER TABLE patient_records
    RENAME COLUMN discharge_diagnosis_code TO discharged_diagnosis_code;

ALTER TABLE patient_records
    RENAME COLUMN to_dept_name TO discharged_dept_name;
ALTER TABLE patient_records
    RENAME COLUMN to_dept_id TO discharged_dept_id;
ALTER TABLE patient_records
    RENAME COLUMN discharge_account_id TO discharging_account_id;

COMMIT;
```

回滚 SQL 完成后，核对备份时已存在的每个 `his_patient_records.id` 都恢复了原
`discharged_type` 值。新版运行期间新增的 HIS 记录在备份中不存在，该列保持 NULL；若旧版对这类
记录有非空依赖，必须在恢复流量前由业务负责人给出补值规则，不能猜测填充。

### 16.4 恢复旧服务和配置

1. 恢复旧 engine jar；确认其中携带旧前端静态资源和旧 engine pbtxt。
2. 启动单个旧 engine，验证 schema、设置解析、患者列表和患者详情。
3. 恢复旧 `icis_j3` jar 和旧外置 pbtxt。
4. 启动旧 `icis_j3`，验证数据库写入。
5. 全部健康后加入负载均衡并恢复上游。
6. 从冻结点核对补发，检查重复和丢失。

### 16.5 回滚后处理

- 保存故障版本日志、请求样本、DDL 输出、设置删除/初始化记录和数据库抽样。
- 禁止立即删除新 `his_patient_records.from_dept_id`，待数据归档和根因分析完成。
- 宣布回滚完成不等于关闭事件；必须建立根因、修复、再演练和再次上线计划。

## 17. 观察期后的收尾

观察期完成且业务验收通过后：

1. 确认生产代码、数据库对象、外部消费者和显示设置均无旧名称引用。
2. 确认无需整版回滚后，按数据保留规范归档或删除两张一次性备份表；删除须另走 DBA 审批，
   不在本次上线脚本内自动执行。
3. 保存最终字段清单、发布制品 SHA-256、DDL 输出、冒烟证据和问题记录。
4. 关闭维护变更单并记录实际停机时长、消息补发数量和回滚最晚决策点。

本方案没有后续删列或 alias 收缩步骤：`his_patient_records.discharged_type` 已在主 DDL 中删除，
旧 JSON key 和旧动态列 ID 也不保留兼容层。

## 18. 上线完成标准

- 两张表的所有已确认字段已切换到目标名称，数据统计与抽样一致。
- `patient_records.discharge_diagnosis_code` 保持 `VARCHAR(1000)`，编码值改名前后逐值一致。
- 惠每等已有明确映射的上游可写入 `his_patient_records.from_dept_id`；嘉禾美康编码和卫宁 HL7
  编码/名称按本期范围保持 NULL。
- 函数 1–4 的存量设置已删除并由新版 pbtxt 默认值重建，所有旧列 ID 零命中；科室自定义顺序
  不属于保留目标。
- `his_patient_records.discharged_type` 已删除，独立备份和回滚恢复步骤验证通过。
- 新版 engine、前端、`icis_j3` 和外置 pbtxt 版本一致。
- 新增患者、入科、转出、死亡、出院、详情 V1/V2、四类列表和质控冒烟通过。
- HIS 消息无丢失、无持续失败，队列恢复正常。
- 监控期内无旧列名、旧 JSON key、unknown field、schema validation 错误。
- 回滚制品和备份保留到观察期结束，后续归档/删除有独立审批和执行记录。

## 已决策

1. `discharged_diagnosis_code` 改为 `discharge_diagnosis_code`，语义仍为出科诊断编码，类型保持
   `VARCHAR(1000)`，只做无损重命名。
2. 本期不向 `PatientInfoPB` 新增 `from_dept_id`、`to_dept_id`、`discharge_account_id` 或诊断编码
   等当前未暴露字段。
3. 本期不实现嘉禾美康真实来源科室编码映射，`from_dept_id` 保持 NULL。
4. 本期不实现卫宁 HL7 来源科室映射，`from_dept_id/from_dept_name` 保持 NULL。
5. 采用停机发布；不设计双列、双写、JSON alias 或滚动混跑。
6. 备份后删除 `dept_system_settings` 函数 1–4，由新版 engine 按新版 pbtxt 默认值初始化；
   不保留原科室自定义设置，也不提供旧动态列 ID alias。
7. `his_patient_records.discharged_type` 在主 DDL 中直接删除，删除前按 `id` 独立备份。
8. 前端先生成 `dist`，完整复制到 engine `src/main/resources/static`，再执行
   `mvn clean package` 并发布最终 jar。

## 待决策

1. **正式维护窗口参数与责任人。**
   需在上线单中填写具体医院、开始/结束时间、预计停机时长、回滚最晚决策点、观察期、各角色
   联系方式，以及两张一次性备份表的保留期限和删除审批人。
2. **外部消费者清单。**
   仓库无法确认生产上的 BI、ETL、JFK 模板、临时 SQL、第三方直连和废弃服务实例；上线负责人需
   给出逐项所有者和“已修改/已停用/不受影响”结论。
3. **各医院实际配置和实例拓扑。**
   需确认每个环境选用的 engine pbtxt、`icis_j3` 外置 pbtxt、服务实例数、负载均衡和守护方式，
   以及 `ah2/xaxrmyy/xnxrmyy` 是否覆盖全部生产医院。
4. **生产缓存失效细节。**
   `dist -> static -> mvn clean package` 已确定，但浏览器、反向代理或 CDN 的具体刷新命令、负责人
   和验收方法仍需按现场架构补齐。
