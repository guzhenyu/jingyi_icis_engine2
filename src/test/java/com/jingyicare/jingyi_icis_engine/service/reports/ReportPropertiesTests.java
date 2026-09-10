package com.jingyicare.jingyi_icis_engine.service.reports;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

public class ReportPropertiesTests {
    @Test
    public void bindsAndNormalizesAh2DepartmentTemplateClasspaths() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(
                "jingyi.report.ah2.dept_template_classpaths",
                "1040110:/config/default.pb.txt, 1090415:classpath:/config/sjtu.pb.txt"
            );

        ReportProperties properties = Binder.get(environment)
            .bind("jingyi.report", Bindable.of(ReportProperties.class))
            .orElseThrow(() -> new AssertionError("ReportProperties binding failed"));

        assertThat(properties.getAh2().getDeptTemplateClasspathMap()).isEqualTo(Map.of(
            "1040110", "classpath:/config/default.pb.txt",
            "1090415", "classpath:/config/sjtu.pb.txt"
        ));
    }

    @Test
    public void rejectsDuplicateAh2DepartmentTemplateClasspaths() {
        ReportProperties properties = new ReportProperties();
        properties.getAh2().setDeptTemplateClasspaths(
            "1090415:/config/first.pb.txt,1090415:/config/second.pb.txt"
        );

        assertThatThrownBy(() -> properties.getAh2().getDeptTemplateClasspathMap())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Duplicate department")
            .hasMessageContaining("1090415");
    }

    @Test
    public void rejectsMalformedAh2DepartmentTemplateClasspath() {
        ReportProperties properties = new ReportProperties();
        properties.getAh2().setDeptTemplateClasspaths("1090415");

        assertThatThrownBy(() -> properties.getAh2().getDeptTemplateClasspathMap())
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("Invalid jingyi.report.ah2.dept_template_classpaths entry");
    }

    @Test
    public void compactPatientBalanceRecordsDrainageTubeParamsHasDefaultAndBlankFallback() {
        ReportProperties properties = new ReportProperties();

        assertThat(properties.getCompact().getPatientBalanceRecords().getDrainageTubeParams())
            .isEqualTo("compactreportdrainagetubeparams");

        properties.getCompact().getPatientBalanceRecords().setDrainageTubeParams(" ");
        assertThat(properties.getCompact().getPatientBalanceRecords().getDrainageTubeParams())
            .isEqualTo("compactreportdrainagetubeparams");
    }

    @Test
    public void bindsCompactPatientBalanceRecordsDrainageTubeParams() {
        MockEnvironment environment = new MockEnvironment()
            .withProperty(
                "jingyi.report.compact.patient-balance-records.drainage-tube-params",
                "customplaceholder"
            );

        ReportProperties properties = Binder.get(environment)
            .bind("jingyi.report", Bindable.of(ReportProperties.class))
            .orElseThrow(() -> new AssertionError("ReportProperties binding failed"));

        assertThat(properties.getCompact().getPatientBalanceRecords().getDrainageTubeParams())
            .isEqualTo("customplaceholder");
    }
}
