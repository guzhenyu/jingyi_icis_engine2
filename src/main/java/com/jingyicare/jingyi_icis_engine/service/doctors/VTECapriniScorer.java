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

import com.jingyicare.jingyi_icis_engine.entity.doctors.VTECapriniScore;
import com.jingyicare.jingyi_icis_engine.service.*; // Assuming ScoreUtils and ReturnCodeUtils are here or accessible
import com.jingyicare.jingyi_icis_engine.utils.*;   // Assuming Pair is here

/**
 * Service for VTE Caprini Score entity and proto conversion.
 * Core functions:
 * - toEntity(...): Converts proto to entity object.
 * - toProto(...): Converts entity object to proto.
 */
@Slf4j
@Service
public class VTECapriniScorer {
    public static Pair<ReturnCode, VTECapriniScore> toEntity(
        VTECapriniScoreMetaPB metaPb, VTECapriniScorePB scorePb, ScoreUtils.ScorerInfo scorerInfo,
        List<String> statusCodeMsgList
    ) {
        VTECapriniScore.VTECapriniScoreBuilder builder = VTECapriniScore.builder();

        // Set common scoring info (patient ID, scorer, time, etc.)
        setScorerInfo(builder, scorerInfo);

        // Extract meta code -> ValueMetaPB map for validation/conversion
        Map<String, ValueMetaPB> codeMetaMap = extractValueMetaMap(metaPb);

        // Combine all factor lists from the proto for easier iteration
        List<DoctorScoreFactorPB> allFactors = Stream.of(
                scorePb.getOnePointRiskFactorList(),
                scorePb.getTwoPointsRiskFactorList(),
                scorePb.getThreePointsRiskFactorList(),
                scorePb.getFivePointsRiskFactorList(),
                scorePb.getBleedingRiskGeneralFactorList(),
                scorePb.getBleedingRiskSurgeryRelatedFactorList(),
                scorePb.getBleedingRiskHighRiskSurgeryFactorList(),
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
                log.error("Unhandled VTE Caprini factor code in toEntity: {}", code);
                ReturnCode rc = ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, code // Assuming specific status code exists
                );
                return new Pair<>(rc, null);
            }

            // Map code to the corresponding entity field
            // Using if-else if for clarity with many fields, switch is also viable
            if ("age_41_60".equals(code)) {
                builder.age4160(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("lower_limb_edema".equals(code)) {
                builder.lowerLimbEdema(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("varicose_veins".equals(code)) {
                builder.varicoseVeins(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("obesity_bmi_25".equals(code)) {
                builder.obesityBmi25(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("planned_minor_surgery".equals(code)) {
                builder.plannedMinorSurgery(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("inflammatory_history".equals(code)) {
                builder.inflammatoryHistory(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("oral_contraceptives_hrt".equals(code)) {
                builder.oralContraceptivesHrt(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("pregnancy_or_postpartum".equals(code)) {
                builder.pregnancyOrPostpartum(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("acute_myocardial_infarction".equals(code)) {
                builder.acuteMyocardialInfarction(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("congestive_heart_failure".equals(code)) {
                builder.congestiveHeartFailure(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("bedridden_medical_patient".equals(code)) {
                builder.bedriddenMedicalPatient(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("pulmonary_dysfunction".equals(code)) {
                builder.pulmonaryDysfunction(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("major_surgery_history".equals(code)) {
                builder.majorSurgeryHistory(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("sepsis".equals(code)) {
                builder.sepsis(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("severe_lung_disease_pneumonia".equals(code)) {
                builder.severeLungDiseasePneumonia(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("unexplained_or_recurrent_miscarriage".equals(code)) {
                builder.unexplainedOrRecurrentMiscarriage(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("other_risk_factors".equals(code)) {
                 // Assuming TEXT maps to String in ValueMetaUtils
                builder.otherRiskFactors(ValueMetaUtils.getStringObj(factor.getValue()));
            }
            // --- 2 Points ---
            else if ("age_61_74".equals(code)) {
                builder.age6174(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("central_venous_catheter".equals(code)) {
                builder.centralVenousCatheter(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("arthroscopic_surgery".equals(code)) {
                builder.arthroscopicSurgery(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("major_surgery_over_45min".equals(code)) {
                builder.majorSurgeryOver45min(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("bed_rest_over_72h".equals(code)) {
                builder.bedRestOver72h(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("malignant_tumor".equals(code)) {
                builder.malignantTumor(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("laparoscopic_surgery_over_45min".equals(code)) {
                builder.laparoscopicSurgeryOver45min(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("cast_immobilization".equals(code)) {
                builder.castImmobilization(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- 3 Points ---
            else if ("age_75_or_older".equals(code)) {
                builder.age75OrOlder(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("thrombosis_family_history".equals(code)) {
                builder.thrombosisFamilyHistory(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("dvt_pe_history".equals(code)) {
                builder.dvtPeHistory(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("prothrombin_20210a_positive".equals(code)) {
                builder.prothrombin20210aPositive(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("factor_v_leiden_positive".equals(code)) {
                builder.factorVLeidenPositive(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("lupus_anticoagulant_positive".equals(code)) {
                builder.lupusAnticoagulantPositive(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("elevated_homocysteine".equals(code)) {
                builder.elevatedHomocysteine(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("antiphospholipid_antibodies".equals(code)) {
                builder.antiphospholipidAntibodies(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("heparin_induced_thrombocytopenia".equals(code)) {
                builder.heparinInducedThrombocytopenia(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("other_congenital_or_acquired_thrombosis".equals(code)) {
                 // Assuming TEXT maps to String in ValueMetaUtils
                builder.otherCongenitalOrAcquiredThrombosis(ValueMetaUtils.getStringObj(factor.getValue()));
            }
            // --- 5 Points ---
            else if ("stroke_within_month".equals(code)) {
                builder.strokeWithinMonth(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("multiple_trauma_within_month".equals(code)) {
                builder.multipleTraumaWithinMonth(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("acute_spinal_cord_injury".equals(code)) {
                builder.acuteSpinalCordInjury(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("hip_pelvis_lower_limb_fracture".equals(code)) {
                builder.hipPelvisLowerLimbFracture(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("elective_lower_limb_joint_replacement".equals(code)) {
                builder.electiveLowerLimbJointReplacement(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- Bleeding Risk - General ---
            else if ("active_bleeding".equals(code)) {
                builder.activeBleeding(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("bleeding_event_within_3months".equals(code)) {
                builder.bleedingEventWithin3months(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("severe_renal_or_liver_failure".equals(code)) {
                builder.severeRenalOrLiverFailure(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("platelet_count_below_50".equals(code)) {
                builder.plateletCountBelow50(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("uncontrolled_hypertension".equals(code)) {
                builder.uncontrolledHypertension(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("lumbar_epidural_or_spinal_anesthesia".equals(code)) {
                builder.lumbarEpiduralOrSpinalAnesthesia(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("anticoagulant_antiplatelet_or_thrombolytic".equals(code)) {
                builder.anticoagulantAntiplateletOrThrombolytic(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("coagulation_disorder".equals(code)) {
                builder.coagulationDisorder(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("active_gi_ulcer".equals(code)) {
                builder.activeGiUlcer(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("known_untreated_bleeding_disorder".equals(code)) {
                builder.knownUntreatedBleedingDisorder(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
            // --- Bleeding Risk - Surgery-Related ---
             else if ("abdominal_surgery_malignant_male_anemia_complex".equals(code)) {
                builder.abdominalSurgeryMalignantMaleAnemiaComplex(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("pancreaticoduodenectomy_sepsis_fistula_bleeding".equals(code)) {
                builder.pancreaticoduodenectomySepsisFistulaBleeding(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("liver_resection_pri_liver_cancer_low_hemoglobin_platelets".equals(code)) {
                builder.liverResectionPriLiverCancerLowHemoglobinPlatelets(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("cardiac_surgery_long_cp_time".equals(code)) {
                builder.cardiacSurgeryLongCpTime(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("thoracic_surgery_pneumonectomy_or_extended".equals(code)) {
                builder.thoracicSurgeryPneumonectomyOrExtended(ValueMetaUtils.getBooleanObj(factor.getValue()));
            }
             // --- Bleeding Risk - High-Risk Surgery ---
            else if ("craniotomy".equals(code)) {
                builder.craniotomy(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("spinal_surgery".equals(code)) {
                builder.spinalSurgery(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("spinal_trauma".equals(code)) {
                builder.spinalTrauma(ValueMetaUtils.getBooleanObj(factor.getValue()));
            } else if ("free_flap_reconstruction".equals(code)) {
                builder.freeFlapReconstruction(ValueMetaUtils.getBooleanObj(factor.getValue()));
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
                 // Assuming TEXT maps to String in ValueMetaUtils
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
                 // Assuming TEXT maps to String in ValueMetaUtils
                builder.otherPharmacologicalMeasures(ValueMetaUtils.getStringObj(factor.getValue()));
            }
            // --- Default ---
            else {
                log.error("Unhandled VTE Caprini factor code in toEntity switch: {}", code);
                 ReturnCode rc = ReturnCodeUtils.getReturnCode(
                    statusCodeMsgList, StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, code
                );
                return new Pair<>(rc, null);
            }
        }

        // Set the final total score (assuming it's pre-calculated in the proto)
        builder.totalScore(scorePb.getTotalScore());

        // Return success
        ReturnCode ok = ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
        return new Pair<>(ok, builder.build());
    }

    public static Pair<ReturnCode, VTECapriniScorePB> toProto(
        VTECapriniScoreMetaPB metaPb, VTECapriniScore vteCapriniScore,
        List<String> statusCodeMsgList
    ) {
        VTECapriniScorePB.Builder builder = VTECapriniScorePB.newBuilder();

        // Build code -> ValueMetaPB map for formatting/validation
        Map<String, ValueMetaPB> codeMetaMap = extractValueMetaMap(metaPb);

        // Helper to add factors to a specific list in the proto builder
        BiFunction<String, Object, ReturnCode> addFactor = (code, value) ->
            addFactorToProto(builder, code, value, codeMetaMap, statusCodeMsgList);

        ReturnCode rc;

        // --- 1 Point Risk Factors ---
        rc = addFactor.apply("age_41_60", vteCapriniScore.getAge4160()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("lower_limb_edema", vteCapriniScore.getLowerLimbEdema()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("varicose_veins", vteCapriniScore.getVaricoseVeins()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("obesity_bmi_25", vteCapriniScore.getObesityBmi25()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("planned_minor_surgery", vteCapriniScore.getPlannedMinorSurgery()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("inflammatory_history", vteCapriniScore.getInflammatoryHistory()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("oral_contraceptives_hrt", vteCapriniScore.getOralContraceptivesHrt()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("pregnancy_or_postpartum", vteCapriniScore.getPregnancyOrPostpartum()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("acute_myocardial_infarction", vteCapriniScore.getAcuteMyocardialInfarction()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("congestive_heart_failure", vteCapriniScore.getCongestiveHeartFailure()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("bedridden_medical_patient", vteCapriniScore.getBedriddenMedicalPatient()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("pulmonary_dysfunction", vteCapriniScore.getPulmonaryDysfunction()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("major_surgery_history", vteCapriniScore.getMajorSurgeryHistory()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("sepsis", vteCapriniScore.getSepsis()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("severe_lung_disease_pneumonia", vteCapriniScore.getSevereLungDiseasePneumonia()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("unexplained_or_recurrent_miscarriage", vteCapriniScore.getUnexplainedOrRecurrentMiscarriage()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("other_risk_factors", vteCapriniScore.getOtherRiskFactors()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- 2 Points Risk Factors ---
        rc = addFactor.apply("age_61_74", vteCapriniScore.getAge6174()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("central_venous_catheter", vteCapriniScore.getCentralVenousCatheter()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("arthroscopic_surgery", vteCapriniScore.getArthroscopicSurgery()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("major_surgery_over_45min", vteCapriniScore.getMajorSurgeryOver45min()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("bed_rest_over_72h", vteCapriniScore.getBedRestOver72h()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("malignant_tumor", vteCapriniScore.getMalignantTumor()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("laparoscopic_surgery_over_45min", vteCapriniScore.getLaparoscopicSurgeryOver45min()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("cast_immobilization", vteCapriniScore.getCastImmobilization()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- 3 Points Risk Factors ---
        rc = addFactor.apply("age_75_or_older", vteCapriniScore.getAge75OrOlder()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("thrombosis_family_history", vteCapriniScore.getThrombosisFamilyHistory()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("dvt_pe_history", vteCapriniScore.getDvtPeHistory()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prothrombin_20210a_positive", vteCapriniScore.getProthrombin20210aPositive()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("factor_v_leiden_positive", vteCapriniScore.getFactorVLeidenPositive()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("lupus_anticoagulant_positive", vteCapriniScore.getLupusAnticoagulantPositive()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("elevated_homocysteine", vteCapriniScore.getElevatedHomocysteine()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("antiphospholipid_antibodies", vteCapriniScore.getAntiphospholipidAntibodies()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("heparin_induced_thrombocytopenia", vteCapriniScore.getHeparinInducedThrombocytopenia()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("other_congenital_or_acquired_thrombosis", vteCapriniScore.getOtherCongenitalOrAcquiredThrombosis()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- 5 Points Risk Factors ---
        rc = addFactor.apply("stroke_within_month", vteCapriniScore.getStrokeWithinMonth()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("multiple_trauma_within_month", vteCapriniScore.getMultipleTraumaWithinMonth()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("acute_spinal_cord_injury", vteCapriniScore.getAcuteSpinalCordInjury()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("hip_pelvis_lower_limb_fracture", vteCapriniScore.getHipPelvisLowerLimbFracture()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("elective_lower_limb_joint_replacement", vteCapriniScore.getElectiveLowerLimbJointReplacement()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Bleeding Risk - General ---
        rc = addFactor.apply("active_bleeding", vteCapriniScore.getActiveBleeding()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("bleeding_event_within_3months", vteCapriniScore.getBleedingEventWithin3months()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("severe_renal_or_liver_failure", vteCapriniScore.getSevereRenalOrLiverFailure()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("platelet_count_below_50", vteCapriniScore.getPlateletCountBelow50()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("uncontrolled_hypertension", vteCapriniScore.getUncontrolledHypertension()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("lumbar_epidural_or_spinal_anesthesia", vteCapriniScore.getLumbarEpiduralOrSpinalAnesthesia()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("anticoagulant_antiplatelet_or_thrombolytic", vteCapriniScore.getAnticoagulantAntiplateletOrThrombolytic()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("coagulation_disorder", vteCapriniScore.getCoagulationDisorder()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("active_gi_ulcer", vteCapriniScore.getActiveGiUlcer()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("known_untreated_bleeding_disorder", vteCapriniScore.getKnownUntreatedBleedingDisorder()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Bleeding Risk - Surgery-Related ---
        rc = addFactor.apply("abdominal_surgery_malignant_male_anemia_complex", vteCapriniScore.getAbdominalSurgeryMalignantMaleAnemiaComplex()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("pancreaticoduodenectomy_sepsis_fistula_bleeding", vteCapriniScore.getPancreaticoduodenectomySepsisFistulaBleeding()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("liver_resection_pri_liver_cancer_low_hemoglobin_platelets", vteCapriniScore.getLiverResectionPriLiverCancerLowHemoglobinPlatelets()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("cardiac_surgery_long_cp_time", vteCapriniScore.getCardiacSurgeryLongCpTime()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("thoracic_surgery_pneumonectomy_or_extended", vteCapriniScore.getThoracicSurgeryPneumonectomyOrExtended()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Bleeding Risk - High-Risk Surgery ---
        rc = addFactor.apply("craniotomy", vteCapriniScore.getCraniotomy()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("spinal_surgery", vteCapriniScore.getSpinalSurgery()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("spinal_trauma", vteCapriniScore.getSpinalTrauma()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("free_flap_reconstruction", vteCapriniScore.getFreeFlapReconstruction()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Prevention Assessment ---
        rc = addFactor.apply("prevention_anticoagulant_only_assess", vteCapriniScore.getPreventionAnticoagulantOnlyAssess()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_physical_only_assess", vteCapriniScore.getPreventionPhysicalOnlyAssess()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_anticoagulant_physical_assess", vteCapriniScore.getPreventionAnticoagulantPhysicalAssess()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_unavailable_assess", vteCapriniScore.getPreventionUnavailableAssess()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Prevention Execution ---
        rc = addFactor.apply("prevention_anticoagulant_only_exec", vteCapriniScore.getPreventionAnticoagulantOnlyExec()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_physical_only_exec", vteCapriniScore.getPreventionPhysicalOnlyExec()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_anticoagulant_physical_exec", vteCapriniScore.getPreventionAnticoagulantPhysicalExec()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("prevention_unavailable_exec", vteCapriniScore.getPreventionUnavailableExec()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Basic Measures ---
        rc = addFactor.apply("elevate_limbs", vteCapriniScore.getElevateLimbs()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("ankle_exercise", vteCapriniScore.getAnkleExercise()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("quadriceps_contraction", vteCapriniScore.getQuadricepsContraction()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("deep_breathing_or_balloon", vteCapriniScore.getDeepBreathingOrBalloon()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("quit_smoking_alcohol", vteCapriniScore.getQuitSmokingAlcohol()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("drink_more_water", vteCapriniScore.getDrinkMoreWater()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("maintain_bowel_regular", vteCapriniScore.getMaintainBowelRegular()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("turn_every_2h_or_leg_movement", vteCapriniScore.getTurnEvery2hOrLegMovement()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("get_out_of_bed", vteCapriniScore.getGetOutOfBed()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("other_basic_measures", vteCapriniScore.getOtherBasicMeasures()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Mechanical Measures ---
        rc = addFactor.apply("intermittent_pneumatic_compression", vteCapriniScore.getIntermittentPneumaticCompression()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("graded_compression_stockings", vteCapriniScore.getGradedCompressionStockings()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("foot_vein_pump", vteCapriniScore.getFootVeinPump()); if (rc.getCode() != 0) return new Pair<>(rc, null);

        // --- Pharmacological Measures ---
        rc = addFactor.apply("low_molecular_heparin_injection", vteCapriniScore.getLowMolecularHeparinInjection()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("rivaroxaban", vteCapriniScore.getRivaroxaban()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("warfarin", vteCapriniScore.getWarfarin()); if (rc.getCode() != 0) return new Pair<>(rc, null);
        rc = addFactor.apply("other_pharmacological_measures", vteCapriniScore.getOtherPharmacologicalMeasures()); if (rc.getCode() != 0) return new Pair<>(rc, null);


        // Set total score from entity
        builder.setTotalScore(
            vteCapriniScore.getTotalScore() == null ? 0 : vteCapriniScore.getTotalScore()
        );

        // Return success
        ReturnCode ok = ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
        return new Pair<>(ok, builder.build());
    }

    // --- Helper Methods (Copied/adapted from SofaScorer) ---

    /**
     * Sets scorer info from ScorerInfo object to the entity builder.
     */
    private static void setScorerInfo(VTECapriniScore.VTECapriniScoreBuilder builder, ScoreUtils.ScorerInfo scorerInfo) {
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
        // Initialize isDeleted to false if not provided, matching entity default
        builder.isDeleted(scorerInfo.isDeleted != null ? scorerInfo.isDeleted : false);
        if (scorerInfo.deletedBy != null) builder.deletedBy(scorerInfo.deletedBy);
        if (scorerInfo.deletedByAccountName != null) builder.deletedByAccountName(scorerInfo.deletedByAccountName);
        if (scorerInfo.deletedAt != null) builder.deletedAt(scorerInfo.deletedAt);
    }

    /**
     * Extracts a map of code -> ValueMetaPB from VTECapriniScoreMetaPB.
     */
     public static Map<String, ValueMetaPB> extractValueMetaMap(VTECapriniScoreMetaPB metaPb) {
        Map<String, ValueMetaPB> result = new HashMap<>();
        Stream.of(
                metaPb.getOnePointRiskFactorList(),
                metaPb.getTwoPointsRiskFactorList(),
                metaPb.getThreePointsRiskFactorList(),
                metaPb.getFivePointsRiskFactorList(),
                metaPb.getBleedingRiskGeneralFactorList(),
                metaPb.getBleedingRiskSurgeryRelatedFactorList(),
                metaPb.getBleedingRiskHighRiskSurgeryFactorList(),
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
     * Adds a single factor to the appropriate list within VTECapriniScorePB.Builder.
     * Determines the target list based on the code's presence in the meta definition.
     * Returns OK status code or error if conversion fails or code is unknown.
     */
    private static ReturnCode addFactorToProto(
        VTECapriniScorePB.Builder builder, String code, Object fieldValue,
        Map<String, ValueMetaPB> codeMetaMap,
        List<String> statusCodeMsgList
    ) {
        if (fieldValue == null) return ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK); // Skip null fields

        ValueMetaPB valueMeta = codeMetaMap.get(code);
        if (valueMeta == null) {
            log.error("No ValueMetaPB found for Caprini code: {}", code);
            return ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, code
            );
        }

        // Convert entity field value to GenericValuePB
        GenericValuePB genericValue = buildGenericValue(fieldValue, valueMeta);
        if (genericValue == null) {
            log.error("Failed to convert Caprini fieldValue to GenericValuePB for code: {}", code);
             return ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, code
            );
        }

        // Build the DoctorScoreFactorPB
        DoctorScoreFactorPB factor = DoctorScoreFactorPB.newBuilder()
            .setCode(code)
            .setValue(genericValue)
            .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
            // Score is not set here, only in the score() method if implemented
            .build();

        // Add the factor to the correct list based on the code's group
        // This requires knowledge of which code belongs to which group.
        // A more robust way would involve checking the meta structure, but this mirrors the toEntity logic.
         if (List.of("age_41_60", "lower_limb_edema", "varicose_veins", "obesity_bmi_25", "planned_minor_surgery", "inflammatory_history", "oral_contraceptives_hrt", "pregnancy_or_postpartum", "acute_myocardial_infarction", "congestive_heart_failure", "bedridden_medical_patient", "pulmonary_dysfunction", "major_surgery_history", "sepsis", "severe_lung_disease_pneumonia", "unexplained_or_recurrent_miscarriage", "other_risk_factors").contains(code)) {
            builder.addOnePointRiskFactor(factor);
        } else if (List.of("age_61_74", "central_venous_catheter", "arthroscopic_surgery", "major_surgery_over_45min", "bed_rest_over_72h", "malignant_tumor", "laparoscopic_surgery_over_45min", "cast_immobilization").contains(code)) {
            builder.addTwoPointsRiskFactor(factor);
        } else if (List.of("age_75_or_older", "thrombosis_family_history", "dvt_pe_history", "prothrombin_20210a_positive", "factor_v_leiden_positive", "lupus_anticoagulant_positive", "elevated_homocysteine", "antiphospholipid_antibodies", "heparin_induced_thrombocytopenia", "other_congenital_or_acquired_thrombosis").contains(code)) {
            builder.addThreePointsRiskFactor(factor);
        } else if (List.of("stroke_within_month", "multiple_trauma_within_month", "acute_spinal_cord_injury", "hip_pelvis_lower_limb_fracture", "elective_lower_limb_joint_replacement").contains(code)) {
            builder.addFivePointsRiskFactor(factor);
        } else if (List.of("active_bleeding", "bleeding_event_within_3months", "severe_renal_or_liver_failure", "platelet_count_below_50", "uncontrolled_hypertension", "lumbar_epidural_or_spinal_anesthesia", "anticoagulant_antiplatelet_or_thrombolytic", "coagulation_disorder", "active_gi_ulcer", "known_untreated_bleeding_disorder").contains(code)) {
            builder.addBleedingRiskGeneralFactor(factor);
        } else if (List.of("abdominal_surgery_malignant_male_anemia_complex", "pancreaticoduodenectomy_sepsis_fistula_bleeding", "liver_resection_pri_liver_cancer_low_hemoglobin_platelets", "cardiac_surgery_long_cp_time", "thoracic_surgery_pneumonectomy_or_extended").contains(code)) {
            builder.addBleedingRiskSurgeryRelatedFactor(factor);
        } else if (List.of("craniotomy", "spinal_surgery", "spinal_trauma", "free_flap_reconstruction").contains(code)) {
            builder.addBleedingRiskHighRiskSurgeryFactor(factor);
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
            log.error("Code {} does not belong to any known Caprini factor group in toProto", code);
             return ReturnCodeUtils.getReturnCode(
                statusCodeMsgList, StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, code
            );
        }

        return ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK);
    }


    /**
     * Converts a Java object field value to GenericValuePB based on ValueMetaPB.
     */
    public static GenericValuePB buildGenericValue(Object fieldValue, ValueMetaPB valueMeta) {
        if (fieldValue == null) return null;

        switch (valueMeta.getValueType()) {
            case BOOL:
                return GenericValuePB.newBuilder().setBoolVal((Boolean) fieldValue).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val((Integer) fieldValue).build();
            case FLOAT:
                    // Handle both Float and potentially Double if needed
                    if (fieldValue instanceof Number) {
                        // Using ValueMetaUtils helper which might handle unit conversion if defined in meta
                    return ValueMetaUtils.getValue(valueMeta, ((Number) fieldValue).doubleValue());
                }
                break;
            case STRING:
                    // Assuming TEXT maps to STRING type
                return GenericValuePB.newBuilder().setStrVal((String) fieldValue).build();
            default:
                log.warn("Unsupported valueType for GenericValuePB conversion: {}", valueMeta.getValueType());
                break;
        }
        return null;
    }

    public static Pair<ReturnCode, VTECapriniScorePB> score(
        VTECapriniScoreMetaPB metaPb, VTECapriniScorePB scorePb, List<String> statusCodeMsgList
    ) {
        Pair<ReturnCode, Integer> onePointCntPair = ScoreUtils.countScoreFactors(
            metaPb.getOnePointRiskFactorList(), scorePb.getOnePointRiskFactorList(),
            StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, statusCodeMsgList
        );
        if (onePointCntPair.getFirst().getCode() != 0) return new Pair<>(onePointCntPair.getFirst(), null);

        Pair<ReturnCode, Integer> twoPointsCntPair = ScoreUtils.countScoreFactors(
            metaPb.getTwoPointsRiskFactorList(), scorePb.getTwoPointsRiskFactorList(),
            StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, statusCodeMsgList
        );
        if (twoPointsCntPair.getFirst().getCode() != 0) return new Pair<>(twoPointsCntPair.getFirst(), null);

        Pair<ReturnCode, Integer> threePointsCntPair = ScoreUtils.countScoreFactors(
            metaPb.getThreePointsRiskFactorList(), scorePb.getThreePointsRiskFactorList(),
            StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, statusCodeMsgList
        );
        if (threePointsCntPair.getFirst().getCode() != 0) return new Pair<>(threePointsCntPair.getFirst(), null);

        Pair<ReturnCode, Integer> fivePointsCntPair = ScoreUtils.countScoreFactors(
            metaPb.getFivePointsRiskFactorList(), scorePb.getFivePointsRiskFactorList(),
            StatusCode.UNEXPECTED_VTE_CAPRINI_FACTOR, statusCodeMsgList
        );
        if (fivePointsCntPair.getFirst().getCode() != 0) return new Pair<>(fivePointsCntPair.getFirst(), null);

        Integer score = onePointCntPair.getSecond() + twoPointsCntPair.getSecond() * 2 + threePointsCntPair.getSecond() * 3 + fivePointsCntPair.getSecond() * 5;
        return new Pair<>(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK), scorePb.toBuilder().setTotalScore(score).build());
    }
}