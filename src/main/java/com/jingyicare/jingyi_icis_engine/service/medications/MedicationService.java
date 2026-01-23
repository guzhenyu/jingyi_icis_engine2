package com.jingyicare.jingyi_icis_engine.service.medications;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MedicationService {
    public MedicationService(
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired MedicationConfig medConfig,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired OrderGroupGenerator ordGroupGenerator,
        @Autowired OrderExecutor ordExecutor,
        @Autowired MedicationDictionary medDict,
        @Autowired MedMonitoringService medMonitoringService,
        @Autowired MedReportUtils medReportUtils,
        @Autowired MedicationExecutionRecordRepository medExeRecordRepo,
        @Autowired MedicationRepository medRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired MedicationFrequencyRepository medFreqRepo,
        @Autowired MedicationExecutionRecordStatRepository exeRecordStatRepo,
        @Autowired DeptSystemSettingsRepository deptSystemSettingsRepo,
        @Autowired AccountRepository accountRepo
    ) {
        this.protoService = protoService;
        this.shiftUtils = shiftUtils;
        this.ZONE_ID = protoService.getConfig().getZoneId();

        final MEnums medEnums = protoService.getConfig().getMedication().getEnums();
        this.ACTION_TYPE_START = medEnums.getMedicationExecutionActionTypeStart().getId();
        this.ACTION_TYPE_PAUSE = medEnums.getMedicationExecutionActionTypePause().getId();
        this.ACTION_TYPE_RESUME = medEnums.getMedicationExecutionActionTypeResume().getId();
        this.ACTION_TYPE_ADJUST_SPEED = medEnums.getMedicationExecutionActionTypeAdjustSpeed().getId();
        this.ACTION_TYPE_FAST_PUSH = medEnums.getMedicationExecutionActionTypeFastPush().getId();
        this.ACTION_TYPE_COMPLETE = medEnums.getMedicationExecutionActionTypeComplete().getId();

        this.ORDER_GROUP_NOT_STARTED_TXT = protoService.getConfig().getMedication().getOrderGroupNotStartedTxt();
        this.ORDER_GROUP_IN_PROGRESS_TXT = protoService.getConfig().getMedication().getOrderGroupInProgressTxt();
        this.ORDER_GROUP_COMPLETED_TXT = protoService.getConfig().getMedication().getOrderGroupCompletedTxt();

        this.medConfig = medConfig;
        this.userService = userService;
        this.patientService = patientService;
        this.ordGroupGenerator = ordGroupGenerator;
        this.ordExecutor = ordExecutor;
        this.medDict = medDict;
        this.medMonitoringService = medMonitoringService;
        this.medReportUtils = medReportUtils;

        this.medExeRecordRepo = medExeRecordRepo;
        this.medRepo = medRepo;
        this.routeRepo = routeRepo;
        this.medFreqRepo = medFreqRepo;
        this.exeRecordStatRepo = exeRecordStatRepo;
        this.deptSystemSettingsRepo = deptSystemSettingsRepo;
        this.accountRepo = accountRepo;
    }

    @Transactional
    public GetOrderGroupsResp getOrderGroups(String getOrderGroupsReqJson) {
        final GetOrderGroupsReq req;
        try {
            GetOrderGroupsReq.Builder builder = GetOrderGroupsReq.newBuilder();
            JsonFormat.parser().merge(getOrderGroupsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetOrderGroupsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetOrderGroupsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 获取当前病人
        final Long patientId = req.getPatientId();
        final PatientRecord patientRecord = patientService.getPatientRecord(patientId);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", patientId);
            return GetOrderGroupsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final Boolean patientInIcu = (
            patientRecord.getAdmissionStatus() == patientService.getAdmissionStatusInIcuId() ||
            patientRecord.getAdmissionStatus() == patientService.getAdmissionStatusPendingDischargedId()
        );
        final String deptId = patientRecord.getDeptId();
        final ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(deptId);
        final MedOrderGroupSettingsPB medOgSettings = medConfig.getMedOrderGroupSettings(deptId);
        final Boolean expandExeRecord = req.getExpandExeRecord();  // 是否分解医嘱

        // 获取HIS医嘱，补录医嘱
        List<MedicationOrderGroup> orderGroups = ordGroupGenerator.generate(patientRecord, medOgSettings);
        for (MedicationOrderGroup orderGroup : ordGroupGenerator.getNonHisOrderGroup(patientId)) {
            orderGroups.add(orderGroup);
        }

        // 获取执行记录和执行过程
        final LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        final LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");

        // 只有在科病人，且有明确的分解指令，才分解
        if (expandExeRecord) {
            if (patientRecord.getAdmissionStatus() == patientService.getAdmissionStatusDischargedId()) {
                return GetOrderGroupsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.DISCHARGED_PATIENT_EXE_RECORD_NOT_TO_REFRESH))
                    .build();
            }

            if (patientInIcu) {
                List<LocalDateTime> shiftUtcList = shiftUtils.getShiftStartTimeUtcs(
                    shiftSettings, queryStartUtc, queryEndUtc.minusMinutes(1), ZONE_ID
                );
                for (LocalDateTime shiftUtc : shiftUtcList) {
                    ordExecutor.retrieveExeRecords(
                        patientInIcu, shiftSettings, medOgSettings, accountId, orderGroups, shiftUtc
                    );
                }
            }
        }

        return composeGetOrderGroupsResp(medOgSettings, patientRecord, queryStartUtc, queryEndUtc);
    }

    @Transactional
    public GetOrderGroupResp getOrderGroup(String getOrderGroupReqJson) {
        final GetOrderGroupReq req;
        try {
            GetOrderGroupReq.Builder builder = GetOrderGroupReq.newBuilder();
            JsonFormat.parser().merge(getOrderGroupReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 查找医嘱，执行记录，执行过程
        final Long medOrderGroupId = req.getMedOrderGroupId();
        MedicationOrderGroup orderGroup = ordGroupGenerator.getOrderGroup(medOrderGroupId);
        if (orderGroup == null) {
            log.error("OrderGroup not found: ", medOrderGroupId);
            return GetOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ORDER_GROUP_NOT_FOUND))
                .build();
        }

        // 查找病人记录
        final PatientRecord patientRecord = patientService.getPatientRecord(orderGroup.getPatientId());
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", orderGroup.getPatientId());
            return GetOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final Boolean patientInIcu = (patientRecord.getAdmissionStatus() == patientService.getAdmissionStatusInIcuId());
        final String deptId = patientRecord.getDeptId();
        final ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(deptId);
        final MedOrderGroupSettingsPB medOgSettings = medConfig.getMedOrderGroupSettings(deptId);

        // 生成执行记录和过程
        final LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        final LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        List<LocalDateTime> shiftUtcList = shiftUtils.getShiftStartTimeUtcs(
            shiftSettings, queryStartUtc, queryEndUtc.minusMinutes(1), ZONE_ID
        );
        for (LocalDateTime shiftUtc : shiftUtcList) {
            ordExecutor.retrieveExeRecords(patientInIcu, shiftSettings, medOgSettings, accountId, orderGroup, shiftUtc);
        }

        // 组装医嘱
        List<MedicationExecutionRecord> records = ordExecutor.retrieveExecutionRecordsByOrderGroupId(
            medOrderGroupId, medOgSettings.getNotStartedExeRecAdvanceHours(), queryStartUtc, queryEndUtc
        ).stream().sorted(Comparator.comparing(MedicationExecutionRecord::getPlanTime)).toList();
        Map<Long, List<MedicationExecutionAction>> actionMap = ordExecutor.getExeActionsByRecords(records);

        OrderGroupPB orderGroupPb = composeOrderGroupPB(orderGroup, records, actionMap);
        return GetOrderGroupResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setOrderGroup(orderGroupPb)
            .build();
    }

    // 补录医嘱
    public NewOrderGroupResp newOrderGroup(String newOrderGroupReqJson) {
        final NewOrderGroupReq req;
        try {
            NewOrderGroupReq.Builder builder = NewOrderGroupReq.newBuilder();
            JsonFormat.parser().merge(newOrderGroupReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return NewOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return NewOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 检查总液体量是否为正数
        Double totalIntakeMl = 0.0;
        for (MedicationDosagePB dosage : req.getDosageGroup().getMdList()) {
            Double intakeVolMl = dosage.getIntakeVolMl();
            if (intakeVolMl < 0) {
                log.error("Total intake volume is negative: ", intakeVolMl);
                return NewOrderGroupResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INTAKE_VOLUME_NEGATIVE))
                    .build();
            }
            totalIntakeMl += intakeVolMl;
        }
        if (totalIntakeMl <= 0) {
            log.error("Total intake volume is zero or negative: ", totalIntakeMl);
            return NewOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TOTAL_INTAKE_VOLUME_ZERO_OR_NEGATIVE))
                .build();
        }

        // 获取当前病人
        final PatientRecord patientRecord = patientService.getPatientRecordInIcu(req.getPatientId());
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", req.getPatientId());
            return NewOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_IN_ICU))
                .build();
        }
        final String deptId = patientRecord.getDeptId();
        ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(deptId);
        final MedOrderGroupSettingsPB medOgSettings = medConfig.getMedOrderGroupSettings(deptId);
        final Map<String, RouteDetails> routeDetailsMap = routeRepo.findRouteDetailsByDeptId(deptId).stream()
            .collect(Collectors.toMap(RouteDetails::getCode, rd -> rd));

        Pair<StatusCode, OrderGroupPB> resPair = newOrderGroupImpl(
            req, patientRecord, medOgSettings, shiftSettings, routeDetailsMap, accountId, accountName, deptId
            
        );
        StatusCode statusCode = resPair.getFirst();
        OrderGroupPB orderGroupPB = resPair.getSecond();
        if (!statusCode.equals(StatusCode.OK)) {
            return NewOrderGroupResp.newBuilder()
                .setRt(protoService.getReturnCode(statusCode))
                .build();
        }
        return NewOrderGroupResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setOrderGroup(orderGroupPB)
            .build();
    }

    @Transactional
    private Pair<StatusCode, OrderGroupPB> newOrderGroupImpl(
        NewOrderGroupReq req,
        PatientRecord patientRecord,
        MedOrderGroupSettingsPB medOgSettings,
        ShiftSettingsPB shiftSettings,
        Map<String, RouteDetails> routeDetailsMap,
        String accountId,
        String accountName,
        String deptId
    ) {
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        LocalDateTime planTime = TimeUtils.fromIso8601String(req.getPlanTimeIso8601(), "UTC");
        if (planTime == null) planTime = nowUtc;
        final LocalDateTime orderTime = TimeUtils.fromIso8601String(req.getOrderTimeIso8601(), "UTC");

        final String routeCode = req.getAdministrationRouteCode();
        final String routeName = req.getAdministrationRouteName();

        // 1. 生成医嘱组
        MedicationOrderGroup orderGroup = ordGroupGenerator.generateNonHisOrderGroup(
            patientRecord, medOgSettings, accountId, accountName, planTime, orderTime,
            req.getDosageGroup(), routeCode, routeName, req.getNote());

        // 2. 生成执行记录
        final Boolean isPersonalMedications = req.getIsPersonalMedications() > 0;
        List<MedicationExecutionRecord> records = ordExecutor.retrieveExeRecords(
            true, shiftSettings, medOgSettings, accountId, orderGroup, isPersonalMedications,
            shiftUtils.getShiftStartTimeUtc(shiftSettings, orderTime, ZONE_ID));
        
        // 必须有且仅有 1 条执行记录
        if (records.size() != 1) {
            return new Pair<>(StatusCode.EXECUTION_RECORD_COUNT_NOT_ONE, null);
        }
        MedicationExecutionRecord medExeRec = records.get(0);
        Long medExeRecId = medExeRec.getId();
        final Boolean isContinuous = medMonitoringService.isRouteContinuous(routeDetailsMap,deptId, routeCode);
        final Double administrationRate = req.getAdministrationRate();
        final Double intakeVolMl = req.getIntakeVolMl();
        final LocalDateTime startTime = TimeUtils.fromIso8601String(req.getStartTimeIso8601(), "UTC");
        final LocalDateTime finishTime = TimeUtils.fromIso8601String(req.getFinishTimeIso8601(), "UTC");

        // 3. 获取执行动作
        Map<Long, List<MedicationExecutionAction>> actionMap = ordExecutor.getExeActionsByRecords(records);
        List<MedicationExecutionAction> newlyAddedActions = new ArrayList<>();

        if (isContinuous != null && isContinuous) {
            if (administrationRate <= 0 && startTime != null ||
                administrationRate > 0 && startTime == null
            ) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new Pair<>(StatusCode.INVALID_ADMINISTRATION_RATE_OR_START_TIME, null);
            }

            // 添加开始执行动作
            if (administrationRate > 0) {
                String medicationRateStr = ProtoUtils.encodeDosageGroupExt(req.getDosageGroupExt());
                MedicationExecutionAction startAction = ordExecutor.addExeAction(
                    accountId, accountName, medExeRecId, ACTION_TYPE_START,
                    administrationRate, medicationRateStr, 0.0, startTime);
                newlyAddedActions.add(startAction);
                actionMap.computeIfAbsent(medExeRecId, k -> new ArrayList<>()).add(startAction);
                medExeRec.setStartTime(startTime);

                // 如果有结束时间，添加完成动作
                if (finishTime != null) {
                    MedicationExecutionAction completeAction = ordExecutor.addExeAction(
                        accountId, accountName, medExeRecId, ACTION_TYPE_COMPLETE,
                        0.0, "", 0.0, finishTime);
                    newlyAddedActions.add(completeAction);
                    actionMap.get(medExeRecId).add(completeAction);
                    medExeRec.setEndTime(finishTime);
                }

                medExeRecordRepo.save(medExeRec);
            }
        } else {
            // 非持续给药（一次性给药）校验
            if ((intakeVolMl <= 0 && finishTime != null) ||
                (intakeVolMl > 0 && finishTime == null)
            ) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                return new Pair<>(StatusCode.INVALID_INTAKE_VOL_OR_FINISH_TIME, null);
            }

            if (intakeVolMl > 0) {
                MedicationExecutionAction completeAction = ordExecutor.addExeAction(
                    accountId, accountName, medExeRecId, ACTION_TYPE_COMPLETE,
                    0.0, "", intakeVolMl, finishTime);
                newlyAddedActions.add(completeAction);
                actionMap.computeIfAbsent(medExeRecId, k -> new ArrayList<>()).add(completeAction);

                medExeRec.setStartTime(finishTime);
                medExeRec.setEndTime(finishTime);
                medExeRecordRepo.save(medExeRec);
            }
        }
        // 更新给药通道（如果有）
        if (!StrUtils.isBlank(req.getMedicationChannelName())) {
            medExeRec.setMedicationChannel(req.getMedicationChannel());
            medExeRec.setMedicationChannelName(req.getMedicationChannelName());
            medExeRecordRepo.save(medExeRec);
        }
        // 组装返回的 PB
        OrderGroupPB orderGroupPB = composeOrderGroupPB(orderGroup, records, actionMap);
        for (ExecutionRecordPB exeRecPb : orderGroupPB.getExeRecordList()) {
            updateExeRecordStats(exeRecPb);
        }
        if (!newlyAddedActions.isEmpty()) {
            medReportUtils.setDirtyPatientNursingReports(
                orderGroup, medExeRec, null, newlyAddedActions, routeDetailsMap, nowUtc, accountId);
        }
        return new Pair<>(StatusCode.OK, orderGroupPB);
    }

    @Transactional
    public SaveOrderExeActionResp saveOrderExeAction(String saveOrderExeActionReqJson) {
        final SaveOrderExeActionReq req;
        try {
            SaveOrderExeActionReq.Builder builder = SaveOrderExeActionReq.newBuilder();
            JsonFormat.parser().merge(saveOrderExeActionReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return SaveOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("accountId is empty.");
            return SaveOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 查找 MedicationExecutionRecord, MedicationOrderGroup, List<MedicationExecutionAction>
        final Long medExeRecId = req.getMedExeRecId();
        MedicationExecutionRecord exeRec = ordExecutor.getExeRecord(medExeRecId);
        if (exeRec == null) {
            log.error("ExecutionRecord not found: ", medExeRecId);
            return SaveOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.EXECUTION_RECORD_NOT_FOUND))
                .build();
        }

        MedicationOrderGroup orderGroup = ordGroupGenerator.getOrderGroup(exeRec.getMedicationOrderGroupId());
        if (orderGroup == null) {
            log.error("OrderGroup not found: ", exeRec.getMedicationOrderGroupId());
            return SaveOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ORDER_GROUP_NOT_FOUND))
                .build();
        }

        final Long actionId = req.getActionId();
        List<MedicationExecutionAction> exeActions = ordExecutor.getExeActions(medExeRecId);
        exeActions.sort(Comparator.comparing(MedicationExecutionAction::getId));
        MedicationExecutionAction targetAction = null;
        List<MedicationExecutionAction> baseExeActions = exeActions;
        if (actionId > 0) {
            targetAction = ordExecutor.getExeAction(actionId);
            if (targetAction == null || Boolean.TRUE.equals(targetAction.getIsDeleted())) {
                log.error("ExecutionAction not found: ", actionId);
                return SaveOrderExeActionResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.EXECUTION_ACTION_NOT_FOUND))
                    .build();
            }
            if (!medExeRecId.equals(targetAction.getMedicationExecutionRecordId())) {
                log.error("ExecutionAction does not belong to record: ", actionId, medExeRecId);
                return SaveOrderExeActionResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INVALID_PARAM_VALUE))
                    .build();
            }
            MedicationExecutionAction lastAction = exeActions.isEmpty() ? null : exeActions.get(exeActions.size() - 1);
            if (lastAction == null || !actionId.equals(lastAction.getId())) {
                log.error("ActionId is not the last one: ", actionId);
                return SaveOrderExeActionResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACTION_ID_NOT_LAST))
                    .build();
            }
            baseExeActions = exeActions.subList(0, exeActions.size() - 1);
        }

        final LocalDateTime createdAtUtc = TimeUtils.fromIso8601String(req.getCreatedAtIso8601(), "UTC");
        if (!baseExeActions.isEmpty()) {
            LocalDateTime lastActionTime = baseExeActions.get(baseExeActions.size() - 1).getCreatedAt();
            if (createdAtUtc.isBefore(lastActionTime)) {
                log.error("ActionTime is before lastActionTime: ", createdAtUtc, lastActionTime);
                return SaveOrderExeActionResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACTION_TIME_BEFORE_LAST_ACTION_TIME))
                    .build();
            }
        }

        // 通过用药途径是否是持续用药, 检查执行动作是否合规
        final Integer actionType = req.getActionType();
        final boolean isCompleteAction = actionType.equals(ACTION_TYPE_COMPLETE);
        final Map<String, RouteDetails> routeDetailsMap = routeRepo.findRouteDetailsByDeptId(orderGroup.getDeptId())
            .stream().collect(Collectors.toMap(RouteDetails::getCode, rd -> rd));
        final Boolean isContinuous = medMonitoringService.isRouteContinuous(routeDetailsMap, orderGroup, exeRec);
        List<Integer> qualifiedActionTypes = getNextActionTypes(isContinuous, baseExeActions);
        if (!qualifiedActionTypes.contains(actionType)) {
            log.error("ActionType is not qualified: ", actionType);
            return SaveOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACTION_TYPE_NOT_QUALIFIED))
                .build();
        }
        if (req.getAdministrationRate() < 0 || req.getIntakeVolMl() < 0) {
            log.error("AdministrationRate or IntakeVolMl is negative: ", req.getAdministrationRate(), req.getIntakeVolMl());
            return SaveOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ADMINISTRATION_RATE_OR_INTAKE_VOL_NEGATIVE))
                .build();
        }

        // 如果是单次用药（快推等），检查用药量是否超过最大值
        MedicationDosageGroupPB dosageGroupPb = medMonitoringService.getDosageGroupPB(orderGroup, exeRec);
        MedMonitoringService.FluidIntakeData intakeData = medMonitoringService
            .calcFluidIntakeImpl(isContinuous, dosageGroupPb, baseExeActions, createdAtUtc);
        if (intakeData.leftMl - req.getIntakeVolMl() < -EPSILON) {
            log.error("Intake liquid is more that the remaining intake: {}, {}",
                req.getIntakeVolMl(), intakeData.leftMl);
            return SaveOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INTAKE_LIQUID_MORE_THAN_REMAINING))
                .build();
        }

        String medicationRateStr = ProtoUtils.encodeDosageGroupExt(req.getDosageGroupExt());
        List<MedicationExecutionAction> oldExeActions = new ArrayList<>(exeActions);
        MedicationExecutionAction savedAction;
        List<MedicationExecutionAction> updatedExeActions = new ArrayList<>(exeActions);
        if (actionId > 0) {
            targetAction.setActionType(actionType);
            targetAction.setAdministrationRate(
                req.getAdministrationRate() <= 0 ? null : req.getAdministrationRate());
            targetAction.setMedicationRate(medicationRateStr);
            targetAction.setIntakeVolMl(req.getIntakeVolMl() <= 0 ? null : req.getIntakeVolMl());
            targetAction.setCreatedAt(createdAtUtc);
            targetAction.setModifiedAt(TimeUtils.getNowUtc());
            savedAction = ordExecutor.updateExeAction(targetAction);
            updatedExeActions.set(updatedExeActions.size() - 1, savedAction);
        } else {
            savedAction = ordExecutor.addExeAction(
                accountId, accountName, medExeRecId, actionType, req.getAdministrationRate(),
                medicationRateStr, req.getIntakeVolMl(), createdAtUtc
            );
            updatedExeActions.add(savedAction);
        }

        // 更新exeRecord统计信息
        boolean updateExeRecord = false;
        if (baseExeActions.isEmpty()) {
            exeRec.setStartTime(createdAtUtc);
            updateExeRecord = true;
        }
        if (isCompleteAction) {
            exeRec.setEndTime(createdAtUtc);
            updateExeRecord = true;
        } else if (actionId > 0 && exeRec.getEndTime() != null) {
            exeRec.setEndTime(null);
            updateExeRecord = true;
        }
        if (updateExeRecord) {
            ordExecutor.updateExeRecord(exeRec);
        }

        if (actionId > 0) {
            MedicationExecutionAction lastOldAction = oldExeActions.isEmpty()
                ? null
                : oldExeActions.get(oldExeActions.size() - 1);
            if (lastOldAction != null && ACTION_TYPE_COMPLETE.equals(lastOldAction.getActionType())) {
                exeRecordStatRepo.deleteByExeRecordId(medExeRecId);
            }
        }

        // 重新计算药物剩余量, 更新小时输液统计
        ExecutionRecordPB exeRecPb = composeExeRecordPB(
            orderGroup.getAdministrationRouteCode(), orderGroup.getAdministrationRouteName(),
            isContinuous, dosageGroupPb, exeRec, updatedExeActions
        );
        updateExeRecordStats(exeRecPb);
        medReportUtils.setDirtyPatientNursingReports(
            orderGroup, exeRec, oldExeActions, updatedExeActions, routeDetailsMap, TimeUtils.getNowUtc(), accountId
        );

        return SaveOrderExeActionResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setExeRecord(exeRecPb)
            .build();
    }

    @Transactional
    public DelOrderExeActionResp delOrderExeAction(String delOrderExeActionReqJson) {
        final DelOrderExeActionReq req;
        try {
            DelOrderExeActionReq.Builder builder = DelOrderExeActionReq.newBuilder();
            JsonFormat.parser().merge(delOrderExeActionReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return DelOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return DelOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 查找MedicationExecutionAction, MedicationExecutionRecord, MedicationOrderGroup, List<MedicationExecutionAction>
        MedicationExecutionAction targetDelAction = ordExecutor.getExeAction(req.getMedExeActionId());
        boolean isCompleteAction = targetDelAction.getActionType().equals(ACTION_TYPE_COMPLETE);
        if (targetDelAction == null) {
            log.error("ExecutionAction not found: ", req.getMedExeActionId());
            return DelOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.EXECUTION_ACTION_NOT_FOUND))
                .build();
        }

        final Long medExeRecId = targetDelAction.getMedicationExecutionRecordId();
        MedicationExecutionRecord exeRec = ordExecutor.getExeRecord(medExeRecId);
        if (exeRec == null) {
            log.error("ExecutionRecord not found: ", medExeRecId);
            return DelOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.EXECUTION_RECORD_NOT_FOUND))
                .build();
        }

        MedicationOrderGroup orderGroup = ordGroupGenerator.getOrderGroup(exeRec.getMedicationOrderGroupId());
        if (orderGroup == null) {
            log.error("OrderGroup not found: ", exeRec.getMedicationOrderGroupId());
            return DelOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ORDER_GROUP_NOT_FOUND))
                .build();
        }

        // 只能删除执行记录的最后一个执行过程
        List<MedicationExecutionAction> exeActions = ordExecutor.getExeActions(medExeRecId);
        exeActions.sort(Comparator.comparing(MedicationExecutionAction::getId));
        MedicationExecutionAction lastAction = exeActions.size() <= 0 ? null : exeActions.get(exeActions.size() - 1);
        if (lastAction == null || !Long.valueOf(req.getMedExeActionId()).equals(lastAction.getId())) {
            log.error("ActionId is not the last one: ", req.getMedExeActionId());
            return DelOrderExeActionResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACTION_ID_NOT_LAST))
                .build();
        }
        final LocalDateTime deleteTime = TimeUtils.fromIso8601String(req.getDeleteTimeIso8601(), "UTC");
        ordExecutor.deleteExeAction(lastAction, accountId, deleteTime);
        List<MedicationExecutionAction> oldExeActions = new ArrayList<>(exeActions);
        exeActions.remove(exeActions.size() - 1);

        // 更新执行记录的开始和结束时间
        boolean updateExeRecord = false;
        Map<String, RouteDetails> routeDetailsMap = routeRepo.findRouteDetailsByDeptId(orderGroup.getDeptId())
            .stream().collect(Collectors.toMap(RouteDetails::getCode, rd -> rd));
        if (isCompleteAction) {
            exeRec.setEndTime(null);
            updateExeRecord = true;
            exeRecordStatRepo.deleteByExeRecordId(medExeRecId);
        }
        if (exeActions.size() == 0) {
            exeRec.setStartTime(null);
            updateExeRecord = true;
            // 删除对应的 医嘱入量
            RouteDetails route = medMonitoringService.getRouteDetails(routeDetailsMap, orderGroup, exeRec);
            if (route != null) {
                medMonitoringService.deleteIntakeRecords(
                    orderGroup.getPatientId(), orderGroup.getDeptId(), route.getCode(),
                    // todo(guzhenyu): 为什么是8？
                    exeRec.getPlanTime().minusHours(8), accountId
                );
            }
            
        }
        if (updateExeRecord) {
            ordExecutor.updateExeRecord(exeRec);
        }
        medReportUtils.setDirtyPatientNursingReports(
            orderGroup, exeRec, oldExeActions, exeActions, routeDetailsMap, TimeUtils.getNowUtc(), accountId
        );

        // 重新计算药物剩余量
        final Boolean isContinuous = medMonitoringService.isRouteContinuous(routeDetailsMap, orderGroup, exeRec);
        final MedicationDosageGroupPB dosageGroupPb = medMonitoringService.getDosageGroupPB(orderGroup, exeRec);
        final ExecutionRecordPB exeRecPb = composeExeRecordPB(
            orderGroup.getAdministrationRouteCode(), orderGroup.getAdministrationRouteName(),
            isContinuous, dosageGroupPb, exeRec, exeActions
        );

        return DelOrderExeActionResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setExeRecord(exeRecPb)
            .build();
    }

    public GenericResp updateExeRecord(String updateExeRecordReqJson) {
        final UpdateExeRecordReq req;
        try {
            UpdateExeRecordReq.Builder builder = UpdateExeRecordReq.newBuilder();
            JsonFormat.parser().merge(updateExeRecordReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 没有对应的执行过程 的执行记录才能被更新
        final Long medExeRecId = req.getMedExeRecId();
        MedicationExecutionRecord exeRec = ordExecutor.getExeRecord(medExeRecId);
        if (exeRec == null) {
            log.error("ExecutionRecord not found: ", medExeRecId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.EXECUTION_RECORD_NOT_FOUND))
                .build();
        }

        MedicationOrderGroup orderGroup = ordGroupGenerator.getOrderGroup(exeRec.getMedicationOrderGroupId());
        if (orderGroup == null) {
            log.error("OrderGroup not found: ", exeRec.getMedicationOrderGroupId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ORDER_GROUP_NOT_FOUND))
                .build();
        }

        List<MedicationExecutionAction> exeActions = ordExecutor.getExeActions(medExeRecId);
        if (exeActions.size() > 0) {
            log.error("ExecutionRecord has actions: ", medExeRecId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.EXECUTION_RECORD_HAS_ACTIONS))
                .build();
        }

        // 如果dosage_group发生变化，或者route有发生变化，才更新；否则，noop
        Boolean updated = false;
        MedicationDosageGroupPB targetDosageGroup = (
            req.getDosageGroup().getMdCount() > 0 &&
            !req.getDosageGroup().equals(ProtoUtils.decodeDosageGroup(orderGroup.getMedicationDosageGroup()))
        ) ? req.getDosageGroup() : null;
        MedicationDosageGroupPB curDosageGroup = ProtoUtils.decodeDosageGroup(exeRec.getMedicationDosageGroup());
        if (targetDosageGroup == null) {
            if (!StrUtils.isBlank(exeRec.getMedicationDosageGroup())) {
                exeRec.setMedicationDosageGroup(null);
                updated = true;
            }
        } else if (curDosageGroup == null || !targetDosageGroup.equals(curDosageGroup)) {
            exeRec.setMedicationDosageGroup(ProtoUtils.encodeDosageGroup(targetDosageGroup));
            updated = true;
        }

        // 更新用药途径
        String targetRouteCode = !StrUtils.isBlank(req.getAdministrationRouteCode()) ?
            req.getAdministrationRouteCode() :
            orderGroup.getAdministrationRouteCode();
        String targetRouteName = !StrUtils.isBlank(req.getAdministrationRouteName()) ?
            req.getAdministrationRouteName() :
            orderGroup.getAdministrationRouteName();
        if (!targetRouteCode.equals(exeRec.getAdministrationRouteCode()) ||
            !targetRouteName.equals(exeRec.getAdministrationRouteName())
        ) {
            exeRec.setAdministrationRouteCode(targetRouteCode);
            exeRec.setAdministrationRouteName(targetRouteName);
            updated = true;
        }

        // 更新用药途径是否为持续用药
        Boolean isRouteContinuous = medMonitoringService.isRouteContinuous(
            orderGroup.getDeptId(), targetRouteCode
        );
        if (!isRouteContinuous.equals(exeRec.getIsContinuous())) {
            exeRec.setIsContinuous(isRouteContinuous);
            updated = true;
        }

        // 更新用药途径
        Integer medicationChannel = req.getMedicationChannel();
        if (!medicationChannel.equals(exeRec.getMedicationChannel())) {
            exeRec.setMedicationChannel(medicationChannel);
            updated = true;
        }
        String medicationChannelName = req.getMedicationChannelName();
        if (!medicationChannelName.equals(exeRec.getMedicationChannelName())) {
            exeRec.setMedicationChannelName(medicationChannelName);
            updated = true;
        }

        // 更新 药物是否个人带入
        Boolean isPersonalMedications = req.getIsPersonalMedications() > 0;
        if (!exeRec.getIsPersonalMedications().equals(isPersonalMedications)) {
            exeRec.setIsPersonalMedications(isPersonalMedications);
            updated = true;
        }

        if (updated) {
            final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
            if (accountWithAutoId == null) {
                log.error("accountId is empty.");
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
            }
            final String accountId = accountWithAutoId.getFirst();

            ordExecutor.updateExeRecord(exeRec);
            log.info(
                "ExecutionRecord updated (by ", accountId, "): ", medExeRecId,
                " dosageGroup:\n", ProtoUtils.protoToTxt(req.getDosageGroup()),
                "\nrouteCode: ", req.getAdministrationRouteCode(), "\nrouteName: ", req.getAdministrationRouteName()
            );
            log.info("ExecutionRecord updated (by {}, exe record - {}, dosageGroup: \n{},\n routeCode: {})",
                accountId, medExeRecId, ProtoUtils.protoToTxt(req.getDosageGroup()), req.getAdministrationRouteCode());
        }

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GenericResp delExeRecord(String delExeRecordReqJson) {
        final DelExeRecordReq req;
        try {
            DelExeRecordReq.Builder builder = DelExeRecordReq.newBuilder();
            JsonFormat.parser().merge(delExeRecordReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 没有对应的执行过程 的执行记录才能被更新
        final Long medExeRecId = req.getMedExeRecId();
        MedicationExecutionRecord exeRec = ordExecutor.getExeRecord(medExeRecId);
        if (exeRec == null) {
            log.error("ExecutionRecord not found: ", medExeRecId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.EXECUTION_RECORD_NOT_FOUND))
                .build();
        }

        List<MedicationExecutionAction> exeActions = ordExecutor.getExeActions(medExeRecId);
        if (exeActions.size() > 0) {
            log.error("ExecutionRecord has actions: exe_record_id = {}", medExeRecId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.EXECUTION_RECORD_HAS_ACTIONS))
                .build();
        }

        // 获取当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 删除
        MedicationOrderGroup orderGroup = ordGroupGenerator.getOrderGroup(exeRec.getMedicationOrderGroupId());
        if (orderGroup == null) {
            log.error("OrderGroup not found: ", exeRec.getMedicationOrderGroupId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ORDER_GROUP_NOT_FOUND))
                .build();
        }
        ordExecutor.deleteExeRecord(
            exeRec, accountId,
            TimeUtils.fromIso8601String(req.getDeleteTimeIso8601(), "UTC"),
            req.getDeleteReason(),
            ProtoUtils.decodeDosageGroup(orderGroup.getMedicationDosageGroup()).getDisplayName());

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GetDeletedExeRecordsResp getDeletedExeRecords(String getDeletedExeRecordsReqJson) {
        final GetDeletedExeRecordsReq req;
        try {
            GetDeletedExeRecordsReq.Builder builder = GetDeletedExeRecordsReq.newBuilder();
            JsonFormat.parser().merge(getDeletedExeRecordsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDeletedExeRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final PatientRecord patientRecord = patientService.getPatientRecordInIcu(req.getPatientId());
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", req.getPatientId());
            return GetDeletedExeRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_IN_ICU))
                .build();
        }

        // 账号id => 账号名称
        Map<String, String> accountIdNameMap = new HashMap<>();
        for (Account account : accountRepo.findAll()) {
            accountIdNameMap.put(account.getId().toString(), account.getName());
        }

        // 查询删除的执行记录
        List<GetDeletedExeRecordsResp.DeletedRecord> deletedRecordList = new ArrayList<>();
        List<MedicationExecutionRecord> deletedExeRecords = ordExecutor.getDeletedExeRecords(
            patientRecord, TimeUtils.fromIso8601String(req.getRetrieveTimeIso8601(), "UTC"));
        for (MedicationExecutionRecord exeRec : deletedExeRecords) {
            final String deleteAccountId = exeRec.getDeleteAccountId();
            final String deleteAccountName = accountIdNameMap.getOrDefault(deleteAccountId, deleteAccountId);
            deletedRecordList.add(GetDeletedExeRecordsResp.DeletedRecord.newBuilder()
                .setMedExeRecId(exeRec.getId())
                .setDosageGroupDisplayName(ProtoUtils.decodeDosageGroup(exeRec.getMedicationDosageGroup()).getDisplayName())
                .setPlanTimeIso8601(TimeUtils.toIso8601String(exeRec.getPlanTime(), ZONE_ID))
                .setDeleteTimeIso8601(TimeUtils.toIso8601String(exeRec.getDeleteTime(), ZONE_ID))
                .setDeleteAccountId(deleteAccountName)
                .setDeleteReason(exeRec.getDeleteReason())
                .build());
        }
        deletedRecordList.sort(Comparator
            .comparing(GetDeletedExeRecordsResp.DeletedRecord::getDosageGroupDisplayName)
            .thenComparing(GetDeletedExeRecordsResp.DeletedRecord::getMedExeRecId));

        return GetDeletedExeRecordsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllDeletedRecord(deletedRecordList)
            .build();
    }

    public GenericResp revertDeletedExeRecord(String revertDeletedExeRecordReqJson) {
        final RevertDeletedExeRecordReq req;
        try {
            RevertDeletedExeRecordReq.Builder builder = RevertDeletedExeRecordReq.newBuilder();
            JsonFormat.parser().merge(revertDeletedExeRecordReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查询执行记录是否处于被删除状态
        MedicationExecutionRecord exeRec = ordExecutor.getExeRecord(req.getMedExeRecId());
        if (exeRec == null || !exeRec.getIsDeleted()) {
            log.error("ExecutionRecord (deleted) not found: ", req.getMedExeRecId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.EXECUTION_RECORD_NOT_FOUND))
                .build();
        }

        // 恢复删除，修正medication_dosage_group
        exeRec.setIsDeleted(false);
        exeRec.setDeleteTime(null);
        exeRec.setDeleteAccountId(null);
        if ( ProtoUtils.decodeDosageGroup(exeRec.getMedicationDosageGroup()).getMdCount() == 0) {
            exeRec.setMedicationDosageGroup(null);
        }
        ordExecutor.updateExeRecord(exeRec);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public LookupMedicationResp lookupMedication(String lookupMedicationReqJson) {
        final LookupMedicationReq req;
        try {
            LookupMedicationReq.Builder builder = LookupMedicationReq.newBuilder();
            JsonFormat.parser().merge(lookupMedicationReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return LookupMedicationResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<MedicationDosagePB> dosages = medDict.lookupMedication(req.getQuery());
        return LookupMedicationResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllMedication(dosages)
            .build();
    }

    @Transactional
    public LookupMedicationV2Resp lookupMedicationV2(String lookupMedicationReqJson) {
        final LookupMedicationReq req;
        try {
            LookupMedicationReq.Builder builder = LookupMedicationReq.newBuilder();
            JsonFormat.parser().merge(lookupMedicationReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return LookupMedicationV2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        return LookupMedicationV2Resp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllMedication(medDict.lookupMedicationPB(req.getQuery()))
            .build();
    }

    @Transactional
    public GenericResp addMedication(String addMedicationReqJson) {
        final AddMedicationReq req;
        try {
            AddMedicationReq.Builder builder = AddMedicationReq.newBuilder();
            JsonFormat.parser().merge(addMedicationReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查药物信息
        MedicationPB medPb = req.getMedication();
        if (StrUtils.isBlank(medPb.getCode()) && StrUtils.isBlank(medPb.getName())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MEDICATION_CODE_AND_NAME_ARE_BLANK))
                .build();
        }

        // 检查是否已存在相同的药物
        String medicationCode = StrUtils.isBlank(medPb.getCode()) ? medPb.getName() : medPb.getCode();
        Medication med = medRepo.findByCode(medicationCode).orElse(null);
        if (med != null) {
            log.warn("Medication already exists: code={}, name={}", medPb.getCode(), medPb.getName());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MEDICATION_ALREADY_EXISTS))
                .build();
        }

        // 创建新的药物
        med = fromProto(medPb, accountId);
        med = medRepo.save(med);
        log.info("New medication added: code={}, name={}", med.getCode(), med.getName());

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    private Medication fromProto(MedicationPB medPb, String accountId) {
        if (medPb == null) return null;
        String medicationCode = medPb.getCode();
        String medicationName = medPb.getName();
        if (StrUtils.isBlank(medicationCode)) medicationCode = medicationName;
        if (StrUtils.isBlank(medicationName)) medicationName = medicationCode;

        return Medication.builder()
            .code(medicationCode)
            .name(medicationName)
            .spec(medPb.getSpec())
            .dose(medPb.getDose())
            .doseUnit(medPb.getDoseUnit())
            .packageCount(medPb.getPackageCount())
            .packageUnit(medPb.getPackageUnit())
            .shouldCalculateRate(medPb.getShouldCalculateRate())
            .type(medPb.getMedicationType())
            .company(medPb.getCompany())
            .price(medPb.getPrice())
            .createdAt(TimeUtils.getNowUtc())
            .createdBy(accountId)
            .confirmed(false)
            .build();
    }

    @Transactional
    public GenericResp updateMedication(String updateMedicationReqJson) {
        final UpdateMedicationReq req;
        try {
            UpdateMedicationReq.Builder builder = UpdateMedicationReq.newBuilder();
            JsonFormat.parser().merge(updateMedicationReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找当前用户
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查药物信息
        MedicationPB medPb = req.getMedication();
        if (StrUtils.isBlank(medPb.getCode()) && StrUtils.isBlank(medPb.getName())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MEDICATION_CODE_AND_NAME_ARE_BLANK))
                .build();
        }

        // 查找药物
        Medication origMedication = medRepo.findByCode(req.getOrigCode()).orElse(null);
        if (origMedication == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MEDICATION_NOT_FOUND))
                .build();
        }

        // 如果原药物代码和更新的药物代码相同，直接更新
        if (req.getOrigCode().equals(medPb.getCode())) {
            medRepo.save(fromProto(medPb, accountId));
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        // 检查是否已存在相同的药物
        Medication dupMed = medRepo.findByCode(medPb.getCode()).orElse(null);
        if (dupMed != null) {
            log.warn("Medication already exists: code={}, name={}", medPb.getCode(), medPb.getName());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MEDICATION_ALREADY_EXISTS))
                .build();
        }

        // 更新药物信息(删除原来药物，新增新药物)
        medRepo.delete(origMedication);
        medRepo.save(fromProto(medPb, accountId));
        log.info("Medication updated: code={}, name={}", medPb.getCode(), medPb.getName());

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteMedication(String deleteMedicationReqJson) {
        final DeleteMedicationReq req;
        try {
            DeleteMedicationReq.Builder builder = DeleteMedicationReq.newBuilder();
            JsonFormat.parser().merge(deleteMedicationReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 查找药物
        Medication medication = medRepo.findByCode(req.getCode()).orElse(null);
        if (medication == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }

        medRepo.delete(medication);
        log.info("Medication deleted: code={}, name={}", medication.getCode(), medication.getName());

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public LookupRouteResp lookupRoute(String lookupRouteReqJson) {
        final LookupRouteReq req;
        try {
            LookupRouteReq.Builder builder = LookupRouteReq.newBuilder();
            JsonFormat.parser().merge(lookupRouteReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return LookupRouteResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<AdministrationRoutePB> routes = medDict.lookupAdministrationRoute(
            req.getDeptId(), req.getQuery()
        );
        return LookupRouteResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRoute(routes)
            .build();
    }

    @Transactional
    public AddRouteResp addRoute(String addRouteReqJson) {
        final AddRouteReq req;
        try {
            AddRouteReq.Builder builder = AddRouteReq.newBuilder();
            JsonFormat.parser().merge(addRouteReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddRouteResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return AddRouteResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查用药途径信息
        AdministrationRoutePB routePb = req.getRoute();
        if (StrUtils.isBlank(routePb.getCode()) || StrUtils.isBlank(routePb.getName())) {
            return AddRouteResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ROUTE_CODE_OR_NAME_ARE_BLANK))
                .build();
        }
        String deptId = req.getDeptId();

        // 检查是否已存在相同的用药途径
        AdministrationRoute route = routeRepo.findByDeptIdAndCode(deptId, routePb.getCode()).orElse(null);
        if (route != null) {
            log.warn("AdministrationRoute already exists: code={}, name={}", routePb.getCode(), routePb.getName());
            return AddRouteResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ROUTE_ALREADY_EXISTS))
                .build();
        }

        // 创建新的用药途径
        route = AdministrationRoute.builder()
            .deptId(deptId)
            .code(routePb.getCode())
            .name(routePb.getName())
            .isContinuous(routePb.getIsContinuous() > 0)
            .groupId(routePb.getGroupId())
            .intakeTypeId(routePb.getIntakeTypeId())
            .isValid(true)
            .build();
        route = routeRepo.save(route);
        log.info("New administration route added: code={}, name={}, by {}", route.getCode(), route.getName(), accountId);

        return AddRouteResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(0)
            .build();
    }

    @Transactional
    public GenericResp updateRoute(String updateRouteReqJson) {
        final UpdateRouteReq req;
        try {
            UpdateRouteReq.Builder builder = UpdateRouteReq.newBuilder();
            JsonFormat.parser().merge(updateRouteReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查用药途径信息
        AdministrationRoutePB routePb = req.getRoute();
        if (StrUtils.isBlank(routePb.getCode()) || StrUtils.isBlank(routePb.getName())) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ROUTE_CODE_OR_NAME_ARE_BLANK))
                .build();
        }
        Integer routeId = req.getId();

        // 查找用药途径
        AdministrationRoute route = routeRepo.findById(routeId).orElse(null);
        if (route == null) {
            log.error("AdministrationRoute not found: id={}", routeId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ROUTE_NOT_FOUND))
                .build();
        } else if (!route.getCode().equals(routePb.getCode())) {
            // 如果用药途径代码发生变化，检查是否已存在相同的用药途径
            AdministrationRoute dupRoute = routeRepo.findByDeptIdAndCode(route.getDeptId(), routePb.getCode()).orElse(null);
            if (dupRoute != null) {
                log.warn("AdministrationRoute already exists: code={}, name={}", routePb.getCode(), routePb.getName());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ROUTE_ALREADY_EXISTS))
                    .build();
            }
        }

        // 更新用药途径信息
        route.setCode(routePb.getCode());
        route.setName(routePb.getName());
        route.setIsContinuous(routePb.getIsContinuous() > 0);
        route.setGroupId(routePb.getGroupId());
        route.setIntakeTypeId(routePb.getIntakeTypeId());
        route.setIsValid(true);
        routeRepo.save(route);
        log.info("AdministrationRoute updated: id={}, code={}, name={}, by {}",
            route.getId(), route.getCode(), route.getName(), accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteRoute(String deleteRouteReqJson) {
        final DeleteRouteReq req;
        try {
            DeleteRouteReq.Builder builder = DeleteRouteReq.newBuilder();
            JsonFormat.parser().merge(deleteRouteReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 查找用药途径
        Integer routeId = req.getId();
        AdministrationRoute route = routeRepo.findById(routeId).orElse(null);
        if (route == null) {
            log.error("AdministrationRoute not found: id={}", routeId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
        }
        routeRepo.delete(route);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public LookupFreqResp lookupFreq(String lookupFreqReqJson) {
        LookupFreqReq req;
        try {
            req = ProtoUtils.parseJsonToProto(lookupFreqReqJson, LookupFreqReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return LookupFreqResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        final Boolean onlyNursingOrderFreq = (req.getOnlyNursingOrderFreq() > 0);
        List<StringIdEntityPB> freqList = new ArrayList<>();
        for (MedicationFrequency freq : medFreqRepo.findByIsDeletedFalse()) {
            if (!onlyNursingOrderFreq || freq.getSupportNursingOrder()) {
                freqList.add(StringIdEntityPB.newBuilder()
                    .setId(freq.getCode())
                    .setName(freq.getName())
                    .build());
            }
        }

        return LookupFreqResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllFreq(freqList)
            .build();
    }

    @Transactional
    public LookupFreqV2Resp lookupFreqV2(String lookupFreqReqJson) {
        LookupFreqReq req;
        try {
            req = ProtoUtils.parseJsonToProto(lookupFreqReqJson, LookupFreqReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return LookupFreqV2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        final Boolean onlyNursingOrderFreq = (req.getOnlyNursingOrderFreq() > 0);
        List<MedicationFrequencySpec> freqList = new ArrayList<>();
        for (MedicationFrequency freq : medFreqRepo.findByIsDeletedFalse()) {
            if (!onlyNursingOrderFreq || freq.getSupportNursingOrder()) {
                MedicationFrequencySpec specPb = ProtoUtils.decodeFreqSpec(freq.getFreqSpec());
                if (specPb == null) {
                    log.error("Failed to decode frequency spec for freq: {}", freq.getCode());
                    continue;
                }
                specPb = specPb.toBuilder().setId(freq.getId()).build();
                freqList.add(specPb);
            }
        }
        freqList.sort(Comparator.comparing(MedicationFrequencySpec::getCode));

        return LookupFreqV2Resp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllFreq(freqList)
            .build();
    }

    @Transactional
    public AddFreqResp addFreq(String addFreqReqJson) {
        final AddFreqReq req;
        try {
            AddFreqReq.Builder builder = AddFreqReq.newBuilder();
            JsonFormat.parser().merge(addFreqReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddFreqResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return AddFreqResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        MedicationFrequencySpec freqPb = req.getFreq();
        // 检查频率信息
        MedicationFrequency freq = medFreqRepo.findByCodeAndIsDeletedFalse(freqPb.getCode()).orElse(null);
        if (freq != null) {
            log.warn("MedicationFrequency already exists: code={}, name={}", freq.getCode(), freq.getName());
            return AddFreqResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.FREQUENCY_ALREADY_EXISTS))
                .build();
        }

        // 创建新的频率
        freq = MedicationFrequency.builder()
            .code(freqPb.getCode())
            .name(freqPb.getName())
            .freqSpec(ProtoUtils.encodeFreqSpec(freqPb))
            .supportNursingOrder(freqPb.getSupportNursingOrder() > 0)
            .isDeleted(false)
            .build();
        freq = medFreqRepo.save(freq);
        log.info("New medication frequency added: code={}, name={}, by {}", freq.getCode(), freq.getName(), accountId);

        return AddFreqResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setId(freq.getId())
            .build();
    }

    @Transactional
    public GenericResp updateFreq(String updateFreqReqJson) {
        final UpdateFreqReq req;
        try {
            UpdateFreqReq.Builder builder = UpdateFreqReq.newBuilder();
            JsonFormat.parser().merge(updateFreqReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        MedicationFrequencySpec freqPb = req.getFreq();
        // 检查频率信息
        MedicationFrequency freq = medFreqRepo.findByIdAndIsDeletedFalse(freqPb.getId()).orElse(null);
        if (freq == null) {
            log.error("MedicationFrequency not found: id={}", freqPb.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.FREQUENCY_NOT_FOUND))
                .build();
        } else if (!freq.getCode().equals(freqPb.getCode())) {
            // 如果频率代码发生变化，检查是否已存在相同的频率
            MedicationFrequency dupFreq = medFreqRepo.findByCodeAndIsDeletedFalse(freqPb.getCode()).orElse(null);
            if (dupFreq != null && dupFreq.getId() != freq.getId()) {
                log.warn("MedicationFrequency already exists: code={}, name={}", freqPb.getCode(), freqPb.getName());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.FREQUENCY_ALREADY_EXISTS))
                    .build();
            }
        }

        // 更新频率信息
        freq.setCode(freqPb.getCode());
        freq.setName(freqPb.getName());
        freq.setFreqSpec(ProtoUtils.encodeFreqSpec(freqPb));
        freq.setSupportNursingOrder(freqPb.getSupportNursingOrder() > 0);
        medFreqRepo.save(freq);
        log.info("MedicationFrequency updated: id={}, code={}, name={}, by {}",
            freq.getId(), freq.getCode(), freq.getName(), accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteFreq(String deleteFreqReqJson) {
        final DeleteFreqReq req;
        try {
            DeleteFreqReq.Builder builder = DeleteFreqReq.newBuilder();
            JsonFormat.parser().merge(deleteFreqReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 检查频率信息
        MedicationFrequency freq = medFreqRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (freq == null) {
            log.error("MedicationFrequency not found: id={}", req.getId());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.FREQUENCY_NOT_FOUND))
                .build();
        }

        // 删除频率
        freq.setIsDeleted(true);
        freq.setDeletedBy(accountId);
        freq.setDeletedAt(TimeUtils.getNowUtc());
        medFreqRepo.save(freq);
        log.info("MedicationFrequency deleted: id={}, code={}, name={}, by {}",
            freq.getId(), freq.getCode(), freq.getName(), accountId);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    public GetDosageGroupExtResp getDosageGroupExt(String getDosageGroupExtReqJson) {
        final GetDosageGroupExtReq req;
        try {
            GetDosageGroupExtReq.Builder builder = GetDosageGroupExtReq.newBuilder();
            JsonFormat.parser().merge(getDosageGroupExtReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDosageGroupExtResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取参数
        MedicationDosageGroupPB dosageGroup = req.getDosageGroup();
        Long pid = req.getPid();

        // 查找患者
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("PatientRecord not found: ", pid);
            return GetDosageGroupExtResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        // 评估药物类型：固体/液体/不确定；固体重量；液体容量等)
        MedicationDosageGroupPB.Builder targetDGBuilder = MedicationDosageGroupPB.newBuilder();
        for (MedicationDosagePB md : dosageGroup.getMdList()) {
            MedicationDosagePB.Builder mdBuilder = md.toBuilder();
            DosageFormTypeEnum dosageFormType = DosageFormTypeEnum.DFT_UNKNOWN;

            // todo(guzhenyu): 同时有液体量&规格时，不算固体量
            double solidMg = medDict.calculateSolidMg(md.getSpec(), md.getDose(), md.getDoseUnit());
            if (solidMg > 0) {
                mdBuilder.setSolidMg(solidMg);
                dosageFormType = DosageFormTypeEnum.DFT_SOLID;
            }

            double liquidMl = medDict.calculateLiquidVolume(md.getSpec(), md.getDose(), md.getDoseUnit());
            if (md.getIntakeVolMl() > 0 || liquidMl > 0) {
                if (md.getIntakeVolMl() <= 0) mdBuilder.setIntakeVolMl(liquidMl);
                if (dosageFormType == DosageFormTypeEnum.DFT_UNKNOWN) dosageFormType = DosageFormTypeEnum.DFT_LIQUID;
            }
            targetDGBuilder.addMd(mdBuilder.setFormType(dosageFormType).build());
        }
        targetDGBuilder.setDisplayName(dosageGroup.getDisplayName());
        MedicationDosageGroupPB targetDosageGroup = targetDGBuilder.build();

        // 计算相关总量
        double totalMl = 0;
        double totalMg = 0;
        int solidMedCount = 0;
        int unknownMedCount = 0;
        for (MedicationDosagePB md : targetDosageGroup.getMdList()) {
            totalMl += md.getIntakeVolMl();
            totalMg += md.getSolidMg();
            if (md.getFormType() == DosageFormTypeEnum.DFT_SOLID) {
                solidMedCount += 1;
            } else if (md.getFormType() == DosageFormTypeEnum.DFT_UNKNOWN ||
                md.getFormType() == DosageFormTypeEnum.DFT_UNSPECIFIED
            ) {
                unknownMedCount += 1;
            }
        }
        DosageGroupExtPB dosageGroupExt = DosageGroupExtPB.newBuilder()
            .setTotalMl(totalMl)
            .setSolidAmount(totalMg)
            .setSolidUnit(Consts.UNIT_MG)
            .setSolidChangable(solidMedCount > 1)  // 多种固体药物，可调整
            .setWeightKg(patientRecord.getWeight())
            .build();

        return GetDosageGroupExtResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setDosageGroup(targetDosageGroup)
            .setDosageGroupExt(dosageGroupExt)
            .build();
    }

    public CalcMedRateResp calcMedRate(String calcMedRateReqJson) {
        final CalcMedRateReq req;
        try {
            CalcMedRateReq.Builder builder = CalcMedRateReq.newBuilder();
            JsonFormat.parser().merge(calcMedRateReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return CalcMedRateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取参数
        boolean useAdminRateAsInput = req.getUseInfusionRateAsInput();
        DosageGroupExtPB dosageGroupExt = req.getDosageGroupExt();

        // 计算结果
        DosageGroupExtPB resultDosageGroupExt = medDict.calcMedRate(dosageGroupExt, useAdminRateAsInput);
        if (resultDosageGroupExt == null) {
            return CalcMedRateResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.CALC_MED_RATE_FAILED))
                .build();
        }

        return CalcMedRateResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setDosageGroupExt(resultDosageGroupExt)
            .build();
    }

    private GetOrderGroupsResp composeGetOrderGroupsResp(
        MedOrderGroupSettingsPB medOgSettings, PatientRecord patientRecord,
        LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        // 获取执行记录, 医嘱, 执行过程
        List<MedicationExecutionRecord> records = ordExecutor.retrieveExecutionRecordsByPatientId(
            patientRecord.getId(), medOgSettings.getNotStartedExeRecAdvanceHours(), queryStartUtc, queryEndUtc);
        List<MedicationOrderGroup> groups = ordExecutor.getMedicationOrderGroupsByRecords(records);
        Map<Long, List<MedicationExecutionAction>> exeActionMap = ordExecutor.getExeActionsByRecords(records);

        // 将记录分组，组内按照记录的id排序
        records = records.stream().sorted(Comparator.comparing(MedicationExecutionRecord::getId)).toList();
        Map<Long /*groupId*/, List<MedicationExecutionRecord>> exeRecordMap = new HashMap<>();
        for (MedicationExecutionRecord record : records) {
            exeRecordMap.computeIfAbsent(record.getMedicationOrderGroupId(), k -> new ArrayList<>()).add(record);
        }

        // 组装GetOrderGroupsResp
        GetOrderGroupsResp.Builder builder = GetOrderGroupsResp.newBuilder();
        List<OrderGroupPB> orderGroupPBList = new ArrayList<>();
        for (int i = 0; i < groups.size(); ++i) {
            orderGroupPBList.add(composeOrderGroupPB(
                groups.get(i),
                exeRecordMap.computeIfAbsent(groups.get(i).getId(), k -> new ArrayList<>()),
                exeActionMap
            ));
        }
        orderGroupPBList.sort(Comparator.comparing(OrderGroupPB::getRouteGroupId)
            .thenComparing(orderGroupPB -> orderGroupPB.getMedOrderGroup().getDosageGroup().getDisplayName()));

        return builder.setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllOrderGroup(orderGroupPBList).build();
    }

    private OrderGroupPB composeOrderGroupPB(
        MedicationOrderGroup orderGroup,
        List<MedicationExecutionRecord> exeRecords,
        Map<Long, List<MedicationExecutionAction>> exeActionMap
    ) {
        OrderGroupPB.Builder orderGroupBuilder = OrderGroupPB.newBuilder();
        orderGroupBuilder.setRouteGroupId(medDict.getAdministrationRouteGroupId(
            orderGroup.getDeptId(), orderGroup.getAdministrationRouteCode()));
        final MedicationOrderGroupPB medOrderGroupPb = convert(orderGroup);
        orderGroupBuilder.setMedOrderGroup(medOrderGroupPb);

        boolean hasActions = false;
        boolean allCompleted = true;
        for (MedicationExecutionRecord exeRec : exeRecords) {
            final Boolean isRouteContinuous = medMonitoringService.isRouteContinuous(orderGroup, exeRec);

            List<MedicationExecutionAction> exeActions = exeActionMap.get(exeRec.getId());
            if (exeActions == null) {
                exeActions = new ArrayList<>();
            }

            hasActions = hasActions || exeActions.size() > 0;
            allCompleted = allCompleted && exeActions.stream()
                .anyMatch(exeAction -> exeAction.getActionType() == ACTION_TYPE_COMPLETE);

            MedicationDosageGroupPB dosageGroup = medMonitoringService
                .getDosageGroupPBOrDefault(exeRec, medOrderGroupPb.getDosageGroup());
            ExecutionRecordPB exeRecPb = composeExeRecordPB(
                orderGroup.getAdministrationRouteCode(), orderGroup.getAdministrationRouteName(),
                isRouteContinuous, dosageGroup, exeRec, exeActions
            );
            orderGroupBuilder.addExeRecord(exeRecPb);
        }

        if (!hasActions) orderGroupBuilder.setStatus(ORDER_GROUP_NOT_STARTED_TXT);
        else if (allCompleted) orderGroupBuilder.setStatus(ORDER_GROUP_COMPLETED_TXT);
        else orderGroupBuilder.setStatus(ORDER_GROUP_IN_PROGRESS_TXT);

        return orderGroupBuilder.build();
    }

    private ExecutionRecordPB composeExeRecordPB(
        String groupAdministrationRouteCode,
        String groupAdministrationRouteName,
        Boolean isRouteContinuous,
        MedicationDosageGroupPB dosageGroupPb,
        MedicationExecutionRecord exeRec,
        List<MedicationExecutionAction> exeActions
    ) {
        ExecutionRecordPB.Builder exeRecBuilder = ExecutionRecordPB.newBuilder();

        MedicationExecutionRecordPB exeRecPb = convert(
            exeRec, groupAdministrationRouteCode, groupAdministrationRouteName, isRouteContinuous
        );
        exeRecBuilder.setMedExeRec(exeRecPb);

        for (MedicationExecutionAction action : exeActions) {
            exeRecBuilder.addMedExeAction(convert(action));
        }

        FluidIntakePB fluidIntakePb = medMonitoringService
            .calcFluidIntake(isRouteContinuous, dosageGroupPb, exeActions, null);
        exeRecBuilder.setIntake(fluidIntakePb);

        List<Integer> nextActionTypes = getNextActionTypes(isRouteContinuous, exeActions);
        // 不过滤，尽管按照时间算剩余量为零，护士可能需要补之前没发生的动作（比如暂停）。
        // if (fluidIntakePb.getRemainingIntake().getMl() < EPSILON) {
        //     nextActionTypes = nextActionTypes.stream()
        //         .filter(actionType -> actionType == ACTION_TYPE_COMPLETE)
        //         .toList();
        // }
        exeRecBuilder.addAllNextActionType(nextActionTypes);

        return exeRecBuilder.build();
    }

    private MedicationOrderGroupPB convert(MedicationOrderGroup orderGroup) {
        String freqCode = orderGroup.getFreqCode();
        MedicationFrequency freq = medFreqRepo.findByCodeAndIsDeletedFalse(freqCode).orElse(null);
        String freqName = freq != null ? freq.getName() : (StrUtils.isBlank(freqCode) ? "" : freqCode);

        return MedicationOrderGroupPB.newBuilder()
            .setId(orderGroup.getId())
            .setHisPatientId(orderGroup.getHisPatientId())
            .setPatientId(orderGroup.getPatientId())
            .setGroupId(orderGroup.getGroupId())
            .setMedicalOrderIds(ProtoUtils.decodeOrderIds(orderGroup.getMedicalOrderIds()))
            .setHisMrn(orderGroup.getHisMrn())
            .setOrderingDoctor(orderGroup.getOrderingDoctor())
            .setOrderingDoctorId(orderGroup.getOrderingDoctorId())
            .setDepartmentId(orderGroup.getDeptId())
            .setOrderType(orderGroup.getOrderType())
            .setStatus(orderGroup.getStatus())
            .setOrderTimeIso8601(TimeUtils.toIso8601String(orderGroup.getOrderTime(), ZONE_ID))
            .setStopTimeIso8601(TimeUtils.toIso8601String(orderGroup.getStopTime(), ZONE_ID))
            .setCancelTimeIso8601(TimeUtils.toIso8601String(orderGroup.getCancelTime(), ZONE_ID))
            .setOrderValidity(orderGroup.getOrderValidity())
            .setInconsistencyExplanation(orderGroup.getInconsistencyExplanation())
            .setDosageGroup(ProtoUtils.decodeDosageGroup(orderGroup.getMedicationDosageGroup()))
            .setOrderDurationType(orderGroup.getOrderDurationType())
            .setFreqCode(freqCode)
            .setFreqName(freqName)
            .setPlanTimeIso8601(TimeUtils.toIso8601String(orderGroup.getPlanTime(), ZONE_ID))
            .setFirstDayExeCount(orderGroup.getFirstDayExeCount() == null ? 0 : orderGroup.getFirstDayExeCount())
            .setAdministrationRouteCode(orderGroup.getAdministrationRouteCode())
            .setAdministrationRouteName(orderGroup.getAdministrationRouteName())
            .setNote(StrUtils.isBlank(orderGroup.getNote()) ? "" : orderGroup.getNote())
            .setReviewer(orderGroup.getReviewer())
            .setReviewerId(orderGroup.getReviewerId())
            .setReviewTimeIso8601(TimeUtils.toIso8601String(orderGroup.getReviewTime(), ZONE_ID))
            .setCreatedAtIso8601(TimeUtils.toIso8601String(orderGroup.getCreatedAt(), ZONE_ID))
            .build();
    }

    private MedicationExecutionRecordPB convert(
        MedicationExecutionRecord exeRecord,
        String groupAdministrationRouteCode,
        String groupAdministrationRouteName,
        Boolean isRouteContinuous
    ) {
        String routeCode = !StrUtils.isBlank(exeRecord.getAdministrationRouteCode()) ?
            exeRecord.getAdministrationRouteCode() : groupAdministrationRouteCode;
        String routeName = !StrUtils.isBlank(exeRecord.getAdministrationRouteName()) ?
            exeRecord.getAdministrationRouteName() : groupAdministrationRouteName;
        Boolean isRouteContinuousForExeRec = exeRecord.getIsContinuous() != null ?
            exeRecord.getIsContinuous() : isRouteContinuous;
        MedicationDosageGroupPB exeDosageGroupPb = ProtoUtils.decodeDosageGroup(exeRecord.getMedicationDosageGroup());
        return MedicationExecutionRecordPB.newBuilder()
            .setId(exeRecord.getId())
            .setMedicationOrderGroupId(exeRecord.getMedicationOrderGroupId())
            .setHisOrderGroupId(exeRecord.getHisOrderGroupId())
            .setPlanTimeIso8601(TimeUtils.toIso8601String(exeRecord.getPlanTime(), ZONE_ID))
            .setStartTimeIso8601(TimeUtils.toIso8601String(exeRecord.getStartTime(), ZONE_ID))
            .setEndTimeIso8601(TimeUtils.toIso8601String(exeRecord.getEndTime(), ZONE_ID))
            .setIsDeleted(exeRecord.getIsDeleted() ? 1 : 0)
            .setDeleteAccountId(exeRecord.getDeleteAccountId())
            .setDeleteTimeIso8601(TimeUtils.toIso8601String(exeRecord.getDeleteTime(), ZONE_ID))
            .setDeleteReason(exeRecord.getDeleteReason())
            .setUserTouched(exeRecord.getUserTouched() ? 1 : 0)
            .setBarCode(exeRecord.getBarCode())
            .setHisExecuteId(exeRecord.getHisExecuteId())
            .setDosageGroup(exeDosageGroupPb)
            .setIsPersonalMedications(
                exeRecord.getIsPersonalMedications() == null ? 0 :
                (exeRecord.getIsPersonalMedications() ? 1 : 0)
            )
            .setAdministrationRouteCode(routeCode)
            .setAdministrationRouteName(routeName)
            .setIsContinuous(isRouteContinuousForExeRec)
            .setShouldCalculateRate(exeRecord.getShouldCalculateRate())
            .setMedicationChannel(exeRecord.getMedicationChannel())
            .setMedicationChannelName(exeRecord.getMedicationChannelName())
            .setComments(exeRecord.getComments())
            .setCreateAccountId(exeRecord.getCreateAccountId())
            .setCreatedAtIso8601(TimeUtils.toIso8601String(exeRecord.getCreatedAt(), ZONE_ID))
            .build();
    }

    private MedicationExecutionActionPB convert(MedicationExecutionAction exeAction) {
        MedicationExecutionActionPB.Builder builder = MedicationExecutionActionPB.newBuilder()
            .setId(exeAction.getId())
            .setMedicationExecutionRecordId(exeAction.getMedicationExecutionRecordId())
            .setCreateAccountId(exeAction.getCreateAccountId())
            .setCreateAccountName(exeAction.getCreateAccountName())
            .setCreatedAtIso8601(TimeUtils.toIso8601String(exeAction.getCreatedAt(), ZONE_ID))
            .setActionType(exeAction.getActionType())  // MedicationExecutionActionType.id
            .setAdministrationRate(exeAction.getAdministrationRate())
            .setIntakeVolMl(exeAction.getIntakeVolMl())
            .setIsDeleted(exeAction.getIsDeleted() ? 1 : 0)
            .setDeleteAccountId(exeAction.getDeleteAccountId())
            .setDeleteTimeIso8601(TimeUtils.toIso8601String(exeAction.getDeleteTime(), ZONE_ID));

        DosageGroupExtPB dosageGroupExtPb = null;
        if (!StrUtils.isBlank(exeAction.getMedicationRate())) {
            dosageGroupExtPb = ProtoUtils.decodeDosageGroupExt(exeAction.getMedicationRate());
        }
        if (dosageGroupExtPb != null) builder.setDosageGroupExt(dosageGroupExtPb);
        return builder.build();
    }

    private List<Integer> getNextActionTypes(
        Boolean isContinuous, List<MedicationExecutionAction> actions
    ) {
        Integer lastActionType = null;
        if (actions != null) {
            for (int i = actions.size() - 1; i >= 0; --i) {
                final Integer actionType = actions.get(i).getActionType();
                if (actionType != ACTION_TYPE_FAST_PUSH) {
                    lastActionType = actionType;
                    break;
                }
            }
        }

        List<Integer> nextActionTypes = new ArrayList<>();
        if (isContinuous) {
            if (lastActionType == null) {
                nextActionTypes.add(ACTION_TYPE_START);
            } else if (lastActionType == ACTION_TYPE_START) {
                nextActionTypes.add(ACTION_TYPE_PAUSE);
                nextActionTypes.add(ACTION_TYPE_ADJUST_SPEED);
                nextActionTypes.add(ACTION_TYPE_FAST_PUSH);
                nextActionTypes.add(ACTION_TYPE_COMPLETE);
            } else if (lastActionType == ACTION_TYPE_PAUSE) {
                nextActionTypes.add(ACTION_TYPE_RESUME);
                nextActionTypes.add(ACTION_TYPE_FAST_PUSH);
                nextActionTypes.add(ACTION_TYPE_COMPLETE);
            } else if (lastActionType == ACTION_TYPE_RESUME) {
                nextActionTypes.add(ACTION_TYPE_PAUSE);
                nextActionTypes.add(ACTION_TYPE_ADJUST_SPEED);
                nextActionTypes.add(ACTION_TYPE_FAST_PUSH);
                nextActionTypes.add(ACTION_TYPE_COMPLETE);
            } else if (lastActionType == ACTION_TYPE_ADJUST_SPEED) {
                nextActionTypes.add(ACTION_TYPE_PAUSE);
                nextActionTypes.add(ACTION_TYPE_ADJUST_SPEED);
                nextActionTypes.add(ACTION_TYPE_FAST_PUSH);
                nextActionTypes.add(ACTION_TYPE_COMPLETE);
            } else if (lastActionType != ACTION_TYPE_COMPLETE) {
                log.error("Unexpected action type: ", lastActionType);
            }
        } else {
            if (lastActionType != ACTION_TYPE_COMPLETE) {
                nextActionTypes.add(ACTION_TYPE_FAST_PUSH);
                nextActionTypes.add(ACTION_TYPE_COMPLETE);
            }
        }

        return nextActionTypes;
    }

    private String getActionTypeStrForTest(Integer type) {
        if (type.equals(ACTION_TYPE_START)) return "start";
        if (type.equals(ACTION_TYPE_PAUSE)) return "pause";
        if (type.equals(ACTION_TYPE_RESUME)) return "resume";
        if (type.equals(ACTION_TYPE_ADJUST_SPEED)) return "adjust_speed";
        if (type.equals(ACTION_TYPE_FAST_PUSH)) return "fast_push";
        if (type.equals(ACTION_TYPE_COMPLETE)) return "complete";
        return "unknown";
    }

    private void updateExeRecordStats(ExecutionRecordPB exeRecPb) {
        if (exeRecPb == null) return;

        // 判断最后一个action是否为complete
        List<MedicationExecutionActionPB> actions = exeRecPb.getMedExeActionList();
        boolean isComplete = false;
        if (!actions.isEmpty()) {
            MedicationExecutionActionPB lastAction = actions.get(actions.size() - 1);
            if (lastAction.getActionType() == ACTION_TYPE_COMPLETE) {
                isComplete = true;
            }
        }

        if (isComplete) {
            FluidIntakePB fluidIntake = exeRecPb.getIntake();
            List<MedicationExecutionRecordStat> statList = new ArrayList<>();
            for (FluidIntakePB.IntakeRecord intakeRecord : fluidIntake.getIntakeRecordList()) {
                statList.add(MedicationExecutionRecordStat.builder()
                    .groupId(exeRecPb.getMedExeRec().getMedicationOrderGroupId())
                    .exeRecordId(exeRecPb.getMedExeRec().getId())
                    .statsTime(TimeUtils.fromIso8601String(intakeRecord.getTimeIso8601(), "UTC"))
                    .consumedMl(intakeRecord.getMl())
                    .isFinal(false)
                    .remainMl(0.0)
                    .build()
                );
            }
            if (!statList.isEmpty()) {
                MedicationExecutionRecordStat stat = statList.get(statList.size() - 1);
                FluidIntakePB.IntakeRecord remainIntake = fluidIntake.getRemainingIntake();
                stat.setIsFinal(true);
                stat.setRemainMl(remainIntake.getMl());
            }
            exeRecordStatRepo.saveAll(statList);
        }
    }

    private static final double EPSILON = 1e-6;

    private final String ZONE_ID;

    private final Integer ACTION_TYPE_START;
    private final Integer ACTION_TYPE_PAUSE;
    private final Integer ACTION_TYPE_RESUME;
    private final Integer ACTION_TYPE_ADJUST_SPEED;
    private final Integer ACTION_TYPE_FAST_PUSH;
    private final Integer ACTION_TYPE_COMPLETE;

    final String ORDER_GROUP_NOT_STARTED_TXT;
    final String ORDER_GROUP_IN_PROGRESS_TXT;
    final String ORDER_GROUP_COMPLETED_TXT;

    private ConfigProtoService protoService;
    private ConfigShiftUtils shiftUtils;
    private MedicationConfig medConfig;
    private UserService userService;
    private PatientService patientService;
    private OrderGroupGenerator ordGroupGenerator;
    private OrderExecutor ordExecutor;
    private MedicationDictionary medDict;
    private MedMonitoringService medMonitoringService;
    private MedReportUtils medReportUtils;

    private MedicationExecutionRecordRepository medExeRecordRepo;
    private MedicationRepository medRepo;
    private AdministrationRouteRepository routeRepo;
    private MedicationFrequencyRepository medFreqRepo;
    private MedicationExecutionRecordStatRepository exeRecordStatRepo;
    private DeptSystemSettingsRepository deptSystemSettingsRepo;
    private AccountRepository accountRepo;
}
