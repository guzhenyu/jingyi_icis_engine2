package com.jingyicare.jingyi_icis_engine.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.GetConfigResp;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonEnumsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupPB;
import com.jingyicare.jingyi_icis_engine.repository.medications.IntakeTypeRepository;
import com.jingyicare.jingyi_icis_engine.service.monitorings.MonEnums;
import com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

class ConfigProtoServiceMonitoringTests {
    @Test
    void keepsMonitoringDictionaryLabelsSeparateFromGroupTemplateNames() {
        Config.Builder configBuilder = Config.newBuilder();
        configBuilder.getTextBuilder().addStatusCodeMsg("OK");
        configBuilder.getMonitoringBuilder()
            .setEnums(MonEnumsPB.newBuilder().setBalanceOut("出量类型"))
            .addBalanceGroup(MonitoringGroupPB.newBuilder()
                .setBalanceRole(MonEnums.BALANCE_TYPE_OUT).setName("排出量记录组"))
            .addBalanceGroup(MonitoringGroupPB.newBuilder()
                .setBalanceRole(MonEnums.BALANCE_TYPE_NET).setName("本班净量"))
            .addBalanceGroup(MonitoringGroupPB.newBuilder()
                .setBalanceRole(MonEnums.BALANCE_TYPE_IN).setName("摄入记录组"));
        Config config = configBuilder.build();
        ProtoConfig protoConfig = mock(ProtoConfig.class);
        when(protoConfig.getConfig()).thenReturn(config);
        IntakeTypeRepository intakeTypes = mock(IntakeTypeRepository.class);
        when(intakeTypes.findAll()).thenReturn(List.of());

        GetConfigResp response = new ConfigProtoService(config, null, intakeTypes,
            new PatientEnums(protoConfig), new MonEnums(protoConfig)).getWebApiConfig();

        JsonObject json = JsonParser.parseString(ProtoUtils.protoToJson(response))
            .getAsJsonObject();
        assertThat(json.get("paramCodeSummary").getAsString()).isEqualTo("all");
        assertThat(json.get("balanceInGroupName").getAsString()).isEqualTo("摄入记录组");
        assertThat(json.get("balanceOutGroupName").getAsString()).isEqualTo("排出量记录组");
        assertThat(json.get("balanceNetGroupName").getAsString()).isEqualTo("本班净量");
        JsonObject enums = json.getAsJsonObject("monitoringEnums");
        assertThat(enums.keySet()).containsExactlyInAnyOrder(
            "balanceNan", "balanceIn", "balanceOut", "balanceNet",
            "groupTypeBalance", "groupTypeMonitoring", "paramCategoryPatient",
            "paramCategoryMonitor", "paramCategoryVent", "paramCategoryCrrt",
            "paramCategoryBga", "paramCategoryEcmo", "paramUiModalNan");
        assertThat(enums.getAsJsonObject("balanceOut").get("id").getAsInt()).isEqualTo(2);
        assertThat(enums.getAsJsonObject("balanceOut").get("name").getAsString())
            .isEqualTo("出量类型");
        assertThat(enums.getAsJsonObject("balanceNan").get("id").getAsInt()).isZero();
        assertThat(enums.getAsJsonObject("paramUiModalNan").get("id").getAsInt()).isEqualTo(1);
    }
}
