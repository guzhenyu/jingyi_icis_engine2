package com.jingyicare.jingyi_icis_engine.service.medications;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionAction;
import com.jingyicare.jingyi_icis_engine.entity.medications.MedicationExecutionRecord;

/** Shared affected range for medication balance recalculation and nursing report invalidation. */
public record MedIntakeTimeRange(LocalDateTime startUtc, LocalDateTime endUtc) {
    public static Optional<MedIntakeTimeRange> calculate(
        MedicationExecutionRecord record,
        List<MedicationExecutionAction> oldActions, List<MedicationExecutionAction> newActions,
        MedMonitoringService.FluidIntakeData oldIntake, MedMonitoringService.FluidIntakeData newIntake
    ) {
        List<LocalDateTime> times = new ArrayList<>();
        times.add(record.getStartTime());
        times.add(record.getEndTime());
        includeActions(times, oldActions);
        includeActions(times, newActions);
        includeIntake(times, oldIntake);
        includeIntake(times, newIntake);
        List<LocalDateTime> sortedTimes = times.stream().filter(Objects::nonNull).sorted().toList();
        if (sortedTimes.isEmpty()) return Optional.empty();

        // Monitoring records are stored at the start of the hour, even for actions at HH:mm.
        return Optional.of(new MedIntakeTimeRange(
            sortedTimes.getFirst().truncatedTo(ChronoUnit.HOURS), sortedTimes.getLast()));
    }

    /** Deletion includes endUtc; monitoring queries exclude their end, including at shift boundaries. */
    public LocalDateTime queryEndUtc() {
        return endUtc.plusNanos(1);
    }

    private static void includeActions(List<LocalDateTime> times, List<MedicationExecutionAction> actions) {
        if (actions != null) actions.forEach(action -> times.add(action.getCreatedAt()));
    }

    private static void includeIntake(List<LocalDateTime> times, MedMonitoringService.FluidIntakeData intake) {
        if (intake == null) return;
        times.addAll(intake.intakeMap.keySet());
        times.add(intake.estimatedFinishTime);
    }
}
