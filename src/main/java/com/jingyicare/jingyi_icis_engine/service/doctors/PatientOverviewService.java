package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.time.*;
import java.util.*;
import java.util.stream.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientOverviewService {
    public PatientOverviewService(
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired MonitoringConfig monConfig,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired PatientMonitoringTimePointRepository monTimepointRepo,
        @Autowired PatientMonitoringRecordRepository monRecordRepo,
        @Autowired PatientTargetDailyBalanceRepository targetDailyBalanceRepo,
        @Autowired MedicationExecutionRecordRepository exeRecordRepo,
        @Autowired MedicationOrderGroupRepository orderGroupRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired AdministrationRouteGroupRepository routeGroupRepo,
        @Autowired PatientTubeRecordRepository tubeRecordRepo,
        @Autowired PatientTubeAttrRepository tubeAttrRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();

        // 设置id和观察项编码的映射关系
        vitalSignTypeMap = new HashMap<>();
        for (VitalSignTypeMapPB pb : protoService.getConfig().getDiagnosis().getVitalSignTypeMapList()) {
            vitalSignTypeMap.put(pb.getVitalSignTypeId(), pb.getMonitoringCode());
        }

        this.protoService = protoService;
        this.shiftUtils = shiftUtils;
        this.userService = userService;
        this.patientService = patientService;
        this.monConfig = monConfig;
        this.patientMonitoringService = patientMonitoringService;

        this.monTimepointRepo = monTimepointRepo;
        this.monRecordRepo = monRecordRepo;
        this.targetDailyBalanceRepo = targetDailyBalanceRepo;
        this.exeRecordRepo = exeRecordRepo;
        this.orderGroupRepo = orderGroupRepo;
        this.routeRepo = routeRepo;
        this.routeGroupRepo = routeGroupRepo;
        this.tubeRecordRepo = tubeRecordRepo;
        this.tubeAttrRepo = tubeAttrRepo;
    }

    @Transactional
    public GetVitalDetailsResp getVitalDetails(String getVitalDetailsReqJson) {
        final GetVitalDetailsReq req;
        try {
            GetVitalDetailsReq.Builder builder = GetVitalDetailsReq.newBuilder();
            JsonFormat.parser().merge(getVitalDetailsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetVitalDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前病人科室
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetVitalDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 解析输入: 观察项编码，时间范围
        List<String> paramCodes = req.getVitalSignTypeIdList().stream()
            .map(vitalSignTypeId -> {
                String code = vitalSignTypeMap.get(vitalSignTypeId);
                if (code == null) {
                    log.error("Vital sign type id not found: " + vitalSignTypeId);
                    return null;
                }
                return code;
            })
            .filter(Objects::nonNull)
            .toList();
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            log.error("Invalid date format: " + req.getQueryStartIso8601() + ", " + req.getQueryEndIso8601());
            return GetVitalDetailsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 查询自定义时点
        Set<LocalDateTime> timePoints = new HashSet<>();
        for (PatientMonitoringTimePoint timePointRec :
            monTimepointRepo.findByPidAndTimePointBetweenAndIsDeletedFalse(pid, queryStartUtc, queryEndUtc)
        ) {
            timePoints.add(timePointRec.getTimePoint());
        }

        // 查询观察项数据，除了自定义时点，过滤非整点数据
        Map<String/*paramCode*/, List<PatientMonitoringRecord>> monRecordsMap = monRecordRepo
            .findByPidAndParamCodesAndEffectiveTimeRange(
                pid, paramCodes, queryStartUtc, queryEndUtc
            )
            .stream()
            .filter(record -> {
                LocalDateTime effectiveTime = record.getEffectiveTime();

                // 自定义时点数据
                if (timePoints.contains(effectiveTime)) return true;
                // 整点数据
                if (effectiveTime.getMinute() == 0 && effectiveTime.getSecond() == 0) return true;

                return false;
            })
            .collect(Collectors.groupingBy(
                PatientMonitoringRecord::getMonitoringParamCode,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    list -> list.stream()
                    .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
                    .toList()
                )
            ));

        // 组装结果
        List<VitalSignDetailsPB> vitalSignDetailsList = new ArrayList<>();
        for (int i = 0; i < req.getVitalSignTypeIdCount(); i++) {
            int vitalSignTypeId = req.getVitalSignTypeId(i);
            String paramCode = vitalSignTypeMap.get(vitalSignTypeId);
            if (paramCode == null) {
                log.error("Vital sign type id not found: " + vitalSignTypeId);
                continue;
            }

            ValueMetaPB valueMeta = monConfig.getMonitoringParamMeta(deptId, paramCode);
            if (valueMeta == null) {
                log.error("Monitoring param code not found: " + paramCode);
                continue;
            }

            List<TimedGenericValuePB> values = new ArrayList<>();
            for (PatientMonitoringRecord record : monRecordsMap.getOrDefault(paramCode, new ArrayList<>())) {
                LocalDateTime effectiveTime = record.getEffectiveTime();
                MonitoringValuePB monValPb = ProtoUtils.decodeMonitoringValue(record.getParamValue());
                if (monValPb == null) {
                    log.error("Failed to decode monitoring value, id: {}", record.getId());
                    continue;
                }
                final GenericValuePB genericValue = monValPb.getValue();
                TimedGenericValuePB value = TimedGenericValuePB.newBuilder()
                    .setValue(genericValue)
                    .setValueStr(getValueStr(genericValue, valueMeta))
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(effectiveTime, ZONE_ID))
                    .build();
                values.add(value);
            }

            vitalSignDetailsList.add(VitalSignDetailsPB.newBuilder()
                .setVitalSignTypeId(vitalSignTypeId)
                .setValueMeta(valueMeta)
                .addAllTimedValue(values)
                .build());
        }

        return GetVitalDetailsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllVitalSignDetails(vitalSignDetailsList)
            .build();
    }

    @Transactional
    public GetDailyBalanceResp getDailyBalance(String getDailyBalanceReqJson) {
        final GetDailyBalanceReq req;
        try {
            GetDailyBalanceReq.Builder builder = GetDailyBalanceReq.newBuilder();
            JsonFormat.parser().merge(getDailyBalanceReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetDailyBalanceResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GetDailyBalanceResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();

        // 获取当前病人科室
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetDailyBalanceResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();
        ShiftSettingsPB shiftSettingsPb = shiftUtils.getShiftByDeptId(deptId);

        // 解析输入: 时间范围
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            log.error("Invalid date format: " + req.getQueryStartIso8601() + ", " + req.getQueryEndIso8601());
            return GetDailyBalanceResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }
        // 将时间转换成科室的午夜时间
        queryStartUtc = shiftUtils.getShiftLocalMidnightUtc(shiftSettingsPb, queryStartUtc, ZONE_ID);
        queryEndUtc = shiftUtils.getShiftLocalMidnightUtc(shiftSettingsPb, queryEndUtc, ZONE_ID);

        // 获取每日出入量数据
        Pair<StatusCode, List<DailyBalancePB>> codeListPair = patientMonitoringService.getDailyBalanceStats(
            pid, deptId, shiftSettingsPb, queryStartUtc, queryEndUtc, accountId);
        if (codeListPair.getFirst() != StatusCode.OK) {
            log.error("Failed to get daily balance stats: " + codeListPair.getFirst());
            return GetDailyBalanceResp.newBuilder()
                .setRt(protoService.getReturnCode(codeListPair.getFirst()))
                .build();
        }
        List<DailyBalancePB> dailyBalanceList = codeListPair.getSecond();
        return GetDailyBalanceResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllDailyBalance(dailyBalanceList)
            .build();
    }

    @Transactional
    public GenericResp setTargetBalance(String setTargetBalanceReqJson) {
        final SetTargetBalanceReq req;
        try {
            SetTargetBalanceReq.Builder builder = SetTargetBalanceReq.newBuilder();
            JsonFormat.parser().merge(setTargetBalanceReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取当前病人科室
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();
        ShiftSettingsPB shiftSettingsPb = shiftUtils.getShiftByDeptId(deptId);

        // 解析输入: 时间
        LocalDateTime targetDateUtc = TimeUtils.fromIso8601String(req.getTargetDateIso8601(), "UTC");
        if (targetDateUtc == null) {
            log.error("Invalid date format: " + req.getTargetDateIso8601());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 根据pid和shiftStartTime查找记录，如果找到，置为删除
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        PatientTargetDailyBalance existingDailyBalance = targetDailyBalanceRepo
            .findByPidAndShiftStartTimeAndIsDeletedFalse(pid, targetDateUtc)
            .stream()
            .findFirst()
            .orElse(null);
        if (existingDailyBalance != null) {
            existingDailyBalance.setIsDeleted(true);
            existingDailyBalance.setDeletedAt(nowUtc);
            existingDailyBalance.setDeletedBy(accountId);
            targetDailyBalanceRepo.save(existingDailyBalance);
        }

        // 插入新的记录
        PatientTargetDailyBalance newDailyBalance = PatientTargetDailyBalance.builder()
            .pid(pid)
            .shiftStartTime(targetDateUtc)
            .targetBalanceMl(Double.valueOf(req.getTargetBalanceMl()))
            .modifiedAt(nowUtc)
            .modifiedBy(accountId)
            .modifiedByAccountName(accountName)
            .isDeleted(false)
            .build();
        targetDailyBalanceRepo.save(newDailyBalance);

        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    @Transactional
    public GetTargetBalancesResp getTargetBalances(String getTargetBalancesReqJson) {
        final GetTargetBalancesReq req;
        try {
            GetTargetBalancesReq.Builder builder = GetTargetBalancesReq.newBuilder();
            JsonFormat.parser().merge(getTargetBalancesReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetTargetBalancesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前病人科室
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetTargetBalancesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 获取平衡量的元数据
        String HOURLY_NET_CODE = protoService.getConfig().getMonitoring().getNetHourlyNetCode();
        ValueMetaPB hourlyNetMeta = monConfig.getMonitoringParamMeta(deptId, HOURLY_NET_CODE);
        if (hourlyNetMeta == null) {
            log.error("Monitoring param code not found: " + HOURLY_NET_CODE);
            return GetTargetBalancesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST))
                .build();
        }

        // 解析输入：时间范围等
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            log.error("Invalid date format: " + req.getQueryStartIso8601() + ", " + req.getQueryEndIso8601());
            return GetTargetBalancesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 组装结果
        List<TargetBalanceInfoPB> targetBalanceInfoList = targetDailyBalanceRepo
            .findByPidAndShiftStartTimeBetweenAndIsDeletedFalse(pid, queryStartUtc, queryEndUtc)
            .stream()
            .sorted(Comparator.comparing(PatientTargetDailyBalance::getShiftStartTime))
            .map(targetDailyBalance -> {
                return TargetBalanceInfoPB.newBuilder()
                    .setId(targetDailyBalance.getId())
                    .setTargetBalanceMl(targetDailyBalance.getTargetBalanceMl().floatValue())
                    .setTargetBalanceStr(getValueStr(
                        targetDailyBalance.getTargetBalanceMl().floatValue(),
                        hourlyNetMeta
                    ))
                    .setRecordedBy(targetDailyBalance.getModifiedByAccountName())
                    .setShiftStartIso8601(TimeUtils.toIso8601String(targetDailyBalance.getShiftStartTime(), ZONE_ID))
                    .build();
            })
            .toList();

        return GetTargetBalancesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllBalance(targetBalanceInfoList)
            .build();
    }

    /*
        medication_execution_records -> medication_order_groups
        (medication_execution_records + medication_order_groups) -> medication_name
             ProtoUtils.decodeDosageGroup(orderGroup.getMedicationDosageGroup()).getDisplayName())
        medication_order_groups.order_duration_type -> is_long_term_order
        medication_order_groups.plan_time -> lasting_days
        medication_order_groups.freq_code -> frequency_code
        (medication_execution_records + medication_order_groups).administration_route_name -> administration_route
        (medication_execution_records + medication_order_groups)
           (administration_route_code | code) -> administration_routes
           (group_id | id) -> administration_route_groups.name -> administration_route_group
        medication_execution_records -> is_finished
        medication_execution_records -> action_start_iso8601
    */
    @Transactional
    public GetMedicationOrderViewsResp getMedicationOrderViews(String getMedicationOrderViewsReqJson) {
        final GetMedicationOrderViewsReq req;
        try {
            GetMedicationOrderViewsReq.Builder builder = GetMedicationOrderViewsReq.newBuilder();
            JsonFormat.parser().merge(getMedicationOrderViewsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetMedicationOrderViewsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前病人科室
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetMedicationOrderViewsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 解析输入: 时间范围
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            log.error("Invalid date format: " + req.getQueryStartIso8601() + ", " + req.getQueryEndIso8601());
            return GetMedicationOrderViewsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 获取病人用药记录相关数据
        Set<Long> medGroupIds = new HashSet<>();
        Set<String> routeCodes = new HashSet<>();
        Set<Integer> routeGroupIds = new HashSet<>();

        List<MedicationExecutionRecord> records = exeRecordRepo
            .findByPatientIdAndIsDeletedFalseAndStartTimeIsNotNullAndStartTimeBetween(
                pid, queryStartUtc, queryEndUtc
            )
            .stream()
            .sorted(Comparator.comparing(MedicationExecutionRecord::getStartTime).reversed())
            .toList();
        for (MedicationExecutionRecord record : records) {
            medGroupIds.add(record.getMedicationOrderGroupId());
            if (!StrUtils.isBlank(record.getAdministrationRouteCode())) {
                routeCodes.add(record.getAdministrationRouteCode());
            }
        }

        Map<Long, MedicationOrderGroup> orderGroupMap = orderGroupRepo
            .findByIds(medGroupIds.stream().toList())
            .stream()
            .collect(Collectors.toMap(MedicationOrderGroup::getId, orderGroup -> orderGroup));
        for (MedicationOrderGroup orderGroup : orderGroupMap.values()) {
            if (!StrUtils.isBlank(orderGroup.getAdministrationRouteCode())) {
                routeCodes.add(orderGroup.getAdministrationRouteCode());
            }
        }

        Map<String, AdministrationRoute> administrationRouteMap = routeRepo
            .findByDeptIdAndCodeIn(deptId, routeCodes.stream().toList())
            .stream()
            .collect(Collectors.toMap(AdministrationRoute::getCode, route -> route));
        for (AdministrationRoute route : administrationRouteMap.values()) {
            routeGroupIds.add(route.getGroupId());
        }

        Map<Integer, AdministrationRouteGroup> administrationRouteGroupMap = routeGroupRepo
            .findByIdIn(routeGroupIds.stream().toList())
            .stream()
            .collect(Collectors.toMap(AdministrationRouteGroup::getId, routeGroup -> routeGroup));

        // 组装结果
        Integer LONG_TERM_ORDER_TYPE_ID = protoService.getConfig().getMedication().getEnums()
            .getOrderDurationTypeLongTerm().getId();
        List<MedicationOrderViewPB> medicationOrderViewList = new ArrayList<>();
        for (MedicationExecutionRecord record : records) {
            Long medGroupId = record.getMedicationOrderGroupId();
            MedicationOrderGroup orderGroup = orderGroupMap.get(medGroupId);
            if (orderGroup == null) {
                log.warn("MedicationOrderGroup not found for id: {}, skipping execution record id: {}", medGroupId, record.getId());
                continue;
            }

            // 获取药物名称
            String dosageGroupTxt = StrUtils.isBlank(record.getMedicationDosageGroup()) ?
                orderGroup.getMedicationDosageGroup() : record.getMedicationDosageGroup();
            MedicationDosageGroupPB dosageGroupPb = ProtoUtils.decodeDosageGroup(dosageGroupTxt);
            if (dosageGroupPb == null) {
                log.error("Failed to decode dosage group: {}, for order group id: {}", dosageGroupTxt, medGroupId);
                continue; // Skip this record if decoding fails
            }
            final String medicationName = dosageGroupPb.getDisplayName();

            // 是否为长期医嘱
            final Boolean isLongTermOrderType = orderGroup.getOrderDurationType().equals(LONG_TERM_ORDER_TYPE_ID);

            // 持续时间
            int lastingDays = 0;
            if (record.getStartTime() != null && orderGroup.getPlanTime() != null) {
                long hours = Duration.between(orderGroup.getPlanTime(), record.getStartTime()).toHours();
                lastingDays = (int) Math.ceil(hours / 24.0);
            }

            // 频次编码
            String frequencyCode = orderGroup.getFreqCode();
            if (StrUtils.isBlank(frequencyCode)) frequencyCode = "";

            // 用药途径名称，以及用药途径分组名称
            String administrationRouteName = StrUtils.isBlank(record.getAdministrationRouteName()) ?
                orderGroup.getAdministrationRouteName() : record.getAdministrationRouteName();
            if (StrUtils.isBlank(administrationRouteName)) administrationRouteName = "";

            String routeCode = StrUtils.isBlank(record.getAdministrationRouteCode()) ?
                orderGroup.getAdministrationRouteCode() : record.getAdministrationRouteCode();
            AdministrationRoute route = administrationRouteMap.get(routeCode);
            AdministrationRouteGroup routeGroup = null;
            if (route != null) {
                routeGroup = administrationRouteGroupMap.get(route.getGroupId());
            }
            final String administrationRouteGroupName = routeGroup != null ? routeGroup.getName() : "";

            // 是否完成
            Boolean isFinished = !(record.getEndTime() == null);

            // 开始时间
            String actionStartIso8601 = TimeUtils.toIso8601String(record.getStartTime(), ZONE_ID);

            // Build the MedicationOrderViewPB object
            MedicationOrderViewPB orderView = MedicationOrderViewPB.newBuilder()
                .setId(record.getId())
                .setMedicationName(medicationName)
                .setIsLongTermOrder(isLongTermOrderType)
                .setLastingDays(lastingDays)
                .setFrequencyCode(frequencyCode)
                .setAdministrationRoute(administrationRouteName)
                .setAdministrationRouteGroup(administrationRouteGroupName)
                .setIsFinished(isFinished)
                .setActionStartIso8601(actionStartIso8601)
                .build();

            medicationOrderViewList.add(orderView);
        }

        return GetMedicationOrderViewsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllOrder(medicationOrderViewList)
            .build();
    }

    @Transactional
    public GetTubeViewsResp getTubeViews(String getTubeViewsReqJson) {
        final GetTubeViewsReq req;
        try {
            GetTubeViewsReq.Builder builder = GetTubeViewsReq.newBuilder();
            JsonFormat.parser().merge(getTubeViewsReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetTubeViewsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前病人
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetTubeViewsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        // 解析输入: 时间范围
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getRemovalTimeStartIso8601(), "UTC");
        if (queryStartUtc == null) queryStartUtc = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getRemovalTimeEndIso8601(), "UTC");
        if (queryEndUtc == null) queryEndUtc = TimeUtils.getLocalTime(9900, 1, 1);

        // 获取病人管道记录
        List<PatientTubeRecord> tubeRecords = new ArrayList<>();
        if (req.getContainsActiveTube()) {
            tubeRecords.addAll(tubeRecordRepo.findByPidAndIsDeletedFalseAndRemovedAtNull(pid));
        }
        if (req.getContainsRemovedTube()) {
            tubeRecords.addAll(tubeRecordRepo.findByPidAndIsDeletedFalseAndRemovedAtNotNullAndRemovedAtBetween(
                pid, queryStartUtc, queryEndUtc
            ));
        }
        tubeRecords = tubeRecords.stream().sorted(Comparator.comparing(PatientTubeRecord::getInsertedAt).reversed()).toList();

        // 获取管道属性
        List<Long> tubeRecordIds = tubeRecords.stream()
            .map(PatientTubeRecord::getId)
            .toList();
        Map<Long, List<TubeAttributePB>> tubeAttrMap = tubeAttrRepo.findByPatientTubeRecordIdIn(tubeRecordIds)
            .stream()
            .collect(Collectors.groupingBy(
                PatientTubeAttr::getPatientTubeRecordId,
                Collectors.mapping(attr -> TubeAttributePB.newBuilder()
                    .setTubeAttrId(attr.getTubeAttrId())
                    .setValue(attr.getValue())
                    .build(),
                    Collectors.toList()
                )
            ));

        // 组装
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        List<TubeViewPB> tubeViewList = new ArrayList<>();
        for (PatientTubeRecord tubeRecord : tubeRecords) {
            List<TubeAttributePB> tubeAttrList = tubeAttrMap.getOrDefault(tubeRecord.getId(), new ArrayList<>());

            int insertedDays = 0;
            if (tubeRecord.getRemovedAt() != null) {
                long insertedHours = Duration.between(tubeRecord.getInsertedAt(), tubeRecord.getRemovedAt()).toHours();
                insertedDays = (int) Math.ceil(insertedHours / 24.0);
            } else {
                long insertedHours = Duration.between(tubeRecord.getInsertedAt(), nowUtc).toHours();
                insertedDays = (int) Math.ceil(insertedHours / 24.0);
            }

            TubeViewPB tubeView = TubeViewPB.newBuilder()
                .setId(tubeRecord.getId())
                .setPatientTubeId(tubeRecord.getId())
                .setTubeTypeId(tubeRecord.getTubeTypeId())
                .setTubeName(tubeRecord.getTubeName())
                .setInsertedAtIso8601(TimeUtils.toIso8601String(tubeRecord.getInsertedAt(), ZONE_ID))
                .setRemovedAtIso8601(TimeUtils.toIso8601String(tubeRecord.getRemovedAt(), ZONE_ID))
                .setInsertedDays(insertedDays)
                .setInsertedBy(tubeRecord.getInsertedByAccountName())
                .setIsRemoved(tubeRecord.getRemovedAt() != null)
                .addAllAttribute(tubeAttrList)
                .build();

            tubeViewList.add(tubeView);
        }

        return GetTubeViewsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllTube(tubeViewList)
            .build();
    }

    private String getValueStr(GenericValuePB genericValue, ValueMetaPB valueMeta) {
        if (genericValue == null || valueMeta == null) return "";
        return ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta) + valueMeta.getUnit();
    }

    private String getValueStr(float value, ValueMetaPB valueMeta) {
        if (valueMeta == null) return "";
        GenericValuePB genericValue = ValueMetaUtils.convertFloatTo(value, valueMeta);
        return ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta) + valueMeta.getUnit();
    }

    private final String ZONE_ID;
    private final Map<Integer, String> vitalSignTypeMap;

    private final ConfigProtoService protoService;
    private final ConfigShiftUtils shiftUtils;
    private final UserService userService;
    private final PatientService patientService;
    private final MonitoringConfig monConfig;
    private final PatientMonitoringService patientMonitoringService;

    private final PatientMonitoringTimePointRepository monTimepointRepo;
    private final PatientMonitoringRecordRepository monRecordRepo;
    private final PatientTargetDailyBalanceRepository targetDailyBalanceRepo;
    private final MedicationExecutionRecordRepository exeRecordRepo;
    private final MedicationOrderGroupRepository orderGroupRepo;
    private final AdministrationRouteRepository routeRepo;
    private final AdministrationRouteGroupRepository routeGroupRepo;
    private final PatientTubeRecordRepository tubeRecordRepo;
    private final PatientTubeAttrRepository tubeAttrRepo;
}