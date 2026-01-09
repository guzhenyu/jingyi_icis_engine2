package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "dept_monitoring_params")
public class DeptMonitoringParam {
    @EmbeddedId
    private DeptMonitoringParamId id; // 嵌入式主键

    @Column(name = "type_pb", nullable = false, length = 2000)
    private String typePb; // 将ValueMeta序列化并用base64编码的字符串
}