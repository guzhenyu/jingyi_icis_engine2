# getPatientMonitoringRecords 整点设备数据近似补齐需求

## 背景

当前 `PatientMonitoringService.getPatientMonitoringRecords` 在 `sync_device_data = true` 时，会通过 `DeviceDataFetcher.fetch()` 同步设备数据。

对于普通监测参数，也就是不在 `paramCodesToApprox` 中的参数，整点 `PatientMonitoringRecord.effective_time` 主要来自 `device_data_hourly.recorded_at`。当某个目标整点在 `device_data_hourly` 中没有值时，当前流程不会再从原始 `device_data` 的邻近时间点补齐。

本次仅整理需求文档，不做 Java / proto / SQL 实现。

## 目标

在 `getPatientMonitoringRecords` 查询流程中，为普通观察项组的普通监测参数增加可配置的整点近似补齐能力：

1. 根据请求时间范围生成候选整点列表。
2. 根据 `group_type` 对应的监测参数列表，筛选出不在 `paramCodesToApprox` 中的普通参数。
3. 对于这些普通参数，如果目标整点在 `device_data_hourly` 中没有对应设备值，并且配置允许近似补齐，则从 `device_data` 中查找目标整点附近最近的一条原始设备数据。
4. 使用找到的原始设备数据生成对应的 `PatientMonitoringRecord`，其 `effective_time` 固定为目标整点。
5. 配置关闭时保持原有逻辑不变。

## 非目标

1. 不改变 `DeviceDataFetcher` 现有的 `paramCodesToApprox` 就近原则参数逻辑。
2. 不改变已有 `PatientMonitoringRecord` 的更新、覆盖和删除语义。
3. 不新增请求字段。
4. 不调整 `device_data_hourly`、`device_data_hourly_approx`、`device_data` 表结构。
5. 不处理非整点自定义时间点的补齐。
6. 暂不处理平衡量 `group_type`；如果平衡量组中也存在来自设备的普通参数，再单独确认范围。

## 命名建议

原需求中的 `hourList` 建议命名为：

```text
hourlyEffectiveTimes
```

原因：

1. 列表中的时间点都是 UTC 整点。
2. 这些时间点最终会作为补齐记录的 `PatientMonitoringRecord.effective_time`。
3. 比 `hourList` 更能表达业务含义，且不会和本地小时数、hour index 混淆。

如需要表达“候选”语义，也可以使用：

```text
candidateHourlyEffectiveTimes
```

## 时间范围

输入来自 `GetPatientMonitoringGroupsReq`：

```text
query_start_iso8601
query_end_iso8601
```

建议解析为 UTC 时间：

```text
queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC")
queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC")
nowUtc = TimeUtils.getNowUtc()
effectiveEndUtc = min(queryEndUtc, nowUtc)
```

候选整点范围建议使用左闭右开：

```text
[queryStartUtc, effectiveEndUtc)
```

生成规则：

1. 取第一个 `>= queryStartUtc` 的整点。
2. 之后每次加 1 小时。
3. 仅保留 `< effectiveEndUtc` 的整点。

示例：

```text
queryStartUtc = 2026-05-28 09:10:00
effectiveEndUtc = 2026-05-28 12:00:00
hourlyEffectiveTimes = [10:00, 11:00]
```

```text
queryStartUtc = 2026-05-28 09:00:00
effectiveEndUtc = 2026-05-28 12:01:00
hourlyEffectiveTimes = [09:00, 10:00, 11:00, 12:00]
```

## 参数范围

建议复用当前 `getPatientMonitoringRecords` 已经获取到的 `groupBetaList`：

```text
monitoringConfig.getMonitoringGroups(pid, deptId, groupType, tubeParamCodes, accountId)
```

本逻辑先只对普通观察项组启用。平衡量 `group_type` 暂不执行本补齐逻辑。

从 `groupBetaList` 中按当前展示顺序展开所有 `MonitoringParamPB.code`，去重后得到当前普通观察项组对应的参数列表。

再过滤：

```text
!paramCodesToApprox.contains(paramCode)
```

其中 `paramCodesToApprox` 与 `DeviceDataFetcher` 当前使用的配置来源保持一致：

```text
protoService.getConfig().getDevice().getParamCodeToApproxList()
```

## 配置项

当前 `application.properties` 已包含：

```properties
monitoring.enable_hourly_approx=true
monitoring.hourly_approx_boundary_minutes=10
```

配置语义：

1. `monitoring.enable_hourly_approx=false`
   - 不执行新增的 `device_data` 邻近补齐逻辑。
   - 完全沿用当前 `DeviceDataFetcher.fetch()` 从 `device_data_hourly` 同步普通参数的逻辑。

2. `monitoring.enable_hourly_approx=true`
   - 对 `device_data_hourly` 缺失的普通参数整点，尝试从 `device_data` 中查找邻近原始数据。

3. `monitoring.hourly_approx_boundary_minutes`
   - 单个整点向前、向后的查找边界，单位分钟。
   - 配置缺失时使用默认值 `10`。
   - 配置小于等于 `0` 时记录 `warning`，并跳过新增近似补齐逻辑。
   - 对目标整点 `hour`，查找窗口为：

```text
[hour - boundaryMinutes, hour + boundaryMinutes]
```

## 补齐判断

对每个目标组合：

```text
(pid, paramCode, hour)
```

建议先判断现有整点数据是否已经满足需求：

1. 如果 `patient_monitoring_records` 中已经存在同一 `pid + paramCode + effective_time` 的有效记录，则不补齐，避免覆盖人工录入或已同步值。
2. 如果当前患者绑定的床位或设备在 `device_data_hourly` 中存在同一 `paramCode + recorded_at = hour` 的设备值，则由原有逻辑处理，不再走 `device_data` 近似补齐。
3. 只有当上面两类数据都不存在时，才进入 `device_data` 邻近查找。

## device_data 邻近查找

对需要补齐的目标整点 `hour`，在 `device_data` 中按患者当时绑定的床位或设备查找：

```text
device_data.param_code = paramCode
device_data.recorded_at >= hour - boundaryMinutes
device_data.recorded_at <= hour + boundaryMinutes
```

床位、设备绑定口径建议复用 `DeviceDataFetcher.getDeviceDataQueries(...)` 当前逻辑，避免与已有设备同步流程产生不同的数据归属判断。

如果同一时间、同一参数、不同设备或床位都命中候选值，去重和优先级规则复用 `DeviceDataFetcher` 现有规则，避免设备同步路径出现两套口径。

命中多条时：

1. 选择 `abs(device_data.recorded_at - hour)` 最小的一条。
2. 如果存在同距离候选值，与 `DeviceDataFetcher.approxSeries(...)` 当前行为保持一致，选择整点左侧较早的一条。
3. 生成 `PatientMonitoringRecord` 时，`effective_time` 写目标整点 `hour`，不是原始 `device_data.recorded_at`。
4. `source` 保留设备类型来源，例如 `dev-{deviceType}`。
5. `note` 补充原始 `device_data.recorded_at`，方便审计近似来源。
6. `param_value`、`param_value_str`、`unit`、`modified_by`、`modified_at` 的生成规则尽量复用 `DeviceDataFetcher.generateMonitoringRecords(...)` 当前逻辑。

## 建议处理流程

建议尽量保持当前结构：

1. `getPatientMonitoringRecords` 继续负责解析请求、获取用户、获取 `groupBetaList` 和调用 `getMonitoringRecords`。
2. 新增近似补齐受 `sync_device_data` 控制；只有 `syncDeviceData` 为 true 时才执行。
3. `getMonitoringRecords` 继续在 `syncDeviceData` 为 true 时调用 `DeviceDataFetcher.fetch()` 执行原有设备同步。
4. 新增逻辑放在原有设备同步之后：
   - 原有 `fetch()` 先保存 `device_data_hourly` 能覆盖的整点记录。
   - 再计算 `hourlyEffectiveTimes` 和普通参数缺失组合。
   - 配置开启时，从 `device_data` 查找邻近值并保存补齐记录。
   - 保存后追加到 `result.recordList`，保持响应中可以看到本次补齐结果。

为保持代码简洁，建议把补齐细节封装为一个小方法，例如：

```text
fetchHourlyApproxRecords(...)
```

方法可放在 `DeviceDataFetcher` 或 `PatientMonitoringService` 中；如果需要复用床位、设备绑定和设备值格式化逻辑，优先考虑放在 `DeviceDataFetcher` 中。

## 兼容性要求

1. `monitoring.enable_hourly_approx=false` 时，接口行为与当前版本一致。
2. `sync_device_data=false` 时，接口行为与当前版本一致。
3. 新增补齐逻辑不应覆盖已有有效 `PatientMonitoringRecord`。
4. 新增补齐逻辑只处理普通观察项组中不在 `paramCodesToApprox` 中的普通参数。
5. 目标整点不能超过 `TimeUtils.getNowUtc()`。
6. `query_start_iso8601`、`query_end_iso8601` 非法时沿用当前 `INVALID_TIME_RANGE` 行为。
7. 新增查询应尽量批量化，避免按 `paramCode + hour` 逐个访问数据库。

## 测试建议

建议增加或补充以下用例：

1. 配置关闭时，`device_data_hourly` 缺失但 `device_data` 有邻近值，不生成补齐记录。
2. 配置开启时，`device_data_hourly` 缺失且 `device_data` 在边界内有值，生成整点 `PatientMonitoringRecord`。
3. `device_data` 有多条候选值时，选择距离整点最近的一条。
4. 候选值在边界外时不补齐。
5. 参数在 `paramCodesToApprox` 中时不走本逻辑。
6. `query_end_iso8601` 晚于 `TimeUtils.getNowUtc()` 时，不生成未来整点。
7. 已存在同一 `pid + paramCode + effective_time` 记录时不补齐。
8. `sync_device_data=false` 时不执行近似补齐。
9. `monitoring.hourly_approx_boundary_minutes <= 0` 时记录 warning 并跳过近似补齐。
10. 补齐记录的 `source` 保留 `dev-{deviceType}`，`note` 包含原始 `device_data.recorded_at`。

## 已决策

1. 新增近似补齐是否必须受 `sync_device_data` 控制？
   - 受 `sync_device_data` 控制。

2. “`device_data_hourly` 中找不到对应的值”应如何精确定义？
   - 按患者在目标整点对应的床位或设备绑定口径，找不到同一 `paramCode + recorded_at = hour` 的设备值即认为缺失。同时如果 `patient_monitoring_records` 已有有效记录，也视为无需补齐。

3. 同距离候选值如何选择？
   - 与 `DeviceDataFetcher.approxSeries(...)` 当前行为保持一致，等距时选择整点左侧较早的一条。

4. 同一时间、同一参数、不同设备或床位都命中候选值时如何去重？
   - 复用 `DeviceDataFetcher` 现有去重和优先级规则，避免设备同步路径出现两套口径。

5. `monitoring.hourly_approx_boundary_minutes <= 0` 或配置缺失时如何处理？
   - 配置缺失时使用默认值 `10`；配置小于等于 `0` 时记录 `warning`，并跳过新增近似补齐逻辑。

6. 平衡量 `group_type` 是否也需要执行本补齐逻辑？
   - 先只对普通观察项组启用；如果平衡量组中也存在来自设备的普通参数，再单独确认范围。

7. 补齐记录的 `source` 字段是否需要标识为近似来源？
   - 在不影响前端兼容的前提下保留设备类型来源，例如 `dev-{deviceType}`；在 `note` 中补充原始 `device_data.recorded_at`。

## 待决策

暂无。
