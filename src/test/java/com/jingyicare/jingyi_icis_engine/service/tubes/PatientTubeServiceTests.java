package com.jingyicare.jingyi_icis_engine.service.tubes;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class PatientTubeServiceTests extends TestsBase {
    public PatientTubeServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired TubeSetting tubeSetting,
        @Autowired TubeService tubeService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired MonitoringService monitoringService,
        @Autowired TubeTypeRepository tubeTypeRepo,
        @Autowired TubeTypeAttributeRepository tubeTypeAttrRepo,
        @Autowired TubeTypeStatusRepository tubeTypeStatusRepo,
        @Autowired PatientTubeService patientTubeService,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired RbacDepartmentRepository rbacDepartmentRepo
    ) {
        this.accountId = "admin";
        this.deptId = "10007";
        this.ZONE_ID = protoService.getConfig().getZoneId();
        final TubeEnums tubeEnums = protoService.getConfig().getTube().getEnums();
        this.UI_TYPE_TEXT = tubeEnums.getTubeUiTypeText().getId();
        this.UI_TYPE_SINGLE_SELECT = tubeEnums.getTubeUiTypeSingleSelector().getId();
        this.UI_TYPE_MULTI_SELECT = tubeEnums.getTubeUiTypeMultiSelector().getId();
        this.TUBE_STATUS_DELETED = tubeEnums.getPatientTubeStatusDeleted().getId();
        this.TUBE_STATUS_INSERTED = tubeEnums.getPatientTubeStatusInserted().getId();
        this.TUBE_STATUS_REMOVED = tubeEnums.getPatientTubeStatusRemoved().getId();

        final TubeTypeListPB typeListPb = protoService.getConfig().getTube().getTypeList();
        this.DRAINAGE_TUBE = typeListPb.getTubeType(0);
        this.TUBE1 = typeListPb.getTubeType(1);
        this.TUBE2 = typeListPb.getTubeType(2);

        final TubeAttributeListPB typeAttrListPb = protoService.getConfig().getTube().getAttributeList();
        this.TUBE_ATTR1 = typeAttrListPb.getAttribute(0);
        this.TUBE_ATTR2 = typeAttrListPb.getAttribute(1);

        final TubeStatusListPB typeStatusListPb = protoService.getConfig().getTube().getStatusList();
        this.TUBE_STATUS1 = typeStatusListPb.getStatus(0);
        this.TUBE_STATUS2 = typeStatusListPb.getStatus(1);
        this.INVALID_ID = 1000000;

        this.GROUP_TYPE_BALANCE_ID = protoService.getConfig().getMonitoring().getEnums().getGroupTypeBalance().getId();

        this.tubeSetting = tubeSetting;
        this.tubeService = tubeService;
        this.monitoringConfig = monitoringConfig;
        this.monitoringService = monitoringService;
        this.tubeTypeRepo = tubeTypeRepo;
        this.tubeTypeAttrRepo = tubeTypeAttrRepo;
        this.tubeTypeStatusRepo = tubeTypeStatusRepo;

        this.patientTubeService = patientTubeService;

        this.patientRepo = patientRepo;
        this.rbacDepartmentRepo = rbacDepartmentRepo;
        this.patientTestUtils = new PatientTestUtils();
    }

    @Test
    public void testInsertRemoveDeleteRetainTube() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initTubeType();

        // 新增一个病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 20, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(401L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);
        final Long patientId = patient.getId();

        // 查询病人管道
        GetPatientTubeRecordsReq patientTubeReq1 = GetPatientTubeRecordsReq.newBuilder()
            .setPid(patientId)
            .setIsDeleted(0)
            .build();
        String patientTubeReqJson1 = ProtoUtils.protoToJson(patientTubeReq1);
        GetPatientTubeRecordsResp resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson1);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增病人管道-1
        final LocalDateTime newTubeTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 21, 10, 0), ZONE_ID);
        final LocalDateTime plannedRemovalTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 25, 10, 0), ZONE_ID);
        NewPatientTubeReq newPatientTubeReq = NewPatientTubeReq.newBuilder()
            .setPid(patientId)
            .setTubeTypeId(tubeTypeId1)
            .setTubeName("管道1")
            .setInsertedBy("user1")
            .setInsertedAtIso8601(TimeUtils.toIso8601String(admissionTime, ZONE_ID))
            .setPlannedRemovalAtIso8601(TimeUtils.toIso8601String(plannedRemovalTime, ZONE_ID))
            .setNote("note1")
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType1AttrId1).setValue("tubeType1AttrId1").build())
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType1AttrId2).setValue("tubeType1AttrId2").build())
            .build();
        String newPatientTubeReqJson = ProtoUtils.protoToJson(newPatientTubeReq);
        NewPatientTubeResp newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Long patientTubeRecordId1 = newTubeResp.getId();

        newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(StatusCode.PATIENT_TUBE_EXIST.ordinal());

        // 新增病人管道-2
        newPatientTubeReq = NewPatientTubeReq.newBuilder()
            .setPid(patientId)
            .setTubeTypeId(tubeTypeId2)
            .setTubeName("管道2")
            .setInsertedBy("user2")
            .setInsertedAtIso8601(TimeUtils.toIso8601String(newTubeTime, ZONE_ID))
            .setPlannedRemovalAtIso8601(TimeUtils.toIso8601String(plannedRemovalTime, ZONE_ID))
            .setNote("note2")
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId1).setValue("tubeType2AttrId1").build())
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId2).setValue("tubeType2AttrId2").build())
            .build();
        newPatientTubeReqJson = ProtoUtils.protoToJson(newPatientTubeReq);
        newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Long patientTubeRecordId2 = newTubeResp.getId();

        resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson1);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).hasSize(2);
        assertThat(resp.getRecord(0).getStatus()).isEqualTo(TUBE_STATUS_INSERTED);
        assertThat(resp.getRecord(1).getStatus()).isEqualTo(TUBE_STATUS_INSERTED);

        // 拔病人管道-1
        final LocalDateTime removeTubeTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 22, 10, 0), ZONE_ID);
        RemovePatientTubeReq removePatientTubeReq = RemovePatientTubeReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId1)
            .setIsUnplannedRemoval(0)
            .setRemovalReason("reason1")
            .setRemovedBy("user1")
            .setRemovedAtIso8601(TimeUtils.toIso8601String(removeTubeTime, ZONE_ID))
            .build();
        String removePatientTubeReqJson = ProtoUtils.protoToJson(removePatientTubeReq);
        GenericResp gResp = patientTubeService.removePatientTube(removePatientTubeReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 确认一根拔了，一根插着
        resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson1);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).hasSize(2);
        assertThat(resp.getRecord(0).getStatus()).isEqualTo(TUBE_STATUS_INSERTED);
        assertThat(resp.getRecord(1).getStatus()).isEqualTo(TUBE_STATUS_REMOVED);

        // 确认没有被删除的管道
        GetPatientTubeRecordsReq patientTubeReq2 = GetPatientTubeRecordsReq.newBuilder()
            .setPid(patientId)
            .setIsDeleted(1)
            .build();
        String patientTubeReqJson2 = ProtoUtils.protoToJson(patientTubeReq2);
        resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson2);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).isEmpty();

        // 删除病人管道-1
        final LocalDateTime deleteTubeTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 23, 10, 0), ZONE_ID);
        DeletePatientTubeReq deletePatientTubeReq = DeletePatientTubeReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId1)
            .setDeleteReason("delete reason1")
            .build();
        String deletePatientTubeReqJson = ProtoUtils.protoToJson(deletePatientTubeReq);
        gResp = patientTubeService.deletePatientTube(deletePatientTubeReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson1);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).hasSize(1);
        assertThat(resp.getRecord(0).getStatus()).isEqualTo(TUBE_STATUS_INSERTED);
        assertThat(resp.getRecord(0).getIsRetainedOnDischarge()).isEqualTo(0);

        resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson2);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).hasSize(1);
        assertThat(resp.getRecord(0).getStatus()).isEqualTo(TUBE_STATUS_DELETED);

        // 将管道2设置为带管出科
        RetainTubeOnDischargeReq retainTubeOnDischargeReq = RetainTubeOnDischargeReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId2)
            .setIsRetain(1)
            .build();
        String retainTubeOnDischargeReqJson = ProtoUtils.protoToJson(retainTubeOnDischargeReq);
        gResp = patientTubeService.retainTubeOnDischarge(retainTubeOnDischargeReqJson);

        assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson1);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).hasSize(1);
        assertThat(resp.getRecord(0).getStatus()).isEqualTo(TUBE_STATUS_INSERTED);
        assertThat(resp.getRecord(0).getIsRetainedOnDischarge()).isEqualTo(1);
    }

    @Test
    public void testReplaceTube() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initTubeType();

        // 新增一个病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 20, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(402L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);
        final Long patientId = patient.getId();

        // 新增一根管道
        final LocalDateTime newTubeTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 21, 10, 0), ZONE_ID);
        final LocalDateTime plannedRemovalTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 25, 10, 0), ZONE_ID);
        NewPatientTubeReq newPatientTubeReq = NewPatientTubeReq.newBuilder()
            .setPid(patientId)
            .setTubeTypeId(tubeTypeId2)
            .setTubeName("管道2")
            .setInsertedBy("user1")
            .setInsertedAtIso8601(TimeUtils.toIso8601String(admissionTime, ZONE_ID))
            .setPlannedRemovalAtIso8601(TimeUtils.toIso8601String(plannedRemovalTime, ZONE_ID))
            .setNote("note1")
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId1).setValue("tubeType2AttrId1").build())
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId2).setValue("tubeType2AttrId2").build())
            .build();
        String newPatientTubeReqJson = ProtoUtils.protoToJson(newPatientTubeReq);
        NewPatientTubeResp newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Long patientTubeRecordId1 = newTubeResp.getId();

        // 查询病人管道
        GetPatientTubeRecordsReq patientTubeReq = GetPatientTubeRecordsReq.newBuilder()
            .setPid(patientId)
            .setIsDeleted(0)
            .build();
        String patientTubeReqJson = ProtoUtils.protoToJson(patientTubeReq);
        GetPatientTubeRecordsResp resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).hasSize(1);

        // 换管
        final LocalDateTime replaceTubeTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 22, 10, 0), ZONE_ID);
        final LocalDateTime replacePlannedRemovalTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 26, 10, 0), ZONE_ID);
        ReplacePatientTubeReq replacePatientTubeReq = ReplacePatientTubeReq.newBuilder()
            .setReplacedTubeRecordId(patientTubeRecordId1)
            .setRemovalReason("reason1")
            .setRemovedBy("user1")
            .setRemovedAtIso8601(TimeUtils.toIso8601String(replaceTubeTime, ZONE_ID))
            .setNewTubeTypeId(tubeTypeId2)
            .setNewTubeName("管道2-2")
            .setPlannedRemovalAtIso8601(TimeUtils.toIso8601String(replacePlannedRemovalTime, ZONE_ID))
            .setNote("note2")
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId1).setValue("tubeType1AttrId1-2").build())
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId2).setValue("tubeType1AttrId2-2").build())
            .build();
        String replacePatientTubeReqJson = ProtoUtils.protoToJson(replacePatientTubeReq);
        NewPatientTubeResp replaceTubeResp = patientTubeService.replacePatientTube(replacePatientTubeReqJson);
        assertThat(replaceTubeResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Long patientTubeRecordId2 = replaceTubeResp.getId();
        assertThat(patientTubeRecordId2).isNotEqualTo(patientTubeRecordId1);

        resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecordList()).hasSize(2);  // status: removed & inserted

        // 更新管道属性
        UpdatePatientTubeAttrReq updatePatientTubeAttrReq = UpdatePatientTubeAttrReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId2)
            .setInsertedBy("user2")
            .setNote("note3")
            .addAttrs(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId1).setValue("tubeType2AttrId1-3").build())
            .build();
        String updatePatientTubeAttrReqJson = ProtoUtils.protoToJson(updatePatientTubeAttrReq);
        GenericResp gResp = patientTubeService.updatePatientTubeAttr(updatePatientTubeAttrReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getRecord(0).getInsertedBy()).isEqualTo("user2");
        assertThat(resp.getRecord(0).getNote()).isEqualTo("note3");
    }

    @Test
    public void testPatientTubeStatus() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initTubeType();

        // 新增一个病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 20, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(403L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);
        final Long patientId = patient.getId();

        // 新增一根管道
        final LocalDateTime newTubeTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 21, 10, 0), ZONE_ID);
        final LocalDateTime plannedRemovalTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 25, 10, 0), ZONE_ID);
        NewPatientTubeReq newPatientTubeReq = NewPatientTubeReq.newBuilder()
            .setPid(patientId)
            .setTubeTypeId(tubeTypeId2)
            .setTubeName("管道2")
            .setInsertedBy("user1")
            .setInsertedAtIso8601(TimeUtils.toIso8601String(admissionTime, ZONE_ID))
            .setPlannedRemovalAtIso8601(TimeUtils.toIso8601String(plannedRemovalTime, ZONE_ID))
            .setNote("note1")
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId1).setValue("tubeType2AttrId1").build())
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId2).setValue("tubeType2AttrId2").build())
            .build();
        String newPatientTubeReqJson = ProtoUtils.protoToJson(newPatientTubeReq);
        NewPatientTubeResp newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Long patientTubeRecordId1 = newTubeResp.getId();

        // 查询状态数据-1
        final LocalDateTime queryTimeStart = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 23, 8, 0), ZONE_ID);
        final LocalDateTime queryTimeEnd = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 24, 12, 0), ZONE_ID);
        GetPatientTubeStatusReq patientTubeStatusReq = GetPatientTubeStatusReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId1)
            .setQueryStartIso8601(TimeUtils.toIso8601String(queryTimeStart, ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(queryTimeEnd, ZONE_ID))
            .build();
        String patientTubeStatusReqJson = ProtoUtils.protoToJson(patientTubeStatusReq);
        GetPatientTubeStatusResp resp = patientTubeService.getPatientTubeStatus(patientTubeStatusReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getTimeStatusList()).hasSize(0);

        // 新增状态数据 - 
        final LocalDateTime statusTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 22, 10, 0), ZONE_ID);  // 被过滤的
        final LocalDateTime statusTime2 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 23, 10, 0), ZONE_ID);  // 正常查询
        final LocalDateTime statusTime3 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 24, 10, 0), ZONE_ID);  // 正常查询

        NewPatientTubeStatusReq newPatientTubeStatusReq = NewPatientTubeStatusReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId1)
            .setTimeStatus(TubeTimeStatusValListPB.newBuilder()
                .setRecordedAtIso8601(TimeUtils.toIso8601String(statusTime, ZONE_ID))
                .addStatus(TubeStatusValPB.newBuilder()
                    .setTubeStatusId(tubeType2StatusId1)
                    .setValue("tubeType2StatusId1-val1")
                    .build()
                )
                .addStatus(TubeStatusValPB.newBuilder()
                    .setTubeStatusId(tubeType2StatusId2)
                    .setValue("tubeType2StatusId2-val1")
                    .build()
                )
                .build()
            )
            .build();
        String newPatientTubeStatusReqJson = ProtoUtils.protoToJson(newPatientTubeStatusReq);
        GenericResp gResp = patientTubeService.newPatientTubeStatus(newPatientTubeStatusReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        newPatientTubeStatusReq = NewPatientTubeStatusReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId1)
            .setTimeStatus(TubeTimeStatusValListPB.newBuilder()
                .setRecordedAtIso8601(TimeUtils.toIso8601String(statusTime2, ZONE_ID))
                .addStatus(TubeStatusValPB.newBuilder()
                    .setTubeStatusId(tubeType2StatusId1)
                    .setValue("tubeType2StatusId1-val2")
                    .build()
                )
                .addStatus(TubeStatusValPB.newBuilder()
                    .setTubeStatusId(tubeType2StatusId2)
                    .setValue("tubeType2StatusId2-val2")
                    .build()
                )
                .build()
            )
            .build();
        newPatientTubeStatusReqJson = ProtoUtils.protoToJson(newPatientTubeStatusReq);
        gResp = patientTubeService.newPatientTubeStatus(newPatientTubeStatusReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        newPatientTubeStatusReq = NewPatientTubeStatusReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId1)
            .setTimeStatus(TubeTimeStatusValListPB.newBuilder()
                .setRecordedAtIso8601(TimeUtils.toIso8601String(statusTime3, ZONE_ID))
                .addStatus(TubeStatusValPB.newBuilder()
                    .setTubeStatusId(tubeType2StatusId1)
                    .setValue("tubeType2StatusId1-val3")
                    .build()
                )
                .addStatus(TubeStatusValPB.newBuilder()
                    .setTubeStatusId(tubeType2StatusId2)
                    .setValue("tubeType2StatusId2-val3")
                    .build()
                )
                .build()
            )
            .build();
        newPatientTubeStatusReqJson = ProtoUtils.protoToJson(newPatientTubeStatusReq);
        gResp = patientTubeService.newPatientTubeStatus(newPatientTubeStatusReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询状态数据-2
        resp = patientTubeService.getPatientTubeStatus(patientTubeStatusReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getTimeStatusList()).hasSize(2);

        // 删除状态数据
        DeletePatientTubeStatusReq deletePatientTubeStatusReq = DeletePatientTubeStatusReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId1)
            .setRecordedAtIso8601(TimeUtils.toIso8601String(statusTime2, ZONE_ID))
            .build();
        String deletePatientTubeStatusReqJson = ProtoUtils.protoToJson(deletePatientTubeStatusReq);
        gResp = patientTubeService.deletePatientTubeStatus(deletePatientTubeStatusReqJson);
        assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询状态数据-3
        resp = patientTubeService.getPatientTubeStatus(patientTubeStatusReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getTimeStatusList()).hasSize(1);
    }

    @Test
    public void testPatientTubeStatusPagination() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initTubeType();

        // 新增一个病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 20, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(404L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);
        final Long patientId = patient.getId();

        // 新增一根管道
        final LocalDateTime newTubeTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 21, 10, 0), ZONE_ID);
        final LocalDateTime plannedRemovalTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 25, 10, 0), ZONE_ID);
        NewPatientTubeReq newPatientTubeReq = NewPatientTubeReq.newBuilder()
            .setPid(patientId)
            .setTubeTypeId(tubeTypeId2)
            .setTubeName("管道2")
            .setInsertedBy("user1")
            .setInsertedAtIso8601(TimeUtils.toIso8601String(admissionTime, ZONE_ID))
            .setPlannedRemovalAtIso8601(TimeUtils.toIso8601String(plannedRemovalTime, ZONE_ID))
            .setNote("note1")
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId1).setValue("tubeType2AttrId1").build())
            .addAttributes(TubeAttributePB.newBuilder().setTubeAttrId(tubeType2AttrId2).setValue("tubeType2AttrId2").build())
            .build();
        String newPatientTubeReqJson = ProtoUtils.protoToJson(newPatientTubeReq);
        NewPatientTubeResp newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Long patientTubeRecordId1 = newTubeResp.getId();

        // 查询状态数据-1
        final LocalDateTime queryTimeStart = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 23, 8, 0), ZONE_ID);
        final LocalDateTime queryTimeEnd = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 24, 12, 0), ZONE_ID);
        GetPatientTubeStatusReq patientTubeStatusReq = GetPatientTubeStatusReq.newBuilder()
            .setPatientTubeRecordId(patientTubeRecordId1)
            .setQueryStartIso8601(TimeUtils.toIso8601String(queryTimeStart, ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(queryTimeEnd, ZONE_ID))
            .build();
        String patientTubeStatusReqJson = ProtoUtils.protoToJson(patientTubeStatusReq);
        GetPatientTubeStatusResp resp = patientTubeService.getPatientTubeStatus(patientTubeStatusReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getTimeStatusList()).hasSize(0);

        // 新增状态数据 - 
        final LocalDateTime statusTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 23, 10, 0), ZONE_ID);
        for (int i = 0; i < 10; ++i) {
            LocalDateTime recordedAt = statusTime.plusMinutes(i * 10);
            NewPatientTubeStatusReq newPatientTubeStatusReq = NewPatientTubeStatusReq.newBuilder()
                .setPatientTubeRecordId(patientTubeRecordId1)
                .setTimeStatus(TubeTimeStatusValListPB.newBuilder()
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(recordedAt, ZONE_ID))
                    .addStatus(TubeStatusValPB.newBuilder()
                        .setTubeStatusId(tubeType2StatusId1)
                        .setValue("tubeType2StatusId1-val" + i)
                        .build()
                    )
                    .addStatus(TubeStatusValPB.newBuilder()
                        .setTubeStatusId(tubeType2StatusId2)
                        .setValue("tubeType2StatusId2-val" + i)
                        .build()
                    )
                    .build()
                )
                .build();
            String newPatientTubeStatusReqJson = ProtoUtils.protoToJson(newPatientTubeStatusReq);
            GenericResp gResp = patientTubeService.newPatientTubeStatus(newPatientTubeStatusReqJson);
            assertThat(gResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        }

        // 查询状态数据-2
        patientTubeStatusReq = patientTubeStatusReq.toBuilder().setPageSize(3).setPageIndex(2).build();
        patientTubeStatusReqJson = ProtoUtils.protoToJson(patientTubeStatusReq);
        resp = patientTubeService.getPatientTubeStatus(patientTubeStatusReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getNumRows()).isEqualTo(10);
        assertThat(resp.getNumPages()).isEqualTo(4);
        assertThat(resp.getTimeStatusList()).hasSize(3);
        assertThat(resp.getTimeStatus(0).getRecordedAtIso8601()).isEqualTo("2024-10-23T10:30+08:00");

        // 查询状态数据-3
        patientTubeStatusReq = patientTubeStatusReq.toBuilder().setPageSize(3).setPageIndex(4).build();
        patientTubeStatusReqJson = ProtoUtils.protoToJson(patientTubeStatusReq);
        resp = patientTubeService.getPatientTubeStatus(patientTubeStatusReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getNumRows()).isEqualTo(10);
        assertThat(resp.getNumPages()).isEqualTo(4);
        assertThat(resp.getTimeStatusList()).hasSize(1);
        assertThat(resp.getTimeStatus(0).getRecordedAtIso8601()).isEqualTo("2024-10-23T11:30+08:00");

        // 放开查询时间
        patientTubeStatusReq = patientTubeStatusReq.toBuilder()
            .setQueryStartIso8601("").setQueryEndIso8601("")
            .setPageSize(0).setPageIndex(0).build();
        patientTubeStatusReqJson = ProtoUtils.protoToJson(patientTubeStatusReq);
        resp = patientTubeService.getPatientTubeStatus(patientTubeStatusReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getTimeStatusList()).hasSize(10);

        patientTubeStatusReq = patientTubeStatusReq.toBuilder().setPageSize(3).setPageIndex(4).build();
        patientTubeStatusReqJson = ProtoUtils.protoToJson(patientTubeStatusReq);
        resp = patientTubeService.getPatientTubeStatus(patientTubeStatusReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getNumRows()).isEqualTo(10);
        assertThat(resp.getNumPages()).isEqualTo(4);
        assertThat(resp.getTimeStatusList()).hasSize(1);
        assertThat(resp.getTimeStatus(0).getRecordedAtIso8601()).isEqualTo("2024-10-23T11:30+08:00");
    }

    // 测试用户引流管
    @Test
    public void testPatientDrainageTube() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initTubeType();

        // 新增一个病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 20, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(405L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);
        final Long patientId = patient.getId();

        // 查询病人管道
        GetPatientTubeRecordsReq patientTubeReq1 = GetPatientTubeRecordsReq.newBuilder()
            .setPid(patientId)
            .setIsDeleted(0)
            .build();
        String patientTubeReqJson1 = ProtoUtils.protoToJson(patientTubeReq1);
        GetPatientTubeRecordsResp resp = patientTubeService.getPatientTubeRecords(patientTubeReqJson1);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增病人引流管
        final LocalDateTime newTubeTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 22, 10, 0), ZONE_ID);
        final LocalDateTime plannedRemovalTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 25, 10, 0), ZONE_ID);
        NewPatientTubeReq newPatientTubeReq = NewPatientTubeReq.newBuilder()
            .setPid(patientId)
            .setTubeTypeId(drainageTubeTypeId)
            .setTubeName("引流管")
            .setInsertedBy("user1")
            .setInsertedAtIso8601(TimeUtils.toIso8601String(newTubeTime, ZONE_ID))
            .setPlannedRemovalAtIso8601(TimeUtils.toIso8601String(plannedRemovalTime, ZONE_ID))
            .setNote("note1")
            .build();
        String newPatientTubeReqJson = ProtoUtils.protoToJson(newPatientTubeReq);
        NewPatientTubeResp newTubeResp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        assertThat(newTubeResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Long patientTubeRecordId1 = newTubeResp.getId();

        // 查询出入量
        final LocalDateTime queryStart = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 22, 9, 0), ZONE_ID);
        final LocalDateTime queryEnd = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 23, 9, 0), ZONE_ID);
        GetPatientMonitoringGroupsReq patientMonitoringReq = GetPatientMonitoringGroupsReq.newBuilder()
            .setPid(patientId)
            .setDeptId(deptId)
            .setGroupType(GROUP_TYPE_BALANCE_ID)
            .setQueryStartIso8601(TimeUtils.toIso8601String(queryStart, ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(queryEnd, ZONE_ID))
            .build();
        String patientMonitoringReqJson = ProtoUtils.protoToJson(patientMonitoringReq);
        GetPatientMonitoringGroupsResp monitoringResp = monitoringService
            .getPatientMonitoringGroups(patientMonitoringReqJson);
        assertThat(monitoringResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 确认引流管存在
        assertThat(monitoringResp.getMonitoringGroupList()).hasSize(3);
        assertThat(monitoringResp.getMonitoringGroup(1).getName()).isEqualTo("出量");
        assertThat(monitoringResp.getMonitoringGroup(1).getParam(1).getCode()).isEqualTo("tube_ylg_ylg");
    }

    private void initTubeType() {
        lock.lock();  // 锁定资源
        try {
            if (!tubeTypeInitialized) {
                initDepartments();
                initTubeTypeImpl();
                tubeTypeInitialized = true;
            }
        } finally {
            lock.unlock();  // 确保在任何情况下都释放锁
        }
    }

    private void initTubeTypeImpl() {
        if (tubeTypeInitialized) return;

        // 为部门添加三个管道
        AddTubeTypeReq addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(TUBE1)
            .build();
        String addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        AddTubeTypeResp resp = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeTypeId1 = resp.getAddedTubeTypeId();

        addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(TUBE2)
            .build();
        addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        resp = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeTypeId2 = resp.getAddedTubeTypeId();

        addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(DRAINAGE_TUBE)
            .build();
        addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        resp = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        drainageTubeTypeId = resp.getAddedTubeTypeId();

        // 新增引流管会增加对应的出入量监测配置，这里手动刷新
        monitoringConfig.refresh();

        // 为每个管道添加两个属性 2*2, display order不同
        AddTubeTypeAttrReq addTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId1)
            .setAttr(TUBE_ATTR1.toBuilder().setDisplayOrder(1).build())
            .build();
        String addTubeTypeAttrReqJson = ProtoUtils.protoToJson(addTubeTypeAttrReq);
        AddTubeTypeAttrResp resp2 = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeType1AttrId1 = resp2.getAddedTubeTypeAttrId();

        addTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId1)
            .setAttr(TUBE_ATTR2.toBuilder().setDisplayOrder(2).build())
            .build();
        addTubeTypeAttrReqJson = ProtoUtils.protoToJson(addTubeTypeAttrReq);
        resp2 = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeType1AttrId2 = resp2.getAddedTubeTypeAttrId();

        addTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId2)
            .setAttr(TUBE_ATTR1.toBuilder().setDisplayOrder(2).build())
            .build();
        addTubeTypeAttrReqJson = ProtoUtils.protoToJson(addTubeTypeAttrReq);
        resp2 = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeType2AttrId1 = resp2.getAddedTubeTypeAttrId();

        addTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId2)
            .setAttr(TUBE_ATTR2.toBuilder().setDisplayOrder(1).build())
            .build();
        addTubeTypeAttrReqJson = ProtoUtils.protoToJson(addTubeTypeAttrReq);
        resp2 = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeType2AttrId2 = resp2.getAddedTubeTypeAttrId();

        // 为每个管道添加两个状态 2*2, display order不同
        AddTubeTypeStatusReq addTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId1)
            .setStatus(TUBE_STATUS1.toBuilder().setDisplayOrder(1).build())
            .build();
        String addTubeTypeStatusReqJson = ProtoUtils.protoToJson(addTubeTypeStatusReq);
        AddTubeTypeStatusResp resp3 = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeType1StatusId1 = resp3.getAddedTubeTypeStatusId();

        addTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId1)
            .setStatus(TUBE_STATUS2.toBuilder().setDisplayOrder(2).build())
            .build();
        addTubeTypeStatusReqJson = ProtoUtils.protoToJson(addTubeTypeStatusReq);
        resp3 = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeType1StatusId2 = resp3.getAddedTubeTypeStatusId();

        addTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId2)
            .setStatus(TUBE_STATUS1.toBuilder().setDisplayOrder(2).build())
            .build();
        addTubeTypeStatusReqJson = ProtoUtils.protoToJson(addTubeTypeStatusReq);
        resp3 = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeType2StatusId1 = resp3.getAddedTubeTypeStatusId();

        addTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId2)
            .setStatus(TUBE_STATUS2.toBuilder().setDisplayOrder(1).build())
            .build();
        addTubeTypeStatusReqJson = ProtoUtils.protoToJson(addTubeTypeStatusReq);
        resp3 = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        tubeType2StatusId2 = resp3.getAddedTubeTypeStatusId();
    }

    private void initDepartments() {
        RbacDepartment dept1 = new RbacDepartment();
        dept1.setDeptId(deptId);
        dept1.setDeptName("dept-1");
        rbacDepartmentRepo.save(dept1);
        monitoringConfig.initialize();
    }

    private static final Lock lock = new ReentrantLock();
    private static Boolean tubeTypeInitialized = false;
    private static Integer tubeTypeId1;
    private static Integer tubeTypeId2;
    private static Integer drainageTubeTypeId;
    private static Integer tubeType1AttrId1;
    private static Integer tubeType1AttrId2;
    private static Integer tubeType2AttrId1;
    private static Integer tubeType2AttrId2;
    private static Integer tubeType1StatusId1;
    private static Integer tubeType1StatusId2;
    private static Integer tubeType2StatusId1;
    private static Integer tubeType2StatusId2;

    // member variables initialized in the ctor.
    private final String accountId;
    private final String deptId;
    private final String ZONE_ID;
    private final Integer UI_TYPE_TEXT;
    private final Integer UI_TYPE_SINGLE_SELECT;
    private final Integer UI_TYPE_MULTI_SELECT;
    private final Integer TUBE_STATUS_DELETED;
    private final Integer TUBE_STATUS_INSERTED;
    private final Integer TUBE_STATUS_REMOVED;

    private final TubeTypePB DRAINAGE_TUBE;
    private final TubeTypePB TUBE1;
    private final TubeTypePB TUBE2;
    private final TubeValueSpecPB TUBE_ATTR1;
    private final TubeValueSpecPB TUBE_ATTR2;
    private final TubeValueSpecPB TUBE_STATUS1;
    private final TubeValueSpecPB TUBE_STATUS2;
    private final Integer INVALID_ID;

    private final Integer GROUP_TYPE_BALANCE_ID;

    private final TubeSetting tubeSetting;
    private final TubeService tubeService;
    private final MonitoringConfig monitoringConfig;
    private final MonitoringService monitoringService;
    private final TubeTypeRepository tubeTypeRepo;
    private final TubeTypeAttributeRepository tubeTypeAttrRepo;
    private final TubeTypeStatusRepository tubeTypeStatusRepo;

    private final PatientTubeService patientTubeService;

    private final PatientRecordRepository patientRepo;
    private final RbacDepartmentRepository rbacDepartmentRepo;
    private final PatientTestUtils patientTestUtils;
}