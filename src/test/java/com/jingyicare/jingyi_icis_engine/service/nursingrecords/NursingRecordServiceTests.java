package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

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
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class NursingRecordServiceTests extends TestsBase {
    public NursingRecordServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired NursingRecordConfig recordConfig,
        @Autowired NursingRecordService recordService,
        @Autowired NursingRecordTemplateGroupRepository templateGroupRepo,
        @Autowired NursingRecordTemplateRepository templateRepo,
        @Autowired NursingRecordRepository recordRepo,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired PatientRecordRepository patientRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.accountId = "admin";
        this.deptId = "admin";
        this.deptId2 = "10016";
        this.deptId3 = "10017";

        this.protoService = protoService;
        this.configPb = protoService.getConfig().getNursingRecord();
        this.recordConfig = recordConfig;
        this.recordService = recordService;
        this.templateGroupRepo = templateGroupRepo;
        this.templateRepo = templateRepo;
        this.recordRepo = recordRepo;
        this.deptRepo = deptRepo;
        this.patientRepo = patientRepo;

        initDepartments();
    }

    @Test
    public void testNursingRecordTemplates() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        GetNursingRecordTemplatesReq getReq = GetNursingRecordTemplatesReq.newBuilder()
            .setDeptId(deptId2).build();
        String getReqJson = ProtoUtils.protoToJson(getReq);
        GetNursingRecordTemplatesResp getResp = recordService.getNursingRecordTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getDeptTemplateList()).hasSize(2);
        Integer groupId1 = getResp.getDeptTemplate(0).getId();
        Integer groupId2 = getResp.getDeptTemplate(1).getId();

        // 新增组
        AddNursingRecordTemplateGroupReq addGroupReq = AddNursingRecordTemplateGroupReq.newBuilder()
            .setDeptId(deptId2)
            .setGroup(NursingRecordTemplateGroupPB.newBuilder()
                .setName("NursingRecordServiceTests-admin-group3")
                .setDisplayOrder(3)
                .build()
            ).build();
        String addGroupReqJson = ProtoUtils.protoToJson(addGroupReq);
        AddNursingRecordTemplateGroupResp addGroupResp = recordService.addNursingRecordTemplateGroup(addGroupReqJson);
        assertThat(addGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Integer groupId3 = addGroupResp.getId();

        addGroupResp = recordService.addNursingRecordTemplateGroup(addGroupReqJson);
        assertThat(addGroupResp.getRt().getCode()).isEqualTo(
            StatusCode.NURSING_RECORD_TEMPLATE_GROUP_ALREADY_EXISTS.ordinal());

        addGroupReq = AddNursingRecordTemplateGroupReq.newBuilder()
            .setDeptId(deptId2)
            .setGroup(NursingRecordTemplateGroupPB.newBuilder()
                .setName("NursingRecordServiceTests-admin-group4")
                .setDisplayOrder(4)
                .build()
            ).build();
        addGroupReqJson = ProtoUtils.protoToJson(addGroupReq);
        addGroupResp = recordService.addNursingRecordTemplateGroup(addGroupReqJson);
        assertThat(addGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Integer groupId4 = addGroupResp.getId();

        // 更新组
        UpdateNursingRecordTemplateGroupsReq updateGroupsReq = UpdateNursingRecordTemplateGroupsReq.newBuilder()
            .setDeptId(deptId2)
            .addGroup(NursingRecordTemplateGroupPB.newBuilder()
                .setId(groupId3)
                .setName("NursingRecordServiceTests-admin-group3")
                .setDisplayOrder(4)
                .build()
            )
            .addGroup(NursingRecordTemplateGroupPB.newBuilder()
                .setId(groupId4)
                .setName("NursingRecordServiceTests-admin-group4")
                .setDisplayOrder(3)
                .build()
            ).build();
        String updateGroupsReqJson = ProtoUtils.protoToJson(updateGroupsReq);
        GenericResp updateGroupsResp = recordService.updateNursingRecordTemplateGroups(updateGroupsReqJson);
        assertThat(updateGroupsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        updateGroupsReq = UpdateNursingRecordTemplateGroupsReq.newBuilder()
            .setDeptId(deptId2)
            .addGroup(NursingRecordTemplateGroupPB.newBuilder()
                .setId(1000000)  // 不存在的group ID
                .setDisplayOrder(1000000)
                .build()
            ).build();
        updateGroupsReqJson = ProtoUtils.protoToJson(updateGroupsReq);
        updateGroupsResp = recordService.updateNursingRecordTemplateGroups(updateGroupsReqJson);
        assertThat(updateGroupsResp.getRt().getCode()).isEqualTo(StatusCode.NURSING_RECORD_TEMPLATE_GROUP_NOT_EXISTS.ordinal());

        // 删除组
        DeleteNursingRecordTemplateGroupReq deleteGroupReq = DeleteNursingRecordTemplateGroupReq.newBuilder()
            .setId(groupId3).build();
        String deleteGroupReqJson = ProtoUtils.protoToJson(deleteGroupReq);
        GenericResp deleteGroupResp = recordService.deleteNursingRecordTemplateGroup(deleteGroupReqJson);
        assertThat(deleteGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getResp = recordService.getNursingRecordTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getDeptTemplateList()).hasSize(3);
        assertThat(getResp.getDeptTemplate(2).getName()).isEqualTo("NursingRecordServiceTests-admin-group4");

        // 新增科室模板
        AddNursingRecordTemplateReq addReq = AddNursingRecordTemplateReq.newBuilder()
            .setDeptId(deptId2)
            .setTemplate(NursingRecordTemplatePB.newBuilder()
                .setName("NursingRecordServiceTests-g4-template1")
                .setContent("NursingRecordServiceTests-g4-template1")
                .setDisplayOrder(1)
                .setIsCommon(1)
                .setGroupId(groupId4)
                .build()
            ).build();
        String addReqJson = ProtoUtils.protoToJson(addReq);
        AddNursingRecordTemplateResp addResp = recordService.addNursingRecordTemplate(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Integer templateId1 = addResp.getId();

        addResp = recordService.addNursingRecordTemplate(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.NURSING_RECORD_TEMPLATE_ALREADY_EXISTS.ordinal());

        addReq = AddNursingRecordTemplateReq.newBuilder()
            .setDeptId(deptId2)
            .setTemplate(NursingRecordTemplatePB.newBuilder()
                .setName("NursingRecordServiceTests-g4-template2")
                .setContent("NursingRecordServiceTests-g4-template2")
                .setDisplayOrder(1)
                .setIsCommon(1)
                .setGroupId(groupId4)
                .build()
            ).build();
        addReqJson = ProtoUtils.protoToJson(addReq);
        addResp = recordService.addNursingRecordTemplate(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Integer templateId2 = addResp.getId();

        // 更新科室模板
        UpdateNursingRecordTemplateReq updateDeptReq = UpdateNursingRecordTemplateReq.newBuilder()
            .setTemplate(NursingRecordTemplatePB.newBuilder()
                .setId(templateId1)
                .setContent("NursingRecordServiceTests-g4-template1-updated")
                .setDisplayOrder(2)
                .setIsCommon(0)
                .setGroupId(groupId4)
                .build()
            ).build();
        String updateDeptReqJson = ProtoUtils.protoToJson(updateDeptReq);
        GenericResp updateDeptResp = recordService.updateNursingRecordTemplate(updateDeptReqJson);

        // 删除科室模板
        DeleteNursingRecordTemplateReq deleteReq = DeleteNursingRecordTemplateReq.newBuilder()
            .setId(templateId2).build();
        String deleteReqJson = ProtoUtils.protoToJson(deleteReq);
        GenericResp deleteResp = recordService.deleteNursingRecordTemplate(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getResp = recordService.getNursingRecordTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getDeptTemplateList()).hasSize(3);
        assertThat(getResp.getDeptTemplate(2).getName()).isEqualTo("NursingRecordServiceTests-admin-group4");
        assertThat(getResp.getDeptTemplate(2).getNursingRecordTemplateList()).hasSize(1);
        assertThat(getResp.getDeptTemplate(2).getNursingRecordTemplate(0).getName()).isEqualTo("NursingRecordServiceTests-g4-template1");
        assertThat(getResp.getDeptTemplate(2).getNursingRecordTemplate(0).getContent()).isEqualTo("NursingRecordServiceTests-g4-template1-updated");
    }

    @Test
    public void testNursingRecords() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        Long patientId = 901L;
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 12, 20, 8, 0), ZONE_ID);
        PatientRecord patient = PatientTestUtils.newPatientRecord(901L, 1 /*admission_status_in_icu*/, deptId3);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);
        patientId = patient.getId();


        // 新增
        LocalDateTime recTimeUtc1 = LocalDateTime.of(2024, 12, 24, 1, 0, 0);
        AddNursingRecordReq addReq = AddNursingRecordReq.newBuilder()
            .setPid(patientId)
            .setRecord(PatientNursingRecordValPB.newBuilder()
                .setContent("NursingRecordServiceTests-admin-record1")
                .setRecordedAtIso8601(TimeUtils.toIso8601String(recTimeUtc1, ZONE_ID))
                .build()
            )
            .build();
        String addReqJson = ProtoUtils.protoToJson(addReq);
        AddNursingRecordResp addResp = recordService.addNursingRecord(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Long recordId1 = addResp.getId();

        addResp = recordService.addNursingRecord(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.NURSING_RECORD_ALREADY_EXISTS.ordinal());

        LocalDateTime recTimeUtc2 = LocalDateTime.of(2024, 12, 24, 2, 0, 0);
        addReq = AddNursingRecordReq.newBuilder()
            .setPid(patientId)
            .setRecord(PatientNursingRecordValPB.newBuilder()
                .setContent("NursingRecordServiceTests-admin-record2")
                .setRecordedAtIso8601(TimeUtils.toIso8601String(recTimeUtc2, ZONE_ID))
                .build()
            )
            .build();
        addReqJson = ProtoUtils.protoToJson(addReq);
        addResp = recordService.addNursingRecord(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Long recordId2 = addResp.getId();

        // 更新
        UpdateNursingRecordReq updateReq = UpdateNursingRecordReq.newBuilder()
            .setRecord(PatientNursingRecordValPB.newBuilder()
                .setId(recordId1)
                .setContent("NursingRecordServiceTests-admin-record1-updated")
                .build()
            ).build();
        String updateReqJson = ProtoUtils.protoToJson(updateReq);
        GenericResp updateResp = recordService.updateNursingRecord(updateReqJson);
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        updateReq = UpdateNursingRecordReq.newBuilder()
            .setRecord(PatientNursingRecordValPB.newBuilder()
                .setId(100000)  // 一个不存在的id
                .setContent("NursingRecordServiceTests-admin-recordx-updated")
                .build()
            ).build();
        updateReqJson = ProtoUtils.protoToJson(updateReq);
        updateResp = recordService.updateNursingRecord(updateReqJson);
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.NURSING_RECORD_NOT_EXISTS.ordinal());

        // 删除
        DeleteNursingRecordReq deleteReq = DeleteNursingRecordReq.newBuilder()
            .setId(recordId2).build();
        String deleteReqJson = ProtoUtils.protoToJson(deleteReq);
        GenericResp deleteResp = recordService.deleteNursingRecord(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询
        LocalDateTime startUtc = LocalDateTime.of(2024, 12, 24, 0, 0, 0);
        LocalDateTime endUtc = LocalDateTime.of(2024, 12, 25, 0, 0, 0);
        GetNursingRecordReq getReq = GetNursingRecordReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(TimeUtils.toIso8601String(startUtc, ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(endUtc, ZONE_ID))
            .build();
        String getReqJson = ProtoUtils.protoToJson(getReq);
        GetNursingRecordResp getResp = recordService.getNursingRecord(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
    }

    private void initDepartments() {
        RbacDepartment dept2 = new RbacDepartment();
        dept2.setDeptId(deptId2);
        dept2.setDeptName("dept-2");
        deptRepo.save(dept2);

        RbacDepartment dept3 = new RbacDepartment();
        dept3.setDeptId(deptId3);
        dept3.setDeptName("dept-3");
        deptRepo.save(dept3);

        recordConfig.initialize();
    }

    private final String ZONE_ID;
    private final String accountId;
    private final String deptId;
    private final String deptId2;
    private final String deptId3;

    private final ConfigProtoService protoService;
    private final NursingRecordConfigPB configPb;
    private final NursingRecordConfig recordConfig;
    private final NursingRecordService recordService;
    private final NursingRecordTemplateGroupRepository templateGroupRepo;
    private final NursingRecordTemplateRepository templateRepo;
    private final NursingRecordRepository recordRepo;
    private final RbacDepartmentRepository deptRepo;
    private final PatientRecordRepository patientRepo;
}