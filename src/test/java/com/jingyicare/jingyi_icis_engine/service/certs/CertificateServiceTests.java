package com.jingyicare.jingyi_icis_engine.service.certs;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_FIXED;
import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_TEMP;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.DepartmentLicencePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.HospitalLicensePB;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedCountRepository;
import com.jingyicare.jingyi_icis_engine.service.IcisBootstrapProperties;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMetaReader;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;

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
        setUsableCertificate(fixture.certificate, buildCertificate("ICU", 3, 1));

        assertThatThrownBy(fixture.service::validatePreInitializationOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("departments table is empty");
    }

    @Test
    void productionEmptyDatabaseAcceptsMatchingBootstrap() {
        IcisBootstrapProperties bootstrap = matchingBootstrap("ICU01", "ICU");
        ServiceFixture fixture = newProductionFixture(bootstrap);
        setUsableCertificate(fixture.certificate, buildCertificate("ICU", 3, 1));

        fixture.service.validatePreInitializationOrThrow();
    }

    @Test
    void productionBootstrapRequiresFrozenAuxiliaryFields() {
        IcisBootstrapProperties bootstrap = matchingBootstrap("ICU01", "ICU");
        bootstrap.getDepartments().get(0).setAbbreviation("重症");
        ServiceFixture fixture = newProductionFixture(bootstrap);
        setUsableCertificate(fixture.certificate, buildCertificate("ICU", 3, 1));

        assertThatThrownBy(fixture.service::validatePreInitializationOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("abbreviation must equal its name");
    }

    @Test
    void productionExistingDatabaseMayOmitBootstrap() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "测试医院");
        when(fixture.userQuery.getAllDeptInfo()).thenReturn(List.of(department));
        setUsableCertificate(fixture.certificate, buildCertificate("ICU", 3, 1));

        fixture.service.validatePreInitializationOrThrow();
    }

    @Test
    void productionStartupRejectsLicensedDepartmentNameAtAnotherHospital() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "其他医院");
        when(fixture.userQuery.getAllDeptInfo()).thenReturn(List.of(department));
        setUsableCertificate(fixture.certificate, buildCertificate("ICU", 3, 1));

        assertThatThrownBy(fixture.service::validateStartupOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("department hospital does not match certificate");
    }

    @Test
    void productionExistingDatabaseRejectsMissingLicensedDepartment() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "测试医院");
        when(fixture.userQuery.getAllDeptInfo()).thenReturn(List.of(department));
        setUsableCertificate(
            fixture.certificate,
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
        when(fixture.userQuery.getAllDeptInfo()).thenReturn(List.of(department));
        when(fixture.userQuery.getDepartmentName("ICU01")).thenReturn("ICU");
        when(fixture.userQuery.getAllRbacDeptInfo())
            .thenReturn(List.of(new RbacDepartment("ICU01", "ICU")));
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", BED_TYPE_FIXED))
            .thenReturn(3);
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", BED_TYPE_TEMP))
            .thenReturn(1);
        setUsableCertificate(fixture.certificate, buildCertificate("ICU", 3, 1));

        fixture.service.validateStartupOrThrow();
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", BED_TYPE_TEMP))
            .thenReturn(2);
        assertThatThrownBy(fixture.service::validateStartupOrThrow)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Bed count exceeds certificate limit");
    }

    @Test
    void productionDoesNotBypassJingyiNamedDepartment() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        when(fixture.userQuery.getDepartmentName("99999")).thenReturn("晶医重症医学科");
        setUsableCertificate(fixture.certificate, buildCertificate("ICU", 3, 1));

        assertThat(fixture.service.getMenuGroupIdList("99999")).isEmpty();
        assertThat(fixture.service.getMaxBedCount("99999").getFirst()).isZero();
        assertThat(fixture.service.getMaxBedCount("99999").getSecond()).isZero();
        assertThat(fixture.service.checkBedAvailable("99999")).isFalse();
    }

    @Test
    void productionFixedAndTemporaryBedLimitsAreCheckedSeparately() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        when(fixture.userQuery.getDepartmentName("ICU01")).thenReturn("ICU");
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", BED_TYPE_FIXED))
            .thenReturn(2);
        when(fixture.bedConfigRepository
            .countByDepartmentIdAndBedTypeAndIsDeletedFalse("ICU01", BED_TYPE_TEMP))
            .thenReturn(0);
        setUsableCertificate(fixture.certificate, buildCertificate("ICU", 3, 1));

        assertThat(fixture.service.checkBedAvailable("ICU01", BED_TYPE_FIXED, 1)).isTrue();
        assertThat(fixture.service.checkBedAvailable("ICU01", BED_TYPE_FIXED, 2)).isFalse();
        assertThat(fixture.service.checkBedAvailable("ICU01", BED_TYPE_TEMP, 1)).isTrue();
        assertThat(fixture.service.checkBedAvailable("ICU01", BED_TYPE_TEMP, 2)).isFalse();
        assertThat(fixture.service.checkBedAvailable("ICU01", 999, 1)).isFalse();
    }

    @Test
    void productionOnlyAllowsAuxiliaryDepartmentUpdates() {
        ServiceFixture fixture = newProductionFixture(new IcisBootstrapProperties());
        Department department = buildDepartment(1, "ICU01", "ICU", "测试医院");
        when(fixture.userQuery.getAllDeptInfo()).thenReturn(List.of(department));

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
        BedConfigRepository bedConfigRepository = mock(BedConfigRepository.class);
        UserQuery userQuery = mock(UserQuery.class);
        CertificateReader certificate = new CertificateReader(
            "", "", requestedTestMode, userQuery, environment);
        BedMetaReader bedMetaReader = new BedMetaReader(
            bedConfigRepository, mock(BedCountRepository.class), certificate);
        CertificateService service = new CertificateService(
            certificate, userQuery, bootstrap, bedMetaReader);
        return new ServiceFixture(
            service,
            certificate,
            userQuery,
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
        CertificateReader reader,
        HospitalLicensePB certificate
    ) {
        ReflectionTestUtils.setField(reader, "hospitalLicensePb", certificate);
        ReflectionTestUtils.setField(reader, "validationError", null);
        ReflectionTestUtils.setField(
            reader,
            "issueTime",
            LocalDateTime.now().minusDays(1));
        ReflectionTestUtils.setField(
            reader,
            "expireTime",
            LocalDateTime.now().plusDays(1));
        Map<String, DepartmentLicencePB> licenceMap =
            (Map<String, DepartmentLicencePB>) ReflectionTestUtils.getField(
                reader,
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

    private record ServiceFixture(
        CertificateService service,
        CertificateReader certificate,
        UserQuery userQuery,
        BedConfigRepository bedConfigRepository
    ) {}
}
