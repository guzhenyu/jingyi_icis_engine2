package com.jingyicare.jingyi_icis_engine.service.reports;

import java.awt.Color;
import java.io.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Base64;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

import com.google.protobuf.TextFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReport.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReportAh2.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.entity.reports.*;
import com.jingyicare.jingyi_icis_engine.entity.scores.*;
import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.repository.reports.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class Ah2ReportData {
    public Ah2ReportData(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils configShiftUtils,
        @Autowired PatientService patientService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired BalanceCalculator balanceCalculator,
        @Autowired MedReportUtils medReportUtils,
        @Autowired PatientTubeImpl patientTubeImpl,
        @Autowired PatientNursingReportUtils pnrUtils,
        @Autowired PatientMonitoringRecordRepository pmrRepo,
        @Autowired NursingRecordRepository nrRepo,
        @Autowired PatientShiftRecordRepository psrRepo,
        @Autowired PatientScoreRepository psRepo,
        @Autowired VTECapriniScoreRepository vteCapriniScoreRepo,
        @Autowired PatientBgaRecordRepository pbgarRepo,
        @Autowired PatientBgaRecordDetailRepository pbgardRepo,
        @Autowired PatientNursingReportRepository pnrRepo,
        @Autowired PatientTubeRecordRepository ptrRepo,
        @Autowired PatientTubeStatusRecordRepository ptsrRepo,
        @Autowired AccountRepository accountRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.BALANCE_GROUP_TYPE_ID = monitoringConfig.getBalanceGroupTypeId();
        this.DOSAGE_DECIMAL_PLACES = protoService.getConfig().getMedication().getDosageDecimalPlaces();
        this.ENABLE_BALANCE_STATS_HOUR_SHIFT = protoService.getConfig().getMonitoringReport()
            .getSettings().getEnableBalanceStatsHourShift();
        this.medExeAdminCodes = new HashSet<>(protoService.getConfig().getMedication().getMedExeAdminCodeList());
        this.dietAdminCodes = new HashSet<>(protoService.getConfig().getMedication().getDietAdminCodeList());
        MonitoringParamPB colorParamPB = protoService.getConfig().getTube().getTubeSetting()
            .getParamList().stream().filter(p -> p.getCode().equals(Consts.DRAINAGE_TUBE_COLOR_CODE))
            .findFirst().orElse(null);
        if (colorParamPB == null) {
            log.error("Missing tube color param config.");
            LogUtils.flushAndQuit(context);
        }
        this.DRAINAGE_TUBE_COLOR_META = colorParamPB.getValueMeta();
        this.configShiftUtils = configShiftUtils;
        this.patientService = patientService;
        this.monitoringConfig = monitoringConfig;
        this.patientMonitoringService = patientMonitoringService;
        this.balanceCalculator = balanceCalculator;
        this.medReportUtils = medReportUtils;
        this.patientTubeImpl = patientTubeImpl;
        this.pnrUtils = pnrUtils;
        this.pmrRepo = pmrRepo;
        this.nrRepo = nrRepo;
        this.psrRepo = psrRepo;
        this.psRepo = psRepo;
        this.vteCapriniScoreRepo = vteCapriniScoreRepo;
        this.pbgarRepo = pbgarRepo;
        this.pbgardRepo = pbgardRepo;
        this.pnrRepo = pnrRepo;
        this.ptrRepo = ptrRepo;
        this.ptsrRepo = ptsrRepo;
        this.accountRepo = accountRepo;

        processingPids = new ConcurrentHashMap<>();
    }

    private void loadAccountSignatureMap(Ah2PdfContext ctx) {
        List<Account> accounts = accountRepo.findByIsDeletedFalse();
        accountIdToPk = new HashMap<>();
        ctx.accountSignPicMap = new HashMap<>();
        ctx.signImageCache = new HashMap<>();
        for (Account account : accounts) {
            if (account == null || account.getId() == null) continue;
            accountIdToPk.put(account.getAccountId(), account.getId());
            if (!StrUtils.isBlank(account.getSignPic())) {
                ctx.accountSignPicMap.put(account.getId(), account.getSignPic());
            }
        }
    }

    /*
     * 取数模块
     * collectPageData: 获取分页数据
     *       |--> tryAcquire: 尝试占位（成功返回 true，失败返回 false）
     *       |--> 获取历史数据
     *       |       |--> fetchDailyData: 补齐过期的数据
     *       |                |--> getPatientData: 获取病人数据
     *       |                |      |--> fillCtxForDailyData: 获取班次数据
     *       |                |      |--> getBalanceSummary: 获取出入量记录
     *       |                |      |--> getMonitoringData: 获取观察项，出入量数据，引流管数据
     *       |                |      |--> getMedExeData: 获取医嘱数据
     *       |                |      |--> getNursingRecords: 获取护理记录
     *       |                |      |--> getPatientScores: 获取护理评分
     *       |                |      |--> getBgaData: 获取血气数据
     *       |                |      |--> getTubeData: 获取管道数据
     *       |                |--> convertToAh2PageData: 转化成 Ah2PageData
     *       |                       |--> createSummaryRowBlock: 创建汇总行
     *       |                       |--> createRowBlock: 创建普通行
     *       |                                |-> setMonitoringItem
     *       |                                |-> setBloodPressure
     *       |                                |-> setMedExeItems
     *       |                                |-> setDrainageTubeItems
     *       |                                |-> setNursingRecords
     *       |                                |-> setNursingSignature  设置签名
     *       |                                |-> setNursingScore
     *       |                                |-> setBgaItems
     *       |                                |-> setTubeData
     *       |--> paginateData: 转化成分页数据，过滤掉无效页
     *       |--> release: 释放占位（务必放在 finally 中调用）
     **/
    public Pair<ReturnCode, List<Ah2PageData>> collectPageData(
        Ah2PdfContext ctx,
        Long pid, String deptId, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc,
        String accountId
    ) {
        loadAccountSignatureMap(ctx);

        // 检查参数合法性
        if (pid == null || pid <= 0 || deptId == null || deptId.isEmpty() ||
            queryStartUtc == null || queryEndUtc == null || !queryEndUtc.isAfter(queryStartUtc)
        ) {
            log.error("Invalid parameters.");
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_PARAM_VALUE), null
            );
        }

        // 尝试占位
        if (!tryAcquire(pid)) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(
                    statusCodeMsgs,
                    StatusCode.AH2_NURSING_REPORT_IS_IN_PROGRESS,
                    "(" + lastProcessedAt(pid) + ")"
                ), null
            );
        }

        // 获取病人信息
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PATIENT_NOT_FOUND), null
            );
        }
        final LocalDateTime admissionTimeUtc = patientRecord.getAdmissionTime();
        if (queryStartUtc.isBefore(admissionTimeUtc)) queryStartUtc = admissionTimeUtc;
        final LocalDateTime dischargeTimeUtc = patientRecord.getDischargeTime();
        if (dischargeTimeUtc != null && queryEndUtc.isAfter(dischargeTimeUtc)) queryEndUtc = dischargeTimeUtc;
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (queryEndUtc.isAfter(nowUtc)) queryEndUtc = nowUtc;

        // 获取病人护理单明细
        Map<LocalDateTime, Ah2PageData> dailyDataMap = new HashMap<>();
        for (PatientNursingReport pnr : pnrRepo.findByPid(pid)) {
            if (pnr.getLastProcessedAt() == null) continue;
            if (pnr.getLatestDataTime() != null &&
                pnr.getLastProcessedAt().isBefore(pnr.getLatestDataTime())
            ) continue;  // 数据不完整，跳过

            Ah2PageDataPB ah2PageDataPb = ProtoUtils.decodeAh2PageData(pnr.getDataPb());
            if (ah2PageDataPb == null) continue;
            Ah2PageData dailyData = new Ah2PageData();
            dailyData.fromProto(ah2PageDataPb);
            dailyDataMap.put(pnr.getEffectiveTimeMidnight(), dailyData);
        }

        long startQueryTimeMillis = System.currentTimeMillis();
        // 补齐缺失的病人护理单明细
        List<Ah2PageData> dailyDataList = new ArrayList<>();
        List<LocalDateTime> deptBalanceStatsUtcs = configShiftUtils.getBalanceStatTimeUtcHistory(deptId);
        int dbsuIdx = 0;
        LocalDateTime dbsuCurUtc = null;
        LocalDateTime dailyDataStartMidnightUtc = pnrUtils.getMidnightUtc(deptBalanceStatsUtcs, admissionTimeUtc);
        LocalDateTime dailyDataEndMidnightUtc = pnrUtils.getMidnightUtc(deptBalanceStatsUtcs, queryEndUtc);
        for (LocalDateTime midnightUtc = dailyDataStartMidnightUtc;
             !midnightUtc.isAfter(dailyDataEndMidnightUtc);
             midnightUtc = midnightUtc.plusDays(1)
        ) {
            setLastProcessedAt(pid, midnightUtc);

            // 定位到当天的班次起始时间，获取班次起始小时
            LocalDateTime dayEndUtc = midnightUtc.plusDays(1).minusSeconds(1);
            while (dbsuIdx < deptBalanceStatsUtcs.size() &&
                !dayEndUtc.isBefore(deptBalanceStatsUtcs.get(dbsuIdx))
            ) {
                dbsuCurUtc = deptBalanceStatsUtcs.get(dbsuIdx);
                dbsuIdx++;
            }
            if (dbsuCurUtc == null) {
                log.error("cannot find deptBalanceStatUtc for midnightUtc=" + midnightUtc);
                release(pid);
                return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INTERNAL_EXCEPTION), null);
            }
            LocalDateTime dbsuCurLocal = TimeUtils.getLocalDateTimeFromUtc(dbsuCurUtc, ZONE_ID);
            int shiftStartHour = dbsuCurLocal.getHour();
            final LocalDateTime shiftStartUtc = midnightUtc.plusHours(shiftStartHour);
            final LocalDateTime shiftEndUtc = shiftStartUtc.plusDays(1);

            // 获取或补齐护理单数据
            Ah2PageData pageData = dailyDataMap.get(midnightUtc);
            if (pageData == null) {
                Pair<StatusCode, Ah2PageData> pageDataPair = fetchDailyData(
                    ctx, pid, deptId, midnightUtc, shiftStartUtc, shiftEndUtc, accountId, nowUtc
                );
                StatusCode statusCode = pageDataPair.getFirst() != StatusCode.OK ?
                    pageDataPair.getFirst() :
                    (pageDataPair.getSecond() == null ? StatusCode.INTERNAL_EXCEPTION : StatusCode.OK);
                if (statusCode != StatusCode.OK) {
                    log.error("cannot find deptBalanceStatUtc for midnightUtc=" + midnightUtc);
                    release(pid);
                    return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgs, statusCode), null);
                }
                // pageData 不可能为 null（前面已判空）
                pageData = pageDataPair.getSecond();
            }
            dailyDataList.add(pageData);
        }
        long endQueryTimeMillis = System.currentTimeMillis();
        log.info("elasped time for query: {}ms", (endQueryTimeMillis - startQueryTimeMillis));

        // 转化成分页数据，过滤掉无效页
        List<Ah2PageData> pageDataList = paginateData(
            dailyDataList, dailyDataStartMidnightUtc, admissionTimeUtc, ctx.tblCommon.getBodyRows()
        );
        final LocalDateTime finalQueryStartUtc = queryStartUtc;
        final LocalDateTime finalQueryEndUtc = queryEndUtc;
        pageDataList = pageDataList.stream().filter(p -> (
            p.pageStartTs != null && p.pageEndTs != null &&
            !p.pageEndTs.isBefore(finalQueryStartUtc) && !p.pageStartTs.isAfter(finalQueryEndUtc)
        )).toList();
        release(pid);

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK), pageDataList);
    }

    public String getContent(ContentCodeEnum contentCode, Ah2PageData pageData) {
        if (contentCode == ContentCodeEnum.YEAR) {
            return pageData.yearStr == null ? "" : pageData.yearStr;
        }
        return "";
    }

    // 尝试占位（成功返回 true，失败返回 false）
    private boolean tryAcquire(long pid) {
        cleanupStale();
        var prev = processingPids.putIfAbsent(pid, new PidInFlight());
        return prev == null;
    }

    // 释放占位（务必放在 finally 中调用）
    private void release(long pid) {
        processingPids.remove(pid);
    }

    // 返回最后处理时间的本地化字符串（失败返回 ""）
    private String lastProcessedAt(long pid) {
        cleanupStale();
        var inFlight = processingPids.get(pid);
        if (inFlight == null) return "";
        LocalDateTime nowLocal = TimeUtils.getLocalDateTimeFromUtc(inFlight.lastProgressAt, ZONE_ID);
        if (nowLocal == null) return "";
        return TimeUtils.getYearMonthDay(nowLocal);
    }

    // 更新最后处理时间（务必在处理过程中定期调用）
    private void setLastProcessedAt(long pid, LocalDateTime timeUtc) {
        var inFlight = processingPids.get(pid);
        if (inFlight != null) {
            inFlight.lastProgressAt = timeUtc;
        }
    }


    // 简单的陈旧清理，避免服务异常中断后卡死
    private void cleanupStale() {
        long now = System.currentTimeMillis();
        processingPids.forEach((k, v) -> {
            if (now - v.startedAt > STALE_MS) {
                processingPids.remove(k, v);
            }
        });
    }

    private Long getAccountPk(String accountId) {
        if (StrUtils.isBlank(accountId)) return null;
        if (accountIdToPk != null && accountIdToPk.containsKey(accountId)) {
            return accountIdToPk.get(accountId);
        }
        try {
            return Long.parseLong(accountId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Data
    private static class DrainageTubeData {
        public DrainageTubeData(String code, String name) {
            this.code = code;
            this.tubeName = name;
        }
        public String code;      // 引流管观察项编码
        public String tubeName;  // 引流管名称
        public String volume;  // 引流量
        public String color;  // 引流颜色
    }

    private static class PatientData {
        public PatientData(LocalDateTime effectiveTime) {
            this.effectiveTime = effectiveTime;
            this.summaryTitle = "s";
            this.summary = new ArrayList<>();
            this.mpKvMap = new HashMap<>();
            this.medExeList = new ArrayList<>();
            this.dietExeList = new ArrayList<>();
            this.drainageTubeMap = new HashMap<>();
            this.nursingRecords = new ArrayList<>();
            this.nursingScores = new HashMap<>();
            this.tubeDataMap = new HashMap<>();
            this.bgaKvMap = new HashMap<>();
        }

        public LocalDateTime effectiveTime;

        public String summaryTitle;  // "小计"(半日) 或 "总计"(全天)
        public List<BalanceGroupSummaryPB> summary;

        // 观察项：
        Map<String, GenericValuePB> mpKvMap;  // 包含意识、体温、血压、每小时入量、每小时出量等。** 注意：hourly_intake/hourly_output 8:00代表8:00-9:00, 在报表中显示为 9:00

        // 执行用药
        List<MedExeRecordSummaryPB> medExeList;

        // 饮食/鼻饲
        List<MedExeRecordSummaryPB> dietExeList;

        // 引流管(名&量&色)
        Map<String/*tube_monitoring_param_code*/, DrainageTubeData> drainageTubeMap;

        // 护理记录
        List<Pair<String/*content*/, Long/*nurseAccountId*/>> nursingRecords;

        // 护理评分
        Map<String/*scoreCode*/, String/*scoreValue*/> nursingScores;

        // 管道
        Map<String/*tubeName*/, Map<String, String>> tubeDataMap;

        // BGA
        Map<String, GenericValuePB> bgaKvMap;  // 包含血气分析的各项指标

    }

    private Pair<StatusCode, Ah2PageData> fetchDailyData(
        Ah2PdfContext ctx,
        long pid, String deptId, LocalDateTime midnightUtc,
        LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc,
        String accountId, LocalDateTime nowUtc
    ) {
        // 获取观察项元数据
        ctx.mpMap = monitoringConfig.getMonitoringParams(deptId);
        if (ctx.mpMap == null || ctx.mpMap.isEmpty()) {
            log.error("No monitoring params configured for deptId=" + deptId);
            return new Pair<>(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST, null);
        }
        ctx.mpVmMap = new HashMap<>();
        for (Map.Entry<String, MonitoringParamPB> entry : ctx.mpMap.entrySet()) {
            if (entry.getValue() != null && entry.getValue().getValueMeta() != null) {
                ctx.mpVmMap.put(entry.getKey(), entry.getValue().getValueMeta());
            }
        }

        // 获取病人数据
        Map<LocalDateTime, PatientData> patientDataMap = new HashMap<>();
        StatusCode statusCode = getPatientData(
            ctx, pid, deptId, midnightUtc, shiftStartUtc, shiftEndUtc, accountId, nowUtc,
            patientDataMap
        );
        if (statusCode != StatusCode.OK) {
            return new Pair<>(statusCode, null);
        }

        Ah2PageData ah2PageData = convertToAh2PageData(ctx, patientDataMap, nowUtc);
        Ah2PageDataPB ah2PageDataPb = ah2PageData.toProto();
        pnrUtils.updateLastProcessedAt(pid, midnightUtc, ah2PageDataPb, nowUtc);

        // 返回 Ah2PageData
        return new Pair<>(StatusCode.OK, ah2PageData);
    }

    private StatusCode getPatientData(
        Ah2PdfContext ctx, long pid, String deptId, LocalDateTime midnightUtc,
        LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc,
        String accountId, LocalDateTime nowUtc,
        Map<LocalDateTime, PatientData> patientDataMap /*output param*/
    ) {
        // 获取班次数据
        fillCtxForDailyData(ctx, pid, shiftStartUtc, shiftEndUtc);

        // 获取出入量记录
        StatusCode statusCode = getBalanceSummary(
            ctx, pid, deptId, midnightUtc, shiftStartUtc, shiftEndUtc,
            accountId, nowUtc, patientDataMap
        );
        if (statusCode != StatusCode.OK) {
            return statusCode;
        }

        // 获取观察项
        getMonitoringData(ctx, pid, shiftStartUtc, shiftEndUtc, patientDataMap);

        // 获取医嘱数据
        getMedExeData(pid, deptId, shiftStartUtc, shiftEndUtc, nowUtc, patientDataMap);

        // 获取护理记录
        getNursingRecords(pid, shiftStartUtc, shiftEndUtc, patientDataMap);

        // 获取护理评分
        getPatientScores(pid, shiftStartUtc, shiftEndUtc, patientDataMap);

        // 获取血气数据
        getBgaData(pid, shiftStartUtc, shiftEndUtc, patientDataMap);

        // 获取管道数据
        getTubeData(pid, shiftStartUtc, shiftEndUtc, patientDataMap);

        return StatusCode.OK;
    }

    private void fillCtxForDailyData(
        Ah2PdfContext ctx, long pid, LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc
    ) {
        List<PatientShiftRecord> shiftRecords = psrRepo.findByPidAndOverlappingTimeRange(
            pid, shiftStartUtc, shiftEndUtc
        );
        ctx.shiftNurses = new ArrayList<>();
        for (PatientShiftRecord psr : shiftRecords) {
            Long nurseAccountId = getAccountPk(psr.getShiftNurseId());
            LocalDateTime start = psr.getShiftStart();
            LocalDateTime end = psr.getShiftEnd();
            if (nurseAccountId == null || start == null || end == null) continue;
            ctx.shiftNurses.add(new Pair<>(new Pair<>(start, end), nurseAccountId));
        }
    }

    private StatusCode getBalanceSummary(
        Ah2PdfContext ctx, long pid, String deptId, LocalDateTime midnightUtc,
        LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc,
        String accountId, LocalDateTime nowUtc,
        Map<LocalDateTime, PatientData> patientDataMap /*output param*/
    ) {
        // 获取出入量记录
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
            .getMonitoringRecords(
                pid, deptId, BALANCE_GROUP_TYPE_ID,
                shiftStartUtc, shiftEndUtc, false/*syncDeviceData*/,
                groupBetaList, accountId
            );
        if (recordsResult.statusCode != StatusCode.OK) {
            log.error("Failed to get monitoring records: {}", recordsResult.statusCode);
            return recordsResult.statusCode;
        }
        if (!shiftStartUtc.equals(recordsResult.recordsQueryStartUtc) ||
            shiftEndUtc.isAfter(recordsResult.recordsQueryEndUtc)
        ) {
            log.error("Records query time mismatch {}-{}, {}-{}.", shiftStartUtc, shiftEndUtc,
                recordsResult.recordsQueryStartUtc, recordsResult.recordsQueryEndUtc
            );
            return StatusCode.INTERNAL_EXCEPTION;
        }
        BalanceCalculator.GetGroupRecordsListArgs args = new BalanceCalculator.GetGroupRecordsListArgs(
            pid, deptId,
            recordsResult.recordsQueryStartUtc,
            recordsResult.recordsQueryEndUtc,
            recordsResult.balanceStatTimeUtcHistory,
            ctx.mpMap,
            recordsResult.groupBetaList,
            recordsResult.recordList,
            accountId
        );
        balanceCalculator.storeGroupRecordsStats(args);  // 存储小时汇总数据，统计对应的天数据

        // 统计天数据
        List<PatientMonitoringRecord> recordList = recordsResult.recordList.stream()
            .filter(record -> record.getEffectiveTime().isBefore(shiftEndUtc))
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        List<BalanceGroupSummaryPB> balanceGroupList = balanceCalculator.summarizeBalanceGroups(args);
        if (balanceGroupList == null || balanceGroupList.isEmpty()) {
            log.info("No balance group summary.");
            return StatusCode.OK;
        }
        PatientData patientData = patientDataMap.computeIfAbsent(
            shiftEndUtc, k -> new PatientData(shiftEndUtc)
        );
        patientData.summaryTitle = Consts.REPORT_TEMPLATE_AH2_FULL_DAY_SUMMARY;
        patientData.summary = balanceGroupList;

        // 统计半天数据
        LocalDateTime halfDayEndUtc = shiftStartUtc.plusHours(12);
        recordList = recordsResult.recordList.stream()
            .filter(record -> record.getEffectiveTime().isBefore(halfDayEndUtc))
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        args.recordList = recordList;
        args.endTime = halfDayEndUtc;
        List<BalanceGroupSummaryPB> halfDayBalanceGroupList = balanceCalculator.summarizeBalanceGroups(args);
        if (halfDayBalanceGroupList == null || halfDayBalanceGroupList.isEmpty()) {
            log.info("No half-day balance group summary.");
            return StatusCode.OK;
        }
        PatientData halfDayPatientData = patientDataMap.computeIfAbsent(
            halfDayEndUtc, k -> new PatientData(halfDayEndUtc)
        );
        halfDayPatientData.summaryTitle = Consts.REPORT_TEMPLATE_AH2_HALF_DAY_SUMMARY;
        halfDayPatientData.summary = halfDayBalanceGroupList;

        return StatusCode.OK;
    }

    private void getMonitoringData(
        Ah2PdfContext ctx, Long pid, LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc, Map<LocalDateTime, PatientData> patientDataMap
    ) {
        List<PatientMonitoringRecord> mpRecords = pmrRepo
            .findByPidAndEffectiveTimeRange(pid, shiftStartUtc, shiftEndUtc);
        // 提取每小时入量、每小时出量、每小时尿量
        List<Pair<LocalDateTime, GenericValuePB>> hourlyIntakeList = new ArrayList<>();
        List<Pair<LocalDateTime, GenericValuePB>> hourlyOutputList = new ArrayList<>();
        List<Pair<LocalDateTime, GenericValuePB>> urineOutputList = new ArrayList<>();
        // 提取引流管数据
        Map<String, String> tubeCodeNameMap = patientTubeImpl.getMonitoringParamCodeNames(
            pid, shiftStartUtc, shiftEndUtc);  // 引流管 编码-名称 映射
        for (PatientMonitoringRecord pmr : mpRecords) {
            String paramCode = pmr.getMonitoringParamCode();
            LocalDateTime effectiveTime = pmr.getEffectiveTime();
            MonitoringValuePB mvPb = ProtoUtils.decodeMonitoringValue(pmr.getParamValue());
            if (StrUtils.isBlank(paramCode) || effectiveTime == null || mvPb == null) {
                log.warn("Invalid monitoring record: {}", pmr.getId());
                continue;
            }

            // 出入量时间微调
            LocalDateTime balanceEffectiveTime = effectiveTime;
            if (ENABLE_BALANCE_STATS_HOUR_SHIFT &&
                balanceEffectiveTime.getMinute() == 0 &&
                balanceEffectiveTime.getSecond() == 0 &&
                balanceEffectiveTime.getNano() == 0
            ) {
                balanceEffectiveTime = balanceEffectiveTime.plusHours(1);
            }

            // 出入量，尿量
            if (paramCode.equals(MP_HOURLY_INTAKE)) {
                if (effectiveTime.getMinute() == 0 && effectiveTime.getSecond() == 0 &&
                    effectiveTime.getNano() == 0
                ) {
                    hourlyIntakeList.add(new Pair<>(balanceEffectiveTime, mvPb.getValue()));
                }
                continue;
            }
            if (paramCode.equals(MP_HOURLY_OUTPUT)) {
                if (effectiveTime.getMinute() == 0 && effectiveTime.getSecond() == 0 &&
                    effectiveTime.getNano() == 0
                ) {
                    hourlyOutputList.add(new Pair<>(balanceEffectiveTime, mvPb.getValue()));
                }
                continue;
            }
            if (paramCode.equals(MP_URINE_OUTPUT)) {
                if (effectiveTime.getMinute() == 0 && effectiveTime.getSecond() == 0 &&
                    effectiveTime.getNano() == 0
                ) {
                    urineOutputList.add(new Pair<>(balanceEffectiveTime, mvPb.getValue()));
                }
                continue;
            }

            // 设置病人观察项数据
            if (paramCode.equals(MP_GASTRIC_FLUID_VOLUME) ||
                paramCode.equals(MP_STOOL_VOLUME) ||
                paramCode.equals(MP_CRRT_UF)
            ) {
                effectiveTime = effectiveTime.plusHours(1);
            }
            final LocalDateTime effectiveTimeF = effectiveTime; 
            PatientData patientData = patientDataMap.computeIfAbsent(
                effectiveTimeF, k -> new PatientData(effectiveTimeF)
            );
            patientData.mpKvMap.put(paramCode, mvPb.getValue());

            // 设置病人数据-引流管
            String tubeParamCode = null;
            String tubeName = null;
            String tubeVol = null;
            String tubeColor = null;
            if (paramCode.endsWith("_" + Consts.DRAINAGE_TUBE_COLOR_CODE)) {
                tubeParamCode = paramCode.substring(0,
                    paramCode.length() - Consts.DRAINAGE_TUBE_COLOR_CODE.length() - 1);
                tubeName = tubeCodeNameMap.get(tubeParamCode);
                if (tubeName == null) continue;  // 非法引流管颜色编码;
                String rawTubeColor = ValueMetaUtils.extractAndFormatParamValue(
                    mvPb.getValue(), DRAINAGE_TUBE_COLOR_META);
                if (rawTubeColor.equals("血性")) {
                    tubeColor = "1";
                } else if (rawTubeColor.equals("褐色")) {
                    tubeColor = "2";
                } else if (rawTubeColor.equals("黄色")) {
                    tubeColor = "3";
                }
                if (StrUtils.isBlank(tubeColor)) continue;
            } else if (tubeCodeNameMap.containsKey(paramCode)) {
                tubeParamCode = paramCode;
                tubeName = tubeCodeNameMap.get(tubeParamCode);
                if (tubeName == null) continue;  // 非法引流管编码;
                ValueMetaPB vmPb = ctx.mpVmMap.get(paramCode);
                if (vmPb == null) continue;  // 非法引流管编码;
                tubeVol = ValueMetaUtils.extractAndFormatParamValue(mvPb.getValue(), vmPb);
                if (StrUtils.isBlank(tubeVol)) continue;
            } else {
                continue;  // 非引流管编码
            }
            final String tubeParamCodeF = tubeParamCode;
            final String tubeNameF = tubeName;
            final LocalDateTime balanceEffectiveTimeF = balanceEffectiveTime;
            patientData = patientDataMap.computeIfAbsent(
                balanceEffectiveTimeF, k -> new PatientData(balanceEffectiveTimeF)
            );
            DrainageTubeData tubeData = patientData.drainageTubeMap.computeIfAbsent(
                tubeParamCode, k -> new DrainageTubeData(tubeParamCodeF, tubeNameF)
            );
            if (tubeColor != null) tubeData.color = tubeColor;
            if (tubeVol != null ) tubeData.volume = tubeVol;
        }

        // 提取元数据
        ValueMetaPB hourlyIntakeMeta = ctx.mpVmMap.get(MP_HOURLY_INTAKE);
        ValueMetaPB hourlyOutputMeta = ctx.mpVmMap.get(MP_HOURLY_OUTPUT);
        ValueMetaPB urineOutputMeta = ctx.mpVmMap.get(MP_URINE_OUTPUT);
        if (hourlyIntakeMeta == null || hourlyOutputMeta == null || urineOutputMeta == null) {
            log.warn("Missing monitoring param meta for hourly intake/output or urine output.");
            return;
        }

        // 处理每小时入量
        GenericValuePB accTotalIntake = ValueMetaUtils.getDefaultValue(hourlyIntakeMeta);
        hourlyIntakeList.sort(Comparator.comparing(Pair::getFirst));
        for (Pair<LocalDateTime, GenericValuePB> pair : hourlyIntakeList) {
            LocalDateTime effectiveTime = pair.getFirst();
            GenericValuePB value = pair.getSecond();
            if (effectiveTime == null || value == null) continue;
            PatientData patientData = patientDataMap.computeIfAbsent(
                effectiveTime, k -> new PatientData(effectiveTime)
            );
            patientData.mpKvMap.put(MP_HOURLY_INTAKE, value);
            accTotalIntake = ValueMetaUtils.addGenericValue(accTotalIntake, value, hourlyIntakeMeta);
            patientData.mpKvMap.put(MP_ACCU_INTAKE, accTotalIntake);
        }

        // 处理每小时出量
        GenericValuePB accTotalOutput = ValueMetaUtils.getDefaultValue(hourlyOutputMeta);
        hourlyOutputList.sort(Comparator.comparing(Pair::getFirst));
        for (Pair<LocalDateTime, GenericValuePB> pair : hourlyOutputList) {
            LocalDateTime effectiveTime = pair.getFirst();
            GenericValuePB value = pair.getSecond();
            if (effectiveTime == null || value == null) continue;
            PatientData patientData = patientDataMap.computeIfAbsent(
                effectiveTime, k -> new PatientData(effectiveTime)
            );
            patientData.mpKvMap.put(MP_HOURLY_OUTPUT, value);
            accTotalOutput = ValueMetaUtils.addGenericValue(accTotalOutput, value, hourlyOutputMeta);
            patientData.mpKvMap.put(MP_ACCU_OUTPUT, accTotalOutput);
        }

        // 处理每小时尿量
        GenericValuePB accTotalUrineOutput = ValueMetaUtils.getDefaultValue(urineOutputMeta);
        urineOutputList.sort(Comparator.comparing(Pair::getFirst));
        for (Pair<LocalDateTime, GenericValuePB> pair : urineOutputList) {
            LocalDateTime effectiveTime = pair.getFirst();
            GenericValuePB value = pair.getSecond();
            if (effectiveTime == null || value == null) continue;
            PatientData patientData = patientDataMap.computeIfAbsent(
                effectiveTime, k -> new PatientData(effectiveTime)
            );
            patientData.mpKvMap.put(MP_URINE_OUTPUT, value);
            accTotalUrineOutput = ValueMetaUtils.addGenericValue(accTotalUrineOutput, value, urineOutputMeta);
            patientData.mpKvMap.put(MP_ACCU_URINE_OUTPUT, accTotalUrineOutput);
        }
    }

    private void getMedExeData(
        Long pid, String deptId, LocalDateTime startUtc, LocalDateTime endUtc,
        LocalDateTime calcTimeUtc, Map<LocalDateTime, PatientData> patientDataMap
    ) {
        for (MedExeRecordSummaryPB exeSum : medReportUtils.generateMedExeRecordSummaryList(
            pid, deptId, startUtc, endUtc, calcTimeUtc, false/*useOverlapWindow*/
        )) {
            LocalDateTime effectiveTime = TimeUtils.fromIso8601String(
                exeSum.getStartTimeIso8601(), "UTC");
            if (effectiveTime == null) continue;

            PatientData patientData = patientDataMap.computeIfAbsent(
                effectiveTime, k -> new PatientData(effectiveTime)
            );
            if (medExeAdminCodes.isEmpty() && !dietAdminCodes.contains(exeSum.getAdminCode()) ||
                medExeAdminCodes.contains(exeSum.getAdminCode())
            ) {
                patientData.medExeList.add(exeSum);
            }
            if (dietAdminCodes.contains(exeSum.getAdminCode())) {
                patientData.dietExeList.add(exeSum);
            }
        }
    }

    private void getNursingRecords(
        Long pid, LocalDateTime startUtc, LocalDateTime endUtc,
        Map<LocalDateTime, PatientData> patientDataMap
    ) {
        List<NursingRecord> records = nrRepo.findByPatientIdAndEffectiveTimeBetweenAndIsDeletedFalse(
            pid, startUtc, endUtc.minusSeconds(1)
        );

        // 按时间分组
        Map<LocalDateTime, List<NursingRecord>> recordsMap = new HashMap<>();
        for (NursingRecord record : records) {
            if (record.getEffectiveTime() == null || StrUtils.isBlank(record.getContent())) continue;
            LocalDateTime effectiveTime = record.getEffectiveTime();
            List<NursingRecord> recList = recordsMap.computeIfAbsent(
                effectiveTime, k -> new ArrayList<>()
            );
            recList.add(record);
        }

        // 填充PatientData
        for (Map.Entry<LocalDateTime, List<NursingRecord>> entry : recordsMap.entrySet()) {
            LocalDateTime effectiveTime = entry.getKey();
            List<NursingRecord> recList = entry.getValue();
            if (recList == null || recList.isEmpty()) continue;
            recList.sort(Comparator.comparing(NursingRecord::getId));

            PatientData patientData = patientDataMap.computeIfAbsent(
                effectiveTime, k -> new PatientData(effectiveTime)
            );
            for (NursingRecord record : recList) {
                Long nurseAccountId = getAccountPk(record.getCreatedBy());
                patientData.nursingRecords.add(new Pair<>(
                    record.getContent(), nurseAccountId
                ));
            }
        }
    }

    private void getPatientScores(
        Long pid, LocalDateTime startUtc, LocalDateTime endUtc,
        Map<LocalDateTime, PatientData> patientDataMap
    ) {
        List<PatientScore> scores = psRepo.findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(
            pid, startUtc, endUtc.minusSeconds(1)
        );

        // 按时间分组
        Map<LocalDateTime, Map<String/*scoreGroupCode*/, String/*scoreStr*/>> scoreMap = new HashMap<>();
        for (PatientScore score : scores) {
            if (score.getEffectiveTime() == null || StrUtils.isBlank(score.getScoreGroupCode()) ||
                StrUtils.isBlank(score.getScoreStr())
            ) continue;
            LocalDateTime effectiveTime = score.getEffectiveTime();
            Map<String, String> scMap = scoreMap.computeIfAbsent(
                effectiveTime, k -> new HashMap<>()
            );
            String scoreStr = score.getScoreStr().endsWith("分") ?
                score.getScoreStr().substring(0, score.getScoreStr().length() - 1) :
                score.getScoreStr();
            scMap.put(score.getScoreGroupCode(), scoreStr);
        }

        List<VTECapriniScore> vteScores = vteCapriniScoreRepo.findByPidAndScoreTimeBetweenAndIsDeletedFalse(
            pid, startUtc, endUtc.minusSeconds(1)
        );
        for (VTECapriniScore vteScore : vteScores) {
            if (vteScore.getScoreTime() == null || vteScore.getTotalScore() == null) continue;
            LocalDateTime scoreTime = vteScore.getScoreTime();
            Map<String, String> scMap = scoreMap.computeIfAbsent(
                scoreTime, k -> new HashMap<>()
            );
            scMap.put(PS_VTE_CAPRINI, vteScore.getTotalScore().toString());
        }

        // 填充PatientData
        for (Map.Entry<LocalDateTime, Map<String, String>> entry : scoreMap.entrySet()) {
            LocalDateTime effectiveTime = entry.getKey();
            Map<String, String> scMap = entry.getValue();
            if (scMap == null || scMap.isEmpty()) continue;
            PatientData patientData = patientDataMap.computeIfAbsent(
                effectiveTime, k -> new PatientData(effectiveTime)
            );
            patientData.nursingScores = scMap;
        }
    }

    private void getBgaData(
        Long pid, LocalDateTime startUtc, LocalDateTime endUtc,
        Map<LocalDateTime, PatientData> patientDataMap
    ) {
        List<PatientBgaRecord> bgaRecords = pbgarRepo
            .findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(pid, startUtc, endUtc.minusSeconds(1));
        List<Long> bgaIds = bgaRecords.stream().map(PatientBgaRecord::getId).toList();
        Map<Long, List<PatientBgaRecordDetail>> bgaDetailsMap = pbgardRepo.findByRecordIdInAndIsDeletedFalse(bgaIds)
            .stream().collect(Collectors.groupingBy(PatientBgaRecordDetail::getRecordId));

        for (PatientBgaRecord bgaRecord : bgaRecords) {
            if (bgaRecord.getEffectiveTime() == null) continue;
            LocalDateTime effectiveTime = bgaRecord.getEffectiveTime();
            List<PatientBgaRecordDetail> details = bgaDetailsMap.get(bgaRecord.getId());
            if (details == null || details.isEmpty()) continue;
            PatientData patientData = patientDataMap.computeIfAbsent(
                effectiveTime, k -> new PatientData(effectiveTime)
            );
            for (PatientBgaRecordDetail detail : details) {
                if (StrUtils.isBlank(detail.getMonitoringParamCode()) || StrUtils.isBlank(detail.getParamValue())) continue;
                GenericValuePB value = ProtoUtils.decodeGenericValue(detail.getParamValue());
                if (value == null) continue;
                patientData.bgaKvMap.put(detail.getMonitoringParamCode(), value);
            }
        }
    }

    private void getTubeData(
        Long pid, LocalDateTime startUtc, LocalDateTime endUtc,
        Map<LocalDateTime, PatientData> patientDataMap
    ) {
        // 查找插管记录
        List<PatientTubeRecord> insertedTubeRecs = ptrRepo.findByPidAndIsDeleted(pid, false)
            .stream().filter(rec -> 
                rec.getInsertedAt() != null &&
                !rec.getInsertedAt().isBefore(startUtc) &&
                rec.getInsertedAt().isBefore(endUtc)
            ).toList();
        for (PatientTubeRecord rec : insertedTubeRecs) {
            LocalDateTime insertedAt = rec.getInsertedAt();
            String tubeName = rec.getTubeName();
            if (insertedAt == null || StrUtils.isBlank(tubeName)) continue;

            PatientData patientData = patientDataMap.computeIfAbsent(
                insertedAt, k -> new PatientData(insertedAt)
            );
            patientData.tubeDataMap.computeIfAbsent(tubeName, k -> new HashMap<>());
        }

        List<PatientTubeStatusBrief> briefs = ptsrRepo.findPatientTubeStatusBrief(pid, startUtc, endUtc);
        for (PatientTubeStatusBrief brief : briefs) {
            LocalDateTime recordedAt = brief.getRecordedAt();
            String tubeName = brief.getTubeName();
            String statusName = brief.getStatusName();
            String statusValue = brief.getValue();
            if (recordedAt == null || StrUtils.isBlank(tubeName) || StrUtils.isBlank(statusName) || StrUtils.isBlank(statusValue)) {
                continue;
            }

            PatientData patientData = patientDataMap.computeIfAbsent(
                recordedAt, k -> new PatientData(recordedAt)
            );
            patientData.tubeDataMap.computeIfAbsent(tubeName, k -> new HashMap<>())
                .put(statusName, statusValue);
        }
    }

    private Ah2PageData convertToAh2PageData(
        Ah2PdfContext ctx, Map<LocalDateTime, PatientData> patientDataMap, LocalDateTime nowUtc
    ) {
        // 提取汇总单元格尺寸信息
        float summaryWidth = ctx.tblCommon.getWidth();
        ParamColMetaPB dtMeta = ctx.colMetaMap.get(AH2P_MMDD);
        if (dtMeta != null) summaryWidth -= dtMeta.getWidth();
        ParamColMetaPB timeMeta = ctx.colMetaMap.get(AH2P_HHMM);
        if (timeMeta != null) summaryWidth -= timeMeta.getWidth();
        ParamColMetaPB signatureMeta = ctx.colMetaMap.get(AH2P_SIGNATURE);
        if (signatureMeta != null) summaryWidth -= signatureMeta.getWidth();

        // 转化成 Ah2PageData
        List<Ah2PageData.RowBlock> rowBlocks = new ArrayList<>();
        for (Map.Entry<LocalDateTime, PatientData> entry : patientDataMap.entrySet()) {
            LocalDateTime timestamp = entry.getKey();
            PatientData patientData = entry.getValue();

            // 汇总行
            if (patientData.summary != null && !patientData.summary.isEmpty() && !timestamp.isAfter(nowUtc)) {
                Ah2PageData.RowBlock rowBlock = createSummaryRowBlock(ctx, timestamp, patientData, summaryWidth);
                rowBlocks.add(rowBlock);
            }

            // 普通行
            Ah2PageData.RowBlock rowBlock = createRowBlock(ctx, timestamp, patientData);
            if (rowBlock != null) rowBlocks.add(rowBlock);
        }
        rowBlocks.sort((rb1, rb2) -> {
            // 先按照timestamp排序（null排在末尾）
            int timestampComparison = Comparator.<LocalDateTime>nullsLast(Comparator.naturalOrder())
                .compare(rb1.timestamp, rb2.timestamp);
            if (timestampComparison != 0) return timestampComparison;
            // 再按照isSummaryRow排序，false在前，true在后
            return Boolean.compare(rb1.isSummaryRow, rb2.isSummaryRow);
        });
        Ah2PageData ah2PageData = new Ah2PageData();
        ah2PageData.rowBlocks = rowBlocks;
        return ah2PageData;
    }

    private Ah2PageData.RowBlock createSummaryRowBlock(
        Ah2PdfContext ctx, LocalDateTime timestamp, PatientData patientData, float summaryWidth
    ) {
        Ah2PageData.RowBlock rowBlock = new Ah2PageData.RowBlock();
        rowBlock.timestamp = timestamp;
        rowBlock.isSummaryRow = true;
        StringBuilder sb = new StringBuilder();
        sb.append(patientData.summaryTitle).append(": ");
        for (BalanceGroupSummaryPB bgs : patientData.summary) {
            sb.append(bgs.getDeptMonitoringGroupName()).append(" ");
            sb.append(bgs.getSumStr()).append("ml");

            List<String> paramSummaries = new ArrayList<>();
            for (BalanceParamRecordPB bpr : bgs.getParamRecordList()) {
                paramSummaries.add(bpr.getParamName() + ": " + bpr.getSumStr() + "ml");
            }
            if (!paramSummaries.isEmpty()) {
                sb.append(" (").append(String.join("; ", paramSummaries)).append(")");
            }

            sb.append("; ");
        }
        rowBlock.summary = new ArrayList<>();
        try {
            rowBlock.summary = JfkPdfUtils.getWrappedLines(
                ctx.font, ctx.tblTxtStyle.getFontSize(), summaryWidth,
                ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(sb.toString()))
            );
        } catch (Exception e) {
            log.error("Error wrapping text for summary: {}", e.getMessage());
        }

        // 汇总行的当班护士签名（仅最后一行放签名id）
        String nurseAccountIdStr = null;
        for (Pair<Pair<LocalDateTime/*start*/, LocalDateTime/*end*/>, Long/*accountId*/> shift :
            ctx.shiftNurses
        ) {
            LocalDateTime start = shift.getFirst().getFirst();
            LocalDateTime end = shift.getFirst().getSecond();
            Long accountId = shift.getSecond();
            if (timestamp.isAfter(start) && !timestamp.isAfter(end)) {  // summary的签名，算尾不算头
                nurseAccountIdStr = accountId == null ? null : accountId.toString();
                break;
            }
        }
        ArrayList<String> nurseLines = new ArrayList<>();
        for (int i = 0; i < rowBlock.summary.size(); ++i) {
            nurseLines.add("");
        }
        if (!StrUtils.isBlank(nurseAccountIdStr) && !nurseLines.isEmpty()) {
            nurseLines.set(nurseLines.size() - 1, nurseAccountIdStr);
        }
        rowBlock.wrappedLinesByParam.put(AH2P_SIGNATURE, nurseLines);
        return rowBlock;
    }

    private Ah2PageData.RowBlock createRowBlock(
        Ah2PdfContext ctx, LocalDateTime timestamp, PatientData patientData
    ) {
        Map<String, ValueMetaPB> paramMap = ctx.mpVmMap;
        Ah2PageData.RowBlock rowBlock = new Ah2PageData.RowBlock();
        rowBlock.timestamp = timestamp;
        int paramCountCnt = 0;
        // AH2P_CONSCIOUSNESS / MP_CONSCIOUSNESS
        String consciousnessStr = genConsciousnessStr(patientData.mpKvMap);
        if (!StrUtils.isBlank(consciousnessStr)) {
            rowBlock.wrappedLinesByParam.put(AH2P_CONSCIOUSNESS, new ArrayList<>(List.of(consciousnessStr)));
            paramCountCnt++;
        }
        paramCountCnt += setMonitoringItem(MP_LEFT_PUPIL_SIZE, AH2P_LEFT_PUPIL_SIZE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_RIGHT_PUPIL_SIZE, AH2P_RIGHT_PUPIL_SIZE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_LEFT_PUPIL_REFLEX, AH2P_LEFT_PUPIL_REFLEX,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_RIGHT_PUPIL_REFLEX, AH2P_RIGHT_PUPIL_REFLEX,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_TEMPERATURE, AH2P_TEMPERATURE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_SKIN_TEMPERATURE, AH2P_SKIN_TEMPERATURE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_HEART_RATE, AH2P_HEART_RATE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_HEART_RHYTHM, AH2P_HEART_RHYTHM,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_RESPIRATORY_RATE, AH2P_RESPIRATORY_RATE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBloodPressure(false/*isInvasive*/,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBloodPressure(true/*isInvasive*/,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_SPO2, AH2P_SPO2,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_CVP, AH2P_CVP,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_ICP, AH2P_ICP,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_BLOOD_GLUCOSE, AH2P_BLOOD_GLUCOSE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_HOURLY_INTAKE, AH2P_HOURLY_INTAKE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_ACCU_INTAKE, AH2P_TOTAL_INTAKE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_ACCU_OUTPUT, AH2P_ACCUMULATED_OUTPUT,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_URINE_OUTPUT, AH2P_URINE_OUTPUT,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_ACCU_URINE_OUTPUT, AH2P_ACCUMULATED_URINE_OUTPUT,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMedExeItems(ctx, patientData, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_GASTRIC_FLUID_VOLUME, AH2P_GASTRIC_FLUID_VOLUME,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_STOOL_VOLUME, AH2P_STOOL_VOLUME,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_CRRT_UF, AH2P_CRRT_UF,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        // AH2P_OTHER_OUTPUT_NAME & AH2P_OTHER_OUTPUT_VOLUME & AH2P_DRAINAGE_FLUID_COLOR
        paramCountCnt += setDrainageTubeItems(
            ctx, patientData.drainageTubeMap, rowBlock.wrappedLinesByParam);
        // AH2P_NURSING_RECORD
        paramCountCnt += setNursingRecords(
            ctx, patientData.nursingRecords, rowBlock.wrappedLinesByParam);
        // AH2P_SIGNATURE
        setNursingSignature(ctx, patientData, rowBlock.wrappedLinesByParam);
        paramCountCnt += setNursingScore(PS_BRADEN, AH2P_BRADEN,
            ctx, patientData.nursingScores, rowBlock.wrappedLinesByParam);
        paramCountCnt += setNursingScore(PS_MORSE, AH2P_MORSE,
            ctx, patientData.nursingScores, rowBlock.wrappedLinesByParam);
        paramCountCnt += setNursingScore(PS_AODLS, AH2P_AODLS,
            ctx, patientData.nursingScores, rowBlock.wrappedLinesByParam);
        paramCountCnt += setNursingScore(PS_VTE_CAPRINI, AH2P_VTE_CAPRINI,
            ctx, patientData.nursingScores, rowBlock.wrappedLinesByParam);
        paramCountCnt += setNursingScore(PS_CATHETER_SLIPPAGE, AH2P_CATHETER_SLIPPAGE,
            ctx, patientData.nursingScores, rowBlock.wrappedLinesByParam);
        paramCountCnt += setNursingScore(PS_RASS, AH2P_RASS,
            ctx, patientData.nursingScores, rowBlock.wrappedLinesByParam);
        paramCountCnt += setNursingScore(PS_FRS_V2, AH2P_CPOT_NRS,
            ctx, patientData.nursingScores, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_OXYGEN_DELIVERY_METHOD, AH2P_OXYGEN_DELIVERY_METHOD,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_RESPIRATORY_TUBE_DEPTH, AH2P_RESPIRATORY_TUBE_DEPTH,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);

        paramCountCnt += setMonitoringItem(MP_RESPIRATORY_MODE, AH2P_RESPIRATORY_MODE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_RESPIRATORY_RATE_VENT, AH2P_RESPIRATORY_RATE_VENT,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_SET_TIDAL_VOLUME, AH2P_SET_TIDAL_VOLUME,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_FIO2, AH2P_FIO2,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_PS, AH2P_PS,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_PEEP, AH2P_PEEP,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_LEFT_BREATH_SOUNDS, AH2P_LEFT_BREATH_SOUNDS,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_RIGHT_BREATH_SOUNDS, AH2P_RIGHT_BREATH_SOUNDS,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_AIRWAY_HUMIDIFICATION, AH2P_AIRWAY_HUMIDIFICATION,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_ATOMIZATION, AH2P_ATOMIZATION,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_SPUTUM_AMOUNT, AH2P_SPUTUM_AMOUNT,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_SPUTUM_COLOR, AH2P_SPUTUM_COLOR,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_SUCTION_COUNT, AH2P_SUCTION_COUNT,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_BED_BATHING, AH2P_BED_BATHING,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_PERINEAL_CARE, AH2P_PERINEAL_CARE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_ORAL_CARE, AH2P_ORAL_CARE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_TRACHEOSTOMY_CARE, AH2P_TRACHEOSTOMY_CARE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_SKIN_CARE, AH2P_SKIN_CARE,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_WASH_HAIR, AH2P_WASH_HAIR,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_PULMONARY_PHYSIOTHERAPY, AH2P_PULMONARY_PHYSIOTHERAPY,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_LIMB_PNEUMATIC_THERAPY, AH2P_LIMB_PNEUMATIC_THERAPY,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_RESTRAINT, AH2P_RESTRAINT,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setMonitoringItem(MP_BODY_POSITION, AH2P_BODY_POSITION,
            ctx, paramMap, patientData.mpKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_PH, AH2P_BGA_PH,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_PCO2, AH2P_BGA_PCO2,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_PO2, AH2P_BGA_PO2,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_PO2_FIO2, AH2P_BGA_PO2_FIO2,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_BE, AH2P_BGA_BE,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_HCO3, AH2P_BGA_HCO3,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_SPO2, AH2P_BGA_SPO2,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_K, AH2P_BGA_K,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_NA, AH2P_BGA_NA,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_CL, AH2P_BGA_CL,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setBgaItems(BGA_LAC, AH2P_BGA_LAC,
            ctx, paramMap, patientData.bgaKvMap, rowBlock.wrappedLinesByParam);
        paramCountCnt += setTubeData(
            ctx, patientData.tubeDataMap, rowBlock.wrappedLinesByParam);

        ensureSignatureForRowBlock(ctx, rowBlock);

        if (paramCountCnt == 0) {
            return null;  // 空行不返回
        }
        return rowBlock;
    }

    private String genConsciousnessStr(
        Map<String, GenericValuePB> mpKvMap
    ) {
        GenericValuePB gvPb = mpKvMap.get(MP_CONSCIOUSNESS);
        if (gvPb == null) return null;
        String strVal = gvPb.getStrVal();
        if (strVal.equals("清醒")) return "1";
        if (strVal.equals("嗜睡")) return "2";
        if (strVal.equals("意识模糊")) return "3";
        if (strVal.equals("昏睡")) return "4";
        if (strVal.equals("浅昏迷")) return "5";
        if (strVal.equals("中度昏迷")) return "6";
        if (strVal.equals("深昏迷")) return "7";
        if (strVal.equals("药物镇静")) return "8";
        if (strVal.equals("植物状态")) return "9";
        if (strVal.equals("全麻未醒")) return "10";
        return null;
    }

    private int setMonitoringItem(
        String mpCode, String ah2pCode,
        Ah2PdfContext ctx,
        Map<String, ValueMetaPB> paramMap,
        Map<String, GenericValuePB> mpKvMap,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        if (StrUtils.isBlank(mpCode) || StrUtils.isBlank(ah2pCode) || ctx == null ||
            ctx.colMetaMap == null || paramMap == null || mpKvMap == null || wrappedLinesByParam == null
        ) {
            return 0;
        }
        ParamColMetaPB colMeta = ctx.colMetaMap.get(ah2pCode);
        ValueMetaPB valueMeta = null;
        if (mpCode.equals(MP_ACCU_INTAKE)) valueMeta = paramMap.get(MP_HOURLY_INTAKE);
        else if (mpCode.equals(MP_ACCU_OUTPUT)) valueMeta = paramMap.get(MP_HOURLY_OUTPUT);
        else if (mpCode.equals(MP_ACCU_URINE_OUTPUT)) valueMeta = paramMap.get(MP_URINE_OUTPUT);
        else valueMeta = paramMap.get(mpCode);
        GenericValuePB gvPb = mpKvMap.get(mpCode);
        if (colMeta == null || valueMeta == null || gvPb == null) {
            return 0;
        }
        String strVal = ValueMetaUtils.extractAndFormatParamValue(gvPb, valueMeta);
        if (StrUtils.isBlank(strVal)) {
            return 0;
        }
        try {
            List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
                ctx.font, ctx.tblTxtStyle.getFontSize(), colMeta.getWidth(),
                ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(strVal))
            );
            if (wrappedLines != null && !wrappedLines.isEmpty()) {
                wrappedLinesByParam.put(ah2pCode, wrappedLines);
                return 1;
            }
        } catch (Exception e) {
            log.error("Error wrapping text for param {}: {}", mpCode, e.getMessage());
        }
        return 0;
    }

    private int setBloodPressure(
        boolean isInvasive,
        Ah2PdfContext ctx,
        Map<String, ValueMetaPB> paramMap,
        Map<String, GenericValuePB> mpKvMap,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        if (ctx == null || ctx.colMetaMap == null ||
            paramMap == null || mpKvMap == null || wrappedLinesByParam == null
        ) {
            return 0;
        }

        // 提取元数据
        String mpBpsCode = isInvasive ? MP_IBP_S : MP_NIBP_S;
        ValueMetaPB mpBpsValueMeta = paramMap.get(mpBpsCode);
        GenericValuePB bpsGvPb = mpKvMap.get(mpBpsCode);
        String mpBpdCode = isInvasive ? MP_IBP_D : MP_NIBP_D;
        ValueMetaPB mpBpdValueMeta = paramMap.get(mpBpdCode);
        GenericValuePB bpdGvPb = mpKvMap.get(mpBpdCode);
        String ah2pCode = isInvasive ? AH2P_IBP : AH2P_NIBP;
        ParamColMetaPB colMeta = ctx.colMetaMap.get(ah2pCode);
        if (mpBpdValueMeta == null || mpBpsValueMeta == null || colMeta == null) {
            return 0;
        }

        String bpsStrVal = bpsGvPb == null ? "" : ValueMetaUtils.extractAndFormatParamValue(bpsGvPb, mpBpsValueMeta);
        String bpdStrVal = bpdGvPb == null ? "" : ValueMetaUtils.extractAndFormatParamValue(bpdGvPb, mpBpdValueMeta);
        if (StrUtils.isBlank(bpsStrVal) && StrUtils.isBlank(bpdStrVal)) {
            return 0;
        }
        String strVal = (StrUtils.isBlank(bpsStrVal) ? "" : bpsStrVal) + "/" +
            (StrUtils.isBlank(bpdStrVal) ? "" : bpdStrVal); 
        try {
            List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
                ctx.font, ctx.tblTxtStyle.getFontSize(), colMeta.getWidth(),
                ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(strVal))
            );
            if (wrappedLines != null && !wrappedLines.isEmpty()) {
                wrappedLinesByParam.put(ah2pCode, wrappedLines);
                return 1;
            }
        } catch (Exception e) {
            log.error("Error wrapping text for param {}: {}", ah2pCode, e.getMessage());
        }
        return 0;
    }

    private int setMedExeItems(Ah2PdfContext ctx, PatientData patientData,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        if (ctx == null || patientData == null ||
            ((patientData.medExeList == null || patientData.medExeList.isEmpty()) &&
             (patientData.dietExeList == null || patientData.dietExeList.isEmpty())
            ) ||
            wrappedLinesByParam == null
        ) {
            return 0;
        }

        // 提取元数据
        ParamColMetaPB medColMeta = ctx.colMetaMap.get(AH2P_MED_EXEC);
        ParamColMetaPB dietColMeta = ctx.colMetaMap.get(AH2P_NASOGASTRIC);
        if (medColMeta == null || dietColMeta == null) {
            log.error("Missing colMeta for med or diet.");
            return 0;
        }

        // 执行用药
        int affectedCount = 0;
        try {
            List<String> medDescLines = new ArrayList<>();
            List<String> medVolLines = new ArrayList<>();
            for (MedExeRecordSummaryPB exeSum : patientData.medExeList) {
                String descStr = exeSum.getDosageGroupDisplayName();
                List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
                    ctx.font, ctx.tblTxtStyle.getFontSize(), medColMeta.getWidth(),
                    ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(descStr))
                );
                if (wrappedLines == null || wrappedLines.isEmpty()) continue;
                medVolLines.add(String.valueOf(
                    ValueMetaUtils.normalize(exeSum.getIntakeMl(), DOSAGE_DECIMAL_PLACES)
                ));
                for (int i = 1; i < wrappedLines.size(); i++) {
                    medVolLines.add("");  // 用药量列空行补齐
                }
                medDescLines.addAll(wrappedLines);
            }

            if (!medDescLines.isEmpty() && !medVolLines.isEmpty() &&
                medDescLines.size() == medVolLines.size()
            ) {
                wrappedLinesByParam.put(AH2P_MED_EXEC, medDescLines);
                wrappedLinesByParam.put(AH2P_MEDICATION_ML, medVolLines);
                affectedCount++;
            }
        } catch (Exception e) {
            log.error("Error wrapping text for param {}: {}", AH2P_MED_EXEC, e.getMessage());
        }

        // 饮食/鼻饲
        try {
            List<String> dietDescLines = new ArrayList<>();
            List<String> dietVolLines = new ArrayList<>();
            for (MedExeRecordSummaryPB exeSum : patientData.dietExeList) {
                String descStr = exeSum.getDosageGroupDisplayName();
                List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
                    ctx.font, ctx.tblTxtStyle.getFontSize(), dietColMeta.getWidth(),
                    ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(descStr))
                );
                if (wrappedLines == null || wrappedLines.isEmpty()) continue;
                dietVolLines.add(String.valueOf(
                    ValueMetaUtils.normalize(exeSum.getIntakeMl(), DOSAGE_DECIMAL_PLACES)
                ));
                for (int i = 1; i < wrappedLines.size(); i++) {
                    dietVolLines.add("");  // 用药量列空行补齐
                }
                dietDescLines.addAll(wrappedLines);
            }
            if (!dietDescLines.isEmpty() && !dietVolLines.isEmpty() &&
                dietDescLines.size() == dietVolLines.size()
            ) {
                wrappedLinesByParam.put(AH2P_NASOGASTRIC, dietDescLines);
                wrappedLinesByParam.put(AH2P_NASOGASTRIC_ML, dietVolLines);
                affectedCount++;
            }
        } catch (Exception e) {
            log.error("Error wrapping text for param {}: {}", AH2P_MED_EXEC, e.getMessage());
        }

        return affectedCount;
    }

    private int setDrainageTubeItems(
        Ah2PdfContext ctx,
        Map<String, DrainageTubeData> drainageTubeMap,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        if (ctx == null || drainageTubeMap == null || drainageTubeMap.isEmpty() ||
            wrappedLinesByParam == null
        ) {
            return 0;
        }

        // 提取元数据
        ParamColMetaPB nameColMeta = ctx.colMetaMap.get(AH2P_OTHER_OUTPUT_NAME);
        ParamColMetaPB volColMeta = ctx.colMetaMap.get(AH2P_OTHER_OUTPUT_VOLUME);
        ParamColMetaPB colorColMeta = ctx.colMetaMap.get(AH2P_DRAINAGE_FLUID_COLOR);
        if (nameColMeta == null || volColMeta == null || colorColMeta == null) {
            log.error("Missing colMeta for drainage tube.");
            return 0;
        }
        List<DrainageTubeData> tubeDataList = new ArrayList<>(drainageTubeMap.values());
        tubeDataList.sort(Comparator.comparing(t -> t.code));

        // 分行
        int affectedCount = 0;
        try {
            List<String> tubeNameLines = new ArrayList<>();
            List<String> tubeVolumeLines = new ArrayList<>();
            List<String> tubeColorLines = new ArrayList<>();
            for (DrainageTubeData tubeData : tubeDataList) {
                String tubeName = tubeData.tubeName;
                if (StrUtils.isBlank(tubeName)) continue;
                List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
                    ctx.font, ctx.tblTxtStyle.getFontSize(), nameColMeta.getWidth(),
                    ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(tubeName))
                );
                List<String> wrappedVolLines = new ArrayList<>();
                if (!StrUtils.isBlank(tubeData.volume)) wrappedVolLines.add(tubeData.volume);
                for (int i = wrappedVolLines.size(); i < wrappedLines.size(); i++) {
                    wrappedVolLines.add("");  // 体积列空行补齐
                }
                List<String> wrappedColorLines = new ArrayList<>();
                if (!StrUtils.isBlank(tubeData.color)) wrappedColorLines.add(tubeData.color);
                for (int i = wrappedColorLines.size(); i < wrappedLines.size(); i++) {
                    wrappedColorLines.add("");  // 颜色列空行补齐
                }
                tubeNameLines.addAll(wrappedLines);
                tubeVolumeLines.addAll(wrappedVolLines);
                tubeColorLines.addAll(wrappedColorLines);
            }
            if (!tubeNameLines.isEmpty() && !tubeVolumeLines.isEmpty() && !tubeColorLines.isEmpty() &&
                tubeNameLines.size() == tubeVolumeLines.size() &&
                tubeNameLines.size() == tubeColorLines.size()
            ) {
                wrappedLinesByParam.put(AH2P_OTHER_OUTPUT_NAME, tubeNameLines);
                wrappedLinesByParam.put(AH2P_OTHER_OUTPUT_VOLUME, tubeVolumeLines);
                wrappedLinesByParam.put(AH2P_DRAINAGE_FLUID_COLOR, tubeColorLines);
                affectedCount++;
            }
        } catch (Exception e) {
            log.error("Error wrapping text for param {}: {}", AH2P_MED_EXEC, e.getMessage());
            return 0;
        }
        return affectedCount;
    }

    private int setNursingRecords(
        Ah2PdfContext ctx,
        List<Pair<String/*content*/, Long/*nurseAccountId*/>> nursingRecords,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        if (ctx == null || nursingRecords == null || nursingRecords.isEmpty() ||
            wrappedLinesByParam == null
        ) {
            return 0;
        }

        // 提取元数据
        ParamColMetaPB colMeta = ctx.colMetaMap.get(AH2P_NURSING_RECORD);
        if (colMeta == null) {
            log.error("Missing colMeta for nursing record.");
            return 0;
        }

        // 分行
        int affectedCount = 0;
        try {
            List<String> recordLines = new ArrayList<>();
            List<String> signatureLines = new ArrayList<>();
            for (Pair<String, Long> record : nursingRecords) {
                String content = record.getFirst();
                Long nurseAccountId = record.getSecond();
                if (StrUtils.isBlank(content)) continue;
                List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
                    ctx.font, ctx.tblTxtStyle.getFontSize(), colMeta.getWidth(),
                    ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(content))
                );
                if (wrappedLines == null || wrappedLines.isEmpty()) continue;
                recordLines.addAll(wrappedLines);
                List<String> wrappedNurseLines = new ArrayList<>();
                for (int i = 1; i < wrappedLines.size(); i++) {
                    wrappedNurseLines.add("");  // 签名列空行补齐
                }
                wrappedNurseLines.add(nurseAccountId == null ? "" : nurseAccountId.toString());
                signatureLines.addAll(wrappedNurseLines);
            }
            if (!recordLines.isEmpty() && !signatureLines.isEmpty() &&
                recordLines.size() == signatureLines.size()
            ) {
                wrappedLinesByParam.put(AH2P_NURSING_RECORD, recordLines);
                wrappedLinesByParam.put(AH2P_SIGNATURE, signatureLines);
                affectedCount++;
            }
        } catch (Exception e) {
            log.error("Error wrapping text for param {}: {}", AH2P_NURSING_RECORD, e.getMessage());
            return 0;
        }
        return affectedCount;
    }

    private void setNursingSignature(
        Ah2PdfContext ctx,
        PatientData patientData,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        if (ctx == null || patientData == null ||
            (patientData.nursingRecords != null && !patientData.nursingRecords.isEmpty()) ||
            wrappedLinesByParam == null
        ) {
            return;
        }

        // 非整点&整点时间，都需要签名
        LocalDateTime ts = patientData.effectiveTime;
        if (ts == null) return;

        // 获取当班护士
        String nurseIdStr = null;
        for (Pair<Pair<LocalDateTime/*start*/, LocalDateTime/*end*/>, Long/*accountId*/> shift :
            ctx.shiftNurses
        ) {
            LocalDateTime start = shift.getFirst().getFirst();
            LocalDateTime end = shift.getFirst().getSecond();
            Long accountId = shift.getSecond();
            if (!ts.isBefore(start) && ts.isBefore(end)) {
                nurseIdStr = accountId == null ? null : accountId.toString();
                break;
            }
        }

        if (StrUtils.isBlank(nurseIdStr)) return;
        wrappedLinesByParam.put(AH2P_SIGNATURE, new ArrayList<>(List.of(nurseIdStr)));

        return;
    }

    private void ensureSignatureForRowBlock(Ah2PdfContext ctx, Ah2PageData.RowBlock rowBlock) {
        if (ctx == null || rowBlock == null || rowBlock.isSummaryRow) return;
        if (rowBlock.wrappedLinesByParam == null || rowBlock.wrappedLinesByParam.isEmpty()) return;
        List<String> sigLines = rowBlock.wrappedLinesByParam.get(AH2P_SIGNATURE);
        boolean hasSignature = sigLines != null && sigLines.stream().anyMatch(line -> !StrUtils.isBlank(line));
        if (hasSignature) return;

        String nurseIdStr = getShiftNurseIdStr(ctx, rowBlock.timestamp);
        if (StrUtils.isBlank(nurseIdStr)) return;

        int maxLines = 0;
        for (List<String> lines : rowBlock.wrappedLinesByParam.values()) {
            if (lines != null) maxLines = Math.max(maxLines, lines.size());
        }
        if (maxLines <= 0) return;

        List<String> newSigLines = new ArrayList<>();
        for (int i = 0; i < maxLines - 1; i++) newSigLines.add("");
        newSigLines.add(nurseIdStr);
        rowBlock.wrappedLinesByParam.put(AH2P_SIGNATURE, newSigLines);
    }

    private String getShiftNurseIdStr(Ah2PdfContext ctx, LocalDateTime ts) {
        if (ctx == null || ctx.shiftNurses == null || ts == null) return null;
        for (Pair<Pair<LocalDateTime/*start*/, LocalDateTime/*end*/>, Long/*accountId*/> shift : ctx.shiftNurses) {
            LocalDateTime start = shift.getFirst().getFirst();
            LocalDateTime end = shift.getFirst().getSecond();
            Long accountId = shift.getSecond();
            if (start == null || end == null) continue;
            if (!ts.isBefore(start) && ts.isBefore(end)) {
                return accountId == null ? null : accountId.toString();
            }
        }
        return null;
    }

    private int setNursingScore(
        String mpCode, String ah2pCode,
        Ah2PdfContext ctx,
        Map<String/*scoreGroupCode*/, String/*scoreStr*/> nursingScores,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        String scoreStr = nursingScores.get(mpCode);
        if (StrUtils.isBlank(scoreStr)) return 0;
        wrappedLinesByParam.put(ah2pCode, new ArrayList<>(List.of(scoreStr)));
        return 1;
    }

    private int setBgaItems(
        String bgaCode, String ah2pCode,
        Ah2PdfContext ctx,
        Map<String, ValueMetaPB> paramMap,
        Map<String, GenericValuePB> bgaKvMap,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        if (StrUtils.isBlank(bgaCode) || StrUtils.isBlank(ah2pCode) || ctx == null ||
            ctx.colMetaMap == null || paramMap == null || bgaKvMap == null || wrappedLinesByParam == null
        ) {
            return 0;
        }
        ParamColMetaPB colMeta = ctx.colMetaMap.get(ah2pCode);
        ValueMetaPB valueMeta = paramMap.get(bgaCode);
        GenericValuePB gvPb = bgaKvMap.get(bgaCode);
        if (colMeta == null || valueMeta == null || gvPb == null) {
            return 0;
        }
        String strVal = ValueMetaUtils.extractAndFormatParamValue(gvPb, valueMeta);
        if (StrUtils.isBlank(strVal)) {
            return 0;
        }
        try {
            List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
                ctx.font, ctx.tblTxtStyle.getFontSize(), colMeta.getWidth(),
                ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(strVal))
            );
            if (wrappedLines != null && !wrappedLines.isEmpty()) {
                wrappedLinesByParam.put(ah2pCode, wrappedLines);
                return 1;
            }
        } catch (Exception e) {
            log.error("Error wrapping text for param {}: {}", bgaCode, e.getMessage());
        }
        return 0;
    }

    private int setTubeData(
        Ah2PdfContext ctx,
        Map<String, Map<String, String>> tubeDataMap,
        Map<String, List<String>> wrappedLinesByParam /*output param*/
    ) {
        if (tubeDataMap == null || tubeDataMap.isEmpty() || wrappedLinesByParam == null) {
            return 0;
        }

        ParamColMetaPB tubeNameColMeta = ctx.colMetaMap.get(AH2P_TUBE_NAME);
        ParamColMetaPB tubeDepthColMeta = ctx.colMetaMap.get(AH2P_TUBE_DEPTH);
        ParamColMetaPB dressingChangeColMeta = ctx.colMetaMap.get(AH2P_TUBE_DRESSING_CHANGE);
        ParamColMetaPB normalColMeta = ctx.colMetaMap.get(AH2P_TUBE_NORMAL);
        ParamColMetaPB abnormalColMeta = ctx.colMetaMap.get(AH2P_TUBE_ABNORMAL);
        if (tubeNameColMeta == null || tubeDepthColMeta == null || dressingChangeColMeta == null ||
            normalColMeta == null || abnormalColMeta == null
        ) {
            return 0;
        }

        int affectedCount = 0;
        for (Map.Entry<String, Map<String, String>> entry : tubeDataMap.entrySet()) {
            String tubeName = entry.getKey();
            Map<String, String> statusMap = entry.getValue();

            // 管道名称
            try {
                List<String> wrappedLines = JfkPdfUtils.getWrappedLines(
                    ctx.font, ctx.tblTxtStyle.getFontSize(), tubeNameColMeta.getWidth(),
                    ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(tubeName))
                );
                if (wrappedLines != null && !wrappedLines.isEmpty()) {
                    wrappedLinesByParam.put(AH2P_TUBE_NAME, wrappedLines);
                    affectedCount += 1;
                } else continue;
            } catch (Exception e) {
                log.error("Error wrapping text for tube {}: {}", tubeName, e.getMessage());
                continue;
            }

            // 置管深度
            String tubeDepthValue = statusMap.get(TUBE_DEPTH_STATUS_NAME);
            if (!StrUtils.isBlank(tubeDepthValue)) {
                wrappedLinesByParam.put(AH2P_TUBE_DEPTH, new ArrayList<>(List.of(tubeDepthValue)));
            }

            // 置管状态
            String tubeStateValue = statusMap.get(TUBE_STATE_STATUS_NAME);
            if (!StrUtils.isBlank(tubeStateValue)) {
                Set<String> states = Arrays.stream(tubeStateValue.split(","))
                    .collect(Collectors.toSet());
                if (states.contains(TUBE_STATE_NORMAL)) {
                    wrappedLinesByParam.put(AH2P_TUBE_NORMAL, new ArrayList<>(List.of("√")));
                }
                if (states.contains(TUBE_STATE_ABNORMAL)) {
                    wrappedLinesByParam.put(AH2P_TUBE_ABNORMAL, new ArrayList<>(List.of("√")));
                }
                if (states.contains(TUBE_STATE_DRESSING_CHANGE)) {
                    wrappedLinesByParam.put(AH2P_TUBE_DRESSING_CHANGE, new ArrayList<>(List.of("√")));
                }
            }
        }
        return affectedCount;
    }

    private List<Ah2PageData> paginateData(
        List<Ah2PageData> dailyDataList, LocalDateTime dailyDataStartMidnightUtc,
        LocalDateTime admissionTimeUtc, int linesPerPage
    ) {
        if (dailyDataStartMidnightUtc == null || admissionTimeUtc == null || dailyDataList == null || dailyDataList.isEmpty() || linesPerPage <= 0) {
            return new ArrayList<>();
        }

        List<Ah2PageData> pageDataList = new ArrayList<>();
        Ah2PageData curPage = new Ah2PageData();
        curPage.pageNumber = 1;
        curPage.pageStartTs = dailyDataStartMidnightUtc;
        int curPageLines = 0;
        LocalDateTime curPageDate = dailyDataStartMidnightUtc;

        // 分页
        for (Ah2PageData dailyData : dailyDataList) {
            for (Ah2PageData.RowBlock rowBlock : dailyData.rowBlocks) {
                // 过滤 admissionTimeUtc 之前的数据，以及空行
                if (rowBlock.timestamp == null) {
                    log.error("rowBlock.timestamp is null");
                    continue;
                }
                if (rowBlock.timestamp != null && rowBlock.timestamp.isBefore(admissionTimeUtc)) {
                    continue;
                }
                if (rowBlock.isSummaryRow &&
                    (rowBlock.summary == null || rowBlock.summary.isEmpty())
                ) {
                    continue;
                } else if (!rowBlock.isSummaryRow &&
                    (rowBlock.wrappedLinesByParam == null || rowBlock.wrappedLinesByParam.isEmpty())
                ) {
                    continue;
                }

                // 统计 RowBlock 行数
                int nLines = 0;
                List<String> summaryLines = rowBlock.summary;
                List<String> summarySignLines = rowBlock.wrappedLinesByParam == null ? null :
                    rowBlock.wrappedLinesByParam.get(AH2P_SIGNATURE);
                List<Pair<String, List<String>>> paramLines = new ArrayList<>();
                if (rowBlock.isSummaryRow) {
                    if (summaryLines == null) {
                        log.error("summaryLines is null");
                        continue;
                    }
                    nLines = rowBlock.summary.size();
                } else {
                    for (Map.Entry<String, List<String>> entry : rowBlock.wrappedLinesByParam.entrySet()) {
                        String paramCode = entry.getKey();
                        List<String> wrappedLines = entry.getValue();
                        if (paramCode == null || wrappedLines == null) continue;
                        paramLines.add(new Pair<>(paramCode, wrappedLines));
                        nLines = Math.max(nLines, wrappedLines.size());
                    }
                    if (nLines == 0) nLines = 1;
                }

                // 将 RowBlock 拆分成多页
                if (curPageDate == null || (rowBlock.timestamp != null && curPageDate.isBefore(rowBlock.timestamp))) {
                    curPageDate = rowBlock.timestamp;
                }
                LocalDateTime ts = rowBlock.timestamp;
                boolean leadingDataBlock = true;
                while (nLines > 0) {
                    int linesToAdd = Math.min(nLines, linesPerPage - curPageLines);

                    // 在当前页中添加RowBlock片段
                    Ah2PageData.RowBlock newRowBlock = new Ah2PageData.RowBlock();
                    newRowBlock.timestamp = ts;
                    newRowBlock.isSummaryRow = rowBlock.isSummaryRow;
                    newRowBlock.startRow = curPageLines;
                    newRowBlock.totalRows = linesToAdd;
                    newRowBlock.leadingDataBlock = leadingDataBlock;
                    if (rowBlock.isSummaryRow) {
                        newRowBlock.summary = new ArrayList<>(summaryLines.subList(
                            summaryLines.size() - nLines, // [fromIndex, 
                            summaryLines.size() - nLines + linesToAdd  // toIndex)
                        ));
                        if (summarySignLines != null && !summarySignLines.isEmpty()) {
                            newRowBlock.wrappedLinesByParam.put(AH2P_SIGNATURE,
                                new ArrayList<>(summarySignLines.subList(
                                    summarySignLines.size() - nLines, // [fromIndex, 
                                    summarySignLines.size() - nLines + linesToAdd  // toIndex)
                                ))
                            );
                        }
                    } else {
                        Map<String, List<String>> newWrappedLinesByParam = new HashMap<>();
                        List<Pair<String, List<String>>> remainingParamLines = new ArrayList<>();
                        for (Pair<String, List<String>> paramLine : paramLines) {
                            String paramCode = paramLine.getFirst();
                            List<String> wrappedLines = paramLine.getSecond();

                            if (wrappedLines.size() <= linesToAdd) {
                                newWrappedLinesByParam.put(paramCode, new ArrayList<>(wrappedLines));
                            } else {
                                newWrappedLinesByParam.put(
                                    paramCode,
                                    new ArrayList<>(wrappedLines.subList(
                                        0/* [fromIndex, */, linesToAdd/* toIndex) */
                                    ))
                                );
                                remainingParamLines.add(new Pair<>(
                                    paramCode,
                                    new ArrayList<>(wrappedLines.subList(
                                        linesToAdd/* [fromIndex, */, wrappedLines.size()/* toIndex) */
                                    ))
                                ));
                            }
                        }
                        newRowBlock.wrappedLinesByParam = newWrappedLinesByParam;
                        paramLines = remainingParamLines;
                    }
                    curPage.rowBlocks.add(newRowBlock);

                    // 如果当前页满了，开启新页
                    curPageLines += linesToAdd;
                    if (curPageLines >= linesPerPage) {
                        LocalDateTime curPageDateLocal = TimeUtils.getLocalDateTimeFromUtc(curPageDate, ZONE_ID);
                        curPage.yearStr = curPageDateLocal == null ? "" : String.valueOf(curPageDateLocal.getYear());
                        curPage.pageEndTs = curPageDate;
                        pageDataList.add(curPage);

                        // 开新的一页
                        curPage = new Ah2PageData();
                        curPage.pageNumber = pageDataList.size() + 1;
                        curPageLines = 0;
                        curPage.pageStartTs = curPageDate;
                    }

                    // 继续处理当前rowBlock
                    nLines -= linesToAdd;
                    leadingDataBlock = false;  // 只有第一个子页保留时间戳
                }
            }
        }

        // 将剩余页放入
        if (curPage.rowBlocks != null && !curPage.rowBlocks.isEmpty()) {
            LocalDateTime curPageDateLocal = TimeUtils.getLocalDateTimeFromUtc(curPageDate, ZONE_ID);
            curPage.yearStr = curPageDateLocal == null ? "" : String.valueOf(curPageDateLocal.getYear());
            curPage.pageEndTs = curPageDate;
            pageDataList.add(curPage);
        }

        return pageDataList;
    }

    /*
     * 画图模块
     **/
    public void drawTableBody(Ah2PdfContext ctx) throws IOException {
        // 如果需要填写时间列，则画时间竖线
        float summaryColLeft = ctx.tblCommon.getLeft();
        float summaryColWidth = ctx.tblCommon.getWidth();
        boolean hasDateTimeCols = ctx.table.getColParamCodeList().contains(AH2P_HHMM) ||
            ctx.table.getColParamCodeList().contains(AH2P_MMDD);
        if (hasDateTimeCols) {
            ParamColMetaPB hhMMCol = ctx.colMetaMap.get(AH2P_HHMM);
            if (hhMMCol != null) {
                float x = hhMMCol.getLeft() + hhMMCol.getWidth();
                ctx.contentStream.moveTo(x, ctx.tableHeaderBottom);
                ctx.contentStream.lineTo(x, ctx.tblCommon.getBottom());
                ctx.contentStream.stroke();
                summaryColLeft += hhMMCol.getWidth();
                summaryColWidth -= hhMMCol.getWidth();
            }
            ParamColMetaPB mmDDCol = ctx.colMetaMap.get(AH2P_MMDD);
            if (mmDDCol != null) {
                float x = mmDDCol.getLeft() + mmDDCol.getWidth();
                ctx.contentStream.moveTo(x, ctx.tableHeaderBottom);
                ctx.contentStream.lineTo(x, ctx.tblCommon.getBottom());
                ctx.contentStream.stroke();
                summaryColLeft += mmDDCol.getWidth();
                summaryColWidth -= mmDDCol.getWidth();
            }
            ParamColMetaPB signCol = ctx.colMetaMap.get(AH2P_SIGNATURE);
            if (signCol != null) {
                summaryColWidth -= signCol.getWidth();
            }
        }

        // 画数据块
        for (Ah2PageData.RowBlock rowBlock : ctx.pageData.rowBlocks) {
            // - 画表体横线
            for (int i = 0; i < rowBlock.totalRows; i++) {
                int rowIdx = rowBlock.startRow + i;
                if (rowIdx == ctx.tblCommon.getBodyRows() - 1) break; // 最后一行不画
                float y = ctx.tableHeaderBottom - (rowIdx + 1) * ctx.tblCommon.getRowHeight();
                ctx.contentStream.moveTo(ctx.tblCommon.getLeft(), y);
                ctx.contentStream.lineTo(ctx.tblCommon.getLeft() + ctx.tblCommon.getWidth(), y);
                ctx.contentStream.stroke();
            }

            if (rowBlock.isSummaryRow) {  // 画汇总行文字
                if (hasDateTimeCols) {
                    // 日期时间
                    float dateBottom = ctx.tableHeaderBottom -
                        (rowBlock.startRow + 1) * ctx.tblCommon.getRowHeight();
                    LocalDateTime tsLocal = TimeUtils.getLocalDateTimeFromUtc(rowBlock.timestamp, ZONE_ID);
                    List<String> mMddStr = tsLocal == null ? Collections.singletonList("") :
                        Collections.singletonList(TimeUtils.getMonthDay(tsLocal));
                    ParamColMetaPB colMeta = ctx.colMetaMap.get(AH2P_MMDD);
                    PdfTextRenderer.drawTxt(
                        ctx.contentStream,
                        ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
                        ctx.tblTxtStyle.getCharSpacing(), ctx.tblCommon.getRowHeight(), HorizontalAlign.CENTER,
                        colMeta.getLeft(), dateBottom, colMeta.getWidth(), 1 * ctx.tblCommon.getRowHeight(),
                        mMddStr
                    );
                    List<String> hHMMStr = tsLocal == null ? Collections.singletonList("") :
                        Collections.singletonList(TimeUtils.getHourMinute(tsLocal));
                    colMeta = ctx.colMetaMap.get(AH2P_HHMM);
                    PdfTextRenderer.drawTxt(
                        ctx.contentStream,
                        ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
                        ctx.tblTxtStyle.getCharSpacing(), ctx.tblCommon.getRowHeight(), HorizontalAlign.CENTER,
                        colMeta.getLeft(), dateBottom, colMeta.getWidth(), 1 * ctx.tblCommon.getRowHeight(),
                        hHMMStr
                    );

                    // 汇总行文字
                    int summaryLines = Math.max(1, rowBlock.summary.size());
                    float summaryBottom = ctx.tableHeaderBottom -
                        (rowBlock.startRow + summaryLines) * ctx.tblCommon.getRowHeight();
                    PdfTextRenderer.drawTxt(
                        ctx.contentStream,
                        ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
                        ctx.tblTxtStyle.getCharSpacing(), ctx.tblCommon.getRowHeight(), HorizontalAlign.LEFT,
                        summaryColLeft + ctx.tblCommon.getSummaryRowHotizontalPadding(),
                        summaryBottom, summaryColWidth, summaryLines * ctx.tblCommon.getRowHeight(),
                        rowBlock.summary
                    );

                    // 画汇总列和签名列之间的竖线
                    float x = summaryColLeft + summaryColWidth;
                    float y0 = summaryBottom;
                    float y1 = y0 + summaryLines * ctx.tblCommon.getRowHeight();
                    ctx.contentStream.moveTo(x, y0);
                    ctx.contentStream.lineTo(x, y1);
                    ctx.contentStream.stroke();

                    // 画签名列
                    ParamColMetaPB signColMeta = ctx.colMetaMap.get(AH2P_SIGNATURE);
                    List<String> signatureLines = rowBlock.wrappedLinesByParam.get(AH2P_SIGNATURE);
                    if (signColMeta != null && signatureLines != null && !signatureLines.isEmpty()) {
                        drawSignatureImage(
                            ctx, signatureLines,
                            signColMeta.getLeft(), summaryBottom,
                            signColMeta.getWidth(), summaryLines * ctx.tblCommon.getRowHeight()
                        );
                    }
                }  // 每页中除了第一个子页，其余子叶不填汇总行内容
            } else {  // 画普通行
                LocalDateTime tsLocal = rowBlock.timestamp == null ?
                    null : TimeUtils.getLocalDateTimeFromUtc(rowBlock.timestamp, ZONE_ID);
                for (int colIdx = 0; colIdx < ctx.table.getColParamCodeCount(); colIdx++) {
                    String paramCode = ctx.table.getColParamCode(colIdx);
                    ParamColMetaPB colMeta = ctx.colMetaMap.get(paramCode);
                    if (colMeta == null) {
                        log.error("Ah2ReportService.drawTableBody: colMetaMap missing paramCode=" + paramCode);
                        continue;
                    }
                    if (!paramCode.equals(AH2P_HHMM) && !paramCode.equals(AH2P_MMDD)
                        && colIdx != ctx.table.getColParamCodeCount() - 1
                    ) {  // 画列竖线
                        float x = colMeta.getLeft() + colMeta.getWidth();
                        float y0 = ctx.tableHeaderBottom - rowBlock.startRow * ctx.tblCommon.getRowHeight();
                        float y1 = y0 - rowBlock.totalRows * ctx.tblCommon.getRowHeight();
                        ctx.contentStream.moveTo(x, y0);
                        ctx.contentStream.lineTo(x, y1);
                        ctx.contentStream.stroke();
                    }

                    if (paramCode.equals(AH2P_SIGNATURE)) {
                        List<String> signatureLines = rowBlock.wrappedLinesByParam.get(AH2P_SIGNATURE);
                        if (signatureLines != null && !signatureLines.isEmpty()) {
                            float cellBottom = ctx.tableHeaderBottom -
                                (rowBlock.startRow + rowBlock.totalRows) * ctx.tblCommon.getRowHeight();
                            drawSignatureImage(
                                ctx, signatureLines,
                                colMeta.getLeft(), cellBottom,
                                colMeta.getWidth(), rowBlock.totalRows * ctx.tblCommon.getRowHeight()
                            );
                        }
                        continue;
                    }

                    // 填写文字
                    List<String> wrappedLines = paramCode.equals(AH2P_HHMM) ? 
                        (tsLocal == null ? Collections.singletonList("") :
                         Collections.singletonList(TimeUtils.getHourMinute(tsLocal))
                        ) : paramCode.equals(AH2P_MMDD) ?
                        (tsLocal == null ? Collections.singletonList("") :
                         Collections.singletonList(TimeUtils.getMonthDay(tsLocal))
                        ) :
                        rowBlock.wrappedLinesByParam.get(paramCode);
                    if (wrappedLines == null) continue;
                    int nLines = Math.max(1, wrappedLines.size());
                    float cellBottom = ctx.tableHeaderBottom -
                        (rowBlock.startRow + nLines) * ctx.tblCommon.getRowHeight();
                    PdfTextRenderer.drawTxt(
                        ctx.contentStream,
                        ctx.font, ctx.tblTxtStyle.getFontSize(), Color.BLACK,
                        ctx.tblTxtStyle.getCharSpacing(), ctx.tblCommon.getRowHeight(), HorizontalAlign.CENTER,
                        colMeta.getLeft(), cellBottom, colMeta.getWidth(), nLines * ctx.tblCommon.getRowHeight(),
                        wrappedLines
                    );
                }
            }
        }
    }

    private List<Ah2PageData.RowBlock> fakeRowBlocks() {
        List<Ah2PageData.RowBlock> rowBlocks = new ArrayList<>();
        Ah2PageData.RowBlock rowBlock = new Ah2PageData.RowBlock();
        rowBlock.timestamp = LocalDateTime.now();
        rowBlock.startRow = 0;
        rowBlock.totalRows = 10;
        rowBlock.wrappedLinesByParam.put(AH2P_CONSCIOUSNESS, List.of("1"));
        rowBlock.wrappedLinesByParam.put(AH2P_MED_EXEC, List.of(
            "阿莫西林克拉维酸钾注射液 1.2g ",
            "静脉滴注 + ",
            "头孢曲松钠 2g 静脉滴注 +",
            "药品2 + ",
            "药品3"));
        rowBlocks.add(rowBlock);

        rowBlock = new Ah2PageData.RowBlock();
        rowBlock.startRow = 10;
        rowBlock.totalRows = 1;
        rowBlock.isSummaryRow = true;
        rowBlock.timestamp = TimeUtils.getLocalTime(2025, 9, 18, 7, 0);
        rowBlock.summary = List.of("汇总行示例1");
        rowBlocks.add(rowBlock);

        return rowBlocks;
    }

    private void drawSignatureImage(
        Ah2PdfContext ctx, List<String> signatureLines,
        float left, float bottom, float width, float height
    ) {
        if (ctx == null || signatureLines == null || signatureLines.isEmpty()) return;
        float adjustedLeft = left + 1;
        float adjustedWidth = Math.max(0, width - 2);
        float lineHeight = ctx.tblCommon.getRowHeight();

        for (int idx = 0; idx < signatureLines.size(); idx++) {
            String idStr = signatureLines.get(idx);
            if (StrUtils.isBlank(idStr)) continue;
            PDImageXObject img = getSignatureImage(ctx, idStr);
            if (img == null) continue;

            float imgW = img.getWidth();
            float imgH = img.getHeight();
            if (imgW <= 0 || imgH <= 0) continue;
            float scale = Math.min(adjustedWidth / imgW, lineHeight / imgH);
            float drawW = imgW * scale;
            float drawH = imgH * scale;
            // 对齐到该行区域：从下往上计算当前行底部
            float lineBottom = bottom + (signatureLines.size() - idx - 1) * lineHeight;
            float drawX = adjustedLeft + (adjustedWidth - drawW) / 2;
            float drawY = lineBottom + (lineHeight - drawH) / 2;
            try {
                ctx.contentStream.drawImage(img, drawX, drawY, drawW, drawH);
            } catch (IOException e) {
                log.error("Failed to draw signature image for {}: {}", idStr, e.getMessage());
            }
        }
    }

    private PDImageXObject getSignatureImage(Ah2PdfContext ctx, String idStr) {
        if (ctx == null || StrUtils.isBlank(idStr)) return null;
        Long accountId = null;
        try {
            accountId = Long.parseLong(idStr);
        } catch (NumberFormatException e) {
            return null;
        }
        if (accountId == null) return null;
        if (ctx.signImageCache != null && ctx.signImageCache.containsKey(accountId)) {
            return ctx.signImageCache.get(accountId);
        }
        String dataUrl = ctx.accountSignPicMap == null ? null : ctx.accountSignPicMap.get(accountId);
        if (StrUtils.isBlank(dataUrl)) return null;
        int commaIdx = dataUrl.indexOf(',');
        String base64Str = commaIdx >= 0 ? dataUrl.substring(commaIdx + 1) : dataUrl;
        try {
            byte[] bytes = Base64.getDecoder().decode(base64Str);
            PDImageXObject img = PDImageXObject.createFromByteArray(ctx.document, bytes, "sign-" + accountId);
            if (ctx.signImageCache != null) ctx.signImageCache.put(accountId, img);
            return img;
        } catch (Exception e) {
            log.error("Failed to decode signature image for account {}: {}", accountId, e.getMessage());
            return null;
        }
    }

    // 系统配置
    private final String ZONE_ID;
    private final List<String> statusCodeMsgs;
    private final Integer BALANCE_GROUP_TYPE_ID;
    private final Integer DOSAGE_DECIMAL_PLACES;
    private final ValueMetaPB DRAINAGE_TUBE_COLOR_META;
    private final boolean ENABLE_BALANCE_STATS_HOUR_SHIFT;
    private final Set<String> medExeAdminCodes;
    private final Set<String> dietAdminCodes;

    // 公共服务
    private final ConfigShiftUtils configShiftUtils;
    private final PatientService patientService;
    private final MonitoringConfig monitoringConfig;
    private final PatientMonitoringService patientMonitoringService;
    private final BalanceCalculator balanceCalculator;
    private final MedReportUtils medReportUtils;
    private final PatientTubeImpl patientTubeImpl;
    private final PatientNursingReportUtils pnrUtils;
    private final PatientMonitoringRecordRepository pmrRepo;
    private final NursingRecordRepository nrRepo;
    private final PatientShiftRecordRepository psrRepo;
    private final PatientScoreRepository psRepo;
    private final VTECapriniScoreRepository vteCapriniScoreRepo;
    private final PatientBgaRecordRepository pbgarRepo;
    private final PatientBgaRecordDetailRepository pbgardRepo;
    private final PatientNursingReportRepository pnrRepo;
    private final PatientTubeRecordRepository ptrRepo;
    private final PatientTubeStatusRecordRepository ptsrRepo;
    private final AccountRepository accountRepo;

    private Map<String, Long> accountIdToPk;

    // 病人报表数据处理状态
    private static final long STALE_MS = 30 * 60 * 1000; // 30 分钟
    private static class PidInFlight {
        public PidInFlight() {
            startedAt = System.currentTimeMillis();
            lastProgressAt = null;
        }
        public final long startedAt;
        public LocalDateTime lastProgressAt;
    }
    private final ConcurrentHashMap<Long/*pid*/, PidInFlight> processingPids;

    // 观察项编码
    public static final String MP_CONSCIOUSNESS = "consciousness";  // 意识 string
    public static final String MP_LEFT_PUPIL_SIZE = "left_pupil_size";  // 左瞳大小 
    public static final String MP_RIGHT_PUPIL_SIZE = "right_pupil_size";  // 右瞳大小
    public static final String MP_LEFT_PUPIL_REFLEX = "left_pupil_reflex";  // 左瞳反射
    public static final String MP_RIGHT_PUPIL_REFLEX = "right_pupil_reflex";  // 右瞳反射
    public static final String MP_TEMPERATURE = "temperature";  // 体温 float
    public static final String MP_SKIN_TEMPERATURE = "skin_temperature";  // 末梢皮温 float
    public static final String MP_HEART_RATE = "hr";  // 心率 int
    public static final String MP_HEART_RHYTHM = "heart_rhythm";  // 心律 string
    public static final String MP_RESPIRATORY_RATE = "respiratory_rate";  // 呼吸频率 | 呼吸 int
    public static final String MP_NIBP_S = "nibp_s";  // 无创收缩压 int
    public static final String MP_NIBP_D = "nibp_d";  // 无创舒张压 int
    public static final String MP_IBP_S = "ibp_s";  // 有创收缩压 int
    public static final String MP_IBP_D = "ibp_d";  // 有创舒张压 int
    public static final String MP_SPO2 = "Spo2";  // 血氧饱和度 | SpO2
    public static final String MP_CVP = "cvp";  // 中心静脉压 | CVP
    public static final String MP_ICP = "icp";  // 颅内压 | ICP
    public static final String MP_BLOOD_GLUCOSE = "blood_glucose";  // 血糖
    public static final String MP_HOURLY_INTAKE = "hourly_intake";  // 每小时入量 float
    public static final String MP_ACCU_INTAKE = "MP_ACCU_INTAKE";  // 累计入量 float
    public static final String MP_HOURLY_OUTPUT = "hourly_output";  // 每小时出量 float
    public static final String MP_ACCU_OUTPUT = "MP_ACCU_OUTPUT";  // 累计出量 float
    public static final String MP_URINE_OUTPUT = "urine_output";  // 每小时尿量 float
    public static final String MP_ACCU_URINE_OUTPUT = "MP_ACCU_URINE_OUTPUT";  // 累计尿量 float
    public static final String MP_GASTRIC_FLUID_VOLUME = "gastric_fluid_volume";  // 胃液 float
    public static final String MP_STOOL_VOLUME = "stool_volume";  // 大便量 float
    public static final String MP_CRRT_UF = "crrt_UF";  // CRRT超滤量 float
    public static final String MP_OXYGEN_DELIVERY_METHOD = "oxygen_delivery_method";  // 吸氧途径 | 吸氧方式
    public static final String MP_RESPIRATORY_TUBE_DEPTH = "vent_tube_plant_depth";  // (呼吸机)插管深度
    public static final String MP_VENT_OXYGEN_CONCENTRATION = "vent_oxygen_concentration";  // 氧浓度
    public static final String MP_VENT_OXYGEN_FLOW_RATE = "vent_oxygen_flow_rate";  // 氧流量
    public static final String MP_RESPIRATORY_MODE = "vent_respiratory_mode";  // 呼吸模式 | 机械通气/模式
    public static final String MP_RESPIRATORY_RATE_VENT = "vent_respiratory_rate";  // 呼吸频率 | 机械通气/频率
    public static final String MP_SET_TIDAL_VOLUME = "vent_set_tidal_volume";  // sVt | 机械通气/VT(set)
    public static final String MP_FIO2 = "vent_fio2";  // FiO2 | 机械通气/FiO2
    public static final String MP_PS = "vent_PS";  // PS | 机械通气/PS
    public static final String MP_PEEP = "vent_PEEP";  // PEEP | 机械通气/PEEP  （测量的呼气末正压 - Positive End-Expiratory Pressure）
    public static final String MP_LEFT_BREATH_SOUNDS = "left_breath_sounds";  // 左呼吸音  | 机械通气/呼吸音
    public static final String MP_RIGHT_BREATH_SOUNDS = "right_breath_sounds";  // 右呼吸音 | 机械通气/呼吸音
    public static final String MP_AIRWAY_HUMIDIFICATION = "airway_humidification";  // 气道湿化 | 气道温湿化
    public static final String MP_ATOMIZATION = "atomization";  // 雾化
    public static final String MP_SPUTUM_AMOUNT = "sputum_amount";  // 痰量
    public static final String MP_SPUTUM_COLOR = "sputum_color";  // 痰颜色  
    public static final String MP_SUCTION_COUNT = "suction_count";  // 吸痰次数
    public static final String MP_BED_BATHING = "bed_bathing";  // 床上擦洗 | 床上擦浴
    public static final String MP_PERINEAL_CARE = "perineal_care";  // 会阴护理
    public static final String MP_ORAL_CARE = "oral_care";  // 口腔护理
    public static final String MP_TRACHEOSTOMY_CARE = "tracheostomy_care";  // 气切护理
    public static final String MP_SKIN_CARE = "skin_care";  // 皮肤护理
    public static final String MP_WASH_HAIR = "washing_hair";  // 洗头
    public static final String MP_PULMONARY_PHYSIOTHERAPY = "pulmonary_physiotherapy";  // 肺部理疗
    public static final String MP_LIMB_PNEUMATIC_THERAPY = "limb_pneumatic_therapy";  // 肢体气压治疗
    public static final String MP_RESTRAINT = "restraint";  // 约束
    public static final String MP_BODY_POSITION = "body_position";  // 体位

    // 护理评分编码
    public static final String PS_BRADEN = "braden";
    public static final String PS_MORSE = "morse";
    public static final String PS_AODLS = "activities_of_daily_living_assessment";
    public static final String PS_VTE_CAPRINI = "vte_caprini";
    public static final String PS_CATHETER_SLIPPAGE = "catheter_slippage";
    public static final String PS_RASS = "rass";
    public static final String PS_FRS_V2 = "frs_v2";

    // 血气编码
    public static final String BGA_PH = "bga_ph";  // 血气：PH
    public static final String BGA_PCO2 = "bga_pco2";  // 血气：PCO2
    public static final String BGA_PO2 = "bga_po2";  // 血气：PO2
    public static final String BGA_PO2_FIO2 = "bga_pao2/fio2";  // 血气：Po2/FiO2
    public static final String BGA_BE = "bga_be";  // 血气：BE
    public static final String BGA_HCO3 = "bga_hco3-";  // 血气：HCO3-
    public static final String BGA_SPO2 = "bga_o2sat";  // 血气：SpO2
    public static final String BGA_K = "bga_k+";  // 血气：K+
    public static final String BGA_NA = "bga_na+";  // 血气：Na+
    public static final String BGA_CL = "bga_cl-";  // 血气：Cl-
    public static final String BGA_LAC = "bga_lac";  // 血气：Lac

    // 管道
    public static final String TUBE_DEPTH_STATUS_NAME = "置入长度";
    public static final String TUBE_STATE_STATUS_NAME = "管道状态";
    public static final String TUBE_STATE_NORMAL = "正常";
    public static final String TUBE_STATE_ABNORMAL = "异常";
    public static final String TUBE_STATE_DRESSING_CHANGE = "换药";

    // 报表参数代码
    public static final String AH2P_MMDD = "AH2P_MMDD";  // 月日
    public static final String AH2P_HHMM = "AH2P_HHMM";  // 时分
    
    public static final String AH2P_CONSCIOUSNESS = "AH2P_CONSCIOUSNESS";  // 意识
    public static final String AH2P_LEFT_PUPIL_SIZE = "AH2P_LEFT_PUPIL_SIZE";  // 左瞳大小
    public static final String AH2P_RIGHT_PUPIL_SIZE = "AH2P_RIGHT_PUPIL_SIZE";  // 右瞳大小
    public static final String AH2P_LEFT_PUPIL_REFLEX = "AH2P_LEFT_PUPIL_REFLEX";  // 左瞳反射
    public static final String AH2P_RIGHT_PUPIL_REFLEX = "AH2P_RIGHT_PUPIL_REFLEX";  // 右瞳反射
    public static final String AH2P_TEMPERATURE = "AH2P_TEMPERATURE";  // 体温
    public static final String AH2P_SKIN_TEMPERATURE = "AH2P_SKIN_TEMPERATURE";  // 末梢皮温
    public static final String AH2P_HEART_RATE = "AH2P_HEART_RATE";  // 心率
    public static final String AH2P_HEART_RHYTHM = "AH2P_HEART_RHYTHM";  // 心律
    public static final String AH2P_RESPIRATORY_RATE = "AH2P_RESPIRATORY_RATE";  // 呼吸频率
    public static final String AH2P_NIBP = "AH2P_NIBP";  // 无创血压
    public static final String AH2P_IBP = "AH2P_IBP";  // 有创血压
    public static final String AH2P_SPO2 = "AH2P_SPO2";  // SpO2
    public static final String AH2P_CVP = "AH2P_CVP";  // CVP
    public static final String AH2P_ICP = "AH2P_ICP";  // ICP
    public static final String AH2P_BLOOD_GLUCOSE = "AH2P_BLOOD_GLUCOSE";  // 血糖
    public static final String AH2P_MED_EXEC = "AH2P_MED_EXEC";  // 执行用药
    public static final String AH2P_MEDICATION_ML = "AH2P_MEDICATION_ML";  // 液体量
    public static final String AH2P_NASOGASTRIC = "AH2P_NASOGASTRIC";  // 饮食/鼻饲
    public static final String AH2P_NASOGASTRIC_ML = "AH2P_NASOGASTRIC_ML";  // 饮食/鼻饲量
    public static final String AH2P_HOURLY_INTAKE = "AH2P_HOURLY_INTAKE";  // 每小时入量
    public static final String AH2P_TOTAL_INTAKE = "AH2P_TOTAL_INTAKE";  // 总量
    public static final String AH2P_URINE_OUTPUT = "AH2P_URINE_OUTPUT";  // 每小时尿量
    public static final String AH2P_ACCUMULATED_URINE_OUTPUT = "AH2P_ACCUMULATED_URINE_OUTPUT";  // 累计尿量
    public static final String AH2P_GASTRIC_FLUID_VOLUME = "AH2P_GASTRIC_FLUID_VOLUME";  // 胃液
    public static final String AH2P_STOOL_VOLUME = "AH2P_STOOL_VOLUME";  // 大便量
    public static final String AH2P_OTHER_OUTPUT_NAME = "AH2P_OTHER_OUTPUT_NAME";  // 其他名称
    public static final String AH2P_OTHER_OUTPUT_VOLUME = "AH2P_OTHER_OUTPUT_VOLUME";  // 其他量
    public static final String AH2P_DRAINAGE_FLUID_COLOR = "AH2P_DRAINAGE_FLUID_COLOR";  // 引流液颜色
    public static final String AH2P_CRRT_UF = "AH2P_CRRT_UF";  // 超滤量
    public static final String AH2P_ACCUMULATED_OUTPUT = "AH2P_ACCUMULATED_OUTPUT";  // 总量
    public static final String AH2P_NURSING_RECORD = "AH2P_NURSING_RECORD";  // 病情观察及处理
    public static final String AH2P_SIGNATURE = "AH2P_SIGNATURE";  // 签名

    public static final String AH2P_OXYGEN_DELIVERY_METHOD = "AH2P_OXYGEN_DELIVERY_METHOD";  // 吸氧方式
    public static final String AH2P_RESPIRATORY_TUBE_DEPTH = "AH2P_RESPIRATORY_TUBE_DEPTH";  // 插管深度
    public static final String AH2P_OXYGEN_CONCENTRATION_FLOW = "AH2P_OXYGEN_CONCENTRATION_FLOW";  // 氧浓度/氧流量
    public static final String AH2P_RESPIRATORY_MODE = "AH2P_RESPIRATORY_MODE";  // 呼吸模式
    public static final String AH2P_RESPIRATORY_RATE_VENT = "AH2P_RESPIRATORY_RATE_VENT";  // 呼吸频率
    public static final String AH2P_SET_TIDAL_VOLUME = "AH2P_SET_TIDAL_VOLUME";  // VT(set)
    public static final String AH2P_FIO2 = "AH2P_FIO2";  // FiO2
    public static final String AH2P_PS = "AH2P_PS";  // PS
    public static final String AH2P_PEEP = "AH2P_PEEP";  // PEEP
    public static final String AH2P_LEFT_BREATH_SOUNDS = "AH2P_LEFT_BREATH_SOUNDS";  // 左呼吸音
    public static final String AH2P_RIGHT_BREATH_SOUNDS = "AH2P_RIGHT_BREATH_SOUNDS";  // 右呼吸音
    public static final String AH2P_AIRWAY_HUMIDIFICATION = "AH2P_AIRWAY_HUMIDIFICATION";  // 气道湿化
    public static final String AH2P_ATOMIZATION = "AH2P_ATOMIZATION";  // 雾化
    public static final String AH2P_SPUTUM_AMOUNT = "AH2P_SPUTUM_AMOUNT";  // 痰量
    public static final String AH2P_SPUTUM_COLOR = "AH2P_SPUTUM_COLOR";  // 痰颜色
    public static final String AH2P_SUCTION_COUNT = "AH2P_SUCTION_COUNT";  // 吸痰次数
    public static final String AH2P_BRADEN = "AH2P_BRADEN";  // Braden评分
    public static final String AH2P_MORSE = "AH2P_MORSE";  // Morse跌倒评估
    public static final String AH2P_AODLS = "AH2P_AODLS";  // 自理评估
    public static final String AH2P_VTE_CAPRINI = "AH2P_VTE_CAPRINI";  // VTE Caprini 血栓风险评估
    public static final String AH2P_CATHETER_SLIPPAGE = "AH2P_CATHETER_SLIPPAGE";  // 导管风险评估
    public static final String AH2P_RASS = "AH2P_RASS";  // RASS镇静评估
    public static final String AH2P_CPOT_NRS = "AH2P_CPOT_NRS";  // CPOT/NRS
    public static final String AH2P_TUBE_NAME = "AH2P_TUBE_NAME";  // 管道名称
    public static final String AH2P_TUBE_DEPTH = "AH2P_TUBE_DEPTH";  // 置管深度
    public static final String AH2P_TUBE_DRESSING_CHANGE = "AH2P_TUBE_DRESSING_CHANGE";  // 管道维护：换药
    public static final String AH2P_TUBE_NORMAL = "AH2P_TUBE_NORMAL";  // 管道维护：正常
    public static final String AH2P_TUBE_ABNORMAL = "AH2P_TUBE_ABNORMAL";  // 管道维护：异常
    public static final String AH2P_BED_BATHING = "AH2P_BED_BATHING";  // 床上擦浴
    public static final String AH2P_PERINEAL_CARE = "AH2P_PERINEAL_CARE";  // 会阴护理
    public static final String AH2P_ORAL_CARE = "AH2P_ORAL_CARE";  // 口腔护理
    public static final String AH2P_TRACHEOSTOMY_CARE = "AH2P_TRACHEOSTOMY_CARE";  // 气切护理
    public static final String AH2P_SKIN_CARE = "AH2P_SKIN_CARE";  // 皮肤护理
    public static final String AH2P_WASH_HAIR = "AH2P_WASH_HAIR";  // 洗头
    public static final String AH2P_PULMONARY_PHYSIOTHERAPY = "AH2P_PULMONARY_PHYSIOTHERAPY";  // 肺部理疗
    public static final String AH2P_LIMB_PNEUMATIC_THERAPY = "AH2P_LIMB_PNEUMATIC_THERAPY";  // 肢体气压理疗
    public static final String AH2P_RESTRAINT = "AH2P_RESTRAINT";  // 约束
    public static final String AH2P_BODY_POSITION = "AH2P_BODY_POSITION";  // 体位
    public static final String AH2P_BGA_PH = "AH2P_BGA_PH";  // 血气：PH
    public static final String AH2P_BGA_PCO2 = "AH2P_BGA_PCO2";  // 血气：PCO2
    public static final String AH2P_BGA_PO2 = "AH2P_BGA_PO2";  // 血气：PO2
    public static final String AH2P_BGA_PO2_FIO2 = "AH2P_BGA_PO2_FIO2";  // 血气：Po2/FiO2
    public static final String AH2P_BGA_BE = "AH2P_BGA_BE";  // 血气：BE
    public static final String AH2P_BGA_HCO3 = "AH2P_BGA_HCO3";  // 血气：HCO3-
    public static final String AH2P_BGA_SPO2 = "AH2P_BGA_SPO2";  // 血气：SpO2
    public static final String AH2P_BGA_K = "AH2P_BGA_K";  // 血气：K+
    public static final String AH2P_BGA_NA = "AH2P_BGA_NA";  // 血气：Na+
    public static final String AH2P_BGA_CL = "AH2P_BGA_CL";  // 血气：Cl-
    public static final String AH2P_BGA_LAC = "AH2P_BGA_LAC";  // 血气：Lac
}

/*
观察项列表：
---
vent_oxygen_concentration 氧浓度
vent_oxygen_flow_rate 氧流量

管道/管道维护/异常：?????
*/
