# 护理记录审核功能需求

## 背景

护理记录表 `nursing_records` 已具备审核相关字段：

| 字段 | 用途 |
| --- | --- |
| `reviewed_by` | 审核人 account_id |
| `reviewed_by_account_name` | 审核人姓名 |
| `reviewed_at` | 审核时间 |

`icis_web_api.proto` 中 `PatientNursingRecordValPB` 也已经定义：

```proto
string reviewed_by = 6;
string reviewed_by_account_name = 7;
string reviewed_at_iso8601 = 8;
```

但当前护理记录查询未把审核字段返回给前端，前端护理记录页面中的审核相关代码处于注释状态。本需求目标是参考血气审核接口 `/api/bga/reviewpatientbgarecords`，补齐护理记录的批量审核能力，并优化护理记录页中的审核入口和勾选交互。

## 目标

1. 新增后端接口 `/api/nursingrecord/reviewnursingrecords`，支持批量审核护理记录。
2. 审核时使用当前登录用户覆盖写入 `nursing_records.reviewed_by` 和 `nursing_records.reviewed_by_account_name`，使用请求时间写入 `nursing_records.reviewed_at`。
3. `getnursingrecord` 返回每条护理记录的审核人和审核时间。
4. 前端护理记录页新增批量“审核”按钮，以及每行“审核勾选”列。
5. 已审核记录再次被勾选并审核时，允许覆盖原审核人和审核时间。
6. 精简并美化护理记录页审核相关布局与交互。

## 非目标

1. 本需求不改护理记录新增、编辑、删除的既有业务规则。
2. 本需求不新增数据库字段或迁移脚本。
3. 本需求不改变危急值同步到护理记录的流程。
4. 本需求不实现反审核、取消审核或审核日志留痕；审核动作只记录 `log.info`。
5. 本需求不新增护理记录审核权限点，不改按钮权限、菜单权限或接口白名单配置。
6. 本需求整理阶段不做代码实现。

## 已确认决策

1. 护理记录审核本次不新增权限点，复用现有登录用户。
2. 护理记录编辑后不保留原审核信息，编辑成功后清空审核人和审核时间。
3. 允许记录人审核自己创建的护理记录。
4. 允许不同患者或不同时间范围的护理记录 ID 混在同一次审核请求中；后端只按 ID 和删除状态校验。
5. 审核时间由前端传入 `reviewTimeIso8601`，后端解析后保存。
6. 未审核记录前端展示留空，不显示“未审核”。
7. 不新增按钮权限、菜单权限或接口白名单配置。
8. 后端审核操作不新增审计日志表；用 `log.info` 记录审核动作即可。
9. 前端不支持表头全选/批量取消，只保留逐行 checkbox。
10. 请求 ID 为空时前端禁止提交；后端防御行为沿用 BGA，返回 `OK` 并 no-op。

## 后端需求

### Proto

在 `jingyi_icis_engine2/src/main/proto/icis_web_api.proto` 的护理记录接口区新增：

```proto
/*
 * /api/nursingrecord/reviewnursingrecords
 *  input: ReviewNursingRecordsReq
 *  output: GenericResp
 */
message ReviewNursingRecordsReq {
    repeated int64 id = 1; // 护理记录ID列表
    string review_time_iso8601 = 2; // 审核时间 (ISO 8601 UTC)
}
```

命名建议：

1. HTTP 路径固定为 `/api/nursingrecord/reviewnursingrecords`。
2. Java service 方法名建议为 `reviewNursingRecords`。
3. 前端生成类型预期为 `ReviewNursingRecordsReq`，字段为 `id` 和 `reviewTimeIso8601`。

### Service

在 `jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/nursingrecords/NursingRecordService.java` 中实现：

```text
GenericResp reviewNursingRecords(String reviewNursingRecordsReqJson)
```

行为参考：

```text
jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/monitorings/BgaService.java
reviewPatientBgaRecords
```

处理规则：

1. 解析 `ReviewNursingRecordsReq`；解析失败返回 `PARSE_JSON_FAILED`。
2. 通过 `userService.getAccountWithAutoId()` 获取当前账号；失败返回 `ACCOUNT_NOT_FOUND`。
3. 解析 `review_time_iso8601`；失败返回 `INVALID_TIME_FORMAT`。
4. 对请求中的 `id` 去重。
5. 查询 `id in (...) and is_deleted = false` 的护理记录。
6. 若查询结果数量与去重后的 ID 数量不一致，返回 `NURSING_RECORD_NOT_EXISTS`。
7. 对所有记录写入：
   - `reviewed_by = 当前账号 account_id`
   - `reviewed_by_account_name = 当前账号姓名`
   - `reviewed_at = review_time_iso8601` 解析后的 UTC 时间
8. 保存后 `log.info` 记录审核动作，至少包含审核账号和审核记录 ID 列表。
9. 返回 `OK`。

Repository 需要补充批量查询方法：

```text
List<NursingRecord> findByIdInAndIsDeletedFalse(List<Long> ids)
```

### 查询返回

`getNursingRecord` 映射 `PatientNursingRecordValPB` 时需要补齐：

1. `reviewed_by`
2. `reviewed_by_account_name`
3. `reviewed_at_iso8601`

时间格式建议沿用现有护理记录时间返回口径：

```text
TimeUtils.toIso8601String(record.getReviewedAt(), ZONE_ID)
```

当记录未审核时，审核字段保持空值，不影响前端展示。

### 编辑后清空审核

护理记录编辑成功后，原审核信息作废。`NursingRecordUtils.updateNursingRecord` 当前保留审核字段，执行时需要改为清空：

```text
reviewed_by = null
reviewed_by_account_name = null
reviewed_at = null
```

清空发生在护理记录内容或记录时间被更新时。若更新失败，不应改变原审核信息。

### Controller 和 WebApiService

在 `IcisController` 护理记录接口区域新增：

```text
POST /api/nursingrecord/reviewnursingrecords
```

调用链：

```text
IcisController.reviewNursingRecords
-> WebApiService.reviewNursingRecords
-> NursingRecordService.reviewNursingRecords
```

`WebApiService.reviewNursingRecords` 需要和其他接口一致，调用 `metricService.recordApiMetrics(resp, GenericResp::getRt)`。

### 权限和状态码

本需求沿用现有护理记录接口的登录态/账号获取方式，不在后端新增专门权限判断，不新增按钮权限、菜单权限或接口白名单配置。

## 前端需求

目标文件：

```text
jingyi_icis_frontend/src/pages/home/tabs/nurses/NursingRecord/index.tsx
```

相关 API 文件：

```text
jingyi_icis_frontend/src/api/WebApi.ts
jingyi_icis_frontend/src/store/slices/NursingApi.ts
```

### API 接入

生成或补充：

1. `API_ENDPOINTS.REVIEW_NURSING_RECORDS = '/api/nursingrecord/reviewnursingrecords'`
2. `ReviewNursingRecordsReq`
3. `reviewNursingRecords` thunk

thunk 风格参考 `BgaApi.reviewPatientBgaRecords`。

### 页面布局

在护理记录页顶部工具栏中，在“新增”按钮旁边增加“审核”按钮。

建议布局：

1. 日期时间选择 + “新增”保持为一个紧凑按钮组。
2. “审核”作为同级主按钮放在新增按钮右侧。
3. 未选择任何记录时，“审核”按钮置灰。
4. 选择至少一条记录后，“审核”按钮亮起。
5. 审核请求执行中展示 loading，避免重复提交。

### 表格列

现有列：

1. 护理记录
2. 记录人

新增一列：

```text
审核勾选
```

建议列宽：

1. 护理记录：约 70% - 75%
2. 记录人/审核人：约 15% - 20%
3. 审核勾选：约 8% - 10%

“记录人”列需要展示：

1. 记录人姓名
2. 记录时间
3. 审核人姓名
4. 审核时间（若有）

推荐文案：

```text
记录人：张三
记录时间：2026-04-18 09:00
审核人：李四
审核时间：2026-04-18 10:00
```

未审核时，审核人和审核时间区域留空，不显示“未审核”。

“审核勾选”列每行展示一个 checkbox。用户勾选后，该记录 ID 加入待审核列表；取消勾选后移出。

### 审核交互

点击顶部“审核”按钮后：

1. 若未选择记录，按钮应已置灰；不发请求。
2. 弹出确认框，提示当前将由当前登录用户审核已选中的护理记录。
3. 确认后调用 `reviewNursingRecords`。
4. 请求体：

```json
{
  "id": ["1001", "1002"],
  "reviewTimeIso8601": "2026-04-18T10:30:00+08:00"
}
```

5. 成功后清空勾选状态，刷新护理记录列表，并提示“审核成功”。
6. 失败时保留勾选状态，展示错误提示。

### 覆盖审核规则

若某条护理记录已经存在 `reviewed_by_account_name`，再次勾选并审核时：

1. 后端直接覆盖审核人和审核时间。
2. 前端不禁止勾选已审核记录。
3. 前端可在确认框中提示“已审核记录将更新审核人和审核时间”。

### 视觉和交互优化

本需求要求精简美化护理记录页审核相关布局，建议控制在以下范围内：

1. 顶部工具栏操作按钮保持一行对齐，减少被注释的旧审核入口。
2. 行内操作按钮仅保留编辑态需要的动作，不再恢复单行“审核”按钮。
3. 审核 checkbox 居中展示。
4. 记录人/审核人信息使用紧凑的纵向排版，避免挤占护理记录正文。
5. 表格列宽稳定，勾选列内容变化不应导致布局跳动。
6. 不使用 `Table.rowSelection` 的表头全选能力，避免出现批量全选/取消入口。

## 验收标准

### 后端

1. `icis_web_api.proto` 中存在 `/api/nursingrecord/reviewnursingrecords` 注释和 `ReviewNursingRecordsReq`。
2. `IcisController`、`WebApiService`、`NursingRecordService` 调用链完整。
3. 审核接口能批量更新 `nursing_records.reviewed_by`、`reviewed_by_account_name`、`reviewed_at`。
4. 请求中存在不存在或已删除的 ID 时，返回 `NURSING_RECORD_NOT_EXISTS`，不做部分成功更新。
5. `getnursingrecord` 返回审核人和审核时间。
6. JSON 解析失败、账号缺失、审核时间格式错误分别返回对应状态码。
7. 编辑护理记录成功后，清空该记录的审核人和审核时间。
8. 审核成功时有 `log.info` 记录审核账号和记录 ID。

### 前端

1. 护理记录页“新增”按钮旁显示“审核”按钮。
2. 未勾选记录时，“审核”按钮不可点击。
3. 勾选任意记录后，“审核”按钮可点击。
4. 表格存在“审核勾选”列，每行 checkbox 可独立勾选/取消。
5. 记录人区域能显示审核人；未审核记录的审核区域留空。
6. 审核成功后清空勾选并刷新列表，审核人更新为当前用户。
7. 已审核记录再次审核后，审核人和审核时间被覆盖。
8. 页面不提供表头全选/批量取消入口。

## 建议测试

后端：

1. 单条护理记录审核成功。
2. 多条护理记录审核成功。
3. 重复 ID 请求只更新一次。
4. 已删除或不存在 ID 返回 `NURSING_RECORD_NOT_EXISTS`。
5. 空 `review_time_iso8601` 或非法时间返回 `INVALID_TIME_FORMAT`。
6. 未登录/账号缺失返回 `ACCOUNT_NOT_FOUND`。
7. `getnursingrecord` 能返回审核字段。
8. 已审核记录被编辑后，审核字段被清空。
9. 审核成功后日志中包含审核账号和记录 ID。

前端：

1. 无勾选时审核按钮置灰。
2. 勾选后审核按钮启用。
3. 审核确认弹窗文案正确。
4. 审核成功后列表刷新，checkbox 清空。
5. 已审核记录再次审核，展示新的审核人。
6. 编辑护理记录内容时，审核勾选列不影响编辑态按钮和文本框操作。
7. 未审核记录不展示“未审核”文案。
8. 表格不出现表头全选 checkbox。

## 执行前仍需确认的问题

暂无。
