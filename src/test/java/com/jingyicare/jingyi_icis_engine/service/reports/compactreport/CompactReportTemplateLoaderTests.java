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
        properties.getCompact().setTemplate("classpath:/config/pbtxt/report_compact.pb.txt");
        CompactReportTemplateLoader loader =
            new CompactReportTemplateLoader(properties, new DefaultResourceLoader());

        CompactReportTemplatePB template = loader.load();

        assertThat(template.getTemplate().getName()).isEqualTo("新安重症监护单");
        assertThat(template.getTemplate().getPagesCount()).isEqualTo(1);
        assertThat(template.getTemplate().getPages(0).getContainersCount()).isEqualTo(1);
        assertThat(template.getTemplate().getPages(0).getContainers(0).getAcTablesCount()).isEqualTo(2);
    }
}
