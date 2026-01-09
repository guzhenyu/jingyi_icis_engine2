package com.jingyicare.jingyi_icis_engine.service.reports;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReportAh2.*;

import com.jingyicare.jingyi_icis_engine.entity.reports.*;
import com.jingyicare.jingyi_icis_engine.repository.reports.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class PatientNursingReportUtils {
    public PatientNursingReportUtils(
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils configShiftUtils,
        @Autowired PatientNursingReportRepository pnrRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.configShiftUtils = configShiftUtils;
        this.pnrRepo = pnrRepo;
    }

    @Transactional
    public void updateLatestDataTime(
        Long pid, String deptId, LocalDateTime startUtc, LocalDateTime endUtc, LocalDateTime calcUtc
    ) {
        List<LocalDateTime> deptBalanceStatsUtcs = configShiftUtils.getBalanceStatTimeUtcHistory(deptId);
        LocalDateTime startMidnightUtc = getMidnightUtc(deptBalanceStatsUtcs, startUtc);
        LocalDateTime endMidnightUtc = getMidnightUtc(deptBalanceStatsUtcs, endUtc);

        List<PatientNursingReport> pnrList = pnrRepo.findByPidAndEffectiveTimeMidnightBetween(
            pid, startMidnightUtc, endMidnightUtc
        );
        Set<LocalDateTime> existMidnights = new HashSet<>();
        for (PatientNursingReport pnr : pnrList) {
            pnr.setLatestDataTime(calcUtc);
            existMidnights.add(pnr.getEffectiveTimeMidnight());
        }

        // 补齐缺失的护理单记录
        for (LocalDateTime midnightUtc = startMidnightUtc;
             !midnightUtc.isAfter(endMidnightUtc);
             midnightUtc = midnightUtc.plusDays(1)
        ) {
            if (!existMidnights.contains(midnightUtc)) {
                PatientNursingReport newPnr = new PatientNursingReport();
                newPnr.setPid(pid);
                newPnr.setEffectiveTimeMidnight(midnightUtc);
                newPnr.setLatestDataTime(calcUtc);
                pnrList.add(newPnr);
            }
        }

        pnrRepo.saveAll(pnrList);
    }

    @Transactional
    public void updateLastProcessedAt(
        Long pid, LocalDateTime effectiveTimeMidnight, Ah2PageDataPB dataPb, LocalDateTime lastProcessedAt
    ) {
        PatientNursingReport pnr = pnrRepo.findByPidAndEffectiveTimeMidnight(pid, effectiveTimeMidnight).orElse(null);
        if (pnr == null) {
            pnr = new PatientNursingReport();
            pnr.setPid(pid);
            pnr.setEffectiveTimeMidnight(effectiveTimeMidnight);
        }
        String dataPbStr = ProtoUtils.encodeAh2PageData(dataPb);
        pnr.setDataPb(dataPbStr);
        pnr.setLastProcessedAt(lastProcessedAt);
        pnrRepo.save(pnr);
    }

    public LocalDateTime getMidnightUtc(List<LocalDateTime> deptBalanceStatsUtcs, LocalDateTime inputUtc) {
        LocalDateTime startLocal = TimeUtils.getLocalDateTimeFromUtc(
            configShiftUtils.getBalanceStatStartUtc(deptBalanceStatsUtcs, inputUtc), ZONE_ID
        );
        LocalDateTime midnightLocal = TimeUtils.truncateToDay(startLocal);
        return TimeUtils.getUtcFromLocalDateTime(midnightLocal, ZONE_ID);
    }

    private final String ZONE_ID;
    private final ConfigShiftUtils configShiftUtils;
    private final PatientNursingReportRepository pnrRepo;
}