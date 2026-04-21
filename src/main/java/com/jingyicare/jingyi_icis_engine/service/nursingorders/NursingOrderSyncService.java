package com.jingyicare.jingyi_icis_engine.service.nursingorders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingOrder.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class NursingOrderSyncService {
    public static final String SOURCE_MEDICAL_ORDERS = "medical_orders";
    public static final int HIS_ORDER_TEMPLATE_ID = 0;
    public static final String HIS_ORDER_GROUP_NAME = "His护嘱";

    public NursingOrderSyncService(
        @Autowired ConfigProtoService protoService,
        @Autowired MedicalOrderRepository medOrdRepo,
        @Autowired NursingOrderRepository orderRepo,
        @Autowired MedicationFrequencyRepository medFreqRepo
    ) {
        this.protoService = protoService;
        this.medOrdRepo = medOrdRepo;
        this.orderRepo = orderRepo;
        this.medFreqRepo = medFreqRepo;

        this.IN_ICU_VAL = protoService.getConfig().getPatient().getEnumsV2().getAdmissionStatusList().stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .map(EnumValue::getId)
            .orElse(null);
    }

    @Transactional
    public StatusCode syncFromMedicalOrders(PatientRecord patient, String accountId) {
        if (patient == null) return StatusCode.PATIENT_NOT_FOUND;
        if (!Objects.equals(patient.getAdmissionStatus(), IN_ICU_VAL)) return StatusCode.OK;
        if (StrUtils.isBlank(patient.getHisPatientId()) || patient.getAdmissionTime() == null) {
            log.warn("Skip nursing order sync because patient HIS id or admission time is empty, pid={}", patient.getId());
            return StatusCode.OK;
        }

        List<MedicalOrder> medicalOrders = medOrdRepo.findByHisPatientIdAndAdmissionTime(
            patient.getHisPatientId(), patient.getAdmissionTime());
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        for (MedicalOrder medicalOrder : medicalOrders) {
            StatusCode statusCode = syncMedicalOrder(patient, accountId, medicalOrder, nowUtc);
            if (statusCode != StatusCode.OK) return statusCode;
        }
        return StatusCode.OK;
    }

    public static boolean isHisSynced(NursingOrder order) {
        return order != null &&
            SOURCE_MEDICAL_ORDERS.equals(order.getSource()) &&
            !StrUtils.isBlank(order.getMedicalOrderId());
    }

    private StatusCode syncMedicalOrder(
        PatientRecord patient, String accountId, MedicalOrder medicalOrder, LocalDateTime nowUtc
    ) {
        if (!isAllowedMedicalOrder(medicalOrder)) return StatusCode.OK;
        if (!Objects.equals(medicalOrder.getDeptId(), patient.getDeptId())) {
            log.warn("Skip nursing order sync because dept mismatch, orderId={}, orderDept={}, patientDept={}",
                medicalOrder.getOrderId(), medicalOrder.getDeptId(), patient.getDeptId());
            return StatusCode.OK;
        }
        if (StrUtils.isBlank(medicalOrder.getOrderId()) ||
            StrUtils.isBlank(medicalOrder.getOrderName()) ||
            StrUtils.isBlank(medicalOrder.getFreqCode()) ||
            medicalOrder.getOrderTime() == null
        ) {
            log.warn("Skip nursing order sync because required field is empty, orderId={}", medicalOrder.getOrderId());
            return StatusCode.OK;
        }

        Integer durationType = toNursingDurationType(medicalOrder.getOrderDurationType());
        if (durationType == null) {
            log.warn("Skip nursing order sync because duration type is unsupported, orderId={}, durationType={}",
                medicalOrder.getOrderId(), medicalOrder.getOrderDurationType());
            return StatusCode.OK;
        }

        MedicationFrequency freq = medFreqRepo.findByCodeAndIsDeletedFalse(medicalOrder.getFreqCode()).orElse(null);
        if (freq != null && !Boolean.TRUE.equals(freq.getSupportNursingOrder())) {
            log.error("Nursing order frequency is not supported, orderId={}, freqCode={}",
                medicalOrder.getOrderId(), medicalOrder.getFreqCode());
            return StatusCode.NURSING_ORDER_FREQUENCY_NOT_EXISTS;
        }

        NursingOrder nursingOrder = orderRepo.findByMedicalOrderId(medicalOrder.getOrderId()).orElse(null);
        if (nursingOrder != null && Boolean.TRUE.equals(nursingOrder.getIsDeleted())) {
            return StatusCode.OK;
        }
        if (nursingOrder == null) {
            nursingOrder = new NursingOrder();
            nursingOrder.setPid(patient.getId());
            nursingOrder.setDeptId(patient.getDeptId());
            nursingOrder.setOrderTemplateId(null);
            nursingOrder.setIsDeleted(false);
        }

        nursingOrder.setSource(SOURCE_MEDICAL_ORDERS);
        nursingOrder.setMedicalOrderId(medicalOrder.getOrderId());
        nursingOrder.setMedicalOrderGroupId(medicalOrder.getGroupId());
        nursingOrder.setName(medicalOrder.getOrderName());
        nursingOrder.setDurationType(durationType);
        nursingOrder.setMedicationFreqCode(medicalOrder.getFreqCode());
        nursingOrder.setOrderBy(emptyIfBlank(medicalOrder.getOrderingDoctor()));
        nursingOrder.setOrderTime(trimToMinute(medicalOrder.getOrderTime()));
        nursingOrder.setNote(buildNote(medicalOrder));

        LocalDateTime stopTime = getStopTime(medicalOrder, nowUtc);
        nursingOrder.setStopTime(stopTime == null ? null : trimToMinute(stopTime));
        nursingOrder.setStopBy(stopTime == null ? null : emptyIfBlank(medicalOrder.getOrderingDoctor()));

        nursingOrder.setSyncedAt(nowUtc);
        nursingOrder.setModifiedBy(accountId);
        nursingOrder.setModifiedAt(nowUtc);
        orderRepo.save(nursingOrder);
        return StatusCode.OK;
    }

    private boolean isAllowedMedicalOrder(MedicalOrder medicalOrder) {
        if (medicalOrder == null) return false;
        for (MedicalOrderTypeNamePB allowed : protoService.getConfig().getNursingOrder().getAllowedOrderTypeList()) {
            if (Objects.equals(allowed.getType(), medicalOrder.getOrderType()) &&
                allowed.getNameList().contains(medicalOrder.getOrderName())
            ) {
                return true;
            }
        }
        return false;
    }

    private Integer toNursingDurationType(Integer medicalOrderDurationType) {
        if (medicalOrderDurationType == null) return null;

        MEnums enums = protoService.getConfig().getMedication().getEnums();
        if (Objects.equals(medicalOrderDurationType, enums.getOrderDurationTypeLongTerm().getId())) {
            return 1;
        }
        if (Objects.equals(medicalOrderDurationType, enums.getOrderDurationTypeOneTime().getId())) {
            return 0;
        }
        return null;
    }

    private LocalDateTime getStopTime(MedicalOrder medicalOrder, LocalDateTime nowUtc) {
        if (isCanceled(medicalOrder)) {
            return medicalOrder.getCancelTime() == null ? nowUtc : medicalOrder.getCancelTime();
        }
        return medicalOrder.getStopTime();
    }

    private boolean isCanceled(MedicalOrder medicalOrder) {
        if (medicalOrder.getCancelTime() != null) return true;
        String canceledTxt = protoService.getConfig().getMedication()
            .getOrderGroupSettings().getStatusCanceledTxt();
        return !StrUtils.isBlank(canceledTxt) && Objects.equals(canceledTxt, medicalOrder.getStatus());
    }

    private String buildNote(MedicalOrder medicalOrder) {
        StringBuilder builder = new StringBuilder();
        if (!StrUtils.isBlank(medicalOrder.getMedicationNote())) {
            builder.append(medicalOrder.getMedicationNote());
            builder.append("\n");
        }
        builder.append("medical_orders.order_id=").append(medicalOrder.getOrderId());
        if (!StrUtils.isBlank(medicalOrder.getGroupId())) {
            builder.append(", medical_orders.group_id=").append(medicalOrder.getGroupId());
        }
        return builder.toString();
    }

    private LocalDateTime trimToMinute(LocalDateTime t) {
        if (t == null) return null;
        return t.withSecond(0).withNano(0);
    }

    private String emptyIfBlank(String s) {
        return StrUtils.isBlank(s) ? "" : s;
    }

    private final Integer IN_ICU_VAL;
    private final ConfigProtoService protoService;
    private final MedicalOrderRepository medOrdRepo;
    private final NursingOrderRepository orderRepo;
    private final MedicationFrequencyRepository medFreqRepo;
}
