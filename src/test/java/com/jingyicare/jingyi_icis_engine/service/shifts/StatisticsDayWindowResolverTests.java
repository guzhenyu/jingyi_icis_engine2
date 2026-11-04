package com.jingyicare.jingyi_icis_engine.service.shifts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

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
    private final List<BalanceStatsShift> history = new ArrayList<>();

    @Test
    void shouldResolveBothWindowsFromOneSelectionBeforeNextLocalMidnight() {
        configure(ANCHOR, shift(6, 7, 9));
        StatisticsDayWindow window = resolver.resolve(ANCHOR, ZONE, DEPT);
        assertThat(window.balanceStartUtc()).isEqualTo("2026-10-22T22:00:00");
        assertThat(window.balanceEndUtc()).isEqualTo("2026-10-23T22:00:00");
        assertThat(window.monitoringStartUtc()).isEqualTo("2026-10-22T23:00:00");
        assertThat(window.monitoringEndUtc()).isEqualTo("2026-10-23T23:00:00");
        assertThat(window.halfDayShiftHours()).isEqualTo(9);
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldUseEightTwelveEightOnlyWhenNoConfigurationExists() {
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
        configure(ANCHOR, shift(start, mon, half));
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
        configure(ANCHOR, shift(start, 7, 12));
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolve(ANCHOR, ZONE, DEPT));
    }

    @ParameterizedTest
    @ValueSource(ints = {6, 10})
    void shouldLeaveAdjacentDayOverlapOrGapUnadjusted(int nextHour) {
        configure(ANCHOR, shift(8, 8, 12));
        configure(ANCHOR.plusDays(1), shift(nextHour, nextHour, 12));
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
        configure(anchor, shift(0, 8, 12));
        StatisticsDayWindow window = resolver.resolve(anchor, "America/New_York", DEPT);
        assertThat(window.balanceStartUtc()).isEqualTo(anchor);
        assertThat(window.balanceEndUtc()).isEqualTo(cutoff);
        assertThat(Duration.between(window.balanceStartUtc(), window.balanceEndUtc()).toHours())
            .isEqualTo(hours);
        assertThat(window.monitoringStartUtc()).isEqualTo(monStart);
        assertThat(window.monitoringEndUtc()).isEqualTo(monEnd);
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
    }

    @Test
    void shouldRejectNonMidnightAnchorInvalidZoneAndBlankDepartment() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolve(null, ZONE, DEPT));
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
        when(repo.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT)).thenThrow(failure);
        assertThatThrownBy(() -> resolver.resolve(ANCHOR, ZONE, DEPT)).isSameAs(failure);
    }

    @Test
    void shouldResolveBatchInInputOrderAndPreserveDuplicatesWithOneQuery() {
        configure(ANCHOR, shift(6, 7, 9));
        configure(ANCHOR.plusDays(1), shift(10, 11, 6));

        List<StatisticsDayWindow> windows = resolver.resolveInternal(
            List.of(ANCHOR.plusDays(1), ANCHOR, ANCHOR.plusDays(1)), ZONE, DEPT);

        assertThat(windows).hasSize(3);
        assertThat(windows).extracting(StatisticsDayWindow::startHour).containsExactly(10, 6, 10);
        assertThat(windows).extracting(StatisticsDayWindow::monitoringStartUtc).containsExactly(
            ANCHOR.plusDays(1).plusHours(11), ANCHOR.plusHours(7),
            ANCHOR.plusDays(1).plusHours(11));
        assertThat(windows).extracting(StatisticsDayWindow::halfDayShiftHours)
            .containsExactly(6, 9, 6);
        assertThat(windows.get(0)).isEqualTo(windows.get(2));
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldApplyStrictCutoffsAndIgnoreFutureConfigurationForEachBatchDate() {
        LocalDateTime cutoff = ANCHOR.plusDays(1);
        configure(cutoff.minusNanos(1), shift(6, 7, 9));
        configure(cutoff, shift(10, 11, 6));
        configure(ANCHOR.plusDays(3), shift(null, 8, 12));

        List<StatisticsDayWindow> windows = resolver.resolveInternal(
            List.of(ANCHOR, ANCHOR.plusDays(1), ANCHOR.minusDays(1)), ZONE, DEPT);

        assertThat(windows).extracting(StatisticsDayWindow::startHour).containsExactly(6, 10, 8);
        assertThat(windows).extracting(StatisticsDayWindow::monStartHour).containsExactly(7, 11, 8);
        assertThat(windows).extracting(StatisticsDayWindow::halfDayShiftHours)
            .containsExactly(9, 6, 12);
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldReturnEmptyBatchWithoutQueryingConfiguration() {
        assertThat(resolver.resolveInternal(List.of(), ZONE, DEPT)).isEmpty();
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldValidateEveryBatchAnchorBeforeQueryingConfiguration() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveInternal(null, ZONE, DEPT));
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolveInternal(
            Arrays.asList(ANCHOR, null), ZONE, DEPT));
        assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolveInternal(
            List.of(ANCHOR, ANCHOR.plusDays(1).plusNanos(1)), ZONE, DEPT));
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldValidateTimezoneAndDepartmentEvenForEmptyBatch() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveInternal(List.of(), null, DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveInternal(List.of(), " ", DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveInternal(List.of(), "not-a-zone", DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveInternal(List.of(), ZONE, null));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveInternal(List.of(), ZONE, " "));
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldPropagateDatabaseFailureForBatch() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(repo.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT)).thenThrow(failure);

        assertThatThrownBy(() -> resolver.resolveInternal(
            List.of(ANCHOR, ANCHOR.plusDays(1)), ZONE, DEPT)).isSameAs(failure);
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
        verifyNoMoreInteractions(repo);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-23T16:00, 2026-10-23",
        "2026-10-23T23:00, 2026-10-23",
        "2026-10-23T23:59:59.999999999, 2026-10-23",
        "2026-10-24T00:00, 2026-10-24",
        "2026-10-24T03:30, 2026-10-24"
    })
    void shouldFindDefaultStatisticDateUsingHalfOpenWindows(
        LocalDateTime utc, LocalDate expectedDate
    ) {
        assertThat(resolver.resolveStatisticDates(utc, ZONE, DEPT)).containsExactly(expectedDate);
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-23T22:00",
        "2026-10-23T23:00",
        "2026-10-23T23:59:59.999999999"
    })
    void shouldReturnBothStatisticDatesInAscendingOrderWhenStartMovesEarlier(LocalDateTime utc) {
        configure(ANCHOR, shift(8, 8, 12));
        configure(ANCHOR.plusDays(1), shift(6, 6, 12));

        assertThat(resolver.resolveStatisticDates(utc, ZONE, DEPT))
            .containsExactly(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24));
    }

    @Test
    void shouldExcludePreviousDateAtTheEndOfAnOverlap() {
        configure(ANCHOR, shift(8, 8, 12));
        configure(ANCHOR.plusDays(1), shift(6, 6, 12));

        assertThat(resolver.resolveStatisticDates(
            LocalDateTime.of(2026, 10, 24, 0, 0), ZONE, DEPT))
            .containsExactly(LocalDate.of(2026, 10, 24));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-10-24T00:00",
        "2026-10-24T01:00",
        "2026-10-24T01:59:59.999999999"
    })
    void shouldReturnNoStatisticDateInTheGapWhenStartMovesLater(LocalDateTime utc) {
        configure(ANCHOR, shift(8, 8, 12));
        configure(ANCHOR.plusDays(1), shift(10, 10, 12));

        assertThat(resolver.resolveStatisticDates(utc, ZONE, DEPT)).isEmpty();
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldIncludeTheDateAtTheEndOfAGap() {
        configure(ANCHOR, shift(8, 8, 12));
        configure(ANCHOR.plusDays(1), shift(10, 10, 12));

        assertThat(resolver.resolveStatisticDates(
            LocalDateTime.of(2026, 10, 24, 2, 0), ZONE, DEPT))
            .containsExactly(LocalDate.of(2026, 10, 24));
    }

    @Test
    void shouldSelectEachStatisticDaysConfigurationUsingItsOwnNextMidnight() {
        configure(ANCHOR, shift(8, 8, 12));
        configure(ANCHOR.plusDays(1).plusHours(12), shift(6, 6, 12));
        LocalDateTime utc = LocalDateTime.of(2026, 10, 23, 23, 0);

        assertThat(resolver.resolveStatisticDates(utc, ZONE, DEPT))
            .containsExactly(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24));
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
        verifyNoMoreInteractions(repo);
    }

    @ParameterizedTest
    @CsvSource({"6, 8", "8, 6"})
    void shouldIncludeDatesMatchedOnlyByMonitoringOrOnlyByBalance(int start, int mon) {
        configureEveryDay(shift(start, mon, 12));

        assertThat(resolver.resolveStatisticDates(
            LocalDateTime.of(2026, 10, 23, 23, 0), ZONE, DEPT))
            .containsExactly(LocalDate.of(2026, 10, 23), LocalDate.of(2026, 10, 24));
    }

    @Test
    void shouldReturnTheDateOnceWhenBothWindowKindsContainTheInstant() {
        configureEveryDay(shift(6, 8, 12));

        assertThat(resolver.resolveStatisticDates(
            LocalDateTime.of(2026, 10, 24, 1, 0), ZONE, DEPT))
            .containsExactly(LocalDate.of(2026, 10, 24));
    }

    @Test
    void shouldRejectInvalidConfigurationWithinTheThreeCandidateDates() {
        configureEveryDay(shift(8, 8, 12));
        configure(ANCHOR.plusDays(2), shift(null, 8, 12));

        assertThatIllegalArgumentException().isThrownBy(() -> resolver.resolveStatisticDates(
            LocalDateTime.of(2026, 10, 24, 1, 0), ZONE, DEPT));
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
        verifyNoMoreInteractions(repo);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 24})
    void shouldUseBalanceHourWhenFindingDatesWithInvalidMonitoringHour(Integer mon) {
        configureEveryDay(shift(6, mon, 12));

        assertThat(resolver.resolveStatisticDates(
            LocalDateTime.of(2026, 10, 23, 23, 0), ZONE, DEPT))
            .containsExactly(LocalDate.of(2026, 10, 24));
    }

    @ParameterizedTest
    @CsvSource({
        "2026-03-08T04:59:59.999999999, 2026-03-07",
        "2026-03-08T05:00, 2026-03-08",
        "2026-03-08T06:30, 2026-03-08",
        "2026-03-08T07:30, 2026-03-08",
        "2026-03-09T03:59:59.999999999, 2026-03-08",
        "2026-03-09T04:00, 2026-03-09",
        "2026-11-01T03:59:59.999999999, 2026-10-31",
        "2026-11-01T04:00, 2026-11-01",
        "2026-11-01T05:30, 2026-11-01",
        "2026-11-01T06:30, 2026-11-01",
        "2026-11-02T04:59:59.999999999, 2026-11-01",
        "2026-11-02T05:00, 2026-11-02"
    })
    void shouldFindDatesAcrossDaylightSavingChangesAndBothRepeatedHourInstants(
        LocalDateTime utc, LocalDate expectedDate
    ) {
        configureEveryDay(shift(0, 0, 12));

        assertThat(resolver.resolveStatisticDates(utc, "America/New_York", DEPT))
            .containsExactly(expectedDate);
    }

    @Test
    void shouldFindPreviousStatisticDateBeforeStartHourInModernApia() {
        configureEveryDay(shift(8, 8, 12));

        assertThat(resolver.resolveStatisticDates(
            LocalDateTime.of(2026, 11, 3, 18, 0), "Pacific/Apia", DEPT))
            .containsExactly(LocalDate.of(2026, 11, 3));
    }

    @Test
    void shouldResolveEachCandidateMidnightIndependentlyWhenDayStartsAtOne() {
        configureEveryDay(shift(0, 0, 12));

        // 当天从 01:00 开始，前后两天仍从 00:00 开始。
        assertThat(resolver.resolveStatisticDates(
            LocalDateTime.of(2018, 11, 4, 3, 30), "America/Sao_Paulo", DEPT))
            .containsExactly(LocalDate.of(2018, 11, 4));
        verify(repo).findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT);
        verifyNoMoreInteractions(repo);
    }

    @Test
    void shouldFindNextStatisticDateWhenClockMovesBackAcrossMidnight() {
        configureEveryDay(shift(0, 0, 12));

        // 午夜后回拨至前一日 23 点，但后一统计日的窗口已经开始。
        assertThat(resolver.resolveStatisticDates(
            LocalDateTime.of(1987, 10, 25, 3, 30), "America/Goose_Bay", DEPT))
            .containsExactly(LocalDate.of(1987, 10, 25));
    }

    @Test
    void shouldRejectMissingArgumentsAndInvalidTimezoneWhenFindingDates() {
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveStatisticDates(null, ZONE, DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveStatisticDates(ANCHOR, null, DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveStatisticDates(ANCHOR, " ", DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveStatisticDates(ANCHOR, "not-a-zone", DEPT));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveStatisticDates(ANCHOR, ZONE, null));
        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveStatisticDates(ANCHOR, ZONE, " "));
        verifyNoMoreInteractions(repo);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {-1, 24})
    void shouldRejectInvalidBalanceHourWhenFindingStatisticDates(Integer start) {
        configureEveryDay(shift(start, 8, 12));

        assertThatIllegalArgumentException()
            .isThrownBy(() -> resolver.resolveStatisticDates(ANCHOR.plusHours(12), ZONE, DEPT));
    }

    @Test
    void shouldPropagateDatabaseFailureWhenFindingStatisticDates() {
        var failure = new DataAccessResourceFailureException("database unavailable");
        when(repo.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT)).thenThrow(failure);

        assertThatThrownBy(() -> resolver.resolveStatisticDates(
            ANCHOR.plusHours(12), ZONE, DEPT)).isSameAs(failure);
    }

    private void configureEveryDay(BalanceStatsShift shift) {
        configure(LocalDateTime.of(1900, 1, 1, 0, 0), shift);
    }

    private void configure(LocalDateTime effectiveTime, BalanceStatsShift shift) {
        shift.setEffectiveTime(effectiveTime);
        history.add(shift);
        history.sort(Comparator.comparing(BalanceStatsShift::getEffectiveTime).reversed());
        when(repo.findByDeptIdAndIsDeletedFalseOrderByEffectiveTimeDesc(DEPT))
            .thenReturn(List.copyOf(history));
    }

    private BalanceStatsShift shift(Integer start, Integer mon, Integer half) {
        return BalanceStatsShift.builder().deptId(DEPT)
            .startHour(start).monStartHour(mon).halfDayShiftHours(half).build();
    }
}
