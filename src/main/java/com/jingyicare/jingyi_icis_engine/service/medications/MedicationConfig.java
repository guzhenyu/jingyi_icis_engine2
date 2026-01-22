package com.jingyicare.jingyi_icis_engine.service.medications;

import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedOrderGroupSettingsPB.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class MedicationConfig {
    public MedicationConfig(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired MedicationTypeRepository medicationTypeRepo,
        @Autowired MedicationFrequencyRepository medicationFrequencyRepo,
        @Autowired AdministrationRouteGroupRepository administrationRouteGroupRepo,
        @Autowired IntakeTypeRepository intakeTypeRepo,
        @Autowired OrderDurationTypeRepository orderDurationTypeRepo,
        @Autowired MedicationOrderValidityTypeRepository medOrdValidityTypeRepo,
        @Autowired MedicationChannelRepository medicationChannelRepo,
        @Autowired MedicationExecutionActionTypeRepository medicationExecutionActionTypeRepo,
        @Autowired DeptSystemSettingsRepository deptSystemSettingsRepo,
        @Autowired MonitoringParamRepository monitoringParamRepo
    ) {
        this.context = context;
        this.medProto  = protoService.getConfig().getMedication();
        this.enums = medProto.getEnums();

        this.medicationTypeRepo = medicationTypeRepo;
        this.medicationFrequencyRepo = medicationFrequencyRepo;
        this.administrationRouteGroupRepo = administrationRouteGroupRepo;
        this.intakeTypeRepo = intakeTypeRepo;
        this.orderDurationTypeRepo = orderDurationTypeRepo;
        this.medOrdValidityTypeRepo = medOrdValidityTypeRepo;
        this.medicationChannelRepo = medicationChannelRepo;
        this.medicationExecutionActionTypeRepo = medicationExecutionActionTypeRepo;
        this.deptSystemSettingsRepo = deptSystemSettingsRepo;
        this.defaultOgSettings = medProto.getOrderGroupSettings();
        this.monitoringParamRepo = monitoringParamRepo;
    }

    @Transactional
    public void initialize() {
        if (medicationTypeRepo.count() == 0) initializeMedicationType();

        if (medicationFrequencyRepo.count() == 0) {
            initializeMedicationFrequency();
        }
        refreshMedicationFrequency();

        if (administrationRouteGroupRepo.count() == 0) initializeAdministrationRouteGroup();
        if (intakeTypeRepo.count() == 0) initializeIntakeType();
        if (orderDurationTypeRepo.count() == 0) initializeOrderDurationType();
        if (medOrdValidityTypeRepo.count() == 0) initializeMedicationOrderValidityType();
        if (medicationChannelRepo.count() == 0) initializeMedicationChannel();
        if (medicationExecutionActionTypeRepo.count() == 0) initializeMedicationExecutionActionType();
    }

    @Transactional
    public void checkIntegrity() {
        if (!checkMedicationType() ||
            !checkMedicationFrequency() ||
            !checkAdministrationRouteGroup() ||
            !checkIntakeType() ||
            !checkOrderDurationType() ||
            !checkMedicationOrderValidityType() ||
            !checkMedicationChannel() ||
            !checkMedicationExecutionActionType()
        ) {
            log.error("Medication tables are inconsistent. Please check the logs.");
            LogUtils.flushAndQuit(context);
        }
    }

    @Transactional
    public void refresh() {
        refreshMedicationType();
        refreshMedicationFrequency();
        refreshOrderDurationType();
        refreshMedicationOrderValidityType();
        refreshMedicationChannel();
        refreshMedicationExecutionActionType();
    }

    public String getMedicationTypeStr(Integer id) {
        if (id == null || !medicationTypeMap.containsKey(id)) return null;
        return medicationTypeMap.get(id);
    }

    public MedicationFrequencySpec getMedicationFrequencySpec(String code) {
        MedicationFrequency freq = medicationFrequencyRepo.findByCodeAndIsDeletedFalse(code).orElse(null);
        return freq == null ? null : freqEntityToPB(freq);
    }

    public String getOrderDurationTypeStr(Integer id) {
        if (id == null || !orderDurationTypeMap.containsKey(id)) return null;
        return orderDurationTypeMap.get(id);
    }

    public String getMedicationOrderValidityTypeStr(Integer id) {
        if (id == null || !medOrdValidityTypeMap.containsKey(id)) return null;
        return medOrdValidityTypeMap.get(id);
    }

    public String getMedicationChannelStr(Integer id) {
        if (id == null || !medicationChannelMap.containsKey(id)) return null;
        return medicationChannelMap.get(id);
    }

    public String getMedicationExecutionActionTypeStr(Integer id) {
        if (id == null || !medicationExecutionActionTypeMap.containsKey(id)) return null;
        return medicationExecutionActionTypeMap.get(id);
    }

    public MedOrderGroupSettingsPB getMedOrderGroupSettings(String deptId) {
        DeptSystemSettingsId deptSettingsId = DeptSystemSettingsId.builder()
            .deptId(deptId)
            .functionId(SystemSettingFunctionId.GET_MEDICAL_ORDER_SETTINGS.getNumber())
            .build();
        DeptSystemSettings deptSettings = deptSystemSettingsRepo.findById(deptSettingsId).orElse(null);

        MedOrderGroupSettingsPB medOgSettingPb = null;
        if (deptSettings != null) {
            medOgSettingPb = ProtoUtils.decodeMedOrderGroupSettings(deptSettings.getSettingsPb());
        }

        return medOgSettingPb == null ? defaultOgSettings : medOgSettingPb;
    }

    public void setMedOrderGroupSettings(String deptId, MedOrderGroupSettingsPB settingsPb, String accountId) {
        DeptSystemSettingsId deptSettingsId = DeptSystemSettingsId.builder()
            .deptId(deptId)
            .functionId(SystemSettingFunctionId.GET_MEDICAL_ORDER_SETTINGS.getNumber())
            .build();
        DeptSystemSettings deptSettings = deptSystemSettingsRepo.findById(deptSettingsId).orElse(null);

        if (deptSettings == null) {
            deptSettings = DeptSystemSettings.builder()
                .id(deptSettingsId)
                .settingsPb(ProtoUtils.encodeMedOrderGroupSettings(settingsPb))
                .modifiedAt(TimeUtils.getNowUtc())
                .modifiedBy(accountId)
                .build();
        } else {
            deptSettings.setSettingsPb(ProtoUtils.encodeMedOrderGroupSettings(settingsPb));
            deptSettings.setModifiedAt(TimeUtils.getNowUtc());
            deptSettings.setModifiedBy(accountId);
        }
        deptSystemSettingsRepo.save(deptSettings);
    }

    public Boolean isOrderTypeValid(MedOrderGroupSettingsPB settingsPb, String orderType, String orderName) {
        for (String allowType : settingsPb.getAllowOrderTypeList()) {
            if (allowType.equals(orderType)) {
                return true;
            }
        }

        if (StrUtils.isBlank(orderName)) return false;
        for (SpecialOrderTypePB specialType : settingsPb.getSpecialOrderTypeList()) {
            if (!specialType.getName().equals(orderType)) continue;
            for (String kw : specialType.getKeywordList()) {
                if (orderName.contains(kw)) {
                    return true;
                }
            }
        }

        return false;
    }

    public Boolean isOrderStatusValid(MedOrderGroupSettingsPB settingsPb, String orderStatus) {
        return settingsPb.getDenyStatusList().stream().filter(
            denyStatus -> denyStatus.equals(orderStatus)
        ).count() == 0;
    }

    public Boolean isOrderRouteValid(MedOrderGroupSettingsPB settingsPb, String route) {
        return settingsPb.getDenyAdministrationRouteCodeList().stream().filter(
            denyRoute -> denyRoute.equals(route)
        ).count() == 0;
    }

    public String getDosageGroupDisplayName(
        MedOrderGroupSettingsPB settingsPb, MedicationDosageGroupPB dosageGroup
    ) {
        List<String> prioritizedMedNames = settingsPb.getPrioritizedMedNameList();

        List<Map.Entry<String, Integer>> nameWithPriority = new ArrayList<>();
        for (MedicationDosagePB md : dosageGroup.getMdList()) {
            int index = -1;
            for (int i = 0; i < prioritizedMedNames.size(); i++) {
                if (md.getName().contains(prioritizedMedNames.get(i))) {
                    index = i;
                    break;
                }
            }

            // '%s(%s%s)' % (药物名称, 剂量, 剂量单位)
            // 剂量保留1位小数
            String displayName = md.getName() + '(' +
                StrUtils.formatDouble(md.getDose(), settingsPb.getDoseDecimalPlaces()) + md.getDoseUnit() +
                ')';
            nameWithPriority.add(Map.entry(displayName, index));
        }

        Collections.sort(nameWithPriority, (entry1, entry2) -> {
            if (entry1.getValue().equals(entry2.getValue())) {
                return 0;
            }
            return Integer.compare(entry1.getValue(), entry2.getValue());
        });

        return nameWithPriority.stream().map(entry -> entry.getKey()).collect(Collectors.joining(" + "));
    }

    private void initializeMedicationType() {
        MedicationType medicationType = new MedicationType();

        medicationType.setId(enums.getMedicationTypePendingConfirmation().getId());
        medicationType.setName(enums.getMedicationTypePendingConfirmation().getName());
        medicationTypeRepo.save(medicationType);

        medicationType.setId(enums.getMedicationTypeAntibiotics().getId());
        medicationType.setName(enums.getMedicationTypeAntibiotics().getName());
        medicationTypeRepo.save(medicationType);

        medicationType.setId(enums.getMedicationTypeAntiInflammatory().getId());
        medicationType.setName(enums.getMedicationTypeAntiInflammatory().getName());
        medicationTypeRepo.save(medicationType);

        medicationType.setId(enums.getMedicationTypeAnalgesics().getId());
        medicationType.setName(enums.getMedicationTypeAnalgesics().getName());
        medicationTypeRepo.save(medicationType);
    }

    // Return true if medication_types table is consistent.
    private Boolean checkMedicationType() {
        List<Integer> medicationTypeIds = medicationTypeRepo.findAll().stream()
            .map(type -> type.getId())
            .collect(Collectors.toList());

        if (medicationTypeIds.size() != 4) { 
            log.error("medicationType count mismatch: " + medicationTypeIds.size());
            return false;
        }

        if (!medicationTypeIds.contains(enums.getMedicationTypePendingConfirmation().getId())) {
            log.error("medicationType " + enums.getMedicationTypePendingConfirmation().getId() + " not found");
            return false;
        }
        if (!medicationTypeIds.contains(enums.getMedicationTypeAntibiotics().getId())) {
            log.error("medicationType " + enums.getMedicationTypeAntibiotics().getId() + " not found");
            return false;
        }
        if (!medicationTypeIds.contains(enums.getMedicationTypeAntiInflammatory().getId())) {
            log.error("medicationType " + enums.getMedicationTypeAntiInflammatory().getId() + " not found");
            return false;
        }
        if (!medicationTypeIds.contains(enums.getMedicationTypeAnalgesics().getId())) {
            log.error("medicationType " + enums.getMedicationTypeAnalgesics().getId() + " not found");
            return false;
        }
        return true;
    }

    private void refreshMedicationType() {
        medicationTypeList = new ArrayList<>();
        medicationTypeMap = new HashMap<>();
        for (MedicationType type : medicationTypeRepo.findAll()) {
            EnumValue.Builder enumBuilder = EnumValue.newBuilder();
            enumBuilder.setId(type.getId());
            enumBuilder.setName(type.getName());
            medicationTypeList.add(enumBuilder.build());
            medicationTypeMap.put(type.getId(), type.getName());
        }
    }

    private void initializeMedicationFrequency() {
        try {
            medProto.getFreqSpec().getSpecList().stream().forEach(spec -> {
                MedicationFrequency  medFreq = MedicationFrequency.builder()
                    .code(spec.getCode())
                    .name(spec.getName())
                    .freqSpec(Base64.getEncoder().encodeToString(spec.toByteArray()))
                    .supportNursingOrder(spec.getSupportNursingOrder() > 0)
                    .isDeleted(false)
                    .build();
                medicationFrequencyRepo.save(medFreq);
            });
        } catch (Exception e) {
            log.error("Failed to initialize medication_frequencies", e);
            LogUtils.flushAndQuit(context);
        }
    }

    private Boolean checkMedicationFrequency() {
        if (medicationFrequencySpecList == null) {
            log.error("medicationFrequencySpecList is null");
            return false;
        }

        for (MedicationFrequencySpec spec : medicationFrequencySpecList) {
            // check by_week or by_interval
            if (spec.hasByWeek() && spec.hasByInterval()) {
                log.error("Both by_week and by_interval are set in medication_frequencies: " + spec.getCode());
                return false;
            }
            if (!spec.hasByWeek() && !spec.hasByInterval()) {
                log.error("Neither by_week nor by_interval is set in medication_frequencies: " + spec.getCode());
                return false;
            }
            if (spec.hasByWeek()) {
                if (spec.getByWeek().getDayOfWeekCount() == 0) {
                    log.error("Empty day_of_week in medication_frequencies: " + spec.getCode());
                    return false;
                }
                Integer prevDay = -1;
                for (Integer day : spec.getByWeek().getDayOfWeekList()) {
                    if (!TimeUtils.isValidWeekDay(day)) {
                        log.error("Invalid day_of_week in medication_frequencies: " + day);
                        return false;
                    }
                    if (day <= prevDay) {
                        log.error("Invalid day_of_week in medication_frequencies: " + spec.getCode());
                        return false;
                    }
                    prevDay = day;
                }
            }

            // check time
            Integer hoursCarriedOver = 0;
            Integer prevHour = -1;
            for (MedicationFrequencySpec.Time time : spec.getTimeList()) {
                if (!TimeUtils.isValidHour(time.getHour())) {
                    log.error("Invalid hour in medication_frequencies: " + time.getHour());
                    return false;
                }
                if (!TimeUtils.isValidMinute(time.getMinute())) {
                    log.error("Invalid minute in medication_frequencies: " + time.getMinute());
                    return false;
                }
                if (time.getHour() < prevHour) {
                    hoursCarriedOver += 24;
                }
                prevHour = time.getHour();
            }
            if (spec.getTimeCount() <= 1) continue;
            if (hoursCarriedOver + spec.getTime(spec.getTimeCount() - 1).getHour() -
                spec.getTime(0).getHour() >= 24
            ) {
                log.error("Invalid time range in medication_frequencies: {}", spec.getCode());
                return false;
            }
        }
        return true;
    }

    private void refreshMedicationFrequency() {
        medicationFrequencySpecList = new ArrayList<>();
        for (MedicationFrequency medFreq : medicationFrequencyRepo.findAll()) {
            MedicationFrequencySpec spec = freqEntityToPB(medFreq);
            medicationFrequencySpecList.add(spec);
        }
    }

    private MedicationFrequencySpec freqEntityToPB(MedicationFrequency freqEntity) {
        MedicationFrequencySpec.Builder specBuilder = MedicationFrequencySpec.newBuilder();
        specBuilder.setCode(freqEntity.getCode());
        specBuilder.setName(freqEntity.getName());
        try {
            byte[] decodedData = Base64.getDecoder().decode(freqEntity.getFreqSpec());
            specBuilder.mergeFrom(decodedData);
        } catch (Exception e) {
            log.error("Failed to parse freqSpec", e, "(code={})", freqEntity.getCode());
            LogUtils.flushAndQuit(context);
        }
        MedicationFrequencySpec spec = specBuilder.build();
        return spec;
    }

    private void initializeAdministrationRouteGroup() {
        AdministrationRouteGroup routeGroup = new AdministrationRouteGroup();

        routeGroup.setId(enums.getAdministrationRouteGroupInfusionPump().getId());
        routeGroup.setName(enums.getAdministrationRouteGroupInfusionPump().getName());
        administrationRouteGroupRepo.save(routeGroup);

        routeGroup.setId(enums.getAdministrationRouteGroupIntravenousDrip().getId());
        routeGroup.setName(enums.getAdministrationRouteGroupIntravenousDrip().getName());
        administrationRouteGroupRepo.save(routeGroup);

        routeGroup.setId(enums.getAdministrationRouteGroupIntravenousPush().getId());
        routeGroup.setName(enums.getAdministrationRouteGroupIntravenousPush().getName());
        administrationRouteGroupRepo.save(routeGroup);

        routeGroup.setId(enums.getAdministrationRouteGroupEnteral().getId());
        routeGroup.setName(enums.getAdministrationRouteGroupEnteral().getName());
        administrationRouteGroupRepo.save(routeGroup);

        routeGroup.setId(enums.getAdministrationRouteGroupOthers().getId());
        routeGroup.setName(enums.getAdministrationRouteGroupOthers().getName());
        administrationRouteGroupRepo.save(routeGroup);
    }

    private Boolean checkAdministrationRouteGroup() {
        List<Integer> routeGroupIds = administrationRouteGroupRepo.findAll().stream()
            .map(group -> group.getId())
            .collect(Collectors.toList());

        if (routeGroupIds.size() != 5) {
            log.error("AdministrationRouteGroup count mismatch: " + routeGroupIds.size());
            return false;
        }

        if (!routeGroupIds.contains(enums.getAdministrationRouteGroupInfusionPump().getId())) {
            log.error("AdministrationRouteGroup " + enums.getAdministrationRouteGroupInfusionPump().getId() + " not found");
            return false;
        }
        if (!routeGroupIds.contains(enums.getAdministrationRouteGroupIntravenousDrip().getId())) {
            log.error("AdministrationRouteGroup " + enums.getAdministrationRouteGroupIntravenousDrip().getId() + " not found");
            return false;
        }
        if (!routeGroupIds.contains(enums.getAdministrationRouteGroupIntravenousPush().getId())) {
            log.error("AdministrationRouteGroup " + enums.getAdministrationRouteGroupIntravenousPush().getId() + " not found");
            return false;
        }
        if (!routeGroupIds.contains(enums.getAdministrationRouteGroupEnteral().getId())) {
            log.error("AdministrationRouteGroup " + enums.getAdministrationRouteGroupEnteral().getId() + " not found");
            return false;
        }
        if (!routeGroupIds.contains(enums.getAdministrationRouteGroupOthers().getId())) {
            log.error("AdministrationRouteGroup " + enums.getAdministrationRouteGroupOthers().getId() + " not found");
            return false;
        }

        return true;
    }

    private void initializeIntakeType() {
        List<IntakeType> intakeTypes = new ArrayList<>();
        for (IntakeTypePB intakeTypePb : medProto.getIntakeTypes().getIntakeTypeList()) {
            IntakeType intakeType = IntakeType.builder()
                .id(intakeTypePb.getId())
                .name(intakeTypePb.getName())
                .monitoringParamCode(intakeTypePb.getMonitoringParamCode())
                .build();
            intakeTypes.add(intakeType);
        }
        intakeTypeRepo.saveAll(intakeTypes);
    }

    private Boolean checkIntakeType() {
        Set<String> allCodes = monitoringParamRepo.findAll().stream()
            .map(param -> param.getCode()).collect(Collectors.toSet());

        for (IntakeType type : intakeTypeRepo.findAll()) {
            if (StrUtils.isBlank(type.getMonitoringParamCode())) continue;
            if (!allCodes.contains(type.getMonitoringParamCode())) {
                log.error("MonitoringParamCode " + type.getMonitoringParamCode() + " not found");
                return false;
            }
        }

        return true;
    }

    private void initializeOrderDurationType() {
        OrderDurationType durationType = new OrderDurationType();

        durationType.setId(enums.getOrderDurationTypeLongTerm().getId());
        durationType.setName(enums.getOrderDurationTypeLongTerm().getName());
        orderDurationTypeRepo.save(durationType);

        durationType.setId(enums.getOrderDurationTypeOneTime().getId());
        durationType.setName(enums.getOrderDurationTypeOneTime().getName());
        orderDurationTypeRepo.save(durationType);

        durationType.setId(enums.getOrderDurationTypeManualEntry().getId());
        durationType.setName(enums.getOrderDurationTypeManualEntry().getName());
        orderDurationTypeRepo.save(durationType);
    }

    private Boolean checkOrderDurationType() {
        List<Integer> durationTypeIds = orderDurationTypeRepo.findAll().stream()
            .map(type -> type.getId())
            .collect(Collectors.toList());

        if (durationTypeIds.size() != 3) {
            log.error("OrderDurationType count mismatch: " + durationTypeIds.size());
            return false;
        }

        if (!durationTypeIds.contains(enums.getOrderDurationTypeLongTerm().getId())) {
            log.error("OrderDurationType " + enums.getOrderDurationTypeLongTerm().getId() + " not found");
            return false;
        }
        if (!durationTypeIds.contains(enums.getOrderDurationTypeOneTime().getId())) {
            log.error("OrderDurationType " + enums.getOrderDurationTypeOneTime().getId() + " not found");
            return false;
        }
        if (!durationTypeIds.contains(enums.getOrderDurationTypeManualEntry().getId())) {
            log.error("OrderDurationType " + enums.getOrderDurationTypeManualEntry().getId() + " not found");
            return false;
        }

        return true;
    }

    private void refreshOrderDurationType() {
        orderDurationTypeList = new ArrayList<>();
        orderDurationTypeMap = new HashMap<>();
        for (OrderDurationType type : orderDurationTypeRepo.findAll()) {
            EnumValue.Builder enumBuilder = EnumValue.newBuilder();
            enumBuilder.setId(type.getId());
            enumBuilder.setName(type.getName());
            orderDurationTypeList.add(enumBuilder.build());
            orderDurationTypeMap.put(type.getId(), type.getName());
        }
    }

    private void initializeMedicationOrderValidityType() {
        MedicationOrderValidityType medOrdType = new MedicationOrderValidityType();

        medOrdType.setId(enums.getMedicationOrderValidityTypeValid().getId());
        medOrdType.setName(enums.getMedicationOrderValidityTypeValid().getName());
        medOrdValidityTypeRepo.save(medOrdType);

        medOrdType.setId(enums.getMedicationOrderValidityTypeStopped().getId());
        medOrdType.setName(enums.getMedicationOrderValidityTypeStopped().getName());
        medOrdValidityTypeRepo.save(medOrdType);

        medOrdType.setId(enums.getMedicationOrderValidityTypeCanceled().getId());
        medOrdType.setName(enums.getMedicationOrderValidityTypeCanceled().getName());
        medOrdValidityTypeRepo.save(medOrdType);

        medOrdType.setId(enums.getMedicationOrderValidityTypeOverwritten().getId());
        medOrdType.setName(enums.getMedicationOrderValidityTypeOverwritten().getName());
        medOrdValidityTypeRepo.save(medOrdType);

        medOrdType.setId(enums.getMedicationOrderValidityTypeManualEntry().getId());
        medOrdType.setName(enums.getMedicationOrderValidityTypeManualEntry().getName());
        medOrdValidityTypeRepo.save(medOrdType);

        medOrdType.setId(enums.getMedicationOrderValidityTypeDurationOneTime().getId());
        medOrdType.setName(enums.getMedicationOrderValidityTypeDurationOneTime().getName());
        medOrdValidityTypeRepo.save(medOrdType);
    }

    private Boolean checkMedicationOrderValidityType() {
        List<Integer> medOrdTypeIds = medOrdValidityTypeRepo.findAll().stream()
            .map(type -> type.getId())
            .collect(Collectors.toList());

        if (medOrdTypeIds.size() != 6) {
            log.error("MedicationOrderValidityType count mismatch: " + medOrdTypeIds.size());
            return false;
        }

        if (!medOrdTypeIds.contains(enums.getMedicationOrderValidityTypeValid().getId())) {
            log.error("MedicationOrderValidityType " + enums.getMedicationOrderValidityTypeValid().getId() + " not found");
            return false;
        }
        if (!medOrdTypeIds.contains(enums.getMedicationOrderValidityTypeStopped().getId())) {
            log.error("MedicationOrderValidityType " + enums.getMedicationOrderValidityTypeStopped().getId() + " not found");
            return false;
        }
        if (!medOrdTypeIds.contains(enums.getMedicationOrderValidityTypeCanceled().getId())) {
            log.error("MedicationOrderValidityType " + enums.getMedicationOrderValidityTypeCanceled().getId() + " not found");
            return false;
        }
        if (!medOrdTypeIds.contains(enums.getMedicationOrderValidityTypeOverwritten().getId())) {
            log.error("MedicationOrderValidityType " + enums.getMedicationOrderValidityTypeOverwritten().getId() + " not found");
            return false;
        }
        if (!medOrdTypeIds.contains(enums.getMedicationOrderValidityTypeManualEntry().getId())) {
            log.error("MedicationOrderValidityType " + enums.getMedicationOrderValidityTypeManualEntry().getId() + " not found");
            return false;
        }
        if (!medOrdTypeIds.contains(enums.getMedicationOrderValidityTypeDurationOneTime().getId())) {
            log.error("MedicationOrderValidityType " + enums.getMedicationOrderValidityTypeDurationOneTime().getId() + " not found");
            return false;
        }

        return true;
    }

    private void refreshMedicationOrderValidityType() {
        medOrdValidityTypeList = new ArrayList<>();
        medOrdValidityTypeMap = new HashMap<>();
        for (MedicationOrderValidityType type : medOrdValidityTypeRepo.findAll()) {
            EnumValue.Builder enumBuilder = EnumValue.newBuilder();
            enumBuilder.setId(type.getId());
            enumBuilder.setName(type.getName());
            medOrdValidityTypeList.add(enumBuilder.build());
            medOrdValidityTypeMap.put(type.getId(), type.getName());
        }
    }

    private void initializeMedicationChannel() {
        MedicationChannel medicationChannel = new MedicationChannel();

        medicationChannel.setId(enums.getMedicationChannelPendingConfirmation().getId());
        medicationChannel.setName(enums.getMedicationChannelPendingConfirmation().getName());
        medicationChannelRepo.save(medicationChannel);

        medicationChannel.setId(enums.getMedicationChannelIntravenous().getId());
        medicationChannel.setName(enums.getMedicationChannelIntravenous().getName());
        medicationChannelRepo.save(medicationChannel);

        medicationChannel.setId(enums.getMedicationChannelNasointestinal().getId());
        medicationChannel.setName(enums.getMedicationChannelNasointestinal().getName());
        medicationChannelRepo.save(medicationChannel);
    }

    // Return true if medication_channels table is consistent.
    private Boolean checkMedicationChannel() {
        List<Integer> medicationChannelIds = medicationChannelRepo.findAll().stream()
            .map(channel -> channel.getId())
            .collect(Collectors.toList());

        if (medicationChannelIds.size() != 3) { 
            log.error("medicationChannel count mismatch: " + medicationChannelIds.size());
            log.error("medicationChannelIds: " + medicationChannelIds);
            return false;
        }

        if (!medicationChannelIds.contains(enums.getMedicationChannelPendingConfirmation().getId())) {
            log.error("medicationChannel " + enums.getMedicationChannelPendingConfirmation().getId() + " not found");
            return false;
        }
        if (!medicationChannelIds.contains(enums.getMedicationChannelIntravenous().getId())) {
            log.error("medicationChannel " + enums.getMedicationChannelIntravenous().getId() + " not found");
            return false;
        }
        if (!medicationChannelIds.contains(enums.getMedicationChannelNasointestinal().getId())) {
            log.error("medicationChannel " + enums.getMedicationChannelNasointestinal().getId() + " not found");
            return false;
        }
        return true;
    }

    private void refreshMedicationChannel() {
        medicationChannelList = new ArrayList<>();
        medicationChannelMap = new HashMap<>();
        for (MedicationChannel channel : medicationChannelRepo.findAll()) {
            EnumValue.Builder enumBuilder = EnumValue.newBuilder();
            enumBuilder.setId(channel.getId());
            enumBuilder.setName(channel.getName());
            medicationChannelList.add(enumBuilder.build());
            medicationChannelMap.put(channel.getId(), channel.getName());
        }
    }

    private void initializeMedicationExecutionActionType() {
        MedicationExecutionActionType actionType = new MedicationExecutionActionType();

        actionType.setId(enums.getMedicationExecutionActionTypeStart().getId());
        actionType.setName(enums.getMedicationExecutionActionTypeStart().getName());
        medicationExecutionActionTypeRepo.save(actionType);

        actionType.setId(enums.getMedicationExecutionActionTypePause().getId());
        actionType.setName(enums.getMedicationExecutionActionTypePause().getName());
        medicationExecutionActionTypeRepo.save(actionType);

        actionType.setId(enums.getMedicationExecutionActionTypeResume().getId());
        actionType.setName(enums.getMedicationExecutionActionTypeResume().getName());
        medicationExecutionActionTypeRepo.save(actionType);

        actionType.setId(enums.getMedicationExecutionActionTypeAdjustSpeed().getId());
        actionType.setName(enums.getMedicationExecutionActionTypeAdjustSpeed().getName());
        medicationExecutionActionTypeRepo.save(actionType);

        actionType.setId(enums.getMedicationExecutionActionTypeFastPush().getId());
        actionType.setName(enums.getMedicationExecutionActionTypeFastPush().getName());
        medicationExecutionActionTypeRepo.save(actionType);

        actionType.setId(enums.getMedicationExecutionActionTypeComplete().getId());
        actionType.setName(enums.getMedicationExecutionActionTypeComplete().getName());
        medicationExecutionActionTypeRepo.save(actionType);
    }

    private Boolean checkMedicationExecutionActionType() {
        List<Integer> actionTypeIds = medicationExecutionActionTypeRepo.findAll().stream()
            .map(type -> type.getId())
            .collect(Collectors.toList());

        if (actionTypeIds.size() != 6) {
            log.error("MedicationExecutionActionType count mismatch: " + actionTypeIds.size());
            return false;
        }

        if (!actionTypeIds.contains(enums.getMedicationExecutionActionTypeStart().getId())) {
            log.error("MedicationExecutionActionType " + enums.getMedicationExecutionActionTypeStart().getId() + " not found");
            return false;
        }
        if (!actionTypeIds.contains(enums.getMedicationExecutionActionTypePause().getId())) {
            log.error("MedicationExecutionActionType " + enums.getMedicationExecutionActionTypePause().getId() + " not found");
            return false;
        }
        if (!actionTypeIds.contains(enums.getMedicationExecutionActionTypeResume().getId())) {
            log.error("MedicationExecutionActionType " + enums.getMedicationExecutionActionTypeResume().getId() + " not found");
            return false;
        }
        if (!actionTypeIds.contains(enums.getMedicationExecutionActionTypeAdjustSpeed().getId())) {
            log.error("MedicationExecutionActionType " + enums.getMedicationExecutionActionTypeAdjustSpeed().getId() + " not found");
            return false;
        }
        if (!actionTypeIds.contains(enums.getMedicationExecutionActionTypeFastPush().getId())) {
            log.error("MedicationExecutionActionType " + enums.getMedicationExecutionActionTypeFastPush().getId() + " not found");
            return false;
        }
        if (!actionTypeIds.contains(enums.getMedicationExecutionActionTypeComplete().getId())) {
            log.error("MedicationExecutionActionType " + enums.getMedicationExecutionActionTypeComplete().getId() + " not found");
            return false;
        }

        return true;
    }

    private void refreshMedicationExecutionActionType() {
        medicationExecutionActionTypeList = new ArrayList<>();
        medicationExecutionActionTypeMap = new HashMap<>();
        for (MedicationExecutionActionType type : medicationExecutionActionTypeRepo.findAll()) {
            EnumValue.Builder enumBuilder = EnumValue.newBuilder();
            enumBuilder.setId(type.getId());
            enumBuilder.setName(type.getName());
            medicationExecutionActionTypeList.add(enumBuilder.build());
            medicationExecutionActionTypeMap.put(type.getId(), type.getName());
        }
    }

    private ConfigurableApplicationContext context;
    private MedicationConfigPB medProto;
    private MEnums enums;

    private MedicationTypeRepository medicationTypeRepo;
    @Getter
    private List<EnumValue> medicationTypeList;
    private Map<Integer, String> medicationTypeMap;

    private MedicationFrequencyRepository medicationFrequencyRepo;
    private List<MedicationFrequencySpec> medicationFrequencySpecList;

    private AdministrationRouteGroupRepository administrationRouteGroupRepo;

    private IntakeTypeRepository intakeTypeRepo;

    private OrderDurationTypeRepository orderDurationTypeRepo;
    @Getter
    private List<EnumValue> orderDurationTypeList;
    private Map<Integer, String> orderDurationTypeMap;

    private MedicationOrderValidityTypeRepository medOrdValidityTypeRepo;
    @Getter
    private List<EnumValue> medOrdValidityTypeList;
    private Map<Integer, String> medOrdValidityTypeMap;

    private MedicationChannelRepository medicationChannelRepo;
    @Getter
    private List<EnumValue> medicationChannelList;
    private Map<Integer, String> medicationChannelMap;

    private MedicationExecutionActionTypeRepository medicationExecutionActionTypeRepo;
    @Getter
    private List<EnumValue> medicationExecutionActionTypeList;
    private Map<Integer, String> medicationExecutionActionTypeMap;

    private DeptSystemSettingsRepository deptSystemSettingsRepo;
    private MedOrderGroupSettingsPB defaultOgSettings;

    private MonitoringParamRepository monitoringParamRepo;
}