# 精简重症监护记录单 medexe_time_range 需求说明

更新时间：2026-04-18

## 背景

精简重症监护记录单中已经有 `balance_time_range` 数据源，用于出入量区域的 24 小时时间轴。当前新增一个类似的数据源 `medexe_time_range`，用于执行用药区域的表头时间轴。

本文档整理已确认需求和实现验收点。

## 已确认需求

1. `balance_stats_shifts.start_time` 是口误，执行用药时间轴确认使用 `balance_stats_shifts.start_hour`。
2. `medexe_time_range` 的小时范围与 `balance_time_range` 完全一致。
3. 第 3 列显示名确认叫 `累计量`，字段 ID 使用 `acc_ml`。
4. 模板新增标题行 `table-233`，标题文本为 `执行用药`。
5. 模板新增时间轴表 `table-234`，数据源为 `medexe_time_range`。
6. 新增 `ac_tables` 放在现有出入量区域之后，也就是当前 `table-232` 之后。
7. 宽度按包含纵向边框后的视觉总宽对齐，执行用药时间轴和出入量时间轴同为 `818.5pt`。

## 目标

新增 JFK 数据源 `medexe_time_range`，用于输出“执行用药时间轴”。

实现范围预计包括：

- 在 `src/main/resources/config/pbtxt/icis_config.pb.txt` 中新增 `medexe_time_range` 数据源元数据。
- 在 `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources` 下新增 `MedexeTimeRangeDataSourceHandler`。
- 在 `src/main/resources/config/pbtxt/report_compact.pb.txt` 的现有出入量区域之后新增两个 `ac_tables`：
  - `table-233`：标题行，静态文本 `执行用药`。
  - `table-234`：执行用药时间轴，引用 `medexe_time_range`。
- 增加单元测试，参考 `BalanceTimeRangeDataSourceHandlerTests`。

预计不需要修改 `src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/compactreport`。原因是 `medexe_time_range` 属于普通模板级数据源，不是类似 `patient_monitoring_records`、`patient_balance_records` 这种 table-scoped 数据源；只要模板中引用了 `data_source_meta_id: "medexe_time_range"`，`CompactReportDataSourceBuilder` 会通过 `commonInputBuilder(...)` 自动传入 `pid`、`dept_id`、`query_start`、`query_end` 等通用输入。

## 数据源定义

### 数据源 ID

```text
medexe_time_range
```

中文名：

```text
执行用药时间轴
```

### 输入字段

参考 `balance_time_range`，最小输入为：

- `dept_id`
- `query_start`

`CompactReportDataSourceBuilder.commonInputBuilder(...)` 实际还会传入：

- `pid`
- `shift_time`
- `query_end`

这些额外输入可以保留但 handler 不使用，保持与其他 compact 数据源兼容。

### 输出字段

输出 27 列：

- `用药医嘱`
- `用药途径`
- `累计量`
- 24 个小时列

字段 ID：

- `med_order_txt`：输出 `用药医嘱`
- `route_txt`：输出 `用药途径`
- `acc_ml`：输出 `累计量`
- `hour1` 至 `hour24`：输出 24 个小时文本

说明：

- 所有输出字段都是数组输出，和 `balance_time_range` 一样每个字段只返回 1 个值。
- `val_type` 可以先沿用 `STRING`。

## 时间窗算法

`medexe_time_range` 使用 `balance_stats_shifts.start_hour`，算法与 `balance_time_range` 保持一致：

1. 读取 `query_start`。
2. `utcStart = TimeUtils.fromIso8601String(query_start, "UTC")`。
3. `utcEnd = utcStart + 24h`。
4. 查询 `effective_time < utcEnd` 的最后一条未删除 `balance_stats_shifts`。
5. 取 `shift.start_hour`。
6. 输出：
   - `hour1 = start_hour % 24 + ":00"`
   - `hour2 = (start_hour + 1) % 24 + ":00"`
   - ...
   - `hour24 = (start_hour + 23) % 24 + ":00"`

不使用 `mon_start_hour`。

## 错误处理

建议复用 `BalanceTimeRangeDataSourceHandler` 的错误策略：

- `dept_id` 或 `query_start` 缺失：返回 `JFK_MISSING_REQUIRED_FIELD`。
- `query_start` 格式非法：返回 `INVALID_TIME_FORMAT`。
- 找不到有效 `balance_stats_shifts`：返回 `BALANCE_STATS_SHIFT_NOT_FOUND`。
- `start_hour` 为空或非法：返回 `INVALID_PARAM_VALUE`。

当前 `BalanceTimeRangeDataSourceHandler` 没有显式校验 `start_hour` 合法性。新实现建议补齐；也可以后续顺手把 `balance_time_range` 做一致性增强。

## report_compact.pb.txt 模板需求

在 `containers-225` 的现有出入量区域之后新增两个 `ac_tables`，也就是放在当前 `table-232` 后面。

### 标题行 table-233

表格约束：

- `id: "table-233"`
- `rows: 1`
- `cols: 1`
- `cell_widths: 817.5`
- `cell_heights: 12`
- `line_width: 0.5`
- 静态文本：`执行用药`
- 样式参考现有出入量标题行 `table-227`、`table-229`、`table-231`

宽度说明：

```text
817.5 + 2 * 0.5 = 818.5
```

标题行视觉总宽与出入量区域一致。

### 时间轴表 table-234

表格约束：

- `id: "table-234"`
- `rows: 1`
- `cols: 27`
- `data_source_meta_id: "medexe_time_range"`
- `offset_top: 0`
- `cell_heights: 12`
- `line_width: 0.5`
- 字体、对齐方式、padding 参考 `table-226`

列宽需求：

- 第 1 列：`129.5`
- 第 2 列：`50`
- 第 3 至第 27 列：`25`，共 25 列

对应字段：

- 第 1 列：`med_order_txt`
- 第 2 列：`route_txt`
- 第 3 列：`acc_ml`
- 第 4 至第 27 列：`hour1` 至 `hour24`

宽度说明：

出入量时间轴 `table-226` 内容宽度为：

```text
55 + 25 * 30 = 805
```

`table-226` 有 26 列，因此包含 27 条纵向边框；按每条 `0.5pt` 计算，视觉总宽为：

```text
55 + 25 * 30 + 27 * 0.5 = 818.5
```

执行用药时间轴 `table-234` 内容宽度为：

```text
129.5 + 50 + 25 * 25 = 804.5
```

`table-234` 有 27 列，因此包含 28 条纵向边框；按每条 `0.5pt` 计算，视觉总宽为：

```text
129.5 + 50 + 25 * 25 + 28 * 0.5 = 818.5
```

因此执行用药时间轴与出入量时间轴视觉总宽一致。第 6 点的理解是正确的。

## 建议 pbtxt 形态

示意如下，实际实现时需要补齐 27 个 `column_metas`，并补齐 25 个 `cell_widths: 25`。

### table-233

```pbtxt
ac_tables {
  offset_top: 0
  tbl {
    id: "table-233"
    type: "table"
    x: 12
    y: 0
    z_index: 1
    rows: 1
    cols: 1
    cell_widths: 817.5
    cell_heights: 12
    line_width: 0.5
    line_color: "#000000"
    column_metas {
      id: "table-233-col-1"
      name: "列1"
      element_meta {
        content_type: 1
      }
      static_vals: "执行用药"
    }
    extend_to_next_page: true
    next_page_x: 12
    next_page_y: 0
    v_align_id: 2
    h_align_id: 4
    font: "Microsoft YaHei"
    font_size: 8
    font_color: "#000000"
    char_spacing: 0
    h_padding: 2
    h_replicas: 0
    h_replica_data_direction_is_col: true
  }
}
```

### table-234

```pbtxt
ac_tables {
  offset_top: 0
  tbl {
    id: "table-234"
    type: "table"
    x: 12
    y: 0
    z_index: 1
    rows: 1
    cols: 27
    cell_widths: 129.5
    cell_widths: 50
    cell_widths: 25
    # 第 3 至第 27 列共 25 个 25；此处仅示意 1 个，实际需要重复到 25 个
    cell_heights: 12
    line_width: 0.5
    line_color: "#000000"

    column_metas {
      id: "table-234-col-1"
      element_meta {
        content_type: 4
        data_source_meta_id: "medexe_time_range"
        data_source_field_id: "med_order_txt"
      }
    }
    column_metas {
      id: "table-234-col-2"
      element_meta {
        content_type: 4
        data_source_meta_id: "medexe_time_range"
        data_source_field_id: "route_txt"
      }
    }
    column_metas {
      id: "table-234-col-3"
      element_meta {
        content_type: 4
        data_source_meta_id: "medexe_time_range"
        data_source_field_id: "acc_ml"
      }
    }
    # 第 4 至第 27 列绑定 hour1 至 hour24

    v_align_id: 2
    h_align_id: 5
    font: "Microsoft YaHei"
    font_size: 6
    font_color: "#000000"
    char_spacing: 0
    h_padding: 2
    data_source_meta_id: "medexe_time_range"
  }
}
```

## Handler 需求

新增：

```text
src/main/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/MedexeTimeRangeDataSourceHandler.java
```

建议实现方式：

- 结构仿照 `BalanceTimeRangeDataSourceHandler`。
- `getMetaId()` 返回 `medexe_time_range`。
- 读取 `dept_id`、`query_start`。
- 查询 `BalanceStatsShiftRepository.findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(...)`。
- 使用 `shift.start_hour` 生成 24 小时文本。
- 输出 27 个字段，每个字段 1 个字符串值。

输出示例：

```text
med_order_txt => ["用药医嘱"]
route_txt     => ["用药途径"]
acc_ml        => ["累计量"]
hour1         => ["7:00"]
hour2         => ["8:00"]
...
hour24        => ["6:00"]
```

## 测试需求

建议新增：

```text
src/test/java/com/jingyicare/jingyi_icis_engine/service/reports/jfkdatasources/MedexeTimeRangeDataSourceHandlerTests.java
```

覆盖：

- 正常生成 27 个输出字段。
- `start_hour = 7` 时输出 `7:00` 到 `6:00`。
- `med_order_txt` 输出 `用药医嘱`。
- `route_txt` 输出 `用药途径`。
- `acc_ml` 输出 `累计量`。
- `dept_id` 缺失。
- `query_start` 缺失。
- `query_start` 格式错误。
- 找不到 `balance_stats_shifts`。
- `start_hour` 非法时返回错误。

模板相关建议继续使用独立测试 pbtxt，避免测试绑定生产 `report_compact.pb.txt`。

## 可进入实现的前提

本文档涉及实现的关键决策已经确认：

- 时间小时来源字段：`start_hour`。
- 第 3 列字段 ID：`acc_ml`。
- 标题行表格 ID：`table-233`。
- 时间轴表格 ID：`table-234`。
- 插入位置：现有出入量区域之后，即当前 `table-232` 后。
- 标题文本：`执行用药`。
