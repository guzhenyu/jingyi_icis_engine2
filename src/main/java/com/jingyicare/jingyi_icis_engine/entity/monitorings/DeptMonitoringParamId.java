package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.Embeddable;
import lombok.*;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Embeddable
public class DeptMonitoringParamId implements Serializable {
    private String code;  // 观察参数编码
    private String deptId;  // 科室ID
}