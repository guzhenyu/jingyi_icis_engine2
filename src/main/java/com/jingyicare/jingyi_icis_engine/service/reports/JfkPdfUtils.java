package com.jingyicare.jingyi_icis_engine.service.reports;

import java.awt.Color;
import java.io.IOException;
import java.util.*;

import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

public class JfkPdfUtils {
    private JfkPdfUtils() {}

    /**
     * 在给定矩形内绘制 JFK 文本组件，仅单行文本，不做自动换行。
     */
    public static void drawJfkText(PDPageContentStream cs, PDFont font, JfkTextPB textPb) throws IOException {
        if (cs == null || font == null || textPb == null) return;

        float fontSize = textPb.getFontSize() > 0 ? textPb.getFontSize() : 12f;
        float charSpacing = sanitizeFloat(textPb.getCharSpacing());
        float boxWidth = textPb.getWidth();
        float boxHeight = textPb.getHeight();
        float left = textPb.getX();
        float bottom = textPb.getY();

        String line = textPb.getContent() == null ? "" : textPb.getContent();
        float ascent = getAscent(font, fontSize);
        float descent = getDescentAbs(font, fontSize);
        float lineWidth = textWidth(font, fontSize, line, charSpacing);

        float startX = left;
        if (boxWidth > 0f) {
            switch (textPb.getHAlignId()) {
                case 5: // center
                    startX = left + (boxWidth - lineWidth) / 2f;
                    break;
                case 6: // right
                    startX = left + boxWidth - lineWidth;
                    break;
                default: // left
                    startX = left;
            }
        }

        float rectHeight = boxHeight > 0f ? boxHeight : ascent + descent;
        float startY;
        switch (textPb.getVAlignId()) {
            case 1: // top
                startY = bottom + rectHeight - ascent;
                break;
            case 3: // bottom
                startY = bottom + descent;
                break;
            default: // middle
                startY = bottom + (rectHeight + (descent - ascent)) / 2f;
                break;
        }

        Color color = parseColor(textPb.getFontColor());
        if (color != null) {
            cs.setNonStrokingColor(color);
        }
        cs.beginText();
        cs.setFont(font, fontSize);
        cs.setCharacterSpacing(charSpacing);
        cs.newLineAtOffset(startX, startY);
        cs.showText(line);
        cs.endText();
    }

    private static Color parseColor(String colorStr) {
        if (colorStr == null || colorStr.isEmpty()) return Color.BLACK;
        try {
            String normalized = colorStr.startsWith("#") || colorStr.startsWith("0x") || colorStr.startsWith("0X")
                ? colorStr : "#" + colorStr;
            return Color.decode(normalized);
        } catch (NumberFormatException e) {
            return Color.BLACK;
        }
    }

    public static float textWidth(PDFont font, float fontSize, String s, float charSpacing) throws IOException {
        if (s == null || s.isEmpty()) return 0f;
        float base = font.getStringWidth(s) / 1000f * fontSize;
        int len = s.length();
        float extra = (len > 1) ? charSpacing * (len - 1) : 0f;
        return base + extra;
    }

    public static float getAscent(PDFont font, float fontSize) {
        float asc = (font.getFontDescriptor() != null ? font.getFontDescriptor().getAscent() : 800);
        return asc / 1000f * fontSize;
    }

    public static float getDescentAbs(PDFont font, float fontSize) {
        float desc = (font.getFontDescriptor() != null ? font.getFontDescriptor().getDescent() : -200);
        return Math.abs(desc) / 1000f * fontSize;
    }

    private static float sanitizeFloat(float value) {
        return (Float.isFinite(value) ? value : 0f);
    }

    // 字符级拆分（适配超宽词/中日韩）
    public static void wrapChars(
        List<String> out, String text,
        PDFont font, float fontSize, float maxWidth, float charSpacing
    ) throws IOException {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            String tryLine = line.toString() + ch;
            if (textWidth(font, fontSize, tryLine, charSpacing) <= maxWidth || line.length() == 0) {
                line.append(ch);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(ch);
            }
        }
        if (line.length() > 0) {
            out.add(line.toString());
        }
    }

    // ========== 内部实现细节 ==========
    // 先按词换，词超宽则字符级拆分
    public static void wrapInto(
        List<String> out, String text,
        PDFont font, float fontSize, float maxWidth, float charSpacing
    ) throws IOException {
        if (text == null || text.isEmpty()) {
            out.add("");
            return;
        }

        String[] tokens = new String[] { text };
        // 按空格保留分隔符（把空格也当作词的一部分，便于累计宽度）
        // String[] tokens = text.split("(?<= )"); // "Hello world " → ["Hello ", "world "]
        StringBuilder line = new StringBuilder();

        for (String tk : tokens) {
            if (tk == null || tk.isEmpty()) continue; // 跳过空字符串
            String tryLine = line.toString() + tk;
            if (textWidth(font, fontSize, tryLine, charSpacing) <= maxWidth) {
                line.append(tk);
            } else {
                if (line.length() > 0) {
                    // 只有非空行才输出，避免添加 ""
                    out.add(StrUtils.trimRightSpaces(line.toString()));
                    line.setLength(0);
                }

                // 单词本身超宽 → 降级字符级拆分
                if (textWidth(font, fontSize, tk, charSpacing) > maxWidth) {
                    wrapChars(out, tk, font, fontSize, maxWidth, charSpacing);
                } else {
                    line.append(tk);
                }
            }
        }
        if (line.length() > 0) {
            out.add(StrUtils.trimRightSpaces(line.toString()));
        }
    }

    /**
     * 将输入的多段文本按 maxWidth 自动换行为多行。
     * 逻辑：优先按词（空格）拼接；若单词本身超宽，降级到字符级拆分。
     *
     * @param font      字体
     * @param fontSize  字号
     * @param maxWidth  最大行宽（pt）
     * @param charSpacing 字符间距（pt）
     * @param lines     源文本段落列表（每个元素至少占一行）
     * @return          包含折行结果的行列表（每行都不超过 maxWidth）
     */
    public static List<String> getWrappedLines(
        PDFont font, float fontSize, float maxWidth, float charSpacing,
        List<String> lines
    ) throws IOException {
        if (lines == null || lines.isEmpty()) return List.of("");
        if (maxWidth <= 0f) return new ArrayList<>(lines); // 宽度无效就原样返回

        List<String> out = new ArrayList<>();
        for (String src : lines) {
            wrapInto(out, src == null ? "" : src, font, fontSize, maxWidth, charSpacing);
        }
        if (out.isEmpty()) out.add("");
        return out;
    }
}
