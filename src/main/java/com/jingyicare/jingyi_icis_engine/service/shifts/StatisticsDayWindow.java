package com.jingyicare.jingyi_icis_engine.service.shifts;

import java.time.LocalDateTime;

/** 同一统计日、同一配置计算的两套 UTC 半开窗口；半日偏移仅用于出入量。 */
public record StatisticsDayWindow(
    LocalDateTime monitoringStartUtc,
    LocalDateTime monitoringEndUtc,
    LocalDateTime balanceStartUtc,
    LocalDateTime balanceEndUtc,
    int startHour,
    int monStartHour,
    int halfDayShiftHours
) {}
