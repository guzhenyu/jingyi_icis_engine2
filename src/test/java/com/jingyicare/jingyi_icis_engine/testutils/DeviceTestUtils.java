package com.jingyicare.jingyi_icis_engine.testutils;

import java.time.LocalDateTime;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;

public class DeviceTestUtils {
    public static DeviceInfo newDeviceInfo(String deptId, String devBedNum, Integer type, String name) {
        DeviceInfo devInfo = new DeviceInfo();
        devInfo.setDepartmentId(deptId);
        devInfo.setDeviceSn("sn-" + name);
        devInfo.setDeviceBedNumber(devBedNum);
        devInfo.setDeviceType(type);
        devInfo.setDeviceName(name);
        devInfo.setIsDeleted(false);

        return devInfo;
    }

    public static PatientDevice newPatientDevice(
        Long pid, Integer devId, LocalDateTime bindingTime, LocalDateTime unbindingTime
    ) {
        PatientDevice patientDev = new PatientDevice();
        patientDev.setPatientId(pid);
        patientDev.setDeviceId(devId);
        patientDev.setBindingTime(bindingTime);
        patientDev.setUnbindingTime(unbindingTime);
        patientDev.setIsDeleted(false);

        return patientDev;
    }

    public static DeviceData newDeviceData(
        String deptId, Integer devId, Integer devType, String devBedNum,
        String paramCode, LocalDateTime recordedAt, String val
    ) {
        DeviceData devData = new DeviceData();
        devData.setDepartmentId(deptId);
        devData.setDeviceId(devId);
        devData.setDeviceType(devType);
        devData.setDeviceBedNumber(devBedNum);
        devData.setParamCode(paramCode);
        devData.setRecordedAt(recordedAt);
        devData.setRecordedStr(val);

        return devData;
    }

    public static DeviceDataHourly newDeviceDataHourly(
        String deptId, Integer devId, Integer devType, String devBedNum,
        String paramCode, LocalDateTime recordedAt, String val
    ) {
        DeviceDataHourly devData = new DeviceDataHourly();
        devData.setDepartmentId(deptId);
        devData.setDeviceId(devId);
        devData.setDeviceType(devType);
        devData.setDeviceBedNumber(devBedNum);
        devData.setParamCode(paramCode);
        devData.setRecordedAt(recordedAt);
        devData.setRecordedStr(val);

        return devData;
    }

    public static DeviceDataHourlyApprox newDeviceDataHourlyApprox(
        String deptId, Integer devId, Integer devType, String devBedNum,
        String paramCode, LocalDateTime recordedAt, String val
    ) {
        DeviceDataHourlyApprox devData = new DeviceDataHourlyApprox();
        devData.setDepartmentId(deptId);
        devData.setDeviceId(devId);
        devData.setDeviceType(devType);
        devData.setDeviceBedNumber(devBedNum);
        devData.setParamCode(paramCode);
        devData.setRecordedAt(recordedAt);
        devData.setRecordedStr(val);

        return devData;
    }
}