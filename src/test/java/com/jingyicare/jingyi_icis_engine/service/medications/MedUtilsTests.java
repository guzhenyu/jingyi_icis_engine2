package com.jingyicare.jingyi_icis_engine.service.medications;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDateTime;
import java.util.*;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public class MedUtilsTests {
    @Test
    public void testGetPlanTimes() {
        LocalDateTime shiftStartUtc = TimeUtils.getLocalTime(2025, 5, 22, 0, 0);
        String zoneId = "Asia/Shanghai";

        MedicationFrequencySpec.Builder freqSpecBuilder = MedicationFrequencySpec.newBuilder();

        // 生成24个时间点，从6点开始，每小时一个时间点
        generateAndAssertPlanTimes(shiftStartUtc, zoneId, 6);

        // 生成24个时间点，从8点开始，每小时一个时间点
        generateAndAssertPlanTimes(shiftStartUtc, zoneId, 6);

        // 生成24个时间点，从10点开始，每小时一个时间点
        generateAndAssertPlanTimes(shiftStartUtc, zoneId, 10);
    }

    private void generateAndAssertPlanTimes(LocalDateTime shiftStartUtc, String zoneId, int startHour) {
        MedicationFrequencySpec.Builder freqSpecBuilder = MedicationFrequencySpec.newBuilder();
        for (int i = 0; i < 24; i++) {
            MedicationFrequencySpec.Time time = MedicationFrequencySpec.Time.newBuilder()
                .setHour((startHour + i) % 24)
                .setMinute(0)
                .build();
            freqSpecBuilder.addTime(time);
        }
        MedicationFrequencySpec freqSpec = freqSpecBuilder.build();
        List<LocalDateTime> planTimes = MedUtils.getPlanTimesUtc(shiftStartUtc, zoneId, freqSpec);
        for (int i = 0; i < 24; ++i) {
            LocalDateTime expectedTime = shiftStartUtc.plusHours(i);
            assertThat(planTimes.get(i)).isEqualTo(expectedTime);
        }
    }
}
