package com.jingyicare.jingyi_icis_engine.service.scores;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.utils.MonitoringRecordUtils;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.common.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class ScoreMetadataServiceTests {
    private static final long PID = 90661L;
    private static final long RECORD_ID = 42L;
    private static final String CODE = "gcs";
    private static final String TIME_TEXT = "2026-11-03T01:02:03.123456Z";
    private static final LocalDateTime TIME = LocalDateTime.parse("2026-11-03T01:02:03.123456");

    private PatientScoreRepository repository;
    private ScoreService service;

    @BeforeEach
    void configureService() {
        ConfigProtoService protoService = mock(ConfigProtoService.class);
        when(protoService.getZoneId()).thenReturn("Asia/Shanghai");
        when(protoService.getConfig()).thenReturn(Config.newBuilder()
            .setZoneId("Asia/Shanghai")
            .setMonitoring(MonitoringPB.newBuilder().addParamModalOption(
                MonitoringParamModalOptionsPB.newBuilder().setParamCode("gcs_param")
                    .addOption(MonitoringModalOptionPB.newBuilder()
                        .setUiModalCode("gcs_modal").setScoreGroupCode(CODE))))
            .build());
        when(protoService.getReturnCode(any(StatusCode.class))).thenAnswer(invocation ->
            ReturnCode.newBuilder().setCode(
                ((StatusCode) invocation.getArgument(0)).getNumber()).build());
        ScoreConfig scoreConfig = mock(ScoreConfig.class);
        when(scoreConfig.getScoreGroupMeta(CODE))
            .thenReturn(ScoreGroupMetaPB.newBuilder().setCode(CODE).build());
        ScoreCalculator calculator = mock(ScoreCalculator.class);
        when(calculator.validateScoreGroup(any(), any())).thenReturn(StatusCode.OK);
        when(calculator.printGroupScore(any(), any(), any())).thenAnswer(invocation -> {
            ((StringBuilder) invocation.getArgument(2)).append("15分");
            return StatusCode.OK;
        });
        UserService userService = mock(UserService.class);
        when(userService.getAccountWithAutoId()).thenReturn(new Pair<>("123", "数据库记录人"));
        PatientService patientService = mock(PatientService.class);
        PatientRecord patient = new PatientRecord();
        patient.setId(PID);
        patient.setDeptId("test-score-metadata");
        patient.setAdmissionTime(TIME.minusDays(1));
        when(patientService.getPatientRecord(PID)).thenReturn(patient);
        repository = mock(PatientScoreRepository.class);
        when(repository.save(any(PatientScore.class))).thenAnswer(invocation -> {
            PatientScore record = invocation.getArgument(0);
            if (record.getId() == null) record.setId(RECORD_ID);
            return record;
        });
        MonitoringParamRepository paramRepository = mock(MonitoringParamRepository.class);
        when(paramRepository.findByCode("gcs_param")).thenReturn(Optional.of(
            MonitoringParam.builder().code("gcs_param").uiModalCode("gcs_modal").build()));
        service = new ScoreService(protoService, mock(MonitoringConfig.class), scoreConfig,
            calculator, userService, patientService, mock(MonitoringRecordUtils.class),
            mock(PatientNursingReportInvalidationService.class),
            mock(DeptScoreGroupRepository.class), repository, paramRepository,
            mock(PatientMonitoringRecordRepository.class), mock(AccountRepository.class));
    }

    @Test
    void shouldReadDatabaseMetadataInBothListAndSingleScoreResponses() {
        PatientScore record = existingRecord();
        record.setModifiedBy("123");
        record.setModifiedByAccountName(null);
        record.setNote(null);
        when(repository.findPatientScores(PID, CODE, TIME.minusHours(1), TIME.plusHours(1)))
            .thenReturn(List.of(record));
        when(repository.findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            PID, CODE, TIME))
            .thenReturn(Optional.of(record));

        GetPatientScoresResp listResponse = service.getPatientScores(ProtoUtils.protoToJson(
            GetPatientScoresReq.newBuilder().setPid(PID).setScoreGroupCode(CODE)
                .setQueryStartIso8601("2026-11-03T00:02:03.123456Z")
                .setQueryEndIso8601("2026-11-03T02:02:03.123456Z").build()));
        GetPatientScoreResp singleResponse = service.getPatientScore(ProtoUtils.protoToJson(
            GetPatientScoreReq.newBuilder().setPid(PID).setMonitoringParamCode("gcs_param")
                .setEffectiveTimeIso8601(TIME_TEXT).build()));

        assertThat(listResponse.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(listResponse.getRecordCount()).isEqualTo(1);
        assertThat(singleResponse.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(singleResponse.getFound()).isTrue();
        ScoreGroupPB mapped = singleResponse.getRecord().getGroup();
        assertThat(listResponse.getRecord(0).getGroup()).isEqualTo(mapped);
        assertThat(mapped.getId()).isEqualTo(RECORD_ID);
        assertThat(mapped.getRecordedBy()).isEqualTo("123");
        assertThat(mapped.getRecordedByAccountName()).isEmpty();
        assertThat(mapped.getNote()).isEmpty();
        assertThat(mapped.getRecordedAtIso8601()).isEqualTo("2026-11-03T09:02+08:00");
        assertThat(singleResponse.getRecord().getRecordedAtIso8601())
            .isEqualTo(mapped.getRecordedAtIso8601());
        assertThat(listResponse.getRecord(0).getRecordedAtIso8601())
            .isEqualTo(mapped.getRecordedAtIso8601());
        assertThat(mapped.getGroupScoreText()).isEqualTo("历史15分");
    }

    @Test
    void shouldStripEmbeddedMetadataOnAdd() {
        AddPatientScoreResp response = service.addPatientScore(ProtoUtils.protoToJson(
            AddPatientScoreReq.newBuilder().setPid(PID).setScoreGroupCode(CODE)
                .setRecord(requestRecord()).build()));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertStoredPayload();
    }

    @Test
    void shouldStripEmbeddedMetadataOnUpdate() {
        when(repository.findById(RECORD_ID)).thenReturn(Optional.of(existingRecord()));
        GenericResp response = service.updatePatientScore(ProtoUtils.protoToJson(
            UpdatePatientScoreReq.newBuilder().setId(RECORD_ID).setGroup(requestGroup())
                .setRecordedBy("123").setRecordedAtIso8601(TIME_TEXT)
                .setNote("列备注").build()));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertStoredPayload();
    }

    @Test
    void shouldStripEmbeddedMetadataWhenSaveCreatesRecord() {
        saveAndAssertPayload();
    }

    @Test
    void shouldStripEmbeddedMetadataWhenSaveUpdatesRecord() {
        when(repository.findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            PID, CODE, TIME))
            .thenReturn(Optional.of(existingRecord()));
        saveAndAssertPayload();
    }

    private void saveAndAssertPayload() {
        SavePatientScoreResp response = service.savePatientScore(ProtoUtils.protoToJson(
            SavePatientScoreReq.newBuilder().setPid(PID).setScoreGroupCode(CODE)
                .setRecord(requestRecord()).build()));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertStoredPayload();
    }

    private void assertStoredPayload() {
        ArgumentCaptor<PatientScore> captor = ArgumentCaptor.forClass(PatientScore.class);
        verify(repository).save(captor.capture());
        PatientScore stored = captor.getValue();
        assertThat(ProtoUtils.decodeScoreGroupPB(stored.getScore())).isEqualTo(
            requestGroup().toBuilder().clearId().clearRecordedBy().clearRecordedByAccountName()
                .clearRecordedAtIso8601().clearNote().build());
        assertThat(stored.getNote()).isEqualTo("列备注");
        assertThat(stored.getEffectiveTime()).isEqualTo(TIME);
    }

    private PatientScore existingRecord() {
        return PatientScore.builder().id(RECORD_ID).pid(PID).scoreGroupCode(CODE)
            .score(Base64.getEncoder().encodeToString(requestGroup().toByteArray()))
            .scoreStr("历史15分").effectiveTime(TIME).modifiedAt(TIME).isDeleted(false).build();
    }

    private ScoreRecordPB requestRecord() {
        return ScoreRecordPB.newBuilder().setGroup(requestGroup()).setRecordedBy("123")
            .setRecordedAtIso8601(TIME_TEXT).setNote("列备注").build();
    }

    private ScoreGroupPB requestGroup() {
        return ScoreGroupPB.newBuilder().setCode(CODE)
            .setGroupScore(GenericValuePB.newBuilder().setInt32Val(15)).setGroupScoreText("15")
            .setId(999L).setRecordedBy("伪造记录人").setRecordedByAccountName("伪造姓名")
            .setRecordedAtIso8601("2000-01-01T00:00:00Z").setNote("伪造备注").build();
    }
}
