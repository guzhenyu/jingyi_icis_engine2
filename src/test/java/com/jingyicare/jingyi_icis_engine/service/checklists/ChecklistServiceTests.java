package com.jingyicare.jingyi_icis_engine.service.checklists;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisChecklist.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.checklists.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.checklists.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class ChecklistServiceTests extends TestsBase {
    public ChecklistServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired ChecklistService checklistService,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired PatientRecordRepository patientRepo
    ) {
        this.deptId1 = "10035";
        this.deptId2 = "10036";
        this.accountId = "admin";

        this.protoService = protoService;
        this.checklistService = checklistService;

        this.deptRepo = deptRepo;
        this.patientRepo = patientRepo;

        init();
    }

    @Test
    public void testSetupDeptChecklists() {  // deptId1
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 新增部门组长质控分组1
        AddDeptChecklistGroupReq addDeptGroupReq = AddDeptChecklistGroupReq.newBuilder().setGroup(
            DeptChecklistGroupPB.newBuilder()
                .setDeptId(deptId1)
                .setGroupName("dept_checklist_group_1")
                .setComments("comment1")
                .build()
        ).build();
        String reqJson = ProtoUtils.protoToJson(addDeptGroupReq);
        AddDeptChecklistGroupResp addDeptGroupResp = checklistService.addDeptChecklistGroup(reqJson);
        assertThat(addDeptGroupResp.getRt().getCode()).isEqualTo(0);
        Integer groupId = addDeptGroupResp.getId();

        // 新增部门组长质控项1-1
        AddDeptChecklistItemReq addDeptItemReq1 = AddDeptChecklistItemReq.newBuilder().setItem(
            DeptChecklistItemPB.newBuilder()
                .setItemName("dept_checklist_item_1-1")
                .setIsCritical(true)
                .setComments("comment1-1")
                .setHasNote(true)
                .setDefaultNote("default note 1-1")
                .build()
        ).setGroupId(groupId).build();
        reqJson = ProtoUtils.protoToJson(addDeptItemReq1);
        AddDeptChecklistItemResp addDeptItemResp1 = checklistService.addDeptChecklistItem(reqJson);
        assertThat(addDeptItemResp1.getRt().getCode()).isEqualTo(0);
        Integer itemId1 = addDeptItemResp1.getId();

        // 新增部门组长质控项1-2
        AddDeptChecklistItemReq addDeptItemReq2 = AddDeptChecklistItemReq.newBuilder().setItem(
            DeptChecklistItemPB.newBuilder()
                .setItemName("dept_checklist_item_1-2")
                .setIsCritical(false)
                .setComments("comment1-2")
                .setHasNote(false)
                .build()
        ).setGroupId(groupId).build();
        reqJson = ProtoUtils.protoToJson(addDeptItemReq2);
        AddDeptChecklistItemResp addDeptItemResp2 = checklistService.addDeptChecklistItem(reqJson);
        assertThat(addDeptItemResp2.getRt().getCode()).isEqualTo(0);
        Integer itemId2 = addDeptItemResp2.getId();

        // 新增部门组长质控分组2
        AddDeptChecklistGroupReq addDeptGroupReq2 = AddDeptChecklistGroupReq.newBuilder().setGroup(
            DeptChecklistGroupPB.newBuilder()
                .setDeptId(deptId1)
                .setGroupName("dept_checklist_group_2")
                .setComments("comment2")
                .build()
        ).build();
        reqJson = ProtoUtils.protoToJson(addDeptGroupReq2);
        AddDeptChecklistGroupResp addDeptGroupResp2 = checklistService.addDeptChecklistGroup(reqJson);
        assertThat(addDeptGroupResp2.getRt().getCode()).isEqualTo(0);
        Integer groupId2 = addDeptGroupResp2.getId();

        // 新增部门组长质控项2-1
        AddDeptChecklistItemReq addDeptItemReq3 = AddDeptChecklistItemReq.newBuilder().setItem(
            DeptChecklistItemPB.newBuilder()
                .setItemName("dept_checklist_item_2-1")
                .setIsCritical(true)
                .setComments("comment2-1")
                .setHasNote(true)
                .setDefaultNote("default note 2-1")
                .build()
        ).setGroupId(groupId2).build();
        reqJson = ProtoUtils.protoToJson(addDeptItemReq3);
        AddDeptChecklistItemResp addDeptItemResp3 = checklistService.addDeptChecklistItem(reqJson);
        assertThat(addDeptItemResp3.getRt().getCode()).isEqualTo(0);
        Integer itemId3 = addDeptItemResp3.getId();

        // 查询部门组长质控分组
        GetDeptChecklistGroupsReq getDeptGroupsReq = GetDeptChecklistGroupsReq.newBuilder()
            .setDeptId(deptId1)
            .build();
        String getGroupsReqJson = ProtoUtils.protoToJson(getDeptGroupsReq);
        GetDeptChecklistGroupsResp getGroupsResp = checklistService.getDeptChecklistGroups(getGroupsReqJson);
        assertThat(getGroupsResp.getRt().getCode()).isEqualTo(0);
        assertThat(getGroupsResp.getGroupCount()).isEqualTo(2);
        List<DeptChecklistGroupPB> groups = getGroupsResp.getGroupList();
        assertThat(groups.get(0).getId()).isEqualTo(groupId);
        assertThat(groups.get(0).getGroupName()).isEqualTo("dept_checklist_group_1");
        assertThat(groups.get(0).getItemCount()).isEqualTo(2);
        assertThat(groups.get(0).getItemList().get(0).getId()).isEqualTo(itemId1);
        assertThat(groups.get(0).getItemList().get(0).getIsCritical()).isTrue();
        assertThat(groups.get(0).getItemList().get(1).getId()).isEqualTo(itemId2);
        assertThat(groups.get(0).getItemList().get(1).getIsCritical()).isFalse();
        assertThat(groups.get(1).getId()).isEqualTo(groupId2);
        assertThat(groups.get(1).getGroupName()).isEqualTo("dept_checklist_group_2");
        assertThat(groups.get(1).getItemCount()).isEqualTo(1);

        // 重排序：部门组长质控项1-2，部门组长质控项1-1
        ReorderDeptChecklistItemsReq reorderItemsReq = ReorderDeptChecklistItemsReq.newBuilder()
            .setDeptId(deptId1)
            .setGroupId(groupId)
            .addItemId(itemId2)
            .addItemId(itemId1)
            .build();
        String reorderItemsReqJson = ProtoUtils.protoToJson(reorderItemsReq);
        GenericResp reorderItemsResp = checklistService.reorderDeptChecklistItems(reorderItemsReqJson);
        assertThat(reorderItemsResp.getRt().getCode()).isEqualTo(0);

        // 重排序：部门组长质控分组2，部门组长质控分组1
        ReorderDeptChecklistGroupsReq reorderGroupsReq = ReorderDeptChecklistGroupsReq.newBuilder()
            .setDeptId(deptId1)
            .addGroupId(groupId2)
            .addGroupId(groupId)
            .build();
        String reorderGroupsReqJson = ProtoUtils.protoToJson(reorderGroupsReq);
        GenericResp reorderGroupsResp = checklistService.reorderDeptChecklistGroups(reorderGroupsReqJson);
        assertThat(reorderGroupsResp.getRt().getCode()).isEqualTo(0);

        // 查询部门组长质控分组
        getGroupsResp = checklistService.getDeptChecklistGroups(getGroupsReqJson);
        assertThat(getGroupsResp.getRt().getCode()).isEqualTo(0);
        assertThat(getGroupsResp.getGroupCount()).isEqualTo(2);
        groups = getGroupsResp.getGroupList();
        assertThat(groups.get(0).getId()).isEqualTo(groupId2);
        assertThat(groups.get(0).getGroupName()).isEqualTo("dept_checklist_group_2");
        assertThat(groups.get(0).getItemCount()).isEqualTo(1);
        assertThat(groups.get(0).getItemList().get(0).getId()).isEqualTo(itemId3);
        assertThat(groups.get(1).getId()).isEqualTo(groupId);
        assertThat(groups.get(1).getGroupName()).isEqualTo("dept_checklist_group_1");
        assertThat(groups.get(1).getItemCount()).isEqualTo(2);
        assertThat(groups.get(1).getItemList().get(0).getId()).isEqualTo(itemId2);
        assertThat(groups.get(1).getItemList().get(0).getIsCritical()).isFalse();
        assertThat(groups.get(1).getItemList().get(1).getId()).isEqualTo(itemId1);
        assertThat(groups.get(1).getItemList().get(1).getIsCritical()).isTrue();

        // 删除部门组长质控项2-1
        DeleteDeptChecklistItemReq deleteItemReq = DeleteDeptChecklistItemReq.newBuilder()
            .setItemId(itemId3)
            .build();
        String deleteItemReqJson = ProtoUtils.protoToJson(deleteItemReq);
        GenericResp deleteItemResp = checklistService.deleteDeptChecklistItem(deleteItemReqJson);
        assertThat(deleteItemResp.getRt().getCode()).isEqualTo(0);

        // 删除部门组长质控分组2
        DeleteDeptChecklistGroupReq deleteGroupReq = DeleteDeptChecklistGroupReq.newBuilder()
            .setGroupId(groupId2)
            .build();
        String deleteGroupReqJson = ProtoUtils.protoToJson(deleteGroupReq);
        GenericResp deleteGroupResp = checklistService.deleteDeptChecklistGroup(deleteGroupReqJson);
        assertThat(deleteGroupResp.getRt().getCode()).isEqualTo(0);

        // 查询部门组长质控分组
        getGroupsResp = checklistService.getDeptChecklistGroups(getGroupsReqJson);
        assertThat(getGroupsResp.getRt().getCode()).isEqualTo(0);
        assertThat(getGroupsResp.getGroupCount()).isEqualTo(1);
        groups = getGroupsResp.getGroupList();
        assertThat(groups.get(0).getId()).isEqualTo(groupId);
        assertThat(groups.get(0).getGroupName()).isEqualTo("dept_checklist_group_1");
        assertThat(groups.get(0).getItemCount()).isEqualTo(2);
        assertThat(groups.get(0).getItemList().get(0).getId()).isEqualTo(itemId2);
        assertThat(groups.get(0).getItemList().get(0).getIsCritical()).isFalse();
        assertThat(groups.get(0).getItemList().get(1).getId()).isEqualTo(itemId1);
        assertThat(groups.get(0).getItemList().get(1).getIsCritical()).isTrue();
    }

    @Test
    public void testPatientChecklists() {  // deptId2
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 新增部门组长质控分组3
        AddDeptChecklistGroupReq addDeptGroupReq = AddDeptChecklistGroupReq.newBuilder().setGroup(
            DeptChecklistGroupPB.newBuilder()
                .setDeptId(deptId2)
                .setGroupName("dept_checklist_group_3")
                .setComments("comment3")
                .build()
        ).build();
        String reqJson = ProtoUtils.protoToJson(addDeptGroupReq);
        AddDeptChecklistGroupResp addDeptGroupResp = checklistService.addDeptChecklistGroup(reqJson);
        assertThat(addDeptGroupResp.getRt().getCode()).isEqualTo(0);
        Integer groupId = addDeptGroupResp.getId();
        // 新增部门组长质控项3-1
        AddDeptChecklistItemReq addItemReq1 = AddDeptChecklistItemReq.newBuilder().setItem(
            DeptChecklistItemPB.newBuilder()
                .setItemName("dept_checklist_item_3_1-1")
                .setIsCritical(true)
                .build()
        ).setGroupId(groupId).build();
        String addItemReqJson1 = ProtoUtils.protoToJson(addItemReq1);
        AddDeptChecklistItemResp addItemResp1 = checklistService.addDeptChecklistItem(addItemReqJson1);
        assertThat(addItemResp1.getRt().getCode()).isEqualTo(0);
        Integer itemId1 = addItemResp1.getId();

        // 新增部门组长质控项3-2
        AddDeptChecklistItemReq addItemReq2 = AddDeptChecklistItemReq.newBuilder().setItem(
            DeptChecklistItemPB.newBuilder()
                .setItemName("dept_checklist_item_3_2")
                .setIsCritical(false)
                .build()
        ).setGroupId(groupId).build();
        String addItemReqJson2 = ProtoUtils.protoToJson(addItemReq2);
        AddDeptChecklistItemResp addItemResp2 = checklistService.addDeptChecklistItem(addItemReqJson2);
        assertThat(addItemResp2.getRt().getCode()).isEqualTo(0);
        Integer itemId2 = addItemResp2.getId();

        // 新增患者组长质控记录（3-1, 3-2）
        AddPatientChecklistRecordReq addPatientRecReq = AddPatientChecklistRecordReq.newBuilder()
            .setRecord(PatientChecklistRecordPB.newBuilder()
                .setPid(pid)
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(
                    TimeUtils.getLocalTime(2025, 7, 9, 2, 0), ZONE_ID
                ))
            ).build();
        String addPatientRecReqJson = ProtoUtils.protoToJson(addPatientRecReq);
        AddPatientChecklistRecordResp addPatientRecResp = checklistService.addPatientChecklistRecord(addPatientRecReqJson);
        assertThat(addPatientRecResp.getRt().getCode()).isEqualTo(0);
        Integer recordId = addPatientRecResp.getId();

        // 查询患者组长质控记录
        String queryStartIso8601 = TimeUtils.toIso8601String(
            TimeUtils.getLocalTime(2025, 7, 9, 0, 0), ZONE_ID
        );
        String queryEndIso8601 = TimeUtils.toIso8601String(
            TimeUtils.getLocalTime(2025, 7, 9, 23, 59), ZONE_ID
        );
        GetPatientChecklistRecordsReq getPatientRecReq = GetPatientChecklistRecordsReq.newBuilder()
            .setPid(pid)
            .setQueryStartIso8601(queryStartIso8601)
            .setQueryEndIso8601(queryEndIso8601)
            .build();
        String getPatientRecReqJson = ProtoUtils.protoToJson(getPatientRecReq);
        GetPatientChecklistRecordsResp getPatientRecResp = checklistService.getPatientChecklistRecords(getPatientRecReqJson);
        assertThat(getPatientRecResp.getRt().getCode()).isEqualTo(0);
        assertThat(getPatientRecResp.getRecordCount()).isEqualTo(1);
        PatientChecklistRecordPB record = getPatientRecResp.getRecordList().get(0);
        assertThat(record.getId()).isEqualTo(recordId);
        assertThat(record.getEffectiveTimeIso8601()).isEqualTo(
            TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 7, 9, 2, 0), ZONE_ID)
        );
        assertThat(record.getGroupCount()).isEqualTo(1);
        PatientChecklistGroupPB group = record.getGroupList().get(0);
        assertThat(group.getGroupName()).isEqualTo("dept_checklist_group_3");
        assertThat(group.getCheckedCount()).isEqualTo(0);
        assertThat(group.getTotalCount()).isEqualTo(2);
        List<PatientChecklistItemPB> items = group.getItemList();
        assertThat(items.size()).isEqualTo(2);

        // 更新患者组长质控项
        UpdatePatientChecklistItemReq updateItemReq = UpdatePatientChecklistItemReq.newBuilder()
            .setItem(PatientChecklistItemPB.newBuilder()
                .setId(items.get(0).getId())
                .setIsChecked(true)
                .build()
            ).build();
        String updateItemReqJson = ProtoUtils.protoToJson(updateItemReq);
        GenericResp updateItemResp = checklistService.updatePatientChecklistItem(updateItemReqJson);
        assertThat(updateItemResp.getRt().getCode()).isEqualTo(0);

        // 查询患者组长质控记录
        getPatientRecResp = checklistService.getPatientChecklistRecords(getPatientRecReqJson);
        assertThat(getPatientRecResp.getRt().getCode()).isEqualTo(0);
        assertThat(getPatientRecResp.getRecordCount()).isEqualTo(1);
        assertThat(getPatientRecResp.getRecordList().get(0).getGroupCount()).isEqualTo(1);
        group = getPatientRecResp.getRecordList().get(0).getGroupList().get(0);
        assertThat(group.getCheckedCount()).isEqualTo(1);
        assertThat(group.getTotalCount()).isEqualTo(2);

        // 新增部门组长质控记录（3-3）
        AddDeptChecklistItemReq addItemReq3 = AddDeptChecklistItemReq.newBuilder().setItem(
            DeptChecklistItemPB.newBuilder()
                .setItemName("dept_checklist_item_3_3")
                .setIsCritical(false)
                .setHasNote(true)
                .setDefaultNote("default note 3-3")
                .build()
        ).setGroupId(groupId).build();
        String addItemReqJson3 = ProtoUtils.protoToJson(addItemReq3);
        AddDeptChecklistItemResp addItemResp3 = checklistService.addDeptChecklistItem(addItemReqJson3);
        assertThat(addItemResp3.getRt().getCode()).isEqualTo(0);
        Integer itemId3 = addItemResp3.getId();

        // 删除部门组长质控记录（3-1）
        DeleteDeptChecklistItemReq deleteItemReq = DeleteDeptChecklistItemReq.newBuilder()
            .setItemId(itemId1)
            .build();
        String deleteItemReqJson = ProtoUtils.protoToJson(deleteItemReq);
        GenericResp deleteItemResp = checklistService.deleteDeptChecklistItem(deleteItemReqJson);
        assertThat(deleteItemResp.getRt().getCode()).isEqualTo(0);

        // 新增患者组长质控记录（3-2, 3-3）
        addPatientRecReq = AddPatientChecklistRecordReq.newBuilder()
            .setRecord(PatientChecklistRecordPB.newBuilder()
                .setPid(pid)
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(
                    TimeUtils.getLocalTime(2025, 7, 9, 1, 0), ZONE_ID
                ))
            ).build();
        addPatientRecReqJson = ProtoUtils.protoToJson(addPatientRecReq);
        addPatientRecResp = checklistService.addPatientChecklistRecord(addPatientRecReqJson);
        assertThat(addPatientRecResp.getRt().getCode()).isEqualTo(0);
        Integer recordId2 = addPatientRecResp.getId();

        // 查询患者组长质控记录
        getPatientRecResp = checklistService.getPatientChecklistRecords(getPatientRecReqJson);
        assertThat(getPatientRecResp.getRt().getCode()).isEqualTo(0);
        assertThat(getPatientRecResp.getRecordCount()).isEqualTo(2);
        assertThat(getPatientRecResp.getRecordList().get(0).getId()).isEqualTo(recordId2);
        assertThat(getPatientRecResp.getRecordList().get(0).getEffectiveTimeIso8601()).isEqualTo(
            TimeUtils.toIso8601String(TimeUtils.getLocalTime(2025, 7, 9, 1, 0), ZONE_ID)
        );
        assertThat(getPatientRecResp.getRecordList().get(0).getGroupCount()).isEqualTo(1);
        group = getPatientRecResp.getRecordList().get(0).getGroupList().get(0);
        assertThat(group.getGroupName()).isEqualTo("dept_checklist_group_3");
        assertThat(group.getCheckedCount()).isEqualTo(0);
        assertThat(group.getTotalCount()).isEqualTo(2);
        items = group.getItemList();
        assertThat(items.size()).isEqualTo(2);

        assertThat(getPatientRecResp.getRecordList().get(1).getGroupCount()).isEqualTo(1);
        group = getPatientRecResp.getRecordList().get(1).getGroupList().get(0);
        assertThat(group.getCheckedCount()).isEqualTo(1);
        assertThat(group.getTotalCount()).isEqualTo(2);

        // 更新患者组长质控项
        updateItemReq = UpdatePatientChecklistItemReq.newBuilder()
            .setItem(PatientChecklistItemPB.newBuilder()
                .setId(items.get(1).getId())
                .setIsChecked(true)
                .setNote("note for item 3-2")
                .build()
            ).build();
        updateItemReqJson = ProtoUtils.protoToJson(updateItemReq);
        updateItemResp = checklistService.updatePatientChecklistItem(updateItemReqJson);
        assertThat(updateItemResp.getRt().getCode()).isEqualTo(0);

        // 查询患者组长质控记录 (按时间排序)
        getPatientRecResp = checklistService.getPatientChecklistRecords(getPatientRecReqJson);
        assertThat(getPatientRecResp.getRt().getCode()).isEqualTo(0);
        assertThat(getPatientRecResp.getRecordCount()).isEqualTo(2);
        assertThat(getPatientRecResp.getRecordList().get(0).getGroupCount()).isEqualTo(1);
        group = getPatientRecResp.getRecordList().get(0).getGroupList().get(0);
        assertThat(group.getCheckedCount()).isEqualTo(1);
        assertThat(group.getTotalCount()).isEqualTo(2);
        items = group.getItemList();
        assertThat(items.size()).isEqualTo(2);
        assertThat(items.get(1).getIsChecked()).isTrue();
        assertThat(items.get(1).getNote()).isEqualTo("note for item 3-2");

        assertThat(getPatientRecResp.getRecordList().get(1).getGroupCount()).isEqualTo(1);
        group = getPatientRecResp.getRecordList().get(1).getGroupList().get(0);
        assertThat(group.getCheckedCount()).isEqualTo(1);
        assertThat(group.getTotalCount()).isEqualTo(2);
    }

    @Transactional
    private void init() {
        List<RbacDepartment> existingDepts = deptRepo.findByDeptIdIn(Arrays.asList(deptId1, deptId2));
        if (existingDepts.size() == 0) {
            // 如果部门不存在，则创建
            RbacDepartment dept1 = new RbacDepartment();
            dept1.setDeptId(deptId1);
            dept1.setDeptName(deptId1);
            deptRepo.save(dept1);

            RbacDepartment dept2 = new RbacDepartment();
            dept2.setDeptId(deptId2);
            dept2.setDeptName(deptId1);
            deptRepo.save(dept2);
        }

        // 病人
        List<PatientRecord> patientRec = patientRepo.findByMrnOrName("1801");
        if (patientRec.size() <= 0) {
            final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2025, 7, 9, 8, 0), ZONE_ID);
            PatientRecord patient = PatientTestUtils.newPatientRecord(1801L, 1 /*admission_status_in_icu*/, deptId2);
            patient.setHisAdmissionTime(admissionTime);
            patient.setAdmissionTime(admissionTime);
            patient = patientRepo.save(patient);
            pid = patient.getId();
        } else {
            if (patientRec.size() > 1) {
                throw new IllegalStateException("Found multiple patients with the same MRN");
            }
            PatientRecord patient = patientRec.get(0);
            if (patient.getDeptId() == null || !patient.getDeptId().equals(deptId2)) {
                throw new IllegalStateException("Patient department ID does not match expected value");
            }
            pid = patient.getId();
        }
    }

    private final String ZONE_ID = "Asia/Shanghai";
    private final String deptId1;
    private final String deptId2;
    private final String accountId;
    private Long pid;

    private final ConfigProtoService protoService;
    private final ChecklistService checklistService;

    RbacDepartmentRepository deptRepo;
    PatientRecordRepository patientRepo;
}