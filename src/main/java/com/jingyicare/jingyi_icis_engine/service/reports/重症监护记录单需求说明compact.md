# 重症监护记录单 compact 需求说明

## 1. 背景

现有监护/护理单报表已拆分为 `standard`、`ah2` 等版本，并通过全局配置选择：

```properties
jingyi.report.monitoring.version=compact
jingyi.report.compact.template=classpath:/config/pbtxt/report_compact.pb.txt
jingyi.report.compact.font=${jingyi.report.font}
```

本需求目标是在后端新增 compact 版“精简重症监护记录单”生成能力。后端应复用前端 JFK renderer 的模板语义和基础渲染规则，同时补充前端当前尚未实现的 PDF 流式布局与弹性表格分页能力。

前端参考目录：

```text
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_frontend/src/pages/home/components/jfk/renderer
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_frontend/src/pages/home/components/jfk/paper
```

后端目标目录：

```text
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport
/Users/guzhenyu/gDocs/jingyi/icis_repos/jingyi_icis_engine2/src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkrenderer
```

参考 PDF：

```text
/Users/guzhenyu/gDocs/jingyi/新安人民医院/表单记录/护理单.pdf
```

本期先完成 compact 报表基础框架和“神经系统”前面的一部分，不要求一次完成整张护理单所有内容。

## 2. 总目标

实现一个基于 `JfkTemplatePB` 的后端 PDF 渲染器，并基于该渲染器完成 compact 版重症监护记录单。

核心目标：

1. 读取 `CompactReportTemplatePB` 配置。
2. 根据 `JfkTemplatePB` 渲染 PDF。
3. 支持绝对定位的文本、表格、线条。
4. 支持 `JfkAbsContainerPB` 内的流式表格分页。
5. 支持数据源驱动表格的可变行数。
6. 支持 `JfkValTypePB.STRINGS` 对应的显式多行 cell。
7. 生成结果接入现有 `MonitoringReportGenerator` 机制，`version()` 返回 `compact`。
8. compact 报表返回 `rotationDegree=0`。

非目标：

1. 本期不实现完整“护理单.pdf”所有表格。
2. 本期不实现 `mon_group.table_id` 驱动的监护参数组查询。
3. 本期不根据 `mon_group` 自动补齐新数据源参数。
4. 本期不自动按宽度折行。
5. 本期不支持 container 内 `ac_tables` 与 `ac_texts` 混排。
6. 本期不处理单个逻辑行高度超过一页后的拆行分页。

## 3. 相关数据结构

### 3.1 CompactReportTemplatePB

配置文件：

```text
src/main/proto/config/icis_report_compact.proto
src/main/resources/config/pbtxt/report_compact.pb.txt
```

结构：

```proto
message CompactReportTemplatePB {
    JfkTemplatePB template = 1;
    LogoMetaPB logo = 2;
    repeated ReportMonGroupPB mon_group = 3;
}
```

本期使用：

1. `template`：PDF 主体模板。
2. `logo`：页 logo 配置。若 `logo.png_path` 为空，则不打印 logo。
3. `mon_group`：保留，不参与本期渲染逻辑。

### 3.2 JfkTemplatePB

`JfkTemplatePB.pages` 中每个 `JfkPagePB` 表示一个硬分页模板。一个 `JfkPagePB` 可以因为 container 流式内容撑开，渲染成多个实际 PDF 页。

如果 `JfkTemplatePB.pages` 有多个硬分页，且第 1 个模板页因流式内容生成 3 个实际页，则实际生成顺序为：

```text
page1-扩展页1
page1-扩展页2
page1-扩展页3
page2
page3
...
```

即先完成当前模板页的全部扩展页，再进入下一个硬分页模板。

### 3.3 JfkPagePB

`JfkPagePB` 包含：

1. `texts`：绝对定位文本。
2. `tables`：绝对定位表格。
3. `lines`：绝对定位线条。
4. `containers`：绝对定位容器，容器内部支持流式布局。

### 3.4 JfkAbsContainerPB

`JfkAbsContainerPB` 本身在页面中绝对定位，内部用于流式排布表格或绝对定位文本。

字段语义：

1. `top`：首页 container 顶部坐标。
2. `height`：首页 container 可用高度。
3. `next_page_top`：后续扩展页 container 顶部坐标。
4. `next_page_height`：后续扩展页 container 可用高度。
5. `ac_tables`：container 内从上往下流式渲染的表格。
6. `ac_texts`：container 内按 `offset_top` 绝对定位的文本。

本期约束：

1. 一个 container 只能使用 `ac_tables` 或 `ac_texts`，不能混排。
2. 如果一个 container 同时配置了 `ac_tables` 和 `ac_texts`，应直接返回错误。
3. `ac_tables` 中表格的 `tbl.y` 不生效。
4. `ac_texts` 中文本的 `text.y` 不生效。
5. container 内元素自己的 `extend_to_next_page/next_page_x/next_page_y` 不参与 container 流式布局。

### 3.5 JfkAcTablePB

`JfkAcTablePB` 用于 container 内的流式表格。

规则：

1. 根据 container 当前光标 `cursor_top` 计算表格顶部。
2. 表格顶部为 `cursor_top - offset_top`。
3. 表格渲染后，光标移动到表格底部。
4. 表格放不下时，按逻辑行分页到下一实际 PDF 页。
5. 如果流式表格分页，“生命体征”这类标题表格不自动重复。

### 3.6 JfkAcTextPB

`JfkAcTextPB` 用于 container 内的文本定位。

本期规则：

1. `JfkAcTextPB` 不影响 container 光标。
2. 首页实际顶部坐标为 `container.top + ac_text.offset_top`。
3. 后续页实际顶部坐标为 `container.next_page_top + ac_text.offset_top`。
4. `text.y` 不生效。
5. 渲染时根据实际顶部坐标和 `text.height` 计算 PDFBox 的 bottom 坐标。

## 4. 渲染坐标与纸张

PDF 坐标单位使用 pt。

纸张尺寸参考前端：

```text
A4 portrait: 595.28 x 841.89
A4 landscape: 841.89 x 595.28
A3 portrait: 841.89 x 1190.55
A3 landscape: 1190.55 x 841.89
```

规则：

1. `JfkTemplatePB.page_size_id=2` 表示 A4。
2. `JfkTemplatePB.page_size_id=1` 表示 A3。
3. `is_page_orientation_portrait=true` 表示纵向。
4. `is_page_orientation_portrait=false` 表示横向。
5. PDFBox 坐标原点在页面左下角，和 `JfkTextPB.x/y`、`JfkTablePB.x/y` 一致。
6. 对于绝对定位元素，`x/y` 表示元素左下角。
7. 对于 container，`top` 表示顶部 y 坐标，渲染元素时需要转换为元素 bottom。

## 5. 数据源规则

compact 渲染器不直接查询业务表。数据获取通过现有 `JfkDataService` 完成。

流程：

1. `CompactMonitoringReportGenerator` 接收 `MonitoringReportRequest`。
2. 根据模板收集需要的数据源。
3. 构造 `JfkDataSourcePB` 输入。
4. 调用 `JfkDataService.getDataSource(...)`。
5. 将返回的 `JfkDataSourcePB.output_data` 放入渲染上下文。
6. 渲染器根据 `JfkElementMetaPB` 解析具体值。

### 5.1 已有数据源

当前已存在 `monitoring_time_range`。

输入：

1. `dept_id`
2. `query_start`

逻辑：

1. `query_start` 通过 `TimeUtils.fromIso8601String(query_start, "UTC")` 转为 `LocalDateTime utcStart`。
2. `utcEnd = utcStart + 24h`。
3. 查询 `effective_time < utcEnd` 的最后一条 `balance_stats_shifts`。
4. 读取 `mon_start_hour`。

输出：

```text
time_txt => ["时间"]
unit_txt => ["单位"]
hour1 => ["7:00"]
hour2 => ["8:00"]
...
hour24 => ["6:00"]
```

### 5.2 数据源列长度校验

对于数据源驱动的表格：

1. 每个 `output_data` 代表一列。
2. 每列的 `vals.length` 必须一致。
3. 如果长度不一致，应直接返回错误。
4. 错误信息必须包含：
   - `table_id`
   - `field_id`
   - 各字段长度

不允许静默补空。

### 5.3 mon_group 处理

本期不考虑 `mon_group.table_id`。

下期预期：

1. 如果某个表格的数据源为特定 compact 观察项数据源。
2. 且该表格配置了对应 `table_id`。
3. 则根据 `table_id` 查询 `CompactReportTemplatePB.mon_group`。
4. 根据 `mon_group.param_code` 补齐相关数据源参数。
5. 查询 `patient_monitoring_records`。
6. 再渲染对应表格。

## 6. 值解析规则

`JfkElementMetaPB.content_type` 支持：

1. `STATIC`：使用模板静态内容。
2. `JFK_DATA_SOURCE`：从 `JfkDataSourcePB.output_data` 获取。
3. `JFK_CUR_PAGE_ID`：当前实际 PDF 页码。
4. `JFK_TOTAL_PAGES`：总实际 PDF 页数。
5. `JFK_CUR_SUB_PAGE_ID`：当前模板子页 ID，本期可先返回模板页序号。

本期 compact 报表不处理用户编辑，因此 `USER_INPUT`、`JFK_INPUT` 可先作为非目标，遇到时可按空值或错误处理。建议首期按空值渲染，并记录 warning。

## 7. 文本渲染规则

文本字段：

1. `font`
2. `font_size`
3. `font_color`
4. `char_spacing`
5. `h_align_id`
6. `v_align_id`
7. `width`
8. `height`

对齐常量：

1. `v_align_id=1`：top。
2. `v_align_id=2`：middle。
3. `v_align_id=3`：bottom。
4. `h_align_id=4`：left。
5. `h_align_id=5`：center。
6. `h_align_id=6`：right。

本期不自动折行。普通类型默认 1 行。

## 8. 表格渲染规则

固定表格支持字段：

1. `id`
2. `x`
3. `y`
4. `z_index`
5. `rows`
6. `cols`
7. `cell_widths`
8. `cell_heights`
9. `line_width`
10. `line_color`
11. `v_align_id`
12. `h_align_id`
13. `font`
14. `font_size`
15. `font_color`
16. `char_spacing`
17. `h_padding`
18. `data_source_meta_id`
19. `column_metas`

表格尺寸：

1. 内容宽度为 `sum(cell_widths)`。
2. 内容高度为 `sum(cell_heights)`。
3. 线宽参与外框和网格绘制。
4. 每个 cell 绘制白底，再绘制文本。

`h_replicas`：

1. 本期 compact 可先支持 `h_replicas=0`。
2. 如果配置了大于 0 的值，建议返回明确错误或 warning。

## 9. 弹性表格规则

弹性表格识别条件：

1. 表格位于 `JfkAbsContainerPB.ac_tables`。
2. `tbl.data_source_meta_id` 非空。
3. `tbl.rows == 1`。
4. `tbl.cell_heights.length == 1`。

弹性行数：

1. 根据数据源列的 `vals.length` 计算。
2. 所有参与渲染的列长度必须一致。
3. 行数等于一致后的 `vals.length`。

多行 cell：

1. 仅当对应字段的 `JfkValMetaPB.val_type == STRINGS` 时，读取 `JfkValPB.strs_val`。
2. `STRINGS` 类型只认 `strs_val` 显式多行。
3. 不自动按宽度折行。
4. 其他类型均默认为 1 行。

行高算法：

```text
row_height = base_cell_height * maxLineCount
```

其中：

1. `base_cell_height = tbl.cell_heights[0]`。
2. `maxLineCount` 为同一逻辑行中所有 cell 的最大行数。
3. 普通类型 cell 的行数为 1。
4. `STRINGS` 类型 cell 的行数为 `max(1, strs_val.length)`。

单行过高：

1. 如果某一逻辑行高度超过当前页 container 可用高度，应直接返回错误。
2. 错误信息需要说明 `table_id`、行号、行高、可用高度。
3. 本期不拆分单个逻辑行到多页。

分页：

1. 按逻辑行分页。
2. 当前页剩余高度不足以放下一行时，创建新实际 PDF 页。
3. 后续页使用 `container.next_page_top` 和 `container.next_page_height`。
4. 流式表格分页后，不重复标题表格。

## 10. 绝对定位元素跨页规则

对于 `JfkPagePB.texts`、`JfkPagePB.tables`、`JfkPagePB.lines`：

1. 首页使用 `x/y`。
2. 当当前模板页因为 container 被撑成后续实际页时：
   - `extend_to_next_page=true` 的元素在后续页继续渲染。
   - 文本/表格使用 `next_page_x/next_page_y`。
   - 线条使用 `next_page_x1/next_page_y1/next_page_x2/next_page_y2`。
3. `extend_to_next_page=false` 的元素只在首页渲染。

container 内的元素不使用上述跨页规则。

## 11. Logo 规则

`CompactReportTemplatePB.logo` 渲染规则：

1. 如果 `logo.png_path` 为空，则不打印 logo。
2. 如果 `logo.png_path` 非空，则读取图片。
3. 按 `LogoMetaPB.left/top/width/height` 渲染到每一个实际 PDF 页。
4. `top` 为图片顶部 y 坐标，渲染时需要转换为 PDFBox bottom 坐标。
5. 图片应缩放到配置区域内。

## 12. 后端模块设计

### 12.1 jfkrenderer 通用模块

新增目录：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkrenderer
```

建议类：

1. `JfkPdfRenderer`
   - 渲染入口。
   - 输入模板、数据源、输出路径。
   - 负责创建 `PDDocument`。

2. `JfkRenderContext`
   - 持有 `PDDocument`、当前 `PDPage`、当前 `PDPageContentStream`。
   - 持有字体、数据源 map、页码信息。
   - 管理实际页创建。

3. `JfkTemplateRenderer`
   - 遍历 `JfkTemplatePB.pages`。
   - 保证硬分页顺序。
   - 处理当前模板页被流式内容撑开后的实际页序列。

4. `JfkPageRenderer`
   - 渲染单个模板页。
   - 先准备绝对元素，再渲染 container。
   - 负责同一实际页上的 z-index 顺序策略。

5. `JfkFlowContainerRenderer`
   - 渲染 `JfkAbsContainerPB`。
   - 管理 `cursor_top`。
   - 管理流式表格分页。

6. `JfkTableRenderer`
   - 渲染固定表格。
   - 渲染弹性表格片段。
   - 负责 cell 边框、背景、文本、行高计算。

7. `JfkTextRenderer`
   - 渲染 JFK 文本框。
   - 可复用现有 `common.JfkPdfUtils` 和 `common.PdfTextRenderer` 的部分能力。

8. `JfkValueResolver`
   - 根据 `JfkElementMetaPB` 解析文本/表格 cell 的值。
   - 处理 `STATIC`、`JFK_DATA_SOURCE`、页码等。

9. `JfkFontRegistry`
   - 根据 `ReportProperties.compact.font` 加载字体。
   - 后续可扩展 bold 字体。

10. `JfkImageRenderer`
    - 渲染 logo。
    - 后续可扩展签名和图片类型。

### 12.2 compactreport 模块

目录：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport
```

建议类：

1. `CompactMonitoringReportGenerator`
   - 实现 `MonitoringReportGenerator`。
   - `version()` 返回 `compact`。
   - `generate(...)` 调用 `CompactReportService`。
   - 成功时返回 `rotationDegree=0`。

2. `CompactReportService`
   - 编排 compact 报表生成。
   - 加载模板。
   - 准备数据源输入。
   - 调用 `JfkDataService`。
   - 调用 `JfkPdfRenderer`。

3. `CompactReportTemplateLoader`
   - 使用 `ReportProperties + ResourceLoader`。
   - 加载 `jingyi.report.compact.template`。
   - 解析 `CompactReportTemplatePB` text format。

4. `CompactReportDataSourceBuilder`
   - 根据 `MonitoringReportRequest` 和模板收集数据源。
   - 本期至少支持：
     - `patient_info`
     - `monitoring_time_range`

## 13. 接入 ReportService

现有 `ReportService.getMonitoringReport(...)` 已通过 `MonitoringReportGenerator` 路由版本。

compact 接入后：

1. `application.properties` 配置：

```properties
jingyi.report.monitoring.version=compact
```

2. Spring 注入 `CompactMonitoringReportGenerator`。
3. `ReportService` 根据版本选择 compact generator。
4. compact generator 生成 PDF 到 `MonitoringReportRequest.reportPath`。
5. 返回 `MonitoringReportResult(returnCode, 0)`。

## 14. 错误处理

渲染阶段遇到以下情况应返回明确错误，不生成错误 PDF：

1. compact 模板资源不存在。
2. compact 模板 text format 解析失败。
3. 字体资源加载失败。
4. container 同时配置 `ac_tables` 和 `ac_texts`。
5. 弹性表格数据源缺失。
6. 弹性表格参与列长度不一致。
7. 弹性表格单行高度超过一页可用高度。
8. 表格 `cell_widths.length != cols`。
9. 固定表格 `cell_heights.length != rows`。
10. `h_replicas > 0` 且本期未实现该能力。

错误信息应尽量包含模板定位信息：

1. `page_index`
2. `container_id`
3. `table_id`
4. `field_id`
5. 具体长度或尺寸

## 15. 本期验收标准

功能验收：

1. 配置 `jingyi.report.monitoring.version=compact` 后，走 compact 报表生成逻辑。
2. 能加载 `report_compact.pb.txt`。
3. 能生成 A4 横向 PDF。
4. 能渲染标题、患者信息、时间轴表格、生命体征标题。
5. 能渲染 `JfkAbsContainerPB.ac_tables` 中连续表格。
6. 能根据 `monitoring_time_range` 输出 24 小时时间轴。
7. container 内表格能从上往下流式排布。
8. 流式表格数据超过当前页时能分页。
9. 后续页能渲染 `extend_to_next_page=true` 的绝对定位 header 元素。
10. logo path 为空时不打印 logo。
11. 返回给前端的 `rotationDegree` 为 0。

质量验收：

1. 通用 JFK 渲染逻辑放在 `jfkrenderer`。
2. compact 业务编排放在 `compactreport`。
3. 不在 `ReportService` 内写 compact PDF 细节。
4. 不在 compact 逻辑内硬编码 PDF 坐标，坐标来自 `report_compact.pb.txt`。
5. 有单元测试覆盖主要布局计算和错误处理。

## 16. 测试要求

建议测试：

1. `CompactReportTemplateLoaderTest`
   - 能解析 `report_compact.pb.txt`。

2. `JfkValueResolverTest`
   - 能解析静态值。
   - 能解析数据源值。
   - 能解析页码值。

3. `JfkTableLayoutTest`
   - 固定表格宽高计算。
   - `cell_widths`/`cell_heights` 长度校验。

4. `JfkElasticTableLayoutTest`
   - `STRINGS` 多行行高计算。
   - 普通类型按 1 行计算。
   - 数据源列长度不一致时报错。
   - 单行高度超过页可用高度时报错。

5. `JfkFlowContainerLayoutTest`
   - 多个 `ac_tables` 顺序排布。
   - 表格分页。
   - 后续页使用 `next_page_top/next_page_height`。
   - `ac_texts` 不影响光标。

6. `CompactMonitoringReportGeneratorTest`
   - `version()` 为 `compact`。
   - 成功时 `rotationDegree=0`。
   - 模板缺失时报错。

7. 集成测试或手工验收：
   - 使用当前 `report_compact.pb.txt` 生成 PDF。
   - 对照 `护理单.pdf` 的首页布局检查标题、患者信息、时间轴。

## 17. 实施阶段建议

### 阶段 1：基础渲染

目标：

1. `CompactMonitoringReportGenerator` 接入。
2. `CompactReportTemplateLoader` 完成。
3. `JfkPdfRenderer` 能创建 A4 横向 PDF。
4. 能渲染绝对定位 `texts`。
5. 能渲染固定 `tables`。
6. 能加载 `patient_info` 和 `monitoring_time_range`。

### 阶段 2：container 流式表格

目标：

1. 支持 `JfkAbsContainerPB.ac_tables`。
2. 支持多个表格从上往下排布。
3. 支持固定高度表格分页。
4. 支持后续页 `next_page_top/next_page_height`。

### 阶段 3：弹性表格

目标：

1. 支持数据源驱动可变行数。
2. 支持 `STRINGS` 显式多行。
3. 支持 `row_height = base_cell_height * maxLineCount`。
4. 完成长度不一致、单行过高等错误处理。

### 阶段 4：compact 首批业务数据

目标：

1. 完成“神经系统”前面一部分数据源。
2. 补齐对应 `report_compact.pb.txt` 模板片段。
3. 生成可对照 `护理单.pdf` 的首批内容。

### 阶段 5：后续扩展

目标：

1. 使用 `mon_group.table_id` 支持监护参数组。
2. 查询 `patient_monitoring_records`。
3. 支持更多观察项表格。
4. 支持图片/签名。
5. 视需要支持自动折行。
6. 视需要支持重复标题行。

## 18. 已确认决策

1. `logo.png_path` 为空时不打印 logo。
2. 本期一个 container 只能用 `ac_tables` 或只能用 `ac_texts`，不能混排。
3. `JfkAcTextPB` 的实际 top 为 `container.top/next_page_top + acText.offsetTop`，不影响光标。
4. 弹性表格仅当对应数据类型为 `STRINGS` 时读取 `strs_val` 显式多行，不自动折行；其他类型默认为 1 行。
5. 行高算法为 `row_height = base_cell_height * maxLineCount`。
6. 数据源列长度不一致时直接报错，错误指出 `table_id`、`field_id`、长度。
7. 单行高度超过一页时直接报错，提示该行内容过高。
8. 流式表格分页后，“生命体征”这类标题表格不自动重复。
9. 多模板页顺序采用方案 A：当前模板页的全部扩展页生成完后，再生成下一个模板页。
10. 本期不考虑 `mon_group.table_id`。
11. 本期不考虑基于 `mon_group` 补齐观察项数据源参数。
12. compact 报表 `rotationDegree` 直接返回 0。

## 19. 下期遗留问题

1. 是否需要 `JfkAcElementPB oneof` 来支持 container 内表格和文本混排。
2. 是否需要自动按宽度折行。
3. 是否需要标题行或分组标题跨页重复。
4. 是否需要支持 `h_replicas > 0`。
5. 是否需要支持图片、护理签名、医生签名。
6. `mon_group.table_id` 的完整数据源协议。
7. “神经系统”完整字段范围、param_code 与展示顺序。
8. compact 模板是否需要和前端 designer 双向兼容。
