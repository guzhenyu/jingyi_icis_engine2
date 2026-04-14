package com.jingyicare.jingyi_icis_engine.service.reports.jfkdatasources;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.WardPatientCountPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourceMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.ShiftSettingsPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.repository.reports.WardReportRepository;
import com.jingyicare.jingyi_icis_engine.repository.scores.DeptScoreGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.scores.PatientScoreRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientDeviceService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientService;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.shifts.ConfigShiftUtils;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
@Slf4j
public class JfkDataSourceSupport {
    public JfkDataSourceSupport(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientService patientService,
        @Autowired PatientDeviceService patientDevService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired WardReportRepository wardReportRepo,
        @Autowired AccountRepository accountRepo,
        @Autowired DeptScoreGroupRepository deptScoreGroupRepo,
        @Autowired PatientScoreRepository patientScoreRepo,
        @Autowired ReportProperties reportProperties,
        @Autowired ResourceLoader resourceLoader
    ) {
        this.patientService = patientService;
        this.patientDevService = patientDevService;
        this.shiftUtils = shiftUtils;
        this.deptRepo = deptRepo;
        this.wardReportRepo = wardReportRepo;
        this.accountRepo = accountRepo;
        this.deptScoreGroupRepo = deptScoreGroupRepo;
        this.patientScoreRepo = patientScoreRepo;

        this.zoneId = protoService.getConfig().getZoneId();
        this.statusMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.jfkDataSourceMetaMap = protoService.getConfig().getJfk().getDataSourcesList().stream()
            .collect(Collectors.toMap(
                JfkDataSourceMetaPB::getId,
                meta -> meta,
                (left, right) -> left,
                LinkedHashMap::new
            ));

        byte[] fontBytes = null;
        Resource wardReportFontResource = resourceLoader.getResource(reportProperties.getAh2().getWardReportFont());
        try (InputStream is = wardReportFontResource.getInputStream()) {
            fontBytes = is.readAllBytes();
        } catch (IOException e) {
            log.error("Failed to load ward report font resource: {}", e.getMessage());
        }
        this.wardReportFontDataBytes = fontBytes;
    }

    public JfkDataSourcePB.Builder newOutputBuilder(JfkDataSourcePB input) {
        return JfkDataSourcePB.newBuilder()
            .setId(input.getId())
            .setMetaId(input.getMetaId())
            .addAllInputData(input.getInputDataList());
    }

    public void addStrOutput(JfkDataSourcePB.Builder builder, String id, String value) {
        builder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setStrVal(value == null ? "" : value).build())
            .build());
    }

    public void addArrayOutput(JfkDataSourcePB.Builder builder, String id, List<String> values) {
        List<JfkValPB> vals = new ArrayList<>();
        for (String value : values) {
            vals.add(JfkValPB.newBuilder().setStrVal(value).build());
        }
        builder.addOutputData(JfkFieldDataPB.newBuilder()
            .setId(id)
            .addAllVals(vals)
            .build());
    }

    public void padWithBlank(List<String> values, int targetSize) {
        while (values.size() < targetSize) {
            values.add(" ");
        }
    }

    public List<String> addPagePadding(List<String> values) {
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

    public String buildPatientHeader(long pid, LocalDateTime shiftStartTime) {
        if (pid <= 0) return "x床 xx";

        PatientRecord patient = patientService.getPatientRecord(pid);
        if (patient == null) return "x床 xx";

        String name = patient.getIcuName();
        if (StrUtils.isBlank(name)) name = "xx";

        String bedNumber = getBedNumber(patient, shiftStartTime);
        if (StrUtils.isBlank(bedNumber)) bedNumber = "x";

        return bedNumber + "床 " + name;
    }

    public String buildLine1(WardPatientCountPB count) {
        if (count == null) return "";
        return String.format("总数%d人 入院%d人 转入%d人 出院%d人 转出%d人",
            count.getTotalPatientCount(),
            count.getAdmissionCount(),
            count.getTransferInCount(),
            count.getDischargeCount(),
            count.getTransferOutCount());
    }

    public String buildLine2(WardPatientCountPB count) {
        if (count == null) return "";
        return String.format("手术%d人 生产%d人 病危%d人 死亡%d人 病重%d人",
            count.getSurgeryCount(),
            count.getDeliveryCount(),
            count.getCriticalPatientCount(),
            count.getDeathCount(),
            count.getSeverePatientCount());
    }

    public String getSignature(long accountId) {
        if (accountId <= 0) return "";
        return accountRepo.findByIdAndIsDeletedFalse(accountId)
            .map(Account::getSignPic)
            .orElse("");
    }

    public String getSignatureByAutoId(String accountAutoId) {
        if (StrUtils.isBlank(accountAutoId)) return "";
        try {
            long accountId = Long.parseLong(accountAutoId);
            return getSignature(accountId);
        } catch (NumberFormatException e) {
            return "";
        }
    }

    public void fillWardReportEmpty(JfkDataSourcePB.Builder outputBuilder) {
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

    public void fillWardReportPatientsEmpty(JfkDataSourcePB.Builder outputBuilder) {
        List<String> empty = addPagePadding(new ArrayList<>());
        addArrayOutput(outputBuilder, "diagnosis", empty);
        addArrayOutput(outputBuilder, "day_shift_notes", empty);
        addArrayOutput(outputBuilder, "evening_shift_notes", empty);
        addArrayOutput(outputBuilder, "night_shift_notes", empty);
    }

    public Pair<ReturnCode, JfkPatientInfo> getJfkPatientInfo(Long pid, LocalDateTime shiftTimeStart) {
        PatientRecord patient = patientService.getPatientRecord(pid);
        if (patient == null) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(
                    statusMsgList, StatusCode.PATIENT_NOT_FOUND, "pid: " + pid),
                null
            );
        }
        String deptId = patient.getDeptId();

        ShiftSettingsPB shiftSettings = shiftUtils.getShiftByDeptId(deptId);
        shiftTimeStart = shiftUtils.getShiftStartTime(shiftSettings, shiftTimeStart, zoneId);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        LocalDateTime nowUtcShiftStart = shiftUtils.getShiftStartTime(shiftSettings, nowUtc, zoneId);
        if (shiftTimeStart.isAfter(nowUtcShiftStart)) {
            shiftTimeStart = nowUtc;
        } else {
            shiftTimeStart = shiftTimeStart.plusDays(1).minusMinutes(1);
        }

        RbacDepartment department = deptRepo.findByDeptId(deptId).orElse(null);
        if (department == null) {
            return new Pair<>(
                ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.DEPT_NOT_FOUND, "deptId: " + deptId),
                null
            );
        }

        JfkPatientInfo info = new JfkPatientInfo();
        info.deptName = department.getDeptName();
        info.bedNumber = getBedNumber(patient, shiftTimeStart);
        info.name = patient.getIcuName();
        info.gender = patient.getIcuGender() == 1 ? "男" : (patient.getIcuGender() == 0 ? "女" : "未知");

        LocalDateTime birthDate = patient.getIcuDateOfBirth();
        info.ageStr = birthDate == null ? "" : TimeUtils.getAge(birthDate) + "岁";
        info.mrn = patient.getHisMrn();
        info.diagnosis = patient.getDiagnosis() == null ? "" : patient.getDiagnosis();

        return new Pair<>(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK), info);
    }

    public Optional<JfkDataSourceMetaPB> getConfiguredDataSourceMeta(String metaId) {
        return Optional.ofNullable(jfkDataSourceMetaMap.get(metaId));
    }

    public PatientService getPatientService() {
        return patientService;
    }

    public RbacDepartmentRepository getDeptRepo() {
        return deptRepo;
    }

    public WardReportRepository getWardReportRepo() {
        return wardReportRepo;
    }

    public DeptScoreGroupRepository getDeptScoreGroupRepo() {
        return deptScoreGroupRepo;
    }

    public PatientScoreRepository getPatientScoreRepo() {
        return patientScoreRepo;
    }

    public byte[] getWardReportFontDataBytes() {
        return wardReportFontDataBytes;
    }

    public String getZoneId() {
        return zoneId;
    }

    public List<String> getStatusMsgList() {
        return statusMsgList;
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

    private final PatientService patientService;
    private final PatientDeviceService patientDevService;
    private final ConfigShiftUtils shiftUtils;
    private final RbacDepartmentRepository deptRepo;
    private final WardReportRepository wardReportRepo;
    private final AccountRepository accountRepo;
    private final DeptScoreGroupRepository deptScoreGroupRepo;
    private final PatientScoreRepository patientScoreRepo;

    private final String zoneId;
    private final List<String> statusMsgList;
    private final byte[] wardReportFontDataBytes;
    private final Map<String, JfkDataSourceMetaPB> jfkDataSourceMetaMap;
}
