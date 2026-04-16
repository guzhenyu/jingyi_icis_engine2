package com.jingyicare.jingyi_icis_engine.service.reports.compactreport;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.google.protobuf.TextFormat;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCompact.CompactReportTemplatePB;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;

@Component
public class CompactReportTemplateLoader {
    public CompactReportTemplateLoader(
        @Autowired ReportProperties reportProperties,
        @Autowired ResourceLoader resourceLoader
    ) {
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
    }

    public CompactReportTemplatePB load() throws IOException {
        Resource resource = resourceLoader.getResource(reportProperties.getCompact().getTemplate());
        CompactReportTemplatePB.Builder builder = CompactReportTemplatePB.newBuilder();
        try (InputStreamReader reader = new InputStreamReader(
            resource.getInputStream(), Charset.forName(reportProperties.getCharset()))) {
            TextFormat.merge(reader, builder);
        }
        return builder.build();
    }

    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
}
