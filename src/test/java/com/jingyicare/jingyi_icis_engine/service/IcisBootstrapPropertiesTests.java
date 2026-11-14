package com.jingyicare.jingyi_icis_engine.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.DepartmentPB;

class IcisBootstrapPropertiesTests {
    @Test
    void configurationBindingBuildsDepartmentProtosWithTheHospitalName() {
        new ApplicationContextRunner().withUserConfiguration(BootstrapConfiguration.class)
            .withPropertyValues(
                "jingyi.icis.bootstrap.hospital-name=测试医院",
                "jingyi.icis.bootstrap.departments[0].dept-code=ICU01",
                "jingyi.icis.bootstrap.departments[0].name=重症医学科",
                "jingyi.icis.bootstrap.departments[0].abbreviation=重症医学科",
                "jingyi.icis.bootstrap.departments[1].dept-code=ICU02",
                "jingyi.icis.bootstrap.departments[1].name=急诊重症医学科",
                "jingyi.icis.bootstrap.departments[1].abbreviation=急诊重症医学科")
            .run(context -> {
                IcisBootstrapProperties properties = context.getBean(IcisBootstrapProperties.class);

                assertThat(properties.getDeptPbList()).containsExactly(
                    departmentPb("ICU01", "重症医学科"), departmentPb("ICU02", "急诊重症医学科"));
                assertThat(properties.getDepartments()).hasSize(2);
            });
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void departmentProtosDoNotDependOnTheSetterOrder(boolean hospitalFirst) {
        IcisBootstrapProperties properties = new IcisBootstrapProperties();
        if (hospitalFirst) properties.setHospitalName(" 测试医院 ");
        properties.setDepartments(List.of(departmentEntry(" ICU01 ", " 重症医学科 ")));
        if (!hospitalFirst) properties.setHospitalName(" 测试医院 ");

        assertThat(properties.getDeptPbList())
            .containsExactly(departmentPb("ICU01", "重症医学科"));
    }

    @Test
    void replacingOrClearingDepartmentsDoesNotLeaveStaleProtos() {
        IcisBootstrapProperties properties = new IcisBootstrapProperties();
        properties.setHospitalName("测试医院");
        properties.setDepartments(List.of(departmentEntry("ICU01", "重症医学科")));
        properties.setDepartments(List.of(departmentEntry("ICU02", "急诊重症医学科")));

        assertThat(properties.getDeptPbList())
            .containsExactly(departmentPb("ICU02", "急诊重症医学科"));

        properties.setDepartments(null);
        assertThat(properties.getDepartments()).isEmpty();
        assertThat(properties.getDeptPbList()).isEmpty();
    }

    @Test
    void incompleteBindingKeepsRawValuesAvailableForCertificateValidation() {
        IcisBootstrapProperties properties = new IcisBootstrapProperties();
        properties.setDepartments(List.of(new IcisBootstrapProperties.DepartmentEntry()));

        assertThat(properties.getDeptPbList()).containsExactly(DepartmentPB.getDefaultInstance());
        assertThat(properties.getDepartments().getFirst().getDeptCode()).isNull();
        assertThat(properties.getDepartments().getFirst().getName()).isNull();
    }

    private IcisBootstrapProperties.DepartmentEntry departmentEntry(String code, String name) {
        IcisBootstrapProperties.DepartmentEntry entry =
            new IcisBootstrapProperties.DepartmentEntry();
        entry.setDeptCode(code);
        entry.setName(name);
        entry.setAbbreviation(name);
        return entry;
    }

    private DepartmentPB departmentPb(String code, String name) {
        return DepartmentPB.newBuilder().setDeptId(code).setDeptName(name).setAbbreviation(name)
            .setHospitalName("测试医院").build();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(IcisBootstrapProperties.class)
    static class BootstrapConfiguration {}
}
