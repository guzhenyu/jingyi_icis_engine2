package com.jingyicare.jingyi_icis_engine.service.reports.compactreport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourceMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCompact.CompactReportTemplatePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataService;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportRequest;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer.JfkPdfRenderer;
import com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer.JfkRenderException;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;

@Service
@Slf4j
public class CompactReportService {
    public CompactReportService(
        @Autowired ConfigProtoService protoService,
        @Autowired ReportProperties reportProperties,
        @Autowired ResourceLoader resourceLoader,
        @Autowired CompactReportTemplateLoader templateLoader,
        @Autowired CompactReportDataSourceBuilder dataSourceBuilder,
        @Autowired JfkDataService jfkDataService,
        @Autowired JfkPdfRenderer jfkPdfRenderer
    ) {
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.dataSourceMetas = protoService.getConfig().getJfk().getDataSourcesList();
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
        this.templateLoader = templateLoader;
        this.dataSourceBuilder = dataSourceBuilder;
        this.jfkDataService = jfkDataService;
        this.jfkPdfRenderer = jfkPdfRenderer;
    }

    public ReturnCode generate(MonitoringReportRequest request) {
        try {
            CompactReportTemplatePB compactTemplate = templateLoader.load();
            DataSourceLoadResult dataSourceLoadResult = loadDataSources(compactTemplate, request);
            if (dataSourceLoadResult.returnCode().getCode() != StatusCode.OK.ordinal()) {
                return dataSourceLoadResult.returnCode();
            }
            byte[] fontData = loadFontData();
            jfkPdfRenderer.render(
                compactTemplate.getTemplate(),
                compactTemplate.hasLogo() ? compactTemplate.getLogo() : null,
                dataSourceMetas,
                dataSourceLoadResult.dataSources(),
                fontData,
                Path.of(request.getReportPath())
            );
            return ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK);
        } catch (JfkRenderException e) {
            log.error("Failed to render compact monitoring report: {}", e.getMessage(), e);
            return ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_PARAM_VALUE, e.getMessage());
        } catch (IOException e) {
            log.error("Failed to generate compact monitoring report: {}", e.getMessage(), e);
            return ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }
    }

    private DataSourceLoadResult loadDataSources(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        List<JfkDataSourcePB> inputList = dataSourceBuilder.buildInputs(compactTemplate.getTemplate(), request);
        List<JfkDataSourcePB> outputList = new ArrayList<>();
        for (JfkDataSourcePB input : inputList) {
            Pair<ReturnCode, JfkDataSourcePB> result = jfkDataService.getDataSource(input);
            if (result.getFirst().getCode() != StatusCode.OK.ordinal()) {
                log.error(
                    "Failed to load JFK data source: meta_id={}, code={}, msg={}",
                    input.getMetaId(), result.getFirst().getCode(), result.getFirst().getMsg()
                );
                return new DataSourceLoadResult(result.getFirst(), List.of());
            }
            if (result.getSecond() != null) {
                outputList.add(result.getSecond());
            }
        }
        return new DataSourceLoadResult(
            ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK), outputList);
    }

    private byte[] loadFontData() throws IOException {
        Resource fontResource = resourceLoader.getResource(reportProperties.getCompact().getFont());
        try (InputStream inputStream = fontResource.getInputStream()) {
            return inputStream.readAllBytes();
        }
    }

    private record DataSourceLoadResult(ReturnCode returnCode, List<JfkDataSourcePB> dataSources) {
    }

    private final List<String> statusCodeMsgs;
    private final List<JfkDataSourceMetaPB> dataSourceMetas;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
    private final CompactReportTemplateLoader templateLoader;
    private final CompactReportDataSourceBuilder dataSourceBuilder;
    private final JfkDataService jfkDataService;
    private final JfkPdfRenderer jfkPdfRenderer;
}
