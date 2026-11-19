package com.jingyicare.jingyi_icis_engine.service.patients.write;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.ReadmissionHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.AdmitPatientReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.NewPatientReq;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientDeviceRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordJdbc;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.ReadmissionHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.SurgeryHistoryRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMetaReader;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class PatientAdmissionService {
    public PatientAdmissionService(
        LockExecutor lockExecutor, JdbcTemplate jdbcTemplate, PatientRecordRepository patientRepo,
        BedMetaReader bedMetaReader, PatientRecordMapper mapper, PatientBedAssignment bedAssignment,
        PatientSyncService syncService, HisPatientRecordRepository hisPatientRepo,
        ReadmissionHistoryRepository readmissionHistoryRepo,
        DiagnosisHistoryRepository diagnosisRepo, SurgeryHistoryRepository surgeryRepo,
        PatientDeviceRepository patientDeviceRepo
    ) {
        this.lockExecutor = lockExecutor;
        this.jdbcTemplate = jdbcTemplate;
        this.patientRepo = patientRepo;
        this.bedMetaReader = bedMetaReader;
        this.mapper = mapper;
        this.bedAssignment = bedAssignment;
        this.syncService = syncService;
        this.hisPatientRepo = hisPatientRepo;
        this.readmissionHistoryRepo = readmissionHistoryRepo;
        this.diagnosisRepo = diagnosisRepo;
        this.surgeryRepo = surgeryRepo;
        this.patientDeviceRepo = patientDeviceRepo;
    }

    public StatusCode createManual(NewPatientReq req, CurrentAccount account) {
        return lockExecutor.execute(List.of(LockKeys.patientRecords()), () -> {
            if (!patientRepo.findByMrnAndAdmissionStatusNotEquals(
                req.getDeptId(), req.getHisMrn(), ADMISSION_STATUS_DISCHARGED).isEmpty()) {
                return StatusCode.PATIENT_ALREADY_EXISTS;
            }

            BedConfig bed = bedMetaReader.findActiveBedByHisNumber(
                req.getDeptId(), req.getBedNumberStr()).orElse(null);
            if (bed == null) {
                log.error("未找到有效床位配置，科室={}，HIS 床号={}", req.getDeptId(), req.getBedNumberStr());
                return StatusCode.BED_CONFIG_NOT_EXISTS;
            }

            PatientRecord patient = mapper.toPatientRecordEntity(req, bed.getHisBedNumber());
            patient.setIcuManualEntry(true);
            patient.setIcuManualEntryAccountId(account.autoAcctId());
            patient.setAdmissionStatus(ADMISSION_STATUS_ADMITTED);
            patient.setCreatedAt(LocalDateTime.now());
            patientRepo.save(patient);
            return StatusCode.OK;
        });
    }

    public StatusCode admitPending(AdmitPatientReq req, CurrentAccount account) {
        if (!req.getToMerge()) {
            return lockExecutor.execute(List.of(LockKeys.patientRecords()),
                () -> admitInTransaction(req, account, null));
        }

        Supplier<List<Long>> load = () -> PatientRecordJdbc.findReadmissionCandidates(
            jdbcTemplate, req.getDeptId(), req.getHisMrn(), ADMISSION_STATUS_PRE_ADMISSION,
            req.getLastPid());
        try {
            return lockExecutor.executePlanned(load,
                pendingIds -> pendingIds.size() == 1
                    ? List.of(LockKeys.patientRecords(),
                        LockKeys.patientDeviceBindings(pendingIds.getFirst()))
                    : List.of(LockKeys.patientRecords()),
                pendingIds -> pendingIds.equals(load.get()),
                pendingIds -> admitInTransaction(req, account,
                    pendingIds.size() == 1 ? pendingIds.getFirst() : null));
        } catch (ReadmissionRejected e) {
            return e.code;
        }
    }

    private StatusCode admitInTransaction(
        AdmitPatientReq req, CurrentAccount account, Long pendingPidWithBindingLock
    ) {
        List<PatientRecord> patients = patientRepo.findByMrnAndAdmissionStatus(
            req.getDeptId(), req.getHisMrn(), ADMISSION_STATUS_PRE_ADMISSION);
        if (patients.isEmpty()) return StatusCode.PATIENT_NOT_FOUND;
        if (patients.size() > 1) {
            log.error("同一住院号有多条待入科记录，科室={}，住院号={}", req.getDeptId(), req.getHisMrn());
            return StatusCode.INTERNAL_EXCEPTION;
        }
        PatientRecord pending = patients.getFirst();
        String deptId = pending.getDeptId();
        if (!bedMetaReader.checkBedAvailable(deptId)) {
            return StatusCode.BED_CONFIG_BED_NUMBER_LIMIT_EXCEEDED;
        }
        if (bedMetaReader.findActiveBedByHisNumber(deptId, pending.getHisBedNumber()).isEmpty()) {
            log.error("未找到有效床位配置，科室={}，HIS 床号={}", deptId, pending.getHisBedNumber());
            return StatusCode.BED_CONFIG_NOT_EXISTS;
        }

        LocalDateTime admissionTime = TimeUtils.fromIso8601String(req.getAdmissionTime(), "UTC");
        if (req.getToMerge()) {
            PatientRecord previous = patientRepo.findById(req.getLastPid()).orElse(null);
            if (previous != null && previous.getHisMrn().equals(pending.getHisMrn())) {
                if (!Objects.equals(pending.getId(), pendingPidWithBindingLock)) {
                    log.warn("待入科记录变化，未取得设备绑定锁，患者={}", pending.getId());
                    return StatusCode.READMISSION_PATIENT_INCONSISTENT;
                }
                return readmitInTransaction(req, account, previous, pending, admissionTime);
            }
        }

        pending.setDeptId(req.getDeptId());
        pending.setAdmissionTime(admissionTime);
        PatientRecordMapper.setAdmissionTypeValues(req.getAdmissionTypeList(), pending);
        pending.setFromDeptName(req.getFromDeptName());
        pending.setPrimaryCareDoctorId(req.getPrimaryCareDoctorId());
        pending.setIsPlannedAdmission(req.getIsPlannedAdmission() > 0);
        pending.setUnplannedAdmissionReason(req.getUnplannedAdmissionReason());
        pending.setAdmissionStatus(ADMISSION_STATUS_ADMITTED);
        patientRepo.save(pending);
        return StatusCode.OK;
    }

    private StatusCode readmitInTransaction(
        AdmitPatientReq req, CurrentAccount account, PatientRecord previous,
        PatientRecord pending, LocalDateTime admissionTime
    ) {
        // 已结束或软删除的绑定同样保留外键引用。
        if (patientDeviceRepo.existsByPatientId(pending.getId())) {
            log.warn("待入科病例已有设备绑定记录，无法重返合并，患者={}", pending.getId());
            return StatusCode.READMISSION_PATIENT_INCONSISTENT;
        }
        HisPatientRecord source = hisPatientRepo.findByPidAndAdmissionStatus(
            pending.getHisPatientId(), ADMISSION_STATUS_ADMITTED).orElse(null);
        if (source == null) {
            log.warn("重返合并缺少 HIS 在科记录，患者={}", pending.getId());
            return StatusCode.READMISSION_PATIENT_NOT_FOUND;
        }

        ReadmissionHistory history = ReadmissionHistory.builder()
            .patientId(previous.getId())
            .readmissionReason(req.getUnplannedAdmissionReason())
            .icuDischargeTime(previous.getDischargeTime())
            .icuDischargeEditTime(previous.getDischargeEditTime())
            .icuDischargingAccountId(previous.getDischargeAccountId())
            .icuAdmissionTime(admissionTime)
            .icuAdmissionEditTime(TimeUtils.getNowUtc())
            .icuAdmittingAccountId(account.autoAcctId())
            .build();

        StatusCode bedStatus = bedAssignment.recordReadmissionInTransaction(
            pending.getHisBedNumber(), pending.getAdmissionTime(), account, previous);
        if (bedStatus != StatusCode.OK) return bedStatus;

        previous.setAdmissionStatus(ADMISSION_STATUS_ADMITTED);
        clearDischargeFields(previous);
        StatusCode mergeStatus = syncService.mergeForReadmission(source, previous);
        if (mergeStatus != StatusCode.OK) {
            // 此时可能已写床史，必须抛出异常，经外层事务回滚后再返回业务状态。
            throw new ReadmissionRejected(mergeStatus);
        }

        readmissionHistoryRepo.save(history);
        patientRepo.save(previous);
        List<SurgeryHistory> surgeries = surgeryRepo.findByPatientId(pending.getId());
        if (!surgeries.isEmpty()) {
            surgeries.forEach(surgery -> surgery.setPatientId(previous.getId()));
            surgeryRepo.saveAll(surgeries);
        }
        List<DiagnosisHistory> diagnoses =
            diagnosisRepo.findByPatientIdAndIsDeletedFalse(pending.getId());
        if (!diagnoses.isEmpty()) {
            diagnoses.forEach(diagnosis -> diagnosis.setPatientId(previous.getId()));
            diagnosisRepo.saveAll(diagnoses);
        }
        patientRepo.delete(pending);
        return StatusCode.OK;
    }

    private static void clearDischargeFields(/* OUT */ PatientRecord patient) {
        patient.setDischargeType(null);
        patient.setDeathTime(null);
        patient.setHisDischargeTime(null);
        patient.setDischargeDiagnosis(null);
        patient.setDischargeDiagnosisCode(null);
        patient.setToDeptName(null);
        patient.setToDeptId(null);
        patient.setDischargeTime(null);
        patient.setDischargeEditTime(null);
        patient.setDischargeAccountId(null);
    }

    private static final class ReadmissionRejected extends RuntimeException {
        private ReadmissionRejected(StatusCode code) {
            this.code = code;
        }

        private final StatusCode code;
    }

    private final LockExecutor lockExecutor;
    private final JdbcTemplate jdbcTemplate;
    private final PatientRecordRepository patientRepo;
    private final BedMetaReader bedMetaReader;
    private final PatientRecordMapper mapper;
    private final PatientBedAssignment bedAssignment;
    private final PatientSyncService syncService;
    private final HisPatientRecordRepository hisPatientRepo;
    private final ReadmissionHistoryRepository readmissionHistoryRepo;
    private final DiagnosisHistoryRepository diagnosisRepo;
    private final SurgeryHistoryRepository surgeryRepo;
    private final PatientDeviceRepository patientDeviceRepo;
}
