package com.jingyicare.jingyi_icis_engine.service.patients.device;

import java.util.Set;

public final class DeviceEnums {
    public static final int SOURCE_MODE_INBOUND_SERVER = 1;  // 采集程序作为服务端
    public static final int SOURCE_MODE_OUTBOUND_CLIENT = 2;  // 采集程序作为客户端
    public static final int SOURCE_MODE_NON_DIRECT_CONNECT = 3;  // 不直接通信

    public static final int SOURCE_TOPOLOGY_SINGLE_DEVICE = 1;  // 单设备直连
    public static final int SOURCE_TOPOLOGY_CENTRAL_STATION_FANOUT = 2;  // 中央站扇出分发

    public static final int DEVICE_TYPE_MONITOR = 1;  // 监护仪
    public static final int DEVICE_TYPE_VENTILATOR = 2;  // 呼吸机
    public static final int DEVICE_TYPE_BLOOD_GAS_ANALYZER = 3;  // 血气机
    public static final int DEVICE_TYPE_PICCO = 4;
    public static final int DEVICE_TYPE_ECMO = 5;
    public static final int DEVICE_TYPE_CRRT = 6;
    public static final int DEVICE_TYPE_IMMUNOASSAY_ANALYZER = 7;  // 免疫分析仪
    public static final int DEVICE_TYPE_URINE_ANALYZER = 8;  // 尿液分析仪
    public static final int DEVICE_TYPE_CENTRAL_STATION = 9;  // 中央站
    public static final int DEVICE_TYPE_GENERIC_COLLECTOR = 101;  // 通用采集

    private static final Set<Integer> DEVICE_TYPE_IDS = Set.of(
        DEVICE_TYPE_MONITOR, DEVICE_TYPE_VENTILATOR, DEVICE_TYPE_BLOOD_GAS_ANALYZER,
        DEVICE_TYPE_PICCO, DEVICE_TYPE_ECMO, DEVICE_TYPE_CRRT, DEVICE_TYPE_IMMUNOASSAY_ANALYZER,
        DEVICE_TYPE_URINE_ANALYZER, DEVICE_TYPE_CENTRAL_STATION, DEVICE_TYPE_GENERIC_COLLECTOR
    );

    public static boolean isValidDeviceTypeId(int deviceTypeId) {
        return DEVICE_TYPE_IDS.contains(deviceTypeId);
    }

    private DeviceEnums() {
    }
}
