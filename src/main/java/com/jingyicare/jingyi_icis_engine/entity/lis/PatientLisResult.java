package com.jingyicare.jingyi_icis_engine.entity.lis;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_lis_results", indexes = {
        @Index(name = "idx_patient_lis_results_report_id_result_code", columnList = "report_id, external_param_code, auth_time")
})
public class PatientLisResult {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "report_id", nullable = false)
    private String reportId;

    @Column(name = "external_param_code", nullable = false)
    private String externalParamCode;

    @Column(name = "external_param_name")
    private String externalParamName;

    @Column(name = "unit")
    private String unit;

    @Column(name = "result_str")
    private String resultStr;

    @Column(name = "auth_time")
    private LocalDateTime authTime;

    @Column(name = "auth_doctor")
    private String authDoctor;

    @Column(name = "notes")
    private String notes;

    @Column(name = "alarm_flag")
    private String alarmFlag;

    @Column(name = "danger_flag")
    private String dangerFlag;

    @Column(name = "normal_min_str")
    private String normalMinStr;

    @Column(name = "normal_max_str")
    private String normalMaxStr;

    @Column(name = "danger_min_str")
    private String dangerMinStr;

    @Column(name = "danger_max_str")
    private String dangerMaxStr;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_by_account_name")
    private String deletedByAccountName;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @Column(name = "modified_by")
    private String modifiedBy;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;
}
