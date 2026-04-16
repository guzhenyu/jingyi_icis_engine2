package com.jingyicare.jingyi_icis_engine.service.reports.compactreport;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportGenerator;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportRequest;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportResult;

@Service
public class CompactMonitoringReportGenerator implements MonitoringReportGenerator {
    public CompactMonitoringReportGenerator(
        @Autowired CompactReportService compactReportService
    ) {
        this.compactReportService = compactReportService;
    }

    @Override
    public String version() {
        return "compact";
    }

    @Override
    public MonitoringReportResult generate(MonitoringReportRequest request) {
        return new MonitoringReportResult(compactReportService.generate(request), 0);
    }

    private final CompactReportService compactReportService;
}
