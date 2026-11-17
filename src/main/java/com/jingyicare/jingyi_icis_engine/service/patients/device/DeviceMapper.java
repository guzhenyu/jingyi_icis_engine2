package com.jingyicare.jingyi_icis_engine.service.patients.device;

import java.util.Objects;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceInfo;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceInfoPB;

public final class DeviceMapper {
    private DeviceMapper() {}

    public static DeviceInfoPB toDeviceInfoPB(DeviceInfo dev) {
        return DeviceInfoPB.newBuilder()
            .setId(dev.getId())
            .setDepartmentId(dev.getDepartmentId())
            .setDeviceSn(dev.getDeviceSn())
            .setDeviceBedNumber(Objects.toString(dev.getDeviceBedNumber(), ""))
            .setDeviceType(dev.getDeviceType())
            .setDeviceName(dev.getDeviceName())
            .setDeviceIp(Objects.toString(dev.getDeviceIp(), ""))
            .setDevicePort(Objects.toString(dev.getDevicePort(), ""))
            .setDeviceDriverCode(Objects.toString(dev.getDeviceDriverCode(), ""))
            .setSourceMode(Objects.requireNonNullElse(dev.getSourceMode(), 0))
            .setSourceTopology(Objects.requireNonNullElse(dev.getSourceTopology(), 0))
            .setUpstreamDeviceId(Objects.requireNonNullElse(dev.getUpstreamDeviceId(), 0))
            .setPdsIpSeq(Objects.requireNonNullElse(dev.getPdsIpSeq(), 0))
            .build();
    }

    public static DeviceInfo toDeviceInfoEntity(DeviceInfoPB device) {
        return DeviceInfo.builder()
            .departmentId(device.getDepartmentId())
            .deviceSn(device.getDeviceSn())
            .deviceBedNumber(emptyToNull(device.getDeviceBedNumber()))
            .deviceType(device.getDeviceType())
            .deviceName(device.getDeviceName())
            .deviceIp(emptyToNull(device.getDeviceIp()))
            .devicePort(emptyToNull(device.getDevicePort()))
            .deviceDriverCode(emptyToNull(device.getDeviceDriverCode()))
            .sourceMode(device.getSourceMode() == 0 ? null : device.getSourceMode())
            .sourceTopology(device.getSourceTopology() == 0 ? null : device.getSourceTopology())
            .upstreamDeviceId(device.getUpstreamDeviceId())
            .pdsIpSeq(device.getPdsIpSeq())
            .isDeleted(false)
            .build();
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }
}
