package com.jingyicare.jingyi_icis_engine.service;

import static com.jingyicare.jingyi_icis_engine.service.patients.device.DeviceEnums.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.TextFormat;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceDriverPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceEnumsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceTypeEntryPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.repository.medications.IntakeTypeRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientConfig;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class ConfigProtoServiceDeviceTests {
    @ParameterizedTest
    @ValueSource(strings = {
        DEFAULT_CONFIG_PATH,
        "config/pbtxt/hospitals/ah2_icis_config.pb.txt",
        "config/pbtxt/hospitals/xaxrmyy_icis_config.pb.txt",
        "config/pbtxt/hospitals/xnxrmyy_icis_config.pb.txt"
    })
    void shouldLoadCommonDeviceForAllProductionConfigs(String path) throws Exception {
        assertThat(readConfig(path).hasDevice()).isFalse();

        Config loaded = loadConfig(path, "").getConfig();
        DeviceConfigPB device = loaded.getDevice();
        assertThat(device).isEqualTo(readCommonDevice());
        assertThat(device.getApproxSeconds()).isEqualTo(1800);
        assertThat(device.getParamCodeToApproxList()).containsExactly("nibp_s", "nibp_d", "nibp_m");
        assertThat(device.getDeviceCascade().getEntryList())
            .extracting(DeviceTypeEntryPB::getDeviceTypeId)
            .containsExactly(DEVICE_TYPE_MONITOR, DEVICE_TYPE_VENTILATOR,
                DEVICE_TYPE_BLOOD_GAS_ANALYZER, DEVICE_TYPE_PICCO, DEVICE_TYPE_ECMO,
                DEVICE_TYPE_CRRT, DEVICE_TYPE_IMMUNOASSAY_ANALYZER, DEVICE_TYPE_URINE_ANALYZER,
                DEVICE_TYPE_CENTRAL_STATION, DEVICE_TYPE_GENERIC_COLLECTOR);
        assertThat(device.getDeviceCascade().getEntryList().stream()
            .flatMap(entry -> entry.getDriverList().stream())
            .map(DeviceDriverPB::getDriverCode)
            .toList()).doesNotHaveDuplicates();

        DeviceTypeEntryPB centralStation = device.getDeviceCascade().getEntryList().stream()
            .filter(entry -> entry.getDeviceTypeId() == DEVICE_TYPE_CENTRAL_STATION)
            .findFirst().orElseThrow();
        assertThat(centralStation.getDriverList()).extracting(DeviceDriverPB::getDriverCode)
            .containsExactly("jd_cms_mindray_egateway", "jd_cms_mindray_pds_realtime",
                "jd_cms_philips_intellivue", "JY_cms_comen_hl7");
        assertThat(centralStation.getDriverList()).extracting(DeviceDriverPB::getDriverName)
            .containsExactly("eGateway(HL7推送)", "PDS Realtime", "IntelliVue", "科曼中央站");
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 10})
    void shouldRejectUndefinedCascadeDeviceTypeAtStartup(int deviceTypeId) {
        Config.Builder config = loadConfig(DEFAULT_CONFIG_PATH, "").getConfig().toBuilder();
        config.getDeviceBuilder().getDeviceCascadeBuilder().addEntryBuilder()
            .setDeviceTypeId(deviceTypeId).setDeviceTypeName("测试设备");
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);
        ConfigProtoService service = new ConfigProtoService(config.build(), null, null, null);
        PatientConfig patientConfig = new PatientConfig(
            context, service, null, null);

        try (MockedStatic<LogUtils> logUtils = mockStatic(LogUtils.class)) {
            patientConfig.checkIntegrity();
            logUtils.verify(() -> LogUtils.flushAndQuit(context));
        }
    }

    @Test
    void shouldPreserveInlineTestDeviceConfig() throws Exception {
        DeviceConfigPB expected = readConfig(TEST_CONFIG_PATH).getDevice();

        DeviceConfigPB loaded = loadConfig(TEST_CONFIG_PATH, "").getConfig().getDevice();

        assertThat(loaded).isEqualTo(expected);
        assertThat(loaded.getApproxSeconds()).isEqualTo(300);
    }

    @Test
    void shouldLoadCommonDeviceWhenExternalConfigOmitsDevice(@TempDir Path tempDir)
        throws Exception {
        Path externalConfig = tempDir.resolve("icis_config.pb.txt");
        Files.writeString(externalConfig, "zone_id: \"Asia/Shanghai\"\n", StandardCharsets.UTF_8);

        ProtoConfig protoConfig = loadConfig(TEST_CONFIG_PATH, externalConfig.toString());
        Config loaded = protoConfig.getConfig();

        assertThat(loaded.getZoneId()).isEqualTo("Asia/Shanghai");
        assertThat(loaded.getDevice()).isEqualTo(readCommonDevice());
        assertThat(publicDeviceEnums(protoConfig).getBedTypeList())
            .extracting(EnumValue::getId, EnumValue::getName)
            .containsExactly(tuple(1, "固定授权"), tuple(2, "临时授权"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "bed_type { id: 91 name: \"固定授权\" }"})
    void shouldUseExternalDeviceAndFixedBedTypes(String legacyBedType, @TempDir Path tempDir)
        throws Exception {
        Path externalConfig = tempDir.resolve("icis_config.pb.txt");
        Files.writeString(externalConfig, """
            patient { enums_v2 { bed_type_fixed: "常设床" bed_type_temp: "临时加床" } }
            device {
                approx_seconds: 42
                enums { source_mode { id: 7 name: "外部模式" } %s }
            }
            """.formatted(legacyBedType), StandardCharsets.UTF_8);

        ProtoConfig protoConfig = loadConfig(DEFAULT_CONFIG_PATH, externalConfig.toString());

        DeviceConfigPB.Builder expected = DeviceConfigPB.newBuilder().setApproxSeconds(42);
        expected.getEnumsBuilder().addSourceModeBuilder().setId(7).setName("外部模式");
        TextFormat.getParser().merge(legacyBedType, expected.getEnumsBuilder());
        DeviceEnumsPB published = publicDeviceEnums(protoConfig);
        assertThat(protoConfig.getConfig().getDevice()).isEqualTo(expected.build());
        assertThat(published.getSourceModeList())
            .isEqualTo(expected.getEnums().getSourceModeList());
        assertThat(published.getBedTypeList()).extracting(EnumValue::getId, EnumValue::getName)
            .containsExactly(tuple(1, "常设床"), tuple(2, "临时加床"));
    }

    @Test
    void shouldExposeFixedBedIdsAndNamesWithDeviceEnums() {
        DeviceEnumsPB published = publicDeviceEnums(loadConfig(DEFAULT_CONFIG_PATH, ""));
        assertThat(published.getBedTypeList())
            .extracting(EnumValue::getId, EnumValue::getName)
            .containsExactly(tuple(1, "固定授权"), tuple(2, "临时授权"));
        String json = ProtoUtils.protoToJson(published);
        JsonObject enums = JsonParser.parseString(json).getAsJsonObject();

        assertThat(enums.keySet()).containsExactlyInAnyOrder(
            "bedType", "sourceMode", "sourceTopology");
    }

    private ProtoConfig loadConfig(String path, String externalPath) {
        return new ProtoConfig(
            null, externalPath, new ClassPathResource(path),
            new ClassPathResource("config/pbtxt/common_text.pb.txt"), null,
            new ClassPathResource(COMMON_DEVICE_PATH), null, null, null,
            StandardCharsets.UTF_8.name()
        );
    }

    private DeviceEnumsPB publicDeviceEnums(ProtoConfig protoConfig) {
        ConfigProtoService service = new ConfigProtoService(protoConfig.getConfig(), null,
            mock(IntakeTypeRepository.class), new PatientEnums(protoConfig));
        return service.getWebApiConfig().getDeviceEnums();
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

    private static final String COMMON_DEVICE_PATH = "config/pbtxt/common_device.pb.txt";
    private static final String DEFAULT_CONFIG_PATH = "config/pbtxt/icis_config.pb.txt";
    private static final String TEST_CONFIG_PATH = "text_resources/icis_config.pb.txt";
}
