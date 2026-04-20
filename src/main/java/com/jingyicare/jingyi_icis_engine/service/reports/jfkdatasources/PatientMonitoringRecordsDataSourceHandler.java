package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
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

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;
import com.jingyicare.jingyi_icis_engine.utils.ValueMetaUtils;

@Component
@Slf4j
public class PatientMonitoringRecordsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientMonitoringRecordsDataSourceHandler(
        JfkDataSourceSupport support,
        PatientMonitoringRecordRepository recordRepo,
        BalanceStatsShiftRepository balanceStatsShiftRepo,
        MonitoringConfig monitoringConfig,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.recordRepo = recordRepo;
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
        this.monitoringConfig = monitoringConfig;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getMetaId() {
        return META_ID;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, FIELD_PID);
        String requestDeptId = getDeptIdInput(input, FIELD_DEPT_ID);
        String queryStartIso = getStringInput(input, FIELD_QUERY_START);
        String tableId = getStringInput(input, FIELD_TABLE_ID);
        List<String> paramCodes = getStringArrayInput(input, FIELD_MONITORING_PARAM_CODES);
        List<Double> colWidths = getDoubleArrayInput(input, FIELD_COL_WIDTHS);
        double fontSize = getDoubleInput(input, FIELD_FONT_SIZE, DEFAULT_FONT_SIZE);
        double charSpacing = getDoubleInput(input, FIELD_CHAR_SPACING, 0d);
        double hPadding = getDoubleInput(input, FIELD_H_PADDING, 0d);

        List<String> missingFields = new ArrayList<>();
        if (pid == null || pid <= 0) missingFields.add(FIELD_PID);
        if (StrUtils.isBlank(queryStartIso)) missingFields.add(FIELD_QUERY_START);
        if (StrUtils.isBlank(tableId)) missingFields.add(FIELD_TABLE_ID);
        if (paramCodes.isEmpty()) missingFields.add(FIELD_MONITORING_PARAM_CODES);
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

        LocalDateTime utcStart = TimeUtils.fromIso8601String(queryStartIso, "UTC");
        if (utcStart == null) {
            return error(StatusCode.INVALID_TIME_FORMAT, FIELD_QUERY_START);
        }
        LocalDateTime utcEnd = utcStart.plusHours(24);

        BalanceStatsShift shift = balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId, utcEnd)
            .orElse(null);
        if (shift == null) {
            return error(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND, "deptId: " + deptId);
        }

        Integer monStartHour = normalizeStartHour(shift);
        if (monStartHour == null) {
            return error(
                StatusCode.INVALID_PARAM_VALUE,
                "deptId: " + deptId + ", balanceStatsShiftId: " + shift.getId()
            );
        }

        LocalDateTime utcMiddle = utcStart.plusHours(12);
        LocalDateTime localMiddle = TimeUtils.getLocalDateTimeFromUtc(utcMiddle, support.getZoneId());
        LocalDateTime localMidnight = localMiddle.toLocalDate().atStartOfDay();
        LocalDateTime monStartUtc = TimeUtils.getUtcFromLocalDateTime(localMidnight.plusHours(monStartHour), support.getZoneId());
        LocalDateTime monEndUtc = monStartUtc.plusHours(24);

        Map<String, MonitoringParamPB> paramMap = monitoringConfig.getMonitoringParams(deptId);
        List<String> validParamCodes = new ArrayList<>();
        for (String paramCode : paramCodes) {
            if (StrUtils.isBlank(paramCode)) continue;
            if (!paramMap.containsKey(paramCode)) {
                log.warn(
                    "Monitoring param not configured for compact report, tableId={}, deptId={}, paramCode={}",
                    tableId, deptId, paramCode
                );
                continue;
            }
            validParamCodes.add(paramCode);
        }

        List<PatientMonitoringRecord> records = validParamCodes.isEmpty()
            ? List.of()
            : recordRepo.findByPidAndParamCodesAndEffectiveTimeRange(pid, validParamCodes, monStartUtc, monEndUtc);
        Map<String, Map<Integer, PatientMonitoringRecord>> recordsByParamAndHour =
            indexRecords(records, monStartUtc);
        if (reportProperties.getCompact().getPatientMonitoringRecords().isFilterEmptyParams()) {
            validParamCodes = filterParamsWithRecords(validParamCodes, recordsByParamAndHour, tableId);
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            addOutput(outputBuilder, FIELD_PARAM_NAME, buildParamNameVals(validParamCodes, paramMap, colWidths, font, fontSize, charSpacing, hPadding));
            addOutput(outputBuilder, FIELD_PARAM_UNIT, buildParamUnitVals(validParamCodes, paramMap, colWidths, font, fontSize, charSpacing, hPadding));
            for (int hourIndex = 0; hourIndex < 24; hourIndex++) {
                addOutput(
                    outputBuilder,
                    "hour" + (hourIndex + 1),
                    buildHourVals(
                        validParamCodes, paramMap, recordsByParamAndHour, hourIndex,
                        colWidths, font, fontSize, charSpacing, hPadding
                    )
                );
            }
        } catch (IOException e) {
            log.error("Failed to wrap compact patient monitoring records text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private List<String> filterParamsWithRecords(
        List<String> validParamCodes,
        Map<String, Map<Integer, PatientMonitoringRecord>> recordsByParamAndHour,
        String tableId
    ) {
        List<String> result = new ArrayList<>();
        for (String paramCode : validParamCodes) {
            Map<Integer, PatientMonitoringRecord> recordsByHour = recordsByParamAndHour.get(paramCode);
            if (recordsByHour == null || recordsByHour.isEmpty()) {
                log.debug(
                    "Filtered empty compact patient monitoring param, tableId={}, paramCode={}",
                    tableId, paramCode
                );
                continue;
            }
            result.add(paramCode);
        }
        return result;
    }

    private Integer normalizeStartHour(BalanceStatsShift shift) {
        if (shift.getMonStartHour() != null && TimeUtils.isValidHour(shift.getMonStartHour())) {
            return shift.getMonStartHour();
        }
        if (shift.getStartHour() != null && TimeUtils.isValidHour(shift.getStartHour())) {
            return shift.getStartHour();
        }
        return null;
    }

    private Map<String, Map<Integer, PatientMonitoringRecord>> indexRecords(
        List<PatientMonitoringRecord> records,
        LocalDateTime monStartUtc
    ) {
        Map<String, Map<Integer, PatientMonitoringRecord>> result = new LinkedHashMap<>();
        for (PatientMonitoringRecord record : records) {
            LocalDateTime effectiveTime = record.getEffectiveTime();
            if (!isStrictHour(effectiveTime)) continue;
            long hourIndex = Duration.between(monStartUtc, effectiveTime).toHours();
            if (hourIndex < 0 || hourIndex >= 24) continue;
            result
                .computeIfAbsent(record.getMonitoringParamCode(), ignored -> new LinkedHashMap<>())
                .merge((int) hourIndex, record, this::newerRecord);
        }
        return result;
    }

    private boolean isStrictHour(LocalDateTime effectiveTime) {
        return effectiveTime != null
            && effectiveTime.getMinute() == 0
            && effectiveTime.getSecond() == 0
            && effectiveTime.getNano() == 0;
    }

    private PatientMonitoringRecord newerRecord(PatientMonitoringRecord left, PatientMonitoringRecord right) {
        log.warn(
            "Duplicate compact patient monitoring record at same hour, pid={}, paramCode={}, effectiveTime={}, leftId={}, rightId={}",
            left.getPid(), left.getMonitoringParamCode(), left.getEffectiveTime(), left.getId(), right.getId()
        );
        LocalDateTime leftModified = left.getModifiedAt();
        LocalDateTime rightModified = right.getModifiedAt();
        if (Comparator.nullsFirst(LocalDateTime::compareTo).compare(leftModified, rightModified) < 0) {
            return right;
        }
        return left;
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
            vals.add(stringsVal(wrap(paramMap.get(paramCode).getName(), 0, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private List<JfkValPB> buildParamUnitVals(
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
            ValueMetaPB valueMeta = paramMap.get(paramCode).getValueMeta();
            vals.add(stringsVal(wrap(valueMeta.getUnit(), 1, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private List<JfkValPB> buildHourVals(
        List<String> validParamCodes,
        Map<String, MonitoringParamPB> paramMap,
        Map<String, Map<Integer, PatientMonitoringRecord>> recordsByParamAndHour,
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
            PatientMonitoringRecord record = recordsByParamAndHour
                .getOrDefault(paramCode, Map.of())
                .get(hourIndex);
            String value = displayValue(record, paramMap.get(paramCode).getValueMeta());
            vals.add(stringsVal(wrap(value, colIndex, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private String displayValue(PatientMonitoringRecord record, ValueMetaPB valueMeta) {
        if (record == null) return "";
        if (!StrUtils.isBlank(record.getParamValueStr())) {
            return record.getParamValueStr();
        }
        if (StrUtils.isBlank(record.getParamValue())) {
            return "";
        }
        MonitoringValuePB monitoringValue = ProtoUtils.decodeMonitoringValue(record.getParamValue());
        if (monitoringValue == null) {
            return "";
        }
        return ValueMetaUtils.extractAndFormatParamValue(
            monitoringValue.getValue(), monitoringValue.getValuesList(), valueMeta);
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
        if (colIndex < 0 || colIndex >= colWidths.size()) {
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

    private static final String META_ID = JfkDataSourceIds.PATIENT_MONITORING_RECORDS;
    private static final String FIELD_PID = "pid";
    private static final String FIELD_DEPT_ID = "dept_id";
    private static final String FIELD_QUERY_START = "query_start";
    private static final String FIELD_TABLE_ID = "table_id";
    private static final String FIELD_MONITORING_PARAM_CODES = "monitoring_param_codes";
    private static final String FIELD_COL_WIDTHS = "col_widths";
    private static final String FIELD_FONT_SIZE = "font_size";
    private static final String FIELD_CHAR_SPACING = "char_spacing";
    private static final String FIELD_H_PADDING = "h_padding";
    private static final String FIELD_PARAM_NAME = "param_name";
    private static final String FIELD_PARAM_UNIT = "param_unit";
    private static final double DEFAULT_FONT_SIZE = 8d;

    private final PatientMonitoringRecordRepository recordRepo;
    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
    private final MonitoringConfig monitoringConfig;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
