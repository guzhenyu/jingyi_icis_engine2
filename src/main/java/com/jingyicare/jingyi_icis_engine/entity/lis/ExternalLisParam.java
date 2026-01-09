package com.jingyicare.jingyi_icis_engine.entity.lis;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "external_lis_params")
public class ExternalLisParam {
    @Id
    @Column(name = "param_code")
    private String paramCode;

    @Column(name = "param_name", nullable = false)
    private String paramName;

    @Column(name = "type_pb")
    private String typePb;

    @Column(name = "danger_max")
    private String dangerMax;

    @Column(name = "danger_min")
    private String dangerMin;

    @Column(name = "display_order", nullable = false)
    private Integer displayOrder;
}