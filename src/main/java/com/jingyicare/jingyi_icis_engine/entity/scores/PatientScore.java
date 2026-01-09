package com.jingyicare.jingyi_icis_engine.entity.scores;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "patient_scores", indexes = {
    @Index(name = "idx_patient_scores_pid_score_group_code_effective_time", columnList = "pid, score_group_code, effective_time")
})
public class PatientScore {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "pid", nullable = false)
    private Long pid;  // 病人ID

    @Column(name = "score_group_code", nullable = false)
    private String scoreGroupCode;  // 评分类型编码

    @Column(name = "score", nullable = false, length = 1000)
    private String score;  // 将ScoreGroupPB序列化字符串并进行base64编码后的字符串

    @Column(name = "score_str", nullable = false)
    private String scoreStr;  // 评分的字符串表示

    @Column(name = "effective_time", nullable = false)
    private LocalDateTime effectiveTime;  // score的生效时间

    @Column(name = "note")
    private String note;  // 备注

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;  // 是否已删除

    @Column(name = "deleted_by")
    private String deletedBy;  // 删除人

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;  // 删除时间

    @Column(name = "modified_by")
    private String modifiedBy;  // 记录人

    public String getModifiedByAccountName() {
        if (modifiedByAccountName == null) {
            return modifiedBy;
        }
        return modifiedByAccountName;
    }
    @Column(name = "modified_by_account_name")
    private String modifiedByAccountName;  // 记录人的账号名

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;  // 记录的时间
}
