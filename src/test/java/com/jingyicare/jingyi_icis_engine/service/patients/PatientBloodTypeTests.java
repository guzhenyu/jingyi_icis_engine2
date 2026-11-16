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

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.EnumTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;

public class PatientBloodTypeTests {
    private PatientEnums patientEnums;

    @BeforeEach
    void setUp() {
        patientEnums = new PatientEnums(protoConfigWithAliases(EnumTextPB.newBuilder()
            .addBloodRhNegativeStr("RH阴性").addBloodRhPositiveStr("RH阳性").build()));
    }

    @Test
    public void testStandardNamesAndLegacyRhNames() {
        assertThat(patientEnums.getBloodTypeId("A")).isEqualTo(PatientEnums.BLOOD_TYPE_A);
        assertThat(patientEnums.getBloodTypeId("B")).isEqualTo(PatientEnums.BLOOD_TYPE_B);
        assertThat(patientEnums.getBloodTypeId("O")).isEqualTo(PatientEnums.BLOOD_TYPE_O);
        assertThat(patientEnums.getBloodTypeId("AB")).isEqualTo(PatientEnums.BLOOD_TYPE_AB);
        assertThat(patientEnums.getBloodRhId("RH-")).isEqualTo(PatientEnums.BLOOD_RH_NEGATIVE);
        assertThat(patientEnums.getBloodRhId("RH+")).isEqualTo(PatientEnums.BLOOD_RH_POSITIVE);
        assertThat(patientEnums.getBloodRhId("RH阴性")).isEqualTo(PatientEnums.BLOOD_RH_NEGATIVE);
        assertThat(patientEnums.getBloodRhId("RH阳性")).isEqualTo(PatientEnums.BLOOD_RH_POSITIVE);
    }

    @Test
    public void testUnknownValuesRemainUnknown() {
        for (String value : Arrays.asList(null, "", " ", "a", " A ", "AB ", "A型", "未知")) {
            assertThat(patientEnums.getBloodTypeId(value)).as("ABO: %s", value).isZero();
        }
        for (String value : Arrays.asList(null, "", " ", "+", "-", "rh+", "RH+ ", " RH阳性", "未知")) {
            assertThat(patientEnums.getBloodRhId(value)).as("Rh: %s", value).isZero();
        }
    }

    @Test
    public void testIdsReturnStandardNames() {
        assertThat(patientEnums.getBloodTypeName(1)).isEqualTo("A");
        assertThat(patientEnums.getBloodTypeName(2)).isEqualTo("B");
        assertThat(patientEnums.getBloodTypeName(3)).isEqualTo("O");
        assertThat(patientEnums.getBloodTypeName(4)).isEqualTo("AB");
        assertThat(patientEnums.getBloodRhName(1)).isEqualTo("RH-");
        assertThat(patientEnums.getBloodRhName(2)).isEqualTo("RH+");
        assertThat(patientEnums.getBloodRhName(patientEnums.getBloodRhId("RH阴性")))
            .isEqualTo("RH-");
        assertThat(patientEnums.getBloodRhName(patientEnums.getBloodRhId("RH阳性")))
            .isEqualTo("RH+");
    }

    @Test
    public void testUnknownIdsReturnEmptyNames() {
        for (Integer id : Arrays.asList(null, 0, -1, 999)) {
            assertThat(patientEnums.getBloodTypeName(id)).as("ABO ID: %s", id).isEmpty();
            assertThat(patientEnums.getBloodRhName(id)).as("Rh ID: %s", id).isEmpty();
        }
        assertThat(patientEnums.getBloodTypeName(5)).isEmpty();
        assertThat(patientEnums.getBloodRhName(3)).isEmpty();
    }

    @Test
    void shouldSupportMultipleConfiguredAliasesAndWriteCanonicalNames() {
        patientEnums = new PatientEnums(protoConfigWithAliases(EnumTextPB.newBuilder()
            .addBloodRhNegativeStr("阴性").addBloodRhNegativeStr("Rh阴")
            .addBloodRhPositiveStr("阳性").addBloodRhPositiveStr("Rh阳").build()));

        for (String value : Arrays.asList("RH-", "阴性", "Rh阴")) {
            assertThat(patientEnums.getBloodRhId(value)).isEqualTo(PatientEnums.BLOOD_RH_NEGATIVE);
            assertThat(patientEnums.getBloodRhName(patientEnums.getBloodRhId(value)))
                .isEqualTo("RH-");
        }
        for (String value : Arrays.asList("RH+", "阳性", "Rh阳")) {
            assertThat(patientEnums.getBloodRhId(value)).isEqualTo(PatientEnums.BLOOD_RH_POSITIVE);
            assertThat(patientEnums.getBloodRhName(patientEnums.getBloodRhId(value)))
                .isEqualTo("RH+");
        }
        assertThat(patientEnums.getBloodRhId("RH阴性")).isZero();
        assertThat(patientEnums.getBloodRhId("RH阳性")).isZero();
    }

    @Test
    void shouldAcceptRepeatedAliasesForTheSamePolarity() {
        patientEnums = new PatientEnums(protoConfigWithAliases(EnumTextPB.newBuilder()
            .addBloodRhNegativeStr("RH-").addBloodRhNegativeStr("RH-")
            .addBloodRhNegativeStr("阴性").addBloodRhNegativeStr("阴性")
            .addBloodRhPositiveStr("RH+").addBloodRhPositiveStr("RH+").build()));

        assertThat(patientEnums.getBloodRhId("阴性")).isEqualTo(PatientEnums.BLOOD_RH_NEGATIVE);
        assertThat(patientEnums.getBloodRhId("RH+")).isEqualTo(PatientEnums.BLOOD_RH_POSITIVE);
    }

    @Test
    void shouldRecognizeCanonicalNamesWithoutAliasConfig() {
        patientEnums = new PatientEnums(
            protoConfigWithAliases(EnumTextPB.getDefaultInstance()));

        assertThat(patientEnums.getBloodRhId("RH-")).isEqualTo(PatientEnums.BLOOD_RH_NEGATIVE);
        assertThat(patientEnums.getBloodRhId("RH+")).isEqualTo(PatientEnums.BLOOD_RH_POSITIVE);
        assertThat(patientEnums.getBloodRhId("RH阴性")).isZero();
    }

    @ParameterizedTest
    @CsvSource({"同一别名,同一别名,同一别名", "RH+,阳性,RH+", "阴性,RH-,RH-"})
    void shouldRejectConflictingAliasesAtStartup(
        String negative, String positive, String conflict
    ) {
        ProtoConfig protoConfig = protoConfigWithAliases(EnumTextPB.newBuilder()
            .addBloodRhNegativeStr(negative).addBloodRhPositiveStr(positive).build());

        new ApplicationContextRunner()
            .withBean(ProtoConfig.class, () -> protoConfig)
            .withUserConfiguration(PatientEnums.class)
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
        ProtoConfig negative = protoConfigWithAliases(
            EnumTextPB.newBuilder().addBloodRhNegativeStr(alias).build());
        ProtoConfig positive = protoConfigWithAliases(
            EnumTextPB.newBuilder().addBloodRhPositiveStr(alias).build());

        assertThatThrownBy(() -> new PatientEnums(negative))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Rh 别名不能为空");
        assertThatThrownBy(() -> new PatientEnums(positive))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Rh 别名不能为空");
    }

    private ProtoConfig protoConfigWithAliases(EnumTextPB aliases) {
        ProtoConfig protoConfig = mock(ProtoConfig.class);
        when(protoConfig.getConfig()).thenReturn(Config.newBuilder()
            .setText(Text.newBuilder().setEnumText(aliases)).build());
        return protoConfig;
    }
}
