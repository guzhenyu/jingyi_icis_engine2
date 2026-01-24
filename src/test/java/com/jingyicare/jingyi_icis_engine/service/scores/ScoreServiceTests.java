package com.jingyicare.jingyi_icis_engine.service.scores;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class ScoreServiceTests extends TestsBase {
    public ScoreServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired ScoreConfig scoreConfig,
        @Autowired ScoreService scoreService,
        @Autowired PatientScoreRepository patientScoreRepo,
        @Autowired RbacDepartmentRepository rbacDepartmentRepo,
        @Autowired PatientRecordRepository patientRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.accountId = "admin";
        this.deptId = "10014";
        this.deptId2 = "10015";
        this.protoService = protoService;
        this.scoreConfig = scoreConfig;
        this.scoreService = scoreService;
        this.patientScoreRepo = patientScoreRepo;
        this.rbacDepartmentRepo = rbacDepartmentRepo;
        this.patientRepo = patientRepo;
        this.patientTestUtils = new PatientTestUtils();

        initPatient();
    }

    @Test
    public void testScoreGroupMetas() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initDepartments();

        // 查询部门评分
        GetScoreGroupMetaReq getScoreGroupMetaReq = GetScoreGroupMetaReq.newBuilder().setDeptId("").build();
        String getScoreGroupMetaReqJson = ProtoUtils.protoToJson(getScoreGroupMetaReq);
        GetScoreGroupMetaResp getScoreGroupMetaResp = scoreService.getScoreGroupMeta(getScoreGroupMetaReqJson);
        assertThat(getScoreGroupMetaResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getScoreGroupMetaResp.getScoreGroupMetaList()).hasSize(4);

        getScoreGroupMetaReq = GetScoreGroupMetaReq.newBuilder().setDeptId(deptId).build();
        getScoreGroupMetaReqJson = ProtoUtils.protoToJson(getScoreGroupMetaReq);
        getScoreGroupMetaResp = scoreService.getScoreGroupMeta(getScoreGroupMetaReqJson);
        assertThat(getScoreGroupMetaResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getScoreGroupMetaResp.getScoreGroupMetaList()).hasSize(1);

        // 新增部门评分
        AddDeptScoreGroupReq addDeptScoreGroupReq = AddDeptScoreGroupReq.newBuilder()
            .setDeptId(deptId)
            .setScoreGroupCode("test_score_1")
            .setDisplayOrder(1)
            .build();
        String addDeptScoreGroupReqJson = ProtoUtils.protoToJson(addDeptScoreGroupReq);
        AddDeptScoreGroupResp addDeptScoreGroupResp = scoreService.addDeptScoreGroup(addDeptScoreGroupReqJson);
        // default_score_group_code: "test_score_1"
        assertThat(addDeptScoreGroupResp.getRt().getCode()).isEqualTo(StatusCode.DEPT_SCORE_GROUP_ALREADY_EXISTS.ordinal());

        addDeptScoreGroupReq = AddDeptScoreGroupReq.newBuilder()
            .setDeptId(deptId)
            .setScoreGroupCode("test_score_2")
            .setDisplayOrder(2)
            .build();
        addDeptScoreGroupReqJson = ProtoUtils.protoToJson(addDeptScoreGroupReq);
        addDeptScoreGroupResp = scoreService.addDeptScoreGroup(addDeptScoreGroupReqJson);
        assertThat(addDeptScoreGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        addDeptScoreGroupReq = AddDeptScoreGroupReq.newBuilder()
            .setDeptId(deptId)
            .setScoreGroupCode("test_score_3")
            .setDisplayOrder(3)
            .build();
        addDeptScoreGroupReqJson = ProtoUtils.protoToJson(addDeptScoreGroupReq);
        addDeptScoreGroupResp = scoreService.addDeptScoreGroup(addDeptScoreGroupReqJson);
        assertThat(addDeptScoreGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getScoreGroupMetaReq = GetScoreGroupMetaReq.newBuilder().setDeptId(deptId).build();
        getScoreGroupMetaReqJson = ProtoUtils.protoToJson(getScoreGroupMetaReq);
        getScoreGroupMetaResp = scoreService.getScoreGroupMeta(getScoreGroupMetaReqJson);
        assertThat(getScoreGroupMetaResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getScoreGroupMetaResp.getScoreGroupMetaList()).hasSize(3);

        // 为部门评分调整次序
        ReorderDeptScoreGroupsReq reorderDeptScoreGroupsReq = ReorderDeptScoreGroupsReq.newBuilder()
            .setDeptId(deptId)
            .addGroupOrder(
                ScoreGroupOrder.newBuilder()
                    .setId(getScoreGroupMetaResp.getScoreGroupMeta(2).getId())
                    .setDisplayOrder(1)
            )
            .addGroupOrder(
                ScoreGroupOrder.newBuilder()
                    .setId(getScoreGroupMetaResp.getScoreGroupMeta(1).getId())
                    .setDisplayOrder(2)
            )
            .addGroupOrder(
                ScoreGroupOrder.newBuilder()
                    .setId(getScoreGroupMetaResp.getScoreGroupMeta(0).getId())
                    .setDisplayOrder(3)
            )
            .build();
        String reorderDeptScoreGroupsReqJson = ProtoUtils.protoToJson(reorderDeptScoreGroupsReq);
        GenericResp reorderDeptScoreGroupsResp = scoreService.reorderDeptScoreGroups(reorderDeptScoreGroupsReqJson);
        assertThat(reorderDeptScoreGroupsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getScoreGroupMetaReq = GetScoreGroupMetaReq.newBuilder().setDeptId(deptId).build();
        getScoreGroupMetaReqJson = ProtoUtils.protoToJson(getScoreGroupMetaReq);
        getScoreGroupMetaResp = scoreService.getScoreGroupMeta(getScoreGroupMetaReqJson);
        assertThat(getScoreGroupMetaResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getScoreGroupMetaResp.getScoreGroupMetaList()).hasSize(3);
        assertThat(getScoreGroupMetaResp.getScoreGroupMeta(0).getCode()).isEqualTo("test_score_3");
        assertThat(getScoreGroupMetaResp.getScoreGroupMeta(1).getCode()).isEqualTo("test_score_2");
        assertThat(getScoreGroupMetaResp.getScoreGroupMeta(2).getCode()).isEqualTo("test_score_1");

        // 删除部门评分
        DeleteDeptScoreGroupReq deleteDeptScoreGroupReq = DeleteDeptScoreGroupReq.newBuilder()
            .setId(getScoreGroupMetaResp.getScoreGroupMeta(0).getId())
            .build();
        String deleteDeptScoreGroupReqJson = ProtoUtils.protoToJson(deleteDeptScoreGroupReq);
        GenericResp deleteDeptScoreGroupResp = scoreService.deleteDeptScoreGroup(deleteDeptScoreGroupReqJson);
        assertThat(deleteDeptScoreGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getScoreGroupMetaReq = GetScoreGroupMetaReq.newBuilder().setDeptId(deptId).build();
        getScoreGroupMetaReqJson = ProtoUtils.protoToJson(getScoreGroupMetaReq);
        getScoreGroupMetaResp = scoreService.getScoreGroupMeta(getScoreGroupMetaReqJson);
        assertThat(getScoreGroupMetaResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getScoreGroupMetaResp.getScoreGroupMetaList()).hasSize(2);
        assertThat(getScoreGroupMetaResp.getScoreGroupMeta(0).getCode()).isEqualTo("test_score_2");
        assertThat(getScoreGroupMetaResp.getScoreGroupMeta(1).getCode()).isEqualTo("test_score_1");
    }

    /**
     * Score Group Table
     * --------------------------------------------------------------------------------------------------------
     * | score_group   | score_group_aggregator | score_item       | score_item_type | score_item_aggregator |
     * --------------------------------------------------------------------------------------------------------
     * | test_score_1  | sum                    | test_group1_item1| single          | sum                   |
     * |               |                        | test_group1_item2| multi           | sum                   |
     * |               |                        | test_group1_item3| multi           | max                   |
     * --------------------------------------------------------------------------------------------------------
     * | test_score_2  | first                  | test_group2_item1| multi           | first                 |
     * --------------------------------------------------------------------------------------------------------
     * | test_score_3  | first                  | test_group3_item1| multi           | max                   |
     * --------------------------------------------------------------------------------------------------------
     */
    @Test
    public void testCalcPatientScore() {
        // Int32
        CalcPatientScoreReq req = CalcPatientScoreReq.newBuilder()
            .setScoreGroupCode("test_score_1")
            .addItem( // 2
                ScoreItemPB.newBuilder()
                    .setCode("test_group1_item1")
                    .addScoreOptionCode("group1_item1_option2")
                    .build()
            )
            .addItem( // 3 = sum(1, 2)
                ScoreItemPB.newBuilder()
                    .setCode("test_group1_item2")
                    .addScoreOptionCode("group1_item2_option1")
                    .addScoreOptionCode("group1_item2_option2")
                    .build()
            )
            .addItem( // 2 = max(1,2)
                ScoreItemPB.newBuilder()
                    .setCode("test_group1_item3")
                    .addScoreOptionCode("group1_item3_option1")
                    .addScoreOptionCode("group1_item3_option2")
                    .build()
            )
            .build();
        String reqJson = ProtoUtils.protoToJson(req);
        CalcPatientScoreResp resp = scoreService.calcPatientScore(reqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getGroupScore().getInt32Val()).isEqualTo(7);
        assertThat(resp.getGroupScoreText()).isEqualTo("7分");

        // string
        req = CalcPatientScoreReq.newBuilder()
            .setScoreGroupCode("test_score_2")
            .addItem( // "level-1" = first(["level-1", "level-2"])
                ScoreItemPB.newBuilder()
                    .setCode("test_group2_item1")
                    .addScoreOptionCode("group2_item1_option1")
                    .addScoreOptionCode("group2_item1_option2")
                    .build()
            )
            .build();
        reqJson = ProtoUtils.protoToJson(req);
        resp = scoreService.calcPatientScore(reqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getGroupScore().getStrVal()).isEqualTo("level-1");
        assertThat(resp.getGroupScoreText()).isEqualTo("level-1");

        // int32 + max
        req = CalcPatientScoreReq.newBuilder()
            .setScoreGroupCode("test_score_3")
            .addItem( // 2 = max([1, 2])
                ScoreItemPB.newBuilder()
                    .setCode("test_group3_item1")
                    .addScoreOptionCode("group3_item1_option1")
                    .addScoreOptionCode("group3_item1_option2")
                    .build()
            )
            .build();
        reqJson = ProtoUtils.protoToJson(req);
        resp = scoreService.calcPatientScore(reqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getGroupScore().getInt32Val()).isEqualTo(2);
        assertThat(resp.getGroupScoreText()).isEqualTo("2分");

        // SCORE_GROUP_NOT_FOUND
        req = CalcPatientScoreReq.newBuilder()
            .setScoreGroupCode("test_score_101")
            .build();
        reqJson = ProtoUtils.protoToJson(req);
        resp = scoreService.calcPatientScore(reqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.SCORE_GROUP_NOT_FOUND.ordinal());

        // SCORE_ITEM_NOT_FOUND
        req = CalcPatientScoreReq.newBuilder()
            .setScoreGroupCode("test_score_3")
            .addItem(
                ScoreItemPB.newBuilder()
                    .setCode("test_group3_item6")
                    .addScoreOptionCode("group3_item1_option1")
                    .build()
            )
            .build();
        reqJson = ProtoUtils.protoToJson(req);
        resp = scoreService.calcPatientScore(reqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.SCORE_ITEM_NOT_FOUND.ordinal());

        // SCORE_ITEM_SINGLE_SELECTION_COUNT_ERROR
        req = CalcPatientScoreReq.newBuilder()
            .setScoreGroupCode("test_score_1")
            .addItem(
                ScoreItemPB.newBuilder()
                    .setCode("test_group1_item1")
                    .addScoreOptionCode("group1_item1_option1")
                    .addScoreOptionCode("group1_item1_option2")
                    .build()
            )
            .build();
        reqJson = ProtoUtils.protoToJson(req);
        resp = scoreService.calcPatientScore(reqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.SCORE_ITEM_SINGLE_SELECTION_COUNT_ERROR.ordinal());

        // SCORE_OPTION_NOT_FOUND
        req = CalcPatientScoreReq.newBuilder()
            .setScoreGroupCode("test_score_3")
            .addItem(
                ScoreItemPB.newBuilder()
                    .setCode("test_group3_item1")
                    .addScoreOptionCode("group3_item1_option6")
                    .build()
            )
            .build();
        reqJson = ProtoUtils.protoToJson(req);
        resp = scoreService.calcPatientScore(reqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.SCORE_OPTION_NOT_FOUND.ordinal());
    }

    @Test
    public void testPatientScores() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initDepartments();

        GetScoreGroupMetaReq getScoreGroupMetaReq = GetScoreGroupMetaReq.newBuilder().setDeptId(deptId2).build();
        String getScoreGroupMetaReqJson = ProtoUtils.protoToJson(getScoreGroupMetaReq);
        GetScoreGroupMetaResp getScoreGroupMetaResp = scoreService.getScoreGroupMeta(getScoreGroupMetaReqJson);
        assertThat(getScoreGroupMetaResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getScoreGroupMetaResp.getScoreGroupMetaList()).hasSize(1);
        final Integer deptScoreGroupId = getScoreGroupMetaResp.getScoreGroupMeta(0).getId();
        final String scoreGroupCode = getScoreGroupMetaResp.getScoreGroupMeta(0).getCode();

        LocalDateTime effectiveTime = LocalDateTime.of(2024, 12, 13, 0, 10, 0);
        LocalDateTime startTime = LocalDateTime.of(2024, 12, 13, 0, 0, 0);
        LocalDateTime endTime = LocalDateTime.of(2024, 12, 14, 0, 0, 0);

        // 添加患者评分
        AddPatientScoreReq addPatientScoreReq = AddPatientScoreReq.newBuilder()
            .setPid(pid)
            .setScoreGroupCode(scoreGroupCode)
            .setRecord(ScoreRecordPB.newBuilder()
                .setGroup(ScoreGroupPB.newBuilder()
                    .setCode(scoreGroupCode)
                    .addItem(ScoreItemPB.newBuilder()
                        .setCode("test_group1_item1")
                        .addScoreOptionCode("group1_item1_option1")
                        .build()
                    )
                    .addItem(ScoreItemPB.newBuilder()
                        .setCode("test_group1_item2")
                        .addScoreOptionCode("group1_item2_option1")
                        .build()
                    )
                    .addItem(ScoreItemPB.newBuilder()
                        .setCode("test_group1_item3")
                        .addScoreOptionCode("group1_item3_option1")
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
        assertThat(addPatientScoreResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        GetPatientScoresReq getPatientScoresReq = GetPatientScoresReq.newBuilder()
            .setPid(pid)
            .setScoreGroupCode(scoreGroupCode)
            .setQueryStartIso8601(TimeUtils.toIso8601String(startTime, ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(endTime, ZONE_ID))
            .build();
        String getPatientScoresReqJson = ProtoUtils.protoToJson(getPatientScoresReq);
        GetPatientScoresResp getPatientScoresResp = scoreService.getPatientScores(getPatientScoresReqJson);
        assertThat(getPatientScoresResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getPatientScoresResp.getRecordList()).hasSize(1);
        assertThat(getPatientScoresResp.getRecord(0).getNote()).isEqualTo("111");

        // 更新患者评分
        UpdatePatientScoreReq updatePatientScoreReq = UpdatePatientScoreReq.newBuilder()
            .setId(getPatientScoresResp.getRecord(0).getId())
            .setGroup(ScoreGroupPB.newBuilder()
                .setCode(scoreGroupCode)
                .addItem(ScoreItemPB.newBuilder()
                    .setCode("test_group1_item1")
                    .addScoreOptionCode("group1_item1_option2")
                    .build()
                )
                .addItem(ScoreItemPB.newBuilder()
                    .setCode("test_group1_item2")
                    .addScoreOptionCode("group1_item2_option2")
                    .build()
                )
                .addItem(ScoreItemPB.newBuilder()
                    .setCode("test_group1_item3")
                    .addScoreOptionCode("group1_item3_option2")
                    .build()
                )
                .setGroupScore(GenericValuePB.newBuilder().setInt32Val(6).build())
                .setGroupScoreText("6分")
            )
            .setRecordedBy(accountId)
            .setNote("222")
            .build();
        String updatePatientScoreReqJson = ProtoUtils.protoToJson(updatePatientScoreReq);
        GenericResp updatePatientScoreResp = scoreService.updatePatientScore(updatePatientScoreReqJson);
        assertThat(updatePatientScoreResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getPatientScoresReqJson = ProtoUtils.protoToJson(getPatientScoresReq);
        getPatientScoresResp = scoreService.getPatientScores(getPatientScoresReqJson);
        assertThat(getPatientScoresResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getPatientScoresResp.getRecordList()).hasSize(1);
        assertThat(getPatientScoresResp.getRecord(0).getNote()).isEqualTo("222");

        // 删除患者评分
        DeletePatientScoreReq deletePatientScoreReq = DeletePatientScoreReq.newBuilder()
            .setId(getPatientScoresResp.getRecord(0).getId())
            .build();
        String deletePatientScoreReqJson = ProtoUtils.protoToJson(deletePatientScoreReq);
        GenericResp deletePatientScoreResp = scoreService.deletePatientScore(deletePatientScoreReqJson);
        assertThat(deletePatientScoreResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getPatientScoresReqJson = ProtoUtils.protoToJson(getPatientScoresReq);
        getPatientScoresResp = scoreService.getPatientScores(getPatientScoresReqJson);
        assertThat(getPatientScoresResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getPatientScoresResp.getRecordList()).hasSize(0);
    }

    private void initDepartments() {
        RbacDepartment dept1 = new RbacDepartment();
        dept1.setDeptId(deptId);
        dept1.setDeptName("dept-1");
        rbacDepartmentRepo.save(dept1);

        RbacDepartment dept2 = new RbacDepartment();
        dept2.setDeptId(deptId2);
        dept2.setDeptName("dept-2");
        rbacDepartmentRepo.save(dept2);

        scoreConfig.initialize();
    }

    private void initPatient() {
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 12, 10, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(801L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);
        this.pid = patient.getId();
    }

    private final String ZONE_ID;
    private final String accountId;
    private final String deptId;
    private final String deptId2;
    private Long pid;

    private final ConfigProtoService protoService;
    private final ScoreConfig scoreConfig;
    private final ScoreService scoreService;
    private final PatientScoreRepository patientScoreRepo;
    private final RbacDepartmentRepository rbacDepartmentRepo;
    private final PatientRecordRepository patientRepo;
    private PatientTestUtils patientTestUtils;
}