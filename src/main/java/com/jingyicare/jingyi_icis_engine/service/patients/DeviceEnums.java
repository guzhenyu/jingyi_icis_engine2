package com.jingyicare.jingyi_icis_engine.service.patients;

public final class DeviceEnums {
    public static final int SOURCE_MODE_INBOUND_SERVER = 1;  // 采集程序作为服务端
    public static final int SOURCE_MODE_OUTBOUND_CLIENT = 2;  // 采集程序作为客户端
    public static final int SOURCE_MODE_NON_DIRECT_CONNECT = 3;  // 不直接通信

    public static final int SOURCE_TOPOLOGY_SINGLE_DEVICE = 1;  // 单设备直连
    public static final int SOURCE_TOPOLOGY_CENTRAL_STATION_FANOUT = 2;  // 中央站扇出分发

    private DeviceEnums() {
    }
}
