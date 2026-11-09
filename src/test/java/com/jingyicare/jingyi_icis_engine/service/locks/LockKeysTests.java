package com.jingyicare.jingyi_icis_engine.service.locks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class LockKeysTests {
    @Test
    void shouldScopeBgaParamKeysToDepartment() {
        assertThat(LockKeys.bgaParam("icu")).isEqualTo("bga_params:icu")
            .isNotEqualTo(LockKeys.bgaParam("other-icu"))
            .isNotEqualTo(LockKeys.department("icu"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void shouldRejectMissingBgaDepartment(String invalid) {
        assertThatIllegalArgumentException().isThrownBy(() -> LockKeys.bgaParam(invalid));
    }

    @Test
    void shouldUseSharedMonitoringParamListKey() {
        assertThat(LockKeys.monitoringParam()).isEqualTo("monitoring_params");
    }

    @Test
    void shouldScopeScoreKeysToDepartmentAndCode() {
        assertThat(LockKeys.deptScoreGroup("icu"))
            .isEqualTo("dept_score_groups:icu");
        assertThat(LockKeys.deptScoreGroup("icu", "gcs"))
            .isEqualTo("dept_score_groups:icu:gcs")
            .isNotEqualTo(LockKeys.deptScoreGroup("other-icu", "gcs"))
            .isNotEqualTo(LockKeys.deptScoreGroup("icu", "rass"))
            .isNotEqualTo(LockKeys.deptScoreGroup("icu"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void shouldRejectMissingDepartmentOrScoreCode(String invalid) {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> LockKeys.deptScoreGroup(invalid));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> LockKeys.deptScoreGroup(invalid, "gcs"));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> LockKeys.deptScoreGroup("icu", invalid));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "2026-11-03T12:00:00",
        "2026-11-03T12:00:30.123456789",
        "2026-11-03T23:59:59.999999999"
    })
    void shouldPreserveEffectiveTimePrecision(LocalDateTime input) {
        assertThat(LockKeys.nursingRecord(42L, input))
            .isEqualTo("nursing_record:42:" + input);
    }

    @Test
    void shouldDistinguishPatientsAndEffectiveTimes() {
        LocalDateTime time = LocalDateTime.of(2026, 11, 3, 12, 0);
        String key = LockKeys.nursingRecord(42L, time);

        assertThat(key).isNotEqualTo(LockKeys.nursingRecord(43L, time));
        assertThat(key).isNotEqualTo(LockKeys.nursingRecord(42L, time.plusNanos(1)));
    }

    @Test
    void shouldScopeSnapshotKeyToPatientAndStatisticsDate() {
        LocalDate date = LocalDate.of(2026, 11, 3);
        String key = LockKeys.patientSnapshot(42L, date);

        assertThat(key).isEqualTo("patient_snapshot:42:2026-11-03");
        assertThat(key).isNotEqualTo(LockKeys.patientSnapshot(43L, date));
        assertThat(key).isNotEqualTo(LockKeys.patientSnapshot(42L, date.plusDays(1)));
        assertThat(key).isNotEqualTo(LockKeys.nursingRecord(42L, date.atStartOfDay()));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void shouldRejectNonPositivePatientIds(long pid) {
        assertThatIllegalArgumentException().isThrownBy(() ->
            LockKeys.nursingRecord(pid, LocalDateTime.of(2026, 11, 3, 12, 0)));
        assertThatIllegalArgumentException().isThrownBy(() ->
            LockKeys.patientSnapshot(pid, LocalDate.of(2026, 11, 3)));
    }

    @Test
    void shouldRejectMissingEffectiveTimeOrStatisticsDate() {
        assertThatIllegalArgumentException().isThrownBy(() -> LockKeys.nursingRecord(42L, null));
        assertThatIllegalArgumentException().isThrownBy(() -> LockKeys.patientSnapshot(42L, null));
    }
}
