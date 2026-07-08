# AH2 护理记录单多医院扩展与休宁县人民医院适配需求

更新时间：2026-06-02

## 背景

当前后端 `ah2report` 目录和模板 `report_template_ah2.pb.txt` 仅适配“安徽省第二人民医院 ICU 护理记录单”：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/ah2report
src/main/resources/config/pbtxt/report_template_ah2.pb.txt
```

第二个需要支持的医院是“休宁县人民医院”。休宁版本与省二院版本在纸张、表头、表格列结构、行数、部分取数逻辑和映射规则上均不同，因此需要先扩展当前 AH2 报表架构，使医院定制能力可以沉到医院专属实现中。

本文件只整理需求、实现边界和决策状态，本次不做代码实现。

## 总目标

1. 将现有 AH2 护理记录单架构扩展为支持多医院定制。
2. 保留安徽省第二人民医院现有行为，不能破坏当前 `ah2` 报表生成。
3. 新增休宁县人民医院版本：
   - A4 横向。
   - 使用休宁专属模板。
   - 支持休宁表格细分列。
   - 支持休宁专属观察项、管道、护理记录和签名取数逻辑。
4. 医院特定实现可放在：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/ah2report/impl
```

## 参考资料

休宁表格尺寸参考历史中间结果：

```text
/Users/guzhenyu/gDocs/jingyi/休宁县人民医院/xiuning_report_template.pb.txt
```

该中间结果中的核心布局结论：

1. 纸张为 A4 横向。
2. 线段包裹的正文后 28 行为一个固定行高表格。
3. 表头可由线条和文本组合表达。
4. 休宁版本不要求使用 `JfkAbsContainerPB`。

## 影响范围

### 后端报表代码

当前相关目录：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/ah2report
```

建议扩展方向：

1. 抽象“医院版本”的模板加载、数据收集、行转换、映射、分页和渲染差异。
2. 当前省二院逻辑作为默认实现或 `impl/Ah2...` 实现保留。
3. 休宁逻辑新增为 `impl/Xiuning...` 实现。
4. 公共能力保留在 `ah2report` 根目录，例如：
   - PDF 绘制基础工具。
   - 公共分页模型。
   - 签名图解析和绘制。
   - 通用观察项值格式化。
   - 通用患者信息读取。

### 后端模板

现有模板：

```text
src/main/resources/config/pbtxt/report_template_ah2.pb.txt
```

后续实现时需要将省二院模板迁移到医院模板目录：

```text
src/main/resources/config/pbtxt/hospitals/report_template_ah2.pb.txt
```

新增休宁模板路径：

```text
src/main/resources/config/pbtxt/hospitals/report_template_xiuning.pb.txt
```

休宁模板短期继续使用 `ReportTemplateAh2PB`，以复用现有 AH2 渲染链路；不改用 `JfkTemplatePB`。历史 JFK 风格中间稿只作为 A4 横向尺寸和表格坐标参考。

休宁模板最终列宽、字号和表头坐标以历史中间稿为初始基准：

```text
/Users/guzhenyu/gDocs/jingyi/休宁县人民医院/xiuning_report_template.pb.txt
```

生成首版 PDF 后，需要通过截图或打印样张确认，再固定模板参数。

配置项：

```properties
jingyi.report.ah2.template=classpath:/config/pbtxt/hospitals/report_template_ah2.pb.txt
```

休宁部署时配置为：

```properties
jingyi.report.ah2.template=classpath:/config/pbtxt/hospitals/report_template_xiuning.pb.txt
```

旧路径兼容：

1. 默认配置和 `ReportProperties` 应迁移到 `hospitals` 目录。
2. 如存在多环境灰度发布需求，可短期保留旧路径文件 `classpath:/config/pbtxt/report_template_ah2.pb.txt` 作为兼容副本。
3. 旧路径兼容仅用于灰度过渡，后续确认所有环境配置已迁移后再删除。

### 数据库

需要扩展：

```text
src/main/resources/config/db/schema.postgresql.sql
```

在 `balance_stats_shifts.start_hour` 后新增字段：

```sql
half_day_shift_hours INTEGER
```

字段语义：

1. 用于半日汇总行偏移。
2. 如果 `half_day_shift_hours` 非空且 `0 < half_day_shift_hours < 24`，则半日汇总使用该值。
3. 否则回退为 `12`，保持现有省二院行为。
4. 数据库不加硬约束，仅允许 `NULL`。
5. 前端和后端校验 `1..23`；历史或异常数据由后端统一回退 12。

相关实体和接口也需要同步扩展：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/entity/shifts/BalanceStatsShift.java
jingyi_icis_frontend/src/pages/home/tabs/settings/BasicFeaturesConfig/components/BalanceStatsShift/index.tsx
```

`ReportProperties` 也需要同步扩展 `Ah2.variant` 字段，并将 AH2 模板默认路径更新到 `hospitals` 目录。

### 前端配置

需要在出入量统计班次配置页面支持编辑：

```text
half_day_shift_hours
```

前端建议行为：

1. 字段可为空。
2. 非空时限制为整数。
3. 有效范围建议为 `1` 到 `23`。
4. 用户清空字段时保存 `NULL`。
5. 前端校验有效范围，非法值阻止保存。
6. 后端同样校验 `1..23`；历史或异常数据由后端回退 12。

## 多医院架构需求

### 版本选择

报表生成入口通过配置选择渲染链路、模板和医院取数逻辑。

总入口版本：

```properties
jingyi.report.monitoring.version=ah2
```

语义：

1. `jingyi.report.monitoring.version=ah2` 表示使用 AH2 护理记录单渲染链路。
2. 该配置只决定使用 AH2 模式，不决定具体医院取数逻辑。

模板选择：

```properties
jingyi.report.ah2.template=classpath:/config/pbtxt/hospitals/report_template_ah2.pb.txt
```

语义：

1. 省二院使用 `classpath:/config/pbtxt/hospitals/report_template_ah2.pb.txt`。
2. 休宁使用 `classpath:/config/pbtxt/hospitals/report_template_xiuning.pb.txt`。
3. `src/main/resources/config/pbtxt/report_template_ah2.pb.txt` 后续应迁移到 `src/main/resources/config/pbtxt/hospitals/report_template_ah2.pb.txt`。

医院取数逻辑选择：

```properties
jingyi.report.ah2.variant=ah2
```

可选值：

| 值 | 含义 |
| --- | --- |
| `ah2` | 安徽省第二人民医院取数和行转换逻辑 |
| `xiuning` | 休宁县人民医院取数和行转换逻辑 |

最终选择规则：

1. 安徽省第二人民医院：继续使用现有 AH2 逻辑。
2. 休宁县人民医院：使用休宁专属模板和取数逻辑。
3. `jingyi.report.monitoring.version` 不为 `ah2` 时，不进入 AH2 医院版本选择。
4. 为已知 `variant` 建立默认模板路径和模板标识校验。
5. `jingyi.report.ah2.template` 和 `jingyi.report.ah2.variant` 不匹配时 fail-fast。
6. fail-fast 错误信息必须包含当前 `variant`、当前模板路径和期望模板路径。

默认匹配关系：

| `variant` | 规范期望模板路径 |
| --- | --- |
| `ah2` | `classpath:/config/pbtxt/hospitals/report_template_ah2.pb.txt` |
| `xiuning` | `classpath:/config/pbtxt/hospitals/report_template_xiuning.pb.txt` |

过渡期如保留旧路径兼容副本，`variant=ah2` 可额外允许：

```text
classpath:/config/pbtxt/report_template_ah2.pb.txt
```

该旧路径仅作为 `ah2` 的兼容别名，不可作为 `xiuning` 或其他 `variant` 的合法模板。

如果模板结构支持稳定标识字段，除路径外还应校验模板标识；如果现有 `ReportTemplateAh2PB` 不支持模板标识，则短期至少做路径校验。

实现时应避免在 `Ah2ReportService` / `Ah2ReportData` 中继续累积医院专属 `if/else`。建议引入类似策略接口：

```text
Ah2HospitalReportAdapter
```

职责建议包括：

1. 返回医院版本标识。
2. 返回模板资源路径或模板对象。
3. 构建表头患者信息。
4. 收集并转换当前医院的行数据。
5. 提供观察项值映射。
6. 提供管道分类和管道状态展示规则。
7. 提供汇总行时间规则。
8. 提供页面和分页约束。

### 公共逻辑

下列逻辑应尽量复用：

1. PDF 字体加载。
2. PDFBox 绘制文本、线条、表格。
3. 基础患者信息查询。
4. 护理签名图逻辑。
5. `patient_monitoring_records` 值的基础格式化。
6. 护理记录查询。
7. 出入量汇总的基础计算。
8. 报表生成锁和缓存失效逻辑。

### 医院专属逻辑

下列逻辑应允许医院覆盖：

1. 纸张大小。
2. 表头字段和坐标。
3. 表格列结构。
4. 每列绑定的数据源。
5. 观察项枚举映射。
6. 管道分类和状态展示规则。
7. 半日汇总行偏移。
8. 行合并和分页规则。

## 休宁模板需求

### 纸张

1. A4 横向。
2. PDF 坐标单位为 pt。
3. A4 横向尺寸参考现有 JFK/PDF 约定：

```text
841.89 x 595.28
```

### 页面标题

标题：

```text
休宁县人民医院
ICU 护 理 记 录 单
```

### 患者表头

原省二院表头：

```text
床号 / 姓名 / 住院号 / 年龄 / 诊断
```

休宁表头调整为：

```text
科室 / 床号 / 姓名 / 性别 / 年龄 / 住院号
诊断
```

要求：

1. 诊断另起一行。
2. 坐标按 A4 横向重新调整。
3. 科室、床号、姓名、性别、年龄、住院号在第一行。
4. 诊断在第二行，可占用较宽区域。
5. 诊断字段短期复用现有 AH2 头部使用的诊断来源；如果省二院当前会拼接手术信息，休宁也先保持一致。

### 主表

1. 正文固定 28 行。
2. 行高固定。
3. 超出 28 行时分页，不截断临床记录。
4. 后续页重复页眉和表头。
5. 入量和出量列按本文“入量和出量”章节取数。

### 行生成、合并和分页

行生成以所有数据源的事件时间按分钟聚合：

| 数据源 | 事件时间字段 |
| --- | --- |
| `patient_monitoring_records` | `effective_time` |
| `patient_tube_records` 插管事件 | `inserted_at` |
| `patient_tube_status_records` | `recorded_at` |
| 每 4 小时管道统计 | 固定本地时钟 `4:00`、`8:00`、`12:00`、`16:00`、`20:00`、次日 `0:00` |
| `nursing_records` | `effective_time` |

规则：

1. 同一分钟内的观察项、管道状态和护理记录合并到同一逻辑时间点。
2. 若同一分钟有多条同类记录，除本文明确要求 `/` 合并的管道字段外，按稳定顺序拆成多行。
3. 单个时间点存在多条引流管或动静脉置管 entry 时，不按管道条数拆行；同类管道字段按 `/` 合并到同一逻辑行。
4. 同一时间的基础观察项只在第一行展示。
5. 拆分出的后续行只填护理记录延续内容和必要签名；管道字段优先在对应统计时刻的首行展示。
6. 纯观察项或纯管道拆分行没有护理记录时，签名留空。
7. 每页最多 28 行；超过 28 行后分页。
8. 护理记录长文本折行占用的续行也计入 28 行。

稳定顺序建议：

1. 先按分钟升序。
2. 同一分钟内按原始事件时间升序。
3. 原始事件时间相同则按数据源优先级和记录 `id` 升序保证输出稳定。
4. 数据源优先级建议为：观察项、引流管、动静脉置管、护理记录。

### 表格列结构

休宁表格列按如下顺序组织。

| 序号 | 分组 | 列 | 数据来源或说明 |
| --- | --- | --- | --- |
| 1 | 日期 | 日期 | `YYYY-MM-DD` |
| 2 | 时间 | 时间 | `HH:mm` |
| 3 | 神志 | 神志 | `consciousness`，枚举映射 |
| 4 | 瞳孔-左 | 大小mm | `left_pupil_size` |
| 5 | 瞳孔-左 | 反射 | `left_pupil_reflex` |
| 6 | 瞳孔-右 | 大小mm | `right_pupil_size` |
| 7 | 瞳孔-右 | 反射 | `right_pupil_reflex` |
| 8 | 基本生命体征 | 体温℃ | `temperature` |
| 9 | 基本生命体征 | 心率次/分 | `hr` |
| 10 | 基本生命体征 | 呼吸次/分 | `respiratory_rate` |
| 11 | 基本生命体征 | 无创血压mmHg | `nibp_s` / `nibp_d`，格式 `收缩压/舒张压` |
| 12 | 基本生命体征 | 血氧饱和度 | `Spo2` |
| 13 | 有创血压 | 有创血压mmHg | `ibp_s` / `ibp_d`，格式 `收缩压/舒张压` |
| 14 | 中心静脉压 | 中心静脉压 | `cvp` |
| 15 | 氧疗 | 吸氧方式 | `oxygen_delivery_method`，枚举映射 |
| 16 | 氧疗 | 氧流量L/分 | `oxygen_flow_rate` |
| 17 | 氧疗 | 氧浓度% | `oxygen_concentration` |
| 18 | 机械通气 | 人工气道 | `artificial_airway_method`，枚举映射 |
| 19 | 机械通气 | 深度cm | `vent_tube_plant_depth` |
| 20 | 机械通气 | 模式 | `vent_respiratory_mode`，枚举映射 |
| 21 | 机械通气 | 潮气量ml | `vent_inspired_tidal_volume` |
| 22 | 机械通气 | 频率次/分 | `vent_respiratory_rate` |
| 23 | 机械通气 | 氧浓度% | `vent_fio2` |
| 24 | 机械通气 | PEEP/PSV cmH2O | `vent_PEEP` / `vent_PS` |
| 25 | 机械通气 | 气囊压cmH2O | `vent_cuff_pressure` |
| 26 | 引流管 | 名称 | 管道记录，原文；多管道用 `/` 合并 |
| 27 | 引流管 | 刻度 | 管道状态 `置入长度` |
| 28 | 引流管 | 引流液颜色 | 管道状态 `颜色`，原文 |
| 29 | 引流管 | 护理 | 管道状态 `护理`，原文 |
| 30 | 动静脉置管 | 名称 | 管道记录和属性，原文；多管道用 `/` 合并 |
| 31 | 动静脉置管 | 刻度 | 管道状态 `置入长度` |
| 32 | 动静脉置管 | 护理 | 管道状态 `护理`，原文 |
| 33 | 血糖 | 血糖 | `blood_glucose` |
| 34 | 评分 | Braden评分 | `patient_scores.score_group_code = braden` |
| 35 | 评分 | Morse跌倒评估 | `patient_scores.score_group_code = morse` |
| 36 | 评分 | 自理评估 | `patient_scores.score_group_code = activities_of_daily_living_assessment` |
| 37 | 评分 | 导管风险评估 | `patient_scores.score_group_code = catheter_slippage` |
| 38 | 评分 | RASS镇静评估 | `patient_scores.score_group_code = rass` |
| 39 | 评分 | CPOT/NRS | `patient_scores.score_group_code = frs_v2` |
| 40 | 入量 | 项目 | 执行用药项目，参考 `AH2P_MED_EXEC` |
| 41 | 入量 | 用法 | 根据 `administration_routes.intake_type_id` 归类 |
| 42 | 入量 | 量 | `MP_HOURLY_INTAKE` 统计值 |
| 43 | 出量 | 项目 | 出量参数项目，多个用 `+` 连接 |
| 44 | 出量 | 用法 | 性状，多个用 `+` 连接 |
| 45 | 出量 | 量 | `MP_HOURLY_OUTPUT`、`stool_volume`，或二者用 `+` 合并 |
| 46 | 护理 | 吸痰 | `suction` |
| 47 | 护理 | 痰量 | `sputum_amount` |
| 48 | 护理 | 痰液颜色 | `sputum_color` |
| 49 | 护理 | 痰液性状 | `sputum_consistency` |
| 50 | 护理 | 约束部位/约束情况 | `restraint` / `restraint_status` |
| 51 | 护理 | 扣背/振动 | `back_percussion` |
| 52 | 护理 | 皮肤护理 | `skin_care`，枚举映射 |
| 53 | 护理 | 其他护理 | `nursing_actions` 多选项 |
| 54 | 护理 | 体位 | `body_position`，枚举映射 |
| 55 | 血气 | PH | `bga_ph` |
| 56 | 血气 | PCO2 | `bga_pco2` |
| 57 | 血气 | PO2 | `bga_po2` |
| 58 | 血气 | SpO2 | `bga_so2` |
| 59 | 血气 | Lac | `bga_lac` |
| 60 | 病情变化及护理措施 | 病情变化及护理措施 | `nursing_records.content` |
| 61 | 签名 | 签名 | 复用省二院签名逻辑 |

### 列宽调整要求

相对图片或旧中间稿：

1. `管道监测/刻度` 和 `动静脉置管/刻度` 从两个文本宽度调整为一个文本宽度，整体 `-2`。
2. `吸氧方式`、`氧流量` 上层标题改为 `8. 氧疗`，新增 `氧浓度%`，整体 `+1`。
3. 多出的一个文本宽度扩到 `病情变化及护理措施`，整体 `+1`。
4. `护理` 分组在 `吸痰` 后新增 `痰量`、`痰液颜色`、`痰液性状` 三列，每列宽度 `15`；`护理` 分组总宽度增加 `45`。
5. `护理` 分组后新增 `血气` 分组，包含 `PH`、`PCO2`、`PO2`、`SpO2`、`Lac` 五列，每列宽度 `15`，分组总宽度 `75`。
6. 上述新增 `120` 宽度全部从 `病情变化及护理措施` 列扣减。
7. 第二个 `sub_page` 中，`XN_DRAINAGE_TUBE_NAME` 宽度为 `60`，`XN_DRAINAGE_TUBE_DEPTH` 宽度为 `35`，`XN_VASCULAR_TUBE_NAME` 宽度为 `60`，`XN_VASCULAR_TUBE_DEPTH` 宽度为 `35`，缩减宽度优先转入 `XN_INTAKE_ITEM`。
8. 第二个 `sub_page` 在管道监测后新增 `评分` 分组，去掉 VTE，包含 6 个评分列，每列宽度 `10`，总宽度 `60`；该宽度从 `XN_INTAKE_ITEM` 扣减。

实现模板时需要确保最终总宽度仍在 A4 横向页面可打印范围内。

## 休宁取数需求

### 日期和时间

1. 日期格式：`YYYY-MM-DD`，例如 `2026-05-28`。
2. 时间格式：`HH:mm`，例如 `17:00`。
3. 日期和时间来自当前逻辑行对应的分钟时间。
4. 同一分钟拆成多行时，日期和时间只在第一行展示；后续拆分行留空。
5. 护理记录长文本折行占用多行时，日期和时间只在首行展示；续行留空。

### 观察项基础规则

来源表：

```text
patient_monitoring_records
```

字段：

```text
monitoring_param_code
effective_time
param_value / param_value_str
```

建议取值规则：

1. 优先使用现有 AH2 逻辑中对观察项值的格式化能力。
2. 若 `param_value_str` 非空，优先展示 `param_value_str`。
3. 若 `param_value_str` 为空但 `param_value` 存在，则按观察项 `ValueMeta` 解码和格式化。
4. 所有映射前先 `trim`。
5. 枚举未命中时显示原值并记录 `log.warn`。
6. 管道分类这类明确要求“无效 entry 过滤”的场景不展示原值，按过滤处理。
7. 参数 code 使用现有配置中的精确 code；休宁配置确认为 `Spo2` 时，不做大小写自动匹配，避免误取。

### 神志映射

参数：

```text
consciousness
```

| 原值 | 输出 |
| --- | --- |
| 清醒 | 1 |
| 嗜睡 | 2 |
| 意识模糊 | 3 |
| 昏睡 | 4 |
| 浅昏迷 | 5 |
| 深昏迷 | 6 |
| 镇静 | 7 |

### 瞳孔

| 列 | 参数 |
| --- | --- |
| 左/大小mm | `left_pupil_size` |
| 左/反射 | `left_pupil_reflex` |
| 右/大小mm | `right_pupil_size` |
| 右/反射 | `right_pupil_reflex` |

### 基本生命体征

| 列 | 参数 | 格式 |
| --- | --- | --- |
| 体温℃ | `temperature` | 普通观察值 |
| 心率次/分 | `hr` | 普通观察值 |
| 呼吸次/分 | `respiratory_rate` | 普通观察值 |
| 无创血压mmHg | `nibp_s` / `nibp_d` | `收缩压/舒张压` |
| 血氧饱和度 | `Spo2` | 普通观察值 |

无创血压格式：

1. 两者都有值：`150/86`
2. 只有收缩压：`150/-`
3. 只有舒张压：`-/86`
4. 两者都缺失：空字符串。

### 有创血压和中心静脉压

| 列 | 参数 | 格式 |
| --- | --- | --- |
| 有创血压mmHg | `ibp_s` / `ibp_d` | `收缩压/舒张压` |
| 中心静脉压 | `cvp` | 普通观察值 |

有创血压单项缺失规则与无创血压一致：

1. 两者都有值：`150/86`
2. 只有收缩压：`150/-`
3. 只有舒张压：`-/86`
4. 两者都缺失：空字符串。

### 氧疗

| 列 | 参数 |
| --- | --- |
| 吸氧方式 | `oxygen_delivery_method` |
| 氧流量L/分 | `oxygen_flow_rate` |
| 氧浓度% | `oxygen_concentration` |

吸氧方式映射：

| 原值 | 输出 |
| --- | --- |
| 鼻塞 | 1 |
| 鼻导管 | 2 |
| 面罩 | 3 |
| 气管插管 | 4 |
| 气切套管 | 5 |
| 高流量治疗 | 6 |
| 无创通气 | 7 |

### 机械通气

| 列 | 参数 | 格式或映射 |
| --- | --- | --- |
| 人工气道 | `artificial_airway_method` | 枚举映射 |
| 深度cm | `vent_tube_plant_depth` | 普通观察值 |
| 模式 | `vent_respiratory_mode` | 枚举映射 |
| 潮气量ml | `vent_inspired_tidal_volume` | 普通观察值 |
| 频率次/分 | `vent_respiratory_rate` | 普通观察值 |
| 氧浓度% | `vent_fio2` | 普通观察值 |
| PEEP/PSV cmH2O | `vent_PEEP` / `vent_PS` | `peep/psv` |
| 气囊压cmH2O | `vent_cuff_pressure` | 普通观察值 |

人工气道映射：

| 原值 | 输出 |
| --- | --- |
| 经口插管 | 1 |
| 经鼻插管 | 2 |
| 气切插管 | 3 |

通气模式映射：

| 原值 | 输出 |
| --- | --- |
| V-C | 1 |
| P-C | 2 |
| V-SIMV | 3 |
| P-SIMV | 4 |
| PSV | 5 |
| BIRAP | 6 |
| BiPAP | 6 |

实现时同时兼容 `BIRAP` 和 `BiPAP`，均输出 `6`；配置展示名统一为 `BiPAP`。

PEEP/PSV 格式：

1. 两者都有值：`peep/psv`
2. 只有 PEEP：`peep/-`
3. 只有 PSV：`-/psv`
4. 两者都没有：空字符串。

### 引流管

基础查询逻辑：

```sql
select
    ptr.id,
    ptr.pid,
    ptr.tube_name,
    tts.name,
    ptsr.value,
    ptsr.recorded_at
from patient_tube_records ptr
join patient_tube_status_records ptsr on ptr.id = ptsr.patient_tube_record_id
join tube_type_statuses tts on ptsr.tube_status_id = tts.id
where ptr.is_deleted = false
  and ptsr.is_deleted = false
  and tts.is_deleted = false;
```

实际实现需要增加：

1. `pid` 约束。
2. `ptsr.recorded_at` 时间范围约束。

合并规则：

```text
List<(ptr.id, ptr.tube_name, recorded_at, Map<tts.name, ptsr.value>)>
```

合并维度：

1. `ptr.id`
2. `ptr.tube_name`
3. `ptsr.recorded_at`

同一 entry 中同一个 `tts.name` 多条记录的取值规则：

1. 如果表结构存在 `ptsr.modified_at`，按 `ptsr.modified_at` 最新取值。
2. 当前 schema 未提供 `ptsr.modified_at` 时，按 `ptsr.id` 最新取值。
3. 发生重复时记录 `log.warn`，便于回溯配置或数据问题。

引流管名称范围：

| `ptr.tube_name` |
| --- |
| 导尿管 |
| 胃管 |
| 头部引流管 |
| 胸管 |
| 腹部引流管 |
| 切口管 |
| 空肠管 |

要求：

1. `trim` 后不在“引流管名称范围”中的 entry 不应出现在“引流管”列中。
2. 名称直接显示 `ptr.tube_name` 原文，不再映射为编码。
3. 刻度取 `tts.name == "置入长度"` 的 `ptsr.value` 原文。
4. 引流液颜色取 `tts.name == "颜色"` 的 `ptsr.value` 原文，不再映射为编码。
5. 护理取 `tts.name == "护理"` 的 `ptsr.value` 原文，不再映射为编码。

同一时刻多引流管合并规则：

1. 同一个统计时刻如果存在多个有效引流管，`名称`、`刻度`、`引流液颜色`、`护理` 四列分别用 `/` 连接。
2. 连接顺序需要稳定，建议优先按 `ptr.id` 升序；若补充了出量推导出的管道，按本文“出量”章节的合并规则追加。
3. 某个管道某个字段为空时保留空位，确保四列的第 N 段仍对应同一根管道。
4. 示例：同一时刻存在 `导尿管`、`胸管`、`胃管` 三根引流管，刻度分别为空、`20`、空，颜色分别为 `黄色`、空、空，护理分别为空、空、`通畅`，则：
   - 名称：`导尿管/胸管/胃管`
   - 刻度：` /20/ `
   - 引流液颜色：`黄色/ / `
   - 护理：` / /通畅`

每 4 小时管道统计规则：

1. 不再按 `balance_stats_shifts.start_hour` 偏移；管道情况固定在本地时钟 `4:00`、`8:00`、`12:00`、`16:00`、`20:00`、次日 `0:00` 统计。
2. 例如一个 24 小时班次为 `7:00` 到次日 `7:00`，则班次内统计时刻为 `8:00`、`12:00`、`16:00`、`20:00`、次日 `0:00`、次日 `4:00`。
3. 固定统计时刻 `t` 超过当前时间时，不生成该固定统计行；例如当前本地时间为 `10:30`，当天 `12:00` 及之后的固定统计点不输出。
4. 除固定统计点外，白名单内的管道在 `patient_tube_records.inserted_at` 所在分钟也生成一条管道行，用于体现插管当时的管道及状态。
5. 插管事件行的名称来自管道本身；刻度、引流液颜色、护理从同一分钟内对应管道状态记录取最新一条；如果没有对应状态则留空。
6. 给定固定统计时刻 `t`，统计窗口为 `[t - 4 hours, t)`。
7. 满足 `patient_tube_records.inserted_at <= t < patient_tube_records.removed_at` 的管道进入固定统计；`removed_at is null` 视为无限大。
8. 如果统计时刻没有任何有效管道，则跳过该管道统计行。
9. 固定统计行名称来自有效管道本身；刻度、引流液颜色、护理从 `[t - 4 hours, t)` 内对应管道状态记录中选择离 `t` 最近的一条。
10. 因窗口右开，离 `t` 最近的一条即该窗口内 `recorded_at < t` 且 `recorded_at` 最大的记录；若同一字段存在同一 `recorded_at` 的多条状态，仍按“同一 entry 中同一个 `tts.name` 多条记录的取值规则”取最新记录。
11. 若某字段在统计窗口或插管事件分钟内没有值，则该字段为空，并在 `/` 合并时保留空位。
12. 多根管道之间的合并规则与“同一时刻多引流管合并规则”一致。

### 动静脉置管

基础查询语句与“引流管”相同。

名称显示规则：

| 条件 | 名称显示 |
| --- | --- |
| `ptr.tube_name == "中心静脉导管"` 且存在有效身体部位属性 | 身体部位属性 `pta.value` 原文 |
| `ptr.tube_name == "外周静脉针"` | `ptr.tube_name` 原文 |
| `ptr.tube_name == "PICC管"` | `ptr.tube_name` 原文 |
| `ptr.tube_name == "动脉导管"` | `ptr.tube_name` 原文 |
| `ptr.tube_name == "中长导管"` | `ptr.tube_name` 原文 |

中心静脉导管需要额外查询属性：

```sql
select
    ptr.pid,
    ptr.id,
    ptr.tube_name,
    tta.name,
    tta.display_order,
    pta.id,
    pta.value
from patient_tube_records ptr
join patient_tube_attrs pta on ptr.id = pta.patient_tube_record_id
join tube_type_attributes tta on pta.tube_attr_id = tta.id
where ptr.is_deleted = false;
```

实际实现建议补充：

1. `ptr.pid = :pid`
2. `ptr.id in (:tubeRecordIds)`
3. `tta.is_deleted = false`
4. `tta.name = '身体部位'`
5. 当前 schema 的 `patient_tube_attrs` 无 `is_deleted` 字段；若后续表结构增加软删除字段，应补充过滤有效属性。

中心静脉导管部位选择规则：

1. 先按 `ptr.id` 取该管道所有有效属性。
2. 只使用 `tta.name == '身体部位'` 的属性。
3. `pta.value` 直接输出原文，不再映射为 `1a`、`1b`、`1c`。
4. 如多个属性命中，优先取 `tta.display_order` 最小的一条。
5. `tta.display_order` 相同或不可用时，取最新修改的一条；当前 `patient_tube_attrs` 无修改时间时，按 `pta.id` 最新取值。

刻度逻辑：

1. 与引流管刻度相同。
2. 取 `tts.name == "置入长度"` 的 `ptsr.value`。

护理逻辑：

1. 与引流管护理相同。
2. 取 `tts.name == "护理"` 的 `ptsr.value` 原文，不再映射为编码。

无效 entry：

1. 中心静脉导管没有有效部位属性时，该 entry 过滤。
2. 其他未列出的 `tube_name` 过滤。

同一时刻多动静脉置管合并规则：

1. 同一个统计时刻如果存在多个有效动静脉置管，`名称`、`刻度`、`护理` 三列分别用 `/` 连接。
2. 连接顺序需要稳定，建议优先按 `ptr.id` 升序。
3. 某个管道某个字段为空时保留空位，确保三列的第 N 段仍对应同一根管道。

每 4 小时管道统计规则：

1. 与引流管相同，不按 `balance_stats_shifts.start_hour` 偏移，固定在本地时钟 `4:00`、`8:00`、`12:00`、`16:00`、`20:00`、次日 `0:00` 统计。
2. 固定统计时刻 `t` 超过当前时间时，不生成该固定统计行。
3. 除固定统计点外，白名单内的动静脉置管在 `patient_tube_records.inserted_at` 所在分钟也生成一条管道行，用于体现插管当时的管道及状态。
4. 插管事件行的名称来自“名称显示规则”；刻度和护理从同一分钟内对应管道状态记录取最新一条；如果没有对应状态则留空。
5. 给定统计时刻 `t`，统计窗口为 `[t - 4 hours, t)`。
6. 满足 `patient_tube_records.inserted_at <= t < patient_tube_records.removed_at` 的动静脉置管进入统计；`removed_at is null` 视为无限大。
7. 如果统计时刻没有任何有效动静脉置管，则跳过该管道统计行。
8. 固定统计行名称来自“名称显示规则”；刻度和护理从 `[t - 4 hours, t)` 内对应管道状态记录中选择离 `t` 最近的一条。
9. 多根管道之间的合并规则与“同一时刻多动静脉置管合并规则”一致。

### 评分

评分数据取值参考省二院 `AnhuiSecondHospitalAh2ReportData` 中护理评分实现。

1. 数据来源为 `patient_scores`。
2. 按 `patient_scores.effective_time` 归入同一分钟逻辑时间点。
3. 使用 `patient_scores.score_group_code` 匹配报表列。
4. 展示值取 `patient_scores.score_str`；如果末尾带 `分`，展示时去掉末尾 `分`。
5. 同一分钟内如果多个评分记录命中同一 `score_group_code`，按 `effective_time`、`score_group_code`、`id` 升序处理，后处理的记录覆盖先处理的记录。
6. 休宁评分分组不展示 VTE。

| 列 | `score_group_code` | 报表列 |
| --- | --- | --- |
| Braden评分 | `braden` | `AH2P_BRADEN` |
| Morse跌倒评估 | `morse` | `AH2P_MORSE` |
| 自理评估 | `activities_of_daily_living_assessment` | `AH2P_AODLS` |
| 导管风险评估 | `catheter_slippage` | `AH2P_CATHETER_SLIPPAGE` |
| RASS镇静评估 | `rass` | `AH2P_RASS` |
| CPOT/NRS | `frs_v2` | `AH2P_CPOT_NRS` |

### 血糖

| 列 | 参数 |
| --- | --- |
| 血糖 | `blood_glucose` |

### 血气

血气数据取值仿照省二院 `AnhuiSecondHospitalAh2ReportData` 的血气实现：

1. 数据来源为 `patient_bga_records` 及其 `patient_bga_record_details`。
2. 按 `patient_bga_records.effective_time` 归入同一分钟逻辑时间点。
3. 明细字段使用 `patient_bga_record_details.monitoring_param_code` 匹配报表列。
4. 明细值使用对应观察项参数的 `ValueMetaPB` 格式化。

| 列 | `monitoring_param_code` |
| --- | --- |
| PH | `bga_ph` |
| PCO2 | `bga_pco2` |
| PO2 | `bga_po2` |
| SpO2 | `bga_so2` |
| Lac | `bga_lac` |

### 入量和出量

#### 出量

时间和值：

1. 参照 `AnhuiSecondHospitalAh2ReportData` 中 `MP_HOURLY_OUTPUT` 的统计逻辑，找出对应显示时间点 `t` 与 `ml`。
2. 沿用省二院口径：`hourly_output` 记录的 `effective_time` 表示 `effective_time` 到 `effective_time + 1 hour` 的小时量，报表显示时间点为 `effective_time + 1 hour`。
3. 下文同一时间点 `t` 的非大便出量项目使用该 `t` 对应的 `MP_HOURLY_OUTPUT` 值作为“量”；大便相关量按“出量额外合并规则”处理。

引流管出量参数：

时间范围内查找 `patient_monitoring_records.monitoring_param_code` 以 `tube_ylg_` 开头的记录，例如导尿管为 `tube_ylg_dng`。

1. `项目` 使用对应 `monitoring_params.name`，不再对 `tube_ylg_dng`、`tube_ylg_wg` 等参数做特殊名称映射。
2. `小记` 和 `总计` 两个汇总行也使用同一逻辑：按对应 `monitoring_params.name` 归并汇总。
3. 多个 `tube_ylg_*` 参数的 `monitoring_params.name` 相同时，在汇总行合并为一个参数明细；名称不同时分别展示。
4. 如果无法找到对应观察项参数，或 `monitoring_params.name` 为空，使用原始 `monitoring_param_code` 兜底展示。

引流管出量填充规则：

1. `项目`：使用对应 `monitoring_params.name`，多个项目用 `+` 连接并去重。
2. `性状`：为空。
3. `量`：填同一时间点 `t` 的 `MP_HOURLY_OUTPUT`。
4. 不再根据 `tube_ylg_*` 固定映射补充“引流管”列；引流管名称、刻度、引流液颜色、护理仍由“引流管”章节的每 4 小时统计逻辑生成。

其他出量参数：

时间范围内查找以下 `patient_monitoring_records.monitoring_param_code`：

```text
gastric_fluid_volume
crrt_UF
stool_volume
stool_consistency
```

填充规则：

1. `gastric_fluid_volume` 存在时，`项目` 增加 `胃液量`。
2. `crrt_UF` 存在时，`项目` 增加 `超滤量`。
3. `stool_volume` 或 `stool_consistency` 任意存在时，`项目` 增加 `大便`。
4. `性状`：只填 `stool_consistency` 对应值；如果多个性状值进入同一时间点，用 `+` 连接并去重。
5. `量`：按“出量额外合并规则”处理。

出量额外合并规则：

1. `项目` 和 `性状` 均用 `+` 连接。
2. `tube_ylg_*`、`gastric_fluid_volume`、`crrt_UF`、`stool_volume`、`stool_consistency` 同一时间点同时存在时，`项目` 合并为一个 `+` 连接字符串。
3. 如果合并后的 `项目` 只有 `大便`，`量` 填 `stool_volume`。
4. 如果合并后的 `项目` 包含 `大便` 和其他项目，`项目` 中的 `大便` 固定放到最后，`量` 填 `MP_HOURLY_OUTPUT + "+" + stool_volume`。
5. 如果合并后的 `项目` 不包含 `大便`，`量` 仍填 `MP_HOURLY_OUTPUT`。
6. 若第 4 条中 `MP_HOURLY_OUTPUT` 或 `stool_volume` 其中一个为空，只显示非空值，不输出多余的 `+`。

#### 入量

项目：

1. 参考 `AnhuiSecondHospitalAh2ReportData` 中 `AH2P_MED_EXEC` 的执行用药项目展示逻辑。
2. 按省二院 `PatientData.medExeList` 的用药执行汇总口径取同一时间点的用药项目。
3. 同一时间点多个入量用药项目复用省二院 `AH2P_MED_EXEC` 的多行折行展示方式，不使用 `+` 合并。
4. 休宁“项目”列展示格式为：

```text
dosageGroupDisplayName(液体总量 xx ml)
```

5. `dosageGroupDisplayName` 仍参考省二院 `MedReportUtils.generateMedExeRecordSummaryList` 中的 `medConfig.getDosageGroupDisplayName(...)`。
6. `xx` 取对应有效 `MedicationDosageGroupPB` 中 `MedicationDosagePB.intake_vol_ml` 的合计值；代码中 `MedicationDosageGroupPB.md` 为 repeated，现有液体量计算也按 `md.intake_vol_ml` 求和。
7. 有效 `MedicationDosageGroupPB` 的选择顺序参考省二院逻辑：优先使用执行记录 `MedicationExecutionRecord.medication_dosage_group`，为空时回退医嘱组 `MedicationOrderGroup.medication_dosage_group`。

用法：

1. 参考省二院 `PatientData.medExeList.admin_code` 的逻辑找到用药执行记录的 `admin_code`。
2. 每次绘制报表时全局计算一次 `Map<admin_code, intake_type_id>`。
3. 基础 SQL：

```sql
select code, intake_type_id
from administration_routes
where dept_id = :deptId
  and is_valid = true;
```

4. `administration_routes` 必须按当前 `dept_id` 和 `is_valid = true` 过滤；当前表唯一索引为 `(dept_id, code)`，不能直接按全院 `code` 建 map。
5. `intake_type_id == 1` 或 `intake_type_id == 2` 时，用法填 `静脉`。
6. `intake_type_id == 3` 时，用法填 `胃肠`。
7. `intake_type_id == 4` 时，用法填 `雾化`。
8. 其他 `intake_type_id` 或未找到 `admin_code` 时，用法填 `其他`。

量：

1. 参照 `AnhuiSecondHospitalAh2ReportData` 中 `MP_HOURLY_INTAKE` 的统计逻辑。
2. 沿用省二院口径：`hourly_intake` 记录的 `effective_time` 表示 `effective_time` 到 `effective_time + 1 hour` 的小时量，报表显示时间点为 `effective_time + 1 hour`。
3. 同一时间点入量“量”填该时间点对应的 `MP_HOURLY_INTAKE` 值。

### 护理

| 列 | 参数 | 格式或映射 |
| --- | --- | --- |
| 吸痰 | `suction` | 普通观察值 |
| 痰量 | `sputum_amount` | 普通观察值 |
| 痰液颜色 | `sputum_color` | 普通观察值 |
| 痰液性状 | `sputum_consistency` | 普通观察值 |
| 约束部位/约束情况 | `restraint` / `restraint_status` | `restraint/restraint_status`；`restraint` 为多选项 |
| 扣背/振动 | `back_percussion` | 普通观察值 |
| 皮肤护理 | `skin_care` | 枚举映射 |
| 其他护理 | `nursing_actions` | 多选项，值来源为 `mvPb.getValuesList()`；逐项映射后用 `,` 连接 |
| 体位 | `body_position` | 枚举映射 |

约束部位映射：

`restraint` 对应的 `patient_monitoring_records` 值为多选项，实际选项存放在 `MonitoringValuePB.values`，即实现中的 `mvPb.getValuesList()`。生成报表时逐个读取 `mvPb.getValues(i)`，按下表映射为 `1` 到 `4`，再用 `,` 连接后填入单元格，例如选择“上肢”和“胸部”时输出 `1,4`。

| 原值 | 输出 |
| --- | --- |
| 上肢 | 1 |
| 下肢 | 2 |
| 上下肢 | 3 |
| 胸部 | 4 |

约束情况映射：

| 原值 | 输出 |
| --- | --- |
| 良好 | 1 |
| 青紫 | 2 |
| 破损 | 3 |
| 骨折 | 4 |

皮肤护理映射：

| 原值 | 输出 |
| --- | --- |
| 皮肤完整/气垫床 | 1a |
| 皮肤完整/温水擦浴 | 1b |
| 皮肤完整/保护膜应用 | 1c |
| 皮肤不完整/贴膜 | 2a |
| 皮肤不完整/伤口处理 | 2b |

其他护理动作映射：

`nursing_actions` 对应的 `patient_monitoring_records` 值为多选项，实际选项存放在 `MonitoringValuePB.values`，即实现中的 `mvPb.getValuesList()`。生成报表时逐个读取 `mvPb.getValues(i)`，按下表映射为 `1` 到 `12`，再用 `,` 连接后填入单元格。

| 原值 | 输出 |
| --- | --- |
| 口腔护理 | 1 |
| 会阴护理 | 2 |
| 面部清洁和梳头 | 3 |
| 床上擦洗 | 4 |
| 床上洗头 | 5 |
| 足部清洁 | 6 |
| 指甲护理 | 7 |
| 协助进水进食 | 8 |
| 擦背 | 9 |
| 更衣 | 10 |
| 床上使用便器 | 11 |
| 失禁护理 | 12 |

体位映射：

| 原值 | 输出 |
| --- | --- |
| 平卧 | 1 |
| 左侧卧位 | 2.a |
| 右侧卧位 | 2.b |
| 半卧 | 3 |
| 俯卧位 | 4 |

### 病情变化及护理措施

来源：

```text
nursing_records.content
```

要求：

1. 使用护理记录内容。
2. 和对应行时间关联。
3. 护理记录过长时，按“病情变化及护理措施”列宽折行。
4. 折行后占用多行，每一折行仍计入 28 行正文行数。
5. 同一护理记录的日期、时间和签名只在首行展示。
6. 同一护理记录的续行日期、时间和签名留空。

### 签名

签名图加载逻辑与安徽省第二人民医院相同。

要求：

1. 复用现有账号签名图加载逻辑。
2. 护理记录行使用 `nursing_records.created_by` 对应账号签名。
3. 汇总行使用省二院现有汇总签名逻辑。
4. 纯观察项/管道行无护理记录时签名留空，除非已有省二院逻辑可明确取到记录人。
5. 同一分钟拆分多行时，护理记录签名只在护理记录内容首行展示。

## 半日汇总行需求

当前省二院半日汇总逻辑使用：

```text
start_hour + 12 hours
```

休宁需求：

1. 在 `balance_stats_shifts.start_hour` 后新增 `half_day_shift_hours`。
2. 休宁先保留省二院既有半日小计和全天总计机制。
3. 入量/出量明细按本文“入量和出量”章节取数；汇总行继续沿用省二院既有出入量汇总机制，需要确保分页和签名不异常。
4. 半日小计结束时间调整为：

```text
if half_day_shift_hours != null and 0 < half_day_shift_hours < 24:
    halfDayHours = half_day_shift_hours
else:
    halfDayHours = 12
```

5. 半日小计时间点使用：

```text
balanceStartUtc + halfDayHours
```

6. `half_day_shift_hours` 调整的是 `REPORT_TEMPLATE_AH2_HALF_DAY_SUMMARY` 的结束时间。
7. `REPORT_TEMPLATE_AH2_FULL_DAY_SUMMARY` 仍使用班次结束时间。
8. 省二院在字段为空时保持原有 12 小时行为。

## 数据库和接口需求

### schema.postgresql.sql

表：

```text
balance_stats_shifts
```

新增列：

```sql
half_day_shift_hours INTEGER
```

数据库层不增加 check constraint；字段允许 `NULL`。

建议注释：

```sql
COMMENT ON COLUMN balance_stats_shifts.half_day_shift_hours IS '半日汇总相对start_hour的小时偏移；为空或非法时默认12小时';
```

### 后端实体和接口

需要同步扩展：

1. `BalanceStatsShift` entity。
2. 相关 request/response proto 或 DTO。
3. 新增/更新出入量统计班次接口。
4. 历史数据兼容，已有记录 `half_day_shift_hours` 为空时默认 12。
5. `ReportProperties.Ah2` 增加 `variant` 字段，默认值为 `ah2`。
6. `ReportProperties.Ah2.template` 默认路径从 `classpath:/config/pbtxt/report_template_ah2.pb.txt` 调整为 `classpath:/config/pbtxt/hospitals/report_template_ah2.pb.txt`。

### 配置文件

需要同步更新：

```text
src/main/resources/application.properties
src/main/resources/application-prod.properties
```

配置项：

```properties
jingyi.report.monitoring.version=ah2
jingyi.report.ah2.template=classpath:/config/pbtxt/hospitals/report_template_ah2.pb.txt
jingyi.report.ah2.variant=ah2
```

休宁部署配置：

```properties
jingyi.report.monitoring.version=ah2
jingyi.report.ah2.template=classpath:/config/pbtxt/hospitals/report_template_xiuning.pb.txt
jingyi.report.ah2.variant=xiuning
```

### 前端配置

页面：

```text
jingyi_icis_frontend/src/pages/home/tabs/settings/BasicFeaturesConfig/components/BalanceStatsShift/index.tsx
```

新增编辑字段：

```text
half_day_shift_hours
```

建议显示文案：

```text
半日汇总小时
```

建议说明：

```text
为空时默认12小时；有效范围1-23。
```

保存规则：

1. 用户未配置或清空时保存 `NULL`。
2. 非空时必须是 `1..23` 的整数。
3. 前端校验失败时阻止保存。
4. 后端对 `NULL` 或非法值统一按 12 回退。

## 非目标

本次需求整理不包含以下实现：

1. 不修改 Java 代码。
2. 不修改 SQL schema。
3. 不修改前端页面。
4. 不生成休宁实际 PDF。
5. 不实现本文新增的引流管、动静脉置管、入量和出量取数逻辑。
6. 不调整省二院现有模板布局内容；省二院模板文件路径迁移到 `hospitals` 目录属于后续实现范围。

## 验收标准

后续实现完成后建议至少满足：

1. 省二院现有 AH2 报表输出保持不变。
2. `jingyi.report.monitoring.version=ah2` 时进入 AH2 渲染链路。
3. 休宁版本可通过 `jingyi.report.ah2.variant=xiuning` 选择。
4. `jingyi.report.ah2.template` 可配置省二院或休宁模板路径。
5. `jingyi.report.ah2.template` 和 `jingyi.report.ah2.variant` 不匹配时 fail-fast。
6. fail-fast 错误信息包含当前 `variant`、当前模板路径和期望模板路径。
7. 休宁模板为 A4 横向。
8. 休宁表头显示科室、床号、姓名、性别、年龄、住院号，诊断另起一行。
9. 休宁主表每页 28 行固定行高。
10. 休宁观察项映射按本文档输出。
11. 引流管和动静脉置管无效 entry 被过滤，有效管道按每 4 小时统计并在同一时刻用 `/` 合并。
12. 入量和出量列按本文档输出项目、用法/性状和量。
13. 休宁保留省二院既有半日小计和全天总计机制；入量/出量明细存在时汇总行不应影响分页和签名。
14. 半日小计在 `half_day_shift_hours` 有效时使用该字段，否则使用 12；全天总计仍使用班次结束时间。
15. 前端可以配置 `half_day_shift_hours`。
16. 休宁和省二院实现没有互相污染的医院专属硬编码。

## 已决策

1. 医院版本选择拆成三层配置：
   - `jingyi.report.monitoring.version=ah2` 决定使用 AH2 护理记录单渲染链路。
   - `jingyi.report.ah2.template` 决定使用哪个 `ReportTemplateAh2PB` 模板。
   - `jingyi.report.ah2.variant=ah2|xiuning` 决定使用哪套取数和行转换逻辑。
2. 省二院模板迁移到 `src/main/resources/config/pbtxt/hospitals/report_template_ah2.pb.txt`。
3. 新增休宁模板 `src/main/resources/config/pbtxt/hospitals/report_template_xiuning.pb.txt`。
4. 休宁模板短期继续使用 `ReportTemplateAh2PB`，不改用 `JfkTemplatePB`。
5. 正文行以所有数据源事件时间按分钟聚合；同一分钟内的观察项、管道状态和护理记录合并到同一逻辑行。
6. 同一分钟有多条同类记录时，除本文明确要求 `/` 合并的管道字段外，按稳定顺序拆成多行。
7. 超过 28 行时分页，后续页重复页眉和表头，不截断临床记录。
8. 单个时间点存在多条引流管或动静脉置管 entry 时，同类管道字段用 `/` 合并，不按管道条数拆行；基础观察项只在第一行展示。
9. 护理记录长文本按列宽折行并占用多行；日期、时间和签名只在首行展示。
10. 观察项枚举未命中时显示原值并记录 `log.warn`；明确要求过滤的管道无效 entry 仍按过滤处理。
11. 无创血压、有创血压和 PEEP/PSV 的单项缺失均用 `-` 占位；两项都无值时显示空。
12. 通气模式同时兼容 `BIRAP` 和 `BiPAP`，输出 `6`，配置展示名统一为 `BiPAP`。
13. `Spo2` 使用现有配置中的精确 code；休宁配置确认为 `Spo2` 时，不做大小写自动匹配。
14. 中心静脉导管部位属性使用 `tta.name == '身体部位'` 过滤，`pta.value` 直接输出原文，不再映射为编码。
15. 同一管道 entry 中同一个 `tts.name` 多条记录时，按最新记录取值并记录 warning；有 `ptsr.modified_at` 时按其排序，否则按 `ptsr.id`。
16. 诊断字段短期复用现有 AH2 头部诊断来源；如果省二院当前会拼接手术信息，休宁也保持一致。
17. 签名口径：护理记录行使用护理记录创建人签名；汇总行使用省二院现有汇总签名逻辑；纯观察项/管道行无护理记录时签名留空。
18. `half_day_shift_hours` 数据库不加硬约束，仅允许 `NULL`；前后端校验 `1..23`，后端对 `NULL` 或非法值回退 12。
19. 前端 `half_day_shift_hours` 清空时保存 `NULL`，不保存 12。
20. 为已知 `variant` 建立默认模板路径和模板标识校验；`template` 和 `variant` 不匹配时 fail-fast，并在错误信息中给出期望模板路径。
21. 休宁先保留省二院既有半日小计和全天总计机制；入量/出量明细按本文新增规则取数。
22. `half_day_shift_hours` 调整 `REPORT_TEMPLATE_AH2_HALF_DAY_SUMMARY` 的结束时间；`REPORT_TEMPLATE_AH2_FULL_DAY_SUMMARY` 仍使用班次结束时间。
23. 休宁模板列宽、字号和表头坐标以 `/Users/guzhenyu/gDocs/jingyi/休宁县人民医院/xiuning_report_template.pb.txt` 为初始基准；首版 PDF 经截图或打印样张确认后固定。
24. 默认配置和 `ReportProperties` 迁移到 `hospitals` 目录；如存在多环境灰度发布需求，可短期保留旧路径文件作为 `ah2` 兼容副本，后续再删除。
25. 管道每 4 小时统计窗口为 `[t - 4 hours, t)`；窗口内取离 `t` 最近的一条状态记录；固定统计时刻超过当前时间时不输出统计行。
26. `tube_ylg_*` 出量参数不再根据固定映射补充“引流管”列；引流管列仍由管道每 4 小时统计逻辑生成。
27. 入量用法按当前 `dept_id` 且 `is_valid = true` 的 `administration_routes` 建立 `admin_code -> intake_type_id` 映射；`intake_type_id == 1 or 2` 显示 `静脉`，`3` 显示 `胃肠`，`4` 显示 `雾化`。
28. 同一时间点多个入量用药项目复用省二院 `AH2P_MED_EXEC` 的多行折行展示方式。
29. 休宁入量项目展示为 `dosageGroupDisplayName(液体总量 xx ml)`，其中 `xx` 取有效 `MedicationDosageGroupPB.md[*].intake_vol_ml` 合计值。
30. 出量项目包含 `大便` 和其他项目时，`大便` 固定放到项目最后，出量金额显示为 `MP_HOURLY_OUTPUT + "+" + stool_volume`；只有 `大便` 时金额显示 `stool_volume`。
31. `小记` 和 `总计` 汇总行中，`tube_ylg_*` 出量参数按对应 `monitoring_params.name` 归并汇总，不再使用 `胃液`、`尿液`、`其他导流液` 的特殊名称映射。

## 待决策

暂无。
