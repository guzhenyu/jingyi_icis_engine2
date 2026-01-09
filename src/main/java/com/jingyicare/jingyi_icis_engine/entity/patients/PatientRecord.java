package com.jingyicare.jingyi_icis_engine.entity.patients;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.Data;
import lombok.NoArgsConstructor;

import com.jingyicare.jingyi_icis_engine.utils.*;

@Data
@NoArgsConstructor
@Entity
@Table(name = "patient_records", indexes = {
    @Index(name = "idx_patient_records_his_mrn", columnList = "his_mrn")
})
public class PatientRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id; // 自增主键

    public String getHisMrn() {
        return hisMrn == null ? "" : hisMrn;
    }
    @Column(name = "his_mrn")
    private String hisMrn; // 病历号, Medical Record Number

    public String getHisPatientId() {
        return hisPatientId == null ? "" : hisPatientId;
    }
    @Column(name = "his_patient_id")
    private String hisPatientId; // HIS系统中的病人记录表主键ID

    public String getHisIndexId() {
        return hisIndexId == null ? "" : hisIndexId;
    }
    @Column(name = "his_index_id")
    private String hisIndexId; // 病案首页ID

    public String getHisPatientSerialNumber() {
        return hisPatientSerialNumber == null ? "" : hisPatientSerialNumber;
    }
    @Column(name = "his_patient_serial_number")
    private String hisPatientSerialNumber; // HIS系统中的病人记录表中的病人流水号

    public Integer getHisAdmissionCount() {
        return hisAdmissionCount == null ? 0 : hisAdmissionCount;
    }
    @Column(name = "his_admission_count")
    private Integer hisAdmissionCount; // HIS系统中的病人入院次数

    @Column(name = "his_admission_time")
    private LocalDateTime hisAdmissionTime; // HIS系统中的病人入院时间

    public String getHisAdmissionDiagnosis() {
        return hisAdmissionDiagnosis == null ? "" : hisAdmissionDiagnosis;
    }
    @Column(name = "his_admission_diagnosis")
    private String hisAdmissionDiagnosis; // HIS系统中的病人入院诊断

    public String getHisAdmissionDiagnosisCode() {
        return hisAdmissionDiagnosisCode == null ? "" : hisAdmissionDiagnosisCode;
    }
    @Column(name = "his_admission_diagnosis_code")
    private String hisAdmissionDiagnosisCode; // HIS系统中的病人入院诊断编码

    public String getHisBedNumber() {
        return hisBedNumber == null ? "" : hisBedNumber;
    }
    @Column(name = "his_bed_number")
    private String hisBedNumber; // HIS系统中的病人床位号

    public Boolean getIcuManualEntry() {
        return icuManualEntry == null ? false : icuManualEntry;
    }
    @Column(name = "icu_manual_entry")
    private Boolean icuManualEntry; // 本条数据是否由手动添加

    public String getIcuManualEntryAccountId() {
        return icuManualEntryAccountId == null ? "" : icuManualEntryAccountId;
    }
    @Column(name = "icu_manual_entry_account_id")
    private String icuManualEntryAccountId; // 手动添加人

    public String getIcuName() {
        return icuName == null ? "" : icuName;
    }
    @Column(name = "icu_name")
    private String icuName; // 姓名。该字段默认由HIS系统提供；断网等特殊情况下，可以由ICU系统提供，但是需要在HIS系统恢复后和HIS保持同步

    public Integer getIcuGender() {
        return icuGender == null ? 1 : icuGender; // 1 - 男
    }
    @Column(name = "icu_gender")
    private Integer icuGender; // 性别。该字段默认由HIS系统提供；断网等特殊情况下，可以由ICU系统提供，但是需要在HIS系统恢复后和HIS保持同步

    public Integer getAge() {
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (icuDateOfBirth == null) {
            return 0;
        } else {
            LocalDateTime yearBirthday = icuDateOfBirth.withYear(nowUtc.getYear());
            if (nowUtc.isAfter(yearBirthday)) {
                return nowUtc.getYear() - icuDateOfBirth.getYear() + 1;
            } else {
                return nowUtc.getYear() - icuDateOfBirth.getYear();
            }
        }
    }

    @Column(name = "icu_date_of_birth")
    private LocalDateTime icuDateOfBirth; // 生日，儿科重症需要展示TIMESTAMP，DATE不够。该字段默认由HIS系统提供；断网等特殊情况下，可以由ICU系统提供，但是需要在HIS系统恢复后和HIS保持同步

    public Float getHeight() {
        return height == null ? 0.0f : height;
    }
    @Column(name = "height")
    private Float height; // 身高，m

    public Float getWeight() {
        return weight == null ? 0.0f : weight;
    }
    @Column(name = "weight")
    private Float weight; // 体重，kg

    public String getWeightType() {
        return weightType == null ? "" : weightType;
    }
    @Column(name = "weight_type")
    private String weightType; // 体重类型，如平车、卧床、轮椅等；如果为空，表示真实体重

    public String getWeightIbw() {
        return weightIbw == null ? "" : weightIbw;
    }
    @Column(name = "weight_ibw")
    private String weightIbw; // 理想体重(IBW)：一个人在最佳健康状态下的理想体重范围

    public String getBloodType() {
        return bloodType == null ? "" : bloodType;
    }
    @Column(name = "blood_type")
    private String bloodType; // 血型, A, B, O, AB

    public String getBloodRh() {
        return bloodRh == null ? "" : bloodRh;
    }
    @Column(name = "blood_rh")
    private String bloodRh; // 血型Rh, +, -

    public Float getBsa() {
        return bsa == null ? 0.0f : bsa;
    }
    @Column(name = "bsa")
    private Float bsa; // 体表面积，m2

    public String getPastMedicalHistory() {
        return pastMedicalHistory == null ? "" : pastMedicalHistory;
    }
    @Column(name = "past_medical_history")
    private String pastMedicalHistory; // 既往病史，手术史、外伤史、家族史等

    public String getAllergies() {
        return allergies == null ? "" : allergies;
    }
    @Column(name = "allergies")
    private String allergies; // 过敏史

    public String getPhone() {
        return phone == null ? "" : phone;
    }
    @Column(name = "phone")
    private String phone; // 电话

    public String getHomePhone() {
        return homePhone == null ? "" : homePhone;
    }
    @Column(name = "home_phone")
    private String homePhone; // 家庭电话

    public String getHomeAddress() {
        return homeAddress == null ? "" : homeAddress;
    }
    @Column(name = "home_address")
    private String homeAddress; // 家庭地址

    public String getDocumentType() {
        return documentType == null ? "" : documentType;
    }
    @Column(name = "document_type")
    private String documentType; // 证件类型, 身份证、护照、军官证、驾驶证等

    public String getIdCardNumber() {
        return idCardNumber == null ? "" : idCardNumber;
    }
    @Column(name = "id_card_number")
    private String idCardNumber; // 身份证号

    public String getNation() {
        return nation == null ? "" : nation;
    }
    @Column(name = "nation")
    private String nation; // 民族

    public String getNativePlace() {
        return nativePlace == null ? "" : nativePlace;
    }
    @Column(name = "native_place")
    private String nativePlace; // 籍贯

    public Integer getEducationLevel() {
        return educationLevel == null ? 1 : educationLevel;
    }
    @Column(name = "education_level")
    private Integer educationLevel; // 学历

    public String getOccupation() {
        return occupation == null ? "" : occupation;
    }
    @Column(name = "occupation")
    private String occupation; // 职业

    public Integer getMaritalStatus() {
        return maritalStatus == null ? 1 : maritalStatus; // 1 - 单身
    }
    @Column(name = "marital_status")
    private Integer maritalStatus; // 婚姻状况

    public String getEmergencyContactName() {
        return emergencyContactName == null ? "" : emergencyContactName;
    }
    @Column(name = "emergency_contact_name")
    private String emergencyContactName; // 紧急联系人姓名

    public String getEmergencyContactRelation() {
        return emergencyContactRelation == null ? "" : emergencyContactRelation;
    }
    @Column(name = "emergency_contact_relation")
    private String emergencyContactRelation; // 紧急联系人关系

    public String getEmergencyContactPhone() {
        return emergencyContactPhone == null ? "" : emergencyContactPhone;
    }
    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone; // 紧急联系人电话

    public String getEmergencyContactAddress() {
        return emergencyContactAddress == null ? "" : emergencyContactAddress;
    }
    @Column(name = "emergency_contact_address")
    private String emergencyContactAddress; // 紧急联系人地址

    public String getPaymentMethod() {
        return paymentMethod == null ? "" : paymentMethod;
    }
    @Column(name = "payment_method")
    private String paymentMethod; // 结算方式

    public String getInsuranceType() {
        return insuranceType == null ? "" : insuranceType;
    }
    @Column(name = "insurance_type")
    private String insuranceType; // 医保类型

    public String getInsuranceNumber() {
        return insuranceNumber == null ? "" : insuranceNumber;
    }
    @Column(name = "insurance_number")
    private String insuranceNumber; // 医保号

    public String getMedicalCardNumber() {
        return medicalCardNumber == null ? "" : medicalCardNumber;
    }
    @Column(name = "medical_card_number")
    private String medicalCardNumber; // 就诊卡号

    public Boolean getIsVipPatient() {
        return isVipPatient == null ? false : isVipPatient;
    }
    @Column(name = "is_vip_patient")
    private Boolean isVipPatient; // 是否VIP病人

    public String getWristbandLocation() {
        return wristbandLocation == null ? "" : wristbandLocation;
    }
    @Column(name = "wristband_location")
    private String wristbandLocation; // 腕带位置，左手、右手、左脚、右脚、其他

    public String getPatientPose() {
        return patientPose == null ? "" : patientPose;
    }
    @Column(name = "patient_pose")
    private String patientPose; // 病人体位, 卧床/平车/轮椅/坐椅/半卧

    public String getNursingCareLevel() {
        return nursingCareLevel == null ? "" : nursingCareLevel;
    }
    @Column(name = "nursing_care_level")
    private String nursingCareLevel; // 护理等级

    public String getIllnessSeverityLevel() {
        return illnessSeverityLevel == null ? "" : illnessSeverityLevel;
    }
    @Column(name = "illness_severity_level")
    private String illnessSeverityLevel; // 病情分级

    public String getDietType() {
        return dietType == null ? "" : dietType;
    }
    @Column(name = "diet_type")
    private String dietType; // 饮食类型

    public String getIsolationPrecaution() {
        return isolationPrecaution == null ? "" : isolationPrecaution;
    }
    @Column(name = "isolation_precaution")
    private String isolationPrecaution; // 隔离措施

    public String getChiefComplaint() {
        return chiefComplaint == null ? "" : chiefComplaint;
    }
    @Column(name = "chief_complaint")
    private String chiefComplaint; // 主诉

    public String getDeptId() {
        return deptId == null ? "" : deptId;
    }
    @Column(name = "dept_id")
    private String deptId; // 科室ID

    public String getDeptName() {
        return deptName == null ? "" : deptName;
    }
    @Column(name = "dept_name")
    private String deptName; // 科室名称

    public String getWardCode() {
        return wardCode == null ? "" : wardCode;
    }
    @Column(name = "ward_code")
    private String wardCode; // 病区编码

    public String getWardName() {
        return wardName == null ? "" : wardName;
    }
    @Column(name = "ward_name")
    private String wardName; // 病区名称

    public String getAttendingDoctorId() {
        return attendingDoctorId == null ? "" : attendingDoctorId;
    }
    @Column(name = "attending_doctor_id")
    private String attendingDoctorId; // 主治医生ID

    public String getPrimaryCareDoctorId() {
        return primaryCareDoctorId == null ? "" : primaryCareDoctorId;
    }
    @Column(name = "primary_care_doctor_id")
    private String primaryCareDoctorId; // 管床医生ID

    public String getAdmittingDoctorId() {
        return admittingDoctorId == null ? "" : admittingDoctorId;
    }
    @Column(name = "admitting_doctor_id")
    private String admittingDoctorId; // 收治医生ID

    public String getResponsibleNurseId() {
        return responsibleNurseId == null ? "" : responsibleNurseId;
    }
    @Column(name = "responsible_nurse_id")
    private String responsibleNurseId; // 责任护士ID

    public String getAdmissionSourceDeptName() {
        return admissionSourceDeptName == null ? "" : admissionSourceDeptName;
    }
    @Column(name = "admission_source_dept_name")
    private String admissionSourceDeptName; // 入科来源科室名称，也可以是护士手动输入

    public String getAdmissionSourceDeptId() {
        return admissionSourceDeptId == null ? "" : admissionSourceDeptId;
    }
    @Column(name = "admission_source_dept_id")
    private String admissionSourceDeptId; // 入科来源科室ID，如果入科来源科室名称不是HIS系统中的名称，该id置空

    public Integer getAdmissionType() {
        return admissionType == null ? 0 : admissionType;
    }
    @Column(name = "admission_type")
    private Integer admissionType; // 入科类型：入院、转入、手术、抢救、重症、外院、病危等

    public Boolean getIsPlannedAdmission() {
        return isPlannedAdmission == null ? false : isPlannedAdmission;
    }
    @Column(name = "is_planned_admission")
    private Boolean isPlannedAdmission; // 是否计划入科

    public String getUnplannedAdmissionReason() {
        return unplannedAdmissionReason == null ? "" : unplannedAdmissionReason;
    }
    @Column(name = "unplanned_admission_reason")
    private String unplannedAdmissionReason; // 非计划入科原因

    public Integer getAdmissionStatusRaw() { return admissionStatus; }
    public Integer getAdmissionStatus() {
        return admissionStatus == null ? 0 : admissionStatus;
    }
    @Column(name = "admission_status")
    private Integer admissionStatus; // 入科状态

    @Column(name = "admission_time")
    private LocalDateTime admissionTime; // 入科时间

    @Column(name = "admission_edit_time")
    private LocalDateTime admissionEditTime; // 入科时间修改时间

    public String getAdmittingAccountId() {
        return admittingAccountId == null ? "" : admittingAccountId;
    }
    @Column(name = "admitting_account_id")
    private String admittingAccountId; // 入科操作员

    public String getDiagnosis() {
        return diagnosis == null ? "" : diagnosis;
    }
    @Column(name = "diagnosis")
    private String diagnosis; // ICU诊断  入科诊断

    public String getDiagnosisTcm() {
        return diagnosisTcm == null ? "" : diagnosisTcm;
    }
    @Column(name = "diagnosis_tcm")
    private String diagnosisTcm; // 中医诊断, Traditional Chinese Medicine

    public String getDiagnosisType() {
        return diagnosisType == null ? "" : diagnosisType;
    }
    @Column(name = "diagnosis_type")
    private String diagnosisType; // 诊断类型，较少使用，reserved

    @Column(name = "surgery_operation")
    private String surgeryOperation; // 手术操作

    @Column(name = "surgery_operation_time")
    private LocalDateTime surgeryOperationTime; // 手术操作时间

    public Integer getDischargedType() {
        return dischargedType == null ? 0 : dischargedType;
    }
    @Column(name = "discharged_type")
    private Integer dischargedType; // 出科类型：转出、死亡、出院

    @Column(name = "discharged_death_time")
    private LocalDateTime dischargedDeathTime; // 死亡时间。当出科类型为*死亡*时，该字段有效

    @Column(name = "discharged_hospital_exit_time")
    private LocalDateTime dischargedHospitalExitTime; // 出院时间。当出科类型为*出院*时，该字段有效

    public String getDischargedDiagnosis() {
        return dischargedDiagnosis == null ? "" : dischargedDiagnosis;
    }
    @Column(name = "discharged_diagnosis")
    private String dischargedDiagnosis; // 出科诊断

    public String getDischargedDiagnosisCode() {
        return dischargedDiagnosisCode == null ? "" : dischargedDiagnosisCode;
    }
    @Column(name = "discharged_diagnosis_code")
    private String dischargedDiagnosisCode; // 出科诊断编码

    public String getDischargedDeptName() {
        return dischargedDeptName == null ? "" : dischargedDeptName;
    }
    @Column(name = "discharged_dept_name")
    private String dischargedDeptName; // 转出科室名称

    public String getDischargedDeptId() {
        return dischargedDeptId == null ? "" : dischargedDeptId;
    }
    @Column(name = "discharged_dept_id")
    private String dischargedDeptId; // 转出科室ID

    @Column(name = "discharge_time")
    private LocalDateTime dischargeTime; // 出科时间

    @Column(name = "discharge_edit_time")
    private LocalDateTime dischargeEditTime; // 出科时间修改时间

    public String getDischargingAccountId() {
        return dischargingAccountId == null ? "" : dischargingAccountId;
    }
    @Column(name = "discharging_account_id")
    private String dischargingAccountId; // 出科操作员

    @Column(name = "created_at")
    private LocalDateTime createdAt; // 重症系统中本条记录的创建时间
}

// todo(guzhenyu): 考虑用反射的方法来实现getValues，以精简代码，维持效率
// public List<String> getValues(List<String> cols) {
//     for (String col : cols) {
//         String colCamel = StrUtils.snakeToCamel(col);
//         try {
//             Field field = getClass().getDeclaredField(colCamel);
//             field.setAccessible(true);
//             Object value = field.get(this);
//         } catch (NoSuchFieldException | IllegalAccessException e) {
//         }
//     }
// }