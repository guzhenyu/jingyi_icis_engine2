package com.jingyicare.jingyi_icis_engine.service.reports.ah2report;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.reports.PatientNursingReport;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportAh2.Ah2PageDataPB;
import com.jingyicare.jingyi_icis_engine.repository.reports.PatientNursingReportRepository;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Component
public class Ah2NursingReportCacheService {
    public Ah2NursingReportCacheService(
        @Autowired PatientNursingReportRepository pnrRepo
    ) {
        this.pnrRepo = pnrRepo;
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

    private final PatientNursingReportRepository pnrRepo;
}
