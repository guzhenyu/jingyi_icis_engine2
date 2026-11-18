package com.jingyicare.jingyi_icis_engine.service.patients;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientBedHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.BedConfigPB;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientBedHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMapper;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMetaReader;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMetaReader.BedKey;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

@Service
@Slf4j
public class PatientReader {
    public PatientReader(
        PatientRecordRepository patientRepo,
        PatientBedHistoryRepository bedHistoryRepo,
        BedMetaReader bedMetaReader
    ) {
        this.patientRepo = patientRepo;
        this.bedHistoryRepo = bedHistoryRepo;
        this.bedMetaReader = bedMetaReader;
    }

    public Optional<PatientRecord> findById(long patientId) {
        return patientRepo.findById(patientId);
    }

    public Map<Long, PatientRecord> findByIds(Collection<Long> patientIds) {
        if (patientIds.isEmpty()) return Map.of();
        return patientRepo.findByIdIn(patientIds.stream().distinct().toList()).stream()
            .collect(Collectors.toMap(PatientRecord::getId, Function.identity()));
    }

    public Optional<BedConfig> findBed(PatientRecord patient) {
        return bedMetaReader.findActiveBedByHisNumber(
            patient.getDeptId(), patient.getHisBedNumber());
    }

    public BedConfigPB findBedConfigByHisBedNumber(String deptId, String hisBedNumber) {
        BedConfig bedConfig = bedMetaReader.findActiveBedByHisNumber(deptId, hisBedNumber)
            .orElse(null);
        if (bedConfig == null) return null;

        Map<String, List<PatientRecord>> patientMap = patientRepo
            .findByDeptIdAndAdmissionStatusIn(
                deptId, List.of(ADMISSION_STATUS_PRE_ADMISSION, ADMISSION_STATUS_ADMITTED))
            .stream()
            .collect(Collectors.groupingBy(PatientRecord::getHisBedNumber));
        List<PatientRecord> patients = patientMap.get(hisBedNumber);
        if (patients == null || patients.isEmpty()) return BedMapper.toBedConfigPB(bedConfig, null);
        if (patients.size() > 1) {
            log.error("HIS 床号 {} 对应多个患者，使用第一个患者", hisBedNumber);
        }
        return BedMapper.toBedConfigPB(bedConfig, patients.getFirst());
    }

    public Map<String, BedConfig> getBedConfigMap(String deptId) {
        return bedMetaReader.findActiveBedsByDepartment(deptId)
            .stream()
            .collect(Collectors.toMap(BedConfig::getHisBedNumber, Function.identity()));
    }

    public Pair<StatusCode, Map<Long, String>> findDisplayBedNumbers(
        Collection<PatientRecord> patients
    ) {
        List<BedKey> beds = patients.stream()
            .map(patient -> new BedKey(patient.getDeptId(), patient.getHisBedNumber()))
            .distinct().toList();
        Pair<StatusCode, Map<BedKey, String>> result = bedMetaReader.findDisplayBedNumbers(beds);
        if (result.getFirst() != StatusCode.OK) {
            return new Pair<>(result.getFirst(), Map.of());
        }
        Map<Long, String> displayBedNumbers = new HashMap<>();
        for (PatientRecord patient : patients) {
            BedKey bed = new BedKey(patient.getDeptId(), patient.getHisBedNumber());
            displayBedNumbers.put(patient.getId(),
                result.getSecond().getOrDefault(bed, patient.getHisBedNumber()));
        }
        return new Pair<>(StatusCode.OK, displayBedNumbers);
    }

    public BedHistory findBedHistory(PatientRecord patient) {
        LocalDateTime startUtc = patient.getAdmissionTime();
        if (startUtc == null) {
            log.warn("患者 {} 缺少入科时间，无法构建床位时间线", patient.getId());
            return new BedHistory(List.of(), patient.getDischargeTime());
        }

        List<PatientBedHistory> changes = bedHistoryRepo
            .findByPatientIdAndSwitchTimeAfterOrderBySwitchTimeAscIdAsc(
                patient.getId(), startUtc);
        List<BedHistoryEntry> entries = new ArrayList<>();
        for (PatientBedHistory change : changes) {
            if (!change.getSwitchTime().isAfter(startUtc)) continue;
            // 类型属于本段结束事件；重返入科对应的前一段保留为出科间隔。
            entries.add(new BedHistoryEntry(
                change.getHisBedNumber(), change.getDeviceBedNumber(),
                change.getDisplayBedNumber(), startUtc, change.getSwitchType()));
            startUtc = change.getSwitchTime();
        }

        BedConfig currentBed = findBed(patient).orElse(null);
        entries.add(new BedHistoryEntry(
            patient.getHisBedNumber(),
            currentBed == null ? "" : currentBed.getDeviceBedNumber(),
            currentBed == null ? "" : currentBed.getDisplayBedNumber(),
            startUtc, SWITCH_TYPE_NORMAL));
        return new BedHistory(List.copyOf(entries), patient.getDischargeTime());
    }

    public Map<Long, List<PatientBedHistory>> findBedChanges(Collection<Long> patientIds) {
        if (patientIds.isEmpty()) return Map.of();
        return bedHistoryRepo.findByPatientIdInOrderBySwitchTimeAscIdAsc(
                patientIds.stream().distinct().toList()).stream()
            .collect(Collectors.groupingBy(PatientBedHistory::getPatientId));
    }

    public record BedHistory(List<BedHistoryEntry> entries, LocalDateTime endUtc) {}

    public record BedHistoryEntry(
        String hisBedNumber,
        String deviceBedNumber,
        String displayBedNumber,
        LocalDateTime startUtc,
        Integer switchType
    ) {}

    private final PatientRecordRepository patientRepo;
    private final PatientBedHistoryRepository bedHistoryRepo;
    private final BedMetaReader bedMetaReader;
}
