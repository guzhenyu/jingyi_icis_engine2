package com.jingyicare.jingyi_icis_engine.service.shifts;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class StatisticsDayWindowResolver {
    public StatisticsDayWindowResolver(BalanceStatsShiftRepository balanceStatsShiftRepo) {
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
    }

    /** dayMidnightUtc 是当地统计日午夜对应的 UTC 时刻，不是模块查询起点。 */
    public StatisticsDayWindow resolve(LocalDateTime dayMidnightUtc, String zoneId, String deptId) {
        if (dayMidnightUtc == null || zoneId == null || zoneId.isBlank()
            || deptId == null || deptId.isBlank()) {
            throw new IllegalArgumentException(
                "Statistical day, timezone and department are required");
        }
        final ZoneId zone;
        try {
            zone = ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid timezone: " + zoneId, e);
        }
        LocalDate day = TimeUtils.getLocalDateTimeFromUtc(dayMidnightUtc, zoneId).toLocalDate();
        if (!dayMidnightUtc.equals(TimeUtils.getUtcFromLocalDateTime(day.atStartOfDay(zone)
            .toLocalDateTime(), zoneId))) {
            throw new IllegalArgumentException("Statistical day must start at local midnight");
        }
        LocalDate nextDay = day.plusDays(1);
        LocalDateTime cutoff = TimeUtils.getUtcFromLocalDateTime(nextDay.atStartOfDay(zone)
            .toLocalDateTime(), zoneId);
        BalanceStatsShift shift = balanceStatsShiftRepo
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
                deptId, cutoff)
            .orElse(null);

        Integer startHour = shift == null ? Consts.DEFAULT_SHIFT_START_HOUR : shift.getStartHour();
        if (startHour == null || !TimeUtils.isValidHour(startHour)) {
            throw new IllegalArgumentException("Invalid balance start hour: " + startHour);
        }
        Integer monHour = shift == null ? startHour : shift.getMonStartHour();
        if (monHour == null || !TimeUtils.isValidHour(monHour)) monHour = startHour;
        Integer halfHours = shift == null ? null : shift.getHalfDayShiftHours();
        if (halfHours == null || halfHours < 1 || halfHours > 23) halfHours =
            DEFAULT_HALF_DAY_HOURS;

        // 两个当地端点分别换算；不读取相邻日配置，也不修补相邻日的重叠或空缺。
        return new StatisticsDayWindow(
            TimeUtils.getUtcFromLocalDateTime(day.atTime(monHour, 0), zoneId),
            TimeUtils.getUtcFromLocalDateTime(nextDay.atTime(monHour, 0), zoneId),
            TimeUtils.getUtcFromLocalDateTime(day.atTime(startHour, 0), zoneId),
            TimeUtils.getUtcFromLocalDateTime(nextDay.atTime(startHour, 0), zoneId),
            startHour, monHour, halfHours);
    }

    private static final int DEFAULT_HALF_DAY_HOURS = 12;
    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
}
