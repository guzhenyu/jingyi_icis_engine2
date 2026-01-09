package com.jingyicare.jingyi_icis_engine.service.ah2pagedata;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.medications.AdministrationRoute;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionAction;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecord;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecordStat;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationOrderGroup;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.repository.medications.AdministrationRouteRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicalOrderRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionActionRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionRecordStatRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationFrequencyRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationOrderGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordStatsDailyRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.reports.PatientNursingReportRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationConfig;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.testutils.MedicationTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.PatientTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetOrderGroupsReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetOrderGroupsResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.NewOrderGroupResp;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MEnums;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedOrderGroupSettingsPB;

public class MedicationPageDataTests extends TestsBase {
    public MedicationPageDataTests(
        @Autowired ConfigProtoService protoService,
        @Autowired MedicationConfig medConfig,
        @Autowired MedicationService medService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired MedicationRepository medRepo,
        @Autowired MedicationOrderGroupRepository medOrdGroupRepo,
        @Autowired MedicationExecutionRecordRepository medExeRecRepo,
        @Autowired MedicationExecutionRecordStatRepository medExeRecStatRepo,
        @Autowired MedicationExecutionActionRepository medExeActRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired MedicalOrderRepository medOrdRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired PatientMonitoringRecordRepository monitoringRecordRepo,
        @Autowired PatientMonitoringRecordStatsDailyRepository monitoringStatsRepo,
        @Autowired PatientNursingReportRepository pnrRepo,
        @Autowired MedicationFrequencyRepository freqRepo
    ) {
        this.accountId = "admin";
        this.deptId = "10043";
        this.ZONE_ID = protoService.getConfig().getZoneId();

        this.FREQ_CODE_ONCE = protoService.getConfig().getMedication().getFreqSpec().getOnceCode();
        this.FREQ_CODE_TID = MedicationTestUtils.FREQ_CODE_TID;

        MEnums medEnums = protoService.getConfig().getMedication().getEnums();
        this.ROUTE_GROUP_PUMP = medEnums.getAdministrationRouteGroupInfusionPump().getId();
        this.ROUTE_GROUP_OTHERS = medEnums.getAdministrationRouteGroupOthers().getId();
        this.INTAKE_TYPE_INTRAVENOUS = protoService.getConfig().getMedication()
            .getIntakeTypes().getIntakeType(1).getId();
        this.ACTION_TYPE_START = medEnums.getMedicationExecutionActionTypeStart().getId();
        this.ACTION_TYPE_COMPLETE = medEnums.getMedicationExecutionActionTypeComplete().getId();
        this.DURATION_TYPE_LONG_TERM = medEnums.getOrderDurationTypeLongTerm().getId();
        this.DURATION_TYPE_ONE_TIME = medEnums.getOrderDurationTypeOneTime().getId();
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE = medEnums.getOneTimeExecutionStopStrategyIgnore().getId();

        this.ROUTE_PUMP = "ah2_route_pump";
        this.ROUTE_OTHER = "ah2_route_other";

        this.medConfig = medConfig;
        this.medService = medService;
        this.monitoringConfig = monitoringConfig;
        this.medRepo = medRepo;
        this.medOrdGroupRepo = medOrdGroupRepo;
        this.medExeRecRepo = medExeRecRepo;
        this.medExeRecStatRepo = medExeRecStatRepo;
        this.medExeActRepo = medExeActRepo;
        this.routeRepo = routeRepo;
        this.medOrdRepo = medOrdRepo;
        this.patientRepo = patientRepo;
        this.monitoringRecordRepo = monitoringRecordRepo;
        this.monitoringStatsRepo = monitoringStatsRepo;
        this.pnrRepo = pnrRepo;

        this.medTestUtils = new MedicationTestUtils(protoService.getConfig().getMedication(), freqRepo);
        this.patientTestUtils = new PatientTestUtils();

        monitoringConfig.initialize();
        medTestUtils.initFreqRepo();
        initMedOrderGroupSettings();
        initRoutes();
    }

    @Test
    public void testManualOrderGroupNonContinuous() {
        setAuth();

        PatientRecord patient = createPatient(9001L);
        String medCode = "med_ah2_manual_1";

        String newOrderGroupReqJson = String.format(
            "{\"department_id\":\"%s\", " +
             "\"patient_id\":%d, " +
             "\"dosageGroup\":{" +
                "\"md\":[{" +
                    "\"code\":\"%s\"," +
                    "\"name\":\"med_ah2_manual_1\"," +
                    "\"spec\":\"100ml:mg\"," +
                    "\"dose\":1.0," +
                    "\"doseUnit\":\"mg\"," +
                    "\"intakeVolMl\":120.0" +
                "}]," +
                "\"displayName\":\"med_ah2_manual_1\"" +
             "}," +
             "\"administration_route_code\":\"%s\"," +
             "\"administration_route_name\":\"%s\"," +
             "\"order_time_iso8601\":\"2024-10-10T08:00:00+08:00\"," +
             "\"intake_vol_ml\":120.0," +
             "\"finish_time_iso8601\":\"2024-10-10T09:00:00+08:00\"}",
            deptId, patient.getId(), medCode, ROUTE_OTHER, ROUTE_OTHER
        );

        NewOrderGroupResp resp = medService.newOrderGroup(newOrderGroupReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        MedicationExecutionRecord exeRecord = assertManualOrderBase(patient.getId(), medCode);
        List<MedicationExecutionAction> actions = medExeActRepo.findByMedicationExecutionRecordId(exeRecord.getId());
        actions.sort(Comparator.comparing(MedicationExecutionAction::getId));
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).getActionType()).isEqualTo(ACTION_TYPE_COMPLETE);
    }

    @Test
    public void testManualOrderGroupContinuous() {
        setAuth();

        PatientRecord patient = createPatient(9002L);
        String medCode = "med_ah2_manual_2";

        String newOrderGroupReqJson = String.format(
            "{\"department_id\":\"%s\", " +
             "\"patient_id\":%d, " +
             "\"dosageGroup\":{" +
                "\"md\":[{" +
                    "\"code\":\"%s\"," +
                    "\"name\":\"med_ah2_manual_2\"," +
                    "\"spec\":\"100ml:mg\"," +
                    "\"dose\":1.0," +
                    "\"doseUnit\":\"mg\"," +
                    "\"intakeVolMl\":100.0" +
                "}]," +
                "\"displayName\":\"med_ah2_manual_2\"" +
             "}," +
             "\"administration_route_code\":\"%s\"," +
             "\"administration_route_name\":\"%s\"," +
             "\"order_time_iso8601\":\"2024-10-10T08:30:00+08:00\"," +
             "\"administration_rate\":10.0," +
             "\"start_time_iso8601\":\"2024-10-10T09:00:00+08:00\"," +
             "\"finish_time_iso8601\":\"2024-10-10T10:00:00+08:00\"}",
            deptId, patient.getId(), medCode, ROUTE_PUMP, ROUTE_PUMP
        );

        NewOrderGroupResp resp = medService.newOrderGroup(newOrderGroupReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        MedicationExecutionRecord exeRecord = assertManualOrderBase(patient.getId(), medCode);
        List<MedicationExecutionAction> actions = medExeActRepo.findByMedicationExecutionRecordId(exeRecord.getId());
        actions.sort(Comparator.comparing(MedicationExecutionAction::getId));
        assertThat(actions).hasSize(2);
        assertThat(actions.get(0).getActionType()).isEqualTo(ACTION_TYPE_START);
        assertThat(actions.get(1).getActionType()).isEqualTo(ACTION_TYPE_COMPLETE);
    }

    @Test
    public void testHisOrderOnceContinuous() {
        runHisOrderSyncCase(
            9003L,
            "order_id_901",
            "ah2_group_901",
            "med_ah2_his_once_pump",
            ROUTE_PUMP,
            FREQ_CODE_ONCE,
            DURATION_TYPE_ONE_TIME
        );
    }

    @Test
    public void testHisOrderOnceNonContinuous() {
        runHisOrderSyncCase(
            9004L,
            "order_id_902",
            "ah2_group_902",
            "med_ah2_his_once_other",
            ROUTE_OTHER,
            FREQ_CODE_ONCE,
            DURATION_TYPE_ONE_TIME
        );
    }

    @Test
    public void testHisOrderTidContinuous() {
        runHisOrderSyncCase(
            9005L,
            "order_id_903",
            "ah2_group_903",
            "med_ah2_his_tid_pump",
            ROUTE_PUMP,
            FREQ_CODE_TID,
            DURATION_TYPE_LONG_TERM
        );
    }

    @Test
    public void testHisOrderTidNonContinuous() {
        runHisOrderSyncCase(
            9006L,
            "order_id_904",
            "ah2_group_904",
            "med_ah2_his_tid_other",
            ROUTE_OTHER,
            FREQ_CODE_TID,
            DURATION_TYPE_LONG_TERM
        );
    }

    private void runHisOrderSyncCase(
        Long patientId,
        String orderId,
        String groupId,
        String medCode,
        String routeCode,
        String freqCode,
        Integer orderDurationType
    ) {
        setAuth();

        PatientRecord patient = createPatient(patientId);
        LocalDateTime orderTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 10, 8, 0), ZONE_ID
        );

        medOrdRepo.save(medTestUtils.newMedicalOrder(
            orderId, patient.getHisPatientId(), groupId, "doctor_ah2",
            deptId, "西药", "已审核", orderTime,
            medCode, medCode, "100ml:mg", 1.0, "mg",
            orderDurationType, orderTime, freqCode,
            0, routeCode, routeCode,
            "reviewer_ah2", orderTime, orderTime
        ));

        LocalDateTime queryStartLocal = TimeUtils.getLocalTime(2024, 10, 10, 0, 0);
        LocalDateTime queryEndLocal = TimeUtils.getLocalTime(2024, 10, 11, 0, 0);
        GetOrderGroupsReq req = GetOrderGroupsReq.newBuilder()
            .setPatientId(patient.getId())
            .setQueryStartIso8601(TimeUtils.toIso8601String(queryStartLocal, ZONE_ID))
            .setQueryEndIso8601(TimeUtils.toIso8601String(queryEndLocal, ZONE_ID))
            .setExpandExeRecord(true)
            .build();
        GetOrderGroupsResp resp = medService.getOrderGroups(ProtoUtils.protoToJson(req));
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getOrderGroupList()).isNotEmpty();

        LocalDateTime queryStartUtc = TimeUtils.getUtcFromLocalDateTime(queryStartLocal, ZONE_ID);
        LocalDateTime queryEndUtc = TimeUtils.getUtcFromLocalDateTime(queryEndLocal, ZONE_ID);
        assertHisOrderTables(patient.getId(), groupId, medCode, queryStartUtc, queryEndUtc);
    }

    private MedicationExecutionRecord assertManualOrderBase(Long pid, String medCode) {
        assertThat(medRepo.findByCode(medCode)).isPresent();

        List<MedicationOrderGroup> orderGroups = medOrdGroupRepo.findByPatientId(pid);
        assertThat(orderGroups).hasSize(1);

        List<MedicationExecutionRecord> exeRecords = medExeRecRepo.findByPatientId(pid);
        assertThat(exeRecords).hasSize(1);

        MedicationExecutionRecord exeRecord = exeRecords.get(0);
        List<MedicationExecutionRecordStat> stats = medExeRecStatRepo.findByGroupIdAndExeRecordId(
            orderGroups.get(0).getId(), exeRecord.getId()
        );
        assertThat(stats).isNotEmpty();

        assertThat(monitoringRecordRepo.findByPidAndIsDeletedFalse(pid)).isNotEmpty();
        assertThat(monitoringStatsRepo.findByPidAndIsDeletedFalse(pid)).isNotEmpty();
        assertThat(pnrRepo.findByPid(pid)).isNotEmpty();

        return exeRecord;
    }

    private void assertHisOrderTables(
        Long pid,
        String groupId,
        String medCode,
        LocalDateTime queryStartUtc,
        LocalDateTime queryEndUtc
    ) {
        assertThat(medRepo.findByCode(medCode)).isPresent();
        assertThat(medOrdGroupRepo.findByPatientIdAndGroupId(pid, groupId)).hasSize(1);

        List<MedicationExecutionRecord> exeRecords = medExeRecRepo.findByPatientId(pid);
        assertThat(exeRecords).isNotEmpty();
        for (MedicationExecutionRecord exeRecord : exeRecords) {
            assertThat(medExeActRepo.findByMedicationExecutionRecordId(exeRecord.getId())).isEmpty();
        }
        assertThat(medExeRecStatRepo.findAllByPatientIdAndStatsTimeRange(
            pid, queryStartUtc, queryEndUtc)).isEmpty();
        assertThat(monitoringRecordRepo.findByPidAndIsDeletedFalse(pid)).isEmpty();
        assertThat(monitoringStatsRepo.findByPidAndIsDeletedFalse(pid)).isEmpty();
        assertThat(pnrRepo.findByPid(pid)).isEmpty();
    }

    private void setAuth() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private PatientRecord createPatient(Long id) {
        LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 9, 8, 0), ZONE_ID
        );
        PatientRecord patient = patientTestUtils.newPatientRecord(id, 1, deptId);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        return patientRepo.save(patient);
    }

    @Transactional
    private void initRoutes() {
        AdministrationRoute route = medTestUtils.newAdministrationRoute(
            deptId, ROUTE_PUMP, ROUTE_PUMP, true,
            ROUTE_GROUP_PUMP, INTAKE_TYPE_INTRAVENOUS, true
        );
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE_PUMP).isPresent()) {
            routeRepo.save(route);
        }

        route.setId(null);
        route.setCode(ROUTE_OTHER);
        route.setName(ROUTE_OTHER);
        route.setIsContinuous(false);
        route.setGroupId(ROUTE_GROUP_OTHERS);
        if (!routeRepo.findByDeptIdAndCode(deptId, ROUTE_OTHER).isPresent()) {
            routeRepo.save(route);
        }

        medConfig.initialize();
        medConfig.refresh();
    }

    private void initMedOrderGroupSettings() {
        MedOrderGroupSettingsPB medOgSettings = medTestUtils.newMedOrderGroupSettings(
            List.of("西药"),
            List.of("未审核"),
            List.of("不允许的口服"),
            false,
            false,
            false,
            false,
            ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE,
            0,
            "已取消",
            List.of("氯化钠")
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
    private final Integer ACTION_TYPE_START;
    private final Integer ACTION_TYPE_COMPLETE;
    private final Integer DURATION_TYPE_LONG_TERM;
    private final Integer DURATION_TYPE_ONE_TIME;
    private final Integer ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE;
    private final String ROUTE_PUMP;
    private final String ROUTE_OTHER;

    private final MedicationConfig medConfig;
    private final MedicationService medService;
    private final MonitoringConfig monitoringConfig;
    private final MedicationRepository medRepo;
    private final MedicationOrderGroupRepository medOrdGroupRepo;
    private final MedicationExecutionRecordRepository medExeRecRepo;
    private final MedicationExecutionRecordStatRepository medExeRecStatRepo;
    private final MedicationExecutionActionRepository medExeActRepo;
    private final AdministrationRouteRepository routeRepo;
    private final MedicalOrderRepository medOrdRepo;
    private final PatientRecordRepository patientRepo;
    private final PatientMonitoringRecordRepository monitoringRecordRepo;
    private final PatientMonitoringRecordStatsDailyRepository monitoringStatsRepo;
    private final PatientNursingReportRepository pnrRepo;

    private final MedicationTestUtils medTestUtils;
    private final PatientTestUtils patientTestUtils;
}
