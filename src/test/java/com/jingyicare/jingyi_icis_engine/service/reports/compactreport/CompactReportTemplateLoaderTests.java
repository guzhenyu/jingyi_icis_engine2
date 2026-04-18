package com.jingyicare.jingyi_icis_engine.service.reports.compactreport;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCompact.CompactReportTemplatePB;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;

public class CompactReportTemplateLoaderTests {
    @Test
    public void loadParsesConfiguredCompactTemplate() throws Exception {
        ReportProperties properties = new ReportProperties();
        properties.getCompact().setTemplate("classpath:/config/pbtxt/report_compact_loader_test.pb.txt");
        CompactReportTemplateLoader loader =
            new CompactReportTemplateLoader(properties, new DefaultResourceLoader());

        CompactReportTemplatePB template = loader.load();

        assertThat(template.getTemplate().getName()).isEqualTo("Compact loader test template");
        assertThat(template.getTemplate().getPagesCount()).isEqualTo(1);
        assertThat(template.getTemplate().getPages(0).getContainersCount()).isEqualTo(1);
        assertThat(template.getTemplate().getPages(0).getContainers(0).getAcTablesCount()).isEqualTo(1);
        assertThat(template.getMonGroupCount()).isEqualTo(1);
        assertThat(template.getMonGroup(0).getTableId()).isEqualTo("loader-table");
        assertThat(template.getMedExeTablesCount()).isEqualTo(1);
        assertThat(template.getMedExeTables(0).getTableId()).isEqualTo("loader-table");
        assertThat(template.getMedExeTables(0).getIntakeTypeId()).isEqualTo(1);
    }
}
