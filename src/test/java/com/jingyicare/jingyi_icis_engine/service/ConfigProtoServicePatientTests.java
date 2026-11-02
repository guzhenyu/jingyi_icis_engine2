package com.jingyicare.jingyi_icis_engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

import com.google.gson.JsonParser;
import com.google.protobuf.TextFormat;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.Patient;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.EnumTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.repository.medications.IntakeTypeRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnumConverter;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class ConfigProtoServicePatientTests {
    private static final String DEFAULT_CONFIG = "config/pbtxt/icis_config.pb.txt";

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
        assertThat(raw.getPatient().getEnumsV2().getBloodTypeList()).isEmpty();
        assertThat(raw.getPatient().getEnumsV2().getBloodRhList()).isEmpty();
        ConfigProtoService service = loadConfig(path, "");
        assertThat(withoutBloodEnums(service.getConfig().getPatient())).isEqualTo(raw.getPatient());
        assertPublicBloodEnums(service);
        assertDefaultRhAliases(service.getConfig().getText().getEnumText());
    }

    @Test
    void shouldReplaceExternalBloodEnumsAndPreserveOtherFields(@TempDir Path tempDir)
        throws Exception {
        Config.Builder external = Config.newBuilder();
        TextFormat.getParser().merge("""
            zone_id: "UTC"
            patient { pending_admission_name: "外部待入科" grace_period_hours_to_readmit: 17
              enums_v2 { admission_status { id: 77 name: "外部待入科" }
                blood_type { id: 99 name: "A型" } blood_rh { id: 8 name: "RH阳性" } } }
            """, external);
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, external.build().toString(), StandardCharsets.UTF_8);
        ConfigProtoService service = loadConfig(DEFAULT_CONFIG, file.toString());
        assertThat(service.getConfig().getZoneId()).isEqualTo("UTC");
        assertThat(withoutBloodEnums(service.getConfig().getPatient()))
            .isEqualTo(withoutBloodEnums(external.getPatient()));
        assertPublicBloodEnums(service);
    }

    @Test
    void shouldGenerateBloodEnumsWhenExternalConfigOmitsPatient(@TempDir Path tempDir)
        throws Exception {
        Path file = tempDir.resolve("external.pb.txt");
        Files.writeString(file, "zone_id: \"UTC\"\n", StandardCharsets.UTF_8);
        ConfigProtoService service = loadConfig(DEFAULT_CONFIG, file.toString());
        assertThat(service.getConfig().getZoneId()).isEqualTo("UTC");
        assertThat(withoutBloodEnums(service.getConfig().getPatient()))
            .isEqualTo(withoutBloodEnums(Patient.getDefaultInstance()));
        assertPublicBloodEnums(service);
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

        ConfigProtoService service = loadConfig(DEFAULT_CONFIG, file.toString());
        Text loaded = service.getConfig().getText();
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
        PatientEnumConverter converter = new PatientEnumConverter(service);
        assertThat(converter.getBloodRhName(converter.getBloodRhId(hasNegative ? "阴性" : "阳性")))
            .isEqualTo(hasNegative ? "RH-" : "RH+");
        assertPublicBloodEnums(service);
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
        ConfigProtoService service = loadConfig(DEFAULT_CONFIG, file.toString());

        assertThatThrownBy(() -> new PatientEnumConverter(service))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Rh 别名冲突: RH阴性");
    }

    private void assertDefaultRhAliases(EnumTextPB aliases) {
        assertThat(aliases.getBloodRhNegativeStrList()).containsExactly("RH阴性");
        assertThat(aliases.getBloodRhPositiveStrList()).containsExactly("RH阳性");
    }

    private ConfigProtoService loadConfig(String path, String externalPath) {
        return loadConfig(path, externalPath,
            new ClassPathResource("config/pbtxt/common_text.pb.txt"));
    }

    private ConfigProtoService loadConfig(String path, String externalPath, Resource commonText) {
        IntakeTypeRepository intakeTypes = mock(IntakeTypeRepository.class);
        when(intakeTypes.findAll()).thenReturn(List.of());
        return new ConfigProtoService(null, externalPath, new ClassPathResource(path),
            commonText, null, null, null, null, null,
            StandardCharsets.UTF_8.name(), null, intakeTypes);
    }

    private Patient withoutBloodEnums(Patient patient) {
        Patient.Builder builder = patient.toBuilder();
        builder.getEnumsV2Builder().clearBloodType().clearBloodRh();
        return builder.build();
    }

    private void assertPublicBloodEnums(ConfigProtoService service) {
        String json = ProtoUtils.protoToJson(service.getWebApiConfig());
        assertThat(json).isNotNull();
        var enums = JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("patientEnums");
        assertThat(enums.get("bloodType")).isEqualTo(JsonParser.parseString("""
            [{"id":1,"name":"A"},{"id":2,"name":"B"},{"id":3,"name":"O"},{"id":4,"name":"AB"}]
            """));
        assertThat(enums.get("bloodRh")).isEqualTo(JsonParser.parseString("""
            [{"id":1,"name":"RH-"},{"id":2,"name":"RH+"}]
            """));
    }
}
