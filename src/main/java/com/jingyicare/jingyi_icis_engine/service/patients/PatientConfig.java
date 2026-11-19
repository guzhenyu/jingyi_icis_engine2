package com.jingyicare.jingyi_icis_engine.service.patients;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceCascadePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceTypeEntryPB;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;

@Service
@Slf4j
public class PatientConfig {
    public PatientConfig(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService
    ) {
        this.context = context;
        this.protoService = protoService;
    }

    public void checkIntegrity() {
        if (!checkDeviceCascade()) {
            log.error("Patient config integrity check failed. Exiting...");
            LogUtils.flushAndQuit(context);
        }
    }

    private boolean checkDeviceCascade() {
        DeviceCascadePB cascade = protoService.getConfig().getDevice().getDeviceCascade();
        for (DeviceTypeEntryPB entry : cascade.getEntryList()) {
            if (!DeviceEnums.isValidDeviceTypeId(entry.getDeviceTypeId())) {
                log.error("Unknown device_cascade device_type_id {} for '{}'",
                    entry.getDeviceTypeId(), entry.getDeviceTypeName());
                return false;
            }
        }
        return true;
    }

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
}
