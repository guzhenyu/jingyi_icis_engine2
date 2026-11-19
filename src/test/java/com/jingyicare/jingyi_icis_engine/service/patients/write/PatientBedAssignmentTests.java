package com.jingyicare.jingyi_icis_engine.service.patients.write;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientBedHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientBedHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;

class PatientBedAssignmentTests extends TestsBase {
    @Test
    void normalMoveRecordsOldBedAndRejectedMovesDoNotWriteHistory() {
        String deptId = "patient-bed-move";
        configureBeds(deptId);
        long pid = createPatient(
            deptId, "normal-mrn", "before", PatientEnums.ADMISSION_STATUS_ADMITTED);
        createPatient(deptId, "occupied-mrn", "occupied", PatientEnums.ADMISSION_STATUS_ADMITTED);

        assertThat(move(pid, "after", ADMISSION_TIME.plusHours(1))).isEqualTo(StatusCode.OK);
        assertThat(move(pid, "after", ADMISSION_TIME.plusHours(2))).isEqualTo(StatusCode.OK);
        assertThat(move(pid, "occupied", ADMISSION_TIME.plusHours(2)))
            .isEqualTo(StatusCode.BED_ALREADY_OCCUPIED);
        assertThat(move(pid, "other", ADMISSION_TIME))
            .isEqualTo(StatusCode.BED_SWITCH_TIME_OUTDATED);

        PatientRecord departed = patientRepo.findById(pid).orElseThrow();
        departed.setAdmissionStatus(PatientEnums.ADMISSION_STATUS_DISCHARGED);
        departed.setDischargeTime(ADMISSION_TIME.plusHours(3));
        patientRepo.save(departed);
        assertThat(move(pid, "other", ADMISSION_TIME.plusHours(4)))
            .isEqualTo(StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH);
        assertThat(patientRepo.findById(pid).orElseThrow().getHisBedNumber()).isEqualTo("after");
        assertThat(bedHistoryRepo.findByPatientId(pid))
            .extracting(PatientBedHistory::getHisBedNumber, PatientBedHistory::getSwitchTime,
                PatientBedHistory::getSwitchType)
            .containsExactly(tuple("before", ADMISSION_TIME.plusHours(1),
                PatientEnums.SWITCH_TYPE_NORMAL));
    }

    @Test
    void readmissionRecordsBreakOnlyWhenBedOwnerAndTimeMatch() {
        String deptId = "patient-bed-readmission";
        configureBeds(deptId);
        long previousPid = createPatient(
            deptId, "readmission-mrn", "before", PatientEnums.ADMISSION_STATUS_ADMITTED);
        assertThat(move(previousPid, "after", ADMISSION_TIME.plusHours(1)))
            .isEqualTo(StatusCode.OK);
        long pendingPid = createPatient(
            deptId, "different-mrn", "other", PatientEnums.ADMISSION_STATUS_PRE_ADMISSION);
        LocalDateTime dischargeTime = ADMISSION_TIME.plusHours(2);
        LocalDateTime readmissionTime = ADMISSION_TIME.plusHours(3);

        assertThat(readmitBed(previousPid, "other", readmissionTime))
            .isEqualTo(StatusCode.INVALID_ADMISSION_STATUS_FOR_BED_SWITCH);

        PatientRecord departed = patientRepo.findById(previousPid).orElseThrow();
        departed.setAdmissionStatus(PatientEnums.ADMISSION_STATUS_DISCHARGED);
        departed.setDischargeTime(dischargeTime);
        patientRepo.save(departed);
        assertThat(readmitBed(previousPid, "other", readmissionTime))
            .isEqualTo(StatusCode.BED_SWITCH_READMISSION_MRN_MISMATCH);

        PatientRecord pending = patientRepo.findById(pendingPid).orElseThrow();
        pending.setHisMrn("readmission-mrn");
        patientRepo.save(pending);
        assertThat(readmitBed(previousPid, "other", dischargeTime))
            .isEqualTo(StatusCode.BED_SWITCH_READMISSION_END_TIME_INVALID);
        assertThat(readmitBed(previousPid, "other", readmissionTime)).isEqualTo(StatusCode.OK);

        List<PatientBedHistory> history = bedHistoryRepo.findByPatientId(previousPid).stream()
            .sorted(Comparator.comparing(PatientBedHistory::getSwitchTime)).toList();
        assertThat(history)
            .extracting(PatientBedHistory::getHisBedNumber, PatientBedHistory::getSwitchTime,
                PatientBedHistory::getSwitchType)
            .containsExactly(
                tuple("before", ADMISSION_TIME.plusHours(1), PatientEnums.SWITCH_TYPE_NORMAL),
                tuple("after", dischargeTime, PatientEnums.SWITCH_TYPE_READMISSION_DISCHARGE),
                tuple("other", readmissionTime, PatientEnums.SWITCH_TYPE_READMISSION_ADMIT));
        PatientRecord updated = patientRepo.findById(previousPid).orElseThrow();
        assertThat(updated.getHisBedNumber()).isEqualTo("other");
        // 入科服务负责变更患者状态和清理出科字段。
        assertThat(updated.getAdmissionStatus())
            .isEqualTo(PatientEnums.ADMISSION_STATUS_DISCHARGED);
        assertThat(updated.getDischargeTime()).isEqualTo(dischargeTime);
    }

    private void configureBeds(String deptId) {
        for (String bed : List.of("before", "after", "occupied", "other")) {
            bedConfigRepo.save(BedConfig.builder()
                .departmentId(deptId).hisBedNumber(bed)
                .deviceBedNumber("device-" + bed).displayBedNumber("display-" + bed)
                .bedType(PatientEnums.BED_TYPE_FIXED).isDeleted(false).build());
        }
    }

    private long createPatient(String deptId, String mrn, String bed, int admissionStatus) {
        PatientRecord patient = new PatientRecord();
        patient.setHisMrn(mrn);
        patient.setDeptId(deptId);
        patient.setHisBedNumber(bed);
        patient.setAdmissionTime(ADMISSION_TIME);
        patient.setAdmissionStatus(admissionStatus);
        return patientRepo.save(patient).getId();
    }

    private StatusCode move(long pid, String bed, LocalDateTime switchTime) {
        return lockExecutor.execute(List.of(LockKeys.patientRecords()), () ->
            bedAssignment.moveInTransaction(bed, switchTime, "123", "123",
                patientRepo.findById(pid).orElseThrow()));
    }

    private StatusCode readmitBed(long pid, String bed, LocalDateTime switchTime) {
        return lockExecutor.execute(List.of(LockKeys.patientRecords()), () ->
            bedAssignment.recordReadmissionInTransaction(bed, switchTime, ACCOUNT,
                patientRepo.findById(pid).orElseThrow()));
    }

    private static final LocalDateTime ADMISSION_TIME = LocalDateTime.of(2025, 8, 15, 8, 0);
    private static final CurrentAccount ACCOUNT = new CurrentAccount("user", "123", "123");

    @Autowired private PatientBedAssignment bedAssignment;
    @Autowired private LockExecutor lockExecutor;
    @Autowired private BedConfigRepository bedConfigRepo;
    @Autowired private PatientBedHistoryRepository bedHistoryRepo;
    @Autowired private PatientRecordRepository patientRepo;
}
