package com.jingyicare.jingyi_icis_engine.service.doctors;

import static org.assertj.core.api.Assertions.assertThat;
// import static org.assertj.core.api.Assertions.within; // Usually not needed for boolean/string

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
// import com.jingyicare.jingyi_icis_engine.repository.doctors.*; // Likely not needed directly
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Slf4j
public class VTEPaduaScorerTests extends TestsBase {
    public VTEPaduaScorerTests(@Autowired ConfigProtoService protoService) {
        // Adjust the path according to your configuration structure
        this.paduaMeta = protoService.getConfig().getDoctorScore().getVtePaduaMeta();
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.codeMetaMap = VTEPaduaScorer.extractValueMetaMap(paduaMeta);
    }

    @Test
    public void testToEntityAndToProto() {
        // 1) Construct a sample VTEPaduaScorePB
        VTEPaduaScorePB.Builder pbBuilder = VTEPaduaScorePB.newBuilder();

        // Add sample values for representative fields
        // --- 1 Point ---
        addPaduaParam(pbBuilder, "age_70_or_older", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "obesity_bmi_30", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "acute_infection_rheumatic", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "acute_mi_or_stroke", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "hormone_therapy", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "heart_or_respiratory_failure", false, TypeEnumPB.BOOL);

        // --- 2 Points ---
        addPaduaParam(pbBuilder, "recent_trauma_or_surgery", true, TypeEnumPB.BOOL);

        // --- 3 Points ---
        addPaduaParam(pbBuilder, "thrombophilic_condition", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "active_malignancy", false, TypeEnumPB.BOOL); // VTE Risk malignancy
        addPaduaParam(pbBuilder, "prior_vte", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "immobilization", false, TypeEnumPB.BOOL);

        // --- High Risk (1 item) ---
        addPaduaParam(pbBuilder, "active_gi_ulcer", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "bleeding_event_within_3months", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "platelet_count_below_50", true, TypeEnumPB.BOOL);

        // --- High Risk (3+ items) ---
        addPaduaParam(pbBuilder, "age_85_or_older", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "liver_dysfunction_inr_15", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "severe_renal_failure", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "icu_or_ccu_admission", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "central_venous_catheter", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "has_active_malignancy", true, TypeEnumPB.BOOL); // Bleeding Risk malignancy
        addPaduaParam(pbBuilder, "rheumatic_disease", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "male_gender", true, TypeEnumPB.BOOL);

        // --- Prevention Assessment ---
        addPaduaParam(pbBuilder, "prevention_anticoagulant_only_assess", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "prevention_physical_only_assess", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "prevention_anticoagulant_physical_assess", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "prevention_unavailable_assess", false, TypeEnumPB.BOOL);

        // --- Prevention Execution ---
        addPaduaParam(pbBuilder, "prevention_anticoagulant_only_exec", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "prevention_physical_only_exec", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "prevention_anticoagulant_physical_exec", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "prevention_unavailable_exec", false, TypeEnumPB.BOOL);

        // --- Basic Measures ---
        addPaduaParam(pbBuilder, "elevate_limbs", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "ankle_exercise", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "quadriceps_contraction", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "deep_breathing_or_balloon", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "quit_smoking_alcohol", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "drink_more_water", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "maintain_bowel_regular", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "turn_every_2h_or_leg_movement", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "get_out_of_bed", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "other_basic_measures", "Padua basic measure text", TypeEnumPB.STRING);

        // --- Mechanical Measures ---
        addPaduaParam(pbBuilder, "intermittent_pneumatic_compression", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "graded_compression_stockings", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "foot_vein_pump", true, TypeEnumPB.BOOL);

        // --- Pharmacological Measures ---
        addPaduaParam(pbBuilder, "low_molecular_heparin_injection", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "rivaroxaban", false, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "warfarin", true, TypeEnumPB.BOOL);
        addPaduaParam(pbBuilder, "other_pharmacological_measures", "Padua pharm measure text", TypeEnumPB.STRING);

        // Set total score (placeholder, as toEntity doesn't calculate)
        pbBuilder.setTotalScore(6);

        VTEPaduaScorePB originalPb = pbBuilder.build();

        // 2) toEntity
        ScoreUtils.ScorerInfo scorerInfo = new ScoreUtils.ScorerInfo(
            456L, "MED-03", LocalDateTime.now(),
            "tester_padua", "Dr. Padua Tester",
            LocalDateTime.now().minusMinutes(30), LocalDateTime.now(), // Eval times
            LocalDateTime.now(), "modifier_padua", // Modified info
            false, null, null, null // Deletion info
        );
        Pair<ReturnCode, VTEPaduaScore> entityResult = VTEPaduaScorer.toEntity(
            paduaMeta, originalPb, scorerInfo, statusCodeMsgList
        );
        assertThat(entityResult.getFirst().getCode()).isEqualTo(0);
        VTEPaduaScore paduaEntity = entityResult.getSecond();
        assertThat(paduaEntity).isNotNull();

        // Validate entity fields
        // --- 1 Point ---
        assertThat(paduaEntity.getAge70OrOlder()).isTrue();
        assertThat(paduaEntity.getObesityBmi30()).isFalse();
        assertThat(paduaEntity.getAcuteInfectionRheumatic()).isTrue();
        assertThat(paduaEntity.getAcuteMiOrStroke()).isFalse();
        assertThat(paduaEntity.getHormoneTherapy()).isTrue();
        assertThat(paduaEntity.getHeartOrRespiratoryFailure()).isFalse();
        // --- 2 Points ---
        assertThat(paduaEntity.getRecentTraumaOrSurgery()).isTrue();
        // --- 3 Points ---
        assertThat(paduaEntity.getThrombophilicCondition()).isTrue();
        assertThat(paduaEntity.getActiveMalignancy()).isFalse();
        assertThat(paduaEntity.getPriorVte()).isTrue();
        assertThat(paduaEntity.getImmobilization()).isFalse();
        // --- High Risk (1 item) ---
        assertThat(paduaEntity.getActiveGiUlcer()).isTrue();
        assertThat(paduaEntity.getBleedingEventWithin3months()).isFalse();
        assertThat(paduaEntity.getPlateletCountBelow50()).isTrue();
        // --- High Risk (3+ items) ---
        assertThat(paduaEntity.getAge85OrOlder()).isFalse();
        assertThat(paduaEntity.getLiverDysfunctionInr15()).isTrue();
        assertThat(paduaEntity.getSevereRenalFailure()).isFalse();
        assertThat(paduaEntity.getIcuOrCcuAdmission()).isTrue();
        assertThat(paduaEntity.getCentralVenousCatheter()).isFalse();
        assertThat(paduaEntity.getHasActiveMalignancy()).isTrue();
        assertThat(paduaEntity.getRheumaticDisease()).isFalse();
        assertThat(paduaEntity.getMaleGender()).isTrue();
        // --- Prevention Assessment ---
        assertThat(paduaEntity.getPreventionAnticoagulantOnlyAssess()).isTrue();
        assertThat(paduaEntity.getPreventionPhysicalOnlyAssess()).isFalse();
        assertThat(paduaEntity.getPreventionAnticoagulantPhysicalAssess()).isTrue();
        assertThat(paduaEntity.getPreventionUnavailableAssess()).isFalse();
        // --- Prevention Execution ---
        assertThat(paduaEntity.getPreventionAnticoagulantOnlyExec()).isTrue();
        assertThat(paduaEntity.getPreventionPhysicalOnlyExec()).isFalse();
        assertThat(paduaEntity.getPreventionAnticoagulantPhysicalExec()).isTrue();
        assertThat(paduaEntity.getPreventionUnavailableExec()).isFalse();
        // --- Basic Measures ---
        assertThat(paduaEntity.getElevateLimbs()).isTrue();
        assertThat(paduaEntity.getAnkleExercise()).isFalse();
        assertThat(paduaEntity.getQuadricepsContraction()).isTrue();
        assertThat(paduaEntity.getDeepBreathingOrBalloon()).isFalse();
        assertThat(paduaEntity.getQuitSmokingAlcohol()).isTrue();
        assertThat(paduaEntity.getDrinkMoreWater()).isFalse();
        assertThat(paduaEntity.getMaintainBowelRegular()).isTrue();
        assertThat(paduaEntity.getTurnEvery2hOrLegMovement()).isFalse();
        assertThat(paduaEntity.getGetOutOfBed()).isTrue();
        assertThat(paduaEntity.getOtherBasicMeasures()).isEqualTo("Padua basic measure text");
        // --- Mechanical Measures ---
        assertThat(paduaEntity.getIntermittentPneumaticCompression()).isTrue();
        assertThat(paduaEntity.getGradedCompressionStockings()).isFalse();
        assertThat(paduaEntity.getFootVeinPump()).isTrue();
        // --- Pharmacological Measures ---
        assertThat(paduaEntity.getLowMolecularHeparinInjection()).isTrue();
        assertThat(paduaEntity.getRivaroxaban()).isFalse();
        assertThat(paduaEntity.getWarfarin()).isTrue();
        assertThat(paduaEntity.getOtherPharmacologicalMeasures()).isEqualTo("Padua pharm measure text");

        // Validate scorerInfo fields
        assertThat(paduaEntity.getPid()).isEqualTo(456L);
        assertThat(paduaEntity.getDeptId()).isEqualTo("MED-03");
        assertThat(paduaEntity.getScoredBy()).isEqualTo("tester_padua");
        assertThat(paduaEntity.getScoredByAccountName()).isEqualTo("Dr. Padua Tester");
        assertThat(paduaEntity.getIsDeleted()).isFalse();
        assertThat(paduaEntity.getModifiedBy()).isEqualTo("modifier_padua");

        // 3) toProto
        Pair<ReturnCode, VTEPaduaScorePB> protoResult = VTEPaduaScorer.toProto(
            paduaMeta, paduaEntity, statusCodeMsgList // Rules PB removed
        );
        assertThat(protoResult.getFirst().getCode()).isEqualTo(0);
        VTEPaduaScorePB newPb = protoResult.getSecond();
        assertThat(newPb).isNotNull();

        // Validate proto fields extracted back match original inputs
        // --- 1 Point ---
        assertThat(getPaduaParamBoolValue(newPb, "age_70_or_older")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "obesity_bmi_30")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "acute_infection_rheumatic")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "acute_mi_or_stroke")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "hormone_therapy")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "heart_or_respiratory_failure")).isFalse();
        // --- 2 Points ---
        assertThat(getPaduaParamBoolValue(newPb, "recent_trauma_or_surgery")).isTrue();
        // --- 3 Points ---
        assertThat(getPaduaParamBoolValue(newPb, "thrombophilic_condition")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "active_malignancy")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "prior_vte")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "immobilization")).isFalse();
        // --- High Risk (1 item) ---
        assertThat(getPaduaParamBoolValue(newPb, "active_gi_ulcer")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "bleeding_event_within_3months")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "platelet_count_below_50")).isTrue();
        // --- High Risk (3+ items) ---
        assertThat(getPaduaParamBoolValue(newPb, "age_85_or_older")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "liver_dysfunction_inr_15")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "severe_renal_failure")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "icu_or_ccu_admission")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "central_venous_catheter")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "has_active_malignancy")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "rheumatic_disease")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "male_gender")).isTrue();
        // --- Prevention Assessment ---
        assertThat(getPaduaParamBoolValue(newPb, "prevention_anticoagulant_only_assess")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "prevention_physical_only_assess")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "prevention_anticoagulant_physical_assess")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "prevention_unavailable_assess")).isFalse();
        // --- Prevention Execution ---
        assertThat(getPaduaParamBoolValue(newPb, "prevention_anticoagulant_only_exec")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "prevention_physical_only_exec")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "prevention_anticoagulant_physical_exec")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "prevention_unavailable_exec")).isFalse();
        // --- Basic Measures ---
        assertThat(getPaduaParamBoolValue(newPb, "elevate_limbs")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "ankle_exercise")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "quadriceps_contraction")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "deep_breathing_or_balloon")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "quit_smoking_alcohol")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "drink_more_water")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "maintain_bowel_regular")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "turn_every_2h_or_leg_movement")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "get_out_of_bed")).isTrue();
        assertThat(getPaduaParamStringValue(newPb, "other_basic_measures")).isEqualTo("Padua basic measure text");
        // --- Mechanical Measures ---
        assertThat(getPaduaParamBoolValue(newPb, "intermittent_pneumatic_compression")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "graded_compression_stockings")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "foot_vein_pump")).isTrue();
        // --- Pharmacological Measures ---
        assertThat(getPaduaParamBoolValue(newPb, "low_molecular_heparin_injection")).isTrue();
        assertThat(getPaduaParamBoolValue(newPb, "rivaroxaban")).isFalse();
        assertThat(getPaduaParamBoolValue(newPb, "warfarin")).isTrue();
        assertThat(getPaduaParamStringValue(newPb, "other_pharmacological_measures")).isEqualTo("Padua pharm measure text");

        // Total score check
        assertThat(newPb.getTotalScore()).isEqualTo(6); // Should match the placeholder value
    }

    // =============== Helper Methods for VTEPaduaScorePB ===============

    /**
     * Adds a factor to the correct list in the VTEPaduaScorePB.Builder.
     */
     private void addPaduaParam(
        VTEPaduaScorePB.Builder builder, String code, Object value, TypeEnumPB type
    ) {
        ValueMetaPB valueMeta = codeMetaMap.get(code);
        if (valueMeta == null) {
            log.warn("ValueMeta not found for Padua code '{}' in test setup, using default type {}", code, type);
            valueMeta = ValueMetaPB.newBuilder().setValueType(type).build();
        }

        GenericValuePB genericValue = VTEPaduaScorer.buildGenericValue(value, valueMeta); // Use static helper
        if (genericValue == null) {
            log.error("Failed to create GenericValuePB for Padua code '{}' in test", code);
            return;
        }

        DoctorScoreFactorPB factor = DoctorScoreFactorPB.newBuilder()
            .setCode(code)
            .setValue(genericValue)
            .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
            .build();

        // Add to the correct list based on the code's group (mirroring VTEPaduaScorer.addFactorToProto)
        if (List.of("age_70_or_older", "obesity_bmi_30", "acute_infection_rheumatic", "acute_mi_or_stroke", "hormone_therapy", "heart_or_respiratory_failure").contains(code)) {
            builder.addOnePointRiskFactor(factor);
        } else if (List.of("recent_trauma_or_surgery").contains(code)) {
            builder.addTwoPointsRiskFactor(factor);
        } else if (List.of("thrombophilic_condition", "active_malignancy", "prior_vte", "immobilization").contains(code)) {
            builder.addThreePointsRiskFactor(factor);
        } else if (List.of("active_gi_ulcer", "bleeding_event_within_3months", "platelet_count_below_50").contains(code)) {
            builder.addHighRiskOneItem(factor);
        } else if (List.of("age_85_or_older", "liver_dysfunction_inr_15", "severe_renal_failure", "icu_or_ccu_admission", "central_venous_catheter", "has_active_malignancy", "rheumatic_disease", "male_gender").contains(code)) {
            builder.addHighRiskThreeOrMore(factor);
        } else if (List.of("prevention_anticoagulant_only_assess", "prevention_physical_only_assess", "prevention_anticoagulant_physical_assess", "prevention_unavailable_assess").contains(code)) {
            builder.addPreventionAssessment(factor);
        } else if (List.of("prevention_anticoagulant_only_exec", "prevention_physical_only_exec", "prevention_anticoagulant_physical_exec", "prevention_unavailable_exec").contains(code)) {
            builder.addPreventionExecution(factor);
        } else if (List.of("elevate_limbs", "ankle_exercise", "quadriceps_contraction", "deep_breathing_or_balloon", "quit_smoking_alcohol", "drink_more_water", "maintain_bowel_regular", "turn_every_2h_or_leg_movement", "get_out_of_bed", "other_basic_measures").contains(code)) {
            builder.addBasicMeasure(factor);
        } else if (List.of("intermittent_pneumatic_compression", "graded_compression_stockings", "foot_vein_pump").contains(code)) {
            builder.addMechanicalMeasure(factor);
        } else if (List.of("low_molecular_heparin_injection", "rivaroxaban", "warfarin", "other_pharmacological_measures").contains(code)) {
            builder.addPharmacologicalMeasure(factor);
        } else {
            log.error("Code {} does not belong to any known Padua factor group in test setup helper", code);
        }
    }

    /**
     * Gets the boolean value for a specific code from VTEPaduaScorePB.
     */
    private boolean getPaduaParamBoolValue(VTEPaduaScorePB scorePb, String code) {
        return findPaduaFactor(scorePb, code)
            .map(factor -> factor.getValue().getBoolVal())
            .orElse(false);
    }

    /**
     * Gets the string value for a specific code from VTEPaduaScorePB.
     */
    private String getPaduaParamStringValue(VTEPaduaScorePB scorePb, String code) {
         return findPaduaFactor(scorePb, code)
            .map(factor -> factor.getValue().getStrVal())
            .orElse(null);
    }

     /**
     * Finds a DoctorScoreFactorPB by code within any list in VTEPaduaScorePB.
     */
    private Optional<DoctorScoreFactorPB> findPaduaFactor(VTEPaduaScorePB scorePb, String code) {
        return Stream.of(
                scorePb.getOnePointRiskFactorList(),
                scorePb.getTwoPointsRiskFactorList(),
                scorePb.getThreePointsRiskFactorList(),
                scorePb.getHighRiskOneItemList(),
                scorePb.getHighRiskThreeOrMoreList(),
                scorePb.getPreventionAssessmentList(),
                scorePb.getPreventionExecutionList(),
                scorePb.getBasicMeasureList(),
                scorePb.getMechanicalMeasureList(),
                scorePb.getPharmacologicalMeasureList()
            )
            .flatMap(Collection::stream)
            .filter(factor -> factor.getCode().equals(code))
            .findFirst();
    }

    private final VTEPaduaScoreMetaPB paduaMeta;
    private final List<String> statusCodeMsgList;
    private Map<String, ValueMetaPB> codeMetaMap; // Helper map
}