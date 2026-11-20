package com.jingyicare.jingyi_icis_engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.protobuf.TextFormat;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetConfigResp;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientEnumsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.EnumTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.repository.medications.IntakeTypeRepository;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonEnums;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class ConfigProtoServicePatientTests {
    @ParameterizedTest
    @ValueSource(strings = {DEFAULT_CONFIG, "config/pbtxt/hospitals/ah2_icis_config.pb.txt",
        "config/pbtxt/hospitals/xaxrmyy_icis_config.pb.txt",
        "config/pbtxt/hospitals/xnxrmyy_icis_config.pb.txt", "text_resources/icis_config.pb.txt"})
    void shouldGenerateBloodEnumsAndPreserveOtherPatientSettings(String path) throws Exception {
        Config.Builder raw = Config.newBuilder();
        try (InputStreamReader reader = new InputStreamReader(
                new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)) {
            TextFormat.getParser().merge(reader, raw);
        }
        PatientEnumsPB patientEnums = raw.getPatient().getEnumsV2();
        assertThat(patientEnums.getBloodTypeList()).isEmpty();
        assertThat(patientEnums.getBloodRhList()).isEmpty();
        assertThat(List.of(
            patientEnums.getAdmissionTypeHospitalName(),
            patientEnums.getAdmissionTypeTransferName(),
            patientEnums.getAdmissionTypeSurgeryName(),
            patientEnums.getAdmissionTypeRescueName(),
            patientEnums.getAdmissionTypeSevereName(),
            patientEnums.getAdmissionTypeOtherHospitalName(),
            patientEnums.getAdmissionTypeCriticalName()
        )).containsExactly("入院", "转入", "手术", "抢救", "重症", "外院", "病危");
        ProtoConfig protoConfig = loadConfig(path, "");
        assertThat(protoConfig.getConfig().getPatient()).isEqualTo(raw.getPatient());
        GetConfigResp publicConfig = getPublicConfig(protoConfig);
        assertThat(publicConfig.getPatientAdmissionTypesList())
            .extracting(EnumValue::getId, EnumValue::getName)
            .containsExactly(tuple(1, "入院"), tuple(2, "转入"), tuple(3, "手术"),
                tuple(4, "抢救"), tuple(5, "重症"), tuple(6, "外院"), tuple(7, "病危"));
        assertThat(publicConfig.getPatientDischargeTypesList())
            .extracting(EnumValue::getId, EnumValue::getName)
            .containsExactly(tuple(1, "转出"), tuple(2, "死亡"), tuple(3, "出院"));
        assertPublicBloodEnums(protoConfig);
        assertDefaultRhAliases(protoConfig.getConfig().getText().getEnumText());
    }

    @Test
    void shouldReplaceExternalBloodEnumsAndPreserveOtherFields(@TempDir Path tempDir)
        throws Exception {
        Config.Builder external = Config.newBuilder();
        TextFormat.getParser().merge("""
            zone_id: "UTC"
            patient { grace_period_hours_to_readmit: 17
              enums_v2 { pending_admission_name: "外部待入科"
                admission_type_hospital_name: "外部入院方式"
                blood_type { id: 99 name: "A型" } blood_rh { id: 8 name: "RH阳性" } } }
            """, external);
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, external.build().toString(), StandardCharsets.UTF_8);
        ProtoConfig protoConfig = loadConfig(DEFAULT_CONFIG, file.toString());
        assertThat(protoConfig.getConfig().getZoneId()).isEqualTo("UTC");
        assertThat(protoConfig.getConfig().getPatient()).isEqualTo(external.getPatient());
        assertPublicBloodEnums(protoConfig);
    }

    @Test
    void shouldGenerateBloodEnumsWhenExternalConfigOmitsPatient(@TempDir Path tempDir)
        throws Exception {
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, "zone_id: \"UTC\"\n", StandardCharsets.UTF_8);
        ProtoConfig protoConfig = loadConfig(DEFAULT_CONFIG, file.toString());
        assertThat(protoConfig.getConfig().getZoneId()).isEqualTo("UTC");
        assertThat(protoConfig.getConfig().hasPatient()).isFalse();
        assertPublicBloodEnums(protoConfig);
    }

    @Test
    void shouldBackfillRhAliasesWithoutReplacingLegacyText(@TempDir Path tempDir) throws Exception {
        Config.Builder external = Config.newBuilder();
        TextFormat.getParser().merge("""
            text {
                status_code_msg: "外部状态码"
                units { unit { id: 77 name: "外部单位" } }
                web_api_message { yes_str: "Y" no_str: "N" }
            }
            """, external);
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, external.build().toString(), StandardCharsets.UTF_8);

        Text loaded = loadConfig(DEFAULT_CONFIG, file.toString()).getConfig().getText();

        assertThat(loaded.toBuilder().clearEnumText().build()).isEqualTo(external.getText());
        assertDefaultRhAliases(loaded.getEnumText());
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void shouldPreserveConfiguredAliasesAndBackfillOnlyMissingList(
        boolean hasStatusCodes, boolean hasNegative, @TempDir Path tempDir
    ) throws Exception {
        EnumTextPB.Builder aliases = EnumTextPB.newBuilder();
        if (hasNegative) {
            aliases.addBloodRhNegativeStr("阴性").addBloodRhNegativeStr("Rh阴");
        } else {
            aliases.addBloodRhPositiveStr("阳性").addBloodRhPositiveStr("Rh阳");
        }
        Text.Builder text = Text.newBuilder().setEnumText(aliases);
        if (hasStatusCodes) text.addStatusCodeMsg("外部状态码");
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, Config.newBuilder().setText(text).build().toString(),
            StandardCharsets.UTF_8);

        ProtoConfig protoConfig = loadConfig(DEFAULT_CONFIG, file.toString());
        Text loaded = protoConfig.getConfig().getText();
        EnumTextPB loadedAliases = loaded.getEnumText();
        assertThat(loadedAliases.getBloodRhNegativeStrList())
            .containsExactlyElementsOf(hasNegative ? List.of("阴性", "Rh阴") : List.of("RH阴性"));
        assertThat(loadedAliases.getBloodRhPositiveStrList())
            .containsExactlyElementsOf(hasNegative ? List.of("RH阳性") : List.of("阳性", "Rh阳"));
        if (hasStatusCodes) {
            assertThat(loaded.toBuilder().clearEnumText().build())
                .isEqualTo(text.clearEnumText().build());
        } else {
            assertThat(loaded.getStatusCodeMsgList()).startsWith("ok");
        }
        PatientEnums converter = new PatientEnums(protoConfig);
        assertThat(converter.getBloodRhName(converter.getBloodRhId(hasNegative ? "阴性" : "阳性")))
            .isEqualTo(hasNegative ? "RH-" : "RH+");
        assertPublicBloodEnums(protoConfig);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void shouldPreserveBothExternalAliasLists(boolean hasStatusCodes, @TempDir Path tempDir)
        throws Exception {
        EnumTextPB aliases = EnumTextPB.newBuilder()
            .addBloodRhNegativeStr("阴性").addBloodRhPositiveStr("阳性").build();
        Text.Builder text = Text.newBuilder().setEnumText(aliases);
        if (hasStatusCodes) text.addStatusCodeMsg("外部状态码");
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, Config.newBuilder().setText(text).build().toString(),
            StandardCharsets.UTF_8);

        Text loaded = loadConfig(DEFAULT_CONFIG, file.toString()).getConfig().getText();

        assertThat(loaded.getEnumText()).isEqualTo(aliases);
        assertThat(loaded.getStatusCodeMsgList())
            .startsWith(hasStatusCodes ? "外部状态码" : "ok");
    }

    @Test
    void shouldBackfillFromConfiguredCommonText(@TempDir Path tempDir) throws Exception {
        Resource commonText = new ByteArrayResource("""
            status_code_msg: "公共状态码"
            enum_text {
                blood_rh_negative_str: "公共阴性"
                blood_rh_positive_str: "公共阳性"
            }
            """.getBytes(StandardCharsets.UTF_8));
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, "text { status_code_msg: \"外部状态码\" }", StandardCharsets.UTF_8);

        Text loaded = loadConfig(DEFAULT_CONFIG, file.toString(), commonText).getConfig().getText();

        assertThat(loaded.getStatusCodeMsgList()).containsExactly("外部状态码");
        assertThat(loaded.getEnumText().getBloodRhNegativeStrList()).containsExactly("公共阴性");
        assertThat(loaded.getEnumText().getBloodRhPositiveStrList()).containsExactly("公共阳性");
    }

    @Test
    void shouldRejectAliasesThatConflictAfterBackfill(@TempDir Path tempDir) throws Exception {
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, """
            text {
                status_code_msg: "外部状态码"
                enum_text { blood_rh_positive_str: "RH阴性" }
            }
            """, StandardCharsets.UTF_8);
        ProtoConfig protoConfig = loadConfig(DEFAULT_CONFIG, file.toString());
        assertThatThrownBy(() -> new PatientEnums(protoConfig))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Rh 别名冲突: RH阴性");
    }

    private void assertDefaultRhAliases(EnumTextPB aliases) {
        assertThat(aliases.getBloodRhNegativeStrList()).containsExactly("RH阴性");
        assertThat(aliases.getBloodRhPositiveStrList()).containsExactly("RH阳性");
    }

    private ProtoConfig loadConfig(String path, String externalPath) {
        return loadConfig(path, externalPath,
            new ClassPathResource("config/pbtxt/common_text.pb.txt"));
    }

    private ProtoConfig loadConfig(String path, String externalPath, Resource commonText) {
        return new ProtoConfig(null, externalPath, new ClassPathResource(path),
            commonText, null, null, null, null, null, StandardCharsets.UTF_8.name());
    }

    private void assertPublicBloodEnums(ProtoConfig protoConfig) {
        String configured = ProtoUtils.protoToJson(
            protoConfig.getConfig().getPatient().getEnumsV2());
        JsonObject expected = JsonParser.parseString(configured).getAsJsonObject();
        expected.add("bloodType", JsonParser.parseString("""
            [{"id":1,"name":"A"},{"id":2,"name":"B"},{"id":3,"name":"O"},{"id":4,"name":"AB"}]
            """));
        expected.add("bloodRh", JsonParser.parseString("""
            [{"id":1,"name":"RH-"},{"id":2,"name":"RH+"}]
            """));
        assertThat(getPublicPatientEnums(protoConfig)).isEqualTo(expected);
    }

    @Test
    void shouldExposeAdmissionNamesInsteadOfTheOldStatusDictionary() {
        JsonObject enums = getPublicPatientEnums(loadConfig(DEFAULT_CONFIG, ""));
        assertThat(enums.has("admissionStatus")).isFalse();
        assertThat(enums.get("pendingAdmissionName").getAsString()).isEqualTo("待入科");
        assertThat(enums.get("inIcuName").getAsString()).isEqualTo("在科");
        assertThat(enums.get("pendingDischargedName").getAsString()).isEqualTo("待出科");
        assertThat(enums.get("dischargedName").getAsString()).isEqualTo("已出科");
    }

    @Test
    void shouldExposeConfiguredAdmissionNamesWithStableCodes(@TempDir Path tempDir)
        throws Exception {
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, """
            patient { enums_v2 { admission_type_surgery_name: "术后转入" } }
            """, StandardCharsets.UTF_8);
        ProtoConfig protoConfig = loadConfig(DEFAULT_CONFIG, file.toString());
        GetConfigResp response = getPublicConfig(protoConfig);

        assertThat(response.getPatientAdmissionTypesList())
            .extracting(EnumValue::getId, EnumValue::getName)
            .containsExactly(tuple(1, "入院"), tuple(2, "转入"), tuple(3, "术后转入"),
                tuple(4, "抢救"), tuple(5, "重症"), tuple(6, "外院"), tuple(7, "病危"));

        JsonObject json = JsonParser.parseString(ProtoUtils.protoToJson(response))
            .getAsJsonObject();
        assertThat(json.has("patientAdmissionTypes")).isTrue();
        assertThat(json.getAsJsonObject("patientEnums").has("admissionType")).isFalse();
    }

    @Test
    void shouldExposeConfiguredDischargeNamesWithStableCodes(@TempDir Path tempDir)
        throws Exception {
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, """
            patient { enums_v2 {
                discharge_type_transfer_name: "转往其他科室"
                discharge_type_dead_name: "院内死亡"
                discharge_type_exit_hospital_name: "离院"
            } }
            """, StandardCharsets.UTF_8);
        ProtoConfig protoConfig = loadConfig(DEFAULT_CONFIG, file.toString());

        GetConfigResp response = getPublicConfig(protoConfig);
        assertThat(response.getPatientDischargeTypesList())
            .extracting(EnumValue::getId, EnumValue::getName)
            .containsExactly(tuple(1, "转往其他科室"), tuple(2, "院内死亡"), tuple(3, "离院"));

        JsonObject json = JsonParser.parseString(ProtoUtils.protoToJson(response))
            .getAsJsonObject();
        assertThat(json.has("patientDischargeTypes")).isTrue();
        assertThat(json.getAsJsonObject("patientEnums").has("dischargeType")).isFalse();
    }

    private JsonObject getPublicPatientEnums(ProtoConfig protoConfig) {
        String json = ProtoUtils.protoToJson(getPublicConfig(protoConfig));
        assertThat(json).isNotNull();
        return JsonParser.parseString(json).getAsJsonObject()
            .getAsJsonObject("patientEnums");
    }

    private GetConfigResp getPublicConfig(ProtoConfig protoConfig) {
        IntakeTypeRepository intakeTypes = mock(IntakeTypeRepository.class);
        when(intakeTypes.findAll()).thenReturn(List.of());
        ConfigProtoService service = new ConfigProtoService(
            protoConfig.getConfig(), null, intakeTypes, new PatientEnums(protoConfig),
            new MonEnums(protoConfig));
        return service.getWebApiConfig();
    }

    private static final String DEFAULT_CONFIG = "config/pbtxt/icis_config.pb.txt";
}
