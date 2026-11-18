package com.jingyicare.jingyi_icis_engine.service.patients;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientBedHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientBedHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader.BedHistory;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader.BedHistoryEntry;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

@Transactional
class PatientReaderTests extends TestsBase {
    @Test
    void basicReadsReturnIncompletePatientsAndAllAdmissionStatuses() {
        PatientRecord pending = savePatient("", "", ADMISSION_STATUS_PRE_ADMISSION, null);
        PatientRecord admitted = savePatient(DEPT, "HIS-A", ADMISSION_STATUS_ADMITTED, null);
        PatientRecord discharged = savePatient(
            DEPT, "HIS-B", ADMISSION_STATUS_DISCHARGED, START);

        assertThat(reader.findById(pending.getId())).contains(pending);
        assertThat(reader.findById(admitted.getId())).contains(admitted);
        assertThat(reader.findById(discharged.getId())).contains(discharged);
        assertThat(reader.findById(Long.MAX_VALUE)).isEmpty();
        assertThat(reader.findByIds(List.of(pending.getId(), pending.getId(),
            admitted.getId(), discharged.getId(), Long.MAX_VALUE)))
            .containsOnlyKeys(pending.getId(), admitted.getId(), discharged.getId());
        assertThat(reader.findByIds(List.of())).isEmpty();
    }

    @Test
    void displayBedNumbersUseDepartmentAndFallbackWithoutCheckingPatientOccupancy() {
        BedConfig firstBed = saveBed(DEPT, "HIS-A", "显示甲");
        saveBed(OTHER_DEPT, "HIS-A", "显示乙");
        // 未请求科室的重复配置不影响查询结果。
        saveBed("unrequested-patient-reader", "HIS-A", "无关甲");
        saveBed("unrequested-patient-reader", "HIS-A", "无关乙");
        PatientRecord admitted = savePatient(DEPT, "HIS-A", ADMISSION_STATUS_ADMITTED, null);
        PatientRecord pending = savePatient(DEPT, "HIS-A", ADMISSION_STATUS_PRE_ADMISSION, null);
        PatientRecord other = savePatient(OTHER_DEPT, "HIS-A", ADMISSION_STATUS_ADMITTED, null);
        PatientRecord missing = savePatient(DEPT, "HIS-MISSING", null, null);

        assertThat(reader.findBed(admitted)).contains(firstBed);
        assertThat(reader.findBed(missing)).isEmpty();
        Pair<StatusCode, Map<Long, String>> result = reader.findDisplayBedNumbers(
            List.of(admitted, pending, other, missing));

        assertThat(result.getFirst()).isEqualTo(StatusCode.OK);
        assertThat(result.getSecond()).containsExactlyInAnyOrderEntriesOf(Map.of(
            admitted.getId(), "显示甲", pending.getId(), "显示甲",
            other.getId(), "显示乙", missing.getId(), "HIS-MISSING"));
        assertThat(reader.findDisplayBedNumbers(List.of()).getSecond()).isEmpty();
    }

    @Test
    void duplicateRequestedActiveBedConfigurationsReturnExplicitError() {
        saveBed(DEPT, "DUPLICATE", "显示甲");
        saveBed(DEPT, "DUPLICATE", "显示乙");
        PatientRecord patient = savePatient(DEPT, "DUPLICATE", null, null);

        Pair<StatusCode, Map<Long, String>> result = reader.findDisplayBedNumbers(List.of(patient));

        assertThat(result.getFirst()).isEqualTo(StatusCode.INTERNAL_EXCEPTION);
        assertThat(result.getSecond()).isEmpty();
    }

    @Test
    void bedHistoryKeepsReadmissionGapAndCurrentBedWithStableTimeOrdering() {
        PatientRecord patient = savePatient(DEPT, "HIS-D", ADMISSION_STATUS_ADMITTED, START);
        patient.setDischargeTime(START.plusDays(4));
        saveBed(DEPT, "HIS-D", "当前床");
        saveChange(patient, "HIS-C", START.plusDays(3), SWITCH_TYPE_READMISSION_ADMIT);
        saveChange(patient, "HIS-B", START.plusDays(2), SWITCH_TYPE_READMISSION_DISCHARGE);
        saveChange(patient, "HIS-A", START.plusDays(1), SWITCH_TYPE_NORMAL);
        saveChange(patient, "SAME-TIME", START.plusDays(1), SWITCH_TYPE_NORMAL);
        saveChange(patient, "AT-ADMISSION", START, SWITCH_TYPE_NORMAL);
        saveChange(patient, "BEFORE-ADMISSION", START.minusDays(1), SWITCH_TYPE_NORMAL);

        BedHistory history = reader.findBedHistory(patient);

        assertThat(history.entries()).containsExactly(
            new BedHistoryEntry("HIS-A", "设备-HIS-A", "显示-HIS-A", START,
                SWITCH_TYPE_NORMAL),
            new BedHistoryEntry("HIS-B", "设备-HIS-B", "显示-HIS-B", START.plusDays(1),
                SWITCH_TYPE_READMISSION_DISCHARGE),
            new BedHistoryEntry("HIS-C", "设备-HIS-C", "显示-HIS-C", START.plusDays(2),
                SWITCH_TYPE_READMISSION_ADMIT),
            new BedHistoryEntry("HIS-D", "设备-HIS-D", "当前床", START.plusDays(3),
                SWITCH_TYPE_NORMAL));
        assertThat(history.endUtc()).isEqualTo(patient.getDischargeTime());
    }

    @Test
    void missingCurrentBedConfigurationPreservesHistoricalNamesAndBlankCurrentNames() {
        PatientRecord patient = savePatient(DEPT, "HIS-MISSING", ADMISSION_STATUS_ADMITTED, START);
        saveChange(patient, "HIS-OLD", START.plusDays(1), SWITCH_TYPE_NORMAL);

        BedHistory history = reader.findBedHistory(patient);

        assertThat(history.entries()).containsExactly(
            new BedHistoryEntry("HIS-OLD", "设备-HIS-OLD", "显示-HIS-OLD", START,
                SWITCH_TYPE_NORMAL),
            new BedHistoryEntry("HIS-MISSING", "", "", START.plusDays(1), SWITCH_TYPE_NORMAL));
        assertThat(history.endUtc()).isNull();
    }

    @Test
    void missingAdmissionTimeKeepsPatientAndRawHistoryWithoutComputableTimeline() {
        PatientRecord patient = savePatient(DEPT, "HIS-A", ADMISSION_STATUS_DISCHARGED, null);
        patient.setDischargeTime(START.plusDays(1));
        PatientBedHistory change = saveChange(patient, "HIS-A", START, SWITCH_TYPE_NORMAL);

        BedHistory history = reader.findBedHistory(patient);

        assertThat(history.entries()).isEmpty();
        assertThat(history.endUtc()).isEqualTo(patient.getDischargeTime());
        assertThat(reader.findById(patient.getId())).contains(patient);
        assertThat(reader.findBedChanges(List.of(patient.getId())).get(patient.getId()))
            .containsExactly(change);
    }

    @Test
    void batchBedChangesKeepAllRecordsSortedWithinEachPatient() {
        PatientRecord first = savePatient(DEPT, "HIS-A", ADMISSION_STATUS_ADMITTED, START);
        PatientRecord second = savePatient(DEPT, "HIS-B", ADMISSION_STATUS_ADMITTED, START);
        PatientBedHistory latest = saveChange(
            first, "HIS-A", START.plusDays(1), SWITCH_TYPE_NORMAL);
        PatientBedHistory earliest = saveChange(
            first, "HIS-A", START.minusDays(1), SWITCH_TYPE_NORMAL);
        PatientBedHistory other = saveChange(second, "HIS-B", START, SWITCH_TYPE_NORMAL);
        PatientBedHistory sameTime = saveChange(
            first, "HIS-A", START.plusDays(1), SWITCH_TYPE_NORMAL);

        Map<Long, List<PatientBedHistory>> result = reader.findBedChanges(
            List.of(first.getId(), second.getId(), first.getId(), Long.MAX_VALUE));

        assertThat(result).containsOnlyKeys(first.getId(), second.getId());
        assertThat(result.get(first.getId())).containsExactly(earliest, latest, sameTime);
        assertThat(result.get(second.getId())).containsExactly(other);
        assertThat(reader.findBedChanges(List.of())).isEmpty();
    }

    private PatientRecord savePatient(
        String deptId, String hisBedNumber, Integer status, LocalDateTime admissionTime
    ) {
        PatientRecord patient = new PatientRecord();
        patient.setDeptId(deptId);
        patient.setHisBedNumber(hisBedNumber);
        patient.setAdmissionStatus(status);
        patient.setAdmissionTime(admissionTime);
        return patientRepo.save(patient);
    }

    private BedConfig saveBed(String deptId, String hisBedNumber, String displayBedNumber) {
        return bedRepo.save(BedConfig.builder()
            .departmentId(deptId).hisBedNumber(hisBedNumber)
            .deviceBedNumber("设备-" + hisBedNumber).displayBedNumber(displayBedNumber)
            .bedType(BED_TYPE_FIXED).isDeleted(false).build());
    }

    private PatientBedHistory saveChange(
        PatientRecord patient, String hisBedNumber, LocalDateTime switchTime, int switchType
    ) {
        return bedHistoryRepo.save(PatientBedHistory.builder()
            .patientId(patient.getId()).hisBedNumber(hisBedNumber)
            .deviceBedNumber("设备-" + hisBedNumber).displayBedNumber("显示-" + hisBedNumber)
            .switchTime(switchTime).switchType(switchType).build());
    }

    private static final String DEPT = "patient-reader-tests";
    private static final String OTHER_DEPT = "patient-reader-other";
    private static final LocalDateTime START = LocalDateTime.of(2025, 8, 1, 0, 0);

    @Autowired private PatientReader reader;
    @Autowired private PatientRecordRepository patientRepo;
    @Autowired private PatientBedHistoryRepository bedHistoryRepo;
    @Autowired private BedConfigRepository bedRepo;
}
