package com.jingyicare.jingyi_icis_engine.service.patients.write;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.repository.patients.DiagnosisHistoryRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.HisPatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientReader;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class PatientSyncService {
    public PatientSyncService(
        PatientReader patientReader, PatientBedAssignment bedAssignment,
        RbacDepartmentRepository deptRepo, PatientRecordRepository patientRepo,
        DiagnosisHistoryRepository diagnosisRepo, HisPatientRecordRepository hisPatientRepo,
        AccountRepository accountRepo, LockExecutor lockExecutor
    ) {
        this.patientReader = patientReader;
        this.bedAssignment = bedAssignment;
        this.deptRepo = deptRepo;
        this.patientRepo = patientRepo;
        this.diagnosisRepo = diagnosisRepo;
        this.hisPatientRepo = hisPatientRepo;
        this.accountRepo = accountRepo;
        this.lockExecutor = lockExecutor;
    }

    public int syncPatientRecords(boolean forceSync) {
        // 与其他 Engine 患者写入共用事务锁，取得锁后才读取对账输入。
        // CIG 若恰好同时更新同一病例，对账可能基于旧快照覆盖其刚写入的字段。
        return lockExecutor.execute(
            List.of(LockKeys.patientRecords()), () -> syncInTransaction(forceSync));
    }

    private int syncInTransaction(boolean forceSync) {
        // 在his_patient_records表中查找在科的病人，按照mrn排序
        List<HisPatientRecord> hisPatientRecords = getInIcuHisPatientRecords();
        if (hisPatientRecords.isEmpty() && !forceSync) {
            log.warn("Skip sync, no in-ICU patient records found in his_patient_records");
            return 0;
        }

        // 在patient_records表中查找在科/待入科的病人
        List<PatientRecord> patientRecords = patientRepo.findByAdmissionStatusInOrderByHisMrnAsc(
            List.of(ADMISSION_STATUS_PRE_ADMISSION, ADMISSION_STATUS_ADMITTED));

        // 用归并排序的算法，合并hisPatientRecords和patientRecords
        List<PatientSyncInfo> stagedWrites = new ArrayList<>();
        int hisIndex = 0;
        int patientIndex = 0;

        while (hisIndex < hisPatientRecords.size() || patientIndex < patientRecords.size()) {
            if (hisIndex < hisPatientRecords.size() && patientIndex < patientRecords.size()) {
                HisPatientRecord hisRecord = hisPatientRecords.get(hisIndex);
                PatientRecord patientRecord = patientRecords.get(patientIndex);
                int compare = hisRecord.getMrn().compareTo(patientRecord.getHisMrn());

                if (compare == 0) {
                    // 1. mrn存在于hisPatientRecords和patientRecords中，更新patientRecord
                    syncExistingPatient(hisRecord, patientRecord, stagedWrites);
                    hisIndex++;
                    patientIndex++;
                } else if (compare < 0) {
                    // 2. mrn存在于hisPatientRecords但不在patientRecords中，创建新记录
                    PatientRecord newRecord = createPatientRecordFromHis(hisRecord, stagedWrites);
                    if (newRecord != null) {
                        newRecord.setAdmissionStatus(ADMISSION_STATUS_PRE_ADMISSION);
                    }
                    hisIndex++;
                } else {
                    // 3. mrn存在于patientRecords但不在hisPatientRecords中，标记为待出科
                    patientRecord.setAdmissionStatus(ADMISSION_STATUS_PRE_DISCHARGE);
                    patientRecord.setDischargeTime(TimeUtils.getNowUtc());
                    stagedWrites.add(new PatientSyncInfo(patientRecord));
                    patientIndex++;
                }
            } else if (hisIndex < hisPatientRecords.size()) {
                // 2. hisPatientRecords中剩余的记录，创建新记录
                PatientRecord newRecord = createPatientRecordFromHis(
                    hisPatientRecords.get(hisIndex), stagedWrites
                );
                if (newRecord != null) {
                    newRecord.setAdmissionStatus(ADMISSION_STATUS_PRE_ADMISSION);
                }
                hisIndex++;
            } else {
                // 3. patientRecords中剩余的记录，标记为待出科
                PatientRecord patientRecord = patientRecords.get(patientIndex);
                if (Boolean.TRUE.equals(patientRecord.getIcuManualEntry())) {
                    log.warn("Skipping manually entered patient {}", patientRecord.getId());
                } else {
                    log.info("Marking patient {} as pending discharged", patientRecord.getId());
                    patientRecord.setAdmissionStatus(ADMISSION_STATUS_PRE_DISCHARGE);
                    patientRecord.setDischargeTime(TimeUtils.getNowUtc());
                    stagedWrites.add(new PatientSyncInfo(patientRecord));
                }
                patientIndex++;
            }
        }

        List<DiagnosisHistory> diagnosesToAdd = new ArrayList<>();
        for (PatientSyncInfo syncInfo : stagedWrites) {
            PatientRecord saved = patientRepo.save(syncInfo.patientRecord);
            for (DiagnosisHistory diagnosis : syncInfo.diagnosisHisList) {
                diagnosis.setPatientId(saved.getId());
                diagnosesToAdd.add(diagnosis);
            }
        }
        if (!diagnosesToAdd.isEmpty()) {
            diagnosisRepo.saveAll(diagnosesToAdd);
        }
        log.info("Synced {} patient records", stagedWrites.size());
        return hisPatientRecords.size();
    }

    private List<HisPatientRecord> getInIcuHisPatientRecords() {
        List<String> deptIds = deptRepo.findAll()
            .stream().map(RbacDepartment::getDeptId).toList();

        List<HisPatientRecord> hisPatientRecords = hisPatientRepo
            .findByAdmissionStatusAndDeptCodeInOrderByMrnAscIdDesc(
                ADMISSION_STATUS_ADMITTED, deptIds)
            .stream()
            .filter(record -> {
                if (record.getDischargeTime() == null) {
                    return true;
                }
                log.warn(
                    "Ignoring HIS in-ICU record with discharge time. "
                        + "hisPatientRecordId={}, mrn={}, pid={}, dischargeTime={}",
                    record.getId(),
                    record.getMrn(),
                    record.getPid(),
                    record.getDischargeTime()
                );
                return false;
            })
            .toList();

        // 保持 MRN 升序供后续归并同步使用；同一 MRN 只保留有效在科记录中 ID 最大的一条。
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
        HisPatientRecord hisRecord, /* OUT */ List<PatientSyncInfo> stagedWrites
    ) {
        Map<String, BedConfig> bedConfigMap =
            patientReader.getBedConfigMap(hisRecord.getDeptCode());
        BedConfig bedConfig = bedConfigMap.get(hisRecord.getBedNumber());
        if (bedConfig == null || Objects.equals(bedConfig.getBedType(), BED_TYPE_TEMP)) {
            log.warn("Bed {} is unavailable for new patient in department {}",
                hisRecord.getBedNumber(), hisRecord.getDeptCode());
            return null;
        }

        PatientRecord patient = new PatientRecord();
        patient.setHisBedNumber(hisRecord.getBedNumber());
        stagedWrites.add(applyHisFields(hisRecord, true, patient));
        return patient;
    }

    private void syncExistingPatient(
        HisPatientRecord hisRecord,
        /* OUT */ PatientRecord patient,
        /* OUT */ List<PatientSyncInfo> stagedWrites
    ) {
        boolean bedChanged = !Objects.equals(patient.getHisBedNumber(), hisRecord.getBedNumber());
        if (bedChanged) {
            StatusCode status = bedAssignment.moveInTransaction(
                hisRecord.getBedNumber(), TimeUtils.getNowUtc(), "his", "his", patient);
            if (status != StatusCode.OK) {
                log.warn("Skipping HIS field updates for patient {}, bed change failed: {}",
                    patient.getId(), status);
                // 就诊信息不一致时仍按原规则置为待出科，不写入无效床号或其他 HIS 资料。
                if (hisAssignmentChanged(hisRecord, patient)) {
                    patient.setAdmissionStatus(ADMISSION_STATUS_PRE_DISCHARGE);
                    patient.setDischargeTime(TimeUtils.getNowUtc());
                    stagedWrites.add(new PatientSyncInfo(patient));
                }
                return;
            }
        }
        PatientSyncInfo syncInfo = applyHisFields(hisRecord, bedChanged, patient);
        if (syncInfo != null) {
            stagedWrites.add(syncInfo);
        }
    }

    // 重返已由入科流程开启锁事务；复用 HIS 规则，但只迁移原有诊断历史。
    StatusCode mergeForReadmission(HisPatientRecord hisRecord, /* OUT */ PatientRecord patient) {
        if (!Objects.equals(patient.getHisBedNumber(), hisRecord.getBedNumber())) {
            StatusCode status = bedAssignment.moveInTransaction(
                hisRecord.getBedNumber(), TimeUtils.getNowUtc(), "his", "his", patient);
            if (status != StatusCode.OK) return status;
        }
        applyHisFields(hisRecord, false, patient);
        return StatusCode.OK;
    }

    private static boolean hisAssignmentChanged(HisPatientRecord hisRecord, PatientRecord patient) {
        String deptId = patient.getDeptId();
        // 与字段合并规则一致：旧档未记录科室时，允许使用 HIS 科室补空。
        if (StrUtils.isBlank(deptId) && !StrUtils.isBlank(hisRecord.getDeptCode())) {
            deptId = hisRecord.getDeptCode();
        }
        return !Objects.equals(deptId, hisRecord.getDeptCode())
            || (!StrUtils.isBlank(patient.getHisPatientId())
                && !Objects.equals(patient.getHisPatientId(), hisRecord.getPid()));
    }

    private PatientSyncInfo applyHisFields(
        HisPatientRecord hisRecord, boolean bedChanged, /* OUT */ PatientRecord patientRecord
    ) {
        boolean isUpdated = bedChanged;
        PatientSyncInfo syncInfo = new PatientSyncInfo(patientRecord);

        // 1. his_patient_id  有值就不更新
        if (StrUtils.isBlank(patientRecord.getHisPatientId())
            && !StrUtils.isBlank(hisRecord.getPid())) {
            patientRecord.setHisPatientId(hisRecord.getPid());
            isUpdated = true;
        }
        // 2. his_mrn  有值就不更新
        if (StrUtils.isBlank(patientRecord.getHisMrn()) && !StrUtils.isBlank(hisRecord.getMrn())) {
            patientRecord.setHisMrn(hisRecord.getMrn());
            isUpdated = true;
        }
        // 3. his_index_id
        if (StrUtils.isBlank(patientRecord.getHisIndexId())
            && !StrUtils.isBlank(hisRecord.getIndexId())) {
            patientRecord.setHisIndexId(hisRecord.getIndexId());
            isUpdated = true;
        }
        // 4. his_encounter_id
        if (StrUtils.isBlank(patientRecord.getHisEncounterId())
            && !StrUtils.isBlank(hisRecord.getHisEncounterId())) {
            patientRecord.setHisEncounterId(hisRecord.getHisEncounterId());
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
        if (StrUtils.isBlank(patientRecord.getHisAdmissionDiagnosis())
            && !StrUtils.isBlank(hisRecord.getAdmissionDiagnosis())) {
            patientRecord.setHisAdmissionDiagnosis(hisRecord.getAdmissionDiagnosis());
            isUpdated = true;
        }
        // 8. his_admission_diagnosis_code
        if (StrUtils.isBlank(patientRecord.getHisAdmissionDiagnosisCode())
            && !StrUtils.isBlank(hisRecord.getAdmissionDiagnosisCode())) {
            patientRecord.setHisAdmissionDiagnosisCode(hisRecord.getAdmissionDiagnosisCode());
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
        if (hisRecord.getHeight() != null
            && (patientRecord.getHeight() == null || patientRecord.getHeight() < 0.01f)) {
            patientRecord.setHeight(hisRecord.getHeight());
            isUpdated = true;
        }
        // 14. weight
        if (hisRecord.getWeight() != null
            && (patientRecord.getWeight() == null || patientRecord.getWeight() < 0.01f)) {
            patientRecord.setWeight(hisRecord.getWeight());
            isUpdated = true;
        }
        // 15. blood_type
        if (StrUtils.isBlank(patientRecord.getBloodType())
            && !StrUtils.isBlank(hisRecord.getBloodType())) {
            patientRecord.setBloodType(hisRecord.getBloodType());
            isUpdated = true;
        }
        // 16. blood_rh
        if (StrUtils.isBlank(patientRecord.getBloodRh())
            && !StrUtils.isBlank(hisRecord.getBloodRh())) {
            patientRecord.setBloodRh(hisRecord.getBloodRh());
            isUpdated = true;
        }
        // 17. past_medical_history
        if (StrUtils.isBlank(patientRecord.getPastMedicalHistory())
            && !StrUtils.isBlank(hisRecord.getPastMedicalHistory())) {
            patientRecord.setPastMedicalHistory(hisRecord.getPastMedicalHistory());
            isUpdated = true;
        }
        // 18. allergies
        if (StrUtils.isBlank(patientRecord.getAllergies())
            && !StrUtils.isBlank(hisRecord.getAllergies())) {
            patientRecord.setAllergies(hisRecord.getAllergies());
            isUpdated = true;
        }
        // 19. phone
        if (StrUtils.isBlank(patientRecord.getPhone()) && !StrUtils.isBlank(hisRecord.getPhone())) {
            patientRecord.setPhone(hisRecord.getPhone());
            isUpdated = true;
        }
        // 20. home_address
        if (StrUtils.isBlank(patientRecord.getHomeAddress())
            && !StrUtils.isBlank(hisRecord.getHomeAddress())) {
            patientRecord.setHomeAddress(hisRecord.getHomeAddress());
            isUpdated = true;
        }
        // 21. document_type
        if (StrUtils.isBlank(patientRecord.getDocumentType())
            && !StrUtils.isBlank(hisRecord.getDocumentType())) {
            patientRecord.setDocumentType(hisRecord.getDocumentType());
            isUpdated = true;
        }
        // 22. id_card_number
        if (StrUtils.isBlank(patientRecord.getIdCardNumber())
            && !StrUtils.isBlank(hisRecord.getIdCardNumber())) {
            patientRecord.setIdCardNumber(hisRecord.getIdCardNumber());
            isUpdated = true;
        }
        // 23. nation
        if (StrUtils.isBlank(patientRecord.getNation())
            && !StrUtils.isBlank(hisRecord.getNation())) {
            patientRecord.setNation(hisRecord.getNation());
            isUpdated = true;
        }
        // 24. native_place
        if (StrUtils.isBlank(patientRecord.getNativePlace())
            && !StrUtils.isBlank(hisRecord.getNativePlace())) {
            patientRecord.setNativePlace(hisRecord.getNativePlace());
            isUpdated = true;
        }
        // 25. occupation
        if (StrUtils.isBlank(patientRecord.getOccupation())
            && !StrUtils.isBlank(hisRecord.getOccupation())) {
            patientRecord.setOccupation(hisRecord.getOccupation());
            isUpdated = true;
        }
        // 26. emergency_contact_name
        if (StrUtils.isBlank(patientRecord.getEmergencyContactName())
            && !StrUtils.isBlank(hisRecord.getEmergencyContactName())) {
            patientRecord.setEmergencyContactName(hisRecord.getEmergencyContactName());
            isUpdated = true;
        }
        // 27. emergency_contact_relation
        if (StrUtils.isBlank(patientRecord.getEmergencyContactRelation())
            && !StrUtils.isBlank(hisRecord.getEmergencyContactRelation())) {
            patientRecord.setEmergencyContactRelation(hisRecord.getEmergencyContactRelation());
            isUpdated = true;
        }
        // 28. emergency_contact_phone
        if (StrUtils.isBlank(patientRecord.getEmergencyContactPhone())
            && !StrUtils.isBlank(hisRecord.getEmergencyContactPhone())) {
            patientRecord.setEmergencyContactPhone(hisRecord.getEmergencyContactPhone());
            isUpdated = true;
        }
        // 29. payment_method
        if (StrUtils.isBlank(patientRecord.getPaymentMethod())
            && !StrUtils.isBlank(hisRecord.getPaymentMethod())) {
            patientRecord.setPaymentMethod(hisRecord.getPaymentMethod());
            isUpdated = true;
        }
        // 30. insurance_type
        if (StrUtils.isBlank(patientRecord.getInsuranceType())
            && !StrUtils.isBlank(hisRecord.getInsuranceType())) {
            patientRecord.setInsuranceType(hisRecord.getInsuranceType());
            isUpdated = true;
        }
        // 31. insurance_number
        if (StrUtils.isBlank(patientRecord.getInsuranceNumber())
            && !StrUtils.isBlank(hisRecord.getInsuranceNumber())) {
            patientRecord.setInsuranceNumber(hisRecord.getInsuranceNumber());
            isUpdated = true;
        }
        // 32. medical_card_number
        if (StrUtils.isBlank(patientRecord.getMedicalCardNumber())
            && !StrUtils.isBlank(hisRecord.getMedicalCardNumber())) {
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
        if (StrUtils.isBlank(patientRecord.getIllnessSeverityLevel())
            && !StrUtils.isBlank(hisRecord.getIllnessSeverityLevel())) {
            patientRecord.setIllnessSeverityLevel(hisRecord.getIllnessSeverityLevel());
            isUpdated = true;
        }
        // 35. chief_complaint
        if (StrUtils.isBlank(patientRecord.getChiefComplaint())
            && !StrUtils.isBlank(hisRecord.getChiefComplaint())) {
            patientRecord.setChiefComplaint(hisRecord.getChiefComplaint());
            isUpdated = true;
        }
        // 36. dept_id  有值就不更新
        if (StrUtils.isBlank(patientRecord.getDeptId())
            && !StrUtils.isBlank(hisRecord.getDeptCode())) {
            patientRecord.setDeptId(hisRecord.getDeptCode());
            isUpdated = true;
        }
        // 37. dept_name  有值就不更新
        if (StrUtils.isBlank(patientRecord.getDeptName())
            && !StrUtils.isBlank(hisRecord.getDeptName())) {
            patientRecord.setDeptName(hisRecord.getDeptName());
            isUpdated = true;
        }
        // 38. ward_code
        if (StrUtils.isBlank(patientRecord.getWardCode())
            && !StrUtils.isBlank(hisRecord.getWardCode())) {
            patientRecord.setWardCode(hisRecord.getWardCode());
            isUpdated = true;
        }
        // 39. ward_name
        if (StrUtils.isBlank(patientRecord.getWardName())
            && !StrUtils.isBlank(hisRecord.getWardName())) {
            patientRecord.setWardName(hisRecord.getWardName());
            isUpdated = true;
        }
        // 40. attending_doctor_id  (doctorName => doctorId)
        if (StrUtils.isBlank(patientRecord.getAttendingDoctorId())
            && !StrUtils.isBlank(hisRecord.getAttendingDoctorName())) {
            List<Account> attendingDoctor =
                accountRepo.findByNameAndIsDeletedFalse(hisRecord.getAttendingDoctorName());
            if (attendingDoctor.size() == 1) {
                patientRecord.setAttendingDoctorId(attendingDoctor.get(0).getId().toString());
                isUpdated = true;
            }
        }
        // 41. from_dept_name
        if (StrUtils.isBlank(patientRecord.getFromDeptName())
            && !StrUtils.isBlank(hisRecord.getFromDeptName())) {
            patientRecord.setFromDeptName(hisRecord.getFromDeptName());
            isUpdated = true;
        }
        // 41. from_dept_id
        if (StrUtils.isBlank(patientRecord.getFromDeptId())
            && !StrUtils.isBlank(hisRecord.getFromDeptId())) {
            patientRecord.setFromDeptId(hisRecord.getFromDeptId());
            isUpdated = true;
        }
        // 42. admission_status  强制更新
        // his(入科) v.s. icu(待入科) 不处理
        // his(入科) v.s. icu(入科) 不处理
        Integer currentAdmissionStatus = patientRecord.getAdmissionStatus();
        if (!Objects.equals(currentAdmissionStatus, ADMISSION_STATUS_PRE_ADMISSION) &&
            !Objects.equals(currentAdmissionStatus, ADMISSION_STATUS_ADMITTED)
        ) {
            patientRecord.setAdmissionStatus(ADMISSION_STATUS_PRE_ADMISSION);
            isUpdated = true;
        }
        if (hisAssignmentChanged(hisRecord, patientRecord)) {
            patientRecord.setAdmissionStatus(ADMISSION_STATUS_PRE_DISCHARGE);
            patientRecord.setDischargeTime(TimeUtils.getNowUtc());
            isUpdated = true;
        }
        // 43. admission_time
        if (hisRecord.getDeptAdmissionTime() != null && patientRecord.getAdmissionTime() == null) {
            patientRecord.setAdmissionTime(hisRecord.getDeptAdmissionTime());
            isUpdated = true;
        }
        // 44. diagnosis
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (!StrUtils.isBlank(hisRecord.getDiagnosis())
            && StrUtils.isBlank(patientRecord.getDiagnosis())) {
            patientRecord.setDiagnosis(hisRecord.getDiagnosis());

            DiagnosisHistory diagnosisHistory = DiagnosisHistory.builder()
                .patientId(patientRecord.getId())
                .diagnosis(hisRecord.getDiagnosis())
                .diagnosisCode(hisRecord.getDiagnosisCode())
                .diagnosisTime(
                    hisRecord.getDiagnosisTime() == null ? nowUtc : hisRecord.getDiagnosisTime())
                .diagnosisAccountId("his")
                .isDeleted(false)
                .modifiedAt(nowUtc)
                .modifiedBy("his")
                .build();
            syncInfo.diagnosisHisList.add(diagnosisHistory);

            isUpdated = true;
        }
        // 46. to_dept_name
        if (StrUtils.isBlank(patientRecord.getToDeptName())
            && !StrUtils.isBlank(hisRecord.getToDeptName())) {
            patientRecord.setToDeptName(hisRecord.getToDeptName());
            isUpdated = true;
        }
        // 47. to_dept_id
        if (StrUtils.isBlank(patientRecord.getToDeptId())
            && !StrUtils.isBlank(hisRecord.getToDeptId())) {
            patientRecord.setToDeptId(hisRecord.getToDeptId());
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
        if (patientRecord.getIcuManualEntry() != null
            && patientRecord.getIcuManualEntry() == true) {
            patientRecord.setIcuManualEntry(false);
            isUpdated = true;
        }

        return isUpdated ? syncInfo : null;
    }

    private static final class PatientSyncInfo {
        private PatientSyncInfo(PatientRecord patientRecord) {
            this.patientRecord = patientRecord;
        }

        private final PatientRecord patientRecord;
        private final List<DiagnosisHistory> diagnosisHisList = new ArrayList<>();
    }

    private final PatientReader patientReader;
    private final PatientBedAssignment bedAssignment;
    private final RbacDepartmentRepository deptRepo;
    private final PatientRecordRepository patientRepo;
    private final DiagnosisHistoryRepository diagnosisRepo;
    private final HisPatientRecordRepository hisPatientRepo;
    private final AccountRepository accountRepo;
    private final LockExecutor lockExecutor;
}
