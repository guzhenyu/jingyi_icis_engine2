package com.jingyicare.jingyi_icis_engine.service.reports;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Component
@ConfigurationProperties(prefix = "jingyi.report")
@Data
public class ReportProperties {
    private String rootPath = "../reports/";
    private String charset = "UTF-8";
    private String font = "classpath:/fonts/msyh.ttf";
    private Monitoring monitoring = new Monitoring();
    private Ah2 ah2 = new Ah2();
    private Compact compact = new Compact();

    public String getRootPath() {
        return StrUtils.isBlank(rootPath) ? "./reports/" : rootPath;
    }

    public String getCharset() {
        return StrUtils.isBlank(charset) ? "UTF-8" : charset;
    }

    public String getFont() {
        return StrUtils.isBlank(font) ? "classpath:/fonts/msyh.ttf" : font;
    }

    public String getMonitoringVersion() {
        String version = monitoring == null ? null : monitoring.version;
        return version == null || version.trim().isEmpty() ? "standard" : version.trim().toLowerCase();
    }

    @Data
    public static class Monitoring {
        private String version = "standard";
    }

    @Data
    public static class Ah2 {
        private String template = "classpath:/config/pbtxt/report_template_ah2.pb.txt";
        private String font = "classpath:/fonts/msyh.ttf";
        private String wardReportFont = "classpath:/fonts/msyh.ttf";
        private int generationLockStaleMinutes = 30;

        public String getTemplate() {
            return StrUtils.isBlank(template) ? "classpath:/config/pbtxt/report_template_ah2.pb.txt" : template;
        }

        public String getFont() {
            return StrUtils.isBlank(font) ? "classpath:/fonts/msyh.ttf" : font;
        }

        public String getWardReportFont() {
            return StrUtils.isBlank(wardReportFont) ? "classpath:/fonts/msyh.ttf" : wardReportFont;
        }
    }

    @Data
    public static class Compact {
        private String template = "classpath:/config/pbtxt/report_compact.pb.txt";
        private String font = "classpath:/fonts/msyh.ttf";
    }
}
