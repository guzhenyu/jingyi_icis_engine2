package com.jingyicare.jingyi_icis_engine.service.debug;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceData;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceInfoPB;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class DeviceDebuggerTests {
    @Test
    void formatsDeviceInfosAsPbTextSeparatedByNewlines() {
        DeviceInfoPB firstPb = DeviceInfoPB.newBuilder().setId(1).setDeviceName("monitor").build();
        DeviceInfoPB secondPb = DeviceInfoPB.newBuilder().setId(2).setDeviceName("ventilator").build();

        String result = DeviceDebugger.formatDeviceInfos(List.of(firstPb, secondPb));

        assertThat(result).isEqualTo(
            ProtoUtils.protoToTxt(firstPb) + "\n" + ProtoUtils.protoToTxt(secondPb) + "\n"
        );
    }

    @Test
    void formatsDeviceDataFieldsSeparatedBySpaces() {
        LocalDateTime recordedAt = LocalDateTime.of(2026, 9, 12, 10, 30, 15);
        DeviceData deviceData = DeviceData.builder()
            .departmentId("ICU")
            .deviceId(18)
            .deviceType(2)
            .deviceBedNumber("08")
            .paramCode("HR")
            .recordedAt(recordedAt)
            .recordedStr("72")
            .build();

        String result = DeviceDebugger.formatDeviceData(deviceData);

        assertThat(result).isEqualTo("ICU 18 2 08 HR 2026-09-12T10:30:15 72");
    }

    @Test
    void formatsMissingDeviceDataAsEmptyText() {
        assertThat(DeviceDebugger.formatDeviceData(null)).isEmpty();
    }
}
