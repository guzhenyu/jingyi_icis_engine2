package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import java.awt.Color;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTableColumnMetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTablePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkValPB;
import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkImageUtils;

public class JfkTableRenderer {
    public JfkTableRenderer(JfkTextRenderer textRenderer) {
        this.textRenderer = textRenderer;
    }

    public float fixedTableHeight(JfkTablePB table) throws JfkRenderException {
        List<Float> rowHeights = JfkRenderUtils.requireCellHeights(table);
        return JfkRenderUtils.tableHeight(rowHeights, JfkRenderUtils.lineWidth(table));
    }

    public boolean isElasticFlowTable(JfkTablePB table) {
        return !table.getDataSourceMetaId().isBlank()
            && table.getRows() == 1
            && table.getCellHeightsCount() == 1;
    }

    public List<RowData> buildFixedRows(JfkTablePB table, JfkValueResolver valueResolver) throws JfkRenderException {
        JfkRenderUtils.requireColumnMetas(table);
        List<Float> rowHeights = JfkRenderUtils.requireCellHeights(table);
        int rows = Math.max(1, table.getRows());
        List<RowData> result = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < rows; rowIndex++) {
            result.add(buildRow(table, valueResolver, rowIndex, rowHeights.get(rowIndex)));
        }
        return result;
    }

    public List<RowData> buildElasticRows(JfkTablePB table, JfkValueResolver valueResolver) throws JfkRenderException {
        JfkRenderUtils.requireColumnMetas(table);
        JfkRenderUtils.requireCellWidths(table);
        if (table.getCellHeightsCount() != 1) {
            throw new JfkRenderException("Elastic table requires exactly one base cell height: table_id=" + table.getId());
        }

        int expectedLength = -1;
        Map<String, Integer> lengths = new LinkedHashMap<>();
        for (JfkTableColumnMetaPB columnMeta : table.getColumnMetasList()) {
            int length = valueResolver.dataSourceFieldLength(table, columnMeta);
            if (length < 0) continue;
            String fieldId = valueResolver.dataSourceFieldId(columnMeta);
            lengths.put(fieldId, length);
            if (expectedLength < 0) {
                expectedLength = length;
            } else if (expectedLength != length) {
                throw new JfkRenderException(
                    "Data source column length mismatch: table_id=" + table.getId()
                        + ", lengths=" + formatLengths(lengths)
                );
            }
        }

        if (expectedLength < 0) expectedLength = Math.max(1, table.getRows());
        float baseCellHeight = table.getCellHeights(0);
        float fontSize = JfkRenderUtils.positive(table.getFontSize(), 12f);
        List<RowData> rows = new ArrayList<>();
        for (int rowIndex = 0; rowIndex < expectedLength; rowIndex++) {
            RowData row = buildRow(table, valueResolver, rowIndex, baseCellHeight);
            int maxLineCount = 1;
            for (CellData cell : row.cells()) {
                maxLineCount = Math.max(maxLineCount, cell.lines().size());
            }
            rows.add(new RowData(
                row.index(),
                JfkRenderUtils.elasticRowHeight(baseCellHeight, fontSize, maxLineCount),
                row.cells()
            ));
        }
        return rows;
    }

    public void drawRows(
        PDDocument document,
        PDPageContentStream contentStream,
        PDFont font,
        JfkTablePB table,
        float left,
        float bottom,
        List<RowData> rows
    ) throws IOException, JfkRenderException {
        List<Float> cellWidths = JfkRenderUtils.requireCellWidths(table);
        JfkRenderUtils.requireColumnMetas(table);
        float lineWidth = JfkRenderUtils.lineWidth(table);
        float contentWidth = JfkRenderUtils.sum(cellWidths);
        float contentHeight = 0f;
        for (RowData row : rows) contentHeight += row.height();

        drawGrid(contentStream, table, left, bottom, cellWidths, rows, contentWidth, contentHeight, lineWidth);
        drawCells(document, contentStream, font, table, left, bottom, cellWidths, rows, lineWidth, contentHeight);
    }

    private RowData buildRow(
        JfkTablePB table,
        JfkValueResolver valueResolver,
        int rowIndex,
        float rowHeight
    ) {
        List<CellData> cells = new ArrayList<>();
        for (int colIndex = 0; colIndex < table.getColumnMetasCount(); colIndex++) {
            JfkTableColumnMetaPB columnMeta = table.getColumnMetas(colIndex);
            JfkValueResolver.ResolvedCell resolvedCell = valueResolver.resolveCell(table, columnMeta, rowIndex);
            List<String> lines = resolvedCell.lines();
            cells.add(new CellData(
                colIndex,
                lines == null || lines.isEmpty() ? List.of("") : lines,
                resolvedCell.val(),
                resolvedCell.valType(),
                columnMeta.getHAlignId() > 0 ? columnMeta.getHAlignId() : table.getHAlignId(),
                columnMeta.getVAlignId() > 0 ? columnMeta.getVAlignId() : table.getVAlignId()
            ));
        }
        return new RowData(rowIndex, rowHeight, cells);
    }

    private void drawGrid(
        PDPageContentStream contentStream,
        JfkTablePB table,
        float left,
        float bottom,
        List<Float> cellWidths,
        List<RowData> rows,
        float contentWidth,
        float contentHeight,
        float lineWidth
    ) throws IOException {
        if (lineWidth <= 0f) return;
        Color lineColor = JfkRenderUtils.parseColor(table.getLineColor());
        contentStream.setStrokingColor(lineColor);
        contentStream.setLineWidth(lineWidth);

        float tableBottom = bottom + lineWidth / 2f;
        float tableTop = JfkRenderUtils.tableTopLineCenter(bottom, contentHeight, rows.size(), lineWidth);
        float x = left + lineWidth / 2f;
        contentStream.moveTo(x, tableBottom);
        contentStream.lineTo(x, tableTop);
        for (Float width : cellWidths) {
            x += width + lineWidth;
            contentStream.moveTo(x, tableBottom);
            contentStream.lineTo(x, tableTop);
        }

        float y = bottom + lineWidth / 2f;
        float right = left + lineWidth / 2f + contentWidth + lineWidth * cellWidths.size();
        contentStream.moveTo(left + lineWidth / 2f, y);
        contentStream.lineTo(right, y);
        for (int i = rows.size() - 1; i >= 0; i--) {
            y += rows.get(i).height() + lineWidth;
            contentStream.moveTo(left + lineWidth / 2f, y);
            contentStream.lineTo(right, y);
        }
        contentStream.stroke();
    }

    private void drawCells(
        PDDocument document,
        PDPageContentStream contentStream,
        PDFont font,
        JfkTablePB table,
        float left,
        float bottom,
        List<Float> cellWidths,
        List<RowData> rows,
        float lineWidth,
        float contentHeight
    ) throws IOException {
        float fontSize = JfkRenderUtils.positive(table.getFontSize(), 12f);
        float charSpacing = JfkRenderUtils.finite(table.getCharSpacing(), 0f);
        float hPadding = Math.max(0f, JfkRenderUtils.finite(table.getHPadding(), 0f));
        float rowTop = JfkRenderUtils.tableTopContentY(bottom, contentHeight, rows.size(), lineWidth);
        Map<String, PDImageXObject> imageCache = new LinkedHashMap<>();
        for (RowData row : rows) {
            float rowBottom = rowTop - row.height();
            float cellLeft = left + lineWidth;
            for (CellData cell : row.cells()) {
                float cellWidth = cellWidths.get(cell.colIndex());
                if (drawImageCell(
                    document, contentStream, imageCache, cell, cellLeft, rowBottom, cellWidth, row.height(), hPadding)) {
                    cellLeft += cellWidth + lineWidth;
                    continue;
                }
                float textLeft = cellLeft + hPadding;
                float textWidth = Math.max(0f, cellWidth - 2f * hPadding);
                textRenderer.drawLines(
                    contentStream,
                    font,
                    cell.lines(),
                    textLeft,
                    rowBottom,
                    textWidth,
                    row.height(),
                    fontSize,
                    charSpacing,
                    table.getFontColor(),
                    cell.hAlignId(),
                    cell.vAlignId()
                );
                cellLeft += cellWidth + lineWidth;
            }
            rowTop = rowBottom - lineWidth;
        }
    }

    private boolean drawImageCell(
        PDDocument document,
        PDPageContentStream contentStream,
        Map<String, PDImageXObject> imageCache,
        CellData cell,
        float cellLeft,
        float cellBottom,
        float cellWidth,
        float cellHeight,
        float hPadding
    ) throws IOException {
        if (!JfkRenderUtils.isImageValType(cell.valType()) || cell.val() == null || cell.val().getStrVal().isBlank()) {
            return false;
        }
        String imageValue = cell.val().getStrVal();
        byte[] bytes = JfkImageUtils.decodeSupportedImageBytes(imageValue).orElse(null);
        if (bytes == null) {
            return false;
        }
        PDImageXObject image = imageCache.get(imageValue);
        if (image == null) {
            try {
                image = PDImageXObject.createFromByteArray(document, bytes, "jfk-cell-image");
            } catch (IOException e) {
                return false;
            }
            imageCache.put(imageValue, image);
        }
        if (image.getWidth() <= 0 || image.getHeight() <= 0) {
            return false;
        }

        float maxWidth = Math.max(0f, cellWidth - 2f * hPadding);
        float maxHeight = Math.max(0f, cellHeight);
        if (maxWidth <= 0f || maxHeight <= 0f) {
            return false;
        }
        float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
        if (!Float.isFinite(scale) || scale <= 0f) {
            return false;
        }
        float drawWidth = image.getWidth() * scale;
        float drawHeight = image.getHeight() * scale;
        float areaLeft = cellLeft + hPadding;
        float drawX;
        if (cell.hAlignId() == JfkRenderUtils.H_ALIGN_LEFT) {
            drawX = areaLeft;
        } else if (cell.hAlignId() == JfkRenderUtils.H_ALIGN_RIGHT) {
            drawX = areaLeft + maxWidth - drawWidth;
        } else {
            drawX = areaLeft + (maxWidth - drawWidth) / 2f;
        }

        float drawY;
        if (cell.vAlignId() == JfkRenderUtils.V_ALIGN_TOP) {
            drawY = cellBottom + maxHeight - drawHeight;
        } else if (cell.vAlignId() == JfkRenderUtils.V_ALIGN_BOTTOM) {
            drawY = cellBottom;
        } else {
            drawY = cellBottom + (maxHeight - drawHeight) / 2f;
        }
        contentStream.drawImage(image, drawX, drawY, drawWidth, drawHeight);
        return true;
    }

    private String formatLengths(Map<String, Integer> lengths) {
        StringJoiner joiner = new StringJoiner(", ");
        for (Map.Entry<String, Integer> entry : lengths.entrySet()) {
            joiner.add(entry.getKey() + ":" + entry.getValue());
        }
        return joiner.toString();
    }

    public record CellData(int colIndex, List<String> lines, JfkValPB val, int valType, int hAlignId, int vAlignId) {
    }

    public record RowData(int index, float height, List<CellData> cells) {
    }

    private final JfkTextRenderer textRenderer;
}
