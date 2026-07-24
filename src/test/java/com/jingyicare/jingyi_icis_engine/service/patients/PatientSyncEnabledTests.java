package com.jingyicare.jingyi_icis_engine.service.patients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceEnums;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.Patient;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientEnumsV2;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

public class PatientSyncEnabledTests {
    @Test
    public void testEnabledStartsScheduledSynchronization() {
        Fixture fixture = new Fixture(true);
        try {
            fixture.service.startSyncTimer();

            verify(fixture.deptRepo, timeout(1000)).findAll();
        } finally {
            fixture.service.shutdown();
        }
    }

    @Test
    public void testDisabledDoesNotStartScheduledSynchronizationAndStillShutsDown() {
        Fixture fixture = new Fixture(false);
        try {
            fixture.service.startSyncTimer();

            verify(fixture.deptRepo, after(200).never()).findAll();
        } finally {
            fixture.service.shutdown();
        }
        ScheduledExecutorService scheduler = (ScheduledExecutorService) ReflectionTestUtils.getField(
            fixture.service, "scheduler"
        );
        assertThat(scheduler).isNotNull();
        assertThat(scheduler.isShutdown()).isTrue();
    }

    @Test
    public void testDisabledBlocksEverySyncEntryIncludingForceAndDepartmentSync() {
        Fixture fixture = new Fixture(false);
        try {
            fixture.service.syncPatientRecords(false);
            fixture.service.syncPatientRecords(true);
            fixture.service.syncPatientRecords(true, "disabled-dept");

            verifyNoInteractions(
                fixture.deptRepo,
                fixture.patientRepo,
                fixture.diagnosisRepo,
                fixture.surgeryRepo,
                fixture.hisPatientRepo
            );
        } finally {
            fixture.service.shutdown();
        }
    }

    private static class Fixture {
        Fixture(boolean enabled) {
            ConfigProtoService protoService = mock(ConfigProtoService.class);
            Config config = Config.newBuilder()
                .setPatient(Patient.newBuilder()
                    .setPendingAdmissionName("待入科")
                    .setInIcuName("在科")
                    .setPendingDischargedName("待出科")
                    .setDischargedName("出科")
                    .setEnumsV2(PatientEnumsV2.newBuilder()
                        .addAdmissionStatus(enumValue(1, "待入科"))
                        .addAdmissionStatus(enumValue(2, "在科"))
                        .addAdmissionStatus(enumValue(3, "待出科"))
                        .addAdmissionStatus(enumValue(4, "出科"))))
                .setDevice(DeviceConfigPB.newBuilder()
                    .setEnums(DeviceEnums.newBuilder()
                        .addBedType(enumValue(1, "固定授权"))
                        .addBedType(enumValue(2, "临时授权"))))
                .build();
            when(protoService.getConfig()).thenReturn(config);
            when(deptRepo.findAll()).thenReturn(List.of());

            service = new PatientSyncService(
                mock(ConfigurableApplicationContext.class),
                protoService,
                mock(PatientConfig.class),
                mock(PatientSyncUtils.class),
                deptRepo,
                patientRepo,
                diagnosisRepo,
                surgeryRepo,
                hisPatientRepo,
                mock(EntityManager.class)
            );
            ReflectionTestUtils.setField(service, "syncEnabled", enabled);
            ReflectionTestUtils.setField(service, "syncStartDelayMinutes", 0);
            ReflectionTestUtils.setField(service, "syncIntervalMinutes", 10000);
            ReflectionTestUtils.setField(service, "syncThreadJoinTimeoutSeconds", 0);
        }

        private static EnumValue enumValue(int id, String name) {
            return EnumValue.newBuilder().setId(id).setName(name).build();
        }

        final RbacDepartmentRepository deptRepo = mock(RbacDepartmentRepository.class);
        final PatientRecordRepository patientRepo = mock(PatientRecordRepository.class);
        final DiagnosisHistoryRepository diagnosisRepo = mock(DiagnosisHistoryRepository.class);
        final SurgeryHistoryRepository surgeryRepo = mock(SurgeryHistoryRepository.class);
        final HisPatientRecordRepository hisPatientRepo = mock(HisPatientRecordRepository.class);
        final PatientSyncService service;
    }
}
