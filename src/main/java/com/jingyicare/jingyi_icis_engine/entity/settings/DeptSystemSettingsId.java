package com.jingyicare.jingyi_icis_engine.entity.settings;

import jakarta.persistence.*;
import lombok.*;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class DeptSystemSettingsId implements Serializable {
    @Column(name = "dept_id", nullable = false)
    private String deptId;

    @Column(name = "function_id", nullable = false)
    private Integer functionId;
}