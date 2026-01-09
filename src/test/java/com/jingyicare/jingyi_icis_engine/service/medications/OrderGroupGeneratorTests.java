package com.jingyicare.jingyi_icis_engine.service.medications;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedOrderGroupSettingsPB.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class OrderGroupGeneratorTests extends TestsBase {
    public OrderGroupGeneratorTests(
        @Autowired OrderGroupGenerator orderGroupGenerator,
        @Autowired PatientRecordRepository patientRecordRepo,
        @Autowired MedicalOrderRepository medOrdRepo,
        @Autowired MedicationOrderGroupRepository medOrdGroupRepo,
        @Autowired MedicationFrequencyRepository freqRepo,
        @Autowired ConfigProtoService configProtoService,
        @Autowired MedicationConfig medCfg,
        @Autowired MedicationDictionary medDict
    ) {
        this.deptId = "10003";

        this.IN_ICU_VAL = configProtoService.getConfig().getPatient().getEnumsV2().getAdmissionStatusList()
            .stream()
            .filter(e -> e.getName().equals(configProtoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .get()
            .getId();

        this.orderGroupGenerator = orderGroupGenerator;
        this.patientRecordRepo = patientRecordRepo;
        this.medOrdRepo = medOrdRepo;
        this.medOrdGroupRepo = medOrdGroupRepo;
        this.configProtoService = configProtoService;
        this.medCfg = medCfg;
        this.medDict = medDict;
        this.patientTestUtils = new PatientTestUtils();
        this.medTestUtils = new MedicationTestUtils(configProtoService.getConfig().getMedication(), freqRepo);
        this.ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE = configProtoService.getConfig()
            .getMedication().getEnums().getOneTimeExecutionStopStrategyIgnore().getId();

        initMedOrderGroupSettings();
    }

    @Test
    public void testGenerateSimpliestOrderGroup() {
        // Patient: hisPatientId101, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(101L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        // SystemSetting
        assertThat(medCfg.isOrderTypeValid(medOgSettings, "西药", "")).isTrue();
        assertThat(medCfg.isOrderTypeValid(medOgSettings, "中药", "")).isTrue();
        assertThat(medCfg.isOrderTypeValid(medOgSettings, "其他", "")).isFalse();
        assertThat(medCfg.isOrderStatusValid(medOgSettings, "未审核")).isFalse();
        assertThat(medCfg.isOrderStatusValid(medOgSettings, "已审核")).isTrue();
        assertThat(medCfg.isOrderRouteValid(medOgSettings, "不允许的口服")).isFalse();
        assertThat(medCfg.isOrderRouteValid(medOgSettings, "静推")).isTrue();

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_1", patient.getHisPatientId(), "group_id_1", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId101");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_1");
        assertThat(orderGroups.get(0).getDeptId()).isEqualTo(deptId);
    }

    @Test
    public void testFilterMedicalOrders() {
        // Patient: hisPatientId102, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(102L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id", patient.getHisPatientId(), "group_id_2", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));

        // 中药
        medOrd.setOrderId("order_id_2");
        medOrd.setOrderType("其他");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setOrderType("西药");

        // 未审核
        medOrd.setOrderId("order_id_3");
        medOrd.setStatus("未审核");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setStatus("已审核");

        // 不允许的口服
        medOrd.setOrderId("order_id_4");
        medOrd.setAdministrationRouteCode("不允许的口服");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setAdministrationRouteCode("code_1");

        assertThat(medOrdRepo.findByHisPatientIdAndAdmissionTime(
            "hisPatientId102", TimeUtils.getLocalTime(2024, 9, 10))).hasSize(3);
        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(0);
    }

    @Test
    public void testInconsistentFields() {
        // Patient: hisPatientId103, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(103L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id", patient.getHisPatientId(), "group_id_3", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));

        // 1st order
        medOrd.setOrderId("order_id_5");
        medOrd = medOrdRepo.save(medOrd);

        // inconsistent field: ordering_doctor
        medOrd.setOrderId("order_id_6");
        medOrd.setOrderingDoctor("doctor_2");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setOrderingDoctor("doctor_1");

        // inconsistent field: ordering_doctor_id
        medOrd.setOrderId("order_id_7");
        medOrd.setOrderingDoctorId("doctor_2-Id");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setOrderingDoctorId("doctor_1-Id");

        // inconsistent field: order_type
        medOrd.setOrderId("order_id_8");
        medOrd.setOrderType("中药");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setOrderType("西药");

        // inconsistent field: status
        medOrd.setOrderId("order_id_9");
        medOrd.setStatus("已开立");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setStatus("已审核");

        // inconsistent field: order_time
        medOrd.setOrderId("order_id_10");
        medOrd.setOrderTime(TimeUtils.getLocalTime(2024, 9, 12));
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setOrderTime(TimeUtils.getLocalTime(2024, 9, 11));

        // inconsistent field: order_duration_type
        medOrd.setOrderId("order_id_11");
        medOrd.setOrderDurationType(1);
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setOrderDurationType(0);

        // inconsistent field: plan_time
        medOrd.setOrderId("order_id_12");
        medOrd.setPlanTime(TimeUtils.getLocalTime(2024, 9, 12));
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setPlanTime(TimeUtils.getLocalTime(2024, 9, 11));

        // inconsistent field: freq_code
        medOrd.setOrderId("order_id_13");
        medOrd.setFreqCode("freq_code_2");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setFreqCode("freq_code_1");

        // inconsistent field: first_day_exe_count
        medOrd.setOrderId("order_id_14");
        medOrd.setFirstDayExeCount(1);
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setFirstDayExeCount(0);

        // inconsistent field: administration_route_code
        medOrd.setOrderId("order_id_15");
        medOrd.setAdministrationRouteCode("route_code_2");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setAdministrationRouteCode("route_code_1");

        // inconsistent field: administration_route_name
        medOrd.setOrderId("order_id_16");
        medOrd.setAdministrationRouteName("route_name_2");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setAdministrationRouteName("route_name_1");

        // inconsistent field: reviewer
        medOrd.setOrderId("order_id_17");
        medOrd.setReviewer("reviewer_2");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setReviewer("reviewer_1");

        // inconsistent field: reviewer_id
        medOrd.setOrderId("order_id_18");
        medOrd.setReviewerId("reviewer_2-Id");
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setReviewerId("reviewer_1-Id");

        // inconsistent field: review_time
        medOrd.setOrderId("order_id_19");
        medOrd.setReviewTime(TimeUtils.getLocalTime(2024, 9, 12));
        medOrd = medOrdRepo.save(medOrd);
        medOrd.setReviewTime(TimeUtils.getLocalTime(2024, 9, 11));

        assertThat(medOrdRepo.findByHisPatientIdAndAdmissionTime(
            "hisPatientId103", TimeUtils.getLocalTime(2024, 9, 10))).hasSize(15);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId103");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_3");
        Set<String> inconsistentFields = new HashSet<>(
            Arrays.asList(orderGroups.get(0).getInconsistencyExplanation().split(",")));
        assertThat(inconsistentFields).hasSize(14);
        assertThat(inconsistentFields).contains("ordering_doctor");
        assertThat(inconsistentFields).contains("ordering_doctor_id");
        assertThat(inconsistentFields).contains("order_type");
        assertThat(inconsistentFields).contains("status");
        assertThat(inconsistentFields).contains("order_time");
        assertThat(inconsistentFields).contains("order_duration_type");
        assertThat(inconsistentFields).contains("plan_time");
        assertThat(inconsistentFields).contains("freq_code");
        assertThat(inconsistentFields).contains("first_day_exe_count");
        assertThat(inconsistentFields).contains("administration_route_code");
        assertThat(inconsistentFields).contains("administration_route_name");
        assertThat(inconsistentFields).contains("reviewer");
        assertThat(inconsistentFields).contains("reviewer_id");
        assertThat(inconsistentFields).contains("review_time");
    }

    @Test
    public void testConsistentFields() {
        // Patient: hisPatientId104, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(104L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        // order_id_20
        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_20", patient.getHisPatientId(), "group_id_4", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        // order_id_21
        medOrd.setOrderId("order_id_21");
        medOrd.setOrderCode("med_code_4");
        medOrd.setOrderName("med_name_4");
        medOrd.setSpec("spec_2");
        medOrd.setDose(2.0);
        medOrd.setDoseUnit("g");
        medOrd.setMedicationType("med_type_2");
        medOrd = medOrdRepo.save(medOrd);

        assertThat(medOrdRepo.findByHisPatientIdAndAdmissionTime(
            "hisPatientId104", TimeUtils.getLocalTime(2024, 9, 10))).hasSize(2);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId104");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_4");
        assertThat(orderGroups.get(0).getInconsistencyExplanation()).isEmpty();
        // test running date should be after 2024-9-15
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(0);  // medication_order_validity_type_valid

        MedicalOrderIdsPB orderIds = ProtoUtils.decodeOrderIds(
            orderGroups.get(0).getMedicalOrderIds());
        assertThat(orderIds.getIdList()).hasSize(2);
        assertThat(orderIds.getIdList()).contains("order_id_20");
        assertThat(orderIds.getIdList()).contains("order_id_21");

        MedicationDosageGroupPB medDosageGroup = ProtoUtils.decodeDosageGroup(
            orderGroups.get(0).getMedicationDosageGroup());
        assertThat(medDosageGroup.getMdList()).hasSize(2);

        MedicationDosagePB dosage = medDosageGroup.getMdList().get(0);
        assertThat(dosage.getCode()).isEqualTo("med_code_3");
        assertThat(dosage.getName()).isEqualTo("med_name_3");
        assertThat(dosage.getSpec()).isEqualTo("spec_1");
        assertThat(dosage.getDose()).isEqualTo(1.0);
        assertThat(dosage.getDoseUnit()).isEqualTo("mg");
        assertThat(dosage.getType()).isEmpty();

        dosage = medDosageGroup.getMdList().get(1);
        assertThat(dosage.getCode()).isEqualTo("med_code_4");
        assertThat(dosage.getName()).isEqualTo("med_name_4");
        assertThat(dosage.getSpec()).isEqualTo("spec_2");
        assertThat(dosage.getDose()).isEqualTo(2.0);
        assertThat(dosage.getDoseUnit()).isEqualTo("g");
        assertThat(dosage.getType()).isEqualTo("med_type_2");

        List<String> medCodeList = medDict.findUnconfirmedMedications().stream()
            .map(med -> med.getCode()).toList();
        assertThat(medCodeList).contains("med_code_3");
        assertThat(medCodeList).contains("med_code_4");
    }

    @Test
    public void testOrderGroupCanceled() {
        // Patient: hisPatientId105, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(105L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_22", patient.getHisPatientId(), "group_id_5", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd.setStopTime(TimeUtils.getLocalTime(2024, 9, 12));
        medOrd.setCancelTime(TimeUtils.getLocalTime(2024, 9, 13));
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        // test running date should be after 2024-9-15
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(2);  // medication_order_validity_type_canceled
    }

    @Test
    public void testOrderGroupCanceledWithoutTimestamp() {
        // Patient: hisPatientId105, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(111L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_28", patient.getHisPatientId(), "group_id_10", "doctor_1",
            deptId, "西药", "已取消", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd.setStopTime(TimeUtils.getLocalTime(2024, 9, 12));
        medOrd.setCancelTime(null);
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getCancelTime()).isNull();
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(2);  // medication_order_validity_type_canceled
    }

    @Test
    public void testOrderGroupStopped() {
        // Patient: hisPatientId106, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(106L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_23", patient.getHisPatientId(), "group_id_6", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd.setStopTime(TimeUtils.getLocalTime(2024, 9, 12));
        medOrd.setCancelTime(null);
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        // test running date should be after 2024-9-15
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(1);  // medication_order_validity_type_stopped
    }

    @Test
    public void testOverwriteOrderGroup() {
        // Patient: hisPatientId107, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(107L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_24", patient.getHisPatientId(), "group_id_7", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId107");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_7");
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(0);  // medication_order_validity_type_valid
        final Long mogId1 = orderGroups.get(0).getId();

        MedicalOrderIdsPB orderIds = ProtoUtils.decodeOrderIds(
            orderGroups.get(0).getMedicalOrderIds());
        assertThat(orderIds.getIdList()).hasSize(1);
        assertThat(orderIds.getIdList()).contains("order_id_24");

        MedicationDosageGroupPB medDosageGroup = ProtoUtils.decodeDosageGroup(
            orderGroups.get(0).getMedicationDosageGroup());
        assertThat(medDosageGroup.getMdList()).hasSize(1);
        assertThat(medDosageGroup.getMdList().get(0).getCode()).isEqualTo("med_code_3");

        // medical order is updated
        medOrd.setOrderId("order_id_25");
        medOrd.setOrderCode("med_code_4");
        medOrd.setOrderName("med_name_4");
        medOrd.setSpec("spec_2");
        medOrd.setDose(2.0);
        medOrd.setDoseUnit("g");
        medOrd.setMedicationType("med_type_2");
        medOrd = medOrdRepo.save(medOrd);

        orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId107");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_7");
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(0);  // medication_order_validity_type_valid
        final Long mogId2 = orderGroups.get(0).getId();

        orderIds = ProtoUtils.decodeOrderIds(orderGroups.get(0).getMedicalOrderIds());
        assertThat(orderIds.getIdList()).hasSize(2);
        assertThat(orderIds.getIdList()).contains("order_id_24");
        assertThat(orderIds.getIdList()).contains("order_id_25");

        medDosageGroup = ProtoUtils.decodeDosageGroup(orderGroups.get(0).getMedicationDosageGroup());
        assertThat(medDosageGroup.getMdList()).hasSize(2);
        assertThat(medDosageGroup.getMdList().get(0).getCode()).isEqualTo("med_code_3");
        assertThat(medDosageGroup.getMdList().get(1).getCode()).isEqualTo("med_code_4");

        // mogId1 was overwritten.
        List<MedicationOrderGroup> mogList = medOrdGroupRepo.findByPatientIdAndGroupId(
            patient.getId(), "group_id_7");
        assertThat(mogList).hasSize(2);
        assertThat(mogList.get(0).getId()).isEqualTo(mogId2);
        assertThat(mogList.get(0).getOrderValidity()).isEqualTo(0);
        assertThat(mogList.get(1).getId()).isEqualTo(mogId1);
        assertThat(mogList.get(1).getOrderValidity()).isEqualTo(3);
    }

    @Test
    public void testUpdateOrderGroup() {
        // Patient: hisPatientId108, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(108L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_26", patient.getHisPatientId(), "group_id_8", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId108");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_8");
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(0);  // medication_order_validity_type_valid
        final Long mogId1 = orderGroups.get(0).getId();

        medOrd.setStopTime(TimeUtils.getLocalTime(2024, 9, 12));
        medOrd = medOrdRepo.save(medOrd);
        assertThat(medOrdRepo.findByHisPatientIdAndAdmissionTime(
            "hisPatientId108", TimeUtils.getLocalTime(2024, 9, 10))).hasSize(1);

        orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId108");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_8");
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(1);  // medication_order_validity_type_stopped
        assertThat(orderGroups.get(0).getId()).isEqualTo(mogId1);

        List<MedicationOrderGroup> mogList = medOrdGroupRepo.findByPatientIdAndGroupId(
            patient.getId(), "group_id_8");
        assertThat(mogList).hasSize(1);
        assertThat(mogList.get(0).getId()).isEqualTo(mogId1);
        assertThat(mogList.get(0).getOrderValidity()).isEqualTo(1);
    }

    @Test
    public void testGenerateDurationOnceOrderGroup() {
        // Patient: hisPatientId110, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(110L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_27", patient.getHisPatientId(), "group_id_9", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            1 /*临时医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId110");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_9");

        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(4);  // medication_order_validity_type_duration_one_time
    }

    @Test
    public void testGenerateNonHisOrderGroup() {
        // Patient: hisPatientId109, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(109L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        // MedicationDosageGroupPB
        MedicationDosageGroupPB dosageGroup = MedicationDosageGroupPB.newBuilder()
            .addMd(MedicationDosagePB.newBuilder()
                .setCode("med_code_5")
                .setName("med_name_5")
                .setSpec("spec_5")
                .setDose(1.0)
                .setDoseUnit("mg")
                .build())
            .addMd(MedicationDosagePB.newBuilder()
                .setCode("med_code_6")
                .setName("med_name_6")
                .setSpec("spec_6")
                .setDose(1.0)
                .setDoseUnit("ml")
                .build())
            .build();
        final LocalDateTime orderTime = TimeUtils.getLocalTime(2024, 9, 11);
        MedicationOrderGroup mog = orderGroupGenerator.generateNonHisOrderGroup(
            patient, medOgSettings, "doctor_1", orderTime, orderTime, dosageGroup, "route_code_1", "route_name_1", "");
        assertThat(mog.getHisPatientId()).isEqualTo("hisPatientId109");
        assertThat(StrUtils.isBlank(mog.getGroupId())).isTrue();
        assertThat(StrUtils.isBlank(mog.getMedicalOrderIds())).isTrue();
        assertThat(mog.getHisMrn()).isEqualTo("mrn109");
        assertThat(mog.getOrderingDoctor()).isEqualTo("doctor_1");
        assertThat(mog.getOrderingDoctorId()).isEqualTo("doctor_1");
        assertThat(StrUtils.isBlank(mog.getOrderType())).isTrue();
        assertThat(StrUtils.isBlank(mog.getStatus())).isTrue();
        assertThat(mog.getOrderTime()).isEqualTo(orderTime);
        assertThat(mog.getStopTime()).isNull();
        assertThat(mog.getCancelTime()).isNull();
        assertThat(mog.getOrderValidity()).isEqualTo(5);  // medication_order_validity_type_manual_entry
        assertThat(StrUtils.isBlank(mog.getInconsistencyExplanation())).isTrue();

        MedicationDosageGroupPB newDosageGroup = ProtoUtils.decodeDosageGroup(
            mog.getMedicationDosageGroup());
        assertThat(newDosageGroup.getMdList()).hasSize(2);
        assertThat(newDosageGroup.getMdList().get(0).getCode()).isEqualTo("med_code_5");
        assertThat(newDosageGroup.getMdList().get(1).getCode()).isEqualTo("med_code_6");

        assertThat(mog.getOrderDurationType()).isEqualTo(2);  // order_duration_type_manual_entry
        assertThat(mog.getPlanTime()).isEqualTo(orderTime);
        assertThat(StrUtils.isBlank(mog.getFreqCode())).isTrue();
        assertThat(mog.getFirstDayExeCount()).isNull();
        assertThat(mog.getAdministrationRouteCode()).isEqualTo("route_code_1");
        assertThat(mog.getAdministrationRouteName()).isEqualTo("route_name_1");
        assertThat(StrUtils.isBlank(mog.getReviewer())).isTrue();
        assertThat(StrUtils.isBlank(mog.getReviewerId())).isTrue();
        assertThat(mog.getReviewTime()).isNull();

        List<String> medCodeList = medDict.findUnconfirmedMedications().stream()
            .map(med -> med.getCode()).toList();
        assertThat(medCodeList).contains("med_code_5");
        assertThat(medCodeList).contains("med_code_6");
    }

    @Test
    public void testOrderGroupDisplayName_Normal() {
        // Patient: hisPatientId108, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(112L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_29", patient.getHisPatientId(), "group_id_11", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_2", "med_name_2", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        medOrd = medTestUtils.newMedicalOrder(
            "order_id_30", patient.getHisPatientId(), "group_id_11", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_1", "med_name_1", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId112");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_11");
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(0);  // medication_order_validity_type_valid
        final MedicationDosageGroupPB medDosageGroup = ProtoUtils.decodeDosageGroup(
            orderGroups.get(0).getMedicationDosageGroup());
        assertThat(medDosageGroup.getDisplayName()).isEqualTo("med_name_2 + med_name_1");
    }

    @Test
    public void testOrderGroupDisplayName_Deprioritized() {
        // Patient: hisPatientId108, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(113L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_31", patient.getHisPatientId(), "group_id_12", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_2", "med_name_2_氯化钠", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        medOrd = medTestUtils.newMedicalOrder(
            "order_id_32", patient.getHisPatientId(), "group_id_12", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_1", "med_name_1", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        medOrd = medTestUtils.newMedicalOrder(
            "order_id_33", patient.getHisPatientId(), "group_id_12", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "spec_1", 1.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId113");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_12");
        assertThat(orderGroups.get(0).getOrderValidity()).isEqualTo(0);  // medication_order_validity_type_valid
        final MedicationDosageGroupPB medDosageGroup = ProtoUtils.decodeDosageGroup(
            orderGroups.get(0).getMedicationDosageGroup());
        assertThat(medDosageGroup.getDisplayName()).isEqualTo("med_name_1 + med_name_3 + med_name_2_氯化钠");
    }

    private void initMedOrderGroupSettings() {
        this.medOgSettings = medTestUtils.newMedOrderGroupSettings(
            new ArrayList<>(List.of("西药", "中药")) /*allowedOrderTypes*/,
            new ArrayList<>(List.of("未审核")) /*denyStatuses*/,
            new ArrayList<>(List.of("不允许的口服")) /*denyRouteCodes*/,
            false /*omitFirstDayOrderExecution*/,
            false /*checkOrderTimeForLongTermExe*/,
            false /*forceGenExeOrdDay1*/,
            false /*checkOrderTimeForOneTimeExe*/,
            ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE /*oneTimeExeStopStrategy*/,
            8 /* notStartedExeRecAdvanceHours */,
            "已取消" /* status_canceled_txt */,
            new ArrayList<>(List.of("氯化钠")) /*deprioritizedMedNames*/
        );
        this.medOgSettings = medOgSettings.toBuilder()
            .addSpecialOrderType(
                SpecialOrderTypePB.newBuilder()
                    .setName("输血")
                    .addKeyword("白蛋白")
                    .addKeyword("血浆")
                    .build()
            )
            .build();
        medCfg.setMedOrderGroupSettings(deptId, medOgSettings, "admin");
    }

    @Test
    public void testCalculateIntakeVolume() {
        // Patient: hisPatientId114, inIcu, 2024-9-10(admissionTime)
        PatientRecord patient = patientTestUtils.newPatientRecord(114L, IN_ICU_VAL, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2024, 9, 10));
        patient = patientRecordRepo.save(patient);

        assertThat(medCfg.isOrderTypeValid(medOgSettings, "西药", "")).isTrue();
        assertThat(medCfg.isOrderTypeValid(medOgSettings, "中药", "")).isTrue();
        assertThat(medCfg.isOrderTypeValid(medOgSettings, "其他", "")).isFalse();
        assertThat(medCfg.isOrderStatusValid(medOgSettings, "未审核")).isFalse();
        assertThat(medCfg.isOrderStatusValid(medOgSettings, "已审核")).isTrue();
        assertThat(medCfg.isOrderRouteValid(medOgSettings, "不允许的口服")).isFalse();
        assertThat(medCfg.isOrderRouteValid(medOgSettings, "静推")).isTrue();

        MedicalOrder medOrd = medTestUtils.newMedicalOrder(
            "order_id_34", patient.getHisPatientId(), "group_id_13", "doctor_1",
            deptId, "西药", "已审核", TimeUtils.getLocalTime(2024, 9, 11) /*order_time*/,
            "med_code_3", "med_name_3", "1000ml:1g(10%)/20支", 2.0, "mg",
            0 /*长期医嘱*/, TimeUtils.getLocalTime(2024, 9, 11) /*plan_time*/, "freq_code_1",
            0 /* 首日分解次数 */, "route_code_1", "route_name_1",
            "reviewer_1", TimeUtils.getLocalTime(2024, 9, 11), TimeUtils.getLocalTime(2024, 9, 11));
        medOrd = medOrdRepo.save(medOrd);

        List<MedicationOrderGroup> orderGroups = orderGroupGenerator.generate(patient, medOgSettings);
        assertThat(orderGroups).hasSize(1);
        assertThat(orderGroups.get(0).getHisPatientId()).isEqualTo("hisPatientId114");
        assertThat(orderGroups.get(0).getGroupId()).isEqualTo("group_id_13");
        assertThat(orderGroups.get(0).getDeptId()).isEqualTo(deptId);
        MedicationDosageGroupPB dosageGroupPB =
            ProtoUtils.decodeDosageGroup(orderGroups.get(0).getMedicationDosageGroup());
        assertThat(dosageGroupPB.getMdList()).hasSize(1);
        assertThat(dosageGroupPB.getMd(0).getIntakeVolMl()).isEqualTo(2.0);
    }

    @Test
    public void testSpecialOrderType() {
        assertThat(medCfg.isOrderTypeValid(medOgSettings, "输血", "白蛋白")).isTrue();
        assertThat(medCfg.isOrderTypeValid(medOgSettings, "输血", "血浆")).isTrue();
        assertThat(medCfg.isOrderTypeValid(medOgSettings, "输血", "其他")).isFalse();
    }

    final private String deptId;
    final private Integer IN_ICU_VAL;
    final private Integer ONE_TIME_EXECUTION_STOP_STRATEGY_IGNORE;

    private MedOrderGroupSettingsPB medOgSettings;

    private OrderGroupGenerator orderGroupGenerator;
    private PatientRecordRepository patientRecordRepo;
    private MedicalOrderRepository medOrdRepo;
    private MedicationOrderGroupRepository medOrdGroupRepo;
    private ConfigProtoService configProtoService;
    private MedicationConfig medCfg;
    private MedicationDictionary medDict;
    private PatientTestUtils patientTestUtils;
    private MedicationTestUtils medTestUtils;
}