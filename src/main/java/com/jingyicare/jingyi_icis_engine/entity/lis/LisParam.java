package com.jingyicare.jingyi_icis_engine.entity.lis;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "lis_params")
public class LisParam {
    @Id
    @Column(name = "param_code")
    private String paramCode;

    @Column(name = "param_name", nullable = false)
    private String paramName;

    @Column(name = "param_description")
    private String paramDescription;

    @Column(name = "external_param_code")
    private String externalParamCode;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;
}