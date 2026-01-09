package com.jingyicare.jingyi_icis_engine.entity.medications;

import java.time.LocalDateTime;
import jakarta.persistence.*;

import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Entity
@Table(name = "medication_frequencies", indexes = {
    @Index(name = "idx_medication_frequencies_code_id", columnList = "code", unique = true),
})
public class MedicationFrequency {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "code")
    private String code;

    @Column(name = "name")
    private String name;

    @Lob
    @Column(name = "freq_spec", nullable = false, columnDefinition = "TEXT")
    private String freqSpec;

    @Column(name = "support_nursing_order", nullable = false)
    private Boolean supportNursingOrder;

    @Column(name = "is_deleted", nullable = false)
    private Boolean isDeleted;

    @Column(name = "deleted_by")
    private String deletedBy;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;
}
