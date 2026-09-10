package com.jingyicare.jingyi_icis_engine.service.reports.ah2report;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

import com.google.protobuf.TextFormat;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.JfkTextPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportAh2.ReportTemplateAh2PB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisReportAh2.SubPagePB;
import com.jingyicare.jingyi_icis_engine.service.reports.ReportProperties.Ah2;

public class Ah2ReportTemplateTests {
    @Test
    public void selectsDepartmentTemplateAndFallsBackToDefault() {
        ReportTemplateAh2PB defaultTemplate = ReportTemplateAh2PB.newBuilder()
            .setName("default")
            .build();
        ReportTemplateAh2PB departmentTemplate = ReportTemplateAh2PB.newBuilder()
            .setName("department")
            .build();
        Map<String, ReportTemplateAh2PB> departmentTemplates =
            Map.of("1090415", departmentTemplate);

        assertThat(Ah2ReportService.selectTemplate(
            "1090415", defaultTemplate, departmentTemplates
        )).isSameAs(departmentTemplate);
        assertThat(Ah2ReportService.selectTemplate(
            "1040110", defaultTemplate, departmentTemplates
        )).isSameAs(defaultTemplate);
        assertThat(Ah2ReportService.selectTemplate(
            null, defaultTemplate, departmentTemplates
        )).isSameAs(defaultTemplate);
    }

    @Test
    public void sjtuTemplateKeepsAh2TablesAndAddsCenteredTitleToEverySubpage() throws Exception {
        ReportTemplateAh2PB defaultTemplate = load(Ah2.TEMPLATE_AH2);
        ReportTemplateAh2PB sjtuTemplate = load(Ah2.TEMPLATE_AH2_SJTU);

        assertThat(sjtuTemplate.getPage().getTblCommon())
            .isEqualTo(defaultTemplate.getPage().getTblCommon());
        assertThat(sjtuTemplate.getPage().getSubPageCount())
            .isEqualTo(defaultTemplate.getPage().getSubPageCount());

        for (int i = 0; i < sjtuTemplate.getPage().getSubPageCount(); i++) {
            SubPagePB defaultSubPage = defaultTemplate.getPage().getSubPage(i);
            SubPagePB sjtuSubPage = sjtuTemplate.getPage().getSubPage(i);
            assertThat(sjtuSubPage.getTable()).isEqualTo(defaultSubPage.getTable());
            assertThat(sjtuSubPage.getNotes()).isEqualTo(defaultSubPage.getNotes());

            JfkTextPB originalTitle = findText(sjtuSubPage, "title");
            JfkTextPB sjtuTitle = findText(sjtuSubPage, "title_sjtu");
            assertThat(originalTitle.getContent())
                .isEqualTo("安徽省第二人民医院消化病医院 ICU护理记录单");
            assertThat(sjtuTitle.getContent())
                .isEqualTo("上海交通大学医学院附属仁济医院安徽医院");
            assertThat(sjtuTitle.getHAlignId()).isEqualTo(originalTitle.getHAlignId());
            assertThat(sjtuTitle.getX() + sjtuTitle.getWidth() / 2f)
                .isEqualTo(originalTitle.getX() + originalTitle.getWidth() / 2f);
            assertThat(sjtuTitle.getY()).isGreaterThan(originalTitle.getY());
        }
    }

    private ReportTemplateAh2PB load(String path) throws Exception {
        ReportTemplateAh2PB.Builder builder = ReportTemplateAh2PB.newBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new DefaultResourceLoader().getResource(path).getInputStream(), UTF_8))) {
            TextFormat.getParser().merge(reader, builder);
        }
        return builder.build();
    }

    private JfkTextPB findText(SubPagePB subPage, String id) {
        return subPage.getTextElemList().stream()
            .filter(text -> id.equals(text.getId()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("Missing text element: " + id));
    }
}
