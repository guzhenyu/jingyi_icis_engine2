package com.jingyicare.jingyi_icis_engine.entity.therapies;

import java.time.LocalDateTime;

import jakarta.persistence.*;
import lombok.*;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
@Entity
@Table(name = "sepsis_and_septic_shock_bundles", indexes = {
    @Index(name = "idx_sepsis_bundles_pid", columnList = "pid", unique = true)
})
public class SepsisAndSepticShockBundle {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "pid", nullable = false)
    private Long pid;

    @Column(name = "t0")
    private LocalDateTime t0;

    @Column(name = "need_bundle", nullable = false)
    private Boolean needBundle;

    @Column(name = "no_bundle_reason", columnDefinition = "TEXT")
    private String noBundleReason;

    @Column(name = "h1_lactate_initial")
    private Boolean h1LactateInitial;

    @Column(name = "h1_lactate_initial_time")
    private LocalDateTime h1LactateInitialTime;

    @Column(name = "h1_lactate_initial_note", columnDefinition = "TEXT")
    private String h1LactateInitialNote;

    @Column(name = "h1_culture_before_abx")
    private Boolean h1CultureBeforeAbx;

    @Column(name = "h1_culture_before_abx_time")
    private LocalDateTime h1CultureBeforeAbxTime;

    @Column(name = "h1_culture_before_abx_note", columnDefinition = "TEXT")
    private String h1CultureBeforeAbxNote;

    @Column(name = "h1_abx_broad")
    private Boolean h1AbxBroad;

    @Column(name = "h1_abx_broad_time")
    private LocalDateTime h1AbxBroadTime;

    @Column(name = "h1_abx_broad_note", columnDefinition = "TEXT")
    private String h1AbxBroadNote;

    @Column(name = "fluid_qualified")
    private Boolean fluidQualified;

    @Column(name = "fluid_30mlkg")
    private Boolean fluid30mlkg;

    @Column(name = "fluid_30mlkg_time")
    private LocalDateTime fluid30mlkgTime;

    @Column(name = "fluid_30mlkg_note", columnDefinition = "TEXT")
    private String fluid30mlkgNote;

    @Column(name = "vasopressor_qualified")
    private Boolean vasopressorQualified;

    @Column(name = "vasopressor")
    private Boolean vasopressor;

    @Column(name = "vasopressor_time")
    private LocalDateTime vasopressorTime;

    @Column(name = "vasopressor_note", columnDefinition = "TEXT")
    private String vasopressorNote;

    @Column(name = "relactate_qualified")
    private Boolean relactateQualified;

    @Column(name = "relactate")
    private Boolean relactate;

    @Column(name = "relactate_time")
    private LocalDateTime relactateTime;

    @Column(name = "relactate_note", columnDefinition = "TEXT")
    private String relactateNote;

    @Column(name = "perfusion_reassessment_details", columnDefinition = "TEXT")
    private String perfusionReassessmentDetails;

    @Column(name = "modified_at", nullable = false)
    private LocalDateTime modifiedAt;

    @Column(name = "modified_by", nullable = false)
    private String modifiedBy;
}
