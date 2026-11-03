package com.jingyicare.jingyi_icis_engine.entity.snapshots;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

@Transactional
class PatientSnapshotTests extends TestsBase {
    private static final long PID = 9910301L;
    private static final LocalDate DATE = LocalDate.of(2026, 11, 3);

    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldRoundTripDateLongPayloadAndUtcTimestampsAtPostgresPrecision() {
        String payload = "A".repeat(4096);
        LocalDateTime modifiedAt = LocalDateTime.parse("2026-11-03T12:00:00.123456499");
        LocalDateTime snapshotAt = LocalDateTime.parse("2026-11-03T23:59:59.999999500");
        PatientSnapshot snapshot = PatientSnapshot.builder()
            .pid(PID)
            .statisticsDate(DATE)
            .payload(payload)
            .lastModifiedAt(modifiedAt)
            .snapshotAt(snapshotAt)
            .build();

        entityManager.persist(snapshot);
        flushAndClear();
        PatientSnapshot stored = entityManager.find(PatientSnapshot.class, snapshot.getId());

        assertThat(stored.getId()).isPositive();
        assertThat(stored.getPid()).isEqualTo(PID);
        assertThat(stored.getStatisticsDate()).isEqualTo(DATE);
        assertThat(stored.getPayload()).isEqualTo(payload);
        assertThat(stored.getLastModifiedAt()).isEqualTo("2026-11-03T12:00:00.123456");
        assertThat(stored.getSnapshotAt()).isEqualTo("2026-11-04T00:00:00");
    }

    @Test
    void shouldAllowMissingPayloadAndTimestampsForInvalidatedSnapshot() {
        PatientSnapshot snapshot = PatientSnapshot.builder()
            .pid(PID)
            .statisticsDate(DATE)
            .build();

        entityManager.persist(snapshot);
        flushAndClear();
        PatientSnapshot stored = entityManager.find(PatientSnapshot.class, snapshot.getId());

        assertThat(stored.getPayload()).isNull();
        assertThat(stored.getLastModifiedAt()).isNull();
        assertThat(stored.getSnapshotAt()).isNull();
    }

    @Test
    void shouldEnforceUniquenessOnlyForSamePatientAndStatisticsDate() {
        entityManager.persist(snapshot(PID, DATE));
        entityManager.persist(snapshot(PID, DATE.plusDays(1)));
        entityManager.persist(snapshot(PID + 1, DATE));
        flushAndClear();

        assertThatThrownBy(() -> {
            entityManager.persist(snapshot(PID, DATE));
            entityManager.flush();
        }).isInstanceOf(PersistenceException.class)
            .hasRootCauseInstanceOf(PSQLException.class)
            .rootCause()
            .hasMessageContaining("idx_patient_snapshots_pid_statistics_date");
    }

    private PatientSnapshot snapshot(long pid, LocalDate statisticsDate) {
        return PatientSnapshot.builder().pid(pid).statisticsDate(statisticsDate).build();
    }

    private void flushAndClear() {
        entityManager.flush();
        entityManager.clear();
    }
}
