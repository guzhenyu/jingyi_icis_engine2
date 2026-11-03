package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.lis.*;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.reports.common.PatientNursingReportInvalidationService;
import com.jingyicare.jingyi_icis_engine.service.settings.SettingService;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class NursingRecordService {
    public NursingRecordService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired MedReportUtils medReportUtils,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired BalanceCalculator balanceCalculator,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired PatientTubeImpl patientTubeImpl,
        @Autowired LisService lisService,
        @Autowired PatientNursingReportInvalidationService pnrUtils,
        @Autowired SettingService settingService,
        @Autowired NursingRecordRepository recordRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired PatientMonitoringRecordRepository monitoringRecordRepo,
        @Autowired LockExecutor lockExecutor,
        @Autowired JdbcTemplate jdbcTemplate
    ) {
        MonitoringPB monitoringPb = protoService.getConfig().getMonitoring();
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.BALANCE_GROUP_TYPE_ID = monitoringPb.getEnums().getGroupTypeBalance().getId();
        this.BALANCE_IN_GROUP_NAME = monitoringPb.getBalanceInGroupName();
        this.BALANCE_OUT_GROUP_NAME = monitoringPb.getBalanceOutGroupName();
        this.BALANCE_NET_GROUP_NAME = monitoringPb.getBalanceNetGroupName();
        this.NET_HOURLY_IN_CODE = monitoringPb.getNetHourlyInCode();
        this.NET_HOURLY_OUT_CODE = monitoringPb.getNetHourlyOutCode();
        this.NET_HOURLY_NET_CODE = monitoringPb.getNetHourlyNetCode();
        this.SUMMARY_MONITORING_CODE = monitoringPb.getParamCodeSummary();

        this.protoService = protoService;
        this.configPb = protoService.getConfig().getNursingRecord();
        this.userService = userService;
        this.patientService = patientService;
        this.medReportUtils = medReportUtils;
        this.monitoringConfig = monitoringConfig;
        this.patientMonitoringService = patientMonitoringService;
        this.balanceCalculator = balanceCalculator;
        this.shiftUtils = shiftUtils;
        this.patientTubeImpl = patientTubeImpl;
        this.lisService = lisService;
        this.pnrUtils = pnrUtils;
        this.settingService = settingService;
        this.recordRepo = recordRepo;
        this.patientRepo = patientRepo;
        this.monitoringRecordRepo = monitoringRecordRepo;
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;
    }

    public GetNursingRecordResp getNursingRecord(String getNursingRecordReqJson) {
        final GetNursingRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingRecordReqJson, GetNursingRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");

        List<NursingRecordPB> records = recordRepo
            .findByPatientIdAndEffectiveTimeBetweenAndIsDeletedFalse(req.getPid(), queryStartUtc, queryEndUtc)
            .stream()
            .sorted(Comparator.comparing(NursingRecord::getEffectiveTime))
            .map(record -> NursingRecordPB.newBuilder()
                .setId(record.getId())
                .setPatientId(record.getPatientId())
                .setContent(record.getContent())
                .setEffectiveTimeIso8601(
                    TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                .setSource(Objects.toString(record.getSource(), ""))
                .setPatientCriticalLisHandlingId(record.getPatientCriticalLisHandlingId() == null
                    ? 0 : record.getPatientCriticalLisHandlingId())
                .setReviewedBy(Objects.toString(record.getReviewedBy(), ""))
                .setReviewedByAccountName(Objects.toString(record.getReviewedByAccountName(), ""))
                .setReviewedAtIso8601(TimeUtils.toIso8601String(record.getReviewedAt(), ZONE_ID))
                .setModifiedBy(Objects.toString(record.getModifiedBy(), ""))
                .setModifiedByAccountName(Objects.toString(record.getModifiedByAccountName(), ""))
                .setModifiedAtIso8601(TimeUtils.toIso8601String(record.getModifiedAt(), ZONE_ID))
                .setCreatedBy(Objects.toString(record.getCreatedBy(), ""))
                .setCreatedByAccountName(Objects.toString(record.getCreatedByAccountName(), ""))
                .setCreatedAtIso8601(TimeUtils.toIso8601String(record.getCreatedAt(), ZONE_ID))
                .build())
            .collect(Collectors.toList());

        return GetNursingRecordResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllRecord(records)
            .build();
    }

    public AddNursingRecordResp addNursingRecord(String addNursingRecordReqJson) {
        final AddNursingRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                addNursingRecordReqJson, AddNursingRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return AddNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            log.error("accountId is empty.");
            return AddNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        long patientId = req.getPid();
        NursingRecordPB recordPb = req.getRecord();
        LocalDateTime effectiveTime = formatTime(TimeUtils.fromIso8601String(
            recordPb.getEffectiveTimeIso8601(), "UTC"));
        if (patientId <= 0 || effectiveTime == null) {
            StatusCode code = patientId <= 0 ? StatusCode.INVALID_PARAM_VALUE
                : StatusCode.INVALID_TIME_FORMAT;
            return AddNursingRecordResp.newBuilder()
                .setRt(protoService.getReturnCode(code)).build();
        }

        return lockExecutor.execute(List.of(LockKeys.nursingRecord(patientId, effectiveTime)),
            () -> {
                NursingRecord existing = recordRepo
                .findByPatientIdAndEffectiveTimeAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
                    patientId, effectiveTime, null).orElse(null);
                if (existing != null) {
                    return AddNursingRecordResp.newBuilder().setRt(protoService.getReturnCode(
                        StatusCode.NURSING_RECORD_ALREADY_EXISTS)).build();
                }
                PatientRecord patient = patientService.getPatientRecord(patientId);
                if (patient == null) {
                    return AddNursingRecordResp.newBuilder().setRt(protoService.getReturnCode(
                        StatusCode.PATIENT_NOT_FOUND)).build();
                }

                LocalDateTime nowUtc = TimeUtils.getNowUtc();
                NursingRecord record = recordRepo.save(NursingRecord.builder()
                    .patientId(patientId)
                    .content(recordPb.getContent())
                    .effectiveTime(effectiveTime)
                    .isDeleted(false)
                    .modifiedBy(accountId)
                    .modifiedByAccountName(accountName)
                    .modifiedAt(formatTime(nowUtc))
                    .createdBy(accountId)
                    .createdByAccountName(accountName)
                    .createdAt(formatTime(nowUtc))
                    .build());
                pnrUtils.updateLatestDataTime(patientId, patient.getDeptId(),
                    effectiveTime, effectiveTime, nowUtc);
                return AddNursingRecordResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.OK))
                    .setId(record.getId()).build();
            });
    }

    public GenericResp updateNursingRecord(String updateNursingRecordReqJson) {
        final UpdateNursingRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                updateNursingRecordReqJson, UpdateNursingRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return nursingRecordResponse(StatusCode.PARSE_JSON_FAILED);
        }
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return nursingRecordResponse(StatusCode.ACCOUNT_NOT_FOUND);

        NursingRecordPB recordPb = req.getRecord();
        if (recordPb.getId() <= 0) return nursingRecordResponse(StatusCode.INVALID_PARAM_VALUE);
        LocalDateTime targetTime = formatTime(TimeUtils.fromIso8601String(
            recordPb.getEffectiveTimeIso8601(), "UTC"));
        // 未提供时间沿用旧时间；提供了无法解析的时间则拒绝更新。
        if (!recordPb.getEffectiveTimeIso8601().isEmpty() && targetTime == null) {
            return nursingRecordResponse(StatusCode.INVALID_TIME_FORMAT);
        }
        return withNursingRecordLocks(List.of(recordPb.getId()), targetTime, records -> {
            if (records.isEmpty()) {
                return nursingRecordResponse(StatusCode.NURSING_RECORD_NOT_EXISTS);
            }
            NursingRecord old = records.get(0);
            LocalDateTime oldTime = old.getEffectiveTime();
            LocalDateTime newTime = targetTime == null ? formatTime(oldTime) : targetTime;
            PatientRecord patient = patientService.getPatientRecord(old.getPatientId());
            if (patient == null) return nursingRecordResponse(StatusCode.PATIENT_NOT_FOUND);

            NursingRecord duplicate = recordRepo
                .findByPatientIdAndEffectiveTimeAndPatientCriticalLisHandlingIdAndIsDeletedFalse(
                    old.getPatientId(), newTime, old.getPatientCriticalLisHandlingId())
                .orElse(null);
            if (duplicate != null && !duplicate.getId().equals(old.getId())) {
                return nursingRecordResponse(StatusCode.NURSING_RECORD_ALREADY_EXISTS);
            }
            if (Objects.equals(old.getContent(), recordPb.getContent())
                && Objects.equals(oldTime, newTime)) {
                return nursingRecordResponse(StatusCode.OK);
            }

            boolean updateCreator = getEnableUpdatingCreatedBy(old.getId());
            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            NursingRecord replacement = old.toBuilder()
                .id(null)
                .content(recordPb.getContent())
                .effectiveTime(newTime)
                .isDeleted(false)
                .deletedBy(null)
                .deletedAt(null)
                .reviewedBy(null)
                .reviewedByAccountName(null)
                .reviewedAt(null)
                .modifiedBy(account.getFirst())
                .modifiedByAccountName(account.getSecond())
                .modifiedAt(formatTime(nowUtc))
                .createdBy(updateCreator ? account.getFirst() : old.getCreatedBy())
                .createdByAccountName(updateCreator
                    ? account.getSecond() : old.getCreatedByAccountName())
                .createdAt(formatTime(updateCreator ? nowUtc : old.getCreatedAt()))
                .build();

            // 先 flush 释放部分唯一索引中的旧键，但不提前提交；任何后续异常整体回滚。
            old.setIsDeleted(true);
            old.setDeletedBy(account.getFirst());
            old.setDeletedAt(formatTime(nowUtc));
            recordRepo.saveAndFlush(old);
            recordRepo.save(replacement);
            if (StrUtils.isBlank(old.getContent())) recordRepo.delete(old);

            // 跨日修改按时间顺序更新护理单，避免反向移动记录时交叉等待日报行锁。
            List<LocalDateTime> affectedTimes = List.of(oldTime, newTime).stream()
                .distinct().sorted().toList();
            for (LocalDateTime time : affectedTimes) {
                pnrUtils.updateLatestDataTime(old.getPatientId(), patient.getDeptId(),
                    time, time, nowUtc);
            }
            return nursingRecordResponse(StatusCode.OK);
        });
    }

    public boolean getEnableUpdatingCreatedBy(Long nursingRecordId) {
        NursingRecord record = recordRepo.findByIdAndIsDeletedFalse(nursingRecordId).orElse(null);
        if (record == null) return false;
        PatientRecord patient = patientRepo.findById(record.getPatientId()).orElse(null);
        if (patient == null) return false;

        return settingService.getNursingRecordSettings(patient.getDeptId())
            .getEnableUpdatingCreatedBy();
    }

    public GenericResp deleteNursingRecord(String deleteNursingRecordReqJson) {
        final DeleteNursingRecordReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                deleteNursingRecordReqJson, DeleteNursingRecordReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return nursingRecordResponse(StatusCode.PARSE_JSON_FAILED);
        }
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return nursingRecordResponse(StatusCode.ACCOUNT_NOT_FOUND);

        return withNursingRecordLocks(List.of(req.getId()), null, records -> {
            if (records.isEmpty()) return nursingRecordResponse(StatusCode.OK);
            NursingRecord record = records.get(0);
            PatientRecord patient = patientService.getPatientRecord(record.getPatientId());
            if (patient == null) return nursingRecordResponse(StatusCode.PATIENT_NOT_FOUND);

            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            record.setIsDeleted(true);
            record.setDeletedBy(account.getFirst());
            record.setDeletedAt(formatTime(nowUtc));
            record.setModifiedBy(account.getFirst());
            record.setModifiedByAccountName(account.getSecond());
            record.setModifiedAt(formatTime(nowUtc));
            recordRepo.save(record);
            pnrUtils.updateLatestDataTime(record.getPatientId(), patient.getDeptId(),
                record.getEffectiveTime(), record.getEffectiveTime(), nowUtc);
            return nursingRecordResponse(StatusCode.OK);
        });
    }

    public GenericResp reviewNursingRecords(String reviewNursingRecordsReqJson) {
        final ReviewNursingRecordsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(
                reviewNursingRecordsReqJson, ReviewNursingRecordsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return nursingRecordResponse(StatusCode.PARSE_JSON_FAILED);
        }
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) return nursingRecordResponse(StatusCode.ACCOUNT_NOT_FOUND);

        LocalDateTime reviewTime = formatTime(
            TimeUtils.fromIso8601String(req.getReviewTimeIso8601(), "UTC"));
        if (reviewTime == null) return nursingRecordResponse(StatusCode.INVALID_TIME_FORMAT);
        List<Long> recordIds = req.getIdList().stream().distinct().toList();
        if (recordIds.isEmpty()) return nursingRecordResponse(StatusCode.OK);

        return withNursingRecordLocks(recordIds, null, records -> {
            if (records.size() != recordIds.size()) {
                return nursingRecordResponse(StatusCode.NURSING_RECORD_NOT_EXISTS);
            }
            if (records.stream()
                .anyMatch(r -> reviewTime.isBefore(formatTime(r.getEffectiveTime())))) {
                return nursingRecordResponse(
                    StatusCode.NURSING_RECORD_REVIEW_TIME_BEFORE_RECORD_TIME);
            }
            for (NursingRecord record : records) {
                record.setReviewedBy(account.getFirst());
                record.setReviewedByAccountName(account.getSecond());
                record.setReviewedAt(reviewTime);
            }
            recordRepo.saveAll(records);
            return nursingRecordResponse(StatusCode.OK);
        });
    }

    private static LocalDateTime formatTime(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MINUTES);
    }

    private GenericResp nursingRecordResponse(StatusCode code) {
        return GenericResp.newBuilder().setRt(protoService.getReturnCode(code)).build();
    }

    private <T> T withNursingRecordLocks(
        List<Long> ids, LocalDateTime targetTime, Function<List<NursingRecord>, T> action
    ) {
        Supplier<List<NursingRecord>> loader =
            () -> NursingRecordJdbc.findActiveRecords(jdbcTemplate, ids);
        List<NursingRecord> initial = loader.get();
        if (initial.isEmpty()) return action.apply(initial);
        List<String> fallbackKeys = new ArrayList<>(nursingRecordKeys(initial).values());
        return lockExecutor.executePlanned(
            () -> {
                List<NursingRecord> plannedRecords = loader.get();
                Map<Long, String> recordKeys = nursingRecordKeys(plannedRecords);
                List<String> resourceKeys = new ArrayList<>(recordKeys.values());
                // 预读后记录可能被删除；仍需合法锁集合，以便在事务中确认已不存在。
                if (resourceKeys.isEmpty()) resourceKeys.addAll(fallbackKeys);
                if (targetTime != null) {
                    for (NursingRecord record : plannedRecords) {
                        resourceKeys.add(LockKeys.nursingRecord(record.getPatientId(), targetTime));
                    }
                }
                return new NursingRecordLockPlan(recordKeys, resourceKeys, new ArrayList<>());
            },
            NursingRecordLockPlan::resourceKeys,
            plan -> {
                List<NursingRecord> current = loader.get();
                if (!plan.recordKeys().equals(nursingRecordKeys(current))) return false;
                plan.records().addAll(current);
                return true;
            },
            plan -> action.apply(plan.records()));
    }

    private Map<Long, String> nursingRecordKeys(List<NursingRecord> records) {
        return records.stream().collect(Collectors.toMap(NursingRecord::getId,
            record -> LockKeys.nursingRecord(
                record.getPatientId(), formatTime(record.getEffectiveTime()))));
    }

    private record NursingRecordLockPlan(
        Map<Long, String> recordKeys, List<String> resourceKeys, List<NursingRecord> records
    ) {}

    public GetNursingRecordShortcutsResp getNursingRecordShortcuts(String getNursingRecordShortcutsReqJson) {
        final GetNursingRecordShortcutsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingRecordShortcutsReqJson, GetNursingRecordShortcutsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 解析参数
        final Long pid = req.getPid();
        final LocalDateTime startTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        final LocalDateTime endTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (startTime == null || endTime == null || !startTime.isBefore(endTime)) {
            log.error("Invalid time range.");
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_RANGE))
                .build();
        }

        // 获取患者信息
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 获取执行医嘱
        List<MedExeRecordSummaryPB> mrsList = medReportUtils.generateMedExeRecordSummaryList(
            pid, deptId, startTime, endTime, TimeUtils.getNowUtc(), false
        ).stream()
        .sorted(Comparator.comparing(MedExeRecordSummaryPB::getIntakeGroupId)
            .thenComparing(MedExeRecordSummaryPB::getStartTimeIso8601)
        )
        .toList();
        List<NrShortcutMedExecPB> nrsMedExecList = new ArrayList<>();
        NrShortcutMedExecPB.Builder cur = null;
        for (MedExeRecordSummaryPB mrs : mrsList) {
            int gid = mrs.getIntakeGroupId();
            // 当 cur 为空或组别变化时，收尾上一组并开启新组
            if (cur == null || cur.getIntakeGroupId() != gid) {
                if (cur != null) nrsMedExecList.add(cur.build());
                cur = NrShortcutMedExecPB.newBuilder()
                        .setIntakeGroupId(gid)
                        .setIntakeGroupName(mrs.getIntakeGroupName());
            }
            cur.addMedExec(mrs);
        }
        if (cur != null) nrsMedExecList.add(cur.build());

        // 获取出入量记录
        Pair<LocalDateTime, LocalDateTime> queryUtcTimeRange = monitoringConfig.normalizePmrQueryTimeRange(
            BALANCE_GROUP_TYPE_ID, deptId, startTime, endTime
        );
        List<String> tubeParamCodes = patientTubeImpl.getMonitoringParamCodes(
            pid, queryUtcTimeRange.getFirst(), queryUtcTimeRange.getSecond()
        );
        List<MonitoringGroupBetaPB> groupBetaList = monitoringConfig.getMonitoringGroups(
            pid, deptId, BALANCE_GROUP_TYPE_ID, tubeParamCodes, accountId
        );
        PatientMonitoringService.GetMonitoringRecordsResult recordsResult = patientMonitoringService
            .getMonitoringRecords(pid, deptId, BALANCE_GROUP_TYPE_ID,
                startTime, endTime, true, groupBetaList, accountId
            );
        if (recordsResult.statusCode != StatusCode.OK) {
            log.error("Failed to get monitoring records: {}", recordsResult.statusCode);
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(recordsResult.statusCode))
                .build();
        }
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
        balanceCalculator.storeGroupRecordsStats(args);  // 存储小时汇总数据，统计对应的天数据
        List<PatientMonitoringRecord> recordList = recordsResult.recordList.stream()
            .filter(record -> !record.getEffectiveTime().isBefore(startTime) &&
                record.getEffectiveTime().isBefore(endTime)
            )
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        args.recordList = recordList;
        args.startTime = startTime;
        args.endTime = endTime;
        List<BalanceGroupSummaryPB> balanceGroupList = balanceCalculator.summarizeBalanceGroups(args);

        // 获取检验结果
        Pair<StatusCode, List<OverviewLisItemPB>> lisPair = lisService.getPatientLisItems(
            patientRecord, startTime, endTime
        );
        if (lisPair.getFirst() != StatusCode.OK) {
            return GetNursingRecordShortcutsResp.newBuilder()
                .setRt(protoService.getReturnCode(lisPair.getFirst()))
                .build();
        }
        List<OverviewLisItemPB> lisItemList = lisPair.getSecond();

        return GetNursingRecordShortcutsResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllMedExec(nrsMedExecList)
            .addAllBalance(balanceGroupList)
            .addAllLisItem(lisItemList)
            .build();
    }

    public GetNursingRecordShortcuts2Resp getNursingRecordShortcuts2(String getNursingRecordShortcuts2ReqJson) {
        final GetNursingRecordShortcuts2Req req;
        try {
            req = ProtoUtils.parseJsonToProto(getNursingRecordShortcuts2ReqJson, GetNursingRecordShortcuts2Req.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 解析参数
        final Long pid = req.getPid();
        LocalDateTime shiftStartUtc = TimeUtils.fromIso8601String(req.getShiftStartIso8601(), "UTC");
        if (shiftStartUtc == null) {
            log.error("Invalid shift start time: {}", req.getShiftStartIso8601());
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.INVALID_TIME_RANGE))
                .build();
        }

        // 获取患者信息
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        final String deptId = patientRecord.getDeptId();

        // 班次起始时间
        List<LocalDateTime> deptBalanceStatsUtcs = shiftUtils.getBalanceStatTimeUtcHistory(deptId);
        LocalDateTime midnightUtc = pnrUtils.getMidnightUtc(deptBalanceStatsUtcs, shiftStartUtc);
        LocalDateTime dayEndUtc = midnightUtc.plusDays(1).minusSeconds(1);
        LocalDateTime dbsuCurUtc = null;
        for (LocalDateTime statUtc : deptBalanceStatsUtcs) {
            if (!dayEndUtc.isBefore(statUtc)) {
                dbsuCurUtc = statUtc;
            } else {
                break;
            }
        }
        if (dbsuCurUtc == null) {
            log.error("Cannot find deptBalanceStatUtc for midnightUtc={}", midnightUtc);
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NO_SHIFT_SETTING))
                .build();
        }
        LocalDateTime dbsuCurLocal = TimeUtils.getLocalDateTimeFromUtc(dbsuCurUtc, ZONE_ID);
        int shiftStartHour = dbsuCurLocal.getHour();
        shiftStartUtc = midnightUtc.plusHours(shiftStartHour);
        final LocalDateTime shiftEndUtc = shiftStartUtc.plusDays(1);

        Pair<StatusCode, List<NrShortcutBalancesPB>> balancePair = buildShortcutBalances(
            pid, deptId, shiftStartUtc, shiftEndUtc, accountId);
        if (balancePair.getFirst() != StatusCode.OK) {
            return GetNursingRecordShortcuts2Resp.newBuilder()
                .setRt(protoService.getReturnCode(balancePair.getFirst()))
                .build();
        }
        List<NrShortcutBalancesPB> balanceList = balancePair.getSecond();

        return GetNursingRecordShortcuts2Resp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .addAllBalance(balanceList)
            .build();
    }

    private Pair<StatusCode, List<NrShortcutBalancesPB>> buildShortcutBalances(
        Long pid, String deptId, LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc, String accountId
    ) {
        Pair<LocalDateTime, LocalDateTime> queryUtcTimeRange = monitoringConfig.normalizePmrQueryTimeRange(
            BALANCE_GROUP_TYPE_ID, deptId, shiftStartUtc, shiftEndUtc
        );
        List<String> tubeParamCodes = patientTubeImpl.getMonitoringParamCodes(
            pid, queryUtcTimeRange.getFirst(), queryUtcTimeRange.getSecond()
        );
        List<MonitoringGroupBetaPB> groupBetaList = monitoringConfig.getMonitoringGroups(
            pid, deptId, BALANCE_GROUP_TYPE_ID, tubeParamCodes, accountId
        );
        PatientMonitoringService.GetMonitoringRecordsResult recordsResult = patientMonitoringService
            .getMonitoringRecords(pid, deptId, BALANCE_GROUP_TYPE_ID,
                shiftStartUtc, shiftEndUtc, true, groupBetaList, accountId
            );
        if (recordsResult.statusCode != StatusCode.OK) {
            log.error("Failed to get monitoring records: {}", recordsResult.statusCode);
            return new Pair<>(recordsResult.statusCode, new ArrayList<>());
        }

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
        balanceCalculator.storeGroupRecordsStats(args);
        List<PatientMonitoringRecord> recordList = recordsResult.recordList.stream()
            .filter(record -> !record.getEffectiveTime().isBefore(shiftStartUtc) &&
                record.getEffectiveTime().isBefore(shiftEndUtc)
            )
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        args.recordList = recordList;
        args.startTime = shiftStartUtc;
        args.endTime = shiftEndUtc;

        List<MonitoringGroupRecordsPB> groupRecordsList = balanceCalculator.getGroupRecordsList(args);
        Map<Integer, String> groupNameMap = recordsResult.groupBetaList.stream()
            .collect(Collectors.toMap(MonitoringGroupBetaPB::getId, MonitoringGroupBetaPB::getName));
        MonitoringGroupRecordsPB inGroupRecords = null;
        MonitoringGroupRecordsPB outGroupRecords = null;
        MonitoringGroupRecordsPB netGroupRecords = null;
        for (MonitoringGroupRecordsPB gr : groupRecordsList) {
            String groupName = groupNameMap.get(gr.getDeptMonitoringGroupId());
            if (BALANCE_IN_GROUP_NAME.equals(groupName)) inGroupRecords = gr;
            else if (BALANCE_OUT_GROUP_NAME.equals(groupName)) outGroupRecords = gr;
            else if (BALANCE_NET_GROUP_NAME.equals(groupName)) netGroupRecords = gr;
        }

        Map<String, MonitoringParamPB> paramMap = monitoringConfig.getMonitoringParams(deptId);
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap = new TreeMap<>();

        List<String> hourlyCodes = new ArrayList<>();
        if (!StrUtils.isBlank(NET_HOURLY_IN_CODE)) hourlyCodes.add(NET_HOURLY_IN_CODE);
        if (!StrUtils.isBlank(NET_HOURLY_OUT_CODE)) hourlyCodes.add(NET_HOURLY_OUT_CODE);
        List<PatientMonitoringRecord> hourlyRecords = hourlyCodes.isEmpty()
            ? new ArrayList<>()
            : monitoringRecordRepo.findByPidAndParamCodesAndEffectiveTimeRange(
                pid, hourlyCodes, shiftStartUtc, shiftEndUtc
            ).stream()
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        fillShortcutBalanceHourlyValues(balanceMap, hourlyRecords, paramMap);

        appendBalanceDetails(balanceMap, inGroupRecords, paramMap, NrShortcutBalancesPB.Builder::addInDetails, null);
        appendBalanceDetails(balanceMap, outGroupRecords, paramMap, NrShortcutBalancesPB.Builder::addOutDetails, null);
        appendBalanceDetails(balanceMap, netGroupRecords, paramMap, NrShortcutBalancesPB.Builder::addNetDetails,
            Set.of(NET_HOURLY_IN_CODE, NET_HOURLY_OUT_CODE, NET_HOURLY_NET_CODE));
        fillShortcutBalanceAccumulatedDetails(balanceMap, paramMap);

        List<NrShortcutBalancesPB> balanceList = balanceMap.values().stream()
            .map(NrShortcutBalancesPB.Builder::build)
            .toList();
        return new Pair<>(StatusCode.OK, balanceList);
    }

    private NrShortcutBalancesPB.Builder getBalanceBuilder(
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap, LocalDateTime timeUtc
    ) {
        return balanceMap.computeIfAbsent(timeUtc,
            t -> NrShortcutBalancesPB.newBuilder()
                .setEffectiveTimeIso8601(TimeUtils.toIso8601String(t, ZONE_ID))
        );
    }

    private void fillShortcutBalanceHourlyValues(
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap,
        List<PatientMonitoringRecord> hourlyRecords,
        Map<String, MonitoringParamPB> paramMap
    ) {
        if (balanceMap == null || hourlyRecords == null || hourlyRecords.isEmpty()) return;

        MonitoringParamPB inParam = paramMap == null ? null : paramMap.get(NET_HOURLY_IN_CODE);
        MonitoringParamPB outParam = paramMap == null ? null : paramMap.get(NET_HOURLY_OUT_CODE);
        MonitoringParamPB netParam = paramMap == null ? null : paramMap.get(NET_HOURLY_NET_CODE);
        ValueMetaPB inMeta = inParam == null ? null : inParam.getValueMeta();
        ValueMetaPB outMeta = outParam == null ? null : outParam.getValueMeta();
        ValueMetaPB netMeta = netParam == null ? null : netParam.getValueMeta();

        Map<LocalDateTime, GenericValuePB> inValueMap = new TreeMap<>();
        Map<LocalDateTime, GenericValuePB> outValueMap = new TreeMap<>();
        for (PatientMonitoringRecord record : hourlyRecords) {
            if (record == null) continue;
            LocalDateTime timeUtc = record.getEffectiveTime();
            if (timeUtc == null ||
                timeUtc.getMinute() != 0 || timeUtc.getSecond() != 0 || timeUtc.getNano() != 0
            ) {
                continue;
            }
            String code = record.getMonitoringParamCode();
            if (NET_HOURLY_IN_CODE.equals(code) && inMeta != null) {
                GenericValuePB value = getMonitoringRecordValue(record, inMeta);
                if (value == null) continue;
                value = ValueMetaUtils.formatParamValue(value, inMeta);
                GenericValuePB existing = inValueMap.get(timeUtc);
                inValueMap.put(timeUtc,
                    existing == null ? value : ValueMetaUtils.addGenericValue(existing, value, inMeta)
                );
            } else if (NET_HOURLY_OUT_CODE.equals(code) && outMeta != null) {
                GenericValuePB value = getMonitoringRecordValue(record, outMeta);
                if (value == null) continue;
                value = ValueMetaUtils.formatParamValue(value, outMeta);
                GenericValuePB existing = outValueMap.get(timeUtc);
                outValueMap.put(timeUtc,
                    existing == null ? value : ValueMetaUtils.addGenericValue(existing, value, outMeta)
                );
            }
        }

        if (inMeta != null) {
            GenericValuePB accIn = ValueMetaUtils.getDefaultValue(inMeta);
            for (Map.Entry<LocalDateTime, GenericValuePB> entry : inValueMap.entrySet()) {
                LocalDateTime timeUtc = entry.getKey();
                GenericValuePB value = entry.getValue();
                if (value == null) continue;
                NrShortcutBalancesPB.Builder builder = getBalanceBuilder(balanceMap, timeUtc);
                builder.setInMl(ValueMetaUtils.extractAndFormatParamValue(value, inMeta));
                accIn = ValueMetaUtils.addGenericValue(accIn, value, inMeta);
                builder.setAccInMl(ValueMetaUtils.extractAndFormatParamValue(accIn, inMeta));
            }
        }

        if (outMeta != null) {
            GenericValuePB accOut = ValueMetaUtils.getDefaultValue(outMeta);
            for (Map.Entry<LocalDateTime, GenericValuePB> entry : outValueMap.entrySet()) {
                LocalDateTime timeUtc = entry.getKey();
                GenericValuePB value = entry.getValue();
                if (value == null) continue;
                NrShortcutBalancesPB.Builder builder = getBalanceBuilder(balanceMap, timeUtc);
                builder.setOutMl(ValueMetaUtils.extractAndFormatParamValue(value, outMeta));
                accOut = ValueMetaUtils.addGenericValue(accOut, value, outMeta);
                builder.setAccOutMl(ValueMetaUtils.extractAndFormatParamValue(accOut, outMeta));
            }
        }

        ValueMetaPB netValueMeta = netMeta != null ? netMeta : (inMeta != null ? inMeta : outMeta);
        if (netValueMeta == null) return;

        Set<LocalDateTime> netTimes = new TreeSet<>();
        netTimes.addAll(inValueMap.keySet());
        netTimes.addAll(outValueMap.keySet());
        GenericValuePB accNet = ValueMetaUtils.getDefaultValue(netValueMeta);
        for (LocalDateTime timeUtc : netTimes) {
            GenericValuePB netValue = ValueMetaUtils.getDefaultValue(netValueMeta);
            GenericValuePB inValue = inValueMap.get(timeUtc);
            if (inValue != null && inMeta != null) {
                netValue = ValueMetaUtils.addGenericValue(
                    netValue,
                    convertMonitoringValue(inValue, inMeta, netValueMeta),
                    netValueMeta
                );
            }
            GenericValuePB outValue = outValueMap.get(timeUtc);
            if (outValue != null && outMeta != null) {
                netValue = ValueMetaUtils.subtractGenericValue(
                    netValue,
                    convertMonitoringValue(outValue, outMeta, netValueMeta),
                    netValueMeta
                );
            }
            NrShortcutBalancesPB.Builder builder = getBalanceBuilder(balanceMap, timeUtc);
            builder.setNetMl(ValueMetaUtils.extractAndFormatParamValue(netValue, netValueMeta));
            accNet = ValueMetaUtils.addGenericValue(accNet, netValue, netValueMeta);
            builder.setAccNetMl(ValueMetaUtils.extractAndFormatParamValue(accNet, netValueMeta));
        }
    }

    private void fillShortcutBalanceAccumulatedDetails(
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap,
        Map<String, MonitoringParamPB> paramMap
    ) {
        if (balanceMap == null || balanceMap.isEmpty()) return;

        Map<String, ValueMetaPB> metaByKey = new LinkedHashMap<>();
        if (paramMap != null) {
            for (MonitoringParamPB param : paramMap.values()) {
                if (param == null) continue;
                String name = param.getName();
                ValueMetaPB valueMeta = param.getValueMeta();
                if (StrUtils.isBlank(name) || valueMeta == null) continue;
                metaByKey.putIfAbsent(name, valueMeta);
            }
        }

        Map<String, Double> accInMap = new LinkedHashMap<>();
        Map<String, Double> accOutMap = new LinkedHashMap<>();
        Map<String, Double> accNetMap = new LinkedHashMap<>();
        for (NrShortcutBalancesPB.Builder builder : balanceMap.values()) {
            accumulateDetailsByKey(builder.getInDetailsList(), accInMap);
            builder.addAllAccInDetails(buildAccumulatedDetailList(accInMap, metaByKey));
            accumulateDetailsByKey(builder.getOutDetailsList(), accOutMap);
            builder.addAllAccOutDetails(buildAccumulatedDetailList(accOutMap, metaByKey));
            accumulateDetailsByKey(builder.getNetDetailsList(), accNetMap);
            builder.addAllAccNetDetails(buildAccumulatedDetailList(accNetMap, metaByKey));
        }
    }

    private void accumulateDetailsByKey(
        List<StrKeyValPB> details,
        Map<String, Double> accMap
    ) {
        if (details == null || details.isEmpty() || accMap == null) return;
        for (StrKeyValPB detail : details) {
            if (detail == null) continue;
            String key = detail.getKey();
            if (StrUtils.isBlank(key)) continue;
            Double value = parseDetailValue(detail.getVal());
            if (value == null) continue;
            accMap.put(key, accMap.getOrDefault(key, 0.0d) + value);
        }
    }

    private List<StrKeyValPB> buildAccumulatedDetailList(
        Map<String, Double> accMap,
        Map<String, ValueMetaPB> metaByKey
    ) {
        if (accMap == null || accMap.isEmpty()) return new ArrayList<>();
        List<StrKeyValPB> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : accMap.entrySet()) {
            String key = entry.getKey();
            Double value = entry.getValue();
            ValueMetaPB valueMeta = metaByKey == null ? null : metaByKey.get(key);
            String valueStr = formatAccumulatedValue(value, valueMeta);
            result.add(StrKeyValPB.newBuilder()
                .setKey(key == null ? "" : key)
                .setVal(valueStr == null ? "" : valueStr)
                .build());
        }
        return result;
    }

    private Double parseDetailValue(String valueStr) {
        if (StrUtils.isBlank(valueStr)) return null;
        String normalized = valueStr.replace(",", "").trim();
        if (normalized.isEmpty()) return null;
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String formatAccumulatedValue(Double value, ValueMetaPB valueMeta) {
        if (value == null) return "";
        if (valueMeta != null && ValueMetaUtils.isNumber(valueMeta)) {
            return ValueMetaUtils.formatNumberValue(value, valueMeta);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    private GenericValuePB getMonitoringRecordValue(PatientMonitoringRecord record, ValueMetaPB valueMeta) {
        if (record == null) return null;
        MonitoringValuePB monitoringValuePB = ProtoUtils.decodeMonitoringValue(record.getParamValue());
        if (monitoringValuePB != null) return monitoringValuePB.getValue();
        String valueStr = record.getParamValueStr();
        if (StrUtils.isBlank(valueStr) || valueMeta == null) return null;
        return ValueMetaUtils.toGenericValue(valueStr, valueMeta);
    }

    private GenericValuePB convertMonitoringValue(
        GenericValuePB value, ValueMetaPB srcMeta, ValueMetaPB dstMeta
    ) {
        if (value == null || srcMeta == null || dstMeta == null) return value;
        return ValueMetaUtils.convertGenericValue(value, srcMeta, dstMeta);
    }

    private void appendBalanceDetails(
        Map<LocalDateTime, NrShortcutBalancesPB.Builder> balanceMap,
        MonitoringGroupRecordsPB groupRecords,
        Map<String, MonitoringParamPB> paramMap,
        BiConsumer<NrShortcutBalancesPB.Builder, StrKeyValPB> detailAppender,
        Set<String> allowedParamCodes
    ) {
        if (groupRecords == null || detailAppender == null) return;
        for (MonitoringCodeRecordsPB codeRecords : groupRecords.getCodeRecordsList()) {
            String paramCode = codeRecords.getParamCode();
            if (SUMMARY_MONITORING_CODE.equals(paramCode)) continue;
            if (allowedParamCodes != null && !allowedParamCodes.contains(paramCode)) continue;

            MonitoringParamPB paramMeta = paramMap.get(paramCode);
            String key = paramMeta == null ? paramCode : paramMeta.getName();
            ValueMetaPB valueMeta = paramMeta == null ? null : paramMeta.getValueMeta();
            for (MonitoringRecordValPB val : codeRecords.getRecordValueList()) {
                if (StrUtils.isBlank(val.getRecordedAtIso8601())) continue;
                LocalDateTime timeUtc = TimeUtils.fromIso8601String(val.getRecordedAtIso8601(), "UTC");
                if (timeUtc == null) continue;
                String valueStr = val.getValueStr();
                if (StrUtils.isBlank(valueStr) && valueMeta != null) {
                    valueStr = ValueMetaUtils.extractAndFormatParamValue(val.getValue(), valueMeta);
                }
                NrShortcutBalancesPB.Builder builder = getBalanceBuilder(balanceMap, timeUtc);
                detailAppender.accept(builder, StrKeyValPB.newBuilder()
                    .setKey(key == null ? "" : key)
                    .setVal(valueStr == null ? "" : valueStr)
                    .build());
            }
        }
    }

    private String formatMonitoringValue(PatientMonitoringRecord record, String deptId) {
        String valueStr = record.getParamValueStr();
        if (!StrUtils.isBlank(valueStr)) return valueStr;

        MonitoringValuePB monitoringValuePB = ProtoUtils.decodeMonitoringValue(record.getParamValue());
        if (monitoringValuePB == null) return valueStr;
        ValueMetaPB meta = monitoringConfig.getMonitoringParamMeta(deptId, record.getMonitoringParamCode());
        if (meta == null) return valueStr;
        return ValueMetaUtils.extractAndFormatParamValue(monitoringValuePB.getValue(), meta);
    }

    private final String ZONE_ID;
    private final Integer BALANCE_GROUP_TYPE_ID;
    private final String BALANCE_IN_GROUP_NAME;
    private final String BALANCE_OUT_GROUP_NAME;
    private final String BALANCE_NET_GROUP_NAME;
    private final String NET_HOURLY_IN_CODE;
    private final String NET_HOURLY_OUT_CODE;
    private final String NET_HOURLY_NET_CODE;
    private final String SUMMARY_MONITORING_CODE;

    private final ConfigProtoService protoService;
    private final NursingRecordConfigPB configPb;
    private final UserService userService;
    private final PatientService patientService;
    private final MedReportUtils medReportUtils;
    private final MonitoringConfig monitoringConfig;
    private final PatientMonitoringService patientMonitoringService;
    private final BalanceCalculator balanceCalculator;
    private final ConfigShiftUtils shiftUtils;
    private final PatientTubeImpl patientTubeImpl;
    private final LisService lisService;
    private final PatientNursingReportInvalidationService pnrUtils;
    private final SettingService settingService;
    private final NursingRecordRepository recordRepo;
    private final PatientRecordRepository patientRepo;
    private final PatientMonitoringRecordRepository monitoringRecordRepo;
    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;
}
