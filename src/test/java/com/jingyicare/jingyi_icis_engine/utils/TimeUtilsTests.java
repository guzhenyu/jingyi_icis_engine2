package com.jingyicare.jingyi_icis_engine.utils;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class TimeUtilsTests {
    @Test
    void shouldFormatSecondsAndPreserveExistingMinutePrecision() {
        LocalDateTime time = LocalDateTime.parse("2026-11-04T01:02:03.987654321");

        assertThat(TimeUtils.toIso8601StringWithSeconds(time, "UTC"))
            .isEqualTo("2026-11-04T01:02:03Z");
        assertThat(TimeUtils.toIso8601StringWithSeconds(time, "Asia/Shanghai"))
            .isEqualTo("2026-11-04T09:02:03+08:00");
        assertThat(TimeUtils.toIso8601String(time, "Asia/Shanghai"))
            .isEqualTo("2026-11-04T09:02+08:00");
        assertThat(TimeUtils.fromIso8601String(
            TimeUtils.toIso8601StringWithSeconds(time, "Asia/Shanghai"), "UTC"))
            .isEqualTo(time.withNano(0));
    }

    @Test
    void shouldUseOffsetAtTheUtcInstantAcrossDaylightSavingOverlap() {
        assertThat(TimeUtils.toIso8601StringWithSeconds(
            LocalDateTime.parse("2026-11-01T05:30:17"), "America/New_York"))
            .isEqualTo("2026-11-01T01:30:17-04:00");
        assertThat(TimeUtils.toIso8601StringWithSeconds(
            LocalDateTime.parse("2026-11-01T06:30:17"), "America/New_York"))
            .isEqualTo("2026-11-01T01:30:17-05:00");
    }

    @Test
    void shouldKeepNullAsEmptyString() {
        assertThat(TimeUtils.toIso8601StringWithSeconds(null, "UTC")).isEmpty();
        assertThat(TimeUtils.toIso8601String(null, "UTC")).isEmpty();
    }
}
