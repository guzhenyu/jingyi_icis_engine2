package com.jingyicare.jingyi_icis_engine.service.reports.common;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

public final class PdfFontSet {
    public static final String FALLBACK_FONT_PATH = "fonts/DejaVuSans.ttf";

    public PdfFontSet(PDFont primaryFont, PDFont fallbackFont) {
        this.primaryFont = Objects.requireNonNull(primaryFont, "primaryFont");
        this.fallbackFont = Objects.requireNonNull(fallbackFont, "fallbackFont");
    }

    public static PdfFontSet load(PDDocument document, Resource primaryResource) throws IOException {
        try (InputStream primaryData = primaryResource.getInputStream();
             InputStream fallbackData = new ClassPathResource(FALLBACK_FONT_PATH).getInputStream()) {
            return new PdfFontSet(
                PDType0Font.load(document, primaryData),
                PDType0Font.load(document, fallbackData)
            );
        }
    }

    public static PdfFontSet load(PDDocument document, byte[] primaryData, byte[] fallbackData) throws IOException {
        return new PdfFontSet(
            PDType0Font.load(document, new ByteArrayInputStream(primaryData)),
            PDType0Font.load(document, new ByteArrayInputStream(fallbackData))
        );
    }

    public List<TextRun> split(String text) throws IOException {
        String safeText = sanitizeText(text);
        if (safeText.isEmpty()) return List.of(new TextRun(primaryFont, ""));

        List<TextRun> runs = new ArrayList<>();
        StringBuilder buffer = new StringBuilder();
        PDFont currentFont = null;
        for (int offset = 0; offset < safeText.length();) {
            int codePoint = safeText.codePointAt(offset);
            PDFont selectedFont = selectFont(codePoint);
            if (currentFont != null && currentFont != selectedFont) {
                runs.add(new TextRun(currentFont, buffer.toString()));
                buffer.setLength(0);
            }
            currentFont = selectedFont;
            buffer.appendCodePoint(codePoint);
            offset += Character.charCount(codePoint);
        }
        runs.add(new TextRun(currentFont, buffer.toString()));
        return runs;
    }

    public float textWidth(float fontSize, String text, float charSpacing) throws IOException {
        String safeText = sanitizeText(text);
        if (safeText.isEmpty()) return 0f;

        float width = 0f;
        for (TextRun run : split(safeText)) {
            width += run.font().getStringWidth(run.text()) / 1000f * fontSize;
        }
        int glyphCount = safeText.codePointCount(0, safeText.length());
        return width + (glyphCount > 1 ? charSpacing * (glyphCount - 1) : 0f);
    }

    public PDFont primaryFont() {
        return primaryFont;
    }

    private PDFont selectFont(int codePoint) throws IOException {
        if (canEncode(primaryFont, codePoint)) return primaryFont;
        if (canEncode(fallbackFont, codePoint)) return fallbackFont;
        throw new IllegalArgumentException(
            "No glyph for U+%04X in fonts %s or %s".formatted(
                codePoint,
                primaryFont.getName(),
                fallbackFont.getName()
            )
        );
    }

    private boolean canEncode(PDFont font, int codePoint) throws IOException {
        try {
            font.encode(new String(Character.toChars(codePoint)));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private String sanitizeText(String text) {
        if (text == null || text.isEmpty()) return "";
        return text.replace("\t", "    ");
    }

    public record TextRun(PDFont font, String text) {
    }

    private final PDFont primaryFont;
    private final PDFont fallbackFont;
}
