package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkAbsContainerPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkAcTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkAcTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourceMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkDataSourcePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkLinePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkPagePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTemplatePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportCommon.LogoMetaPB;

@Component
public class JfkPdfRenderer {
    public JfkPdfRenderer(@Autowired ResourceLoader resourceLoader) {
        this.resourceLoader = resourceLoader;
        this.textRenderer = new JfkTextRenderer();
        this.tableRenderer = new JfkTableRenderer(textRenderer);
    }

    public JfkRenderResult render(
        JfkTemplatePB template,
        LogoMetaPB logo,
        List<JfkDataSourceMetaPB> dataSourceMetas,
        List<JfkDataSourcePB> dataSources,
        byte[] fontData,
        Path outputPath
    ) throws IOException, JfkRenderException {
        int pageCount;
        try (PDDocument countDocument = new PDDocument()) {
            RenderContext countContext = new RenderContext(
                countDocument, template, logo, dataSourceMetas, dataSources, fontData, 0);
            renderTemplate(countContext);
            pageCount = countDocument.getNumberOfPages();
        }

        try (PDDocument document = new PDDocument()) {
            RenderContext renderContext = new RenderContext(
                document, template, logo, dataSourceMetas, dataSources, fontData, pageCount);
            renderTemplate(renderContext);
            if (outputPath.getParent() != null) {
                Files.createDirectories(outputPath.getParent());
            }
            document.save(outputPath.toFile());
        }
        return new JfkRenderResult(pageCount);
    }

    private void renderTemplate(RenderContext context) throws IOException, JfkRenderException {
        if (context.template.getPagesCount() == 0) {
            throw new JfkRenderException("JFK template has no pages");
        }
        for (int pageIndex = 0; pageIndex < context.template.getPagesCount(); pageIndex++) {
            JfkPagePB page = context.template.getPages(pageIndex);
            TemplatePageState state = new TemplatePageState(pageIndex, String.valueOf(pageIndex + 1));
            startTemplatePage(context, page, state);
            renderContainers(context, page, state);
        }
        context.closeContentStream();
    }

    private void startTemplatePage(
        RenderContext context,
        JfkPagePB page,
        TemplatePageState state
    ) throws IOException, JfkRenderException {
        context.startPage();
        renderLogo(context);
        renderAbsoluteElements(context, page, state);
    }

    private void renderAbsoluteElements(
        RenderContext context,
        JfkPagePB page,
        TemplatePageState state
    ) throws IOException, JfkRenderException {
        List<AbsoluteElement> elements = new ArrayList<>();
        for (int i = 0; i < page.getTablesCount(); i++) {
            JfkTablePB table = page.getTables(i);
            if (!context.valueResolver.shouldRenderTable(table)) continue;
            if (state.extensionIndex > 1 && !table.getExtendToNextPage()) continue;
            elements.add(new AbsoluteElement(table.getZIndex(), () -> renderAbsoluteTable(context, table, state)));
        }
        for (int i = 0; i < page.getTextsCount(); i++) {
            JfkTextPB text = page.getTexts(i);
            if (state.extensionIndex > 1 && !text.getExtendToNextPage()) continue;
            elements.add(new AbsoluteElement(text.getZIndex(), () -> renderAbsoluteText(context, text, state)));
        }
        for (int i = 0; i < page.getLinesCount(); i++) {
            JfkLinePB line = page.getLines(i);
            if (state.extensionIndex > 1 && !line.getExtendToNextPage()) continue;
            elements.add(new AbsoluteElement(line.getZIndex(), () -> renderAbsoluteLine(context, line, state)));
        }
        elements.sort(Comparator.comparingInt(AbsoluteElement::zIndex));
        for (AbsoluteElement element : elements) {
            element.renderer().render();
        }
    }

    private void renderAbsoluteText(
        RenderContext context,
        JfkTextPB text,
        TemplatePageState state
    ) throws IOException {
        float x = state.extensionIndex > 1 ? text.getNextPageX() : text.getX();
        float y = state.extensionIndex > 1 ? text.getNextPageY() : text.getY();
        String content = context.valueResolver.resolveText(
            text,
            context.pageNo,
            context.totalPages,
            state.subPageId
        );
        textRenderer.drawText(context.contentStream, context.font, text, content, x, y);
    }

    private void renderAbsoluteTable(
        RenderContext context,
        JfkTablePB table,
        TemplatePageState state
    ) throws IOException, JfkRenderException {
        rejectUnsupportedHReplicas(table);
        float x = state.extensionIndex > 1 ? table.getNextPageX() : table.getX();
        float y = state.extensionIndex > 1 ? table.getNextPageY() : table.getY();
        List<JfkTableRenderer.RowData> rows = tableRenderer.buildFixedRows(table, context.valueResolver);
        tableRenderer.drawRows(context.contentStream, context.font, table, x, y, rows);
    }

    private void renderAbsoluteLine(
        RenderContext context,
        JfkLinePB line,
        TemplatePageState state
    ) throws IOException {
        float x1 = state.extensionIndex > 1 ? line.getNextPageX1() : line.getX1();
        float y1 = state.extensionIndex > 1 ? line.getNextPageY1() : line.getY1();
        float x2 = state.extensionIndex > 1 ? line.getNextPageX2() : line.getX2();
        float y2 = state.extensionIndex > 1 ? line.getNextPageY2() : line.getY2();
        context.contentStream.setStrokingColor(JfkRenderUtils.parseColor(line.getLineColor()));
        context.contentStream.setLineWidth(Math.max(0f, line.getLineWidth()));
        context.contentStream.moveTo(x1, y1);
        context.contentStream.lineTo(x2, y2);
        context.contentStream.stroke();
    }

    private void renderContainers(
        RenderContext context,
        JfkPagePB page,
        TemplatePageState state
    ) throws IOException, JfkRenderException {
        for (JfkAbsContainerPB container : page.getContainersList()) {
            boolean hasTables = container.getAcTablesCount() > 0;
            boolean hasTexts = container.getAcTextsCount() > 0;
            if (hasTables && hasTexts) {
                throw new JfkRenderException("Container cannot mix ac_tables and ac_texts: container_id=" + container.getId());
            }
            if (hasTables) {
                renderTableContainer(context, page, state, container);
            } else if (hasTexts) {
                renderTextContainer(context, container, state);
            }
        }
    }

    private void renderTextContainer(
        RenderContext context,
        JfkAbsContainerPB container,
        TemplatePageState state
    ) throws IOException {
        float containerTop = containerTop(container, state);
        for (JfkAcTextPB acText : container.getAcTextsList()) {
            JfkTextPB text = acText.getText();
            float top = containerTop + acText.getOffsetTop();
            float bottom = top - text.getHeight();
            String content = context.valueResolver.resolveText(text, context.pageNo, context.totalPages, state.subPageId);
            textRenderer.drawText(context.contentStream, context.font, text, content, text.getX(), bottom);
        }
    }

    private void renderTableContainer(
        RenderContext context,
        JfkPagePB page,
        TemplatePageState state,
        JfkAbsContainerPB container
    ) throws IOException, JfkRenderException {
        float cursorTop = containerTop(container, state);
        float bottomLimit = containerBottom(container, state);
        float previousLineWidth = 0f;
        for (JfkAcTablePB acTable : container.getAcTablesList()) {
            JfkTablePB table = acTable.getTbl();
            if (!context.valueResolver.shouldRenderTable(table)) {
                continue;
            }
            rejectUnsupportedHReplicas(table);
            FlowTableResult result;
            if (tableRenderer.isElasticFlowTable(table)) {
                result = renderElasticFlowTable(
                    context, page, state, container, acTable, cursorTop, bottomLimit, previousLineWidth);
            } else {
                result = renderFixedFlowTable(
                    context, page, state, container, acTable, cursorTop, bottomLimit, previousLineWidth);
            }
            cursorTop = result.cursorTop();
            previousLineWidth = result.bottomLineWidth();
            bottomLimit = containerBottom(container, state);
        }
    }

    private FlowTableResult renderFixedFlowTable(
        RenderContext context,
        JfkPagePB page,
        TemplatePageState state,
        JfkAbsContainerPB container,
        JfkAcTablePB acTable,
        float cursorTop,
        float bottomLimit,
        float previousLineWidth
    ) throws IOException, JfkRenderException {
        JfkTablePB table = acTable.getTbl();
        float lineWidth = JfkRenderUtils.lineWidth(table);
        float top = JfkRenderUtils.flowTableTop(cursorTop, acTable.getOffsetTop(), previousLineWidth, lineWidth);
        List<JfkTableRenderer.RowData> rows = tableRenderer.buildFixedRows(table, context.valueResolver);
        float tableHeight = segmentHeight(rows, lineWidth);
        if (top - tableHeight < bottomLimit) {
            startFlowContinuationPage(context, page, state);
            top = containerTop(container, state) - acTable.getOffsetTop();
            bottomLimit = containerBottom(container, state);
        }
        if (top - tableHeight < bottomLimit) {
            throw new JfkRenderException(
                "Flow table too high for one page: table_id=" + table.getId()
                    + ", table_height=" + tableHeight
                    + ", page_capacity=" + (containerTop(container, state) - bottomLimit)
            );
        }
        float bottom = top - tableHeight;
        tableRenderer.drawRows(context.contentStream, context.font, table, table.getX(), bottom, rows);
        return new FlowTableResult(bottom, lineWidth);
    }

    private FlowTableResult renderElasticFlowTable(
        RenderContext context,
        JfkPagePB page,
        TemplatePageState state,
        JfkAbsContainerPB container,
        JfkAcTablePB acTable,
        float cursorTop,
        float bottomLimit,
        float previousLineWidth
    ) throws IOException, JfkRenderException {
        JfkTablePB table = acTable.getTbl();
        List<JfkTableRenderer.RowData> rows = tableRenderer.buildElasticRows(table, context.valueResolver);
        float lineWidth = JfkRenderUtils.lineWidth(table);
        float top = JfkRenderUtils.flowTableTop(cursorTop, acTable.getOffsetTop(), previousLineWidth, lineWidth);
        int rowIndex = 0;
        while (rowIndex < rows.size()) {
            float available = top - bottomLimit;
            int start = rowIndex;
            int end = rowIndex;
            while (end < rows.size()) {
                float candidateHeight = segmentHeight(rows.subList(start, end + 1), lineWidth);
                if (candidateHeight <= available) {
                    end++;
                    continue;
                }
                break;
            }

            if (end == start) {
                float singleRowHeight = segmentHeight(rows.subList(start, start + 1), lineWidth);
                float fullPageCapacity = containerTop(container, state) - containerBottom(container, state);
                if (singleRowHeight > fullPageCapacity) {
                    throw new JfkRenderException(
                        "Elastic table row too high: table_id=" + table.getId()
                            + ", row=" + rows.get(start).index()
                            + ", row_height=" + singleRowHeight
                            + ", page_capacity=" + fullPageCapacity
                    );
                }
                startFlowContinuationPage(context, page, state);
                top = containerTop(container, state) - acTable.getOffsetTop();
                bottomLimit = containerBottom(container, state);
                continue;
            }

            List<JfkTableRenderer.RowData> segment = rows.subList(start, end);
            float segmentHeight = segmentHeight(segment, lineWidth);
            float bottom = top - segmentHeight;
            tableRenderer.drawRows(context.contentStream, context.font, table, table.getX(), bottom, segment);
            rowIndex = end;
            top = bottom;

            if (rowIndex < rows.size()) {
                startFlowContinuationPage(context, page, state);
                top = containerTop(container, state);
                bottomLimit = containerBottom(container, state);
            }
        }
        return new FlowTableResult(top, lineWidth);
    }

    private void startFlowContinuationPage(
        RenderContext context,
        JfkPagePB page,
        TemplatePageState state
    ) throws IOException, JfkRenderException {
        state.extensionIndex++;
        startTemplatePage(context, page, state);
    }

    private float segmentHeight(List<JfkTableRenderer.RowData> rows, float lineWidth) {
        float height = 0f;
        for (JfkTableRenderer.RowData row : rows) {
            height += row.height();
        }
        return height + lineWidth * (rows.size() + 1);
    }

    private float containerTop(JfkAbsContainerPB container, TemplatePageState state) {
        return state.extensionIndex == 1 ? container.getTop() : container.getNextPageTop();
    }

    private float containerBottom(JfkAbsContainerPB container, TemplatePageState state) {
        float top = containerTop(container, state);
        float height = state.extensionIndex == 1 ? container.getHeight() : container.getNextPageHeight();
        return top - height;
    }

    private void renderLogo(RenderContext context) throws IOException, JfkRenderException {
        if (context.logo == null || context.logo.getPngPath().isBlank()) return;
        byte[] logoBytes = loadResourceBytes(context.logo.getPngPath());
        PDImageXObject image = PDImageXObject.createFromByteArray(context.document, logoBytes, "compact-logo");
        float bottom = context.logo.getTop() - context.logo.getHeight();
        context.contentStream.drawImage(image, context.logo.getLeft(), bottom, context.logo.getWidth(), context.logo.getHeight());
    }

    private byte[] loadResourceBytes(String path) throws IOException, JfkRenderException {
        Resource resource = resourceLoader.getResource(path);
        if (resource.exists()) {
            try (var inputStream = resource.getInputStream()) {
                return inputStream.readAllBytes();
            }
        }
        Path filePath = Path.of(path);
        if (Files.exists(filePath)) {
            return Files.readAllBytes(filePath);
        }
        throw new JfkRenderException("Logo resource not found: " + path);
    }

    private void rejectUnsupportedHReplicas(JfkTablePB table) throws JfkRenderException {
        if (table.getHReplicas() > 0) {
            throw new JfkRenderException(
                "h_replicas is not supported in compact renderer yet: table_id=" + table.getId()
                    + ", h_replicas=" + table.getHReplicas()
            );
        }
    }

    private class RenderContext {
        RenderContext(
            PDDocument document,
            JfkTemplatePB template,
            LogoMetaPB logo,
            List<JfkDataSourceMetaPB> dataSourceMetas,
            List<JfkDataSourcePB> dataSources,
            byte[] fontData,
            int totalPages
        ) throws IOException, JfkRenderException {
            if (fontData == null || fontData.length == 0) {
                throw new JfkRenderException("JFK renderer font data is empty");
            }
            this.document = document;
            this.template = template;
            this.logo = logo;
            this.pageRectangle = JfkRenderUtils.pageRectangle(
                template.getPageSizeId(), template.getIsPageOrientationPortrait());
            this.font = PDType0Font.load(document, new ByteArrayInputStream(fontData));
            this.valueResolver = new JfkValueResolver(new JfkRenderData(dataSourceMetas, dataSources));
            this.totalPages = totalPages;
        }

        void startPage() throws IOException {
            closeContentStream();
            PDPage page = new PDPage(pageRectangle);
            document.addPage(page);
            contentStream = new PDPageContentStream(document, page);
            pageNo++;
        }

        void closeContentStream() throws IOException {
            if (contentStream != null) {
                contentStream.close();
                contentStream = null;
            }
        }

        final PDDocument document;
        final JfkTemplatePB template;
        final LogoMetaPB logo;
        final PDRectangle pageRectangle;
        final PDType0Font font;
        final JfkValueResolver valueResolver;
        final int totalPages;

        PDPageContentStream contentStream;
        int pageNo = 0;
    }

    private static class TemplatePageState {
        TemplatePageState(int templatePageIndex, String subPageId) {
            this.templatePageIndex = templatePageIndex;
            this.subPageId = subPageId;
        }

        final int templatePageIndex;
        final String subPageId;
        int extensionIndex = 1;
    }

    private record AbsoluteElement(int zIndex, ElementRenderer renderer) {
    }

    private record FlowTableResult(float cursorTop, float bottomLineWidth) {
    }

    @FunctionalInterface
    private interface ElementRenderer {
        void render() throws IOException, JfkRenderException;
    }

    private final ResourceLoader resourceLoader;
    private final JfkTextRenderer textRenderer;
    private final JfkTableRenderer tableRenderer;
}
