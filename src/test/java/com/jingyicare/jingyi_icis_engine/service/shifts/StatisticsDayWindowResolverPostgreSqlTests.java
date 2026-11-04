package com.jingyicare.jingyi_icis_engine.service.shifts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.shifts.BalanceStatsShift;
import com.jingyicare.jingyi_icis_engine.repository.shifts.BalanceStatsShiftRepository;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

@Transactional
class StatisticsDayWindowResolverPostgreSqlTests extends TestsBase {
    private static final LocalDateTime ANCHOR = LocalDateTime.of(2026, 10, 22, 16, 0);
    private static final String ZONE = "Asia/Shanghai";
    private static final String DEPT = "statistics-window-history";

    @Autowired
    private BalanceStatsShiftRepository repository;
    @Autowired
    private StatisticsDayWindowResolver resolver;
    @Autowired
    private EntityManager entityManager;

    @Test
    void shouldSelectLatestActiveConfigurationForEachBatchDayInInputOrder() {
        save(DEPT, ANCHOR.minusDays(1), 8, 8, false);
        save(DEPT, ANCHOR.plusHours(12), 6, 7, false);
        save(DEPT, ANCHOR.plusHours(23), 9, 9, true);
        save(DEPT, ANCHOR.plusDays(1), 10, 11, false);
        save("statistics-window-other", ANCHOR.plusHours(23), 15, 15, false);
        repository.flush();
        entityManager.clear();

        List<StatisticsDayWindow> windows = resolver.resolveInternal(
            List.of(ANCHOR.plusDays(1), ANCHOR, ANCHOR.plusDays(1)), ZONE, DEPT);
        assertThat(windows).hasSize(3);
        assertThat(windows.get(2)).isEqualTo(windows.get(0));

        // 输入乱序且重复，各日期仍须分别按严格截止时间选择配置。
        StatisticsDayWindow day = windows.get(1);
        assertThat(day.balanceStartUtc()).isEqualTo(ANCHOR.plusHours(6));
        assertThat(day.balanceEndUtc()).isEqualTo(ANCHOR.plusDays(1).plusHours(6));
        assertThat(day.monitoringStartUtc()).isEqualTo(ANCHOR.plusHours(7));
        assertThat(day.monitoringEndUtc()).isEqualTo(ANCHOR.plusDays(1).plusHours(7));
        assertThat(day.halfDayShiftHours()).isEqualTo(9);

        StatisticsDayWindow next = windows.get(0);
        assertThat(next.balanceStartUtc()).isEqualTo(ANCHOR.plusDays(1).plusHours(10));
        assertThat(next.monitoringStartUtc()).isEqualTo(ANCHOR.plusDays(1).plusHours(11));
    }

    @Test
    void shouldUseDefaultsWhenNoActiveConfigurationExists() {
        String deptId = "statistics-window-deleted";
        save(deptId, ANCHOR.minusDays(1), 6, 7, true);
        repository.flush();
        entityManager.clear();

        StatisticsDayWindow day = resolver.resolve(ANCHOR, ZONE, deptId);
        assertThat(day.startHour()).isEqualTo(8);
        assertThat(day.monStartHour()).isEqualTo(8);
        assertThat(day.halfDayShiftHours()).isEqualTo(12);
        assertThat(day.balanceStartUtc()).isEqualTo(ANCHOR.plusHours(8));
        assertThat(day.balanceEndUtc()).isEqualTo(ANCHOR.plusDays(1).plusHours(8));
        assertThat(day.monitoringStartUtc()).isEqualTo(day.balanceStartUtc());
        assertThat(day.monitoringEndUtc()).isEqualTo(day.balanceEndUtc());
    }

    @ParameterizedTest
    @CsvSource({"6, 7", "10, 9"})
    void shouldResolveStatisticDatesAcrossConfigurationOverlapAndGap(
        int nextStartHour, int instantHour
    ) {
        save(DEPT, ANCHOR.minusDays(1), 8, 8, false);
        // 恰好在前一统计日截止时生效，前一日必须继续使用旧配置。
        save(DEPT, ANCHOR, nextStartHour, nextStartHour, false);
        int interferingHour = nextStartHour == 6 ? 10 : 6;
        save(DEPT, ANCHOR.plusHours(22), interferingHour, interferingHour, true);
        save("statistics-window-other", ANCHOR.plusHours(23),
            interferingHour, interferingHour, false);
        repository.flush();
        entityManager.clear();

        List<LocalDate> expected = nextStartHour == 6
            ? List.of(LocalDate.of(2026, 10, 22), LocalDate.of(2026, 10, 23))
            : List.of();
        assertThat(resolver.resolveStatisticDates(ANCHOR.plusHours(instantHour), ZONE, DEPT))
            .containsExactlyElementsOf(expected);
    }

    private void save(
        String deptId, LocalDateTime effectiveTime, int startHour, int monHour, boolean deleted
    ) {
        repository.save(BalanceStatsShift.builder()
            .deptId(deptId)
            .effectiveTime(effectiveTime)
            .startHour(startHour)
            .monStartHour(monHour)
            .halfDayShiftHours(9)
            .isDeleted(deleted)
            .modifiedAt(effectiveTime)
            .build());
    }
}
