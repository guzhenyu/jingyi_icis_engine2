package com.jingyicare.jingyi_icis_engine.service;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;

import lombok.extern.slf4j.Slf4j;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import com.google.protobuf.TextFormat;
import com.jingyicare.jingyi_icis_engine.consts.PatientConsts;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCustomHospital.CustomHospitalPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMedication.MedicationFrequencySpecs;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCommon.IcisCommonPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.EnumTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.UserConfigPB;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Component
@Slf4j
public class ProtoConfig {
    public ProtoConfig(
        ConfigurableApplicationContext context,
        @Value("${config_pb_txt}") String configPbTxtPath,
        @Value("${jingyi.textresources.icis_config}") Resource configResource,
        @Value("${jingyi.textresources.common_text}") Resource commonTextResource,
        @Value("${jingyi.textresources.user_config}") Resource userConfigResource,
        @Value("${jingyi.textresources.device_config}") Resource deviceConfigResource,
        @Value("${hospital_pb_txt}") Resource hospitalPbResource,
        @Value("${jingyi.textresources.common_config}") Resource commonConfigResource,
        @Value("${jingyi.textresources.freq_config}") Resource freqConfigResource,
        @Value("${jingyi.textresources.charset}") String charsetName
    ) {
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
            // 血型字典由固定常量生成；外部旧配置中的同名字段不再作为定义来源
            builder.getPatientBuilder().getEnumsV2Builder()
                .clearBloodType().addAllBloodType(PatientConsts.BLOOD_TYPES)
                .clearBloodRh().addAllBloodRh(PatientConsts.BLOOD_RHS);
            this.config = builder.build();
            reader.close();
            log.info("Config loaded successfully");

            loadCommonText(commonTextResource, charsetName);

            if (!this.config.hasUser()
                && userConfigResource != null && userConfigResource.exists()) {
                reader = new BufferedReader(new InputStreamReader(
                    userConfigResource.getInputStream(), charsetName));
                UserConfigPB.Builder userBuilder = UserConfigPB.newBuilder();
                TextFormat.getParser().merge(reader, userBuilder);
                reader.close();

                this.config = this.config.toBuilder()
                    .setUser(userBuilder.build())
                    .build();
                log.info("User config loaded successfully");
            } else if (this.config.hasUser()) {
                log.info("Using user config from primary config resource");
            }

            if (!this.config.hasDevice()
                && deviceConfigResource != null && deviceConfigResource.exists()) {
                reader = new BufferedReader(new InputStreamReader(
                    deviceConfigResource.getInputStream(), charsetName));
                DeviceConfigPB.Builder deviceBuilder = DeviceConfigPB.newBuilder();
                TextFormat.getParser().merge(reader, deviceBuilder);
                reader.close();

                this.config = this.config.toBuilder()
                    .setDevice(deviceBuilder.build())
                    .build();
                log.info("Common device config loaded successfully");
            } else if (this.config.hasDevice()) {
                log.info("Using device config from primary config resource");
            }

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
                    MonitoringPB.Builder monitoringBuilder =
                        this.config.getMonitoring().toBuilder();
                    if (customHospitalConfig.getBalanceGroupCount() > 0) {
                        monitoringBuilder.clearBalanceGroup();
                        monitoringBuilder.addAllBalanceGroup(
                            customHospitalConfig.getBalanceGroupList());
                    }
                    if (customHospitalConfig.getMonitoringGroupCount() > 0) {
                        monitoringBuilder.clearMonitoringGroup();
                        monitoringBuilder.addAllMonitoringGroup(
                            customHospitalConfig.getMonitoringGroupList());
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
                MedicationFrequencySpecs.Builder freqSpecsBuilder =
                    MedicationFrequencySpecs.newBuilder();
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

        } catch (IOException e) {
            log.error("Failed to load text resource: "
                + e.getMessage() + "\n" + e.getStackTrace());
            LogUtils.flushAndQuit(context);
        }
    }

    private void loadCommonText(Resource resource, String charsetName) throws IOException {
        Text text = this.config.getText();
        EnumTextPB enumText = text.getEnumText();
        boolean needsText = text.getStatusCodeMsgCount() == 0;
        boolean needsNegative = enumText.getBloodRhNegativeStrCount() == 0;
        boolean needsPositive = enumText.getBloodRhPositiveStrCount() == 0;
        if ((!needsText && !needsNegative && !needsPositive)
            || resource == null || !resource.exists()) {
            return;
        }

        Text.Builder commonText = Text.newBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                resource.getInputStream(), charsetName))) {
            TextFormat.getParser().merge(reader, commonText);
        }

        // Rh 别名逐列表回填，不受旧配置是否包含状态码文本的影响。
        EnumTextPB.Builder mergedEnums = enumText.toBuilder();
        if (needsNegative) {
            mergedEnums.addAllBloodRhNegativeStr(
                commonText.getEnumText().getBloodRhNegativeStrList());
        }
        if (needsPositive) {
            mergedEnums.addAllBloodRhPositiveStr(
                commonText.getEnumText().getBloodRhPositiveStrList());
        }
        Text.Builder mergedText = needsText ? commonText : text.toBuilder();
        mergedText.setEnumText(mergedEnums);
        this.config = this.config.toBuilder().setText(mergedText).build();
        log.info("Common text config loaded successfully");
    }

    // 所有组件共用已经完成资源合并的不可变配置对象。
    @Bean
    public Config getConfig() {
        return config;
    }

    private Config config;
}
