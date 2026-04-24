package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientMonitoringRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupBetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientMonitoringRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonitoringConfig;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;
import com.jingyicare.jingyi_icis_engine.utils.ValueMetaUtils;

@Component
@Slf4j
public class PatientNonHourlyMonitoringRecordsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientNonHourlyMonitoringRecordsDataSourceHandler(
        JfkDataSourceSupport support,
        MonitoringWindowResolver monitoringWindowResolver,
        PatientMonitoringRecordRepository monitoringRecordRepo,
        MonitoringConfig monitoringConfig,
        ConfigProtoService configProtoService,
        UserService userService,
        AccountRepository accountRepo,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.monitoringWindowResolver = monitoringWindowResolver;
        this.monitoringRecordRepo = monitoringRecordRepo;
        this.monitoringConfig = monitoringConfig;
        this.configProtoService = configProtoService;
        this.userService = userService;
        this.accountRepo = accountRepo;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.PATIENT_NON_HOURLY_MONITORING_RECORDS;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, FIELD_PID);
        String requestDeptId = getDeptIdInput(input, FIELD_DEPT_ID);
        String queryStartIso = getStringInput(input, FIELD_QUERY_START);
        String tableId = getStringInput(input, FIELD_TABLE_ID);
        List<Double> colWidths = getDoubleArrayInput(input, FIELD_COL_WIDTHS);
        double fontSize = getDoubleInput(input, FIELD_FONT_SIZE, DEFAULT_FONT_SIZE);
        double charSpacing = getDoubleInput(input, FIELD_CHAR_SPACING, 0d);
        double hPadding = getDoubleInput(input, FIELD_H_PADDING, 0d);

        List<String> missingFields = new ArrayList<>();
        if (pid == null || pid <= 0) missingFields.add(FIELD_PID);
        if (StrUtils.isBlank(queryStartIso)) missingFields.add(FIELD_QUERY_START);
        if (StrUtils.isBlank(tableId)) missingFields.add(FIELD_TABLE_ID);
        if (!missingFields.isEmpty()) {
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        Pair<ReturnCode, MonitoringWindow> windowResult = monitoringWindowResolver.resolve(pid, queryStartIso);
        if (windowResult.getFirst().getCode() != StatusCode.OK.ordinal()) {
            return new Pair<>(windowResult.getFirst(), null);
        }

        MonitoringWindow window = windowResult.getSecond();
        if (!StrUtils.isBlank(requestDeptId) && !requestDeptId.equals(window.deptId())) {
            log.warn(
                "Compact non-hourly monitoring dept_id mismatch, pid={}, requestDeptId={}, patientDeptId={}",
                pid, requestDeptId, window.deptId()
            );
        }

        List<PatientMonitoringRecord> nonHourlyRecords = monitoringRecordRepo
            .findByPidAndEffectiveTimeRange(pid, window.monStartUtc(), window.monEndUtc())
            .stream()
            .filter(record -> record.getEffectiveTime() != null)
            .filter(record -> !isStrictHour(record.getEffectiveTime()))
            .toList();

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        if (nonHourlyRecords.isEmpty()) {
            addEmptyOutputs(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null || StrUtils.isBlank(account.getFirst())) {
            return error(StatusCode.ACCOUNT_NOT_FOUND, "pid: " + pid);
        }

        Map<String, MonitoringParamPB> paramMap = monitoringConfig.getMonitoringParams(window.deptId());
        Map<String, Integer> paramOrder = monitoringParamOrder(pid, window.deptId(), account.getFirst());
        Map<String, String> accountNameByModifiedBy = accountNameByModifiedBy(nonHourlyRecords);
        JfkSignatureValueResolver signatureResolver = new JfkSignatureValueResolver(
            accountRepo,
            nonHourlyRecords.stream()
                .map(PatientMonitoringRecord::getModifiedBy)
                .filter(modifiedBy -> !StrUtils.isBlank(modifiedBy))
                .toList(),
            log,
            "Compact non-hourly monitoring records"
        );

        MonitoringRows rows;
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            rows = buildRows(
                nonHourlyRecords,
                paramMap,
                paramOrder,
                accountNameByModifiedBy,
                signatureResolver,
                colWidths,
                font,
                fontSize,
                charSpacing,
                hPadding
            );
        } catch (IOException e) {
            log.error("Failed to wrap compact non-hourly monitoring records text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        addOutput(outputBuilder, FIELD_RECORD_TIME, rows.recordTime());
        addOutput(outputBuilder, FIELD_CONTENT, rows.content());
        addOutput(outputBuilder, FIELD_RECORDED_BY, rows.recordedBy());
        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private MonitoringRows buildRows(
        List<PatientMonitoringRecord> nonHourlyRecords,
        Map<String, MonitoringParamPB> paramMap,
        Map<String, Integer> paramOrder,
        Map<String, String> accountNameByModifiedBy,
        JfkSignatureValueResolver signatureResolver,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        Map<LocalDateTime, List<PatientMonitoringRecord>> recordsByTime = nonHourlyRecords.stream()
            .sorted(Comparator
                .comparing(PatientMonitoringRecord::getEffectiveTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(monitoringRecordComparator(paramMap, paramOrder)))
            .collect(Collectors.groupingBy(
                PatientMonitoringRecord::getEffectiveTime,
                LinkedHashMap::new,
                Collectors.toList()
            ));

        MonitoringRows rows = new MonitoringRows();
        for (Map.Entry<LocalDateTime, List<PatientMonitoringRecord>> entry : recordsByTime.entrySet()) {
            List<String> displayValues = new ArrayList<>();
            for (PatientMonitoringRecord record : entry.getValue()) {
                MonitoringParamPB param = paramMap.get(record.getMonitoringParamCode());
                if (param == null) {
                    log.warn(
                        "Monitoring param not configured for compact non-hourly monitoring, pid={}, paramCode={}, recordId={}",
                        record.getPid(), record.getMonitoringParamCode(), record.getId()
                    );
                    continue;
                }
                String value = displayValue(record, param);
                if (StrUtils.isBlank(value)) {
                    continue;
                }
                displayValues.add(param.getName() + ": " + value);
            }
            if (displayValues.isEmpty()) {
                continue;
            }

            PatientMonitoringRecord recordedByRecord = entry.getValue().stream()
                .sorted(Comparator
                    .comparing(PatientMonitoringRecord::getModifiedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                    .thenComparing(PatientMonitoringRecord::getId, Comparator.nullsLast(Comparator.reverseOrder())))
                .findFirst()
                .orElse(null);
            String recordedBy = recordedByRecord == null
                ? ""
                : signatureResolver.signatureOrFallback(
                    recordedByRecord.getModifiedBy(),
                    displayAccountName(recordedByRecord.getModifiedBy(), accountNameByModifiedBy),
                    recordedByRecord.getId()
                );

            rows.add(
                stringsVal(wrap(formatLocal(entry.getKey()), RECORD_TIME_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding)),
                stringsVal(wrap(String.join("; ", displayValues), CONTENT_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding)),
                strVal(recordedBy)
            );
        }
        return rows;
    }

    private Map<String, String> accountNameByModifiedBy(List<PatientMonitoringRecord> records) {
        Map<String, Long> accountIdByModifiedBy = new LinkedHashMap<>();
        for (PatientMonitoringRecord record : records) {
            String modifiedBy = record.getModifiedBy();
            if (StrUtils.isBlank(modifiedBy)) continue;
            try {
                accountIdByModifiedBy.putIfAbsent(modifiedBy, Long.parseLong(modifiedBy));
            } catch (NumberFormatException e) {
                log.warn(
                    "Compact non-hourly monitoring modified_by is not accounts.id, recordId={}, modifiedBy={}",
                    record.getId(), modifiedBy
                );
            }
        }
        if (accountIdByModifiedBy.isEmpty()) {
            return Map.of();
        }

        List<Long> accountIds = new ArrayList<>(new LinkedHashSet<>(accountIdByModifiedBy.values()));
        Map<Long, String> accountNameById = accountRepo.findByIdInAndIsDeletedFalse(accountIds).stream()
            .collect(Collectors.toMap(
                Account::getId,
                account -> safe(account.getName()),
                (left, right) -> left,
                LinkedHashMap::new
            ));

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : accountIdByModifiedBy.entrySet()) {
            String accountName = accountNameById.get(entry.getValue());
            if (StrUtils.isBlank(accountName)) {
                log.warn(
                    "Compact non-hourly monitoring modified_by account not found, modifiedBy={}, accountId={}",
                    entry.getKey(), entry.getValue()
                );
                continue;
            }
            result.put(entry.getKey(), accountName);
        }
        return result;
    }

    private String displayAccountName(String modifiedBy, Map<String, String> accountNameByModifiedBy) {
        if (StrUtils.isBlank(modifiedBy)) return "";
        String accountName = accountNameByModifiedBy.get(modifiedBy);
        return StrUtils.isBlank(accountName) ? modifiedBy : accountName;
    }

    private Comparator<PatientMonitoringRecord> monitoringRecordComparator(
        Map<String, MonitoringParamPB> paramMap,
        Map<String, Integer> paramOrder
    ) {
        return Comparator
            .comparingInt((PatientMonitoringRecord record) ->
                paramOrder.getOrDefault(record.getMonitoringParamCode(), Integer.MAX_VALUE))
            .thenComparingInt(record -> {
                MonitoringParamPB param = paramMap.get(record.getMonitoringParamCode());
                return param == null ? Integer.MAX_VALUE : param.getDisplayOrder();
            })
            .thenComparing(PatientMonitoringRecord::getMonitoringParamCode, Comparator.nullsLast(String::compareTo))
            .thenComparing(PatientMonitoringRecord::getId, Comparator.nullsLast(Long::compareTo));
    }

    private Map<String, Integer> monitoringParamOrder(Long pid, String deptId, String accountId) {
        int groupType = configProtoService.getConfig()
            .getMonitoring()
            .getEnums()
            .getGroupTypeMonitoring()
            .getId();
        List<MonitoringGroupBetaPB> groups = monitoringConfig.getMonitoringGroups(
            pid, deptId, groupType, List.of(), accountId);
        Map<String, Integer> orderByCode = new LinkedHashMap<>();
        int order = 0;
        for (MonitoringGroupBetaPB group : groups.stream()
            .sorted(Comparator
                .comparingInt(MonitoringGroupBetaPB::getDisplayOrder)
                .thenComparingInt(MonitoringGroupBetaPB::getId))
            .toList()) {
            for (MonitoringParamPB param : group.getParamList().stream()
                .sorted(Comparator
                    .comparingInt(MonitoringParamPB::getDisplayOrder)
                    .thenComparing(MonitoringParamPB::getCode))
                .toList()) {
                if (!StrUtils.isBlank(param.getCode())) {
                    orderByCode.putIfAbsent(param.getCode(), order++);
                }
            }
        }
        return orderByCode;
    }

    private boolean isStrictHour(LocalDateTime effectiveTime) {
        return effectiveTime != null
            && effectiveTime.getMinute() == 0
            && effectiveTime.getSecond() == 0
            && effectiveTime.getNano() == 0;
    }

    private String displayValue(PatientMonitoringRecord record, MonitoringParamPB param) {
        String value;
        if (!StrUtils.isBlank(record.getParamValueStr())) {
            value = record.getParamValueStr();
        } else if (StrUtils.isBlank(record.getParamValue())) {
            return "";
        } else {
            MonitoringValuePB monitoringValue = ProtoUtils.decodeMonitoringValue(record.getParamValue());
            if (monitoringValue == null) {
                return "";
            }
            value = ValueMetaUtils.extractAndFormatParamValue(
                monitoringValue.getValue(), monitoringValue.getValuesList(), param.getValueMeta());
        }
        return appendUnit(value, param.getValueMeta().getUnit());
    }

    private String appendUnit(String value, String unit) {
        if (StrUtils.isBlank(value) || StrUtils.isBlank(unit)) {
            return safe(value);
        }
        return value + " " + unit;
    }

    private String formatLocal(LocalDateTime utcTime) {
        if (utcTime == null) return "";
        LocalDateTime localTime = TimeUtils.getLocalDateTimeFromUtc(utcTime, support.getZoneId());
        return DATE_TIME_FORMATTER.format(localTime);
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
        if (font == null || colIndex < 0 || colIndex >= colWidths.size()) {
            return List.of(value == null ? "" : value);
        }
        float availableWidth = (float) Math.max(0d, colWidths.get(colIndex) - 2d * Math.max(0d, hPadding));
        return JfkPdfUtils.getWrappedLines(
            font,
            (float) fontSize,
            availableWidth,
            (float) charSpacing,
            List.of(value == null ? "" : value)
        );
    }

    private PDFont loadFont(PDDocument document) throws IOException {
        Resource fontResource = resourceLoader.getResource(reportProperties.getCompact().getFont());
        try (var inputStream = fontResource.getInputStream()) {
            return PDType0Font.load(document, new ByteArrayInputStream(inputStream.readAllBytes()));
        }
    }

    private void addEmptyOutputs(JfkDataSourcePB.Builder outputBuilder) {
        addOutput(outputBuilder, FIELD_RECORD_TIME, List.of());
        addOutput(outputBuilder, FIELD_CONTENT, List.of());
        addOutput(outputBuilder, FIELD_RECORDED_BY, List.of());
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

    private JfkValPB strVal(String value) {
        return JfkValPB.newBuilder()
            .setStrVal(safe(value))
            .build();
    }

    private String safe(String value) {
        return value == null ? "" : value;
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

    private record MonitoringRows(
        List<JfkValPB> recordTime,
        List<JfkValPB> content,
        List<JfkValPB> recordedBy
    ) {
        private MonitoringRows() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        private void add(JfkValPB recordTimeVal, JfkValPB contentVal, JfkValPB recordedByVal) {
            recordTime.add(recordTimeVal);
            content.add(contentVal);
            recordedBy.add(recordedByVal);
        }
    }

    private static final String FIELD_PID = "pid";
    private static final String FIELD_DEPT_ID = "dept_id";
    private static final String FIELD_QUERY_START = "query_start";
    private static final String FIELD_TABLE_ID = "table_id";
    private static final String FIELD_COL_WIDTHS = "col_widths";
    private static final String FIELD_FONT_SIZE = "font_size";
    private static final String FIELD_CHAR_SPACING = "char_spacing";
    private static final String FIELD_H_PADDING = "h_padding";
    private static final String FIELD_RECORD_TIME = "record_time";
    private static final String FIELD_CONTENT = "content";
    private static final String FIELD_RECORDED_BY = "recorded_by";
    private static final int RECORD_TIME_COL_INDEX = 0;
    private static final int CONTENT_COL_INDEX = 1;
    private static final double DEFAULT_FONT_SIZE = 8d;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MonitoringWindowResolver monitoringWindowResolver;
    private final PatientMonitoringRecordRepository monitoringRecordRepo;
    private final MonitoringConfig monitoringConfig;
    private final ConfigProtoService configProtoService;
    private final UserService userService;
    private final AccountRepository accountRepo;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
