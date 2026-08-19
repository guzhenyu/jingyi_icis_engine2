package com.jingyicare.jingyi_icis_engine.service.certs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.DepartmentLicencePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.HospitalLicensePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceEnums;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.IcisBootstrapProperties;

class CertificateServiceTests {
    @Test
    void productionStartupRejectsMissingCertificate() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());

        assertThatThrownBy(fixture.service::validatePreInitializationOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cert_pb_txt is empty");
    }

    @Test
    void productionEmptyDatabaseRequiresBootstrap() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        setUsableCertificate(fixture.service, buildCertificate("ICU", 3, 1));

        assertThatThrownBy(fixture.service::validatePreInitializationOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("departments table is empty");
    }

    @Test
    void productionEmptyDatabaseAcceptsMatchingBootstrap() {
        IcisBootstrapProperties bootstrap = matchingBootstrap("ICU01", "ICU");
        ServiceFixture fixture = newProductionFixture(bootstrap);
        setUsableCertificate(fixture.service, buildCertificate("ICU", 3, 1));

        fixture.service.validatePreInitializationOrThrow();
    }

    @Test
    void productionBootstrapRequiresFrozenAuxiliaryFields() {
        IcisBootstrapProperties bootstrap = matchingBootstrap("ICU01", "ICU");
        bootstrap.getDepartments().get(0).setAbbreviation("重症");
        ServiceFixture fixture = newProductionFixture(bootstrap);
        setUsableCertificate(fixture.service, buildCertificate("ICU", 3, 1));

        assertThatThrownBy(fixture.service::validatePreInitializationOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("abbreviation must equal its name");
    }

    @Test
    void productionExistingDatabaseMayOmitBootstrap() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "测试医院");
        when(fixture.departmentRepository.count()).thenReturn(1L);
        when(fixture.departmentRepository.findByIsDeletedFalse()).thenReturn(List.of(department));
        setUsableCertificate(fixture.service, buildCertificate("ICU", 3, 1));

        fixture.service.validatePreInitializationOrThrow();
    }

    @Test
    void productionExistingDatabaseRejectsMissingLicensedDepartment() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "测试医院");
        when(fixture.departmentRepository.count()).thenReturn(1L);
        when(fixture.departmentRepository.findByIsDeletedFalse()).thenReturn(List.of(department));
        setUsableCertificate(
            fixture.service,
            buildCertificate("ICU", 3, 1).toBuilder()
                .addDepartmentLicences(buildDepartmentLicence("EICU", 2, 0))
                .build());

        assertThatThrownBy(fixture.service::validatePreInitializationOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("missing=[EICU]");
    }

    @Test
    void productionStartupValidatesRbacMirrorAndBedCounts() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "测试医院");
        when(fixture.departmentRepository.count()).thenReturn(1L);
        when(fixture.departmentRepository.findByIsDeletedFalse()).thenReturn(List.of(department));
        when(fixture.departmentRepository.findByDeptIdAndIsDeletedFalse("ICU01"))
            .thenReturn(Optional.of(department));
        when(fixture.rbacDepartmentRepository.findAll())
            .thenReturn(List.of(new RbacDepartment("ICU01", "ICU")));
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", FIXED_BED_TYPE))
            .thenReturn(3);
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", TEMP_BED_TYPE))
            .thenReturn(1);
        setUsableCertificate(fixture.service, buildCertificate("ICU", 3, 1));

        fixture.service.validateStartupOrThrow();
    }

    @Test
    void productionDoesNotBypassJingyiNamedDepartment() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "99999", "晶医重症医学科", "晶医");
        when(fixture.departmentRepository.findByDeptIdAndIsDeletedFalse("99999"))
            .thenReturn(Optional.of(department));
        setUsableCertificate(fixture.service, buildCertificate("ICU", 3, 1));

        assertThat(fixture.service.getMenuGroupIdList("99999")).isEmpty();
        assertThat(fixture.service.getMaxBedCount("99999").getFirst()).isZero();
        assertThat(fixture.service.getMaxBedCount("99999").getSecond()).isZero();
        assertThat(fixture.service.checkBedAvailable("99999")).isFalse();
    }

    @Test
    void productionFixedAndTemporaryBedLimitsAreCheckedSeparately() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "测试医院");
        when(fixture.departmentRepository.findByDeptIdAndIsDeletedFalse("ICU01"))
            .thenReturn(Optional.of(department));
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", FIXED_BED_TYPE))
            .thenReturn(2);
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", TEMP_BED_TYPE))
            .thenReturn(0);
        setUsableCertificate(fixture.service, buildCertificate("ICU", 3, 1));

        assertThat(fixture.service.checkBedAvailable("ICU01", FIXED_BED_TYPE, 1)).isTrue();
        assertThat(fixture.service.checkBedAvailable("ICU01", FIXED_BED_TYPE, 2)).isFalse();
        assertThat(fixture.service.checkBedAvailable("ICU01", TEMP_BED_TYPE, 1)).isTrue();
        assertThat(fixture.service.checkBedAvailable("ICU01", TEMP_BED_TYPE, 2)).isFalse();
        assertThat(fixture.service.checkBedAvailable("ICU01", 999, 1)).isFalse();
    }

    @Test
    void productionOnlyAllowsAuxiliaryDepartmentUpdates() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "测试医院");
        when(fixture.departmentRepository.findByIdAndIsDeletedFalse(1))
            .thenReturn(Optional.of(department));

        assertThat(fixture.service.isDepartmentCreationAllowed()).isFalse();
        assertThat(fixture.service.isDepartmentDeletionAllowed()).isFalse();
        assertThat(fixture.service.isDepartmentIdentityUpdateAllowed(
            1, "ICU01", "ICU", "测试医院")).isTrue();
        assertThat(fixture.service.isDepartmentIdentityUpdateAllowed(
            1, "ICU02", "ICU", "测试医院")).isFalse();
        assertThat(fixture.service.isDepartmentIdentityUpdateAllowed(
            1, "ICU01", "EICU", "测试医院")).isFalse();
    }

    @Test
    void certTestModeIsRejectedOutsideTestProfile() {
        IcisBootstrapProperties bootstrap = new IcisBootstrapProperties();
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        ServiceFixture fixture = newFixture(bootstrap, true, environment);

        assertThatThrownBy(fixture.service::validatePreInitializationOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("test profile");
    }

    private ServiceFixture newProductionFixture(IcisBootstrapProperties bootstrap) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return newFixture(bootstrap, false, environment);
    }

    private ServiceFixture newFixture(
        IcisBootstrapProperties bootstrap,
        boolean requestedTestMode,
        MockEnvironment environment
    ) {
        ConfigProtoService configProtoService = mock(ConfigProtoService.class);
        Config config = Config.newBuilder()
            .setDevice(DeviceConfigPB.newBuilder()
                .setEnums(DeviceEnums.newBuilder()
                    .addBedType(EnumValue.newBuilder()
                        .setId(FIXED_BED_TYPE).setName("固定授权"))
                    .addBedType(EnumValue.newBuilder()
                        .setId(TEMP_BED_TYPE).setName("临时授权"))))
            .build();
        when(configProtoService.getConfig()).thenReturn(config);

        DepartmentRepository departmentRepository = mock(DepartmentRepository.class);
        RbacDepartmentRepository rbacDepartmentRepository = mock(RbacDepartmentRepository.class);
        BedConfigRepository bedConfigRepository = mock(BedConfigRepository.class);
        CertificateService service = new CertificateService(
            "",
            "",
            requestedTestMode,
            configProtoService,
            departmentRepository,
            rbacDepartmentRepository,
            bedConfigRepository,
            bootstrap,
            environment);
        return new ServiceFixture(
            service,
            departmentRepository,
            rbacDepartmentRepository,
            bedConfigRepository);
    }

    private IcisBootstrapProperties matchingBootstrap(String deptCode, String departmentName) {
        IcisBootstrapProperties properties = new IcisBootstrapProperties();
        properties.setHospitalName("测试医院");
        IcisBootstrapProperties.DepartmentEntry entry =
            new IcisBootstrapProperties.DepartmentEntry();
        entry.setDeptCode(deptCode);
        entry.setName(departmentName);
        entry.setAbbreviation(departmentName);
        entry.setWardCode("");
        entry.setWardName("");
        properties.setDepartments(List.of(entry));
        return properties;
    }

    private HospitalLicensePB buildCertificate(
        String departmentName,
        int fixedBedLimit,
        int temporaryBedLimit
    ) {
        return HospitalLicensePB.newBuilder()
            .setHospitalName("测试医院")
            .addMenuGroupId(1)
            .setMaxBedCount(10)
            .addDepartmentLicences(
                buildDepartmentLicence(departmentName, fixedBedLimit, temporaryBedLimit))
            .setIssueTimeIso8601("2020-01-01T00:00:00Z")
            .setExpirationTimeIso8601("2099-01-01T00:00:00Z")
            .build();
    }

    private DepartmentLicencePB buildDepartmentLicence(
        String departmentName,
        int fixedBedLimit,
        int temporaryBedLimit
    ) {
        return DepartmentLicencePB.newBuilder()
            .setDepartmentName(departmentName)
            .addMenuGroupId(15)
            .setMaxBedCount(fixedBedLimit)
            .setMaxTempBedCount(temporaryBedLimit)
            .build();
    }

    @SuppressWarnings("unchecked")
    private void setUsableCertificate(
        CertificateService service,
        HospitalLicensePB certificate
    ) {
        ReflectionTestUtils.setField(service, "hospitalLicensePb", certificate);
        ReflectionTestUtils.setField(service, "validationError", null);
        ReflectionTestUtils.setField(
            service,
            "issueTime",
            LocalDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(
            service,
            "expireTime",
            LocalDateTime.now().plusDays(1));
        Map<String, DepartmentLicencePB> licenceMap =
            (Map<String, DepartmentLicencePB>) ReflectionTestUtils.getField(
                service,
                "departmentLicencePbMap");
        licenceMap.clear();
        for (DepartmentLicencePB licence : certificate.getDepartmentLicencesList()) {
            licenceMap.put(licence.getDepartmentName(), licence);
        }
    }

    private Department buildDepartment(
        int id,
        String deptCode,
        String name,
        String hospitalName
    ) {
        Department department = new Department();
        department.setId(id);
        department.setDeptId(deptCode);
        department.setName(name);
        department.setAbbreviation(name);
        department.setHospitalName(hospitalName);
        department.setIsDeleted(false);
        return department;
    }

    private static final int FIXED_BED_TYPE = 1;
    private static final int TEMP_BED_TYPE = 2;

    private record ServiceFixture(
        CertificateService service,
        DepartmentRepository departmentRepository,
        RbacDepartmentRepository rbacDepartmentRepository,
        BedConfigRepository bedConfigRepository
    ) {}
}
