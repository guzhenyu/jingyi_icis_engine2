package com.jingyicare.jingyi_icis_engine.service.debug;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceData;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceInfoPB;
import com.jingyicare.jingyi_icis_engine.repository.patients.DeviceDataRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.DeviceInfoRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientConfig;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Service
public class DeviceDebugger {
    public DeviceDebugger(
        @Autowired DeviceInfoRepository deviceInfoRepository,
        @Autowired DeviceDataRepository deviceDataRepository,
        @Autowired PatientConfig patientConfig
    ) {
        this.deviceInfoRepository = deviceInfoRepository;
        this.deviceDataRepository = deviceDataRepository;
        this.patientConfig = patientConfig;
    }

    public String listDevInfos() {
        List<DeviceInfoPB> deviceInfos = deviceInfoRepository
            .findAllByIsDeletedFalse()
            .stream()
            .map(patientConfig::toDeviceInfoPB)
            .toList();
        return formatDeviceInfos(deviceInfos);
    }

    public String getLatestDevData(Integer deviceId) {
        return deviceDataRepository
            .findFirstByDeviceIdOrderByRecordedAtDesc(deviceId)
            .map(DeviceDebugger::formatDeviceData)
            .orElse("");
    }

    static String formatDeviceInfos(List<DeviceInfoPB> deviceInfos) {
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

    private DeviceInfoRepository deviceInfoRepository;
    private DeviceDataRepository deviceDataRepository;
    private PatientConfig patientConfig;
}
