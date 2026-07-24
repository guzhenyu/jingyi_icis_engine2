package com.jingyicare.jingyi_icis_engine.service.patients;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.HisPatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.entity.patients.SurgeryHistory;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientEnumsV2;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class PatientSyncUtils {
    public static class PatientSyncInfo {
        public PatientSyncInfo() {
            diagnosisHisList = new ArrayList<>();
        }

        public HisPatientRecord hisRecord;
        public PatientRecord patientRecord;
        public List<DiagnosisHistory> diagnosisHisList;
        public SurgeryHistory surgeryHistory;
    }

    public PatientSyncUtils(
        ConfigProtoService protoService,
        PatientDeviceService patientDeviceService,
        AccountRepository accountRepo
    ) {
        PatientEnumsV2 enumsV2 = protoService.getConfig().getPatient().getEnumsV2();
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

        this.patientDeviceService = patientDeviceService;
        this.accountRepo = accountRepo;
    }

    public boolean updatePatientRecord(
        HisPatientRecord hisRecord,
        PatientRecord patientRecord,
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
        // 4. his_encounter_id
        if (StrUtils.isBlank(patientRecord.getHisEncounterId()) && !StrUtils.isBlank(hisRecord.getHisEncounterId())) {
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
        // 41. from_dept_name
        if (StrUtils.isBlank(patientRecord.getFromDeptName()) && !StrUtils.isBlank(hisRecord.getFromDeptName())) {
            patientRecord.setFromDeptName(hisRecord.getFromDeptName());
            isUpdated = true;
        }
        // 41. from_dept_id
        if (StrUtils.isBlank(patientRecord.getFromDeptId()) && !StrUtils.isBlank(hisRecord.getFromDeptId())) {
            patientRecord.setFromDeptId(hisRecord.getFromDeptId());
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
        if (hisRecord.getDeptAdmissionTime() != null && patientRecord.getAdmissionTime() == null) {
            patientRecord.setAdmissionTime(hisRecord.getDeptAdmissionTime());
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
        // 46. to_dept_name
        if (StrUtils.isBlank(patientRecord.getToDeptName()) && !StrUtils.isBlank(hisRecord.getToDeptName())) {
            patientRecord.setToDeptName(hisRecord.getToDeptName());
            isUpdated = true;
        }
        // 47. to_dept_id
        if (StrUtils.isBlank(patientRecord.getToDeptId()) && !StrUtils.isBlank(hisRecord.getToDeptId())) {
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

    private final Integer PENDING_ADMISSION_VAL;
    private final Integer IN_ICU_VAL;
    private final Integer PENDING_DISCHARGED_VAL;

    private final PatientDeviceService patientDeviceService;
    private final AccountRepository accountRepo;
}
