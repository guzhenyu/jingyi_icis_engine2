package com.jingyicare.jingyi_icis_engine.service.qcs;

import java.time.LocalDateTime;
import java.util.*;

import com.jingyicare.jingyi_icis_engine.utils.*;

public class QcUtils {
    public static List<LocalDateTime> buildMonthsUtc(
        LocalDateTime queryStartUtc, LocalDateTime queryEndUtc, String zoneId
    ) {
        LocalDateTime queryStartLocal = TimeUtils.getLocalDateTimeFromUtc(queryStartUtc, zoneId);
        LocalDateTime queryEndLocal = TimeUtils.getLocalDateTimeFromUtc(queryEndUtc, zoneId);

        List<LocalDateTime> monthsUtc = new ArrayList<>();
        LocalDateTime monthStart = TimeUtils.truncateToMonthStart(queryStartLocal);
        LocalDateTime monthEnd = TimeUtils.truncateToMonthStart(queryEndLocal);
        if (monthEnd.isBefore(queryEndLocal)) monthEnd = monthEnd.plusMonths(1);
        while (monthStart.isBefore(monthEnd)) {
            LocalDateTime monthStartUtc = TimeUtils.getUtcFromLocalDateTime(monthStart, zoneId);
            monthsUtc.add(monthStartUtc);
            monthStart = monthStart.plusMonths(1);
        }
        return monthsUtc;
    }
}