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
