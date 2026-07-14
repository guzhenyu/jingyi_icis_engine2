package com.jingyicare.jingyi_icis_engine.service.reports.ah2report.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.service.reports.ah2report.Ah2PageData;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

class XiuningAh2ReportDataTests {
    @Test
    void shouldSkipFutureFixedTubeStatsTimes() {
        String zoneId = "Asia/Shanghai";
        LocalDateTime startUtc = TimeUtils.getUtcFromLocalDateTime(
            LocalDateTime.of(2026, 7, 8, 7, 0), zoneId
        );
        LocalDateTime endUtc = TimeUtils.getUtcFromLocalDateTime(
            LocalDateTime.of(2026, 7, 9, 7, 0), zoneId
        );
        LocalDateTime nowUtc = TimeUtils.getUtcFromLocalDateTime(
            LocalDateTime.of(2026, 7, 8, 10, 30), zoneId
        );

        List<LocalDateTime> statsTimes = XiuningAh2ReportData.getFixedTubeStatsTimes(
            startUtc, endUtc, nowUtc, zoneId
        );

        assertEquals(
            List.of(TimeUtils.getUtcFromLocalDateTime(LocalDateTime.of(2026, 7, 8, 8, 0), zoneId)),
            statsTimes
        );
    }

    @Test
    void shouldClipLastPageDataEndToDischargeQueryEnd() {
        String zoneId = "Asia/Shanghai";
        LocalDateTime shiftEndUtc = TimeUtils.getUtcFromLocalDateTime(
            LocalDateTime.of(2026, 7, 10, 7, 0), zoneId
        );
        LocalDateTime dischargeUtc = TimeUtils.getUtcFromLocalDateTime(
            LocalDateTime.of(2026, 7, 9, 8, 24), zoneId
        );

        LocalDateTime dataEndUtc = XiuningAh2ReportData.resolvePageDataEnd(shiftEndUtc, dischargeUtc);

        assertEquals(dischargeUtc, dataEndUtc);
    }

    @Test
    void shouldOnlyAddFullDaySummaryAtNaturalShiftEnd() {
        String zoneId = "Asia/Shanghai";
        LocalDateTime shiftEndUtc = TimeUtils.getUtcFromLocalDateTime(
            LocalDateTime.of(2026, 7, 14, 7, 0), zoneId
        );
        LocalDateTime nextShiftEndUtc = TimeUtils.getUtcFromLocalDateTime(
            LocalDateTime.of(2026, 7, 15, 7, 0), zoneId
        );
        LocalDateTime clippedEndUtc = TimeUtils.getUtcFromLocalDateTime(
            LocalDateTime.of(2026, 7, 14, 7, 59), zoneId
        );

        assertTrue(XiuningAh2ReportData.shouldAddFullDaySummary(shiftEndUtc, shiftEndUtc));
        assertFalse(XiuningAh2ReportData.shouldAddFullDaySummary(clippedEndUtc, nextShiftEndUtc));
    }

    @Test
    void shouldMoveExactBoundaryRowsToPreviousShiftAndMerge() {
        LocalDateTime previousStart = LocalDateTime.of(2026, 7, 9, 23, 0);
        LocalDateTime boundary = LocalDateTime.of(2026, 7, 10, 23, 0);
        LocalDateTime currentEnd = LocalDateTime.of(2026, 7, 11, 23, 0);

        Ah2PageData previous = pageData(
            previousStart, boundary,
            row(boundary, Map.of(XiuningAh2ReportData.XN_INTAKE_AMOUNT, List.of("100"))),
            summary(boundary, "总计")
        );
        Ah2PageData current = pageData(
            boundary, currentEnd,
            row(boundary, Map.of(XiuningAh2ReportData.XN_TEMPERATURE, List.of("37"))),
            row(boundary.plusMinutes(1), Map.of(XiuningAh2ReportData.XN_HR, List.of("80")))
        );

        XiuningAh2ReportData.moveBoundaryRowsToPreviousShift(new ArrayList<>(List.of(previous, current)));

        assertEquals(2, previous.rowBlocks.size());
        Ah2PageData.RowBlock merged = previous.rowBlocks.get(0);
        assertFalse(merged.isSummaryRow);
        assertEquals(boundary, merged.timestamp);
        assertEquals(List.of("100"), merged.wrappedLinesByParam.get(XiuningAh2ReportData.XN_INTAKE_AMOUNT));
        assertEquals(List.of("37"), merged.wrappedLinesByParam.get(XiuningAh2ReportData.XN_TEMPERATURE));
        assertTrue(previous.rowBlocks.get(1).isSummaryRow);

        assertEquals(1, current.rowBlocks.size());
        assertEquals(boundary.plusMinutes(1), current.rowBlocks.get(0).timestamp);
        assertEquals(List.of("80"), current.rowBlocks.get(0).wrappedLinesByParam.get(XiuningAh2ReportData.XN_HR));
    }

    private Ah2PageData pageData(
        LocalDateTime start, LocalDateTime end, Ah2PageData.RowBlock... rowBlocks
    ) {
        Ah2PageData pageData = new Ah2PageData();
        pageData.pageStartTs = start;
        pageData.pageEndTs = end;
        pageData.rowBlocks.addAll(List.of(rowBlocks));
        return pageData;
    }

    private Ah2PageData.RowBlock row(LocalDateTime timestamp, Map<String, List<String>> lines) {
        Ah2PageData.RowBlock rowBlock = new Ah2PageData.RowBlock();
        rowBlock.timestamp = timestamp;
        rowBlock.wrappedLinesByParam = new HashMap<>(lines);
        return rowBlock;
    }

    private Ah2PageData.RowBlock summary(LocalDateTime timestamp, String value) {
        Ah2PageData.RowBlock rowBlock = new Ah2PageData.RowBlock();
        rowBlock.timestamp = timestamp;
        rowBlock.isSummaryRow = true;
        rowBlock.summary = new ArrayList<>(List.of(value));
        return rowBlock;
    }
}
