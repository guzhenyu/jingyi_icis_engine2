package com.jingyicare.jingyi_icis_engine.utils;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public class TimeUtils {
    // 生成LocalDateTime
    public static LocalDateTime getNowUtc() {
        return LocalDateTime.now(ZoneOffset.UTC);
    }

    public static LocalDateTime getLocalTime(int year, int month, int dayOfMonth) {
        return LocalDateTime.of(year, month, dayOfMonth, 0, 0);
    }

    public static LocalDateTime getLocalTime(int year, int month, int dayOfMonth, int hour, int minute) {
        return LocalDateTime.of(year, month, dayOfMonth, hour, minute);
    }

    public static final LocalDateTime getLocalDateTimeFromUtc(LocalDateTime utcDateTime, String zoneId) {
        if (utcDateTime == null) return null;
        ZonedDateTime zdtSrc = utcDateTime.atZone(ZoneId.of("UTC"));
        ZonedDateTime zdtDst = zdtSrc.withZoneSameInstant(ZoneId.of(zoneId));
        return zdtDst.toLocalDateTime();
    }

    public static final LocalDateTime getUtcFromLocalDateTime(LocalDateTime localDateTime, String zoneId) {
        if (localDateTime == null) return null;
        ZonedDateTime zdtSrc = localDateTime.atZone(ZoneId.of(zoneId));
        ZonedDateTime zdtDst = zdtSrc.withZoneSameInstant(ZoneId.of("UTC"));
        return zdtDst.toLocalDateTime();
    }

    public static LocalDateTime getMin() {
        return LocalDateTime.of(1900, 1, 1, 0, 0);
    }

    public static LocalDateTime getMax() {
        return LocalDateTime.of(9999, 1, 1, 0, 0);
    }

    public static LocalDateTime truncateToHour(LocalDateTime localDateTime) {
        if (localDateTime == null) return null;
        // 将时间截断到小时，去掉分秒
        return localDateTime.withMinute(0).withSecond(0).withNano(0);
    }

    public static LocalDateTime truncateToDay(LocalDateTime localDateTime) {
        if (localDateTime == null) return null;
        // 将时间截断到天，去掉时分秒
        return localDateTime.withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    public static LocalDateTime truncateToMonthStart(LocalDateTime localDateTime) {
        if (localDateTime == null) return null;
        // 将时间截断到月，去掉日时分秒
        return localDateTime.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    public static Boolean isSameDay(LocalDateTime dateTime1, LocalDateTime dateTime2) {
        if (dateTime1 == null || dateTime2 == null) return false;
        return dateTime1.toLocalDate().equals(dateTime2.toLocalDate());
    }

    // 生成String
    public static String getYMDHMTimeString(LocalDateTime localTime) {
        return localTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
    }

    public static String getYearMonth(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    public static String getYearMonthDay(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
    }

    public static String getYearMonthDay2(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("yyyy年MM月dd日"));
    }

    public static String getMonthDay(LocalDateTime dateTime) {
        return dateTime.format(DateTimeFormatter.ofPattern("MM.dd"));
    }

    public static String getYMDTimeString(LocalDateTime localTime) {
        return localTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
    }

    public static String getHourMinute(LocalDateTime localTime) {
        return localTime.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    // Iso8601 conversion
    public static LocalDateTime fromIso8601String(String iso8601String, String dstZoneId) {
        if (iso8601String == null || iso8601String.isEmpty()) {
            return null;
        }
        try {
            ZonedDateTime zonedDateTime = ZonedDateTime.parse(iso8601String, DateTimeFormatter.ISO_DATE_TIME);
            ZonedDateTime zdtDst = zonedDateTime.withZoneSameInstant(ZoneId.of(dstZoneId));
            return zdtDst.toLocalDateTime();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public static String toIso8601String(LocalDateTime dateTimeUtc, String zoneId) {
        if (dateTimeUtc == null) return "";

        ZonedDateTime zonedDateTimeUtc = dateTimeUtc.atZone(UTC);
        ZonedDateTime dstTime = zonedDateTimeUtc.withZoneSameInstant(ZoneId.of(zoneId));

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mmXXX");
        return dstTime.format(formatter);
    }

    // 其他
    public static boolean isValidHour(int hour) {
        return hour >= 0 && hour <= 23;
    }

    public static boolean isValidMinute(int minute) {
        return minute >= 0 && minute <= 59;
    }

    public static boolean isValidWeekDay(int day) {
        // 和LocalDateTime.getDayOfWeek().getValue()一致
        return day >= 1 && day <= 7;
    }

    public static Integer getAge(LocalDateTime birthDate) {
        if (birthDate == null) return -1;
        LocalDateTime nowUtc = LocalDateTime.now(ZoneOffset.UTC);
        if (birthDate.isAfter(nowUtc)) return -1;
        Integer age = nowUtc.getYear() - birthDate.getYear();

        if (nowUtc.getMonthValue() < birthDate.getMonthValue()) {
            age--;
        } else if (nowUtc.getMonthValue() == birthDate.getMonthValue()) {
            if (nowUtc.getDayOfMonth() < birthDate.getDayOfMonth()) {
                age--;
            }
        }
        return age;
    }

    public static String format1Date(String iso8601Date, String zoneId) {
        if (StrUtils.isBlank(iso8601Date)) return "";
        LocalDateTime dateTime = TimeUtils.fromIso8601String(iso8601Date, zoneId);
        return dateTime != null ? dateTime.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) : "";
    }

    public static LocalDateTime parse(String timeStr, String pattern) {
        if (timeStr == null || timeStr.isEmpty()) return null;
        try {
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern);
            return LocalDateTime.parse(timeStr, formatter);
        } catch (DateTimeParseException e) {
            log.warn("Failed to parse time string '{}' with pattern '{}': {}", timeStr, pattern, e.getMessage());
            return null;
        }
    }

    public static final ZoneId ASIA_SHANGHAI;
    public static final ZoneId UTC;
    static {
        UTC = ZoneId.of("UTC");
        ZoneId zoneId = null;
        try {
            zoneId = ZoneId.of("Asia/Shanghai");
        } catch (DateTimeException e) {
            log.error("Error initializing ASIA_SHANGHAI zone ID: " + e.getMessage());
            zoneId = ZoneId.of("UTC");
        }
        ASIA_SHANGHAI = zoneId;
    }
}