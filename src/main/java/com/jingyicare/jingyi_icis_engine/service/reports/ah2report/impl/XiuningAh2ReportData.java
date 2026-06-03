package com.jingyicare.jingyi_icis_engine.service.reports.ah2report.impl;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.entity.patientshifts.PatientShiftRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportAh2.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.NursingRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patientshifts.PatientShiftRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.ah2report.*;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.service.shifts.ConfigShiftUtils;
import com.jingyicare.jingyi_icis_engine.service.tubes.PatientTubeImpl;
import com.jingyicare.jingyi_icis_engine.utils.*;

import static com.jingyicare.jingyi_icis_engine.service.reports.ah2report.Ah2ReportCodes.*;

@Component
@Slf4j
public class XiuningAh2ReportData implements Ah2ReportDataProvider {
    public XiuningAh2ReportData(
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils configShiftUtils,
        @Autowired PatientService patientService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired BalanceCalculator balanceCalculator,
        @Autowired PatientTubeImpl patientTubeImpl,
        @Autowired PatientMonitoringRecordRepository pmrRepo,
        @Autowired NursingRecordRepository nrRepo,
        @Autowired PatientShiftRecordRepository psrRepo,
        @Autowired PatientTubeRecordRepository ptrRepo,
        @Autowired PatientTubeStatusRecordRepository ptsrRepo,
        @Autowired PatientTubeAttrRepository ptaRepo,
        @Autowired TubeTypeAttributeRepository ttaRepo,
        @Autowired AccountRepository accountRepo,
        @Autowired BalanceStatsShiftRepository balanceStatsShiftRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.BALANCE_GROUP_TYPE_ID = monitoringConfig.getBalanceGroupTypeId();
        this.configShiftUtils = configShiftUtils;
        this.patientService = patientService;
        this.monitoringConfig = monitoringConfig;
        this.patientMonitoringService = patientMonitoringService;
        this.balanceCalculator = balanceCalculator;
        this.patientTubeImpl = patientTubeImpl;
        this.pmrRepo = pmrRepo;
        this.nrRepo = nrRepo;
        this.psrRepo = psrRepo;
        this.ptrRepo = ptrRepo;
        this.ptsrRepo = ptsrRepo;
        this.ptaRepo = ptaRepo;
        this.ttaRepo = ttaRepo;
        this.accountRepo = accountRepo;
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
    }

    @Override
    public String variant() {
        return ReportProperties.Ah2.VARIANT_XIUNING;
    }

    public Pair<ReturnCode, List<Ah2PageData>> collectPageData(
        Ah2PdfContext ctx,
        Long pid, String deptId, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc,
        String accountId
    ) {
        loadAccountSignatureMap(ctx);

        if (pid == null || pid <= 0 || StrUtils.isBlank(deptId) ||
            queryStartUtc == null || queryEndUtc == null || !queryEndUtc.isAfter(queryStartUtc)
        ) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_PARAM_VALUE), null
            );
        }

        PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PATIENT_NOT_FOUND), null
            );
        }
        LocalDateTime admissionTimeUtc = patientRecord.getAdmissionTime();
        if (admissionTimeUtc != null && queryStartUtc.isBefore(admissionTimeUtc)) {
            queryStartUtc = admissionTimeUtc;
        }
        LocalDateTime dischargeTimeUtc = patientRecord.getDischargeTime();
        if (dischargeTimeUtc != null && queryEndUtc.isAfter(dischargeTimeUtc)) {
            queryEndUtc = dischargeTimeUtc;
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (queryEndUtc.isAfter(nowUtc)) queryEndUtc = nowUtc;

        ctx.mpMap = monitoringConfig.getMonitoringParams(deptId);
        if (ctx.mpMap == null || ctx.mpMap.isEmpty()) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.MONITORING_PARAM_CODE_NOT_EXIST), null
            );
        }
        ctx.mpVmMap = ctx.mpMap.entrySet().stream()
            .filter(e -> e.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getValueMeta()));

        List<LocalDateTime> balanceStatTimeUtcHistory = configShiftUtils.getBalanceStatTimeUtcHistory(deptId);
        LocalDateTime shiftStartUtc = configShiftUtils.getBalanceStatStartUtc(balanceStatTimeUtcHistory, queryStartUtc);
        if (shiftStartUtc == null) shiftStartUtc = queryStartUtc;

        List<Ah2PageData> dailyDataList = new ArrayList<>();
        while (shiftStartUtc.isBefore(queryEndUtc)) {
            LocalDateTime shiftEndUtc = shiftStartUtc.plusDays(1);
            ctx.halfDayShiftHours = resolveHalfDayShiftHours(deptId, shiftStartUtc);
            fillShiftNurses(ctx, pid, shiftStartUtc, shiftEndUtc);

            Ah2PageData dailyData = new Ah2PageData();
            dailyData.pageStartTs = shiftStartUtc;
            dailyData.pageEndTs = shiftEndUtc;
            dailyData.rowBlocks.addAll(collectRows(ctx, pid, deptId, shiftStartUtc, shiftEndUtc, accountId));
            addBalanceSummaryRows(ctx, dailyData.rowBlocks, pid, deptId, shiftStartUtc, shiftEndUtc, accountId);
            dailyData.rowBlocks.sort(this::compareRows);
            dailyDataList.add(dailyData);

            shiftStartUtc = shiftEndUtc;
        }

        List<Ah2PageData> pageDataList = paginateData(
            dailyDataList, queryStartUtc, admissionTimeUtc == null ? queryStartUtc : admissionTimeUtc,
            ctx.tblCommon.getBodyRows(), 1
        );
        final LocalDateTime finalQueryStartUtc = queryStartUtc;
        final LocalDateTime finalQueryEndUtc = queryEndUtc;
        pageDataList = pageDataList.stream()
            .filter(p -> p.pageStartTs != null && p.pageEndTs != null &&
                !p.pageEndTs.isBefore(finalQueryStartUtc) && !p.pageStartTs.isAfter(finalQueryEndUtc))
            .toList();

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK), pageDataList);
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

    private void fillShiftNurses(Ah2PdfContext ctx, long pid, LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc) {
        ctx.shiftNurses = new ArrayList<>();
        for (PatientShiftRecord psr : psrRepo.findByPidAndOverlappingTimeRange(pid, shiftStartUtc, shiftEndUtc)) {
            Long nurseAccountId = getAccountPk(psr.getShiftNurseId());
            if (nurseAccountId == null || psr.getShiftStart() == null || psr.getShiftEnd() == null) continue;
            ctx.shiftNurses.add(new Pair<>(new Pair<>(psr.getShiftStart(), psr.getShiftEnd()), nurseAccountId));
        }
    }

    private List<Ah2PageData.RowBlock> collectRows(
        Ah2PdfContext ctx, Long pid, String deptId, LocalDateTime startUtc, LocalDateTime endUtc, String accountId
    ) {
        Map<LocalDateTime, MinuteBucket> buckets = new TreeMap<>();

        List<PatientMonitoringRecord> pmrs = pmrRepo.findByPidAndEffectiveTimeRange(pid, startUtc, endUtc);
        pmrs.sort(Comparator
            .comparing(PatientMonitoringRecord::getEffectiveTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PatientMonitoringRecord::getMonitoringParamCode, Comparator.nullsLast(String::compareTo))
            .thenComparing(PatientMonitoringRecord::getId, Comparator.nullsLast(Long::compareTo)));
        for (PatientMonitoringRecord pmr : pmrs) {
            if (pmr.getEffectiveTime() == null || StrUtils.isBlank(pmr.getMonitoringParamCode())) continue;
            LocalDateTime minute = truncateToMinute(pmr.getEffectiveTime());
            buckets.computeIfAbsent(minute, MinuteBucket::new)
                .recordsByCode.computeIfAbsent(pmr.getMonitoringParamCode(), k -> new ArrayList<>())
                .add(pmr);
        }

        List<TubeEntry> tubeEntries = collectTubeEntries(pid, startUtc, endUtc);
        for (TubeEntry entry : tubeEntries) {
            buckets.computeIfAbsent(entry.minute, MinuteBucket::new).tubeEntries.add(entry);
        }

        List<NursingRecord> nursingRecords = nrRepo.findReportNursingRecords(pid, startUtc, endUtc);
        for (NursingRecord record : nursingRecords) {
            if (record.getEffectiveTime() == null || StrUtils.isBlank(record.getContent())) continue;
            buckets.computeIfAbsent(truncateToMinute(record.getEffectiveTime()), MinuteBucket::new)
                .nursingRecords.add(record);
        }

        List<Ah2PageData.RowBlock> rows = new ArrayList<>();
        for (MinuteBucket bucket : buckets.values()) {
            List<Map<String, List<String>>> observationRows = buildObservationRows(ctx, bucket);
            List<Map<String, List<String>>> tubeRows = buildTubeRows(ctx, bucket);
            List<Map<String, List<String>>> nursingRows = buildNursingRows(ctx, bucket);
            int rowCount = Math.max(observationRows.size(), Math.max(tubeRows.size(), nursingRows.size()));
            for (int i = 0; i < rowCount; i++) {
                Map<String, List<String>> lines = new HashMap<>();
                if (i < observationRows.size()) lines.putAll(observationRows.get(i));
                if (i < tubeRows.size()) lines.putAll(tubeRows.get(i));
                if (i < nursingRows.size()) lines.putAll(nursingRows.get(i));
                if (lines.isEmpty()) continue;

                Ah2PageData.RowBlock rowBlock = new Ah2PageData.RowBlock();
                rowBlock.timestamp = bucket.minute;
                rowBlock.leadingDataBlock = i == 0;
                rowBlock.wrappedLinesByParam.putAll(lines);
                rows.add(rowBlock);
            }
        }
        return rows;
    }

    private List<Map<String, List<String>>> buildObservationRows(Ah2PdfContext ctx, MinuteBucket bucket) {
        int rowCount = bucket.recordsByCode.values().stream().mapToInt(List::size).max().orElse(0);
        if (rowCount == 0) return new ArrayList<>();

        List<Map<String, List<String>>> rows = new ArrayList<>();
        for (int rowIdx = 0; rowIdx < rowCount; rowIdx++) {
            Map<String, List<String>> row = new HashMap<>();
            setMappedRecord(ctx, row, bucket, MP_CONSCIOUSNESS, XN_CONSCIOUSNESS, rowIdx, CONSCIOUSNESS_MAP);
            setRawRecord(ctx, row, bucket, MP_LEFT_PUPIL_SIZE, XN_LEFT_PUPIL_SIZE, rowIdx);
            setRawRecord(ctx, row, bucket, MP_LEFT_PUPIL_REFLEX, XN_LEFT_PUPIL_REFLEX, rowIdx);
            setRawRecord(ctx, row, bucket, MP_RIGHT_PUPIL_SIZE, XN_RIGHT_PUPIL_SIZE, rowIdx);
            setRawRecord(ctx, row, bucket, MP_RIGHT_PUPIL_REFLEX, XN_RIGHT_PUPIL_REFLEX, rowIdx);
            setRawRecord(ctx, row, bucket, MP_TEMPERATURE, XN_TEMPERATURE, rowIdx);
            setRawRecord(ctx, row, bucket, MP_HEART_RATE, XN_HR, rowIdx);
            setRawRecord(ctx, row, bucket, MP_RESPIRATORY_RATE, XN_RESPIRATORY_RATE, rowIdx);
            setCombined(ctx, row, XN_NIBP, getRecordString(ctx, bucket, MP_NIBP_S, rowIdx), getRecordString(ctx, bucket, MP_NIBP_D, rowIdx));
            setRawRecord(ctx, row, bucket, MP_SPO2, XN_SPO2, rowIdx);
            setCombined(ctx, row, XN_IBP, getRecordString(ctx, bucket, MP_IBP_S, rowIdx), getRecordString(ctx, bucket, MP_IBP_D, rowIdx));
            setRawRecord(ctx, row, bucket, MP_CVP, XN_CVP, rowIdx);
            setMappedRecord(ctx, row, bucket, MP_OXYGEN_DELIVERY_METHOD, XN_OXYGEN_DELIVERY_METHOD, rowIdx, OXYGEN_METHOD_MAP);
            setRawRecord(ctx, row, bucket, MP_OXYGEN_FLOW_RATE_XN, XN_OXYGEN_FLOW_RATE, rowIdx);
            setRawRecord(ctx, row, bucket, MP_OXYGEN_CONCENTRATION_XN, XN_OXYGEN_CONCENTRATION, rowIdx);
            setMappedRecord(ctx, row, bucket, MP_ARTIFICIAL_AIRWAY_METHOD, XN_ARTIFICIAL_AIRWAY_METHOD, rowIdx, ARTIFICIAL_AIRWAY_MAP);
            setRawRecord(ctx, row, bucket, MP_RESPIRATORY_TUBE_DEPTH, XN_VENT_TUBE_PLANT_DEPTH, rowIdx);
            setMappedRecord(ctx, row, bucket, MP_RESPIRATORY_MODE, XN_VENT_RESPIRATORY_MODE, rowIdx, VENT_MODE_MAP);
            setRawRecord(ctx, row, bucket, MP_VENT_TIDAL_VOLUME_XN, XN_VENT_TIDAL_VOLUME, rowIdx);
            setRawRecord(ctx, row, bucket, MP_RESPIRATORY_RATE_VENT, XN_VENT_RESPIRATORY_RATE, rowIdx);
            setRawRecord(ctx, row, bucket, MP_FIO2, XN_VENT_FIO2, rowIdx);
            setPeepPsv(ctx, row, bucket, rowIdx);
            setRawRecord(ctx, row, bucket, MP_VENT_CUFF_PRESSURE, XN_VENT_CUFF_PRESSURE, rowIdx);
            setRawRecord(ctx, row, bucket, MP_BLOOD_GLUCOSE, AH2P_BLOOD_GLUCOSE, rowIdx);
            setRawRecord(ctx, row, bucket, MP_SUCTION_XN, XN_SUCTION, rowIdx);
            setRestraint(ctx, row, bucket, rowIdx);
            setRawRecord(ctx, row, bucket, MP_BACK_PERCUSSION, XN_BACK_PERCUSSION, rowIdx);
            setMappedRecord(ctx, row, bucket, MP_SKIN_CARE, XN_SKIN_CARE, rowIdx, SKIN_CARE_MAP);
            setOtherNursing(ctx, row, bucket, rowIdx);
            setMappedRecord(ctx, row, bucket, MP_BODY_POSITION, XN_BODY_POSITION, rowIdx, BODY_POSITION_MAP);
            if (!row.isEmpty()) rows.add(row);
        }
        return rows;
    }

    private List<Map<String, List<String>>> buildTubeRows(Ah2PdfContext ctx, MinuteBucket bucket) {
        if (bucket.tubeEntries.isEmpty()) return new ArrayList<>();
        bucket.tubeEntries.sort(Comparator
            .comparing((TubeEntry e) -> e.category)
            .thenComparing(e -> e.tubeRecordId, Comparator.nullsLast(Long::compareTo)));
        List<Map<String, List<String>>> rows = new ArrayList<>();
        for (TubeEntry entry : bucket.tubeEntries) {
            Map<String, List<String>> row = new HashMap<>();
            if (entry.category == TubeCategory.DRAINAGE) {
                putCell(ctx, row, XN_DRAINAGE_TUBE_NAME, entry.nameCode);
                putCell(ctx, row, XN_DRAINAGE_TUBE_DEPTH, entry.depth);
                putCell(ctx, row, XN_DRAINAGE_TUBE_COLOR, entry.colorCode);
                putCell(ctx, row, XN_DRAINAGE_TUBE_NURSING, entry.nursingCode);
            } else if (entry.category == TubeCategory.VASCULAR) {
                putCell(ctx, row, XN_VASCULAR_TUBE_NAME, entry.nameCode);
                putCell(ctx, row, XN_VASCULAR_TUBE_DEPTH, entry.depth);
                putCell(ctx, row, XN_VASCULAR_TUBE_NURSING, entry.nursingCode);
            }
            if (!row.isEmpty()) rows.add(row);
        }
        return rows;
    }

    private List<Map<String, List<String>>> buildNursingRows(Ah2PdfContext ctx, MinuteBucket bucket) {
        if (bucket.nursingRecords.isEmpty()) return new ArrayList<>();
        ParamColMetaPB nursingCol = ctx.colMetaMap.get(AH2P_NURSING_RECORD);
        if (nursingCol == null) return new ArrayList<>();
        bucket.nursingRecords.sort(Comparator
            .comparing(NursingRecord::getEffectiveTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(NursingRecord::getId, Comparator.nullsLast(Long::compareTo)));

        List<Map<String, List<String>>> rows = new ArrayList<>();
        for (NursingRecord record : bucket.nursingRecords) {
            List<String> contentLines = wrap(ctx, nursingCol, record.getContent());
            if (contentLines.isEmpty()) continue;

            Map<String, List<String>> row = new HashMap<>();
            row.put(AH2P_NURSING_RECORD, contentLines);

            List<String> signatureLines = new ArrayList<>();
            Long nurseAccountId = getAccountPk(record.getCreatedBy());
            signatureLines.add(nurseAccountId == null ? "" : nurseAccountId.toString());
            for (int i = 1; i < contentLines.size(); i++) signatureLines.add("");
            row.put(AH2P_SIGNATURE, signatureLines);
            rows.add(row);
        }
        return rows;
    }

    private void addBalanceSummaryRows(
        Ah2PdfContext ctx, List<Ah2PageData.RowBlock> rows, Long pid, String deptId,
        LocalDateTime shiftStartUtc, LocalDateTime shiftEndUtc, String accountId
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
        PatientMonitoringService.GetMonitoringRecordsResult recordsResult = patientMonitoringService.getMonitoringRecords(
            pid, deptId, BALANCE_GROUP_TYPE_ID, shiftStartUtc, shiftEndUtc,
            false, groupBetaList, accountId
        );
        if (recordsResult.statusCode != StatusCode.OK) {
            log.warn("Xiuning AH2 balance summary skipped, status={}", recordsResult.statusCode);
            return;
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
        balanceCalculator.storeGroupRecordsStats(args);

        List<PatientMonitoringRecord> fullRecords = recordsResult.recordList.stream()
            .filter(record -> record.getEffectiveTime() != null && record.getEffectiveTime().isBefore(shiftEndUtc))
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        args.recordList = fullRecords;
        args.endTime = shiftEndUtc;
        List<BalanceGroupSummaryPB> fullSummary = balanceCalculator.summarizeBalanceGroups(args);
        if (fullSummary != null && !fullSummary.isEmpty()) {
            rows.add(createSummaryRowBlock(ctx, shiftEndUtc, Consts.REPORT_TEMPLATE_AH2_FULL_DAY_SUMMARY, fullSummary));
        }

        LocalDateTime halfDayEndUtc = shiftStartUtc.plusHours(normalizeHalfDayShiftHours(ctx));
        List<PatientMonitoringRecord> halfRecords = recordsResult.recordList.stream()
            .filter(record -> record.getEffectiveTime() != null && record.getEffectiveTime().isBefore(halfDayEndUtc))
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        args.recordList = halfRecords;
        args.endTime = halfDayEndUtc;
        List<BalanceGroupSummaryPB> halfSummary = balanceCalculator.summarizeBalanceGroups(args);
        if (halfSummary != null && !halfSummary.isEmpty()) {
            rows.add(createSummaryRowBlock(ctx, halfDayEndUtc, Consts.REPORT_TEMPLATE_AH2_HALF_DAY_SUMMARY, halfSummary));
        }
    }

    private Ah2PageData.RowBlock createSummaryRowBlock(
        Ah2PdfContext ctx, LocalDateTime timestamp, String title, List<BalanceGroupSummaryPB> summaryList
    ) {
        float summaryWidth = ctx.tblCommon.getWidth();
        ParamColMetaPB dateMeta = ctx.colMetaMap.get(AH2P_MMDD);
        ParamColMetaPB timeMeta = ctx.colMetaMap.get(AH2P_HHMM);
        ParamColMetaPB signatureMeta = ctx.colMetaMap.get(AH2P_SIGNATURE);
        if (dateMeta != null) summaryWidth -= dateMeta.getWidth();
        if (timeMeta != null) summaryWidth -= timeMeta.getWidth();
        if (signatureMeta != null) summaryWidth -= signatureMeta.getWidth();

        StringBuilder sb = new StringBuilder(title).append(": ");
        for (BalanceGroupSummaryPB bgs : summaryList) {
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

        Ah2PageData.RowBlock rowBlock = new Ah2PageData.RowBlock();
        rowBlock.timestamp = timestamp;
        rowBlock.isSummaryRow = true;
        try {
            rowBlock.summary = JfkPdfUtils.getWrappedLines(
                ctx.font, ctx.tblTxtStyle.getFontSize(), summaryWidth,
                ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(sb.toString()))
            );
        } catch (Exception e) {
            log.warn("Failed to wrap Xiuning summary: {}", e.getMessage());
            rowBlock.summary = new ArrayList<>(List.of(sb.toString()));
        }

        List<String> signatureLines = new ArrayList<>();
        for (int i = 0; i < rowBlock.summary.size(); i++) signatureLines.add("");
        String nurseIdStr = getShiftNurseIdStr(ctx, timestamp);
        if (!StrUtils.isBlank(nurseIdStr) && !signatureLines.isEmpty()) {
            signatureLines.set(signatureLines.size() - 1, nurseIdStr);
        }
        rowBlock.wrappedLinesByParam.put(AH2P_SIGNATURE, signatureLines);
        return rowBlock;
    }

    private List<TubeEntry> collectTubeEntries(Long pid, LocalDateTime startUtc, LocalDateTime endUtc) {
        Map<Long, PatientTubeRecord> tubeRecordMap = ptrRepo.findReportTubeRecords(pid, startUtc, endUtc)
            .stream()
            .filter(r -> r.getId() != null)
            .collect(Collectors.toMap(PatientTubeRecord::getId, r -> r, (a, b) -> a));
        List<Long> tubeRecordIds = new ArrayList<>(tubeRecordMap.keySet());
        Map<Long, String> centralBodyPartMap = getCentralVenousBodyPartMap(tubeRecordIds);

        Map<TubeStatusKey, TubeStatusGroup> groupMap = new HashMap<>();
        for (PatientTubeStatusReportBrief brief : ptsrRepo.findPatientTubeStatusReportBrief(pid, startUtc, endUtc)) {
            if (brief.getTubeRecordId() == null || brief.getRecordedAt() == null || StrUtils.isBlank(brief.getStatusName())) {
                continue;
            }
            TubeStatusKey key = new TubeStatusKey(brief.getTubeRecordId(), truncateToMinute(brief.getRecordedAt()));
            TubeStatusGroup group = groupMap.computeIfAbsent(key, k -> new TubeStatusGroup(k.tubeRecordId, k.minute));
            group.tubeName = brief.getTubeName();
            TubeStatusValue prev = group.statusMap.get(brief.getStatusName());
            TubeStatusValue next = new TubeStatusValue(brief.getValue(), brief.getStatusRecordId());
            if (prev != null) {
                log.warn("Duplicate tube status entry, tubeRecordId={}, minute={}, statusName={}",
                    brief.getTubeRecordId(), key.minute, brief.getStatusName());
            }
            if (prev == null || compareNullableLong(next.statusRecordId, prev.statusRecordId) >= 0) {
                group.statusMap.put(brief.getStatusName(), next);
            }
        }

        List<TubeEntry> entries = new ArrayList<>();
        for (TubeStatusGroup group : groupMap.values()) {
            String tubeName = trim(group.tubeName);
            String drainageName = DRAINAGE_TUBE_NAME_MAP.get(tubeName);
            if (!StrUtils.isBlank(drainageName)) {
                TubeEntry entry = new TubeEntry(TubeCategory.DRAINAGE, group.tubeRecordId, group.minute);
                entry.nameCode = drainageName;
                entry.depth = getStatus(group, TUBE_DEPTH_STATUS_NAME);
                entry.colorCode = mapStrict(DRAINAGE_COLOR_MAP, getStatus(group, "颜色"));
                entry.nursingCode = mapStrict(TUBE_NURSING_MAP, getStatus(group, "护理"));
                entries.add(entry);
                continue;
            }

            String vascularName = mapVascularTubeName(tubeName, centralBodyPartMap.get(group.tubeRecordId));
            if (!StrUtils.isBlank(vascularName)) {
                TubeEntry entry = new TubeEntry(TubeCategory.VASCULAR, group.tubeRecordId, group.minute);
                entry.nameCode = vascularName;
                entry.depth = getStatus(group, TUBE_DEPTH_STATUS_NAME);
                entry.nursingCode = mapStrict(TUBE_NURSING_MAP, getStatus(group, "护理"));
                entries.add(entry);
            }
        }

        entries.sort(Comparator
            .comparing((TubeEntry e) -> e.minute)
            .thenComparing(e -> e.tubeRecordId, Comparator.nullsLast(Long::compareTo))
            .thenComparing(e -> e.category));
        return entries;
    }

    private Map<Long, String> getCentralVenousBodyPartMap(List<Long> tubeRecordIds) {
        if (tubeRecordIds == null || tubeRecordIds.isEmpty()) return new HashMap<>();
        Map<Integer, TubeTypeAttribute> attrMetaMap = ttaRepo.findAll().stream()
            .filter(tta -> tta != null && Boolean.FALSE.equals(tta.getIsDeleted()))
            .collect(Collectors.toMap(TubeTypeAttribute::getId, tta -> tta, (a, b) -> a));

        Map<Long, String> result = new HashMap<>();
        Map<Long, List<PatientTubeAttr>> attrMap = ptaRepo.findByPatientTubeRecordIdIn(tubeRecordIds).stream()
            .collect(Collectors.groupingBy(PatientTubeAttr::getPatientTubeRecordId));
        for (Map.Entry<Long, List<PatientTubeAttr>> entry : attrMap.entrySet()) {
            PatientTubeAttr selected = entry.getValue().stream()
                .filter(pta -> pta != null && !StrUtils.isBlank(pta.getValue()))
                .filter(pta -> {
                    TubeTypeAttribute meta = attrMetaMap.get(pta.getTubeAttrId());
                    return meta != null && "身体部位".equals(trim(meta.getName())) &&
                        CENTRAL_VENOUS_BODY_PART_MAP.containsKey(trim(pta.getValue()));
                })
                .min(Comparator
                    .comparing((PatientTubeAttr pta) -> {
                        TubeTypeAttribute meta = attrMetaMap.get(pta.getTubeAttrId());
                        return meta == null || meta.getDisplayOrder() == null ? Integer.MAX_VALUE : meta.getDisplayOrder();
                    })
                    .thenComparing(PatientTubeAttr::getId, Comparator.nullsLast(Long::compareTo)))
                .orElse(null);
            if (selected != null) {
                result.put(entry.getKey(), CENTRAL_VENOUS_BODY_PART_MAP.get(trim(selected.getValue())));
            }
        }
        return result;
    }

    private String mapVascularTubeName(String tubeName, String centralBodyPartCode) {
        if ("中心静脉导管".equals(tubeName)) return centralBodyPartCode;
        if ("外周静脉针".equals(tubeName)) return "2";
        if ("PICC管".equals(tubeName)) return "3";
        if ("动脉导管".equals(tubeName)) return "4";
        if ("中长导管".equals(tubeName)) return "5";
        return "";
    }

    private List<Ah2PageData> paginateData(
        List<Ah2PageData> dailyDataList, LocalDateTime pageStartUtc,
        LocalDateTime admissionTimeUtc, int linesPerPage, int startPageNumber
    ) {
        if (dailyDataList == null || dailyDataList.isEmpty() || linesPerPage <= 0) return new ArrayList<>();
        int normalizedStartPageNumber = Math.max(startPageNumber, 1);
        List<Ah2PageData> pageDataList = new ArrayList<>();
        Ah2PageData curPage = new Ah2PageData();
        curPage.pageNumber = normalizedStartPageNumber;
        curPage.pageStartTs = pageStartUtc;
        int curPageLines = 0;
        LocalDateTime curPageDate = pageStartUtc;

        for (Ah2PageData dailyData : dailyDataList) {
            for (Ah2PageData.RowBlock rowBlock : dailyData.rowBlocks) {
                if (rowBlock.timestamp == null) continue;
                if (admissionTimeUtc != null && rowBlock.timestamp.isBefore(admissionTimeUtc)) continue;
                if (rowBlock.isSummaryRow && (rowBlock.summary == null || rowBlock.summary.isEmpty())) continue;
                if (!rowBlock.isSummaryRow && (rowBlock.wrappedLinesByParam == null || rowBlock.wrappedLinesByParam.isEmpty())) continue;

                int nLines = rowBlock.isSummaryRow
                    ? Math.max(1, rowBlock.summary.size())
                    : rowBlock.wrappedLinesByParam.values().stream()
                        .filter(Objects::nonNull)
                        .mapToInt(List::size)
                        .max()
                        .orElse(1);

                List<String> summaryLines = rowBlock.summary == null ? new ArrayList<>() : new ArrayList<>(rowBlock.summary);
                List<String> summarySignLines = rowBlock.wrappedLinesByParam == null ? null :
                    rowBlock.wrappedLinesByParam.get(AH2P_SIGNATURE);
                List<Pair<String, List<String>>> paramLines = new ArrayList<>();
                if (!rowBlock.isSummaryRow) {
                    for (Map.Entry<String, List<String>> entry : rowBlock.wrappedLinesByParam.entrySet()) {
                        if (entry.getKey() != null && entry.getValue() != null) {
                            paramLines.add(new Pair<>(entry.getKey(), new ArrayList<>(entry.getValue())));
                        }
                    }
                }

                curPageDate = rowBlock.timestamp;
                LocalDateTime ts = rowBlock.timestamp;
                boolean leadingDataBlock = rowBlock.leadingDataBlock;
                while (nLines > 0) {
                    int linesToAdd = Math.min(nLines, linesPerPage - curPageLines);
                    Ah2PageData.RowBlock newRowBlock = new Ah2PageData.RowBlock();
                    newRowBlock.timestamp = ts;
                    newRowBlock.isSummaryRow = rowBlock.isSummaryRow;
                    newRowBlock.startRow = curPageLines;
                    newRowBlock.totalRows = linesToAdd;
                    newRowBlock.leadingDataBlock = leadingDataBlock;

                    if (rowBlock.isSummaryRow) {
                        int from = summaryLines.size() - nLines;
                        newRowBlock.summary = new ArrayList<>(summaryLines.subList(from, from + linesToAdd));
                        if (summarySignLines != null && !summarySignLines.isEmpty()) {
                            newRowBlock.wrappedLinesByParam.put(AH2P_SIGNATURE,
                                new ArrayList<>(summarySignLines.subList(from, from + linesToAdd)));
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
                                newWrappedLinesByParam.put(paramCode, new ArrayList<>(wrappedLines.subList(0, linesToAdd)));
                                remainingParamLines.add(new Pair<>(
                                    paramCode, new ArrayList<>(wrappedLines.subList(linesToAdd, wrappedLines.size()))
                                ));
                            }
                        }
                        newRowBlock.wrappedLinesByParam = newWrappedLinesByParam;
                        paramLines = remainingParamLines;
                    }
                    curPage.rowBlocks.add(newRowBlock);

                    curPageLines += linesToAdd;
                    if (curPageLines >= linesPerPage) {
                        LocalDateTime curPageDateLocal = TimeUtils.getLocalDateTimeFromUtc(curPageDate, ZONE_ID);
                        curPage.yearStr = curPageDateLocal == null ? "" : String.valueOf(curPageDateLocal.getYear());
                        curPage.pageEndTs = curPageDate;
                        pageDataList.add(curPage);
                        curPage = new Ah2PageData();
                        curPage.pageNumber = normalizedStartPageNumber + pageDataList.size();
                        curPage.pageStartTs = curPageDate;
                        curPageLines = 0;
                    }

                    nLines -= linesToAdd;
                    leadingDataBlock = false;
                }
            }
        }

        if (curPage.rowBlocks != null && !curPage.rowBlocks.isEmpty()) {
            LocalDateTime curPageDateLocal = TimeUtils.getLocalDateTimeFromUtc(curPageDate, ZONE_ID);
            curPage.yearStr = curPageDateLocal == null ? "" : String.valueOf(curPageDateLocal.getYear());
            curPage.pageEndTs = curPageDate;
            pageDataList.add(curPage);
        }
        return pageDataList;
    }

    private void setRawRecord(
        Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket,
        String mpCode, String paramCode, int rowIdx
    ) {
        putCell(ctx, row, paramCode, getRecordString(ctx, bucket, mpCode, rowIdx));
    }

    private void setMappedRecord(
        Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket,
        String mpCode, String paramCode, int rowIdx, Map<String, String> valueMap
    ) {
        String raw = getRecordString(ctx, bucket, mpCode, rowIdx);
        if (StrUtils.isBlank(raw)) return;
        String mapped = mapWithFallback(valueMap, raw, mpCode);
        putCell(ctx, row, paramCode, mapped);
    }

    private void setCombined(Ah2PdfContext ctx, Map<String, List<String>> row, String paramCode, String left, String right) {
        if (StrUtils.isBlank(left) && StrUtils.isBlank(right)) return;
        putCell(ctx, row, paramCode, (StrUtils.isBlank(left) ? "-" : left) + "/" + (StrUtils.isBlank(right) ? "-" : right));
    }

    private void setPeepPsv(Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket, int rowIdx) {
        setCombined(ctx, row, XN_VENT_PEEP_PSV,
            getRecordString(ctx, bucket, MP_PEEP, rowIdx),
            getRecordString(ctx, bucket, MP_PS, rowIdx));
    }

    private void setRestraint(Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket, int rowIdx) {
        String status = getRecordString(ctx, bucket, MP_RESTRAINT_STATUS, rowIdx);
        String mappedRestraint = getMappedMultiSelectRecordString(ctx, bucket, MP_RESTRAINT, rowIdx, RESTRAINT_MAP);
        String mappedStatus = StrUtils.isBlank(status) ? "" : mapWithFallback(RESTRAINT_STATUS_MAP, status, MP_RESTRAINT_STATUS);
        setCombined(ctx, row, XN_RESTRAINT, mappedRestraint, mappedStatus);
    }

    private void setOtherNursing(Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket, int rowIdx) {
        putCell(
            ctx, row, XN_OTHER_NURSING,
            getMappedMultiSelectRecordString(ctx, bucket, MP_NURSING_ACTIONS, rowIdx, NURSING_ACTION_MAP)
        );
    }

    private String getRecordString(Ah2PdfContext ctx, MinuteBucket bucket, String mpCode, int rowIdx) {
        List<PatientMonitoringRecord> records = bucket.recordsByCode.get(mpCode);
        if (records == null || rowIdx >= records.size()) return "";
        return getRecordString(ctx, records.get(rowIdx));
    }

    private String getRecordString(Ah2PdfContext ctx, PatientMonitoringRecord record) {
        if (record == null) return "";
        if (!StrUtils.isBlank(record.getParamValueStr())) return record.getParamValueStr().trim();
        MonitoringValuePB mvPb = ProtoUtils.decodeMonitoringValue(record.getParamValue());
        if (mvPb == null) return "";
        ValueMetaPB valueMeta = ctx.mpVmMap == null ? null : ctx.mpVmMap.get(record.getMonitoringParamCode());
        if (valueMeta == null) return "";
        return ValueMetaUtils.extractAndFormatParamValue(mvPb.getValue(), mvPb.getValuesList(), valueMeta);
    }

    private String getMappedMultiSelectRecordString(
        Ah2PdfContext ctx, MinuteBucket bucket, String mpCode, int rowIdx, Map<String, String> valueMap
    ) {
        List<PatientMonitoringRecord> records = bucket.recordsByCode.get(mpCode);
        if (records == null || rowIdx >= records.size()) return "";
        PatientMonitoringRecord record = records.get(rowIdx);
        if (record == null) return "";

        MonitoringValuePB mvPb = ProtoUtils.decodeMonitoringValue(record.getParamValue());
        ValueMetaPB valueMeta = ctx.mpVmMap == null ? null : ctx.mpVmMap.get(mpCode);
        if (mvPb == null) {
            return mapDelimitedValues(valueMap, record.getParamValueStr(), mpCode);
        }

        List<GenericValuePB> values = mvPb.getValuesList();
        if (values != null && !values.isEmpty()) {
            List<String> mappedValues = new ArrayList<>();
            for (GenericValuePB value : values) {
                String raw = getGenericValueString(value, valueMeta);
                if (StrUtils.isBlank(raw)) continue;
                mappedValues.add(mapWithFallback(valueMap, raw, mpCode));
            }
            return String.join(",", mappedValues);
        }

        String raw = getGenericValueString(mvPb.getValue(), valueMeta);
        if (StrUtils.isBlank(raw)) raw = record.getParamValueStr();
        return mapDelimitedValues(valueMap, raw, mpCode);
    }

    private String getGenericValueString(GenericValuePB value, ValueMetaPB valueMeta) {
        if (value == null) return "";
        if (valueMeta != null) return ValueMetaUtils.extractAndFormatParamValue(value, valueMeta);
        if (!StrUtils.isBlank(value.getStrVal())) return value.getStrVal();
        if (value.getInt32Val() != 0) return String.valueOf(value.getInt32Val());
        if (value.getInt64Val() != 0L) return String.valueOf(value.getInt64Val());
        if (value.getFloatVal() != 0f) return String.valueOf(value.getFloatVal());
        if (value.getDoubleVal() != 0d) return String.valueOf(value.getDoubleVal());
        if (value.getBoolVal()) return String.valueOf(true);
        return "";
    }

    private String mapDelimitedValues(Map<String, String> valueMap, String raw, String codeForLog) {
        if (StrUtils.isBlank(raw)) return "";
        List<String> mappedValues = new ArrayList<>();
        for (String item : raw.split(",")) {
            if (StrUtils.isBlank(item)) continue;
            mappedValues.add(mapWithFallback(valueMap, item, codeForLog));
        }
        return String.join(",", mappedValues);
    }

    private void putCell(Ah2PdfContext ctx, Map<String, List<String>> row, String paramCode, String value) {
        if (StrUtils.isBlank(value)) return;
        ParamColMetaPB colMeta = ctx.colMetaMap.get(paramCode);
        if (colMeta == null) return;
        List<String> lines = wrap(ctx, colMeta, value);
        if (!lines.isEmpty()) row.put(paramCode, lines);
    }

    private List<String> wrap(Ah2PdfContext ctx, ParamColMetaPB colMeta, String value) {
        if (StrUtils.isBlank(value)) return new ArrayList<>();
        try {
            return JfkPdfUtils.getWrappedLines(
                ctx.font, ctx.tblTxtStyle.getFontSize(), getBodyTextWidth(ctx, colMeta.getWidth()),
                ctx.tblTxtStyle.getCharSpacing(), new ArrayList<>(List.of(value))
            );
        } catch (Exception e) {
            log.warn("Failed to wrap Xiuning cell param={}, value={}: {}", colMeta.getParamCode(), value, e.getMessage());
            return new ArrayList<>(List.of(value));
        }
    }

    private float getBodyTextWidth(Ah2PdfContext ctx, float colWidth) {
        if (ctx == null || ctx.tblTxtStyle == null) return colWidth;
        float padding = Math.max(0f, ctx.tblTxtStyle.getPadding());
        return padding > 0f && colWidth > 2f * padding ? colWidth - 2f * padding : colWidth;
    }

    private String mapWithFallback(Map<String, String> valueMap, String raw, String codeForLog) {
        if (StrUtils.isBlank(raw)) return "";
        String trimmed = raw.trim();
        String mapped = valueMap.get(trimmed);
        if (mapped == null) {
            String normalized = trimmed.replaceAll("\\s+", "");
            if (!normalized.equals(trimmed)) mapped = valueMap.get(normalized);
        }
        if (mapped == null) {
            log.warn("Xiuning AH2 enum value not mapped: code={}, value={}", codeForLog, trimmed);
            return trimmed;
        }
        return mapped;
    }

    private String mapStrict(Map<String, String> valueMap, String raw) {
        if (StrUtils.isBlank(raw)) return "";
        return valueMap.getOrDefault(raw.trim(), "");
    }

    private String getStatus(TubeStatusGroup group, String statusName) {
        TubeStatusValue value = group.statusMap.get(statusName);
        return value == null ? "" : value.value;
    }

    private int normalizeHalfDayShiftHours(Ah2PdfContext ctx) {
        int hours = ctx == null ? 12 : ctx.halfDayShiftHours;
        return hours > 0 && hours < 24 ? hours : 12;
    }

    private int resolveHalfDayShiftHours(String deptId, LocalDateTime shiftStartUtc) {
        if (StrUtils.isBlank(deptId) || shiftStartUtc == null) return 12;
        LocalDateTime shiftStartLocal = TimeUtils.getLocalDateTimeFromUtc(shiftStartUtc, ZONE_ID);
        LocalDateTime effectiveLocal = TimeUtils.truncateToDay(shiftStartLocal);
        LocalDateTime effectiveUtc = TimeUtils.getUtcFromLocalDateTime(effectiveLocal, ZONE_ID);
        if (effectiveUtc == null) return 12;

        BalanceStatsShift shift = balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                deptId, effectiveUtc.plusSeconds(1)
            )
            .orElse(null);
        Integer hours = shift == null ? null : shift.getHalfDayShiftHours();
        return hours != null && hours > 0 && hours < 24 ? hours : 12;
    }

    private String getShiftNurseIdStr(Ah2PdfContext ctx, LocalDateTime ts) {
        if (ctx == null || ctx.shiftNurses == null || ts == null) return null;
        for (Pair<Pair<LocalDateTime, LocalDateTime>, Long> shift : ctx.shiftNurses) {
            LocalDateTime start = shift.getFirst().getFirst();
            LocalDateTime end = shift.getFirst().getSecond();
            Long accountId = shift.getSecond();
            if (start == null || end == null) continue;
            if (ts.isAfter(start) && !ts.isAfter(end)) {
                return accountId == null ? null : accountId.toString();
            }
        }
        return null;
    }

    private Long getAccountPk(String accountId) {
        if (StrUtils.isBlank(accountId)) return null;
        if (accountIdToPk != null && accountIdToPk.containsKey(accountId)) {
            return accountIdToPk.get(accountId);
        }
        Long parsed = StrUtils.parseLongOrDefault(accountId, 0);
        return parsed > 0 ? parsed : null;
    }

    private LocalDateTime truncateToMinute(LocalDateTime time) {
        return time == null ? null : time.truncatedTo(ChronoUnit.MINUTES);
    }

    private int compareRows(Ah2PageData.RowBlock a, Ah2PageData.RowBlock b) {
        int tsCompare = Comparator.<LocalDateTime>nullsLast(Comparator.naturalOrder()).compare(a.timestamp, b.timestamp);
        if (tsCompare != 0) return tsCompare;
        return Boolean.compare(a.isSummaryRow, b.isSummaryRow);
    }

    private int compareNullableLong(Long a, Long b) {
        if (a == null && b == null) return 0;
        if (a == null) return -1;
        if (b == null) return 1;
        return Long.compare(a, b);
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static class MinuteBucket {
        MinuteBucket(LocalDateTime minute) {
            this.minute = minute;
        }
        LocalDateTime minute;
        Map<String, List<PatientMonitoringRecord>> recordsByCode = new HashMap<>();
        List<TubeEntry> tubeEntries = new ArrayList<>();
        List<NursingRecord> nursingRecords = new ArrayList<>();
    }

    private enum TubeCategory {
        DRAINAGE,
        VASCULAR
    }

    private static class TubeEntry {
        TubeEntry(TubeCategory category, Long tubeRecordId, LocalDateTime minute) {
            this.category = category;
            this.tubeRecordId = tubeRecordId;
            this.minute = minute;
        }
        TubeCategory category;
        Long tubeRecordId;
        LocalDateTime minute;
        String nameCode;
        String depth;
        String colorCode;
        String nursingCode;
    }

    private static class TubeStatusKey {
        TubeStatusKey(Long tubeRecordId, LocalDateTime minute) {
            this.tubeRecordId = tubeRecordId;
            this.minute = minute;
        }
        Long tubeRecordId;
        LocalDateTime minute;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TubeStatusKey that)) return false;
            return Objects.equals(tubeRecordId, that.tubeRecordId) && Objects.equals(minute, that.minute);
        }

        @Override
        public int hashCode() {
            return Objects.hash(tubeRecordId, minute);
        }
    }

    private static class TubeStatusGroup {
        TubeStatusGroup(Long tubeRecordId, LocalDateTime minute) {
            this.tubeRecordId = tubeRecordId;
            this.minute = minute;
        }
        Long tubeRecordId;
        LocalDateTime minute;
        String tubeName;
        Map<String, TubeStatusValue> statusMap = new HashMap<>();
    }

    private static class TubeStatusValue {
        TubeStatusValue(String value, Long statusRecordId) {
            this.value = value;
            this.statusRecordId = statusRecordId;
        }
        String value;
        Long statusRecordId;
    }

    private final String ZONE_ID;
    private final List<String> statusCodeMsgs;
    private final Integer BALANCE_GROUP_TYPE_ID;
    private final ConfigShiftUtils configShiftUtils;
    private final PatientService patientService;
    private final MonitoringConfig monitoringConfig;
    private final PatientMonitoringService patientMonitoringService;
    private final BalanceCalculator balanceCalculator;
    private final PatientTubeImpl patientTubeImpl;
    private final PatientMonitoringRecordRepository pmrRepo;
    private final NursingRecordRepository nrRepo;
    private final PatientShiftRecordRepository psrRepo;
    private final PatientTubeRecordRepository ptrRepo;
    private final PatientTubeStatusRecordRepository ptsrRepo;
    private final PatientTubeAttrRepository ptaRepo;
    private final TubeTypeAttributeRepository ttaRepo;
    private final AccountRepository accountRepo;
    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
    private Map<String, Long> accountIdToPk;

    public static final String XN_CONSCIOUSNESS = "XN_CONSCIOUSNESS";
    public static final String XN_LEFT_PUPIL_SIZE = "XN_LEFT_PUPIL_SIZE";
    public static final String XN_LEFT_PUPIL_REFLEX = "XN_LEFT_PUPIL_REFLEX";
    public static final String XN_RIGHT_PUPIL_SIZE = "XN_RIGHT_PUPIL_SIZE";
    public static final String XN_RIGHT_PUPIL_REFLEX = "XN_RIGHT_PUPIL_REFLEX";
    public static final String XN_TEMPERATURE = "XN_TEMPERATURE";
    public static final String XN_HR = "XN_HR";
    public static final String XN_RESPIRATORY_RATE = "XN_RESPIRATORY_RATE";
    public static final String XN_NIBP = "XN_NIBP";
    public static final String XN_SPO2 = "XN_SPO2";
    public static final String XN_IBP = "XN_IBP";
    public static final String XN_CVP = "XN_CVP";
    public static final String XN_OXYGEN_DELIVERY_METHOD = "XN_OXYGEN_DELIVERY_METHOD";
    public static final String XN_OXYGEN_FLOW_RATE = "XN_OXYGEN_FLOW_RATE";
    public static final String XN_OXYGEN_CONCENTRATION = "XN_OXYGEN_CONCENTRATION";
    public static final String XN_ARTIFICIAL_AIRWAY_METHOD = "XN_ARTIFICIAL_AIRWAY_METHOD";
    public static final String XN_VENT_TUBE_PLANT_DEPTH = "XN_VENT_TUBE_PLANT_DEPTH";
    public static final String XN_VENT_RESPIRATORY_MODE = "XN_VENT_RESPIRATORY_MODE";
    public static final String XN_VENT_TIDAL_VOLUME = "XN_VENT_TIDAL_VOLUME";
    public static final String XN_VENT_RESPIRATORY_RATE = "XN_VENT_RESPIRATORY_RATE";
    public static final String XN_VENT_FIO2 = "XN_VENT_FIO2";
    public static final String XN_VENT_PEEP_PSV = "XN_VENT_PEEP_PSV";
    public static final String XN_VENT_CUFF_PRESSURE = "XN_VENT_CUFF_PRESSURE";
    public static final String XN_DRAINAGE_TUBE_NAME = "XN_DRAINAGE_TUBE_NAME";
    public static final String XN_DRAINAGE_TUBE_DEPTH = "XN_DRAINAGE_TUBE_DEPTH";
    public static final String XN_DRAINAGE_TUBE_COLOR = "XN_DRAINAGE_TUBE_COLOR";
    public static final String XN_DRAINAGE_TUBE_NURSING = "XN_DRAINAGE_TUBE_NURSING";
    public static final String XN_VASCULAR_TUBE_NAME = "XN_VASCULAR_TUBE_NAME";
    public static final String XN_VASCULAR_TUBE_DEPTH = "XN_VASCULAR_TUBE_DEPTH";
    public static final String XN_VASCULAR_TUBE_NURSING = "XN_VASCULAR_TUBE_NURSING";
    public static final String XN_INTAKE_ITEM = "XN_INTAKE_ITEM";
    public static final String XN_INTAKE_USAGE = "XN_INTAKE_USAGE";
    public static final String XN_INTAKE_AMOUNT = "XN_INTAKE_AMOUNT";
    public static final String XN_OUTPUT_ITEM = "XN_OUTPUT_ITEM";
    public static final String XN_OUTPUT_USAGE = "XN_OUTPUT_USAGE";
    public static final String XN_OUTPUT_AMOUNT = "XN_OUTPUT_AMOUNT";
    public static final String XN_SUCTION = "XN_SUCTION";
    public static final String XN_RESTRAINT = "XN_RESTRAINT";
    public static final String XN_BACK_PERCUSSION = "XN_BACK_PERCUSSION";
    public static final String XN_SKIN_CARE = "XN_SKIN_CARE";
    public static final String XN_OTHER_NURSING = "XN_OTHER_NURSING";
    public static final String XN_BODY_POSITION = "XN_BODY_POSITION";

    private static final String MP_OXYGEN_FLOW_RATE_XN = "oxygen_flow_rate";
    private static final String MP_OXYGEN_CONCENTRATION_XN = "oxygen_concentration";
    private static final String MP_ARTIFICIAL_AIRWAY_METHOD = "artificial_airway_method";
    private static final String MP_VENT_TIDAL_VOLUME_XN = "vent_inspired_tidal_volume";
    private static final String MP_SUCTION_XN = "suction";
    private static final String MP_RESTRAINT_STATUS = "restraint_status";
    private static final String MP_BACK_PERCUSSION = "back_percussion";
    private static final String MP_NURSING_ACTIONS = "nursing_actions";

    private static final Map<String, String> CONSCIOUSNESS_MAP = Map.of(
        "清醒", "1", "嗜睡", "2", "意识模糊", "3", "昏睡", "4", "浅昏迷", "5", "深昏迷", "6", "镇静", "7"
    );
    private static final Map<String, String> OXYGEN_METHOD_MAP = Map.of(
        "鼻塞", "1", "鼻导管", "2", "面罩", "3", "气管插管", "4", "气切套管", "5", "高流量治疗", "6", "无创通气", "7"
    );
    private static final Map<String, String> ARTIFICIAL_AIRWAY_MAP = Map.of(
        "经口插管", "1", "经鼻插管", "2", "气切插管", "3"
    );
    private static final Map<String, String> VENT_MODE_MAP = Map.of(
        "V-C", "1", "P-C", "2", "V-SIMV", "3", "P-SIMV", "4", "PSV", "5", "BIRAP", "6", "BiPAP", "6"
    );
    private static final Map<String, String> DRAINAGE_TUBE_NAME_MAP = Map.of(
        "导尿管", "1", "胃管", "2", "头部引流管", "3", "胸管", "4", "腹部引流管", "5", "切口管", "6", "空肠管", "7"
    );
    private static final Map<String, String> DRAINAGE_COLOR_MAP = Map.of(
        "血性", "1", "褐色", "2", "黄色", "3", "酱油色", "4", "浓茶色", "5", "淡黄色", "6", "深黄色", "7"
    );
    private static final Map<String, String> TUBE_NURSING_MAP = Map.of(
        "通畅/妥善固定", "1a", "通畅/教育告知", "1b", "通畅/挤压", "1c",
        "不畅/冲洗", "2a", "不畅/拔管", "2b", "不畅/更换", "2c"
    );
    private static final Map<String, String> CENTRAL_VENOUS_BODY_PART_MAP = Map.of(
        "颈内", "1a", "锁骨下", "1b", "股静脉", "1c"
    );
    private static final Map<String, String> RESTRAINT_MAP = Map.of(
        "上肢", "1", "下肢", "2", "上下肢", "3", "胸部", "4"
    );
    private static final Map<String, String> RESTRAINT_STATUS_MAP = Map.of(
        "良好", "1", "青紫", "2", "破损", "3", "骨折", "4"
    );
    private static final Map<String, String> BODY_POSITION_MAP = Map.of(
        "平卧", "1", "左侧卧位", "2.a", "右侧卧位", "2.b", "半卧", "3", "俯卧位", "4"
    );
    private static final Map<String, String> SKIN_CARE_MAP = Map.of(
        "皮肤完整/气垫床", "1a",
        "皮肤完整/温水擦浴", "1b",
        "皮肤完整/保护膜应用", "1c",
        "皮肤不完整/贴膜", "2a",
        "皮肤不完整/伤口处理", "2b"
    );
    private static final Map<String, String> NURSING_ACTION_MAP = new LinkedHashMap<>() {{
        put("口腔护理", "1");
        put("会阴护理", "2");
        put("面部清洁和梳头", "3");
        put("床上擦洗", "4");
        put("床上洗头", "5");
        put("足部清洁", "6");
        put("指甲护理", "7");
        put("协助进水进食", "8");
        put("擦背", "9");
        put("更衣", "10");
        put("床上使用便器", "11");
        put("失禁护理", "12");
    }};
}
