package com.jingyicare.jingyi_icis_engine.testutils;

import java.time.LocalDateTime;
import java.util.*;

import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Slf4j
public class MedicationTestUtils {
    public static final String FREQ_CODE_TID = "test_freq_10_16_22";
    public static final String FREQ_CODE_BW135_TID = "test_freq_bw135_10_16_22";
    public static final String FREQ_CODE_BI2_TID = "test_freq_bi2_10_16_22";

    public static Medication newMedication(String code, String name, String spec, Double dose, String doseUnit) {
        Medication med = new Medication();
        med.setCode(code);
        med.setName(name);
        med.setSpec(spec);
        med.setDose(dose);
        med.setDoseUnit(doseUnit);
        med.setConfirmed(true);
        med.setCreatedAt(TimeUtils.getNowUtc());
        return med;
    }

    public static AdministrationRoute newAdministrationRoute(
        String deptId, String code, String name, Boolean isContinuous,
        Integer groupId, Integer intakeTypeId, Boolean isValid
    ) {
        AdministrationRoute route = new AdministrationRoute();
        route.setDeptId(deptId);
        route.setCode(code);
        route.setName(name);
        route.setIsContinuous(isContinuous);
        route.setGroupId(groupId);
        route.setIntakeTypeId(intakeTypeId);
        route.setIsValid(isValid);
        return route;
    }

    public MedicationTestUtils(
        MedicationConfigPB medProto,
        MedicationFrequencyRepository freqRepo
    ) {
        this.medProto = medProto;
        this.freqRepo = freqRepo;
    }

    public MedicalOrder newMedicalOrder(
        String orderId, String hisPatientId, String GroupId, String doctor,
        String deptId, String orderType, String orderStatus, LocalDateTime orderTime,
        String code, String name, String spec, Double dose, String doseUnit,
        Integer durationType, LocalDateTime planTime, String freqCode,
        Integer firstDayExeCount, String routeCode, String routeName,
        String reviewer, LocalDateTime reviewTime, LocalDateTime createdAt
    ) {
        MedicalOrder order = new MedicalOrder();
        order.setOrderId(orderId);
        order.setHisPatientId(hisPatientId);
        order.setGroupId(GroupId);
        order.setOrderingDoctor(doctor);
        order.setOrderingDoctorId(doctor + "-Id");
        order.setDeptId(deptId);
        order.setOrderType(orderType);
        order.setStatus(orderStatus);
        order.setOrderTime(orderTime);
        order.setOrderCode(code);
        order.setOrderName(name);
        order.setSpec(spec);
        order.setDose(dose);
        order.setDoseUnit(doseUnit);
        order.setOrderDurationType(durationType);
        order.setPlanTime(planTime);
        order.setFreqCode(freqCode);
        order.setFirstDayExeCount(firstDayExeCount);
        order.setAdministrationRouteCode(routeCode);
        order.setAdministrationRouteName(routeName);
        order.setReviewer(reviewer);
        order.setReviewerId(reviewer + "-Id");
        order.setReviewTime(reviewTime);
        order.setCreatedAt(createdAt);

        return order;
    }

    public MedicationDosageGroupPB createMedicationDosageGroup(
        int size, List<String> codes, List<String> names,
        List<String> specs, List<Double> doses, List<String> doseUnits
    ) {
        MedicationDosageGroupPB.Builder dosageGroupBuilder = MedicationDosageGroupPB.newBuilder();

        for (int i = 0; i < size; i++) {
            MedicationDosagePB.Builder dosageBuilder = MedicationDosagePB.newBuilder();
            dosageBuilder.setCode(codes.get(i));
            dosageBuilder.setName(names.get(i));
            dosageBuilder.setSpec(specs.get(i));
            dosageBuilder.setDose(doses.get(i));
            dosageBuilder.setDoseUnit(doseUnits.get(i));
            dosageGroupBuilder.addMd(dosageBuilder.build());
        }
        return dosageGroupBuilder.build();
    }

    public MedicationOrderGroup newMedicationOrderGroup(
        String hisPatientId, Long patientId, String groupId, List<String> orderIds,
        String hisMrn, String orderingDoctor, String orderingDoctorId, String deptId,
        String orderType, String status, LocalDateTime orderTime, LocalDateTime stopTime,
        LocalDateTime cancelTime, Integer orderValidity, MedicationDosageGroupPB dosageGroup,
        Integer orderDurationType, String freqCode, LocalDateTime planTime, Integer firstDayExeCount,
        String routeCode, String routeName, LocalDateTime createdAt
    ) {
        MedicationOrderGroup ordGroup = new MedicationOrderGroup();

        ordGroup.setHisPatientId(hisPatientId);
        ordGroup.setPatientId(patientId);
        ordGroup.setGroupId(groupId);

        ordGroup.setMedicalOrderIds(Base64.getEncoder().encodeToString(
            MedicalOrderIdsPB.newBuilder().addAllId(orderIds).build().toByteArray()));

        ordGroup.setHisMrn(hisMrn);
        ordGroup.setOrderingDoctor(orderingDoctor);
        ordGroup.setOrderingDoctorId(orderingDoctorId);
        ordGroup.setDeptId(deptId);
        ordGroup.setOrderType(orderType);
        ordGroup.setStatus(status);
        ordGroup.setOrderTime(orderTime);
        ordGroup.setStopTime(stopTime);
        ordGroup.setCancelTime(cancelTime);
        ordGroup.setOrderValidity(orderValidity);

        ordGroup.setMedicationDosageGroup(Base64.getEncoder().encodeToString(dosageGroup.toByteArray()));

        ordGroup.setOrderDurationType(orderDurationType);
        ordGroup.setFreqCode(freqCode);
        ordGroup.setPlanTime(planTime);
        ordGroup.setFirstDayExeCount(firstDayExeCount);
        ordGroup.setAdministrationRouteCode(routeCode);
        ordGroup.setAdministrationRouteName(routeName);
        ordGroup.setCreatedAt(createdAt);
        return ordGroup;
    }

    public MedicationExecutionRecord newMedicationExecutionRecord(
        Long medOrdGroupId, String hisOrdGroupId, Long patientId, LocalDateTime planTime,
        LocalDateTime startTime, LocalDateTime endTime, Boolean isDeleted,
        String deleteAccountId, LocalDateTime deleteTime, String deleteReason,
        Boolean userTouched, String routeCode, String routeName, Boolean isRouteContinuous,
        String createAccountId, LocalDateTime createdAt
    ) {
        MedicationExecutionRecord record = new MedicationExecutionRecord();
        
        // Set the provided parameters to the MedicationExecutionRecord object
        record.setMedicationOrderGroupId(medOrdGroupId);
        record.setHisOrderGroupId(hisOrdGroupId);
        record.setPatientId(patientId);
        record.setPlanTime(planTime);
        record.setIsContinuous(isRouteContinuous);
        record.setStartTime(startTime);
        record.setEndTime(endTime);
        record.setIsDeleted(isDeleted);
        record.setDeleteAccountId(deleteAccountId);
        record.setDeleteTime(deleteTime);
        record.setDeleteReason(deleteReason);
        record.setUserTouched(userTouched);
        record.setAdministrationRouteCode(routeCode);
        record.setAdministrationRouteName(routeName);
        record.setCreateAccountId(createAccountId);
        record.setCreatedAt(createdAt);

        return record;
    }

    public MedOrderGroupSettingsPB newMedOrderGroupSettings(
        List<String> allowedOrderTypes, List<String> denyStatuses,
        List<String> denyRouteCodes, boolean omitFirstDayOrderExecution,
        boolean checkOrderTimeForLongTermExe, boolean forceGenExeOrdDay1,
        boolean checkOrderTimeForOneTimeExe, int oneTimeExeStopStrategy,
        int notStartedExeRecAdvanceHours, String statusCanceledTxt,
        List<String> prioritizedMedNameList
    ) {
        return MedOrderGroupSettingsPB.newBuilder()
            .setFunctionId(SystemSettingFunctionId.GET_MEDICAL_ORDER_SETTINGS)
            .setFunctionName("医嘱生成配置")
            .setType(MedOrderGroupSettingsPB.Type.BY_FREQUENCY)
            .addAllAllowOrderType(allowedOrderTypes)
            .addAllDenyStatus(denyStatuses)
            .addAllDenyAdministrationRouteCode(denyRouteCodes)
            .setOmitFirstDayOrderExecution(omitFirstDayOrderExecution)
            .setCheckOrderTimeForLongTermExecution(checkOrderTimeForLongTermExe)
            .setForceGenerateExecutionOrderDay1(forceGenExeOrdDay1)
            .setCheckOrderTimeForOneTimeExecution(checkOrderTimeForOneTimeExe)
            .setOneTimeExecutionStopStrategy(oneTimeExeStopStrategy)
            .setNotStartedExeRecAdvanceHours(notStartedExeRecAdvanceHours)
            .setStatusCanceledTxt(statusCanceledTxt)  // "已取消"
            .addAllPrioritizedMedName(prioritizedMedNameList)
            .build();
    }

    public void initFreqRepo() {
        MedicationFrequencySpec freqSpec = MedicationFrequencySpec.newBuilder()
            .setCode(FREQ_CODE_TID)
            .setName("一日三次，每次10点、16点、22点")
            .setByInterval(MedicationFrequencySpec.ByInterval.newBuilder().setIntervalDays(0).build())
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(10).setMinute(0).build())
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(16).setMinute(0).build())
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(22).setMinute(0).build())
            .build();
        saveFreq(freqSpec);

        freqSpec = MedicationFrequencySpec.newBuilder()
            .setCode(FREQ_CODE_BW135_TID)
            .setName("周135，一日三次，每次10点、16点、22点")
            .setByWeek(MedicationFrequencySpec.ByWeek.newBuilder()
                .addDayOfWeek(1).addDayOfWeek(3).addDayOfWeek(5).build()
            )
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(10).setMinute(0).build())
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(16).setMinute(0).build())
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(22).setMinute(0).build())
            .build();
        saveFreq(freqSpec);

        freqSpec = MedicationFrequencySpec.newBuilder()
            .setCode(FREQ_CODE_BI2_TID)
            .setName("每隔两天，一日三次，每次10点、16点、22点")
            .setByInterval(MedicationFrequencySpec.ByInterval.newBuilder().setIntervalDays(2).build())
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(10).setMinute(0).build())
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(16).setMinute(0).build())
            .addTime(MedicationFrequencySpec.Time.newBuilder().setHour(22).setMinute(0).build())
            .build();
        saveFreq(freqSpec);
    }

    @Transactional
    private void saveFreq(MedicationFrequencySpec freqSpec) {
        MedicationFrequency medFreq = freqRepo.findByCode(freqSpec.getCode())
            .orElse(null);
        if (medFreq != null) return;

        medFreq = MedicationFrequency.builder()
            .code(freqSpec.getCode())
            .name(freqSpec.getName())
            .freqSpec(Base64.getEncoder().encodeToString(freqSpec.toByteArray()))
            .supportNursingOrder(false)
            .isDeleted(false)
            .build();

        freqRepo.save(medFreq);
    }

    private MedicationConfigPB medProto;
    private MedicationFrequencyRepository freqRepo;
}