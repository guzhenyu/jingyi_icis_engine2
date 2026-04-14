package com.jingyicare.jingyi_icis_engine.service.reports;

public interface MonitoringReportGenerator {
    String version();

    MonitoringReportResult generate(MonitoringReportRequest request);
}
