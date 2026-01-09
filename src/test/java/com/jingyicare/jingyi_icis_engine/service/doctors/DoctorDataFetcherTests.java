package com.jingyicare.jingyi_icis_engine.service.doctors;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.google.protobuf.util.JsonFormat;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.scores.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class DoctorDataFetcherTests extends TestsBase {
    public DoctorDataFetcherTests(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringService monitoringService,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired ScoreConfig scoreConfig,
        @Autowired ScoreService scoreService,
        @Autowired TubeService tubeService,
        @Autowired PatientTubeService patientTubeService,
        @Autowired DoctorDataFetcher doctorDataFetcher,
        @Autowired PatientRecordRepository patientRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.BALANCE_TYPE_NAN_ID = protoService.getConfig().getMonitoring()
            .getEnums().getBalanceNan().getId();

        this.deptId = "10033";
        this.accountId = "admin";

        this.protoService = protoService;
        this.monitoringService = monitoringService;
        this.patientMonitoringService = patientMonitoringService;
        this.scoreConfig = scoreConfig;
        this.scoreService = scoreService;
        this.tubeService = tubeService;
        this.patientTubeService = patientTubeService;
        this.doctorDataFetcher = doctorDataFetcher;
        this.patientRepo = patientRepo;
    }

    @Test
    public void testFetchMonitoringItems() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        final String paramCode = "test_param10008";

        // 新增观察参数 test_param10008, FLOAT
        AddMonitoringParamReq addParamReq = AddMonitoringParamReq.newBuilder()
            .setParam(
                MonitoringParamPB.newBuilder()
                    .setCode(paramCode)
                    .setName("测试参数10008")
                    .setValueMeta(
                        ValueMetaPB.newBuilder()
                            .setValueType(TypeEnumPB.FLOAT)
                            .setUnit("testUnit")
                            .setDecimalPlaces(2)
                            .setPadZeroDecimal(false)
                            .build())
                    .setBalanceType(BALANCE_TYPE_NAN_ID)
                    .build()
            )
            .build();
        String addParamReqJson = ProtoUtils.protoToJson(addParamReq);
        GenericResp addParamResp = monitoringService.addMonitoringParam(addParamReqJson);
        assertThat(addParamResp.getRt().getCode()).isEqualTo(0);

        // 构造测试数据
        PatientRecord patient = PatientTestUtils.newPatientRecord(1601L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient = patientRepo.save(patient);

        for (int i = 1; i < 10; i++) {
            LocalDateTime recordedAt = TimeUtils.getLocalTime(2025, 3, 9, i, 0);
            String recordedAtIso8601 = TimeUtils.toIso8601String(recordedAt, ZONE_ID);

            AddPatientMonitoringRecordReq addRecordReq = AddPatientMonitoringRecordReq.newBuilder()
                .setPid(patient.getId())
                .setDeptId(deptId)
                .setParamCode(paramCode)
                .setValue(GenericValuePB.newBuilder().setFloatVal(i * 1f).build())
                .setRecordedAtIso8601(recordedAtIso8601)
                .setRecordedBy(accountId)
                .build();
            String addRecordReqJson = ProtoUtils.protoToJson(addRecordReq);
            AddPatientMonitoringRecordResp addResp = patientMonitoringService.addPatientMonitoringRecord(addRecordReqJson);
            assertThat(addResp.getRt().getCode()).isEqualTo(0);
        }

        // 测试LE
        DoctorDataFetcher.MonitoringItemConfig config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), true, 1f, DoctorDataFetcher.CompOp.LE);
        DiseaseItemPB diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isTrue();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("1testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T09:00+08:00");

        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), true, 0.9f, DoctorDataFetcher.CompOp.LE);
        diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isFalse();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("1testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T09:00+08:00");

        // 测试GE
        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), false, 9f, DoctorDataFetcher.CompOp.GE);
        diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isTrue();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("9testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T17:00+08:00");

        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), false, 9.01f, DoctorDataFetcher.CompOp.GE);
        diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isFalse();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("9testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T17:00+08:00");

        // 测试LT
        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), true, 5.0f, DoctorDataFetcher.CompOp.LT);

        diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isTrue();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("1testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T09:00+08:00");

        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), true, 1f, DoctorDataFetcher.CompOp.LT);
        diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isFalse();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("1testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T09:00+08:00");

        // 测试GT
        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), false, 5.0f, DoctorDataFetcher.CompOp.GT);

        diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isTrue();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("9testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T17:00+08:00");

        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), false, 9f, DoctorDataFetcher.CompOp.GT);
        diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isFalse();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("9testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T17:00+08:00");

        // 测试EQ
        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(paramCode), false, 5.01f, DoctorDataFetcher.CompOp.EQ);
        diseaseItemPb = doctorDataFetcher.fetchMonitoringItem(
            patient.getId(), deptId, "test_mon_fetch_code", TimeUtils.getLocalTime(2025, 3, 9),
            TimeUtils.getLocalTime(2025, 3, 10), config).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isFalse();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("9testUnit");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T17:00+08:00");
    }

    @Test
    public void testFetchScoreItems() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 构造测试数据: 部门评分配置；病人；评分记录
        // ScorePB -> (*) -> ScoreGroupMetaPB, 已经在icis_config.pb.txt中定义
        // dept_score_groups -> (id | dept_score_group_id) -> patient_scores
        final String scoreGroupCode = "test_score_4";
        AddDeptScoreGroupReq addDeptScoreGroupReq = AddDeptScoreGroupReq.newBuilder()
            .setDeptId(deptId)
            .setScoreGroupCode("test_score_4")
            .setDisplayOrder(1)
            .build();
        String addDeptScoreGroupReqJson = ProtoUtils.protoToJson(addDeptScoreGroupReq);
        AddDeptScoreGroupResp addDeptScoreGroupResp = scoreService.addDeptScoreGroup(addDeptScoreGroupReqJson);
        assertThat(addDeptScoreGroupResp.getRt().getCode()).isEqualTo(0);

        // 病人
        PatientRecord patient = PatientTestUtils.newPatientRecord(1601L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient = patientRepo.save(patient);

        // 评分记录
        LocalDateTime effectiveTime = TimeUtils.getLocalTime(2025, 3, 9, 1, 0);
        AddPatientScoreReq addPatientScoreReq = AddPatientScoreReq.newBuilder()
            .setPid(patient.getId())
            .setScoreGroupCode("test_score_4")
            .setRecord(ScoreRecordPB.newBuilder()
                .setGroup(ScoreGroupPB.newBuilder()
                    .setCode(scoreGroupCode)
                    .addItem(ScoreItemPB.newBuilder()
                        .setCode("test_group4_item1")
                        .addScoreOptionCode("group4_item1_option1")
                        .build()
                    )
                    .addItem(ScoreItemPB.newBuilder()
                        .setCode("test_group4_item1")
                        .addScoreOptionCode("group4_item1_option2")
                        .build()
                    )
                    .setGroupScore(GenericValuePB.newBuilder().setInt32Val(3).build())
                    .setGroupScoreText("3分")
                )
                .setRecordedBy(accountId)
                .setRecordedAtIso8601(TimeUtils.toIso8601String(effectiveTime, ZONE_ID))
                .setNote("111")
            )
            .build();
        String addPatientScoreReqJson = ProtoUtils.protoToJson(addPatientScoreReq);
        AddPatientScoreResp addPatientScoreResp = scoreService.addPatientScore(addPatientScoreReqJson);
        assertThat(addPatientScoreResp.getRt().getCode()).isEqualTo(0);

        effectiveTime = TimeUtils.getLocalTime(2025, 3, 9, 2, 0);
        addPatientScoreReq = AddPatientScoreReq.newBuilder()
            .setPid(patient.getId())
            .setScoreGroupCode("test_score_4")
            .setRecord(ScoreRecordPB.newBuilder()
                .setGroup(ScoreGroupPB.newBuilder()
                    .setCode(scoreGroupCode)
                    .addItem(ScoreItemPB.newBuilder()
                        .setCode("test_group4_item1")
                        .addScoreOptionCode("group4_item1_option1")
                        .build()
                    )
                    .setGroupScore(GenericValuePB.newBuilder().setInt32Val(1).build())
                    .setGroupScoreText("1分")
                )
                .setRecordedBy(accountId)
                .setRecordedAtIso8601(TimeUtils.toIso8601String(effectiveTime, ZONE_ID))
                .setNote("222")
            )
            .build();
        addPatientScoreReqJson = ProtoUtils.protoToJson(addPatientScoreReq);
        addPatientScoreResp = scoreService.addPatientScore(addPatientScoreReqJson);
        assertThat(addPatientScoreResp.getRt().getCode()).isEqualTo(0);

        // 测试GT
        LocalDateTime queryStartUtc = TimeUtils.getLocalTime(2025, 3, 9, 0, 0);
        LocalDateTime queryEndUtc = TimeUtils.getLocalTime(2025, 3, 10, 0, 0);
        DoctorDataFetcher.MonitoringItemConfig config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(scoreGroupCode), false, 2f, DoctorDataFetcher.CompOp.GT);
        DiseaseItemPB diseaseItemPb = doctorDataFetcher.fetchScoreItem(
            patient.getId(), deptId, "test_score_fetch_code", queryStartUtc, queryEndUtc, config
        ).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isTrue();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("3分");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T09:00+08:00");

        config = new DoctorDataFetcher.MonitoringItemConfig(
            List.of(scoreGroupCode), false, 4f, DoctorDataFetcher.CompOp.GT);
        diseaseItemPb = doctorDataFetcher.fetchScoreItem(
            patient.getId(), deptId, "test_score_fetch_code", queryStartUtc, queryEndUtc, config
        ).getSecond().getItem();
        assertThat(diseaseItemPb.getValue().getBoolVal()).isFalse();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("3分");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T09:00+08:00");
    }

    @Test
    public void testFetchTubeItems() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 构造测试数据: 部门管道配置；病人；管道记录
        // tube_types -> (id | tube_type_id) -> patient_tube_records

        // 加管道类型
        final String tubeTypeCode = "PICCO管";
        TubeTypePB piccoTubeType = protoService.getConfig().getTube().getTypeList().getTubeTypeList().stream()
            .filter(tubeType -> tubeType.getType().equals(tubeTypeCode))
            .findFirst().get();
        assertThat(piccoTubeType).isNotNull();

        AddTubeTypeReq addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(piccoTubeType)
            .build();
        String addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        AddTubeTypeResp resp = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeId = resp.getAddedTubeTypeId();

        // 病人
        PatientRecord patient = PatientTestUtils.newPatientRecord(1603L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 3, 1));
        patient = patientRepo.save(patient);

        // 管道记录
        // 2025-03-08 12:00:00 插管
        // 2025-03-08 18:00:00 换管
        // 2025-03-09 02:00:00 拔管
        // 2025-03-09 08:00:00 插管
        // 2025-03-09 14:00:00 拔管
        final LocalDateTime newTubeTime = TimeUtils.getLocalTime(2025, 3, 8, 12, 0);
        NewPatientTubeReq newPatientTubeReq = NewPatientTubeReq.newBuilder()
            .setPid(patient.getId())
            .setTubeTypeId(tubeTypeId)
            .setTubeName("PICCO管")
            .setInsertedBy(accountId)
            .setInsertedAtIso8601(TimeUtils.toIso8601String(newTubeTime, ZONE_ID))
            .setNote("note1")
            .build();
        String newPatientTubeReqJson = ProtoUtils.protoToJson(newPatientTubeReq);
        NewPatientTubeResp newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(0);
        final Long patientTubeRecordId1 = newTubeResp.getId();

        final LocalDateTime replaceTubeTime = TimeUtils.getLocalTime(2025, 3, 8, 18, 0);
        ReplacePatientTubeReq replacePatientTubeReq = ReplacePatientTubeReq.newBuilder()
            .setReplacedTubeRecordId(patientTubeRecordId1)
            .setRemovalReason("reason1")
            .setRemovedBy(accountId)
            .setRemovedAtIso8601(TimeUtils.toIso8601String(replaceTubeTime, ZONE_ID))
            .setNewTubeTypeId(tubeTypeId)
            .setNewTubeName("PICCO管")
            .setNote("note2")
            .build();
        String replacePatientTubeReqJson = ProtoUtils.protoToJson(replacePatientTubeReq);
        NewPatientTubeResp replaceTubeResp = patientTubeService.replacePatientTube(replacePatientTubeReqJson);
        assertThat(replaceTubeResp.getRt().getCode()).isEqualTo(0);
        final Long patientTubeRecordId2 = replaceTubeResp.getId();

        final LocalDateTime removeTubeTime = TimeUtils.getLocalTime(2025, 3, 9, 2, 0);
        RemovePatientTubeReq removePatientTubeReq = RemovePatientTubeReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId2)
            .setIsUnplannedRemoval(0)
            .setRemovalReason("reason1")
            .setRemovedBy(accountId)
            .setRemovedAtIso8601(TimeUtils.toIso8601String(removeTubeTime, ZONE_ID))
            .build();
        String removePatientTubeReqJson = ProtoUtils.protoToJson(removePatientTubeReq);
        GenericResp gResp = patientTubeService.removePatientTube(removePatientTubeReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(0);

        final LocalDateTime newTubeTime2 = TimeUtils.getLocalTime(2025, 3, 9, 8, 0);
        newPatientTubeReq = NewPatientTubeReq.newBuilder()
            .setPid(patient.getId())
            .setTubeTypeId(tubeTypeId)
            .setTubeName("PICCO管")
            .setInsertedBy(accountId)
            .setInsertedAtIso8601(TimeUtils.toIso8601String(newTubeTime2, ZONE_ID))
            .setNote("note3")
            .build();
        newPatientTubeReqJson = ProtoUtils.protoToJson(newPatientTubeReq);
        newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(0);
        final Long patientTubeRecordId3 = newTubeResp.getId();

        final LocalDateTime removeTubeTime2 = TimeUtils.getLocalTime(2025, 3, 9, 14, 0);
        removePatientTubeReq = RemovePatientTubeReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId3)
            .setIsUnplannedRemoval(0)
            .setRemovalReason("reason1")
            .setRemovedBy(accountId)
            .setRemovedAtIso8601(TimeUtils.toIso8601String(removeTubeTime2, ZONE_ID))
            .build();
        removePatientTubeReqJson = ProtoUtils.protoToJson(removePatientTubeReq);
        gResp = patientTubeService.removePatientTube(removePatientTubeReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(0);

        // 测试GT
        LocalDateTime queryStartUtc = TimeUtils.getLocalTime(2025, 3, 9, 0, 0);
        LocalDateTime queryEndUtc = TimeUtils.getLocalTime(2025, 3, 10, 0, 0);
        DoctorDataFetcher.TubeDurationConfig config = new DoctorDataFetcher.TubeDurationConfig(
            tubeTypeCode, false, 24 * 60, DoctorDataFetcher.CompOp.GT);
        DiseaseItemPB diseaseItemPb = doctorDataFetcher.fetchTubeItem(
            patient.getId(), deptId, "test_tube_fetch_code", queryStartUtc, queryEndUtc, config);

        assertThat(diseaseItemPb.getValue().getBoolVal()).isFalse();
        assertThat(diseaseItemPb.getOrigValueStr()).isEqualTo("20小时");
        assertThat(diseaseItemPb.getEffectiveTimeIso8601()).isEqualTo("2025-03-09T02:00+08:00");
    }

    private final String ZONE_ID;
    private final Integer BALANCE_TYPE_NAN_ID;

    private final String deptId;
    private final String accountId;

    private final ConfigProtoService protoService;
    private final MonitoringService monitoringService;
    private final PatientMonitoringService patientMonitoringService;
    private final ScoreConfig scoreConfig;
    private final ScoreService scoreService;
    private final TubeService tubeService;
    private final PatientTubeService patientTubeService;
    private final DoctorDataFetcher doctorDataFetcher;

    private final PatientRecordRepository patientRepo;
}