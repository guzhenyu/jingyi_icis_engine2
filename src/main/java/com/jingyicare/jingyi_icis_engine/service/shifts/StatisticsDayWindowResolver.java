package com.jingyicare.jingyi_icis_engine.service.shifts;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

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
        return resolveInternal(Collections.singletonList(dayMidnightUtc), zoneId, deptId).get(0);
    }

    /**
     * 批量解析当地统计日午夜对应的 UTC，结果与输入逐项对应，保留顺序和重复项。
     * 非空输入只读取一次科室配置历史；空列表返回空列表。
     */
    public List<StatisticsDayWindow> resolveInternal(
        List<LocalDateTime> dayMidnightUtcs, String zoneId, String deptId
    ) {
        ZoneId zone = validateArguments(zoneId, deptId);
        if (dayMidnightUtcs == null) {
            throw new IllegalArgumentException("Statistical days are required");
        }

        // 验证当地午夜对应的 UTC 时刻，转换为当地日期，检查是否与输入一致。
        List<LocalDate> days = new ArrayList<>(dayMidnightUtcs.size());
        for (LocalDateTime dayMidnightUtc : dayMidnightUtcs) {
            if (dayMidnightUtc == null) {
                throw new IllegalArgumentException("Statistical day is required");
            }
            LocalDate day = TimeUtils.getLocalDateTimeFromUtc(dayMidnightUtc, zoneId).toLocalDate();
            if (!dayMidnightUtc.equals(TimeUtils.getDayMidnightUtc(day, zone))) {
                throw new IllegalArgumentException("Statistical day must start at local midnight");
            }
            days.add(day);
        }
        if (days.isEmpty()) return List.of();

        List<BalanceStatsShift> shifts = balanceStatsShiftRepo
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId);
        List<StatisticsDayWindow> windows = new ArrayList<>(days.size());
        for (LocalDate day : days) {
            windows.add(resolveDay(day, zone, shifts));
        }
        return windows;
    }

    /**
     * 返回观察或出入量半开窗口包含 utc 的统计日期，按日期升序去重。
     * 各统计日独立选取配置，因此配置变更时可能返回多个日期，也可能返回空列表。
     * 仅检查当地前一天、当天、后一天，面向当前业务，不保证历史大幅时区跳变的完整性。
     * 例如萨摩亚、托克劳（2011）、基里巴斯（1994）、马绍尔群岛（1993）的跳日，
     * 以及莫斯科（1918）的跨午夜前拨，可能使时间点归属当地前两天的统计窗口。
     */
    public List<LocalDate> resolveStatisticDates(LocalDateTime utc, String zoneId, String deptId) {
        validateArguments(zoneId, deptId);
        if (utc == null) throw new IllegalArgumentException("Time is required");
        List<BalanceStatsShift> shifts = balanceStatsShiftRepo
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId);
        return resolveStatisticDatesFromShifts(utc, zoneId, shifts);
    }

    /** 动态锁计划使用 JDBC 预读的配置，按生效时间倒序传入，不再通过 JPA 查询。 */
    public List<LocalDate> resolveStatisticDatesFromShifts(
        LocalDateTime utc, String zoneId, List<BalanceStatsShift> shifts
    ) {
        ZoneId zone = validateZoneId(zoneId);
        if (utc == null) throw new IllegalArgumentException("Time is required");
        Objects.requireNonNull(shifts, "shifts");

        // 取当前时间点的当地日期及前后各一天，避免配置变更时遗漏。
        LocalDate day = TimeUtils.getLocalDateTimeFromUtc(utc, zoneId).toLocalDate();
        List<LocalDate> candidateDays = List.of(day.minusDays(1), day, day.plusDays(1));

        // 获取合法统计日
        List<LocalDate> statisticDates = new ArrayList<>();
        List<StatisticsDayWindow> windows = candidateDays.stream()
            .map(candidate -> resolveDay(candidate, zone, shifts)).toList();
        for (int i = 0; i < windows.size(); i++) {
            StatisticsDayWindow window = windows.get(i);
            if (containsUtc(utc, window.monitoringStartUtc(), window.monitoringEndUtc())
                || containsUtc(utc, window.balanceStartUtc(), window.balanceEndUtc())
            ) {
                statisticDates.add(candidateDays.get(i));
            }
        }
        return statisticDates;
    }

    private boolean containsUtc(LocalDateTime utc, LocalDateTime start, LocalDateTime end) {
        return !utc.isBefore(start) && utc.isBefore(end);
    }

    private ZoneId validateArguments(String zoneId, String deptId) {
        if (deptId == null || deptId.isBlank()) {
            throw new IllegalArgumentException("Department is required");
        }
        return validateZoneId(zoneId);
    }

    private ZoneId validateZoneId(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            throw new IllegalArgumentException("Timezone is required");
        }
        try {
            return ZoneId.of(zoneId);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("Invalid timezone: " + zoneId, e);
        }
    }

    private StatisticsDayWindow resolveDay(LocalDate day, ZoneId zone,
        List<BalanceStatsShift> shifts) {
        String zoneId = zone.getId();
        LocalDate nextDay = day.plusDays(1);
        LocalDateTime cutoff = TimeUtils.getDayMidnightUtc(nextDay, zone);
        // 配置已按生效时间倒序排列，严格排除恰好在截止时刻生效的记录。
        BalanceStatsShift shift = shifts.stream()
            .filter(candidate -> candidate.getEffectiveTime().isBefore(cutoff))
            .findFirst().orElse(null);

        Integer startHour = shift == null ? Consts.DEFAULT_SHIFT_START_HOUR : shift.getStartHour();
        if (startHour == null || !TimeUtils.isValidHour(startHour)) {
            throw new IllegalArgumentException("Invalid balance start hour: " + startHour);
        }
        Integer monHour = shift == null ? startHour : shift.getMonStartHour();
        if (monHour == null || !TimeUtils.isValidHour(monHour)) monHour = startHour;
        Integer halfHours = shift == null ? null : shift.getHalfDayShiftHours();
        if (halfHours == null || halfHours < 1 || halfHours > 23) halfHours =
            DEFAULT_HALF_DAY_HOURS;

        // 两个当地端点分别换算，保留相邻统计日之间的重叠或空缺。
        return new StatisticsDayWindow(
            getHourUtc(day, monHour, zoneId),
            getHourUtc(nextDay, monHour, zoneId),
            getHourUtc(day, startHour, zoneId),
            getHourUtc(nextDay, startHour, zoneId),
            startHour, monHour, halfHours);
    }

    private LocalDateTime getHourUtc(LocalDate day, int hour, String zoneId) {
        return TimeUtils.getUtcFromLocalDateTime(day.atTime(hour, 0), zoneId);
    }

    private static final int DEFAULT_HALF_DAY_HOURS = 12;
    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
}
