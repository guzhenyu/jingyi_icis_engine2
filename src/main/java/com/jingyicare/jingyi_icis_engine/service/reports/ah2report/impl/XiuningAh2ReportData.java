package com.jingyicare.jingyi_icis_engine.service.reports.ah2report.impl;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecord;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecordDetail;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.entity.patientshifts.PatientShiftRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.scores.PatientScore;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportAh2.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientBgaRecordDetailRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientBgaRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.NursingRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patientshifts.PatientShiftRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.scores.PatientScoreRepository;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.medications.*;
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
        @Autowired PatientScoreRepository psRepo,
        @Autowired PatientTubeRecordRepository ptrRepo,
        @Autowired PatientTubeStatusRecordRepository ptsrRepo,
        @Autowired PatientTubeAttrRepository ptaRepo,
        @Autowired TubeTypeAttributeRepository ttaRepo,
        @Autowired AccountRepository accountRepo,
        @Autowired BalanceStatsShiftRepository balanceStatsShiftRepo,
        @Autowired MedicationExecutionRecordRepository medExeRecordRepo,
        @Autowired MedicationOrderGroupRepository medOrderGroupRepo,
        @Autowired AdministrationRouteRepository routeRepo,
        @Autowired MedMonitoringService medMonitoringService,
        @Autowired PatientBgaRecordRepository pbgarRepo,
        @Autowired PatientBgaRecordDetailRepository pbgardRepo,
        @Autowired MedicationConfig medConfig
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.BALANCE_GROUP_TYPE_ID = monitoringConfig.getBalanceGroupTypeId();
        this.medOrderGroupSettingsPb = protoService.getConfig().getMedication().getOrderGroupSettings();
        this.configShiftUtils = configShiftUtils;
        this.patientService = patientService;
        this.monitoringConfig = monitoringConfig;
        this.patientMonitoringService = patientMonitoringService;
        this.balanceCalculator = balanceCalculator;
        this.patientTubeImpl = patientTubeImpl;
        this.pmrRepo = pmrRepo;
        this.nrRepo = nrRepo;
        this.psrRepo = psrRepo;
        this.psRepo = psRepo;
        this.ptrRepo = ptrRepo;
        this.ptsrRepo = ptsrRepo;
        this.ptaRepo = ptaRepo;
        this.ttaRepo = ttaRepo;
        this.accountRepo = accountRepo;
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
        this.medExeRecordRepo = medExeRecordRepo;
        this.medOrderGroupRepo = medOrderGroupRepo;
        this.routeRepo = routeRepo;
        this.medMonitoringService = medMonitoringService;
        this.pbgarRepo = pbgarRepo;
        this.pbgardRepo = pbgardRepo;
        this.medConfig = medConfig;
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
        Map<String, AdministrationRoute> routeMap = getRouteMap(deptId);
        Map<String, Integer> routeIntakeTypeMap = routeMap.entrySet().stream()
            .filter(e -> e.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getIntakeTypeId()));
        Map<String, String> routeNameMap = routeMap.entrySet().stream()
            .filter(e -> e.getValue() != null)
            .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue().getName()));

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
            dailyData.rowBlocks.addAll(collectRows(
                ctx, pid, deptId, shiftStartUtc, shiftEndUtc, accountId, routeIntakeTypeMap, routeNameMap
            ));
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
        Ah2PdfContext ctx, Long pid, String deptId, LocalDateTime startUtc, LocalDateTime endUtc,
        String accountId, Map<String, Integer> routeIntakeTypeMap, Map<String, String> routeNameMap
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
        collectBgaRows(buckets, pid, startUtc, endUtc);
        collectScoreRows(buckets, pid, startUtc, endUtc);

        TubeReportContext tubeCtx = loadTubeReportContext(pid, startUtc, endUtc);
        List<TubeEntry> tubeEntries = collectTubeEntries(tubeCtx, startUtc, endUtc);
        for (TubeEntry entry : tubeEntries) {
            buckets.computeIfAbsent(entry.minute, MinuteBucket::new).tubeEntries.add(entry);
        }
        collectBalanceRows(ctx, buckets, pmrs, tubeCtx, endUtc);
        collectMedicationIntakeRows(buckets, pid, startUtc, endUtc, routeIntakeTypeMap, routeNameMap);

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
            List<Map<String, List<String>>> balanceRows = buildBalanceRows(ctx, bucket);
            List<Map<String, List<String>>> nursingRows = buildNursingRows(ctx, bucket);
            int rowCount = Math.max(
                Math.max(observationRows.size(), tubeRows.size()),
                Math.max(balanceRows.size(), nursingRows.size())
            );
            for (int i = 0; i < rowCount; i++) {
                Map<String, List<String>> lines = new HashMap<>();
                if (i < observationRows.size()) lines.putAll(observationRows.get(i));
                if (i < tubeRows.size()) lines.putAll(tubeRows.get(i));
                if (i < balanceRows.size()) lines.putAll(balanceRows.get(i));
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

    private void collectBgaRows(
        Map<LocalDateTime, MinuteBucket> buckets, Long pid, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        List<PatientBgaRecord> bgaRecords = pbgarRepo
            .findByPidAndEffectiveTimeGreaterThanEqualAndEffectiveTimeLessThanAndIsDeletedFalseOrderByEffectiveTimeAsc(
                pid, startUtc, endUtc
            );
        if (bgaRecords == null || bgaRecords.isEmpty()) return;

        List<Long> bgaIds = bgaRecords.stream()
            .map(PatientBgaRecord::getId)
            .filter(Objects::nonNull)
            .toList();
        if (bgaIds.isEmpty()) return;

        Map<Long, List<PatientBgaRecordDetail>> bgaDetailsMap = pbgardRepo.findByRecordIdInAndIsDeletedFalse(bgaIds)
            .stream()
            .sorted(Comparator.comparing(PatientBgaRecordDetail::getId, Comparator.nullsLast(Long::compareTo)))
            .collect(Collectors.groupingBy(PatientBgaRecordDetail::getRecordId));

        for (PatientBgaRecord bgaRecord : bgaRecords) {
            if (bgaRecord.getEffectiveTime() == null) continue;
            List<PatientBgaRecordDetail> details = bgaDetailsMap.get(bgaRecord.getId());
            if (details == null || details.isEmpty()) continue;
            MinuteBucket bucket = buckets.computeIfAbsent(truncateToMinute(bgaRecord.getEffectiveTime()), MinuteBucket::new);
            for (PatientBgaRecordDetail detail : details) {
                if (detail == null ||
                    StrUtils.isBlank(detail.getMonitoringParamCode()) ||
                    StrUtils.isBlank(detail.getParamValue())
                ) {
                    continue;
                }
                GenericValuePB value = ProtoUtils.decodeGenericValue(detail.getParamValue());
                if (value == null) continue;
                bucket.bgaValuesByCode.computeIfAbsent(detail.getMonitoringParamCode(), k -> new ArrayList<>())
                    .add(value);
            }
        }
    }

    private void collectScoreRows(
        Map<LocalDateTime, MinuteBucket> buckets, Long pid, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        List<PatientScore> scores = psRepo.findByPidAndEffectiveTimeBetweenAndIsDeletedFalse(pid, startUtc, endUtc);
        if (scores == null || scores.isEmpty()) return;
        scores.sort(Comparator
            .comparing(PatientScore::getEffectiveTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(PatientScore::getScoreGroupCode, Comparator.nullsLast(String::compareTo))
            .thenComparing(PatientScore::getId, Comparator.nullsLast(Long::compareTo)));

        for (PatientScore score : scores) {
            if (score == null || score.getEffectiveTime() == null ||
                score.getEffectiveTime().isBefore(startUtc) || !score.getEffectiveTime().isBefore(endUtc) ||
                StrUtils.isBlank(score.getScoreGroupCode()) || StrUtils.isBlank(score.getScoreStr())
            ) {
                continue;
            }
            String scoreStr = score.getScoreStr().trim();
            if (scoreStr.endsWith("分")) {
                scoreStr = scoreStr.substring(0, scoreStr.length() - 1);
            }
            if (StrUtils.isBlank(scoreStr)) continue;
            buckets.computeIfAbsent(truncateToMinute(score.getEffectiveTime()), MinuteBucket::new)
                .scoresByCode.put(score.getScoreGroupCode(), scoreStr);
        }
    }

    private List<Map<String, List<String>>> buildObservationRows(Ah2PdfContext ctx, MinuteBucket bucket) {
        int rowCount = Math.max(
            Math.max(
                bucket.recordsByCode.values().stream().mapToInt(List::size).max().orElse(0),
                bucket.bgaValuesByCode.values().stream().mapToInt(List::size).max().orElse(0)
            ),
            bucket.scoresByCode.isEmpty() ? 0 : 1
        );
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
            setRawRecord(ctx, row, bucket, MP_SPUTUM_AMOUNT, XN_SPUTUM_AMOUNT, rowIdx);
            setRawRecord(ctx, row, bucket, MP_SPUTUM_COLOR, XN_SPUTUM_COLOR, rowIdx);
            setRawRecord(ctx, row, bucket, MP_SPUTUM_CONSISTENCY, XN_SPUTUM_CONSISTENCY, rowIdx);
            setRestraint(ctx, row, bucket, rowIdx);
            setRawRecord(ctx, row, bucket, MP_BACK_PERCUSSION, XN_BACK_PERCUSSION, rowIdx);
            setMappedRecord(ctx, row, bucket, MP_SKIN_CARE, XN_SKIN_CARE, rowIdx, SKIN_CARE_MAP);
            setOtherNursing(ctx, row, bucket, rowIdx);
            setMappedRecord(ctx, row, bucket, MP_BODY_POSITION, XN_BODY_POSITION, rowIdx, BODY_POSITION_MAP);
            setBgaRecord(ctx, row, bucket, BGA_PH, AH2P_BGA_PH, rowIdx);
            setBgaRecord(ctx, row, bucket, BGA_PCO2, AH2P_BGA_PCO2, rowIdx);
            setBgaRecord(ctx, row, bucket, BGA_PO2, AH2P_BGA_PO2, rowIdx);
            setBgaRecord(ctx, row, bucket, BGA_SO2, AH2P_BGA_SPO2, rowIdx);
            setBgaRecord(ctx, row, bucket, BGA_LAC, AH2P_BGA_LAC, rowIdx);
            if (rowIdx == 0) setScoreRecords(ctx, row, bucket);
            if (!row.isEmpty()) rows.add(row);
        }
        return rows;
    }

    private List<Map<String, List<String>>> buildTubeRows(Ah2PdfContext ctx, MinuteBucket bucket) {
        if (bucket.tubeEntries.isEmpty()) return new ArrayList<>();
        bucket.tubeEntries.sort(Comparator
            .comparing((TubeEntry e) -> e.category)
            .thenComparing(e -> e.tubeRecordId, Comparator.nullsLast(Long::compareTo))
            .thenComparing(e -> e.nameCode, Comparator.nullsLast(String::compareTo)));
        Map<String, List<String>> row = new HashMap<>();

        List<TubeEntry> drainageEntries = bucket.tubeEntries.stream()
            .filter(entry -> entry.category == TubeCategory.DRAINAGE)
            .toList();
        if (!drainageEntries.isEmpty()) {
            putJoinedCell(ctx, row, XN_DRAINAGE_TUBE_NAME, drainageEntries.stream().map(e -> e.nameCode).toList());
            putJoinedCell(ctx, row, XN_DRAINAGE_TUBE_DEPTH, drainageEntries.stream().map(e -> e.depth).toList());
            putJoinedCell(ctx, row, XN_DRAINAGE_TUBE_COLOR, drainageEntries.stream().map(e -> e.colorCode).toList());
            putNursingCell(ctx, row, XN_DRAINAGE_TUBE_NURSING, drainageEntries.stream().map(e -> e.nursingCode).toList());
        }

        List<TubeEntry> vascularEntries = bucket.tubeEntries.stream()
            .filter(entry -> entry.category == TubeCategory.VASCULAR)
            .toList();
        if (!vascularEntries.isEmpty()) {
            putJoinedCell(ctx, row, XN_VASCULAR_TUBE_NAME, vascularEntries.stream().map(e -> e.nameCode).toList());
            putJoinedCell(ctx, row, XN_VASCULAR_TUBE_DEPTH, vascularEntries.stream().map(e -> e.depth).toList());
            putNursingCell(ctx, row, XN_VASCULAR_TUBE_NURSING, vascularEntries.stream().map(e -> e.nursingCode).toList());
        }
        return row.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(row));
    }

    private List<Map<String, List<String>>> buildBalanceRows(Ah2PdfContext ctx, MinuteBucket bucket) {
        if ((bucket.intakeEntries == null || bucket.intakeEntries.isEmpty()) &&
            StrUtils.isBlank(bucket.intakeAmount) && bucket.outputEntry == null
        ) {
            return new ArrayList<>();
        }

        Map<String, List<String>> row = new HashMap<>();
        setIntakeCells(ctx, row, bucket);
        setOutputCells(ctx, row, bucket.outputEntry);
        return row.isEmpty() ? new ArrayList<>() : new ArrayList<>(List.of(row));
    }

    private void setIntakeCells(Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket) {
        if ((bucket.intakeEntries == null || bucket.intakeEntries.isEmpty()) && StrUtils.isBlank(bucket.intakeAmount)) {
            return;
        }

        ParamColMetaPB itemColMeta = ctx.colMetaMap.get(XN_INTAKE_ITEM);
        ParamColMetaPB usageColMeta = ctx.colMetaMap.get(XN_INTAKE_USAGE);
        List<String> itemLines = new ArrayList<>();
        List<String> usageLines = new ArrayList<>();
        if (itemColMeta != null) {
            bucket.intakeEntries.sort(Comparator
                .comparing((IntakeEntry entry) -> entry.recordId, Comparator.nullsLast(Long::compareTo))
                .thenComparing(entry -> entry.item, Comparator.nullsLast(String::compareTo)));
            for (IntakeEntry entry : bucket.intakeEntries) {
                List<String> wrapped = wrap(ctx, itemColMeta, entry.item);
                if (wrapped.isEmpty()) continue;
                itemLines.addAll(wrapped);
                if (usageColMeta != null) {
                    usageLines.add(trim(entry.usage));
                    for (int i = 1; i < wrapped.size(); i++) usageLines.add("");
                }
            }
        }
        if (!itemLines.isEmpty()) row.put(XN_INTAKE_ITEM, itemLines);
        if (!usageLines.isEmpty()) row.put(XN_INTAKE_USAGE, usageLines);

        if (!StrUtils.isBlank(bucket.intakeAmount) && ctx.colMetaMap.containsKey(XN_INTAKE_AMOUNT)) {
            List<String> amountLines = new ArrayList<>();
            int amountLineCount = Math.max(1, itemLines.size());
            amountLines.add(bucket.intakeAmount);
            for (int i = 1; i < amountLineCount; i++) amountLines.add("");
            row.put(XN_INTAKE_AMOUNT, amountLines);
        }
    }

    private void setOutputCells(Ah2PdfContext ctx, Map<String, List<String>> row, OutputEntry outputEntry) {
        if (outputEntry == null) return;
        putCell(ctx, row, XN_OUTPUT_ITEM, outputEntry.getItemText());
        putCell(ctx, row, XN_OUTPUT_USAGE, outputEntry.getUsageText());
        putCell(ctx, row, XN_OUTPUT_AMOUNT, outputEntry.amount);
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
            Map<String, SummaryAccumulator> tubeOutputSummaryMap = new LinkedHashMap<>();
            for (BalanceParamRecordPB bpr : bgs.getParamRecordList()) {
                String tubeSummaryName = getTubeOutputSummaryName(ctx, bpr.getParamCode());
                if (!StrUtils.isBlank(tubeSummaryName)) {
                    tubeOutputSummaryMap.computeIfAbsent(tubeSummaryName, SummaryAccumulator::new).add(bpr);
                    continue;
                }
                paramSummaries.add(bpr.getParamName() + ": " + bpr.getSumStr() + "ml");
            }
            for (SummaryAccumulator acc : tubeOutputSummaryMap.values()) {
                paramSummaries.add(acc.toSummaryText());
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

    private TubeReportContext loadTubeReportContext(Long pid, LocalDateTime startUtc, LocalDateTime endUtc) {
        TubeReportContext ctx = new TubeReportContext();
        ctx.tubeRecords = ptrRepo.findReportTubeRecords(pid, startUtc, endUtc).stream()
            .filter(r -> r != null && r.getId() != null)
            .sorted(Comparator.comparing(PatientTubeRecord::getId, Comparator.nullsLast(Long::compareTo)))
            .toList();
        List<Long> tubeRecordIds = ctx.tubeRecords.stream().map(PatientTubeRecord::getId).toList();
        ctx.centralBodyPartMap = getCentralVenousBodyPartMap(tubeRecordIds);

        LocalDateTime statusStartUtc = startUtc.minusHours(TUBE_STATS_INTERVAL_HOURS);
        for (PatientTubeStatusReportBrief brief : ptsrRepo.findPatientTubeStatusReportBrief(pid, statusStartUtc, endUtc)) {
            if (brief.getTubeRecordId() == null || brief.getRecordedAt() == null || StrUtils.isBlank(brief.getStatusName())) {
                continue;
            }
            ctx.statusByTubeRecordId.computeIfAbsent(brief.getTubeRecordId(), k -> new ArrayList<>()).add(brief);
        }
        for (List<PatientTubeStatusReportBrief> briefs : ctx.statusByTubeRecordId.values()) {
            briefs.sort(Comparator
                .comparing(PatientTubeStatusReportBrief::getRecordedAt, Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(PatientTubeStatusReportBrief::getStatusRecordId, Comparator.nullsLast(Long::compareTo)));
        }
        return ctx;
    }

    private List<TubeEntry> collectTubeEntries(
        TubeReportContext tubeCtx, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        List<TubeEntry> entries = new ArrayList<>();
        if (tubeCtx == null || tubeCtx.tubeRecords == null || tubeCtx.tubeRecords.isEmpty()) return entries;

        for (LocalDateTime statsTime = startUtc; statsTime.isBefore(endUtc);
             statsTime = statsTime.plusHours(TUBE_STATS_INTERVAL_HOURS)
        ) {
            LocalDateTime windowStartUtc = statsTime.minusHours(TUBE_STATS_INTERVAL_HOURS);
            for (PatientTubeRecord tubeRecord : tubeCtx.tubeRecords) {
                if (!isTubeActiveAt(tubeRecord, statsTime)) continue;
                TubeEntry entry = createTubeEntry(tubeCtx, tubeRecord, statsTime, windowStartUtc);
                if (entry != null) entries.add(entry);
            }
        }

        entries.sort(Comparator
            .comparing((TubeEntry e) -> e.minute)
            .thenComparing(e -> e.category)
            .thenComparing(e -> e.tubeRecordId, Comparator.nullsLast(Long::compareTo)));
        return entries;
    }

    private TubeEntry createTubeEntry(
        TubeReportContext tubeCtx, PatientTubeRecord tubeRecord,
        LocalDateTime statsTime, LocalDateTime windowStartUtc
    ) {
        String tubeName = trim(tubeRecord.getTubeName());
        TubeCategory category = null;
        String displayName = "";
        if (DRAINAGE_TUBE_NAMES.contains(tubeName)) {
            category = TubeCategory.DRAINAGE;
            displayName = tubeName;
        } else {
            displayName = mapVascularTubeName(tubeName, tubeCtx.centralBodyPartMap.get(tubeRecord.getId()));
            if (!StrUtils.isBlank(displayName)) category = TubeCategory.VASCULAR;
        }
        if (category == null || StrUtils.isBlank(displayName)) return null;

        List<PatientTubeStatusReportBrief> briefs = tubeCtx.statusByTubeRecordId.getOrDefault(
            tubeRecord.getId(), Collections.emptyList()
        );
        TubeEntry entry = new TubeEntry(category, tubeRecord.getId(), statsTime);
        entry.nameCode = displayName;
        entry.depth = getNearestStatus(briefs, TUBE_DEPTH_STATUS_NAME, windowStartUtc, statsTime);
        entry.nursingCode = getNearestStatus(briefs, "护理", windowStartUtc, statsTime);
        if (category == TubeCategory.DRAINAGE) {
            entry.colorCode = getNearestStatus(briefs, "颜色", windowStartUtc, statsTime);
        }
        return entry;
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
                    return meta != null && "身体部位".equals(trim(meta.getName()));
                })
                .min(Comparator
                    .comparing((PatientTubeAttr pta) -> {
                        TubeTypeAttribute meta = attrMetaMap.get(pta.getTubeAttrId());
                        return meta == null || meta.getDisplayOrder() == null ? Integer.MAX_VALUE : meta.getDisplayOrder();
                    })
                    .thenComparing(PatientTubeAttr::getId, Comparator.nullsLast(Long::compareTo)))
                .orElse(null);
            if (selected != null) {
                result.put(entry.getKey(), trim(selected.getValue()));
            }
        }
        return result;
    }

    private String mapVascularTubeName(String tubeName, String centralBodyPartCode) {
        if ("中心静脉导管".equals(tubeName)) {
            return StrUtils.isBlank(centralBodyPartCode) ? tubeName : (tubeName + '-' + centralBodyPartCode);
        }
        if (VASCULAR_TUBE_NAMES.contains(tubeName)) return tubeName;
        return "";
    }

    private boolean isTubeActiveAt(PatientTubeRecord tubeRecord, LocalDateTime statsTime) {
        if (tubeRecord == null || tubeRecord.getInsertedAt() == null || statsTime == null) return false;
        if (tubeRecord.getInsertedAt().isAfter(statsTime)) return false;
        return tubeRecord.getRemovedAt() == null || statsTime.isBefore(tubeRecord.getRemovedAt());
    }

    private String getNearestStatus(
        List<PatientTubeStatusReportBrief> briefs, String statusName,
        LocalDateTime windowStartUtc, LocalDateTime statsTime
    ) {
        if (briefs == null || briefs.isEmpty() || StrUtils.isBlank(statusName) ||
            windowStartUtc == null || statsTime == null
        ) {
            return "";
        }

        PatientTubeStatusReportBrief selected = null;
        for (PatientTubeStatusReportBrief brief : briefs) {
            if (brief == null || !statusName.equals(brief.getStatusName()) || brief.getRecordedAt() == null) {
                continue;
            }
            LocalDateTime recordedAt = brief.getRecordedAt();
            if (recordedAt.isBefore(windowStartUtc) || !recordedAt.isBefore(statsTime)) continue;
            if (selected == null ||
                recordedAt.isAfter(selected.getRecordedAt()) ||
                (recordedAt.equals(selected.getRecordedAt()) &&
                    compareNullableLong(brief.getStatusRecordId(), selected.getStatusRecordId()) >= 0)
            ) {
                selected = brief;
            }
        }
        return selected == null ? "" : trim(selected.getValue());
    }

    private void collectBalanceRows(
        Ah2PdfContext ctx, Map<LocalDateTime, MinuteBucket> buckets,
        List<PatientMonitoringRecord> pmrs, TubeReportContext tubeCtx, LocalDateTime endUtc
    ) {
        Map<LocalDateTime, String> hourlyIntakeByTime = new HashMap<>();
        Map<LocalDateTime, String> hourlyOutputByTime = new HashMap<>();
        Map<LocalDateTime, OutputEntry> outputByTime = new HashMap<>();

        for (PatientMonitoringRecord pmr : pmrs) {
            if (pmr == null || pmr.getEffectiveTime() == null || StrUtils.isBlank(pmr.getMonitoringParamCode())) continue;
            String paramCode = pmr.getMonitoringParamCode();
            if (!isXiuningBalanceParam(paramCode)) continue;

            LocalDateTime balanceTime = getBalanceDisplayTime(pmr.getEffectiveTime(), endUtc);
            if (balanceTime == null) continue;
            String value = getRecordString(ctx, pmr);

            if (MP_HOURLY_INTAKE.equals(paramCode)) {
                hourlyIntakeByTime.put(balanceTime, value);
                continue;
            }
            if (MP_HOURLY_OUTPUT.equals(paramCode)) {
                hourlyOutputByTime.put(balanceTime, value);
                continue;
            }

            OutputEntry outputEntry = outputByTime.computeIfAbsent(balanceTime, k -> new OutputEntry());
            if (paramCode.startsWith(TUBE_OUTPUT_PARAM_PREFIX)) {
                outputEntry.items.add(getMonitoringParamName(ctx, paramCode));
                continue;
            }

            if (MP_GASTRIC_FLUID_VOLUME.equals(paramCode)) {
                outputEntry.items.add("胃液");
                continue;
            }
            if (MP_CRRT_UF.equals(paramCode)) {
                outputEntry.items.add("超滤量");
                continue;
            }
            if (MP_STOOL_VOLUME.equals(paramCode)) {
                outputEntry.items.add("大便");
                if (!StrUtils.isBlank(value)) outputEntry.stoolVolume = value;
                continue;
            }
            if (MP_STOOL_CONSISTENCY.equals(paramCode)) {
                outputEntry.items.add("大便");
                if (!StrUtils.isBlank(value)) outputEntry.usages.add(value);
            }
        }

        for (Map.Entry<LocalDateTime, String> entry : hourlyIntakeByTime.entrySet()) {
            if (StrUtils.isBlank(entry.getValue())) continue;
            buckets.computeIfAbsent(entry.getKey(), MinuteBucket::new).intakeAmount = entry.getValue();
        }
        for (Map.Entry<LocalDateTime, OutputEntry> entry : outputByTime.entrySet()) {
            OutputEntry outputEntry = entry.getValue();
            outputEntry.amount = outputEntry.getAmountText(hourlyOutputByTime.get(entry.getKey()));
            if (!outputEntry.isEmpty()) {
                buckets.computeIfAbsent(entry.getKey(), MinuteBucket::new).outputEntry = outputEntry;
            }
        }
    }

    private boolean isXiuningBalanceParam(String paramCode) {
        return MP_HOURLY_INTAKE.equals(paramCode) ||
            MP_HOURLY_OUTPUT.equals(paramCode) ||
            paramCode.startsWith(TUBE_OUTPUT_PARAM_PREFIX) ||
            MP_GASTRIC_FLUID_VOLUME.equals(paramCode) ||
            MP_CRRT_UF.equals(paramCode) ||
            MP_STOOL_VOLUME.equals(paramCode) ||
            MP_STOOL_CONSISTENCY.equals(paramCode);
    }

    private LocalDateTime getBalanceDisplayTime(LocalDateTime effectiveTime, LocalDateTime endUtc) {
        if (effectiveTime == null) return null;
        if (effectiveTime.getMinute() != 0 || effectiveTime.getSecond() != 0 || effectiveTime.getNano() != 0) {
            log.warn("Xiuning AH2 non-hourly balance effective time: {}", effectiveTime);
            return null;
        }
        if (effectiveTime.equals(endUtc)) return null;
        return effectiveTime.plusHours(1);
    }

    private void collectMedicationIntakeRows(
        Map<LocalDateTime, MinuteBucket> buckets, Long pid, LocalDateTime startUtc, LocalDateTime endUtc,
        Map<String, Integer> routeIntakeTypeMap, Map<String, String> routeNameMap
    ) {
        List<MedicationExecutionRecord> records = medExeRecordRepo.findStartedRecordsByPatientId(pid, startUtc, endUtc);
        if (records == null || records.isEmpty()) return;

        Set<Long> orderGroupIds = records.stream()
            .map(MedicationExecutionRecord::getMedicationOrderGroupId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, MedicationOrderGroup> orderGroupMap = orderGroupIds.isEmpty() ? new HashMap<>() :
            medOrderGroupRepo.findByIds(new ArrayList<>(orderGroupIds)).stream()
                .filter(orderGroup -> orderGroup.getId() != null)
                .collect(Collectors.toMap(MedicationOrderGroup::getId, orderGroup -> orderGroup, (a, b) -> a));

        records.sort(Comparator
            .comparing(MedicationExecutionRecord::getStartTime, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(MedicationExecutionRecord::getId, Comparator.nullsLast(Long::compareTo)));
        for (MedicationExecutionRecord record : records) {
            if (record.getStartTime() == null) continue;
            MedicationOrderGroup orderGroup = orderGroupMap.get(record.getMedicationOrderGroupId());
            if (orderGroup == null) {
                log.warn("Xiuning AH2 medication intake skipped, missing orderGroup: recId={}, orderGroupId={}",
                    record.getId(), record.getMedicationOrderGroupId());
                continue;
            }

            MedicationDosageGroupPB dosageGroup = medMonitoringService.getDosageGroupPB(orderGroup, record);
            if (dosageGroup == null || dosageGroup.getMdCount() == 0) {
                log.warn("Xiuning AH2 medication intake skipped, missing dosageGroup: recId={}, orderGroupId={}",
                    record.getId(), record.getMedicationOrderGroupId());
                continue;
            }

            String routeCode = StrUtils.isBlank(record.getAdministrationRouteCode()) ?
                orderGroup.getAdministrationRouteCode() : record.getAdministrationRouteCode();
            String usage = mapIntakeUsage(routeIntakeTypeMap == null ? null : routeIntakeTypeMap.get(routeCode));
            String routeName = routeNameMap == null ? null : routeNameMap.get(routeCode);
            String item = buildIntakeItemText(dosageGroup, routeName);
            if (StrUtils.isBlank(item)) continue;

            IntakeEntry intakeEntry = new IntakeEntry();
            intakeEntry.recordId = record.getId();
            intakeEntry.item = item;
            intakeEntry.usage = usage;
            buckets.computeIfAbsent(truncateToMinute(record.getStartTime()), MinuteBucket::new)
                .intakeEntries.add(intakeEntry);
        }
    }

    private Map<String, AdministrationRoute> getRouteMap(String deptId) {
        if (StrUtils.isBlank(deptId)) return new HashMap<>();
        Map<String, AdministrationRoute> result = new HashMap<>();
        for (AdministrationRoute route : routeRepo.findByDeptId(deptId)) {
            if (route == null || StrUtils.isBlank(route.getCode()) || !Boolean.TRUE.equals(route.getIsValid())) continue;
            result.put(route.getCode(), route);
        }
        return result;
    }

    private String buildIntakeItemText(MedicationDosageGroupPB dosageGroup, String routeName) {
        String displayName = medConfig.getDosageGroupDisplayName(medOrderGroupSettingsPb, dosageGroup);
        if (StrUtils.isBlank(displayName)) return "";
        double intakeVolMl = dosageGroup.getMdList().stream()
            .mapToDouble(MedicationDosagePB::getIntakeVolMl)
            .sum();
        return displayName + (StrUtils.isBlank(routeName)? "" : ("("+ routeName + ")")) +
            "(液体总量 " + StrUtils.formatDouble(intakeVolMl, Consts.MED_ML_DECIMAL_PLACES) + " ml)";
    }

    private String mapIntakeUsage(Integer intakeTypeId) {
        if (intakeTypeId != null && (intakeTypeId == 1 || intakeTypeId == 2)) return "静脉";
        if (intakeTypeId != null && intakeTypeId == 3) return "胃肠";
        if (intakeTypeId != null && intakeTypeId == 4) return "雾化";
        return "其他";
    }

    private String getTubeOutputSummaryName(Ah2PdfContext ctx, String paramCode) {
        if (StrUtils.isBlank(paramCode) || !paramCode.startsWith(TUBE_OUTPUT_PARAM_PREFIX)) return "";
        return getMonitoringParamName(ctx, paramCode);
    }

    private String getMonitoringParamName(Ah2PdfContext ctx, String paramCode) {
        if (StrUtils.isBlank(paramCode)) return "";
        MonitoringParamPB param = ctx == null || ctx.mpMap == null ? null : ctx.mpMap.get(paramCode);
        if (param == null || StrUtils.isBlank(param.getName())) return paramCode;
        return param.getName();
    }

    private String formatSummaryMl(double value) {
        long rounded = Math.round(value);
        if (Math.abs(value - rounded) < 0.000001d) {
            return String.valueOf(rounded);
        }
        return StrUtils.formatDouble(value, Consts.MED_ML_DECIMAL_PLACES);
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

    private void setBgaRecord(
        Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket,
        String bgaCode, String paramCode, int rowIdx
    ) {
        putCell(ctx, row, paramCode, getBgaRecordString(ctx, bucket, bgaCode, rowIdx));
    }

    private void setScoreRecords(Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket) {
        putScore(ctx, row, bucket, PS_BRADEN, AH2P_BRADEN);
        putScore(ctx, row, bucket, PS_MORSE, AH2P_MORSE);
        putScore(ctx, row, bucket, PS_AODLS, AH2P_AODLS);
        putScore(ctx, row, bucket, PS_CATHETER_SLIPPAGE, AH2P_CATHETER_SLIPPAGE);
        putScore(ctx, row, bucket, PS_RASS, AH2P_RASS);
        putScore(ctx, row, bucket, PS_FRS_V2, AH2P_CPOT_NRS);
    }

    private void putScore(
        Ah2PdfContext ctx, Map<String, List<String>> row, MinuteBucket bucket, String scoreCode, String paramCode
    ) {
        if (bucket == null || bucket.scoresByCode == null) return;
        putCell(ctx, row, paramCode, bucket.scoresByCode.get(scoreCode));
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

    private String getBgaRecordString(Ah2PdfContext ctx, MinuteBucket bucket, String bgaCode, int rowIdx) {
        List<GenericValuePB> values = bucket.bgaValuesByCode.get(bgaCode);
        if (values == null || rowIdx >= values.size()) return "";
        ValueMetaPB valueMeta = ctx.mpVmMap == null ? null : ctx.mpVmMap.get(bgaCode);
        return getGenericValueString(values.get(rowIdx), valueMeta);
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

    private void putJoinedCell(Ah2PdfContext ctx, Map<String, List<String>> row, String paramCode, List<String> segments) {
        if (segments == null || segments.isEmpty()) return;
        List<String> normalizedSegments = segments.stream()
            .map(this::trim)
            .toList();
        boolean hasValue = normalizedSegments.stream().anyMatch(v -> !StrUtils.isBlank(v));
        if (!hasValue && normalizedSegments.size() <= 1) return;
        putCell(ctx, row, paramCode, String.join("/", normalizedSegments));
    }

    private void putNursingCell(Ah2PdfContext ctx, Map<String, List<String>> row, String paramCode, List<String> segments) {
        if (segments == null || segments.isEmpty()) return;
        List<String> normalizedSegments = segments.stream()
            .map(this::trim)
            .toList();
        LinkedHashSet<String> uniqueNonBlankValues = normalizedSegments.stream()
            .filter(v -> !StrUtils.isBlank(v))
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (uniqueNonBlankValues.isEmpty()) return;
        if (uniqueNonBlankValues.size() == 1) {
            putCell(ctx, row, paramCode, uniqueNonBlankValues.iterator().next());
            return;
        }
        putJoinedCell(ctx, row, paramCode, normalizedSegments);
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
        Map<String, List<GenericValuePB>> bgaValuesByCode = new HashMap<>();
        Map<String, String> scoresByCode = new HashMap<>();
        List<TubeEntry> tubeEntries = new ArrayList<>();
        List<IntakeEntry> intakeEntries = new ArrayList<>();
        String intakeAmount;
        OutputEntry outputEntry;
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

    private static class TubeReportContext {
        List<PatientTubeRecord> tubeRecords = new ArrayList<>();
        Map<Long, String> centralBodyPartMap = new HashMap<>();
        Map<Long, List<PatientTubeStatusReportBrief>> statusByTubeRecordId = new HashMap<>();
    }

    private static class IntakeEntry {
        Long recordId;
        String item;
        String usage;
    }

    private static class OutputEntry {
        LinkedHashSet<String> items = new LinkedHashSet<>();
        LinkedHashSet<String> usages = new LinkedHashSet<>();
        String amount;
        String stoolVolume;

        String getItemText() {
            if (!items.contains("大便") || items.size() <= 1) {
                return String.join("+", items);
            }
            List<String> orderedItems = items.stream()
                .filter(item -> !"大便".equals(item))
                .collect(Collectors.toCollection(ArrayList::new));
            orderedItems.add("大便");
            return String.join("+", orderedItems);
        }

        String getAmountText(String hourlyOutput) {
            boolean hasStool = items.contains("大便");
            boolean hasOtherItems = items.stream().anyMatch(item -> !"大便".equals(item));
            if (!hasStool) return hourlyOutput;
            if (!hasOtherItems) return stoolVolume;
            if (StrUtils.isBlank(hourlyOutput)) return stoolVolume;
            if (StrUtils.isBlank(stoolVolume)) return hourlyOutput;
            return hourlyOutput + "+" + stoolVolume;
        }

        String getUsageText() {
            return String.join("+", usages);
        }

        boolean isEmpty() {
            return items.isEmpty() && usages.isEmpty() && StrUtils.isBlank(amount);
        }
    }

    private class SummaryAccumulator {
        SummaryAccumulator(String name) {
            this.name = name;
        }
        String name;
        double sumMl;
        String singleSumStr;
        int count;

        void add(BalanceParamRecordPB record) {
            if (record == null) return;
            sumMl += record.getSumMl();
            singleSumStr = record.getSumStr();
            count++;
        }

        String toSummaryText() {
            String sumStr = count == 1 ? singleSumStr : formatSummaryMl(sumMl);
            return name + ": " + sumStr + "ml";
        }
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
    private final PatientScoreRepository psRepo;
    private final PatientTubeRecordRepository ptrRepo;
    private final PatientTubeStatusRecordRepository ptsrRepo;
    private final PatientTubeAttrRepository ptaRepo;
    private final TubeTypeAttributeRepository ttaRepo;
    private final AccountRepository accountRepo;
    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
    private final MedicationExecutionRecordRepository medExeRecordRepo;
    private final MedicationOrderGroupRepository medOrderGroupRepo;
    private final AdministrationRouteRepository routeRepo;
    private final MedMonitoringService medMonitoringService;
    private final PatientBgaRecordRepository pbgarRepo;
    private final PatientBgaRecordDetailRepository pbgardRepo;
    private final MedicationConfig medConfig;
    private final MedOrderGroupSettingsPB medOrderGroupSettingsPb;
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
    public static final String XN_SPUTUM_AMOUNT = "XN_SPUTUM_AMOUNT";
    public static final String XN_SPUTUM_COLOR = "XN_SPUTUM_COLOR";
    public static final String XN_SPUTUM_CONSISTENCY = "XN_SPUTUM_CONSISTENCY";
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
    private static final String MP_STOOL_CONSISTENCY = "stool_consistency";
    private static final String TUBE_OUTPUT_PARAM_PREFIX = "tube_ylg_";
    private static final int TUBE_STATS_INTERVAL_HOURS = 4;

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
    private static final Set<String> DRAINAGE_TUBE_NAMES = Set.of(
        "导尿管", "胃管", "头部引流管", "胸管", "腹部引流管", "切口管", "空肠管"
    );
    private static final Set<String> VASCULAR_TUBE_NAMES = Set.of(
        "中心静脉导管", "外周静脉针", "PICC管", "动脉导管", "中长导管"
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
