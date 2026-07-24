package com.jingyicare.jingyi_icis_engine.service.patients;

import jakarta.persistence.EntityManager;
import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.grpc.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.certs.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientSyncUtils.PatientSyncInfo;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientSyncService {
    public PatientSyncService(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired PatientConfig patientConfig,
        @Autowired PatientSyncUtils patientSyncUtils,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired DiagnosisHistoryRepository diagnosisRepo,
        @Autowired SurgeryHistoryRepository surgeryRepo,
        @Autowired HisPatientRecordRepository hisPatientRepo,
        @Autowired EntityManager entityManager
    ) {
        PatientEnumsV2 enumsV2 = protoService.getConfig().getPatient().getEnumsV2();
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.PENDING_ADMISSION_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getPendingAdmissionName()))
            .findFirst()
            .get()
            .getId();
        this.IN_ICU_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .get()
            .getId();
        this.PENDING_DISCHARGED_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getPendingDischargedName()))
            .findFirst()
            .get()
            .getId();
        this.DISCHARGED_VAL = enumsV2.getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getDischargedName()))
            .findFirst()
            .get()
            .getId();
        this.TEMP_BED_TYPE_ID = protoService.getConfig().getDevice().getEnums().getBedTypeList().stream()
            .filter(e -> e.getName().equals("临时授权"))
            .findFirst()
            .get()
            .getId();

        this.context = context;
        this.protoService = protoService;
        this.patientConfig = patientConfig;
        this.patientSyncUtils = patientSyncUtils;
        this.deptRepo = deptRepo;
        this.patientRepo = patientRepo;
        this.diagnosisRepo = diagnosisRepo;
        this.surgeryRepo = surgeryRepo;
        this.hisPatientRepo = hisPatientRepo;

        this.entityManager = entityManager;

        this.scheduler = Executors.newSingleThreadScheduledExecutor();
    }

    @PostConstruct
    public void startSyncTimer() {
        log.info("syncStartDelayMinutes: {}, syncIntervalMinutes: {}",
            syncStartDelayMinutes, syncIntervalMinutes
        );
        scheduler.scheduleAtFixedRate(() -> {
            try {
                syncPatientRecords(false);
            } catch (Exception e) {
                log.error("Scheduled syncPatientRecords failed", e);
            }
        }, syncStartDelayMinutes, syncIntervalMinutes, TimeUnit.MINUTES);
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(syncThreadJoinTimeoutSeconds, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        log.info("PatientSyncService shutdown complete.");
    }

    public void syncPatientRecords(Boolean forceSync) {
        syncPatientRecords(forceSync, null);
    }

    @Transactional
    public void syncPatientRecords(Boolean forceSync, String deptId) {
        /**
         * 重要：一个mrn，在科的病人最多只能有一位
         */
        // 在his_patient_records表中查找在科的病人，按照mrn排序
        List<HisPatientRecord> hisPatientRecords = getInIcuHisPatientRecords();
        if (hisPatientRecords.isEmpty() && !forceSync) {
            log.warn("Skip sync, no in-ICU patient records found in his_patient_records");
            return;
        }
        if (deptId != null) {
            hisPatientRecords = hisPatientRecords.stream()
                .filter(r -> r.getDeptCode().equals(deptId)).toList();
        }

        // 在patient_records表中查找在科/待入科的病人
        List<PatientRecord> patientRecords = patientRepo.findByAdmissionStatusInOrderByHisMrnAsc(
            List.of(PENDING_ADMISSION_VAL, IN_ICU_VAL));
        if (deptId != null) {
            patientRecords = patientRecords.stream()
                .filter(r -> r.getDeptId().equals(deptId)).toList();
        }

        // 用归并排序的算法，合并hisPatientRecords和patientRecords
        Map<String/*hisMrn*/, PatientSyncInfo> syncInfoMap = new HashMap<>();
        int hisIndex = 0;
        int patientIndex = 0;

        while (hisIndex < hisPatientRecords.size() || patientIndex < patientRecords.size()) {
            if (hisIndex < hisPatientRecords.size() && patientIndex < patientRecords.size()) {
                HisPatientRecord hisRecord = hisPatientRecords.get(hisIndex);
                PatientRecord patientRecord = patientRecords.get(patientIndex);
                int compare = hisRecord.getMrn().compareTo(patientRecord.getHisMrn());

                if (compare == 0) {
                    // 1. mrn存在于hisPatientRecords和patientRecords中，更新patientRecord
                    patientSyncUtils.updatePatientRecord(hisRecord, patientRecord, syncInfoMap);
                    hisIndex++;
                    patientIndex++;
                } else if (compare < 0) {
                    // 2. mrn存在于hisPatientRecords但不在patientRecords中，创建新记录
                    PatientRecord newRecord = createPatientRecordFromHis(hisRecord, syncInfoMap);
                    if (newRecord != null) {
                        newRecord.setAdmissionStatus(PENDING_ADMISSION_VAL);
                    }
                    hisIndex++;
                } else {
                    // 3. mrn存在于patientRecords但不在hisPatientRecords中，标记为待出科
                    patientRecord.setAdmissionStatus(PENDING_DISCHARGED_VAL);
                    patientRecord.setDischargeTime(TimeUtils.getNowUtc());
                    PatientSyncInfo syncInfo = new PatientSyncInfo();
                    syncInfo.patientRecord = patientRecord;
                    syncInfoMap.put(patientRecord.getHisMrn(), syncInfo);
                    patientIndex++;
                }
            } else if (hisIndex < hisPatientRecords.size()) {
                // 2. hisPatientRecords中剩余的记录，创建新记录
                PatientRecord newRecord = createPatientRecordFromHis(
                    hisPatientRecords.get(hisIndex), syncInfoMap
                );
                if (newRecord != null) {
                    newRecord.setAdmissionStatus(PENDING_ADMISSION_VAL);
                }
                hisIndex++;
            } else {
                // 3. patientRecords中剩余的记录，标记为待出科
                PatientRecord patientRecord = patientRecords.get(patientIndex);
                if (patientRecord.getIcuManualEntry() != null && patientRecord.getIcuManualEntry() == true) {
                    log.warn("Patient record {} is not manually entered, skipping sync", patientRecord.getId());
                } else {
                    log.info("Marking patient record {} as pending discharged", patientRecord.getId());
                    patientRecord.setAdmissionStatus(PENDING_DISCHARGED_VAL);
                    patientRecord.setDischargeTime(TimeUtils.getNowUtc());
                    PatientSyncInfo syncInfo = new PatientSyncInfo();
                    syncInfo.patientRecord = patientRecord;
                    syncInfoMap.put(patientRecord.getHisMrn(), syncInfo);
                }
                patientIndex++;
            }
        }

        // 批量保存更新后的记录
        List<PatientRecord> recordsToSave = new ArrayList<>();
        for (PatientSyncInfo syncInfo : syncInfoMap.values()) {
            PatientRecord patientRecord = syncInfo.patientRecord;
            if (patientRecord != null) recordsToSave.add(patientRecord);
        }

        // 处理诊断和手术历史
        List<DiagnosisHistory> diagnosisHistoriesToAdd = new ArrayList<>();
        List<SurgeryHistory> surgeryHistoriesToAdd = new ArrayList<>();
        for (PatientRecord record : recordsToSave) {
            PatientRecord pr = patientRepo.save(record);
            Long pid = pr.getId();
            PatientSyncInfo syncInfo = syncInfoMap.get(record.getHisMrn());
            if (syncInfo != null) {
                for (DiagnosisHistory dh : syncInfo.diagnosisHisList) {
                    dh.setPatientId(pid);
                    diagnosisHistoriesToAdd.add(dh);
                }

                SurgeryHistory surgeryHistory = syncInfo.surgeryHistory;
                if (surgeryHistory != null) {
                    surgeryHistory.setPatientId(pid);
                    surgeryHistoriesToAdd.add(surgeryHistory);
                }
            }
        }
        if (!diagnosisHistoriesToAdd.isEmpty()) { diagnosisRepo.saveAll(diagnosisHistoriesToAdd); }
        if (!surgeryHistoriesToAdd.isEmpty()) { surgeryRepo.saveAll(surgeryHistoriesToAdd); }
        log.info("Synced {} patient records", recordsToSave.size());
    }

    public List<HisPatientRecord> getInIcuHisPatientRecords() {
        List<String> deptIds = deptRepo.findAll()
            .stream().map(RbacDepartment::getDeptId).toList();

        List<HisPatientRecord> hisPatientRecords = hisPatientRepo
            .findByAdmissionStatusAndDeptCodeInOrderByMrnAsc(IN_ICU_VAL, deptIds)
            .stream()
            .filter(record -> {
                if (record.getDischargeTime() == null) {
                    return true;
                }
                log.warn(
                    "Ignoring HIS in-ICU patient record with discharge time. hisPatientRecordId={}, mrn={}, pid={}, dischargeTime={}",
                    record.getId(),
                    record.getMrn(),
                    record.getPid(),
                    record.getDischargeTime()
                );
                return false;
            })
            .toList();

        String prevMrn = null;
        List<HisPatientRecord> filteredRecords = new ArrayList<>();
        for (HisPatientRecord record : hisPatientRecords) {
            if (!record.getMrn().equals(prevMrn)) {
                filteredRecords.add(record);
                prevMrn = record.getMrn();
            } else {
                log.warn("Duplicate MRN found in his_patient_records: {}", record.getMrn());
            }
        }

        return filteredRecords;
    }

    private PatientRecord createPatientRecordFromHis(
        HisPatientRecord hisRecord, Map<String, PatientSyncInfo> syncInfoMap
    ) {
        // 如果hisRecord的床位号不在我们的配置中，则不创建
        Map<String, BedConfig> bedConfigMap = patientConfig.getBedConfigMap(hisRecord.getDeptCode());
        BedConfig bedConfig = bedConfigMap.get(hisRecord.getBedNumber());
        if (bedConfig == null || Objects.equals(bedConfig.getBedType(), TEMP_BED_TYPE_ID)) {
            log.warn("Bed number {} not found in config for department {}, skipping creation", 
                     hisRecord.getBedNumber(), hisRecord.getDeptCode());
            return null; // 床位号不在配置中，跳过创建
        }

        // 从hisRecord创建新的PatientRecord
        PatientRecord newRecord = new PatientRecord();
        // 复用updatePatientRecord方法设置字段
        patientSyncUtils.updatePatientRecord(hisRecord, newRecord, syncInfoMap);
        return newRecord;
    }

    private void saveSurgeryHistory(List<SurgeryHistory> surgeryHistoriesToAdd) {
        if (!surgeryHistoriesToAdd.isEmpty()) {
            for (SurgeryHistory history : surgeryHistoriesToAdd) {
                SurgeryHistory existingHistory = surgeryRepo.findByPatientIdAndStartTime(
                    history.getPatientId(), history.getStartTime()
                ).orElse(null);
                if (existingHistory != null) {
                    history.setId(existingHistory.getId());
                }
            }
            // 批量保存手术历史
            surgeryRepo.saveAll(surgeryHistoriesToAdd);
        }
    }

    @Value("${patient.sync.start_delay_minutes:3}")
    private int syncStartDelayMinutes;

    @Value("${patient.sync.interval_minutes:3}")
    private int syncIntervalMinutes;

    @Value("${patient.sync.thread_join_timeout_seconds:10}")
    private int syncThreadJoinTimeoutSeconds;

    private final String ZONE_ID;
    private final Integer PENDING_ADMISSION_VAL;
    private final Integer IN_ICU_VAL;
    private final Integer PENDING_DISCHARGED_VAL;
    private final Integer DISCHARGED_VAL;
    private final Integer TEMP_BED_TYPE_ID;

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final PatientConfig patientConfig;
    private final PatientSyncUtils patientSyncUtils;
    private final RbacDepartmentRepository deptRepo;
    private final PatientRecordRepository patientRepo;
    private final DiagnosisHistoryRepository diagnosisRepo;
    private final SurgeryHistoryRepository surgeryRepo;
    private final HisPatientRecordRepository hisPatientRepo;

    private final EntityManager entityManager;

    private final ScheduledExecutorService scheduler;
}
