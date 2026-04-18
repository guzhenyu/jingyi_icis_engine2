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

        JfkDataSourcePB balanceInput = inputs.stream()
            .filter(input -> JfkDataSourceIds.PATIENT_BALANCE_RECORDS.equals(input.getMetaId()))
            .findFirst()
            .orElseThrow();

        assertThat(balanceInput.getId())
            .isEqualTo(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_BALANCE_RECORDS, "table-228"));
        assertThat(strVal(balanceInput, "table_id")).isEqualTo("table-228");
        assertThat(strVals(balanceInput, "balance_param_codes")).containsExactly(
            "intravenous_intake", "hourly_intake", "total_intake");
        assertThat(doubleVals(balanceInput, "col_widths")).hasSize(26);
        assertThat(doubleVal(balanceInput, "font_size")).isEqualTo(6d);
        assertThat(doubleVal(balanceInput, "char_spacing")).isEqualTo(0d);
        assertThat(doubleVal(balanceInput, "h_padding")).isEqualTo(2d);

        JfkDataSourcePB medexeInput = inputs.stream()
            .filter(input -> JfkDataSourceIds.MEDEXE_RECORDS.equals(input.getMetaId()))
            .findFirst()
            .orElseThrow();

        assertThat(medexeInput.getId())
            .isEqualTo(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.MEDEXE_RECORDS, "table-236"));
        assertThat(strVal(medexeInput, "table_id")).isEqualTo("table-236");
        assertThat(int64Val(medexeInput, "intake_type_id")).isEqualTo(1L);
        assertThat(doubleVals(medexeInput, "col_widths")).containsExactly(
            129.5d, 50d,
            25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d,
            25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d, 25d);
        assertThat(doubleVal(medexeInput, "font_size")).isEqualTo(6d);
        assertThat(doubleVal(medexeInput, "char_spacing")).isEqualTo(0d);
        assertThat(doubleVal(medexeInput, "h_padding")).isEqualTo(2d);

        JfkDataSourcePB tubeInput = inputs.stream()
            .filter(input -> JfkDataSourceIds.PATIENT_TUBE_RECORDS.equals(input.getMetaId()))
            .findFirst()
            .orElseThrow();

        assertThat(tubeInput.getId())
            .isEqualTo(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_TUBE_RECORDS, "table-253"));
        assertThat(strVal(tubeInput, "table_id")).isEqualTo("table-253");
        assertThat(doubleVals(tubeInput, "col_widths")).containsExactly(100d, 100d, 80d, 536d);
        assertThat(doubleVal(tubeInput, "font_size")).isEqualTo(6d);
        assertThat(doubleVal(tubeInput, "char_spacing")).isEqualTo(0d);
        assertThat(doubleVal(tubeInput, "h_padding")).isEqualTo(2d);

        JfkDataSourcePB nursingInput = inputs.stream()
            .filter(input -> JfkDataSourceIds.PATIENT_NURSING_RECORDS.equals(input.getMetaId()))
            .findFirst()
            .orElseThrow();

        assertThat(nursingInput.getId())
            .isEqualTo(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_NURSING_RECORDS, "table-256"));
        assertThat(strVal(nursingInput, "table_id")).isEqualTo("table-256");
        assertThat(doubleVals(nursingInput, "col_widths")).containsExactly(100d, 596d, 60d, 60d);
        assertThat(doubleVal(nursingInput, "font_size")).isEqualTo(6d);
        assertThat(doubleVal(nursingInput, "char_spacing")).isEqualTo(0d);
        assertThat(doubleVal(nursingInput, "h_padding")).isEqualTo(2d);

        JfkDataSourcePB skincareInput = inputs.stream()
            .filter(input -> JfkDataSourceIds.PATIENT_SKINCARE_RECORDS.equals(input.getMetaId()))
            .findFirst()
            .orElseThrow();

        assertThat(skincareInput.getId())
            .isEqualTo(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_SKINCARE_RECORDS, "table-259"));
        assertThat(strVal(skincareInput, "table_id")).isEqualTo("table-259");
        assertThat(doubleVals(skincareInput, "col_widths")).containsExactly(100d, 656.5d, 60d);
        assertThat(doubleVal(skincareInput, "font_size")).isEqualTo(6d);
        assertThat(doubleVal(skincareInput, "char_spacing")).isEqualTo(0d);
        assertThat(doubleVal(skincareInput, "h_padding")).isEqualTo(2d);
    }

    private String strVal(JfkDataSourcePB input, String id) {
        return field(input, id).getVal().getStrVal();
    }

    private long int64Val(JfkDataSourcePB input, String id) {
        return field(input, id).getVal().getInt64Val();
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
