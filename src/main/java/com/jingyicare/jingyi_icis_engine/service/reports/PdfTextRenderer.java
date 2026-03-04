package com.jingyicare.jingyi_icis_engine.service.reports;

import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.PDPageContentStream;

import java.awt.Color;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReport.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoringReportAh2.*;

/**
 * PDF 文本渲染工具：
 * 1) 生成折行后的行数据（按宽度自动换行）
 * 2) 在矩形内按【水平对齐策略】【整体垂直居中】绘制
 *
 * 约定：
 * - padding 一律为 0
 * - wordSpacing 固定为 0
 * - 按词换行（英文）、超宽词降级为按字符换行；中文可直接当作“单个大词”，会走字符拆分
 */
public class PdfTextRenderer {

    private PdfTextRenderer() {}

    /**
     * 在一个矩形区域内绘制多行文本：
     * - CENTER 对齐时按“每一行”分别水平居中
     * - 文本块基线按 ascent/descent 计算整体垂直居中
     *
     * @param cs        ContentStream
     * @param font      字体
     * @param fontSize  字号
     * @param textColor 颜色（null 则沿用当前色）
     * @param charSpacing 字符间距（pt）
     * @param leading   行距（基线间距），建议传 1.2*fontSize
     * @param left      矩形左
     * @param bottom    矩形下
     * @param width     矩形宽
     * @param height    矩形高
     * @param wrappedLines 折行后的行数据（每行宽度已不超过可用宽度）
     */
    public static void drawTxt(
            PDPageContentStream cs,
            PDFont font, float fontSize, Color textColor,
            float charSpacing, float leading, HorizontalAlign hAlign,
            float left, float bottom, float width, float height,
            List<String> wrappedLines
    ) throws IOException {
        if (wrappedLines == null || wrappedLines.isEmpty()) wrappedLines = Collections.singletonList("");
        if (leading <= 0f) leading = fontSize * 1.2f;

        int n = wrappedLines.size();

        // 字体指标
        final float A = JfkPdfUtils.getAscent(font, fontSize);      // >0
        final float D = JfkPdfUtils.getDescentAbs(font, fontSize);  // >0

        // 计算各行宽度（含 charSpacing）及最长行宽
        float[] lineWs = new float[n];
        float maxLineW = 0f;
        for (int i = 0; i < n; i++) {
            float lineW = JfkPdfUtils.textWidth(font, fontSize, wrappedLines.get(i), charSpacing);
            lineWs[i] = lineW;
            maxLineW = Math.max(maxLineW, lineW);
        }

        // 起始行 x：CENTER 时按首行单独居中，其他保持原有逻辑
        float x0 = hAlign == HorizontalAlign.LEFT ? left :
            hAlign == HorizontalAlign.RIGHT ? left + (width - maxLineW) :
            left  + (width - lineWs[0]) / 2f;

        // 垂直居中：首行基线 y
        float y1 = bottom + (height + (D - A) + (n - 1) * leading) / 2f;

        // 绘制
        if (textColor != null) {
            cs.setNonStrokingColor(textColor);
        }
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setCharacterSpacing(charSpacing);
        cs.newLineAtOffset(x0, y1);

        float currentX = x0;
        for (int i = 0; i < n; i++) {
            cs.showText(wrappedLines.get(i));
            if (i != n - 1) {
                float dx = 0f;
                if (hAlign == HorizontalAlign.CENTER) {
                    float nextX = left + (width - lineWs[i + 1]) / 2f;
                    dx = nextX - currentX;
                    currentX = nextX;
                }
                cs.newLineAtOffset(dx, -leading);
            }
        }
        cs.endText();
    }

    public static void drawTxt(
        PDPageContentStream cs, PDFont font, float fontSize, Color textColor,
        float charSpacing, float x, float y1, String line
    ) throws IOException {
        cs.setNonStrokingColor(textColor);
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setCharacterSpacing(charSpacing);
        cs.newLineAtOffset(x, y1);
        cs.showText(line == null ? "" : line);
        cs.endText();
    }
}
