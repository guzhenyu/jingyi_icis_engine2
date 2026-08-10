package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.NursingRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.service.reports.common.PdfFontSet;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
@Slf4j
public class PatientNursingRecordsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientNursingRecordsDataSourceHandler(
        JfkDataSourceSupport support,
        MonitoringWindowResolver monitoringWindowResolver,
        NursingRecordRepository nursingRecordRepo,
        AccountRepository accountRepo,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.monitoringWindowResolver = monitoringWindowResolver;
        this.nursingRecordRepo = nursingRecordRepo;
        this.accountRepo = accountRepo;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.PATIENT_NURSING_RECORDS;
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
                "Compact nursing records dept_id mismatch, pid={}, requestDeptId={}, patientDeptId={}",
                pid, requestDeptId, window.deptId()
            );
        }

        List<NursingRecord> nursingRecords = nursingRecordRepo.findReportNursingRecords(
            pid, window.monStartUtc(), window.monEndUtc());

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        if (nursingRecords.isEmpty()) {
            addEmptyOutputs(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        JfkSignatureValueResolver signatureResolver = new JfkSignatureValueResolver(
            accountRepo, nursingSignatureAccountRefs(nursingRecords), log, "Compact nursing records");

        NursingRows rows;
        try (PDDocument document = new PDDocument()) {
            PdfFontSet fonts = loadFonts(document);
            rows = buildRows(nursingRecords, signatureResolver, colWidths, fonts, fontSize, charSpacing, hPadding);
        } catch (IOException e) {
            log.error("Failed to wrap compact patient nursing records text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        addOutput(outputBuilder, FIELD_RECORD_TIME, rows.recordTime());
        addOutput(outputBuilder, FIELD_CONTENT, rows.content());
        addOutput(outputBuilder, FIELD_RECORDED_BY, rows.recordedBy());
        addOutput(outputBuilder, FIELD_REVIEWED_BY, rows.reviewedBy());
        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private NursingRows buildRows(
        List<NursingRecord> nursingRecords,
        JfkSignatureValueResolver signatureResolver,
        List<Double> colWidths,
        PdfFontSet fonts,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        NursingRows rows = new NursingRows();
        for (NursingRecord nursingRecord : nursingRecords.stream()
            .sorted(Comparator
                .comparing(NursingRecord::getEffectiveTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(NursingRecord::getId, Comparator.nullsLast(Long::compareTo)))
            .toList()) {
            rows.add(
                stringsVal(wrap(
                    formatLocal(nursingRecord.getEffectiveTime()),
                    RECORD_TIME_COL_INDEX,
                    colWidths,
                    fonts,
                    fontSize,
                    charSpacing,
                    hPadding
                )),
                stringsVal(wrapLines(
                    splitLines(safe(nursingRecord.getContent())),
                    CONTENT_COL_INDEX,
                    colWidths,
                    fonts,
                    fontSize,
                    charSpacing,
                    hPadding
                )),
                strVal(signatureResolver.signatureOrFallback(
                    nursingRecord.getCreatedBy(),
                    safe(nursingRecord.getCreatedByAccountName()),
                    nursingRecord.getId()
                )),
                strVal(signatureResolver.signatureOrFallback(
                    nursingRecord.getReviewedBy(),
                    safe(nursingRecord.getReviewedByAccountName()),
                    nursingRecord.getId()
                ))
            );
        }
        return rows;
    }

    private List<String> nursingSignatureAccountRefs(List<NursingRecord> nursingRecords) {
        List<String> result = new ArrayList<>();
        for (NursingRecord nursingRecord : nursingRecords) {
            if (!StrUtils.isBlank(nursingRecord.getCreatedBy())) result.add(nursingRecord.getCreatedBy());
            if (!StrUtils.isBlank(nursingRecord.getReviewedBy())) result.add(nursingRecord.getReviewedBy());
        }
        return result;
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
        PdfFontSet fonts,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        return wrapLines(List.of(value == null ? "" : value), colIndex, colWidths, fonts, fontSize, charSpacing, hPadding);
    }

    private List<String> wrapLines(
        List<String> lines,
        int colIndex,
        List<Double> colWidths,
        PdfFontSet fonts,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        if (fonts == null || colIndex < 0 || colIndex >= colWidths.size()) {
            return lines == null || lines.isEmpty() ? List.of("") : lines;
        }
        float availableWidth = (float) Math.max(0d, colWidths.get(colIndex) - 2d * Math.max(0d, hPadding));
        return JfkPdfUtils.getWrappedLines(
            fonts, (float) fontSize, availableWidth, (float) charSpacing,
            lines == null || lines.isEmpty() ? List.of("") : lines
        );
    }

    private List<String> splitLines(String value) {
        if (value == null) return List.of("");
        return Arrays.asList(value.split("\\R", -1));
    }

    private PdfFontSet loadFonts(PDDocument document) throws IOException {
        Resource fontResource = resourceLoader.getResource(reportProperties.getCompact().getFont());
        return PdfFontSet.load(document, fontResource);
    }

    private void addEmptyOutputs(JfkDataSourcePB.Builder outputBuilder) {
        addOutput(outputBuilder, FIELD_RECORD_TIME, List.of());
        addOutput(outputBuilder, FIELD_CONTENT, List.of());
        addOutput(outputBuilder, FIELD_RECORDED_BY, List.of());
        addOutput(outputBuilder, FIELD_REVIEWED_BY, List.of());
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

    private record NursingRows(
        List<JfkValPB> recordTime,
        List<JfkValPB> content,
        List<JfkValPB> recordedBy,
        List<JfkValPB> reviewedBy
    ) {
        private NursingRows() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        private void add(
            JfkValPB recordTimeVal,
            JfkValPB contentVal,
            JfkValPB recordedByVal,
            JfkValPB reviewedByVal
        ) {
            recordTime.add(recordTimeVal);
            content.add(contentVal);
            recordedBy.add(recordedByVal);
            reviewedBy.add(reviewedByVal);
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
    private static final String FIELD_REVIEWED_BY = "reviewed_by";
    private static final int RECORD_TIME_COL_INDEX = 0;
    private static final int CONTENT_COL_INDEX = 1;
    private static final double DEFAULT_FONT_SIZE = 8d;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MonitoringWindowResolver monitoringWindowResolver;
    private final NursingRecordRepository nursingRecordRepo;
    private final AccountRepository accountRepo;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
