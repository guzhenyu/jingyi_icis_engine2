package com.jingyicare.jingyi_icis_engine.service.reports;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.reports.WardReport;
import com.jingyicare.jingyi_icis_engine.entity.scores.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.repository.reports.WardReportRepository;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class JfkDataService {
    public JfkDataService(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientService patientService,
        @Autowired PatientDeviceService patientDevService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired WardReportRepository wardReportRepo,
        @Autowired AccountRepository accountRepo,
        @Autowired DeptScoreGroupRepository deptScoreGroupRepo,
        @Autowired PatientScoreRepository patientScoreRepo,
        @Value("${report_ah2_ward_report_font}") Resource wardReportFontResource
    ) {
        this.protoService = protoService;
        this.patientService = patientService;
        this.patientDevService = patientDevService;
        this.shiftUtils = shiftUtils;
        this.deptRepo = deptRepo;
        this.wardReportRepo = wardReportRepo;
        this.accountRepo = accountRepo;
        this.deptScoreGroupRepo = deptScoreGroupRepo;
        this.patientScoreRepo = patientScoreRepo;

        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusMsgList = protoService.getConfig().getText().getStatusCodeMsgList();

        byte[] fontBytes = null;
        try (InputStream is = wardReportFontResource.getInputStream()) {
            fontBytes = is.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to load ward report font resource: {}", e.getMessage());
        }
        this.wardReportFontDataBytes = fontBytes;
    }

    /**
     * 获取 JFK 病人信息
     */
    public Pair<ReturnCode, JfkDataSourcePB> getPatientInfo(JfkDataSourcePB input) {
        Long pid = null;
        LocalDateTime shiftTimeStart = null;
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldData.getId().equals("pid")) {
                pid = fieldData.getVal().getInt64Val();
            }
            if (fieldData.getId().equals("shift_time")) {
                String timeIso8601 = fieldData.getVal().getStrVal();
                shiftTimeStart = TimeUtils.fromIso8601String(timeIso8601, "UTC");
            }
        }

        if (pid == null || shiftTimeStart == null) {
            String missingFields = "";
            if (pid == null) missingFields += "pid ";
            if (shiftTimeStart == null) missingFields += "shift_time ";
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.JFK_MISSING_REQUIRED_FIELD, missingFields), null);
        }

        Pair<ReturnCode, JfkPatientInfo> result = getJfkPatientInfo(pid, shiftTimeStart);
        if (result.getFirst().getCode() != 0) {
            return new Pair<>(result.getFirst(), null);
        }

        JfkDataSourcePB.Builder outputBuilder = JfkDataSourcePB.newBuilder()
            .setId(input.getId())
            .setMetaId(input.getMetaId())
            .addAllInputData(input.getInputDataList());

        JfkPatientInfo info = result.getSecond();
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("dept_name")
            .setVal(JfkValPB.newBuilder().setStrVal(info.deptName).build())
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("bed_no")
            .setVal(JfkValPB.newBuilder().setStrVal(info.bedNumber).build())
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("patient_name")
            .setVal(JfkValPB.newBuilder().setStrVal(info.name).build())
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("gender")
            .setVal(JfkValPB.newBuilder().setStrVal(info.gender).build())
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("age")
            .setVal(JfkValPB.newBuilder().setStrVal(info.ageStr).build())
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("mrn")
            .setVal(JfkValPB.newBuilder().setStrVal(info.mrn).build())
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("diagnosis")
            .setVal(JfkValPB.newBuilder().setStrVal(info.diagnosis).build())
            .build());

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
    }

    public static class JfkPatientInfo {
        public String deptName;
        public String bedNumber;
        public String name;
        public String gender;
        public String ageStr;
        public String mrn;
        public String diagnosis;
    }
    public Pair<ReturnCode, JfkPatientInfo> getJfkPatientInfo(Long pid, LocalDateTime shiftTimeStart) {
        // 查找病人信息
        PatientRecord patient = patientService.getPatientRecord(pid);
        if (patient == null) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(
                    statusMsgList, StatusCode.PATIENT_NOT_FOUND, "pid: " + pid),
                null
            );
        }
        String deptId = patient.getDeptId();

        // 获取班次信息
        ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(deptId);
        shiftTimeStart = shiftUtils.getShiftStartTime(shiftSettings, shiftTimeStart, ZONE_ID);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        LocalDateTime nowUtcShiftStart = shiftUtils.getShiftStartTime(shiftSettings, nowUtc, ZONE_ID);
        if (shiftTimeStart.isAfter(nowUtcShiftStart)) {
            shiftTimeStart = nowUtc;
        } else {
            shiftTimeStart = shiftTimeStart.plusDays(1).minusMinutes(1);
        }

        RbacDepartment department = deptRepo.findByDeptId(deptId).orElse(null);
        if (department == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.DEPT_NOT_FOUND, "deptId: " + deptId),
                null
            );
        }

        // 部门名称
        JfkPatientInfo info = new JfkPatientInfo();
        info.deptName = department.getDeptName();

        // 床号
        PatientDeviceService.UsageHistory<PatientDeviceService.BedName> bedHistory =
            patientDevService.getBedHistory(patient);
        String bedNumber = null;
        for (Pair<PatientDeviceService.BedName, LocalDateTime> entry : bedHistory.usageRecords) {
            PatientDeviceService.BedName bedName = entry.getFirst();
            LocalDateTime recordTime = entry.getSecond();
            if (bedNumber == null) bedNumber = bedName.displayBedNumber;
            else if (!recordTime.isAfter(shiftTimeStart)) {
                bedNumber = bedName.displayBedNumber;
            } else {
                break;
            }
        }
        if (bedNumber == null) bedNumber = "";
        info.bedNumber = bedNumber;

        // 姓名
        info.name = patient.getIcuName();

        // 性别
        info.gender = patient.getIcuGender() == 1 ? "男" : (patient.getIcuGender() == 0 ? "女" : "未知");

        // 年龄
        LocalDateTime birthDate = patient.getIcuDateOfBirth();
        info.ageStr = birthDate == null ? "" : TimeUtils.getAge(birthDate) + "岁";

        // 住院号
        info.mrn = patient.getHisMrn();

        // 诊断
        info.diagnosis = patient.getDiagnosis() == null ? "" : patient.getDiagnosis();

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), info);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getWardReportSummary(JfkDataSourcePB input) {
        String deptId = null;
        String shiftTimeIso = null;
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldData.getId().equals("dept_id")) {
                deptId = !StrUtils.isBlank(fieldData.getVal().getStrVal())
                    ? fieldData.getVal().getStrVal()
                    : String.valueOf(fieldData.getVal().getInt64Val());
            }
            if (fieldData.getId().equals("shift_time")) {
                shiftTimeIso = fieldData.getVal().getStrVal();
            }
        }

        if (StrUtils.isBlank(deptId) || StrUtils.isBlank(shiftTimeIso)) {
            String missingFields = "";
            if (StrUtils.isBlank(deptId)) missingFields += "dept_id ";
            if (StrUtils.isBlank(shiftTimeIso)) missingFields += "shift_time ";
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.JFK_MISSING_REQUIRED_FIELD, missingFields), null);
        }

        LocalDateTime shiftStartTime = TimeUtils.fromIso8601String(shiftTimeIso, "UTC");
        if (shiftStartTime == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.INVALID_TIME_FORMAT), null);
        }

        RbacDepartment dept = deptRepo.findByDeptId(deptId).orElse(null);
        if (dept == null) {
            JfkDataSourcePB.Builder outputBuilder = JfkDataSourcePB.newBuilder()
                .setId(input.getId())
                .setMetaId(input.getMetaId())
                .addAllInputData(input.getInputDataList());
            fillWardReportEmpty(outputBuilder);
            return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
        }

        JfkDataSourcePB.Builder outputBuilder = JfkDataSourcePB.newBuilder()
            .setId(input.getId())
            .setMetaId(input.getMetaId())
            .addAllInputData(input.getInputDataList());

        addStrOutput(outputBuilder, "dept_name", dept.getDeptName());
        addStrOutput(outputBuilder, "report_date", TimeUtils.getYearMonthDay2(shiftStartTime));

        WardReport wardReport = wardReportRepo
            .findByDeptIdAndShiftStartTimeAndIsDeletedFalse(deptId, shiftStartTime)
            .orElse(null);
        if (wardReport == null) {
            fillWardReportEmpty(outputBuilder);
            return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
        }

        WardReportPB wardReportPb = ProtoUtils.decodeWardReport(wardReport.getWardReportPb());
        if (wardReportPb == null) {
            fillWardReportEmpty(outputBuilder);
            return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
        }

        addStrOutput(outputBuilder, "day_shift_line1", buildLine1(wardReportPb.getDayShiftCount()));
        addStrOutput(outputBuilder, "day_shift_line2", buildLine2(wardReportPb.getDayShiftCount()));
        addStrOutput(outputBuilder, "evening_shift_line1", buildLine1(wardReportPb.getEveningShiftCount()));
        addStrOutput(outputBuilder, "evening_shift_line2", buildLine2(wardReportPb.getEveningShiftCount()));
        addStrOutput(outputBuilder, "night_shift_line1", buildLine1(wardReportPb.getNightShiftCount()));
        addStrOutput(outputBuilder, "night_shift_line2", buildLine2(wardReportPb.getNightShiftCount()));

        addStrOutput(outputBuilder, "day_shift_outgoing_handover_signature",
            getSignature(wardReportPb.getDayShiftCount().getOutgoingHandoverAccountId()));
        addStrOutput(outputBuilder, "day_shift_incoming_handover_signature",
            getSignature(wardReportPb.getDayShiftCount().getIncomingHandoverAccountId()));
        addStrOutput(outputBuilder, "evening_shift_outgoing_handover_signature",
            getSignature(wardReportPb.getEveningShiftCount().getOutgoingHandoverAccountId()));
        addStrOutput(outputBuilder, "evening_shift_incoming_handover_signature",
            getSignature(wardReportPb.getEveningShiftCount().getIncomingHandoverAccountId()));
        addStrOutput(outputBuilder, "night_shift_outgoing_handover_signature",
            getSignature(wardReportPb.getNightShiftCount().getOutgoingHandoverAccountId()));
        addStrOutput(outputBuilder, "night_shift_incoming_handover_signature",
            getSignature(wardReportPb.getNightShiftCount().getIncomingHandoverAccountId()));

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
    }

    public Pair<ReturnCode, JfkDataSourcePB> getWardReportPatients(JfkDataSourcePB input) {
        String deptId = null;
        String shiftTimeIso = null;
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldData.getId().equals("dept_id")) {
                deptId = !StrUtils.isBlank(fieldData.getVal().getStrVal())
                    ? fieldData.getVal().getStrVal()
                    : String.valueOf(fieldData.getVal().getInt64Val());
            }
            if (fieldData.getId().equals("shift_time")) {
                shiftTimeIso = fieldData.getVal().getStrVal();
            }
        }

        if (StrUtils.isBlank(deptId) || StrUtils.isBlank(shiftTimeIso)) {
            String missingFields = "";
            if (StrUtils.isBlank(deptId)) missingFields += "dept_id ";
            if (StrUtils.isBlank(shiftTimeIso)) missingFields += "shift_time ";
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.JFK_MISSING_REQUIRED_FIELD, missingFields), null);
        }

        LocalDateTime shiftStartTime = TimeUtils.fromIso8601String(shiftTimeIso, "UTC");
        if (shiftStartTime == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.INVALID_TIME_FORMAT), null);
        }

        JfkDataSourcePB.Builder outputBuilder = JfkDataSourcePB.newBuilder()
            .setId(input.getId())
            .setMetaId(input.getMetaId())
            .addAllInputData(input.getInputDataList());

        WardReport wardReport = wardReportRepo
            .findByDeptIdAndShiftStartTimeAndIsDeletedFalse(deptId, shiftStartTime)
            .orElse(null);
        if (wardReport == null) {
            fillWardReportPatientsEmpty(outputBuilder);
            return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
        }

        WardReportPB wardReportPb = ProtoUtils.decodeWardReport(wardReport.getWardReportPb());
        if (wardReportPb == null) {
            fillWardReportPatientsEmpty(outputBuilder);
            return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
        }

        if (wardReportFontDataBytes == null) {
            fillWardReportPatientsEmpty(outputBuilder);
            return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
        }

        List<String> diagnosisList = new ArrayList<>();
        List<String> dayNoteList = new ArrayList<>();
        List<String> eveningNoteList = new ArrayList<>();
        List<String> nightNoteList = new ArrayList<>();

        try (PDDocument document = new PDDocument()) {
            PDFont font = PDType0Font.load(document, new ByteArrayInputStream(wardReportFontDataBytes));
            for (WardPatientStatsPB stat : wardReportPb.getPatientStatsList()) {
                List<String> tmpDiagnosis = new ArrayList<>();
                JfkPdfUtils.wrapInto(
                    tmpDiagnosis,
                    buildPatientHeader(stat.getPid(), shiftStartTime),
                    font, 8f, 68f, 0f);
                List<String> diagnosisLines = stat.getDayShiftHandoverNote() == null
                    ? List.of("") : Arrays.asList(stat.getDiagnosis().split("\n"));
                tmpDiagnosis.addAll(JfkPdfUtils.getWrappedLines(font, 8f, 68f, 0f, diagnosisLines));

                List<String> dayNoteLines = stat.getDayShiftHandoverNote() == null
                    ? List.of("") : Arrays.asList(stat.getDayShiftHandoverNote().split("\n"));
                List<String> tmpDayNotes = JfkPdfUtils.getWrappedLines(font, 8f, 218f, 0f, dayNoteLines);

                List<String> eveningNoteLines = stat.getEveningShiftHandoverNote() == null
                    ? List.of("") : Arrays.asList(stat.getEveningShiftHandoverNote().split("\n"));
                List<String> tmpEveningNotes = JfkPdfUtils.getWrappedLines(font, 8f, 218f, 0f, eveningNoteLines);

                List<String> nightNoteLines = stat.getNightShiftHandoverNote() == null
                    ? List.of("") : Arrays.asList(stat.getNightShiftHandoverNote().split("\n"));
                List<String> tmpNightNotes = JfkPdfUtils.getWrappedLines(font, 8f, 218f, 0f, nightNoteLines);

                int maxSize = Math.max(
                    Math.max(tmpDiagnosis.size(), tmpDayNotes.size()),
                    Math.max(tmpEveningNotes.size(), tmpNightNotes.size()));
                padWithBlank(tmpDiagnosis, maxSize);
                padWithBlank(tmpDayNotes, maxSize);
                padWithBlank(tmpEveningNotes, maxSize);
                padWithBlank(tmpNightNotes, maxSize);

                tmpDiagnosis.add(" ");
                tmpDayNotes.add(" ");
                tmpEveningNotes.add(" ");
                tmpNightNotes.add(" ");

                diagnosisList.addAll(tmpDiagnosis);
                dayNoteList.addAll(tmpDayNotes);
                eveningNoteList.addAll(tmpEveningNotes);
                nightNoteList.addAll(tmpNightNotes);
            }
        } catch (IOException e) {
            log.error("Failed to wrap ward report patient notes: {}", e.getMessage());
            fillWardReportPatientsEmpty(outputBuilder);
            return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
        }

        diagnosisList = addPagePadding(diagnosisList);
        dayNoteList = addPagePadding(dayNoteList);
        eveningNoteList = addPagePadding(eveningNoteList);
        nightNoteList = addPagePadding(nightNoteList);

        addArrayOutput(outputBuilder, "diagnosis", diagnosisList);
        addArrayOutput(outputBuilder, "day_shift_notes", dayNoteList);
        addArrayOutput(outputBuilder, "evening_shift_notes", eveningNoteList);
        addArrayOutput(outputBuilder, "night_shift_notes", nightNoteList);

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
    }

    private void addStrOutput(JfkDataSourcePB.Builder builder, String id, String value) {
        builder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setStrVal(value == null ? "" : value).build())
            .build());
    }

    private void addArrayOutput(JfkDataSourcePB.Builder builder, String id, List<String> values) {
        List<JfkValPB> vals = new ArrayList<>();
        for (String value : values) {
            vals.add(JfkValPB.newBuilder().setStrVal(value).build());
        }
        builder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(vals)
            .build());
    }

    private void padWithBlank(List<String> values, int targetSize) {
        while (values.size() < targetSize) {
            values.add(" ");
        }
    }

    private List<String> addPagePadding(List<String> values) {
        List<String> output = new ArrayList<>();
        if (values.isEmpty()) {
            output.add(" ");
            output.add(" ");
            output.add(" ");
            return output;
        }
        for (int i = 0; i < values.size(); i += 22) {
            output.add(" ");
            output.add(" ");
            output.add(" ");
            int end = Math.min(i + 22, values.size());
            output.addAll(values.subList(i, end));
        }
        return output;
    }

    private String buildPatientHeader(long pid, LocalDateTime shiftStartTime) {
        if (pid <= 0) return "x床 xx";

        PatientRecord patient = patientService.getPatientRecord(pid);
        if (patient == null) return "x床 xx";

        String name = patient.getIcuName();
        if (StrUtils.isBlank(name)) name = "xx";

        String bedNumber = getBedNumber(patient, shiftStartTime);
        if (StrUtils.isBlank(bedNumber)) bedNumber = "x";

        return bedNumber + "床 " + name;
    }

    private String getBedNumber(PatientRecord patient, LocalDateTime shiftStartTime) {
        PatientDeviceService.UsageHistory<PatientDeviceService.BedName> bedHistory =
            patientDevService.getBedHistory(patient);
        String bedNumber = null;
        for (Pair<PatientDeviceService.BedName, LocalDateTime> entry : bedHistory.usageRecords) {
            PatientDeviceService.BedName bedName = entry.getFirst();
            LocalDateTime recordTime = entry.getSecond();
            if (bedNumber == null) {
                bedNumber = bedName.displayBedNumber;
            } else if (!recordTime.isAfter(shiftStartTime)) {
                bedNumber = bedName.displayBedNumber;
            } else {
                break;
            }
        }
        return bedNumber == null ? "" : bedNumber;
    }

    private String buildLine1(WardPatientCountPB count) {
        if (count == null) return "";
        return String.format("总数%d人 入院%d人 转入%d人 出院%d人 转出%d人",
            count.getTotalPatientCount(),
            count.getAdmissionCount(),
            count.getTransferInCount(),
            count.getDischargeCount(),
            count.getTransferOutCount());
    }

    private String buildLine2(WardPatientCountPB count) {
        if (count == null) return "";
        return String.format("手术%d人 生产%d人 病危%d人 死亡%d人 病重%d人",
            count.getSurgeryCount(),
            count.getDeliveryCount(),
            count.getCriticalPatientCount(),
            count.getDeathCount(),
            count.getSeverePatientCount());
    }

    private String getSignature(long accountId) {
        if (accountId <= 0) return "";
        return accountRepo.findByIdAndIsDeletedFalse(accountId)
            .map(Account::getSignPic)
            .orElse("");
    }

    private String getSignatureByAutoId(String accountAutoId) {
        if (StrUtils.isBlank(accountAutoId)) return "";
        try {
            long accountId = Long.parseLong(accountAutoId);
            return getSignature(accountId);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private void fillWardReportEmpty(JfkDataSourcePB.Builder outputBuilder) {
        addStrOutput(outputBuilder, "dept_name", " ");
        addStrOutput(outputBuilder, "report_date", " ");
        addStrOutput(outputBuilder, "day_shift_line1", "总数 人 入院 人 转入 人 出院 人 转出 人");
        addStrOutput(outputBuilder, "day_shift_line2", "手术 人 生产 人 病危 人 死亡 人 病重 人");
        addStrOutput(outputBuilder, "evening_shift_line1", "总数 人 入院 人 转入 人 出院 人 转出 人");
        addStrOutput(outputBuilder, "evening_shift_line2", "手术 人 生产 人 病危 人 死亡 人 病重 人");
        addStrOutput(outputBuilder, "night_shift_line1", "总数 人 入院 人 转入 人 出院 人 转出 人");
        addStrOutput(outputBuilder, "night_shift_line2", "手术 人 生产 人 病危 人 死亡 人 病重 人");
        addStrOutput(outputBuilder, "day_shift_outgoing_handover_signature", " ");
        addStrOutput(outputBuilder, "day_shift_incoming_handover_signature", " ");
        addStrOutput(outputBuilder, "evening_shift_outgoing_handover_signature", " ");
        addStrOutput(outputBuilder, "evening_shift_incoming_handover_signature", " ");
        addStrOutput(outputBuilder, "night_shift_outgoing_handover_signature", " ");
        addStrOutput(outputBuilder, "night_shift_incoming_handover_signature", " ");
    }

    private void fillWardReportPatientsEmpty(JfkDataSourcePB.Builder outputBuilder) {
        List<String> empty = addPagePadding(new ArrayList<>());
        addArrayOutput(outputBuilder, "diagnosis", empty);
        addArrayOutput(outputBuilder, "day_shift_notes", empty);
        addArrayOutput(outputBuilder, "evening_shift_notes", empty);
        addArrayOutput(outputBuilder, "night_shift_notes", empty);
    }

    public Pair<ReturnCode, JfkDataSourcePB> getRassDataSource(JfkDataSourcePB input) {
        Long pid = null;
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldData.getId().equals("pid")) {
                pid = fieldData.getVal().getInt64Val();
            }
        }

        if (pid == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.JFK_MISSING_REQUIRED_FIELD, "pid"), null);
        }
        log.info("### RASS Data Source: pid={}", pid);

        PatientRecord patient = patientService.getPatientRecord(pid);
        if (patient == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.PATIENT_NOT_FOUND, "pid: " + pid), null);
        }
        String deptId = patient.getDeptId();

        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, "rass")
            .orElse(null);
        if (deptScoreGroup == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.DEPT_SCORE_GROUP_NOT_FOUND, "deptId: " + deptId), null);
        }

        ScoreGroupMetaPB scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        if (scoreGroupMeta == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.INTERNAL_EXCEPTION), null);
        }

        JfkDataSourcePB.Builder outputBuilder = JfkDataSourcePB.newBuilder()
            .setId(input.getId())
            .setMetaId(input.getMetaId())
            .addAllInputData(input.getInputDataList());

        List<JfkValPB> scoreDateTimeList = new ArrayList<>();
        List<JfkValPB> scoreValueList = new ArrayList<>();
        List<JfkValPB> signatureList = new ArrayList<>();

        List<PatientScore> patientScores = patientScoreRepo.findByPidAndScoreGroupCodeAndIsDeletedFalse(pid, "rass")
            .stream()
            .sorted(Comparator.comparing(PatientScore::getEffectiveTime))
            .toList();
        for (PatientScore record : patientScores) {
            ScoreGroupPB scoreGroupPb = ProtoUtils.decodeScoreGroupPB(record.getScore());
            if (scoreGroupPb == null) continue;
            scoreDateTimeList.add(JfkValPB.newBuilder()
                .setStrVal(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                .build());
            scoreValueList.add(JfkValPB.newBuilder()
                .setStrVal(String.valueOf(scoreGroupPb.getGroupScore().getInt32Val()))
                .build());
            signatureList.add(JfkValPB.newBuilder()
                .setStrVal(getSignatureByAutoId(record.getModifiedBy()))
                .build());
        }

        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score_datetime")
            .addAllVals(scoreDateTimeList)
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score")
            .addAllVals(scoreValueList)
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("signature")
            .addAllVals(signatureList)
            .build());

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
    }

    public Pair<ReturnCode, JfkDataSourcePB> getCpotDataSource(JfkDataSourcePB input) {
        Long pid = null;
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldData.getId().equals("pid")) {
                pid = fieldData.getVal().getInt64Val();
            }
        }

        if (pid == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.JFK_MISSING_REQUIRED_FIELD, "pid"), null);
        }
        log.info("### CPOT Data Source: pid={}", pid);

        PatientRecord patient = patientService.getPatientRecord(pid);
        if (patient == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.PATIENT_NOT_FOUND, "pid: " + pid), null);
        }
        String deptId = patient.getDeptId();

        DeptScoreGroup deptScoreGroup = deptScoreGroupRepo
            .findByDeptIdAndScoreGroupCodeAndIsDeletedFalse(deptId, "cpot")
            .orElse(null);
        if (deptScoreGroup == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.DEPT_SCORE_GROUP_NOT_FOUND, "deptId: " + deptId), null);
        }

        ScoreGroupMetaPB scoreGroupMeta = ProtoUtils.decodeScoreGroupMetaPB(deptScoreGroup.getScoreGroupMeta());
        if (scoreGroupMeta == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.INTERNAL_EXCEPTION), null);
        }

        Map<String, ScoreItemMetaPB> itemMetaMap = new HashMap<>();
        Map<String, Map<String, Integer>> itemOptionScoreMap = new HashMap<>();
        for (ScoreItemMetaPB itemMeta : scoreGroupMeta.getItemList()) {
            itemMetaMap.put(itemMeta.getCode(), itemMeta);
            Map<String, Integer> optionScoreMap = new HashMap<>();
            for (ScoreOptionPB option : itemMeta.getOptionList()) {
                optionScoreMap.put(option.getCode(), option.getScore().getInt32Val());
            }
            itemOptionScoreMap.put(itemMeta.getCode(), optionScoreMap);
        }

        JfkDataSourcePB.Builder outputBuilder = JfkDataSourcePB.newBuilder()
            .setId(input.getId())
            .setMetaId(input.getMetaId())
            .addAllInputData(input.getInputDataList());

        List<JfkValPB> scoreDateTimeList = new ArrayList<>();
        Map<String, List<JfkValPB>> itemScoreLists = new HashMap<>();
        for (String itemCode : itemMetaMap.keySet()) {
            itemScoreLists.put(itemCode, new ArrayList<>());
        }
        List<JfkValPB> scoreValueList = new ArrayList<>();
        List<JfkValPB> notesList = new ArrayList<>();
        List<JfkValPB> signatureList = new ArrayList<>();

        List<PatientScore> patientScores = patientScoreRepo.findByPidAndScoreGroupCodeAndIsDeletedFalse(pid, "cpot")
            .stream()
            .sorted(Comparator.comparing(PatientScore::getEffectiveTime))
            .toList();

        for (PatientScore record : patientScores) {
            ScoreGroupPB scoreGroupPb = ProtoUtils.decodeScoreGroupPB(record.getScore());
            if (scoreGroupPb == null) continue;

            scoreDateTimeList.add(JfkValPB.newBuilder()
                .setStrVal(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                .build());
            scoreValueList.add(JfkValPB.newBuilder()
                .setStrVal(String.valueOf(scoreGroupPb.getGroupScore().getInt32Val()))
                .build());
            notesList.add(JfkValPB.newBuilder()
                .setStrVal(record.getNote() == null ? "" : record.getNote())
                .build());
            signatureList.add(JfkValPB.newBuilder()
                .setStrVal(getSignatureByAutoId(record.getModifiedBy()))
                .build());

            Map<String, Integer> itemScoreSum = new HashMap<>();
            for (ScoreItemPB itemPb : scoreGroupPb.getItemList()) {
                String code = itemPb.getCode();
                Map<String, Integer> optionScoreMap = itemOptionScoreMap.get(code);
                if (optionScoreMap == null) continue;
                int sum = 0;
                boolean filled = false;
                for (String optionCode : itemPb.getScoreOptionCodeList()) {
                    Integer score = optionScoreMap.get(optionCode);
                    if (score != null) {
                        sum += score;
                        filled = true;
                    }
                }
                if (filled) {
                    itemScoreSum.put(code, sum);
                }
            }

            for (Map.Entry<String, ScoreItemMetaPB> entry : itemMetaMap.entrySet()) {
                String itemCode = entry.getKey();
                Integer score = itemScoreSum.get(itemCode);
                String scoreStr = score == null ? "" : String.valueOf(score);
                itemScoreLists.get(itemCode).add(JfkValPB.newBuilder()
                    .setStrVal(scoreStr)
                    .build());
            }
        }

        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score_datetime")
            .addAllVals(scoreDateTimeList)
            .build());
        for (Map.Entry<String, ScoreItemMetaPB> entry : itemMetaMap.entrySet()) {
            String itemCode = entry.getKey();
            outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
                .setId(itemCode)
                .addAllVals(itemScoreLists.get(itemCode))
                .build());
        }
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score")
            .addAllVals(scoreValueList)
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("notes")
            .addAllVals(notesList)
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("signature")
            .addAllVals(signatureList)
            .build());

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
    }

    /**
     * 测试评分数据集1: for demo purpose
     */
    public Pair<ReturnCode, JfkDataSourcePB> getTestDataSource1(JfkDataSourcePB input) {
        Long pid = null;
        LocalDateTime queryStartUtc = null;
        LocalDateTime queryEndUtc = null;
        for (JfkFieldDataPB fieldData : input.getInputDataList()) {
            if (fieldData.getId().equals("pid")) {
                pid = fieldData.getVal().getInt64Val();
            }
            if (fieldData.getId().equals("query_start")) {
                String timeIso8601 = fieldData.getVal().getStrVal();
                queryStartUtc = TimeUtils.fromIso8601String(timeIso8601, "UTC");
            }
            if (fieldData.getId().equals("query_end")) {
                String timeIso8601 = fieldData.getVal().getStrVal();
                queryEndUtc = TimeUtils.fromIso8601String(timeIso8601, "UTC");
            }
        }

        if (pid == null || queryStartUtc == null || queryEndUtc == null) {
            String missingFields = "";
            if (pid == null) missingFields += "pid ";
            if (queryStartUtc == null) missingFields += "query_start ";
            if (queryEndUtc == null) missingFields += "query_end ";
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.JFK_MISSING_REQUIRED_FIELD, missingFields), null);
        }
        log.info("### Test Data Source 1: pid={}, queryStartUtc={}, queryEndUtc={}",
            pid, queryStartUtc, queryEndUtc);

        // 查找病人信息
        PatientRecord patient = patientService.getPatientRecord(pid);
        if (patient == null) {
            return new Pair<>(ReturnCodeUtils.getReturnCode(
                statusMsgList, StatusCode.PATIENT_NOT_FOUND, "pid: " + pid), null);
        }

        // 构造106行数据，模拟评分数据
        // 日期从 2025-01-01 00:00 开始，每行间隔1小时
        // 分数从1到106递增: 1分, 2分, ..., 106分
        JfkDataSourcePB.Builder outputBuilder = JfkDataSourcePB.newBuilder()
            .setId(input.getId())
            .setMetaId(input.getMetaId())
            .addAllInputData(input.getInputDataList());

        List<JfkValPB> scoreDateTimeList = new ArrayList<>();
        List<JfkValPB> scoreValueList = new ArrayList<>();

        LocalDateTime recordTime = LocalDateTime.of(2025, 1, 1, 0, 0);
        for (int i = 1; i <= 106; i++) {
            scoreDateTimeList.add(JfkValPB.newBuilder().setStrVal(TimeUtils.toIso8601String(recordTime, "UTC")).build());
            scoreValueList.add(JfkValPB.newBuilder().setStrVal(String.valueOf(i) + "分").build());
            recordTime = recordTime.plusHours(1);
        }

        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score_datetime")
            .addAllVals(scoreDateTimeList)
            .build());
        outputBuilder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId("score")
            .addAllVals(scoreValueList)
            .build());

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), outputBuilder.build());
    }

    private final ConfigProtoService protoService;
    private final PatientService patientService;
    private final PatientDeviceService patientDevService;
    private final ConfigShiftUtils shiftUtils;
    private final RbacDepartmentRepository deptRepo;
    private final WardReportRepository wardReportRepo;
    private final AccountRepository accountRepo;
    private final DeptScoreGroupRepository deptScoreGroupRepo;
    private final PatientScoreRepository patientScoreRepo;

    private final String ZONE_ID;
    private final List<String> statusMsgList;
    private final byte[] wardReportFontDataBytes;
}
