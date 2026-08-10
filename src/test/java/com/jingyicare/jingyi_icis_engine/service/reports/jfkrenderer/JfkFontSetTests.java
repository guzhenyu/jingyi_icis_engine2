package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import com.jingyicare.jingyi_icis_engine.service.reports.common.JfkPdfUtils;
import com.jingyicare.jingyi_icis_engine.service.reports.common.PdfFontSet;

public class JfkFontSetTests {
    @Test
    public void splitsUnsupportedPrimaryGlyphIntoFallbackRun() throws Exception {
        try (PDDocument document = new PDDocument();
             InputStream fallbackData = font("/fonts/DejaVuSans.ttf")) {
            PDFont primary = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType0Font fallback = PDType0Font.load(document, fallbackData);
            PdfFontSet fonts = new PdfFontSet(primary, fallback);

            List<PdfFontSet.TextRun> runs = fonts.split("PaO₂");

            assertThat(runs).extracting(PdfFontSet.TextRun::text)
                .containsExactly("PaO", "₂");
            assertThat(runs.get(0).font()).isSameAs(primary);
            assertThat(runs.get(1).font()).isSameAs(fallback);
            assertThat(fonts.textWidth(12f, "PaO₂", 0f)).isPositive();
        }
    }

    @Test
    public void rendersMixedPrimaryAndFallbackText() throws Exception {
        try (PDDocument document = new PDDocument();
             InputStream fallbackData = font("/fonts/DejaVuSans.ttf")) {
            PdfFontSet fonts = new PdfFontSet(
                new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                PDType0Font.load(document, fallbackData)
            );
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                new JfkTextRenderer().drawLines(
                    contentStream,
                    fonts,
                    List.of("PaO₂"),
                    20f,
                    20f,
                    200f,
                    20f,
                    12f,
                    0f,
                    "#000000",
                    JfkRenderUtils.H_ALIGN_LEFT,
                    JfkRenderUtils.V_ALIGN_MIDDLE
                );
            }

            ByteArrayOutputStream output = new ByteArrayOutputStream();
            document.save(output);
            assertThat(output.size()).isGreaterThan(0);
        }
    }

    @Test
    public void wrapsTextUsingFallbackFontMetrics() throws Exception {
        try (PDDocument document = new PDDocument();
             InputStream fallbackData = font("/fonts/DejaVuSans.ttf")) {
            PdfFontSet fonts = new PdfFontSet(
                new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                PDType0Font.load(document, fallbackData)
            );

            List<String> lines = JfkPdfUtils.getWrappedLines(
                fonts, 12f, 200f, 0f, List.of("PaO₂"));

            assertThat(lines).containsExactly("PaO₂");
        }
    }

    @Test
    public void reportsCodePointWhenNeitherFontHasGlyph() throws Exception {
        try (PDDocument document = new PDDocument();
             InputStream fallbackData = font("/fonts/DejaVuSans.ttf")) {
            PdfFontSet fonts = new PdfFontSet(
                new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                PDType0Font.load(document, fallbackData)
            );

            assertThatThrownBy(() -> fonts.split("\uD83E\uDEBF"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("U+1FABF")
                .hasMessageContaining("Helvetica")
                .hasMessageContaining("DejaVuSans");
        }
    }

    private InputStream font(String path) {
        InputStream inputStream = JfkFontSetTests.class.getResourceAsStream(path);
        if (inputStream == null) throw new IllegalStateException("Missing test font: " + path);
        return inputStream;
    }
}
