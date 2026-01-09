package com.jingyicare.jingyi_icis_engine.entity.medications;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "medication_execution_records", indexes = {
    @Index(name = "idx_medication_execution_records_his_order_group_id_plan_time",
        columnList = "his_order_group_id, plan_time")
})
public class MedicationExecutionRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "medication_order_group_id", nullable = false)
    private Long medicationOrderGroupId;

    @Column(name = "his_order_group_id", nullable = false)
    private String hisOrderGroupId;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "plan_time", nullable = false)
    private LocalDateTime planTime;

    public Boolean getIsPersonalMedications() {
        return isPersonalMedications == null ? false : isPersonalMedications;
    }
    @Column(name = "is_personal_medications")
    private Boolean isPersonalMedications;

    public Boolean getShouldCalculateRate() {
        return shouldCalculateRate == null ? false : shouldCalculateRate;
    }
    @Column(name = "should_calculate_rate")
    private Boolean shouldCalculateRate;

    @Column(name = "start_time")
    private LocalDateTime startTime;

    @Column(name = "end_time")
    private LocalDateTime endTime;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    public String getDeleteAccountId() {
        return deleteAccountId == null ? "" : deleteAccountId;
    }

    @Column(name = "delete_account_id")
    private String deleteAccountId;

    @Column(name = "delete_time")
    private LocalDateTime deleteTime;

    public String getDeleteReason() {
        return deleteReason == null ? "" : deleteReason;
    }

    @Column(name = "delete_reason")
    private String deleteReason;

    @Column(name = "user_touched", nullable = false)
    private Boolean userTouched;

    public String getBarCode() {
        return barCode == null ? "" : barCode;
    }
    @Column(name = "bar_code")
    private String barCode;

    public String getHisExecuteId() {
        return hisExecuteId == null ? "" : hisExecuteId;
    }
    @Column(name = "his_execute_id")
    private String hisExecuteId;

    public String getMedicationDosageGroup() {
        return medicationDosageGroup == null ? "" : medicationDosageGroup;
    }
    @Column(name = "medication_dosage_group")
    private String medicationDosageGroup;

    public String getAdministrationRouteCode() {
        return administrationRouteCode == null ? "" : administrationRouteCode;
    }
    @Column(name = "administration_route_code")
    private String administrationRouteCode;

    public String getAdministrationRouteName() {
        return administrationRouteName == null ? "" : administrationRouteName;
    }
    @Column(name = "administration_route_name")
    private String administrationRouteName;

    
    @Column(name = "is_continuous", nullable = false)
    private Boolean isContinuous;

    public Integer getMedicationChannel() {
        return medicationChannel == null ? 0 : medicationChannel;
    }
    @Column(name = "medication_channel")
    private Integer medicationChannel;

    public String getMedicationChannelName() {
        return medicationChannelName == null ? "" : medicationChannelName;
    }
    @Column(name = "medication_channel_name")
    private String medicationChannelName;

    public String getComments() {
        return comments == null ? "" : comments;
    }
    @Column(name = "comments")
    private String comments;

    @Column(name = "create_account_id", nullable = false)
    private String createAccountId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
