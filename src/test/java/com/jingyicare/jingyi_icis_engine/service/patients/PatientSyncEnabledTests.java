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

import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;

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
            when(deptRepo.findAll()).thenReturn(List.of());

            service = new PatientSyncService(
                mock(ConfigurableApplicationContext.class),
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

        final RbacDepartmentRepository deptRepo = mock(RbacDepartmentRepository.class);
        final PatientRecordRepository patientRepo = mock(PatientRecordRepository.class);
        final DiagnosisHistoryRepository diagnosisRepo = mock(DiagnosisHistoryRepository.class);
        final SurgeryHistoryRepository surgeryRepo = mock(SurgeryHistoryRepository.class);
        final HisPatientRecordRepository hisPatientRepo = mock(HisPatientRecordRepository.class);
        final PatientSyncService service;
    }
}
