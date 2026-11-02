package com.jingyicare.jingyi_icis_engine.service.shifts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
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
    void shouldSelectLatestActiveConfigurationStrictlyBeforeNextLocalMidnight() {
        save(DEPT, ANCHOR.minusDays(1), 8, 8, false);
        save(DEPT, ANCHOR.plusHours(12), 6, 7, false);
        save(DEPT, ANCHOR.plusHours(23), 9, 9, true);
        save(DEPT, ANCHOR.plusDays(1), 10, 11, false);
        save("statistics-window-other", ANCHOR.plusHours(23), 15, 15, false);
        repository.flush();
        entityManager.clear();

        StatisticsDayWindow day = resolver.resolve(ANCHOR, ZONE, DEPT);
        assertThat(day.balanceStartUtc()).isEqualTo(ANCHOR.plusHours(6));
        assertThat(day.balanceEndUtc()).isEqualTo(ANCHOR.plusDays(1).plusHours(6));
        assertThat(day.monitoringStartUtc()).isEqualTo(ANCHOR.plusHours(7));
        assertThat(day.monitoringEndUtc()).isEqualTo(ANCHOR.plusDays(1).plusHours(7));
        assertThat(day.halfDayShiftHours()).isEqualTo(9);

        StatisticsDayWindow next = resolver.resolve(ANCHOR.plusDays(1), ZONE, DEPT);
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
