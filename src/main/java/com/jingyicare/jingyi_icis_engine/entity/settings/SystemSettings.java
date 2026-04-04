package com.jingyicare.jingyi_icis_engine.entity.settings;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "system_settings")
public class SystemSettings {
    @Id
    @Column(name = "function_id", nullable = false)
    private Integer functionId;

    @Column(name = "function_name")
    private String functionName;

    @Lob
    @Column(name = "settings_pb", nullable = false, columnDefinition = "TEXT")
    private String settingsPb;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", nullable = false)
    private String modifiedBy;
}
