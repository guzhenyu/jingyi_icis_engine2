package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.medications.MedMonitoringService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientMonitoringService {
    public PatientMonitoringService(
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired PatientTubeImpl patientTubeImpl,
        @Autowired MedMonitoringService medMonitoringService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired BalanceCalculator balanceCalculator,
        @Autowired DeviceDataFetcher deviceDataFetcher,
        @Autowired MonitoringRecordUtils monitoringRecordUtils,
        @Autowired PatientNursingReportUtils pnrUtils,
        @Autowired PatientMonitoringRecordRepository recordRepository,
        @Autowired PatientTargetDailyBalanceRepository targetDailyBalanceRepo,
        @Autowired PatientMonitoringTimePointRepository timePointRepository,
        @Autowired DeptMonitoringParamRepository deptMonitoringParamRepository,
        @Autowired MonitoringParamRepository monitoringParamRepository,
        @Autowired PatientMonitoringRecordStatsDailyRepository dailyStatsRepository
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.protoService = protoService;
        this.monitoringPb = protoService.getConfig().getMonitoring();
        this.shiftUtils = shiftUtils;
        this.userService = userService;
        this.patientService = patientService;
        this.patientTubeImpl = patientTubeImpl;
        this.medMonitoringService = medMonitoringService;
        this.monitoringConfig = monitoringConfig;
        this.balanceCalculator = balanceCalculator;
        this.deviceDataFetcher = deviceDataFetcher;
        this.monitoringRecordUtils = monitoringRecordUtils;
        this.pnrUtils = pnrUtils;

        this.recordRepository = recordRepository;
        this.targetDailyBalanceRepo = targetDailyBalanceRepo;
        this.timePointRepository = timePointRepository;
        this.deptMonitoringParamRepository = deptMonitoringParamRepository;
        this.monitoringParamRepository = monitoringParamRepository;
        this.dailyStatsRepository = dailyStatsRepository;
    }

    public static class GetMonitoringRecordsResult {
        public StatusCode statusCode;
        public boolean isBalanceGroups;
        public LocalDateTime recordsQueryStartUtc;
        public LocalDateTime recordsQueryEndUtc;
        public List<LocalDateTime> balanceStatTimeUtcHistory;
        public List<MonitoringGroupBetaPB> groupBetaList;
        public List<PatientMonitoringTimePointPB> customTimePoints;
        public List<PatientMonitoringRecord> recordList;

        public GetMonitoringRecordsResult() {
            this.statusCode = StatusCode.OK;
            this.isBalanceGroups = false;
            this.recordsQueryStartUtc = null;
            this.recordsQueryEndUtc = null;
            this.balanceStatTimeUtcHistory = new ArrayList<>();
            this.groupBetaList = new ArrayList<>();
            this.customTimePoints = new ArrayList<>();
            this.recordList = new ArrayList<>();
        }
    }

    public GetPatientMonitoringRecordsResp getPatientMonitoringRecords(String getPatientMonitoringRecordsReqJson) {
        GetPatientMonitoringGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientMonitoringRecordsReqJson, GetPatientMonitoringGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GetPatientMonitoringRecordsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GetPatientMonitoringRecordsResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 解析参数
        final Long pid = req.getPid();
        final String deptId = req.getDeptId();
        final int groupType = req.getGroupType();
        final boolean syncDeviceData = req.getSyncDeviceData();

        // 解析时间范围
        final LocalDateTime startTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        final LocalDateTime endTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            log.error("Invalid time range.");
            return GetPatientMonitoringRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_RANGE))
                .build();
        }

        Pair<LocalDateTime, LocalDateTime> queryUtcTimeRange = monitoringConfig.normalizePmrQueryTimeRange(
            groupType, deptId, startTime, endTime
        );
        List<String> tubeParamCodes = (groupType == monitoringPb.getEnums().getGroupTypeBalance().getId()) ?
            patientTubeImpl.getMonitoringParamCodes(
                pid, queryUtcTimeRange.getFirst(), queryUtcTimeRange.getSecond()
            ) : Collections.emptyList();
        List<MonitoringGroupBetaPB> groupBetaList = monitoringConfig.getMonitoringGroups(
            pid, deptId, groupType, tubeParamCodes, accountId
        );
        GetMonitoringRecordsResult recordsResult = getMonitoringRecords(
            pid, deptId, groupType, startTime, endTime, syncDeviceData, groupBetaList, accountId
        );
        if (recordsResult.statusCode != StatusCode.OK) {
            log.error("Failed to get monitoring records: {}", recordsResult.statusCode);
            return GetPatientMonitoringRecordsResp.newBuilder()
                .setRt(protoService.getReturnCode(recordsResult.statusCode))
                .build();
        }

        // 获取分组数据
        BalanceCalculator.GetGroupRecordsListArgs args = new BalanceCalculator.GetGroupRecordsListArgs(
            pid, deptId,
            recordsResult.recordsQueryStartUtc,
            recordsResult.recordsQueryEndUtc,
            recordsResult.balanceStatTimeUtcHistory,
            monitoringConfig.getMonitoringParams(deptId),
            recordsResult.groupBetaList,
            recordsResult.recordList,
            accountId
        );
        List<MonitoringGroupRecordsPB> groupRecordsList = null;
        if (recordsResult.isBalanceGroups) {  // 平衡组s
            // 存储小时汇总数据，统计对应的天数据
            balanceCalculator.storeGroupRecordsStats(args);
            // 将观察项记录集重新限制在[startTime, endTime)之内
            List<PatientMonitoringRecord> recordList = recordsResult.recordList.stream()
                .filter(record -> !record.getEffectiveTime().isBefore(startTime) &&
                    record.getEffectiveTime().isBefore(endTime)
                ).toList();
            args.recordList = recordList;
            args.startTime = startTime;
            args.endTime = endTime;
            // 统计汇总
            groupRecordsList = balanceCalculator.getGroupRecordsList(args);
        } else {  // 观察项组s
            groupRecordsList = balanceCalculator.getNonBalanceGroupRecordsList(args);
        }

        return GetPatientMonitoringRecordsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllGroupRecords(groupRecordsList)
            .addAllCustomTimePoint(recordsResult.customTimePoints)
            .build();
    }

    public GetMonitoringRecordsResult getMonitoringRecords(
        Long pid, String deptId, int groupType, LocalDateTime startTime, LocalDateTime endTime,
        boolean syncDeviceData, List<MonitoringGroupBetaPB> groupBetaList, String accountId
    ) {
        GetMonitoringRecordsResult result = new GetMonitoringRecordsResult();

        // 获取isBalanceGroups
        final boolean isBalanceGroups = (groupType == monitoringPb.getEnums().getGroupTypeBalance().getId());
        result.isBalanceGroups = isBalanceGroups;

        // 获取 recordsQueryStartUtc 和 recordsQueryEndUtc
        Pair<LocalDateTime, LocalDateTime> queryUtcTimeRange = monitoringConfig.normalizePmrQueryTimeRange(
            groupType, deptId, startTime, endTime
        );
        LocalDateTime recordsQueryStartUtc = queryUtcTimeRange.getFirst();
        LocalDateTime recordsQueryEndUtc = queryUtcTimeRange.getSecond();
        result.recordsQueryStartUtc = recordsQueryStartUtc;
        result.recordsQueryEndUtc = recordsQueryEndUtc;
        if (isBalanceGroups) {
            result.balanceStatTimeUtcHistory = shiftUtils.getBalanceStatTimeUtcHistory(deptId);
        }

        // 获取 groupBetaList
        /*
        !!! 不要在这里调用，会导致死锁
        !!! 1. monitoringConfig.getMonitoringGroups 会读取dept_monitoring_params表
        !!! 2. dept_monitoring_params表 需要在 patient_monitoring_records 前读取 （读取顺序一致避免死锁）
        List<MonitoringGroupBetaPB> groupBetaList = monitoringConfig.getMonitoringGroups(
            pid, deptId, groupType,
            (isBalanceGroups ? patientTubeImpl.getMonitoringParamCodes(pid, recordsQueryStartUtc, recordsQueryEndUtc) : new ArrayList<>()),
            accountId);
        */
        result.groupBetaList = groupBetaList;

        /////////////////////////////////////////////
        // 获取 recordList
        List<PatientMonitoringTimePointPB> customTimePoints = isBalanceGroups ? new ArrayList<>() : timePointRepository
            .findByPidAndTimePointBetweenAndIsDeletedFalse(pid, recordsQueryStartUtc, recordsQueryEndUtc)
            .stream()
            .sorted(Comparator.comparing(PatientMonitoringTimePoint::getTimePoint))
            .map(timePoint ->
                PatientMonitoringTimePointPB.newBuilder()
                    .setId(timePoint.getId())
                    .setTimePointIso8601(TimeUtils.toIso8601String(timePoint.getTimePoint(), ZONE_ID))
                    .build()
            )
            .toList();
        result.customTimePoints = customTimePoints;

        // 获取医嘱相关的观察项记录（未计入db的补充db），获取已经在观察项记录表中的记录（获取被跟新的医嘱参数，本函数非transactional）
        List<PatientMonitoringRecord> medRecordList = medMonitoringService
            .getPatientMedMonitoringRecords(pid, deptId, recordsQueryStartUtc, recordsQueryEndUtc, accountId);
        result.recordList = recordRepository  // 如果医嘱参数有被更新，则会被下面查到（本函数非transactional）
            .findByPidAndEffectiveTimeRange(pid, recordsQueryStartUtc, recordsQueryEndUtc);

        // 获取设备相关的观察项记录
        if (syncDeviceData) {
            Pair<StatusCode, List<PatientMonitoringRecord>> resultPair = deviceDataFetcher.fetch(
                pid, recordsQueryStartUtc, recordsQueryEndUtc, customTimePoints, groupBetaList, accountId
            );
            if (resultPair.getFirst() != StatusCode.OK) {
                log.error("Failed to fetch device data: {}", resultPair.getFirst());
                result.statusCode = resultPair.getFirst();
                return result;
            }
            List<PatientMonitoringRecord> recordsToAdd = resultPair.getSecond();
            recordRepository.saveAll(recordsToAdd);
            result.recordList.addAll(recordsToAdd);
        }

        return result;
    }

    public Pair<StatusCode, GetMonitoringRecordsResult> refreshBalanceGroupRecordStats(
        Map<String, MonitoringParamPB> paramMap,
        long pid, String deptId, LocalDateTime startUtc, LocalDateTime endUtc,
        List<MonitoringGroupBetaPB> groupBetaList, String accountId
    ) {
        log.info(">>>>>> patMon.refreshBalanceGroupRecordStats begin pid={} deptId={} range=[{}, {}] groupBetaSize={}",
            pid, deptId, startUtc, endUtc, groupBetaList == null ? -1 : groupBetaList.size());
        GetMonitoringRecordsResult recordsResult = getMonitoringRecords(
            pid, deptId, monitoringConfig.getBalanceGroupTypeId(),
            startUtc, endUtc, false/*syncDeviceData*/, groupBetaList, accountId
        );
        if (recordsResult.statusCode != StatusCode.OK) {
            log.error("Failed to get monitoring records: {}", recordsResult.statusCode);
            return new Pair<>(recordsResult.statusCode, null);
        }

        BalanceCalculator.GetGroupRecordsListArgs args = new BalanceCalculator.GetGroupRecordsListArgs(
            pid, deptId,
            recordsResult.recordsQueryStartUtc,
            recordsResult.recordsQueryEndUtc,
            recordsResult.balanceStatTimeUtcHistory,
            paramMap,
            recordsResult.groupBetaList,
            recordsResult.recordList,
            accountId
        );
        balanceCalculator.storeGroupRecordsStats(args);
        log.info(">>>>>> patMon.refreshBalanceGroupRecordStats end pid={} deptId={} recordsQueryRange=[{}, {}] recordCount={}",
            pid, deptId, recordsResult.recordsQueryStartUtc, recordsResult.recordsQueryEndUtc,
            recordsResult.recordList == null ? -1 : recordsResult.recordList.size());
        return new Pair<>(StatusCode.OK, recordsResult);
    }

    @Transactional
    public AddPatientMonitoringRecordResp addPatientMonitoringRecord(String addPatientMonitoringRecordReqJson) {
        final AddPatientMonitoringRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addPatientMonitoringRecordReqJson, AddPatientMonitoringRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return AddPatientMonitoringRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return AddPatientMonitoringRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // Fetch ValueMetaPB from monitoring_params or dept_monitoring_params
        String paramCode = req.getParamCode();
        String deptId = req.getDeptId();
        Optional<ValueMetaPB> valueMetaOpt = fetchValueMeta(deptId, paramCode);

        if (valueMetaOpt.isEmpty()) {
            log.error("ValueMetaPB not found for param_code: {}, dept_id: {}", paramCode, deptId);
            return AddPatientMonitoringRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.VALUE_META_NOT_FOUND))
                    .build();
        }

        ValueMetaPB valueMeta = valueMetaOpt.get();

        // 针对用户输入的值，做格式化，比如截断小数点后2位
        GenericValuePB value = req.getValue();
        List<GenericValuePB> values = req.getValuesList();
        if (monitoringPb.getFormatUserInput()) {
            value = ValueMetaUtils.formatParamValue(value, valueMeta);
            values = values.stream()
                .map(v -> ValueMetaUtils.formatParamValue(v, valueMeta))
                .toList();
        }

        // Ensure the value matches ValueMetaPB.value_type
        String paramValueStr;
        try {
            paramValueStr = ValueMetaUtils.extractAndFormatParamValue(value, values, valueMeta);
        } catch (IllegalArgumentException e) {
            log.error("Invalid value for param_code: {}, error: {}", paramCode, e.getMessage());
            return AddPatientMonitoringRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INVALID_PARAM_VALUE))
                    .build();
        }

        // Check for existing record
        LocalDateTime effectiveTime = TimeUtils.fromIso8601String(req.getRecordedAtIso8601(), "UTC");
        if (effectiveTime == null) {
            log.error("Invalid effective time: {}", req.getRecordedAtIso8601());
            return AddPatientMonitoringRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_FORMAT))
                    .build();
        }
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return AddPatientMonitoringRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        StatusCode statusCode = patientService.validateTime(patientRecord, effectiveTime);
        if (statusCode != StatusCode.OK) {
            log.warn("Effective time is out of range.");
            return AddPatientMonitoringRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(statusCode))
                    .build();
        }

        PatientMonitoringRecord existingRecord = recordRepository
            .findByPidAndMonitoringParamCodeAndEffectiveTimeAndIsDeletedFalse(
                pid, req.getParamCode(), effectiveTime
            ).orElse(null);
        if (existingRecord != null) {
            log.error("Record already exists for PID: {}, effective_time: {}", pid, effectiveTime);
            return AddPatientMonitoringRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PATIENT_MONITORING_RECORD_ALREADY_EXISTS))
                    .build();
        }

        // Create new record
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        PatientMonitoringRecord newRecord = PatientMonitoringRecord.builder()
            .pid(pid)
            .deptId(deptId)
            .monitoringParamCode(paramCode)
            .effectiveTime(effectiveTime)
            .paramValue(ProtoUtils.encodeMonitoringValue(MonitoringValuePB.newBuilder()
                .setValue(value)
                .addAllValues(values)
                .build()
            ))
            .paramValueStr(paramValueStr)
            .unit(valueMeta.getUnit()) // Set unit from ValueMetaPB
            .deviceId(null) // Optionally set device_id
            .source("") // Optionally derive source
            .note("") // Optionally set note
            .status("") // Optionally set status
            .modifiedBy(accountId)
            .modifiedAt(nowUtc)
            .build();
        newRecord = monitoringRecordUtils.saveRecordJdbc(newRecord);
        if (newRecord == null) {
            log.error("Failed to save new monitoring record for PID: {}", pid);
            return AddPatientMonitoringRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INTERNAL_EXCEPTION))
                    .build();
        }
        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        log.info("Successfully added new record for PID: {}, effective_time: {}, by user: {}", req.getPid(), effectiveTime, accountId);
        return AddPatientMonitoringRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .setId(newRecord.getId())
                .build();
    }

    @Transactional
    public GenericResp updatePatientMonitoringRecord(String updatePatientMonitoringRecordReqJson) {
        final UpdatePatientMonitoringRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updatePatientMonitoringRecordReqJson, UpdatePatientMonitoringRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // Fetch the existing record
        PatientMonitoringRecord existingRecord = recordRepository
            .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (existingRecord == null) {
            log.error("Monitoring record not found for ID: {}", req.getId());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PATIENT_MONITORING_RECORD_NOT_EXIST))
                    .build();
        }

        // Fetch ValueMetaPB
        Long pid = existingRecord.getPid();
        String paramCode = existingRecord.getMonitoringParamCode();
        LocalDateTime effectiveTime = existingRecord.getEffectiveTime();
        String deptId = existingRecord.getDeptId();
        Optional<ValueMetaPB> valueMetaOpt = fetchValueMeta(deptId, paramCode);

        if (valueMetaOpt.isEmpty()) {
            log.error("ValueMetaPB not found for param_code: {}, dept_id: {}", paramCode, deptId);
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.VALUE_META_NOT_FOUND))
                    .build();
        }

        ValueMetaPB valueMeta = valueMetaOpt.get();

        // Validate and format the new value
        String paramValueStr;
        try {
            paramValueStr = ValueMetaUtils.extractAndFormatParamValue(
                req.getValue(), req.getValuesList(), valueMeta);
        } catch (IllegalArgumentException e) {
            log.error("Invalid value for param_code: {}, error: {}", paramCode, e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.INVALID_PARAM_VALUE))
                    .build();
        }

        // 针对用户输入的值，做格式化，比如截断小数点后2位
        GenericValuePB value = req.getValue();
        List<GenericValuePB> values = req.getValuesList();
        if (monitoringPb.getFormatUserInput()) {
            value = ValueMetaUtils.formatParamValue(value, valueMeta);
            values = values.stream()
                .map(v -> ValueMetaUtils.formatParamValue(v, valueMeta))
                .toList();
        }
        String monitoringValueStr = ProtoUtils.encodeMonitoringValue(
            MonitoringValuePB.newBuilder()
                .setValue(value)
                .addAllValues(values)
                .build()
        );

        // Update the record
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        PatientMonitoringRecord newRecord = existingRecord.toBuilder()
            .id(null)
            .paramValue(monitoringValueStr)
            .paramValueStr(paramValueStr)
            .unit(valueMeta.getUnit())
            .modifiedBy(accountId)
            .modifiedAt(nowUtc)
            .build();
        monitoringRecordUtils.saveRecordJdbc(newRecord);

        // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
        pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);

        log.info("Successfully updated monitoring record for ID: {}, by user: {}", req.getId(), accountId);
        return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
    }

    @Transactional
    public GenericResp deletePatientMonitoringRecord(String deletePatientMonitoringRecordReqJson) {
        // Parse JSON to Proto
        final DeletePatientMonitoringRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientMonitoringRecordReqJson, DeletePatientMonitoringRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // Get the current account ID
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty.");
            return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                    .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 软删除
        PatientMonitoringRecord deletedRecord = monitoringRecordUtils.deleteRecord(req.getId(), accountId);
        if (deletedRecord != null) {
            log.info("Deleted PatientMonitoringRecord ID: {}. User: {}", req.getId(), accountId);
            // 更新护理单的处理时间（为 打印从首页到尾页都一致的护理单 提效，省二院）
            Long pid = deletedRecord.getPid();
            String deptId = deletedRecord.getDeptId();
            LocalDateTime effectiveTime = deletedRecord.getEffectiveTime();
            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            pnrUtils.updateLatestDataTime(pid, deptId, effectiveTime, effectiveTime, nowUtc);
        }

        return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK))
                .build();
    }

    @Transactional
    public AddPatientMonitoringTimePointsResp addPatientMonitoringTimePoints(String addPatientMonitoringTimePointsReqJson) {
        // Parse request
        AddPatientMonitoringTimePointsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addPatientMonitoringTimePointsReqJson, AddPatientMonitoringTimePointsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return AddPatientMonitoringTimePointsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Get accountId
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("AccountId is empty.");
            return AddPatientMonitoringTimePointsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 获取患者信息
        final Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return AddPatientMonitoringTimePointsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        if (req.getTimePointIso8601Count() <= 0) {
            log.error("No valid time points provided for PID: {} by User: {}", pid, accountId);
            return AddPatientMonitoringTimePointsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.MONITORING_TIME_POINTS_EMPTY))
                .build();
        }

        // Convert and deduplicate time points
        List<LocalDateTime> timePointList = req.getTimePointIso8601List().stream()
            .map(tp -> TimeUtils.fromIso8601String(tp, "UTC"))
            .toList();
        Set<LocalDateTime> timePointSet = timePointList.stream().filter(Objects::nonNull)
            .collect(Collectors.toSet());

        // 检查日期的合法性
        for (LocalDateTime timePoint : timePointSet) {
            StatusCode statusCode = patientService.validateTime(patientRecord, timePoint);
            if (statusCode != StatusCode.OK) {
                log.warn("Effective time is out of range.");
                return AddPatientMonitoringTimePointsResp.newBuilder()
                        .setRt(protoService.getReturnCode(statusCode))
                        .build();
            }
        }

        // Query the database to check existence of each time point
        List<PatientMonitoringTimePoint> existingTimePoints = timePointRepository
            .findByPidAndTimePointInAndIsDeletedFalse(pid, timePointSet);
        Map<LocalDateTime, Long> existingTimePointMap = existingTimePoints.stream()
            .collect(Collectors.toMap(
                PatientMonitoringTimePoint::getTimePoint,
                PatientMonitoringTimePoint::getId,
                (existing, replacement) -> existing, // 处理键冲突的合并函数
                HashMap::new // 指定结果类型为 HashMap
            ));

        // Insert new time points
        List<LocalDateTime> newTimePoints = new ArrayList<>();
        List<LocalDateTime> dupTimePoints = new ArrayList<>();
        for (LocalDateTime timePoint : timePointList) {
            if (existingTimePointMap.containsKey(timePoint)) {
                dupTimePoints.add(timePoint);
                continue;
            }
            newTimePoints.add(timePoint);
        }

        for (LocalDateTime timePoint : newTimePoints) {
            PatientMonitoringTimePoint newTimePoint = PatientMonitoringTimePoint.builder()
                .pid(pid)
                .deptId(req.getDeptId())
                .timePoint(timePoint)
                .modifiedBy(accountId)
                .modifiedAt(TimeUtils.getNowUtc())
                .isDeleted(false)
                .build();
            newTimePoint = timePointRepository.save(newTimePoint);
            existingTimePointMap.put(timePoint, newTimePoint.getId());
            log.info("Added TimePoint: {} for PID: {} by User: {}, newID {}", timePoint, pid, accountId, newTimePoint.getId());
        }

        List<Long> insertedIds = timePointList.stream()
            .map(timePoint -> existingTimePointMap.getOrDefault(timePoint, -1L))
            .toList();

        return AddPatientMonitoringTimePointsResp.newBuilder()
            .setRt(protoService.getReturnCode(
                dupTimePoints.isEmpty() ? StatusCode.OK : StatusCode.MONITORING_TIME_POINTS_DUPLICATE
            ))
            .addAllId(insertedIds)
            .build();
    }

    @Transactional
    public GenericResp deletePatientMonitoringTimePoints(String deletePatientMonitoringTimePointsReqJson) {
        // Parse request
        DeletePatientMonitoringTimePointsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deletePatientMonitoringTimePointsReqJson, DeletePatientMonitoringTimePointsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Get accountId
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("AccountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // Fetch time point record
        Optional<PatientMonitoringTimePoint> optionalTimePoint = timePointRepository.findById(req.getId());
        if (optionalTimePoint.isEmpty() || optionalTimePoint.get().getIsDeleted()) {
            log.info("TimePoint ID {} not found or already deleted. User: {}", req.getId(), accountId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.OK)) // Deletion is idempotent
                .build();
        }

        // Mark as deleted
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        PatientMonitoringTimePoint timePoint = optionalTimePoint.get();
        timePoint.setIsDeleted(true);
        timePoint.setDeletedBy(accountId);
        timePoint.setDeletedAt(nowUtc);

        timePointRepository.save(timePoint);

        // 删除时间点相关的数据，保存到历史表
        List<PatientMonitoringRecord> recordsToDelete = recordRepository
            .findByPidAndEffectiveTimeAndIsDeletedFalse(timePoint.getPid(), timePoint.getTimePoint());
        for (PatientMonitoringRecord record : recordsToDelete) {
            monitoringRecordUtils.markRecordAsDeleted(record, accountId, nowUtc);
            log.info("Deleted PatientMonitoringRecord ID: {} by User: {}", record.getId(), accountId);
        }
        recordRepository.saveAll(recordsToDelete);

        log.info("Deleted PatientMonitoringTimePoint ID: {} by User: {}", req.getId(), accountId);
        return GenericResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .build();
    }

    // apacheII 评分模块中调用
    @Transactional
    public Pair<ValueMetaPB, List<TimedGenericValuePB>> fetchMonitoringItem(
        Long pid, String deptId, String monitoringCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        queryEndUtc = queryEndUtc.isAfter(nowUtc) ? nowUtc : queryEndUtc;

        // 获取监测参数元数据
        MonitoringParamPB monParamPb = monitoringConfig.getMonitoringParam(deptId, monitoringCode);
        if (monParamPb == null) {
            log.error("No monitoring param found for code: {}, deptId: {}", monitoringCode, deptId);
            return null;
        }
        ValueMetaPB valueMeta = monParamPb.getValueMeta();

        List<TimedGenericValuePB> origValues = new ArrayList<>();
        for (PatientMonitoringRecord record : recordRepository
            .findByPidAndParamCodeAndEffectiveTimeRange(
                pid, monitoringCode, queryStartUtc, queryEndUtc
            ).stream()
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList()
        ) {
            MonitoringValuePB monValPb = ProtoUtils.decodeMonitoringValue(record.getParamValue());
            if (monValPb == null) continue;

            origValues.add(TimedGenericValuePB.newBuilder()
                .setValue(monValPb.getValue())
                .setValueStr(ValueMetaUtils.getValueStrWithUnit(monValPb.getValue(), valueMeta))
                .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                .build());
        }

        return new Pair<ValueMetaPB, List<TimedGenericValuePB>>(valueMeta, origValues);
    }

    private static class DailyBalanceStats {
        public float intake;
        public float output;
        public float targetBalance;
        public DailyBalanceStats() { intake = 0; output = 0; targetBalance = 0; }
    }

    @Transactional
    public Pair<StatusCode, List<DailyBalancePB>> getDailyBalanceStats(
        Long pid, String deptId, ShiftSettingsPB shiftSettings,
        LocalDateTime queryStartMidnightUtc, LocalDateTime queryEndMidnightUtc,
        String accountId
    ) {
        // 查找每日入量，出量，平衡量的元数据
        Pair<String, ValueMetaPB> dailyInPair = getCodeMetaPair(
            deptId, protoService.getConfig().getMonitoring().getDailyTotalIntakeCode()
        );
        if (dailyInPair == null) return new Pair<>(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST, null);
        Pair<String, ValueMetaPB> dailyOutPair = getCodeMetaPair(
            deptId, protoService.getConfig().getMonitoring().getDailyTotalOutputCode()
        );
        if (dailyOutPair == null) return new Pair<>(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST, null);
        Pair<String, ValueMetaPB> dailyBalancePair = getCodeMetaPair(
            deptId, protoService.getConfig().getMonitoring().getDailyTotalBalanceCode()
        );
        if (dailyBalancePair == null) return new Pair<>(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST, null);

        // 初始化统计Map
        Map<LocalDateTime, DailyBalanceStats> dailyStatsMap = new HashMap<>();

        // 获取每日出入量统计
        List<PatientMonitoringRecordStatsDaily> intakeDailyStatsList = dailyStatsRepository
            .findByPidAndParamCodeAndTimeBetween(
                pid, dailyInPair.getFirst(), queryStartMidnightUtc, queryEndMidnightUtc.plusMinutes(1)
            );
        for (PatientMonitoringRecordStatsDaily stats : intakeDailyStatsList) {
            LocalDateTime date = stats.getEffectiveTime();
            GenericValuePB valuePb = ProtoUtils.decodeGenericValue(stats.getParamValue());
            if (valuePb == null) continue;
            float intakeMl = ValueMetaUtils.convertToFloat(valuePb, dailyInPair.getSecond());

            DailyBalanceStats dailyStats = dailyStatsMap.computeIfAbsent(date, d -> new DailyBalanceStats());
            dailyStats.intake = intakeMl;
        }
        List<PatientMonitoringRecordStatsDaily> outputDailyStatsList = dailyStatsRepository
            .findByPidAndParamCodeAndTimeBetween(
                pid, dailyOutPair.getFirst(), queryStartMidnightUtc, queryEndMidnightUtc.plusMinutes(1)
            );
        for (PatientMonitoringRecordStatsDaily stats : outputDailyStatsList) {
            LocalDateTime date = stats.getEffectiveTime();
            GenericValuePB valuePb = ProtoUtils.decodeGenericValue(stats.getParamValue());
            if (valuePb == null) continue;
            float outputMl = ValueMetaUtils.convertToFloat(valuePb, dailyOutPair.getSecond());

            DailyBalanceStats dailyStats = dailyStatsMap.computeIfAbsent(date, d -> new DailyBalanceStats());
            dailyStats.output = outputMl;
        }

        // 获取目标平衡量统计
        List<PatientTargetDailyBalance> targetDailyBalanceList = targetDailyBalanceRepo
            .findByPidAndShiftStartTimeBetweenAndIsDeletedFalse(
                pid, queryStartMidnightUtc, queryEndMidnightUtc.plusMinutes(1)
            );
        for (PatientTargetDailyBalance target : targetDailyBalanceList) {
            LocalDateTime date = target.getShiftStartTime();
            DailyBalanceStats dailyStats = dailyStatsMap.computeIfAbsent(date, d -> new DailyBalanceStats());
            dailyStats.targetBalance = target.getTargetBalanceMl() == null ?
                0f : target.getTargetBalanceMl().floatValue();
        }

        List<DailyBalancePB> dailyBalanceList = new ArrayList<>();
        for (Map.Entry<LocalDateTime, DailyBalanceStats> entry : dailyStatsMap.entrySet()) {
            LocalDateTime date = entry.getKey();
            DailyBalanceStats stats = entry.getValue();
            float balance = stats.intake - stats.output;

            DailyBalancePB dailyPb = DailyBalancePB.newBuilder()
                .setDateIso8601(TimeUtils.toIso8601String(date, ZONE_ID))
                .setIntakeMl(stats.intake)
                .setIntakeStr(getValueStr(stats.intake, dailyInPair.getSecond()))
                .setOutputMl(stats.output)
                .setOutputStr(getValueStr(stats.output, dailyOutPair.getSecond()))
                .setBalanceMl(balance)
                .setBalanceStr(getValueStr(balance, dailyBalancePair.getSecond()))
                .setTargetBalanceMl(stats.targetBalance)
                .setTargetBalanceStr(getValueStr(stats.targetBalance, dailyBalancePair.getSecond()))
                .build();

            dailyBalanceList.add(dailyPb);
        }
        dailyBalanceList.sort(Comparator.comparing(DailyBalancePB::getDateIso8601));
        return new Pair<>(StatusCode.OK, dailyBalanceList);
    }

    private Optional<ValueMetaPB> fetchValueMeta(String deptId, String paramCode) {
        Map<String, MonitoringParamPB> deptParams = monitoringConfig.getMonitoringParams(deptId);
        if (deptParams != null) {
            MonitoringParamPB paramPb = deptParams.get(paramCode);
            if (paramPb != null) {
                return Optional.of(paramPb.getValueMeta());
            }
        }
        return Optional.empty();
    }

    private Pair<String, ValueMetaPB> getCodeMetaPair(String deptId, String paramCode) {
        ValueMetaPB valueMeta = monitoringConfig.getMonitoringParamMeta(deptId, paramCode);
        if (valueMeta == null) return null;
        return new Pair<>(paramCode, valueMeta);
    }

    private List<LocalDateTime> getBalanceStatsDates(
        ShiftSettingsPB shiftSettings,
        List<Pair<LocalDateTime, Integer>> balanceDailyShiftHours,
        LocalDateTime effectiveTimeUtc
    ) {
        List<LocalDateTime> balanceLocalMidnightUtcList = new ArrayList<>();

        for (Pair<LocalDateTime, Integer> shift : balanceDailyShiftHours) {
            LocalDateTime shiftStartUtc = shift.getFirst();
            int shiftStartHour = shift.getSecond();
            // if ((shiftStartUtc + shiftStartHour) <= effectiveTimeUtc &&
            //     effectiveTimeUtc < (shiftStartUtc + shiftStartHour + 24 hours))
            // )
            if (!shiftStartUtc.plusHours(shiftStartHour).isAfter(effectiveTimeUtc) && 
                effectiveTimeUtc.isBefore(shiftStartUtc.plusDays(1).plusHours(shiftStartHour))
            ) {
                balanceLocalMidnightUtcList.add(shiftStartUtc);
            }

            // if ((effectiveTimeUtc - shiftStartUtc) >= 48h) break;
            if (shiftStartUtc.plusDays(2).isBefore(effectiveTimeUtc)) {
                break;
            }
        }

        if (balanceLocalMidnightUtcList.isEmpty()) {
            LocalDateTime defaultMidnightUtc = shiftUtils.getShiftLocalMidnightUtc(
                shiftSettings, effectiveTimeUtc, ZONE_ID
            );
            balanceLocalMidnightUtcList.add(defaultMidnightUtc);
        }
        return balanceLocalMidnightUtcList;
    }

    private String getValueStr(float value, ValueMetaPB valueMeta) {
        if (valueMeta == null) return "";
        GenericValuePB genericValue = ValueMetaUtils.convertFloatTo(value, valueMeta);
        return ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta) + valueMeta.getUnit();
    }

    private final String ZONE_ID;

    private final ConfigProtoService protoService;
    private final MonitoringPB monitoringPb;
    private final ConfigShiftUtils shiftUtils;
    private final UserService userService;
    private final PatientService patientService;
    private final PatientTubeImpl patientTubeImpl;
    private final MedMonitoringService medMonitoringService;
    private final MonitoringConfig monitoringConfig;
    private final BalanceCalculator balanceCalculator;
    private final DeviceDataFetcher deviceDataFetcher;
    private final MonitoringRecordUtils monitoringRecordUtils;
    private final PatientNursingReportUtils pnrUtils;

    private final PatientMonitoringRecordRepository recordRepository;
    private final PatientTargetDailyBalanceRepository targetDailyBalanceRepo;
    private final PatientMonitoringTimePointRepository timePointRepository;
    private final DeptMonitoringParamRepository deptMonitoringParamRepository;
    private final MonitoringParamRepository monitoringParamRepository;
    private final PatientMonitoringRecordStatsDailyRepository dailyStatsRepository;
}
