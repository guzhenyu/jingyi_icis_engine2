package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecord;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.PatientBgaRecordDetail;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientBgaRecordDetailRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.PatientBgaRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
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
public class PatientBgaRecordsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientBgaRecordsDataSourceHandler(
        JfkDataSourceSupport support,
        MonitoringWindowResolver monitoringWindowResolver,
        PatientBgaRecordRepository recordRepo,
        PatientBgaRecordDetailRepository detailRepo,
        BgaParamRepository bgaParamRepo,
        MonitoringConfig monitoringConfig,
        ConfigProtoService configProtoService,
        AccountRepository accountRepo,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.monitoringWindowResolver = monitoringWindowResolver;
        this.recordRepo = recordRepo;
        this.detailRepo = detailRepo;
        this.bgaParamRepo = bgaParamRepo;
        this.monitoringConfig = monitoringConfig;
        this.configProtoService = configProtoService;
        this.accountRepo = accountRepo;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.PATIENT_BGA_RECORDS;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, FIELD_PID);
        String queryStartIso = getStringInput(input, FIELD_QUERY_START);
        List<Double> colWidths = getDoubleArrayInput(input, FIELD_COL_WIDTHS);
        double fontSize = getDoubleInput(input, FIELD_FONT_SIZE, DEFAULT_FONT_SIZE);
        double charSpacing = getDoubleInput(input, FIELD_CHAR_SPACING, 0d);
        double hPadding = getDoubleInput(input, FIELD_H_PADDING, 0d);

        List<String> missingFields = new ArrayList<>();
        if (pid == null || pid <= 0) missingFields.add(FIELD_PID);
        if (StrUtils.isBlank(queryStartIso)) missingFields.add(FIELD_QUERY_START);
        if (!missingFields.isEmpty()) {
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        Pair<ReturnCode, MonitoringWindow> windowResult = monitoringWindowResolver.resolve(pid, queryStartIso);
        if (windowResult.getFirst().getCode() != StatusCode.OK.ordinal()) {
            return new Pair<>(windowResult.getFirst(), null);
        }

        MonitoringWindow window = windowResult.getSecond();
        List<PatientBgaRecord> records = recordRepo
            .findByPidAndEffectiveTimeGreaterThanEqualAndEffectiveTimeLessThanAndIsDeletedFalseOrderByEffectiveTimeAsc(
                pid, window.monStartUtc(), window.monEndUtc());
        Map<Long, List<PatientBgaRecordDetail>> detailsByRecordId = findDetails(records);
        Map<String, MonitoringParamPB> paramMap = monitoringConfig.getMonitoringParams(window.deptId());
        Map<String, Integer> bgaParamOrder = bgaParamOrder(window.deptId());
        JfkSignatureValueResolver signatureResolver = new JfkSignatureValueResolver(
            accountRepo, bgaSignatureAccountRefs(records), log, "Compact BGA records");

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        BgaRows rows;
        try (PDDocument document = new PDDocument()) {
            PDFont font = records.isEmpty() ? null : loadFont(document);
            rows = buildRows(
                records, detailsByRecordId, paramMap, bgaParamOrder, signatureResolver,
                colWidths, font, fontSize, charSpacing, hPadding
            );
        } catch (IOException e) {
            log.error("Failed to wrap compact patient BGA records text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        addOutput(outputBuilder, FIELD_DATE_STR, rows.dateStr());
        addOutput(outputBuilder, FIELD_CATEGORY_NAME, rows.categoryName());
        addOutput(outputBuilder, FIELD_BGA_DETAILS, rows.bgaDetails());
        addOutput(outputBuilder, FIELD_RECORDED_BY, rows.recordedBy());
        addOutput(outputBuilder, FIELD_REVIEWED_BY, rows.reviewedBy());
        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private BgaRows buildRows(
        List<PatientBgaRecord> records,
        Map<Long, List<PatientBgaRecordDetail>> detailsByRecordId,
        Map<String, MonitoringParamPB> paramMap,
        Map<String, Integer> bgaParamOrder,
        JfkSignatureValueResolver signatureResolver,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        BgaRows rows = new BgaRows();
        if (records.isEmpty()) {
            return rows;
        }

        for (PatientBgaRecord record : records) {
            rows.add(
                stringsVal(List.of(formatEffectiveTime(record.getEffectiveTime()))),
                stringsVal(List.of(categoryName(record))),
                stringsVal(wrap(
                    bgaDetails(record, detailsByRecordId.getOrDefault(record.getId(), List.of()), paramMap, bgaParamOrder),
                    BGA_DETAILS_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding
                )),
                strVal(signatureResolver.signatureOrFallback(
                    record.getRecordedBy(), safe(record.getRecordedByAccountName()), record.getId())),
                strVal(signatureResolver.signatureOrFallback(
                    record.getReviewedBy(), safe(record.getReviewedByAccountName()), record.getId()))
            );
        }
        return rows;
    }

    private List<String> bgaSignatureAccountRefs(List<PatientBgaRecord> records) {
        List<String> result = new ArrayList<>();
        for (PatientBgaRecord record : records) {
            if (!StrUtils.isBlank(record.getRecordedBy())) result.add(record.getRecordedBy());
            if (!StrUtils.isBlank(record.getReviewedBy())) result.add(record.getReviewedBy());
        }
        return result;
    }

    private Map<Long, List<PatientBgaRecordDetail>> findDetails(List<PatientBgaRecord> records) {
        List<Long> recordIds = records.stream()
            .map(PatientBgaRecord::getId)
            .filter(id -> id != null)
            .toList();
        if (recordIds.isEmpty()) return Map.of();
        return detailRepo.findByRecordIdInAndIsDeletedFalse(recordIds).stream()
            .collect(Collectors.groupingBy(
                PatientBgaRecordDetail::getRecordId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
    }

    private Map<String, Integer> bgaParamOrder(String deptId) {
        return bgaParamRepo.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId).stream()
            .filter(param -> !StrUtils.isBlank(param.getMonitoringParamCode()))
            .collect(Collectors.toMap(
                BgaParam::getMonitoringParamCode,
                param -> param.getDisplayOrder() == null ? Integer.MAX_VALUE : param.getDisplayOrder(),
                Math::min,
                LinkedHashMap::new
            ));
    }

    private String bgaDetails(
        PatientBgaRecord record,
        List<PatientBgaRecordDetail> details,
        Map<String, MonitoringParamPB> paramMap,
        Map<String, Integer> bgaParamOrder
    ) {
        return details.stream()
            .sorted(detailComparator(bgaParamOrder))
            .map(detail -> bgaDetail(record, detail, paramMap))
            .filter(value -> !StrUtils.isBlank(value))
            .collect(Collectors.joining("; "));
    }

    private Comparator<PatientBgaRecordDetail> detailComparator(Map<String, Integer> bgaParamOrder) {
        Function<PatientBgaRecordDetail, Integer> displayOrder = detail ->
            bgaParamOrder.getOrDefault(detail.getMonitoringParamCode(), Integer.MAX_VALUE);
        return Comparator.comparing(displayOrder)
            .thenComparing(
                PatientBgaRecordDetail::getId,
                Comparator.nullsLast(Long::compareTo)
            );
    }

    private String bgaDetail(
        PatientBgaRecord record,
        PatientBgaRecordDetail detail,
        Map<String, MonitoringParamPB> paramMap
    ) {
        MonitoringParamPB param = paramMap.get(detail.getMonitoringParamCode());
        if (param == null) {
            log.warn(
                "BGA param not configured for compact report, recordId={}, paramCode={}",
                record.getId(), detail.getMonitoringParamCode()
            );
            return "";
        }
        String value = displayValue(detail, param);
        if (StrUtils.isBlank(value)) {
            return "";
        }
        return param.getName() + ": " + value;
    }

    private String displayValue(PatientBgaRecordDetail detail, MonitoringParamPB param) {
        if (!StrUtils.isBlank(detail.getParamValueStr())) {
            return detail.getParamValueStr();
        }
        if (StrUtils.isBlank(detail.getParamValue())) {
            return "";
        }
        GenericValuePB value = ProtoUtils.decodeGenericValue(detail.getParamValue());
        if (value == null) {
            return "";
        }
        return ValueMetaUtils.extractAndFormatParamValue(value, param.getValueMeta());
    }

    private String formatEffectiveTime(LocalDateTime effectiveTimeUtc) {
        if (effectiveTimeUtc == null) return "";
        return TimeUtils.getLocalDateTimeFromUtc(effectiveTimeUtc, support.getZoneId()).format(DATE_TIME_FORMATTER);
    }

    private String categoryName(PatientBgaRecord record) {
        Integer categoryId = record.getBgaCategoryId();
        if (categoryId != null) {
            for (EnumValue category : configProtoService.getConfig().getBga().getEnums().getBgaCategoryList()) {
                if (category.getId() == categoryId) {
                    return category.getName();
                }
            }
        }
        log.warn(
            "BGA category not configured for compact report, recordId={}, categoryId={}",
            record.getId(), categoryId
        );
        return safe(record.getBgaCategoryName());
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
        String text = safe(value);
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

    private record BgaRows(
        List<JfkValPB> dateStr,
        List<JfkValPB> categoryName,
        List<JfkValPB> bgaDetails,
        List<JfkValPB> recordedBy,
        List<JfkValPB> reviewedBy
    ) {
        private BgaRows() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        private void add(
            JfkValPB dateStrVal,
            JfkValPB categoryNameVal,
            JfkValPB bgaDetailsVal,
            JfkValPB recordedByVal,
            JfkValPB reviewedByVal
        ) {
            dateStr.add(dateStrVal);
            categoryName.add(categoryNameVal);
            bgaDetails.add(bgaDetailsVal);
            recordedBy.add(recordedByVal);
            reviewedBy.add(reviewedByVal);
        }
    }

    private static final String FIELD_PID = "pid";
    private static final String FIELD_QUERY_START = "query_start";
    private static final String FIELD_COL_WIDTHS = "col_widths";
    private static final String FIELD_FONT_SIZE = "font_size";
    private static final String FIELD_CHAR_SPACING = "char_spacing";
    private static final String FIELD_H_PADDING = "h_padding";
    private static final String FIELD_DATE_STR = "date_str";
    private static final String FIELD_CATEGORY_NAME = "category_name";
    private static final String FIELD_BGA_DETAILS = "bga_details";
    private static final String FIELD_RECORDED_BY = "recorded_by";
    private static final String FIELD_REVIEWED_BY = "reviewed_by";
    private static final int BGA_DETAILS_COL_INDEX = 2;
    private static final double DEFAULT_FONT_SIZE = 8d;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd H:mm");

    private final MonitoringWindowResolver monitoringWindowResolver;
    private final PatientBgaRecordRepository recordRepo;
    private final PatientBgaRecordDetailRepository detailRepo;
    private final BgaParamRepository bgaParamRepo;
    private final MonitoringConfig monitoringConfig;
    private final ConfigProtoService configProtoService;
    private final AccountRepository accountRepo;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
