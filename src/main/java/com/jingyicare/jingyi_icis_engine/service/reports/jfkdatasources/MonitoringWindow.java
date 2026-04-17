package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.LocalDateTime;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;

public record MonitoringWindow(
    PatientRecord patient,
    String deptId,
    LocalDateTime monStartUtc,
    LocalDateTime monEndUtc,
    LocalDateTime monStartLocal,
    LocalDateTime monEndLocal,
    int monStartHour
) {
}
