package com.jingyicare.jingyi_icis_engine.service.patients.write;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AddDiagnosisHistoryReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DiagnosisHistoryPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.UpdateDiagnosisHistoryReq;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
public class PatientDiagnosisHistoryService {
    public PatientDiagnosisHistoryService(
        LockExecutor lockExecutor, PatientRecordRepository patientRepo,
        DiagnosisHistoryRepository diagnosisRepo, ProtoConfig protoConfig
    ) {
        this.lockExecutor = lockExecutor;
        this.patientRepo = patientRepo;
        this.diagnosisRepo = diagnosisRepo;
        this.zoneId = protoConfig.getConfig().getZoneId();
    }

    public DiagnosisHistoryResult getAndRefresh(long pid) {
        return lockExecutor.execute(List.of(LockKeys.patientRecords()), () -> {
            PatientRecord patient = patientRepo.findById(pid).orElse(null);
            if (patient == null) {
                return new DiagnosisHistoryResult(StatusCode.PATIENT_NOT_FOUND, List.of());
            }

            List<DiagnosisHistory> histories = diagnosisRepo.findByPatientIdAndIsDeletedFalse(pid)
                .stream()
                .filter(history -> !StrUtils.isBlank(history.getDiagnosis())
                    || !StrUtils.isBlank(history.getDiagnosisCode()))
                .sorted(NEWEST_FIRST)
                .toList();
            DiagnosisHistory latest = histories.stream()
                .filter(history -> !StrUtils.isBlank(history.getDiagnosis()))
                .findFirst()
                .orElse(null);
            refreshDiagnosis(latest, patient);

            List<DiagnosisHistoryPB> result = histories.stream()
                .map(history -> PatientRecordMapper.toDiagnosisHistoryPB(history, zoneId))
                .toList();
            return new DiagnosisHistoryResult(StatusCode.OK, result);
        });
    }

    public AddDiagnosisResult add(AddDiagnosisHistoryReq req, CurrentAccount account) {
        return lockExecutor.execute(List.of(LockKeys.patientRecords()), () -> {
            long pid = req.getPid();
            PatientRecord patient = patientRepo.findById(pid).orElse(null);
            if (patient == null) return new AddDiagnosisResult(StatusCode.PATIENT_NOT_FOUND, 0);

            DiagnosisHistoryPB input = req.getDiagnosisHistory();
            LocalDateTime time =
                TimeUtils.fromIso8601String(input.getDiagnosisTimeIso8601(), "UTC");
            if (time == null) return new AddDiagnosisResult(StatusCode.INVALID_TIME_FORMAT, 0);
            if (account == null) return new AddDiagnosisResult(StatusCode.ACCOUNT_NOT_FOUND, 0);

            DiagnosisHistory latest = findLatestDiagnosis(pid, null);
            // 新增记录的 ID 最大，同一时间按新记录计算。
            if (!StrUtils.isBlank(input.getDiagnosis())
                && (latest == null || !latest.getDiagnosisTime().isAfter(time))) {
                saveDiagnosisIfChanged(input.getDiagnosis(), patient);
            }

            DiagnosisHistory history = diagnosisRepo.save(DiagnosisHistory.builder()
                .patientId(pid)
                .diagnosisTime(time)
                .diagnosisAccountId(account.autoAcctId())
                .diagnosis(input.getDiagnosis())
                .diagnosisCode(input.getDiagnosisCode())
                .isDeleted(false)
                .modifiedAt(TimeUtils.getNowUtc())
                .modifiedBy(account.autoAcctId())
                .modifiedByAccountName(account.acctName())
                .build());
            return new AddDiagnosisResult(StatusCode.OK, history.getId());
        });
    }

    public StatusCode update(UpdateDiagnosisHistoryReq req, CurrentAccount account) {
        if (account == null) return StatusCode.ACCOUNT_NOT_FOUND;
        DiagnosisHistoryPB input = req.getDiagnosisHistory();
        LocalDateTime time = TimeUtils.fromIso8601String(input.getDiagnosisTimeIso8601(), "UTC");
        if (time == null) return StatusCode.INVALID_TIME_FORMAT;

        return lockExecutor.execute(List.of(LockKeys.patientRecords()), () -> {
            DiagnosisHistory history = diagnosisRepo.findById(req.getId()).orElse(null);
            if (history == null) return StatusCode.DIAGNOSIS_HISTORY_NOT_FOUND;

            Long pid = history.getPatientId();
            PatientRecord patient = pid == null ? null : patientRepo.findById(pid).orElse(null);
            if (patient == null) return StatusCode.PATIENT_NOT_FOUND;

            DiagnosisHistory latest = findLatestDiagnosis(pid, history.getId());
            String summary = latest == null ? "" : latest.getDiagnosis();
            boolean updatedDiagnosisIsLatest = latest == null
                || time.isAfter(latest.getDiagnosisTime())
                || (time.equals(latest.getDiagnosisTime()) && history.getId() > latest.getId());
            if (updatedDiagnosisIsLatest && !StrUtils.isBlank(input.getDiagnosis())) {
                summary = input.getDiagnosis();
            }
            saveDiagnosisIfChanged(summary, patient);

            history.setDiagnosisTime(time);
            history.setDiagnosisAccountId(account.autoAcctId());
            history.setDiagnosis(input.getDiagnosis());
            history.setDiagnosisCode(input.getDiagnosisCode());
            history.setModifiedAt(TimeUtils.getNowUtc());
            history.setModifiedBy(account.autoAcctId());
            history.setModifiedByAccountName(account.acctName());
            diagnosisRepo.save(history);
            return StatusCode.OK;
        });
    }

    public StatusCode delete(int id, CurrentAccount account) {
        if (account == null) return StatusCode.ACCOUNT_NOT_FOUND;

        return lockExecutor.execute(List.of(LockKeys.patientRecords()), () -> {
            DiagnosisHistory history = diagnosisRepo.findById(id).orElse(null);
            if (history == null) return StatusCode.OK;

            Long pid = history.getPatientId();
            PatientRecord patient = pid == null ? null : patientRepo.findById(pid).orElse(null);
            if (patient == null) return StatusCode.PATIENT_NOT_FOUND;

            DiagnosisHistory latest = findLatestDiagnosis(pid, history.getId());
            history.setIsDeleted(true);
            history.setDeletedAt(TimeUtils.getNowUtc());
            history.setDeletedBy(account.autoAcctId());
            diagnosisRepo.save(history);
            refreshDiagnosis(latest, patient);
            return StatusCode.OK;
        });
    }

    private DiagnosisHistory findLatestDiagnosis(long pid, Integer excludedId) {
        return diagnosisRepo.findByPatientIdAndIsDeletedFalse(pid).stream()
            .filter(history -> !Objects.equals(history.getId(), excludedId))
            .filter(history -> !StrUtils.isBlank(history.getDiagnosis()))
            .min(NEWEST_FIRST)
            .orElse(null);
    }

    private void refreshDiagnosis(DiagnosisHistory latest, /* OUT */ PatientRecord patient) {
        if (latest == null && StrUtils.isBlank(patient.getDiagnosis())) return;
        saveDiagnosisIfChanged(latest == null ? "" : latest.getDiagnosis(), patient);
    }

    private void saveDiagnosisIfChanged(String diagnosis, /* OUT */ PatientRecord patient) {
        if (!diagnosis.equals(patient.getDiagnosis())) {
            patient.setDiagnosis(diagnosis);
            patientRepo.save(patient);
        }
    }

    public record DiagnosisHistoryResult(StatusCode status, List<DiagnosisHistoryPB> history) {}

    public record AddDiagnosisResult(StatusCode status, int id) {}

    private static final Comparator<DiagnosisHistory> NEWEST_FIRST =
        Comparator.comparing(DiagnosisHistory::getDiagnosisTime)
            .reversed()
            .thenComparing(DiagnosisHistory::getId, Comparator.reverseOrder());

    private final LockExecutor lockExecutor;
    private final PatientRecordRepository patientRepo;
    private final DiagnosisHistoryRepository diagnosisRepo;
    private final String zoneId;
}
