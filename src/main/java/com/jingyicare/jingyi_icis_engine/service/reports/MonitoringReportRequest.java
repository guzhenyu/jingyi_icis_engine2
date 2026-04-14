package com.jingyicare.jingyi_icis_engine.service.reports;

import java.time.LocalDateTime;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class MonitoringReportRequest {
    private String rawRequestJson;
    private Long pid;
    private String deptId;
    private Integer groupType;
    private LocalDateTime queryStartUtc;
    private LocalDateTime queryEndUtc;
    private String accountId;
    private LocalDateTime shiftStartTime;
    private boolean balanceReport;
    private PatientRecord patientRecord;
    private String reportPath;
    private String urlPath;
}
