package com.jingyicare.jingyi_icis_engine.entity.monitorings;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "raw_bga_records", indexes = {
    @Index(name = "idx_raw_bga_records_mrn", columnList = "mrn_bednum")
})
public class RawBgaRecord {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "mrn_bednum", nullable = false)
    private String mrnBednum;

    @Column(name = "bga_category_id", nullable = false)
    private Integer bgaCategoryId;

    @Column(name = "effective_time", nullable = false)
    private LocalDateTime effectiveTime;
}