package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
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

import com.jingyicare.jingyi_icis_engine.entity.medications.AdministrationRoute;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionAction;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecord;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecordStat;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationOrderGroup;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedOrderGroupSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedicationDosageGroupPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.medications.AdministrationRouteRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionActionRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationExecutionRecordStatRepository;
import com.jingyicare.jingyi_icis_engine.repository.medications.MedicationOrderGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationConfig;
import com.jingyicare.jingyi_icis_engine.service.medications.MedMonitoringService;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
@Slf4j
public class MedexeRecordsDataSourceHandler extends AbstractJfkDataSourceHandler {
    public MedexeRecordsDataSourceHandler(
        JfkDataSourceSupport support,
        BalanceStatsShiftRepository balanceStatsShiftRepo,
        MedicationExecutionRecordRepository exeRecordRepo,
        MedicationExecutionRecordStatRepository statRepo,
        MedicationOrderGroupRepository orderGroupRepo,
        MedicationExecutionActionRepository actionRepo,
        AdministrationRouteRepository routeRepo,
        MedicationConfig medConfig,
        MedMonitoringService medMonitoringService,
        ReportProperties reportProperties,
        ResourceLoader resourceLoader
    ) {
        super(support);
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
        this.exeRecordRepo = exeRecordRepo;
        this.statRepo = statRepo;
        this.orderGroupRepo = orderGroupRepo;
        this.actionRepo = actionRepo;
        this.routeRepo = routeRepo;
        this.medConfig = medConfig;
        this.medMonitoringService = medMonitoringService;
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.MEDEXE_RECORDS;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, FIELD_PID);
        String requestDeptId = getDeptIdInput(input, FIELD_DEPT_ID);
        String queryStartIso = getStringInput(input, FIELD_QUERY_START);
        String tableId = getStringInput(input, FIELD_TABLE_ID);
        Long intakeTypeIdInput = getInt64Input(input, FIELD_INTAKE_TYPE_ID);
        List<Double> colWidths = getDoubleArrayInput(input, FIELD_COL_WIDTHS);
        double fontSize = getDoubleInput(input, FIELD_FONT_SIZE, DEFAULT_FONT_SIZE);
        double charSpacing = getDoubleInput(input, FIELD_CHAR_SPACING, 0d);
        double hPadding = getDoubleInput(input, FIELD_H_PADDING, 0d);

        List<String> missingFields = new ArrayList<>();
        if (StrUtils.isBlank(queryStartIso)) missingFields.add(FIELD_QUERY_START);
        if (StrUtils.isBlank(tableId)) missingFields.add(FIELD_TABLE_ID);
        if (intakeTypeIdInput == null) missingFields.add(FIELD_INTAKE_TYPE_ID);
        if (!missingFields.isEmpty()) {
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        boolean hasValidPid = pid != null && pid > 0;
        String deptId = requestDeptId;
        if (hasValidPid) {
            PatientRecord patient = support.getPatientService().getPatientRecord(pid);
            if (patient == null) {
                return error(StatusCode.PATIENT_NOT_FOUND, "pid: " + pid);
            }
            if (!StrUtils.isBlank(patient.getDeptId())) {
                deptId = patient.getDeptId();
                if (!StrUtils.isBlank(requestDeptId) && !requestDeptId.equals(deptId)) {
                    log.warn(
                        "Compact medexe records dept_id mismatch, pid={}, requestDeptId={}, patientDeptId={}",
                        pid, requestDeptId, deptId
                    );
                }
            }
        }
        if (StrUtils.isBlank(deptId)) {
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, FIELD_DEPT_ID + " ");
        }

        MedexeWindow window = resolveMedexeWindow(deptId, queryStartIso);
        if (window.errorCode() != null) {
            return error(window.errorCode(), window.errorDetail());
        }

        int intakeTypeId = intakeTypeIdInput.intValue();
        List<RowData> rows = hasValidPid
            ? buildRows(pid, deptId, tableId, intakeTypeId, window.startUtc(), window.endUtc())
            : List.of();

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        try (PDDocument document = new PDDocument()) {
            PDFont font = loadFont(document);
            addOutput(outputBuilder, FIELD_MED_ORDER_TXT, buildMedOrderVals(rows, colWidths, font, fontSize, charSpacing, hPadding));
            addOutput(outputBuilder, FIELD_ROUTE_TXT, buildRouteVals(rows, colWidths, font, fontSize, charSpacing, hPadding));
            addOutput(outputBuilder, FIELD_ACC_ML, buildAccVals(rows, colWidths, font, fontSize, charSpacing, hPadding));
            for (int hourIndex = 0; hourIndex < 24; hourIndex++) {
                addOutput(
                    outputBuilder,
                    "hour" + (hourIndex + 1),
                    buildHourVals(rows, hourIndex, window.startUtc(), colWidths, font, fontSize, charSpacing, hPadding)
                );
            }
        } catch (IOException e) {
            log.error("Failed to wrap compact medexe records text: {}", e.getMessage(), e);
            return error(StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private MedexeWindow resolveMedexeWindow(String deptId, String queryStartIso) {
        LocalDateTime utcStart = TimeUtils.fromIso8601String(queryStartIso, "UTC");
        if (utcStart == null) {
            return MedexeWindow.error(StatusCode.INVALID_TIME_FORMAT, FIELD_QUERY_START);
        }
        LocalDateTime utcEnd = utcStart.plusHours(24);

        BalanceStatsShift shift = balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId, utcEnd)
            .orElse(null);
        if (shift == null) {
            return MedexeWindow.error(StatusCode.BALANCE_STATS_SHIFT_NOT_FOUND, "deptId: " + deptId);
        }

        Integer startHour = shift.getStartHour();
        if (startHour == null || !TimeUtils.isValidHour(startHour)) {
            return MedexeWindow.error(
                StatusCode.INVALID_PARAM_VALUE,
                "deptId: " + deptId + ", balanceStatsShiftId: " + shift.getId() + ", start_hour: " + startHour
            );
        }

        LocalDateTime utcMiddle = utcStart.plusHours(12);
        LocalDateTime localMiddle = TimeUtils.getLocalDateTimeFromUtc(utcMiddle, support.getZoneId());
        LocalDateTime localMidnight = localMiddle.toLocalDate().atStartOfDay();
        LocalDateTime startUtc = TimeUtils.getUtcFromLocalDateTime(
            localMidnight.plusHours(startHour), support.getZoneId());
        return new MedexeWindow(null, "", startUtc, startUtc.plusHours(24));
    }

    private List<RowData> buildRows(
        Long pid,
        String deptId,
        String tableId,
        int intakeTypeId,
        LocalDateTime startUtc,
        LocalDateTime endUtc
    ) {
        List<MedicationExecutionRecord> records = findExecutionRecords(pid, startUtc, endUtc);
        if (records.isEmpty()) return List.of();

        Map<Long, MedicationOrderGroup> groupById = findGroups(records);
        Map<Long, List<MedicationExecutionRecord>> recordsByGroupId = records.stream()
            .filter(record -> groupById.containsKey(record.getMedicationOrderGroupId()))
            .collect(Collectors.groupingBy(
                MedicationExecutionRecord::getMedicationOrderGroupId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
        if (recordsByGroupId.isEmpty()) return List.of();

        Map<String, AdministrationRoute> routeByCode = findRoutes(deptId, recordsByGroupId, groupById);
        Map<Long, List<MedicationExecutionRecordStat>> statsByRecordId =
            statRepo.findAllByPatientIdAndStatsTimeRange(pid, startUtc, endUtc).stream()
                .filter(stat -> stat.getExeRecordId() != null)
                .collect(Collectors.groupingBy(
                    MedicationExecutionRecordStat::getExeRecordId,
                    LinkedHashMap::new,
                    Collectors.toList()
                ));
        Map<Long, List<MedicationExecutionAction>> actionsByRecordId = findActions(records);
        MedOrderGroupSettingsPB medOrderGroupSettings = medConfig.getMedOrderGroupSettings(deptId);

        List<RowData> rows = new ArrayList<>();
        for (Map.Entry<Long, List<MedicationExecutionRecord>> entry : recordsByGroupId.entrySet()) {
            MedicationOrderGroup group = groupById.get(entry.getKey());
            RowData row = buildRow(
                tableId, deptId, intakeTypeId, group, entry.getValue(),
                routeByCode, statsByRecordId, actionsByRecordId, medOrderGroupSettings, startUtc, endUtc
            );
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort(Comparator
            .comparing(RowData::sortTime, Comparator.nullsLast(LocalDateTime::compareTo))
            .thenComparing(RowData::medOrderText, Comparator.nullsLast(String::compareTo))
            .thenComparing(RowData::groupId));
        return rows;
    }

    private List<MedicationExecutionRecord> findExecutionRecords(Long pid, LocalDateTime startUtc, LocalDateTime endUtc) {
        Map<Long, MedicationExecutionRecord> recordById = new LinkedHashMap<>();
        for (MedicationExecutionRecord record : exeRecordRepo.findInProgressRecordsByPatientId(pid, endUtc)) {
            if (record.getId() != null) {
                recordById.put(record.getId(), record);
            }
        }
        for (MedicationExecutionRecord record : exeRecordRepo.findCompletedRecordsByPatientId(pid, startUtc, endUtc)) {
            if (record.getId() != null) {
                recordById.put(record.getId(), record);
            }
        }
        return recordById.values().stream()
            .sorted(Comparator
                .comparing(MedexeRecordsDataSourceHandler::sortTime, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(MedicationExecutionRecord::getId))
            .toList();
    }

    private Map<Long, MedicationOrderGroup> findGroups(List<MedicationExecutionRecord> records) {
        List<Long> groupIds = records.stream()
            .map(MedicationExecutionRecord::getMedicationOrderGroupId)
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
        if (groupIds.isEmpty()) return Map.of();
        return orderGroupRepo.findByIds(groupIds).stream()
            .collect(Collectors.toMap(
                MedicationOrderGroup::getId,
                group -> group,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private Map<String, AdministrationRoute> findRoutes(
        String deptId,
        Map<Long, List<MedicationExecutionRecord>> recordsByGroupId,
        Map<Long, MedicationOrderGroup> groupById
    ) {
        Set<String> routeCodes = new LinkedHashSet<>();
        for (Map.Entry<Long, List<MedicationExecutionRecord>> entry : recordsByGroupId.entrySet()) {
            MedicationOrderGroup group = groupById.get(entry.getKey());
            if (group == null) continue;
            for (MedicationExecutionRecord record : entry.getValue()) {
                String routeCode = effectiveRouteCode(group, record);
                if (!StrUtils.isBlank(routeCode)) {
                    routeCodes.add(routeCode);
                }
            }
        }
        if (routeCodes.isEmpty()) return Map.of();
        return routeRepo.findByDeptIdAndCodeIn(deptId, new ArrayList<>(routeCodes)).stream()
            .filter(route -> !StrUtils.isBlank(route.getCode()))
            .collect(Collectors.toMap(
                AdministrationRoute::getCode,
                route -> route,
                (left, right) -> left,
                LinkedHashMap::new
            ));
    }

    private Map<Long, List<MedicationExecutionAction>> findActions(List<MedicationExecutionRecord> records) {
        List<Long> recordIds = records.stream()
            .map(MedicationExecutionRecord::getId)
            .filter(id -> id != null && id > 0)
            .distinct()
            .toList();
        if (recordIds.isEmpty()) return Map.of();
        return actionRepo.findByMedicationExecutionRecordIdInAndIsDeletedFalse(recordIds).stream()
            .sorted(Comparator
                .comparing(MedicationExecutionAction::getCreatedAt, Comparator.nullsLast(LocalDateTime::compareTo))
                .thenComparing(MedicationExecutionAction::getId, Comparator.nullsLast(Long::compareTo)))
            .collect(Collectors.groupingBy(
                MedicationExecutionAction::getMedicationExecutionRecordId,
                LinkedHashMap::new,
                Collectors.toList()
            ));
    }

    private RowData buildRow(
        String tableId,
        String deptId,
        int intakeTypeId,
        MedicationOrderGroup group,
        List<MedicationExecutionRecord> records,
        Map<String, AdministrationRoute> routeByCode,
        Map<Long, List<MedicationExecutionRecordStat>> statsByRecordId,
        Map<Long, List<MedicationExecutionAction>> actionsByRecordId,
        MedOrderGroupSettingsPB medOrderGroupSettings,
        LocalDateTime startUtc,
        LocalDateTime endUtc
    ) {
        if (group == null || records.isEmpty()) return null;
        String routeCode = chooseRouteCode(tableId, group, records, routeByCode);
        if (StrUtils.isBlank(routeCode)) return null;
        AdministrationRoute route = routeByCode.get(routeCode);
        if (route == null) {
            log.warn(
                "Compact medexe route not found, tableId={}, deptId={}, groupId={}, routeCode={}",
                tableId, deptId, group.getId(), routeCode
            );
            return null;
        }
        if (intakeTypeId > 0 && (route.getIntakeTypeId() == null || route.getIntakeTypeId() != intakeTypeId)) {
            return null;
        }

        RowData row = new RowData(group.getId(), sortTime(records.get(0)), route.getName(), route.getIsContinuous());
        for (MedicationExecutionRecord record : records) {
            Boolean recordContinuous = effectiveContinuous(route, record);
            row.mergeContinuous(tableId, record.getId(), recordContinuous);

            MedicationDosageGroupPB dosageGroup = medMonitoringService.getDosageGroupPB(group, record);
            if (dosageGroup == null || dosageGroup.getMdCount() == 0) {
                log.warn(
                    "Compact medexe dosage group not found, tableId={}, groupId={}, recordId={}",
                    tableId, group.getId(), record.getId()
                );
                continue;
            }
            if (StrUtils.isBlank(row.medOrderText())) {
                row.medOrderText = medOrderText(medOrderGroupSettings, record, dosageGroup);
            }

            List<MedicationExecutionAction> actions = actionsByRecordId.getOrDefault(record.getId(), List.of());
            addStatsOrCalculated(row, record, recordContinuous, dosageGroup, actions,
                statsByRecordId.getOrDefault(record.getId(), List.of()), startUtc, endUtc);
        }
        return row.hasAnyDisplayableRecord() ? row : null;
    }

    private String medOrderText(
        MedOrderGroupSettingsPB medOrderGroupSettings,
        MedicationExecutionRecord record,
        MedicationDosageGroupPB effectiveDosageGroup
    ) {
        MedicationDosageGroupPB recordDosageGroup = decodeRecordDosageGroup(record);
        if (recordDosageGroup != null) {
            if (recordDosageGroup.getMdCount() == 0) {
                return recordDosageGroup.getDisplayName();
            }
            return medConfig.getDosageGroupDisplayName(medOrderGroupSettings, recordDosageGroup);
        }

        if (effectiveDosageGroup == null) return "";
        if (effectiveDosageGroup.getMdCount() == 0) {
            return effectiveDosageGroup.getDisplayName();
        }
        return medConfig.getDosageGroupDisplayName(medOrderGroupSettings, effectiveDosageGroup);
    }

    private MedicationDosageGroupPB decodeRecordDosageGroup(MedicationExecutionRecord record) {
        if (record == null || StrUtils.isBlank(record.getMedicationDosageGroup())) return null;
        return ProtoUtils.decodeDosageGroup(record.getMedicationDosageGroup());
    }

    private void addStatsOrCalculated(
        RowData row,
        MedicationExecutionRecord record,
        Boolean isContinuous,
        MedicationDosageGroupPB dosageGroup,
        List<MedicationExecutionAction> actions,
        List<MedicationExecutionRecordStat> stats,
        LocalDateTime startUtc,
        LocalDateTime endUtc
    ) {
        if (!stats.isEmpty()) {
            for (MedicationExecutionRecordStat stat : stats) {
                addHourValue(row, stat.getStatsTime(), stat.getConsumedMl(), startUtc, endUtc);
            }
        } else if (!actions.isEmpty()) {
            MedMonitoringService.FluidIntakeData intake =
                medMonitoringService.calcFluidIntakeImpl(Boolean.TRUE.equals(isContinuous), dosageGroup, actions, endUtc);
            if (intake != null && intake.intakeMap != null) {
                for (Map.Entry<LocalDateTime, Double> entry : intake.intakeMap.entrySet()) {
                    addHourValue(row, entry.getKey(), entry.getValue(), startUtc, endUtc);
                }
            }
        }

        if (!Boolean.TRUE.equals(row.isContinuous)) {
            addActionTimes(row, actions, startUtc, endUtc);
        }
    }

    private void addHourValue(
        RowData row,
        LocalDateTime statsTime,
        Double consumedMl,
        LocalDateTime startUtc,
        LocalDateTime endUtc
    ) {
        if (!isStrictHour(statsTime) || statsTime.isBefore(startUtc) || !statsTime.isBefore(endUtc)) return;
        long hourIndex = Duration.between(startUtc, statsTime).toHours();
        if (hourIndex < 0 || hourIndex >= 24 || !startUtc.plusHours(hourIndex).equals(statsTime)) return;
        if (consumedMl == null || consumedMl <= 0d) return;
        row.hours[(int) hourIndex] += consumedMl;
        row.hasHour[(int) hourIndex] = true;
    }

    private void addActionTimes(
        RowData row,
        List<MedicationExecutionAction> actions,
        LocalDateTime startUtc,
        LocalDateTime endUtc
    ) {
        for (MedicationExecutionAction action : actions) {
            LocalDateTime actionTime = action.getCreatedAt();
            if (actionTime == null || actionTime.isBefore(startUtc) || !actionTime.isBefore(endUtc)) continue;
            if (action.getIntakeVolMl() <= 0d) continue;
            long hourIndex = Duration.between(startUtc, actionTime).toHours();
            if (hourIndex < 0 || hourIndex >= 24) continue;
            int index = (int) hourIndex;
            LocalDateTime current = row.actionTimeUtc[index];
            if (current == null || actionTime.isBefore(current)) {
                row.actionTimeUtc[index] = actionTime;
            }
        }
    }

    private String chooseRouteCode(
        String tableId,
        MedicationOrderGroup group,
        List<MedicationExecutionRecord> records,
        Map<String, AdministrationRoute> routeByCode
    ) {
        String firstValidRouteCode = "";
        Set<String> routeCodes = new LinkedHashSet<>();
        for (MedicationExecutionRecord record : records) {
            String routeCode = effectiveRouteCode(group, record);
            if (StrUtils.isBlank(routeCode)) continue;
            if (routeByCode.containsKey(routeCode)) {
                routeCodes.add(routeCode);
                if (StrUtils.isBlank(firstValidRouteCode)) {
                    firstValidRouteCode = routeCode;
                }
            }
        }
        if (routeCodes.size() > 1) {
            log.warn(
                "Compact medexe group has multiple route codes, tableId={}, groupId={}, routeCodes={}, using={}",
                tableId, group.getId(), routeCodes, firstValidRouteCode
            );
        }
        return firstValidRouteCode;
    }

    private String effectiveRouteCode(MedicationOrderGroup group, MedicationExecutionRecord record) {
        if (record != null && !StrUtils.isBlank(record.getAdministrationRouteCode())) {
            return record.getAdministrationRouteCode();
        }
        return group == null ? "" : group.getAdministrationRouteCode();
    }

    private Boolean effectiveContinuous(AdministrationRoute route, MedicationExecutionRecord record) {
        return record != null && record.getIsContinuous() != null
            ? record.getIsContinuous()
            : route != null && Boolean.TRUE.equals(route.getIsContinuous());
    }

    private List<JfkValPB> buildMedOrderVals(
        List<RowData> rows,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        List<JfkValPB> vals = new ArrayList<>();
        for (RowData row : rows) {
            vals.add(stringsVal(wrap(row.medOrderText(), 0, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private List<JfkValPB> buildRouteVals(
        List<RowData> rows,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        List<JfkValPB> vals = new ArrayList<>();
        for (RowData row : rows) {
            vals.add(stringsVal(wrap(row.routeName(), 1, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private List<JfkValPB> buildAccVals(
        List<RowData> rows,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        List<JfkValPB> vals = new ArrayList<>();
        for (RowData row : rows) {
            vals.add(stringsVal(wrap(row.hasAnyHour() ? formatMl(row.accMl()) : "", 2, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
    }

    private List<JfkValPB> buildHourVals(
        List<RowData> rows,
        int hourIndex,
        LocalDateTime startUtc,
        List<Double> colWidths,
        PDFont font,
        double fontSize,
        double charSpacing,
        double hPadding
    ) throws IOException {
        List<JfkValPB> vals = new ArrayList<>();
        int colIndex = hourIndex + 3;
        for (RowData row : rows) {
            if (!row.hasHour[hourIndex]) {
                vals.add(stringsVal(List.of("")));
                continue;
            }
            List<String> lines = new ArrayList<>();
            lines.add(formatMl(row.hours[hourIndex]));
            if (!Boolean.TRUE.equals(row.isContinuous)) {
                LocalDateTime actionTimeUtc = row.actionTimeUtc[hourIndex];
                if (actionTimeUtc != null) {
                    LocalDateTime localActionTime = TimeUtils.getLocalDateTimeFromUtc(actionTimeUtc, support.getZoneId());
                    lines.add(HOUR_MINUTE_FORMATTER.format(localActionTime));
                }
            }
            vals.add(stringsVal(wrapLines(lines, colIndex, colWidths, font, fontSize, charSpacing, hPadding)));
        }
        return vals;
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
        return JfkPdfUtils.getWrappedLines(font, (float) fontSize, availableWidth, (float) charSpacing, lines);
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

    private String formatMl(double value) {
        int decimalPlaces = Math.max(0, reportProperties.getCompact().getMedicationMlDecimalPlaces());
        BigDecimal decimal = BigDecimal.valueOf(value)
            .setScale(decimalPlaces, RoundingMode.HALF_UP)
            .stripTrailingZeros();
        return decimal.compareTo(BigDecimal.ZERO) == 0 ? "0" : decimal.toPlainString();
    }

    private static LocalDateTime sortTime(MedicationExecutionRecord record) {
        return record == null ? null : record.getStartTime();
    }

    private boolean isStrictHour(LocalDateTime time) {
        return time != null
            && time.getMinute() == 0
            && time.getSecond() == 0
            && time.getNano() == 0;
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

    private record MedexeWindow(
        StatusCode errorCode,
        String errorDetail,
        LocalDateTime startUtc,
        LocalDateTime endUtc
    ) {
        static MedexeWindow error(StatusCode statusCode, String detail) {
            return new MedexeWindow(statusCode, detail, null, null);
        }
    }

    private static class RowData {
        private RowData(Long groupId, LocalDateTime sortTime, String routeName, Boolean isContinuous) {
            this.groupId = groupId == null ? 0L : groupId;
            this.sortTime = sortTime;
            this.routeName = routeName == null ? "" : routeName;
            this.isContinuous = isContinuous;
        }

        private void mergeContinuous(String tableId, Long recordId, Boolean recordContinuous) {
            if (isContinuous == null) {
                isContinuous = recordContinuous;
                return;
            }
            if (recordContinuous != null && !isContinuous.equals(recordContinuous)) {
                log.warn(
                    "Compact medexe row has mixed continuous flags, tableId={}, groupId={}, recordId={}, using={}",
                    tableId, groupId, recordId, isContinuous
                );
            }
        }

        private boolean hasAnyDisplayableRecord() {
            return !StrUtils.isBlank(medOrderText) || hasAnyHour();
        }

        private boolean hasAnyHour() {
            for (boolean has : hasHour) {
                if (has) return true;
            }
            return false;
        }

        private double accMl() {
            double result = 0d;
            for (int i = 0; i < hours.length; i++) {
                if (hasHour[i]) {
                    result += hours[i];
                }
            }
            return result;
        }

        private Long groupId() {
            return groupId;
        }

        private LocalDateTime sortTime() {
            return sortTime;
        }

        private String medOrderText() {
            return medOrderText == null ? "" : medOrderText;
        }

        private String routeName() {
            return routeName;
        }

        private final Long groupId;
        private final LocalDateTime sortTime;
        private final String routeName;
        private Boolean isContinuous;
        private String medOrderText = "";
        private final double[] hours = new double[24];
        private final boolean[] hasHour = new boolean[24];
        private final LocalDateTime[] actionTimeUtc = new LocalDateTime[24];
    }

    private static final String FIELD_PID = "pid";
    private static final String FIELD_DEPT_ID = "dept_id";
    private static final String FIELD_QUERY_START = "query_start";
    private static final String FIELD_TABLE_ID = "table_id";
    private static final String FIELD_INTAKE_TYPE_ID = "intake_type_id";
    private static final String FIELD_COL_WIDTHS = "col_widths";
    private static final String FIELD_FONT_SIZE = "font_size";
    private static final String FIELD_CHAR_SPACING = "char_spacing";
    private static final String FIELD_H_PADDING = "h_padding";
    private static final String FIELD_MED_ORDER_TXT = "med_order_txt";
    private static final String FIELD_ROUTE_TXT = "route_txt";
    private static final String FIELD_ACC_ML = "acc_ml";
    private static final double DEFAULT_FONT_SIZE = 8d;
    private static final DateTimeFormatter HOUR_MINUTE_FORMATTER = DateTimeFormatter.ofPattern("H:mm");

    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
    private final MedicationExecutionRecordRepository exeRecordRepo;
    private final MedicationExecutionRecordStatRepository statRepo;
    private final MedicationOrderGroupRepository orderGroupRepo;
    private final MedicationExecutionActionRepository actionRepo;
    private final AdministrationRouteRepository routeRepo;
    private final MedicationConfig medConfig;
    private final MedMonitoringService medMonitoringService;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
