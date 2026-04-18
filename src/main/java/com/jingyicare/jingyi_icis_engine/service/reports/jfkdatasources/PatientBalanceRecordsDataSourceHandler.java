package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringCodeRecordsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupRecordsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringRecordValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.BalanceCalculator;
import com.jingyicare.jingyi_icis_engine.service.monitorings.PatientMonitoringService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.service.tubes.PatientTubeImpl;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;
import com.jingyicare.jingyi_icis_engine.utils.ValueMetaUtils;

@Component
@Slf4j
public class PatientBalanceRecordsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientBalanceRecordsDataSourceHandler(
        JfkDataSourceSupport support,
        BalanceStatsShiftRepository balanceStatsShiftRepo,
        MonitoringConfig monitoringConfig,
        PatientMonitoringService patientMonitoringService,
        BalanceCalculator balanceCalculator,
        PatientTubeImpl patientTubeImpl,
        UserService userService,
        ConfigProtoService configProtoService,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
        this.monitoringConfig = monitoringConfig;
        this.patientMonitoringService = patientMonitoringService;
        this.balanceCalculator = balanceCalculator;
        this.patientTubeImpl = patientTubeImpl;
        this.userService = userService;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;

        MonitoringPB monitoring = configProtoService.getConfig().getMonitoring();
        this.summaryCode = monitoring.getParamCodeSummary();
        this.summaryText = monitoring.getParamCodeSummaryTxt();
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.PATIENT_BALANCE_RECORDS;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, FIELD_PID);
        String requestDeptId = getDeptIdInput(input, FIELD_DEPT_ID);
        String queryStartIso = getStringInput(input, FIELD_QUERY_START);
        String tableId = getStringInput(input, FIELD_TABLE_ID);
        List<String> paramCodes = getStringArrayInput(input, FIELD_BALANCE_PARAM_CODES);
        List<Double> colWidths = getDoubleArrayInput(input, FIELD_COL_WIDTHS);
        double fontSize = getDoubleInput(input, FIELD_FONT_SIZE, DEFAULT_FONT_SIZE);
        double charSpacing = getDoubleInput(input, FIELD_CHAR_SPACING, 0d);
        double hPadding = getDoubleInput(input, FIELD_H_PADDING, 0d);

        List<String> missingFields = new ArrayList<>();
        if (pid == null || pid <= 0) missingFields.add(FIELD_PID);
        if (StrUtils.isBlank(queryStartIso)) missingFields.add(FIELD_QUERY_START);
        if (StrUtils.isBlank(tableId)) missingFields.add(FIELD_TABLE_ID);
        if (paramCodes.isEmpty()) missingFields.add(FIELD_BALANCE_PARAM_CODES);
        if (!missingFields.isEmpty()) {
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        PatientRecord patient = support.getPatientService().getPatientRecord(pid);
        if (patient == null) {
            return error(StatusCode.PATIENT_NOT_FOUND, "pid: " + pid);
        }
        String deptId = patient.getDeptId();
        if (StrUtils.isBlank(deptId)) {
            return error(StatusCode.DEPT_NOT_FOUND, "pid: " + pid);
        }
        if (!StrUtils.isBlank(requestDeptId) && !requestDeptId.equals(deptId)) {
            log.warn(
                "Compact report patient dept_id mismatch, pid={}, requestDeptId={}, patientDeptId={}",
                pid, requestDeptId, deptId
            );
        }

        BalanceWindow window = resolveBalanceWindow(deptId, queryStartIso);
        if (window.errorCode() != null) {
            return error(window.errorCode(), window.errorDetail());
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null || StrUtils.isBlank(account.getFirst())) {
            return error(StatusCode.ACCOUNT_NOT_FOUND, "pid: " + pid);
        }
        String accountId = account.getFirst();

        Map<String, MonitoringParamPB> paramMap = monitoringConfig.getMonitoringParams(deptId);
        List<String> validParamCodes = validParamCodes(tableId, deptId, paramCodes, paramMap);
        List<String> tubeParamCodes = patientTubeImpl.getMonitoringParamCodes(
            pid, window.balanceStartUtc(), window.balanceEndUtc());
        var groupBetaList = monitoringConfig.getMonitoringGroups(
            pid, deptId, monitoringConfig.getBalanceGroupTypeId(), tubeParamCodes, accountId);

        PatientMonitoringService.GetMonitoringRecordsResult recordsResult =
            patientMonitoringService.getMonitoringRecords(
                pid, deptId, monitoringConfig.getBalanceGroupTypeId(),
                window.balanceStartUtc(), window.balanceEndUtc(),
                false, groupBetaList, accountId
            );
        if (recordsResult.statusCode != StatusCode.OK) {
            return error(recordsResult.statusCode, "pid: " + pid + ", tableId: " + tableId);
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

        args.recordList = recordsResult.recordList.stream()
            .filter(record -> !record.getEffectiveTime().isBefore(window.balanceStartUtc())
                && record.getEffectiveTime().isBefore(window.balanceEndUtc()))
            .toList();
        args.startTime = window.balanceStartUtc();
        args.endTime = window.balanceEndUtc();

        Map<String, MonitoringCodeRecordsPB> codeRecordsByCode =
            indexCodeRecords(balanceCalculator.getGroupRecordsList(args));
        Map<String, Map<Integer, MonitoringRecordValPB>> hourValsByCode =
            indexHourVals(codeRecordsByCode, window.balanceStartUtc(), window.balanceEndUtc());

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        try (PDDocument document = new PDDocument()) {
            PDFont font = validParamCodes.isEmpty() ? null : loadFont(document);
            addOutput(outputBuilder, FIELD_PARAM_NAME, buildParamNameVals(validParamCodes, paramMap, colWidths, font, fontSize, charSpacing, hPadding));
            addOutput(outputBuilder, FIELD_ACC_ML, buildAccVals(validParamCodes, paramMap, codeRecordsByCode, colWidths, font, fontSize, charSpacing, hPadding));
            for (int hourIndex = 0; hourIndex < 24; hourIndex++) {
                addOutput(
                    outputBuilder,
                    "hour" + (hourIndex + 1),
                    buildHourVals(validParamCodes, paramMap, hourValsByCode, hourIndex, colWidths, font, fontSize, charSpacing, hPadding)
                );
            }
        } catch (IOException e) {
            log.error("Failed to wrap compact patient balance records text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private BalanceWindow resolveBalanceWindow(String deptId, String queryStartIso) {
        LocalDateTime utcStart = TimeUtils.fromIso8601String(queryStartIso, "UTC");
        if (utcStart == null) {
            return BalanceWindow.error(StatusCode.INVALID_TIME_FORMAT, FIELD_QUERY_START);
        }
        LocalDateTime utcEnd = utcStart.plusHours(24);

        BalanceStatsShift shift = balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId, utcEnd)
            .orElse(null);
        if (shift == null) {
            return BalanceWindow.error(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND, "deptId: " + deptId);
        }
        Integer startHour = shift.getStartHour();
        if (startHour == null || !TimeUtils.isValidHour(startHour)) {
            return BalanceWindow.error(
                StatusCode.INVALID_PARAM_VALUE,
                "deptId: " + deptId + ", balanceStatsShiftId: " + shift.getId() + ", start_hour: " + startHour
            );
        }

        LocalDateTime utcMiddle = utcStart.plusHours(12);
        LocalDateTime localMiddle = TimeUtils.getLocalDateTimeFromUtc(utcMiddle, support.getZoneId());
        LocalDateTime localMidnight = localMiddle.toLocalDate().atStartOfDay();
        LocalDateTime balanceStartLocal = localMidnight.plusHours(startHour);
        LocalDateTime balanceStartUtc = TimeUtils.getUtcFromLocalDateTime(balanceStartLocal, support.getZoneId());
        return new BalanceWindow(null, "", balanceStartUtc, balanceStartUtc.plusHours(24));
    }

    private List<String> validParamCodes(
        String tableId,
        String deptId,
        List<String> paramCodes,
        Map<String, MonitoringParamPB> paramMap
    ) {
        List<String> valid = new ArrayList<>();
        for (String paramCode : paramCodes) {
            if (StrUtils.isBlank(paramCode)) continue;
            if (paramMap.containsKey(paramCode) || summaryCode.equals(paramCode)) {
                valid.add(paramCode);
                continue;
            }
            log.warn(
                "Balance param not configured for compact report, tableId={}, deptId={}, paramCode={}",
                tableId, deptId, paramCode
            );
        }
        return valid;
    }

    private Map<String, MonitoringCodeRecordsPB> indexCodeRecords(List<MonitoringGroupRecordsPB> groupRecordsList) {
        Map<String, MonitoringCodeRecordsPB> result = new LinkedHashMap<>();
        for (MonitoringGroupRecordsPB groupRecords : groupRecordsList) {
            for (MonitoringCodeRecordsPB codeRecords : groupRecords.getCodeRecordsList()) {
                result.putIfAbsent(codeRecords.getParamCode(), codeRecords);
            }
        }
        return result;
    }

    private Map<String, Map<Integer, MonitoringRecordValPB>> indexHourVals(
        Map<String, MonitoringCodeRecordsPB> codeRecordsByCode,
        LocalDateTime balanceStartUtc,
        LocalDateTime balanceEndUtc
    ) {
        Map<String, Map<Integer, MonitoringRecordValPB>> result = new LinkedHashMap<>();
        for (Map.Entry<String, MonitoringCodeRecordsPB> entry : codeRecordsByCode.entrySet()) {
            Map<Integer, MonitoringRecordValPB> valsByHour = new LinkedHashMap<>();
            for (MonitoringRecordValPB val : entry.getValue().getRecordValueList()) {
                LocalDateTime recordedAtUtc = TimeUtils.fromIso8601String(val.getRecordedAtIso8601(), "UTC");
                if (!isStrictHour(recordedAtUtc)) continue;
                if (recordedAtUtc.isBefore(balanceStartUtc) || !recordedAtUtc.isBefore(balanceEndUtc)) continue;
                long hourIndex = Duration.between(balanceStartUtc, recordedAtUtc).toHours();
                if (hourIndex < 0 || hourIndex >= 24) continue;
                if (!balanceStartUtc.plusHours(hourIndex).equals(recordedAtUtc)) continue;
                valsByHour.put((int) hourIndex, val);
            }
            result.put(entry.getKey(), valsByHour);
        }
        return result;
    }

    private boolean isStrictHour(LocalDateTime utcTime) {
        return utcTime != null
            && utcTime.getMinute() == 0
            && utcTime.getSecond() == 0
            && utcTime.getNano() == 0;
    }

    private List<JfkValPB> buildParamNameVals(
        List<String> validParamCodes,
        Map<String, MonitoringParamPB> paramMap,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        List<JfkValPB> vals = new ArrayList<>();
        for (String paramCode : validParamCodes) {
            String name = summaryCode.equals(paramCode)
                ? summaryText
                : paramMap.get(paramCode).getName();
            vals.add(stringsVal(wrap(name, 0, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private List<JfkValPB> buildAccVals(
        List<String> validParamCodes,
        Map<String, MonitoringParamPB> paramMap,
        Map<String, MonitoringCodeRecordsPB> codeRecordsByCode,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        List<JfkValPB> vals = new ArrayList<>();
        for (String paramCode : validParamCodes) {
            MonitoringRecordValPB summaryVal = summaryVal(codeRecordsByCode.get(paramCode));
            vals.add(stringsVal(wrap(displayValue(paramCode, summaryVal, paramMap), 1, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private List<JfkValPB> buildHourVals(
        List<String> validParamCodes,
        Map<String, MonitoringParamPB> paramMap,
        Map<String, Map<Integer, MonitoringRecordValPB>> hourValsByCode,
        int hourIndex,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        List<JfkValPB> vals = new ArrayList<>();
        int colIndex = hourIndex + 2;
        for (String paramCode : validParamCodes) {
            MonitoringRecordValPB hourVal = hourValsByCode
                .getOrDefault(paramCode, Map.of())
                .get(hourIndex);
            vals.add(stringsVal(wrap(displayValue(paramCode, hourVal, paramMap), colIndex, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private MonitoringRecordValPB summaryVal(MonitoringCodeRecordsPB codeRecords) {
        if (codeRecords == null) return null;
        MonitoringRecordValPB result = null;
        for (MonitoringRecordValPB val : codeRecords.getRecordValueList()) {
            if (StrUtils.isBlank(val.getRecordedAtIso8601())) {
                result = val;
            }
        }
        return result;
    }

    private String displayValue(
        String paramCode,
        MonitoringRecordValPB val,
        Map<String, MonitoringParamPB> paramMap
    ) {
        if (val == null) return "";
        if (!StrUtils.isBlank(val.getValueStr())) {
            return val.getValueStr();
        }
        MonitoringParamPB param = paramMap.get(paramCode);
        ValueMetaPB valueMeta = param == null ? null : param.getValueMeta();
        if (valueMeta == null || !val.hasValue()) {
            return "";
        }
        return ValueMetaUtils.extractAndFormatParamValue(val.getValue(), valueMeta);
    }

    private List<String> wrap(
        String value,
        int colIndex,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        String text = value == null ? "" : value;
        if (font == null || colIndex < 0 || colIndex >= colWidths.size()) {
            return List.of(text);
        }
        float availableWidth = (float) Math.max(0d, colWidths.get(colIndex) - 2d * Math.max(0d, hPadding));
        return JfkPdfUtils.getWrappedLines(
            font, (float) fontSize, availableWidth, (float) charSpacing, List.of(text));
    }

    private PDFont loadFont(PDDocument document) throws IOException {
        Resource fontResource = resourceLoader.getResource(reportProperties.getCompact().getFont());
        try (var inputStream = fontResource.getInputStream()) {
            return PDType0Font.load(document, new ByteArrayInputStream(inputStream.readAllBytes()));
        }
    }

    private void addOutput(JfkDataSourcePB.Builder outputBuilder, String id, List<JfkValPB> vals) {
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(vals)
            .build());
    }

    private JfkValPB stringsVal(List<String> lines) {
        return JfkValPB.newBuilder()
            .addAllStrsVal(lines == null || lines.isEmpty() ? List.of("") : lines)
            .build();
    }

    private List<String> getStringArrayInput(JfkDataSourcePB input, String fieldId) {
        JfkFieldDataPB fieldData = getInputField(input, fieldId);
        if (fieldData == null) return List.of();
        if (fieldData.getValsCount() == 0 && fieldData.hasVal()) {
            return List.of(fieldData.getVal().getStrVal());
        }
        return fieldData.getValsList().stream()
            .map(JfkValPB::getStrVal)
            .filter(value -> !StrUtils.isBlank(value))
            .toList();
    }

    private List<Double> getDoubleArrayInput(JfkDataSourcePB input, String fieldId) {
        JfkFieldDataPB fieldData = getInputField(input, fieldId);
        if (fieldData == null) return List.of();
        if (fieldData.getValsCount() == 0 && fieldData.hasVal()) {
            return List.of(fieldData.getVal().getDoubleVal());
        }
        return fieldData.getValsList().stream()
            .map(JfkValPB::getDoubleVal)
            .toList();
    }

    private double getDoubleInput(JfkDataSourcePB input, String fieldId, double fallback) {
        JfkFieldDataPB fieldData = getInputField(input, fieldId);
        return fieldData == null || !fieldData.hasVal() ? fallback : fieldData.getVal().getDoubleVal();
    }

    private JfkFieldDataPB getInputField(JfkDataSourcePB input, String fieldId) {
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldId.equals(fieldData.getId())) {
                return fieldData;
            }
        }
        return null;
    }

    private record BalanceWindow(
        StatusCode errorCode,
        String errorDetail,
        LocalDateTime balanceStartUtc,
        LocalDateTime balanceEndUtc
    ) {
        static BalanceWindow error(StatusCode statusCode, String detail) {
            return new BalanceWindow(statusCode, detail, null, null);
        }
    }

    private static final String FIELD_PID = "pid";
    private static final String FIELD_DEPT_ID = "dept_id";
    private static final String FIELD_QUERY_START = "query_start";
    private static final String FIELD_TABLE_ID = "table_id";
    private static final String FIELD_BALANCE_PARAM_CODES = "balance_param_codes";
    private static final String FIELD_COL_WIDTHS = "col_widths";
    private static final String FIELD_FONT_SIZE = "font_size";
    private static final String FIELD_CHAR_SPACING = "char_spacing";
    private static final String FIELD_H_PADDING = "h_padding";
    private static final String FIELD_PARAM_NAME = "param_name";
    private static final String FIELD_ACC_ML = "acc_ml";
    private static final double DEFAULT_FONT_SIZE = 8d;

    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
    private final MonitoringConfig monitoringConfig;
    private final PatientMonitoringService patientMonitoringService;
    private final BalanceCalculator balanceCalculator;
    private final PatientTubeImpl patientTubeImpl;
    private final UserService userService;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
    private final String summaryCode;
    private final String summaryText;
}
