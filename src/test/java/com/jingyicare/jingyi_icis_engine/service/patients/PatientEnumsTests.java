package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientEnumsV2;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;

class PatientEnumsTests {
    @BeforeEach
    void prepare() {
        ProtoConfig protoConfig = mock(ProtoConfig.class);
        Config config = Config.newBuilder().setPatient(PatientConfigPB.newBuilder()
            .setEnumsV2(PatientEnumsV2.newBuilder()
                .setPendingAdmissionName("等待入科")
                .setInIcuName("正在诊疗")
                .setPendingDischargedName("准备离科")
                .setDischargedName("完成出科"))).build();
        when(protoConfig.getConfig()).thenReturn(config);
        patientEnums = new PatientEnums(protoConfig);
    }

    @Test
    void configuredNamesUseFixedAdmissionStatusCodes() {
        assertThat(patientEnums.getAdmissionStatusName(PatientEnums.ADMISSION_STATUS_PRE_ADMISSION))
            .isEqualTo("等待入科");
        assertThat(patientEnums.getAdmissionStatusName(PatientEnums.ADMISSION_STATUS_ADMITTED))
            .isEqualTo("正在诊疗");
        assertThat(patientEnums.getAdmissionStatusName(PatientEnums.ADMISSION_STATUS_PRE_DISCHARGE))
            .isEqualTo("准备离科");
        assertThat(patientEnums.getAdmissionStatusName(PatientEnums.ADMISSION_STATUS_DISCHARGED))
            .isEqualTo("完成出科");
    }

    @Test
    void unknownAdmissionStatusHasNoDisplayName() {
        assertThat(patientEnums.getAdmissionStatusName(null)).isEmpty();
        assertThat(patientEnums.getAdmissionStatusName(-1)).isEmpty();
        assertThat(patientEnums.getAdmissionStatusName(4)).isEmpty();
    }

    private PatientEnums patientEnums;
}
