# 精简重症监护记录单 patient_balance_records 需求说明

更新时间：2026-04-18

## 背景

精简重症监护记录单已经支持：

- `table-26`：观察项时间轴，数据源为 `monitoring_time_range`
- `table-28`：观察项明细表，数据源为 `patient_monitoring_records`
- `table-226`：出入量时间轴，数据源为 `balance_time_range`
- `table-227`：出入量分段标题，当前为静态文本 `出入量 - 入量`

本次目标是新增出入量明细表 `table-228`。`table-228` 与 `table-226` 的关系，应当类似 `table-28` 与 `table-26` 的关系：上方表格提供固定时间轴，后续表格按同一列结构输出每个出入量参数在 24 个小时列中的值。

本需求文档整理实现方案、已确认决策和实现约束。

## 目标

新增 compact 报表数据源 `patient_balance_records`，用于生成 `table-228`。

实现范围包括后续代码改造时需要完成的几类工作：

- 在 `src/main/resources/config/pbtxt/icis_config.pb.txt` 中声明 `patient_balance_records` 数据源元数据
- 在 `src/main/resources/config/pbtxt/report_compact.pb.txt` 中增加 `table-228` 的显示配置
- 在 `src/main/proto/config/icis_report_compact.proto` 中使用 `balance_group` 配置 `table-228` 需要渲染的参数
- 在 `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources` 下实现 `patient_balance_records` 数据源处理器
- 在 `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport` 中补齐 table-scoped 数据源输入构造
- 补充单元测试，避免测试直接绑定生产模板

## 已确认决策

`CompactReportTemplatePB` 中 `balance_group` 使用字段号 `5`，避免和 `bga_table_id = 4` 冲突：

```proto
message CompactReportTemplatePB {
    JfkTemplatePB template = 1;
    LogoMetaPB logo = 2;
    repeated ReportMonGroupPB mon_group = 3;
    string bga_table_id = 4;
    repeated ReportMonGroupPB balance_group = 5;
}
```

其他已确认决策：

- `table-228` 首期配置 3 个参数：`intravenous_intake`、`hourly_intake`、`total_intake`
- `balance_group` 中允许配置 Balance 页面中合法的出入量/平衡相关参数，不限定为原始 `patient_monitoring_records` 参数；由 `BalanceCalculator` 产生的计算参数也应被支持
- 后续还会有 `出量`、`平衡量` 对应的 `table-230`、`table-232` 等表格，`patient_balance_records` 应设计成可复用多表格、多 `balance_group` 的通用数据源
- 前端 Balance 页面中的分组和 compact 报表中的分组不要求完全一致，例如 `累计入量` 在前端属于 `平衡量` 组，但在报表中可以按模板配置放到其他出入量分段中
- `acc_ml` 列代表“一行加总的量”
- `hourly_intake.acc_ml` 直接使用 `BalanceCalculator` 返回的虚拟合计值，语义上等价于当前时间范围内每小时入量的加总
- `total_intake.acc_ml` 直接使用 `BalanceCalculator` 返回的最后一个虚拟累计值，累计范围跟随当前请求时间窗 `[startTime, endTime)`
- `NET_ACCUMULATED_IN_CODE`、`NET_ACCUMULATED_OUT_CODE`、`NET_ACCUMULATED_NET_CODE` 这三行的 `acc_ml` 等于该行最后一个累计值
- 其他 `toSummarizeCode == true` 的参数，`acc_ml` 使用该参数在当前时间范围内的值加总；应最大化复用 `BalanceCalculator` 内已有函数，不重复实现汇总逻辑
- 允许报表生成时调用 `BalanceCalculator.storeGroupRecordsStats(args)` 写入统计记录
- 小时列严格按照整点匹配，只接受 `effective_time` 严格落在对应整点的值
- compact 报表调用 `storeGroupRecordsStats` 时，`accountId` / `modifiedBy` 使用当前用户
- 需要包含患者 tube/drainage 相关动态出量参数，按前端 Balance 流程调用 `patientTubeImpl.getMonitoringParamCodes(...)`
- 如果 `balance_group` 中配置的参数不在 `MonitoringConfig.getMonitoringParams(deptId)` 中，`log.warn` 后跳过
- 如果没有任何出入量记录，仍渲染配置的参数行，小时值和 `acc_ml` 为空
- 如果表格找不到对应 `balance_group.table_id`，静默不渲染该表格
- `start_hour` 为空或非法时返回错误
- `query_end` 与其他 compact 数据源一样只作为输入保留，不参与窗口计算；实际窗口固定按 `query_start + 24h` 和 `start_hour` 计算
- `patient_balance_records` 输出字段全部统一使用 `STRINGS/strs_val`
- `table-228` 跨页后不重复 `table-226` 时间轴，保持与 `table-28` 的流式表格规则一致

## 模板关系

### 观察项现有模式

`table-26` 渲染固定时间轴：

- 第 1 列：`time_txt`
- 第 2 列：`unit_txt`
- 第 3 至 26 列：`hour1` 至 `hour24`

`table-28` 渲染观察项明细：

- 第 1 列：`param_name`
- 第 2 列：`param_unit`
- 第 3 至 26 列：每个观察项在对应小时的值
- 参数列表来自 `CompactReportTemplatePB.mon_group[]`
- `mon_group.table_id == table-28`

### 出入量目标模式

`table-226` 已经渲染出入量时间轴：

- 第 1 列：`time_txt`
- 第 2 列：`acc_ml`
- 第 3 至 26 列：`hour1` 至 `hour24`
- 数据源：`balance_time_range`
- `balance_time_range` 使用 `balance_stats_shifts.start_hour` 生成 24 小时时间列

`table-228` 应作为 `table-226` 的明细表：

- 第 1 列：出入量参数名称，建议字段名 `param_name`
- 第 2 列：累计量，建议字段名 `acc_ml`
- 第 3 至 26 列：该参数在 `table-226` 对应小时列中的值
- 数据源：`patient_balance_records`
- 参数列表来自 `CompactReportTemplatePB.balance_group[]`
- `balance_group.table_id == table-228`
- 后续 `table-230`、`table-232` 等表格继续使用同一个 `patient_balance_records` 数据源，仅通过不同的 `balance_group.table_id` 和 `param_code` 列表区分表格内容

## report_compact.pb.txt 需求

在 `table-227` 后新增一个 `ac_tables`，作为 `table-228`。

建议结构：

```pbtxt
ac_tables {
  offset_top: 0
  tbl {
    id: "table-228"
    type: "table"
    x: 12
    y: 0
    rows: 1
    cols: 26
    cell_widths: 55
    cell_widths: 30
    # ...共 26 列，列宽应与 table-226 保持一致
    cell_heights: 12
    line_width: 0.5
    line_color: "#000000"
    column_metas {
      id: "table-228-col-1"
      name: "列1"
      element_meta {
        content_type: 4
        data_source_meta_id: "patient_balance_records"
        data_source_field_id: "param_name"
      }
    }
    column_metas {
      id: "table-228-col-2"
      name: "列2"
      element_meta {
        content_type: 4
        data_source_meta_id: "patient_balance_records"
        data_source_field_id: "acc_ml"
      }
    }
    # 第 3 至 26 列分别绑定 hour1 至 hour24
    v_align_id: 2
    h_align_id: 5
    font: "Microsoft YaHei"
    font_size: 6
    font_color: "#000000"
    char_spacing: 0
    h_padding: 2
    data_source_meta_id: "patient_balance_records"
  }
}
```

`table-228` 的列宽、字体、padding、对齐方式应从模板中读取，并通过 `CompactReportDataSourceBuilder` 作为数据源输入传给 handler，用于文本折行。

## icis_report_compact.proto 需求

`table-228` 的参数列表建议使用已有通用结构 `ReportMonGroupPB`：

```proto
message ReportMonGroupPB {
    string table_id = 1;  // 对应于 JfkTablePB.id
    repeated string param_code = 2;
}
```

在 `CompactReportTemplatePB` 中使用：

```proto
repeated ReportMonGroupPB balance_group = 5;
```

在 `report_compact.pb.txt` 中配置：

```pbtxt
balance_group {
  table_id: "table-228"
  param_code: "intravenous_intake"
  param_code: "hourly_intake"
  param_code: "total_intake"
}
```

首期 `table-228` 先绘制上述 3 行。后续可以继续通过 `balance_group` 增加其他 Balance 页面中合法的出入量/平衡相关参数。

## icis_config.pb.txt 数据源元数据需求

新增 `patient_balance_records` 数据源。

输入字段建议：

- `pid`
- `dept_id`
- `query_start`
- `query_end`
- `balance_param_codes`
- `col_widths`
- `table_id`
- `font_size`
- `char_spacing`
- `h_padding`

`query_end` 保留为输入，与其他 compact 数据源保持一致。实际 24 小时时间窗建议仍基于 `query_start + 24h` 和 `balance_stats_shifts.start_hour` 计算，保持与 `balance_time_range` 一致。

输出字段建议：

- `param_name`：参数名称
- `acc_ml`：累计量，单位由表头表达为 `累计量ml`
- `hour1` 至 `hour24`：24 小时列

所有输出字段统一使用 `STRINGS/strs_val`，原因：

- 与 `patient_monitoring_records`、`patient_bga_records` 保持一致
- 支持按列宽显式折行
- 渲染器已有可变行高逻辑，能根据 `strs_val.length` 计算行高

数据源元数据示意：

```pbtxt
data_sources {
  id: "patient_balance_records"
  name: "出入量记录"
  input_field_ids: "pid"
  input_field_ids: "dept_id"
  input_field_ids: "query_start"
  input_field_ids: "query_end"
  input_field_ids: "balance_param_codes"
  input_field_ids: "col_widths"
  input_field_ids: "table_id"
  input_field_ids: "font_size"
  input_field_ids: "char_spacing"
  input_field_ids: "h_padding"
  output_fields {
    id: "param_name"
    name: "出入量参数名称"
    val_meta {
      val_type: 9 # STRINGS
    }
    content_type: 4 # JFK_DATA_SOURCE
    is_array: true
    is_options: false
  }
  output_fields {
    id: "acc_ml"
    name: "累计量ml"
    val_meta {
      val_type: 9 # STRINGS
    }
    content_type: 4 # JFK_DATA_SOURCE
    is_array: true
    is_options: false
  }
  # hour1 到 hour24 同样使用 STRINGS
}
```

## 时间窗规则

`balance_time_range` 当前使用 `balance_stats_shifts.start_hour` 生成时间轴，因此 `patient_balance_records` 必须使用完全相同的时间窗，否则 `table-226` 表头与 `table-228` 数据会错位。

建议规则：

1. 输入 `query_start`，通过 `TimeUtils.fromIso8601String(query_start, "UTC")` 得到 `utcStart`
2. `utcEnd = utcStart + 24h`
3. 根据患者 `dept_id` 查询 `effective_time < utcEnd` 的最后一条 `balance_stats_shifts`
4. 使用 `balance_stats_shifts.start_hour` 作为出入量统计开始小时
5. 计算 `utcMiddle = utcStart + 12h`
6. 通过 `TimeUtils.getLocalDateTimeFromUtc(utcMiddle, zoneId)` 得到 `localMiddle`
7. `localMidnight = localMiddle.toLocalDate().atStartOfDay()`
8. `balanceStartLocal = localMidnight + start_hour`
9. `balanceStartUtc = TimeUtils.getUtcFromLocalDateTime(balanceStartLocal, zoneId)`
10. `balanceEndUtc = balanceStartUtc + 24h`

如果找不到 `balance_stats_shifts`，沿用现有错误：

- `StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND`

如果 `start_hour` 为空或非法，返回错误。建议使用 `StatusCode.INVALID_PARAM_VALUE`，错误信息中包含 `deptId`、`balanceStatsShiftId`、`start_hour`。

`query_end` 只保留为输入字段，便于与其他 compact 数据源结构一致，不参与实际窗口计算。

## 参数取值逻辑

`table-228` 中的参数来自 `src/main/proto/config/icis_report_compact.proto` 中的 `balance_group`，实际配置在 `report_compact.pb.txt`。

取值逻辑需要参考：

- 前端 `jingyi_icis_frontend/src/pages/home/tabs/nurses/Balance/index.tsx`
  - `getMonitoringParams`
  - `getPatientMonitoringGroups`
  - `getPatientMonitoringRecords`
- 后端
  - `PatientMonitoringService.getPatientMonitoringRecords`
  - `PatientMonitoringService.getMonitoringRecords`
  - `BalanceCalculator.getGroupRecordsList`
  - `BalanceCalculator.storeGroupRecordsStats`

出入量报表不能简单只查 `patient_monitoring_records` 原始记录，因为部分行是计算值：

- 每小时入量：`hourly_intake`
- 每小时出量：`hourly_output`
- 每小时平衡：`hourly_balance`
- 累计入量：`total_intake`
- 累计出量：`total_output`
- 累计平衡量：`total_balance`
- 组内合计：配置中的 `param_code_summary`，当前为 `all`

这些值由 `BalanceCalculator` 根据原始入量、出量记录计算得到。

## 建议实现路径

### 方案 A：复用 PatientMonitoringService/BalanceCalculator 计算链路

核心思路：

1. 由 handler 计算 `balanceStartUtc`、`balanceEndUtc`
2. 获取 balance 分组：
   - `MonitoringConfig.getMonitoringGroups(...)`
   - group type 使用 `GROUP_TYPE_BALANCE`
3. 获取患者 tube 相关参数：
   - `patientTubeImpl.getMonitoringParamCodes(pid, balanceStartUtc, balanceEndUtc)`
4. 查询原始 `patient_monitoring_records`
5. 构造 `BalanceCalculator.GetGroupRecordsListArgs`
6. 调用 `BalanceCalculator.getGroupRecordsList(args)` 得到每个 group 的参数行、合计行、平衡量行和累计行
7. 根据 `balance_group.table_id == table-228` 的 `param_code` 顺序筛选输出行

优点：

- 复用已有前后端一致的出入量计算规则
- 能覆盖累计量、平衡量、合计等非原始记录行
- 后续出量、平衡量表格可继续复用

注意事项：

- 已确认报表生成允许调用 `BalanceCalculator.storeGroupRecordsStats(args)`，可以写入每小时统计和每日统计记录
- `accountId` / `modifiedBy` 使用当前用户

### 方案 B：在 patient_balance_records handler 中手动重算

核心思路：

1. 直接查询 `[balanceStartUtc, balanceEndUtc)` 内原始 `patient_monitoring_records`
2. 识别入量、出量参数
3. 手动按小时汇总、计算合计、累计、平衡量
4. 生成 JFK 输出

优点：

- handler 自包含，不依赖 `PatientMonitoringService` 的副作用
- accountId 问题较少

风险：

- 容易与前端出入量页面计算不一致
- 需要重复实现 `BalanceCalculator` 已经存在的规则
- tube 参数、组内合计、虚拟记录、累计记录容易漏

建议采用方案 A，最大化复用 `PatientMonitoringService` 和 `BalanceCalculator` 内已有计算逻辑。

## 输出组织规则

handler 最终输出 `JfkDataSourcePB` 时，应构造列式数据：

- `output_data["param_name"].vals.length == 行数`
- `output_data["acc_ml"].vals.length == 行数`
- `output_data["hour1"].vals.length == 行数`
- ...
- `output_data["hour24"].vals.length == 行数`

每一行对应 `balance_group.param_code` 中的一个有效参数。

建议行生成规则：

1. 按 `balance_group.param_code` 的顺序输出
2. 如果参数不存在或无法计算：
   - `log.warn`
   - 跳过该行
3. `param_name`：
   - 普通参数使用 `MonitoringParamPB.name`
   - `all` 使用 `monitoring.param_code_summary_txt`，当前为 `合计`
   - 计算参数如 `hourly_intake`、`total_intake` 使用对应 `MonitoringParamPB.name`
4. `acc_ml`：
   - 代表“一行加总的量”
   - `hourly_intake` 使用 `BalanceCalculator` 返回的虚拟合计值，语义上等价于当前时间范围内每小时入量的加总
   - `total_intake` 使用 `BalanceCalculator` 返回的最后一个虚拟累计值，累计范围跟随当前请求时间窗 `[startTime, endTime)`
   - `NET_ACCUMULATED_IN_CODE`、`NET_ACCUMULATED_OUT_CODE`、`NET_ACCUMULATED_NET_CODE` 这三行，`acc_ml` 等于该行最后一个累计值
   - 其他 `toSummarizeCode == true` 的参数，`acc_ml` 使用当前时间范围内的值加总
   - `toSummarizeCode == true` 的定义以 `BalanceCalculator.getCodeRecords(...)` 当前逻辑为准，即 `MonitoringParamPB.balance_type` 为入量或出量的参数
   - 应优先复用 `BalanceCalculator` 内已有虚拟汇总值和累计值计算，不重复实现一套汇总逻辑
5. `hour1` 至 `hour24`：
   - 对应 `balanceStartUtc + (i - 1) hours`
   - 值来自 `BalanceCalculator` 计算后的 `MonitoringRecordValPB`
   - 严格整点匹配，只接受 `recordedAtIso8601` 对应 UTC 时间等于该小时整点的值
   - 没有值则输出空字符串

所有字段用 `strs_val` 输出。需要折行时，使用 `col_widths`、`font_size`、`char_spacing`、`h_padding` 和现有 PDF 字体宽度计算逻辑。

如果没有任何出入量记录，handler 仍按 `balance_group.param_code` 渲染可识别的配置参数行，`acc_ml` 和小时列输出空字符串。

## 当前数据库和前端调研结论

调研环境：

- 数据库：`jingyi_icis_db`
- 科室：`99999`
- 前端页面：`src/pages/home/tabs/nurses/Balance/index.tsx`

当前数据库中，出入量分组配置如下：

- `入量` 组：
  - `oral_intake`：口服
  - `intravenous_intake`：静脉入量
  - `gastric_intake`：胃肠入量
  - `other_intake`：其他入量
- `出量` 组：
  - `urine_output`：尿量
  - `gastric_fluid_volume`：胃液量
  - `stool_volume`：大便量
  - `crrt_UF`：超滤量
- `平衡量` 组：
  - `hourly_intake`：每小时入量
  - `hourly_output`：每小时出量
  - `hourly_balance`：每小时平衡
  - `total_intake`：累计入量
  - `total_output`：累计出量
  - `total_balance`：累计平衡量

`每小时入量` 和 `累计入量` 在当前数据库里都是 `monitoring_params` 中的观察项：

- `hourly_intake`：`name = 每小时入量`，`balance_type = 3`
- `total_intake`：`name = 累计入量`，`balance_type = 0`

它们都配置在科室 `99999` 的 `平衡量` 分组中。已查询到的患者级出入量分组当前没有单独配置参数，因此患者打开 Balance 页面时会沿用科室默认分组参数。

前端 Balance 页面显示逻辑：

- `Balance/index.tsx` 的合计列读取 `record.recordValue.find(i => String(i.id) === '-1' && !i.recordedAtIso8601)?.valueStr`
- 因此前端“合计”列依赖后端返回的虚拟记录：`id == -1` 且 `recordedAtIso8601` 为空

后端 `BalanceCalculator` 对这两行的来源：

- `hourly_intake` 对应配置 `NET_HOURLY_IN_CODE`
  - 由 `getNetGroupRecordsMap(...)` 从入量组汇总行 `inList` 生成
  - `inList` 来自入量组的 `all` 汇总行
  - 因此 `hourly_intake` 本质是入量组所有入量参数在每个时间点的合计
- `total_intake` 对应配置 `NET_ACCUMULATED_IN_CODE`
  - 由 `getAccumulatedVals(inList, inGroupBeta.getSumValueMeta())` 生成
  - 本质是对 `hourly_intake` 的时间序列做累计

基于这次调研，已确认：

- `hourly_intake.acc_ml` 直接使用 `BalanceCalculator` 返回的虚拟合计值，即前端合计列当前读取的那一个值；语义上等价于当前时间范围内每小时入量的加总
- `total_intake.acc_ml` 直接使用 `BalanceCalculator` 返回的最后一个虚拟累计值；在当前 `PatientMonitoringService` 流程中，展示用的累计范围跟随请求时间窗 `[startTime, endTime)`，Balance 页面通常传入完整 24 小时出入量时间窗

## 与 compactreport 的集成

`CompactReportDataSourceBuilder` 需要新增 table-scoped 输入构造，类似当前：

- `buildPatientMonitoringRecordsInputs(...)`
- `buildPatientBgaRecordsInputs(...)`

新增建议：

- `JfkDataSourceIds.PATIENT_BALANCE_RECORDS = "patient_balance_records"`
- `JfkDataSourceIds.isCompactTableScoped(...)` 增加 `patient_balance_records`
- `buildPatientBalanceRecordsInputs(...)`

构造逻辑：

1. 遍历 `CompactReportTemplatePB.balance_group[]`
2. 以 `table_id` 建立 `Map<tableId, ReportMonGroupPB>`
3. 遍历模板中所有表格，包括 container 内的 `ac_tables`
4. 如果 `table.data_source_meta_id == "patient_balance_records"`：
   - 找到同 table_id 的 `balance_group`
   - 如果找不到或参数为空，不构造输入，该表静默不渲染
   - 找到后构造 table-scoped 输入：

```java
.setId(JfkDataSourceIds.compactTableScoped("patient_balance_records", table.getId()))
.addInputData(strInput("table_id", table.getId()))
.addInputData(strArrayInput("balance_param_codes", balanceGroup.getParamCodeList()))
.addInputData(doubleArrayInput("col_widths", table.getCellWidthsList()))
.addInputData(doubleInput("font_size", table.getFontSize()))
.addInputData(doubleInput("char_spacing", table.getCharSpacing()))
.addInputData(doubleInput("h_padding", table.getHPadding()))
```

## 数据源 handler 需求

建议新增：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/PatientBalanceRecordsDataSourceHandler.java
```

主要职责：

1. 校验输入：
   - `pid`
   - `query_start`
   - `table_id`
   - `balance_param_codes`
2. 根据 `pid` 查询患者
3. `request.dept_id` 与患者 `dept_id` 不一致时，沿用已有 compact 数据源策略：
   - `log.warn`
   - 以患者 `dept_id` 为准
4. 计算 `balanceStartUtc` 和 `balanceEndUtc`
5. 按 balance 规则获取或计算记录
6. 按 `balance_param_codes` 生成行
7. 输出 `param_name`、`acc_ml`、`hour1` 至 `hour24`

错误处理建议：

- `pid` 缺失：`JFK_MISSING_REQUIRED_FIELD`
- 患者不存在：`PATIENT_NOT_FOUND`
- 患者 dept 缺失：`DEPT_NOT_FOUND`
- `query_start` 非法：`INVALID_TIME_FORMAT`
- 找不到 `balance_stats_shifts`：`BALANCE_STATS_SHIFT_NOT_FOUND`
- `start_hour` 为空或非法：`INVALID_PARAM_VALUE`
- 字段长度不一致：返回错误，指出 `table_id`、`field_id`、长度
- 单行高度超过一页：沿用 renderer 现有错误策略

特殊行为：

- `balance_group` 中配置的参数不在 `MonitoringConfig.getMonitoringParams(deptId)` 中：`log.warn` 后跳过该行
- 没有任何出入量记录：仍渲染配置的参数行，`acc_ml` 和小时列为空
- 需要包含患者 tube/drainage 相关动态出量参数：按前端 Balance 流程调用 `patientTubeImpl.getMonitoringParamCodes(pid, balanceStartUtc, balanceEndUtc)` 并传入 `MonitoringConfig.getMonitoringGroups(...)`

## 渲染规则

`table-228` 是可变行数表格，应放在 `JfkAbsContainerPB.ac_tables` 中，并满足已有流式表格规则：

- `rows == 1`
- `cell_heights.length == 1`
- `data_source_meta_id == "patient_balance_records"`
- handler 输出的每个字段行数一致

分页规则沿用现有 compact renderer：

- container 内 `ac_tables` 顺序渲染
- `table-228.offset_top == 0` 时，应紧贴 `table-227` 底线
- 如果跨页，不重复上方 `table-226` 或 `table-227`

行高规则沿用当前实现：

- 非多行字段默认为 1 行
- `STRINGS/strs_val` 按显式多行计算
- `row_height = (base_cell_height - font_size) + 多行文本总行高`

## 测试需求

### handler 单元测试

覆盖：

- 正常输出入量参数行
- `all` 合计行
- 计算行，如 `hourly_intake`、`total_intake`
- 输出字段全部使用 `strs_val`
- 参数不存在时 warn 并跳过
- 无记录时仍渲染配置参数行，`acc_ml` 和小时列为空
- 查询不到 `balance_stats_shifts`
- `start_hour` 为空或非法时返回错误
- `request.dept_id` 与患者 `dept_id` 不一致
- 包含 tube/drainage 动态出量参数
- 文本折行

### CompactReportDataSourceBuilder 测试

覆盖：

- `patient_balance_records` 被识别为 table-scoped
- 从 `balance_group` 中找到 `table-228` 的参数列表
- 输入包含 `table_id`、`balance_param_codes`、`col_widths`、`font_size`、`char_spacing`、`h_padding`
- 找不到 `balance_group` 时不构造 table-scoped 输入，表格静默不渲染

测试应使用独立测试模板，避免依赖生产 `report_compact.pb.txt`。

### renderer 测试

覆盖：

- synthetic data 渲染 `table-226 + table-227 + table-228`
- `table-228.offset_top == 0` 时无缝衔接
- 多行折行后行高正确
- 数据行较多时分页正确

## 待确认问题

本轮需求已经收敛，暂无阻塞实现的问题。

## 实现顺序建议

1. 先修复 `icis_report_compact.proto` 字段号冲突
2. 在 `icis_config.pb.txt` 增加 `patient_balance_records` 数据源元数据
3. 在 `JfkDataSourceIds` 和 `CompactReportDataSourceBuilder` 中增加 table-scoped 输入构造
4. 实现 `PatientBalanceRecordsDataSourceHandler`
5. 增加 `report_compact.pb.txt` 中的 `table-228` 和 `balance_group`
6. 补充 handler、builder、renderer 测试
7. 用真实 compact 报表 PDF 验证表格衔接、折行、分页和计算值
