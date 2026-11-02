package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.jingyicare.jingyi_icis_engine.consts.PatientConsts;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.EnumTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

public class PatientBloodTypeTests {
    private PatientEnumConverter converter;

    @BeforeEach
    void setUp() {
        converter = new PatientEnumConverter(protoServiceWithAliases(EnumTextPB.newBuilder()
            .addBloodRhNegativeStr("RH阴性").addBloodRhPositiveStr("RH阳性").build()));
    }

    @Test
    public void testStandardNamesAndLegacyRhNames() {
        assertThat(converter.getBloodTypeId("A")).isEqualTo(PatientConsts.BLOOD_TYPE_A);
        assertThat(converter.getBloodTypeId("B")).isEqualTo(PatientConsts.BLOOD_TYPE_B);
        assertThat(converter.getBloodTypeId("O")).isEqualTo(PatientConsts.BLOOD_TYPE_O);
        assertThat(converter.getBloodTypeId("AB")).isEqualTo(PatientConsts.BLOOD_TYPE_AB);
        assertThat(converter.getBloodRhId("RH-")).isEqualTo(PatientConsts.BLOOD_RH_NEGATIVE);
        assertThat(converter.getBloodRhId("RH+")).isEqualTo(PatientConsts.BLOOD_RH_POSITIVE);
        assertThat(converter.getBloodRhId("RH阴性")).isEqualTo(PatientConsts.BLOOD_RH_NEGATIVE);
        assertThat(converter.getBloodRhId("RH阳性")).isEqualTo(PatientConsts.BLOOD_RH_POSITIVE);
    }

    @Test
    public void testUnknownValuesRemainUnknown() {
        for (String value : Arrays.asList(null, "", " ", "a", " A ", "AB ", "A型", "未知")) {
            assertThat(converter.getBloodTypeId(value)).as("ABO: %s", value).isZero();
        }
        for (String value : Arrays.asList(null, "", " ", "+", "-", "rh+", "RH+ ", " RH阳性", "未知")) {
            assertThat(converter.getBloodRhId(value)).as("Rh: %s", value).isZero();
        }
    }

    @Test
    public void testIdsReturnStandardNames() {
        assertThat(converter.getBloodTypeName(1)).isEqualTo("A");
        assertThat(converter.getBloodTypeName(2)).isEqualTo("B");
        assertThat(converter.getBloodTypeName(3)).isEqualTo("O");
        assertThat(converter.getBloodTypeName(4)).isEqualTo("AB");
        assertThat(converter.getBloodRhName(1)).isEqualTo("RH-");
        assertThat(converter.getBloodRhName(2)).isEqualTo("RH+");
        assertThat(converter.getBloodRhName(converter.getBloodRhId("RH阴性")))
            .isEqualTo("RH-");
        assertThat(converter.getBloodRhName(converter.getBloodRhId("RH阳性")))
            .isEqualTo("RH+");
    }

    @Test
    public void testUnknownIdsReturnEmptyNames() {
        for (Integer id : Arrays.asList(null, 0, -1, 999)) {
            assertThat(converter.getBloodTypeName(id)).as("ABO ID: %s", id).isEmpty();
            assertThat(converter.getBloodRhName(id)).as("Rh ID: %s", id).isEmpty();
        }
        assertThat(converter.getBloodTypeName(5)).isEmpty();
        assertThat(converter.getBloodRhName(3)).isEmpty();
    }

    @Test
    void shouldSupportMultipleConfiguredAliasesAndWriteCanonicalNames() {
        converter = new PatientEnumConverter(protoServiceWithAliases(EnumTextPB.newBuilder()
            .addBloodRhNegativeStr("阴性").addBloodRhNegativeStr("Rh阴")
            .addBloodRhPositiveStr("阳性").addBloodRhPositiveStr("Rh阳").build()));

        for (String value : Arrays.asList("RH-", "阴性", "Rh阴")) {
            assertThat(converter.getBloodRhId(value)).isEqualTo(PatientConsts.BLOOD_RH_NEGATIVE);
            assertThat(converter.getBloodRhName(converter.getBloodRhId(value))).isEqualTo("RH-");
        }
        for (String value : Arrays.asList("RH+", "阳性", "Rh阳")) {
            assertThat(converter.getBloodRhId(value)).isEqualTo(PatientConsts.BLOOD_RH_POSITIVE);
            assertThat(converter.getBloodRhName(converter.getBloodRhId(value))).isEqualTo("RH+");
        }
        assertThat(converter.getBloodRhId("RH阴性")).isZero();
        assertThat(converter.getBloodRhId("RH阳性")).isZero();
    }

    @Test
    void shouldAcceptRepeatedAliasesForTheSamePolarity() {
        converter = new PatientEnumConverter(protoServiceWithAliases(EnumTextPB.newBuilder()
            .addBloodRhNegativeStr("RH-").addBloodRhNegativeStr("RH-")
            .addBloodRhNegativeStr("阴性").addBloodRhNegativeStr("阴性")
            .addBloodRhPositiveStr("RH+").addBloodRhPositiveStr("RH+").build()));

        assertThat(converter.getBloodRhId("阴性")).isEqualTo(PatientConsts.BLOOD_RH_NEGATIVE);
        assertThat(converter.getBloodRhId("RH+")).isEqualTo(PatientConsts.BLOOD_RH_POSITIVE);
    }

    @Test
    void shouldRecognizeCanonicalNamesWithoutAliasConfig() {
        converter = new PatientEnumConverter(
            protoServiceWithAliases(EnumTextPB.getDefaultInstance()));

        assertThat(converter.getBloodRhId("RH-")).isEqualTo(PatientConsts.BLOOD_RH_NEGATIVE);
        assertThat(converter.getBloodRhId("RH+")).isEqualTo(PatientConsts.BLOOD_RH_POSITIVE);
        assertThat(converter.getBloodRhId("RH阴性")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"同一别名,同一别名,同一别名", "RH+,阳性,RH+", "阴性,RH-,RH-"})
    void shouldRejectConflictingAliasesAtStartup(
        String negative, String positive, String conflict
    ) {
        ConfigProtoService protoService = protoServiceWithAliases(EnumTextPB.newBuilder()
            .addBloodRhNegativeStr(negative).addBloodRhPositiveStr(positive).build());

        new ApplicationContextRunner()
            .withBean(ConfigProtoService.class, () -> protoService)
            .withUserConfiguration(PatientEnumConverter.class)
            .run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasRootCauseInstanceOf(IllegalArgumentException.class)
                    .hasRootCauseMessage("Rh 别名冲突: " + conflict);
            });
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "\t"})
    void shouldRejectBlankAliases(String alias) {
        ConfigProtoService negative = protoServiceWithAliases(
            EnumTextPB.newBuilder().addBloodRhNegativeStr(alias).build());
        ConfigProtoService positive = protoServiceWithAliases(
            EnumTextPB.newBuilder().addBloodRhPositiveStr(alias).build());

        assertThatThrownBy(() -> new PatientEnumConverter(negative))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Rh 别名不能为空");
        assertThatThrownBy(() -> new PatientEnumConverter(positive))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Rh 别名不能为空");
    }

    private ConfigProtoService protoServiceWithAliases(EnumTextPB aliases) {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setText(Text.newBuilder().setEnumText(aliases)).build());
        return protoService;
    }
}
