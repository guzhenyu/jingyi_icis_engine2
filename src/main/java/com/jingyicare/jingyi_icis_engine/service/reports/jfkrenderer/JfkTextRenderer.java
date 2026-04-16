package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import java.awt.Color;
import java.io.IOException;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTextPB;

public class JfkTextRenderer {
    public void drawText(
        PDPageContentStream contentStream,
        PDFont font,
        JfkTextPB text,
        String content,
        float left,
        float bottom
    ) throws IOException {
        drawLines(
            contentStream,
            font,
            splitContent(content),
            left,
            bottom,
            JfkRenderUtils.positive(text.getWidth(), 0f),
            JfkRenderUtils.positive(text.getHeight(), JfkRenderUtils.positive(text.getFontSize(), 12f)),
            JfkRenderUtils.positive(text.getFontSize(), 12f),
            JfkRenderUtils.finite(text.getCharSpacing(), 0f),
            text.getFontColor(),
            text.getHAlignId(),
            text.getVAlignId()
        );
    }

    public void drawLines(
        PDPageContentStream contentStream,
        PDFont font,
        List<String> lines,
        float left,
        float bottom,
        float width,
        float height,
        float fontSize,
        float charSpacing,
        String fontColor,
        int hAlignId,
        int vAlignId
    ) throws IOException {
        if (lines == null || lines.isEmpty()) lines = List.of("");
        float safeWidth = Math.max(0f, width);
        float safeHeight = Math.max(fontSize, height);
        float leading = fontSize;
        float ascent = JfkRenderUtils.ascent(font, fontSize);
        float descent = JfkRenderUtils.descentAbs(font, fontSize);
        int lineCount = lines.size();

        float firstBaseline;
        if (vAlignId == JfkRenderUtils.V_ALIGN_TOP) {
            firstBaseline = bottom + safeHeight - ascent;
        } else if (vAlignId == JfkRenderUtils.V_ALIGN_BOTTOM) {
            firstBaseline = bottom + descent + (lineCount - 1) * leading;
        } else {
            firstBaseline = bottom + (safeHeight + (descent - ascent) + (lineCount - 1) * leading) / 2f;
        }

        Color color = JfkRenderUtils.parseColor(fontColor);
        contentStream.setNonStrokingColor(color);
        for (int i = 0; i < lineCount; i++) {
            String line = lines.get(i) == null ? "" : lines.get(i);
            float lineWidth = JfkRenderUtils.textWidth(font, fontSize, line, charSpacing);
            float x = left;
            if (hAlignId == JfkRenderUtils.H_ALIGN_CENTER) {
                x = left + (safeWidth - lineWidth) / 2f;
            } else if (hAlignId == JfkRenderUtils.H_ALIGN_RIGHT) {
                x = left + safeWidth - lineWidth;
            }
            float y = firstBaseline - i * leading;
            contentStream.beginText();
            contentStream.setFont(font, fontSize);
            contentStream.setCharacterSpacing(charSpacing);
            contentStream.newLineAtOffset(x, y);
            contentStream.showText(line);
            contentStream.endText();
        }
    }

    private List<String> splitContent(String content) {
        if (content == null || content.isEmpty()) return List.of("");
        return content.lines().toList();
    }
}
