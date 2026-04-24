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

import com.jingyicare.jingyi_icis_engine.entity.nursingorders.NursingExecutionRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.NursingExecutionRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.NursingExecutionRecordRepository.NursingExecutionRecordReportRow;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
@Slf4j
public class PatientNursingOrdersDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientNursingOrdersDataSourceHandler(
        JfkDataSourceSupport support,
        MonitoringWindowResolver monitoringWindowResolver,
        NursingExecutionRecordRepository nursingExecutionRecordRepo,
        AccountRepository accountRepo,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.monitoringWindowResolver = monitoringWindowResolver;
        this.nursingExecutionRecordRepo = nursingExecutionRecordRepo;
        this.accountRepo = accountRepo;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.PATIENT_NURSING_ORDERS;
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
                "Compact nursing orders dept_id mismatch, pid={}, requestDeptId={}, patientDeptId={}",
                pid, requestDeptId, window.deptId()
            );
        }

        List<NursingExecutionRecordReportRow> executionRecords = nursingExecutionRecordRepo.findReportNursingExecutionRecords(
            pid, window.monStartUtc(), window.monEndUtc());

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        if (executionRecords.isEmpty()) {
            addEmptyOutputs(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        Map<String, String> accountNameByCompletedBy = accountNameByCompletedBy(executionRecords);
        JfkSignatureValueResolver signatureResolver = new JfkSignatureValueResolver(
            accountRepo,
            executionRecords.stream()
                .filter(reportRow -> reportRow != null && reportRow.getExecutionRecord() != null)
                .map(reportRow -> reportRow.getExecutionRecord().getCompletedBy())
                .filter(completedBy -> !StrUtils.isBlank(completedBy))
                .toList(),
            log,
            "Compact nursing orders"
        );

        NursingOrderRows rows;
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            rows = buildRows(
                executionRecords,
                accountNameByCompletedBy,
                signatureResolver,
                colWidths,
                font,
                fontSize,
                charSpacing,
                hPadding
            );
        } catch (IOException e) {
            log.error("Failed to wrap compact patient nursing orders text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        addOutput(outputBuilder, FIELD_RECORD_TIME, rows.recordTime());
        addOutput(outputBuilder, FIELD_CONTENT, rows.content());
        addOutput(outputBuilder, FIELD_RECORDED_BY, rows.recordedBy());
        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private NursingOrderRows buildRows(
        List<NursingExecutionRecordReportRow> executionRecords,
        Map<String, String> accountNameByCompletedBy,
        JfkSignatureValueResolver signatureResolver,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        NursingOrderRows rows = new NursingOrderRows();
        for (NursingExecutionRecordReportRow reportRow : executionRecords.stream()
            .filter(reportRow -> reportRow != null && reportRow.getExecutionRecord() != null)
            .sorted(Comparator
                .comparing((NursingExecutionRecordReportRow reportRow) ->
                    reportRow.getExecutionRecord().getCompletedTime(), Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(reportRow -> reportRow.getExecutionRecord().getId(), Comparator.nullsLast(Long::compareTo)))
            .toList()) {
            NursingExecutionRecord record = reportRow.getExecutionRecord();
            if (record.getCompletedTime() == null) {
                continue;
            }
            if (StrUtils.isBlank(reportRow.getOrderName())) {
                log.warn(
                    "Nursing order name is empty for compact nursing orders, executionRecordId={}, nursingOrderId={}",
                    record.getId(), record.getNursingOrderId()
                );
            }
            rows.add(
                stringsVal(wrap(
                    formatLocal(record.getCompletedTime()),
                    RECORD_TIME_COL_INDEX,
                    colWidths,
                    font,
                    fontSize,
                    charSpacing,
                    hPadding
                )),
                stringsVal(wrap(
                    nursingExecutionContent(reportRow.getOrderName(), record.getNote()),
                    CONTENT_COL_INDEX,
                    colWidths,
                    font,
                    fontSize,
                    charSpacing,
                    hPadding
                )),
                strVal(signatureResolver.signatureOrFallback(
                    record.getCompletedBy(),
                    displayAccountName(record.getCompletedBy(), accountNameByCompletedBy),
                    record.getId()
                ))
            );
        }
        return rows;
    }

    private String nursingExecutionContent(String orderName, String note) {
        String content = safe(orderName);
        if (StrUtils.isBlank(note)) {
            return content;
        }
        return content + "(备注: " + note + ")";
    }

    private Map<String, String> accountNameByCompletedBy(List<NursingExecutionRecordReportRow> records) {
        Map<String, Long> accountIdByCompletedBy = new LinkedHashMap<>();
        for (NursingExecutionRecordReportRow reportRow : records) {
            if (reportRow == null || reportRow.getExecutionRecord() == null) continue;
            NursingExecutionRecord record = reportRow.getExecutionRecord();
            String completedBy = record.getCompletedBy();
            if (StrUtils.isBlank(completedBy)) continue;
            try {
                accountIdByCompletedBy.putIfAbsent(completedBy, Long.parseLong(completedBy));
            } catch (NumberFormatException e) {
                log.warn(
                    "Compact nursing orders completed_by is not accounts.id, recordId={}, completedBy={}",
                    record.getId(), completedBy
                );
            }
        }
        if (accountIdByCompletedBy.isEmpty()) {
            return Map.of();
        }

        List<Long> accountIds = new ArrayList<>(new LinkedHashSet<>(accountIdByCompletedBy.values()));
        Map<Long, String> accountNameById = accountRepo.findByIdInAndIsDeletedFalse(accountIds).stream()
            .collect(Collectors.toMap(
                Account::getId,
                account -> safe(account.getName()),
                (left, right) -> left,
                LinkedHashMap::new
            ));

        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : accountIdByCompletedBy.entrySet()) {
            String accountName = accountNameById.get(entry.getValue());
            if (StrUtils.isBlank(accountName)) {
                log.warn(
                    "Compact nursing orders completed_by account not found, completedBy={}, accountId={}",
                    entry.getKey(), entry.getValue()
                );
                continue;
            }
            result.put(entry.getKey(), accountName);
        }
        return result;
    }

    private String displayAccountName(String completedBy, Map<String, String> accountNameByCompletedBy) {
        if (StrUtils.isBlank(completedBy)) return "";
        String accountName = accountNameByCompletedBy.get(completedBy);
        return StrUtils.isBlank(accountName) ? completedBy : accountName;
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

    private record NursingOrderRows(
        List<JfkValPB> recordTime,
        List<JfkValPB> content,
        List<JfkValPB> recordedBy
    ) {
        private NursingOrderRows() {
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
    private final NursingExecutionRecordRepository nursingExecutionRecordRepo;
    private final AccountRepository accountRepo;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
