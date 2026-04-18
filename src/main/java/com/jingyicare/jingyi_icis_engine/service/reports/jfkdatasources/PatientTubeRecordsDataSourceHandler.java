package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Duration;
import java.time.LocalDate;
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

import com.jingyicare.jingyi_icis_engine.entity.tubes.PatientTubeRecord;
import com.jingyicare.jingyi_icis_engine.entity.tubes.PatientTubeStatusRecord;
import com.jingyicare.jingyi_icis_engine.entity.tubes.TubeTypeStatus;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.ShiftSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.tubes.PatientTubeRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.tubes.PatientTubeStatusRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.tubes.TubeTypeStatusRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.service.shifts.ConfigShiftUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
@Slf4j
public class PatientTubeRecordsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientTubeRecordsDataSourceHandler(
        JfkDataSourceSupport support,
        MonitoringWindowResolver monitoringWindowResolver,
        PatientTubeRecordRepository tubeRecordRepo,
        PatientTubeStatusRecordRepository tubeStatusRecordRepo,
        TubeTypeStatusRepository tubeTypeStatusRepo,
        ConfigShiftUtils configShiftUtils,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.monitoringWindowResolver = monitoringWindowResolver;
        this.tubeRecordRepo = tubeRecordRepo;
        this.tubeStatusRecordRepo = tubeStatusRecordRepo;
        this.tubeTypeStatusRepo = tubeTypeStatusRepo;
        this.configShiftUtils = configShiftUtils;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.PATIENT_TUBE_RECORDS;
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
                "Compact tube records dept_id mismatch, pid={}, requestDeptId={}, patientDeptId={}",
                pid, requestDeptId, window.deptId()
            );
        }

        List<PatientTubeRecord> records = tubeRecordRepo.findReportTubeRecords(
            pid, window.monStartUtc(), window.monEndUtc());

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        if (records.isEmpty()) {
            addEmptyOutputs(outputBuilder);
            return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
        }

        ShiftSettingsPB shiftSettings = configShiftUtils.getShiftByDeptId(window.deptId());
        if (shiftSettings == null || shiftSettings.getShiftCount() == 0) {
            return error(StatusCode.NO_SHIFT_SETTING, "deptId: " + window.deptId());
        }

        List<ShiftRange> shiftRanges = shiftRanges(shiftSettings, window.monStartLocal(), window.monEndLocal());
        if (shiftRanges.isEmpty()) {
            return error(StatusCode.NO_SHIFT_SETTING, "deptId: " + window.deptId());
        }

        Map<Long, LocalDateTime> rootInsertedAtByRecordId = rootInsertedAtByRecordId(records);
        Map<Long, List<PatientTubeStatusRecord>> statusesByRecordId = statusesByRecordId(records, window);
        Map<Integer, TubeTypeStatus> statusMetaById = statusMetaById(statusesByRecordId);

        TubeRows rows;
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            rows = buildRows(
                tableId, records, rootInsertedAtByRecordId, statusesByRecordId, statusMetaById,
                shiftRanges, window, colWidths, font, fontSize, charSpacing, hPadding
            );
        } catch (IOException e) {
            log.error("Failed to wrap compact patient tube records text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        addOutput(outputBuilder, FIELD_TUBE_NAME, rows.tubeName());
        addOutput(outputBuilder, FIELD_INSERTED_AT, rows.insertedAt());
        addOutput(outputBuilder, FIELD_DURATION_DAYS, rows.durationDays());
        addOutput(outputBuilder, FIELD_MAINTENANCE_STATUS, rows.maintenanceStatus());
        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private TubeRows buildRows(
        String tableId,
        List<PatientTubeRecord> records,
        Map<Long, LocalDateTime> rootInsertedAtByRecordId,
        Map<Long, List<PatientTubeStatusRecord>> statusesByRecordId,
        Map<Integer, TubeTypeStatus> statusMetaById,
        List<ShiftRange> shiftRanges,
        MonitoringWindow window,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        TubeRows rows = new TubeRows();
        Comparator<PatientTubeRecord> recordComparator = Comparator
                .<PatientTubeRecord, LocalDateTime>comparing(
                    record -> rootInsertedAtByRecordId.getOrDefault(record.getId(), record.getInsertedAt()),
                    Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(PatientTubeRecord::getId, Comparator.nullsLast(Long::compareTo));
        List<PatientTubeRecord> sorted = records.stream()
            .sorted(recordComparator)
            .toList();

        for (PatientTubeRecord record : sorted) {
            LocalDateTime rootInsertedAt = rootInsertedAtByRecordId.getOrDefault(record.getId(), record.getInsertedAt());
            rows.add(
                stringsVal(wrap(safe(record.getTubeName()), TUBE_NAME_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding)),
                stringsVal(wrap(formatLocal(rootInsertedAt), INSERTED_AT_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding)),
                stringsVal(wrap(durationDays(rootInsertedAt, record.getRemovedAt(), window), DURATION_DAYS_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding)),
                stringsVal(wrapLines(
                    maintenanceStatusLines(
                        tableId, record, statusesByRecordId.getOrDefault(record.getId(), List.of()), statusMetaById, shiftRanges),
                    MAINTENANCE_STATUS_COL_INDEX, colWidths, font, fontSize, charSpacing, hPadding
                ))
            );
        }
        return rows;
    }

    private Map<Long, LocalDateTime> rootInsertedAtByRecordId(List<PatientTubeRecord> records) {
        Set<Long> rootIdsToFetch = new LinkedHashSet<>();
        for (PatientTubeRecord record : records) {
            Long rootId = rootId(record);
            if (rootId != null && !rootId.equals(record.getId())) {
                rootIdsToFetch.add(rootId);
            }
        }

        Map<Long, List<PatientTubeRecord>> chainByRootId = new LinkedHashMap<>();
        if (!rootIdsToFetch.isEmpty()) {
            for (PatientTubeRecord root : tubeRecordRepo.findByIdInAndIsDeletedFalse(new ArrayList<>(rootIdsToFetch))) {
                Long rootId = rootId(root);
                chainByRootId.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(root);
            }
            for (PatientTubeRecord chainRecord : tubeRecordRepo.findByRootTubeRecordIdInAndIsDeletedFalse(new ArrayList<>(rootIdsToFetch))) {
                Long rootId = rootId(chainRecord);
                chainByRootId.computeIfAbsent(rootId, ignored -> new ArrayList<>()).add(chainRecord);
            }
        }

        Map<Long, LocalDateTime> result = new LinkedHashMap<>();
        for (PatientTubeRecord record : records) {
            Long rootId = rootId(record);
            if (rootId == null || rootId.equals(record.getId())) {
                result.put(record.getId(), record.getInsertedAt());
                continue;
            }

            LocalDateTime rootInsertedAt = chainByRootId.getOrDefault(rootId, List.of()).stream()
                .map(PatientTubeRecord::getInsertedAt)
                .filter(time -> time != null)
                .min(LocalDateTime::compareTo)
                .orElse(null);
            if (rootInsertedAt == null) {
                log.warn(
                    "Compact tube root record not found, recordId={}, rootTubeRecordId={}",
                    record.getId(), rootId
                );
                rootInsertedAt = record.getInsertedAt();
            }
            result.put(record.getId(), rootInsertedAt);
        }
        return result;
    }

    private Long rootId(PatientTubeRecord record) {
        if (record == null) return null;
        return record.getRootTubeRecordId() == null ? record.getId() : record.getRootTubeRecordId();
    }

    private Map<Long, List<PatientTubeStatusRecord>> statusesByRecordId(
        List<PatientTubeRecord> records,
        MonitoringWindow window
    ) {
        List<Long> recordIds = records.stream()
            .map(PatientTubeRecord::getId)
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
        if (recordIds.isEmpty()) return Map.of();
        return tubeStatusRecordRepo.findReportStatusRecords(recordIds, window.monStartUtc(), window.monEndUtc()).stream()
            .collect(Collectors.groupingBy(
                PatientTubeStatusRecord::getPatientTubeRecordId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
    }

    private Map<Integer, TubeTypeStatus> statusMetaById(Map<Long, List<PatientTubeStatusRecord>> statusesByRecordId) {
        List<Integer> statusIds = statusesByRecordId.values().stream()
            .flatMap(List::stream)
            .map(PatientTubeStatusRecord::getTubeStatusId)
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
        if (statusIds.isEmpty()) return Map.of();
        return tubeTypeStatusRepo.findByIdInAndIsDeletedFalse(statusIds).stream()
            .collect(Collectors.toMap(
                TubeTypeStatus::getId,
                status -> status,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private List<String> maintenanceStatusLines(
        String tableId,
        PatientTubeRecord record,
        List<PatientTubeStatusRecord> statuses,
        Map<Integer, TubeTypeStatus> statusMetaById,
        List<ShiftRange> shiftRanges
    ) {
        if (statuses.isEmpty()) return List.of("");

        Map<ShiftRange, Map<LocalDateTime, List<PatientTubeStatusRecord>>> byShiftAndTime = new LinkedHashMap<>();
        for (PatientTubeStatusRecord status : statuses) {
            LocalDateTime recordedAt = status.getRecordedAt();
            LocalDateTime recordedAtLocal = TimeUtils.getLocalDateTimeFromUtc(recordedAt, support.getZoneId());
            ShiftRange shiftRange = shiftRange(recordedAtLocal, shiftRanges);
            if (shiftRange == null) {
                log.warn(
                    "Compact tube status outside shift ranges, tableId={}, recordId={}, statusRecordId={}, recordedAt={}",
                    tableId, record.getId(), status.getId(), recordedAt
                );
                continue;
            }
            byShiftAndTime
                .computeIfAbsent(shiftRange, ignored -> new LinkedHashMap<>())
                .computeIfAbsent(recordedAt, ignored -> new ArrayList<>())
                .add(status);
        }

        boolean firstRecordInShift = reportProperties.getCompact().getTubePolicy().isFirstRecordInShift();
        List<MaintenanceStatusLine> lines = new ArrayList<>();
        for (Map.Entry<ShiftRange, Map<LocalDateTime, List<PatientTubeStatusRecord>>> entry : byShiftAndTime.entrySet()) {
            LocalDateTime selectedRecordedAt = selectedRecordedAt(entry.getValue().keySet(), firstRecordInShift);
            if (selectedRecordedAt == null) continue;
            String statusText = statusText(
                tableId, record, entry.getValue().getOrDefault(selectedRecordedAt, List.of()), statusMetaById);
            if (StrUtils.isBlank(statusText)) continue;
            lines.add(new MaintenanceStatusLine(
                selectedRecordedAt,
                entry.getKey().name() + " " + formatLocal(selectedRecordedAt) + " " + statusText
            ));
        }
        return lines.isEmpty()
            ? List.of("")
            : lines.stream()
                .sorted(Comparator
                    .comparing(MaintenanceStatusLine::recordedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                    .thenComparing(MaintenanceStatusLine::line))
                .map(MaintenanceStatusLine::line)
                .toList();
    }

    private LocalDateTime selectedRecordedAt(Set<LocalDateTime> recordedAts, boolean firstRecordInShift) {
        return recordedAts.stream()
            .filter(time -> time != null)
            .min(firstRecordInShift ? LocalDateTime::compareTo : Comparator.reverseOrder())
            .orElse(null);
    }

    private String statusText(
        String tableId,
        PatientTubeRecord record,
        List<PatientTubeStatusRecord> statuses,
        Map<Integer, TubeTypeStatus> statusMetaById
    ) {
        return statuses.stream()
            .filter(status -> !StrUtils.isBlank(status.getValue()))
            .map(status -> statusValue(tableId, record, status, statusMetaById))
            .filter(value -> value != null)
            .sorted(Comparator
                .comparingInt(StatusValue::displayOrder)
                .thenComparingInt(StatusValue::statusId))
            .map(value -> value.name() + ": " + value.value())
            .collect(Collectors.joining(", "));
    }

    private StatusValue statusValue(
        String tableId,
        PatientTubeRecord record,
        PatientTubeStatusRecord status,
        Map<Integer, TubeTypeStatus> statusMetaById
    ) {
        TubeTypeStatus meta = statusMetaById.get(status.getTubeStatusId());
        if (meta == null) {
            log.warn(
                "Compact tube status meta not found, tableId={}, recordId={}, statusRecordId={}, tubeStatusId={}",
                tableId, record.getId(), status.getId(), status.getTubeStatusId()
            );
            return null;
        }
        return new StatusValue(
            status.getTubeStatusId(),
            meta.getName(),
            meta.getDisplayOrder() == null ? Integer.MAX_VALUE : meta.getDisplayOrder(),
            status.getValue()
        );
    }

    private ShiftRange shiftRange(LocalDateTime recordedAtLocal, List<ShiftRange> shiftRanges) {
        if (recordedAtLocal == null) return null;
        for (ShiftRange shiftRange : shiftRanges) {
            if (!recordedAtLocal.isBefore(shiftRange.startLocal())
                && recordedAtLocal.isBefore(shiftRange.endLocal())) {
                return shiftRange;
            }
        }
        return null;
    }

    private List<ShiftRange> shiftRanges(
        ShiftSettingsPB shiftSettings,
        LocalDateTime monStartLocal,
        LocalDateTime monEndLocal
    ) {
        List<ShiftRange> ranges = new ArrayList<>();
        LocalDate startDate = monStartLocal.toLocalDate().minusDays(1);
        LocalDate endDate = monEndLocal.toLocalDate().plusDays(1);
        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            for (ShiftSettingsPB.Shift shift : shiftSettings.getShiftList()) {
                if (ALL_DAY_SHIFT_NAME.equals(shift.getName())) continue;
                if (!shift.hasDefaultShift()) continue;
                int hours = shift.getDefaultShift().getHours();
                int startHour = shift.getDefaultShift().getStartHour();
                if (!TimeUtils.isValidHour(startHour) || hours <= 0) continue;
                LocalDateTime start = date.atStartOfDay().plusHours(startHour);
                LocalDateTime end = start.plusHours(hours);
                if (end.isAfter(monStartLocal) && start.isBefore(monEndLocal)) {
                    ranges.add(new ShiftRange(shift.getName(), start, end));
                }
            }
        }
        ranges.sort(Comparator
            .comparing(ShiftRange::startLocal)
            .thenComparing(ShiftRange::name));
        return ranges;
    }

    private String durationDays(LocalDateTime rootInsertedAt, LocalDateTime removedAt, MonitoringWindow window) {
        if (rootInsertedAt == null) return "";
        LocalDateTime rootInsertedLocal = TimeUtils.getLocalDateTimeFromUtc(rootInsertedAt, support.getZoneId());
        LocalDateTime removedAtLocal = removedAt == null
            ? FUTURE_LOCAL
            : TimeUtils.getLocalDateTimeFromUtc(removedAt, support.getZoneId());
        LocalDateTime cutoffLocal = window.monStartLocal().isBefore(removedAtLocal) ? window.monStartLocal() : removedAtLocal;
        LocalDateTime insertedShiftStartLocal = shiftStartLocal(rootInsertedLocal, window.monStartHour());
        long hours = Math.max(0L, Duration.between(insertedShiftStartLocal, cutoffLocal).toHours());
        long days = hours / 24 + 1;
        return days + "天";
    }

    private LocalDateTime shiftStartLocal(LocalDateTime localTime, int startHour) {
        LocalDateTime dayStart = localTime.toLocalDate().atStartOfDay().plusHours(startHour);
        return localTime.isBefore(dayStart) ? dayStart.minusDays(1) : dayStart;
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
        return wrapLines(List.of(value == null ? "" : value), colIndex, colWidths, font, fontSize, charSpacing, hPadding);
    }

    private List<String> wrapLines(
        List<String> lines,
        int colIndex,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        if (font == null || colIndex < 0 || colIndex >= colWidths.size()) {
            return lines == null || lines.isEmpty() ? List.of("") : lines;
        }
        float availableWidth = (float) Math.max(0d, colWidths.get(colIndex) - 2d * Math.max(0d, hPadding));
        return JfkPdfUtils.getWrappedLines(
            font, (float) fontSize, availableWidth, (float) charSpacing,
            lines == null || lines.isEmpty() ? List.of("") : lines);
    }

    private PDFont loadFont(PDDocument document) throws IOException {
        Resource fontResource = resourceLoader.getResource(reportProperties.getCompact().getFont());
        try (var inputStream = fontResource.getInputStream()) {
            return PDType0Font.load(document, new ByteArrayInputStream(inputStream.readAllBytes()));
        }
    }

    private void addEmptyOutputs(JfkDataSourcePB.Builder outputBuilder) {
        addOutput(outputBuilder, FIELD_TUBE_NAME, List.of());
        addOutput(outputBuilder, FIELD_INSERTED_AT, List.of());
        addOutput(outputBuilder, FIELD_DURATION_DAYS, List.of());
        addOutput(outputBuilder, FIELD_MAINTENANCE_STATUS, List.of());
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

    private record ShiftRange(String name, LocalDateTime startLocal, LocalDateTime endLocal) {
    }

    private record StatusValue(int statusId, String name, int displayOrder, String value) {
    }

    private record MaintenanceStatusLine(LocalDateTime recordedAt, String line) {
    }

    private record TubeRows(
        List<JfkValPB> tubeName,
        List<JfkValPB> insertedAt,
        List<JfkValPB> durationDays,
        List<JfkValPB> maintenanceStatus
    ) {
        private TubeRows() {
            this(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        }

        private void add(
            JfkValPB tubeNameVal,
            JfkValPB insertedAtVal,
            JfkValPB durationDaysVal,
            JfkValPB maintenanceStatusVal
        ) {
            tubeName.add(tubeNameVal);
            insertedAt.add(insertedAtVal);
            durationDays.add(durationDaysVal);
            maintenanceStatus.add(maintenanceStatusVal);
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
    private static final String FIELD_TUBE_NAME = "tube_name";
    private static final String FIELD_INSERTED_AT = "inserted_at";
    private static final String FIELD_DURATION_DAYS = "duration_days";
    private static final String FIELD_MAINTENANCE_STATUS = "maintenance_status";
    private static final int TUBE_NAME_COL_INDEX = 0;
    private static final int INSERTED_AT_COL_INDEX = 1;
    private static final int DURATION_DAYS_COL_INDEX = 2;
    private static final int MAINTENANCE_STATUS_COL_INDEX = 3;
    private static final double DEFAULT_FONT_SIZE = 8d;
    private static final LocalDateTime FUTURE_LOCAL = LocalDateTime.of(9999, 1, 1, 0, 0);
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String ALL_DAY_SHIFT_NAME = "全天";

    private final MonitoringWindowResolver monitoringWindowResolver;
    private final PatientTubeRecordRepository tubeRecordRepo;
    private final PatientTubeStatusRecordRepository tubeStatusRecordRepo;
    private final TubeTypeStatusRepository tubeTypeStatusRepo;
    private final ConfigShiftUtils configShiftUtils;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
