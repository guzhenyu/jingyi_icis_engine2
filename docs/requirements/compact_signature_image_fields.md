# 精简重症监护记录单签名图片字段需求

## 背景

当前 compact 报表中以下数据源字段仍按文本显示记录人/审核人：

- `patient_skincare_records.recorded_by`
- `patient_nursing_records.recorded_by`
- `patient_nursing_records.reviewed_by`
- `patient_bga_records.recorded_by`
- `patient_bga_records.reviewed_by`

目标是将这些字段改为优先展示 `accounts.sign_pic` 签名图片；当账号不存在、没有签名图片、签名图片不可解析时，回退显示原记录人/审核人姓名文本。

本需求只覆盖后端 compact PDF 报表生成，不改变前端 JFK 表单编辑能力。

## 已确认决策

- `patient_skincare_records` 本期继续使用 `modified_by` 作为签名账号来源。
- 图片格式不支持 WebP；上传端限制 PNG/JPEG。
- 接受轻量方案：数据源先校验 `accounts.sign_pic`，可解析则输出图片 data URL/base64，否则输出回退文本。
- 签名图片只用于 compact PDF，不要求同步前端 JFK renderer 的 data source 图片显示能力。
- 签名列基础行高暂不调整，12pt 行高内尽可能按比例填满 cell。
- BGA 表格拆成两张表：
  - `table-38-1`：静态表头，始终显示。
  - `table-38-2`：动态内容表，使用 `patient_bga_records` 数据源。
  - 表头的 `记录人/审核人` 保持普通文本，不参与签名图片解析。

## 前端 JFK 渲染调研结论

前端 JFK 类型定义：

- `JFK_VAL_TYPE_IMAGE = 6`
- `JFK_VAL_TYPE_NURSING_SIGN_PIC = 7`
- `JFK_VAL_TYPE_DOCTOR_SIGN_PIC = 8`
- `isJfkImageValType(...)` 将 6/7/8 都视为图片类型。

前端渲染行为：

- `JfkPageView.renderDisplayNode(...)` 遇到图片类型时，读取 `JfkValPB.strVal` 作为 `<Image src={val.strVal}>`。
- 图片容器占满文本框/表格单元格，内部图片 `width: 90%`、`height: 90%`、`maxWidth: 90%`、`objectFit: contain`。
- 表格单元格会把当前行高传入 `maxHeightPt`，图片最大高度约为当前 cell/row 高度的 90%。
- 图片选择器中预览尺寸为 `60 x 36`，也是 `objectFit: contain`。
- 前端账号管理上传签名时使用 `FileReader.readAsDataURL(file)`，因此正常入库的 `accounts.sign_pic` 形态应是 `data:image/...;base64,...`。
- 前端浏览器理论上可接受任意 `<img src>` 支持的图片源，但本需求要求上传端限制 PNG/JPEG，后端不支持 WebP。

## 后端现状

JFK proto 已有图片类型：

- `JfkValPB.str_val` 注释说明对应 `STRING / DATETIME / IMAGE`。
- `JfkValTypePB.IMAGE = 6`
- `JfkValTypePB.NURSING_SIGN_PIC = 7`
- `JfkValTypePB.DOCTOR_SIGN_PIC = 8`
- `JfkValTypePB.STRINGS = 9`

compact PDF renderer 当前限制：

- `JfkValueResolver.resolveCellLines(...)` 当前只返回 `List<String>`。
- `STRINGS` 使用 `strs_val` 多行文本，其他类型最终落到 `valToDisplayString(...)`。
- `JfkTableRenderer.CellData` 当前只包含文本行和对齐信息。
- `JfkTableRenderer.drawCells(...)` 当前只调用 `JfkTextRenderer.drawLines(...)`，没有图片绘制分支。
- `JfkPdfRenderer` 已能用 `PDImageXObject.createFromByteArray(...)` 绘制 logo。
- AH2 报表已有签名图片绘制参考：根据账号 ID 找 `accounts.sign_pic`，去掉 data URL 逗号前缀，base64 解码，`PDImageXObject.createFromByteArray(...)`，按单元格宽高等比缩放居中绘制。

## 目标行为

### 1. JFK 数据源元数据

将 `icis_config.pb.txt` 中下列 output field 的 `val_meta.val_type` 从 `STRINGS(9)` 调整为 `NURSING_SIGN_PIC(7)`：

- `patient_skincare_records.recorded_by`
- `patient_nursing_records.recorded_by`
- `patient_nursing_records.reviewed_by`
- `patient_bga_records.recorded_by`
- `patient_bga_records.reviewed_by`

这些字段仍保持 `is_array: true`，每一行对应一个 `JfkValPB`。

### 2. 数据源输出值

数据源输出字段仍使用原 field id，不新增模板字段：

- 图片可用时：`JfkValPB.str_val = accounts.sign_pic`
- 图片不可用或不可解析时：`JfkValPB.str_val = fallbackName`

其中 fallbackName 定义如下：

- BGA：
  - `recorded_by` 回退 `patient_bga_records.recorded_by_account_name`
  - `reviewed_by` 回退 `patient_bga_records.reviewed_by_account_name`
- 护理记录：
  - 护理记录行 `recorded_by` 回退 `nursing_records.created_by_account_name`
  - 护理记录行 `reviewed_by` 回退 `nursing_records.reviewed_by_account_name`
  - 非整点观察项合并行 `recorded_by` 当前来自 `PatientMonitoringRecord.modified_by`，回退逻辑沿用当前账号名/原始 `modified_by` 文本
  - 非整点观察项合并行无 `reviewed_by`
- 皮肤护理：
  - 输出字段名仍为 `recorded_by`
  - 当前表结构没有 `patient_skincare_records.recorded_by`，现有实现使用 `patient_skincare_records.modified_by`
  - 本期确认继续以 `modified_by` 作为签名账号来源，回退当前账号名/原始 `modified_by` 文本

### 3. 账号查询规则

记录人/审核人的源字段是字符串，需要按 Long 解析为 `accounts.id`：

- 可解析为正整数：查询 `accounts.id`
- 不能解析：记录 warn，直接回退原有姓名文本
- 找不到账号或账号已删除：记录 warn，回退原有姓名文本
- 找到账号但 `sign_pic` 为空：回退原有姓名文本
- 找到账号且 `sign_pic` 可解析：输出 `sign_pic`

查询应批量完成，避免每行单独查账号。

### 4. 图片解析规则

为匹配前端账号上传行为，后端图片解析支持：

- `data:image/png;base64,...`
- `data:image/jpeg;base64,...`
- `data:image/jpg;base64,...`
- bare base64 PNG/JPEG，即没有 `data:image/...;base64,` 前缀但内容可被解码

不支持 WebP。若遇到 WebP 或其他不支持格式，按不可解析处理并回退姓名文本。

解析策略：

- 如果字符串包含逗号，取逗号后作为 base64 部分。
- base64 decode 失败，视为不可解析。
- `PDImageXObject.createFromByteArray(...)` 失败，视为不可解析。
- 不应把无法解析的 base64 原文画到 PDF 上，应回退姓名文本。
- 数据源阶段应尽量提前校验 `sign_pic` 是否可解析；可解析时输出 `sign_pic`，不可解析时输出 fallbackName。PDF renderer 仍需保留兜底：如果收到图片类型但 `str_val` 解析失败，则按文本绘制 `str_val`。

### 5. PDF 渲染规则

表格单元格遇到 `val_meta.val_type == IMAGE/NURSING_SIGN_PIC/DOCTOR_SIGN_PIC` 时：

- 若 `str_val` 可解析为图片：按图片绘制。
- 若 `str_val` 不可解析为图片：按普通文本绘制。
- 图片绘制区域使用当前 cell 内容区：
  - 左右扣除 `h_padding`
  - 图片等比缩放，`object-fit: contain`
  - 按单元格水平/垂直对齐方式居中，至少默认居中
  - 本期不额外预留 5% 留白，尽可能填满 cell 内容区
- 缩放公式：
  - `scale = min(cellContentWidth / imageWidth, cellContentHeight / imageHeight)`
  - `drawWidth = imageWidth * scale`
  - `drawHeight = imageHeight * scale`
  - draw box 在 cell 内容区内水平、垂直居中
- 示例：cell 宽 100pt、高 12pt 时：
  - 图片 `80 x 12`：显示 `80 x 12`
  - 图片 `200 x 12`：显示 `100 x 6`
  - 图片 `60 x 24`：显示 `30 x 12`
- 行高计算：
  - 签名图片不应按 base64 字符串长度参与文本换行和行高计算。
  - 对图片单元格，行高至少使用模板已有 `cell_heights`。
  - 如果同行其他文本产生多行，仍由其他文本决定弹性行高。

推荐实现方式：

- 将 `JfkValueResolver` 从“只返回文本行”扩展为可返回 `CellContent`，包含：
  - `valType`
  - `JfkValPB`
  - `List<String> textLines`
  - `fallbackText`
- 或在 `JfkTableRenderer.CellData` 中增加图片相关字段。
- `JfkTableRenderer.drawCells(...)` 根据 cell 类型选择 `drawImage` 或 `drawLines`。
- 增加签名图片解析/缓存工具，避免同一个账号/同一张 base64 在同一份 PDF 中重复解码。
- 本期可采用数据源预校验轻量方案，不扩展 `JfkValPB`。实现时需要保证：
  - 数据源输出图片前已经验证可解析。
  - 不可解析直接输出 fallbackName。
  - renderer 对图片类型解析失败时按普通文本 fallback 绘制。

### 6. compact 数据源改造范围

#### `PatientBgaRecordsDataSourceHandler`

当前：

- `recorded_by` 输出 `record.getRecordedByAccountName()`
- `reviewed_by` 输出 `record.getReviewedByAccountName()`
- 当前实现会额外输出一行表头：`时间/血气类别/血气记录/记录人/审核人`

目标：

- 用 `record.getRecordedBy()` 解析 `accounts.id`，取 `accounts.sign_pic`
- 用 `record.getReviewedBy()` 解析 `accounts.id`，取 `accounts.sign_pic`
- 图片不可用时分别回退 account name 字段
- 配合模板拆表，`table-38-2` 内容表只渲染实际 BGA 数据行，不再由数据源输出表头行，避免与静态 `table-38-1` 重复。

#### `PatientNursingRecordsDataSourceHandler`

当前：

- 护理记录行：
  - `recorded_by` 输出 `nursingRecord.getCreatedByAccountName()`
  - `reviewed_by` 输出 `nursingRecord.getReviewedByAccountName()`
- 非整点观察项行：
  - `recorded_by` 根据 `PatientMonitoringRecord.modified_by` 映射账号名

目标：

- 护理记录行：
  - `recorded_by` 用 `nursingRecord.getCreatedBy()` 解析 `accounts.id`，取签名
  - `reviewed_by` 用 `nursingRecord.getReviewedBy()` 解析 `accounts.id`，取签名
  - 图片不可用时回退原 account name 字段
- 非整点观察项行：
  - `recorded_by` 用 `PatientMonitoringRecord.modified_by` 解析 `accounts.id`，取签名
  - 图片不可用时回退现有账号名/原始 `modified_by`

#### `PatientSkincareRecordsDataSourceHandler`

当前：

- 输出字段 `recorded_by` 来自 `PatientSkincareRecord.modified_by` 账号名映射。

目标：

- 暂按现有源字段 `modified_by` 解析 `accounts.id`，取签名。
- 图片不可用时回退现有账号名/原始 `modified_by`。
- 本期确认继续使用 `modified_by`，未来如果业务新增 `patient_skincare_records.recorded_by`，再切换源字段。

### 7. 模板配置影响

`report_compact.pb.txt` 中护理记录和皮肤护理相关表格列不需要改 field id：

- `table-256` 的 `recorded_by/reviewed_by`
- `table-259` 的 `recorded_by`

BGA 表格需要调整：

- 原 `table-38` 拆为 `table-38-1` 和 `table-38-2`。
- `table-38-1` 是静态表头，始终显示。
- `table-38-2` 是动态内容表，使用 `patient_bga_records` 数据源。
- `table-38-2` 继续使用 `recorded_by/reviewed_by` field id。

签名图片显示依赖 `icis_config.pb.txt` 中对应 output field 的 `val_meta.val_type = 7`。

当前这些列宽大约为 60pt，字体 6pt，基础行高 12pt。图片会在 60pt x 行高的 cell 内等比缩放，若签名过扁或过高，需要后续通过模板列宽/行高调整，而不是数据源硬编码。

### 8. 测试要求

单元测试至少覆盖：

- data URL PNG/JPEG 签名可用时，数据源输出 `str_val = sign_pic`。
- bare base64 签名可用时可识别。
- `recorded_by/reviewed_by/modified_by` 不能解析为 Long 时，回退姓名/原始文本。
- 账号不存在时回退。
- `sign_pic` 为空时回退。
- `sign_pic` base64 非法或 PDFBox 不可解析时回退。
- `patient_nursing_records` 中护理记录行和非整点观察项行都能正确处理。
- BGA 静态表头 `table-38-1` 仍显示文本 `"记录人"` / `"审核人"`，不能被当成图片解析。
- `patient_bga_records` 内容数据源不再输出表头行，避免 `table-38-1` 和 `table-38-2` 重复表头。
- PDF renderer 对 `NURSING_SIGN_PIC` cell 绘制图片，不按 base64 文本换行。
- 图片不可解析时，PDF renderer 绘制回退文本。
- 图片缩放符合示例：
  - cell `100 x 12`，image `80 x 12`，绘制 `80 x 12`
  - cell `100 x 12`，image `200 x 12`，绘制 `100 x 6`
  - cell `100 x 12`，image `60 x 24`，绘制 `30 x 12`

## 当前无阻塞问题

以上决策已足够进入实现。实现前只需注意一个一致性点：BGA 拆成 `table-38-1/table-38-2` 后，`table-38-2` 应只接收内容行，不能继续包含数据源表头行，否则会重复显示表头。
