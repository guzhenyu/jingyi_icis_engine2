package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.time.LocalDateTime;
import java.util.*;
import java.util.function.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import lombok.*;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.VTEPaduaScore;
import com.jingyicare.jingyi_icis_engine.service.*; // Assuming ScoreUtils and ReturnCodeUtils are here or accessible
import com.jingyicare.jingyi_icis_engine.utils.*;   // Assuming Pair is here

/**
 * Service for VTE Padua Score entity and proto conversion.
 * Core functions:
 * - toEntity(...): Converts proto to entity object.
 * - toProto(...): Converts entity object to proto.
 */
@Slf4j
@Service
public class VTEPaduaScorer {

    /**
     * Converts VTEPaduaScorePB proto to VTEPaduaScore entity.
     */
    public static Pair<ReturnCode, VTEPaduaScore> toEntity(
            VTEPaduaScoreMetaPB metaPb, VTEPaduaScorePB scorePb, ScoreUtils.ScorerInfo scorerInfo,
            List<String> statusCodeMsgList
    ) {
        VTEPaduaScore.VTEPaduaScoreBuilder builder = VTEPaduaScore.builder();

        // Set common scoring info (patient ID, scorer, time, etc.)
        setScorerInfo(builder, scorerInfo);

        // Extract meta code -> ValueMetaPB map for validation/conversion
        Map<String, ValueMetaPB> codeMetaMap = extractValueMetaMap(metaPb);

        // Combine all factor lists from the proto for easier iteration
        List<DoctorScoreFactorPB> allFactors = Stream.of(
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
            .collect(Collectors.toList());

        // Process each factor
        for (DoctorScoreFactorPB factor : allFactors) {
            String code = factor.getCode();
            ValueMetaPB valueMeta = codeMetaMap.get(code);
            if (valueMeta == null) {
                log.error("Unhandled VTE Padua factor code in toEntity: {}", code);
                ReturnCode rc = ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.UNEXPECTED_VTE_PADUA_FACTOR, code // Assuming specific status code exists
                );
                return new Pair<>(rc, null);
            }

            // Map code to the corresponding entity field
             if ("age_70_or_older".equals(code)) {
                builder.age70OrOlder(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("obesity_bmi_30".equals(code)) {
                builder.obesityBmi30(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("acute_infection_rheumatic".equals(code)) {
                builder.acuteInfectionRheumatic(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("acute_mi_or_stroke".equals(code)) {
                builder.acuteMiOrStroke(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("hormone_therapy".equals(code)) {
                builder.hormoneTherapy(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("heart_or_respiratory_failure".equals(code)) {
                builder.heartOrRespiratoryFailure(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- 2 Points ---
            else if ("recent_trauma_or_surgery".equals(code)) {
                builder.recentTraumaOrSurgery(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- 3 Points ---
             else if ("thrombophilic_condition".equals(code)) {
                builder.thrombophilicCondition(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("active_malignancy".equals(code)) { // VTE Risk Malignancy
                builder.activeMalignancy(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("prior_vte".equals(code)) {
                builder.priorVte(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("immobilization".equals(code)) {
                builder.immobilization(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- High Risk (1 item) ---
             else if ("active_gi_ulcer".equals(code)) {
                builder.activeGiUlcer(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("bleeding_event_within_3months".equals(code)) {
                builder.bleedingEventWithin3months(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("platelet_count_below_50".equals(code)) {
                builder.plateletCountBelow50(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- High Risk (3+ items) ---
            else if ("age_85_or_older".equals(code)) {
                builder.age85OrOlder(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("liver_dysfunction_inr_15".equals(code)) {
                builder.liverDysfunctionInr15(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("severe_renal_failure".equals(code)) {
                builder.severeRenalFailure(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("icu_or_ccu_admission".equals(code)) {
                builder.icuOrCcuAdmission(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("central_venous_catheter".equals(code)) {
                builder.centralVenousCatheter(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("has_active_malignancy".equals(code)) { // Bleeding Risk Malignancy
                builder.hasActiveMalignancy(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("rheumatic_disease".equals(code)) {
                builder.rheumaticDisease(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("male_gender".equals(code)) {
                builder.maleGender(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- Prevention Assessment ---
            else if ("prevention_anticoagulant_only_assess".equals(code)) {
                builder.preventionAnticoagulantOnlyAssess(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("prevention_physical_only_assess".equals(code)) {
                builder.preventionPhysicalOnlyAssess(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("prevention_anticoagulant_physical_assess".equals(code)) {
                builder.preventionAnticoagulantPhysicalAssess(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("prevention_unavailable_assess".equals(code)) {
                builder.preventionUnavailableAssess(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- Prevention Execution ---
             else if ("prevention_anticoagulant_only_exec".equals(code)) {
                builder.preventionAnticoagulantOnlyExec(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("prevention_physical_only_exec".equals(code)) {
                builder.preventionPhysicalOnlyExec(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("prevention_anticoagulant_physical_exec".equals(code)) {
                builder.preventionAnticoagulantPhysicalExec(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("prevention_unavailable_exec".equals(code)) {
                builder.preventionUnavailableExec(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
             // --- Basic Measures ---
            else if ("elevate_limbs".equals(code)) {
                builder.elevateLimbs(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("ankle_exercise".equals(code)) {
                builder.ankleExercise(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("quadriceps_contraction".equals(code)) {
                builder.quadricepsContraction(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("deep_breathing_or_balloon".equals(code)) {
                builder.deepBreathingOrBalloon(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("quit_smoking_alcohol".equals(code)) {
                builder.quitSmokingAlcohol(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("drink_more_water".equals(code)) {
                builder.drinkMoreWater(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("maintain_bowel_regular".equals(code)) {
                builder.maintainBowelRegular(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("turn_every_2h_or_leg_movement".equals(code)) {
                builder.turnEvery2hOrLegMovement(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("get_out_of_bed".equals(code)) {
                builder.getOutOfBed(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("other_basic_measures".equals(code)) {
                builder.otherBasicMeasures(ValueMetaUtils.getStringObj(factor.getValue()));
            }
            // --- Mechanical Measures ---
            else if ("intermittent_pneumatic_compression".equals(code)) {
                builder.intermittentPneumaticCompression(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("graded_compression_stockings".equals(code)) {
                builder.gradedCompressionStockings(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("foot_vein_pump".equals(code)) {
                builder.footVeinPump(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
             // --- Pharmacological Measures ---
             else if ("low_molecular_heparin_injection".equals(code)) {
                builder.lowMolecularHeparinInjection(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("rivaroxaban".equals(code)) {
                builder.rivaroxaban(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("warfarin".equals(code)) {
                builder.warfarin(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("other_pharmacological_measures".equals(code)) {
                builder.otherPharmacologicalMeasures(ValueMetaUtils.getStringObj(factor.getValue()));
            }
             // --- Default ---
            else {
                log.error("Unhandled VTE Padua factor code in toEntity switch: {}", code);
                 ReturnCode rc = ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.UNEXPECTED_VTE_PADUA_FACTOR, code
                );
                return new Pair<>(rc, null);
            }
        }

        // Set the final total score
        builder.totalScore(scorePb.getTotalScore());

        // Return success
        ReturnCode ok = ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
        return new Pair<>(ok, builder.build());
    }

    /**
     * Converts VTEPaduaScore entity to VTEPaduaScorePB proto.
     * Note: Does not re-calculate the score.
     */
    public static Pair<ReturnCode, VTEPaduaScorePB> toProto(
            VTEPaduaScoreMetaPB metaPb, VTEPaduaScore vtePaduaScore, // RulesPB removed
            List<String> statusCodeMsgList
    ) {
        VTEPaduaScorePB.Builder builder = VTEPaduaScorePB.newBuilder();
        Map<String, ValueMetaPB> codeMetaMap = extractValueMetaMap(metaPb);

        // Helper to add factors
        BiFunction<String, Object, ReturnCode> addFactor = (code, value) ->
            addFactorToProto(builder, code, value, codeMetaMap, statusCodeMsgList);

        ReturnCode rc;

        // --- 1 Point Risk Factors ---
        rc = addFactor.apply("age_70_or_older", vtePaduaScore.getAge70OrOlder()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("obesity_bmi_30", vtePaduaScore.getObesityBmi30()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("acute_infection_rheumatic", vtePaduaScore.getAcuteInfectionRheumatic()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("acute_mi_or_stroke", vtePaduaScore.getAcuteMiOrStroke()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("hormone_therapy", vtePaduaScore.getHormoneTherapy()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("heart_or_respiratory_failure", vtePaduaScore.getHeartOrRespiratoryFailure()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- 2 Points Risk Factors ---
        rc = addFactor.apply("recent_trauma_or_surgery", vtePaduaScore.getRecentTraumaOrSurgery()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- 3 Points Risk Factors ---
        rc = addFactor.apply("thrombophilic_condition", vtePaduaScore.getThrombophilicCondition()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("active_malignancy", vtePaduaScore.getActiveMalignancy()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prior_vte", vtePaduaScore.getPriorVte()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("immobilization", vtePaduaScore.getImmobilization()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- High Risk (1 item) ---
        rc = addFactor.apply("active_gi_ulcer", vtePaduaScore.getActiveGiUlcer()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("bleeding_event_within_3months", vtePaduaScore.getBleedingEventWithin3months()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("platelet_count_below_50", vtePaduaScore.getPlateletCountBelow50()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- High Risk (3+ items) ---
        rc = addFactor.apply("age_85_or_older", vtePaduaScore.getAge85OrOlder()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("liver_dysfunction_inr_15", vtePaduaScore.getLiverDysfunctionInr15()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("severe_renal_failure", vtePaduaScore.getSevereRenalFailure()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("icu_or_ccu_admission", vtePaduaScore.getIcuOrCcuAdmission()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("central_venous_catheter", vtePaduaScore.getCentralVenousCatheter()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("has_active_malignancy", vtePaduaScore.getHasActiveMalignancy()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("rheumatic_disease", vtePaduaScore.getRheumaticDisease()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("male_gender", vtePaduaScore.getMaleGender()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Prevention Assessment ---
        rc = addFactor.apply("prevention_anticoagulant_only_assess", vtePaduaScore.getPreventionAnticoagulantOnlyAssess()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_physical_only_assess", vtePaduaScore.getPreventionPhysicalOnlyAssess()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_anticoagulant_physical_assess", vtePaduaScore.getPreventionAnticoagulantPhysicalAssess()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_unavailable_assess", vtePaduaScore.getPreventionUnavailableAssess()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Prevention Execution ---
        rc = addFactor.apply("prevention_anticoagulant_only_exec", vtePaduaScore.getPreventionAnticoagulantOnlyExec()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_physical_only_exec", vtePaduaScore.getPreventionPhysicalOnlyExec()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_anticoagulant_physical_exec", vtePaduaScore.getPreventionAnticoagulantPhysicalExec()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_unavailable_exec", vtePaduaScore.getPreventionUnavailableExec()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Basic Measures ---
        rc = addFactor.apply("elevate_limbs", vtePaduaScore.getElevateLimbs()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("ankle_exercise", vtePaduaScore.getAnkleExercise()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("quadriceps_contraction", vtePaduaScore.getQuadricepsContraction()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("deep_breathing_or_balloon", vtePaduaScore.getDeepBreathingOrBalloon()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("quit_smoking_alcohol", vtePaduaScore.getQuitSmokingAlcohol()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("drink_more_water", vtePaduaScore.getDrinkMoreWater()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("maintain_bowel_regular", vtePaduaScore.getMaintainBowelRegular()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("turn_every_2h_or_leg_movement", vtePaduaScore.getTurnEvery2hOrLegMovement()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("get_out_of_bed", vtePaduaScore.getGetOutOfBed()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("other_basic_measures", vtePaduaScore.getOtherBasicMeasures()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Mechanical Measures ---
        rc = addFactor.apply("intermittent_pneumatic_compression", vtePaduaScore.getIntermittentPneumaticCompression()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("graded_compression_stockings", vtePaduaScore.getGradedCompressionStockings()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("foot_vein_pump", vtePaduaScore.getFootVeinPump()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Pharmacological Measures ---
        rc = addFactor.apply("low_molecular_heparin_injection", vtePaduaScore.getLowMolecularHeparinInjection()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("rivaroxaban", vtePaduaScore.getRivaroxaban()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("warfarin", vtePaduaScore.getWarfarin()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("other_pharmacological_measures", vtePaduaScore.getOtherPharmacologicalMeasures()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // Set total score from entity
        builder.setTotalScore(
            vtePaduaScore.getTotalScore() == null ? 0 : vtePaduaScore.getTotalScore()
        );

        // Return success
        ReturnCode ok = ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
        return new Pair<>(ok, builder.build());
    }

    // --- Helper Methods (Copied/adapted from SofaScorer/CapriniScorer) ---

    /**
     * Sets scorer info from ScorerInfo object to the entity builder.
     */
    private static void setScorerInfo(VTEPaduaScore.VTEPaduaScoreBuilder builder, ScoreUtils.ScorerInfo scorerInfo) {
       if (scorerInfo == null) return;
        if (scorerInfo.pid != null) builder.pid(scorerInfo.pid);
        if (scorerInfo.deptId != null) builder.deptId(scorerInfo.deptId);
        if (scorerInfo.scoreTime != null) builder.scoreTime(scorerInfo.scoreTime);
        if (scorerInfo.scoredBy != null) builder.scoredBy(scorerInfo.scoredBy);
        if (scorerInfo.scoredByAccountName != null) builder.scoredByAccountName(scorerInfo.scoredByAccountName);
        if (scorerInfo.evalStartAt != null) builder.evalStartAt(scorerInfo.evalStartAt);
        if (scorerInfo.evalEndAt != null) builder.evalEndAt(scorerInfo.evalEndAt);

        if (scorerInfo.modifiedBy != null) builder.modifiedBy(scorerInfo.modifiedBy);
        if (scorerInfo.modifiedAt != null) builder.modifiedAt(scorerInfo.modifiedAt);
        builder.isDeleted(scorerInfo.isDeleted != null ? scorerInfo.isDeleted : false); // Default to false
        if (scorerInfo.deletedBy != null) builder.deletedBy(scorerInfo.deletedBy);
        if (scorerInfo.deletedByAccountName != null) builder.deletedByAccountName(scorerInfo.deletedByAccountName);
        if (scorerInfo.deletedAt != null) builder.deletedAt(scorerInfo.deletedAt);
    }

     /**
     * Extracts a map of code -> ValueMetaPB from VTEPaduaScoreMetaPB.
     */
     public static Map<String, ValueMetaPB> extractValueMetaMap(VTEPaduaScoreMetaPB metaPb) {
        Map<String, ValueMetaPB> result = new HashMap<>();
        Stream.of(
                metaPb.getOnePointRiskFactorList(),
                metaPb.getTwoPointsRiskFactorList(),
                metaPb.getThreePointsRiskFactorList(),
                metaPb.getHighRiskOneItemList(),
                metaPb.getHighRiskThreeOrMoreList(),
                metaPb.getPreventionAssessmentList(),
                metaPb.getPreventionExecutionList(),
                metaPb.getBasicMeasureList(),
                metaPb.getMechanicalMeasureList(),
                metaPb.getPharmacologicalMeasureList()
            )
            .flatMap(Collection::stream)
            .forEach(factorMeta -> result.put(factorMeta.getCode(), factorMeta.getValueMeta()));
        return result;
    }

    /**
     * Adds a single factor to the appropriate list within VTEPaduaScorePB.Builder.
     * Determines the target list based on the code's presence in the meta definition.
     */
    private static ReturnCode addFactorToProto(
        VTEPaduaScorePB.Builder builder, String code, Object fieldValue,
        Map<String, ValueMetaPB> codeMetaMap,
        List<String> statusCodeMsgList
    ) {
        if (fieldValue == null) return ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);

        ValueMetaPB valueMeta = codeMetaMap.get(code);
        if (valueMeta == null) {
            log.error("No ValueMetaPB found for Padua code: {}", code);
            return ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_VTE_PADUA_FACTOR, code
            );
        }

        GenericValuePB genericValue = buildGenericValue(fieldValue, valueMeta);
        if (genericValue == null) {
             log.error("Failed to convert Padua fieldValue to GenericValuePB for code: {}", code);
             return ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_VTE_PADUA_FACTOR, code
            );
        }

        DoctorScoreFactorPB factor = DoctorScoreFactorPB.newBuilder()
            .setCode(code)
            .setValue(genericValue)
            .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
            .build();

        // Add to the correct list
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
             log.error("Code {} does not belong to any known Padua factor group in toProto", code);
             return ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_VTE_PADUA_FACTOR, code
            );
        }

        return ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
    }

    public static GenericValuePB buildGenericValue(Object fieldValue, ValueMetaPB valueMeta) {
        if (fieldValue == null) return null;

        switch (valueMeta.getValueType()) {
            case BOOL:
                return GenericValuePB.newBuilder().setBoolVal((Boolean) fieldValue).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val((Integer) fieldValue).build();
            case FLOAT:
                    if (fieldValue instanceof Number) {
                    return ValueMetaUtils.getValue(valueMeta, ((Number) fieldValue).doubleValue());
                }
                break;
            case STRING:
                return GenericValuePB.newBuilder().setStrVal((String) fieldValue).build();
            default:
                log.warn("Unsupported valueType for GenericValuePB conversion: {}", valueMeta.getValueType());
                break;
        }
        return null;
    }

    public static Pair<ReturnCode, VTEPaduaScorePB> score(
        VTEPaduaScoreMetaPB metaPb, VTEPaduaScorePB scorePb, List<String> statusCodeMsgList
    ) {
        Pair<ReturnCode, Integer> onePointCntPair = ScoreUtils.countScoreFactors(
            metaPb.getOnePointRiskFactorList(), scorePb.getOnePointRiskFactorList(),
            StatusCode.UNEXPECTED_VTE_PADUA_FACTOR, statusCodeMsgList
        );
        if (onePointCntPair.getFirst().getCode() != 0) return new Pair<>(onePointCntPair.getFirst(), null);

        Pair<ReturnCode, Integer> twoPointsCntPair = ScoreUtils.countScoreFactors(
            metaPb.getTwoPointsRiskFactorList(), scorePb.getTwoPointsRiskFactorList(),
            StatusCode.UNEXPECTED_VTE_PADUA_FACTOR, statusCodeMsgList
        );
        if (twoPointsCntPair.getFirst().getCode() != 0) return new Pair<>(twoPointsCntPair.getFirst(), null);

        Pair<ReturnCode, Integer> threePointsCntPair = ScoreUtils.countScoreFactors(
            metaPb.getThreePointsRiskFactorList(), scorePb.getThreePointsRiskFactorList(),
            StatusCode.UNEXPECTED_VTE_PADUA_FACTOR, statusCodeMsgList
        );
        if (threePointsCntPair.getFirst().getCode() != 0) return new Pair<>(threePointsCntPair.getFirst(), null);

        Integer score = onePointCntPair.getSecond() + twoPointsCntPair.getSecond() * 2 + threePointsCntPair.getSecond() * 3;
        return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK), scorePb.toBuilder().setTotalScore(score).build());
    }
}