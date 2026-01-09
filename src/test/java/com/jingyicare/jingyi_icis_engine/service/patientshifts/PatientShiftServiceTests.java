package com.jingyicare.jingyi_icis_engine.service.patientshifts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.repository.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.testutils.PatientTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class PatientShiftServiceTests extends TestsBase {
    public PatientShiftServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientService patientService,
        @Autowired PatientShiftService patientShiftService,
        @Autowired PatientRecordRepository patientRepo
    ) {
        this.accountId = "admin";
        this.deptId = "10019";
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.patientService = patientService;
        this.patientShiftService = patientShiftService;
        this.patientRepo = patientRepo;

        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 6, 8, 0), ZONE_ID);
        PatientTestUtils patientTestUtils = new PatientTestUtils();
        PatientRecord patient = patientTestUtils.newPatientRecord(
            1101L, patientService.getAdmissionStatusInIcuId(), deptId
        );
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        this.pid = patientRepo.save(patient).getId();
    }

    @Test
    public void testPatientShifts() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 新增
        final String shiftName = "P班";
        final LocalDateTime shiftStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 6, 8, 0), ZONE_ID);
        final LocalDateTime shiftEndTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2025, 2, 6, 16, 0), ZONE_ID);
        AddPatientShiftRecordReq addReq = AddPatientShiftRecordReq.newBuilder()
            .setPid(pid)
            .setContent("takeover1")
            .setShiftName(shiftName)
            .setShiftStartIso8601(TimeUtils.toIso8601String(shiftStartTime, ZONE_ID))
            .setShiftEndIso8601(TimeUtils.toIso8601String(shiftEndTime, ZONE_ID))
            .build();
        String addReqJson = ProtoUtils.protoToJson(addReq);
        AddPatientShiftRecordResp addResp = patientShiftService.addPatientShiftRecord(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Long recordId = addResp.getId();

        // 查询
        GetPatientShiftRecordsReq getReq = GetPatientShiftRecordsReq.newBuilder().setPid(pid).build();
        String getReqJson = ProtoUtils.protoToJson(getReq);
        GetPatientShiftRecordsResp getResp = patientShiftService.getPatientShiftRecords(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getRecordList()).hasSize(1);

        // 检查记录是否存在
        PatientShiftRecordExistsReq existsReq = PatientShiftRecordExistsReq.newBuilder()
            .setPid(pid)
            .setShiftName(shiftName)
            .setShiftStartIso8601(TimeUtils.toIso8601String(shiftStartTime, ZONE_ID))
            .build();
        String existsReqJson = ProtoUtils.protoToJson(existsReq);
        PatientShiftRecordExistsResp existsResp = patientShiftService.patientShiftRecordExists(existsReqJson);
        assertThat(existsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(existsResp.getExists()).isTrue();

        existsReq = existsReq.toBuilder().setShiftName("xxx").build();
        existsReqJson = ProtoUtils.protoToJson(existsReq);
        existsResp = patientShiftService.patientShiftRecordExists(existsReqJson);
        assertThat(existsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(existsResp.getExists()).isFalse();

        // 修改
        final String newContent = "takeover2";
        addReq = addReq.toBuilder().setId(recordId).setContent(newContent).build();
        addReqJson = ProtoUtils.protoToJson(addReq);
        GenericResp updateResp = patientShiftService.updatePatientShiftRecord(addReqJson);
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询
        getResp = patientShiftService.getPatientShiftRecords(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getRecordList()).hasSize(1);
        assertThat(getResp.getRecord(0).getContent()).isEqualTo(newContent);

        // 删除
        DeletePatientShiftRecordReq deleteReq = DeletePatientShiftRecordReq.newBuilder().setId(recordId).build();
        String deleteReqJson = ProtoUtils.protoToJson(deleteReq);
        GenericResp deleteResp = patientShiftService.deletePatientShiftRecord(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询
        getResp = patientShiftService.getPatientShiftRecords(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getRecordList()).hasSize(0);
    }

    final String accountId;
    final String deptId;
    final Long pid;
    final String ZONE_ID;

    final PatientService patientService;
    final PatientShiftService patientShiftService;
    final PatientRecordRepository patientRepo;
}