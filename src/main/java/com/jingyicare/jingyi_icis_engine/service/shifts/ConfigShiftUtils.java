package com.jingyicare.jingyi_icis_engine.service.shifts;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;

import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.shifts.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
public class ConfigShiftUtils {
    public static LocalDateTime getShiftStartTime(
        ShiftSettingsPB shift, LocalDateTime utcTime, String zoneId
    ) {
        if (utcTime == null) return null;

        LocalDateTime localTime = TimeUtils.getLocalDateTimeFromUtc(utcTime, zoneId);
        final Integer startHour = shift.getShift(0).getDefaultShift().getStartHour();

        return localTime.getHour() < startHour
            ? localTime.minusDays(1).withHour(startHour).withMinute(0).withSecond(0).withNano(0)
            : localTime.withHour(startHour).withMinute(0).withSecond(0).withNano(0);
    }

    public static LocalDateTime getShiftLocalMidnightUtc(
        ShiftSettingsPB shift, LocalDateTime utcTime, String zoneId
    ) {
        LocalDateTime shiftStartLocal = getShiftStartTime(shift, utcTime, zoneId);
        shiftStartLocal = shiftStartLocal.withHour(0).withMinute(0).withSecond(0);
        return TimeUtils.getUtcFromLocalDateTime(shiftStartLocal, zoneId);
    }

    public static LocalDateTime getShiftStartTimeUtc(
        ShiftSettingsPB shift, LocalDateTime utcTime, String zoneId
    ) {
        LocalDateTime localTime = getShiftStartTime(shift, utcTime, zoneId);
        return TimeUtils.getUtcFromLocalDateTime(localTime, zoneId);
    }

    public static List<LocalDateTime> getShiftStartTimes(
        ShiftSettingsPB shift, LocalDateTime startUtc, LocalDateTime endUtc, String zoneId
    ) {
        List<LocalDateTime> shiftTimes = new ArrayList<>();
        if (startUtc == null || endUtc == null) return shiftTimes;

        LocalDateTime startShift = getShiftStartTime(shift, startUtc, zoneId);
        LocalDateTime endShift = getShiftStartTime(shift, endUtc, zoneId);

        while (!startShift.isAfter(endShift)) {
            shiftTimes.add(startShift);
            startShift = startShift.plusDays(1);
        }

        return shiftTimes;
    }

    public static List<LocalDateTime> getShiftStartTimeUtcs(
        ShiftSettingsPB shift, LocalDateTime startUtc, LocalDateTime endUtc, String zoneId
    ) {
        return getShiftStartTimes(shift, startUtc, endUtc, zoneId).stream()
            .map(time -> TimeUtils.getUtcFromLocalDateTime(time, zoneId))
            .toList();
    }

    public ConfigShiftUtils(
        @Autowired ConfigProtoService protoService,
        @Autowired DeptSystemSettingsRepository deptSystemSettingsRepo,
        @Autowired BalanceStatsShiftRepository balanceStatsShiftRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.defaultShiftSettings = protoService.getConfig().getShift().getDefaultSetting();
        this.DEFAULT_SHIFT_START_HOUR = defaultShiftSettings.getShiftCount() > 0
            ? defaultShiftSettings.getShift(0).getBalanceTimeRange().getStartHour()
            : Consts.DEFAULT_SHIFT_START_HOUR;

        this.deptSystemSettingsRepo = deptSystemSettingsRepo;
        this.balanceStatsShiftRepo = balanceStatsShiftRepo;
    }

    public List<Pair<LocalDateTime, Integer>> getBalanceDailyShiftHours(String deptId) {
        List<BalanceStatsShift> balanceStatsShifts = balanceStatsShiftRepo
            .findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(deptId);
        return balanceStatsShifts.stream()
            .map(shift -> new Pair<>(shift.getEffectiveTime(), shift.getStartHour()))
            .toList();
    }

    // Returns balance stats cutoff history in UTC.
    // Example (dept timezone is Shanghai):
    // [1900-01-01 00:00:00, 2025-01-01 20:00:00, 2025-06-01 22:00:00] means:
    // - Shanghai time 1900-01-01 to 2024-12-31: daily stats from 08:00 to next day 08:00
    // - Shanghai time 2025-01-01 to 2025-05-31: daily stats from 04:00 to next day 04:00
    // - Shanghai time from 2025-06-01: daily stats from 06:00 to next day 06:00
    public List<LocalDateTime> getBalanceStatTimeUtcHistory(String deptId) {
        Integer defaultShiftStartHour = this.DEFAULT_SHIFT_START_HOUR;
        DeptSystemSettingsId deptSettingsId = DeptSystemSettingsId.builder()
            .deptId(deptId)
            .functionId(SystemSettingFunctionId.GET_SHIFT.getNumber())
            .build();
        DeptSystemSettings deptSettings = deptSystemSettingsRepo.findById(deptSettingsId).orElse(null);
        if (deptSettings != null) {
            ShiftSettingsPB shiftSettings = ProtoUtils.decodeShiftSettings(deptSettings.getSettingsPb());
            if (shiftSettings != null && shiftSettings.getShiftCount() > 0) {
                defaultShiftStartHour = shiftSettings.getShift(0).getBalanceTimeRange().getStartHour();
            }
        }

        List<LocalDateTime> statsUtcs = new ArrayList<>();
        LocalDateTime dt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getMin().withHour(defaultShiftStartHour), ZONE_ID
        );
        statsUtcs.add(dt);

        Map<LocalDateTime/*local date*/, Integer/*start hour*/> statsLocalMap = new HashMap<>();
        for (BalanceStatsShift bss : balanceStatsShiftRepo.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeAsc(deptId)) {
            LocalDateTime localDate = TimeUtils.getLocalDateTimeFromUtc(bss.getEffectiveTime(), ZONE_ID);
            localDate = TimeUtils.truncateToDay(localDate);
            statsLocalMap.put(localDate, bss.getStartHour());
        }

        for (Map.Entry<LocalDateTime, Integer> entry : statsLocalMap.entrySet()) {
            LocalDateTime localDate = entry.getKey().withHour(entry.getValue());
            LocalDateTime utcDate = TimeUtils.getUtcFromLocalDateTime(localDate, ZONE_ID);
            statsUtcs.add(utcDate);
        }
        statsUtcs.sort(Comparator.naturalOrder());

        return statsUtcs;
    }

    public LocalDateTime getBalanceStatStartUtc(
        List<LocalDateTime> balanceStatTimeUtcHistory, LocalDateTime dateTimeUtc
    ) {
        LocalDateTime startUtc = null;
        for (LocalDateTime utcTime : balanceStatTimeUtcHistory) {
            if (!dateTimeUtc.isBefore(utcTime)) {
                startUtc = utcTime;
            }
        }

        LocalDateTime startLocal = TimeUtils.getLocalDateTimeFromUtc(startUtc, ZONE_ID);
        Integer startHour = startLocal.getHour();
        LocalDateTime dateTimeLocal = TimeUtils.getLocalDateTimeFromUtc(dateTimeUtc, ZONE_ID);
        LocalDateTime dateTimeStatLocal = TimeUtils.truncateToDay(dateTimeLocal);
        if (dateTimeLocal.getHour() < startHour) {
            dateTimeStatLocal = dateTimeStatLocal.minusDays(1);
        }
        return TimeUtils.getUtcFromLocalDateTime(dateTimeStatLocal.withHour(startHour), ZONE_ID);
    }

    @Transactional
    public ShiftSettingsPB getShiftByDeptId(String deptId) {
        // 获取班次配置，如果没有特殊设置，返回系统配置
        ShiftSettingsPB shiftSettings = null;

        DeptSystemSettingsId deptSettingsId = DeptSystemSettingsId.builder()
            .deptId(deptId)
            .functionId(SystemSettingFunctionId.GET_SHIFT.getNumber())
            .build();
        DeptSystemSettings deptSettings = deptSystemSettingsRepo.findById(deptSettingsId).orElse(null);
        if (deptSettings != null) {
            shiftSettings = ProtoUtils.decodeShiftSettings(deptSettings.getSettingsPb());
        }

        return shiftSettings == null ? defaultShiftSettings : shiftSettings;
    }

    private final String ZONE_ID;
    private final ShiftSettingsPB defaultShiftSettings;
    private final Integer DEFAULT_SHIFT_START_HOUR;

    private final DeptSystemSettingsRepository deptSystemSettingsRepo;
    private final BalanceStatsShiftRepository balanceStatsShiftRepo;
}
