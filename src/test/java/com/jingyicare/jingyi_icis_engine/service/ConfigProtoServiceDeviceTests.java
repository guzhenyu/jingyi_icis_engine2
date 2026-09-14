package com.jingyicare.jingyi_icis_engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;

import com.google.protobuf.TextFormat;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceDriverPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceTypeEntryPB;

class ConfigProtoServiceDeviceTests {
    private static final String COMMON_DEVICE_PATH = "config/pbtxt/common_device.pb.txt";
    private static final String DEFAULT_CONFIG_PATH = "config/pbtxt/icis_config.pb.txt";
    private static final String TEST_CONFIG_PATH = "text_resources/icis_config.pb.txt";

    @ParameterizedTest
    @ValueSource(strings = {
        DEFAULT_CONFIG_PATH,
        "config/pbtxt/hospitals/ah2_icis_config.pb.txt",
        "config/pbtxt/hospitals/xaxrmyy_icis_config.pb.txt",
        "config/pbtxt/hospitals/xnxrmyy_icis_config.pb.txt"
    })
    void shouldLoadCommonDeviceForAllProductionConfigs(String path) throws Exception {
        assertThat(readConfig(path).hasDevice()).isFalse();

        Config loaded = loadConfig(path, "");
        DeviceConfigPB device = loaded.getDevice();
        assertThat(device).isEqualTo(readCommonDevice());
        assertThat(device.getApproxSeconds()).isEqualTo(1800);
        assertThat(device.getParamCodeToApproxList()).containsExactly("nibp_s", "nibp_d", "nibp_m");
        assertThat(device.getDeviceCascade().getEntryList())
            .extracting(DeviceTypeEntryPB::getDeviceTypeId)
            .containsExactly(1, 2, 3, 4, 5, 6, 7, 8, 9, 101);
        assertThat(device.getDeviceCascade().getEntryList().stream()
            .flatMap(entry -> entry.getDriverList().stream())
            .map(DeviceDriverPB::getDriverCode)
            .toList()).doesNotHaveDuplicates();

        DeviceTypeEntryPB centralStation = device.getDeviceCascade().getEntryList().stream()
            .filter(entry -> entry.getDeviceTypeId() == 9)
            .findFirst().orElseThrow();
        assertThat(centralStation.getDriverList()).extracting(DeviceDriverPB::getDriverCode)
            .containsExactly("jd_cms_mindray_egateway", "jd_cms_mindray_pds_realtime",
                "jd_cms_philips_intellivue", "JY_cms_comen_hl7");
        assertThat(centralStation.getDriverList()).extracting(DeviceDriverPB::getDriverName)
            .containsExactly("eGateway(HL7推送)", "PDS Realtime", "IntelliVue", "科曼中央站");
    }

    @Test
    void shouldPreserveInlineTestDeviceConfig() throws Exception {
        DeviceConfigPB expected = readConfig(TEST_CONFIG_PATH).getDevice();

        DeviceConfigPB loaded = loadConfig(TEST_CONFIG_PATH, "").getDevice();

        assertThat(loaded).isEqualTo(expected);
        assertThat(loaded.getApproxSeconds()).isEqualTo(300);
    }

    @Test
    void shouldLoadCommonDeviceWhenExternalConfigOmitsDevice(@TempDir Path tempDir) throws Exception {
        Path externalConfig = tempDir.resolve("icis_config.pb.txt");
        Files.writeString(externalConfig, "zone_id: \"Asia/Shanghai\"\n", StandardCharsets.UTF_8);

        // The external config must take precedence over even a resource with an inline device block.
        Config loaded = loadConfig(TEST_CONFIG_PATH, externalConfig.toString());

        assertThat(loaded.getZoneId()).isEqualTo("Asia/Shanghai");
        assertThat(loaded.getDevice()).isEqualTo(readCommonDevice());
    }

    @Test
    void shouldUseExternalDeviceAsWholeOverride(@TempDir Path tempDir) throws Exception {
        Path externalConfig = tempDir.resolve("icis_config.pb.txt");
        Files.writeString(externalConfig, "device { approx_seconds: 42 }\n", StandardCharsets.UTF_8);

        DeviceConfigPB loaded = loadConfig(DEFAULT_CONFIG_PATH, externalConfig.toString()).getDevice();

        assertThat(loaded).isEqualTo(DeviceConfigPB.newBuilder().setApproxSeconds(42).build());
    }

    private Config loadConfig(String path, String externalPath) {
        ConfigProtoService service = new ConfigProtoService(
            null, externalPath, new ClassPathResource(path), null, null,
            new ClassPathResource(COMMON_DEVICE_PATH), null, null, null,
            StandardCharsets.UTF_8.name(), null, null
        );
        return service.getConfig();
    }

    private Config readConfig(String path) throws Exception {
        Config.Builder builder = Config.newBuilder();
        try (InputStreamReader reader = new InputStreamReader(
            new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8
        )) {
            TextFormat.getParser().merge(reader, builder);
        }
        return builder.build();
    }

    private DeviceConfigPB readCommonDevice() throws Exception {
        DeviceConfigPB.Builder builder = DeviceConfigPB.newBuilder();
        try (InputStreamReader reader = new InputStreamReader(
            new ClassPathResource(COMMON_DEVICE_PATH).getInputStream(), StandardCharsets.UTF_8
        )) {
            TextFormat.getParser().merge(reader, builder);
        }
        return builder.build();
    }
}
