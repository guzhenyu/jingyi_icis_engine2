package com.jingyicare.jingyi_icis_engine.service.shifts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessResourceFailureException;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;

class StatisticsDayWindowResolverTests {
    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 10, 22, 16, 0);
    private static final String ZONE = "Asia/Shanghai";
    private static final String DEPT = "dept-history";
    private final BalanceStatsShiftRepository repo = mock(BalanceStatsShiftRepository.class);
    private final StatisticsDayWindowResolver resolver = new StatisticsDayWindowResolver(repo);

    @Test
    void shouldResolveBothWindowsFromOneSelectionBeforeNextLocalMidnight() {
        configure(ANCHOR.plusDays(1), shift(6, 7, 9));
        StatisticsDayWindow window = resolver.resolve(ANCHOR, ZONE, DEPT);
        assertThat(window.balanceStartUtc()).isEqualTo("2026-10-22T22:00:00");
        assertThat(window.balanceEndUtc()).isEqualTo("2026-10-23T22:00:00");
        assertThat(window.monitoringStartUtc()).isEqualTo("2026-10-22T23:00:00");
        assertThat(window.monitoringEndUtc()).isEqualTo("2026-10-23T23:00:00");
        assertThat(window.halfDayShiftHours()).isEqualTo(9);
        verify(repo)
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
            DEPT, LocalDateTime.of(2026, 10, 23, 16, 0));
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldUseEightTwelveEightOnlyWhenNoConfigurationExists() {
        configure(ANCHOR.plusDays(1), null);
        StatisticsDayWindow window = resolver.resolve(ANCHOR, ZONE, DEPT);
        assertThat(window.startHour()).isEqualTo(8);
        assertThat(window.monStartHour()).isEqualTo(8);
        assertThat(window.halfDayShiftHours()).isEqualTo(12);
        assertThat(window.monitoringStartUtc()).isEqualTo("2026-10-23T00:00:00");
        assertThat(window.monitoringEndUtc()).isEqualTo("2026-10-24T00:00:00");
        assertThat(window.balanceStartUtc()).isEqualTo(window.monitoringStartUtc());
        assertThat(window.balanceEndUtc()).isEqualTo(window.monitoringEndUtc());
    }

    @ParameterizedTest
    @CsvSource(value = {
        "6, null, null, 6, 12",
        "6, -1, 0, 6, 12",
        "6, 24, 24, 6, 12",
        "6, 0, -1, 0, 12",
        "0, null, 12, 0, 12",
        "6, 23, 23, 23, 23",
        "6, 7, 1, 7, 1"
    }, nullValues = "null")
    void shouldPreserveValidHoursAndApplyFieldFallbacks(
        int start, Integer mon, Integer half, int expectedMon, int expectedHalf
    ) {
        configure(ANCHOR.plusDays(1), shift(start, mon, half));
        StatisticsDayWindow window = resolver.resolve(ANCHOR, ZONE, DEPT);
        assertThat(window.monStartHour()).isEqualTo(expectedMon);
        assertThat(window.halfDayShiftHours()).isEqualTo(expectedHalf);
        assertThat(window.monitoringStartUtc()).isEqualTo(ANCHOR.plusHours(expectedMon));
        assertThat(window.balanceStartUtc()).isEqualTo(ANCHOR.plusHours(start));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 24})
    void shouldRejectInvalidBalanceHourEvenWhenMonitoringHourIsValid(Integer start) {
        configure(ANCHOR.plusDays(1), shift(start, 7, 12));
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolve(ANCHOR, ZONE, DEPT));
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 10})
    void shouldLeaveAdjacentDayOverlapOrGapUnadjusted(int nextHour) {
        configure(ANCHOR.plusDays(1), shift(8, 8, 12));
        configure(ANCHOR.plusDays(2), shift(nextHour, nextHour, 12));
        StatisticsDayWindow first = resolver.resolve(ANCHOR, ZONE, DEPT);
        StatisticsDayWindow next = resolver.resolve(ANCHOR.plusDays(1), ZONE, DEPT);
        assertThat(first.monitoringEndUtc()).isEqualTo("2026-10-24T00:00:00");
        assertThat(next.monitoringStartUtc()).isEqualTo(first.monitoringEndUtc().plusHours(
            nextHour - 8));
        assertThat(first.balanceEndUtc()).isEqualTo(first.monitoringEndUtc());
        assertThat(next.balanceStartUtc()).isEqualTo(next.monitoringStartUtc());
    }

    @ParameterizedTest
    @CsvSource({
        "2026-03-08T05:00, 2026-03-09T04:00, 2026-03-08T12:00, 2026-03-09T12:00, 23",
        "2026-11-01T04:00, 2026-11-02T05:00, 2026-11-01T13:00, 2026-11-02T13:00, 25"
    })
    void shouldConvertEachLocalEndpointAcrossDaylightSavingTime(
        LocalDateTime anchor, LocalDateTime cutoff, LocalDateTime monStart, LocalDateTime monEnd,
            long hours
    ) {
        configure(cutoff, shift(0, 8, 12));
        StatisticsDayWindow window = resolver.resolve(anchor, "America/New_York", DEPT);
        assertThat(window.balanceStartUtc()).isEqualTo(anchor);
        assertThat(window.balanceEndUtc()).isEqualTo(cutoff);
        assertThat(Duration.between(window.balanceStartUtc(), window.balanceEndUtc()).toHours())
            .isEqualTo(hours);
        assertThat(window.monitoringStartUtc()).isEqualTo(monStart);
        assertThat(window.monitoringEndUtc()).isEqualTo(monEnd);
        verify(repo)
            .findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT,
            cutoff);
    }

    @Test
    void shouldRejectNonMidnightAnchorInvalidZoneAndBlankDepartment() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolve(ANCHOR.plusHours(1), ZONE, DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolve(ANCHOR.plusMinutes(1), ZONE, DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolve(ANCHOR.plusSeconds(1), ZONE, DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolve(ANCHOR.plusNanos(1), ZONE, DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolve(ANCHOR, "not-a-zone", DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolve(ANCHOR, ZONE, " "));
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldPropagateDatabaseFailureWithoutDefaulting() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(repo.findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
            DEPT, ANCHOR.plusDays(1))).thenThrow(failure);
        assertThatThrownBy(() -> resolver.resolve(ANCHOR, ZONE, DEPT)).isSameAs(failure);
    }

    private void configure(LocalDateTime cutoff, BalanceStatsShift shift) {
        when(repo.findFirstByDeptIdAndEffectiveTimeBeforeAndIsDeletedFalseOrderByEffectiveTimeDesc(
            DEPT, cutoff))
            .thenReturn(Optional.ofNullable(shift));
    }

    private BalanceStatsShift shift(Integer start, Integer mon, Integer half) {
        return BalanceStatsShift.builder().deptId(DEPT)
            .startHour(start).monStartHour(mon).halfDayShiftHours(half).build();
    }
}
