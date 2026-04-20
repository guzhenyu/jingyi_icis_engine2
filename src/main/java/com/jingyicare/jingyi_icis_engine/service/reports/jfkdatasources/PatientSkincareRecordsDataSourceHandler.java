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
import java.util.Set;
import java.util.stream.Collectors;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincarePlan;
import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincarePlanAttr;
import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincareRecord;
import com.jingyicare.jingyi_icis_engine.entity.skincares.PatientSkincareRecordAttr;
import com.jingyicare.jingyi_icis_engine.entity.skincares.SkincareType;
import com.jingyicare.jingyi_icis_engine.entity.skincares.SkincareTypeAttribute;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.GenericValuePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.skincares.PatientSkincarePlanAttrRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.PatientSkincarePlanRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.PatientSkincareRecordAttrRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.PatientSkincareRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.SkincareTypeAttributeRepository;
import com.jingyicare.jingyi_icis_engine.repository.skincares.SkincareTypeRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
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
public class PatientSkincareRecordsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientSkincareRecordsDataSourceHandler(
        JfkDataSourceSupport support,
        MonitoringWindowResolver monitoringWindowResolver,
        PatientSkincareRecordRepository skincareRecordRepo,
        PatientSkincarePlanRepository skincarePlanRepo,
        PatientSkincarePlanAttrRepository skincarePlanAttrRepo,
        PatientSkincareRecordAttrRepository skincareRecordAttrRepo,
        SkincareTypeRepository skincareTypeRepo,
        SkincareTypeAttributeRepository skincareTypeAttrRepo,
        AccountRepository accountRepo,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.monitoringWindowResolver = monitoringWindowResolver;
        this.skincareRecordRepo = skincareRecordRepo;
        this.skincarePlanRepo = skincarePlanRepo;
        this.skincarePlanAttrRepo = skincarePlanAttrRepo;
        this.skincareRecordAttrRepo = skincareRecordAttrRepo;
        this.skincareTypeRepo = skincareTypeRepo;
        this.skincareTypeAttrRepo = skincareTypeAttrRepo;
        this.accountRepo = accountRepo;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.PATIENT_SKINCARE_RECORDS;
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
                "Compact skincare records dept_id mismatch, pid={}, requestDeptId={}, patientDeptId={}",
                pid, requestDeptId, window.deptId()
            );
        }

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        List<PatientSkincareRecord> records = skincareRecordRepo.findReportSkincareRecords(
            pid, window.monStartUtc(), window.monEndUtc());
        if (records.isEmpty()) {
            addEmptyOutputs(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        SkincareData data = loadSkincareData(records);
        JfkSignatureValueResolver signatureResolver = new JfkSignatureValueResolver(
            accountRepo, skincareSignatureAccountRefs(records), log, "Compact skincare records");
        Set<String> attrCodeWhitelist = attrCodeWhitelist();
        SkincareRows rows;
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            rows = buildRows(
                records, data, attrCodeWhitelist, signatureResolver, colWidths, font, fontSize, charSpacing, hPadding);
        } catch (IOException e) {
            log.error("Failed to wrap compact patient skincare records text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        addOutput(outputBuilder, FIELD_RECORD_TIME, rows.recordTime());
        addOutput(outputBuilder, FIELD_CONTENT, rows.content());
        addOutput(outputBuilder, FIELD_RECORDED_BY, rows.recordedBy());
        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private SkincareRows buildRows(
        List<PatientSkincareRecord> records,
        SkincareData data,
        Set<String> attrCodeWhitelist,
        JfkSignatureValueResolver signatureResolver,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        SkincareRows rows = new SkincareRows();
        for (PatientSkincareRecord record : records.stream()
            .sorted(Comparator
                .comparing(PatientSkincareRecord::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(PatientSkincareRecord::getId, Comparator.nullsLast(Long::compareTo)))
            .toList()) {
            PatientSkincarePlan plan = data.planById().get(record.getPatientSkincarePlanId());
            if (plan == null) {
                log.warn(
                    "Compact skincare plan not found, pid={}, recordId={}, planId={}",
                    record.getPid(), record.getId(), record.getPatientSkincarePlanId()
                );
                continue;
            }
            SkincareType type = data.typeById().get(plan.getSkincareTypeId());
            if (type == null) {
                log.warn(
                    "Compact skincare type not found, pid={}, recordId={}, planId={}, skincareTypeId={}",
                    record.getPid(), record.getId(), plan.getId(), plan.getSkincareTypeId()
                );
                continue;
            }

            String planAttrsText = attrsText(
                data.planAttrsByPlanId().getOrDefault(plan.getId(), List.of()),
                data.attrById(), attrCodeWhitelist, "plan", record.getId());
            String recordAttrsText = attrsText(
                data.recordAttrsByRecordId().getOrDefault(record.getId(), List.of()),
                data.attrById(), attrCodeWhitelist, "record", record.getId());
            String content = content(type.getName(), planAttrsText, recordAttrsText);

            rows.add(
                stringsVal(wrap(formatLocal(record.getCreatedAt()), RECORD_TIME_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding)),
                stringsVal(wrap(content, CONTENT_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding)),
                strVal(signatureResolver.signatureOrFallback(
                    record.getModifiedBy(),
                    displayAccountName(record.getModifiedBy(), data.accountNameByModifiedBy()),
                    record.getId()
                ))
            );
        }
        return rows;
    }

    private SkincareData loadSkincareData(List<PatientSkincareRecord> records) {
        List<Long> recordIds = records.stream()
            .map(PatientSkincareRecord::getId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        List<Long> planIds = records.stream()
            .map(PatientSkincareRecord::getPatientSkincarePlanId)
            .filter(id -> id != null)
            .distinct()
            .toList();

        Map<Long, PatientSkincarePlan> planById = planIds.isEmpty()
            ? Map.of()
            : skincarePlanRepo.findByIdInAndIsDeletedFalse(planIds).stream()
                .collect(Collectors.toMap(
                    PatientSkincarePlan::getId,
                    plan -> plan,
                    (left, right) -> left,
                    LinkedHashMap::new
                ));

        List<Integer> typeIds = planById.values().stream()
            .map(PatientSkincarePlan::getSkincareTypeId)
            .filter(id -> id != null)
            .distinct()
            .toList();
        Map<Integer, SkincareType> typeById = typeIds.isEmpty()
            ? Map.of()
            : skincareTypeRepo.findByIdInAndIsDeletedFalse(typeIds).stream()
                .collect(Collectors.toMap(
                    SkincareType::getId,
                    type -> type,
                    (left, right) -> left,
                    LinkedHashMap::new
                ));

        Map<Long, List<PatientSkincarePlanAttr>> planAttrsByPlanId = planIds.isEmpty()
            ? Map.of()
            : skincarePlanAttrRepo.findByPatientSkincarePlanIdInAndIsDeletedFalse(planIds).stream()
                .collect(Collectors.groupingBy(
                    PatientSkincarePlanAttr::getPatientSkincarePlanId,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));
        Map<Long, List<PatientSkincareRecordAttr>> recordAttrsByRecordId = recordIds.isEmpty()
            ? Map.of()
            : skincareRecordAttrRepo.findByPatientSkincareRecordIdInAndIsDeletedFalse(recordIds).stream()
                .collect(Collectors.groupingBy(
                    PatientSkincareRecordAttr::getPatientSkincareRecordId,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));

        List<Integer> attrIds = new ArrayList<>();
        planAttrsByPlanId.values().forEach(attrs -> attrs.stream()
            .map(PatientSkincarePlanAttr::getSkincareAttrId)
            .filter(id -> id != null)
            .forEach(attrIds::add));
        recordAttrsByRecordId.values().forEach(attrs -> attrs.stream()
            .map(PatientSkincareRecordAttr::getSkincareAttrId)
            .filter(id -> id != null)
            .forEach(attrIds::add));

        Map<Integer, SkincareTypeAttribute> attrById = attrIds.isEmpty()
            ? Map.of()
            : skincareTypeAttrRepo.findByIdInAndIsDeletedFalse(attrIds.stream().distinct().toList()).stream()
                .collect(Collectors.toMap(
                    SkincareTypeAttribute::getId,
                    attr -> attr,
                    (left, right) -> left,
                    LinkedHashMap::new
                ));

        return new SkincareData(
            planById,
            typeById,
            planAttrsByPlanId,
            recordAttrsByRecordId,
            attrById,
            accountNameByModifiedBy(records)
        );
    }

    private String attrsText(
        List<?> rawAttrs,
        Map<Integer, SkincareTypeAttribute> attrById,
        Set<String> attrCodeWhitelist,
        String sourceKind,
        Long recordId
    ) {
        List<SkincareAttrValue> values = rawAttrs.stream()
            .map(this::toAttrValue)
            .filter(value -> value != null)
            .sorted(attrValueComparator(attrById))
            .toList();

        List<String> texts = new ArrayList<>();
        for (SkincareAttrValue value : values) {
            SkincareTypeAttribute attr = attrById.get(value.attrId());
            if (attr == null) {
                log.warn(
                    "Compact skincare attribute not found, sourceKind={}, recordId={}, attrId={}",
                    sourceKind, recordId, value.attrId()
                );
                continue;
            }
            if (!attrCodeWhitelist.isEmpty() && !attrCodeWhitelist.contains(safe(attr.getAttrCode()))) {
                continue;
            }
            String formattedValue = formatAttrValue(value, attr, sourceKind, recordId);
            if (StrUtils.isBlank(formattedValue)) {
                continue;
            }
            texts.add(safe(attr.getAttrName()) + ": " + formattedValue);
        }
        return String.join(", ", texts);
    }

    private SkincareAttrValue toAttrValue(Object attr) {
        if (attr instanceof PatientSkincarePlanAttr planAttr) {
            return new SkincareAttrValue(planAttr.getSkincareAttrId(), planAttr.getValue(), planAttr.getId());
        }
        if (attr instanceof PatientSkincareRecordAttr recordAttr) {
            return new SkincareAttrValue(recordAttr.getSkincareAttrId(), recordAttr.getValue(), recordAttr.getId());
        }
        return null;
    }

    private Comparator<SkincareAttrValue> attrValueComparator(Map<Integer, SkincareTypeAttribute> attrById) {
        return Comparator
            .comparingInt((SkincareAttrValue value) -> {
                SkincareTypeAttribute attr = attrById.get(value.attrId());
                return attr == null || attr.getDisplayOrder() == null ? Integer.MAX_VALUE : attr.getDisplayOrder();
            })
            .thenComparingInt(value -> {
                SkincareTypeAttribute attr = attrById.get(value.attrId());
                return attr == null || attr.getId() == null ? Integer.MAX_VALUE : attr.getId();
            })
            .thenComparing(SkincareAttrValue::sourceId, Comparator.nullsLast(Long::compareTo));
    }

    private String formatAttrValue(
        SkincareAttrValue value,
        SkincareTypeAttribute attr,
        String sourceKind,
        Long recordId
    ) {
        GenericValuePB genericValue = ProtoUtils.decodeGenericValue(value.encodedValue());
        if (genericValue == null) {
            log.warn(
                "Compact skincare attribute value decode failed, sourceKind={}, recordId={}, attrId={}, sourceId={}",
                sourceKind, recordId, value.attrId(), value.sourceId()
            );
            return "";
        }
        ValueMetaPB valueMeta = ProtoUtils.decodeValueMeta(attr.getAttrTypePb());
        if (valueMeta == null) {
            log.warn(
                "Compact skincare attribute value meta decode failed, sourceKind={}, recordId={}, attrId={}",
                sourceKind, recordId, value.attrId()
            );
            return "";
        }
        return ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta);
    }

    private String content(String typeName, String planAttrsText, String recordAttrsText) {
        StringBuilder builder = new StringBuilder();
        if (!StrUtils.isBlank(typeName)) {
            builder.append(typeName);
        }
        if (!StrUtils.isBlank(planAttrsText)) {
            if (!builder.isEmpty()) builder.append(" ");
            builder.append(planAttrsText);
        }
        if (!StrUtils.isBlank(recordAttrsText)) {
            if (!builder.isEmpty()) builder.append("; ");
            builder.append(recordAttrsText);
        }
        return builder.toString();
    }

    private List<String> skincareSignatureAccountRefs(List<PatientSkincareRecord> records) {
        List<String> result = new ArrayList<>();
        for (PatientSkincareRecord record : records) {
            if (!StrUtils.isBlank(record.getModifiedBy())) {
                result.add(record.getModifiedBy());
            }
        }
        return result;
    }

    private Map<String, String> accountNameByModifiedBy(List<PatientSkincareRecord> records) {
        Map<String, Long> accountIdByModifiedBy = new LinkedHashMap<>();
        for (PatientSkincareRecord record : records) {
            String modifiedBy = record.getModifiedBy();
            if (StrUtils.isBlank(modifiedBy)) continue;
            try {
                accountIdByModifiedBy.putIfAbsent(modifiedBy, Long.parseLong(modifiedBy));
            } catch (NumberFormatException e) {
                log.warn(
                    "Compact skincare record modified_by is not accounts.id, recordId={}, modifiedBy={}",
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
                    "Compact skincare record modified_by account not found, modifiedBy={}, accountId={}",
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

    private Set<String> attrCodeWhitelist() {
        if (reportProperties == null
            || reportProperties.getCompact() == null
            || reportProperties.getCompact().getSkincare() == null
            || StrUtils.isBlank(reportProperties.getCompact().getSkincare().getAttrCodeWhitelist())) {
            return Set.of();
        }
        return java.util.Arrays.stream(reportProperties.getCompact().getSkincare().getAttrCodeWhitelist().split(","))
            .map(String::trim)
            .filter(value -> !StrUtils.isBlank(value))
            .collect(Collectors.toCollection(LinkedHashSet::new));
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
            font, (float) fontSize, availableWidth, (float) charSpacing,
            List.of(value == null ? "" : value));
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

    private record SkincareAttrValue(Integer attrId, String encodedValue, Long sourceId) {
    }

    private record SkincareData(
        Map<Long, PatientSkincarePlan> planById,
        Map<Integer, SkincareType> typeById,
        Map<Long, List<PatientSkincarePlanAttr>> planAttrsByPlanId,
        Map<Long, List<PatientSkincareRecordAttr>> recordAttrsByRecordId,
        Map<Integer, SkincareTypeAttribute> attrById,
        Map<String, String> accountNameByModifiedBy
    ) {
    }

    private record SkincareRows(
        List<JfkValPB> recordTime,
        List<JfkValPB> content,
        List<JfkValPB> recordedBy
    ) {
        private SkincareRows() {
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
    private static final int RECORDED_BY_COL_INDEX = 2;
    private static final double DEFAULT_FONT_SIZE = 8d;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final MonitoringWindowResolver monitoringWindowResolver;
    private final PatientSkincareRecordRepository skincareRecordRepo;
    private final PatientSkincarePlanRepository skincarePlanRepo;
    private final PatientSkincarePlanAttrRepository skincarePlanAttrRepo;
    private final PatientSkincareRecordAttrRepository skincareRecordAttrRepo;
    private final SkincareTypeRepository skincareTypeRepo;
    private final SkincareTypeAttributeRepository skincareTypeAttrRepo;
    private final AccountRepository accountRepo;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
