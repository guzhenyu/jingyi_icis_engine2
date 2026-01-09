package com.jingyicare.jingyi_icis_engine.service.medications;

import java.time.LocalDateTime;
import java.util.*;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Slf4j
public class MedUtils {
    static public List<LocalDateTime> getPlanTimesUtc(
        LocalDateTime shiftStartUtc, String zoneId, MedicationFrequencySpec freqSpec
    ) {
        LocalDateTime shiftStart = TimeUtils.getLocalDateTimeFromUtc(shiftStartUtc, zoneId);
        List<LocalDateTime> planTimes = getPlanTimesLocal(shiftStart, freqSpec);

        return planTimes.stream()
            .map(dt -> TimeUtils.getUtcFromLocalDateTime(dt, zoneId))
            .toList();
    }

    static public List<LocalDateTime> getPlanTimesLocal(
        LocalDateTime shiftStart/*local*/, MedicationFrequencySpec freqSpec
    ) {
        List<LocalDateTime> planTimes = new ArrayList<>();
        if (freqSpec.getTimeList().isEmpty()) return planTimes;

        final int shiftStartHour = shiftStart.getHour();
        final int freqSpecStartHour = freqSpec.getTime(0).getHour();

        for (int i = 0; i < freqSpec.getTimeCount(); i++) {
            int hour = freqSpec.getTime(i).getHour();
            int minute = freqSpec.getTime(i).getMinute();

            if (hour < freqSpecStartHour) {
                hour += 24;
            }

            int offsetHoursToShiftStart = hour - shiftStartHour;
            if (offsetHoursToShiftStart < 0) {
                // 例子：班次时间从8点开始，频次时间从6点开始
                offsetHoursToShiftStart += 24;
            }
            if (offsetHoursToShiftStart > 23) {
                // 例子：班次时间从8点开始，频次时间从10点开始
                offsetHoursToShiftStart -= 24;
            }
            LocalDateTime planTime = shiftStart.plusHours(offsetHoursToShiftStart).withMinute(minute);
            planTimes.add(planTime);
        }
        planTimes.sort(Comparator.naturalOrder());
        return planTimes;
    }
}