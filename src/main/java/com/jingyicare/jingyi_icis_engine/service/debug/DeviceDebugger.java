package com.jingyicare.jingyi_icis_engine.service.debug;

import java.util.List;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceData;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetDeviceInfoReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DevicePB;
import com.jingyicare.jingyi_icis_engine.repository.patients.DeviceDataRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceMapper;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceService;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Service
public class DeviceDebugger {
    public DeviceDebugger(
        DeviceService deviceService,
        DeviceDataRepository deviceDataRepository
    ) {
        this.deviceService = deviceService;
        this.deviceDataRepository = deviceDataRepository;
    }

    public String listDevInfos() {
        List<DevicePB> deviceInfos = deviceService
            .searchActiveDevices(GetDeviceInfoReq.getDefaultInstance()).stream()
            .map(DeviceMapper::toDevicePB)
            .toList();
        return formatDeviceInfos(deviceInfos);
    }

    public String getLatestDevData(Integer deviceId) {
        return deviceDataRepository
            .findFirstByDeviceIdOrderByRecordedAtDesc(deviceId)
            .map(DeviceDebugger::formatDeviceData)
            .orElse("");
    }

    static String formatDeviceInfos(List<DevicePB> deviceInfos) {
        StringBuilder result = new StringBuilder();
        deviceInfos.forEach(deviceInfo -> {
            result.append(ProtoUtils.protoToTxt(deviceInfo));
            result.append('\n');
        });
        return result.toString();
    }

    static String formatDeviceData(DeviceData deviceData) {
        if (deviceData == null) {
            return "";
        }

        return deviceData.getDepartmentId() + " "
            + deviceData.getDeviceId() + " "
            + deviceData.getDeviceType() + " "
            + deviceData.getDeviceBedNumber() + " "
            + deviceData.getParamCode() + " "
            + deviceData.getRecordedAt() + " "
            + deviceData.getRecordedStr();
    }

    private final DeviceService deviceService;
    private final DeviceDataRepository deviceDataRepository;
}
