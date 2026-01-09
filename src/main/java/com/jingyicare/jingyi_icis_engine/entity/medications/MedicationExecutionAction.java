package com.jingyicare.jingyi_icis_engine.entity.medications;

import jakarta.persistence.*;
import java.time.LocalDateTime;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "medication_execution_actions", indexes = {
    @Index(name = "idx_medication_execution_actions_merid_id", columnList = "medication_execution_record_id, id")
})
public class MedicationExecutionAction {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "medication_execution_record_id", nullable = false)
    private Long medicationExecutionRecordId;  // 执行记录的id

    @Column(name = "create_account_id", nullable = false)
    private String createAccountId;  // 创建用户

    @Column(name = "create_account_name")
    private String createAccountName;  // 创建用户姓名

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;  // 创建时间

    @Column(name = "action_type", nullable = false)
    private Integer actionType;  // 过程的类型

    // Get method for administrationRate with a default value of 0.0 if null
    public Double getAdministrationRate() {
        return administrationRate == null ? 0.0 : administrationRate;
    }
    @Column(name = "administration_rate")
    private Double administrationRate;  // 持续用药的药速，单位：ml/h

    public String getMedicationRate() {
        return medicationRate == null ? "" : medicationRate;
    }
    @Column(name = "medication_rate", columnDefinition = "TEXT")
    private String medicationRate;  // 药速，药速，DosageGroupExtPB的实例序列化后的base64编码，只有当action_type为开始/调速时有效

    // Get method for intakeVolMl with a default value of 0.0 if null
    public Double getIntakeVolMl() {
        return intakeVolMl == null ? 0.0 : intakeVolMl;
    }
    @Column(name = "intake_vol_ml")
    private Double intakeVolMl;  // 单次用药的药量，单位: ml

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否删除

    public String getDeleteAccountId() {
        return deleteAccountId == null ? "" : deleteAccountId;
    }
    @Column(name = "delete_account_id")
    private String deleteAccountId;  // 删除本条记录的用户

    @Column(name = "delete_time")
    private LocalDateTime deleteTime;  // 删除时间

    @Column(name = "modified_at")
    private LocalDateTime modifiedAt;  // 修改时间
}