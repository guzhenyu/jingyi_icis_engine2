package com.jingyicare.jingyi_icis_engine.service.settings.stats;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.settings.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class StatsDayWindowResolver {
    public StatsDayWindowResolver(
        ProtoConfig protoConfig,
        StatsDayConfigReader statsDayConfigReader
    ) {
        this.ZONE_ID = protoConfig.getConfig().getZoneId();
        this.zone = ZoneId.of(ZONE_ID);
        this.statsDayConfigReader = statsDayConfigReader;
    }

    /** dayMidnightUtc 是当地统计日午夜对应的 UTC 时刻，不是模块查询起点。 */
    public StatsDayWindow resolve(LocalDateTime dayMidnightUtc, String deptId) {
        return resolve(Collections.singletonList(dayMidnightUtc), deptId).get(0);
    }

    /**
     * 批量解析当地统计日午夜对应的 UTC，结果与输入逐项对应，保留顺序和重复项。
     * 非空输入只读取一次科室配置历史；空列表返回空列表。
     */
    public List<StatsDayWindow> resolve(List<LocalDateTime> dayMidnightUtcs, String deptId) {
        if (deptId == null || deptId.isBlank()) {
            throw new IllegalArgumentException("Department is required");
        }
        if (dayMidnightUtcs == null) {
            throw new IllegalArgumentException("Statistical days are required");
        }

        // 验证当地午夜对应的 UTC 时刻，转换为当地日期，检查是否与输入一致。
        List<LocalDate> days = new ArrayList<>(dayMidnightUtcs.size());
        for (LocalDateTime dayMidnightUtc : dayMidnightUtcs) {
            if (dayMidnightUtc == null) {
                throw new IllegalArgumentException("Statistical day is required");
            }
            LocalDate day = TimeUtils.getLocalDateTimeFromUtc(dayMidnightUtc, ZONE_ID)
                .toLocalDate();
            if (!dayMidnightUtc.equals(TimeUtils.getDayMidnightUtc(day, zone))) {
                throw new IllegalArgumentException("Statistical day must start at local midnight");
            }
            days.add(day);
        }
        if (days.isEmpty()) return List.of();

        List<BalanceStatsShift> shifts = statsDayConfigReader.listActiveByDept(deptId);
        List<StatsDayWindow> windows = new ArrayList<>(days.size());
        for (LocalDate day : days) {
            windows.add(resolveDay(day, shifts));
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
    public List<LocalDate> resolveStatisticDates(LocalDateTime utc, String deptId) {
        if (deptId == null || deptId.isBlank()) {
            throw new IllegalArgumentException("Department is required");
        }
        if (utc == null) throw new IllegalArgumentException("Time is required");
        List<BalanceStatsShift> shifts = statsDayConfigReader.listActiveByDept(deptId);
        return resolveStatisticDatesFromShifts(utc, shifts);
    }

    /** 按输入首次出现的顺序解析统计日。非空批次查询一次，空批次返回空映射。 */
    public Map<LocalDateTime, List<LocalDate>> resolveStatisticDates(
        List<LocalDateTime> utcs, String deptId
    ) {
        if (deptId == null || deptId.isBlank()) {
            throw new IllegalArgumentException("Department is required");
        }
        if (utcs == null || utcs.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Time is required");
        }
        if (utcs.isEmpty()) return Map.of();

        List<BalanceStatsShift> shifts = statsDayConfigReader.listActiveByDept(deptId);
        Map<LocalDateTime, List<LocalDate>> statisticDates = new LinkedHashMap<>();
        for (LocalDateTime utc : utcs) {
            statisticDates.computeIfAbsent(utc,
                time -> resolveStatisticDatesFromShifts(time, shifts));
        }
        return statisticDates;
    }

    /**
     * 按输入首次出现的顺序，返回各时间点命中的出入量窗口起点（UTC）。
     * 窗口包含起点，排除结束时刻。窗口重叠可命中多个起点，空缺时返回空列表。
     */
    public Map<LocalDateTime, List<LocalDateTime>> resolveBalanceStartsUtc(
        String deptId, List<LocalDateTime> inputTimesUtc
    ) {
        if (deptId == null || deptId.isBlank()) {
            throw new IllegalArgumentException("Department is required");
        }
        if (inputTimesUtc == null || inputTimesUtc.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("Time is required");
        }
        if (inputTimesUtc.isEmpty()) return Map.of();

        List<BalanceStatsShift> shifts = statsDayConfigReader.listActiveByDept(deptId);
        Map<LocalDateTime, List<LocalDateTime>> balanceStarts = new LinkedHashMap<>();
        for (LocalDateTime utc : inputTimesUtc) {
            balanceStarts.computeIfAbsent(utc, time -> resolveBalanceWindows(time, shifts).stream()
                .map(StatsDayWindow::balanceStartUtc)
                .collect(Collectors.toCollection(ArrayList::new)));
        }
        return balanceStarts;
    }

    /**
     * 按当地日期升序返回两个端点命中的出入量统计日及其间每日的窗口，包含首尾。
     * 端点无命中时纳入当地当天和前一天。整批只读取一次科室配置。
     */
    public List<StatsDayWindow> getAffectedStatsDayWindows(
        String deptId, LocalDateTime startUtc, LocalDateTime endUtc
    ) {
        if (startUtc == null || endUtc == null || startUtc.isAfter(endUtc)) {
            throw new IllegalArgumentException("Invalid time range");
        }
        if (deptId == null || deptId.isBlank()) {
            throw new IllegalArgumentException("Department is required");
        }

        List<BalanceStatsShift> shifts = statsDayConfigReader.listActiveByDept(deptId);
        List<LocalDate> affectedDays = new ArrayList<>();
        for (LocalDateTime utc : List.of(startUtc, endUtc)) {
            List<StatsDayWindow> matches = resolveBalanceWindows(utc, shifts);
            if (matches.isEmpty()) {
                LocalDate day = TimeUtils.getLocalDateTimeFromUtc(utc, ZONE_ID).toLocalDate();
                affectedDays.add(day.minusDays(1));
                affectedDays.add(day);
            } else {
                for (StatsDayWindow window : matches) {
                    affectedDays.add(TimeUtils.getLocalDateTimeFromUtc(
                        window.dayMidnightUtc(), ZONE_ID).toLocalDate());
                }
            }
        }
        LocalDate firstDay = Collections.min(affectedDays);
        LocalDate lastDay = Collections.max(affectedDays);
        return firstDay.datesUntil(lastDay.plusDays(1))
            .map(day -> resolveDay(day, shifts))
            .toList();
    }

    private List<LocalDate> resolveStatisticDatesFromShifts(
        LocalDateTime utc, List<BalanceStatsShift> shifts
    ) {
        if (utc == null) throw new IllegalArgumentException("Time is required");
        Objects.requireNonNull(shifts, "shifts");

        List<LocalDate> statisticDates = new ArrayList<>();
        for (LocalDate candidateDay : candidateDays(utc)) {
            StatsDayWindow window = resolveDay(candidateDay, shifts);
            if (containsUtc(utc, window.monitoringStartUtc(), window.monitoringEndUtc())
                || containsUtc(utc, window.balanceStartUtc(), window.balanceEndUtc())
            ) {
                statisticDates.add(candidateDay);
            }
        }
        return statisticDates;
    }

    private List<StatsDayWindow> resolveBalanceWindows(
        LocalDateTime utc, List<BalanceStatsShift> shifts
    ) {
        List<StatsDayWindow> windows = new ArrayList<>();
        for (LocalDate candidateDay : candidateDays(utc)) {
            StatsDayWindow window = resolveDay(candidateDay, shifts);
            if (containsUtc(utc, window.balanceStartUtc(), window.balanceEndUtc())) {
                windows.add(window);
            }
        }
        return windows;
    }

    private List<LocalDate> candidateDays(LocalDateTime utc) {
        // 取当前时间点的当地日期及前后各一天，避免配置变更时遗漏。
        LocalDate day = TimeUtils.getLocalDateTimeFromUtc(utc, ZONE_ID).toLocalDate();
        return List.of(day.minusDays(1), day, day.plusDays(1));
    }

    private boolean containsUtc(LocalDateTime utc, LocalDateTime start, LocalDateTime end) {
        return !utc.isBefore(start) && utc.isBefore(end);
    }

    private StatsDayWindow resolveDay(LocalDate day, List<BalanceStatsShift> shifts) {
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
        Integer configuredMonHour = shift == null ? null : shift.getMonStartHour();
        int monHour = configuredMonHour != null && TimeUtils.isValidHour(configuredMonHour)
            ? configuredMonHour : startHour;
        Integer configuredHalfHours = shift == null ? null : shift.getHalfDayShiftHours();
        int halfHours = configuredHalfHours != null && configuredHalfHours >= 1
            && configuredHalfHours <= 23 ? configuredHalfHours : DEFAULT_HALF_DAY_HOURS;

        // 两个当地端点分别换算，保留相邻统计日之间的重叠或空缺。
        return new StatsDayWindow(
            TimeUtils.getDayMidnightUtc(day, zone),
            getHourUtc(day, monHour),
            getHourUtc(nextDay, monHour),
            getHourUtc(day, startHour),
            getHourUtc(nextDay, startHour),
            startHour, monHour, halfHours);
    }

    private LocalDateTime getHourUtc(LocalDate day, int hour) {
        return TimeUtils.getUtcFromLocalDateTime(day.atTime(hour, 0), ZONE_ID);
    }

    private static final int DEFAULT_HALF_DAY_HOURS = 12;

    private final String ZONE_ID;
    private final ZoneId zone;
    private final StatsDayConfigReader statsDayConfigReader;
}
