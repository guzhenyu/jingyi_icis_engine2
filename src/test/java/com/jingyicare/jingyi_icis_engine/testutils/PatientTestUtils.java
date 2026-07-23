package com.jingyicare.jingyi_icis_engine.testutils;

import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Autowired;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

public class PatientTestUtils {
    public static PatientRecord newPatientRecord(Long id, Integer admissionStatus, String deptId) {
        LocalDateTime now = LocalDateTime.now();
        PatientRecord record = new PatientRecord();
        record.setId(id);
        record.setHisMrn("mrn" + id);
        record.setHisPatientId("hisPatientId" + id);
        record.setHisIndexId("hisIndexId" + id);
        record.setHisEncounterId("hisEncounterId" + id);
        record.setHisAdmissionCount(1);
        record.setHisAdmissionTime(now);
        record.setHisAdmissionDiagnosis("hisAdmissionDiagnosis" + id);
        record.setHisBedNumber("hisBedNumber" + id);
        record.setIcuManualEntry(false);
        record.setIcuManualEntryAccountId("");
        record.setIcuName("icuName" + id);
        record.setIcuGender(1);
        record.setIcuDateOfBirth(now);
        record.setHeight(1.75f + id * 0.01f);
        record.setWeight(65.0f + id * 0.02f);
        record.setWeightType("weightType" + id);
        record.setWeightIbw("weightIbw" + id);
        record.setBloodType("bloodType" + id);
        record.setBloodRh("bloodRh" + id);
        record.setPastMedicalHistory("pastMedicalHistory" + id);
        record.setAllergies("allergies" + id);
        record.setPhone("phone" + id);
        record.setHomePhone("homePhone" + id);
        record.setHomeAddress("homeAddress" + id);
        record.setDocumentType("documentType" + id);
        record.setIdCardNumber("idCardNumber" + id);
        record.setNation("nation" + id);
        record.setNativePlace("nativePlace" + id);
        record.setOccupation("occupation" + id);
        record.setMaritalStatus(0);
        record.setEmergencyContactName("emergencyContactName" + id);
        record.setEmergencyContactRelation("emergencyContactRelation" + id);
        record.setEmergencyContactPhone("emergencyContactPhone" + id);
        record.setEmergencyContactAddress("emergencyContactAddress" + id);
        record.setPaymentMethod("paymentMethod" + id);
        record.setInsuranceType("insuranceType" + id);
        record.setInsuranceNumber("insuranceNumber" + id);
        record.setMedicalCardNumber("medicalCardNumber" + id);
        record.setIsVipPatient(false);
        record.setWristbandLocation("wristbandLocation" + id);
        record.setPatientPose("patientPose" + id);
        record.setNursingCareLevel("nursingCareLevel" + id);
        record.setIllnessSeverityLevel("illnessSeverityLevel" + id);
        record.setDietType("dietType" + id);
        record.setIsolationPrecaution("isolationPrecaution" + id);
        record.setChiefComplaint("chiefComplaint" + id);
        record.setDeptId(deptId);
        record.setDeptName("deptName" + deptId);
        record.setWardCode("wardCode" + id);
        record.setWardName("wardName" + id);
        record.setAttendingDoctorId("attendingDoctorId" + id);
        record.setPrimaryCareDoctorId("primaryCareDoctorId" + id);
        record.setAdmittingDoctorId("admittingDoctorId" + id);
        record.setResponsibleNurseId("responsibleNurseId" + id);
        record.setFromDeptName("fromDeptName" + id);
        record.setFromDeptId("fromDeptId" + id);
        record.setAdmissionType(1);  // todo(guzhenyu): deprecate this. 1 - "入院"
        record.setIsPlannedAdmission(true);
        record.setUnplannedAdmissionReason("");
        record.setAdmissionStatus(admissionStatus);
        record.setAdmissionTime(now);
        record.setAdmissionEditTime(now);
        record.setAdmittingAccountId("admittingAccountId" + id);
        record.setDiagnosis("diagnosis" + id);
        record.setDiagnosisTcm("diagnosisTcm" + id);
        record.setDiagnosisType("diagnosisType" + id);
        record.setDischargeType(1);  // todo(guzhenyu): deprecate this. 1 - "转出"
        record.setDeathTime(now);
        record.setHisDischargeTime(now);
        record.setDischargeDiagnosis("dischargeDiagnosis" + id);
        record.setToDeptName("toDeptName" + id);
        record.setToDeptId("toDeptId" + id);
        record.setDischargeTime(now);
        record.setDischargeEditTime(now);
        record.setDischargeAccountId("dischargeAccountId" + id);
        record.setCreatedAt(now);

        return record;
    }

    public static HisPatientRecord newHisPatientRecord(Long id, Integer admissionStatus, String deptCode) {
        LocalDateTime now = LocalDateTime.now();
        HisPatientRecord record = new HisPatientRecord();
        record.setId(id);
        record.setPid("hisPatientId" + id);
        record.setMrn("mrn" + id);
        record.setIndexId("hisIndexId" + id);
        record.setHisEncounterId("hisEncounterId" + id);
        record.setAdmissionCount(1);
        record.setAdmissionTime(now);
        record.setAdmissionDiagnosis("admissionDiagnosis" + id);
        record.setAdmissionDiagnosisCode("diagnosisCode" + id);
        record.setBedNumber("hisBedNumber" + id);
        record.setName("icuName" + id);
        record.setGender(1);
        record.setDateOfBirth(now);
        record.setHeight(1.75f + id * 0.01f);
        record.setWeight(65.0f + id * 0.02f);
        record.setBloodType("bloodType" + id);
        record.setBloodRh("bloodRh" + id);
        record.setPastMedicalHistory("pastMedicalHistory" + id);
        record.setAllergies("allergies" + id);
        record.setPhone("phone" + id);
        record.setHomeAddress("homeAddress" + id);
        record.setDocumentType("documentType" + id);
        record.setIdCardNumber("idCardNumber" + id);
        record.setNation("nation" + id);
        record.setNativePlace("nativePlace" + id);
        record.setOccupation("occupation" + id);
        record.setEmergencyContactName("emergencyContactName" + id);
        record.setEmergencyContactRelation("emergencyContactRelation" + id);
        record.setEmergencyContactPhone("emergencyContactPhone" + id);
        record.setPaymentMethod("paymentMethod" + id);
        record.setInsuranceType("insuranceType" + id);
        record.setInsuranceNumber("insuranceNumber" + id);
        record.setMedicalCardNumber("medicalCardNumber" + id);
        record.setIsVipPatient(false);
        record.setIllnessSeverityLevel("illnessSeverityLevel" + id);
        record.setChiefComplaint("chiefComplaint" + id);
        record.setDeptCode(deptCode);
        record.setDeptName("deptName" + deptCode);
        record.setWardCode("wardCode" + id);
        record.setWardName("wardName" + id);
        record.setAttendingDoctorName("attendingDoctorName" + id);
        record.setFromDeptName("fromDeptName" + id);
        record.setFromDeptId("fromDeptId" + id);
        record.setAdmissionStatus(admissionStatus);
        record.setDeptAdmissionTime(now);
        record.setDiagnosisTime(now);
        record.setDiagnosisCode("diagnosisCode" + id);
        record.setDiagnosis("diagnosis" + id);
        record.setDiagnosisTcmTime(now);
        record.setDiagnosisTcmCode("diagnosisTcmCode" + id);
        record.setDiagnosisTcm("diagnosisTcm" + id);
        record.setToDeptId("toDeptId" + id);
        record.setToDeptName("toDeptName" + id);
        record.setOperation("operation" + id);
        record.setOperationTime(now);
        record.setCreatedAt(now);

        return record;
    }
}
