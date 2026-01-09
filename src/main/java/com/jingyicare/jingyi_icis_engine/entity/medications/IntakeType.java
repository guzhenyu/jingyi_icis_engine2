package com.jingyicare.jingyi_icis_engine.entity.medications;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "intake_types")
public class IntakeType {
    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "name")
    private String name;

    @Column(name = "monitoring_param_code")
    private String monitoringParamCode;
}