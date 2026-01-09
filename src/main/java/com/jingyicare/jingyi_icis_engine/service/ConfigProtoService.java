package com.jingyicare.jingyi_icis_engine.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.InputStreamReader;
import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.google.protobuf.TextFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.grpc.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCommon.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCustomHospital.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ConfigProtoService {
    public ConfigProtoService(
            ConfigurableApplicationContext context,
            @Value("${config_pb_txt}") String configPbTxtPath,
            @Value("${jingyi.textresources.icis_config}") Resource configResource,
            @Value("${hospital_pb_txt}") Resource hospitalPbResource,
            @Value("${jingyi.textresources.common_config}") Resource commonConfigResource,
            @Value("${jingyi.textresources.freq_config}") Resource freqConfigResource,
            @Value("${jingyi.textresources.engine_config}") Resource engineConfigResource,
            @Value("${jingyi.textresources.charset}") String charsetName) {
        BufferedReader reader = null;
        try {
            if (!StrUtils.isBlank(configPbTxtPath)) {
                File configFile = new File(configPbTxtPath);
                if (configFile.exists() && configFile.isFile()) {
                    reader = new BufferedReader(new FileReader(configFile));
                    log.info("Using config_pb_txt file: {}", configPbTxtPath);
                }
            }
            if (reader == null) {
                reader = new BufferedReader(new InputStreamReader(
                    configResource.getInputStream(), charsetName));
                log.info("Using default text resource");
            }

            Config.Builder builder = Config.newBuilder();
            TextFormat.getParser().merge(reader, builder);
            this.config = builder.build();
            reader.close();
            log.info("Config loaded successfully");

            // 医院特定配置
            if (hospitalPbResource != null && hospitalPbResource.exists()) {
                reader = new BufferedReader(new InputStreamReader(
                    hospitalPbResource.getInputStream(), charsetName));
                CustomHospitalPB.Builder customHospitalBuilder = CustomHospitalPB.newBuilder();
                TextFormat.getParser().merge(reader, customHospitalBuilder);
                CustomHospitalPB customHospitalConfig = customHospitalBuilder.build();
                reader.close();

                if (customHospitalConfig.getBalanceGroupCount() > 0 || 
                    customHospitalConfig.getMonitoringGroupCount() > 0
                ) {
                    log.info("Using hospital_pb_txt file: {}", hospitalPbResource.getFilename());
                    MonitoringPB.Builder monitoringBuilder = this.config.getMonitoring().toBuilder();
                    if (customHospitalConfig.getBalanceGroupCount() > 0) {
                        monitoringBuilder.clearBalanceGroup();
                        monitoringBuilder.addAllBalanceGroup(customHospitalConfig.getBalanceGroupList());
                    }
                    if (customHospitalConfig.getMonitoringGroupCount() > 0) {
                        monitoringBuilder.clearMonitoringGroup();
                        monitoringBuilder.addAllMonitoringGroup(customHospitalConfig.getMonitoringGroupList());
                    }
                    this.config = this.config.toBuilder()
                        .setMonitoring(monitoringBuilder.build())
                        .build();
                    log.info("Hospital config loaded successfully");
                }
            }

            // 通用
            if (commonConfigResource != null && commonConfigResource.exists()) {
                reader = new BufferedReader(new InputStreamReader(
                    commonConfigResource.getInputStream(), charsetName));
                IcisCommonPB.Builder commonBuilder = IcisCommonPB.newBuilder();
                TextFormat.getParser().merge(reader, commonBuilder);
                IcisCommonPB commonConfig = commonBuilder.build();

                this.config = this.config.toBuilder()
                    .setCommon(commonConfig)
                    .build();
                reader.close();
                log.info("Common config loaded successfully");
            }

            // 医嘱：加载频次配置
            if (freqConfigResource != null && freqConfigResource.exists()) {
                reader = new BufferedReader(new InputStreamReader(
                    freqConfigResource.getInputStream(), charsetName));
                MedicationFrequencySpecs.Builder freqSpecsBuilder = MedicationFrequencySpecs.newBuilder();
                TextFormat.getParser().merge(reader, freqSpecsBuilder);
                MedicationFrequencySpecs freqSpecs = freqSpecsBuilder.build();

                this.config = this.config.toBuilder()
                    .setMedication(this.config.getMedication().toBuilder()
                        .setFreqSpec(freqSpecs)
                        .build())
                    .build();
                reader.close();
                log.info("Medication frequency config loaded successfully");
            }

            // 引擎RPC：加载配置
            if (engineConfigResource != null && engineConfigResource.exists()) {
                reader = new BufferedReader(new InputStreamReader(
                    engineConfigResource.getInputStream(), charsetName));
                EngineConfigPB.Builder engineConfigBuilder = EngineConfigPB.newBuilder();
                TextFormat.getParser().merge(reader, engineConfigBuilder);
                this.engineConfig = engineConfigBuilder.build();
                reader.close();
                log.info("Engine config loaded successfully");
            }

        } catch (IOException e) {
            log.error("Failed to load text resource: " + e.getMessage() + "\n" + e.getStackTrace());
            LogUtils.flushAndQuit(context);
        } finally {
            this.genderMap = config == null ? null :
                config.getCommon().getEnums().getGenderList().stream()
                    .collect(Collectors.toMap(EnumValue::getId, EnumValue::getName));
            this.maritalStatusMap = config == null ? null :
                config.getCommon().getEnums().getMaritalStatusList().stream()
                    .collect(Collectors.toMap(EnumValue::getId, EnumValue::getName));
        }
    }

    public ReturnCode getReturnCode(StatusCode code) {
        return ReturnCode.newBuilder().setCode(code.ordinal())
            .setMsg(config.getText().getStatusCodeMsg(code.getNumber())).build();
    }

    public ReturnCode getEngineReturnCode(EngineStatusCode code) {
        return ReturnCode.newBuilder().setCode(code.ordinal())
            .setMsg(engineConfig.getStatusCodeMsg(code.getNumber())).build();
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
            .addAllIntakeType(config.getMedication().getIntakeTypes().getIntakeTypeList())
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
            .build();
    }

    @Getter
    private Config config;

    @Getter
    private EngineConfigPB engineConfig;

    private final Map<Integer, String> genderMap;
    private final Map<Integer, String> maritalStatusMap;
}