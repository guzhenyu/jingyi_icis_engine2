# 护嘱执行备注模板需求

## 背景

当前护嘱执行记录在 `ExecuteRecordModal` 中执行或批量执行时，只提供一个备注输入框。临床常用备注需要手工重复输入，缺少按科室维护和快速插入的模板能力。

本需求整理“护嘱执行备注配置”和“插入备注模板”能力，作为后端与前端实现、验收的依据。

## 目标

1. 新增按科室维护的护嘱执行备注模板表 `nursing_order_notes`。
2. 在 `/api/nursingorder/xxx` 下新增护嘱执行备注模板的增删改查接口。
3. 在 `NursingOrderService` 中实现接口逻辑，同部门同内容不重复保存。
4. 在护理医嘱页面增加“护嘱执行备注配置”入口，支持新增、修改、删除、查询备注模板。
5. 在执行/批量执行弹窗中展示当前科室备注模板，并支持一键插入到备注输入框光标处。
6. 支持备注模板按 `display_order` 排序，并在配置界面通过拖拽调整顺序。

## 非目标

1. 不改变 `nursing_execution_records.note` 的现有存储结构和提交方式。
2. 不改变 HIS 同步护嘱逻辑。
3. 不引入备注模板分组、搜索或分页，除非后续明确需要。

## 后端需求

### 数据表

在 `src/main/resources/config/db/schema.postgresql.sql` 护理医嘱相关表附近新增：

```sql
CREATE TABLE nursing_order_notes (
    id SERIAL PRIMARY KEY,  -- 自增id
    dept_id VARCHAR(255) NOT NULL,  -- 部门id
    content VARCHAR(1000) NOT NULL,  -- 备注内容
    display_order INTEGER,  -- 显示顺序，可为空
    is_deleted BOOLEAN NOT NULL,  -- 是否已删除
    deleted_by VARCHAR(255),  -- 删除人
    deleted_at TIMESTAMP,  -- 删除时间
    modified_by VARCHAR(255),  -- 最后修改人
    modified_at TIMESTAMP  -- 最后修改时间
);
```

排序要求：

1. 按 `display_order` 升序。
2. `display_order` 为空的记录排在非空记录之后。
3. `display_order` 相同或都为空时，按 `id` 升序。
4. 只返回 `is_deleted = false` 的记录。

新增备注模板时，前端不传也不展示 `display_order`，后端自动使用当前科室未删除备注模板中最大非空 `display_order + 1`。如果当前科室没有非空 `display_order`，从 1 开始。

需要补充表和字段 `COMMENT ON ...`，与现有 schema 风格保持一致。

同科室同内容不重复保存通过服务层代码检查实现，不新增数据库唯一索引。

### 实体与 Repository

建议新增：

1. `src/main/java/com/jingyicare/jingyi_icis_engine/entity/nursingorders/NursingOrderNote.java`
2. `src/main/java/com/jingyicare/jingyi_icis_engine/repository/nursingorders/NursingOrderNoteRepository.java`

Repository 需要支持：

1. 按科室查询未删除备注模板，并按需求排序。
2. 按 `id` 查询未删除记录。
3. 按 `dept_id + content + is_deleted=false` 查询重复记录。
4. 更新时按 `dept_id + content + id != currentId + is_deleted=false` 检查重复。
5. 查询当前科室最大非空 `display_order`。
6. 批量更新同科室备注模板的 `display_order`，用于拖拽排序。

### Proto 与接口

在 `src/main/proto/icis_web_api.proto` 的护理计划接口段落中新增备注模板 message 和接口说明。

建议 message：

```proto
message NursingOrderNotePB {
    int32 id = 1;
    string dept_id = 2;
    string content = 3;
    optional int32 display_order = 4;
}

message GetNursingOrderNotesReq {
    string dept_id = 1;
}

message GetNursingOrderNotesResp {
    shared.ReturnCode rt = 1;
    repeated NursingOrderNotePB note = 2;
}

message AddNursingOrderNoteReq {
    string dept_id = 1;
    string content = 2;
}

message AddNursingOrderNoteResp {
    shared.ReturnCode rt = 1;
    int32 id = 2;
}

message UpdateNursingOrderNoteReq {
    int32 id = 1;
    string dept_id = 2;
    string content = 3;
}

message DeleteNursingOrderNoteReq {
    int32 id = 1;
    string dept_id = 2;
}

message ReorderNursingOrderNotePB {
    int32 id = 1;
    int32 display_order = 2;
}

message ReorderNursingOrderNotesReq {
    string dept_id = 1;
    repeated ReorderNursingOrderNotePB note_order = 2;
}
```

建议接口路径：

1. `/api/nursingorder/getnursingordernotes`
2. `/api/nursingorder/addnursingordernote`
3. `/api/nursingorder/updatenursingordernote`
4. `/api/nursingorder/deletenursingordernote`
5. `/api/nursingorder/reordernursingordernotes`

返回类型建议：

1. 查询返回 `GetNursingOrderNotesResp`。
2. 新增返回 `AddNursingOrderNoteResp`，方便前端拿到新 id。
3. 修改、删除、重排返回现有 `GenericResp`。

### 错误码

在 `StatusCode` 中新增护嘱执行备注相关错误码，并同步补充以下配置文件的 `text`：

1. `src/main/resources/config/pbtxt/icis_config.pb.txt`
2. `src/main/resources/config/pbtxt/hospitals/*_icis_config.pb.txt`
3. `src/test/resources/text_resources/icis_config.pb.txt`

建议错误码：

1. `NURSING_ORDER_NOTE_NOT_EXISTS`：备注模板不存在。
2. `NURSING_ORDER_NOTE_ALREADY_EXISTS`：同科室下备注内容已存在。
3. `NURSING_ORDER_NOTE_CONTENT_EMPTY`：备注内容为空。
4. `NURSING_ORDER_NOTE_CONTENT_TOO_LONG`：备注内容超过 1000 字符。
5. `INVALID_NURSING_ORDER_NOTE_DISPLAY_ORDER`：显示顺序无效。

### 服务实现

在 `NursingOrderService` 中新增增删改查方法，职责包括：

1. 解析请求 JSON。
2. 获取当前账号，用于 `modified_by`、`deleted_by`。
3. 查询接口按 `dept_id` 返回当前科室未删除备注模板。
4. 新增和修改时先对 `content` 做 `trim`，trim 后为空返回 `NURSING_ORDER_NOTE_CONTENT_EMPTY`。
5. `content` 超过 1000 字符返回 `NURSING_ORDER_NOTE_CONTENT_TOO_LONG`。
6. 重复判断使用 trim 后内容，大小写敏感；同一科室下未删除记录内容相同则不允许重复保存。
7. 新增接口校验 `dept_id`、`content`，自动生成 `display_order` 后保存。
8. 修改接口按 `id` 查未删除记录，仅允许修改 `content`，更新时继续检查同科室内容重复。
9. 重排接口接收当前科室备注模板 id 和新 `display_order`，批量更新排序；请求中的记录必须属于请求 `dept_id` 且未删除。
10. `display_order` 调整参考 `jingyi_icis_frontend/src/pages/home/tabs/settings/BasicFeaturesConfig/components/MonitorConfig/index.tsx` 中分组参数列表的拖拽排序：前端拖拽后按当前位置生成从 1 开始的连续顺序，后端批量保存。
11. 删除接口软删除记录，写入 `is_deleted=true`、`deleted_by`、`deleted_at`。
12. 所有写接口写入 `modified_by`、`modified_at`。
13. 首版沿用当前护理医嘱配置能力，以请求 `dept_id` 查询和维护；后端需要防止跨科室维护，至少校验当前账号有权操作请求中的 `dept_id`。

需要同步接入：

1. `WebApiService` 增加转发方法。
2. `IcisController` 增加 `/nursingorder/...` 的 `@PostMapping`。
3. 新增或更新单元测试。

### 后端测试建议

1. 查询只返回当前科室未删除记录。
2. 查询排序覆盖 `display_order` 有值、为空、相同、全为空场景。
3. 新增成功，返回新 id，并自动生成当前科室最大 `display_order + 1`。
4. 新增时同科室同内容重复返回错误。
5. 不同科室允许相同内容。
6. 修改不存在或已删除记录返回不存在。
7. 修改成同科室已有内容返回重复错误。
8. 删除为软删除，删除后查询不返回。
9. 内容为空、超长、非法 `display_order` 返回对应错误码。
10. 内容前后空格不参与空值和重复判断。
11. 重排接口能把同科室备注模板保存为连续 `display_order`，并拒绝跨科室记录。

## 前端需求

### API 与状态

需要更新：

1. `src/api/WebApi.ts` 中的 API endpoint、请求/响应类型。
2. `src/store/slices/NursingOrderApi.ts` 中新增五个 thunk。
3. `src/api/Text.ts` 中新增所有 UI 文案、成功失败提示、确认删除提示。

建议新增 endpoint 常量：

1. `GET_NURSING_ORDER_NOTES`
2. `ADD_NURSING_ORDER_NOTE`
3. `UPDATE_NURSING_ORDER_NOTE`
4. `DELETE_NURSING_ORDER_NOTE`
5. `REORDER_NURSING_ORDER_NOTES`

### 护嘱执行备注配置

位置：`jingyi_icis_frontend/src/pages/home/tabs/nurses/NursingOrder`。

在 `OrderTempConfigModal` 左侧分组区域上方或左侧工具区增加“护嘱执行备注配置”按钮。点击后调用当前科室的备注模板查询接口，并打开独立的新增配置 modal，避免把备注配置混入模板分组表。

建议新增组件：

```text
src/pages/home/tabs/nurses/NursingOrder/components/NursingOrderNoteConfigModal/index.tsx
src/pages/home/tabs/nurses/NursingOrder/components/NursingOrderNoteConfigModal/index.module.scss
```

Modal 需求：

1. 标题为“护嘱执行备注配置”。
2. 表格列为“内容”和“操作”。
3. 操作列提供修改、删除按钮或图标。
4. 表格右上角提供新增图标。
5. 新增、修改、删除分别即时调用新增、修改、删除接口。
6. 底部提供“取消”和“确定”两个按钮；两者都只关闭 modal，不做批量暂存和回滚。
7. 操作成功后刷新当前科室备注模板列表。
8. 删除需要二次确认。
9. 内容输入限制 1000 字符，前端需要给出剩余字数或超长提示。
10. 空列表展示空状态。
11. loading 时显示加载状态。
12. 配置 modal 暂不展示 `display_order` 字段。
13. 表格支持行拖拽调整顺序，拖拽交互仿照 `BasicFeaturesConfig/components/MonitorConfig` 中分组参数列表的 `SortableTable` 行拖拽能力。
14. 拖拽完成后按新顺序生成从 1 开始的连续 `displayOrder`，调用重排接口；接口成功后刷新列表。

UI 要求：

1. 与现有 `OrderTempConfigModal` 视觉风格一致。
2. 操作按钮优先使用图标，悬浮 tooltip 说明动作。
3. 表格内容列支持省略展示，悬浮展示完整内容。
4. modal 宽度和表格高度应保证常用备注可快速浏览，不出现拥挤布局。
5. 拖拽手柄、编辑、删除等操作按钮使用清晰图标和 tooltip，避免表格操作列过宽。

### 执行/批量执行弹窗插入备注模板

位置：`src/pages/home/tabs/nurses/NursingOrder/components/ExecuteRecordModal/index.tsx`。

当前执行或批量执行会打开一个包含“备注”输入框、取消、确定的小弹窗。新需求为：

1. 执行和批量执行都复用新弹窗布局。
2. 弹窗设置 `width=780`，内容区域最小高度约 420-480px。
3. 弹窗分左右两栏，各占 50%。
4. 左侧保留“备注”输入框、取消、确定。
5. 右侧展示当前科室所有 `nursing_order_notes`。
6. 右侧表格两列：“内容”和“操作”。
7. 每行操作列提供“插入”按钮或图标。
8. 点击插入后，将该行原始 `content` 插入到左侧备注输入框当前光标处，不自动追加换行或分隔符。
9. 如果没有光标位置，则插入到备注末尾。
10. 插入后应保持输入框聚焦，并把光标移动到插入内容之后。

数据获取：

1. 打开执行/批量执行弹窗时，按当前科室查询备注模板。
2. 当前科室来自 Redux `state.user.curDeptId`。
3. 查询失败时不阻断执行流程，右侧展示错误或空状态。
4. 备注模板列表为空时，右侧展示空状态。
5. 首版不分页，按科室全量返回。

插入逻辑建议：

1. 使用 `Input.TextArea` ref 读取 `selectionStart`、`selectionEnd`。
2. 如果存在选区，用模板内容替换选区。
3. 如果不存在选区，按光标位置插入。
4. 如果无法读取光标位置，则追加到末尾。
5. 插入后通过 `executeForm.setFieldsValue({ note: nextNote })` 同步表单值。

## 验收标准

1. 科室 A 新增备注模板后，只在科室 A 查询结果中出现。
2. 科室 A 同内容不能新增第二条未删除备注模板。
3. 科室 A 删除备注模板后，查询不再返回该记录。
4. 科室 A 删除后可再次新增相同内容。
5. 执行单条记录时可从右侧模板表插入备注，并提交到该条 `nursing_execution_records.note`。
6. 批量执行时可从右侧模板表插入备注，并提交到所有待执行记录。
7. 模板插入不影响现有手工输入内容。
8. 后端错误码能在前端展示明确错误信息。
9. 所有新增接口在 proto、controller、`WebApiService`、service、frontend API thunk 中路径和类型一致。
10. 新增备注模板时前端不展示排序字段，后端自动生成排序值。
11. 配置 modal 中拖拽备注模板行后，刷新后仍保持新顺序。

## 实现清单

后端：

1. 更新 schema。
2. 新增 entity 和 repository。
3. 更新 `icis_web_api.proto`。
4. 重新生成 Java/TS proto 产物。
5. 新增 `StatusCode` 和所有 pbtxt 文案。
6. 更新 `NursingOrderService`。
7. 更新 `WebApiService`。
8. 更新 `IcisController`。
9. 增加单元测试。

前端：

1. 更新 `WebApi.ts` API endpoint 和类型。
2. 更新 `NursingOrderApi.ts` thunk。
3. 更新 `Text.ts` 文案。
4. 新增备注配置 modal。
5. 在护嘱模板配置入口中接入备注配置。
6. 改造 `ExecuteRecordModal` 执行弹窗布局。
7. 增加模板插入逻辑。
8. 接入 `SortableTable` 或同等拖拽表格能力，实现备注模板排序。
9. 做桌面宽度下的 UI 检查，确认表格、输入框、按钮不重叠。

## 已决策

1. `display_order` 允许为空，数据库字段使用 `INTEGER` 可空。
2. 备注模板查询排序为非空 `display_order` 升序，空值排在后面，相同或都为空时按 `id` 升序。
3. 新增备注模板时配置 modal 不展示 `display_order`，后端自动使用当前科室最大非空 `display_order + 1`。
4. `content` 新增和修改时先 `trim`；trim 后为空返回 `NURSING_ORDER_NOTE_CONTENT_EMPTY`。
5. `content` 重复判断使用 trim 后内容，大小写敏感。
6. 配置 modal 中新增、修改、删除按钮即时调用接口；底部“取消”和“确定”都只关闭 modal。
7. “护嘱执行备注配置”入口放在 `OrderTempConfigModal` 左侧分组区域上方或左侧工具区，点击后打开独立 `NursingOrderNoteConfigModal`。
8. 执行/批量执行弹窗设置 `width=780`，内容区域最小高度约 420-480px。
9. 插入模板时严格按光标位置插入原始 `content`，不自动追加换行或分隔符。
10. 查询备注模板首版不分页，按科室全量返回。
11. 首版沿用当前护理医嘱配置能力，以请求 `dept_id` 查询和维护；后端需要防止跨科室维护。
12. 错误码编号在当前 `StatusCode` 最大编号后顺序追加，避免复用已有编号。
13. 数据库保留 `modified_by/modified_at/deleted_by/deleted_at`，前端首版不展示审计字段。
14. `display_order` 调整仿照 `MonitorConfig` 中分组参数列表的行拖拽功能，通过拖拽表格行批量重排。

## 待决策

空。
