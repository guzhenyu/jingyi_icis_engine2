package com.jingyicare.jingyi_icis_engine.service.reports;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

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
        public static final String VARIANT_AH2 = "ah2";
        public static final String VARIANT_XIUNING = "xiuning";
        public static final String TEMPLATE_AH2 = "classpath:/config/pbtxt/hospitals/report_template_ah2.pb.txt";
        public static final String TEMPLATE_AH2_LEGACY = "classpath:/config/pbtxt/report_template_ah2.pb.txt";
        public static final String TEMPLATE_AH2_SJTU = "classpath:/config/pbtxt/hospitals/report_template_ah2_sjtu.pb.txt";
        public static final String TEMPLATE_XIUNING = "classpath:/config/pbtxt/hospitals/report_template_xiuning.pb.txt";

        private String template = TEMPLATE_AH2;
        private String deptTemplateClasspaths = "";
        private String variant = VARIANT_AH2;
        private String font = "classpath:/fonts/msyh.ttf";
        private String wardReportFont = "classpath:/fonts/msyh.ttf";
        private int generationLockStaleMinutes = 30;
        private boolean drawLineForEmptyRows = false;

        public String getTemplate() {
            return StrUtils.isBlank(template) ? TEMPLATE_AH2 : template.trim();
        }

        public String getVariant() {
            return StrUtils.isBlank(variant) ? VARIANT_AH2 : variant.trim().toLowerCase();
        }

        public Map<String, String> getDeptTemplateClasspathMap() {
            if (StrUtils.isBlank(deptTemplateClasspaths)) {
                return Collections.emptyMap();
            }

            Map<String, String> result = new LinkedHashMap<>();
            for (String rawEntry : deptTemplateClasspaths.split(",")) {
                String entry = rawEntry.trim();
                if (entry.isEmpty()) continue;

                int separator = entry.indexOf(':');
                if (separator <= 0 || separator == entry.length() - 1) {
                    throw new IllegalArgumentException(
                        "Invalid jingyi.report.ah2.dept_template_classpaths entry: " + entry
                    );
                }

                String deptId = entry.substring(0, separator).trim();
                String classpath = normalizeClasspath(entry.substring(separator + 1));
                if (deptId.isEmpty() || classpath.isEmpty()) {
                    throw new IllegalArgumentException(
                        "Invalid jingyi.report.ah2.dept_template_classpaths entry: " + entry
                    );
                }
                if (result.putIfAbsent(deptId, classpath) != null) {
                    throw new IllegalArgumentException(
                        "Duplicate department in jingyi.report.ah2.dept_template_classpaths: " + deptId
                    );
                }
            }
            return Collections.unmodifiableMap(result);
        }

        private String normalizeClasspath(String configuredPath) {
            String path = configuredPath == null ? "" : configuredPath.trim();
            if (path.isEmpty() || path.startsWith("classpath:")) return path;
            return path.startsWith("/") ? "classpath:" + path : "classpath:/" + path;
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
        private String template = "classpath:/config/pbtxt/hospitals/xaxrmyy_report_compact.pb.txt";
        private String font = "classpath:/fonts/msyh.ttf";
        private int medicationMlDecimalPlaces = 1;
        private PatientMonitoringRecords patientMonitoringRecords = new PatientMonitoringRecords();
        private PatientBalanceRecords patientBalanceRecords = new PatientBalanceRecords();
        private Skincare skincare = new Skincare();
        private TubePolicy tubePolicy = new TubePolicy();
    }

    @Data
    public static class PatientMonitoringRecords {
        private boolean filterEmptyParams = false;
    }

    @Data
    public static class PatientBalanceRecords {
        private String drainageTubeParams = "compactreportdrainagetubeparams";

        public String getDrainageTubeParams() {
            return StrUtils.isBlank(drainageTubeParams) ? "compactreportdrainagetubeparams" : drainageTubeParams;
        }
    }

    @Data
    public static class Skincare {
        private String attrCodeWhitelist = "";
    }

    @Data
    public static class TubePolicy {
        private boolean firstRecordInShift = true;
    }
}
