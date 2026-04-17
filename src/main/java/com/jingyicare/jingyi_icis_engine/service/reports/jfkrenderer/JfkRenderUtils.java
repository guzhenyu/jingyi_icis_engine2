package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTablePB;

final class JfkRenderUtils {
    private JfkRenderUtils() {}

    static final int PAGE_SIZE_A3 = 1;
    static final int PAGE_SIZE_A4 = 2;

    static final int V_ALIGN_TOP = 1;
    static final int V_ALIGN_MIDDLE = 2;
    static final int V_ALIGN_BOTTOM = 3;

    static final int H_ALIGN_LEFT = 4;
    static final int H_ALIGN_CENTER = 5;
    static final int H_ALIGN_RIGHT = 6;

    static final int CONTENT_STATIC = 1;
    static final int CONTENT_USER_INPUT = 2;
    static final int CONTENT_JFK_INPUT = 3;
    static final int CONTENT_JFK_DATA_SOURCE = 4;
    static final int CONTENT_CUR_PAGE_ID = 5;
    static final int CONTENT_TOTAL_PAGES = 6;
    static final int CONTENT_CUR_SUB_PAGE_ID = 7;

    static final int VAL_TYPE_BOOL = 1;
    static final int VAL_TYPE_INT64 = 2;
    static final int VAL_TYPE_DOUBLE = 3;
    static final int VAL_TYPE_DATETIME = 5;
    static final int VAL_TYPE_STRINGS = 9;

    static final float TEXT_LINE_HEIGHT_PADDING = 1f;

    static PDRectangle pageRectangle(int pageSizeId, boolean portrait) throws JfkRenderException {
        float width;
        float height;
        if (pageSizeId == PAGE_SIZE_A3) {
            width = 841.89f;
            height = 1190.55f;
        } else if (pageSizeId == PAGE_SIZE_A4 || pageSizeId == 0) {
            width = 595.28f;
            height = 841.89f;
        } else {
            throw new JfkRenderException("Unsupported JFK page size id: " + pageSizeId);
        }
        return portrait ? new PDRectangle(width, height) : new PDRectangle(height, width);
    }

    static Color parseColor(String colorStr) {
        if (colorStr == null || colorStr.isBlank()) return Color.BLACK;
        try {
            String normalized = colorStr.startsWith("#") || colorStr.startsWith("0x") || colorStr.startsWith("0X")
                ? colorStr
                : "#" + colorStr;
            return Color.decode(normalized);
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }

    static float textWidth(PDFont font, float fontSize, String text, float charSpacing) throws IOException {
        if (text == null || text.isEmpty()) return 0f;
        float base = font.getStringWidth(text) / 1000f * fontSize;
        int len = text.length();
        return base + (len > 1 ? charSpacing * (len - 1) : 0f);
    }

    static float ascent(PDFont font, float fontSize) {
        float ascent = font.getFontDescriptor() == null ? 800f : font.getFontDescriptor().getAscent();
        return ascent / 1000f * fontSize;
    }

    static float descentAbs(PDFont font, float fontSize) {
        float descent = font.getFontDescriptor() == null ? -200f : font.getFontDescriptor().getDescent();
        return Math.abs(descent) / 1000f * fontSize;
    }

    static float textLineHeight(float fontSize) {
        return positive(fontSize, 12f) + TEXT_LINE_HEIGHT_PADDING;
    }

    static float textLogicalBlockHeight(float fontSize, int lineCount) {
        int safeLineCount = Math.max(1, lineCount);
        float safeFontSize = positive(fontSize, 12f);
        if (safeLineCount == 1) {
            return safeFontSize;
        }
        return safeFontSize + (safeLineCount - 1) * textLineHeight(safeFontSize);
    }

    static float elasticRowHeight(float baseCellHeight, float fontSize, int lineCount) {
        float safeFontSize = positive(fontSize, 12f);
        float safeBaseCellHeight = positive(baseCellHeight, safeFontSize);
        return Math.max(
            safeBaseCellHeight,
            safeBaseCellHeight - safeFontSize + textLogicalBlockHeight(safeFontSize, lineCount)
        );
    }

    static float positive(float value, float fallback) {
        return Float.isFinite(value) && value > 0f ? value : fallback;
    }

    static float finite(float value, float fallback) {
        return Float.isFinite(value) ? value : fallback;
    }

    static List<Float> requireCellWidths(JfkTablePB table) throws JfkRenderException {
        int cols = Math.max(1, table.getCols());
        if (table.getCellWidthsCount() != cols) {
            throw new JfkRenderException(
                "Invalid cell_widths length: table_id=" + table.getId()
                    + ", cols=" + cols
                    + ", cell_widths=" + table.getCellWidthsCount()
            );
        }
        return table.getCellWidthsList();
    }

    static List<Float> requireCellHeights(JfkTablePB table) throws JfkRenderException {
        int rows = Math.max(1, table.getRows());
        if (table.getCellHeightsCount() != rows) {
            throw new JfkRenderException(
                "Invalid cell_heights length: table_id=" + table.getId()
                    + ", rows=" + rows
                    + ", cell_heights=" + table.getCellHeightsCount()
            );
        }
        return table.getCellHeightsList();
    }

    static void requireColumnMetas(JfkTablePB table) throws JfkRenderException {
        int cols = Math.max(1, table.getCols());
        if (table.getColumnMetasCount() != cols) {
            throw new JfkRenderException(
                "Invalid column_metas length: table_id=" + table.getId()
                    + ", cols=" + cols
                    + ", column_metas=" + table.getColumnMetasCount()
            );
        }
    }

    static float lineWidth(JfkTablePB table) {
        return Math.max(0f, finite(table.getLineWidth(), 0f));
    }

    static float flowTableTop(
        float cursorTop,
        float offsetTop,
        float previousLineWidth,
        float currentLineWidth
    ) {
        float safeOffsetTop = finite(offsetTop, 0f);
        float safePreviousLineWidth = Math.max(0f, finite(previousLineWidth, 0f));
        float safeCurrentLineWidth = Math.max(0f, finite(currentLineWidth, 0f));
        if (Math.abs(safeOffsetTop) < 0.0001f && safePreviousLineWidth > 0f && safeCurrentLineWidth > 0f) {
            return cursorTop + (safePreviousLineWidth + safeCurrentLineWidth) / 2f;
        }
        return cursorTop - safeOffsetTop;
    }

    static float firstLineBaseline(
        float bottom,
        float height,
        float fontSize,
        float lineHeight,
        int lineCount,
        float ascent,
        float descent,
        int vAlignId
    ) {
        int safeLineCount = Math.max(1, lineCount);
        float leading = positive(lineHeight, textLineHeight(fontSize));
        float safeAscent = Math.max(0f, finite(ascent, leading));
        float safeDescent = Math.max(0f, finite(descent, 0f));
        float safeHeight = Math.max(leading, finite(height, leading));
        float lineBlockHeight = safeAscent + safeDescent + (safeLineCount - 1) * leading;
        float blockBottom;
        if (vAlignId == V_ALIGN_TOP) {
            blockBottom = bottom + safeHeight - lineBlockHeight;
        } else if (vAlignId == V_ALIGN_BOTTOM) {
            blockBottom = bottom;
        } else {
            blockBottom = bottom + (safeHeight - lineBlockHeight) / 2f;
        }
        return blockBottom + safeDescent + (safeLineCount - 1) * leading;
    }

    static float tableTopLineCenter(float bottom, float contentHeight, int rowCount, float lineWidth) {
        int safeRowCount = Math.max(1, rowCount);
        float safeLineWidth = Math.max(0f, finite(lineWidth, 0f));
        return bottom + safeLineWidth / 2f + Math.max(0f, finite(contentHeight, 0f)) + safeRowCount * safeLineWidth;
    }

    static float tableTopContentY(float bottom, float contentHeight, int rowCount, float lineWidth) {
        float safeLineWidth = Math.max(0f, finite(lineWidth, 0f));
        return tableTopLineCenter(bottom, contentHeight, rowCount, safeLineWidth) - safeLineWidth / 2f;
    }

    static float sum(List<Float> values) {
        float result = 0f;
        for (Float value : values) {
            if (value != null && Float.isFinite(value)) {
                result += value;
            }
        }
        return result;
    }

    static float tableHeight(List<Float> rowHeights, float lineWidth) {
        return sum(rowHeights) + lineWidth * (rowHeights.size() + 1);
    }
}
