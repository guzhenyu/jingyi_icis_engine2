package com.jingyicare.jingyi_icis_engine.service.patients.write;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;
import com.jingyicare.jingyi_icis_engine.entity.patients.DiagnosisHistory;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.DiagnosisHistoryPB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.NewPatientReq;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientRecordPB;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Component
public class PatientRecordMapper {
    public PatientRecordMapper(PatientEnums patientEnums, UserQuery userQuery) {
        this.patientEnums = patientEnums;
        this.userQuery = userQuery;
    }

    public PatientRecordPB toPatientRecordPB(
        PatientRecord patient, Map<String, BedConfig> bedConfigMap
    ) {
        PatientRecordPB.Builder builder = PatientRecordPB.newBuilder();

        builder.setPid(patient.getId() != null ? patient.getId() : 0);
        builder.setHisMrn(patient.getHisMrn());
        builder.setHisPatientId(patient.getHisPatientId());
        builder.setHisAdmissionTimeIso8601(
            TimeUtils.toIso8601String(patient.getHisAdmissionTime(), "UTC"));
        builder.setHisAdmissionDiagnosis(patient.getHisAdmissionDiagnosis());
        builder.setHisBedNumber(patient.getHisBedNumber());
        builder.setIcuName(patient.getIcuName());
        builder.setIcuGender(patient.getIcuGender());
        builder.setIcuDateOfBirthIso8601(
            TimeUtils.toIso8601String(patient.getIcuDateOfBirth(), "UTC"));
        builder.setAge(TimeUtils.getAge(patient.getIcuDateOfBirth()));
        builder.setHeight(patient.getHeight());
        builder.setWeight(patient.getWeight());
        builder.setWeightType(patient.getWeightType());
        builder.setWeightIbw(patient.getWeightIbw());
        // 身高单位为厘米，计算 BMI 时换算为米。
        if (patient.getHeight() != null && patient.getHeight() > 0 &&
            patient.getWeight() != null && patient.getWeight() > 0
        ) {
            float height = patient.getHeight();
            float bmi = patient.getWeight() * 10000.0f / (height * height);
            builder.setBmi(String.format("%.1f", bmi));
        }

        builder.setBloodType(patientEnums.getBloodTypeId(patient.getBloodType()));
        builder.setBloodRh(patientEnums.getBloodRhId(patient.getBloodRh()));
        builder.setBsa(patient.getBsa());
        builder.setPastMedicalHistory(patient.getPastMedicalHistory());
        builder.setAllergies(patient.getAllergies());
        builder.setPhone(patient.getPhone());
        builder.setHomePhone(patient.getHomePhone());
        builder.setHomeAddress(patient.getHomeAddress());
        builder.setDocumentType(patient.getDocumentType());
        builder.setIdCardNumber(patient.getIdCardNumber());
        builder.setNation(patient.getNation());
        builder.setNativePlace(patient.getNativePlace());
        builder.setEducationLevel(patient.getEducationLevel());
        builder.setOccupation(patient.getOccupation());
        builder.setMaritalStatus(patient.getMaritalStatus());
        builder.setEmergencyContactName(patient.getEmergencyContactName());
        builder.setEmergencyContactRelation(patient.getEmergencyContactRelation());
        builder.setEmergencyContactPhone(patient.getEmergencyContactPhone());
        builder.setEmergencyContactAddress(patient.getEmergencyContactAddress());
        builder.setPaymentMethod(patient.getPaymentMethod());
        builder.setInsuranceType(patient.getInsuranceType());
        builder.setInsuranceNumber(patient.getInsuranceNumber());
        builder.setMedicalCardNumber(patient.getMedicalCardNumber());
        builder.setIsVipPatient(patient.getIsVipPatient());

        BedConfig bed = bedConfigMap.get(patient.getHisBedNumber());
        builder.setBedNumberStr(bed == null ? "" : bed.getDisplayBedNumber());

        builder.setWristbandLocation(patient.getWristbandLocation());
        builder.setPatientPose(patient.getPatientPose());
        builder.setNursingCareLevel(patient.getNursingCareLevel());
        builder.setIllnessSeverityLevel(patient.getIllnessSeverityLevel());
        builder.setDietType(patient.getDietType());
        builder.setIsolationPrecaution(patient.getIsolationPrecaution());
        builder.setChiefComplaint(patient.getChiefComplaint());

        // 医护姓名按相应的账号类型查询。
        builder.setAttendingDoctorId(patient.getAttendingDoctorId());
        builder.setAttendingDoctorName(patient.getAttendingDoctorId() != null
            ? userQuery.getNameByAutoId(patient.getAttendingDoctorId()) : "");
        builder.setPrimaryCareDoctorId(patient.getPrimaryCareDoctorId());
        builder.setPrimaryCareDoctorName(patient.getPrimaryCareDoctorId() != null
            ? userQuery.getNameByAccountId(patient.getPrimaryCareDoctorId()) : "");
        builder.setAdmittingDoctorId(patient.getAdmittingDoctorId());
        builder.setAdmittingDoctorName(patient.getAdmittingDoctorId() != null
            ? userQuery.getNameByAutoId(patient.getAdmittingDoctorId()) : "");
        builder.setResponsibleNurseId(patient.getResponsibleNurseId());
        builder.setResponsibleNurseName(patient.getResponsibleNurseId() != null
            ? userQuery.getNameByAutoId(patient.getResponsibleNurseId()) : "");

        builder.setDiagnosis(patient.getDiagnosis());

        builder.setAdmissionStatus(patient.getAdmissionStatus());

        builder.setFromDeptName(patient.getFromDeptName());
        builder.addAllAdmissionType(admissionTypeValues(patient));
        builder.setIsPlannedAdmission(patient.getIsPlannedAdmission());
        builder.setUnplannedAdmissionReason(patient.getUnplannedAdmissionReason());
        builder.setAdmissionTimeIso8601(
            TimeUtils.toIso8601String(patient.getAdmissionTime(), "UTC"));

        builder.setDischargeType(patient.getDischargeType());
        builder.setDeathTimeIso8601(TimeUtils.toIso8601String(patient.getDeathTime(), "UTC"));
        builder.setHisDischargeTimeIso8601(
            TimeUtils.toIso8601String(patient.getHisDischargeTime(), "UTC"));
        builder.setToDeptName(patient.getToDeptName());
        builder.setDischargeTimeIso8601(
            TimeUtils.toIso8601String(patient.getDischargeTime(), "UTC"));
        builder.setDischargeDiagnosis(
            StrUtils.getStringOrDefault(patient.getDischargeDiagnosis(), ""));

        return builder.build();
    }

    PatientRecord toPatientRecordEntity(NewPatientReq req, String hisBedNumber) {
        PatientRecord patient = new PatientRecord();
        patient.setIcuName(req.getIcuName());
        patient.setHisMrn(req.getHisMrn());
        patient.setHisBedNumber(hisBedNumber);
        patient.setIcuGender(req.getIcuGender());
        patient.setIcuDateOfBirth(TimeUtils.fromIso8601String(req.getIcuDateOfBirth(), "UTC"));
        patient.setAdmissionTime(TimeUtils.fromIso8601String(req.getAdmissionTime(), "UTC"));
        setAdmissionTypeValues(req.getAdmissionTypeList(), patient);
        patient.setFromDeptName(req.getFromDeptName());
        patient.setPrimaryCareDoctorId(req.getPrimaryCareDoctorId());
        patient.setIsPlannedAdmission(req.getIsPlannedAdmission() > 0);
        patient.setUnplannedAdmissionReason(req.getUnplannedAdmissionReason());
        patient.setDiagnosis(req.getDiagnosis());
        patient.setHisPatientId(req.getHisPid());
        patient.setDeptId(req.getDeptId());
        return patient;
    }

    void toPatientRecordEntity(
        PatientRecordPB patientInfo, DischargeTimes times, /* OUT */ PatientRecord patient
    ) {
        patient.setHisAdmissionTime(
            TimeUtils.fromIso8601String(patientInfo.getHisAdmissionTimeIso8601(), "UTC"));
        patient.setHisAdmissionDiagnosis(emptyToNull(patientInfo.getHisAdmissionDiagnosis()));

        patient.setIcuDateOfBirth(
            TimeUtils.fromIso8601String(patientInfo.getIcuDateOfBirthIso8601(), "UTC"));
        patient.setHeight(patientInfo.getHeight() != 0.0f ? patientInfo.getHeight() : null);
        patient.setWeight(patientInfo.getWeight() != 0.0f ? patientInfo.getWeight() : null);
        patient.setWeightType(emptyToNull(patientInfo.getWeightType()));
        patient.setWeightIbw(emptyToNull(patientInfo.getWeightIbw()));

        patient.setBloodType(patientEnums.getBloodTypeName(patientInfo.getBloodType()));
        patient.setBloodRh(patientEnums.getBloodRhName(patientInfo.getBloodRh()));
        patient.setBsa(patientInfo.getBsa() != 0.0f ? patientInfo.getBsa() : null);
        patient.setPastMedicalHistory(emptyToNull(patientInfo.getPastMedicalHistory()));
        patient.setAllergies(emptyToNull(patientInfo.getAllergies()));
        patient.setPhone(emptyToNull(patientInfo.getPhone()));
        patient.setHomePhone(emptyToNull(patientInfo.getHomePhone()));
        patient.setHomeAddress(emptyToNull(patientInfo.getHomeAddress()));
        patient.setDocumentType(emptyToNull(patientInfo.getDocumentType()));
        patient.setIdCardNumber(emptyToNull(patientInfo.getIdCardNumber()));
        patient.setNation(emptyToNull(patientInfo.getNation()));
        patient.setNativePlace(emptyToNull(patientInfo.getNativePlace()));
        int educationLevel = patientInfo.getEducationLevel();
        patient.setEducationLevel(educationLevel == 0 ? 1 : educationLevel);
        patient.setOccupation(emptyToNull(patientInfo.getOccupation()));
        int maritalStatus = patientInfo.getMaritalStatus();
        patient.setMaritalStatus(maritalStatus == 0 ? null : maritalStatus);
        patient.setEmergencyContactName(emptyToNull(patientInfo.getEmergencyContactName()));
        patient.setEmergencyContactRelation(emptyToNull(patientInfo.getEmergencyContactRelation()));
        patient.setEmergencyContactPhone(emptyToNull(patientInfo.getEmergencyContactPhone()));
        patient.setEmergencyContactAddress(emptyToNull(patientInfo.getEmergencyContactAddress()));
        patient.setPaymentMethod(emptyToNull(patientInfo.getPaymentMethod()));
        patient.setInsuranceType(emptyToNull(patientInfo.getInsuranceType()));
        patient.setInsuranceNumber(emptyToNull(patientInfo.getInsuranceNumber()));
        patient.setMedicalCardNumber(emptyToNull(patientInfo.getMedicalCardNumber()));
        patient.setIsVipPatient(patientInfo.getIsVipPatient());
        patient.setWristbandLocation(emptyToNull(patientInfo.getWristbandLocation()));
        patient.setPatientPose(emptyToNull(patientInfo.getPatientPose()));
        patient.setNursingCareLevel(emptyToNull(patientInfo.getNursingCareLevel()));
        patient.setIllnessSeverityLevel(emptyToNull(patientInfo.getIllnessSeverityLevel()));
        patient.setDietType(emptyToNull(patientInfo.getDietType()));
        patient.setIsolationPrecaution(emptyToNull(patientInfo.getIsolationPrecaution()));
        patient.setChiefComplaint(emptyToNull(patientInfo.getChiefComplaint()));

        // 更新医护人员标识。
        patient.setAttendingDoctorId(emptyToNull(patientInfo.getAttendingDoctorId()));
        patient.setPrimaryCareDoctorId(emptyToNull(patientInfo.getPrimaryCareDoctorId()));
        patient.setAdmittingDoctorId(emptyToNull(patientInfo.getAdmittingDoctorId()));
        patient.setResponsibleNurseId(emptyToNull(patientInfo.getResponsibleNurseId()));

        patient.setDiagnosis(emptyToNull(patientInfo.getDiagnosis()));

        patient.setFromDeptName(emptyToNull(patientInfo.getFromDeptName()));
        setAdmissionTypeValues(patientInfo.getAdmissionTypeList(), patient);
        patient.setIsPlannedAdmission(patientInfo.getIsPlannedAdmission());
        patient.setUnplannedAdmissionReason(emptyToNull(patientInfo.getUnplannedAdmissionReason()));
        patient.setAdmissionTime(
            TimeUtils.fromIso8601String(patientInfo.getAdmissionTimeIso8601(), "UTC"));

        int dischargeType = patientInfo.getDischargeType();
        patient.setDischargeType(dischargeType == 0 ? null : dischargeType);
        patient.setDeathTime(times.deathTimeUtc());
        patient.setHisDischargeTime(times.hisDischargeTimeUtc());
        patient.setToDeptName(emptyToNull(patientInfo.getToDeptName()));
        patient.setDischargeTime(times.dischargeTimeUtc());
        patient.setDischargeDiagnosis(emptyToNull(patientInfo.getDischargeDiagnosis()));
    }

    static DischargeTimes toDischargeTimes(PatientRecordPB info) {
        return new DischargeTimes(
            TimeUtils.fromIso8601String(info.getDischargeTimeIso8601(), "UTC"),
            TimeUtils.fromIso8601String(info.getDeathTimeIso8601(), "UTC"),
            TimeUtils.fromIso8601String(info.getHisDischargeTimeIso8601(), "UTC"));
    }

    static DiagnosisHistoryPB toDiagnosisHistoryPB(DiagnosisHistory history, String zoneId) {
        return DiagnosisHistoryPB.newBuilder()
            .setId(history.getId())
            .setDiagnosisTimeIso8601(TimeUtils.toIso8601String(history.getDiagnosisTime(), zoneId))
            .setDiagnosisDoctorId(history.getDiagnosisAccountId())
            .setDiagnosis(StrUtils.getStringOrDefault(history.getDiagnosis(), ""))
            .setDiagnosisCode(StrUtils.getStringOrDefault(history.getDiagnosisCode(), ""))
            .build();
    }

    static void setAdmissionTypeValues(
        List<Integer> admissionTypeValues, /* OUT */ PatientRecord patient
    ) {
        List<Integer> validAdmissionTypes = new ArrayList<>();
        for (Integer admissionType : admissionTypeValues) {
            if (admissionType != null && admissionType > 0) {
                validAdmissionTypes.add(admissionType);
            }
        }
        patient.setAdmissionTypes(admissionTypeValuesToString(validAdmissionTypes));
        patient.setAdmissionType(validAdmissionTypes.isEmpty() ? null : validAdmissionTypes.get(0));
    }

    private static String admissionTypeValuesToString(List<Integer> admissionTypeValues) {
        if (admissionTypeValues == null || admissionTypeValues.isEmpty()) return null;

        StringJoiner joiner = new StringJoiner(",");
        for (Integer admissionType : admissionTypeValues) {
            joiner.add(String.valueOf(admissionType));
        }
        return joiner.toString();
    }

    public static List<Integer> admissionTypeValues(PatientRecord patient) {
        List<Integer> admissionTypes = parseAdmissionTypes(patient.getAdmissionTypes());
        if (!admissionTypes.isEmpty()) return admissionTypes;

        Integer legacyAdmissionType = patient.getAdmissionTypeRaw();
        if (legacyAdmissionType == null || legacyAdmissionType == 0) return Collections.emptyList();
        return List.of(legacyAdmissionType);
    }

    private static List<Integer> parseAdmissionTypes(String admissionTypes) {
        if (StrUtils.isBlank(admissionTypes)) return Collections.emptyList();

        String[] tokens = admissionTypes.split(",");
        List<Integer> parsed = new ArrayList<>();
        for (String token : tokens) {
            String trimmedToken = token.trim();
            if (trimmedToken.isEmpty()) return Collections.emptyList();
            try {
                parsed.add(Integer.parseInt(trimmedToken));
            } catch (NumberFormatException e) {
                return Collections.emptyList();
            }
        }
        return parsed.isEmpty() ? Collections.emptyList() : parsed;
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    record DischargeTimes(
        LocalDateTime dischargeTimeUtc, LocalDateTime deathTimeUtc,
        LocalDateTime hisDischargeTimeUtc
    ) {}

    private final PatientEnums patientEnums;
    private final UserQuery userQuery;
}
