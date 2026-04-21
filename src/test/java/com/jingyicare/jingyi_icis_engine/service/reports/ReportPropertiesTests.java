package com.jingyicare.jingyi_icis_engine.service.reports;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

public class ReportPropertiesTests {
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
