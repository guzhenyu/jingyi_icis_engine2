package com.jingyicare.jingyi_icis_engine.service.reports.ah2report.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

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
}
