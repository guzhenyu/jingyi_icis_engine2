package com.jingyicare.jingyi_icis_engine.service.doctors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within; // May not be needed for boolean/string checks

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
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Slf4j
public class VTECapriniScorerTests extends TestsBase {
    public VTECapriniScorerTests(@Autowired ConfigProtoService protoService) {
        this.capriniMeta = protoService.getConfig().getDoctorScore().getVteCapriniMeta();
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.codeMetaMap = VTECapriniScorer.extractValueMetaMap(capriniMeta);
    }

    @Test
    public void testToEntityAndToProto() {
        // 1) Construct a sample VTECapriniScorePB
        VTECapriniScorePB.Builder pbBuilder = VTECapriniScorePB.newBuilder();

        // Add sample values for representative fields across all categories
        // --- 1 Point ---
        addCapriniParam(pbBuilder, "age_41_60", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "lower_limb_edema", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "varicose_veins", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "obesity_bmi_25", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "planned_minor_surgery", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "inflammatory_history", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "oral_contraceptives_hrt", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "pregnancy_or_postpartum", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "acute_myocardial_infarction", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "congestive_heart_failure", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "bedridden_medical_patient", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "pulmonary_dysfunction", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "major_surgery_history", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "sepsis", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "severe_lung_disease_pneumonia", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "unexplained_or_recurrent_miscarriage", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "other_risk_factors", "Sample other risk factors", TypeEnumPB.STRING);

        // --- 2 Points ---
        addCapriniParam(pbBuilder, "age_61_74", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "central_venous_catheter", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "arthroscopic_surgery", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "major_surgery_over_45min", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "bed_rest_over_72h", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "malignant_tumor", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "laparoscopic_surgery_over_45min", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "cast_immobilization", false, TypeEnumPB.BOOL);

        // --- 3 Points ---
        addCapriniParam(pbBuilder, "age_75_or_older", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "thrombosis_family_history", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "dvt_pe_history", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "prothrombin_20210a_positive", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "factor_v_leiden_positive", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "lupus_anticoagulant_positive", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "elevated_homocysteine", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "antiphospholipid_antibodies", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "heparin_induced_thrombocytopenia", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "other_congenital_or_acquired_thrombosis", "Sample thrombosis", TypeEnumPB.STRING);

        // --- 5 Points ---
        addCapriniParam(pbBuilder, "stroke_within_month", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "multiple_trauma_within_month", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "acute_spinal_cord_injury", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "hip_pelvis_lower_limb_fracture", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "elective_lower_limb_joint_replacement", true, TypeEnumPB.BOOL);

        // --- Bleeding Risk - General ---
        addCapriniParam(pbBuilder, "active_bleeding", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "bleeding_event_within_3months", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "severe_renal_or_liver_failure", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "platelet_count_below_50", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "uncontrolled_hypertension", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "lumbar_epidural_or_spinal_anesthesia", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "anticoagulant_antiplatelet_or_thrombolytic", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "coagulation_disorder", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "active_gi_ulcer", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "known_untreated_bleeding_disorder", true, TypeEnumPB.BOOL);

        // --- Bleeding Risk - Surgery-Related ---
        addCapriniParam(pbBuilder, "abdominal_surgery_malignant_male_anemia_complex", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "pancreaticoduodenectomy_sepsis_fistula_bleeding", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "liver_resection_pri_liver_cancer_low_hemoglobin_platelets", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "cardiac_surgery_long_cp_time", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "thoracic_surgery_pneumonectomy_or_extended", false, TypeEnumPB.BOOL);

        // --- Bleeding Risk - High-Risk Surgery ---
        addCapriniParam(pbBuilder, "craniotomy", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "spinal_surgery", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "spinal_trauma", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "free_flap_reconstruction", false, TypeEnumPB.BOOL);

        // --- Prevention Assessment ---
        addCapriniParam(pbBuilder, "prevention_anticoagulant_only_assess", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "prevention_physical_only_assess", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "prevention_anticoagulant_physical_assess", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "prevention_unavailable_assess", false, TypeEnumPB.BOOL);

        // --- Prevention Execution ---
        addCapriniParam(pbBuilder, "prevention_anticoagulant_only_exec", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "prevention_physical_only_exec", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "prevention_anticoagulant_physical_exec", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "prevention_unavailable_exec", false, TypeEnumPB.BOOL);

        // --- Basic Measures ---
        addCapriniParam(pbBuilder, "elevate_limbs", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "ankle_exercise", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "quadriceps_contraction", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "deep_breathing_or_balloon", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "quit_smoking_alcohol", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "drink_more_water", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "maintain_bowel_regular", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "turn_every_2h_or_leg_movement", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "get_out_of_bed", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "other_basic_measures", "Sample basic measure", TypeEnumPB.STRING);

        // --- Mechanical Measures ---
        addCapriniParam(pbBuilder, "intermittent_pneumatic_compression", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "graded_compression_stockings", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "foot_vein_pump", true, TypeEnumPB.BOOL);

        // --- Pharmacological Measures ---
        addCapriniParam(pbBuilder, "low_molecular_heparin_injection", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "rivaroxaban", false, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "warfarin", true, TypeEnumPB.BOOL);
        addCapriniParam(pbBuilder, "other_pharmacological_measures", "Sample pharm measure", TypeEnumPB.STRING);

        // Set total score (toEntity doesn't calculate it)
        pbBuilder.setTotalScore(5); // Or any placeholder value

        VTECapriniScorePB originalPb = pbBuilder.build();

        // 2) toEntity
        ScoreUtils.ScorerInfo scorerInfo = new ScoreUtils.ScorerInfo(
            123L, "SURG-02", LocalDateTime.now(),
            "user123", "Dr. Caprini Tester",
            LocalDateTime.now().minusHours(1), LocalDateTime.now(), // Eval times
            LocalDateTime.now(), "modifier_user", // Modified info
            false, null, null, null // Deletion info
        );
        Pair<ReturnCode, VTECapriniScore> entityResult = VTECapriniScorer.toEntity(
            capriniMeta, originalPb, scorerInfo, statusCodeMsgList
        );
        assertThat(entityResult.getFirst().getCode()).isEqualTo(0);
        VTECapriniScore capriniEntity = entityResult.getSecond();
        assertThat(capriniEntity).isNotNull();

        // Validate entity fields against input proto values
        // --- 1 Point ---
        assertThat(capriniEntity.getAge4160()).isTrue();
        assertThat(capriniEntity.getLowerLimbEdema()).isFalse();
        assertThat(capriniEntity.getVaricoseVeins()).isTrue();
        assertThat(capriniEntity.getObesityBmi25()).isFalse();
        assertThat(capriniEntity.getPlannedMinorSurgery()).isTrue();
        assertThat(capriniEntity.getInflammatoryHistory()).isFalse();
        assertThat(capriniEntity.getOralContraceptivesHrt()).isTrue();
        assertThat(capriniEntity.getPregnancyOrPostpartum()).isFalse();
        assertThat(capriniEntity.getAcuteMyocardialInfarction()).isTrue();
        assertThat(capriniEntity.getCongestiveHeartFailure()).isFalse();
        assertThat(capriniEntity.getBedriddenMedicalPatient()).isTrue();
        assertThat(capriniEntity.getPulmonaryDysfunction()).isFalse();
        assertThat(capriniEntity.getMajorSurgeryHistory()).isTrue();
        assertThat(capriniEntity.getSepsis()).isFalse();
        assertThat(capriniEntity.getSevereLungDiseasePneumonia()).isTrue();
        assertThat(capriniEntity.getUnexplainedOrRecurrentMiscarriage()).isFalse();
        assertThat(capriniEntity.getOtherRiskFactors()).isEqualTo("Sample other risk factors");
        // --- 2 Points ---
        assertThat(capriniEntity.getAge6174()).isTrue();
        assertThat(capriniEntity.getCentralVenousCatheter()).isFalse();
        assertThat(capriniEntity.getArthroscopicSurgery()).isTrue();
        assertThat(capriniEntity.getMajorSurgeryOver45min()).isFalse();
        assertThat(capriniEntity.getBedRestOver72h()).isTrue();
        assertThat(capriniEntity.getMalignantTumor()).isFalse();
        assertThat(capriniEntity.getLaparoscopicSurgeryOver45min()).isTrue();
        assertThat(capriniEntity.getCastImmobilization()).isFalse();
        // --- 3 Points ---
        assertThat(capriniEntity.getAge75OrOlder()).isTrue();
        assertThat(capriniEntity.getThrombosisFamilyHistory()).isFalse();
        assertThat(capriniEntity.getDvtPeHistory()).isTrue();
        assertThat(capriniEntity.getProthrombin20210aPositive()).isFalse();
        assertThat(capriniEntity.getFactorVLeidenPositive()).isTrue();
        assertThat(capriniEntity.getLupusAnticoagulantPositive()).isFalse();
        assertThat(capriniEntity.getElevatedHomocysteine()).isTrue();
        assertThat(capriniEntity.getAntiphospholipidAntibodies()).isFalse();
        assertThat(capriniEntity.getHeparinInducedThrombocytopenia()).isTrue();
        assertThat(capriniEntity.getOtherCongenitalOrAcquiredThrombosis()).isEqualTo("Sample thrombosis");
        // --- 5 Points ---
        assertThat(capriniEntity.getStrokeWithinMonth()).isTrue();
        assertThat(capriniEntity.getMultipleTraumaWithinMonth()).isFalse();
        assertThat(capriniEntity.getAcuteSpinalCordInjury()).isTrue();
        assertThat(capriniEntity.getHipPelvisLowerLimbFracture()).isFalse();
        assertThat(capriniEntity.getElectiveLowerLimbJointReplacement()).isTrue();
        // --- Bleeding Risk - General ---
        assertThat(capriniEntity.getActiveBleeding()).isFalse();
        assertThat(capriniEntity.getBleedingEventWithin3months()).isTrue();
        assertThat(capriniEntity.getSevereRenalOrLiverFailure()).isFalse();
        assertThat(capriniEntity.getPlateletCountBelow50()).isTrue();
        assertThat(capriniEntity.getUncontrolledHypertension()).isFalse();
        assertThat(capriniEntity.getLumbarEpiduralOrSpinalAnesthesia()).isTrue();
        assertThat(capriniEntity.getAnticoagulantAntiplateletOrThrombolytic()).isFalse();
        assertThat(capriniEntity.getCoagulationDisorder()).isTrue();
        assertThat(capriniEntity.getActiveGiUlcer()).isFalse();
        assertThat(capriniEntity.getKnownUntreatedBleedingDisorder()).isTrue();
        // --- Bleeding Risk - Surgery-Related ---
        assertThat(capriniEntity.getAbdominalSurgeryMalignantMaleAnemiaComplex()).isFalse();
        assertThat(capriniEntity.getPancreaticoduodenectomySepsisFistulaBleeding()).isTrue();
        assertThat(capriniEntity.getLiverResectionPriLiverCancerLowHemoglobinPlatelets()).isFalse();
        assertThat(capriniEntity.getCardiacSurgeryLongCpTime()).isTrue();
        assertThat(capriniEntity.getThoracicSurgeryPneumonectomyOrExtended()).isFalse();
        // --- Bleeding Risk - High-Risk Surgery ---
        assertThat(capriniEntity.getCraniotomy()).isTrue();
        assertThat(capriniEntity.getSpinalSurgery()).isFalse();
        assertThat(capriniEntity.getSpinalTrauma()).isTrue();
        assertThat(capriniEntity.getFreeFlapReconstruction()).isFalse();
        // --- Prevention Assessment ---
        assertThat(capriniEntity.getPreventionAnticoagulantOnlyAssess()).isTrue();
        assertThat(capriniEntity.getPreventionPhysicalOnlyAssess()).isFalse();
        assertThat(capriniEntity.getPreventionAnticoagulantPhysicalAssess()).isTrue();
        assertThat(capriniEntity.getPreventionUnavailableAssess()).isFalse();
        // --- Prevention Execution ---
        assertThat(capriniEntity.getPreventionAnticoagulantOnlyExec()).isTrue();
        assertThat(capriniEntity.getPreventionPhysicalOnlyExec()).isFalse();
        assertThat(capriniEntity.getPreventionAnticoagulantPhysicalExec()).isTrue();
        assertThat(capriniEntity.getPreventionUnavailableExec()).isFalse();
        // --- Basic Measures ---
        assertThat(capriniEntity.getElevateLimbs()).isTrue();
        assertThat(capriniEntity.getAnkleExercise()).isFalse();
        assertThat(capriniEntity.getQuadricepsContraction()).isTrue();
        assertThat(capriniEntity.getDeepBreathingOrBalloon()).isFalse();
        assertThat(capriniEntity.getQuitSmokingAlcohol()).isTrue();
        assertThat(capriniEntity.getDrinkMoreWater()).isFalse();
        assertThat(capriniEntity.getMaintainBowelRegular()).isTrue();
        assertThat(capriniEntity.getTurnEvery2hOrLegMovement()).isFalse();
        assertThat(capriniEntity.getGetOutOfBed()).isTrue();
        assertThat(capriniEntity.getOtherBasicMeasures()).isEqualTo("Sample basic measure");
        // --- Mechanical Measures ---
        assertThat(capriniEntity.getIntermittentPneumaticCompression()).isTrue();
        assertThat(capriniEntity.getGradedCompressionStockings()).isFalse();
        assertThat(capriniEntity.getFootVeinPump()).isTrue();
        // --- Pharmacological Measures ---
        assertThat(capriniEntity.getLowMolecularHeparinInjection()).isTrue();
        assertThat(capriniEntity.getRivaroxaban()).isFalse();
        assertThat(capriniEntity.getWarfarin()).isTrue();
        assertThat(capriniEntity.getOtherPharmacologicalMeasures()).isEqualTo("Sample pharm measure");

        // Validate scorerInfo fields
        assertThat(capriniEntity.getPid()).isEqualTo(123L);
        assertThat(capriniEntity.getDeptId()).isEqualTo("SURG-02");
        assertThat(capriniEntity.getScoredBy()).isEqualTo("user123");
        assertThat(capriniEntity.getScoredByAccountName()).isEqualTo("Dr. Caprini Tester");
        assertThat(capriniEntity.getIsDeleted()).isFalse(); // Check default/set value
        assertThat(capriniEntity.getModifiedBy()).isEqualTo("modifier_user");


        // 3) toProto
        Pair<ReturnCode, VTECapriniScorePB> protoResult = VTECapriniScorer.toProto(
            capriniMeta, capriniEntity, statusCodeMsgList // Removed rules
        );
        assertThat(protoResult.getFirst().getCode()).isEqualTo(0);
        VTECapriniScorePB newPb = protoResult.getSecond();
        assertThat(newPb).isNotNull();

        // Validate proto fields extracted back match original inputs
        // --- 1 Point ---
        assertThat(getCapriniParamBoolValue(newPb, "age_41_60")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "lower_limb_edema")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "varicose_veins")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "obesity_bmi_25")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "planned_minor_surgery")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "inflammatory_history")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "oral_contraceptives_hrt")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "pregnancy_or_postpartum")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "acute_myocardial_infarction")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "congestive_heart_failure")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "bedridden_medical_patient")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "pulmonary_dysfunction")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "major_surgery_history")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "sepsis")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "severe_lung_disease_pneumonia")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "unexplained_or_recurrent_miscarriage")).isFalse();
        assertThat(getCapriniParamStringValue(newPb, "other_risk_factors")).isEqualTo("Sample other risk factors");
        // --- 2 Points ---
        assertThat(getCapriniParamBoolValue(newPb, "age_61_74")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "central_venous_catheter")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "arthroscopic_surgery")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "major_surgery_over_45min")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "bed_rest_over_72h")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "malignant_tumor")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "laparoscopic_surgery_over_45min")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "cast_immobilization")).isFalse();
        // --- 3 Points ---
        assertThat(getCapriniParamBoolValue(newPb, "age_75_or_older")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "thrombosis_family_history")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "dvt_pe_history")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "prothrombin_20210a_positive")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "factor_v_leiden_positive")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "lupus_anticoagulant_positive")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "elevated_homocysteine")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "antiphospholipid_antibodies")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "heparin_induced_thrombocytopenia")).isTrue();
        assertThat(getCapriniParamStringValue(newPb, "other_congenital_or_acquired_thrombosis")).isEqualTo("Sample thrombosis");
        // --- 5 Points ---
        assertThat(getCapriniParamBoolValue(newPb, "stroke_within_month")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "multiple_trauma_within_month")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "acute_spinal_cord_injury")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "hip_pelvis_lower_limb_fracture")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "elective_lower_limb_joint_replacement")).isTrue();
        // --- Bleeding Risk - General ---
        assertThat(getCapriniParamBoolValue(newPb, "active_bleeding")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "bleeding_event_within_3months")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "severe_renal_or_liver_failure")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "platelet_count_below_50")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "uncontrolled_hypertension")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "lumbar_epidural_or_spinal_anesthesia")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "anticoagulant_antiplatelet_or_thrombolytic")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "coagulation_disorder")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "active_gi_ulcer")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "known_untreated_bleeding_disorder")).isTrue();
        // --- Bleeding Risk - Surgery-Related ---
        assertThat(getCapriniParamBoolValue(newPb, "abdominal_surgery_malignant_male_anemia_complex")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "pancreaticoduodenectomy_sepsis_fistula_bleeding")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "liver_resection_pri_liver_cancer_low_hemoglobin_platelets")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "cardiac_surgery_long_cp_time")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "thoracic_surgery_pneumonectomy_or_extended")).isFalse();
        // --- Bleeding Risk - High-Risk Surgery ---
        assertThat(getCapriniParamBoolValue(newPb, "craniotomy")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "spinal_surgery")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "spinal_trauma")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "free_flap_reconstruction")).isFalse();
        // --- Prevention Assessment ---
        assertThat(getCapriniParamBoolValue(newPb, "prevention_anticoagulant_only_assess")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "prevention_physical_only_assess")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "prevention_anticoagulant_physical_assess")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "prevention_unavailable_assess")).isFalse();
        // --- Prevention Execution ---
        assertThat(getCapriniParamBoolValue(newPb, "prevention_anticoagulant_only_exec")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "prevention_physical_only_exec")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "prevention_anticoagulant_physical_exec")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "prevention_unavailable_exec")).isFalse();
        // --- Basic Measures ---
        assertThat(getCapriniParamBoolValue(newPb, "elevate_limbs")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "ankle_exercise")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "quadriceps_contraction")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "deep_breathing_or_balloon")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "quit_smoking_alcohol")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "drink_more_water")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "maintain_bowel_regular")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "turn_every_2h_or_leg_movement")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "get_out_of_bed")).isTrue();
        assertThat(getCapriniParamStringValue(newPb, "other_basic_measures")).isEqualTo("Sample basic measure");
        // --- Mechanical Measures ---
        assertThat(getCapriniParamBoolValue(newPb, "intermittent_pneumatic_compression")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "graded_compression_stockings")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "foot_vein_pump")).isTrue();
        // --- Pharmacological Measures ---
        assertThat(getCapriniParamBoolValue(newPb, "low_molecular_heparin_injection")).isTrue();
        assertThat(getCapriniParamBoolValue(newPb, "rivaroxaban")).isFalse();
        assertThat(getCapriniParamBoolValue(newPb, "warfarin")).isTrue();
        assertThat(getCapriniParamStringValue(newPb, "other_pharmacological_measures")).isEqualTo("Sample pharm measure");

        // Total score should also match (though it wasn't calculated, just passed through)
        assertThat(newPb.getTotalScore()).isEqualTo(5);
    }

    // =============== Helper Methods for VTECapriniScorePB ===============

    /**
     * Adds a factor to the correct list in the VTECapriniScorePB.Builder.
     */
    private void addCapriniParam(
        VTECapriniScorePB.Builder builder, String code, Object value, TypeEnumPB type
    ) {
        ValueMetaPB valueMeta = codeMetaMap.get(code);
        if (valueMeta == null) {
            log.warn("ValueMeta not found for code '{}' in test setup, using default type {}", code, type);
            valueMeta = ValueMetaPB.newBuilder().setValueType(type).build(); // Basic fallback
        }

        GenericValuePB genericValue = VTECapriniScorer.buildGenericValue(value, valueMeta); // Use static helper
        if (genericValue == null) {
            log.error("Failed to create GenericValuePB for code '{}' in test", code);
            return;
        }

        DoctorScoreFactorPB factor = DoctorScoreFactorPB.newBuilder()
            .setCode(code)
            .setValue(genericValue)
            .setValueStr(ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta))
            .build();

        // Add to the correct list based on the code's group (mirroring VTECapriniScorer.addFactorToProto)
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
            log.error("Code {} does not belong to any known Caprini factor group in test setup helper", code);
            // Optionally throw an exception or handle error
        }
    }


    /**
     * Gets the boolean value for a specific code from VTECapriniScorePB.
     * Searches across all relevant lists. Returns false if not found.
     */
    private boolean getCapriniParamBoolValue(VTECapriniScorePB scorePb, String code) {
        return findCapriniFactor(scorePb, code)
            .map(factor -> factor.getValue().getBoolVal())
            .orElse(false); // Default to false if not found or not boolean
    }

    /**
     * Gets the string value for a specific code from VTECapriniScorePB.
     * Searches across all relevant lists. Returns null if not found.
     */
    private String getCapriniParamStringValue(VTECapriniScorePB scorePb, String code) {
         return findCapriniFactor(scorePb, code)
            .map(factor -> factor.getValue().getStrVal())
            .orElse(null); // Default to null if not found or not string
    }

     /**
     * Finds a DoctorScoreFactorPB by code within any list in VTECapriniScorePB.
     */
    private Optional<DoctorScoreFactorPB> findCapriniFactor(VTECapriniScorePB scorePb, String code) {
        return Stream.of(
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
            .filter(factor -> factor.getCode().equals(code))
            .findFirst();
    }

    private final VTECapriniScoreMetaPB capriniMeta;
    private final List<String> statusCodeMsgList;
    private Map<String, ValueMetaPB> codeMetaMap; // Helper map
}