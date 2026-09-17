package com.jingyicare.jingyi_icis_engine.service.medications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionAction;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecord;
import com.jingyicare.jingyi_icis_engine.service.medications.MedMonitoringService.FluidIntakeData;

class MedIntakeTimeRangeTests {
    private static final LocalDateTime DAY = LocalDateTime.of(2025, 9, 17, 0, 0);

    @Test
    void includesOldAndNewHoursWhenBolusIsMovedEarlier() {
        LocalDateTime oldTime = DAY.plusHours(14).plusMinutes(21);
        LocalDateTime newTime = DAY.plusHours(13).plusMinutes(21);
        var range = MedIntakeTimeRange.calculate(
            MedicationExecutionRecord.builder().startTime(newTime).endTime(newTime).build(),
            actions(oldTime), actions(newTime), intake(DAY.plusHours(14)), intake(DAY.plusHours(13)))
            .orElseThrow();

        assertThat(range.startUtc()).isEqualTo(DAY.plusHours(13));
        assertThat(range.endUtc()).isEqualTo(oldTime);
    }

    @Test
    void retainsRemovedContinuousTailEvenWhenNewIntakeIsEmpty() {
        var oldIntake = intake(DAY.plusHours(12), DAY.plusHours(13), DAY.plusHours(14));
        var range = MedIntakeTimeRange.calculate(new MedicationExecutionRecord(),
            actions(DAY.plusHours(12), DAY.plusHours(15)), List.of(), oldIntake, null).orElseThrow();

        assertThat(range.startUtc()).isEqualTo(DAY.plusHours(12));
        assertThat(range.endUtc()).isEqualTo(DAY.plusHours(15));
    }

    @Test
    void includesActionTimesAcrossDaysEvenWithoutIntake() {
        LocalDateTime oldTime = DAY.plusHours(23).plusMinutes(50);
        LocalDateTime newTime = DAY.plusDays(1).plusMinutes(10);
        var range = MedIntakeTimeRange.calculate(new MedicationExecutionRecord(),
            actions(oldTime), actions(newTime), null, null).orElseThrow();

        assertThat(range.startUtc()).isEqualTo(DAY.plusHours(23));
        assertThat(range.endUtc()).isEqualTo(newTime);
    }

    @Test
    void includesEstimatedCompletionForOngoingInfusion() {
        var oldIntake = intake(DAY.plusHours(12));
        oldIntake.estimatedFinishTime = DAY.plusDays(1).plusHours(16);
        var newIntake = intake(DAY.plusHours(13));
        newIntake.estimatedFinishTime = DAY.plusDays(1).plusHours(14);
        var range = MedIntakeTimeRange.calculate(new MedicationExecutionRecord(),
            List.of(), List.of(), oldIntake, newIntake).orElseThrow();

        assertThat(range.startUtc()).isEqualTo(DAY.plusHours(12));
        assertThat(range.endUtc()).isEqualTo(oldIntake.estimatedFinishTime);
    }

    @Test
    void halfOpenQueryIncludesLastAffectedTimeAtShiftBoundary() {
        LocalDateTime shiftBoundary = DAY.plusHours(8);
        var range = MedIntakeTimeRange.calculate(
            MedicationExecutionRecord.builder().startTime(shiftBoundary).endTime(shiftBoundary).build(),
            null, actions(shiftBoundary), null, intake(shiftBoundary)).orElseThrow();

        assertThat(range.startUtc()).isEqualTo(shiftBoundary);
        assertThat(range.endUtc()).isEqualTo(shiftBoundary);
        assertThat(range.queryEndUtc()).isAfter(shiftBoundary);
    }

    @Test
    void noExecutionHistoryHasNoAffectedRange() {
        assertThat(MedIntakeTimeRange.calculate(new MedicationExecutionRecord(),
            null, List.of(), null, intake())).isEmpty();
    }

    private List<MedicationExecutionAction> actions(LocalDateTime... times) {
        return java.util.Arrays.stream(times)
            .map(time -> MedicationExecutionAction.builder().createdAt(time).build()).toList();
    }

    private FluidIntakeData intake(LocalDateTime... hours) {
        var intake = new FluidIntakeData(3000.0, DAY.plusDays(2), "UTC");
        for (LocalDateTime hour : hours) intake.intakeMap.put(hour, 100.0);
        return intake;
    }
}
