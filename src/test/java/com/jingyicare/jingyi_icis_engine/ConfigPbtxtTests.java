package com.jingyicare.jingyi_icis_engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.google.protobuf.TextFormat;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;

class ConfigPbtxtTests {
    @Test
    void shouldParseXiuningConfigAndAllowHospitalMedicationOrderType() throws Exception {
        Config.Builder builder = Config.newBuilder();
        try (InputStream input = Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream("config/pbtxt/hospitals/xnxrmyy_icis_config.pb.txt"),
            "config/pbtxt/hospitals/xnxrmyy_icis_config.pb.txt"
        )) {
            TextFormat.getParser().merge(new InputStreamReader(input, StandardCharsets.UTF_8), builder);
        }

        assertThat(builder.getMedication().getOrderGroupSettings().getAllowOrderTypeList())
            .contains("西药", "西成药");
    }

    @Test
    void shouldParseCommonTextConfig() throws Exception {
        Text.Builder builder = Text.newBuilder();
        try (InputStream input = Objects.requireNonNull(
            getClass().getClassLoader().getResourceAsStream("config/pbtxt/common_text.pb.txt"),
            "config/pbtxt/common_text.pb.txt"
        )) {
            TextFormat.getParser().merge(new InputStreamReader(input, StandardCharsets.UTF_8), builder);
        }

        assertThat(builder.getStatusCodeMsgList()).contains("ok", "患者不存在");
        assertThat(builder.getWebApiMessage().getYesStr()).isNotBlank();
    }
}
