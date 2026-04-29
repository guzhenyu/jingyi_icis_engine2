# DeviceInfoPB source_mode 迁移需求

本文只整理需求和执行注意点，本次不修改 Java、proto、SQL 或前端代码。

## 背景

`icis_jd2` 已切换为用 `source_mode` 表达设备采集角色：

- `source_mode = 1`：`INBOUND_SERVER`，设备或中央站主动连采集程序。
- `source_mode = 2`：`OUTBOUND_CLIENT`，采集程序主动连设备或中央站。
- `source_mode = 3`：`NON_DIRECT_CONNECT`，本设备不直接通信，由上游 source 代采集。

后续 `jingyi_icis_engine2` 和 `jingyi_icis_frontend` 需要适配新的 `DeviceInfoPB`，不再依赖 `enabled_as_source`。

## 字段约束

目标运行约束：

- `source_mode = 1 / 2` 时，`upstream_device_id` 应为 `0`。
- `source_mode = 3` 时，`upstream_device_id` 必须 `> 0`。
- `source_mode = 3` 时，`source_topology` 保存为 `2`，即 `CENTRAL_STATION_FANOUT`。
- `pds_ip_seq` 后端/proto/DB 保留，前端本次不展示、不编辑。

PDS 常用配置：

| 设备 | `source_mode` | `source_topology` | `upstream_device_id` |
| --- | --- | --- | --- |
| PDS 中央站 | `2` | `2` | `0` |
| 普通监护仪 target | `3` | `2` | 中央站 `device_infos.id` |

## 后端需求

### proto

更新文件：

`jingyi_icis_engine2/src/main/proto/config/icis_device.proto`

`DeviceInfoPB` 目标结构：

```proto
message DeviceInfoPB {
    reserved 9, 11, 12, 13, 14, 17;

    int32 id = 1;
    string department_id = 2;
    string device_sn = 3;
    // 固定设备：deviceType：中央站 或者 deviceBedNumber不为空
    string device_bed_number = 4;
    int32 device_type = 5;
    string device_name = 6;
    string device_ip = 7;
    string device_port = 8;
    string device_driver_code = 10;
    int32 source_mode = 15;  // 1: INBOUND_SERVER, 2: OUTBOUND_CLIENT, 3: NON_DIRECT_CONNECT
    int32 source_topology = 16;  // 1: SINGLE_DEVICE, 2: CENTRAL_STATION_FANOUT
    int32 upstream_device_id = 18;  // 0=无上游；PDS target 指向所属中央站
    int32 pds_ip_seq = 19;  // PDS realtime 目标监护仪的 ipSeq；第一版床旁 target 默认 0
}
```

移除字段：

- `data_collector_port = 9`
- `network_protocol = 11`
- `serial_protocol = 12`
- `model = 13`
- `manufacturer = 14`
- `enabled_as_source = 17`

`DeviceEnums` 仍需保留：

- `source_mode`
- `source_topology`
- `bed_type`
- 换床相关枚举

`network_protocol` 和 `serial_protocol` 从 `DeviceEnums` 彻底删除，pbtxt 中对应配置同步删除。

### 数据库 schema

更新文件：

`jingyi_icis_engine2/src/main/resources/config/db/schema.postgresql.sql`

`device_infos` 目标方向：

- 删除列：
  - `network_protocol`
  - `serial_protocol`
  - `model`
  - `manufacturer`
  - `enabled_as_source`
- 当前 schema 中没有 `data_collector_port`；如果其它迁移脚本或生产库存在该列，也直接删除。
- 新增列：
  - `pds_ip_seq INTEGER NOT NULL DEFAULT 0`
- 保留列：
  - `device_port`
  - `device_driver_code`
  - `source_mode`
  - `source_topology`
  - `upstream_device_id`

建议约束：

```sql
CHECK (source_mode IN (1, 2, 3))
CHECK (
    (source_mode IN (1, 2) AND upstream_device_id = 0 AND source_topology IN (1, 2))
    OR
    (source_mode = 3 AND upstream_device_id > 0 AND source_topology = 2)
)
```

生产库迁移直接 `DROP COLUMN`，不保留旧列做回滚兼容。

### Entity 和映射

涉及文件：

- `src/main/java/com/jingyicare/jingyi_icis_engine/entity/patients/DeviceInfo.java`
- `src/main/java/com/jingyicare/jingyi_icis_engine/service/patients/PatientConfig.java`
- `src/main/java/com/jingyicare/jingyi_icis_engine/utils/ProtoUtils.java`

需求：

- `DeviceInfo` 删除旧字段：
  - `networkProtocol`
  - `serialProtocol`
  - `model`
  - `manufacturer`
  - `enabledAsSource`
- `DeviceInfo` 新增：
  - `pdsIpSeq`
- `PatientConfig.toDeviceInfoPB(...)` 不再 set 旧字段，新增 `setPdsIpSeq(...)`。
- `PatientConfig.toDeviceInfo(...)` 不再读取旧字段，新增保存 `pdsIpSeq`。
- `ProtoUtils` 当前显式保留 `DeviceInfoPB.enabled_as_source = 17` 和 `upstream_device_id = 18` 的默认值；迁移后应移除 field 17，保留 field 18，并按需要增加 field 19，确保 JSON 输出 `upstreamDeviceId: 0` / `pdsIpSeq: 0` 的默认值策略一致。

### PatientDeviceService

涉及文件：

`src/main/java/com/jingyicare/jingyi_icis_engine/service/patients/PatientDeviceService.java`

当前 `validateUpstreamDevice(...)` 依赖 `enabled_as_source`，需要改为依赖 `source_mode`。

目标逻辑：

```text
source_mode = 1 / 2:
  upstream_device_id 必须为 0

source_mode = 3:
  upstream_device_id 必须 > 0
  upstream_device_id 不能等于自己
  上游设备必须存在且未删除
  上游设备必须和当前设备同科室
  上游设备 source_mode 必须为 1 或 2
  上游设备仍要求是中央站 device_type
```

新增/更新设备时还应做防御性归一化：

- `source_mode = 1 / 2`：保存前清空 `upstream_device_id` 为 `0`。
- `source_mode = 3`：保存前将 `source_topology` 固定为 `2`。
- `source_mode = 2`：`device_port` 必填，需要保留并校验端口格式。
- `source_mode = 1 / 3`：`device_port` 不参与采集连接配置，可保存为空。

字段校验失败应新增更明确的设备字段校验状态码，不再只复用 `DEVICE_INFO_NOT_EXISTS`。

### /api/device 接口

涉及 proto：

`src/main/proto/icis_web_api.proto`

接口路径不变：

- `/api/device/getdeviceinfo`
- `/api/device/adddeviceinfo`
- `/api/device/updatedeviceinfo`
- `/api/device/deletedeviceinfo`

请求/响应结构仍通过 `config.DeviceInfoPB` 承载设备信息。迁移后前端提交的 `deviceInfo` 不再包含：

- `networkProtocol`
- `serialProtocol`
- `model`
- `manufacturer`
- `enabledAsSource`

后端返回的 `DeviceInfoPB` 应包含：

- `sourceMode`
- `sourceTopology`
- `upstreamDeviceId`
- `pdsIpSeq`

### 配置枚举

涉及文件：

- `src/main/resources/config/pbtxt/icis_config.pb.txt`
- `src/test/resources/text_resources/icis_config.pb.txt`
- 各医院专用 pbtxt，例如 `src/main/resources/config/pbtxt/hospitals/*.pb.txt`

需求：

- `source_mode` 必须包含 1、2、3。
- `source_topology` 必须包含 1、2。
- 主配置、测试配置和医院专用配置需要在同一轮变更中同步。
- 从 `DeviceEnums` 删除 `network_protocol` / `serial_protocol`，对应 pbtxt 配置也要同步删除，前端 selector 不能再读取这些枚举。

## 前端需求

涉及文件：

- `jingyi_icis_frontend/src/pages/home/tabs/managements/Device/index.tsx`
- `jingyi_icis_frontend/src/pages/home/tabs/managements/Device/components/EditDeviceModal/index.tsx`
- 患者设备绑定相关页面中引用 `manufacturer` / `model` 的表格和详情组件

本次前端不考虑 `pds_ip_seq` 展示和编辑。

### 设备列表页

目标：

- 去掉列：
  - 设备厂家 `manufacturer`
  - 设备型号 `model`
  - 网络协议 `networkProtocol`
  - 直接数据源 `enabledAsSource`
- 保留/展示列：
  - `sourceMode`
  - `sourceTopology`
  - `upstreamDeviceId`
  - `deviceIp`
  - `devicePort`
- 调整列顺序：`sourceMode`、`sourceTopology`、`upstreamDeviceId` 放到 `deviceIp` 前面。
- 上游设备名称映射不再依赖 `enabledAsSource`，应按 `sourceMode in (1, 2)` 或中央站设备类型构造。

### 编辑设备弹窗

目标：

- 去掉表单项：
  - `networkProtocol`
  - `serialProtocol`
  - `model`
  - `manufacturer`
  - `enabledAsSource`
- `sourceMode`、`sourceTopology`、`upstreamDeviceId` 放到 `deviceIp` 上面。
- `sourceMode` 必填。
- `sourceMode = 1`：
  - 隐藏 `devicePort`
  - `upstreamDeviceId = 0`
  - 展示并要求 `sourceTopology`
- `sourceMode = 2`：
  - 展示 `devicePort`
  - `devicePort` 必填且必须是合法端口
  - `upstreamDeviceId = 0`
  - 展示并要求 `sourceTopology`
- `sourceMode = 3`：
  - 隐藏 `devicePort`
  - `sourceTopology` 固定为 `2`，可隐藏或禁用展示
  - `upstreamDeviceId` 必填且必须 `> 0`
- 上游设备下拉候选：
  - 同科室
  - 排除当前正在编辑的设备
  - 中央站设备类型
  - `sourceMode in (1, 2)`

提交 payload：

- 不提交旧字段：
  - `networkProtocol`
  - `serialProtocol`
  - `model`
  - `manufacturer`
  - `enabledAsSource`
- `sourceMode = 1 / 2` 时提交 `upstreamDeviceId = 0`。
- `sourceMode = 3` 时提交选中的 `upstreamDeviceId`，并提交 `sourceTopology = 2`。
- `sourceMode = 1 / 3` 时 `devicePort` 提交为空或不提交。

## 代码生成和验证

执行实现时需要重新生成：

- engine2 protobuf Java 代码。
- frontend `WebApi` / `WebApiFE` 类型。

建议验证：

- 后端编译通过。
- `/api/device/getdeviceinfo` 返回不含旧字段，且包含新字段。
- `/api/device/adddeviceinfo`：
  - `sourceMode=1, upstreamDeviceId=0` 成功。
  - `sourceMode=2, upstreamDeviceId=0, devicePort=合法端口` 成功。
  - `sourceMode=3, upstreamDeviceId=中央站id` 成功。
  - `sourceMode=3, upstreamDeviceId=0` 失败。
  - `sourceMode=1/2, upstreamDeviceId>0` 失败。
- 前端设备列表不再引用已删除字段。
- 前端编辑弹窗切换 `sourceMode` 时，端口、拓扑、上游设备表单状态正确。

## 建议执行顺序

1. 更新 `icis_device.proto` 和配置枚举。
2. 重新生成后端 proto Java。
3. 更新 `DeviceInfo` entity、`PatientConfig` 映射、`ProtoUtils` 默认值保留策略。
4. 更新 `schema.postgresql.sql` 和生产迁移 SQL。
5. 更新 `PatientDeviceService` 校验逻辑。
6. 跑后端编译和设备接口测试。
7. 重新生成前端 API 类型。
8. 更新设备管理列表和编辑弹窗。
9. 更新患者设备绑定相关页面，移除 `manufacturer` / `model` 引用。
10. 跑前端 typecheck/build。
11. 联调 `/api/device/getdeviceinfo`、新增、编辑、删除。

## 已决策点

1. 删除 proto 字段时显式保留字段号：`reserved 9, 11, 12, 13, 14, 17;`。
2. `source_mode = 3` 时，`source_topology` 保存为 `2`，即 `CENTRAL_STATION_FANOUT`。
3. `source_mode = 1 / 3` 时，`device_port` 保存为空。
4. 前端设备类型选择继续使用级联结构，但需要调整级联数据结构以去掉 `manufacturer` / `model` 层级，展示效果保持不变。
5. 除设备管理页外，患者设备绑定相关页面中对 `manufacturer` / `model` 的引用一并调整。
6. `DeviceEnums.network_protocol` / `serial_protocol` 从 proto 和 pbtxt 中彻底删除。
7. 生产库迁移直接 `DROP COLUMN`，不保留旧列做回滚兼容。
8. `pds_ip_seq` 前端本次不处理，后端默认 `0` 足够。
9. 上游设备现在保持要求为“中央站” `device_type`。
10. `source_mode = 2` 总是要求 `device_port`。
11. 新增更明确的设备字段校验错误状态码，不复用含义不准确的 `DEVICE_INFO_NOT_EXISTS`。
12. 主配置、测试配置、医院专用 pbtxt 的 `source_mode` 枚举在同一 PR 中同步。

## 执行时可能会卡点的待决策点

暂无。
