package com.jingyicare.jingyi_icis_engine.entity.medications;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "medical_orders", indexes = {
    @Index(name = "idx_medical_orders_patient_id", columnList = "his_patient_id"),
    @Index(name = "idx_medical_orders_patient_id_group_id", columnList = "his_patient_id, group_id")
})
public class MedicalOrder {
    @Id
    @Column(name = "order_id", nullable = false)
    private String orderId;  // HIS医嘱的主键

    @Column(name = "his_patient_id", nullable = false)
    private String hisPatientId;  // HIS系统中的病人记录表主键ID

    @Column(name = "group_id")
    private String groupId;  // 同一组医嘱关联字段

    // Get method for hisMrn with a default value of an empty string if null
    public String getHisMrn() {
        return hisMrn == null ? "" : hisMrn;
    }
    @Column(name = "his_mrn")
    private String hisMrn;  // 病历号

    // Get method for patientName with a default value of an empty string if null
    public String getPatientName() {
        return patientName == null ? "" : patientName;
    }
    @Column(name = "patient_name")
    private String patientName;  // 病人姓名

    @Column(name = "ordering_doctor")
    private String orderingDoctor;  // 下嘱医生

    @Column(name = "ordering_doctor_id")
    private String orderingDoctorId;  // 下嘱医生id

    @Column(name = "deptId", nullable = false)
    private String deptId;  // 科室id/编码

    @Column(name = "order_type")
    private String orderType;  // 医嘱类型： 中药，西药，检验...

    @Column(name = "status")
    private String status;  // 医嘱状态：已开立，取消，停止...

    @Column(name = "order_time")
    private LocalDateTime orderTime;  // 下嘱时间

    @Column(name = "stop_time")
    private LocalDateTime stopTime;  // 停止时间

    @Column(name = "cancel_time")
    private LocalDateTime cancelTime;  // 作废时间

    @Column(name = "order_code")
    private String orderCode;  // 医嘱编码

    @Column(name = "order_name")
    private String orderName;  // 医嘱名称

    @Column(name = "spec")
    private String spec;  // 规格

    @Column(name = "dose")
    private Double dose;  // 剂量

    @Column(name = "dose_unit")
    private String doseUnit;  // 计量单位

    public String getRecommendSpeed() {
        return recommendSpeed == null ? "" : recommendSpeed;
    }
    @Column(name = "recommend_speed")
    private String recommendSpeed;  // 推荐速度

    // Get method for medicationNote with a default value of an empty string if null
    public String getMedicationNote() {
        return medicationNote == null ? "" : medicationNote;
    }
    @Column(name = "medication_note")
    private String medicationNote;  // 备注

    // Get method for medicationType with a default value of an empty string if null
    public String getMedicationType() {
        return medicationType == null ? "" : medicationType;
    }
    @Column(name = "medication_type")
    private String medicationType;  // 毒麻药、抗生素等

    @Column(name = "should_calculate_rate")
    private Boolean shouldCalculateRate;  // 是否需要计算药速 ug(药)/kg(体重)/min

    // Get method for paymentType with a default value of an empty string if null
    public String getPaymentType() {
        return paymentType == null ? "" : paymentType;
    }
    @Column(name = "payment_type")
    private String paymentType;  // 支付方式

    @Column(name = "order_duration_type")
    private Integer orderDurationType;  // 长期医嘱/临时医嘱

    @Column(name = "freq_code")
    private String freqCode;  // 频次编码

    @Column(name = "plan_time")
    private LocalDateTime planTime;  // 计划执行时间

    @Column(name = "first_day_exe_count")
    private Integer firstDayExeCount;  // 首日执行医嘱次数

    @Column(name = "administration_route_code")
    private String administrationRouteCode;  // 给药途径编码

    @Column(name = "administration_route_name")
    private String administrationRouteName;  // 给药途径名称

    // Get method for reviewer with a default value of an empty string if null
    public String getReviewer() {
        return reviewer == null ? "" : reviewer;
    }
    @Column(name = "reviewer")
    private String reviewer;  // 审核人

    // Get method for reviewerId with a default value of an empty string if null
    public String getReviewerId() {
        return reviewerId == null ? "" : reviewerId;
    }
    @Column(name = "reviewer_id")
    private String reviewerId;  // 审核人ID

    @Column(name = "review_time")
    private LocalDateTime reviewTime;  // 审核时间

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}