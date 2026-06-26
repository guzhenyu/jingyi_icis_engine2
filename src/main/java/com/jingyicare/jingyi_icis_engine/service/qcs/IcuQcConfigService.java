package com.jingyicare.jingyi_icis_engine.service.qcs;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import com.google.protobuf.TextFormat;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;

@Service
@Slf4j
public class IcuQcConfigService {
    public IcuQcConfigService(
        @Value("${jingyi.textresources.icis_qc_config}") Resource icuQcConfigResource,
        @Value("${jingyi.textresources.charset}") String charsetName,
        ConfigProtoService protoService
    ) {
        this.icuQcConfigPb = loadIcuQcConfig(icuQcConfigResource, charsetName, protoService.getConfig().getQualityControl());
    }

    public IcuQcConfigPB getIcuQcConfigPb() {
        return icuQcConfigPb;
    }

    private IcuQcConfigPB loadIcuQcConfig(Resource configResource, String charsetName, QcConfigPB legacyConfig) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            configResource.getInputStream(), charsetName))) {
            IcuQcConfigPB.Builder builder = IcuQcConfigPB.newBuilder();
            TextFormat.getParser().merge(reader, builder);
            log.info("ICU QC config loaded successfully: {}", configResource.getDescription());
            return builder.build();
        } catch (Exception e) {
            log.error("Failed to load ICU QC config; using legacy quality_control config: {}", e.getMessage(), e);
            return fromLegacyConfig(legacyConfig);
        }
    }

    private IcuQcConfigPB fromLegacyConfig(QcConfigPB legacyConfig) {
        IcuQcConfigPB.Builder builder = IcuQcConfigPB.newBuilder()
            .addAllItem(legacyConfig.getItemList())
            .addAllDoctorRoleId(legacyConfig.getDoctorRoleIdList())
            .addAllNurseRoleId(legacyConfig.getNurseRoleIdList())
            .addAllPainScoreGroupCode(legacyConfig.getPainScoreGroupCodeList())
            .addAllSedationScoreGroupCode(legacyConfig.getSedationScoreGroupCodeList())
            .addAllStatsDeptId(legacyConfig.getStatsDeptIdList())
            .setBedUtilization(IcuQcBedUtilizationConfigPB.newBuilder()
                .setSharedDayMode(legacyConfig.getBedSharedDayMode())
                .build())
            .setApache2Over15AdmissionRate(IcuQcApache2Over15AdmissionRateConfigPB.newBuilder()
                .setCountByAdmissionTime(true)
                .build());
        return builder.build();
    }

    private final IcuQcConfigPB icuQcConfigPb;
}
