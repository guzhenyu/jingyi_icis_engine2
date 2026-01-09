package com.jingyicare.jingyi_icis_engine.service.medications;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class MedReportUtils {
    public MedReportUtils(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientNursingReportUtils reportUtils,
        @Autowired MedMonitoringService medMonService,
        @Autowired MonitoringConfig monConfig,
        @Autowired PatientMonitoringService patMonService,
        @Autowired MonitoringRecordUtils monRecUtils,
        @Autowired PatientTubeImpl patientTubeImpl,
        @Autowired MedicationOrderGroupRepository orderGroupRepo,
        @Autowired MedicationExecutionRecordRepository recRepo,
        @Autowired MedicationExecutionActionRepository actionRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired AdministrationRouteGroupRepository routeGroupRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.BALANCE_GROUP_TYPE_ID = protoService.getConfig().getMonitoring()
            .getEnums().getGroupTypeBalance().getId();
        this.reportUtils = reportUtils;
        this.medMonService = medMonService;
        this.monConfig = monConfig;
        this.patMonService = patMonService;
        this.monRecUtils = monRecUtils;
        this.patientTubeImpl = patientTubeImpl;
        this.orderGroupRepo = orderGroupRepo;
        this.actionRepo = actionRepo;
        this.recRepo = recRepo;
        this.routeRepo = routeRepo;
        this.routeGroupRepo = routeGroupRepo;
    }

    /**
     * 生成病人某时间段内的用药执行记录汇总。
     *
     * @param useOverlapWindow
     *   false：按“首个执行动作在 [startUtc, endUtc)”筛选（已完成 & 未完成）
     *   true ：按“时间区间与 [startUtc, endUtc) 有重叠”筛选：
     *          - 已完成： [startTime, endTime) 与窗口重叠
     *          - 未完成： [startTime, expectedEndTime) 与窗口重叠（需已有至少一个执行动作）
     */
    public List<MedExeRecordSummaryPB> generateMedExeRecordSummaryList(
        Long pid, String deptId, LocalDateTime startUtc, LocalDateTime endUtc,
        LocalDateTime calcTimeUtc, boolean useOverlapWindow
    ) {
        Set<String> routeCodes = new HashSet<>();

        // 获取执行记录
        List<MedicationExecutionRecord> exeRecords = useOverlapWindow ?
            getOverlappedExeRecords(pid, startUtc, endUtc) :
            getStartedExeRecords(pid, startUtc, endUtc);
        if (exeRecords.isEmpty()) return Collections.emptyList();

        Set<Long> ordGroupIds = new HashSet<>();
        List<Long> exeRecordIds = new ArrayList<>();
        for (MedicationExecutionRecord rec : exeRecords) {
            exeRecordIds.add(rec.getId());
            if (rec.getMedicationOrderGroupId() != null) {
                ordGroupIds.add(rec.getMedicationOrderGroupId());
            }
            String routeCode = rec.getAdministrationRouteCode();
            if (!StrUtils.isBlank(routeCode)) routeCodes.add(routeCode);
        }

        // 获取医嘱（含药品 + 用药途径）
        Map<Long, MedicationOrderGroup> orderGroupMap = new HashMap<>();
        for (MedicationOrderGroup orderGroup : orderGroupRepo.findByIds(new ArrayList<>(ordGroupIds))) {
            orderGroupMap.put(orderGroup.getId(), orderGroup);
            String routeCode = orderGroup.getAdministrationRouteCode();
            if (!StrUtils.isBlank(routeCode)) routeCodes.add(routeCode);
        }

        // 获取执行动作
        Map<Long, List<MedicationExecutionAction>> actionMap = new HashMap<>();
        for (MedicationExecutionAction action :
            actionRepo.findByMedicationExecutionRecordIdInAndIsDeletedFalse(exeRecordIds)
        ) {
            Long recId = action.getMedicationExecutionRecordId();
            actionMap.computeIfAbsent(recId, k -> new ArrayList<>()).add(action);
        }

        // 获取用药途径
        Map<String, AdministrationRoute> routeMap = new HashMap<>();
        for (AdministrationRoute route : routeRepo.findByDeptIdAndCodeIn(deptId, new ArrayList<>(routeCodes))) {
            routeMap.put(route.getCode(), route);
        }

        // 获取用药途径分组
        Map<Integer, String> routeGroupMap = new HashMap<>();
        for (AdministrationRouteGroup group : routeGroupRepo.findAll()) {
            routeGroupMap.put(group.getId(), group.getName());
        }

        // 获取液体量使用数据
        List<MedExeRecordSummaryPB> summaries = new ArrayList<>();
        for (MedicationExecutionRecord rec : exeRecords) {
            MedicationOrderGroup orderGroup = orderGroupMap.get(rec.getMedicationOrderGroupId());
            List<MedicationExecutionAction> actions = actionMap.get(rec.getId());
            if (orderGroup == null || actions == null || actions.isEmpty()) {
                log.error("Missing orderGroup or actions: recId={}, orderGroupId={}, actions={}",
                    rec.getId(), rec.getMedicationOrderGroupId(), actions);
                continue;
            }
            actions.sort(Comparator.comparing(MedicationExecutionAction::getId));

            // 用药分组
            MedicationDosageGroupPB dosageGroupPb = null;
            if (!StrUtils.isBlank(rec.getMedicationDosageGroup())) {
                dosageGroupPb = ProtoUtils.decodeDosageGroup(rec.getMedicationDosageGroup());
            }
            if (dosageGroupPb == null) {
                dosageGroupPb = ProtoUtils.decodeDosageGroup(orderGroup.getMedicationDosageGroup());
            }
            if (dosageGroupPb == null) {
                log.error("Missing dosageGroup: recId={}, orderGroupId={}",
                    rec.getId(), rec.getMedicationOrderGroupId());
                continue;
            }

            // 获取用药途径
            String routeCode = StrUtils.isBlank(rec.getAdministrationRouteCode()) ?
                orderGroup.getAdministrationRouteCode() : rec.getAdministrationRouteCode();
            if (StrUtils.isBlank(routeCode)) {
                log.error("Missing administrationRouteCode: recId={}, orderGroupId={}",
                    rec.getId(), rec.getMedicationOrderGroupId());
                continue;
            }
            AdministrationRoute route = routeMap.get(routeCode);
            if (route == null) {
                log.error("Missing administrationRoute: recId={}, routeCode={}",
                    rec.getId(), routeCode);
                continue;
            }
            String routeGroupName = routeGroupMap.getOrDefault(route.getGroupId(), "");

            // 生成汇总
            MedMonitoringService.FluidIntakeData intake = medMonService.calcFluidIntakeImpl(
                route.getIsContinuous(), dosageGroupPb, actions, calcTimeUtc
            );
            MedExeRecordSummaryPB summary = MedExeRecordSummaryPB.newBuilder()
                .setDosageGroupDisplayName(dosageGroupPb.getDisplayName())
                .setStartTimeIso8601(TimeUtils.toIso8601String(rec.getStartTime(), ZONE_ID))
                .setIntakeMl(ValueMetaUtils.normalize(intake.getUsedMl(), Consts.MED_ML_DECIMAL_PLACES))
                .setIntakeGroupId(route.getGroupId())
                .setIntakeGroupName(routeGroupName)
                .setAdminCode(route.getCode())
                .setAdminName(route.getName())
                .build();
            summaries.add(summary);
        }

        summaries.sort(Comparator
            .comparing(MedExeRecordSummaryPB::getStartTimeIso8601)
            .thenComparing(MedExeRecordSummaryPB::getDosageGroupDisplayName)
        );

        return summaries;
    }

    /**
     * 标记相关病人护理记录为“脏”，以便后续更新。适用场景：
     * - 补录医嘱时，附加了执行动作
     * - 删除执行记录
     * - 给定一个执行记录，新增执行动作
     * - 给定一个执行记录，删除执行动作
     */
    public void setDirtyPatientNursingReports(
        MedicationOrderGroup orderGroup, MedicationExecutionRecord rec,
        List<MedicationExecutionAction> oldActions,
        List<MedicationExecutionAction> newActions,
        Map<String, RouteDetails> routeDetailsMap,
        LocalDateTime calcTimeUtc,
        String accountId
    ) {
        final Long pid = orderGroup.getPatientId();
        final String deptId = orderGroup.getDeptId();
        if (pid == null || StrUtils.isBlank(deptId)) {
            log.error("Missing pid or deptId: orderGroupId={}, pid={}, deptId={}",
                orderGroup.getId(), pid, deptId);
            return;
        }

        // 用药分组
        MedicationDosageGroupPB dosageGroupPb = null;
        if (!StrUtils.isBlank(rec.getMedicationDosageGroup())) {
            dosageGroupPb = ProtoUtils.decodeDosageGroup(rec.getMedicationDosageGroup());
        }
        if (dosageGroupPb == null) {
            dosageGroupPb = ProtoUtils.decodeDosageGroup(orderGroup.getMedicationDosageGroup());
        }
        if (dosageGroupPb == null) {
            log.error("Missing dosageGroup: recId={}, orderGroupId={}",
                rec.getId(), rec.getMedicationOrderGroupId());
            return;
        }

        // 获取用药途径
        String routeCode = StrUtils.isBlank(rec.getAdministrationRouteCode()) ?
            orderGroup.getAdministrationRouteCode() : rec.getAdministrationRouteCode();
        if (StrUtils.isBlank(routeCode)) {
            log.error("Missing administrationRouteCode: recId={}, orderGroupId={}",
                rec.getId(), rec.getMedicationOrderGroupId());
            return;
        }
        AdministrationRoute route = routeRepo.findByDeptIdAndCode(deptId, routeCode).orElse(null);
        if (route == null) {
            log.error("Missing administrationRoute: recId={}, routeCode={}",
                rec.getId(), routeCode);
            return;
        }

        // 统计两组执行动作影响的时间段
        Pair<LocalDateTime, LocalDateTime> affectedTimeRange = new Pair<>(
            updateAffectedTime(null, rec.getStartTime(), true),
            updateAffectedTime(null, rec.getEndTime(), false)
        );
        log.info(">>>>>> medReport.setDirtyPatientNursingReports begin pid={}, deptId={}, orderGroupId={}, exeRecId={}, actionCountOld={}, actionCountNew={}",
            pid, deptId, orderGroup.getId(), rec.getId(),
            oldActions == null ? 0 : oldActions.size(),
            newActions == null ? 0 : newActions.size());

        if (oldActions != null && !oldActions.isEmpty()) {
            MedMonitoringService.FluidIntakeData oldIntake = medMonService.calcFluidIntakeImpl(
                route.getIsContinuous(), dosageGroupPb, oldActions, calcTimeUtc
            );
            affectedTimeRange = getAffectedTimeRange(affectedTimeRange, oldIntake);
        }

        if (newActions != null && !newActions.isEmpty()) {
            MedMonitoringService.FluidIntakeData newIntake = medMonService.calcFluidIntakeImpl(
                route.getIsContinuous(), dosageGroupPb, newActions, calcTimeUtc
            );
            affectedTimeRange = getAffectedTimeRange(affectedTimeRange, newIntake);
        }

        // 获取最终影响的时间段，批量标记相关护理单记录为脏
        LocalDateTime affectedStartUtc = affectedTimeRange.getFirst();
        LocalDateTime affectedEndUtc = affectedTimeRange.getSecond();
        if (affectedStartUtc == null || affectedEndUtc == null) {
            log.warn("No valid affected time range to mark nursing reports dirty: recId={}, oldActions={}, newActions={}",
                rec.getId(), oldActions, newActions);
            return;
        }

        // 删除对应的观察项记录；重新计算用药入量，小时入量，日入量。

        // 需要先读 dept_monitoring_params 在读 patient_monitoring_records ， 避免死锁
        /*
         * 修改前
         *    MedReportUtils.setDirtyPatientNursingReports
         *         先调用 MonitoringRecordUtils.deleteRecords 软删除了 patient_monitoring_records
         *         再调用 PatientMonitoringService.refreshBalanceGroupRecordStats
         *             => PatientMonitoringService.getMonitoringRecords
         *                => MonitoringConfig.getMonitoringGroups 读取 dept_monitoring_params
         *    违反了 先读 dept_monitoring_params 再读 patient_monitoring_records 的顺序，导致死锁
         *  修改后
         *    将 MonitoringConfig.getMonitoringGroups 提前到 MonitoringRecordUtils.deleteRecords 之前
         *        返回 List<MonitoringGroupBetaPB> groupBetaList
         *    PatientMonitoringService.refreshBalanceGroupRecordStats 传入 groupBetaList 参数
         *       避免在 PatientMonitoringService.refreshBalanceGroupRecordStats 内部再读 dept_monitoring_params
         *  避免死锁
         */
        // 将 MonitoringConfig.getMonitoringGroups 提前到 MonitoringRecordUtils.deleteRecords 之前
        Pair<LocalDateTime, LocalDateTime> queryUtcTimeRange = monConfig.normalizePmrQueryTimeRange(
            BALANCE_GROUP_TYPE_ID, deptId, affectedStartUtc, affectedEndUtc
        );
        List<String> tubeParamCodes = patientTubeImpl.getMonitoringParamCodes(
            pid, queryUtcTimeRange.getFirst(), queryUtcTimeRange.getSecond()
        );
        List<MonitoringGroupBetaPB> groupBetaList = monConfig.getMonitoringGroups(
            pid, deptId, BALANCE_GROUP_TYPE_ID, tubeParamCodes, accountId
        );
        Map<String, MonitoringParamPB> monitoringParams = monConfig.getMonitoringParams(deptId);
        RouteDetails routeDetails = routeDetailsMap.get(routeCode);

        // 避免死锁修改结束
        if (routeDetails != null && !StrUtils.isBlank(routeDetails.getMonitoringParamCode())) {
            final String monitoringParamCode = routeDetails.getMonitoringParamCode();
            monRecUtils.deleteRecords(pid, monitoringParamCode, affectedStartUtc, affectedEndUtc, accountId);
            patMonService.refreshBalanceGroupRecordStats(
                monitoringParams, pid, deptId, affectedStartUtc, affectedEndUtc, groupBetaList, accountId
            );
        }

        reportUtils.updateLatestDataTime(pid, deptId, affectedStartUtc, affectedEndUtc, calcTimeUtc);
    }

    private List<MedicationExecutionRecord> getOverlappedExeRecords(
        Long pid, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        List<MedicationExecutionRecord> result = new ArrayList<>();

        // 未完成的执行记录
        // - exeRecord.startTime != null &&
        // - exeRecord.endTime == null &&
        // - exeRecord.startTime < endUtc）
        result.addAll(recRepo.findInProgressRecordsByPatientId(pid, endUtc));

        // 已完成的执行记录
        // - exeRecord.startTime != null && 
        // - exeRecord.endTime != null &&
        // - exeRecord.endTime > startUtc &&
        // - exeRecord.startTime < endUtc
        result.addAll(recRepo.findCompletedRecordsByPatientId(pid, startUtc, endUtc));

        return result;
    }

    private List<MedicationExecutionRecord> getStartedExeRecords(
        Long pid, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        return recRepo.findStartedRecordsByPatientId(pid, startUtc, endUtc);
    }

    private Pair<LocalDateTime, LocalDateTime> getAffectedTimeRange(
        Pair<LocalDateTime, LocalDateTime> timeRange, MedMonitoringService.FluidIntakeData intake
    ) {
        LocalDateTime affectedStartUtc = timeRange != null ? timeRange.getFirst() : null;
        LocalDateTime affectedEndUtc = timeRange != null ? timeRange.getSecond() : null;

        for (Map.Entry<LocalDateTime, Double> entry : intake.intakeMap.entrySet()) {
            LocalDateTime time = entry.getKey();
            affectedStartUtc = updateAffectedTime(affectedStartUtc, time, true);
            affectedEndUtc = updateAffectedTime(affectedEndUtc, time, false);
        }
        affectedStartUtc = updateAffectedTime(affectedStartUtc, intake.estimatedFinishTime, true);
        affectedEndUtc = updateAffectedTime(affectedEndUtc, intake.estimatedFinishTime, false);

        return new Pair<>(affectedStartUtc, affectedEndUtc);
    }

    private LocalDateTime updateAffectedTime(
        LocalDateTime affectedTime, LocalDateTime candidate, boolean isStart
    ) {
        if (candidate == null) return affectedTime;
        if (affectedTime == null) return candidate;
        if (isStart) {
            return affectedTime.isAfter(candidate) ? candidate : affectedTime;
        } else {
            return affectedTime.isBefore(candidate) ? candidate : affectedTime;
        }
    }

    private final String ZONE_ID;
    private final Integer BALANCE_GROUP_TYPE_ID;
    private final PatientNursingReportUtils reportUtils;
    private final MedMonitoringService medMonService;
    private final MonitoringConfig monConfig;
    private final PatientMonitoringService patMonService;
    private final MonitoringRecordUtils monRecUtils;
    private final PatientTubeImpl patientTubeImpl;
    private final MedicationOrderGroupRepository orderGroupRepo;
    private final MedicationExecutionRecordRepository recRepo;
    private final MedicationExecutionActionRepository actionRepo;
    private final AdministrationRouteRepository routeRepo;
    private final AdministrationRouteGroupRepository routeGroupRepo;
}
