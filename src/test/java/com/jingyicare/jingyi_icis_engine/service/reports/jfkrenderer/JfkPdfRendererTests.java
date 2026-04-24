package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkAbsContainerPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkAcTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourceMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkElementMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldDataPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkFieldMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkPagePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTableColumnMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTemplatePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCompact.CompactReportTemplatePB;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataSourceIds;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.compactreport.CompactReportTemplateLoader;

public class JfkPdfRendererTests {
    @Test
    public void renderWritesMinimalPdf(@TempDir Path tempDir) throws Exception {
        JfkPdfRenderer renderer = new JfkPdfRenderer(new DefaultResourceLoader());
        Path output = tempDir.resolve("compact.pdf");

        JfkRenderResult result = renderer.render(
            template(),
            null,
            List.of(),
            List.of(),
            Files.readAllBytes(Path.of("src/main/resources/fonts/msyh.ttf")),
            output
        );

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(output).exists();
        assertThat(Files.size(output)).isGreaterThan(0);
    }

    @Test
    public void renderWritesConfiguredCompactTemplateWithSyntheticData(@TempDir Path tempDir) throws Exception {
        ReportProperties properties = new ReportProperties();
        properties.getCompact().setTemplate("classpath:/config/pbtxt/report_compact_renderer_test.pb.txt");
        CompactReportTemplatePB compactTemplate =
            new CompactReportTemplateLoader(properties, new DefaultResourceLoader()).load();
        Path output = tempDir.resolve("configured-compact.pdf");

        JfkRenderResult result = new JfkPdfRenderer(new DefaultResourceLoader()).render(
            compactTemplate.getTemplate(),
            compactTemplate.getLogo(),
            syntheticMetas(),
            syntheticDataSources(),
            Files.readAllBytes(Path.of("src/main/resources/fonts/msyh.ttf")),
            output
        );

        assertThat(result.pageCount()).isEqualTo(1);
        assertThat(output).exists();
        assertThat(Files.size(output)).isGreaterThan(0);
    }

    private JfkTemplatePB template() {
        return JfkTemplatePB.newBuilder()
            .setId(1)
            .setDeptId("test")
            .setName("compact-test")
            .setPageSizeId(2)
            .setIsPageOrientationPortrait(false)
            .addPages(JfkPagePB.newBuilder()
                .addTexts(JfkTextPB.newBuilder()
                    .setId("text-1")
                    .setType("text")
                    .setX(10)
                    .setY(560)
                    .setWidth(100)
                    .setHeight(20)
                    .setVAlignId(2)
                    .setHAlignId(4)
                    .setFont("Microsoft YaHei")
                    .setFontSize(10)
                    .setFontColor("#000000")
                    .setContent("测试")
                    .build())
                .addContainers(JfkAbsContainerPB.newBuilder()
                    .setId("container-1")
                    .setName("container")
                    .setTop(530)
                    .setHeight(100)
                    .setNextPageTop(530)
                    .setNextPageHeight(100)
                    .addAcTables(JfkAcTablePB.newBuilder()
                        .setOffsetTop(0)
                        .setTbl(staticTable())
                        .build())
                    .build())
                .build())
            .build();
    }

    private JfkTablePB staticTable() {
        return JfkTablePB.newBuilder()
            .setId("table-1")
            .setType("table")
            .setX(10)
            .setY(0)
            .setRows(1)
            .setCols(1)
            .addCellWidths(100)
            .addCellHeights(20)
            .setLineWidth(0.5f)
            .setLineColor("#000000")
            .setVAlignId(2)
            .setHAlignId(5)
            .setFont("Microsoft YaHei")
            .setFontSize(10)
            .setFontColor("#000000")
            .addColumnMetas(JfkTableColumnMetaPB.newBuilder()
                .setId("col-1")
                .setName("列1")
                .setElementMeta(JfkElementMetaPB.newBuilder().setContentType(1).build())
                .addStaticVals("生命体征")
                .build())
            .build();
    }

    private List<JfkDataSourceMetaPB> syntheticMetas() {
        JfkDataSourceMetaPB.Builder patientInfo = JfkDataSourceMetaPB.newBuilder().setId("patient_info");
        for (String fieldId : List.of("mrn", "bed_no", "patient_name", "gender", "age")) {
            patientInfo.addOutputFields(stringFieldMeta(fieldId));
        }

        JfkDataSourceMetaPB.Builder timeRange = JfkDataSourceMetaPB.newBuilder().setId("monitoring_time_range");
        timeRange.addOutputFields(stringFieldMeta("time_txt"));
        timeRange.addOutputFields(stringFieldMeta("unit_txt"));
        for (int i = 1; i <= 24; i++) {
            timeRange.addOutputFields(stringFieldMeta("hour" + i));
        }

        JfkDataSourceMetaPB.Builder monitoringRecords = JfkDataSourceMetaPB.newBuilder()
            .setId("patient_monitoring_records")
            .addOutputFields(stringsFieldMeta("param_name"))
            .addOutputFields(stringsFieldMeta("param_unit"));
        for (int i = 1; i <= 24; i++) {
            monitoringRecords.addOutputFields(stringsFieldMeta("hour" + i));
        }

        JfkDataSourceMetaPB.Builder nonHourlyMonitoringRecords = JfkDataSourceMetaPB.newBuilder()
            .setId("patient_non_hourly_monitoring_records")
            .addOutputFields(stringsFieldMeta("record_time"))
            .addOutputFields(stringsFieldMeta("content"))
            .addOutputFields(signPicFieldMeta("recorded_by"));

        JfkDataSourceMetaPB.Builder nursingOrders = JfkDataSourceMetaPB.newBuilder()
            .setId("patient_nursing_orders")
            .addOutputFields(stringsFieldMeta("record_time"))
            .addOutputFields(stringsFieldMeta("content"))
            .addOutputFields(signPicFieldMeta("recorded_by"));

        return List.of(
            patientInfo.build(),
            timeRange.build(),
            monitoringRecords.build(),
            nonHourlyMonitoringRecords.build(),
            nursingOrders.build()
        );
    }

    private List<JfkDataSourcePB> syntheticDataSources() {
        JfkDataSourcePB.Builder patientInfo = JfkDataSourcePB.newBuilder()
            .setId("patient_info")
            .setMetaId("patient_info")
            .addOutputData(strVal("mrn", "MRN001"))
            .addOutputData(strVal("bed_no", "1"))
            .addOutputData(strVal("patient_name", "张三"))
            .addOutputData(strVal("gender", "男"))
            .addOutputData(strVal("age", "60岁"));

        JfkDataSourcePB.Builder timeRange = JfkDataSourcePB.newBuilder()
            .setId("monitoring_time_range")
            .setMetaId("monitoring_time_range")
            .addOutputData(strVals("time_txt", "时间"))
            .addOutputData(strVals("unit_txt", "单位"));
        for (int i = 1; i <= 24; i++) {
            timeRange.addOutputData(strVals("hour" + i, Math.floorMod(6 + i - 1, 24) + ":00"));
        }

        JfkDataSourcePB.Builder monitoringRecords = JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_MONITORING_RECORDS, "table-28"))
            .setMetaId(JfkDataSourceIds.PATIENT_MONITORING_RECORDS)
            .addOutputData(strLinesVals("param_name", List.of("体温"), List.of("脉搏")))
            .addOutputData(strLinesVals("param_unit", List.of("℃"), List.of("次/分")));
        for (int i = 1; i <= 24; i++) {
            monitoringRecords.addOutputData(strLinesVals("hour" + i, List.of("36." + (i % 10)), List.of(String.valueOf(70 + i))));
        }

        JfkDataSourcePB nonHourlyMonitoringRecords = JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(
                JfkDataSourceIds.PATIENT_NON_HOURLY_MONITORING_RECORDS, "table-40-2"))
            .setMetaId(JfkDataSourceIds.PATIENT_NON_HOURLY_MONITORING_RECORDS)
            .addOutputData(strLinesVals("record_time", List.of("2026-04-17 08:10")))
            .addOutputData(strLinesVals("content", List.of("体温: 36.8 ℃; 心率: 88 次/分")))
            .addOutputData(strVals("recorded_by", SIGNATURE_PNG))
            .build();

        JfkDataSourcePB nursingOrders = JfkDataSourcePB.newBuilder()
            .setId(JfkDataSourceIds.compactTableScoped(JfkDataSourceIds.PATIENT_NURSING_ORDERS, "table-260-3"))
            .setMetaId(JfkDataSourceIds.PATIENT_NURSING_ORDERS)
            .addOutputData(strLinesVals("record_time", List.of("2026-04-17 08:20")))
            .addOutputData(strLinesVals("content", List.of("翻身护理")))
            .addOutputData(strVals("recorded_by", SIGNATURE_PNG))
            .build();

        return List.of(
            patientInfo.build(),
            timeRange.build(),
            monitoringRecords.build(),
            nonHourlyMonitoringRecords,
            nursingOrders
        );
    }

    private JfkFieldMetaPB stringFieldMeta(String id) {
        return JfkFieldMetaPB.newBuilder()
            .setId(id)
            .setValMeta(JfkValMetaPB.newBuilder().setValType(4).build())
            .build();
    }

    private JfkFieldMetaPB stringsFieldMeta(String id) {
        return JfkFieldMetaPB.newBuilder()
            .setId(id)
            .setValMeta(JfkValMetaPB.newBuilder().setValType(9).build())
            .build();
    }

    private JfkFieldMetaPB signPicFieldMeta(String id) {
        return JfkFieldMetaPB.newBuilder()
            .setId(id)
            .setValMeta(JfkValMetaPB.newBuilder().setValType(7).build())
            .build();
    }

    private JfkFieldDataPB strVal(String id, String value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .setVal(JfkValPB.newBuilder().setStrVal(value).build())
            .build();
    }

    private JfkFieldDataPB strVals(String id, String value) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addVals(JfkValPB.newBuilder().setStrVal(value).build())
            .build();
    }

    private JfkFieldDataPB strLinesVals(String id, List<String> first, List<String> second) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addVals(JfkValPB.newBuilder().addAllStrsVal(first).build())
            .addVals(JfkValPB.newBuilder().addAllStrsVal(second).build())
            .build();
    }

    private JfkFieldDataPB strLinesVals(String id, List<String> only) {
        return JfkFieldDataPB.newBuilder()
            .setId(id)
            .addVals(JfkValPB.newBuilder().addAllStrsVal(only).build())
            .build();
    }

    private static final String SIGNATURE_PNG =
        "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII=";
}
