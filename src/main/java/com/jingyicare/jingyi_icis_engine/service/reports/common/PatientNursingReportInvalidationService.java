package com.jingyicare.jingyi_icis_engine.service.reports.common;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.reports.PatientNursingReport;
import com.jingyicare.jingyi_icis_engine.repository.reports.PatientNursingReportRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.settings.stats.StatsDayWindowResolver;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class PatientNursingReportInvalidationService {
    public PatientNursingReportInvalidationService(
        ConfigProtoService protoService,
        StatsDayWindowResolver statsDayWindowResolver,
        PatientNursingReportRepository pnrRepo
    ) {
        this.zone = ZoneId.of(protoService.getConfig().getZoneId());
        this.statsDayWindowResolver = statsDayWindowResolver;
        this.pnrRepo = pnrRepo;
    }

    @Transactional
    public void updateLatestDataTime(
        Long pid, String deptId, LocalDateTime startUtc, LocalDateTime endUtc, LocalDateTime calcUtc
    ) {
        if (StrUtils.isBlank(deptId) || startUtc == null || endUtc == null
            || startUtc.isAfter(endUtc)) return;
        Map<LocalDateTime, List<LocalDateTime>> startsByInputUtc =
            statsDayWindowResolver.resolveBalanceStartsUtc(deptId, List.of(startUtc, endUtc));
        List<LocalDateTime> startsUtc = new ArrayList<>();
        startsUtc.addAll(startsByInputUtc.values().stream().flatMap(List::stream).toList());

        List<LocalDateTime> starts = startsByInputUtc.getOrDefault(startUtc, List.of());
        if (starts.isEmpty()) {
            startsUtc.addAll(getFallbackReportMidnightsUtc(startUtc));
        }
        List<LocalDateTime> ends = startsByInputUtc.getOrDefault(endUtc, List.of());
        if (ends.isEmpty()) {
            startsUtc.addAll(getFallbackReportMidnightsUtc(endUtc));
        }
        startsUtc.sort(Comparator.naturalOrder());

        LocalDateTime firstMidnightUtc =
            TimeUtils.getLocalMidnightUtc(startsUtc.getFirst(), zone.getId());
        LocalDateTime lastMidnightUtc =
            TimeUtils.getLocalMidnightUtc(startsUtc.getLast(), zone.getId());
        List<PatientNursingReport> reportsToUpdate = pnrRepo
            .findByPidAndEffectiveTimeMidnightBetween(pid, firstMidnightUtc, lastMidnightUtc);
        for (PatientNursingReport report : reportsToUpdate) {
            report.setLatestDataTime(calcUtc);
        }
        pnrRepo.saveAll(reportsToUpdate);
    }

    private List<LocalDateTime> getFallbackReportMidnightsUtc(LocalDateTime inputUtc) {
        LocalDate day = TimeUtils.getLocalDateTimeFromUtc(inputUtc, zone.getId()).toLocalDate();
        return List.of(
            TimeUtils.getDayMidnightUtc(day.minusDays(1), zone),
            TimeUtils.getDayMidnightUtc(day, zone)
        );
    }

    // AH2 分页和护理快捷项仍使用旧切点定位单个统计日。
    public LocalDateTime getMidnightUtc(
        List<LocalDateTime> deptBalanceStatsUtcs, LocalDateTime inputUtc
    ) {
        LocalDateTime cutoffUtc = null;
        for (LocalDateTime cutoff : deptBalanceStatsUtcs) {
            if (!inputUtc.isBefore(cutoff)) cutoffUtc = cutoff;
        }
        int startHour = cutoffUtc == null ? Consts.DEFAULT_SHIFT_START_HOUR
            : TimeUtils.getLocalDateTimeFromUtc(cutoffUtc, zone.getId()).getHour();
        LocalDateTime inputLocal = TimeUtils.getLocalDateTimeFromUtc(inputUtc, zone.getId());
        LocalDate day = inputLocal.toLocalDate();
        if (inputLocal.getHour() < startHour) day = day.minusDays(1);
        LocalDateTime startLocal = day.atTime(startHour, 0).atZone(zone).toLocalDateTime();
        return TimeUtils.getUtcFromLocalDateTime(TimeUtils.truncateToDay(startLocal), zone.getId());
    }

    private final ZoneId zone;
    private final StatsDayWindowResolver statsDayWindowResolver;
    private final PatientNursingReportRepository pnrRepo;
}
