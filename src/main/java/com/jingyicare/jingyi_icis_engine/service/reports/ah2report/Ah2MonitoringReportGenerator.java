package com.jingyicare.jingyi_icis_engine.service.reports.ah2report;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportGenerator;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportRequest;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportResult;

@Service
public class Ah2MonitoringReportGenerator implements MonitoringReportGenerator {
    public Ah2MonitoringReportGenerator(
        @Autowired Ah2ReportService ah2ReportService
    ) {
        this.ah2ReportService = ah2ReportService;
    }

    @Override
    public String version() {
        return "ah2";
    }

    @Override
    public MonitoringReportResult generate(MonitoringReportRequest request) {
        return new MonitoringReportResult(
            ah2ReportService.drawPdf(
                request.getPid(),
                request.getDeptId(),
                request.getQueryStartUtc(),
                request.getQueryEndUtc(),
                request.getAccountId(),
                request.getReportPath()
            ),
            0
        );
    }

    private final Ah2ReportService ah2ReportService;
}
