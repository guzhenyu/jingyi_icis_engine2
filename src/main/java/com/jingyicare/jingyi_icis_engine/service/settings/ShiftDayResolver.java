package com.jingyicare.jingyi_icis_engine.service.settings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.ShiftSettingsPB;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

/** 以每日首班的默认起始小时划分班次日。 */
public final class ShiftDayResolver {
    private ShiftDayResolver() {}

    public static LocalDateTime getShiftDayStartLocal(
        ShiftSettingsPB shiftSettings, LocalDateTime utcTime, String zoneId
    ) {
        if (utcTime == null) return null;

        LocalDateTime localTime = TimeUtils.getLocalDateTimeFromUtc(utcTime, zoneId);
        int startHour = shiftSettings.getShift(0).getDefaultShift().getStartHour();
        LocalDateTime shiftDay = localTime.getHour() < startHour
            ? localTime.minusDays(1) : localTime;
        return shiftDay.withHour(startHour).withMinute(0).withSecond(0).withNano(0);
    }

    public static LocalDateTime getShiftDayLocalMidnightAsUtc(
        ShiftSettingsPB shiftSettings, LocalDateTime utcTime, String zoneId
    ) {
        LocalDateTime shiftDayStart = getShiftDayStartLocal(shiftSettings, utcTime, zoneId);
        LocalDateTime midnightLocal = shiftDayStart.toLocalDate().atStartOfDay();
        return TimeUtils.getUtcFromLocalDateTime(midnightLocal, zoneId);
    }

    public static LocalDateTime getShiftDayStartUtc(
        ShiftSettingsPB shiftSettings, LocalDateTime utcTime, String zoneId
    ) {
        LocalDateTime startLocal = getShiftDayStartLocal(shiftSettings, utcTime, zoneId);
        return TimeUtils.getUtcFromLocalDateTime(startLocal, zoneId);
    }

    /** 返回每个班次日的本地起点，包含起止时间各自所属的班次日。 */
    public static List<LocalDateTime> listShiftDayStartsLocal(
        ShiftSettingsPB shiftSettings, LocalDateTime startUtc, LocalDateTime endUtc, String zoneId
    ) {
        List<LocalDateTime> startsLocal = new ArrayList<>();
        if (startUtc == null || endUtc == null) return startsLocal;

        LocalDateTime dayStartLocal = getShiftDayStartLocal(shiftSettings, startUtc, zoneId);
        LocalDateTime lastDayStartLocal = getShiftDayStartLocal(shiftSettings, endUtc, zoneId);
        while (!dayStartLocal.isAfter(lastDayStartLocal)) {
            startsLocal.add(dayStartLocal);
            dayStartLocal = dayStartLocal.plusDays(1);
        }

        return startsLocal;
    }

    public static List<LocalDateTime> listShiftDayStartsUtc(
        ShiftSettingsPB shiftSettings, LocalDateTime startUtc, LocalDateTime endUtc, String zoneId
    ) {
        return listShiftDayStartsLocal(shiftSettings, startUtc, endUtc, zoneId).stream()
            .map(time -> TimeUtils.getUtcFromLocalDateTime(time, zoneId))
            .toList();
    }
}
