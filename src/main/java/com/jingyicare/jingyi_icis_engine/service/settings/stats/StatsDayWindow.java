package com.jingyicare.jingyi_icis_engine.service.settings.stats;

import java.time.LocalDateTime;

/** 同一统计日的当地零点（UTC）及两套 UTC 半开窗口，半日偏移仅用于出入量。 */
public record StatsDayWindow(
    LocalDateTime dayMidnightUtc,
    LocalDateTime monitoringStartUtc,
    LocalDateTime monitoringEndUtc,
    LocalDateTime balanceStartUtc,
    LocalDateTime balanceEndUtc,
    int startHour,
    int monStartHour,
    int halfDayShiftHours
) {
    /** 兼容只提供查询窗口的构造方式，此时统计日零点为空。 */
    public StatsDayWindow(
        LocalDateTime monitoringStartUtc,
        LocalDateTime monitoringEndUtc,
        LocalDateTime balanceStartUtc,
        LocalDateTime balanceEndUtc,
        int startHour,
        int monStartHour,
        int halfDayShiftHours
    ) {
        this(null, monitoringStartUtc, monitoringEndUtc, balanceStartUtc, balanceEndUtc,
            startHour, monStartHour, halfDayShiftHours);
    }
}
