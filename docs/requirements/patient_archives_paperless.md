# 重症文档无纸化归档对接需求

## 背景

医院无纸化系统需要访问重症护理文档。当前 compact 重症护理单在生成报表时会按 JFK 数据源实时查询并渲染 PDF，但缺少一份可追溯、可幂等更新、可被无纸化系统访问的归档记录。

本文档只提炼需求，不做实现。实现前必须先把本文末尾“待决策”清空，并把确认后的结论移动到“已决策”。

## 目标

1. 在 `jingyi_icis_engine2` 中新增患者文档归档表 `patient_archives`，记录归档文档类型、业务日期、归档数据和外部访问 URL。
2. 新增 `config/icis_archive.proto`，定义 compact 重症护理单归档数据结构 `NursingReportCompactArchivePB`。
3. 在 `application.properties` 的无纸化区域新增 archives 文档文件根目录配置，并让 `/archives/...` 可以免登录访问。
4. 在 `service/archives` 下新增 `ArchiveService`，提供幂等存储接口。
5. compact 重症护理单每次获取报表时，把本次实际查询过的 JFK 数据源输出组装成 `NursingReportCompactArchivePB`，序列化为 Base64 后写入 `patient_archives.data_pb`。
6. 在 `icis_j3/src/main/java/com/jingyicare/icis_j3/utils/Consts.java` 中定义文档类型常量，`1` 表示 `nursing report compact`，即重症护理单。

## 非目标

1. 本轮不实现代码、SQL、proto 或测试。
2. 本轮不定义三方无纸化系统接口协议。覆盖旧归档或更新 URL 时只定义调用时机，并预留 TODO。
3. 本轮不新增前端页面。
4. 本轮不设计除 compact 重症护理单以外的文档归档内容。
5. 本轮不改变现有 compact 报表 PDF 渲染样式和数据源查询口径。

## 数据库需求

在：

```text
jingyi_icis_engine2/src/main/resources/config/db/schema.postgresql.sql
```

新增表：

```sql
CREATE TABLE patient_archives (
    id SERIAL PRIMARY KEY,
    pid BIGINT NOT NULL,
    type INTEGER NOT NULL,
    local_midnight_utc TIMESTAMP NOT NULL,
    page_count INTEGER,
    data_pb TEXT,
    relative_url VARCHAR(255),
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    deleted_at TIMESTAMP,
    deleted_by VARCHAR(255),
    modified_at TIMESTAMP NOT NULL,
    modified_by VARCHAR(255) NOT NULL
);
```

字段语义：

| 字段 | 语义 |
| --- | --- |
| `id` | 归档记录主键，使用 PostgreSQL `SERIAL` |
| `pid` | 患者记录 ID，对应 `patient_records.id` |
| `type` | 文档类型；`1` 表示 compact 重症护理单 |
| `local_midnight_utc` | 业务日期的本地 0 点所对应的 UTC 时间 |
| `page_count` | 归档 PDF 文档页数 |
| `data_pb` | `NursingReportCompactArchivePB` 的二进制序列化 Base64 字符串 |
| `relative_url` | 无纸化系统访问该文档的相对 URL，例如 `/archives/...` |
| `is_deleted` | 软删除标记 |
| `deleted_at` | 软删除时间，UTC |
| `deleted_by` | 软删除账号或系统标识 |
| `modified_at` | 最后修改时间，UTC |
| `modified_by` | 最后修改账号或系统标识 |

唯一约束：

```sql
CREATE UNIQUE INDEX idx_patient_archives_pid_type_local_midnight
    ON patient_archives (pid, type, local_midnight_utc)
    WHERE is_deleted = FALSE;
```

`local_midnight_utc` 计算要求：

1. 输入 `effectiveTimeUtc` 是 UTC 时间。
2. 使用系统配置时区 `ConfigProtoService.getConfig().getZoneId()`。
3. 用 `TimeUtils.getLocalDateTimeFromUtc(effectiveTimeUtc, zoneId)` 转成本地时间。
4. 取该本地日期的 `00:00:00`。
5. 用 `TimeUtils.getUtcFromLocalDateTime(localMidnight, zoneId)` 转回 UTC 后存入 `local_midnight_utc`。

## 文档类型常量

在：

```text
icis_j3/src/main/java/com/jingyicare/icis_j3/utils/Consts.java
```

新增一组归档文档类型常量，至少包含：

```java
public static final Integer PATIENT_ARCHIVE_TYPE_NURSING_REPORT_COMPACT = 1;
```

命名要求：

1. 常量名称需要表达“患者归档文档类型”。
2. `1` 固定表示 compact 重症护理单。
3. 后续新增其他文档类型时继续在同一组常量中扩展。

## Proto 需求

新增文件：

```text
jingyi_icis_engine2/src/main/proto/config/icis_archive.proto
```

包名沿用现有 config proto：

```proto
syntax = "proto3";

import "config/icis_jfk.proto";

package com.jingyicare.jingyi_icis_engine.proto.config;
```

定义 compact 重症护理单归档 PB：

```proto
message NursingReportCompactArchivePB {
    repeated JfkDataSourcePB sources = 1;

    int64 pid = 2;
    int32 type = 3;
    string name = 4;
    string query_start_iso8601 = 5;
    string query_midnight_utc_iso8601 = 6;
    string generated_at_iso8601 = 7;
    int32 template_id = 8;
    string template_name = 9;
    string relative_url = 10;
}
```

`sources` 内容要求：

1. 来源是 compact 报表本次实际查询成功的 `JfkDataSourcePB` 输出。
2. 应包含 `meta_id` 和 `output_data`。
3. 如现有 handler 输出中包含 `input_data`，默认保留，便于排查 table-scoped 数据源的 `table_id`、列宽等输入口径。
4. `id` 不置空，保留现有 `JfkDataSourcePB.id`；table-scoped 数据源需要依赖该字段区分不同表格。

元信息要求：

1. `pid`、`type` 与 `patient_archives` 表一致。
2. `name` 用于归档文件名，例如重症护理单。
3. `query_start_iso8601` 使用报表请求的 `request.queryStartUtc`。
4. `query_midnight_utc_iso8601` 使用 `queryStartUtc` 换算后的本地业务日 0 点 UTC。
5. `generated_at_iso8601` 使用归档生成时间 UTC。
6. `template_id` 和 `template_name` 来自 compact 报表模板；取不到时 `template_id` 保持 `0`，`template_name` 留空。
7. `relative_url` 与 `patient_archives.relative_url` 一致，必须以 `/archives/` 开头。

## 配置和静态访问需求

在：

```text
jingyi_icis_engine2/src/main/resources/application.properties
```

的无纸化区域新增 archives 文档文件根目录配置：

```properties
# 无纸化 archives
jingyi.archives.root-path=./archives/
```

访问路径要求：

```text
http://localhost:8080/archives/...
```

安全要求：

1. `/archives/**` 不需要登录即可访问。
2. `SecurityConfig` 需要对 servlet path 以 `/archives/` 开头的请求 `permitAll()`。
3. 新增专门的 `WebMvcConfigurer` resource handler，把 URL `/archives/**` 映射到 `jingyi.archives.root-path`。
4. 不能扩大到所有本地文件免鉴权访问。

## ArchiveService 需求

新增目录：

```text
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/archives
```

新增接口：

```text
ArchiveService.java
```

核心方法：

```java
boolean store(
    long pid,
    int type,
        LocalDateTime effectiveTimeUtc,
        String name,
        String pbStr,
        int pageCount,
        Path sourcePdfPath
);
```

参数语义：

| 参数 | 语义 |
| --- | --- |
| `pid` | 患者记录 ID |
| `type` | 文档类型 |
| `effectiveTimeUtc` | 用于计算业务日期的 UTC 时间 |
| `name` | 文档名称，用于归档文件名 |
| `pbStr` | 归档 PB 的 Base64 字符串 |
| `pageCount` | 已生成 PDF 的页数 |
| `sourcePdfPath` | 已生成成功的报表 PDF 路径 |

`ArchiveService.store` 负责把 `sourcePdfPath` 对应的 PDF 复制到 archives 根目录，并计算 `relative_url`。

归档文件路径：

```text
${jingyi.archives.root-path}/${pid}/${pid}_${type}_${yyyymmddhhmm}_${name}.pdf
```

文件名规则：

1. `yyyymmddhhmm` 使用 `queryStartUtc` 转为本地时间后取当天 0 点得到的 `queryMidnightLocal`，格式为 `yyyyMMddHHmm`。
2. `name` 需要做文件名安全处理，避免路径分隔符、空白尾缀或操作系统不支持的字符。
3. 目标文件名相同时允许覆盖。
4. 数据库 `relative_url` 保存带前导斜杠的 URL：

```text
/archives/${pid}/${pid}_${type}_${yyyymmddhhmm}_${name}.pdf
```

返回值语义：

| 场景 | 行为 | 返回 |
| --- | --- | --- |
| 唯一键下无有效记录 | 复制 PDF，新增有效归档记录 | `false` |
| 唯一键下已有有效记录，且 `data_pb` 与 `pbStr` 相同、`relative_url` 也相同 | 不新增、不软删除，视为幂等命中 | `true` |
| 唯一键下已有有效记录，且 `data_pb` 与 `pbStr` 相同、`relative_url` 不同 | 事务前调用三方无纸化接口通知，复制 PDF，更新当前有效记录的 `relative_url`、`modified_at`、`modified_by` | `true` |
| 唯一键下已有有效记录，且 `data_pb` 与 `pbStr` 不同 | 事务前调用三方无纸化接口通知，复制 PDF，软删除旧记录，新增新记录 | `false` |

覆盖要求：

1. 覆盖旧归档或更新 URL 前先预留 TODO：调用无纸化系统接口通知旧文档将被覆盖或 URL 将变更。
   - 该 TODO 的调用时机是数据库事务前。
2. 旧记录只做软删除：
   - `is_deleted = true`
   - `deleted_at = TimeUtils.getNowUtc()`
   - `deleted_by = 当前登录用户`
3. 新记录写入：
   - `pid`
   - `type`
   - `local_midnight_utc`
   - `page_count = pageCount`
   - `data_pb = pbStr`
   - `relative_url = /archives/...`
   - `is_deleted = false`
   - `modified_at = TimeUtils.getNowUtc()`
   - `modified_by = 当前登录用户`
4. 方法应放在事务内，避免同一唯一键并发插入多个有效记录。
5. 判断归档是否“完全相同”时同时比较归档业务数据、`page_count` 和 `relative_url`。
   - compact 归档比较业务数据时忽略 `generated_at_iso8601`。
   - compact 归档比较业务数据时也忽略 PB 内的 `relative_url`，因为 URL 已由 `patient_archives.relative_url` 单独比较。
   - 否则每次报表生成都会因为生成时间变化而误判为数据变化。
6. 文件名相同时允许覆盖文件；返回值按上表规则处理。
7. 归档记录的 `modified_by`、`deleted_by` 使用当前登录用户。

## compact 报表归档需求

涉及目录：

```text
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport
```

归档触发点：

1. 每次 compact 重症护理单获取报表时触发。
2. JFK 数据源全部查询成功后组装归档数据。
3. PDF 渲染成功后再调用 `ArchiveService.store(...)`。
4. 归档写入失败时只记录错误，不影响原报表返回。

数据采集要求：

1. 使用 `CompactReportService.loadDataSources(...)` 本次返回的 `outputList`。
2. 把每个实际查询成功的 `JfkDataSourcePB` 放入 `NursingReportCompactArchivePB.sources`。
3. 包含 table-scoped 数据源，例如：
   - `patient_monitoring_records`
   - `patient_bga_records`
   - `patient_balance_records`
   - `medexe_records`
   - `patient_tube_records`
   - `patient_nursing_records`
   - `patient_non_hourly_monitoring_records`
   - `patient_nursing_orders`
   - `patient_skincare_records`
4. 普通非 table-scoped 数据源也应归档，例如患者信息类数据源。
5. 查询失败时不写入归档，沿用现有报表错误返回。

归档写入要求：

1. 构建 `NursingReportCompactArchivePB`。
2. 通过 `ProtoUtils` 新增方法序列化为 Base64 字符串。
3. PDF 渲染成功后，调用 `ArchiveService.store(...)`。
4. `type` 使用 compact 重症护理单文档类型常量 `1`。
5. `effectiveTimeUtc` 使用 `request.queryStartUtc` 转换得到的 `queryMidnightUtc`。
6. `ArchiveService.store(...)` 将 PDF 复制到 archives 根目录下，并生成带前导斜杠的 `relative_url`。

归档业务日期计算要求：

```text
queryStartUtc -> queryStartLocal -> queryMidnightLocal -> queryMidnightUtc
```

具体规则：

1. `queryStartLocal = TimeUtils.getLocalDateTimeFromUtc(request.queryStartUtc, zoneId)`。
2. `queryMidnightLocal = queryStartLocal.withHour(0).withMinute(0).withSecond(0).withNano(0)`。
3. `queryMidnightUtc = TimeUtils.getUtcFromLocalDateTime(queryMidnightLocal, zoneId)`。
4. 可在 `TimeUtils` 中新增工具函数封装该逻辑。

报表失败口径：

1. JFK 数据源查询失败：不写入归档，沿用现有报表错误返回。
2. PDF 渲染失败：不写入归档，沿用现有报表错误返回。
3. PDF 渲染成功但归档失败：记录 `log.error`，原报表仍正常返回。

## ProtoUtils 需求

在：

```text
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/utils/ProtoUtils.java
```

新增针对 `NursingReportCompactArchivePB` 的 Base64 序列化方法：

```java
static public String encodeNursingReportCompactArchive(NursingReportCompactArchivePB pb)
```

建议同时新增解码方法，便于测试和排查：

```java
static public NursingReportCompactArchivePB decodeNursingReportCompactArchive(String base64)
```

序列化要求：

1. 使用 `pb.toByteArray()` 后 Base64 编码。
2. 不使用 text format 或 JSON 作为 `data_pb` 的持久化格式。
3. 解码失败时记录日志并返回 `null`，风格与现有 `ProtoUtils.decodeXxx` 方法保持一致。

## 验收标准

1. 需求确认后，数据库 schema 中存在 `patient_archives` 表和部分唯一索引。
2. `patient_archives` 在同一 `pid + type + local_midnight_utc` 下最多只有一条 `is_deleted = false` 的有效记录。
3. `icis_archive.proto` 中存在 `NursingReportCompactArchivePB`，并能引用 `JfkDataSourcePB`。
4. `ProtoUtils` 能把 `NursingReportCompactArchivePB` 编码为 Base64，并能解码回等价 PB。
5. `/archives/...` 可以匿名访问，其他受保护接口不受影响。
6. `ArchiveService.store` 在 `pbStr` 相同时返回 `true` 且不新增记录。
7. `ArchiveService.store` 在 `pbStr` 相同但 URL 不同时更新 URL、返回 `true`。
8. `ArchiveService.store` 在新增或 `pbStr` 不同时返回 `false`。
9. 覆盖时旧记录被软删除，新记录成为唯一有效记录。
10. `ArchiveService.store` 会把 PDF 复制到 archives 根目录，并生成 `/archives/...` 相对 URL。
11. compact 报表生成时，归档 PB 包含本次实际查询成功的 JFK 数据源输出和元信息。
12. compact 报表数据源查询失败或 PDF 渲染失败时不写入归档。
13. PDF 渲染成功但归档失败时，记录错误且不影响报表返回。

## 建议测试

1. `ArchiveService` 单元测试：
   - 首次存储返回 `false`。
   - 相同 `pbStr` 再次存储返回 `true`。
   - 相同 `pbStr` 但 URL 不同时，更新 URL 并返回 `true`。
   - 不同 `pbStr` 再次存储返回 `false`。
   - PDF 被复制到 `${pid}/${pid}_${type}_${yyyymmddhhmm}_${name}.pdf`。
   - 覆盖后旧记录 `is_deleted = true`，新记录 `is_deleted = false`。
   - `effectiveTimeUtc` 能正确换算为配置时区下的 `local_midnight_utc`。
2. 数据库约束测试：
   - 同一唯一键不能存在两条有效记录。
   - 软删除旧记录后允许插入新有效记录。
3. Proto 测试：
   - `NursingReportCompactArchivePB` 能存放多个 `JfkDataSourcePB`。
   - Base64 编码和解码后内容一致。
4. compact report 测试：
   - mock 多个 JFK 数据源输出后，归档 PB 的 `sources` 数量和顺序符合预期。
   - table-scoped 数据源不会丢失区分表格所需的信息。
   - 归档 PB 包含 `pid`、`type`、业务日期、生成时间、模板信息和 `relative_url`。
   - 数据源失败时不会调用 `ArchiveService.store`。
   - PDF 渲染失败时不会调用 `ArchiveService.store`。
   - `ArchiveService.store` 抛错时，报表接口仍返回已生成的报表。
5. 静态访问测试：
   - `/archives/test.pdf` 免登录可访问。
   - `/api/...` 仍需要登录。

## 已决策

1. `patient_archives.id` 使用 PostgreSQL `SERIAL`。
2. 原始字段名中的 `modifiec_by` 确认为笔误，统一使用现有 schema 命名 `modified_by`。
3. `ArchiveService.store` 的 `modified_by`、`deleted_by` 使用当前登录用户。
4. `ArchiveService.store` 判断归档是否完全相同时，同时比较归档业务数据、`pageCount` 和 `relativeUrl`；compact 归档比较业务数据时忽略 `generated_at_iso8601` 和 PB 内的 `relative_url`。
5. 当 `pbStr` 相同但 `relativeUrl` 不同时，更新 URL，调用三方无纸化接口，返回 `true`。
6. compact 报表归档业务日期使用 `request.queryStartUtc` 计算：先转 `queryStartLocal`，再取本地 0 点得到 `queryMidnightLocal`，最后转 UTC 得到 `queryMidnightUtc`。可考虑在 `TimeUtils` 中新增工具函数。
7. 归档写入发生在 PDF 渲染成功之后。
8. PDF 渲染成功后，归档服务把 PDF 复制一份到 archives 根目录下，路径为 `${pid}/${pid}_${type}_${yyyymmddhhmm}_${name}.pdf`。
9. 归档 PDF 文件复制完成后，再把归档 PB、URL 等数据写入 `patient_archives`。
10. 归档写入失败时只记录错误，报表获取接口仍返回已生成的报表。
11. PDF 文件由 `ArchiveService.store` 写入；`store` 参数可以调整，以接收文档名称和已生成 PDF 路径。
12. `relative_url` 保存带前导斜杠的 `/archives/...`。
13. archives 根目录配置项使用 `jingyi.archives.root-path`。
14. `/archives/**` 到本地 archives 根目录的映射新增专门的 `WebMvcConfigurer` resource handler。
15. `NursingReportCompactArchivePB.sources[].id` 不置空，保留原始数据源实例 ID。
16. `NursingReportCompactArchivePB` 需要包含除 `sources` 外的元信息，例如 `pid`、业务日期、生成时间、模板信息和 `relative_url`。
17. `NursingReportCompactArchivePB.sources[]` 保留 `input_data`。
18. `icis_j3` 中新增的文档类型常量不需要在 `jingyi_icis_engine2` 中同步定义。
19. 覆盖旧归档时，三方无纸化通知 TODO 的调用时机是数据库事务前。
20. 历史软删除归档永久保留，本需求不设计清理策略。

## 待决策

暂无。
