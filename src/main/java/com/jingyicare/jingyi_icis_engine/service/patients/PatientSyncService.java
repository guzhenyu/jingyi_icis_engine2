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
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class PatientSyncService {
    public PatientSyncService(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired PatientConfig patientConfig,
        @Autowired PatientDeviceService patientDeviceService,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired AccountRepository accountRepo,
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
        this.patientDeviceService = patientDeviceService;
        this.deptRepo = deptRepo;
        this.accountRepo = accountRepo;
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

    private static class PatientSyncInfo {
        public PatientSyncInfo() { diagnosisHisList = new ArrayList<>(); }

        public HisPatientRecord hisRecord;
        public PatientRecord patientRecord;
        public List<DiagnosisHistory> diagnosisHisList;
        public SurgeryHistory surgeryHistory;
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
                    updatePatientRecord(hisRecord, patientRecord, syncInfoMap);
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
            .findByAdmissionStatusAndDeptCodeInOrderByMrnAsc(IN_ICU_VAL, deptIds);

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

    // 患者重返：
    // 1. 在 patient_bed_history 中增加两条记录，重返出科 + 重返入科
    // 2. 将原来 dischargedPatient 的入科状态改成"入科"
    // 3. 通过newPatient(由刷新服务产生)找到对应的HisPatientRecord，更新对应的 dischargedPatient
    // 4. 将更新后的 dischargedPatient 保存到数据库，删除 newPatient
    public StatusCode readmitPatient(PatientRecord dischargedPatient, PatientRecord newPatient) {
        if (dischargedPatient == null || newPatient == null ||
            !dischargedPatient.getHisMrn().equals(newPatient.getHisMrn())
        ) {
            log.warn("Cannot merge patients: {} and {}", dischargedPatient.getHisMrn(), newPatient.getHisMrn());
            return StatusCode.READMISSION_PATIENT_INCONSISTENT;
        }
        HisPatientRecord hisPatientRecord = hisPatientRepo.findByPidAndAdmissionStatus(
            newPatient.getHisPatientId(), IN_ICU_VAL
        ).orElse(null);
        if (hisPatientRecord == null) {
            log.warn("Cannot find hisPatientRecord for: {}", newPatient.getHisPatientId());
            return StatusCode.READMISSION_PATIENT_NOT_FOUND;
        }

        // 更新病人
        Map<String, PatientSyncInfo> syncInfoMap = new HashMap<>();
        boolean toUpdate = updatePatientRecord(hisPatientRecord, dischargedPatient, syncInfoMap);
        if (toUpdate) {
            patientRepo.save(dischargedPatient);
        }
        patientRepo.delete(newPatient);  // 删除新病人记录

        // 更新手术信息
        List<SurgeryHistory> surgeries = surgeryRepo.findByPatientId(newPatient.getId());
        for (SurgeryHistory surgery : surgeries) {
            surgery.setPatientId(dischargedPatient.getId());
        }
        if (!surgeries.isEmpty()) surgeryRepo.saveAll(surgeries);

        // 更新诊断信息
        List<DiagnosisHistory> diagnoses = diagnosisRepo.findByPatientIdAndIsDeletedFalse(newPatient.getId());
        for (DiagnosisHistory diagnosis : diagnoses) {
            diagnosis.setPatientId(dischargedPatient.getId());
        }
        if (!diagnoses.isEmpty()) diagnosisRepo.saveAll(diagnoses);

        return StatusCode.OK;
    }

    private boolean updatePatientRecord(
        HisPatientRecord hisRecord, PatientRecord patientRecord,
        Map<String, PatientSyncInfo> syncInfoMap
    ) {
        boolean isUpdated = false;
        PatientSyncInfo syncInfo = new PatientSyncInfo();
        syncInfo.hisRecord = hisRecord;
        syncInfo.patientRecord = patientRecord;

        // 1. his_patient_id  有值就不更新
        if (StrUtils.isBlank(patientRecord.getHisPatientId()) && !StrUtils.isBlank(hisRecord.getPid())) {
            patientRecord.setHisPatientId(hisRecord.getPid());
            isUpdated = true;
        }
        // 2. his_mrn  有值就不更新
        if (StrUtils.isBlank(patientRecord.getHisMrn()) && !StrUtils.isBlank(hisRecord.getMrn())) {
            patientRecord.setHisMrn(hisRecord.getMrn());
            isUpdated = true;
        }
        // 3. his_index_id
        if (StrUtils.isBlank(patientRecord.getHisIndexId()) && !StrUtils.isBlank(hisRecord.getIndexId())) {
            patientRecord.setHisIndexId(hisRecord.getIndexId());
            isUpdated = true;
        }
        // 4. his_patient_serial_number
        if (StrUtils.isBlank(patientRecord.getHisPatientSerialNumber()) && !StrUtils.isBlank(hisRecord.getPatientSerialNumber())) {
            patientRecord.setHisPatientSerialNumber(hisRecord.getPatientSerialNumber());
            isUpdated = true;
        }
        // 5. his_admission_count  强制更新
        if (!Objects.equals(patientRecord.getHisAdmissionCount(), hisRecord.getAdmissionCount())) {
            patientRecord.setHisAdmissionCount(hisRecord.getAdmissionCount());
            isUpdated = true;
        }
        // 6. his_admission_time
        if (hisRecord.getAdmissionTime() != null && patientRecord.getHisAdmissionTime() == null) {
            patientRecord.setHisAdmissionTime(hisRecord.getAdmissionTime());
            isUpdated = true;
        }
        // 7. his_admission_diagnosis
        if (StrUtils.isBlank(patientRecord.getHisAdmissionDiagnosis()) && !StrUtils.isBlank(hisRecord.getAdmissionDiagnosis())) {
            patientRecord.setHisAdmissionDiagnosis(hisRecord.getAdmissionDiagnosis());
            isUpdated = true;
        }
        // 8. his_admission_diagnosis_code
        if (StrUtils.isBlank(patientRecord.getHisAdmissionDiagnosisCode()) && !StrUtils.isBlank(hisRecord.getAdmissionDiagnosisCode())) {
            patientRecord.setHisAdmissionDiagnosisCode(hisRecord.getAdmissionDiagnosisCode());
            isUpdated = true;
        }
        // 9. his_bed_number  强制更新
        Boolean switchBedSuccess = true;
        if (!Objects.equals(patientRecord.getHisBedNumber(), hisRecord.getBedNumber())) {
            if (patientRecord.getId() != null) {  // 新病人不需要换床
                StatusCode statusCode = patientDeviceService.switchBedImpl(
                    patientRecord, hisRecord.getBedNumber(), TimeUtils.getNowUtc(), false, "his", "his"
                );
                switchBedSuccess = (statusCode == StatusCode.OK);
            }
            patientRecord.setHisBedNumber(hisRecord.getBedNumber());
            isUpdated = true;
        }
        // 10. icu_name  强制更新
        if (!Objects.equals(patientRecord.getIcuName(), hisRecord.getName())) {
            patientRecord.setIcuName(hisRecord.getName());
            isUpdated = true;
        }
        // 11. icu_gender  强制更新
        if (!Objects.equals(patientRecord.getIcuGender(), hisRecord.getGender())) {
            patientRecord.setIcuGender(hisRecord.getGender());
            isUpdated = true;
        }
        // 12. icu_date_of_birth
        if (hisRecord.getDateOfBirth() != null && patientRecord.getIcuDateOfBirth() == null) {
            patientRecord.setIcuDateOfBirth(hisRecord.getDateOfBirth());
            isUpdated = true;
        }
        // 13. height
        if (hisRecord.getHeight() != null && (patientRecord.getHeight() == null || patientRecord.getHeight() < 0.01f)) {
            patientRecord.setHeight(hisRecord.getHeight());
            isUpdated = true;
        }
        // 14. weight
        if (hisRecord.getWeight() != null && (patientRecord.getWeight() == null || patientRecord.getWeight() < 0.01f)) {
            patientRecord.setWeight(hisRecord.getWeight());
            isUpdated = true;
        }
        // 15. blood_type
        if (StrUtils.isBlank(patientRecord.getBloodType()) && !StrUtils.isBlank(hisRecord.getBloodType())) {
            patientRecord.setBloodType(hisRecord.getBloodType());
            isUpdated = true;
        }
        // 16. blood_rh
        if (StrUtils.isBlank(patientRecord.getBloodRh()) && !StrUtils.isBlank(hisRecord.getBloodRh())) {
            patientRecord.setBloodRh(hisRecord.getBloodRh());
            isUpdated = true;
        }
        // 17. past_medical_history
        if (StrUtils.isBlank(patientRecord.getPastMedicalHistory()) && !StrUtils.isBlank(hisRecord.getPastMedicalHistory())) {
            patientRecord.setPastMedicalHistory(hisRecord.getPastMedicalHistory());
            isUpdated = true;
        }
        // 18. allergies
        if (StrUtils.isBlank(patientRecord.getAllergies()) && !StrUtils.isBlank(hisRecord.getAllergies())) {
            patientRecord.setAllergies(hisRecord.getAllergies());
            isUpdated = true;
        }
        // 19. phone
        if (StrUtils.isBlank(patientRecord.getPhone()) && !StrUtils.isBlank(hisRecord.getPhone())) {
            patientRecord.setPhone(hisRecord.getPhone());
            isUpdated = true;
        }
        // 20. home_address
        if (StrUtils.isBlank(patientRecord.getHomeAddress()) && !StrUtils.isBlank(hisRecord.getHomeAddress())) {
            patientRecord.setHomeAddress(hisRecord.getHomeAddress());
            isUpdated = true;
        }
        // 21. document_type
        if (StrUtils.isBlank(patientRecord.getDocumentType()) && !StrUtils.isBlank(hisRecord.getDocumentType())) {
            patientRecord.setDocumentType(hisRecord.getDocumentType());
            isUpdated = true;
        }
        // 22. id_card_number
        if (StrUtils.isBlank(patientRecord.getIdCardNumber()) && !StrUtils.isBlank(hisRecord.getIdCardNumber())) {
            patientRecord.setIdCardNumber(hisRecord.getIdCardNumber());
            isUpdated = true;
        }
        // 23. nation
        if (StrUtils.isBlank(patientRecord.getNation()) && !StrUtils.isBlank(hisRecord.getNation())) {
            patientRecord.setNation(hisRecord.getNation());
            isUpdated = true;
        }
        // 24. native_place
        if (StrUtils.isBlank(patientRecord.getNativePlace()) && !StrUtils.isBlank(hisRecord.getNativePlace())) {
            patientRecord.setNativePlace(hisRecord.getNativePlace());
            isUpdated = true;
        }
        // 25. occupation
        if (StrUtils.isBlank(patientRecord.getOccupation()) && !StrUtils.isBlank(hisRecord.getOccupation())) {
            patientRecord.setOccupation(hisRecord.getOccupation());
            isUpdated = true;
        }
        // 26. emergency_contact_name
        if (StrUtils.isBlank(patientRecord.getEmergencyContactName()) && !StrUtils.isBlank(hisRecord.getEmergencyContactName())) {
            patientRecord.setEmergencyContactName(hisRecord.getEmergencyContactName());
            isUpdated = true;
        }
        // 27. emergency_contact_relation
        if (StrUtils.isBlank(patientRecord.getEmergencyContactRelation()) && !StrUtils.isBlank(hisRecord.getEmergencyContactRelation())) {
            patientRecord.setEmergencyContactRelation(hisRecord.getEmergencyContactRelation());
            isUpdated = true;
        }
        // 28. emergency_contact_phone
        if (StrUtils.isBlank(patientRecord.getEmergencyContactPhone()) && !StrUtils.isBlank(hisRecord.getEmergencyContactPhone())) {
            patientRecord.setEmergencyContactPhone(hisRecord.getEmergencyContactPhone());
            isUpdated = true;
        }
        // 29. payment_method
        if (StrUtils.isBlank(patientRecord.getPaymentMethod()) && !StrUtils.isBlank(hisRecord.getPaymentMethod())) {
            patientRecord.setPaymentMethod(hisRecord.getPaymentMethod());
            isUpdated = true;
        }
        // 30. insurance_type
        if (StrUtils.isBlank(patientRecord.getInsuranceType()) && !StrUtils.isBlank(hisRecord.getInsuranceType())) {
            patientRecord.setInsuranceType(hisRecord.getInsuranceType());
            isUpdated = true;
        }
        // 31. insurance_number
        if (StrUtils.isBlank(patientRecord.getInsuranceNumber()) && !StrUtils.isBlank(hisRecord.getInsuranceNumber())) {
            patientRecord.setInsuranceNumber(hisRecord.getInsuranceNumber());
            isUpdated = true;
        }
        // 32. medical_card_number
        if (StrUtils.isBlank(patientRecord.getMedicalCardNumber()) && !StrUtils.isBlank(hisRecord.getMedicalCardNumber())) {
            patientRecord.setMedicalCardNumber(hisRecord.getMedicalCardNumber());
            isUpdated = true;
        }
        // 33. is_vip_patient
        if (hisRecord.getIsVipPatient() != null ||
            (patientRecord.getIsVipPatient() != null &&
            !patientRecord.getIsVipPatient().equals(hisRecord.getIsVipPatient())
            )
        ) {
            patientRecord.setIsVipPatient(hisRecord.getIsVipPatient());
            isUpdated = true;
        }
        // 34. illness_severity_level
        if (StrUtils.isBlank(patientRecord.getIllnessSeverityLevel()) && !StrUtils.isBlank(hisRecord.getIllnessSeverityLevel())) {
            patientRecord.setIllnessSeverityLevel(hisRecord.getIllnessSeverityLevel());
            isUpdated = true;
        }
        // 35. chief_complaint
        if (StrUtils.isBlank(patientRecord.getChiefComplaint()) && !StrUtils.isBlank(hisRecord.getChiefComplaint())) {
            patientRecord.setChiefComplaint(hisRecord.getChiefComplaint());
            isUpdated = true;
        }
        // 36. dept_id  有值就不更新
        if (StrUtils.isBlank(patientRecord.getDeptId()) && !StrUtils.isBlank(hisRecord.getDeptCode())) {
            patientRecord.setDeptId(hisRecord.getDeptCode());
            isUpdated = true;
        }
        // 37. dept_name  有值就不更新
        if (StrUtils.isBlank(patientRecord.getDeptName()) && !StrUtils.isBlank(hisRecord.getDeptName())) {
            patientRecord.setDeptName(hisRecord.getDeptName());
            isUpdated = true;
        }
        // 38. ward_code
        if (StrUtils.isBlank(patientRecord.getWardCode()) && !StrUtils.isBlank(hisRecord.getWardCode())) {
            patientRecord.setWardCode(hisRecord.getWardCode());
            isUpdated = true;
        }
        // 39. ward_name
        if (StrUtils.isBlank(patientRecord.getWardName()) && !StrUtils.isBlank(hisRecord.getWardName())) {
            patientRecord.setWardName(hisRecord.getWardName());
            isUpdated = true;
        }
        // 40. attending_doctor_id  (doctorName => doctorId)
        if (StrUtils.isBlank(patientRecord.getAttendingDoctorId()) && !StrUtils.isBlank(hisRecord.getAttendingDoctorName())) {
            List<Account> attendingDoctor = accountRepo.findByNameAndIsDeletedFalse(hisRecord.getAttendingDoctorName());
            if (attendingDoctor.size() == 1) {
                patientRecord.setAttendingDoctorId(attendingDoctor.get(0).getId().toString());
                isUpdated = true;
            }
        }
        // 41. admission_source_dept_name
        if (StrUtils.isBlank(patientRecord.getAdmissionSourceDeptName()) && !StrUtils.isBlank(hisRecord.getAdmissionSourceDeptName())) {
            patientRecord.setAdmissionSourceDeptName(hisRecord.getAdmissionSourceDeptName());
            isUpdated = true;
        }
        // 42. admission_status  强制更新
        // his(入科) v.s. icu(待入科) 不处理
        // his(入科) v.s. icu(入科) 不处理
        Integer currentAdmissionStatus = patientRecord.getAdmissionStatus();
        if (!Objects.equals(currentAdmissionStatus, PENDING_ADMISSION_VAL) &&
            !Objects.equals(currentAdmissionStatus, IN_ICU_VAL)
        ) {
            patientRecord.setAdmissionStatus(PENDING_ADMISSION_VAL);
            isUpdated = true;
        }
        if (!Objects.equals(patientRecord.getDeptId(), hisRecord.getDeptCode()) ||
            (!StrUtils.isBlank(patientRecord.getHisPatientId()) &&
                !Objects.equals(patientRecord.getHisPatientId(), hisRecord.getPid()))
        ) {
            patientRecord.setAdmissionStatus(PENDING_DISCHARGED_VAL);
            patientRecord.setDischargeTime(TimeUtils.getNowUtc());
            isUpdated = true;
        }
        // 43. admission_time
        if (hisRecord.getIcuAdmissionTime() != null && patientRecord.getAdmissionTime() == null) {
            patientRecord.setAdmissionTime(hisRecord.getIcuAdmissionTime());
            isUpdated = true;
        }
        // 44. diagnosis, diagnosis_tcm, diagnosis_type
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (!StrUtils.isBlank(hisRecord.getDiagnosis()) && StrUtils.isBlank(patientRecord.getDiagnosis())) {
            patientRecord.setDiagnosis(hisRecord.getDiagnosis());

            DiagnosisHistory diagnosisHistory = DiagnosisHistory.builder()
                .patientId(patientRecord.getId())
                .diagnosis(hisRecord.getDiagnosis())
                .diagnosisCode(hisRecord.getDiagnosisCode())
                .diagnosisTime(hisRecord.getDiagnosisTime() == null ? nowUtc : hisRecord.getDiagnosisTime())
                .diagnosisAccountId("his")
                .isDeleted(false)
                .modifiedAt(nowUtc)
                .modifiedBy("his")
                .build();
            syncInfo.diagnosisHisList.add(diagnosisHistory);

            isUpdated = true;
        }
        if (!StrUtils.isBlank(hisRecord.getDiagnosisTcm()) && StrUtils.isBlank(patientRecord.getDiagnosisTcm())) {
            patientRecord.setDiagnosisTcm(hisRecord.getDiagnosisTcm());

            DiagnosisHistory diagnosisHistory = DiagnosisHistory.builder()
                .patientId(patientRecord.getId())
                .diagnosisTcm(hisRecord.getDiagnosisTcm())
                .diagnosisTcmCode(hisRecord.getDiagnosisTcmCode())
                .diagnosisTime(hisRecord.getDiagnosisTcmTime())
                .diagnosisAccountId("his")
                .isDeleted(false)
                .modifiedAt(nowUtc)
                .modifiedBy("his")
                .build();
            syncInfo.diagnosisHisList.add(diagnosisHistory);

            isUpdated = true;
        }
        // 45. surgery_operation, surgery_operation_time
        if (StrUtils.isBlank(patientRecord.getSurgeryOperation()) && !StrUtils.isBlank(hisRecord.getOperation())) {
            // 手术信息需要添加到手术历史中
            SurgeryHistory surgeryHistory = new SurgeryHistory();
            surgeryHistory.setPatientId(patientRecord.getId());
            surgeryHistory.setName(hisRecord.getOperation());
            syncInfo.surgeryHistory = surgeryHistory;

            // 更新patientRecord的手术信息
            patientRecord.setSurgeryOperation(hisRecord.getOperation());
            patientRecord.setSurgeryOperationTime(hisRecord.getOperationTime());
            isUpdated = true;
        }
        // 46. discharged_dept_name
        if (StrUtils.isBlank(patientRecord.getDischargedDeptName()) && !StrUtils.isBlank(hisRecord.getDischargedDeptName())) {
            patientRecord.setDischargedDeptName(hisRecord.getDischargedDeptName());
            isUpdated = true;
        }
        // 47. discharged_dept_id
        if (StrUtils.isBlank(patientRecord.getDischargedDeptId()) && !StrUtils.isBlank(hisRecord.getDischargedDeptId())) {
            patientRecord.setDischargedDeptId(hisRecord.getDischargedDeptId());
            isUpdated = true;
        }
        // 48. discharge_time
        if (hisRecord.getDischargeTime() != null &&  // 此条件为假，因为取得是在科的his记录
            (patientRecord.getDischargeTime() == null ||
             !patientRecord.getDischargeTime().equals(hisRecord.getDischargeTime())
            )
        ) {
            patientRecord.setDischargeTime(hisRecord.getDischargeTime());
            isUpdated = true;
        }

        // 49. icu_manual_entry
        if (patientRecord.getIcuManualEntry() != null && patientRecord.getIcuManualEntry() == true) {
            patientRecord.setIcuManualEntry(false);
            isUpdated = true;
        }

        if (isUpdated && switchBedSuccess) {
            // 如果有更新，记录同步信息
            syncInfoMap.put(hisRecord.getMrn(), syncInfo);
        }

        return isUpdated;
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
        updatePatientRecord(hisRecord, newRecord, syncInfoMap);
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
    private final PatientDeviceService patientDeviceService;
    private final RbacDepartmentRepository deptRepo;
    private final AccountRepository accountRepo;
    private final PatientRecordRepository patientRepo;
    private final DiagnosisHistoryRepository diagnosisRepo;
    private final SurgeryHistoryRepository surgeryRepo;
    private final HisPatientRecordRepository hisPatientRepo;

    private final EntityManager entityManager;

    private final ScheduledExecutorService scheduler;
}