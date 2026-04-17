package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.entity.patientshifts.PatientShiftRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patientshifts.PatientShiftRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class PatientInfoExtendedDataSourceHandler extends AbstractJfkDataSourceHandler {
    public PatientInfoExtendedDataSourceHandler(
        JfkDataSourceSupport support,
        MonitoringWindowResolver monitoringWindowResolver,
        SurgeryHistoryRepository surgeryHistoryRepo,
        PatientShiftRecordRepository patientShiftRecordRepo
    ) {
        super(support);
        this.monitoringWindowResolver = monitoringWindowResolver;
        this.surgeryHistoryRepo = surgeryHistoryRepo;
        this.patientShiftRecordRepo = patientShiftRecordRepo;
    }

    @Override
    public String getMetaId() {
        return JfkDataSourceIds.PATIENT_INFO_EXTENDED;
    }

    @Override
    public Pair<ReturnCode, JfkDataSourcePB> handle(JfkDataSourcePB input) {
        Long pid = getInt64Input(input, FIELD_PID);
        String queryStartIso = getStringInput(input, FIELD_QUERY_START);

        List<String> missingFields = new ArrayList<>();
        if (pid == null || pid <= 0) missingFields.add(FIELD_PID);
        if (StrUtils.isBlank(queryStartIso)) missingFields.add(FIELD_QUERY_START);
        if (!missingFields.isEmpty()) {
            return error(StatusCode.JFK_MISSING_REQUIRED_FIELD, joinMissingFields(missingFields));
        }

        Pair<ReturnCode, MonitoringWindow> windowResult = monitoringWindowResolver.resolve(pid, queryStartIso);
        if (windowResult.getFirst().getCode() != StatusCode.OK.ordinal()) {
            return new Pair<>(windowResult.getFirst(), null);
        }

        MonitoringWindow window = windowResult.getSecond();
        PatientRecord patient = window.patient();
        RbacDepartment department = support.getDeptRepo().findByDeptId(window.deptId()).orElse(null);
        if (department == null) {
            return error(StatusCode.DEPT_NOT_FOUND, "deptId: " + window.deptId());
        }

        List<SurgeryHistory> surgeries = surgeryHistoryRepo.findByPatientId(pid);

        JfkDataSourcePB.Builder outputBuilder = newOutputBuilder(input);
        support.addStrOutput(outputBuilder, "dept_name", department.getDeptName());
        support.addStrOutput(outputBuilder, "bed_no", support.getBedNumber(patient, window.monEndUtc()));
        support.addStrOutput(outputBuilder, "patient_name", patient.getIcuName());
        support.addStrOutput(outputBuilder, "gender", gender(patient));
        support.addStrOutput(outputBuilder, "age", age(patient.getIcuDateOfBirth(), window.monEndLocal()));
        support.addStrOutput(outputBuilder, "mrn", patient.getHisMrn());
        support.addStrOutput(outputBuilder, "diagnosis", patient.getDiagnosis());
        support.addStrOutput(outputBuilder, "height", height(patient.getHeight()));
        support.addStrOutput(outputBuilder, "weight", weight(patient.getWeight()));
        support.addStrOutput(outputBuilder, "admission_time_yyyymmdd", admissionDate(patient.getAdmissionTime()));
        support.addStrOutput(outputBuilder, "illness_severity_level", patient.getIllnessSeverityLevel());
        support.addStrOutput(outputBuilder, "days_after_surgery", daysAfterSurgery(surgeries, window.monEndUtc()));
        support.addStrOutput(outputBuilder, "mon_record_day_range", recordDayRange(window));
        support.addStrOutput(outputBuilder, "diagnosis_and_surgery", diagnosisAndSurgery(patient, surgeries));
        support.addStrOutput(outputBuilder, "patient_shift_info", patientShiftInfo(pid, window.monStartUtc(), window.monEndUtc()));

        return new Pair<>(returnCode(StatusCode.OK), outputBuilder.build());
    }

    private String gender(PatientRecord patient) {
        return patient.getIcuGender() == 1 ? "男" : (patient.getIcuGender() == 0 ? "女" : "未知");
    }

    private String age(LocalDateTime birthDate, LocalDateTime asOfLocal) {
        if (birthDate == null || asOfLocal == null) return "";
        LocalDate birthDay = birthDate.toLocalDate();
        LocalDate asOfDay = asOfLocal.toLocalDate();
        if (birthDay.isAfter(asOfDay)) return "";
        return Period.between(birthDay, asOfDay).getYears() + "岁";
    }

    private String height(float height) {
        return height <= 0f ? "" : Math.round(height) + "cm";
    }

    private String weight(float weight) {
        return weight <= 0f ? "" : Math.round(weight) + "kg";
    }

    private String admissionDate(LocalDateTime admissionTimeUtc) {
        if (admissionTimeUtc == null) return "";
        return TimeUtils.getLocalDateTimeFromUtc(admissionTimeUtc, support.getZoneId())
            .format(DATE_FORMATTER);
    }

    private String daysAfterSurgery(List<SurgeryHistory> surgeries, LocalDateTime monEndUtc) {
        LocalDateTime latestEndTime = surgeries.stream()
            .map(SurgeryHistory::getEndTime)
            .filter(endTime -> endTime != null)
            .max(LocalDateTime::compareTo)
            .orElse(null);
        if (latestEndTime == null) return "天";
        if (latestEndTime.isAfter(monEndUtc)) return "0天";
        return Duration.between(latestEndTime, monEndUtc).toHours() / 24 + "天";
    }

    private String recordDayRange(MonitoringWindow window) {
        return window.monStartLocal().format(DATE_FORMATTER) + "-" + window.monEndLocal().format(DATE_FORMATTER);
    }

    private String diagnosisAndSurgery(PatientRecord patient, List<SurgeryHistory> surgeries) {
        List<String> parts = new ArrayList<>();
        if (!StrUtils.isBlank(patient.getDiagnosis())) {
            parts.add(patient.getDiagnosis());
        }
        if (!StrUtils.isBlank(patient.getDiagnosisTcm())) {
            parts.add(patient.getDiagnosisTcm());
        }

        List<String> surgeryNames = surgeries.stream()
            .sorted(Comparator.comparing(
                SurgeryHistory::getEndTime,
                Comparator.nullsLast(Comparator.reverseOrder())
            ))
            .map(SurgeryHistory::getName)
            .filter(name -> !StrUtils.isBlank(name))
            .toList();
        if (!surgeryNames.isEmpty()) {
            parts.add("手术: " + String.join(" + ", surgeryNames));
        }
        return String.join("; ", parts);
    }

    private String patientShiftInfo(Long pid, LocalDateTime monStartUtc, LocalDateTime monEndUtc) {
        return patientShiftRecordRepo.findByPidAndContainedInTimeRange(pid, monStartUtc, monEndUtc).stream()
            .map(record -> safe(record.getShiftName()) + ": " + safe(record.getShiftNurseName()))
            .collect(Collectors.joining("  "));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private static final String FIELD_PID = "pid";
    private static final String FIELD_QUERY_START = "query_start";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final MonitoringWindowResolver monitoringWindowResolver;
    private final SurgeryHistoryRepository surgeryHistoryRepo;
    private final PatientShiftRecordRepository patientShiftRecordRepo;
}
