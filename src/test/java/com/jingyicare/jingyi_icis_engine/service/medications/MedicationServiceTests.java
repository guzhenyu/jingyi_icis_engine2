package com.jingyicare.jingyi_icis_engine.service.medications;

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
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.MedicationTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.PatientTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public class MedicationServiceTests extends TestsBase {
    public MedicationServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired MedicationConfig medConfig,
        @Autowired MedicationDictionary medDict,
        @Autowired MedicationService medService,
        @Autowired OrderExecutor ordExecutor,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired MedicalOrderRepository medOrdRepo,
        @Autowired MedicationFrequencyRepository freqRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired MedicationOrderGroupRepository medOrdGroupRepo,
        @Autowired MedicationExecutionRecordRepository medExeRecRepo,
        @Autowired MedicationExecutionActionRepository medExeActRepo
    ) {
        this.accountId = "admin";
        this.deptId = "10005";
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.FREQ_CODE_ONCE = protoService.getConfig().getMedication().getFreqSpec().getOnceCode();
        this.FREQ_CODE_TID = "test_freq_10_16_22";
        this.ROUTE_GROUP_PUMP = protoService.getConfig().getMedication()
            .getEnums().getAdministrationRouteGroupInfusionPump().getId();
        this.ROUTE_GROUP_OTHERS = protoService.getConfig().getMedication()
            .getEnums().getAdministrationRouteGroupOthers().getId();
        this.INTAKE_TYPE_INTRAVENOUS = protoService.getConfig().getMedication()
            .getIntakeTypes().getIntakeType(1).getId();
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE = protoService.getConfig()
            .getMedication().getEnums().getOneTimeExecutionStopStrategyIgnore().getId();

        final MEnums medEnums = protoService.getConfig().getMedication().getEnums();
        this.ACTION_TYPE_START = medEnums.getMedicationExecutionActionTypeStart().getId();
        this.ACTION_TYPE_PAUSE = medEnums.getMedicationExecutionActionTypePause().getId();
        this.ACTION_TYPE_RESUME = medEnums.getMedicationExecutionActionTypeResume().getId();
        this.ACTION_TYPE_ADJUST_SPEED = medEnums.getMedicationExecutionActionTypeAdjustSpeed().getId();
        this.ACTION_TYPE_FAST_PUSH = medEnums.getMedicationExecutionActionTypeFastPush().getId();
        this.ACTION_TYPE_COMPLETE = medEnums.getMedicationExecutionActionTypeComplete().getId();
        this.VALIDITY_TYPE_MANUAL_ENTRY = medEnums.getMedicationOrderValidityTypeManualEntry().getId();
        this.DURATION_TYPE_MANUAL_ENTRY = medEnums.getOrderDurationTypeManualEntry().getId();

        this.ROUTE1 = "test_route_1";
        this.ROUTE2 = "test_route_2";
        this.ROUTE3 = "test_route_3";
        this.ROUTE4 = "test_route_4";
        this.ROUTE5 = "test_route_5";

        this.ORDER_GROUP_STATUS_NOT_STARTED_TXT = protoService.getConfig().getMedication().getOrderGroupNotStartedTxt();
        this.ORDER_GROUP_STATUS_IN_PROGRESS_TXT = protoService.getConfig().getMedication().getOrderGroupInProgressTxt();
        this.ORDER_GROUP_STATUS_COMPLETED_TXT = protoService.getConfig().getMedication().getOrderGroupCompletedTxt();

        this.medConfig = medConfig;
        this.medDict = medDict;
        this.medService = medService;
        this.ordExecutor = ordExecutor;
        this.patientRepo = patientRepo;
        this.medOrdRepo = medOrdRepo;
        this.routeRepo = routeRepo;
        this.medOrdGroupRepo = medOrdGroupRepo;
        this.medExeRecRepo = medExeRecRepo;
        this.medExeActRepo = medExeActRepo;
        this.medTestUtils = new MedicationTestUtils(protoService.getConfig().getMedication(), freqRepo);
        this.patientTestUtils = new PatientTestUtils();

        medTestUtils.initFreqRepo();
        initMedOrderGroupSettings();
        initRoutes();
    }

    @Test
    public void testGetOrderGroups() {
        assertThat(medDict.getAdministrationRouteGroupId(deptId, ROUTE1)).isEqualTo(ROUTE_GROUP_PUMP);
        assertThat(medDict.getAdministrationRouteGroupId(deptId, ROUTE2)).isEqualTo(ROUTE_GROUP_OTHERS);
        assertThat(medDict.getAdministrationRouteGroupId(deptId, ROUTE3)).isEqualTo(ROUTE_GROUP_PUMP);
        assertThat(medDict.getAdministrationRouteGroupId(deptId, ROUTE4)).isEqualTo(ROUTE_GROUP_OTHERS);
        assertThat(medDict.getAdministrationRouteGroupId(deptId, ROUTE5)).isEqualTo(ROUTE_GROUP_OTHERS);

        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(301L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_301
        //    order_id_301: "med_code_2", FREQ_CODE_TID, ROUTE_GROUP_OTHERS
        // group_id_302
        //    order_id_302: "med_code_1", FREQ_CODE_TID, ROUTE_GROUP_OTHERS
        // group_id_303
        //    order_id_303: "med_code_1_氯化钠", FREQ_CODE_TID, ROUTE_GROUP_PUMP
        //    order_id_304: "med_code_3", FREQ_CODE_TID, ROUTE_GROUP_PUMP
        // group_id_304
        //    order_id_305: "med_code_2", FREQ_CODE_TID, ROUTE_GROUP_PUMP
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_301", patient.getHisPatientId(), "group_id_301", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_2", "med_name_2", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, MedicationTestUtils.FREQ_CODE_TID,
            0 /* 首日分解次数 */, ROUTE2/*route_code*/, ROUTE2/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_302");
        medOrd.setGroupId("group_id_302");
        medOrd.setOrderCode("med_code_1");
        medOrd.setOrderName("med_name_1");
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_303");
        medOrd.setGroupId("group_id_303");
        medOrd.setOrderCode("med_code_1_氯化钠");
        medOrd.setOrderName("med_name_1_氯化钠");
        medOrd.setAdministrationRouteCode(ROUTE1);
        medOrd.setAdministrationRouteName(ROUTE1);
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_304");
        medOrd.setOrderCode("med_code_3");
        medOrd.setOrderName("med_name_3");
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_305");
        medOrd.setGroupId("group_id_304");
        medOrd.setOrderCode("med_code_2");
        medOrd.setOrderName("med_name_2");
        medOrd.setAdministrationRouteCode(ROUTE3);
        medOrd.setAdministrationRouteName(ROUTE3);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 0, 0), ZONE_ID))  // shanghai 24.10.10 8:00
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 11, 0, 0), ZONE_ID))    // shanghai 24.10.11 8:00
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(4);

        assertThat(resp.getOrderGroup(0).getRouteGroupId()).isEqualTo(ROUTE_GROUP_PUMP);
        assertThat(resp.getOrderGroup(0).getMedOrderGroup().getGroupId()).isEqualTo("group_id_304");
        assertThat(resp.getOrderGroup(0).getMedOrderGroup().getDosageGroup().getMdList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getMedOrderGroup().getDosageGroup().getDisplayName())
            .isEqualTo("med_name_2");
        assertThat(resp.getOrderGroup(0).getMedOrderGroup().getMedicalOrderIds().getIdList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getMedOrderGroup().getMedicalOrderIds().getId(0)).isEqualTo("order_id_305");
        assertThat(resp.getOrderGroup(0).getExeRecordList()).hasSize(3);
        assertThat(resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T10:00+08:00");
        assertThat(resp.getOrderGroup(0).getExeRecord(0).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(0).getExeRecord(1).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T16:00+08:00");
        assertThat(resp.getOrderGroup(0).getExeRecord(1).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(0).getExeRecord(2).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T22:00+08:00");
        assertThat(resp.getOrderGroup(0).getExeRecord(2).getMedExeActionList()).hasSize(0);

        assertThat(resp.getOrderGroup(1).getRouteGroupId()).isEqualTo(ROUTE_GROUP_PUMP);
        assertThat(resp.getOrderGroup(1).getMedOrderGroup().getGroupId()).isEqualTo("group_id_303");
        assertThat(resp.getOrderGroup(1).getMedOrderGroup().getDosageGroup().getMdList()).hasSize(2);
        assertThat(resp.getOrderGroup(1).getMedOrderGroup().getDosageGroup().getDisplayName())
            .isEqualTo("med_name_3 + med_name_1_氯化钠");
        assertThat(resp.getOrderGroup(1).getMedOrderGroup().getMedicalOrderIds().getIdList()).hasSize(2);
        assertThat(resp.getOrderGroup(1).getMedOrderGroup().getMedicalOrderIds().getId(0)).isEqualTo("order_id_303");
        assertThat(resp.getOrderGroup(1).getMedOrderGroup().getMedicalOrderIds().getId(1)).isEqualTo("order_id_304");
        assertThat(resp.getOrderGroup(1).getExeRecordList()).hasSize(3);
        assertThat(resp.getOrderGroup(1).getExeRecord(0).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T10:00+08:00");
        assertThat(resp.getOrderGroup(1).getExeRecord(0).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(1).getExeRecord(1).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T16:00+08:00");
        assertThat(resp.getOrderGroup(1).getExeRecord(1).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(1).getExeRecord(2).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T22:00+08:00");
        assertThat(resp.getOrderGroup(1).getExeRecord(2).getMedExeActionList()).hasSize(0);

        assertThat(resp.getOrderGroup(2).getRouteGroupId()).isEqualTo(ROUTE_GROUP_OTHERS);
        assertThat(resp.getOrderGroup(2).getMedOrderGroup().getGroupId()).isEqualTo("group_id_302");
        assertThat(resp.getOrderGroup(2).getMedOrderGroup().getDosageGroup().getMdList()).hasSize(1);
        assertThat(resp.getOrderGroup(2).getMedOrderGroup().getDosageGroup().getDisplayName())
            .isEqualTo("med_name_1");
        assertThat(resp.getOrderGroup(2).getMedOrderGroup().getMedicalOrderIds().getIdList()).hasSize(1);
        assertThat(resp.getOrderGroup(2).getMedOrderGroup().getMedicalOrderIds().getId(0)).isEqualTo("order_id_302");
        assertThat(resp.getOrderGroup(2).getExeRecordList()).hasSize(3);
        assertThat(resp.getOrderGroup(2).getExeRecord(0).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T10:00+08:00");
        assertThat(resp.getOrderGroup(2).getExeRecord(0).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(2).getExeRecord(1).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T16:00+08:00");
        assertThat(resp.getOrderGroup(2).getExeRecord(1).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(2).getExeRecord(2).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T22:00+08:00");
        assertThat(resp.getOrderGroup(2).getExeRecord(2).getMedExeActionList()).hasSize(0);

        assertThat(resp.getOrderGroup(3).getRouteGroupId()).isEqualTo(ROUTE_GROUP_OTHERS);
        assertThat(resp.getOrderGroup(3).getMedOrderGroup().getGroupId()).isEqualTo("group_id_301");
        assertThat(resp.getOrderGroup(3).getMedOrderGroup().getDosageGroup().getMdList()).hasSize(1);
        assertThat(resp.getOrderGroup(3).getMedOrderGroup().getDosageGroup().getDisplayName())
            .isEqualTo("med_name_2");
        assertThat(resp.getOrderGroup(3).getMedOrderGroup().getMedicalOrderIds().getIdList()).hasSize(1);
        assertThat(resp.getOrderGroup(3).getMedOrderGroup().getMedicalOrderIds().getId(0)).isEqualTo("order_id_301");
        assertThat(resp.getOrderGroup(3).getExeRecordList()).hasSize(3);
        assertThat(resp.getOrderGroup(3).getExeRecord(0).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T10:00+08:00");
        assertThat(resp.getOrderGroup(3).getExeRecord(0).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(3).getExeRecord(1).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T16:00+08:00");
        assertThat(resp.getOrderGroup(3).getExeRecord(1).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(3).getExeRecord(2).getMedExeRec().getPlanTimeIso8601()).contains("2024-10-10T22:00+08:00");
        assertThat(resp.getOrderGroup(3).getExeRecord(2).getMedExeActionList()).hasSize(0);

        // 生成执行过程
        Long exeRecordId = resp.getOrderGroup(2).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_COMPLETE,
            0.0/*administrationRate*/, "", 100.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 10), ZONE_ID)
        );
        exeRecordId = resp.getOrderGroup(2).getExeRecord(1).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_COMPLETE,
            0.0/*administrationRate*/, "", 100.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 11), ZONE_ID)
        );
        exeRecordId = resp.getOrderGroup(2).getExeRecord(2).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_COMPLETE,
            0.0/*administrationRate*/, "", 100.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 12), ZONE_ID)
        );

        exeRecordId = resp.getOrderGroup(3).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_START,
            0.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 13), ZONE_ID)
        );
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_COMPLETE,
            0.0/*administrationRate*/, "", 100.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 14), ZONE_ID)
        );

        // 查询
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(4);

        // 验证
        assertThat(resp.getOrderGroup(0).getRouteGroupId()).isEqualTo(ROUTE_GROUP_PUMP);
        assertThat(resp.getOrderGroup(0).getExeRecordList()).hasSize(3);
        assertThat(resp.getOrderGroup(0).getExeRecord(0).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(0).getExeRecord(1).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(0).getExeRecord(2).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(0).getStatus()).isEqualTo(ORDER_GROUP_STATUS_NOT_STARTED_TXT);

        assertThat(resp.getOrderGroup(1).getRouteGroupId()).isEqualTo(ROUTE_GROUP_PUMP);
        assertThat(resp.getOrderGroup(1).getExeRecordList()).hasSize(3);
        assertThat(resp.getOrderGroup(1).getExeRecord(0).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(1).getExeRecord(1).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(1).getExeRecord(2).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(1).getStatus()).isEqualTo(ORDER_GROUP_STATUS_NOT_STARTED_TXT);

        assertThat(resp.getOrderGroup(2).getRouteGroupId()).isEqualTo(ROUTE_GROUP_OTHERS);
        assertThat(resp.getOrderGroup(2).getExeRecordList()).hasSize(3);
        assertThat(resp.getOrderGroup(2).getExeRecord(0).getMedExeActionList()).hasSize(1);
        assertThat(resp.getOrderGroup(2).getExeRecord(1).getMedExeActionList()).hasSize(1);
        assertThat(resp.getOrderGroup(2).getExeRecord(2).getMedExeActionList()).hasSize(1);
        assertThat(resp.getOrderGroup(2).getStatus()).isEqualTo(ORDER_GROUP_STATUS_COMPLETED_TXT);

        assertThat(resp.getOrderGroup(3).getRouteGroupId()).isEqualTo(ROUTE_GROUP_OTHERS);
        assertThat(resp.getOrderGroup(3).getExeRecordList()).hasSize(3);

        assertThat(resp.getOrderGroup(3).getExeRecord(0).getMedExeActionList()).hasSize(2);
        assertThat(resp.getOrderGroup(3).getExeRecord(0).getMedExeAction(0).getActionType()).isEqualTo(ACTION_TYPE_START);
        assertThat(resp.getOrderGroup(3).getExeRecord(0).getMedExeAction(1).getActionType()).isEqualTo(ACTION_TYPE_COMPLETE);

        assertThat(resp.getOrderGroup(3).getExeRecord(1).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(3).getExeRecord(2).getMedExeActionList()).hasSize(0);
        assertThat(resp.getOrderGroup(3).getStatus()).isEqualTo(ORDER_GROUP_STATUS_IN_PROGRESS_TXT);
    }

    @Test
    public void testNextActions_RouteIsContinuous() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(302L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_305 (ROUTE4: isContinuous = true)  // 没有任何action
        //    order_id_306: "med_code_1", FREQ_CODE_ONCE
        // group_id_306 (ROUTE4: isContinuous = true)  // 前置action为start
        //    order_id_307: "med_code_1", FREQ_CODE_ONCE
        // group_id_307 (ROUTE4: isContinuous = true)  // 前置action为adjust_speed
        //    order_id_308: "med_code_1", FREQ_CODE_ONCE
        // group_id_308 (ROUTE4: isContinuous = true)  // 前置action为pause
        //    order_id_309: "med_code_1", FREQ_CODE_ONCE
        // group_id_309 (ROUTE4: isContinuous = true)  // 前置action为resume
        //    order_id_310: "med_code_1", FREQ_CODE_ONCE
        // group_id_310 (ROUTE4: isContinuous = true)  // 前置action为complete
        //    order_id_311: "med_code_1", FREQ_CODE_ONCE
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_306", patient.getHisPatientId(), "group_id_305", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_307");
        medOrd.setGroupId("group_id_306");
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_308");
        medOrd.setGroupId("group_id_307");
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_309");
        medOrd.setGroupId("group_id_308");
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_310");
        medOrd.setGroupId("group_id_309");
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_311");
        medOrd.setGroupId("group_id_310");
        medOrd = medOrdRepo.save(medOrd);

        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(6);

        Long exeRecordId = resp.getOrderGroup(1).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_START,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 10), ZONE_ID)
        );

        /* 暂停到当下 */
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_PAUSE,
            0.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 40), ZONE_ID)
        );
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_RESUME,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/, TimeUtils.getNowUtc()
        );

        exeRecordId = resp.getOrderGroup(2).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_START,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 10), ZONE_ID)
        );
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_ADJUST_SPEED,
            100.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 40), ZONE_ID)
        );

        exeRecordId = resp.getOrderGroup(3).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_START,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 10), ZONE_ID)
        );
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_PAUSE,
            0.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 40), ZONE_ID)
        );

        exeRecordId = resp.getOrderGroup(4).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_START,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 10), ZONE_ID)
        );
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_PAUSE,
            0.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 40), ZONE_ID)
        );
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_RESUME,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 10), ZONE_ID)
        );
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_FAST_PUSH,
            0.0/*administrationRate*/, "", 20.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 15), ZONE_ID)
        );

        exeRecordId = resp.getOrderGroup(5).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_START,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 10), ZONE_ID)
        );
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_COMPLETE,
            0.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 40), ZONE_ID)
        );

        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(6);
        assertThat(resp.getOrderGroup(0).getExeRecord(0).getNextActionTypeList()).containsExactly(
            ACTION_TYPE_START
        );  // null
        assertThat(resp.getOrderGroup(1).getExeRecord(0).getNextActionTypeList()).containsExactly(
            ACTION_TYPE_PAUSE, ACTION_TYPE_ADJUST_SPEED, ACTION_TYPE_FAST_PUSH, ACTION_TYPE_COMPLETE
        );  // start
        assertThat(resp.getOrderGroup(2).getExeRecord(0).getNextActionTypeList()).containsExactly(
            ACTION_TYPE_PAUSE, ACTION_TYPE_ADJUST_SPEED, ACTION_TYPE_FAST_PUSH, ACTION_TYPE_COMPLETE
        );  // start -> adjust_speed
        assertThat(resp.getOrderGroup(3).getExeRecord(0).getNextActionTypeList()).containsExactly(
            ACTION_TYPE_RESUME, ACTION_TYPE_FAST_PUSH, ACTION_TYPE_COMPLETE
        );  // start -> pause
        assertThat(resp.getOrderGroup(4).getExeRecord(0).getNextActionTypeList()).containsExactly(
            ACTION_TYPE_PAUSE, ACTION_TYPE_ADJUST_SPEED, ACTION_TYPE_FAST_PUSH, ACTION_TYPE_COMPLETE
        );  // start -> pause -> resume -> fast_push
        assertThat(resp.getOrderGroup(5).getExeRecord(0).getNextActionTypeList()).isEmpty();  // start -> complete

    }

    @Test
    public void testNextActions_RouteIsBolus() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(303L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_311 (ROUTE5: isContinuous = false)  // 前置action为null
        //    order_id_312: "med_code_1", FREQ_CODE_ONCE
        // group_id_312 (ROUTE5: isContinuous = false)  // 前置action为fast_push
        //    order_id_313: "med_code_1", FREQ_CODE_ONCE
        // group_id_313 (ROUTE5: isContinuous = false)  // 前置action为fast_push
        //    order_id_314: "med_code_1", FREQ_CODE_ONCE
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_312", patient.getHisPatientId(), "group_id_311", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE5/*route_code*/, ROUTE5/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_313");
        medOrd.setGroupId("group_id_312");
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_314");
        medOrd.setGroupId("group_id_313");
        medOrd = medOrdRepo.save(medOrd);

        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(3);

        Long exeRecordId = resp.getOrderGroup(1).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_FAST_PUSH,
            0.0/*administrationRate*/, "", 50.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 10), ZONE_ID)
        );

        exeRecordId = resp.getOrderGroup(2).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_COMPLETE,
            0.0/*administrationRate*/, "", 50.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 10), ZONE_ID)
        );

        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(3);
        assertThat(resp.getOrderGroup(0).getExeRecord(0).getNextActionTypeList()).containsExactly(
            ACTION_TYPE_FAST_PUSH, ACTION_TYPE_COMPLETE
        );  // null
        assertThat(resp.getOrderGroup(1).getExeRecord(0).getNextActionTypeList()).containsExactly(
            ACTION_TYPE_FAST_PUSH, ACTION_TYPE_COMPLETE
        );  // fast_push
        assertThat(resp.getOrderGroup(2).getExeRecord(0).getNextActionTypeList()).isEmpty();  // complete
    }

    @Test
    public void testIntakeVolume_Continuous() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(304L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_314 (ROUTE4: isContinuous = true)
        //    order_id_315: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        //    order_id_316: "med_code_2", FREQ_CODE_ONCE, 200ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_315", patient.getHisPatientId(), "group_id_314", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_316");
        medOrd.setOrderCode("med_code_2");
        medOrd.setOrderName("med_name_2");
        medOrd.setSpec("200ml:mg");
        medOrd = medOrdRepo.save(medOrd);

        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);

        // 9:45 - 10:00, 100ml/h, 25ml
        Long exeRecordId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_START,
            100.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 45), ZONE_ID)
        );

        // 10:00 - 10:30, 50ml/h, 25ml
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_ADJUST_SPEED,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 0), ZONE_ID)
        );

        // 10:30 - 10:45, 0ml/h, 0ml
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_PAUSE,
            0.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 30), ZONE_ID)
        );

        // 10:45 - 11:00, 50ml/h, 12.5ml
        // 11:00 - 12:00, 50ml/h, 50ml
        // 12:00 - 12:45, 50ml/h, 37.5ml
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_RESUME,
            50.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 10, 45), ZONE_ID)
        );

        // 12:45 - 13:00, 200ml/h, 50ml
        // 13:00 - 13:15, 200ml/h, 50ml
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_ADJUST_SPEED,
            200.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 12, 45), ZONE_ID)
        );

        // 13:00, 50ml
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_FAST_PUSH,
            0.0/*administrationRate*/, "", 50.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 13, 0), ZONE_ID)
        );

        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_COMPLETE,
            0.0/*administrationRate*/, "", 0.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 14, 00), ZONE_ID)
        );

        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
        FluidIntakePB intakePb = resp.getOrderGroup(0).getExeRecord(0).getIntake();
        assertThat(intakePb.getTotalMl()).isEqualTo(300.0);

        assertThat(intakePb.getIntakeRecordCount()).isEqualTo(5);
        assertThat(intakePb.getIntakeRecord(0).getTimeIso8601()).isEqualTo("2024-10-10T09:00+08:00");
        assertThat(intakePb.getIntakeRecord(0).getMl()).isEqualTo(25.0);
        assertThat(intakePb.getIntakeRecord(1).getTimeIso8601()).isEqualTo("2024-10-10T10:00+08:00");
        assertThat(intakePb.getIntakeRecord(1).getMl()).isEqualTo(37.5);
        assertThat(intakePb.getIntakeRecord(2).getTimeIso8601()).isEqualTo("2024-10-10T11:00+08:00");
        assertThat(intakePb.getIntakeRecord(2).getMl()).isEqualTo(50.0);
        assertThat(intakePb.getIntakeRecord(3).getTimeIso8601()).isEqualTo("2024-10-10T12:00+08:00");
        assertThat(intakePb.getIntakeRecord(3).getMl()).isEqualTo(87.5);
        assertThat(intakePb.getIntakeRecord(4).getTimeIso8601()).isEqualTo("2024-10-10T13:00+08:00");
        assertThat(intakePb.getIntakeRecord(4).getMl()).isEqualTo(100.0);
        // intakePb.getRemainingIntake().getTimeIso8601() 为当前时间

        assertThat(intakePb.getRemainingIntake().getTimeIso8601()).isEqualTo("2024-10-10T13:15+08:00");
        assertThat(intakePb.getRemainingIntake().getMl()).isEqualTo(0.0);
    }

    @Test
    public void testIntakeVolume_Bolus() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(305L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_315 (ROUTE5: isContinuous = true)
        //    order_id_317: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_317", patient.getHisPatientId(), "group_id_315", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE5/*route_code*/, ROUTE5/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);

        // 9:20, 20ml
        Long exeRecordId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_FAST_PUSH,
            0.0/*administrationRate*/, "", 20.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 20), ZONE_ID)
        );

        // 9:40, 20ml
        exeRecordId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_FAST_PUSH,
            0.0/*administrationRate*/, "", 20.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 40), ZONE_ID)
        );

        // 9:50, 20ml
        exeRecordId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        ordExecutor.addExeAction(
            accountId, accountId, exeRecordId, ACTION_TYPE_COMPLETE,
            0.0/*administrationRate*/, "", 20.0/*intakeVolMl*/,
            TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2024, 10, 10, 9, 50), ZONE_ID)
        );

        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);

        FluidIntakePB intakePb = resp.getOrderGroup(0).getExeRecord(0).getIntake();
        assertThat(intakePb.getTotalMl()).isEqualTo(100.0);
        assertThat(intakePb.getIntakeRecordCount()).isEqualTo(1);
        assertThat(intakePb.getIntakeRecord(0).getTimeIso8601()).isEqualTo("2024-10-10T09:00+08:00");
        assertThat(intakePb.getIntakeRecord(0).getMl()).isEqualTo(60.0);
        // intakePb.getRemainingIntake().getTimeIso8601() 为当前时间
        assertThat(intakePb.getRemainingIntake().getMl()).isEqualTo(40.0);
    }

    // 获取单条医嘱记录
    @Test
    public void testGetOrderGroup() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(306L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_316 (ROUTE4: isContinuous = true)
        //    order_id_318: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_318", patient.getHisPatientId(), "group_id_316", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_319");
        medOrd.setGroupId("group_id_317");
        medOrd.setOrderCode("med_code_2");
        medOrd.setOrderName("med_name_2");
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(2);
        final Long medOrderGroupId1 = resp.getOrderGroup(0).getMedOrderGroup().getId();
        final Long medOrderGroupId2 = resp.getOrderGroup(0).getMedOrderGroup().getId();

        GetOrderGroupReq getOrderGroupReq = GetOrderGroupReq.newBuilder()
            .setMedOrderGroupId(medOrderGroupId1)
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 8, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 15, 0), ZONE_ID))
            .build();
        String getOrderGroupReqJson = ProtoUtils.protoToJson(getOrderGroupReq);
        GetOrderGroupResp resp2 = medService.getOrderGroup(getOrderGroupReqJson);
        assertThat(resp2.getOrderGroup().getMedOrderGroup().getId()).isEqualTo(medOrderGroupId1);

        getOrderGroupReq = getOrderGroupReq.toBuilder().setMedOrderGroupId(medOrderGroupId2).build();
        getOrderGroupReqJson = ProtoUtils.protoToJson(getOrderGroupReq);
        resp2 = medService.getOrderGroup(getOrderGroupReqJson);
        assertThat(resp2.getOrderGroup().getMedOrderGroup().getId()).isEqualTo(medOrderGroupId2);

        getOrderGroupReq = getOrderGroupReq.toBuilder().setMedOrderGroupId(1000000L /*测试环境中不可能插入100w条记录*/).build();
        getOrderGroupReqJson = ProtoUtils.protoToJson(getOrderGroupReq);
        resp2 = medService.getOrderGroup(getOrderGroupReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.ORDER_GROUP_NOT_FOUND.ordinal());
    }

    @Test
    public void testNewOrderGroup() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(307L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // 补录医嘱
        String newOrderGroupReqJson = String.format(
            "{\"department_id\": \"%s\", " +
             "\"patient_id\": %d, " +
             "\"dosageGroup\": { " +
                "\"md\": [{ " +
                    "\"code\": \"med_code_1\", " +
                    "\"name\": \"med_name_1\", " +
                    "\"spec\": \"100ml:mg\", " +
                    "\"dose\": 1.0, " +
                    "\"doseUnit\": \"mg\", " +
                    "\"intakeVolMl\": 100.0 " +
                "}], " +
                "\"displayName\": \"med_name_1\" " +
             "}, " +
             "\"administration_route_code\": \"%s\", " +
             "\"administration_route_name\": \"%s\", " +
             "\"order_time_iso8601\": \"2024-10-10T15:00:00+08:00\"}",
            deptId,
            patient.getId(),
            ROUTE4,
            ROUTE4
        );
        NewOrderGroupResp resp = medService.newOrderGroup(newOrderGroupReqJson);
        OrderGroupPB orderGroupPb = resp.getOrderGroup();
        assertThat(orderGroupPb.getMedOrderGroup().getHisPatientId()).isEqualTo(patient.getHisPatientId());
        assertThat(orderGroupPb.getMedOrderGroup().getOrderValidity()).isEqualTo(VALIDITY_TYPE_MANUAL_ENTRY);
        assertThat(orderGroupPb.getMedOrderGroup().getOrderDurationType()).isEqualTo(DURATION_TYPE_MANUAL_ENTRY);
        assertThat(orderGroupPb.getMedOrderGroup().getAdministrationRouteCode()).isEqualTo(ROUTE4);
    }

    // 获取单条医嘱记录
    @Test
    public void testAddOrderExeAction() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(308L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_318 (ROUTE4: isContinuous = true)
        //    order_id_320: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_320", patient.getHisPatientId(), "group_id_318", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);

        // 新增执行过程
        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        String addOrderExeActionReqJson = String.format(
            "{\"med_exe_rec_id\": %d, \"action_type\": %d, \"administration_rate\": %f," +
            " \"intake_vol_ml\": %f, \"created_at_iso8601\": \"2024-10-10T15:00:00+08:00\"}",
            medExeRecId, ACTION_TYPE_START, 10.0, 0.0
        );
        AddOrderExeActionResp resp2 = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp2.getExeRecord().getMedExeActionCount()).isEqualTo(1);
        assertThat(resp2.getExeRecord().getMedExeAction(0).getActionType()).isEqualTo(ACTION_TYPE_START);

        addOrderExeActionReqJson = String.format(
            "{\"med_exe_rec_id\": %d, \"action_type\": %d, \"administration_rate\": %f," +
            " \"intake_vol_ml\": %f, \"created_at_iso8601\": \"2024-10-10T15:10:00+08:00\"}",
            medExeRecId, ACTION_TYPE_START, 10.0, 0.0
        );
        resp2 = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.ACTION_TYPE_NOT_QUALIFIED.ordinal());

        addOrderExeActionReqJson = String.format(
            "{\"med_exe_rec_id\": %d, \"action_type\": %d, \"administration_rate\": %f," +
            " \"intake_vol_ml\": %f, \"created_at_iso8601\": \"2024-10-10T15:20:00+08:00\"}",
            medExeRecId, ACTION_TYPE_PAUSE, 0.0, 0.0
        );
        resp2 = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp2.getExeRecord().getMedExeActionCount()).isEqualTo(2);
        assertThat(resp2.getExeRecord().getMedExeAction(0).getActionType()).isEqualTo(ACTION_TYPE_START);
        assertThat(resp2.getExeRecord().getMedExeAction(1).getActionType()).isEqualTo(ACTION_TYPE_PAUSE);
    }

    // 删除单条执行过程
    @Test
    public void testDelOrderExeAction() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(309L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_319 (ROUTE4: isContinuous = true)
        //    order_id_321: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_321", patient.getHisPatientId(), "group_id_319", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);

        // 新增执行过程
        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        String addOrderExeActionReqJson = String.format(
            "{\"med_exe_rec_id\": %d, \"action_type\": %d, \"administration_rate\": %f," +
            " \"intake_vol_ml\": %f, \"created_at_iso8601\": \"2024-10-10T15:00:00+08:00\"}",
            medExeRecId, ACTION_TYPE_START, 60.0, 0.0
        );
        AddOrderExeActionResp resp2 = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp2.getExeRecord().getMedExeActionCount()).isEqualTo(1);
        assertThat(resp2.getExeRecord().getMedExeAction(0).getActionType()).isEqualTo(ACTION_TYPE_START);

        // 暂停
        addOrderExeActionReqJson = String.format(
            "{\"med_exe_rec_id\": %d, \"action_type\": %d, \"administration_rate\": %f," +
            " \"intake_vol_ml\": %f, \"created_at_iso8601\": \"2024-10-10T15:30:00+08:00\"}",
            medExeRecId, ACTION_TYPE_PAUSE, 0.0, 0.0
        );
        resp2 = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp2.getExeRecord().getMedExeActionCount()).isEqualTo(2);
        assertThat(resp2.getExeRecord().getMedExeAction(0).getActionType()).isEqualTo(ACTION_TYPE_START);
        assertThat(resp2.getExeRecord().getMedExeAction(1).getActionType()).isEqualTo(ACTION_TYPE_PAUSE);

        // 删除暂停
        String delOrderExeActionReqJson = String.format(
            "{\"med_exe_action_id\": %d, \"delete_time_iso8601\": \"2024-10-10T15:40:00+08:00\"}",
            resp2.getExeRecord().getMedExeAction(1).getId());
        DelOrderExeActionResp resp3 = medService.delOrderExeAction(delOrderExeActionReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());


        // GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
        //     .setPatientId(patient.getId())
        //     .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
        //     .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
        //     .setExpandExeRecord(true)
        //     .build();
        // String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
    }

    @Test
    public void testActionsAndRecords() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(313L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_324 (ROUTE4: isContinuous = true)
        //    order_id_326: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_326", patient.getHisPatientId(), "group_id_324", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getExeRecordCount()).isEqualTo(1);

        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        MedicationExecutionRecord medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNull();
        assertThat(medExeRec.getEndTime()).isNull();

        // 新增执行过程start (deptId + ROUTE4)
        LocalDateTime actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 16, 10), ZONE_ID);
        AddOrderExeActionReq addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_START)
            .setAdministrationRate(60.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        String addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        AddOrderExeActionResp addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNotNull();
        assertThat(medExeRec.getStartTime()).isEqualTo(actionStartTime);
        assertThat(medExeRec.getEndTime()).isNull();

        // 新增执行过程COMPLETE失败（在start之前）
        LocalDateTime actionEndTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 16, 0), ZONE_ID);
        addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_COMPLETE)
            .setAdministrationRate(0.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionEndTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.ACTION_TIME_BEFORE_LAST_ACTION_TIME.ordinal());

        // 新增执行过程COMPLETE
        actionEndTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 17, 40), ZONE_ID);
        addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_COMPLETE)
            .setAdministrationRate(0.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionEndTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(addOrderExeActionResp.getExeRecord().getMedExeActionCount()).isEqualTo(2);
        final Long startMedExeActionId = addOrderExeActionResp.getExeRecord().getMedExeAction(0).getId();
        final Long completeMedExeActionId = addOrderExeActionResp.getExeRecord().getMedExeAction(1).getId();

        medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNotNull();
        assertThat(medExeRec.getStartTime()).isEqualTo(actionStartTime);
        assertThat(medExeRec.getEndTime()).isNotNull();
        assertThat(medExeRec.getEndTime()).isEqualTo(actionEndTime);

        // 删除执行过程COMPLETE
        LocalDateTime delTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 18, 0), ZONE_ID);
        DelOrderExeActionReq delOrderExeActionReq = DelOrderExeActionReq.newBuilder()
            .setMedExeActionId(completeMedExeActionId)
            .setDeleteTimeIso8601(TimeUtils.toIso8601String(delTime, ZONE_ID))
            .build();
        String delOrderExeActionReqJson = ProtoUtils.protoToJson(delOrderExeActionReq);
        DelOrderExeActionResp delOrderExeActionResp = medService.delOrderExeAction(delOrderExeActionReqJson);
        assertThat(delOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNotNull();
        assertThat(medExeRec.getStartTime()).isEqualTo(actionStartTime);
        assertThat(medExeRec.getEndTime()).isNull();

        // 删除执行过程start
        delTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 18, 10), ZONE_ID);
        delOrderExeActionReq = DelOrderExeActionReq.newBuilder()
            .setMedExeActionId(startMedExeActionId)
            .setDeleteTimeIso8601(TimeUtils.toIso8601String(delTime, ZONE_ID))
            .build();
        delOrderExeActionReqJson = ProtoUtils.protoToJson(delOrderExeActionReq);
        delOrderExeActionResp = medService.delOrderExeAction(delOrderExeActionReqJson);
        assertThat(delOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNull();
        assertThat(medExeRec.getEndTime()).isNull();
    }

    // 持续用药类型的医嘱，剩余量为0
    // - 最后一个动作是快推
    // - 自然结束
    @Test
    public void testAddActionWithZeroRemaining1() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(314L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_325 (ROUTE4: isContinuous = true)
        //    order_id_327: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getLocalTime(2024, 10, 9, 1, 0);  // shanghai: 20241009 09:00
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_327", patient.getHisPatientId(), "group_id_325", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(
                TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 0, 0), ZONE_ID)
            )
            .setQueryEndIso8601(
                TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 11, 0, 0), ZONE_ID)
            )
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getExeRecordCount()).isEqualTo(1);

        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        MedicationExecutionRecord medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNull();
        assertThat(medExeRec.getEndTime()).isNull();

        // 新增执行过程start, 60ml/hour
        LocalDateTime actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID);
        AddOrderExeActionReq addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_START)
            .setAdministrationRate(60.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        String addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        AddOrderExeActionResp addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增执行过程fast push, 50ml - 过度了
        actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 10, 0), ZONE_ID);
        addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_FAST_PUSH)
            .setAdministrationRate(0.0)
            .setIntakeVolMl(50.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.INTAKE_LIQUID_MORE_THAN_REMAINING.ordinal());

        // 如果自然结束，查看对应的剩余时间
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        FluidIntakePB.IntakeRecord intakeRec = resp.getOrderGroup(0).getExeRecord(0)
            .getIntake().getRemainingIntake();
        assertThat(intakeRec.getTimeIso8601()).isEqualTo(
            TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 2, 40), ZONE_ID) // 24-10-10 10:40 (shanghai)
        );
        assertThat(intakeRec.getMl()).isEqualTo(0);

        // 新增执行过程fast push, 40ml - 刚好
        addOrderExeActionReq = addOrderExeActionReq.toBuilder().setIntakeVolMl(40.0).build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 获取执行记录，以及对应的剩余时间
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        intakeRec = resp.getOrderGroup(0).getExeRecord(0)
            .getIntake().getRemainingIntake();
        assertThat(intakeRec.getTimeIso8601()).isEqualTo(
            TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 2, 0), ZONE_ID) // 24-10-10 10:00 (shanghai)
        );
        assertThat(intakeRec.getMl()).isEqualTo(0);
    }

    // 单次用药类型的医嘱，剩余量为0
    // - 最后一个动作是快推
    @Test
    public void testAddActionWithZeroRemaining2() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(315L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_326 (ROUTE5: isContinuous = false)
        //    order_id_328: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getLocalTime(2024, 10, 9, 1, 0);  // shanghai: 20241009 09:00
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_328", patient.getHisPatientId(), "group_id_326", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE5/*route_code*/, ROUTE5/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(
                TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 8, 0), ZONE_ID)
            )
            .setQueryEndIso8601(
                TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 11, 8, 0), ZONE_ID)
            )
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getExeRecordCount()).isEqualTo(1);

        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        MedicationExecutionRecord medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNull();
        assertThat(medExeRec.getEndTime()).isNull();

        // 新增执行过程fast push, 50ml
        LocalDateTime actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID);
        AddOrderExeActionReq addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_FAST_PUSH)
            .setAdministrationRate(0.0)
            .setIntakeVolMl(50.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();;
        String addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        AddOrderExeActionResp addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 在推50ml， 刚好100
        actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 9, 30), ZONE_ID);
        addOrderExeActionReq = addOrderExeActionReq.toBuilder()
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增执行过程fast push, 50ml - 过度了
        actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 10, 0), ZONE_ID);
        addOrderExeActionReq = addOrderExeActionReq.toBuilder()
            .setIntakeVolMl(50.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.INTAKE_LIQUID_MORE_THAN_REMAINING.ordinal());

        // 新增执行过程complete, 50ml - 过度了
        actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 10, 30), ZONE_ID);
        addOrderExeActionReq = addOrderExeActionReq.toBuilder()
            .setActionType(ACTION_TYPE_COMPLETE)
            .setIntakeVolMl(50.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.INTAKE_LIQUID_MORE_THAN_REMAINING.ordinal());

        // 获取执行记录，以及对应的剩余时间
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        FluidIntakePB.IntakeRecord intakeRec = resp.getOrderGroup(0).getExeRecord(0)
            .getIntake().getRemainingIntake();
        assertThat(intakeRec.getTimeIso8601()).isEqualTo(
            TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 30), ZONE_ID) // 24-10-10 9:30 (shanghai)
        );
        assertThat(intakeRec.getMl()).isEqualTo(0);
    }

    // 持续用药类型的医嘱
    // - 总药量100ml
    // - 60ml/hour 用药一小时
    // - 暂停
    // - 统计验证remainingIntake
    // - 结束
    // - 统计验证remainingIntake
    @Test
    public void testAddActionWithNonZeroRemaining1() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(316L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_327 (ROUTE4: isContinuous = true)
        //    order_id_329: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getLocalTime(2024, 10, 9, 1, 0);  // shanghai: 20241009 09:00
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_329", patient.getHisPatientId(), "group_id_327", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(
                TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 0, 0), ZONE_ID)
            )
            .setQueryEndIso8601(
                TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 11, 0, 0), ZONE_ID)
            )
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getExeRecordCount()).isEqualTo(1);

        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        MedicationExecutionRecord medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNull();
        assertThat(medExeRec.getEndTime()).isNull();

        // 新增执行过程start, 60ml/hour
        LocalDateTime actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID);
        AddOrderExeActionReq addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_START)
            .setAdministrationRate(60.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        String addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        AddOrderExeActionResp addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增执行过程pause
        actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 10, 0), ZONE_ID);
        addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_PAUSE)
            .setAdministrationRate(0.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 获取执行记录，以及对应的剩余时间
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        FluidIntakePB.IntakeRecord intakeRec = resp.getOrderGroup(0).getExeRecord(0)
            .getIntake().getRemainingIntake();
        assertThat(intakeRec.getTimeIso8601()).isEqualTo(
            TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 2, 0), ZONE_ID) // 24-10-10 10:00 (shanghai)
        );
        assertThat(intakeRec.getMl()).isEqualTo(40.0);

        // 新增执行过程complete
        actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 10, 30), ZONE_ID);
        addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_COMPLETE)
            .setAdministrationRate(0.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 获取执行记录，以及对应的剩余时间
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        intakeRec = resp.getOrderGroup(0).getExeRecord(0)
            .getIntake().getRemainingIntake();
        assertThat(intakeRec.getTimeIso8601()).isEqualTo(
            TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 2, 30), ZONE_ID) // 24-10-10 10:30 (shanghai)
        );
        assertThat(intakeRec.getMl()).isEqualTo(40.0);
    }

    // 持续用药类型的医嘱
    // - 总药量100ml
    // - 30ml/hour 用药一小时
    // - 快推20ml
    // - 用药一小时
    // - 结束
    // - 统计验证remainingIntake (20ml, 时间为结束时间)
    @Test
    public void testAddActionWithNonZeroRemaining2() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(317L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_328 (ROUTE4: isContinuous = true)
        //    order_id_330: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getLocalTime(2024, 10, 9, 1, 0);  // shanghai: 20241009 09:00
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_330", patient.getHisPatientId(), "group_id_328", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(
                TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 0, 0), ZONE_ID)
            )
            .setQueryEndIso8601(
                TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 11, 0, 0), ZONE_ID)
            )
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getExeRecordCount()).isEqualTo(1);

        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        MedicationExecutionRecord medExeRec = medExeRecRepo.findById(medExeRecId).orElse(null);
        assertThat(medExeRec).isNotNull();
        assertThat(medExeRec.getStartTime()).isNull();
        assertThat(medExeRec.getEndTime()).isNull();

        // 新增执行过程start, 30ml/hour
        LocalDateTime actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID);
        AddOrderExeActionReq addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_START)
            .setAdministrationRate(30.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        String addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        AddOrderExeActionResp addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增执行过程fast_push, 20ml
        actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 10, 0), ZONE_ID);
        addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_FAST_PUSH)
            .setAdministrationRate(0.0)
            .setIntakeVolMl(20.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增执行过程complete
        actionStartTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 11, 0), ZONE_ID);
        addOrderExeActionReq = AddOrderExeActionReq.newBuilder()
            .setMedExeRecId(medExeRecId)
            .setActionType(ACTION_TYPE_COMPLETE)
            .setAdministrationRate(0.0)
            .setIntakeVolMl(0.0)
            .setCreatedAtIso8601(TimeUtils.toIso8601String(actionStartTime, ZONE_ID))
            .build();
        addOrderExeActionReqJson = ProtoUtils.protoToJson(addOrderExeActionReq);
        addOrderExeActionResp = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(addOrderExeActionResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 获取执行记录，以及对应的剩余时间
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        FluidIntakePB.IntakeRecord intakeRec = resp.getOrderGroup(0).getExeRecord(0)
            .getIntake().getRemainingIntake();
        assertThat(intakeRec.getTimeIso8601()).isEqualTo(
            TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 3, 0), ZONE_ID) // 24-10-10 11:00 (shanghai)
        );
        assertThat(intakeRec.getMl()).isEqualTo(20.0);
    }

    // 更新执行记录
    @Test
    public void testUpdateExeRecord() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(310L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_320 (ROUTE4: isContinuous = true)
        //    order_id_322: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_322", patient.getHisPatientId(), "group_id_320", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);

        // 新增执行过程
        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        String addOrderExeActionReqJson = String.format(
            "{\"med_exe_rec_id\": %d, \"action_type\": %d, \"administration_rate\": %f," +
            " \"intake_vol_ml\": %f, \"created_at_iso8601\": \"2024-10-10T15:00:00+08:00\"}",
            medExeRecId, ACTION_TYPE_START, 60.0, 0.0
        );
        AddOrderExeActionResp resp2 = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp2.getExeRecord().getMedExeActionCount()).isEqualTo(1);
        assertThat(resp2.getExeRecord().getMedExeAction(0).getActionType()).isEqualTo(ACTION_TYPE_START);
        final Long medExeActionId = resp2.getExeRecord().getMedExeAction(0).getId();

        // spec: 100ml:mg => 200ml:mg
        String updateExeRecordReqJson = String.format(
            "{\"med_exe_rec_id\": %d, " +
            "\"dosage_group\":{\"md\":[{\"code\":\"med_code_1\",\"name\":\"med_name_1\",\"spec\":\"200ml:mg\",\"dose\":1.0,\"doseUnit\":\"mg\",\"intakeVolMl\":200.0}],\"displayName\":\"med_name_1\"}, " +
            "\"update_time_iso8601\": \"2024-10-10T15:00:00+08:00\"}",
            medExeRecId
        );

        // 更新执行记录失败
        GenericResp resp3 = medService.updateExeRecord(updateExeRecordReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.EXECUTION_RECORD_HAS_ACTIONS.ordinal());

        // 删除执行过程
        String delOrderExeActionReqJson = String.format(
            "{\"med_exe_action_id\": %d, \"delete_time_iso8601\": \"2024-10-10T15:40:00+08:00\"}",
            medExeActionId);
        DelOrderExeActionResp resp4 = medService.delOrderExeAction(delOrderExeActionReqJson);
        assertThat(resp4.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 更新执行记录成功
        resp3 = medService.updateExeRecord(updateExeRecordReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 检查最终结果
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getDosageGroup().getMd(0).getSpec()).isEqualTo("200ml:mg");
    }

    // 删除执行记录
    @Test
    public void testDelExeRecord() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(311L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_321 (ROUTE4: isContinuous = true)
        //    order_id_323: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_323", patient.getHisPatientId(), "group_id_321", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);

        // 新增执行过程
        final Long medExeRecId = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        String addOrderExeActionReqJson = String.format(
            "{\"med_exe_rec_id\": %d, \"action_type\": %d, \"administration_rate\": %f," +
            " \"intake_vol_ml\": %f, \"created_at_iso8601\": \"2024-10-10T15:00:00+08:00\"}",
            medExeRecId, ACTION_TYPE_START, 60.0, 0.0
        );
        AddOrderExeActionResp resp2 = medService.addOrderExeAction(addOrderExeActionReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp2.getExeRecord().getMedExeActionCount()).isEqualTo(1);
        assertThat(resp2.getExeRecord().getMedExeAction(0).getActionType()).isEqualTo(ACTION_TYPE_START);
        final Long medExeActionId = resp2.getExeRecord().getMedExeAction(0).getId();

        String delExeRecordReqJson = String.format(
            "{\"med_exe_rec_id\": %d, " +
            "\"delete_time_iso8601\": \"2024-10-10T15:00:00+08:00\", " +
            "\"delete_reason\": \"123\"}",
            medExeRecId
        );

        // 删除执行记录失败
        GenericResp resp3 = medService.delExeRecord(delExeRecordReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.EXECUTION_RECORD_HAS_ACTIONS.ordinal());

        // 删除执行过程
        String delOrderExeActionReqJson = String.format(
            "{\"med_exe_action_id\": %d, \"delete_time_iso8601\": \"2024-10-10T15:40:00+08:00\"}",
            medExeActionId);
        DelOrderExeActionResp resp4 = medService.delOrderExeAction(delOrderExeActionReqJson);
        assertThat(resp4.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 删除执行记录成功
        resp3 = medService.delExeRecord(delExeRecordReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 检查最终结果
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(0);
    }

    // 恢复删除执行记录
    @Test
    public void testRevertDeletedExeRecord() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(312L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_322 (ROUTE4: isContinuous = true)
        //    order_id_324: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        // group_id_323 (ROUTE4: isContinuous = true)
        //    order_id_325: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_324", patient.getHisPatientId(), "group_id_322", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        medOrd.setOrderId("order_id_325");
        medOrd.setGroupId("group_id_323");
        medOrd.setOrderCode("med_code_2");
        medOrd.setOrderName("med_name_2");
        medOrd = medOrdRepo.save(medOrd);

        // 生成执行记录
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(2);
        assertThat(resp.getOrderGroup(0).getExeRecordCount()).isEqualTo(1);
        assertThat(resp.getOrderGroup(1).getExeRecordCount()).isEqualTo(1);

        // 删除执行记录
        final Long medExeRecId1 = resp.getOrderGroup(0).getExeRecord(0).getMedExeRec().getId();
        final Long medExeRecId2 = resp.getOrderGroup(1).getExeRecord(0).getMedExeRec().getId();
        String delExeRecordReqJson = String.format(
            "{\"med_exe_rec_id\": %d, " +
            "\"delete_time_iso8601\": \"2024-10-10T15:10:00+08:00\", " +
            "\"delete_reason\": \"123\"}",
            medExeRecId1
        );
        GenericResp resp2 = medService.delExeRecord(delExeRecordReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        delExeRecordReqJson = String.format(
            "{\"med_exe_rec_id\": %d, " +
            "\"delete_time_iso8601\": \"2024-10-10T15:10:00+08:00\", " +
            "\"delete_reason\": \"123\"}",
            medExeRecId2
        );
        resp2 = medService.delExeRecord(delExeRecordReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询执行记录-1
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(0);

        // 查询可恢复的删除记录-1
        String getDeletedExeRecordsReqJson = String.format(
            "{\"patient_id\": %d, \"retrieve_time_iso8601\": \"2024-10-10T15:00:00+08:00\"}",
            patient.getId()
        );
        GetDeletedExeRecordsResp resp3 = medService.getDeletedExeRecords(getDeletedExeRecordsReqJson);
        assertThat(resp3.getDeletedRecordCount()).isEqualTo(2);

        // 恢复执行记录-1
        String revertDeletedExeRecordReqJson = String.format(
            "{\"med_exe_rec_id\": %d}",
            medExeRecId1
        );
        GenericResp resp4 = medService.revertDeletedExeRecord(revertDeletedExeRecordReqJson);
        assertThat(resp4.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询可恢复的删除记录-2
        resp3 = medService.getDeletedExeRecords(getDeletedExeRecordsReqJson);
        assertThat(resp3.getDeletedRecordCount()).isEqualTo(1);

        // 恢复执行记录-2
        revertDeletedExeRecordReqJson = String.format(
            "{\"med_exe_rec_id\": %d}",
            medExeRecId2
        );
        resp4 = medService.revertDeletedExeRecord(revertDeletedExeRecordReqJson);
        assertThat(resp4.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询可恢复的删除记录-2
        resp3 = medService.getDeletedExeRecords(getDeletedExeRecordsReqJson);
        assertThat(resp3.getDeletedRecordCount()).isEqualTo(0);
    }

    @Test
    public void testExpandExeRecord() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID);
        PatientRecord patient = patientTestUtils.newPatientRecord(318L, 1 /*admission_status_in_icu*/, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRepo.save(patient);

        // His医嘱
        // group_id_330 (ROUTE4: isContinuous = true)
        //    order_id_334: "med_code_1", FREQ_CODE_ONCE, 100ml:mg, 1.0mg
        final LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 9, 0), ZONE_ID);
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_334", patient.getHisPatientId(), "group_id_330", "doctor_1",
            deptId, "西药", "已审核", orderTime /*order_time*/,
            "med_code_1", "med_name_1", "100ml:mg", 1.0, "mg",
            0 /*长期医嘱*/, orderTime /*plan_time*/, FREQ_CODE_ONCE,
            0 /* 首日分解次数 */, ROUTE4/*route_code*/, ROUTE4/*route_name*/,
            "reviewer_1", orderTime/*reviewer_time*/, orderTime/*created_at*/);
        medOrd = medOrdRepo.save(medOrd);

        // 查询执行记录(expand_exe_record = false)
        GetOrderGroupsReq getOrderGroupsReq = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 1, 0), ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(TimeUtils.getLocalTime(2024, 10, 10, 9, 0), ZONE_ID))
            .setExpandExeRecord(false)
            .build();
        String getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        GetOrderGroupsResp resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(0);

        // 查询执行记录(expand_exe_record = true)
        getOrderGroupsReq = getOrderGroupsReq.toBuilder()
            .setExpandExeRecord(true)
            .build();
        getOrderGroupsReqJson = ProtoUtils.protoToJson(getOrderGroupsReq);
        resp = medService.getOrderGroups(getOrderGroupsReqJson);
        assertThat(resp.getOrderGroupList()).hasSize(1);
        assertThat(resp.getOrderGroup(0).getExeRecordCount()).isEqualTo(1);
    }

    @Test
    public void testLookupFreq() {
        LookupFreqReq req = LookupFreqReq.newBuilder().setOnlyNursingOrderFreq(1).build();
        String reqJson = ProtoUtils.protoToJson(req);
        LookupFreqResp resp = medService.lookupFreq(reqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getFreqList()).hasSize(4);
    }

    @Transactional
    private void initRoutes() {
        AdministrationRoute route = medTestUtils.newAdministrationRoute(
            deptId, ROUTE1/*code*/, ROUTE1/*name*/, true/*isContinuous*/,
            ROUTE_GROUP_PUMP/*groupId*/, INTAKE_TYPE_INTRAVENOUS/*intakeTypeId*/, true/*isValid*/
        );
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE1).isPresent()) {
            routeRepo.save(route);
        }

        route.setId(null);  // reset ID for new route
        route.setCode(ROUTE3);
        route.setName(ROUTE3);
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE3).isPresent()) {
            routeRepo.save(route);
        }

        route.setId(null);  // reset ID for new route
        route.setCode(ROUTE2);
        route.setName(ROUTE2);
        route.setGroupId(ROUTE_GROUP_OTHERS);
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE2).isPresent()) {
            routeRepo.save(route);
        }

        route.setId(null);  // reset ID for new route
        route.setCode(ROUTE4);
        route.setName(ROUTE4);
        route.setIntakeTypeId(INTAKE_TYPE_INTRAVENOUS);
        route.setIsContinuous(true);
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE4).isPresent()) {
            routeRepo.save(route);
        }

        route.setId(null);  // reset ID for new route
        route.setCode(ROUTE5);
        route.setName(ROUTE5);
        route.setIntakeTypeId(INTAKE_TYPE_INTRAVENOUS);
        route.setIsContinuous(false);
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE5).isPresent()) {
            routeRepo.save(route);
        }

        medConfig.initialize();
        medConfig.refresh();
    }

    private void initMedOrderGroupSettings() {
        MedOrderGroupSettingsPB medOgSettings = medTestUtils.newMedOrderGroupSettings(
            new ArrayList<>(List.of("西药", "中药")) /*allowedOrderTypes*/,
            new ArrayList<>(List.of("未审核")) /*denyStatuses*/,
            new ArrayList<>(List.of("不允许的口服")) /*denyRouteCodes*/,
            false /*omitFirstDayOrderExecution*/,
            false /*checkOrderTimeForLongTermExe*/,
            false /*forceGenExeOrdDay1*/,
            false /*checkOrderTimeForOneTimeExe*/,
            ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE /*oneTimeExeStopStrategy*/,
            0 /* notStartedExeRecAdvanceHours */,
            "已取消" /* status_canceled_txt */,
            new ArrayList<>(List.of("氯化钠")) /*deprioritizedMedNames*/
        );
        medConfig.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
    }

    private final String accountId;
    private final String deptId;
    private final String ZONE_ID;

    private final String FREQ_CODE_ONCE;
    private final String FREQ_CODE_TID;
    private final Integer ROUTE_GROUP_PUMP;
    private final Integer ROUTE_GROUP_OTHERS;
    private final Integer INTAKE_TYPE_INTRAVENOUS;
    private final Integer ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE;
    private final Integer ACTION_TYPE_START;
    private final Integer ACTION_TYPE_PAUSE;
    private final Integer ACTION_TYPE_RESUME;
    private final Integer ACTION_TYPE_ADJUST_SPEED;
    private final Integer ACTION_TYPE_FAST_PUSH;
    private final Integer ACTION_TYPE_COMPLETE;
    private final Integer VALIDITY_TYPE_MANUAL_ENTRY;
    private final Integer DURATION_TYPE_MANUAL_ENTRY;
    private final String ROUTE1;
    private final String ROUTE2;
    private final String ROUTE3;
    private final String ROUTE4;  // continuous, to test volume and next actions calculation.
    private final String ROUTE5;  // bolus, to test volume and next actions calculation.
    private final String ORDER_GROUP_STATUS_NOT_STARTED_TXT;
    private final String ORDER_GROUP_STATUS_IN_PROGRESS_TXT;
    private final String ORDER_GROUP_STATUS_COMPLETED_TXT;

    private MedicationConfig medConfig;
    private MedicationDictionary medDict;
    private MedicationService medService;
    private OrderExecutor ordExecutor;

    private PatientRecordRepository patientRepo;
    private MedicalOrderRepository medOrdRepo;
    private AdministrationRouteRepository routeRepo;
    private MedicationOrderGroupRepository medOrdGroupRepo;
    private MedicationExecutionRecordRepository medExeRecRepo;
    private MedicationExecutionActionRepository medExeActRepo;

    private MedicationTestUtils medTestUtils;
    private PatientTestUtils patientTestUtils;
}