package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.PatientBasicsPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.PatientTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.Patient;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientEnumsV2;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class PatientReadmissionInfoTests {
    private static final String MRN = "readmission-test-mrn";
    private static final LocalDateTime ADMISSION_TIME = LocalDateTime.of(2026, 10, 2, 8, 0);

    private PatientRecordRepository patientRepo;
    private PatientService patientService;

    @BeforeEach
    void setUp() {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setZoneId("Asia/Shanghai")
            .setPatient(Patient.newBuilder()
                .setPendingAdmissionName("待入科")
                .setInIcuName("在科")
                .setPendingDischargedName("待出科")
                .setDischargedName("已出科")
                .setGracePeriodHoursToReadmit(48)
                .setEnumsV2(PatientEnumsV2.newBuilder()
                    .addAdmissionStatus(enumValue(0, "待入科"))
                    .addAdmissionStatus(enumValue(1, "在科"))
                    .addAdmissionStatus(enumValue(2, "待出科"))
                    .addAdmissionStatus(enumValue(3, "已出科"))
                    .addDischargeType(enumValue(1, "转出"))
                    .addDischargeType(enumValue(2, "死亡"))
                    .addDischargeType(enumValue(3, "出院"))))
            .build());
        patientRepo = mock(PatientRecordRepository.class);
        patientService = new PatientService(
            null, protoService, null, null, null, null, null, null, null, null,
            patientRepo, null, null, null, null, null, null);
    }

    @ParameterizedTest
    @CsvSource({"0,1,2", "0,2,1", "1,0,2", "1,2,0", "2,0,1", "2,1,0"})
    void selectsLatestKnownDischargeRegardlessOfNullRecordOrder(int first, int second, int third) {
        List<PatientRecord> records = List.of(
            dischargedPatient(101L, null),
            dischargedPatient(102L, ADMISSION_TIME.minusHours(24)),
            dischargedPatient(103L, ADMISSION_TIME.minusHours(1)));
        stubHistory(List.of(records.get(first), records.get(second), records.get(third)));

        PatientBasicsPB result = patientService.appendReadmissionInfo(pendingTable()).getBasics(0);

        assertThat(result.getId()).isEqualTo(200L);
        assertThat(result.getLastPid()).isEqualTo(103L);
        assertThat(result.getLastDischargeTime())
            .isEqualTo(TimeUtils.toIso8601String(ADMISSION_TIME.minusHours(1), "Asia/Shanghai"));
    }

    @Test
    void retainsPendingPatientWhenAllDischargeTimesAreMissing() {
        stubHistory(List.of(dischargedPatient(101L, null), dischargedPatient(102L, null)));
        PatientTablePB table = pendingTable();

        assertThat(patientService.appendReadmissionInfo(table)).isEqualTo(table);
    }

    @Test
    void retainsPendingPatientWhenThereIsNoDischargeHistory() {
        stubHistory(List.of());
        PatientTablePB table = pendingTable();

        assertThat(patientService.appendReadmissionInfo(table)).isEqualTo(table);
    }

    @ParameterizedTest
    @CsvSource({"-1,false", "0,false", "1,true", "172800,true", "172801,false"})
    void preservesReadmissionWindowWithMissingDischargeHistory(long secondsBeforeAdmission, boolean eligible) {
        stubHistory(List.of(
            dischargedPatient(101L, null),
            dischargedPatient(102L, ADMISSION_TIME.minusSeconds(secondsBeforeAdmission))));
        PatientTablePB table = pendingTable();

        PatientBasicsPB result = patientService.appendReadmissionInfo(table).getBasics(0);

        if (eligible) {
            assertThat(result.getLastPid()).isEqualTo(102L);
            assertThat(result.getLastDischargeTime()).isNotEmpty();
        } else {
            assertThat(result).isEqualTo(table.getBasics(0));
        }
    }

    private void stubHistory(List<PatientRecord> records) {
        when(patientRepo.findByHisMrnInAndAdmissionStatusIn(List.of(MRN), List.of(2, 3)))
            .thenReturn(records);
    }

    private PatientTablePB pendingTable() {
        return PatientTablePB.newBuilder().addBasics(PatientBasicsPB.newBuilder()
            .setId(200L)
            .setHisMrn(MRN)
            .setAdmissionTime(TimeUtils.toIso8601String(ADMISSION_TIME, "Asia/Shanghai")))
            .build();
    }

    private PatientRecord dischargedPatient(long id, LocalDateTime dischargeTime) {
        PatientRecord patient = new PatientRecord();
        patient.setId(id);
        patient.setHisMrn(MRN);
        patient.setAdmissionStatus(3);
        patient.setDischargeTime(dischargeTime);
        return patient;
    }

    private EnumValue enumValue(int id, String name) {
        return EnumValue.newBuilder().setId(id).setName(name).build();
    }
}
