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
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
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
    private MonitoringRecordUtils monitoringRecordUtils;
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
        when(userService.getNameByAutoId("321")).thenReturn("指定记录人");
        AccountRepository accountRepository = mock(AccountRepository.class);
        Account recorder = new Account();
        recorder.setId(321L);
        recorder.setAccountId("recorder");
        recorder.setName("指定记录人");
        when(accountRepository.findByAccountIdAndIsDeletedFalse("recorder"))
            .thenReturn(Optional.of(recorder));
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
        monitoringRecordUtils = mock(MonitoringRecordUtils.class);
        service = new ScoreService(protoService, mock(MonitoringConfig.class), scoreConfig,
            calculator, userService, patientService, monitoringRecordUtils,
            mock(PatientNursingReportInvalidationService.class),
            mock(DeptScoreGroupRepository.class), repository, paramRepository,
            mock(PatientMonitoringRecordRepository.class), accountRepository);
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
        ScoreGroupPB mapped = singleResponse.getRecord();
        assertThat(listResponse.getRecord(0)).isEqualTo(mapped);
        assertThat(mapped.getId()).isEqualTo(RECORD_ID);
        assertThat(mapped.getRecordedBy()).isEqualTo("123");
        assertThat(mapped.getRecordedByAccountName()).isEmpty();
        assertThat(mapped.getNote()).isEmpty();
        assertThat(mapped.getRecordedAtIso8601()).isEqualTo("2026-11-03T09:02+08:00");
        assertThat(mapped.getGroupScoreText()).isEqualTo("历史15分");
    }

    @Test
    void shouldPersistMetadataOutsideScorePayloadOnAdd() {
        AddPatientScoreResp response = service.addPatientScore(ProtoUtils.protoToJson(
            AddPatientScoreReq.newBuilder().setPid(PID)
                .setRecord(requestRecord()).build()));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        PatientScore stored = assertStoredPayload("列备注");
        assertThat(stored.getModifiedBy()).isEqualTo("321");
        assertThat(stored.getRawModifiedByAccountName()).isEqualTo("指定记录人");
    }

    @Test
    void shouldPersistMetadataOutsideScorePayloadOnUpdate() {
        when(repository.findById(RECORD_ID)).thenReturn(Optional.of(existingRecord()));
        GenericResp response = service.updatePatientScore(ProtoUtils.protoToJson(
            UpdatePatientScoreReq.newBuilder().setRecord(requestRecord().toBuilder()
                .setId(RECORD_ID).setRecordedBy("321")).build()));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        PatientScore stored = assertStoredPayload("列备注");
        assertThat(stored.getModifiedBy()).isEqualTo("321");
        assertThat(stored.getRawModifiedByAccountName()).isEqualTo("指定记录人");
    }

    @Test
    void shouldKeepTimeAndClearEmptyMetadataOnUpdate() {
        PatientScore existing = existingRecord();
        existing.setNote("旧备注");
        existing.setModifiedBy("321");
        existing.setModifiedByAccountName("指定记录人");
        when(repository.findById(RECORD_ID)).thenReturn(Optional.of(existing));
        GenericResp response = service.updatePatientScore(ProtoUtils.protoToJson(
            UpdatePatientScoreReq.newBuilder().setRecord(requestRecord().toBuilder()
                .setId(RECORD_ID).clearRecordedAtIso8601().clearNote().clearRecordedBy())
                .build()));

        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        PatientScore stored = assertStoredPayload("");
        assertThat(stored.getModifiedBy()).isEmpty();
        assertThat(stored.getRawModifiedByAccountName()).isNull();
    }

    @Test
    void shouldPersistMetadataOutsideScorePayloadWhenSaveCreatesRecord() {
        saveAndAssertPayload();
    }

    @Test
    void shouldPersistMetadataOutsideScorePayloadWhenSaveUpdatesRecord() {
        when(repository.findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            PID, CODE, TIME))
            .thenReturn(Optional.of(existingRecord()));
        saveAndAssertPayload();
    }

    private void saveAndAssertPayload() {
        SavePatientScoreResp response = service.savePatientScore(ProtoUtils.protoToJson(
            SavePatientScoreReq.newBuilder().setPid(PID)
                .setRecord(requestRecord()).build()));
        assertThat(response.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(response.getGroupScoreText()).isEqualTo("15分");
        PatientScore stored = assertStoredPayload("列备注");
        assertThat(stored.getModifiedBy()).isEqualTo("123");
        assertThat(stored.getRawModifiedByAccountName()).isEqualTo("数据库记录人");
        assertThat(stored.getScoreStr()).isEqualTo("15分");
        verify(repository).findByPidAndScoreGroupCodeAndEffectiveTimeAndIsDeletedFalse(
            PID, CODE, TIME);
        ArgumentCaptor<PatientMonitoringRecord> monitoringCaptor =
            ArgumentCaptor.forClass(PatientMonitoringRecord.class);
        verify(monitoringRecordUtils).saveRecordJdbc(monitoringCaptor.capture());
        PatientMonitoringRecord monitoring = monitoringCaptor.getValue();
        assertThat(monitoring.getPid()).isEqualTo(PID);
        assertThat(monitoring.getMonitoringParamCode()).isEqualTo("gcs_param");
        assertThat(monitoring.getEffectiveTime()).isEqualTo(TIME);
        assertThat(monitoring.getParamValueStr()).isEqualTo("15分");
        assertThat(monitoring.getNote()).isEqualTo("列备注");
        assertThat(monitoring.getModifiedBy()).isEqualTo("123");
    }

    private PatientScore assertStoredPayload(String note) {
        ArgumentCaptor<PatientScore> captor = ArgumentCaptor.forClass(PatientScore.class);
        verify(repository).save(captor.capture());
        PatientScore stored = captor.getValue();
        assertThat(ProtoUtils.decodeScoreGroupPB(stored.getScore())).isEqualTo(
            requestGroup().toBuilder().clearId().clearRecordedBy().clearRecordedByAccountName()
                .clearRecordedAtIso8601().clearNote().build());
        assertThat(stored.getId()).isEqualTo(RECORD_ID);
        assertThat(stored.getScoreGroupCode()).isEqualTo(CODE);
        assertThat(stored.getNote()).isEqualTo(note);
        assertThat(stored.getEffectiveTime()).isEqualTo(TIME);
        return stored;
    }

    private PatientScore existingRecord() {
        return PatientScore.builder().id(RECORD_ID).pid(PID).scoreGroupCode(CODE)
            .score(Base64.getEncoder().encodeToString(requestGroup().toByteArray()))
            .scoreStr("历史15分").effectiveTime(TIME).modifiedAt(TIME).isDeleted(false).build();
    }

    private ScoreGroupPB requestRecord() {
        return requestGroup().toBuilder().setRecordedBy("recorder")
            .setRecordedAtIso8601(TIME_TEXT).setNote("列备注").build();
    }

    private ScoreGroupPB requestGroup() {
        return ScoreGroupPB.newBuilder().setCode(CODE)
            .setGroupScore(GenericValuePB.newBuilder().setInt32Val(15)).setGroupScoreText("15")
            .setId(999L).setRecordedBy("伪造记录人").setRecordedByAccountName("伪造姓名")
            .setRecordedAtIso8601("2000-01-01T00:00:00Z").setNote("伪造备注").build();
    }
}
