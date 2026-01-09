package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "raw_bga_record_details")
public class RawBgaRecordDetail {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "record_id", nullable = false)
    private Long recordId;

    @Column(name = "monitoring_param_code", nullable = false)
    private String monitoringParamCode;

    @Column(name = "param_value_str", nullable = false)
    private String paramValueStr;
}