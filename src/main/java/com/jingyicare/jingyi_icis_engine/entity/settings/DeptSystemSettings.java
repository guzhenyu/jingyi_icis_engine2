package com.jingyicare.jingyi_icis_engine.entity.settings;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dept_system_settings")
public class DeptSystemSettings {
    @EmbeddedId
    private DeptSystemSettingsId id;

    @Lob
    @Column(name = "settings_pb", nullable = false, columnDefinition = "TEXT")
    private String settingsPb;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", nullable = false)
    private String modifiedBy;
}