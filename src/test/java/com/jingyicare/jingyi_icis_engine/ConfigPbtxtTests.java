package com.jingyicare.jingyi_icis_engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import org.junit.jupiter.api.Test;

import com.google.protobuf.TextFormat;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.Text;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.UserConfigPB;

class ConfigPbtxtTests {
    @Test
    void shouldParseAllPatientConfigsWithRenamedFields() throws Exception {
        for (String path : List.of(
            "config/pbtxt/icis_config.pb.txt",
            "config/pbtxt/hospitals/ah2_icis_config.pb.txt",
            "config/pbtxt/hospitals/xaxrmyy_icis_config.pb.txt",
            "config/pbtxt/hospitals/xnxrmyy_icis_config.pb.txt"
        )) {
            Config.Builder builder = Config.newBuilder();
            try (InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(path), path
            )) {
                TextFormat.getParser().merge(new InputStreamReader(input, StandardCharsets.UTF_8), builder);
            }

            assertThat(builder.getPatient().getEnumsV2().getDischargeTypeCount()).isEqualTo(3);
            List<String> columnIds = builder.getPatient().getDefaultDisplayFieldSettingsList().stream()
                .flatMap(setting -> setting.getTableList().stream())
                .flatMap(table -> table.getRequiredColList().stream())
                .map(column -> column.getColumnId())
                .toList();
            assertThat(columnIds).contains("from_dept_name", "discharge_type", "to_dept_name");
            assertThat(columnIds).doesNotContain(
                "admission_source_dept_name", "discharged_type", "discharged_dept_name"
            );
        }
    }

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

    @Test
    void shouldParseExtractedUserConfigs() throws Exception {
        for (String path : new String[] {
            "config/pbtxt/common_user.pb.txt",
            "text_resources/test_user.pb.txt",
        }) {
            UserConfigPB.Builder builder = UserConfigPB.newBuilder();
            try (InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(path), path
            )) {
                TextFormat.getParser().merge(new InputStreamReader(input, StandardCharsets.UTF_8), builder);
            }

            assertThat(builder.getAdminAccountId()).isEqualTo("admin");
            assertThat(builder.getMenu().getGroupCount()).isGreaterThan(0);
            assertThat(builder.getRoleCount()).isGreaterThan(0);
        }
    }
}
