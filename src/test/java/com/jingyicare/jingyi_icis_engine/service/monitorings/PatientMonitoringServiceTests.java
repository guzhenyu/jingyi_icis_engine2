package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Comparator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class PatientMonitoringServiceTests extends TestsBase {
    public PatientMonitoringServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired MonitoringService monitoringService,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired MedicationConfig medConfig,
        @Autowired MedicationService medService,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired MedicationFrequencyRepository freqRepo,
        @Autowired MedicalOrderRepository medOrdRepo,
        @Autowired DeptMonitoringGroupRepository deptMonitoringGroupRepo,
        @Autowired DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepo,
        @Autowired PatientMonitoringTimePointRepository patientMonitoringTimePointRepo,
        @Autowired PatientMonitoringRecordRepository patientMonitoringRecordRepo,
        @Autowired RbacDepartmentRepository rbacDepartmentRepo
    ) {
        this.accountId = "admin";
        this.deptId = "10012";
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.IN_ICU_VAL = protoService.getConfig().getPatient().getEnumsV2()
            .getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .get()
            .getId();

        MonitoringPB monitoringMeta = protoService.getConfig().getMonitoring();
        this.SUMMARY_MONOTORING_CODE = monitoringMeta.getParamCodeSummary();
        this.IN_GROUP_NAME = monitoringMeta.getBalanceInGroupName();
        this.OUT_GROUP_NAME = monitoringMeta.getBalanceOutGroupName();
        this.NET_GROUP_NAME = monitoringMeta.getBalanceNetGroupName();
        this.HOURLY_NET_CODE = monitoringMeta.getNetHourlyNetCode();
        this.CUSTOM_MONITORING_GROUP_NAME = "test_group";
        this.BALANCE_TYPE_NAN_ID = monitoringMeta.getEnums().getBalanceNan().getId();
        this.BALANCE_TYPE_IN_ID = monitoringMeta.getEnums().getBalanceIn().getId();
        this.BALANCE_TYPE_OUT_ID = monitoringMeta.getEnums().getBalanceOut().getId();
        this.GROUP_TYPE_BALANCE = monitoringMeta.getEnums().getGroupTypeBalance().getId();
        this.GROUP_TYPE_MONITORING = monitoringMeta.getEnums().getGroupTypeMonitoring().getId();

        this.FREQ_CODE_ONCE = protoService.getConfig().getMedication().getFreqSpec().getOnceCode();
        this.ROUTE_INFUSION = "test_route_6";
        this.ROUTE_NOSE = "test_route_7";
        this.ROUTE_MOUTH = "test_route_8";
        MedicationConfigPB medMeta = protoService.getConfig().getMedication();
        this.ROUTE_GROUP_PUMP = medMeta.getEnums().getAdministrationRouteGroupInfusionPump().getId();
        this.INTAKE_TYPE_INTRAVENOUS = medMeta.getIntakeTypes().getIntakeType(1).getId();
        this.INTAKE_TYPE_GASTRIC = medMeta.getIntakeTypes().getIntakeType(3).getId();

        MEnums medEnums = medMeta.getEnums();
        this.ACTION_TYPE_START = medEnums.getMedicationExecutionActionTypeStart().getId();
        this.ACTION_TYPE_PAUSE = medEnums.getMedicationExecutionActionTypePause().getId();
        this.ACTION_TYPE_RESUME = medEnums.getMedicationExecutionActionTypeResume().getId();
        this.ACTION_TYPE_ADJUST_SPEED = medEnums.getMedicationExecutionActionTypeAdjustSpeed().getId();
        this.ACTION_TYPE_FAST_PUSH = medEnums.getMedicationExecutionActionTypeFastPush().getId();
        this.ACTION_TYPE_COMPLETE = medEnums.getMedicationExecutionActionTypeComplete().getId();

        this.protoService = protoService;
        this.monitoringConfig = monitoringConfig;
        this.monitoringService = monitoringService;
        this.patientMonitoringService = patientMonitoringService;
        this.medConfig = medConfig;
        this.medService = medService;

        this.patientRepo = patientRepo;
        this.routeRepo = routeRepo;
        this.medOrdRepo = medOrdRepo;
        this.deptMonitoringGroupRepo = deptMonitoringGroupRepo;
        this.deptMonitoringGroupParamRepo = deptMonitoringGroupParamRepo;
        this.patientMonitoringTimePointRepo = patientMonitoringTimePointRepo;
        this.patientMonitoringRecordRepo = patientMonitoringRecordRepo;
        this.rbacDepartmentRepo = rbacDepartmentRepo;

        this.patientTestUtils = new PatientTestUtils();
        this.medTestUtils = new MedicationTestUtils(protoService.getConfig().getMedication(), freqRepo);
    }

    @Test
    public void testPatientTimePoints() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initDepartments();

        PatientRecord patient = PatientTestUtils.newPatientRecord(601L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 11, 21));
        patient = patientRepo.save(patient);
        final Long pid = patient.getId();

        AddPatientMonitoringTimePointsReq addReq = AddPatientMonitoringTimePointsReq.newBuilder()
            .setPid(pid).setDeptId(deptId)
            .addTimePointIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 10), ZONE_ID))
            .addTimePointIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 20), ZONE_ID))
            .addTimePointIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 30), ZONE_ID))
            .addTimePointIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 40), ZONE_ID))
            .addTimePointIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 50), ZONE_ID))
            .build();
        String reqStr = ProtoUtils.protoToJson(addReq);
        AddPatientMonitoringTimePointsResp addResp = patientMonitoringService.addPatientMonitoringTimePoints(reqStr);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(addResp.getIdList()).hasSize(5);

        DeletePatientMonitoringTimePointsReq deleteReq = DeletePatientMonitoringTimePointsReq.newBuilder()
            .setId(addResp.getId(4)).build();
        reqStr = ProtoUtils.protoToJson(deleteReq);
        GenericResp deleteResp = patientMonitoringService.deletePatientMonitoringTimePoints(reqStr);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        GetPatientMonitoringGroupsReq req = GetPatientMonitoringGroupsReq.newBuilder()
            .setPid(pid).setDeptId(deptId).setGroupType(GROUP_TYPE_MONITORING)
            .setQueryStartIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 1, 0), ZONE_ID))
            .setSyncDeviceData(true)
            .build();
        reqStr = ProtoUtils.protoToJson(req);
        GetPatientMonitoringRecordsResp getResp = patientMonitoringService.getPatientMonitoringRecords(reqStr);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getCustomTimePointList()).hasSize(4);
        assertThat(getResp.getCustomTimePoint(0).getTimePointIso8601()).isEqualTo(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 10), ZONE_ID));
        assertThat(getResp.getCustomTimePoint(1).getTimePointIso8601()).isEqualTo(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 20), ZONE_ID));
        assertThat(getResp.getCustomTimePoint(2).getTimePointIso8601()).isEqualTo(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 30), ZONE_ID));
        assertThat(getResp.getCustomTimePoint(3).getTimePointIso8601()).isEqualTo(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 22, 0, 40), ZONE_ID));
    }

    @Test
    public void testPatientMonitoringRecords() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initDepartments();

        PatientRecord patient = PatientTestUtils.newPatientRecord(602L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 11, 21));
        patient = patientRepo.save(patient);
        final Long pid = patient.getId();

        /*
         *                          20241123 8:00      9:00      10:00      11:00
         * (in)  test_param1_in     1.0(->10.0)                  2.0
         * (in)  test_param2_in     3.0                4.0
         * intake_sum               13.0               4.0       2.0              (total = 19.0)
         * (out) test_param3_out    5.0                                     6.0
         * (out) test_param4_out    7.0(deleted)                 8.0
         * output_sum               5.0                          8.0        6.0   (total = 19.0)
         * (in)  test_param5_in     9.0                10.0                       (不计入量)
         * (in)  test_param6_in     11.0                         12.0             (不计入量)
         */
        Long recordId1 = addFloatValue(pid, "test_param1_in", 1.0f, LocalDateTime.of(2024, 11, 23, 0, 0));
        addFloatValue(pid, "test_param1_in", 2.0f, LocalDateTime.of(2024, 11, 23, 2, 0));
        addFloatValue(pid, "test_param2_in", 3.0f, LocalDateTime.of(2024, 11, 23, 0, 0));
        addFloatValue(pid, "test_param2_in", 4.0f, LocalDateTime.of(2024, 11, 23, 1, 0));
        addFloatValue(pid, "test_param3_out", 5.0f, LocalDateTime.of(2024, 11, 23, 0, 0));
        addFloatValue(pid, "test_param3_out", 6.0f, LocalDateTime.of(2024, 11, 23, 3, 0));
        Long recordId2 = addFloatValue(pid, "test_param4_out", 7.0f, LocalDateTime.of(2024, 11, 23, 0, 0));
        addFloatValue(pid, "test_param4_out", 8.0f, LocalDateTime.of(2024, 11, 23, 2, 0));
        addFloatValue(pid, "test_param5_in", 9.0f, LocalDateTime.of(2024, 11, 23, 0, 0));
        addFloatValue(pid, "test_param5_in", 10.0f, LocalDateTime.of(2024, 11, 23, 1, 0));
        addFloatValue(pid, "test_param6_in", 11.0f, LocalDateTime.of(2024, 11, 23, 0, 0));
        addFloatValue(pid, "test_param6_in", 12.0f, LocalDateTime.of(2024, 11, 23, 2, 0));

        UpdatePatientMonitoringRecordReq updateReq = UpdatePatientMonitoringRecordReq.newBuilder()
            .setId(recordId1).setValue(GenericValuePB.newBuilder().setFloatVal(10.0f).build())
            .setModifiedBy("admin").build();
        String reqStr = ProtoUtils.protoToJson(updateReq);
        GenericResp updateResp = patientMonitoringService.updatePatientMonitoringRecord(reqStr);
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        DeletePatientMonitoringRecordReq deleteReq = DeletePatientMonitoringRecordReq.newBuilder()
            .setId(recordId2).build();
        reqStr = ProtoUtils.protoToJson(deleteReq);
        GenericResp deleteResp = patientMonitoringService.deletePatientMonitoringRecord(reqStr);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        List<PatientMonitoringRecord> records = patientMonitoringRecordRepo.findByPidAndIsDeletedFalse(pid)
            .stream()
            .sorted(Comparator.comparing(PatientMonitoringRecord::getMonitoringParamCode)
                .thenComparing(PatientMonitoringRecord::getEffectiveTime))
            .collect(Collectors.toList());
        assertThat(records).hasSize(11);  // deleted (-1)

        List<PatientMonitoringRecord> deletedRecords = patientMonitoringRecordRepo
            .findByPidAndIsDeletedTrue(pid).stream()
            .sorted(Comparator.comparing(PatientMonitoringRecord::getMonitoringParamCode))
            .toList();
        assertThat(deletedRecords).hasSize(2);
        PatientMonitoringRecord deletedRecord = deletedRecords.get(0);
        assertThat(deletedRecord.getMonitoringParamCode()).isEqualTo("test_param1_in");
        assertThat(deletedRecord.getEffectiveTime()).isEqualTo(LocalDateTime.of(2024, 11, 23, 0, 0));
        assertThat(deletedRecord.getDeleteReason()).isEqualTo(MonitoringRecordUtils.DELETED_REASON_UPDATED);
        deletedRecord = deletedRecords.get(1);
        assertThat(deletedRecord.getMonitoringParamCode()).isEqualTo("test_param4_out");
        assertThat(deletedRecord.getEffectiveTime()).isEqualTo(LocalDateTime.of(2024, 11, 23, 0, 0));
        assertThat(deletedRecord.getDeleteReason()).isEqualTo(MonitoringRecordUtils.DELETED_REASON_DELETED);

        // 观察项
        GetPatientMonitoringGroupsReq getReq = GetPatientMonitoringGroupsReq.newBuilder()
            .setPid(pid).setDeptId(deptId).setGroupType(GROUP_TYPE_MONITORING)
            .setQueryStartIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 23, 0, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 24, 0, 0), ZONE_ID))
            .setSyncDeviceData(true)
            .build();
        reqStr = ProtoUtils.protoToJson(getReq);
        GetPatientMonitoringRecordsResp getResp = patientMonitoringService.getPatientMonitoringRecords(reqStr);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupRecordsList()).hasSize(1);
        assertThat(getResp.getGroupRecords(0).getCodeRecordsList()).hasSize(2);
        assertThat(getResp.getGroupRecords(0).getCodeRecords(0).getParamCode()).isEqualTo("test_param5_in");
        assertThat(getResp.getGroupRecords(0).getCodeRecords(0).getRecordValueList()).hasSize(2);
        assertThat(getResp.getGroupRecords(0).getCodeRecords(0).getRecordValue(0).getValue().getFloatVal()).isEqualTo(9.0f);
        assertThat(getResp.getGroupRecords(0).getCodeRecords(0).getRecordValue(1).getValue().getFloatVal()).isEqualTo(10.0f);
        assertThat(getResp.getGroupRecords(0).getCodeRecords(1).getParamCode()).isEqualTo("test_param6_in");
        assertThat(getResp.getGroupRecords(0).getCodeRecords(1).getRecordValueList()).hasSize(2);
        assertThat(getResp.getGroupRecords(0).getCodeRecords(1).getRecordValue(0).getValue().getFloatVal()).isEqualTo(11.0f);
        assertThat(getResp.getGroupRecords(0).getCodeRecords(1).getRecordValue(1).getValue().getFloatVal()).isEqualTo(12.0f);

        // 出入量
        getReq = GetPatientMonitoringGroupsReq.newBuilder()
            .setPid(pid).setDeptId(deptId).setGroupType(GROUP_TYPE_BALANCE)
            .setQueryStartIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 23, 0, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 24, 0, 0), ZONE_ID))
            .setSyncDeviceData(true)
            .build();
        reqStr = ProtoUtils.protoToJson(getReq);
        getResp = patientMonitoringService.getPatientMonitoringRecords(reqStr);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupRecordsList()).hasSize(3);
        assertThat(getResp.getGroupRecords(2).getCodeRecordsList()).hasSize(1);
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getParamCode()).isEqualTo(HOURLY_NET_CODE);
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getRecordValueList()).hasSize(5);
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getRecordValue(0).getValue().getFloatVal()).isEqualTo(8.0f);
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getRecordValue(1).getValue().getFloatVal()).isEqualTo(4.0f);
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getRecordValue(2).getValue().getFloatVal()).isEqualTo(-6.0f);
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getRecordValue(3).getValue().getFloatVal()).isEqualTo(-6.0f);
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getRecordValue(4).getValue().getFloatVal()).isEqualTo(0f);
    }

    @Test
    public void testBalanceWithMedIntakes() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initDepartments();
        initRoutes();

        // 新增病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(604L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);
        final Long pid = patient.getId();

        // His医嘱
        // group_id_329 (ROUTE_INFUSION: isContinuous = true)
        //    order_id_331: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getLocalTime(2024, 10, 10, 1, 0);  // shanghai: 20241011 09:00
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_331", patient.getHisPatientId(), "group_id_329", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "20ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE_INFUSION/*route_code*/, ROUTE_INFUSION/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // group_id_330 (ROUTE_NOSE: isContinuous = true), order_id_332
        medOrd.setGroupId("group_id_330");
        medOrd.setOrderId("order_id_332");
        medOrd.setAdministrationRouteCode(ROUTE_NOSE);
        medOrd.setAdministrationRouteName(ROUTE_NOSE);
        medOrd = medOrdRepo.save(medOrd);

        // group_id_331 (ROUTE_MOUTH: isContinuous = false), order_id_333
        medOrd.setGroupId("group_id_331");
        medOrd.setOrderId("order_id_333");
        medOrd.setAdministrationRouteCode(ROUTE_MOUTH);
        medOrd.setAdministrationRouteName(ROUTE_MOUTH);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        LocalDateTime queryStart = TimeUtils.getLocalTime(2024, 10, 11, 0, 0);
        LocalDateTime queryEnd = TimeUtils.getLocalTime(2024, 10, 12, 0, 0);
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(queryStart, ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(queryEnd, ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(3);
        assertThat(resp.getOrderGroup(0).getExeRecordCount()).isEqualTo(1);
        final Long medExeRecId1 = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        assertThat(resp.getOrderGroup(1).getExeRecordCount()).isEqualTo(1);
        final Long medExeRecId2 = resp.getOrderGroup(1).getExeRecord(0).getMedExeRec().getId();
        assertThat(resp.getOrderGroup(2).getExeRecordCount()).isEqualTo(1);
        final Long medExeRecId3 = resp.getOrderGroup(2).getExeRecord(0).getMedExeRec().getId();


        // 新增执行过程start, 10ml/hour
        LocalDateTime actionUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 11, 9, 0), ZONE_ID);
        SaveOrderExeActionReq saveOrderExeActionReq = SaveOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId1)
            .setActionType(ACTION_TYPE_START)
            .setAdministrationRate(10.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionUtc, ZONE_ID))
            .build();
        String saveOrderExeActionReqJson = ProtoUtils.protoToJson(saveOrderExeActionReq);
        SaveOrderExeActionResp saveOrderExeActionResp = medService.saveOrderExeAction(saveOrderExeActionReqJson);
        assertThat(saveOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        saveOrderExeActionReq = saveOrderExeActionReq.toBuilder()
            .setMedExeRecId(medExeRecId2)
            .build();
        saveOrderExeActionReqJson = ProtoUtils.protoToJson(saveOrderExeActionReq);
        saveOrderExeActionResp = medService.saveOrderExeAction(saveOrderExeActionReqJson);
        assertThat(saveOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        saveOrderExeActionReq = saveOrderExeActionReq.toBuilder()
            .setMedExeRecId(medExeRecId3)
            .build();
        saveOrderExeActionReqJson = ProtoUtils.protoToJson(saveOrderExeActionReq);
        saveOrderExeActionResp = medService.saveOrderExeAction(saveOrderExeActionReqJson);
        assertThat(saveOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 结束执行过程(medExeRecId1, medExeRecId2在数据库中，medExeRecId3临时算)
        actionUtc = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 11, 15, 0), ZONE_ID);
        saveOrderExeActionReq = saveOrderExeActionReq.toBuilder()
            .setMedExeRecId(medExeRecId1)
            .setActionType(ACTION_TYPE_COMPLETE)
            .setAdministrationRate(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionUtc, ZONE_ID))
            .build();
        saveOrderExeActionReqJson = ProtoUtils.protoToJson(saveOrderExeActionReq);
        saveOrderExeActionResp = medService.saveOrderExeAction(saveOrderExeActionReqJson);

        saveOrderExeActionReq = saveOrderExeActionReq.toBuilder()
            .setMedExeRecId(medExeRecId2)
            .build();
        saveOrderExeActionReqJson = ProtoUtils.protoToJson(saveOrderExeActionReq);
        saveOrderExeActionResp = medService.saveOrderExeAction(saveOrderExeActionReqJson);

        // 查看结果
        GetPatientMonitoringGroupsReq getReq = GetPatientMonitoringGroupsReq.newBuilder()
            .setPid(pid).setDeptId(deptId).setGroupType(GROUP_TYPE_BALANCE)
            .setQueryStartIso8601(TimeUtils.toIso8601String(queryStart, ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(queryEnd, ZONE_ID))
            .setSyncDeviceData(true)
            .build();
        String reqStr = ProtoUtils.protoToJson(getReq);
        GetPatientMonitoringRecordsResp getResp = patientMonitoringService.getPatientMonitoringRecords(reqStr);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupRecordsList()).hasSize(3);

        // 入量
        // intravenous_intake, gastric_intake, all
        assertThat(getResp.getGroupRecords(0).getCodeRecordsList()).hasSize(3);
        // intravenous_intake: 9:00, 10:00, all
        assertThat(getResp.getGroupRecords(0).getCodeRecords(0).getRecordValueList()).hasSize(3);
        // gastric_intake: 9:00, 10:00, all
        assertThat(getResp.getGroupRecords(0).getCodeRecords(1).getRecordValueList()).hasSize(3);
        // all: 9:00, 10:00, all
        assertThat(getResp.getGroupRecords(0).getCodeRecords(2).getRecordValueList()).hasSize(3);

        // 出量
        // all
        assertThat(getResp.getGroupRecords(1).getCodeRecordsList()).hasSize(1);
        // all: all
        assertThat(getResp.getGroupRecords(1).getCodeRecords(0).getRecordValueList()).hasSize(1);

        // 平衡
        // all
        assertThat(getResp.getGroupRecords(2).getCodeRecordsList()).hasSize(1);
        // all: 9:00, 10:00, all
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getRecordValueList()).hasSize(3);
        assertThat(getResp.getGroupRecords(2).getCodeRecords(0).getRecordValue(2).getValueStr()).isEqualTo("60");
    }

    @Test
    public void testPatientMonitoringRecordsWithMultiValue() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        final String paramCode = "test_param10007";

        // A: 新增观察参数 test_param10007
        AddMonitoringParamReq addParamReq = AddMonitoringParamReq.newBuilder()
            .setParam(
                MonitoringParamPB.newBuilder()
                    .setCode(paramCode)
                    .setName("测试参数10007")
                    .setValueMeta(
                        ValueMetaPB.newBuilder()
                            .setValueType(TypeEnumPB.STRING)
                            .setIsMultipleSelection(true)
                            .setUnit("")
                            .build())
                    .setBalanceType(BALANCE_TYPE_NAN_ID)
                    .build()
            )
            .build();
        String addParamReqJson = ProtoUtils.protoToJson(addParamReq);
        GenericResp addParamResp = monitoringService.addMonitoringParam(addParamReqJson);
        assertThat(addParamResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // A: 新增分组
        AddDeptMonitoringGroupReq addDeptGroupReq = AddDeptMonitoringGroupReq.newBuilder()
            .setDepartmentId(deptId)
            .setName("测试参数10007分组")
            .setGroupType(protoService.getConfig().getMonitoring().getEnums()
                .getGroupTypeMonitoring().getId()
            )
            .build();
        String addDeptGroupReqJson = ProtoUtils.protoToJson(addDeptGroupReq);
        AddDeptMonitoringGroupResp addDeptGroupResp = monitoringService.addDeptMonitoringGroup(addDeptGroupReqJson);
        assertThat(addDeptGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // A. 新增分组-参数
        AddDeptMonitoringGroupParamReq addGroupParamReq = AddDeptMonitoringGroupParamReq.newBuilder()
            .setDeptId(deptId)
            .setDeptMonitoringGroupId(addDeptGroupResp.getId()) // 入量分组 ID
            .setCode(paramCode)
            .setDisplayOrder(1)
            .build();
        String addGroupParamReqJson = ProtoUtils.protoToJson(addGroupParamReq);
        AddDeptMonitoringGroupParamResp addGroupParamResp = monitoringService.addDeptMonitoringGroupParam(addGroupParamReqJson);
        assertThat(addGroupParamResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        PatientRecord patient = PatientTestUtils.newPatientRecord(603L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 11, 21));
        patient = patientRepo.save(patient);
        final Long pid = patient.getId();

        LocalDateTime recordedAt = LocalDateTime.of(2024, 11, 23, 2, 0);
        AddPatientMonitoringRecordReq addReq = AddPatientMonitoringRecordReq.newBuilder()
            .setPid(pid).setDeptId(deptId)
            .setParamCode(paramCode)
            .addValues(GenericValuePB.newBuilder().setStrVal("A1").build())
            .addValues(GenericValuePB.newBuilder().setStrVal("A2").build())
            .addValues(GenericValuePB.newBuilder().setStrVal("A3").build())
            .setSource("")
            .setRecordedAtIso8601(TimeUtils.toIso8601String(recordedAt, ZONE_ID))
            .setRecordedBy("admin")
            .build();
        String reqStr = ProtoUtils.protoToJson(addReq);
        AddPatientMonitoringRecordResp addResp = patientMonitoringService.addPatientMonitoringRecord(reqStr);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查看结果
        GetPatientMonitoringGroupsReq getReq = GetPatientMonitoringGroupsReq.newBuilder()
            .setPid(pid).setDeptId(deptId).setGroupType(GROUP_TYPE_MONITORING)
            .setQueryStartIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 23, 0, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 11, 24, 0, 0), ZONE_ID))
            .setSyncDeviceData(true)
            .build();
        reqStr = ProtoUtils.protoToJson(getReq);
        GetPatientMonitoringRecordsResp getResp = patientMonitoringService.getPatientMonitoringRecords(reqStr);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupRecordsList()).hasSize(1);
        assertThat(getResp.getGroupRecords(0).getCodeRecordsList()).hasSize(1);
        assertThat(getResp.getGroupRecords(0).getCodeRecords(0).getRecordValueList()).hasSize(1);
        assertThat(getResp.getGroupRecords(0).getCodeRecords(0).getRecordValue(0).getValueStr()).isEqualTo("A1, A2, A3");
    }

    private Long addFloatValue(Long pid, String code, Float value, LocalDateTime timePoint) {
        AddPatientMonitoringRecordReq addReq = AddPatientMonitoringRecordReq.newBuilder()
            .setPid(pid).setDeptId(deptId)
            .setParamCode(code)
            .setValue(GenericValuePB.newBuilder().setFloatVal(value).build())
            .setSource("")
            .setRecordedAtIso8601(TimeUtils.toIso8601String(timePoint, ZONE_ID))
            .setRecordedBy("admin")
            .build();
        String reqStr = ProtoUtils.protoToJson(addReq);
        AddPatientMonitoringRecordResp addResp = patientMonitoringService.addPatientMonitoringRecord(reqStr);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        return addResp.getId();
    }

    private void initDepartments() {
        // 1. 初始化科室
        RbacDepartment dept = new RbacDepartment();
        dept.setDeptId(deptId);
        dept.setDeptName("dept");
        rbacDepartmentRepo.save(dept);

        // 2. 初始化监测参数
        monitoringConfig.initDeptMonitoringParams(deptId);

        // 3. 查询已存在的分组
        List<DeptMonitoringGroup> deptGroups = deptMonitoringGroupRepo.findByDeptIdAndIsDeletedFalse(deptId);

        // 4. 入量组参数
        DeptMonitoringGroup inGroup = getGroupByName(deptGroups, IN_GROUP_NAME);
        saveParamIfNotExist(inGroup.getId(), "test_param1_in", 1/*displayOrder*/);
        saveParamIfNotExist(inGroup.getId(), "test_param2_in", 2/*displayOrder*/);

        // 5. 出量组参数
        DeptMonitoringGroup outGroup = getGroupByName(deptGroups, OUT_GROUP_NAME);
        saveParamIfNotExist(outGroup.getId(), "test_param3_out", 1/*displayOrder*/);
        saveParamIfNotExist(outGroup.getId(), "test_param4_out", 2/*displayOrder*/);

        // 6. 自定义监测组
        DeptMonitoringGroup customGroup = getGroupByName(deptGroups, CUSTOM_MONITORING_GROUP_NAME);
        if (customGroup == null) {
            customGroup = DeptMonitoringGroup.builder()
                .deptId(deptId)
                .name(CUSTOM_MONITORING_GROUP_NAME)
                .groupType(GROUP_TYPE_MONITORING)
                .displayOrder(2)
                .sumTypePb("")
                .isDeleted(false)
                .modifiedBy("admin")
                .modifiedAt(LocalDateTime.of(2024, 11, 28, 0, 0))
                .build();
            customGroup = deptMonitoringGroupRepo.save(customGroup);

            saveParamIfNotExist(customGroup.getId(), "test_param5_in", 1/*displayOrder*/);
            saveParamIfNotExist(customGroup.getId(), "test_param6_in", 2/*displayOrder*/);
        }
    }

    private DeptMonitoringGroup getGroupByName(List<DeptMonitoringGroup> groups, String name) {
        return groups.stream()
            .filter(g -> g.getName().equals(name))
            .findFirst()
            .orElse(null);
    }

    private void saveParamIfNotExist(Integer groupId, String paramCode, int displayOrder) {
        boolean exists = deptMonitoringGroupParamRepo
            .findByDeptMonitoringGroupIdAndMonitoringParamCodeAndIsDeletedFalse(groupId, paramCode)
            .isPresent();
        if (exists) return;

        DeptMonitoringGroupParam param = DeptMonitoringGroupParam.builder()
            .deptMonitoringGroupId(groupId)
            .monitoringParamCode(paramCode)
            .displayOrder(displayOrder)
            .isDeleted(false)
            .modifiedBy("admin")
            .modifiedAt(LocalDateTime.of(2024, 11, 28, 0, displayOrder - 1))
            .build();
        deptMonitoringGroupParamRepo.save(param);
    }


    @Transactional
    private void initRoutes() {
        AdministrationRoute route = MedicationTestUtils.newAdministrationRoute(
            deptId, ROUTE_INFUSION/*code*/, ROUTE_INFUSION/*name*/, true/*isContinuous*/,
            ROUTE_GROUP_PUMP/*groupId*/, INTAKE_TYPE_INTRAVENOUS/*intakeTypeId*/, true/*isValid*/
        );
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE_INFUSION).isPresent()) {
            routeRepo.save(route);
        }

        route.setId(null);
        route.setCode(ROUTE_NOSE);
        route.setName(ROUTE_NOSE);
        route.setIntakeTypeId(INTAKE_TYPE_GASTRIC);
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE_NOSE).isPresent()) {
            routeRepo.save(route);
        }

        route.setId(null);
        route.setCode(ROUTE_MOUTH);
        route.setName(ROUTE_MOUTH);
        route.setIntakeTypeId(INTAKE_TYPE_GASTRIC);
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE_MOUTH).isPresent()) {
            routeRepo.save(route);
        }

        medConfig.initialize();
        medConfig.refresh();
    }

    private final String accountId;
    private final String deptId;

    private final String ZONE_ID;
    private final Integer IN_ICU_VAL;
    private final String SUMMARY_MONOTORING_CODE;
    private final String IN_GROUP_NAME;
    private final String OUT_GROUP_NAME;
    private final String NET_GROUP_NAME;
    private final String HOURLY_NET_CODE;
    private final String CUSTOM_MONITORING_GROUP_NAME;
    private final Integer BALANCE_TYPE_NAN_ID;
    private final Integer BALANCE_TYPE_IN_ID;
    private final Integer BALANCE_TYPE_OUT_ID;
    private final Integer GROUP_TYPE_BALANCE;
    private final Integer GROUP_TYPE_MONITORING;

    private final String FREQ_CODE_ONCE;
    private final String ROUTE_INFUSION;
    private final String ROUTE_NOSE;
    private final String ROUTE_MOUTH;
    private final Integer ROUTE_GROUP_PUMP;
    private final Integer INTAKE_TYPE_INTRAVENOUS;
    private final Integer INTAKE_TYPE_GASTRIC;

    private final Integer ACTION_TYPE_START;
    private final Integer ACTION_TYPE_PAUSE;
    private final Integer ACTION_TYPE_RESUME;
    private final Integer ACTION_TYPE_ADJUST_SPEED;
    private final Integer ACTION_TYPE_FAST_PUSH;
    private final Integer ACTION_TYPE_COMPLETE;

    private final ConfigProtoService protoService;
    private final MonitoringConfig monitoringConfig;
    private final MonitoringService monitoringService;
    private final PatientMonitoringService patientMonitoringService;

    private final MedicationConfig medConfig;
    private final MedicationService medService;

    private final PatientRecordRepository patientRepo;
    private final AdministrationRouteRepository routeRepo;
    private final MedicalOrderRepository medOrdRepo;
    private final DeptMonitoringGroupRepository deptMonitoringGroupRepo;
    private final DeptMonitoringGroupParamRepository deptMonitoringGroupParamRepo;
    private final PatientMonitoringTimePointRepository patientMonitoringTimePointRepo;
    private final PatientMonitoringRecordRepository patientMonitoringRecordRepo;
    private final RbacDepartmentRepository rbacDepartmentRepo;

    private PatientTestUtils patientTestUtils;
    private MedicationTestUtils medTestUtils;
}