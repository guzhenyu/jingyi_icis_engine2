package com.jingyicare.jingyi_icis_engine.service.medications;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class OrderExecutor {
    public OrderExecutor(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired MedicationConfig medCfg,
        @Autowired MedMonitoringService medMonitoringService,
        @Autowired MedicationOrderGroupRepository medOrdGroupRepo,
        @Autowired MedicationExecutionRecordRepository medExeRecordRepo,
        @Autowired MedicationExecutionActionRepository medExeActionRepo
    ) {
        this.context = context;
        this.ZONE_ID = protoService.getConfig().getZoneId();

        MedicationConfigPB medicationPb = protoService.getConfig().getMedication();
        this.FREQ_CODE_ONCE = medicationPb.getFreqSpec().getOnceCode();
        this.DURATION_LONG_TERM = medicationPb.getEnums().getOrderDurationTypeLongTerm().getId();
        this.DURATION_ONE_TIME = medicationPb.getEnums().getOrderDurationTypeOneTime().getId();
        this.DURATION_MANUAL_ENTRY = medicationPb.getEnums().getOrderDurationTypeManualEntry().getId();
        this.ORDER_GROUP_OVERWRITTEN = medicationPb.getEnums().getMedicationOrderValidityTypeOverwritten().getId();
        this.ORDER_GROUP_CANCELED = medicationPb.getEnums().getMedicationOrderValidityTypeCanceled().getId();
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE = medicationPb.getEnums().getOneTimeExecutionStopStrategyIgnore().getId();
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_ALL = medicationPb.getEnums().getOneTimeExecutionStopStrategyDeleteAll().getId();
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_AFTER = medicationPb.getEnums().getOneTimeExecutionStopStrategyDeleteAfter().getId();
        this.DELETE_REASON_CANCELED = medicationPb.getDeleteReasonCanceled();
        this.DELETE_REASON_STOPPED = medicationPb.getDeleteReasonStopped();
        this.DELETE_REASON_MANUALLY = medicationPb.getDeleteReasonManually();
        this.ADMINISTRATION_ROUTE_GROUP_INFUSION_PUMP = medicationPb.getEnums().getAdministrationRouteGroupInfusionPump().getId();
        this.shiftUtils = shiftUtils;
        this.medCfg = medCfg;
        this.medMonitoringService = medMonitoringService;
        this.medOrdGroupRepo = medOrdGroupRepo;
        this.medExeRecordRepo = medExeRecordRepo;
        this.medExeActionRepo = medExeActionRepo;
    }

    // retrieveExeRecords(版本1: 获取所有医嘱执行记录)：版本1=>版本2
    // retrieveExeRecords(版本2(重载): 获取单个医嘱执行记录）：版本2=>版本3
    // **retrieveExeRecords(版本3(重载): 获取单个医嘱执行记录, 是否个人用药, 用于补录医嘱)
    public List<List<MedicationExecutionRecord>> retrieveExeRecords(
        Boolean patientInIcu, ShiftSettingsPB shiftSettings, MedOrderGroupSettingsPB medOrdGroupSettings,
        String accountId, List<MedicationOrderGroup> medOrdGroups, LocalDateTime retrieveUtcTime
    ) {
        List<List<MedicationExecutionRecord>> recordsList = new ArrayList<>();
        for (MedicationOrderGroup medOrdGroup : medOrdGroups) {
            recordsList.add(retrieveExeRecords(
                patientInIcu, shiftSettings, medOrdGroupSettings, accountId, medOrdGroup, retrieveUtcTime
            ));
        }
        return recordsList;
    }

    public List<MedicationExecutionRecord> retrieveExeRecords(
        Boolean patientInIcu, ShiftSettingsPB shiftSettings, MedOrderGroupSettingsPB medOrdGroupSettings,
        String accountId, MedicationOrderGroup medOrdGroup, LocalDateTime retrieveUtcTime
    ) {
        return retrieveExeRecords(
            patientInIcu, shiftSettings, medOrdGroupSettings, accountId, medOrdGroup, false, retrieveUtcTime
        );
    }

    // **retrieveExeRecords(版本3(重载): 获取单个医嘱执行记录, 是否个人用药, 用于补录医嘱)
    // 1. 根据医嘱id（补录医嘱看重症医嘱id/his医嘱看his医嘱分组id），时间范围（单日），查询医嘱执行记录 List<MedicationExecutionRecord> records
    //    1.1 根据医嘱状态取消（删除所有活跃的执行记录）或者停止（删除停止时间之后的执行记录）已分解的医嘱
    //    1.2 分解医嘱，生成执行记录
    //        1.2.1 getExeTimeList，获取医嘱执行记录的执行时间列表
    //        1.2.2 生成执行记录 List<MedicationExecutionRecord> records
    public List<MedicationExecutionRecord> retrieveExeRecords(
        Boolean patientInIcu, ShiftSettingsPB shiftSettings, MedOrderGroupSettingsPB medOrdGroupSettings,
        String accountId, MedicationOrderGroup medOrdGroup, Boolean isPersonalMedications,
        LocalDateTime retrieveUtcTime
    ) {
        final LocalDateTime retrieveShiftTime = shiftUtils.getShiftStartTime(shiftSettings, retrieveUtcTime, ZONE_ID);

        // 不允许分解未来的医嘱
        final LocalDateTime curUtcTime = TimeUtils.getNowUtc();
        final LocalDateTime curShiftTime = shiftUtils.getShiftStartTime(shiftSettings, curUtcTime, ZONE_ID);
        if (retrieveShiftTime.isAfter(curShiftTime)) return new ArrayList<>();

        // 查询医嘱执行记录, 补录医嘱中没有his_group_id
        final int durationType = medOrdGroup.getOrderDurationType();
        final LocalDateTime queryStartUtc = TimeUtils.getUtcFromLocalDateTime(retrieveShiftTime, ZONE_ID);
        List<MedicationExecutionRecord> records = durationType == DURATION_MANUAL_ENTRY ?
            medExeRecordRepo.findByMedGroupIdAndTimeRange(medOrdGroup.getId(), queryStartUtc, queryStartUtc.plusDays(1)) :
            medExeRecordRepo.findByHisGroupIdAndTimeRange(medOrdGroup.getGroupId(), queryStartUtc, queryStartUtc.plusDays(1));

        final int orderValidity = medOrdGroup.getOrderValidity();
         // 取消的两种情况：1. medOrdGroup.status为已取消，或者 2. medOrdGroup.cancelTime不为空
        final boolean isCanceled = orderValidity == ORDER_GROUP_CANCELED;
        final LocalDateTime stopUtcTime = medOrdGroup.getStopTime();
        final LocalDateTime stopShiftTime = shiftUtils.getShiftStartTime(shiftSettings, stopUtcTime, ZONE_ID);
        final String orderDosageGroupDisplayName = ProtoUtils.decodeDosageGroup(medOrdGroup.getMedicationDosageGroup()).getDisplayName();
        clearCanceledAndStoppedRecords(
            records, durationType, isCanceled, stopUtcTime, stopShiftTime,
            medOrdGroupSettings, accountId, curUtcTime, retrieveShiftTime, orderDosageGroupDisplayName);
        List<MedicationExecutionRecord> deletedRecords =
            records.stream().filter(record -> record.getIsDeleted()).collect(Collectors.toList());
        medExeRecordRepo.saveAll(deletedRecords);

        if (!records.isEmpty() || !patientInIcu) {
            List<MedicationExecutionRecord> activeRecords = records.stream()
                .filter(record -> !record.getIsDeleted())
                .collect(Collectors.toList());
            return activeRecords;
        }

        // 生成执行记录
        final MedicationFrequencySpec freqSpec = medOrdGroup.getFreqCode() == FREQ_CODE_ONCE ?
            null : medCfg.getMedicationFrequencySpec(medOrdGroup.getFreqCode());
        final LocalDateTime cancelShiftTime = shiftUtils.getShiftStartTime(shiftSettings, medOrdGroup.getCancelTime(), ZONE_ID);
        final LocalDateTime orderUtcTime = medOrdGroup.getOrderTime();
        final LocalDateTime orderShiftTime = shiftUtils.getShiftStartTime(shiftSettings, orderUtcTime, ZONE_ID);
        final LocalDateTime planTime = TimeUtils.getLocalDateTimeFromUtc(medOrdGroup.getPlanTime(), ZONE_ID);
        List<LocalDateTime> exeTimeList = getExeTimeList(
            durationType, isCanceled, cancelShiftTime, stopShiftTime, orderShiftTime,
            planTime, orderValidity, freqSpec, curShiftTime, retrieveShiftTime
        );

        final Long medGroupId = medOrdGroup.getId();
        final String hisGroupId = medOrdGroup.getGroupId();
        final AdministrationRoute route = medMonitoringService.getRoute(medOrdGroup, null);
        final Long patientId = medOrdGroup.getPatientId();

        // 过滤已存在的执行时间
        Set<LocalDateTime> existingExeTimes = medExeRecordRepo.findByPatientIdAndMedicationOrderGroupId(patientId, medGroupId).stream()
            .map(MedicationExecutionRecord::getPlanTime)
            .collect(Collectors.toSet());
        exeTimeList = exeTimeList.stream()
            .filter(exeTime -> !existingExeTimes.contains(
                TimeUtils.getUtcFromLocalDateTime(exeTime, ZONE_ID)
            ))
            .collect(Collectors.toList());
        if (exeTimeList.isEmpty()) return new ArrayList<>();

        records = exeTimeList.stream()
            .map(exeTime -> createExecutionRecord(
                medOrdGroup, route, isPersonalMedications,
                TimeUtils.getUtcFromLocalDateTime(exeTime, ZONE_ID), accountId, curUtcTime,
                medOrdGroupSettings
            ))
            .collect(Collectors.toList());

        final Integer firstDayExeCount = medOrdGroup.getFirstDayExeCount();
        boolean retrieveDayQualified = isRetrieveDayQualified(
            hisGroupId, freqSpec, retrieveShiftTime, shiftSettings
        );
        if (retrieveShiftTime.isEqual(orderShiftTime)) {
            // 首日逻辑
            tuneOrderDayExeRecords(
                records, durationType, firstDayExeCount, orderUtcTime, medOrdGroupSettings,
                accountId, curUtcTime, orderDosageGroupDisplayName
            );
        // TESTED
            if (!retrieveDayQualified) {
                deleteRecords(
                    records, accountId, curUtcTime,
                    DELETE_REASON_MANUALLY,
                    orderDosageGroupDisplayName,
                    record -> true);
            }
        } else {
        // TESTED
            // 非首日逻辑
            if (!retrieveDayQualified) return new ArrayList<>();
        }

        clearCanceledAndStoppedRecords(
            records, durationType, isCanceled, stopUtcTime, stopShiftTime,
            medOrdGroupSettings, accountId, curUtcTime, retrieveShiftTime, orderDosageGroupDisplayName);

        return medExeRecordRepo.saveAll(records).stream()
            .filter(record -> !record.getIsDeleted())
            .collect(Collectors.toList());
    }

    public MedicationExecutionRecord getExeRecord(Long exeRecordId) {
        Optional<MedicationExecutionRecord> optRecord = medExeRecordRepo.findById(exeRecordId);
        return optRecord.orElse(null);
    }

    public List<MedicationExecutionAction> getExeActions(Long exeRecordId) {
        return medExeActionRepo.findByMedicationExecutionRecordId(exeRecordId);
    }

    public MedicationExecutionAction getExeAction(Long exeActionId) {
        return medExeActionRepo.findById(exeActionId).orElse(null);
    }

    public void deleteExeAction(MedicationExecutionAction action, String accountId, LocalDateTime delUtcTime) {
        action.setIsDeleted(true);
        action.setDeleteAccountId(accountId);
        action.setDeleteTime(delUtcTime);
        medExeActionRepo.save(action);
    }

    public void updateExeRecord(MedicationExecutionRecord record) {
        medExeRecordRepo.save(record);
    }

    public void deleteExeRecord(
        MedicationExecutionRecord record, String accountId, LocalDateTime delUtcTime, String deleteReason,
        String orderDosageGroupDisplayName
    ) {
        final String reason = StrUtils.isBlank(deleteReason) ? DELETE_REASON_MANUALLY : deleteReason;
        deleteRecord(record, accountId, delUtcTime, reason, orderDosageGroupDisplayName);
        medExeRecordRepo.save(record);
    }

    public List<MedicationExecutionRecord> getDeletedExeRecords(PatientRecord patientRecord, LocalDateTime retrieveUtcTime) {
        final ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(patientRecord.getDeptId());
        final LocalDateTime retrieveShiftTime = shiftUtils.getShiftStartTime(shiftSettings, retrieveUtcTime, ZONE_ID);

        final LocalDateTime queryStartUtc = TimeUtils.getUtcFromLocalDateTime(retrieveShiftTime, ZONE_ID);
        return medExeRecordRepo.findDeletedRecordsByPatientIdAndTimeRange(
            patientRecord.getId(), queryStartUtc, queryStartUtc.plusDays(1)
        );
    }

    public List<MedicationExecutionRecord> retrieveExecutionRecordsByPatientId(
        Long patientId, Integer advanceHours, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        // Find unstarted execution records
        LocalDateTime unstartedStartUtc = queryStartUtc.minusHours(advanceHours);
        List<MedicationExecutionRecord> unstartedRecords = medExeRecordRepo.findNotStartedRecordsByPatientId(
            patientId, unstartedStartUtc, queryEndUtc);

        // Find unfinished execution records
        List<MedicationExecutionRecord> inProgressRecords = medExeRecordRepo.findInProgressRecordsByPatientId(
            patientId, queryEndUtc);

        // Find finished execution records
        List<MedicationExecutionRecord> completedRecords = medExeRecordRepo.findCompletedRecordsByPatientId(
            patientId, queryStartUtc, queryEndUtc);

        // merge
        return Stream.of(unstartedRecords, inProgressRecords, completedRecords)
            .flatMap(List::stream)
            .toList();
    }

    public List<MedicationExecutionRecord> retrieveExecutionRecordsByOrderGroupId(
        Long medOrderGroupId, Integer advanceHours, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        // Find unstarted execution records
        LocalDateTime unstartedStartUtc = queryStartUtc.minusHours(advanceHours);
        List<MedicationExecutionRecord> unstartedRecords = medExeRecordRepo.findNotStartedRecordsByMedicationOrderGroupId(
            medOrderGroupId, unstartedStartUtc, queryEndUtc);

        // Find unfinished execution records
        List<MedicationExecutionRecord> inProgressRecords = medExeRecordRepo.findInProgressRecordsByMedicationOrderGroupId(
            medOrderGroupId, queryEndUtc);

        // Find finished execution records
        List<MedicationExecutionRecord> completedRecords = medExeRecordRepo.findCompletedRecordsByMedicationOrderGroupId(
            medOrderGroupId, queryStartUtc, queryEndUtc);

        // merge
        return Stream.of(unstartedRecords, inProgressRecords, completedRecords)
            .flatMap(List::stream)
            .toList();
    }

    public List<MedicationOrderGroup> getMedicationOrderGroupsByRecords(List<MedicationExecutionRecord> records) {
        Set<Long> groupIds = records.stream()
            .map(MedicationExecutionRecord::getMedicationOrderGroupId)
            .collect(Collectors.toSet());
        List<Long> groupIdList = new ArrayList<>(groupIds);

        List<MedicationOrderGroup> orderGroups = new ArrayList<>();
        for (int i = 0; i < groupIdList.size(); i += QUERY_SET_BATCH_SIZE) {
            int endIdx = Math.min(i + QUERY_SET_BATCH_SIZE, groupIdList.size());
            List<MedicationOrderGroup> subGroups = medOrdGroupRepo.findByIds(groupIdList.subList(i, endIdx));
            orderGroups.addAll(subGroups);
        }

        return orderGroups;
    }

    public Map<Long, List<MedicationExecutionAction>> getExeActionsByRecords(List<MedicationExecutionRecord> records) {
        Set<Long> recordIds = records.stream()
            .map(MedicationExecutionRecord::getId)
            .collect(Collectors.toSet());
        List<Long> recordIdList = new ArrayList<>(recordIds);

        List<MedicationExecutionAction> actions = new ArrayList<>();
        for (int i = 0; i < recordIdList.size(); i += QUERY_SET_BATCH_SIZE) {
            int endIdx = Math.min(i + QUERY_SET_BATCH_SIZE, recordIdList.size());
            List<MedicationExecutionAction> subActions =
                medExeActionRepo.findByMedicationExecutionRecordIdInAndIsDeletedFalse(
                    recordIdList.subList(i, endIdx)
                );
            actions.addAll(subActions);
        }

        actions = actions.stream().sorted(Comparator.comparing(MedicationExecutionAction::getId)).toList();
        Map<Long, List<MedicationExecutionAction>> exeActionMap = new HashMap<>();
        for (MedicationExecutionAction action : actions) {
            exeActionMap.computeIfAbsent(action.getMedicationExecutionRecordId(), k -> new ArrayList<>()).add(action);
        }
        return exeActionMap;
    }

    public MedicationExecutionAction addExeAction(
        String accountId, String accountName, Long exeRecordId, Integer actionType,
        Double administrationRate, String medicationRate, Double intakeVolMl,
        LocalDateTime createdAtUtc
    ) {
        final String dbgStr = String.format("id = %d; accountId = %s; actionType = %d",
            exeRecordId, accountId, actionType);

        Optional<MedicationExecutionRecord> optExeRecord = medExeRecordRepo.findById(exeRecordId);
        if (!optExeRecord.isPresent()) {
            log.error("MedicationExecutionRecord is not exist: ", dbgStr);
            return null;
        }
        if (medCfg.getMedicationExecutionActionTypeStr(actionType) == null) {
            log.error("Unexpected action type: ", dbgStr);
            return null;
        }
        MedicationExecutionAction action = new MedicationExecutionAction();
        action.setMedicationExecutionRecordId(exeRecordId);
        action.setCreateAccountId(accountId);
        action.setCreateAccountName(accountName);
        action.setCreatedAt(createdAtUtc == null ? TimeUtils.getNowUtc() : createdAtUtc);
        action.setActionType(actionType);
        action.setAdministrationRate((administrationRate == null || administrationRate <= 0) ?
            null : administrationRate);
        action.setMedicationRate(medicationRate);
        action.setIntakeVolMl((intakeVolMl == null || intakeVolMl <= 0) ?
            null : intakeVolMl);
        action.setIsDeleted(false);
        action.setModifiedAt(TimeUtils.getNowUtc());

        return medExeActionRepo.save(action);
    }

    private List<LocalDateTime> getExeTimeList(
        int durationType, boolean isCanceled, LocalDateTime cancelShiftTime, LocalDateTime stopShiftTime,
        LocalDateTime orderShiftTime, LocalDateTime planTime, int orderValidity,
        MedicationFrequencySpec freqSpec, LocalDateTime curShiftTime,
        LocalDateTime retrieveShiftTime
    ) {
// TESTED
        if (orderValidity == ORDER_GROUP_OVERWRITTEN) return new ArrayList<>();

        if (durationType == DURATION_ONE_TIME || durationType == DURATION_MANUAL_ENTRY) {
            if (!retrieveShiftTime.isEqual(orderShiftTime)) return new ArrayList<>();
        } else {  // durationType == DURATION_LONG_TERM
            if (isCanceled &&
                (cancelShiftTime == null || retrieveShiftTime.isAfter(cancelShiftTime))
            ) {
// TESTED
                return new ArrayList<>();
            }
// TESTED
            if ((stopShiftTime != null && retrieveShiftTime.isAfter(stopShiftTime)) ||
                retrieveShiftTime.isBefore(orderShiftTime) ||
                retrieveShiftTime.isAfter(curShiftTime)
            ) return new ArrayList<>();
        }

        List<LocalDateTime> resTimeList = new ArrayList<>();
        if (freqSpec == null) {
// TESTED
            resTimeList = new ArrayList<>(List.of(durationType == DURATION_ONE_TIME ?
                planTime : retrieveShiftTime.withHour(planTime.getHour()).withMinute(planTime.getMinute())
            ));
        } else {
// TESTED
            resTimeList = MedUtils.getPlanTimesLocal(retrieveShiftTime, freqSpec);
            // List<LocalDateTime> tails = new ArrayList<>();
            // Integer shiftStartHour = retrieveShiftTime.getHour();
            // for (MedicationFrequencySpec.Time time : freqSpec.getTimeList()) {
            //     if (time.getHour() >= shiftStartHour) {
            //         resTimeList.add(retrieveShiftTime.withHour(time.getHour()).withMinute(time.getMinute()));
            //     } else {
            //         tails.add(retrieveShiftTime.withHour(time.getHour() + 24).withMinute(time.getMinute()));
            //     }
            // }
            // resTimeList.addAll(tails);
        }

        return resTimeList;
    }

    private boolean isRetrieveDayQualified(
        String hisGroupId, MedicationFrequencySpec freqSpec,
        LocalDateTime retrieveShiftTime, ShiftSettingsPB shiftSettings
    ) {
        if (freqSpec == null) return true;
        if (freqSpec.getSpecTypeCase() == MedicationFrequencySpec.SpecTypeCase.BY_WEEK) {
// TESTED
            MedicationFrequencySpec.ByWeek byWeek = freqSpec.getByWeek();
            for (int dayOfWeek : byWeek.getDayOfWeekList()) {
                if (dayOfWeek == retrieveShiftTime.getDayOfWeek().getValue()) {
                    return true;
                }
            }
            return false;
        } else if (freqSpec.getSpecTypeCase() == MedicationFrequencySpec.SpecTypeCase.BY_INTERVAL) {
            Optional<MedicationExecutionRecord> latestRecord = medExeRecordRepo.findLatestValidRecord(hisGroupId);
// TESTED
            if (!latestRecord.isPresent()) return true;

            final int intervalDays = freqSpec.getByInterval().getIntervalDays();
            final long days = Duration.between(
                retrieveShiftTime,
                shiftUtils.getShiftStartTime(shiftSettings, latestRecord.get().getPlanTime(), ZONE_ID)
            ).toDays();
// TESTED
            return (days % (intervalDays + 1) == 0);
        }

        // should not go here.
        log.error("Invalid frequency spec type: {}", ProtoUtils.protoToTxt(freqSpec));
        return true;
    }

    private MedicationExecutionRecord createExecutionRecord(
        MedicationOrderGroup medOrdGroup, AdministrationRoute route,
        boolean isPersonalMedications, LocalDateTime exeUtcTime,
        String accountId, LocalDateTime curUtcTime, MedOrderGroupSettingsPB medOrdGroupSettings
    ) {
        // 执行记录是否需要计算药速
        boolean shouldCalculateMedRate = true;
        MedicationDosageGroupPB medDosageGroupPb = ProtoUtils.decodeDosageGroup(medOrdGroup.getMedicationDosageGroup());
        if (medDosageGroupPb != null) {
            boolean medFlag = false;
            for (MedicationDosagePB mdPb : medDosageGroupPb.getMdList()) {
                if (mdPb.getShouldCalculateRate()) {
                    medFlag = true;
                    break;
                }
            }
            if (!medFlag) shouldCalculateMedRate = false;
        }
        if (medOrdGroupSettings == null || !medOrdGroupSettings.getEnableMedicationSpeed()) {
            shouldCalculateMedRate = false;
        }

        MedicationExecutionRecord record = MedicationExecutionRecord.builder()
            .medicationOrderGroupId(medOrdGroup.getId())
            .hisOrderGroupId(medOrdGroup.getGroupId())
            .patientId(medOrdGroup.getPatientId())
            .planTime(exeUtcTime)
            .administrationRouteCode(route == null ? null : route.getCode())
            .administrationRouteName(route == null ? null : route.getName())
            .isContinuous(route == null ? null : route.getIsContinuous())
            .isPersonalMedications(isPersonalMedications)
            .shouldCalculateRate(shouldCalculateMedRate)
            .isDeleted(false)
            .userTouched(false)
            .createAccountId(accountId)
            .createdAt(curUtcTime)
            .build();
        record.setShouldCalculateRate(shouldCalculateMedRate(medOrdGroupSettings, medOrdGroup, record, route));
        return record;
    }

    private boolean shouldCalculateMedRate(
        MedOrderGroupSettingsPB medOrdGroupSettings,
        MedicationOrderGroup medOrdGroup,
        MedicationExecutionRecord exeRec,
        AdministrationRoute route
    ) {
        if (medOrdGroupSettings == null || !medOrdGroupSettings.getEnableMedicationSpeed()) {
            return false;
        }

        MedicationDosageGroupPB dosageGroupPb = medMonitoringService.getDosageGroupPB(medOrdGroup, exeRec);
        boolean containsRateFlag = dosageGroupPb != null && dosageGroupPb.getMdList().stream()
            .anyMatch(MedicationDosagePB::getShouldCalculateRate);
        if (!containsRateFlag) return false;

        AdministrationRoute targetRoute = route == null ? medMonitoringService.getRoute(medOrdGroup, exeRec) : route;
        return targetRoute != null && targetRoute.getGroupId() != null &&
            targetRoute.getGroupId().equals(ADMINISTRATION_ROUTE_GROUP_INFUSION_PUMP);
    }

    private void deleteRecord(
        MedicationExecutionRecord record,
        String accountId, LocalDateTime delUtcTime, String reason,
        String orderDosageGroupDisplayName
    ) {
        MedicationDosageGroupPB exeRecDosageGroupPb = ProtoUtils.decodeDosageGroup(record.getMedicationDosageGroup());
        if (StrUtils.isBlank(exeRecDosageGroupPb.getDisplayName())) {
            record.setMedicationDosageGroup(ProtoUtils.encodeDosageGroup(
                MedicationDosageGroupPB.newBuilder().setDisplayName(orderDosageGroupDisplayName).build()));
        }
        record.setIsDeleted(true);
        record.setDeleteAccountId(accountId);
        record.setDeleteTime(delUtcTime);
        record.setDeleteReason(reason);
    }

    private void deleteRecords(
        List<MedicationExecutionRecord> records,
        String accountId, LocalDateTime delUtcTime, String reason,
        String orderDosageGroupDisplayName,
        Predicate<MedicationExecutionRecord> filter
    ) {
        records.forEach(record -> {
            if (filter.test(record)) deleteRecord(record, accountId, delUtcTime, reason, orderDosageGroupDisplayName);
        });
    }

    // 下嘱日/首日 特殊处理
    private void tuneOrderDayExeRecords(
        List<MedicationExecutionRecord> records,
        Integer durationType, Integer firstDayExeCount, LocalDateTime orderUtcTime,
        MedOrderGroupSettingsPB setting, String accountId, LocalDateTime curUtcTime,
        String orderDosageGroupDisplayName
    ) {
        // 不处理补录医嘱
        if (durationType == DURATION_MANUAL_ENTRY) return;

        if (durationType == DURATION_LONG_TERM && setting.getOmitFirstDayOrderExecution()) {
// TESTED
            deleteRecords(records, accountId, curUtcTime, DELETE_REASON_MANUALLY, orderDosageGroupDisplayName, record -> true);
            return;
        }

        final boolean checkOrderTime = (durationType == DURATION_LONG_TERM)
                ? setting.getCheckOrderTimeForLongTermExecution()
                : setting.getCheckOrderTimeForOneTimeExecution();
        if (firstDayExeCount != null && firstDayExeCount > 0) {
// TESTED
            deleteRecords(
                records.subList(0, Math.max(0, records.size() - firstDayExeCount)),
                accountId, curUtcTime, DELETE_REASON_MANUALLY, orderDosageGroupDisplayName, record -> true);
        } else if (checkOrderTime) {
// TESTED
            deleteRecords(
                records, accountId, curUtcTime, DELETE_REASON_MANUALLY, orderDosageGroupDisplayName,
                record -> record.getPlanTime().isBefore(orderUtcTime));
        }

// TESTED
        if (setting.getForceGenerateExecutionOrderDay1() && !records.isEmpty()) {
            MedicationExecutionRecord record = records.get(records.size() - 1);
            record.setMedicationDosageGroup(null);
            record.setIsDeleted(false);
            record.setDeleteAccountId(null);
            record.setDeleteTime(null);
            record.setDeleteReason(null);
        }
    }

    private void clearCanceledAndStoppedRecords(
        List<MedicationExecutionRecord> records, int durationType,
        boolean isCanceled, LocalDateTime stopUtcTime, LocalDateTime stopShiftTime,
        MedOrderGroupSettingsPB setting, String accountId, LocalDateTime curUtcTime,
        LocalDateTime retrieveShiftTime, String orderDosageGroupDisplayName
    ) {
        if (records == null || records.isEmpty()) return;

        // 已有action的记录不处理
        Set<Long> recordIdsWithActions = medExeActionRepo.findByMedicationExecutionRecordIdInAndIsDeletedFalse(
            records.stream().map(MedicationExecutionRecord::getId).toList()
        ).stream().map(MedicationExecutionAction::getMedicationExecutionRecordId).collect(Collectors.toSet());
        records = records.stream()
            .filter(record -> !recordIdsWithActions.contains(record.getId()))
            .collect(Collectors.toList());

        // 医嘱取消
        if (isCanceled) {
// TESTED
            deleteRecords(records, accountId, curUtcTime, DELETE_REASON_CANCELED, orderDosageGroupDisplayName, record -> !record.getUserTouched());
            return;
        }

        if (stopUtcTime == null) return;
        // 医嘱停止
        if (retrieveShiftTime.isBefore(stopShiftTime)) return;
        if (retrieveShiftTime.isAfter(stopShiftTime)) {
// TESTED
            deleteRecords(records, accountId, curUtcTime, DELETE_REASON_STOPPED, orderDosageGroupDisplayName, record -> !record.getUserTouched());
            return;
        }

        if (durationType == DURATION_ONE_TIME) {
            final Integer strategy = setting.getOneTimeExecutionStopStrategy();
            if (strategy == ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_ALL) {
// TESTED
                deleteRecords(records, accountId, curUtcTime, DELETE_REASON_STOPPED, orderDosageGroupDisplayName, record -> !record.getUserTouched());
            } else if (strategy == ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_AFTER) {
                deleteRecords(records, accountId, curUtcTime, DELETE_REASON_STOPPED, orderDosageGroupDisplayName,
                    record -> !record.getUserTouched() && record.getPlanTime().isAfter(stopUtcTime));
// TESTED
            }
            // strategy == ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE: no-op;
// TESTED
        } else if (durationType == DURATION_LONG_TERM) { // 长期医嘱
// TESTED - deleted
// TESTED - not deleted
            deleteRecords(records, accountId, curUtcTime, DELETE_REASON_STOPPED, orderDosageGroupDisplayName,
                record -> !record.getUserTouched() && record.getPlanTime().isAfter(stopUtcTime));
        } else {
            // durationType == DURATION_MANUAL_ENTRY
            log.error("补录医嘱不应该有停止时间");
        }
    }

    private static final int QUERY_SET_BATCH_SIZE = 100; // select * from tbl where id in :ids, 每次ids的大小

    private ConfigurableApplicationContext context;
    private final String ZONE_ID;

    private final String FREQ_CODE_ONCE;

    private final int DURATION_LONG_TERM;
    private final int DURATION_ONE_TIME;
    private final int DURATION_MANUAL_ENTRY;

    private final int ORDER_GROUP_OVERWRITTEN;
    private final int ORDER_GROUP_CANCELED;
    private final int ADMINISTRATION_ROUTE_GROUP_INFUSION_PUMP;

    private final int ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE;
    private final int ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_ALL;
    private final int ONE_TIME_EXECUTION_STOP_STRATEGY_DELETE_AFTER;

    private final String DELETE_REASON_CANCELED;
    private final String DELETE_REASON_STOPPED;
    private final String DELETE_REASON_MANUALLY;

    private ConfigShiftUtils shiftUtils;
    private MedicationConfig medCfg;
    private MedMonitoringService medMonitoringService;
    private MedicationOrderGroupRepository medOrdGroupRepo;
    private MedicationExecutionRecordRepository medExeRecordRepo;
    private MedicationExecutionActionRepository medExeActionRepo;
}
