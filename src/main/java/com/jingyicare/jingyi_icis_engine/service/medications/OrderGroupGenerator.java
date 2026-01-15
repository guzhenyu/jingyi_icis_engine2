package com.jingyicare.jingyi_icis_engine.service.medications;

import java.time.LocalDateTime;
import java.util.function.Consumer;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class OrderGroupGenerator {
    public OrderGroupGenerator(
        @Autowired ConfigProtoService protoService,
        @Autowired MedicationConfig config,
        @Autowired MedicationDictionary medDict,
        @Autowired MedicalOrderRepository medOrdRepo,
        @Autowired MedicationOrderGroupRepository medOrdGroupRepo
    ) {
        final String canceledTxt = protoService.getConfig().getMedication()
            .getOrderGroupSettings().getStatusCanceledTxt();
        this.STATUS_CANCELED_TXT = StrUtils.isBlank(canceledTxt) ? null : canceledTxt;
        this.enums = protoService.getConfig().getMedication().getEnums();
        this.config = config;
        this.medDict = medDict;
        this.medOrdRepo = medOrdRepo;
        this.medOrdGroupRepo = medOrdGroupRepo;
    }

    public MedicationOrderGroup generateNonHisOrderGroup(
        PatientRecord patient, MedOrderGroupSettingsPB medOgSettings,
        String accountId/*ordering doctor*/, String accountName,
        LocalDateTime planTime, LocalDateTime orderTime,
        MedicationDosageGroupPB dosageGroup, String routeCode, String routeName, String note
    ) {
        MedicationOrderGroup group = new MedicationOrderGroup();
        group.setHisPatientId(patient.getHisPatientId());
        group.setPatientId(patient.getId());
        group.setGroupId("");
        group.setMedicalOrderIds("");
        group.setHisMrn(patient.getHisMrn());
        group.setOrderingDoctor(accountName);
        group.setOrderingDoctorId(accountId);
        group.setDeptId(patient.getDeptId());

        // 分组状态
        group.setOrderType("");
        group.setStatus("");
        group.setOrderTime(orderTime);
        group.setOrderValidity(enums.getMedicationOrderValidityTypeManualEntry().getId());

        // 药物成分
        dosageGroup = dosageGroup.toBuilder()
            .setDisplayName(config.concatMedicationNames(medOgSettings, dosageGroup))
            .build();
        group.setMedicationDosageGroup(Base64.getEncoder().encodeToString(dosageGroup.toByteArray()));
        medDict.updateIfNecessary(patient.getDeptId(), dosageGroup, routeCode, routeName);

        // 药物执行参数
        group.setOrderDurationType(enums.getOrderDurationTypeManualEntry().getId());
        group.setPlanTime(planTime);
        group.setFreqCode("");
        group.setAdministrationRouteCode(routeCode);
        group.setAdministrationRouteName(routeName);

        // 创建时间
        group.setNote(note);
        group.setCreatedAt(TimeUtils.getNowUtc());

        return medOrdGroupRepo.save(group);
    }

    public List<MedicationOrderGroup> getNonHisOrderGroup(Long patientId) {
        return medOrdGroupRepo.findByPatientIdAndOrderValidity(
            patientId, enums.getMedicationOrderValidityTypeManualEntry().getId());
    }

    // 生成医嘱，并根据需要分解医嘱
    // generate => (getMedicalOrdersByGroupId, 查数据库并分组) => Map<String/*group_id*/, List<MedicalOrder>> ordersMap
    //    *<orderGroupId, List<MedicalOrder>> entry
    //         => (mergeOrders, 将his医嘱合并到 重症医嘱) => MedicationOrderGroup group
    //         => (updateOrderGroups, 如果根据his生成的医嘱和数据库中的不一致，则更新数据库中的医嘱) => MedicationOrderGroup group
    public List<MedicationOrderGroup> generate(PatientRecord patient, MedOrderGroupSettingsPB medOgSettings) {
        List<MedicationOrderGroup> resultGroups = new ArrayList<>();
        for (Map.Entry<String, List<MedicalOrder>> entry : 
            getMedicalOrdersByGroupId(patient.getHisPatientId(), patient.getAdmissionTime()).entrySet()
        ) {
            String groupId = entry.getKey();
            List<MedicalOrder> orders = entry.getValue();
            MedicationOrderGroup group = mergeOrders(patient, medOgSettings, entry.getKey(), entry.getValue());
            if (group == null) continue;

            resultGroups.add(updateOrderGroups(group));
        }

        resultGroups.sort(Comparator.comparing(MedicationOrderGroup::getGroupId));
        return resultGroups;
    }

    public MedicationOrderGroup getOrderGroup(Long medOrderGroupId) {
        Optional<MedicationOrderGroup> optGroup = medOrdGroupRepo.findById(medOrderGroupId);
        return optGroup.orElse(null);
    }

    private Map<String /*group_id*/, List<MedicalOrder>> getMedicalOrdersByGroupId(
        String hisPatientId, LocalDateTime admissionTime
    ) {
        Map<String /*group_id*/, List<MedicalOrder>> orderGroups = new HashMap<>();
        for (MedicalOrder order : medOrdRepo.findByHisPatientIdAndAdmissionTime(hisPatientId, admissionTime)) {
            if (StrUtils.isBlank(order.getGroupId())) {
                log.error("Group id not found for order: {}", order);
                continue;
            }
            final String groupId = order.getGroupId();
            if (!orderGroups.containsKey(groupId)) {
               orderGroups.put(groupId, new ArrayList<>());
            }
            orderGroups.get(groupId).add(order);
        }

        return orderGroups;
    }

    private boolean isOrderValid(MedOrderGroupSettingsPB medOgSettings, MedicalOrder order) {
        if (!config.isOrderTypeValid(medOgSettings, order.getOrderType(), order.getOrderName())) {
            log.warn("order type is not qualified, order_type {}, setting.order_types {}",
                order.getOrderType(), medOgSettings.getAllowOrderTypeList());
            return false;
        }

        if (!config.isOrderStatusValid(medOgSettings, order.getStatus())) {
            log.warn("order status is not qualified.");
            return false;
        }

        if (!config.isOrderRouteValid(medOgSettings, order.getAdministrationRouteCode())) {
            log.warn("administration route code is not qualified.");
            return false;
        }

        if (StrUtils.isBlank(order.getOrderId()) ||
            StrUtils.isBlank(order.getHisPatientId()) ||
            StrUtils.isBlank(order.getGroupId()) ||
            StrUtils.isBlank(order.getOrderingDoctor()) ||
            StrUtils.isBlank(order.getOrderingDoctorId())
        ) {
            log.warn("some entity id is missed");
            return false;
        }

        if (order.getOrderTime() == null ||
            StrUtils.isBlank(order.getOrderCode()) ||
            StrUtils.isBlank(order.getOrderName())
        ) {
            log.warn("some dosage info is missed.");
            return false;
        }

        if (order.getOrderDurationType() == null ||
            order.getPlanTime() == null ||
            StrUtils.isBlank(order.getFreqCode())
        ) {
            log.warn("some order exe arg is missed.");
            return false;
        }

        if (StrUtils.isBlank(order.getAdministrationRouteCode()) ||
            StrUtils.isBlank(order.getAdministrationRouteName())
        ) {
            log.warn("administration route code or name is missed.");
            return false;
        }

        return true;
    }

    private static <T> void checkAndSet(
        String fieldName, Set<T> fieldvals, Set<String> inconsistentFields, Consumer<T> setter
    ) {
        if (fieldvals == null || fieldvals.isEmpty()) {
            log.error("Error: {} values are null or empty", fieldName);
            return;
        }
        if (fieldvals.size() > 1) inconsistentFields.add(fieldName);
        // Apply the consumer function
        setter.accept(fieldvals.iterator().next());
    }

    private MedicationOrderGroup mergeOrders(
        PatientRecord patient, MedOrderGroupSettingsPB medOgSettings,
        String groupId, List<MedicalOrder> orders
    ) {
        if (StrUtils.isBlank(groupId) || patient == null || orders == null || orders.isEmpty()) {
            log.error("invalid parameters to merge orders");
            return null;
        }

        // 收集字段
        int qualifiedOrderCnt = 0;
        MedicalOrderIdsPB.Builder orderIdsBuilder = MedicalOrderIdsPB.newBuilder();
        MedicationDosageGroupPB.Builder dosageGroupBuilder = MedicationDosageGroupPB.newBuilder();
        List<String> medicationNames = new ArrayList<>();
        Set<String> doctorSet = new HashSet<>();
        Set<String> doctorIdSet = new HashSet<>();
        Set<String> orderTypeSet = new HashSet<>();
        Set<String> statusSet = new HashSet<>();
        Set<LocalDateTime> orderTimeSet = new HashSet<>();
        Set<LocalDateTime> stopTimeSet = new HashSet<>();
        Set<LocalDateTime> cancelTimeSet = new HashSet<>();
        Set<Integer> orderDurationTypeSet = new HashSet<>();
        Set<LocalDateTime> planTimeSet = new HashSet<>();
        Set<String> freqCodeSet = new HashSet<>();
        Set<Integer> firstDayExeCountSet = new HashSet<>();
        Set<String> administrationRouteCodeSet = new HashSet<>();
        Set<String> administrationRouteNameSet = new HashSet<>();
        Set<String> reviewerSet = new HashSet<>();
        Set<String> reviewerIdSet = new HashSet<>();
        Set<LocalDateTime> reviewTimeSet = new HashSet<>();

        for (MedicalOrder order : orders) {
            if (!isOrderValid(medOgSettings, order)) {
                log.warn("medical order is filtered {}", order);
                continue;
            }
            ++qualifiedOrderCnt;

            medDict.updateIfNecessary(patient.getDeptId(), order);
            orderIdsBuilder.addId(order.getOrderId());

            doctorSet.add(order.getOrderingDoctor());
            doctorIdSet.add(order.getOrderingDoctorId());
            orderTypeSet.add(order.getOrderType());
            statusSet.add(order.getStatus());
            orderTimeSet.add(order.getOrderTime().withSecond(0).withNano(0));
            if (order.getStopTime() != null) stopTimeSet.add(order.getStopTime());
            if (order.getCancelTime() != null) cancelTimeSet.add(order.getCancelTime());

            MedicationDosagePB.Builder dosageBuilder = MedicationDosagePB.newBuilder()
                .setCode(order.getOrderCode())
                .setName(order.getOrderName())
                .setSpec(order.getSpec())
                .setDose(order.getDose())
                .setDoseUnit(order.getDoseUnit())
                .setIntakeVolMl(medDict.calculateLiquidVolume(order.getSpec(), order.getDose(), order.getDoseUnit()))
                .setRecommendSpeed(order.getRecommendSpeed())
                .setShouldCalculateRate(order.getShouldCalculateRate() != null ? order.getShouldCalculateRate() : false);
            if (!StrUtils.isBlank(order.getMedicationNote())) dosageBuilder.setNote(order.getMedicationNote());
            if (!StrUtils.isBlank(order.getMedicationType())) dosageBuilder.setType(order.getMedicationType());
            if (!StrUtils.isBlank(order.getPaymentType())) dosageBuilder.setPaymentType(order.getPaymentType());
            dosageGroupBuilder.addMd(dosageBuilder.build());
            medicationNames.add(order.getOrderName());

            orderDurationTypeSet.add(order.getOrderDurationType());
            planTimeSet.add(order.getPlanTime().withSecond(0).withNano(0));
            freqCodeSet.add(order.getFreqCode());
            firstDayExeCountSet.add(order.getFirstDayExeCount());
            administrationRouteCodeSet.add(order.getAdministrationRouteCode());
            administrationRouteNameSet.add(order.getAdministrationRouteName());
            reviewerSet.add(order.getReviewer());
            reviewerIdSet.add(order.getReviewerId());
            reviewTimeSet.add(order.getReviewTime());
        }
        if (qualifiedOrderCnt == 0) {
            log.warn("medical order group {}, his_patient_id {}, is filtered.",
                groupId, patient.getHisPatientId());
            return null;
        }

        MedicationOrderGroup group = new MedicationOrderGroup();
        group.setHisPatientId(patient.getHisPatientId());
        group.setPatientId(patient.getId());
        group.setGroupId(groupId);
        group.setMedicalOrderIds(Base64.getEncoder().encodeToString(orderIdsBuilder.build().toByteArray()));
        group.setHisMrn(patient.getHisMrn());
        group.setDeptId(patient.getDeptId());

        Set<String> inconsistentFields = new LinkedHashSet<>();
        LocalDateTime now = TimeUtils.getNowUtc();
        checkAndSet("ordering_doctor", doctorSet, inconsistentFields, group::setOrderingDoctor);
        checkAndSet("ordering_doctor_id", doctorIdSet, inconsistentFields, group::setOrderingDoctorId);
        checkAndSet("order_type", orderTypeSet, inconsistentFields, group::setOrderType);
        checkAndSet("status", statusSet, inconsistentFields, group::setStatus);
        checkAndSet("order_time", orderTimeSet, inconsistentFields, group::setOrderTime);

        boolean canceled = false;
        if (cancelTimeSet.size() > 0 ||
            (STATUS_CANCELED_TXT != null && statusSet.contains(STATUS_CANCELED_TXT))
        ) {
            if (cancelTimeSet.size() > 0) {
                final LocalDateTime cancelTime = cancelTimeSet.iterator().next();
                group.setCancelTime(cancelTime);
            }
            canceled = true;
            group.setOrderValidity(enums.getMedicationOrderValidityTypeCanceled().getId());
        }

        boolean stopped = false;
        if (stopTimeSet.size() > 0) {
            final LocalDateTime stopTime = stopTimeSet.iterator().next();
            group.setStopTime(stopTime);
            if (!canceled && now.isAfter(stopTime)) {
                stopped = true;
                group.setOrderValidity(enums.getMedicationOrderValidityTypeStopped().getId());
            }
        }

        dosageGroupBuilder.setDisplayName(config.concatMedicationNames(medOgSettings, medicationNames));
        group.setMedicationDosageGroup(Base64.getEncoder().encodeToString(dosageGroupBuilder.build().toByteArray()));

        checkAndSet("order_duration_type", orderDurationTypeSet, inconsistentFields, group::setOrderDurationType);
        if (!canceled && !stopped) {
            if (group.getOrderDurationType() == enums.getOrderDurationTypeOneTime().getId()) {
                group.setOrderValidity(enums.getMedicationOrderValidityTypeDurationOneTime().getId());
            } else {
                group.setOrderValidity(enums.getMedicationOrderValidityTypeValid().getId());
            }
        }

        checkAndSet("plan_time", planTimeSet, inconsistentFields, group::setPlanTime);
        checkAndSet("freq_code", freqCodeSet, inconsistentFields, group::setFreqCode);
        checkAndSet("first_day_exe_count", firstDayExeCountSet, inconsistentFields, group::setFirstDayExeCount);
        checkAndSet("administration_route_code", administrationRouteCodeSet, inconsistentFields, group::setAdministrationRouteCode);
        checkAndSet("administration_route_name", administrationRouteNameSet, inconsistentFields, group::setAdministrationRouteName);
        checkAndSet("reviewer", reviewerSet, inconsistentFields, group::setReviewer);
        checkAndSet("reviewer_id", reviewerIdSet, inconsistentFields, group::setReviewerId);
        checkAndSet("review_time", reviewTimeSet, inconsistentFields, group::setReviewTime);
        if (inconsistentFields.size() > 0) group.setInconsistencyExplanation(String.join(",", inconsistentFields));
        group.setCreatedAt(now);

        return group;
    }

    private MedicationOrderGroup updateOrderGroups(MedicationOrderGroup orderGroup) {
        Optional<MedicationOrderGroup> optMog = medOrdGroupRepo.findLatestByPatientIdAndGroupId(
            orderGroup.getPatientId(), orderGroup.getGroupId());
        if (!optMog.isPresent()) {
            medOrdGroupRepo.save(orderGroup);
            return orderGroup;
        }

        MedicationOrderGroup curMog = optMog.get();
        if (!Objects.equals(curMog.getHisPatientId(), orderGroup.getHisPatientId()) ||
            !Objects.equals(curMog.getMedicalOrderIds(), orderGroup.getMedicalOrderIds()) ||
            !Objects.equals(curMog.getOrderingDoctor(), orderGroup.getOrderingDoctor()) ||
            !Objects.equals(curMog.getOrderType(), orderGroup.getOrderType()) ||
            !Objects.equals(curMog.getStatus(), orderGroup.getStatus()) ||
            !Objects.equals(curMog.getOrderTime(), orderGroup.getOrderTime()) ||
            !Objects.equals(curMog.getInconsistencyExplanation(), orderGroup.getInconsistencyExplanation()) ||
            !Objects.equals(curMog.getMedicationDosageGroup(), orderGroup.getMedicationDosageGroup()) ||
            !Objects.equals(curMog.getOrderDurationType(), orderGroup.getOrderDurationType()) ||
            !Objects.equals(curMog.getPlanTime(), orderGroup.getPlanTime()) ||
            !Objects.equals(curMog.getFreqCode(), orderGroup.getFreqCode()) ||
            !Objects.equals(curMog.getFirstDayExeCount(), orderGroup.getFirstDayExeCount()) ||
            !Objects.equals(curMog.getAdministrationRouteCode(), orderGroup.getAdministrationRouteCode()) ||
            !Objects.equals(curMog.getAdministrationRouteName(), orderGroup.getAdministrationRouteName()) ||
            !Objects.equals(curMog.getReviewer(), orderGroup.getReviewer()) ||
            !Objects.equals(curMog.getReviewerId(), orderGroup.getReviewerId()) ||
            !Objects.equals(curMog.getReviewTime(), orderGroup.getReviewTime())
        ) {
            curMog.setOrderValidity(enums.getMedicationOrderValidityTypeOverwritten().getId());
            medOrdGroupRepo.save(curMog);
            medOrdGroupRepo.save(orderGroup);
            return orderGroup;
        }
        boolean update = false;
        if (!Objects.equals(curMog.getStopTime(), orderGroup.getStopTime())) {
            update = true;
            curMog.setStopTime(orderGroup.getStopTime());
        }
        if (!Objects.equals(curMog.getCancelTime(), orderGroup.getCancelTime())) {
            update = true;
            curMog.setCancelTime(orderGroup.getCancelTime());
        }
        if (curMog.getOrderValidity() != orderGroup.getOrderValidity()) {
            update = true;
            curMog.setOrderValidity(orderGroup.getOrderValidity());
        }
        if (update) curMog = medOrdGroupRepo.save(curMog);

        return curMog;
    }

    private final String STATUS_CANCELED_TXT;
    private MEnums enums;
    private MedicationConfig config;
    private MedicationDictionary medDict;
    private MedicalOrderRepository medOrdRepo;
    private MedicationOrderGroupRepository medOrdGroupRepo;
}