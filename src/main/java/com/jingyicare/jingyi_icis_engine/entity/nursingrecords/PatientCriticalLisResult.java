package com.jingyicare.jingyi_icis_engine.entity.nursingrecords;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_critical_lis_results", indexes = {
    @Index(name = "idx_patient_critical_lis_results_pid_auth_time", columnList = "pid, auth_time")
})
public class PatientCriticalLisResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "pid", nullable = false)
    private Long pid;

    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "report_id")
    private String reportId;

    @Column(name = "auth_time", nullable = false)
    private LocalDateTime authTime;

    @Column(name = "order_doctor", nullable = false)
    private String orderDoctor;

    @Column(name = "order_doctor_id")
    private String orderDoctorId;

    @Column(name = "lis_item_name", nullable = false)
    private String lisItemName;

    @Column(name = "lis_item_short_name")
    private String lisItemShortName;

    @Column(name = "lis_item_code")
    private String lisItemCode;

    @Column(name = "patient_lis_result_id")
    private Long patientLisResultId;

    @Column(name = "external_param_code", nullable = false)
    private String externalParamCode;

    @Column(name = "external_param_name", nullable = false)
    private String externalParamName;

    @Column(name = "unit")
    private String unit;

    @Column(name = "result_str", nullable = false)
    private String resultStr;

    @Column(name = "normal_min_str")
    private String normalMinStr;

    @Column(name = "normal_max_str")
    private String normalMaxStr;

    @Column(name = "alarm_flag")
    private String alarmFlag;

    @Column(name = "danger_flag")
    private String dangerFlag;

    @Column(name = "notes")
    private String notes;

    @Column(name = "handling_id")
    private Integer handlingId;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "modified_by")
    private String modifiedBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;
}