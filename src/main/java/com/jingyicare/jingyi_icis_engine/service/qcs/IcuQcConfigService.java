package com.jingyicare.jingyi_icis_engine.service.qcs;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.TextFormat;
import com.google.protobuf.util.JsonFormat;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.settings.SystemSettings;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.SystemSettingFunctionId;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.SystemSettingsRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class IcuQcConfigService {
    private static final int DEFAULT_SEPSIS_AGE_LOWER_BOUND = 18;
    private static final int DEFAULT_SEPSIS_GRACE_PERIOD_HOURS = 24;
    private static final int DEFAULT_SEPSIS_MBP_THRESHOLD = 65;
    private static final int DEFAULT_SEPSIS_SBP_THRESHOLD = 90;
    private static final int DEFAULT_SEPSIS_DECREASE_SBP_THRESHOLD = 40;
    private static final List<String> DEFAULT_SEPSIS_DIAGNOSIS_KEYWORDS = List.of("脓毒症", "感染性休克");
    private static final List<String> DEFAULT_SEPSIS_BLOOD_CULTURE_KEYWORDS = List.of("细菌培养");
    private static final List<String> DEFAULT_SEPSIS_ABX_BOARD_KEYWORDS = List.of("哌拉西林", "他佐巴坦钠");
    private static final List<String> DEFAULT_SEPSIS_VASOPRESSOR_KEYWORDS = List.of("去甲肾上腺素");
    private static final List<String> DEFAULT_SEPSIS_FLUID_KEYWORDS = List.of("氯化钠", "格林");

    public IcuQcConfigService(
        @Value("${jingyi.textresources.icis_qc_config}") Resource icuQcConfigResource,
        @Value("${jingyi.textresources.charset}") String charsetName,
        ConfigProtoService protoService,
        SystemSettingsRepository systemSettingsRepo,
        UserService userService
    ) {
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.systemSettingsRepo = systemSettingsRepo;
        this.userService = userService;

        this.defaultIcuQcConfigPb = normalizeConfig(
            loadDefaultIcuQcConfig(icuQcConfigResource, charsetName),
            null
        );
        this.icuQcConfigPb = loadCurrentIcuQcConfig(defaultIcuQcConfigPb);
    }

    public IcuQcConfigPB getIcuQcConfigPb() {
        return icuQcConfigPb;
    }

    @Transactional(readOnly = true)
    public GetIqcConfigResp getIqcConfig(String getIqcConfigReqJson) {
        try {
            GetIqcConfigReq.Builder builder = GetIqcConfigReq.newBuilder();
            JsonFormat.parser().merge(getIqcConfigReqJson, builder);
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GetIqcConfigResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        return GetIqcConfigResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setIqcConfig(icuQcConfigPb)
            .build();
    }

    @Transactional
    public GenericResp updateIqcConfig(String updateIqcConfigReqJson) {
        final UpdateIqcConfigReq req;
        try {
            UpdateIqcConfigReq.Builder builder = UpdateIqcConfigReq.newBuilder();
            JsonFormat.parser().merge(updateIqcConfigReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }
        if (!req.hasIqcConfig()) {
            log.error("UpdateIqcConfigReq missing iqc_config");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        IcuQcConfigPB nextConfig = normalizeConfig(req.getIqcConfig(), defaultIcuQcConfigPb);
        final Integer functionId = SystemSettingFunctionId.GET_IQC_CONFIG.getNumber();
        SystemSettings entity = systemSettingsRepo.findById(functionId).orElse(null);
        String settingsStr = ProtoUtils.encodeIcuQcConfig(nextConfig);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (entity == null) {
            entity = SystemSettings.builder()
                .functionId(functionId)
                .functionName(SystemSettingFunctionId.GET_IQC_CONFIG.name())
                .settingsPb(settingsStr)
                .modifiedAt(nowUtc)
                .modifiedBy(account.getFirst())
                .build();
        } else {
            entity.setFunctionName(SystemSettingFunctionId.GET_IQC_CONFIG.name());
            entity.setSettingsPb(settingsStr);
            entity.setModifiedAt(nowUtc);
            entity.setModifiedBy(account.getFirst());
        }
        systemSettingsRepo.save(entity);
        this.icuQcConfigPb = nextConfig;

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    private IcuQcConfigPB loadDefaultIcuQcConfig(Resource configResource, String charsetName) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            configResource.getInputStream(), charsetName))) {
            IcuQcConfigPB.Builder builder = IcuQcConfigPB.newBuilder();
            TextFormat.getParser().merge(reader, builder);
            log.info("ICU QC config loaded successfully: {}", configResource.getDescription());
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to load ICU QC config: {}", e.getMessage(), e);
            return IcuQcConfigPB.newBuilder()
                .setBedUtilization(IcuQcBedUtilizationConfigPB.newBuilder()
                    .setSharedDayMode(IcuQcBedSharedDayModePB.ICU_QC_BED_SHARED_DAY_MODE_FRACTION))
                .setDoctorBedRatio(IcuQcStaffBedRatioConfigPB.newBuilder())
                .setNurseBedRatio(IcuQcStaffBedRatioConfigPB.newBuilder())
                .setApache2Over15AdmissionRate(IcuQcApache2Over15AdmissionRateConfigPB.newBuilder()
                    .setCountByAdmissionTime(true))
                .setSepsisSepticShockDiagnosis(defaultSepsisDiagnosisConfig())
                .build();
        }
    }

    private IcuQcConfigPB loadCurrentIcuQcConfig(IcuQcConfigPB defaultConfig) {
        final Integer functionId = SystemSettingFunctionId.GET_IQC_CONFIG.getNumber();
        SystemSettings entity = systemSettingsRepo.findById(functionId).orElse(null);
        if (entity == null) {
            return defaultConfig;
        }

        IcuQcConfigPB storedConfig = ProtoUtils.decodeIcuQcConfig(entity.getSettingsPb());
        if (storedConfig == null) {
            log.error("Failed to load stored ICU QC config; using default config");
            return defaultConfig;
        }
        log.info("ICU QC config loaded from SystemSettings({})", functionId);
        return normalizeConfig(storedConfig, defaultConfig);
    }

    private IcuQcConfigPB normalizeConfig(IcuQcConfigPB config, IcuQcConfigPB fallbackConfig) {
        IcuQcConfigPB.Builder builder = (config == null ? IcuQcConfigPB.newBuilder() : config.toBuilder());

        if (builder.getItemCount() == 0 && fallbackConfig != null && fallbackConfig.getItemCount() > 0) {
            builder.addAllItem(fallbackConfig.getItemList());
        }

        if (!builder.hasBedUtilization() && fallbackConfig != null && fallbackConfig.hasBedUtilization()) {
            builder.setBedUtilization(fallbackConfig.getBedUtilization());
        }
        if (!builder.hasDoctorBedRatio() && fallbackConfig != null && fallbackConfig.hasDoctorBedRatio()) {
            builder.setDoctorBedRatio(fallbackConfig.getDoctorBedRatio());
        }
        if (!builder.hasNurseBedRatio() && fallbackConfig != null && fallbackConfig.hasNurseBedRatio()) {
            builder.setNurseBedRatio(fallbackConfig.getNurseBedRatio());
        }
        if (!builder.hasApache2Over15AdmissionRate()
            && fallbackConfig != null && fallbackConfig.hasApache2Over15AdmissionRate()) {
            builder.setApache2Over15AdmissionRate(fallbackConfig.getApache2Over15AdmissionRate());
        }
        setDefaultEmptyMetricConfigs(builder, fallbackConfig);

        SepsisSepticShockDiagnosisConfigPB fallbackSepsisConfig =
            fallbackConfig != null && fallbackConfig.hasSepsisSepticShockDiagnosis()
                ? fallbackConfig.getSepsisSepticShockDiagnosis()
                : defaultSepsisDiagnosisConfig();
        SepsisSepticShockDiagnosisConfigPB sepsisConfig =
            builder.hasSepsisSepticShockDiagnosis()
                ? builder.getSepsisSepticShockDiagnosis()
                : fallbackSepsisConfig;
        builder.setSepsisSepticShockDiagnosis(normalizeSepsisDiagnosisConfig(sepsisConfig, fallbackSepsisConfig));

        return builder.build();
    }

    private void setDefaultEmptyMetricConfigs(IcuQcConfigPB.Builder builder, IcuQcConfigPB fallbackConfig) {
        if (fallbackConfig == null) return;
        if (!builder.hasSepticShockBundleCompletionRate() && fallbackConfig.hasSepticShockBundleCompletionRate()) {
            builder.setSepticShockBundleCompletionRate(fallbackConfig.getSepticShockBundleCompletionRate());
        }
        if (!builder.hasPreAntibioticPathogenTestRate() && fallbackConfig.hasPreAntibioticPathogenTestRate()) {
            builder.setPreAntibioticPathogenTestRate(fallbackConfig.getPreAntibioticPathogenTestRate());
        }
        if (!builder.hasDvtPreventionRate() && fallbackConfig.hasDvtPreventionRate()) {
            builder.setDvtPreventionRate(fallbackConfig.getDvtPreventionRate());
        }
        if (!builder.hasArdsPronePositionRate() && fallbackConfig.hasArdsPronePositionRate()) {
            builder.setArdsPronePositionRate(fallbackConfig.getArdsPronePositionRate());
        }
        if (!builder.hasPainAssessmentRate() && fallbackConfig.hasPainAssessmentRate()) {
            builder.setPainAssessmentRate(fallbackConfig.getPainAssessmentRate());
        }
        if (!builder.hasSedationAssessmentRate() && fallbackConfig.hasSedationAssessmentRate()) {
            builder.setSedationAssessmentRate(fallbackConfig.getSedationAssessmentRate());
        }
        if (!builder.hasStandardizedMortalityRatio() && fallbackConfig.hasStandardizedMortalityRatio()) {
            builder.setStandardizedMortalityRatio(fallbackConfig.getStandardizedMortalityRatio());
        }
        if (!builder.hasUnplannedExtubationRate() && fallbackConfig.hasUnplannedExtubationRate()) {
            builder.setUnplannedExtubationRate(fallbackConfig.getUnplannedExtubationRate());
        }
        if (!builder.hasReintubationWithin48HRate() && fallbackConfig.hasReintubationWithin48HRate()) {
            builder.setReintubationWithin48HRate(fallbackConfig.getReintubationWithin48HRate());
        }
        if (!builder.hasUnplannedIcuAdmissionRate() && fallbackConfig.hasUnplannedIcuAdmissionRate()) {
            builder.setUnplannedIcuAdmissionRate(fallbackConfig.getUnplannedIcuAdmissionRate());
        }
        if (!builder.hasIcuReadmissionWithin48HRate() && fallbackConfig.hasIcuReadmissionWithin48HRate()) {
            builder.setIcuReadmissionWithin48HRate(fallbackConfig.getIcuReadmissionWithin48HRate());
        }
        if (!builder.hasVapIncidenceRate() && fallbackConfig.hasVapIncidenceRate()) {
            builder.setVapIncidenceRate(fallbackConfig.getVapIncidenceRate());
        }
        if (!builder.hasCrbsiIncidenceRate() && fallbackConfig.hasCrbsiIncidenceRate()) {
            builder.setCrbsiIncidenceRate(fallbackConfig.getCrbsiIncidenceRate());
        }
        if (!builder.hasBrainInjuryConsciousnessAssessmentRate()
            && fallbackConfig.hasBrainInjuryConsciousnessAssessmentRate()) {
            builder.setBrainInjuryConsciousnessAssessmentRate(fallbackConfig.getBrainInjuryConsciousnessAssessmentRate());
        }
        if (!builder.hasEnteralNutritionWithin48HRate() && fallbackConfig.hasEnteralNutritionWithin48HRate()) {
            builder.setEnteralNutritionWithin48HRate(fallbackConfig.getEnteralNutritionWithin48HRate());
        }
    }

    private SepsisSepticShockDiagnosisConfigPB normalizeSepsisDiagnosisConfig(
        SepsisSepticShockDiagnosisConfigPB config,
        SepsisSepticShockDiagnosisConfigPB fallbackConfig
    ) {
        SepsisSepticShockDiagnosisConfigPB.Builder builder = config.toBuilder();
        if (builder.getAgeLowerBound() <= 0) {
            builder.setAgeLowerBound(fallbackConfig.getAgeLowerBound() > 0
                ? fallbackConfig.getAgeLowerBound()
                : DEFAULT_SEPSIS_AGE_LOWER_BOUND);
        }
        if (builder.getGracePeriodHoursFromAdmissionTime() <= 0) {
            builder.setGracePeriodHoursFromAdmissionTime(fallbackConfig.getGracePeriodHoursFromAdmissionTime() > 0
                ? fallbackConfig.getGracePeriodHoursFromAdmissionTime()
                : DEFAULT_SEPSIS_GRACE_PERIOD_HOURS);
        }
        if (builder.getDiagnosisKeywordCount() == 0) {
            builder.addAllDiagnosisKeyword(fallbackConfig.getDiagnosisKeywordCount() > 0
                ? fallbackConfig.getDiagnosisKeywordList()
                : DEFAULT_SEPSIS_DIAGNOSIS_KEYWORDS);
        }
        builder.setHypotensionCriteria(normalizeHypotensionCriteria(
            builder.hasHypotensionCriteria() ? builder.getHypotensionCriteria() : HypotensionCriteriaPB.getDefaultInstance(),
            fallbackConfig.hasHypotensionCriteria()
                ? fallbackConfig.getHypotensionCriteria()
                : defaultHypotensionCriteria()
        ));
        if (builder.getBloodCultureKeywordCount() == 0) {
            builder.addAllBloodCultureKeyword(fallbackConfig.getBloodCultureKeywordCount() > 0
                ? fallbackConfig.getBloodCultureKeywordList()
                : DEFAULT_SEPSIS_BLOOD_CULTURE_KEYWORDS);
        }
        if (builder.getAbxBoardKeywordCount() == 0) {
            builder.addAllAbxBoardKeyword(fallbackConfig.getAbxBoardKeywordCount() > 0
                ? fallbackConfig.getAbxBoardKeywordList()
                : DEFAULT_SEPSIS_ABX_BOARD_KEYWORDS);
        }
        if (builder.getVasopressorKeywordCount() == 0) {
            builder.addAllVasopressorKeyword(fallbackConfig.getVasopressorKeywordCount() > 0
                ? fallbackConfig.getVasopressorKeywordList()
                : DEFAULT_SEPSIS_VASOPRESSOR_KEYWORDS);
        }
        if (builder.getFluidKeywordCount() == 0) {
            builder.addAllFluidKeyword(fallbackConfig.getFluidKeywordCount() > 0
                ? fallbackConfig.getFluidKeywordList()
                : DEFAULT_SEPSIS_FLUID_KEYWORDS);
        }
        return builder.build();
    }

    private HypotensionCriteriaPB normalizeHypotensionCriteria(
        HypotensionCriteriaPB config,
        HypotensionCriteriaPB fallbackConfig
    ) {
        HypotensionCriteriaPB.Builder builder = config.toBuilder();
        if (builder.getMbpThreshold() <= 0) {
            builder.setMbpThreshold(fallbackConfig.getMbpThreshold() > 0
                ? fallbackConfig.getMbpThreshold()
                : DEFAULT_SEPSIS_MBP_THRESHOLD);
        }
        if (builder.getSbpThreshold() <= 0) {
            builder.setSbpThreshold(fallbackConfig.getSbpThreshold() > 0
                ? fallbackConfig.getSbpThreshold()
                : DEFAULT_SEPSIS_SBP_THRESHOLD);
        }
        if (builder.getDecreaseSbpThreshold() <= 0) {
            builder.setDecreaseSbpThreshold(fallbackConfig.getDecreaseSbpThreshold() > 0
                ? fallbackConfig.getDecreaseSbpThreshold()
                : DEFAULT_SEPSIS_DECREASE_SBP_THRESHOLD);
        }
        return builder.build();
    }

    private HypotensionCriteriaPB defaultHypotensionCriteria() {
        return HypotensionCriteriaPB.newBuilder()
            .setMbpThreshold(DEFAULT_SEPSIS_MBP_THRESHOLD)
            .setSbpThreshold(DEFAULT_SEPSIS_SBP_THRESHOLD)
            .setDecreaseSbpThreshold(DEFAULT_SEPSIS_DECREASE_SBP_THRESHOLD)
            .build();
    }

    private SepsisSepticShockDiagnosisConfigPB defaultSepsisDiagnosisConfig() {
        return SepsisSepticShockDiagnosisConfigPB.newBuilder()
            .setAgeLowerBound(DEFAULT_SEPSIS_AGE_LOWER_BOUND)
            .setGracePeriodHoursFromAdmissionTime(DEFAULT_SEPSIS_GRACE_PERIOD_HOURS)
            .addAllDiagnosisKeyword(DEFAULT_SEPSIS_DIAGNOSIS_KEYWORDS)
            .setHypotensionCriteria(defaultHypotensionCriteria())
            .addAllBloodCultureKeyword(DEFAULT_SEPSIS_BLOOD_CULTURE_KEYWORDS)
            .addAllAbxBoardKeyword(DEFAULT_SEPSIS_ABX_BOARD_KEYWORDS)
            .addAllVasopressorKeyword(DEFAULT_SEPSIS_VASOPRESSOR_KEYWORDS)
            .addAllFluidKeyword(DEFAULT_SEPSIS_FLUID_KEYWORDS)
            .build();
    }

    private final List<String> statusCodeMsgList;
    private final SystemSettingsRepository systemSettingsRepo;
    private final UserService userService;
    private final IcuQcConfigPB defaultIcuQcConfigPb;
    private volatile IcuQcConfigPB icuQcConfigPb;
}
