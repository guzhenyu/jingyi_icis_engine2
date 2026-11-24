package com.jingyicare.jingyi_icis_engine.service.shifts;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.ShiftSettingsPB;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.settings.stats.StatsDayConfigReader;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class ConfigShiftUtils {
    public ConfigShiftUtils(
        @Autowired ConfigProtoService protoService,
        @Autowired StatsDayConfigReader statsDayConfigReader
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        ShiftSettingsPB defaultShiftSettings =
            protoService.getConfig().getShift().getDefaultSetting();
        this.DEFAULT_SHIFT_START_HOUR = defaultShiftSettings.getShiftCount() > 0
            ? defaultShiftSettings.getShift(0).getBalanceTimeRange().getStartHour()
            : Consts.DEFAULT_SHIFT_START_HOUR;

        this.statsDayConfigReader = statsDayConfigReader;
    }

    public List<Pair<LocalDateTime, Integer>> getBalanceDailyShiftHours(String deptId) {
        return statsDayConfigReader.listActiveByDept(deptId).stream()
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
        List<LocalDateTime> statsUtcs = new ArrayList<>();
        LocalDateTime dt = TimeUtils.getUtcFromLocalDateTime(
            TimeUtils.getMin().withHour(DEFAULT_SHIFT_START_HOUR), ZONE_ID
        );
        statsUtcs.add(dt);

        Map<LocalDateTime/*local date*/, Integer/*start hour*/> statsLocalMap = new HashMap<>();
        for (BalanceStatsShift shift : statsDayConfigReader.listActiveByDept(deptId).reversed()) {
            LocalDateTime localDate = TimeUtils.getLocalDateTimeFromUtc(
                shift.getEffectiveTime(), ZONE_ID);
            localDate = TimeUtils.truncateToDay(localDate);
            statsLocalMap.put(localDate, shift.getStartHour());
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

    private final String ZONE_ID;
    private final Integer DEFAULT_SHIFT_START_HOUR;

    private final StatsDayConfigReader statsDayConfigReader;
}
