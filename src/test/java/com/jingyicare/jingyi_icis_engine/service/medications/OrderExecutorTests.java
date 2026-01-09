package com.jingyicare.jingyi_icis_engine.service.medications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

import com.jingyicare.jingyi_icis_engine.testutils.MedicationTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.PatientTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public class OrderExecutorTests extends TestsBase {
    public OrderExecutorTests(
        @Autowired OrderExecutor orderExecutor,
        @Autowired MedMonitoringService medMonitoringService,
        @Autowired MedicationOrderGroupRepository ordGroupRepo,
        @Autowired MedicationExecutionRecordRepository exeRecordRepo,
        @Autowired MedicationExecutionActionRepository exeActionRepo,
        @Autowired MedicationFrequencyRepository freqRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired ConfigProtoService protoService,
        @Autowired MedicationConfig medCfg
    ) {
        this.deptId = "10004";
        this.accountId = "account001";
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.shiftSettings = protoService.getConfig().getShift().getDefaultSetting();

        MEnums mEnums = protoService.getConfig().getMedication().getEnums();
        this.VALIDITY_TYPE_VALID = mEnums.getMedicationOrderValidityTypeValid().getId();
        this.VALIDITY_TYPE_STOPPED = mEnums.getMedicationOrderValidityTypeStopped().getId();
        this.VALIDITY_TYPE_CANCELED = mEnums.getMedicationOrderValidityTypeCanceled().getId();
        this.VALIDITY_TYPE_OVERWRITTEN = mEnums.getMedicationOrderValidityTypeOverwritten().getId();
        this.VALIDITY_TYPE_MANUAL_ENTRY = mEnums.getMedicationOrderValidityTypeManualEntry().getId();
        this.DURATION_TYPE_LONG_TERM = mEnums.getOrderDurationTypeLongTerm().getId();
        this.DURATION_TYPE_ONE_TIME = mEnums.getOrderDurationTypeOneTime().getId();
        this.DURATION_TYPE_MANUAL_ENTRY = mEnums.getOrderDurationTypeManualEntry().getId();
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_ALL = mEnums.getOneTimeExecutionStopStrategyDeleteAll().getId();
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_AFTER = mEnums.getOneTimeExecutionStopStrategyDeleteAfter().getId();
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE = mEnums.getOneTimeExecutionStopStrategyIgnore().getId();
        this.ACTION_TYPE_START = mEnums.getMedicationExecutionActionTypeStart().getId();
        this.ACTION_TYPE_COMPLETE = mEnums.getMedicationExecutionActionTypeComplete().getId();
        this.STATUS_CANCELED_TXT = "已取消";

        MedicationConfigPB medicationPb = protoService.getConfig().getMedication();
        this.FREQ_CODE_ONCE = medicationPb.getFreqSpec().getOnceCode();
        this.DELETE_REASON_CANCELED = medicationPb.getDeleteReasonCanceled();
        this.DELETE_REASON_STOPPED = medicationPb.getDeleteReasonStopped();
        this.DELETE_REASON_MANUALLY = medicationPb.getDeleteReasonManually();

        this.orderExecutor = orderExecutor;
        this.medMonitoringService = medMonitoringService;
        this.ordGroupRepo = ordGroupRepo;
        this.exeRecordRepo = exeRecordRepo;
        this.exeActionRepo = exeActionRepo;
        this.patientRepo = patientRepo;

        this.medCfg = medCfg;
        this.medTestUtils = new MedicationTestUtils(protoService.getConfig().getMedication(), freqRepo);

        this.medOgSettings = medTestUtils.newMedOrderGroupSettings(
            new ArrayList<>(List.of("西药", "中药")) /*allowedOrderTypes*/,
            new ArrayList<>(List.of("未审核")) /*denyStatuses*/,
            new ArrayList<>(List.of("不允许的口服")) /*denyRouteCodes*/,
            false /*omitFirstDayOrderExecution*/,
            true /*checkOrderTimeForLongTermExe*/,
            true /*forceGenExeOrdDay1*/,
            true /*checkOrderTimeForOneTimeExe*/,
            ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE /*oneTimeExeStopStrategy*/,
            8 /* notStartedExeRecAdvanceHours */,
            STATUS_CANCELED_TXT /* status_canceled_txt */,
            new ArrayList<>() /*deprioritizedMedNames*/
        );
        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, "accountId");
        medTestUtils.initFreqRepo();
        medCfg.refresh();

        this.patientTestUtils = new PatientTestUtils();
    }

    @Test
    public void testRetrieveExistingExeOrders_ManualEntry() {
        lock.lock();  // 锁定部门配置

        // 创建一个补录医嘱
        final String hisPatientId = "hisPatientId201";
        final Long patientId = 201L;
        final String hisMrn = "hisMrn201";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 8), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );
        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "" /*group_id*/, Arrays.asList("order_id_201", "order_id_202") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_MANUAL_ENTRY,
            dosageGroup, DURATION_TYPE_MANUAL_ENTRY, ""/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 创建一个执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByMedGroupId(ordGroup.getId());
        assertThat(exeRecords).isEmpty();

        Boolean isRouteContinuous = medMonitoringService.isRouteContinuous(ordGroup);
        MedicationExecutionRecord exeRecord = medTestUtils.newMedicationExecutionRecord(
            ordGroup.getId(), ordGroup.getGroupId(), patientId, ordTime,
            null/*start_time*/, null/*end_time*/, false/*is_deleted*/,
            null/*deleteAccountId*/, null/*deleteTime*/, null/*deleteReason*/,
            false/*userTouched*/, ordGroup.getAdministrationRouteCode()/*routeCode*/,
            ordGroup.getAdministrationRouteName()/*routeName*/, isRouteContinuous,
            accountId/*createAccountId*/, createdAt/*createdAt*/
        );
        exeRecord = exeRecordRepo.save(exeRecord);

        exeRecords = exeRecordRepo.findByMedGroupId(ordGroup.getId());
        assertThat(exeRecords).hasSize(1);

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, ordGroup, retrieveAt);

        // 校验结果
        assertThat(retrievedExeRecs).hasSize(1);
        MedicationExecutionRecord retrievedExeRec = retrievedExeRecs.get(0);
        assertThat(retrievedExeRec.getMedicationOrderGroupId()).isEqualTo(ordGroup.getId());
        assertThat(retrievedExeRec.getHisOrderGroupId()).isEmpty();
        assertThat(retrievedExeRec.getPlanTime()).isEqualTo(ordTime);
        assertThat(retrievedExeRec.getStartTime()).isNull();
        assertThat(retrievedExeRec.getEndTime()).isNull();
        assertThat(retrievedExeRec.getIsDeleted()).isFalse();
        assertThat(retrievedExeRec.getDeleteAccountId()).isEmpty();
        assertThat(retrievedExeRec.getDeleteTime()).isNull();
        assertThat(retrievedExeRec.getDeleteReason()).isEmpty();
        assertThat(retrievedExeRec.getUserTouched()).isFalse();
        assertThat(retrievedExeRec.getAdministrationRouteCode()).isEqualTo(ordGroup.getAdministrationRouteCode());
        assertThat(retrievedExeRec.getAdministrationRouteName()).isEqualTo(ordGroup.getAdministrationRouteName());
        assertThat(retrievedExeRec.getCreateAccountId()).isEqualTo(accountId);
        assertThat(retrievedExeRec.getCreatedAt()).isEqualTo(createdAt);

        lock.unlock();
    }

    @Test
    public void testRetrieveExistingExeOrders_FromHis() {
        lock.lock();  // 锁定部门配置

        // 创建一个临时医嘱
        final String hisPatientId = "hisPatientId202";
        final Long patientId = 202L;
        final String hisMrn = "hisMrn202";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 8), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );
        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_201" /*group_id*/, Arrays.asList("order_id_201", "order_id_202") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_ONE_TIME, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 创建一个执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        Boolean isRouteContinuous = medMonitoringService.isRouteContinuous(ordGroup);
        MedicationExecutionRecord exeRecord = medTestUtils.newMedicationExecutionRecord(
            ordGroup.getId(), ordGroup.getGroupId(), patientId, ordTime,
            null/*start_time*/, null/*end_time*/, false/*is_deleted*/,
            null/*deleteAccountId*/, null/*deleteTime*/, null/*deleteReason*/,
            false/*userTouched*/, ordGroup.getAdministrationRouteCode()/*routeCode*/,
            ordGroup.getAdministrationRouteName()/*routeName*/, isRouteContinuous,
            accountId/*createAccountId*/, createdAt/*createdAt*/
        );
        exeRecord = exeRecordRepo.save(exeRecord);

        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());;
        assertThat(exeRecords).hasSize(1);

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, ordGroup, retrieveAt);

        // 校验结果
        assertThat(retrievedExeRecs).hasSize(1);
        MedicationExecutionRecord retrievedExeRec = retrievedExeRecs.get(0);
        assertThat(retrievedExeRec.getMedicationOrderGroupId()).isEqualTo(ordGroup.getId());
        assertThat(retrievedExeRec.getHisOrderGroupId()).isEqualTo("group_id_201");
        assertThat(retrievedExeRec.getPlanTime()).isEqualTo(ordTime);
        assertThat(retrievedExeRec.getStartTime()).isNull();
        assertThat(retrievedExeRec.getEndTime()).isNull();
        assertThat(retrievedExeRec.getIsDeleted()).isFalse();
        assertThat(retrievedExeRec.getDeleteAccountId()).isEmpty();
        assertThat(retrievedExeRec.getDeleteTime()).isNull();
        assertThat(retrievedExeRec.getDeleteReason()).isEmpty();
        assertThat(retrievedExeRec.getUserTouched()).isFalse();
        assertThat(retrievedExeRec.getAdministrationRouteCode()).isEqualTo(ordGroup.getAdministrationRouteCode());
        assertThat(retrievedExeRec.getAdministrationRouteName()).isEqualTo(ordGroup.getAdministrationRouteName());
        assertThat(retrievedExeRec.getCreateAccountId()).isEqualTo(accountId);
        assertThat(retrievedExeRec.getCreatedAt()).isEqualTo(createdAt);

        lock.unlock();
    }

    @Test
    public void testRetrieveExistingExeOrders_CancelTouched() {
        lock.lock();  // 锁定部门配置

        // 创建一个临时医嘱
        final String hisPatientId = "hisPatientId203";
        final Long patientId = 203L;
        final String hisMrn = "hisMrn203";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 8), ZONE_ID);
        final LocalDateTime canceledAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 2), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );
        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_202" /*group_id*/, Arrays.asList("order_id_203") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", STATUS_CANCELED_TXT, ordTime,
            null /*stop_time*/, canceledAt /*cancel_time*/, VALIDITY_TYPE_CANCELED,
            dosageGroup, DURATION_TYPE_ONE_TIME, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 创建一个执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // userTouched == true
        Boolean isRouteContinuous = medMonitoringService.isRouteContinuous(ordGroup);
        MedicationExecutionRecord exeRecord = medTestUtils.newMedicationExecutionRecord(
            ordGroup.getId(), ordGroup.getGroupId(), patientId, ordTime,
            null/*start_time*/, null/*end_time*/, false/*is_deleted*/,
            null/*deleteAccountId*/, null/*deleteTime*/, null/*deleteReason*/,
            true/*userTouched*/, ordGroup.getAdministrationRouteCode()/*routeCode*/,
            ordGroup.getAdministrationRouteName()/*routeName*/, isRouteContinuous,
            accountId/*createAccountId*/, createdAt/*createdAt*/
        );
        exeRecord = exeRecordRepo.save(exeRecord);

        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());;
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, ordGroup, retrieveAt);

        // 校验结果
        assertThat(retrievedExeRecs).hasSize(1);
        assertThat(retrievedExeRecs.get(0).getIsDeleted()).isFalse();

        lock.unlock();
    }

    // 给定班次：护士生成医嘱执行记录 => 医嘱被取消 => 生成的医嘱执行记录被删除
    @Test
    public void testCancelWithTime_WithDeletedExeRecord() {
        lock.lock();  // 锁定部门配置

        // 创建一个临时医嘱
        final String hisPatientId = "hisPatientId204";
        final Long patientId = 204L;
        final String hisMrn = "hisMrn204";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 8), ZONE_ID);
        final LocalDateTime canceledAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 2), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );
        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_203" /*group_id*/, Arrays.asList("order_id_204") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", STATUS_CANCELED_TXT, ordTime,
            null /*stop_time*/, canceledAt /*cancel_time*/, VALIDITY_TYPE_CANCELED,
            dosageGroup, DURATION_TYPE_ONE_TIME, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 模拟护士生成的医嘱执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        Boolean isRouteContinuous = medMonitoringService.isRouteContinuous(ordGroup);
        MedicationExecutionRecord exeRecord = medTestUtils.newMedicationExecutionRecord(
            ordGroup.getId(), ordGroup.getGroupId(), patientId, ordTime,
            null/*start_time*/, null/*end_time*/, false/*is_deleted*/,
            null/*deleteAccountId*/, null/*deleteTime*/, null/*deleteReason*/,
            false/*userTouched*/, ordGroup.getAdministrationRouteCode()/*routeCode*/,
            ordGroup.getAdministrationRouteName()/*routeName*/, isRouteContinuous,
            accountId/*createAccountId*/, createdAt/*createdAt*/
        );
        exeRecord = exeRecordRepo.save(exeRecord);

        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, ordGroup, retrieveAt);

        // 校验结果
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(0).getDeleteReason()).isEqualTo(DELETE_REASON_CANCELED);

        lock.unlock();
    }

    // 在医嘱取消时间对应的班次：医嘱被取消 => 生成被删除的医嘱执行记录
    @Test
    public void testCancelWithTime_CreateADeletedExeRecord() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOmitFirstDayOrderExecution(false)  // 检查一次性医嘱是否按下嘱时间执行
            .setCheckOrderTimeForLongTermExecution(false)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        // 创建一个长期医嘱
        String hisPatientId = "hisPatientId205";
        Long patientId = 205L;
        String hisMrn = "hisMrn205";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 8), ZONE_ID);
        final LocalDateTime canceledAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 2), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_204" /*group_id*/, Arrays.asList("order_id_205") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", STATUS_CANCELED_TXT, ordTime,
            null /*stop_time*/, canceledAt /*cancel_time*/, VALIDITY_TYPE_CANCELED,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录0条
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    // 在医嘱取消时间对应的班次的后一个班次：医嘱被取消 => 不生成对应的医嘱执行记录
    @Test
    public void testCancelWithTime_NoExeRecord() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOmitFirstDayOrderExecution(false)  // 检查一次性医嘱是否按下嘱时间执行
            .setCheckOrderTimeForLongTermExecution(false)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        // 创建一个长期医嘱
        String hisPatientId = "hisPatientId206";
        Long patientId = 206L;
        String hisMrn = "hisMrn206";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 8), ZONE_ID);
        final LocalDateTime canceledAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 2), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_205" /*group_id*/, Arrays.asList("order_id_206") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", STATUS_CANCELED_TXT, ordTime,
            null /*stop_time*/, canceledAt /*cancel_time*/, VALIDITY_TYPE_CANCELED,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录0条
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(0);

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testCancelWithoutTime_WithDeletedExeRecord() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOmitFirstDayOrderExecution(false)  // 检查一次性医嘱是否按下嘱时间执行
            .setCheckOrderTimeForLongTermExecution(false)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        // 创建一个长期医嘱
        String hisPatientId = "hisPatientId207";
        Long patientId = 207L;
        String hisMrn = "hisMrn207";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 8), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );
        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_206" /*group_id*/, Arrays.asList("order_id_207") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);

        // 校验结果
        assertThat(retrievedExeRecs).hasSize(1);
        MedicationExecutionRecord retrievedExeRec = retrievedExeRecs.get(0);
        assertThat(retrievedExeRec.getMedicationOrderGroupId()).isEqualTo(ordGroup.getId());
        assertThat(retrievedExeRec.getHisOrderGroupId()).isEqualTo("group_id_206");
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();

        ordGroup.setOrderValidity(VALIDITY_TYPE_OVERWRITTEN);
        ordGroup = ordGroupRepo.save(ordGroup);
        final Long ordGroupId1 = ordGroup.getId();

        ordGroup.setId(null);
        ordGroup.setOrderValidity(VALIDITY_TYPE_CANCELED);
        ordGroup.setStatus(STATUS_CANCELED_TXT);
        ordGroup = ordGroupRepo.save(ordGroup);
        final Long ordGroupId2 = ordGroup.getId();
        assertThat(ordGroupId2).isNotEqualTo(ordGroupId1);

        // 医嘱状态变成"取消", 对应的未更改的执行记录也变成取消
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testCancelWithoutTime_NoExeRecord() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOmitFirstDayOrderExecution(false)  // 检查一次性医嘱是否按下嘱时间执行
            .setCheckOrderTimeForLongTermExecution(false)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        // 创建一个长期医嘱
        String hisPatientId = "hisPatientId208";
        Long patientId = 208L;
        String hisMrn = "hisMrn208";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 8), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_207" /*group_id*/, Arrays.asList("order_id_208") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", STATUS_CANCELED_TXT, ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_CANCELED,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录0条
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testRetrieveExeOrders_Stop_IgnorePrev() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOmitFirstDayOrderExecution(false)  // 检查一次性医嘱是否按下嘱时间执行
            .setCheckOrderTimeForLongTermExecution(false)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        // 创建一个长期医嘱
        String hisPatientId = "hisPatientId209";
        Long patientId = 209L;
        String hisMrn = "hisMrn209";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 8), ZONE_ID);
        LocalDateTime stoppedAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 5, 8, 8), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_208" /*group_id*/, Arrays.asList("order_id_209") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            stoppedAt /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_STOPPED,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录0条
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getHisOrderGroupId()).isEqualTo(ordGroup.getGroupId());
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testRetrieveExistingExeOrders_Stop_DeleteAfter() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOmitFirstDayOrderExecution(false)  // 检查一次性医嘱是否按下嘱时间执行
            .setCheckOrderTimeForLongTermExecution(false)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId210";
        Long patientId = 210L;
        String hisMrn = "hisMrn210";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 30), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 10), ZONE_ID);
        LocalDateTime stoppedAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 8), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_209" /*group_id*/, Arrays.asList("order_id_210") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);
        final Long ordGroupId1 = ordGroup.getId();

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建一条未被干预的执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();
        final LocalDateTime exeTime = exeRecords.get(0).getPlanTime();

        // 更新长期医嘱，状态改成停止
        ordGroup.setId(null);
        ordGroup.setStatus("已停止");
        ordGroup.setStopTime(stoppedAt);
        ordGroup.setOrderValidity(VALIDITY_TYPE_STOPPED);
        ordGroup = ordGroupRepo.save(ordGroup);
        final Long ordGroupId2 = ordGroup.getId();

        // 更新执行记录
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);

        // 检查执行记录的状态，从正常变成删除
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(0).getDeleteReason()).isEqualTo(DELETE_REASON_STOPPED);

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testRetrieveExistingExeOrders_StopOnetime_DeleteAll() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOneTimeExecutionStopStrategy(ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_ALL)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId211";
        Long patientId = 211L;
        String hisMrn = "hisMrn211";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);
        LocalDateTime stoppedAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 8), ZONE_ID);

        // 创建一个临时医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_210" /*group_id*/, Arrays.asList("order_id_211") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已停止", ordTime,
            stoppedAt /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_STOPPED,
            dosageGroup, DURATION_TYPE_ONE_TIME, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testRetrieveExistingExeOrders_StopOnetime_DeleteAfter() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOneTimeExecutionStopStrategy(ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_AFTER)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId212";
        Long patientId = 212L;
        String hisMrn = "hisMrn212";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);
        LocalDateTime stoppedAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 8), ZONE_ID);

        // 创建一个临时医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_211" /*group_id*/, Arrays.asList("order_id_212") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已停止", ordTime,
            stoppedAt /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_STOPPED,
            dosageGroup, DURATION_TYPE_ONE_TIME, MedicationTestUtils.FREQ_CODE_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(2);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // Shanghai 10:00 (before 17:08) -> UTC 2:00
        assertThat(exeRecords.get(1).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // Shanghai 16:00 (before 17:08) -> UTC 8:00
        assertThat(exeRecords.get(2).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(2).getDeleteReason()).isEqualTo(DELETE_REASON_STOPPED);
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // Shanghai 22:00 (after 17:08) -> UTC 8:00

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testRetrieveExistingExeOrders_StopOnetime_Ignore() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOneTimeExecutionStopStrategy(ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId213";
        Long patientId = 213L;
        String hisMrn = "hisMrn213";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);
        LocalDateTime stoppedAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 8), ZONE_ID);

        // 创建一个临时医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_212" /*group_id*/, Arrays.asList("order_id_213") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已停止", ordTime,
            stoppedAt /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_STOPPED,
            dosageGroup, DURATION_TYPE_ONE_TIME, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testManualEntryOrderGroup() {
        lock.lock();  // 锁定部门配置

        // 创建一个补录医嘱
        final String hisPatientId = "hisPatientId214";
        final Long patientId = 214L;
        final String hisMrn = "hisMrn214";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);

        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );
        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "" /*group_id*/, Collections.emptyList() /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, ""/*type*/, ""/*status*/, ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_MANUAL_ENTRY,
            dosageGroup, DURATION_TYPE_MANUAL_ENTRY, ""/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroupRepo.save(ordGroup);
        final Long ordGroupId = ordGroup.getId();

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByMedGroupId(ordGroupId);
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        assertThat(retrievedExeRecs.get(0).getMedicationOrderGroupId()).isEqualTo(ordGroupId);

        exeRecords = exeRecordRepo.findByMedGroupId(ordGroupId);
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getMedicationOrderGroupId()).isEqualTo(ordGroupId);
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();

        // 再次调用，不会重复生成执行记录
        retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 16, 0), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        assertThat(retrievedExeRecs.get(0).getMedicationOrderGroupId()).isEqualTo(ordGroupId);

        exeRecords = exeRecordRepo.findByMedGroupId(ordGroupId);
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getMedicationOrderGroupId()).isEqualTo(ordGroupId);
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();

        // 非当日调用，不会生成重复执行记录
        retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 0), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByMedGroupId(ordGroupId);
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getMedicationOrderGroupId()).isEqualTo(ordGroupId);
        LocalDateTime planTime = TimeUtils.getLocalDateTimeFromUtc(exeRecords.get(0).getPlanTime(), ZONE_ID);
        assertThat(planTime.getDayOfMonth()).isEqualTo(3);

        lock.unlock();
    }

    @Test
    public void testIgnoreOverwrittenOrderGroup() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOneTimeExecutionStopStrategy(ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE)  // 一次性医嘱停止策略
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId214";
        Long patientId = 214L;
        String hisMrn = "hisMrn214";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);

        // 创建一个临时医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_213" /*group_id*/, Arrays.asList("order_id_214") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_OVERWRITTEN,
            dosageGroup, DURATION_TYPE_ONE_TIME, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(0);

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testIgnoreFutureShiftDays() {
        lock.lock();  // 锁定部门配置

        String hisPatientId = "hisPatientId215";
        Long patientId = 215L;
        String hisMrn = "hisMrn215";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        final LocalDateTime retrieveAt = TimeUtils.getLocalTime(nowUtc.getYear() + 1, 10, 3, 8, 10);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_214" /*group_id*/, Arrays.asList("order_id_215") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(0);

        lock.unlock();
    }

    @Test
    public void testOrderDay_LongTerm_OmitFirstDay() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOmitFirstDayOrderExecution(true)  // 是否忽略首日医嘱执行
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId216";
        Long patientId = 216L;
        String hisMrn = "hisMrn216";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_215" /*group_id*/, Arrays.asList("order_id_216") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();

        final LocalDateTime retrieveAt2 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 10), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt2);
        assertThat(retrievedExeRecs).hasSize(1);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(2);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(0).getPlanTime().getDayOfMonth()).isEqualTo(3);
        assertThat(exeRecords.get(1).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(1).getPlanTime().getDayOfMonth()).isEqualTo(4);

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testOrderDay_FirstDayCnt() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setOmitFirstDayOrderExecution(false)  // 是否忽略首日医嘱执行
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId217";
        Long patientId = 217L;
        String hisMrn = "hisMrn217";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_216" /*group_id*/, Arrays.asList("order_id_217") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, FREQ_CODE_ONCE/*freqCode*/, ordTime, 2/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(1);

        ////// 将每天的频次从1改成3
        hisPatientId = "hisPatientId218";
        patientId = 218L;
        hisMrn = "hisMrn218";

        ordGroup.setId(null);
        ordGroup.setGroupId("group_id_217");
        ordGroup.setMedicalOrderIds(Base64.getEncoder().encodeToString(
            MedicalOrderIdsPB.newBuilder().addId("order_id_218").build().toByteArray()));
        ordGroup.setFreqCode(MedicationTestUtils.FREQ_CODE_TID);
        ordGroup = ordGroupRepo.save(ordGroup);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(2);
        retrievedExeRecs.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(retrievedExeRecs.get(0).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(retrievedExeRecs.get(1).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testOrderDay_OneTime_CheckOrderTimeYes() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForOneTimeExecution(true)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId219";
        Long patientId = 219L;
        String hisMrn = "hisMrn219";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 10), ZONE_ID);

        // 创建一个临时医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_218" /*group_id*/, Arrays.asList("order_id_219") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_ONE_TIME, MedicationTestUtils.FREQ_CODE_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        assertThat(retrievedExeRecs.get(0).getIsDeleted()).isFalse();
        assertThat(retrievedExeRecs.get(0).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(1).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        assertThat(exeRecords.get(2).getIsDeleted()).isFalse();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testOrderDay_OneTime_CheckOrderTimeNo() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForOneTimeExecution(false)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId220";
        Long patientId = 220L;
        String hisMrn = "hisMrn220";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 10), ZONE_ID);

        // 创建一个临时医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_219" /*group_id*/, Arrays.asList("order_id_220") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_ONE_TIME, MedicationTestUtils.FREQ_CODE_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(3);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(1).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        assertThat(exeRecords.get(2).getIsDeleted()).isFalse();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testOrderDay_LongTerm_CheckOrderTimeYes() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(true)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId221";
        Long patientId = 221L;
        String hisMrn = "hisMrn221";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_220" /*group_id*/, Arrays.asList("order_id_221") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        assertThat(retrievedExeRecs.get(0).getIsDeleted()).isFalse();
        assertThat(retrievedExeRecs.get(0).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(1).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        assertThat(exeRecords.get(2).getIsDeleted()).isFalse();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testOrderDay_LongTerm_CheckOrderTimeNo() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(false)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId222";
        Long patientId = 222L;
        String hisMrn = "hisMrn222";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 17, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_221" /*group_id*/, Arrays.asList("order_id_222") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(3);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(1).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        assertThat(exeRecords.get(2).getIsDeleted()).isFalse();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testOrderDay_ForceGeneratingYes() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(true)
            .setForceGenerateExecutionOrderDay1(true)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId223";
        Long patientId = 223L;
        String hisMrn = "hisMrn223";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 23, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 23, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 23, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_222" /*group_id*/, Arrays.asList("order_id_223") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(1);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(1).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        assertThat(exeRecords.get(2).getIsDeleted()).isFalse();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testOrderDay_ForceGeneratingNo() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(true)
            .setForceGenerateExecutionOrderDay1(false)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId224";
        Long patientId = 224L;
        String hisMrn = "hisMrn224";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 23, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 23, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 23, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_223" /*group_id*/, Arrays.asList("order_id_224") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(1).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        assertThat(exeRecords.get(2).getIsDeleted()).isTrue();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testRetrieveDay_ByWeekFiltered_OrderDay() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(false)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId225";
        Long patientId = 225L;
        String hisMrn = "hisMrn225";

        // 2024-10-3是周四，被MedicationTestUtils.FREQ_CODE_BW135_TID过滤
        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_224" /*group_id*/, Arrays.asList("order_id_225") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_BW135_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(0).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(1).getIsDeleted()).isTrue();
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        assertThat(exeRecords.get(2).getIsDeleted()).isTrue();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testRetrieveDay_ByWeekUnFiltered_OrderDay() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(false) 
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId226";
        Long patientId = 226L;
        String hisMrn = "hisMrn226";

        // 2024-10-2是周三，不被MedicationTestUtils.FREQ_CODE_BW135_TID过滤
        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_225" /*group_id*/, Arrays.asList("order_id_226") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_BW135_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(3);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);
        exeRecords.sort(Comparator.comparing(MedicationExecutionRecord::getPlanTime));
        assertThat(exeRecords.get(0).getPlanTime().getHour()).isEqualTo(2);  // UTC 2:00 -> Shanghai 10:00
        assertThat(exeRecords.get(0).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(1).getPlanTime().getHour()).isEqualTo(8);  // UTC 8:00 -> Shanghai 16:00
        assertThat(exeRecords.get(1).getIsDeleted()).isFalse();
        assertThat(exeRecords.get(2).getPlanTime().getHour()).isEqualTo(14);  // UTC 14:00 -> Shanghai 22:00
        assertThat(exeRecords.get(2).getIsDeleted()).isFalse();

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    public void testRetrieveDay_ByWeekFiltered_NotOrderDay() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(false)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId227";
        Long patientId = 227L;
        String hisMrn = "hisMrn227";

        // 2024-10-3是周四，被MedicationTestUtils.FREQ_CODE_BW135_TID过滤
        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_226" /*group_id*/, Arrays.asList("order_id_227") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_BW135_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录0
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(0);

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    void testRetrieveDay_ByInterval_OrderDay() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(false)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId228";
        Long patientId = 228L;
        String hisMrn = "hisMrn228";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_227" /*group_id*/, Arrays.asList("order_id_228") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_BI2_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(3);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    void testRetrieveDay_ByIntervalFiltered_StartFromOrderDay() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(false)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId229";
        Long patientId = 229L;
        String hisMrn = "hisMrn229";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_228" /*group_id*/, Arrays.asList("order_id_229") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_BI2_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(3);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);

        // 2024-10-2第一天，隔2天执行一次，2024-10-3不符合，不生成新的记录
        final LocalDateTime retrieveAt2 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt2);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);

        // 2024-10-2第一天，隔2天执行一次，2024-10-4不符合，不生成新的记录
        final LocalDateTime retrieveAt3 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 10), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt3);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);

        // 2024-10-2第一天，隔2天执行一次，2024-10-5符合，生成新的记录
        final LocalDateTime retrieveAt4 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 5, 8, 10), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt4);
        assertThat(retrievedExeRecs).hasSize(3);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(6);

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    @Test
    void testRetrieveDay_ByIntervalUnFiltered_NotStartFromOrderDay() {
        lock.lock();  // 锁定部门配置
        MedOrderGroupSettingsPB localMedOgSettings = medOgSettings.toBuilder()
            .setCheckOrderTimeForLongTermExecution(false)
            .build();
        medCfg.setMedOrderGroupSettings(deptId, localMedOgSettings, accountId);

        String hisPatientId = "hisPatientId230";
        Long patientId = 230L;
        String hisMrn = "hisMrn230";

        final LocalDateTime ordTime = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 0), ZONE_ID);
        final LocalDateTime createdAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 2, 8, 6), ZONE_ID);
        final LocalDateTime retrieveAt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 3, 8, 10), ZONE_ID);

        // 创建一个长期医嘱
        MedicationDosageGroupPB dosageGroup = medTestUtils.createMedicationDosageGroup(
            3, 
            Arrays.asList("med_code_1", "med_code_2", "med_code_3"),
            Arrays.asList("med_name_1", "med_name_2", "med_name_3"),
            Arrays.asList("spec_1", "spec_2", "spec_3"),
            Arrays.asList(10.0, 20.0, 30.0),
            Arrays.asList("mg", "ml", "mg")
        );

        MedicationOrderGroup ordGroup = medTestUtils.newMedicationOrderGroup(
            hisPatientId, patientId, "group_id_229" /*group_id*/, Arrays.asList("order_id_230") /*order_ids*/,
            hisMrn, "doctor1", "doctor1", deptId, "西药", "已开立", ordTime,
            null /*stop_time*/, null /*cancel_time*/, VALIDITY_TYPE_VALID,
            dosageGroup, DURATION_TYPE_LONG_TERM, MedicationTestUtils.FREQ_CODE_BI2_TID/*freqCode*/, ordTime, null/*first_day_cnt*/,
            "intravenous_drip", "点滴", createdAt
        );
        ordGroup = ordGroupRepo.save(ordGroup);

        // 尚未生成执行记录
        List<MedicationExecutionRecord> exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).isEmpty();

        // 创建执行记录
        List<MedicationExecutionRecord> retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt);
        assertThat(retrievedExeRecs).hasSize(3);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);

        // 2024-10-2创建医嘱，2024-10-3第一天创建执行记录，隔2天执行一次，2024-10-4不符合，不生成新的记录
        final LocalDateTime retrieveAt2 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 4, 8, 10), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt2);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);

        // 2024-10-2创建医嘱，2024-10-3第一天创建执行记录，隔2天执行一次，2024-10-5不符合，不生成新的记录
        final LocalDateTime retrieveAt3 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 5, 8, 10), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt3);
        assertThat(retrievedExeRecs).hasSize(0);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(3);

        // 2024-10-2创建医嘱，2024-10-3第一天创建执行记录，隔2天执行一次，2024-10-6符合，生成新的记录
        final LocalDateTime retrieveAt4 = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getLocalTime(2024, 10, 6, 8, 10), ZONE_ID);
        retrievedExeRecs = orderExecutor.retrieveExeRecords(
            true, shiftSettings, localMedOgSettings, accountId, ordGroup, retrieveAt4);
        assertThat(retrievedExeRecs).hasSize(3);
        exeRecords = exeRecordRepo.findByHisGroupId(ordGroup.getGroupId());
        assertThat(exeRecords).hasSize(6);

        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, accountId);
        lock.unlock();
    }

    private static final Lock lock = new ReentrantLock();

    private final String deptId;
    private final String accountId;
    private final String ZONE_ID;
    private final ShiftSettingsPB shiftSettings;
    private final Integer VALIDITY_TYPE_VALID;
    private final Integer VALIDITY_TYPE_STOPPED;
    private final Integer VALIDITY_TYPE_CANCELED;
    private final Integer VALIDITY_TYPE_OVERWRITTEN;
    private final Integer VALIDITY_TYPE_MANUAL_ENTRY;
    private final Integer DURATION_TYPE_LONG_TERM;
    private final Integer DURATION_TYPE_ONE_TIME;
    private final Integer DURATION_TYPE_MANUAL_ENTRY;
    private final Integer ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_ALL;
    private final Integer ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_AFTER;
    private final Integer ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE;
    private final Integer ACTION_TYPE_START;
    private final Integer ACTION_TYPE_COMPLETE;
    private final String STATUS_CANCELED_TXT;

    private final String FREQ_CODE_ONCE;

    private final String DELETE_REASON_CANCELED;
    private final String DELETE_REASON_STOPPED;
    private final String DELETE_REASON_MANUALLY;

    private MedOrderGroupSettingsPB medOgSettings;

    private OrderExecutor orderExecutor;
    private MedMonitoringService medMonitoringService;
    private MedicationOrderGroupRepository ordGroupRepo;
    private MedicationExecutionRecordRepository exeRecordRepo;
    private MedicationExecutionActionRepository exeActionRepo;
    private PatientRecordRepository patientRepo;
    private MedicationConfig medCfg;
    private MedicationTestUtils medTestUtils;
    private PatientTestUtils patientTestUtils;
}