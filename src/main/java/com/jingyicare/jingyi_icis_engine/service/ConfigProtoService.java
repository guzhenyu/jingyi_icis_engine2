package com.jingyicare.jingyi_icis_engine.service;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.*;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCommon.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.medications.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.medications.*;

import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
public class ConfigProtoService {
    public ConfigProtoService(
        Config config,
        @Autowired DeptSystemSettingsRepository deptSettingsRepo,
        @Autowired IntakeTypeRepository intakeTypeRepo
    ) {
        this.config = config;
        this.genderMap = config == null ? null :
            config.getCommon().getEnums().getGenderList().stream()
                .collect(Collectors.toMap(EnumValue::getId, EnumValue::getName));
        this.maritalStatusMap = config == null ? null :
            config.getCommon().getEnums().getMaritalStatusList().stream()
                .collect(Collectors.toMap(EnumValue::getId, EnumValue::getName));
        this.deptSettingsRepo = deptSettingsRepo;
        this.intakeTypeRepo = intakeTypeRepo;
    }

    public ReturnCode getReturnCode(StatusCode code) {
        return ReturnCode.newBuilder().setCode(code.ordinal())
            .setMsg(config.getText().getStatusCodeMsg(code.getNumber())).build();
    }

    public String getBoolStr(Boolean b) {
        if (b) return config.getText().getWebApiMessage().getYesStr();
        return config.getText().getWebApiMessage().getNoStr();
    }

    public String getGenderStr(Integer genderId) {
        return genderMap.getOrDefault(genderId, "");
    }

    public String getMaritalStatusStr(Integer maritalStatusId) {
        return maritalStatusMap.getOrDefault(maritalStatusId, "");
    }

    public WebApiMessage getWebApiText() {
        return config.getText().getWebApiMessage();
    }

    public GetConfigResp getWebApiConfig() {
        return GetConfigResp.newBuilder()
            .setRt(getReturnCode(StatusCode.OK))
            .setCommonEnums(config.getCommon().getEnums())
            .setPatientEnums(config.getPatient().getEnumsV2())
            .setTubeEnums(config.getTube().getEnums())
            .setTubeTypeList(config.getTube().getTypeList())
            .setTubeAttrList(config.getTube().getAttributeList())
            .setTubeStatusList(config.getTube().getStatusList())
            .setParamCodeSummary(config.getMonitoring().getParamCodeSummary())
            .setBalanceInGroupName(config.getMonitoring().getBalanceInGroupName())
            .setBalanceOutGroupName(config.getMonitoring().getBalanceOutGroupName())
            .setBalanceNetGroupName(config.getMonitoring().getBalanceNetGroupName())
            .setMonitoringEnums(config.getMonitoring().getEnums())
            .setMedicationEnums(config.getMedication().getEnums())
            .addAllDosageUnit(config.getText().getUnits().getUnitList())
            .addAllIntakeType(getIntakeTypesFromDb())
            .addAllDosageRateUnit(config.getMedication().getEnums().getDoseRateUnitList())
            .addAllSolidUnit(config.getMedication().getEnums().getSolidUnitList())
            .setScoreEnums(config.getScore().getEnums())
            .setNursingRecordSpecialTerm(config.getNursingRecord().getSpecialTerm())
            .setNursingRecordQuickWords(config.getNursingRecord().getQuickWords())
            .setJfkEnums(config.getJfk().getEnums())
            .addAllJfkInputs(config.getJfk().getInputsList())
            .addAllJfkDataSources(config.getJfk().getDataSourcesList())
            .setDeviceEnums(config.getDevice().getEnums())
            .setDeviceCascade(config.getDevice().getDeviceCascade())
            .setDiagnosisEnums(config.getDiagnosis().getEnums())
            .setBgaEnums(config.getBga().getEnums())
            .setOverviewEnums(config.getOverview().getEnums())
            .addAllDoctorScoreType(config.getDoctorScore().getDoctorScoreTypeList())
            .addAllPredefinedParamValue(config.getUrl().getPredefinedParamValueList())
            .addAllPredefinedParamEncoder(config.getUrl().getPredefinedParamEncoderList())
            .addAllSkincareCategory(config.getSkincare().getSkincareCategoryList())
            .build();
    }

    private List<IntakeTypePB> getIntakeTypesFromDb() {
        return intakeTypeRepo.findAll().stream()
            .sorted(Comparator.comparing(IntakeType::getId, Comparator.nullsLast(Integer::compareTo)))
            .map(intakeType -> IntakeTypePB.newBuilder()
                .setId(intakeType.getId() == null ? 0 : intakeType.getId())
                .setName(StrUtils.isBlank(intakeType.getName()) ? "" : intakeType.getName())
                .setMonitoringParamCode(
                    StrUtils.isBlank(intakeType.getMonitoringParamCode()) ? "" : intakeType.getMonitoringParamCode()
                )
                .build())
            .collect(Collectors.toList());
    }

    public Boolean checkFutureTime(String deptId, LocalDateTime utcToCheck) {
        boolean checkFutureTime = false;

        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_APP_SETTINGS.getNumber());
        DeptSystemSettings settingsEntity = deptSettingsRepo.findById(settingsId).orElse(null);
        AppGeneralSettingsPB generalSettingsPb = null;
        if (settingsEntity != null) {
            generalSettingsPb = ProtoUtils.decodeAppGeneralSettings(settingsEntity.getSettingsPb());
            checkFutureTime = generalSettingsPb.getCheckFutureTime();
        }

        if (!checkFutureTime) return true;

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (checkFutureTime && utcToCheck.isAfter(nowUtc)) {
            return false;
        }
        return true;
    }

    public String getZoneId() {
        return config.getZoneId();
    }

    @Getter
    private final Config config;

    private final Map<Integer, String> genderMap;
    private final Map<Integer, String> maritalStatusMap;

    private final DeptSystemSettingsRepository deptSettingsRepo;
    private final IntakeTypeRepository intakeTypeRepo;
}
