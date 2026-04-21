# 同步 HIS 护理医嘱需求

## 背景

当前护理医嘱由 `NursingOrderService` 通过护理医嘱模板手工新增，落库到 `nursing_orders`，并在 `/api/nursingorder/getnursingorderdetails` 中按护理医嘱生成 `nursing_execution_records`。

需要参考 `src/main/java/com/jingyicare/jingyi_icis_engine/service/medications/OrderGroupGenerator.java` 的模式，在 `src/main/java/com/jingyicare/jingyi_icis_engine/service/nursingorders` 下新增从 HIS 中间表 `medical_orders` 同步护理医嘱的功能。

同步范围由护理医嘱配置控制：

```java
ConfigProtoService.getConfig()
    .getNursingOrder()
    .getAllowedOrderTypeList()
```

配置结构来自 `src/main/proto/config/icis_nursing_order.proto`：

```proto
message MedicalOrderTypeNamePB {
    string type = 1;
    repeated string name = 2;
}

message NursingOrderConfigPB {
    repeated NursingOrderTemplateGroupPB default_dept_group = 2;
    repeated MedicalOrderTypeNamePB allowed_order_type = 3;
}
```

## 已确认决策

1. `allowed_order_type` 使用精确匹配：`medical_orders.order_type` 必须完全等于配置 `type`，`medical_orders.order_name` 必须完全等于同一配置项下的某个 `name`。
2. `medical_orders.order_type` 会稳定等于配置中的值，例如 `诊疗`、`文本`。
3. 一条 `medical_orders` 对应一条 `nursing_orders`，不按 `group_id` 合并。
4. 同意新增 `nursing_orders.medical_order_id` 等 HIS 来源字段，用于幂等同步和来源识别。
5. `nursing_orders.order_template_id` 确认改为可空。
6. HIS 同步护理医嘱在 `getNursingOrderDetails` 中显示在一个虚拟组，组名固定为 `His护嘱`。
7. `His护嘱` 虚拟组置顶显示。
8. 前端当前可以兼容无模板医嘱返回 `orderTemplateId = 0`，不需要为了本需求把 `NursingOrderPB.order_template_id` 改成 `optional`。
9. `medical_orders.freq_code` 为空时过滤该医嘱。
10. `medical_orders.freq_code` 存在但查不到 `medication_frequencies` 时，按 once 处理。
11. 频次存在但 `support_nursing_order = false` 时，记录错误日志并返回 `NURSING_ORDER_FREQUENCY_NOT_EXISTS`。
12. `order_by` 对前端展示医生姓名；当前手工护理医嘱仍保持写入账号 ID，返回前需要做展示转换。
13. `medical_orders.dept_id` 与 `patient_records.dept_id` 不一致时过滤该 HIS 医嘱。
14. `medical_orders.cancel_time` 或 HIS 取消状态不新增护理医嘱取消状态字段，仅停止生成后续执行记录。
15. 手工删除过的 HIS 同步护理医嘱，下次同步不恢复。
16. 同步只处理 ICU 患者。
17. HIS 同步护理医嘱不允许用户在前端“已有护嘱”里手工停止或删除；前端隐藏操作，后端也要拦截。
18. `/api/nursingorder/getnursingorders` 也需要触发 HIS 同步，保证“开立护嘱”弹窗里的已有护嘱列表包含 HIS 护嘱。
19. `stop_by` 对 HIS 停止医嘱使用医生姓名。
20. `note` 需要追加 `medical_orders.order_id/group_id`，方便排查同步来源。
21. 同步写入 `modified_by` 使用当前接口账号。
22. 单元测试放在 `src/test/java/com/jingyicare/jingyi_icis_engine/service/nursingorders` 下。
23. 同步代码尽量放在独立文件，例如 `NursingOrderSyncService`；`NursingOrderService` 只负责调用和接入。
24. HIS 取消状态的文字来源复用 `ConfigProtoService.getConfig().getMedication().getOrderGroupSettings().getStatusCanceledTxt()`。
25. 取消或停止后，已经生成的未来 `nursing_execution_records` 不需要软删除，只阻止后续新生成。
26. 后端拦截 HIS 护嘱手工停止/删除时新增一个 `StatusCode`。
27. 前端识别 HIS 同步护理医嘱使用 `orderTemplateId === 0` 已足够，不需要新增 `NursingOrderPB.source` 或 `is_his_synced`。

## 目标

1. 新增护理医嘱同步服务，从 `medical_orders` 查询患者对应 HIS 医嘱。
2. 按 `NursingOrderConfigPB.allowed_order_type` 精确白名单过滤 `medical_orders.order_type` 和 `medical_orders.order_name`。
3. 将符合条件的 `medical_orders` 同步为 `nursing_orders`。
4. `/api/nursingorder/getnursingorderdetails` 和 `/api/nursingorder/getnursingorders` 查询时先完成同步，再按现有流程获取对应 `nursing_orders`。
5. 支持由 HIS 同步而来的护理医嘱不依赖手工护理医嘱模板。
6. 前端护理医嘱页保持现有返回结构和交互逻辑，后端用置顶 `His护嘱` 虚拟组承载 HIS 同步护理医嘱。
7. `nursing_execution_records` 新增 `note` 备注字段，并支持前端在“执行记录编辑”弹窗执行时录入“护理说明”。

本需求仅整理实现目标、确认决策与待确认问题。

## 建议实现位置

新增独立服务类：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/nursingorders/NursingOrderSyncService.java
```

建议职责：

1. 读取护理医嘱白名单配置。
2. 校验患者是否 ICU 在院。
3. 查询并过滤 `medical_orders`。
4. 将 HIS 护理医嘱转换为 `NursingOrder`。
5. 幂等写入或更新 HIS 同步来源的 `nursing_orders`。
6. 跳过手工删除过的 HIS 同步护理医嘱，确保下次同步不恢复。
7. 返回同步结果，供调用方决定继续查询或返回错误。

`NursingOrderService` 建议只做最小接入：

1. 注入 `NursingOrderSyncService`。
2. 在 `getNursingOrderDetails(...)` 查询患者成功后、查询 `nursing_orders` 前调用同步。
3. 在 `getNursingOrders(...)` 查询患者成功后、查询 `nursing_orders` 前调用同步。
4. 在 `stopNursingOrder(...)` 和 `deleteNursingOrder(...)` 中拦截 HIS 同步护理医嘱的手工停删。

## 数据来源

查询条件参考药品医嘱生成器：

```text
medical_orders.his_patient_id = patient_records.his_patient_id
medical_orders.order_time >= patient_records.admission_time
```

可复用或扩展：

```java
MedicalOrderRepository.findByHisPatientIdAndAdmissionTime(...)
```

患者来源：

1. `getNursingOrderDetails` 和 `getNursingOrders` 都先根据 `pid` 查询 `PatientRecord`。
2. 同步服务接收 `PatientRecord patient` 和当前接口账号 `accountId` 作为入参，避免重复解析请求。

额外过滤：

1. 只处理 ICU 患者；非 ICU 患者不触发 HIS 同步。
2. `medical_orders.dept_id` 与 `patient_records.dept_id` 不一致时过滤，并记录 `warn` 日志。

## 白名单过滤

白名单配置示例：

```text
allowed_order_type {
    type: "诊疗"
    name: "机械深度排痰"
    name: "气压治疗"
}
allowed_order_type {
    type: "文本"
    name: "保护约束"
}
```

过滤规则：

1. `medical_orders.order_type` 必须完全等于 `allowed_order_type.type`。
2. `medical_orders.order_name` 必须完全等于同一个 `allowed_order_type` 下的某个 `name`。
3. `order_type` 或 `order_name` 为空时过滤掉并记录日志。
4. 多个 `allowed_order_type` 命中时以配置顺序的第一个为准；正常情况下不应出现多个命中。
5. 不做 `contains`、拼音、别名、去括号、去规格等模糊匹配。

## 字段映射

建议初始映射如下：

| `nursing_orders` 字段 | 来源 | 说明 |
| --- | --- | --- |
| `pid` | `patient_records.id` | 当前 ICIS 患者 ID |
| `dept_id` | `patient_records.dept_id` | `medical_orders.dept_id` 不一致则过滤 |
| `order_template_id` | `null` | HIS 同步护理医嘱不绑定模板 |
| `name` | `medical_orders.order_name` | 护理医嘱名称，白名单精确匹配后写入 |
| `duration_type` | `medical_orders.order_duration_type` 转换 | 见“长临映射” |
| `medication_freq_code` | `medical_orders.freq_code` | 为空过滤；查不到频次时仍写入并按 once 执行 |
| `order_by` | `medical_orders.ordering_doctor` | 前端期望展示医生姓名 |
| `order_time` | `medical_orders.order_time` | 建议去掉秒和纳秒，保持与药品医嘱聚合一致 |
| `stop_by` | `medical_orders.ordering_doctor` | HIS 停止医嘱使用医生姓名 |
| `stop_time` | `medical_orders.stop_time` 或取消时间 | 用于停止生成后续执行记录 |
| `note` | `medical_orders.medication_note` + HIS 来源信息 | 追加 `order_id/group_id` |
| `is_deleted` | `false` | 手工删除后保持删除，不恢复 |
| `modified_by` | 当前接口账号 | 来自 `UserService.getAccountWithAutoId()` |
| `modified_at` | 当前 UTC 时间 | `TimeUtils.getNowUtc()` |
| `medical_order_id` | `medical_orders.order_id` | HIS 医嘱唯一标识 |
| `medical_order_group_id` | `medical_orders.group_id` | 仅记录来源，不用于合并 |
| `source` | `"medical_orders"` | 标识 HIS 同步来源 |
| `synced_at` | 当前 UTC 时间 | 最近同步时间 |

`note` 追加格式建议：

```text
{原 medication_note}
HIS医嘱ID: {medical_orders.order_id}; HIS分组ID: {medical_orders.group_id}
```

## 长临映射

需要同时尊重两个语义：

1. `medical_orders.order_duration_type` 使用药品配置枚举，当前配置中 `order_duration_type_long_term.id = 0`，`order_duration_type_one_time.id = 1`。
2. `nursing_orders.duration_type` 表结构注释为 `0: 临时护嘱 1: 长期护嘱`。

因此同步映射为：

| `medical_orders.order_duration_type` | 药品语义 | `nursing_orders.duration_type` | 护理语义 |
| --- | --- | --- | --- |
| `0` | 长期 | `1` | 长期护嘱 |
| `1` | 临时 | `0` | 临时护嘱 |

其他值，例如补录或空值，建议过滤并记录日志。

## 频次规则

处理顺序：

1. `medical_orders.freq_code` 为空：过滤该 HIS 医嘱，记录 `warn`。
2. 根据 `freq_code` 查询 `medication_frequencies`。
3. 频次查不到：不返回错误，仍写入 `nursing_orders.medication_freq_code`，执行记录生成按 once 处理。
4. 频次存在但 `support_nursing_order = false`：记录错误日志并返回 `NURSING_ORDER_FREQUENCY_NOT_EXISTS`。
5. 频次存在且支持护理医嘱：写入 `nursing_orders.medication_freq_code`。
6. `MedicationConfig.getMedicationFrequencySpec(freq_code)` 返回空时，按 once 生成执行记录。

## 取消与停止规则

不新增护理医嘱取消状态字段。

处理规则：

1. `medical_orders.stop_time` 非空：同步到 `nursing_orders.stop_time`，`stop_by` 使用 `medical_orders.ordering_doctor`。
2. `medical_orders.cancel_time` 非空：同步到 `nursing_orders.stop_time`，`stop_by` 使用 `medical_orders.ordering_doctor`。
3. HIS 取消状态命中但 `cancel_time` 为空：`nursing_orders.stop_time` 使用当前同步时间，`stop_by` 使用 `medical_orders.ordering_doctor`。
4. 取消或停止都不软删除 `nursing_orders`，只通过 `stop_time` 阻止后续执行记录生成。
5. 已经存在的未来 `nursing_execution_records` 不删除、不软删除；只阻止后续新生成。

取消状态识别复用药品配置：

```java
ConfigProtoService.getConfig()
    .getMedication()
    .getOrderGroupSettings()
    .getStatusCanceledTxt()
```

## 状态码调整

后端拦截 HIS 同步护理医嘱手工停止或删除时，需要新增一个业务状态码。

建议 enum 命名：

```proto
NURSING_ORDER_HIS_SYNCED_NOT_EDITABLE = 284;  // HIS同步护嘱不允许手工停止或删除
LAST_CODE = 285;
```

需要同步修改：

```text
src/main/proto/icis_web_api.proto:36-332
src/main/resources/config/pbtxt/icis_config.pb.txt:2-286
src/test/resources/text_resources/icis_config.pb.txt:2-286
```

配置文案建议：

```text
status_code_msg: "HIS同步护嘱不允许手工停止或删除"
```

要求：

1. 在 `StatusCode` 中将新状态码插入到当前 `LAST_CODE = 284` 之前。
2. 将 `LAST_CODE` 从 `284` 更新为 `285`。
3. 在主配置和测试配置的 `text.status_code_msg` 列表末尾追加对应文案，确保下标与枚举值一致。
4. `stopNursingOrder(...)` 和 `deleteNursingOrder(...)` 拦截 HIS 护嘱时返回该状态码。

## 数据模型调整

当前表结构：

```sql
CREATE TABLE nursing_orders (
    ...
    order_template_id INT NOT NULL,
    ...
    FOREIGN KEY (order_template_id) REFERENCES nursing_order_templates(id)
);
```

当前 JPA 实体：

```java
@Column(name = "order_template_id", nullable = false)
private Integer orderTemplateId;
```

确认调整：

1. `nursing_orders.order_template_id` 改为可空。
2. 外键约束保留，但允许空值。
3. `NursingOrder.orderTemplateId` 的 `nullable = false` 改为可空。
4. 当前唯一索引 `idx_nursing_orders_pid_order_time` 包含 `order_template_id`，建议只约束手工模板医嘱。

建议新增 HIS 来源字段：

```sql
source VARCHAR(64)
medical_order_id VARCHAR(255)
medical_order_group_id VARCHAR(255)
synced_at TIMESTAMP
```

建议索引：

```sql
CREATE UNIQUE INDEX idx_nursing_orders_pid_template_order_time
    ON nursing_orders (pid, order_template_id, order_time)
    WHERE is_deleted = FALSE AND order_template_id IS NOT NULL;

CREATE UNIQUE INDEX idx_nursing_orders_medical_order_id
    ON nursing_orders (medical_order_id)
    WHERE medical_order_id IS NOT NULL;
```

`idx_nursing_orders_medical_order_id` 不加 `is_deleted = FALSE` 条件，用于保证手工删除过的 HIS 同步护理医嘱不会被下次同步重新插入。

补充执行记录备注字段：

```sql
ALTER TABLE nursing_execution_records
    ADD COLUMN note TEXT;
```

要求：

1. `nursing_execution_records.note` 用于保存单条执行记录的护理说明。
2. 字段类型使用 `TEXT`，允许为空。
3. JPA 实体 `NursingExecutionRecord` 新增 `note` 字段映射。
4. 表注释与列注释需要同步补齐。

## 同步策略

建议同步策略参考 `OrderGroupGenerator.updateOrderGroups(...)`：

1. 新医嘱：`medical_order_id` 未同步过时，新增 `nursing_orders`。
2. 医嘱内容变化：若名称、长临、频次、开立时间、停止时间、备注等关键字段变化，更新已有 HIS 同步护理医嘱。
3. 停止或取消变化：更新 `nursing_orders.stop_time/stop_by`，不新增取消状态，不软删除。
4. 不按 `medical_orders.group_id` 合并；`group_id` 只作为来源字段保存。
5. 同步服务只更新 `source = "medical_orders"` 或 `medical_order_id IS NOT NULL` 的记录，不覆盖手工新增的 `nursing_orders`。
6. 若找到同 `medical_order_id` 且 `is_deleted = true` 的记录，视为用户手工删除过，跳过同步且不恢复。

错误处理建议：

1. 白名单不命中、空 `freq_code`、科室不一致、非 ICU 患者属于过滤或跳过，不影响接口返回。
2. `support_nursing_order = false` 属于配置错误，应记录错误日志并返回 `NURSING_ORDER_FREQUENCY_NOT_EXISTS`。
3. 频次不存在不作为错误，按 once 处理。

## 接入接口

### getNursingOrderDetails

当前 `NursingOrderService.getNursingOrderDetails(...)` 流程为：

1. 解析请求。
2. 查询账号。
3. 查询患者。
4. 查询 `nursing_orders`。
5. 查询或生成 `nursing_execution_records`。
6. 按模板组装返回。

建议插入点：

```text
查询患者成功之后
查询 nursing_orders 之前
```

伪流程：

```text
accountId = userService.getAccountWithAutoId().getFirst()
patientRecord = patientService.getPatientRecord(pid)
nursingOrderSyncService.sync(patientRecord, accountId)
orders = orderRepo.findByPidAndIsDeletedFalse(pid)
...
```

### getNursingOrders

`/api/nursingorder/getnursingorders` 也需要触发同步，保证“开立护嘱”弹窗里的已有护嘱列表包含 HIS 护嘱。

建议插入点：

```text
解析请求之后
查询 nursing_orders 之前
```

伪流程：

```text
accountId = userService.getAccountWithAutoId().getFirst()
patientRecord = patientService.getPatientRecord(pid)
nursingOrderSyncService.sync(patientRecord, accountId)
orders = orderRepo.findByPidAndIsDeletedFalse(pid)
...
```

### stopNursingOrder/deleteNursingOrder

HIS 同步护理医嘱不允许手工停止或删除：

1. 若请求中的 `nursing_orders.id` 对应记录 `source = "medical_orders"` 或 `medical_order_id IS NOT NULL`，后端应拦截。
2. 拦截返回新增状态码 `NURSING_ORDER_HIS_SYNCED_NOT_EDITABLE`。
3. 手工模板护理医嘱保持现有行为。

### updateNursingExeRecord

`/api/nursingorder/updatenursingexerecord` 需要扩展执行记录备注写入能力。

建议调整：

```proto
message UpdateNursingExeRecordReq {
    int64 id = 1;  // nursing_execution_records.id
    string completed_time_iso8601 = 2;  // 完成时间；可以为空，空时代表撤回完成时间
    string note = 3;  // 护理说明；可以为空
}
```

处理规则建议：

1. 单条执行时，`completed_time_iso8601` 和 `note` 一起提交。
2. 批量执行时，每条 `UpdateNursingExeRecordReq.note` 都写入同一个备注值。
3. 撤回执行时，`completed_time_iso8601` 置空，同时将 `note` 清空。
4. 删除执行记录接口保持现状，不额外新增备注入参。

## getNursingOrderDetails 组装调整

`order_template_id` 允许为空后，当前组装逻辑需要拆分：

1. 现有模板护理医嘱继续通过 `order_template_id -> nursing_order_templates.group_id -> nursing_order_template_groups` 分组。
2. HIS 同步护理医嘱，即 `source = "medical_orders"` 或 `medical_order_id IS NOT NULL` 的记录，统一放入虚拟组 `His护嘱`。

虚拟组：

```text
id: 0
name: "His护嘱"
display_order: 置顶
nursing_order: HIS 同步护理医嘱列表
```

排序：

1. `His护嘱` 固定在所有模板组之前。
2. 模板组保持现有 `display_order`。
3. `His护嘱` 组内 HIS 护理医嘱按 `order_time` 升序，再按 `medical_order_id` 升序。

`NursingOrderPB.order_template_id` 处理：

1. 当前 proto 是 `int32`，前端生成类型为必填 `number`。
2. 无模板 HIS 医嘱返回 `order_template_id = 0`。
3. 后端组装 `NursingOrderPB` 时不能直接对空 `orderTemplateId` 调用 setter，需要显式兜底为 `0`。

## 前端要求

已检查：

```text
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_frontend/src/pages/home/tabs/nurses/NursingOrder/index.tsx
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_frontend/src/pages/home/tabs/nurses/NursingOrder/components/NursingOrderCard/index.tsx
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_frontend/src/pages/home/tabs/nurses/NursingOrder/components/ExecuteRecordModal/index.tsx
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_frontend/src/pages/home/tabs/nurses/NursingOrder/components/ExistingPlanTable/index.tsx
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_frontend/src/api/WebApi.ts
```

结论：

1. 护理医嘱详情页只渲染后端返回的 `group.name`、`group.id` 和 `group.nursingOrder`，可以直接展示置顶 `His护嘱` 虚拟组。
2. 护理医嘱卡片只使用 `nursingOrder.id/name/medicationFreqCode/exeRecord`，不依赖 `orderTemplateId`。
3. 执行记录弹窗只用 `nursingOrder.id` 调 `/api/nursingorder/getnursingorder`，不依赖 `orderTemplateId`。
4. 已有护嘱表只展示和操作 `nursingOrder.id/name/durationType/orderBy/orderTime/stopBy/stopTime`，不依赖 `orderTemplateId`。
5. 前端 `NursingOrderPB.orderTemplateId` 当前是必填 `number`，因此后端对 HIS 无模板医嘱返回 `0` 更稳，不建议为本需求改 proto optional。

新增前端改动：

1. `ExistingPlanTable` 中 HIS 同步护理医嘱不允许被选中用于停止或删除。
2. 最小改动可以用 `nursingOrder.orderTemplateId === 0` 判断 HIS 同步护理医嘱。
3. 不需要为本需求新增 `NursingOrderPB.source` 或 `is_his_synced` 字段。
4. “已有护嘱”列表需要包含 `/api/nursingorder/getnursingorders` 同步后的 HIS 护嘱。
5. `ExecuteRecordModal` 中单条“执行”按钮当前使用 `Popconfirm`，需要改为弹出可输入“护理说明”的确认弹窗。
6. `ExecuteRecordModal` 中“批量执行”当前使用 `Popconfirm`，也需要改为弹出可输入“护理说明”的确认弹窗；提交时所有本次批量完成的执行记录写入同一份备注。
7. 单条执行时，允许用户修改执行时间，继续沿用当前表格行内时间选择器；新弹窗只新增“护理说明”输入框，不再重复放执行时间控件。
8. 除批量执行外，每一条未完成执行记录都保留单独“执行”入口，点击后只执行当前这一条。
9. 执行记录备注需要在“执行记录编辑”表格中直接展示一列，便于查看当前备注。
10. `NursingExeRecordPB` 需要增加 `note` 字段，前端类型重新生成后用于回显执行记录备注。

## 执行记录生成

同步后的护理医嘱应继续复用现有 `parseNursingOrders(...)` 和 `getPlanTimes(...)`：

1. 临时护理医嘱按开立时间或频次生成执行记录。
2. 长期护理医嘱按班次和频次生成执行记录。
3. `stop_time` 之后不再生成执行记录。
4. 非 ICU 患者不触发 HIS 同步，也不新增 HIS 护嘱执行记录。

前置要求：

1. `duration_type` 必须按“长临映射”转换为护理医嘱语义。
2. `medication_freq_code` 为空的 HIS 医嘱不落库。
3. 频次查不到或 `MedicationConfig.getMedicationFrequencySpec(freq_code)` 返回空时，按 once 处理。
4. `order_template_id = null` 的 HIS 护理医嘱不能参与模板分组查询。

## 执行记录备注

当前 `nursing_execution_records`、`NursingExecutionRecord` 和 `config.NursingExeRecordPB` 都没有备注字段；如果只修改写接口，前端执行后无法回显备注。

本次补充需求：

1. `nursing_execution_records` 新增 `note TEXT` 字段。
2. `src/main/proto/config/icis_nursing_order.proto` 中 `NursingExeRecordPB` 新增 `string note = 6;`。
3. `NursingOrderService.buildNursingExeRecordPB(...)` 返回执行记录时带出 `note`。
4. `/api/nursingorder/getnursingorderdetails` 与 `/api/nursingorder/getnursingorder` 返回的执行记录都应包含 `note`。
5. `/api/nursingorder/updatenursingexerecord` 更新完成时间时，同时支持更新 `note`。
6. 批量执行时，前端为本次提交的全部执行记录写入相同备注。

建议交互：

1. 单条执行：
   - 继续使用当前表格行内的执行时间输入。
   - 每条未完成执行记录都有独立“执行”按钮。
   - 点击单条“执行”后，弹出模态框而不是 `Popconfirm`，确认后只更新当前这一条执行记录。
   - 模态框内至少包含一个 `护理说明` 输入框，以及确认/取消按钮。
2. 批量执行：
   - 点击“批量执行”后，弹出模态框。
   - 模态框内至少包含一个 `护理说明` 输入框，以及确认/取消按钮。
   - 确认后仅遍历当前列表中的未完成记录，沿用当前批量执行时间规则：执行时间等于各自 `plan_time`，备注统一为同一个值。
3. 恢复未执行时，清空 `completed_time`、`completed_by` 和 `note`。
4. 删除执行记录的交互保持现状。

涉及文件：

```text
src/main/resources/config/db/schema.postgresql.sql
src/main/java/com/jingyicare/jingyi_icis_engine/entity/nursingorders/NursingExecutionRecord.java
src/main/java/com/jingyicare/jingyi_icis_engine/service/nursingorders/NursingOrderService.java
src/main/proto/config/icis_nursing_order.proto
src/main/proto/icis_web_api.proto
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_frontend/src/pages/home/tabs/nurses/NursingOrder/components/ExecuteRecordModal/index.tsx
```

## 测试要求

单元测试路径：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/nursingorders
```

建议覆盖以下场景：

1. 白名单精确匹配：`order_type + order_name` 都命中才同步。
2. 白名单过滤：类型命中但名称不命中时不产生 `nursing_orders`。
3. 科室过滤：`medical_orders.dept_id != patient_records.dept_id` 时不同步。
4. 非 ICU 患者：不触发 HIS 同步。
5. 一对一同步：每条 `medical_orders.order_id` 对应一条 `nursing_orders`。
6. 幂等同步：同一个 `medical_orders.order_id` 多次调用只产生一条 `nursing_orders`。
7. 手工删除过的 HIS 护嘱：下次同步不恢复。
8. 更新同步：`stop_time` 或 `freq_code` 变化后更新已有 HIS 同步护理医嘱。
9. 取消同步：`cancel_time` 或取消状态只更新 `stop_time/stop_by`，不软删除。
10. 频次为空：过滤该医嘱。
11. 频次不存在：按 once 处理。
12. 频次不支持护理医嘱：记录错误并返回 `NURSING_ORDER_FREQUENCY_NOT_EXISTS`。
13. 长临转换：`medical_orders.order_duration_type = 0` 同步为长期护理医嘱，`= 1` 同步为临时护理医嘱。
14. `getNursingOrderDetails`：调用前只有 `medical_orders`，调用后返回置顶 `His护嘱` 虚拟组及执行记录。
15. `getNursingOrders`：调用前只有 `medical_orders`，调用后已有护嘱列表包含 HIS 护嘱。
16. `order_template_id = null`：模板医嘱和 HIS 同步医嘱能同时返回，且不会因模板分组查询报错。
17. `orderTemplateId = 0`：前端详情页、卡片、执行记录弹窗正常展示和执行。
18. 前端停删隐藏：HIS 护嘱在已有护嘱表中不可选中停止或删除。
19. 后端停删拦截：`stopNursingOrder/deleteNursingOrder` 请求包含 HIS 护嘱 ID 时返回 `NURSING_ORDER_HIS_SYNCED_NOT_EDITABLE`，不修改记录。
20. 状态码配置：`icis_web_api.proto`、主 `icis_config.pb.txt` 和测试 `icis_config.pb.txt` 中新增状态码与文案，且枚举值和文案下标一致。
21. 执行记录备注：单条执行时可写入 `nursing_execution_records.note`，查询接口能回显。
22. 批量执行备注：本次批量完成的全部记录都写入相同 `note`。
23. 撤回执行：`UpdateNursingExeRecordReq.completed_time_iso8601 = ""` 时，`note` 同时被清空。
24. `UpdateNursingExeRecordReq.note = ""` 的语义为清空备注。
25. 前端“执行记录编辑”表格新增备注列后，单条执行、批量执行、撤回执行都能正确回显。
26. 前端“执行记录编辑”中单条执行与批量执行都从 `Popconfirm` 切换到模态框后，执行时间与备注提交行为正确。

## 待确认问题

当前需求层面暂无业务未确认问题。

执行实现前仍需核对新增 proto 字段号和数据库字段是否与并行修改冲突。
