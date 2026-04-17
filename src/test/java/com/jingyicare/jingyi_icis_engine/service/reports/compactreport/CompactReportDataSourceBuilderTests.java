package com.jingyicare.jingyi_icis_engine.service.reports.compactreport;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCompact.CompactReportTemplatePB;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportRequest;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;

public class CompactReportDataSourceBuilderTests {
    @Test
    public void buildInputsAddsTableScopedPatientMonitoringRecordsInputs() throws Exception {
        ReportProperties properties = new ReportProperties();
        properties.getCompact().setTemplate("classpath:/config/pbtxt/report_compact_builder_test.pb.txt");
        CompactReportTemplatePB compactTemplate =
            new CompactReportTemplateLoader(properties, new DefaultResourceLoader()).load();

        List<JfkDataSourcePB> inputs = new CompactReportDataSourceBuilder().buildInputs(
            compactTemplate,
            MonitoringReportRequest.builder()
                .pid(10001L)
                .deptId("request-dept")
                .queryStartUtc(LocalDateTime.of(2026, 4, 16, 0, 0))
                .queryEndUtc(LocalDateTime.of(2026, 4, 17, 0, 0))
                .shiftStartTime(LocalDateTime.of(2026, 4, 16, 8, 0))
                .build()
        );

        JfkDataSourcePB monitoringInput = inputs.stream()
            .filter(input -> JfkDataSourceIds.PATIENT_MONITORING_RECORDS.equals(input.getMetaId()))
            .findFirst()
            .orElseThrow();

        assertThat(monitoringInput.getId())
            .isEqualTo(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_MONITORING_RECORDS, "table-28"));
        assertThat(strVal(monitoringInput, "table_id")).isEqualTo("table-28");
        assertThat(strVals(monitoringInput, "monitoring_param_codes")).containsExactly(
            "temperature", "pr", "hr", "respiration_rate", "nibp_s", "nibp_d", "Spo2");
        assertThat(doubleVals(monitoringInput, "col_widths")).hasSize(26);
        assertThat(doubleVal(monitoringInput, "font_size")).isEqualTo(8d);
        assertThat(doubleVal(monitoringInput, "char_spacing")).isEqualTo(0d);
        assertThat(doubleVal(monitoringInput, "h_padding")).isEqualTo(2d);

        JfkDataSourcePB bgaInput = inputs.stream()
            .filter(input -> JfkDataSourceIds.PATIENT_BGA_RECORDS.equals(input.getMetaId()))
            .findFirst()
            .orElseThrow();

        assertThat(bgaInput.getId())
            .isEqualTo(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_BGA_RECORDS, "table-38"));
        assertThat(strVal(bgaInput, "table_id")).isEqualTo("table-38");
        assertThat(doubleVals(bgaInput, "col_widths")).containsExactly(74d, 60d, 561.5d, 60d, 60d);
        assertThat(doubleVal(bgaInput, "font_size")).isEqualTo(6d);
        assertThat(doubleVal(bgaInput, "char_spacing")).isEqualTo(0d);
        assertThat(doubleVal(bgaInput, "h_padding")).isEqualTo(2d);
    }

    private String strVal(JfkDataSourcePB input, String id) {
        return field(input, id).getVal().getStrVal();
    }

    private double doubleVal(JfkDataSourcePB input, String id) {
        return field(input, id).getVal().getDoubleVal();
    }

    private List<String> strVals(JfkDataSourcePB input, String id) {
        return field(input, id).getValsList().stream().map(JfkValPB::getStrVal).toList();
    }

    private List<Double> doubleVals(JfkDataSourcePB input, String id) {
        return field(input, id).getValsList().stream().map(JfkValPB::getDoubleVal).toList();
    }

    private JfkFieldDataPB field(JfkDataSourcePB input, String id) {
        return input.getInputDataList().stream()
            .filter(field -> id.equals(field.getId()))
            .findFirst()
            .orElseThrow();
    }
}
