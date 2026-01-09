package com.jingyicare.jingyi_icis_engine.service.medications;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class MedMonitoringService {
    public MedMonitoringService(
        @Autowired ConfigProtoService protoService,
        @Autowired MedicationDictionary medDict,
        @Autowired MonitoringRecordUtils monitoringRecordUtils,
        @Autowired MedicationExecutionRecordRepository exeRecordRepo,
        @Autowired MedicationExecutionRecordStatRepository exeRecordStatRepo,
        @Autowired MedicationOrderGroupRepository orderGroupRepo,
        @Autowired MedicationExecutionActionRepository actionRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired IntakeTypeRepository intakeTypeRepo,
        @Autowired PatientMonitoringRecordRepository monitoringRecordRepo,
        @Autowired MonitoringConfig monitoringConfig
    ) {
        this.protoService = protoService;

        this.ZONE_ID = protoService.getConfig().getZoneId();
        final MEnums medEnums = protoService.getConfig().getMedication().getEnums();
        this.ACTION_TYPE_START = medEnums.getMedicationExecutionActionTypeStart().getId();
        this.ACTION_TYPE_PAUSE = medEnums.getMedicationExecutionActionTypePause().getId();
        this.ACTION_TYPE_RESUME = medEnums.getMedicationExecutionActionTypeResume().getId();
        this.ACTION_TYPE_ADJUST_SPEED = medEnums.getMedicationExecutionActionTypeAdjustSpeed().getId();
        this.ACTION_TYPE_FAST_PUSH = medEnums.getMedicationExecutionActionTypeFastPush().getId();
        this.ACTION_TYPE_COMPLETE = medEnums.getMedicationExecutionActionTypeComplete().getId();

        this.medDict = medDict;
        this.monitoringRecordUtils = monitoringRecordUtils;
        this.exeRecordRepo = exeRecordRepo;
        this.exeRecordStatRepo = exeRecordStatRepo;
        this.orderGroupRepo = orderGroupRepo;
        this.actionRepo = actionRepo;
        this.routeRepo = routeRepo;
        this.intakeTypeRepo = intakeTypeRepo;
        this.monitoringRecordRepo = monitoringRecordRepo;
        this.monitoringConfig = monitoringConfig;
    }

    /**
     * 获取患者的用药观察项记录
     *  (patientId + startUtc + endUtc))
     *   |--> inProgressRecords ------------------------------------------------------> recordIdsForActions
     *   |    (List<MedicationExecutionRecord>) |               |              |
     *   |--> completedRecords  ----------------|---------------|--------------|
     *        (List<MedicationExecutionRecord>) |               |              |
     *                                          |               |              |
     *                                          |--> recordMap  |              |
     *                                                          --> recordIds  |
     *                                                                         --> orderGroupIds
     * 
     *  recordMap ----(*)--> MedicationExecutionRecord record --(已完成的通过recordStatMap)--> 用药剂量 paramVolMap
     *                                                        |
     *                                                        |--(未完成的通过actionMap和groupDosageMap)--> 用药剂量 paramVolMap
     *
     *  paramVolMap -(*)-> PatientMonitoringRecord(paramCode通过route得到) --> 保存到数据库
     */
    @Transactional
    public List<PatientMonitoringRecord> getPatientMedMonitoringRecords(
        Long patientId, String deptId, LocalDateTime startUtc, LocalDateTime endUtc,
        String accountId
    ) {
        // 获取相关groups, records, 以及必要的actions(不需要获得已完成的actions)
        Map<Long, MedicationExecutionRecord> recordMap = new HashMap<>();
        List<Long> recordIds = new ArrayList<>();
        List<Long> recordIdsForActions = new ArrayList<>();

        Set<Long> orderGroupIds = new HashSet<>();
        Map<Long, MedicationDosageGroupPB> groupDosageMap = new HashMap<>();
        Map<Long, String> groupRouteCode = new HashMap<>();

        Map<Long, List<MedicationExecutionAction>> actionMap = new HashMap<>();

        // 获取records
        List<MedicationExecutionRecord> inProgressRecords = exeRecordRepo
            .findInProgressRecordsByPatientId(patientId, endUtc);
        for (MedicationExecutionRecord record : inProgressRecords) {
            recordMap.put(record.getId(), record);
            recordIds.add(record.getId());
            recordIdsForActions.add(record.getId());
            orderGroupIds.add(record.getMedicationOrderGroupId());
        }

        List<MedicationExecutionRecord> completedRecords = exeRecordRepo
            .findCompletedRecordsByPatientId(patientId, startUtc, endUtc);
        for (MedicationExecutionRecord record : completedRecords) {
            recordMap.put(record.getId(), record);
            recordIds.add(record.getId());
            orderGroupIds.add(record.getMedicationOrderGroupId());
        }

        // 获取groups
        for (MedicationOrderGroup group : orderGroupRepo
            .findAllById(orderGroupIds.stream().collect(Collectors.toList()))
        ) {
            groupDosageMap.put(group.getId(), ProtoUtils.decodeDosageGroup(group.getMedicationDosageGroup()));
            groupRouteCode.put(group.getId(), group.getAdministrationRouteCode());
        }

        // 获取actions
        for (MedicationExecutionAction action : actionRepo
            .findByMedicationExecutionRecordIdInAndIsDeletedFalse(recordIdsForActions)
        ) {
            List<MedicationExecutionAction> actions = actionMap.get(action.getMedicationExecutionRecordId());
            if (actions == null) {
                actions = new ArrayList<>();
                actionMap.put(action.getMedicationExecutionRecordId(), actions);
            }
            actions.add(action);
        }

        // 获取已完成的医嘱入量统计
        Map<Long, List<MedicationExecutionRecordStat>> recordStatMap = exeRecordStatRepo
            .findAllByPatientIdAndStatsTimeRange(patientId, startUtc, endUtc)
            .stream()
            .collect(Collectors.groupingBy(MedicationExecutionRecordStat::getExeRecordId));

        // 获取用药途径信息&用药参数元数据
        Map<String/*routeCode*/, RouteDetails> routeDetailsMap = new HashMap<>();
        for (RouteDetails routeDetails : routeRepo.findRouteDetailsByDeptId(deptId)) {
            routeDetailsMap.put(routeDetails.getCode(), routeDetails);
        }
        Map<String/*paramCode*/, ValueMetaPB> paramCodeMetaMap = monitoringConfig.getMonitoringParamMetas(
            deptId, routeDetailsMap.values().stream().map(RouteDetails::getMonitoringParamCode)
            .collect(Collectors.toSet())
        );

        // 获取Map<ExeRecordId, ParamCode>
        Map<String/*paramCode*/, Map<LocalDateTime, Double>> paramVolMap = new HashMap<>();
        for (Map.Entry<Long, MedicationExecutionRecord> entry : recordMap.entrySet()) {
            final Long recordId = entry.getKey();
            final MedicationExecutionRecord record = entry.getValue();

            // 获取用药途径信息
            final String routeCode = StrUtils.isBlank(record.getAdministrationRouteCode()) ?
                groupRouteCode.get(record.getMedicationOrderGroupId()) : record.getAdministrationRouteCode();
            final RouteDetails routeDetails = routeDetailsMap.getOrDefault(routeCode, null);
            if (routeDetails == null) {
                log.error("RouteDetails not found for routeCode: {}", routeCode);
                continue;
            }
            final Boolean isContinuous = routeDetails.getIsContinuous();
            final String paramCode = routeDetails.getMonitoringParamCode();
            Map<LocalDateTime, Double> timeVolMap = paramVolMap.computeIfAbsent(paramCode, k -> new HashMap<>());

            // 获取用药剂量信息
            if (recordStatMap.containsKey(recordId)) {
                List<MedicationExecutionRecordStat> recordStats = recordStatMap.get(recordId);
                for (MedicationExecutionRecordStat recordStat : recordStats) {
                    LocalDateTime statsTime = recordStat.getStatsTime();
                    if (timeVolMap.containsKey(statsTime)) {
                        timeVolMap.put(statsTime, timeVolMap.get(statsTime) + recordStat.getConsumedMl());
                    } else {
                        timeVolMap.put(statsTime, recordStat.getConsumedMl());
                    }
                }
            } else {
                MedicationDosageGroupPB dosagePb = getDosageGroupPBOrDefault(
                    record, groupDosageMap.get(record.getMedicationOrderGroupId())
                );
                List<MedicationExecutionAction> actions = actionMap.get(recordId);
                if (actions == null) continue;
                actions = actions.stream().sorted(Comparator.comparing(MedicationExecutionAction::getId)).toList();

                for (Map.Entry<LocalDateTime, Double> statsEntry :
                    calcFluidIntakeImpl(isContinuous, dosagePb, actions, null).intakeMap.entrySet()
                ) {
                    Double consumedMl = timeVolMap.getOrDefault(statsEntry.getKey(), 0.0) + statsEntry.getValue();
                    timeVolMap.put(statsEntry.getKey(), consumedMl);
                }
            }
        }

        // 构建返回结果
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        List<PatientMonitoringRecord> monitoringRecords = new ArrayList<>();
        for (Map.Entry<String, Map<LocalDateTime, Double>> entry : paramVolMap.entrySet()) {
            final String paramCode = entry.getKey();
            final Map<LocalDateTime, Double> timeVolMap = entry.getValue();
            final ValueMetaPB paramMeta = paramCodeMetaMap.get(paramCode);
            if (paramMeta == null) continue;

            for (Map.Entry<LocalDateTime, Double> timeVolEntry : timeVolMap.entrySet()) {
                final LocalDateTime statsTime = timeVolEntry.getKey();
                if ((statsTime.isEqual(startUtc) || statsTime.isAfter(startUtc))
                    && statsTime.isBefore(endUtc)
                ) {
                    final Double consumedMl = timeVolEntry.getValue();
                    GenericValuePB valuePb = ValueMetaUtils.getValue(paramMeta, consumedMl);
                    String valueStr = ValueMetaUtils.extractAndFormatParamValue(valuePb, paramMeta);

                    PatientMonitoringRecord record = PatientMonitoringRecord.builder()
                        .pid(patientId)
                        .deptId(deptId)
                        .monitoringParamCode(paramCode)
                        .effectiveTime(statsTime)
                        .paramValue(ProtoUtils.encodeMonitoringValue(
                            MonitoringValuePB.newBuilder().setValue(valuePb).build()
                        ))
                        .paramValueStr(valueStr)
                        .unit(paramMeta.getUnit())
                        .source("medication")
                        .modifiedBy(accountId)
                        .modifiedAt(nowUtc)
                        .build();
                    record = monitoringRecordUtils.saveRecordJdbc(record);
                    if (record != null) monitoringRecords.add(record);
                }
            }
        }
        return monitoringRecords;
    }

    @Transactional
    public void deleteIntakeRecords(
        Long pid, String deptId, String routeCode, LocalDateTime startUtc, String accountId
    ) {
        AdministrationRoute route = medDict.findRouteByCode(deptId, routeCode);
        if (route == null) return;
        IntakeType intakeType = intakeTypeRepo.findById(route.getIntakeTypeId()).orElse(null);
        if (intakeType == null) return;
        String paramCode = intakeType.getMonitoringParamCode();

        // 删除指定患者的指定参数代码的入量记录（独立 JDBC，避免持锁跨事务）
        LocalDateTime endUtc = TimeUtils.getNowUtc();
        monitoringRecordUtils.deleteRecords(pid, paramCode, startUtc, endUtc, accountId);
        log.info("Deleted intake records for patient {} with param code {} in range {} - {}",
            pid, paramCode, startUtc, endUtc);
    }

    public Boolean isRouteContinuous(String deptId, String routeCode) {
        AdministrationRoute route = medDict.findRouteByCode(deptId, routeCode);
        if (route == null) {
            log.error("Route not found for code: {}", routeCode);
            return false;
        }
        return route.getIsContinuous();
    }

    public Boolean isRouteContinuous(Map<String, RouteDetails> routeDetailsMap, String deptId, String routeCode) {
        RouteDetails routeDetails = routeDetailsMap.get(routeCode);
        if (routeDetails != null) {
            return routeDetails.getIsContinuous();
        }
        return false;
    }

    public Boolean isRouteContinuous(MedicationOrderGroup orderGroup) {
        String routeCode = orderGroup.getAdministrationRouteCode();
        return isRouteContinuous(orderGroup.getDeptId(), routeCode);
    }

    public Boolean isRouteContinuous(MedicationOrderGroup orderGroup, MedicationExecutionRecord exeRec) {
        if (exeRec != null && exeRec.getIsContinuous() != null) {
            return exeRec.getIsContinuous();
        }

        String routeCode = (exeRec != null && !StrUtils.isBlank(exeRec.getAdministrationRouteCode())) ?
            exeRec.getAdministrationRouteCode() :
            (orderGroup != null ? orderGroup.getAdministrationRouteCode() : "");
        return isRouteContinuous(orderGroup.getDeptId(), routeCode);
    }

    public Boolean isRouteContinuous(Map<String, RouteDetails> routeDetailsMap, MedicationOrderGroup orderGroup, MedicationExecutionRecord exeRec) {
        if (exeRec != null && exeRec.getIsContinuous() != null) {
            return exeRec.getIsContinuous();
        }

        String routeCode = (exeRec != null && !StrUtils.isBlank(exeRec.getAdministrationRouteCode())) ?
            exeRec.getAdministrationRouteCode() :
            (orderGroup != null ? orderGroup.getAdministrationRouteCode() : "");
        RouteDetails routeDetails = routeDetailsMap.get(routeCode);
        if (routeDetails != null) {
            return routeDetails.getIsContinuous();
        }
        return false;
    }

    public AdministrationRoute getRoute(MedicationOrderGroup orderGroup, MedicationExecutionRecord exeRec) {
        String routeCode = (exeRec != null && !StrUtils.isBlank(exeRec.getAdministrationRouteCode())) ?
            exeRec.getAdministrationRouteCode() :
            (orderGroup != null ? orderGroup.getAdministrationRouteCode() : "");
        return medDict.findRouteByCode(orderGroup.getDeptId(), routeCode);
    }

    public RouteDetails getRouteDetails(Map<String, RouteDetails> routeDetailsMap, MedicationOrderGroup orderGroup, MedicationExecutionRecord exeRec) {
        String routeCode = (exeRec != null && !StrUtils.isBlank(exeRec.getAdministrationRouteCode())) ?
            exeRec.getAdministrationRouteCode() :
            (orderGroup != null ? orderGroup.getAdministrationRouteCode() : "");

        return routeDetailsMap.get(routeCode);
    }

    public MedicationDosageGroupPB getDosageGroupPB(
        MedicationOrderGroup orderGroup, MedicationExecutionRecord exeRec
    ) {
        MedicationDosageGroupPB dosageGroup = getDosageGroupPBOrDefault(exeRec, null);
        if (dosageGroup != null && dosageGroup.getMdCount() > 0) {
            return dosageGroup;
        }

        // 如果exeRecord的dosageGroup为空，使用orderGroup的dosageGroup
        return ProtoUtils.decodeDosageGroup(orderGroup.getMedicationDosageGroup());
    }

    public MedicationDosageGroupPB getDosageGroupPBOrDefault(
        MedicationExecutionRecord exeRec, MedicationDosageGroupPB defaultDosageGroup
    ) {
        if (exeRec != null && !StrUtils.isBlank(exeRec.getMedicationDosageGroup())) {
            MedicationDosageGroupPB dosageGroupPb = ProtoUtils.decodeDosageGroup(
                exeRec.getMedicationDosageGroup());
            if (dosageGroupPb != null && dosageGroupPb.getMdCount() > 0) {
                return dosageGroupPb;
            }
        }
        return defaultDosageGroup;
    }

    public FluidIntakePB calcFluidIntake(
        Boolean isRouteContinuous,
        MedicationDosageGroupPB dosageGroupPb,
        List<MedicationExecutionAction> actions,
        LocalDateTime calcTimeUtc
    ) {
        return calcFluidIntakeImpl(isRouteContinuous, dosageGroupPb, actions, calcTimeUtc).composeFluidIntake();
    }

    public FluidIntakeData calcFluidIntakeImpl(
        Boolean isRouteContinuous,
        MedicationDosageGroupPB dosageGroupPb,
        List<MedicationExecutionAction> actions,
        LocalDateTime calcTimeUtc
    ) {
        FluidIntakeData inData = new FluidIntakeData(
            dosageGroupPb.getMdList().stream().mapToDouble(MedicationDosagePB::getIntakeVolMl).sum(),
            calcTimeUtc, ZONE_ID);
        if (actions == null) return inData;

        if (isRouteContinuous) {
            for (int i = 0; i < actions.size() && !inData.isLeftZero(); ++i) {
                MedicationExecutionAction action = actions.get(i);
                LocalDateTime actionUtc = action.getCreatedAt();

                if (action.getActionType() == ACTION_TYPE_COMPLETE) {
                    inData.complete(actionUtc, 0.0);
                    break;
                }
                if (action.getActionType() == ACTION_TYPE_PAUSE) {
                    inData.pause(actionUtc, i == actions.size() - 1);
                    continue;
                }
                if (action.getActionType() == ACTION_TYPE_FAST_PUSH) {
                   if (inData.addIntakeStats(actionUtc, action.getIntakeVolMl(), 0L)) break;
                } else {  // ACTION_TYPE_START, ACTION_TYPE_ADJUST_SPEED, ACTION_TYPE_RESUME
                    inData.mlPerHour = actions.get(i).getAdministrationRate();
                }

                if (inData.mlPerHour <= 0) {
                    log.error("Invalid rate: {}, action: {}", inData.mlPerHour, actions.get(i));
                    continue;
                }

                LocalDateTime actionEndUtc = (i + 1 < actions.size()) ?
                    actions.get(i+1).getCreatedAt() : inData.calcTimeUtc;
                while (actionUtc.isBefore(actionEndUtc) && !inData.isLeftZero()) {
                    LocalDateTime nextHour = actionUtc.plusHours(1).truncatedTo(ChronoUnit.HOURS); // 下一个整点

                    // 计算当前时间段的有效分钟数
                    long minutesInThisHour = ChronoUnit.MINUTES.between(
                        actionUtc, nextHour.isBefore(actionEndUtc) ? nextHour : actionEndUtc);
                    if (inData.addIntakeStats(actionUtc, 0.0, minutesInThisHour)) break;
                    actionUtc = nextHour; // 进入下一个小时
                }
            }
            if (inData.mlPerHour > 0 && !inData.isLeftZero()) {
                inData.estimatedFinishTime = inData.calcTimeUtc.plusSeconds(
                    Math.round(inData.leftMl / inData.mlPerHour * 3600));
            }
        } else {
            for (MedicationExecutionAction action : actions) {
                if (inData.addIntakeStats(action.getCreatedAt(), action.getIntakeVolMl(), 0L)) break;
            }
        }

        return inData;
    }

    public static class FluidIntakeData {
        String ZONE_ID;
        Double totalMl;
        LocalDateTime calcTimeUtc;
        public Map<LocalDateTime, Double> intakeMap;

        // leftMl影响的相关变量
        Double leftMl;
        LocalDateTime finishTime;
        Double mlPerHour;  // 只对持续用药有效，表示最后一个有效动作后对应的速度
        public LocalDateTime estimatedFinishTime;

        public FluidIntakeData(Double totalMl, LocalDateTime calcTimeUtc, String ZONE_ID) {
            this.ZONE_ID = ZONE_ID;
            this.totalMl = totalMl;
            this.calcTimeUtc = calcTimeUtc == null ? TimeUtils.getNowUtc() : calcTimeUtc;
            this.intakeMap = new HashMap<>();

            this.leftMl = totalMl;
            this.finishTime = null;
            this.mlPerHour = 0.0;
            this.estimatedFinishTime = null;
        }

        public Boolean isLeftZero() {
            return leftMl <= Consts.EPS;
        }

        public Double getUsedMl() {
            if (totalMl == null || leftMl == null) return 0.0;
            return Math.max(0.0, totalMl - leftMl);
        }

        // 非持续用药：intakeMl > 0, intakeMinutes == 0，actionUtc可以是非整点
        // 持续用药：intakeMl == 0, 0 < intakeMinutes <= 60，actionUtc需要是整点
        public Boolean addIntakeStats(
            LocalDateTime actionUtc, Double intakeMl, Long intakeMinutes
        ) {
            if (intakeMl < 0 || intakeMinutes < 0) {
                log.error("Invalid intakeMl: {}, intakeMinutes: {}", intakeMl, intakeMinutes);
                return false;
            }
            Double consumedMl = 0.0;
            if (intakeMinutes > 0) {
                consumedMl = Math.min((intakeMinutes / 60.0) * mlPerHour, leftMl);
            } else if (intakeMl > 0) {
                Double roughLeftMl = leftMl - intakeMl;
                if (roughLeftMl < -Consts.EPS) log.error("Invalid intake: {}, leftMl: {}", intakeMl, leftMl);
                if (roughLeftMl < Consts.EPS) consumedMl = leftMl;
                else consumedMl = intakeMl;
            }

            final LocalDateTime actionHourUtc = actionUtc.truncatedTo(ChronoUnit.HOURS);
            Double intakeMlOld = intakeMap.getOrDefault(actionHourUtc, 0.0);
            intakeMap.put(actionHourUtc, intakeMlOld + consumedMl);
            leftMl -= consumedMl;
            if (leftMl > Consts.EPS) return false;

            if (intakeMinutes > 0) {
                finishTime = actionUtc.plusMinutes(Math.round(consumedMl / mlPerHour * 60));
            } else {
                finishTime = actionUtc;
            }
            mlPerHour = 0.0;

            return true;
        }

        public void complete(LocalDateTime completeTime, Double consumedMl) {
            if (consumedMl > Consts.EPS) addIntakeStats(completeTime, consumedMl, 0L);
            if (finishTime == null) finishTime = completeTime;
            mlPerHour = 0.0;
        }

        public void pause(LocalDateTime pauseTime, Boolean isLastAction) {
            if (finishTime == null && isLastAction) finishTime = pauseTime;
            mlPerHour = 0.0;
        }

        public FluidIntakePB composeFluidIntake() {
            List<FluidIntakePB.IntakeRecord> intakeRecords = intakeMap.entrySet().stream()
                .map(entry -> FluidIntakePB.IntakeRecord.newBuilder()
                    .setMl(entry.getValue())
                    .setMlStr(String.format("%.2f", entry.getValue()))
                    .setTimeIso8601(TimeUtils.toIso8601String(entry.getKey(), ZONE_ID))
                    .build()
                )
                .sorted(Comparator.comparing(FluidIntakePB.IntakeRecord::getTimeIso8601))
                .toList();

            return FluidIntakePB.newBuilder()
                .setTotalMl(totalMl)
                .addAllIntakeRecord(intakeRecords)
                .setRemainingIntake(
                    FluidIntakePB.IntakeRecord.newBuilder()
                        .setMl(leftMl)
                        .setMlStr(String.format("%.2f", leftMl))
                        .setTimeIso8601(TimeUtils.toIso8601String(
                            finishTime != null ? finishTime : calcTimeUtc, ZONE_ID
                        ))
                        .build()
                )
                .setEstimatedCompletionTimeIso8601(estimatedFinishTime == null ? "" :
                    TimeUtils.toIso8601String(estimatedFinishTime, ZONE_ID)
                )
                .build();
        }
    }

    private final String ZONE_ID;

    private final Integer ACTION_TYPE_START;
    private final Integer ACTION_TYPE_PAUSE;
    private final Integer ACTION_TYPE_RESUME;
    private final Integer ACTION_TYPE_ADJUST_SPEED;
    private final Integer ACTION_TYPE_FAST_PUSH;
    private final Integer ACTION_TYPE_COMPLETE;

    private final ConfigProtoService protoService;
    private final MedicationDictionary medDict;
    private final MonitoringRecordUtils monitoringRecordUtils;
    private final MedicationExecutionRecordRepository exeRecordRepo;
    private final MedicationExecutionRecordStatRepository exeRecordStatRepo;
    private final MedicationOrderGroupRepository orderGroupRepo;
    private final MedicationExecutionActionRepository actionRepo;
    private final AdministrationRouteRepository routeRepo;
    private final IntakeTypeRepository intakeTypeRepo;
    private final PatientMonitoringRecordRepository monitoringRecordRepo;
    private final MonitoringConfig monitoringConfig;
}
