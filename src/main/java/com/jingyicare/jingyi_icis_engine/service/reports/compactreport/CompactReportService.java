package com.jingyicare.jingyi_icis_engine.service.reports.compactreport;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisArchive.NursingReportCompactArchivePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourceMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCompact.CompactReportTemplatePB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.ReturnCode;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.archives.ArchiveService;
import com.jingyicare.jingyi_icis_engine.service.reports.JfkDataService;
import com.jingyicare.jingyi_icis_engine.service.reports.MonitoringReportRequest;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties;
import com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer.JfkPdfRenderer;
import com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer.JfkRenderException;
import com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer.JfkRenderResult;
import com.jingyicare.jingyi_icis_engine.utils.Consts;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.ReturnCodeUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

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
        @Autowired JfkPdfRenderer jfkPdfRenderer,
        @Autowired ArchiveService archiveService
    ) {
        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.zoneId = protoService.getConfig().getZoneId();
        this.dataSourceMetas = protoService.getConfig().getJfk().getDataSourcesList();
        this.reportProperties = reportProperties;
        this.resourceLoader = resourceLoader;
        this.templateLoader = templateLoader;
        this.dataSourceBuilder = dataSourceBuilder;
        this.jfkDataService = jfkDataService;
        this.jfkPdfRenderer = jfkPdfRenderer;
        this.archiveService = archiveService;
    }

    public ReturnCode generate(MonitoringReportRequest request) {
        try {
            CompactReportTemplatePB compactTemplate = templateLoader.load();
            DataSourceLoadResult dataSourceLoadResult = loadDataSources(compactTemplate, request);
            if (dataSourceLoadResult.returnCode().getCode() != StatusCode.OK.ordinal()) {
                return dataSourceLoadResult.returnCode();
            }
            byte[] fontData = loadFontData();
            Path reportPath = Path.of(request.getReportPath());
            JfkRenderResult renderResult = jfkPdfRenderer.render(
                compactTemplate.getTemplate(),
                compactTemplate.hasLogo() ? compactTemplate.getLogo() : null,
                dataSourceMetas,
                dataSourceLoadResult.dataSources(),
                fontData,
                reportPath
            );
            storeArchive(compactTemplate, request, dataSourceLoadResult.dataSources(), reportPath, renderResult.pageCount());
            return ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK);
        } catch (JfkRenderException e) {
            log.error("Failed to render compact monitoring report: {}", e.getMessage(), e);
            return ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_PARAM_VALUE, e.getMessage());
        } catch (IOException e) {
            log.error("Failed to generate compact monitoring report: {}", e.getMessage(), e);
            return ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INTERNAL_EXCEPTION, e.getMessage());
        }
    }

    private void storeArchive(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request,
        List<JfkDataSourcePB> dataSources,
        Path reportPath,
        int pageCount
    ) {
        try {
            if (request.getPid() == null || request.getPid() <= 0) {
                log.warn("Skip compact report archive because pid is invalid: {}", request.getPid());
                return;
            }
            LocalDateTime queryMidnightUtc = TimeUtils.getLocalMidnightUtc(request.getQueryStartUtc(), zoneId);
            if (queryMidnightUtc == null) {
                log.warn("Skip compact report archive because queryStartUtc is empty, pid={}", request.getPid());
                return;
            }

            String relativeUrl = archiveService.buildRelativeUrl(
                request.getPid(), Consts.PATIENT_ARCHIVE_TYPE_NURSING_REPORT_COMPACT, queryMidnightUtc, ARCHIVE_NAME);
            LocalDateTime generatedAtUtc = TimeUtils.getNowUtc();
            NursingReportCompactArchivePB archivePb = NursingReportCompactArchivePB.newBuilder()
                .addAllSources(dataSources)
                .setPid(request.getPid())
                .setType(Consts.PATIENT_ARCHIVE_TYPE_NURSING_REPORT_COMPACT)
                .setName(ARCHIVE_NAME)
                .setQueryStartIso8601(TimeUtils.toIso8601String(request.getQueryStartUtc(), "UTC"))
                .setQueryMidnightUtcIso8601(TimeUtils.toIso8601String(queryMidnightUtc, "UTC"))
                .setGeneratedAtIso8601(TimeUtils.toIso8601String(generatedAtUtc, "UTC"))
                .setTemplateId(compactTemplate.getTemplate().getId())
                .setTemplateName(compactTemplate.getTemplate().getName())
                .setRelativeUrl(relativeUrl)
                .build();
            String pbStr = ProtoUtils.encodeNursingReportCompactArchive(archivePb);
            boolean unchanged = archiveService.store(
                request.getPid(), Consts.PATIENT_ARCHIVE_TYPE_NURSING_REPORT_COMPACT, queryMidnightUtc,
                ARCHIVE_NAME, pbStr, pageCount, reportPath);
            log.info(
                "Stored compact report archive, pid={}, queryMidnightUtc={}, pageCount={}, unchanged={}",
                request.getPid(), queryMidnightUtc, pageCount, unchanged
            );
        } catch (Exception e) {
            log.error(
                "Failed to store compact report archive, pid={}, reportPath={}: {}",
                request.getPid(), reportPath, e.getMessage(), e
            );
        }
    }

    private DataSourceLoadResult loadDataSources(
        CompactReportTemplatePB compactTemplate,
        MonitoringReportRequest request
    ) {
        List<JfkDataSourcePB> inputList = dataSourceBuilder.buildInputs(compactTemplate, request);
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
    private final String zoneId;
    private final List<JfkDataSourceMetaPB> dataSourceMetas;
    private final ReportProperties reportProperties;
    private final ResourceLoader resourceLoader;
    private final CompactReportTemplateLoader templateLoader;
    private final CompactReportDataSourceBuilder dataSourceBuilder;
    private final JfkDataService jfkDataService;
    private final JfkPdfRenderer jfkPdfRenderer;
    private final ArchiveService archiveService;

    private static final String ARCHIVE_NAME = "nursing_report_compact";
}
