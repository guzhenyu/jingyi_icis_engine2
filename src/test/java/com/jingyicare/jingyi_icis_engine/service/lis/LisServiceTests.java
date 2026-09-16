package com.jingyicare.jingyi_icis_engine.service.lis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.entity.lis.PatientLisItem;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.lis.ExternalLisParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.lis.LisParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.lis.PatientLisItemRepository;
import com.jingyicare.jingyi_icis_engine.repository.lis.PatientLisResultRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;

class LisServiceTests {
    @BeforeEach
    void setUp() {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getConfig()).thenReturn(Config.newBuilder().setZoneId("UTC").build());
        externalLisParamRepo = mock(ExternalLisParamRepository.class);
        patientLisItemRepo = mock(PatientLisItemRepository.class);
        patientLisResultRepo = mock(PatientLisResultRepository.class);
        service = new LisService(
            protoService,
            mock(UserService.class),
            mock(PatientService.class),
            externalLisParamRepo,
            patientLisItemRepo,
            patientLisResultRepo,
            mock(LisParamRepository.class)
        );
    }

    @Test
    void skipsHistoricalLisItemsWithMissingNamesInsteadOfFailingTheQuery() {
        LocalDateTime queryStart = LocalDateTime.of(2026, 9, 16, 0, 0);
        LocalDateTime queryEnd = LocalDateTime.of(2026, 9, 17, 0, 0);
        PatientLisItem missingName = PatientLisItem.builder()
            .reportId("2046884")
            .hisPid("1711922")
            .authTime(LocalDateTime.of(2026, 9, 16, 6, 39, 38))
            .build();
        PatientLisItem blankName = PatientLisItem.builder()
            .reportId("2046885")
            .hisPid("1711922")
            .lisItemName("   ")
            .authTime(LocalDateTime.of(2026, 9, 16, 6, 40))
            .build();
        when(patientLisItemRepo.findByHisPidAndAuthTimeBetween(
            "1711922", queryStart, queryEnd
        )).thenReturn(new ArrayList<>(List.of(missingName, blankName)));
        when(patientLisItemRepo.findByHisPidAndAuthTimeIsNull("1711922"))
            .thenReturn(List.of());
        when(externalLisParamRepo.findAll()).thenReturn(List.of());

        PatientRecord patient = new PatientRecord();
        patient.setId(20L);
        patient.setHisPatientId("1711922");

        var result = service.getPatientLisItems(patient, queryStart, queryEnd);

        assertThat(result.getFirst()).isEqualTo(StatusCode.OK);
        assertThat(result.getSecond()).isEmpty();
        verifyNoInteractions(patientLisResultRepo);
    }

    @Test
    void excludesMalformedLisItemsWhileContinuingToQueryValidReports() {
        LocalDateTime queryStart = LocalDateTime.of(2026, 9, 16, 0, 0);
        LocalDateTime queryEnd = LocalDateTime.of(2026, 9, 17, 0, 0);
        PatientLisItem missingName = PatientLisItem.builder()
            .reportId("2046884")
            .hisPid("1711922")
            .authTime(LocalDateTime.of(2026, 9, 16, 6, 39, 38))
            .build();
        PatientLisItem valid = PatientLisItem.builder()
            .reportId("2046886")
            .hisPid("1711922")
            .lisItemName("血气+乳酸测定")
            .authTime(LocalDateTime.of(2026, 9, 16, 6, 41))
            .build();
        when(patientLisItemRepo.findByHisPidAndAuthTimeBetween(
            "1711922", queryStart, queryEnd
        )).thenReturn(new ArrayList<>(List.of(missingName, valid)));
        when(patientLisItemRepo.findByHisPidAndAuthTimeIsNull("1711922"))
            .thenReturn(List.of());
        when(externalLisParamRepo.findAll()).thenReturn(List.of());
        when(patientLisResultRepo.findByReportIdInAndIsDeletedFalse(List.of("2046886")))
            .thenReturn(List.of());

        PatientRecord patient = new PatientRecord();
        patient.setId(20L);
        patient.setHisPatientId("1711922");

        var result = service.getPatientLisItems(patient, queryStart, queryEnd);

        assertThat(result.getFirst()).isEqualTo(StatusCode.OK);
        assertThat(result.getSecond()).isEmpty();
        verify(patientLisResultRepo).findByReportIdInAndIsDeletedFalse(List.of("2046886"));
    }

    private ExternalLisParamRepository externalLisParamRepo;
    private PatientLisItemRepository patientLisItemRepo;
    private PatientLisResultRepository patientLisResultRepo;
    private LisService service;
}
