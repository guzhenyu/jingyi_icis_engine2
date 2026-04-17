package com.jingyicare.jingyi_icis_engine.service.reports.jfkrenderer;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

public class JfkRenderUtilsTests {
    @Test
    public void flowTableTopOverlapsAdjacentBordersWhenOffsetIsZero() {
        assertThat(JfkRenderUtils.flowTableTop(100f, 0f, 0.5f, 0.5f)).isEqualTo(100.5f);
    }

    @Test
    public void flowTableTopKeepsExplicitOffsetAsGap() {
        assertThat(JfkRenderUtils.flowTableTop(100f, 3f, 0.5f, 0.5f)).isEqualTo(97f);
    }

    @Test
    public void firstLineBaselineCentersFontVisualBoxForMiddleAlignment() {
        assertThat(JfkRenderUtils.firstLineBaseline(
            10f,
            12f,
            6f,
            7f,
            1,
            5f,
            1.5f,
            JfkRenderUtils.V_ALIGN_MIDDLE
        )).isEqualTo(14.25f);
    }

    @Test
    public void tableTopLineCenterIncludesInternalRowLines() {
        assertThat(JfkRenderUtils.tableTopLineCenter(402f, 84f, 7, 0.5f)).isEqualTo(489.75f);
    }

    @Test
    public void tableTopContentYIncludesInternalRowLines() {
        assertThat(JfkRenderUtils.tableTopContentY(402f, 84f, 7, 0.5f)).isEqualTo(489.5f);
    }

    @Test
    public void textLineHeightAddsConstantPadding() {
        assertThat(JfkRenderUtils.textLineHeight(6f)).isEqualTo(7f);
    }

    @Test
    public void elasticRowHeightPreservesSingleLinePaddingAndAddsMultiLineHeight() {
        assertThat(JfkRenderUtils.elasticRowHeight(12f, 6f, 1)).isEqualTo(12f);
        assertThat(JfkRenderUtils.elasticRowHeight(12f, 6f, 2)).isEqualTo(19f);
    }
}
