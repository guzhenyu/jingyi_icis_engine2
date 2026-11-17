package com.jingyicare.jingyi_icis_engine.service.certs;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import javax.crypto.Cipher;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

import com.google.protobuf.TextFormat;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.CertificatePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.DepartmentLicencePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.HospitalLicensePB;
import com.jingyicare.jingyi_icis_engine.service.IcisBootstrapProperties;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.RsaUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

/** 启动时读取并校验证书，各调用方共享同一份授权数据。 */
@Component
@Slf4j
public class CertificateReader {
    public record BedLimits(int maxFixedBeds, int maxTempBeds) {}

    CertificateReader(
        @Value("${public_key_file_path:}") String publicKeyFilePath,
        @Value("${cert_pb_txt:}") String certPbTxtPath,
        @Value("${cert_test_mode:false}") Boolean requestedTestMode,
        UserQuery userQuery,
        Environment environment
    ) {
        boolean testProfileOnly = environment.acceptsProfiles(Profiles.of("test"))
            && !environment.acceptsProfiles(Profiles.of("prod"));
        this.testMode = Boolean.TRUE.equals(requestedTestMode) && testProfileOnly;
        this.invalidTestModeRequested = Boolean.TRUE.equals(requestedTestMode) && !testProfileOnly;
        this.userQuery = userQuery;
        loadCertificate(publicKeyFilePath, certPbTxtPath);
    }

    public boolean isTestMode() {
        return testMode;
    }

    public Optional<BedLimits> findBedLimits(String deptId) {
        if (testMode) return Optional.of(TEST_BED_LIMITS);
        DepartmentLicencePB licence = findDepartmentLicence(deptId);
        if (licence == null) return Optional.empty();

        int maxFixedBeds = licence.getMaxBedCount() == 0
            ? hospitalLicensePb.getMaxBedCount() : licence.getMaxBedCount();
        return Optional.of(new BedLimits(maxFixedBeds, licence.getMaxTempBedCount()));
    }

    Set<Integer> getMenuGroupIdList(String deptId) {
        if (testMode) return Set.of(1, 15);
        DepartmentLicencePB licence = findDepartmentLicence(deptId);
        if (licence == null) return Set.of();

        Set<Integer> menuGroupIds = new LinkedHashSet<>(hospitalLicensePb.getMenuGroupIdList());
        menuGroupIds.addAll(licence.getMenuGroupIdList());
        return menuGroupIds;
    }

    private DepartmentLicencePB findDepartmentLicence(String deptId) {
        if (!isCertificateUsable() || StrUtils.isBlank(deptId)) return null;
        return departmentLicencePbMap.get(userQuery.getDepartmentName(deptId));
    }

    void validateForStartupOrThrow() {
        if (invalidTestModeRequested) {
            throw new IllegalStateException(
                "cert_test_mode may only be enabled when the test profile is active without prod");
        }
        if (testMode) return;
        validateCertificateUsableOrThrow();
    }

    private void loadCertificate(String publicKeyFilePath, String certPbTxtPath) {
        if (StrUtils.isBlank(certPbTxtPath)) {
            validationError = "cert_pb_txt is empty";
            return;
        }
        if (StrUtils.isBlank(publicKeyFilePath)) {
            validationError = "public_key_file_path is empty";
            return;
        }

        try {
            PublicKey publicKey = loadPublicKey(publicKeyFilePath.trim());
            CertificatePB certificatePb = loadCertificatePb(certPbTxtPath.trim());

            byte[] encryptedData = Base64.getDecoder().decode(certificatePb.getCertificateData());
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, publicKey);
            byte[] decryptedData = cipher.doFinal(encryptedData);
            HospitalLicensePB decryptedCertificate = HospitalLicensePB.parseFrom(
                Base64.getDecoder().decode(new String(decryptedData, StandardCharsets.UTF_8)));

            if (!certificatePb.getHospitalLicense().equals(decryptedCertificate)) {
                validationError = "certificate hospital license does not match decrypted data";
                return;
            }

            validateCertificateModel(decryptedCertificate);
            hospitalLicensePb = decryptedCertificate;
            issueTime = TimeUtils.fromIso8601String(
                decryptedCertificate.getIssueTimeIso8601(), "UTC");
            expireTime = TimeUtils.fromIso8601String(
                decryptedCertificate.getExpirationTimeIso8601(), "UTC");
            for (DepartmentLicencePB departmentLicence
                : decryptedCertificate.getDepartmentLicencesList()) {
                departmentLicencePbMap.put(
                    departmentLicence.getDepartmentName(), departmentLicence);
            }
            validationError = null;
            log.info("Certificate loaded successfully");
        } catch (Exception exception) {
            validationError = "error reading certificate: " + exception.getMessage();
            log.error("Error reading the certificate", exception);
        }
    }

    private PublicKey loadPublicKey(String publicKeyFilePath) throws Exception {
        StringBuilder publicKeyBody = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(publicKeyFilePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("-----")) {
                    publicKeyBody.append(line.trim());
                }
            }
        }
        return RsaUtils.getJingyiPublicKey(publicKeyBody.toString());
    }

    private CertificatePB loadCertificatePb(String certPbTxtPath) throws Exception {
        File certificateFile = new File(certPbTxtPath);
        if (!certificateFile.isFile()) {
            throw new IllegalArgumentException(
                "certificate file does not exist or is not a file: " + certPbTxtPath);
        }
        CertificatePB.Builder builder = CertificatePB.newBuilder();
        try (BufferedReader reader = new BufferedReader(new FileReader(certificateFile))) {
            TextFormat.getParser().merge(reader, builder);
        }
        return builder.build();
    }

    private void validateCertificateModel(HospitalLicensePB certificate) {
        requireTrimmed("certificate hospital_name", certificate.getHospitalName());
        if (certificate.getMaxBedCount() < 1) {
            throw new IllegalArgumentException("certificate max_bed_count must be at least 1");
        }

        LocalDateTime parsedIssueTime = TimeUtils.fromIso8601String(
            certificate.getIssueTimeIso8601(), "UTC");
        LocalDateTime parsedExpireTime = TimeUtils.fromIso8601String(
            certificate.getExpirationTimeIso8601(), "UTC");
        if (parsedIssueTime == null || parsedExpireTime == null) {
            throw new IllegalArgumentException(
                "certificate issue_time_iso8601 and expiration_time_iso8601 must be valid");
        }
        if (!parsedExpireTime.isAfter(parsedIssueTime)) {
            throw new IllegalArgumentException(
                "certificate expiration_time_iso8601 must be later than issue_time_iso8601");
        }

        validatePositiveUniqueIds("certificate menu_group_id", certificate.getMenuGroupIdList());
        if (certificate.getDepartmentLicencesCount() == 0) {
            throw new IllegalArgumentException(
                "certificate department_licences must contain at least one department");
        }
        Set<String> departmentNames = new HashSet<>();
        for (DepartmentLicencePB departmentLicence : certificate.getDepartmentLicencesList()) {
            String departmentName = departmentLicence.getDepartmentName();
            requireTrimmed("certificate department_name", departmentName);
            if (!departmentNames.add(departmentName)) {
                throw new IllegalArgumentException(
                    "certificate contains duplicate department_name: " + departmentName);
            }
            if (departmentLicence.getMaxBedCount() < 0
                || departmentLicence.getMaxTempBedCount() < 0) {
                throw new IllegalArgumentException(
                    "certificate department bed limits must not be negative: " + departmentName);
            }
            validatePositiveUniqueIds(
                "certificate department menu_group_id for " + departmentName,
                departmentLicence.getMenuGroupIdList());
        }
    }

    void validateBootstrapAgainstCertificate(IcisBootstrapProperties bootstrapProperties) {
        String bootstrapHospitalName = bootstrapProperties.getHospitalName();
        requireTrimmed("jingyi.icis.bootstrap.hospital-name", bootstrapHospitalName);
        if (!bootstrapHospitalName.equals(hospitalLicensePb.getHospitalName())) {
            throw new IllegalStateException(String.format(
                "Bootstrap hospital does not match certificate hospital: "
                    + "bootstrap=%s, certificate=%s",
                bootstrapHospitalName,
                hospitalLicensePb.getHospitalName()));
        }

        List<IcisBootstrapProperties.DepartmentEntry> bootstrapDepartments =
            bootstrapProperties.getDepartments();
        if (bootstrapDepartments.isEmpty()) {
            throw new IllegalStateException(
                "jingyi.icis.bootstrap.departments must contain at least one department");
        }

        Set<String> bootstrapCodes = new LinkedHashSet<>();
        Set<String> bootstrapNames = new LinkedHashSet<>();
        for (IcisBootstrapProperties.DepartmentEntry entry : bootstrapDepartments) {
            String deptCode = entry.getDeptCode();
            String departmentName = entry.getName();
            requireTrimmed("Bootstrap department code", deptCode);
            requireTrimmed("Bootstrap department name", departmentName);
            if (!bootstrapCodes.add(deptCode)) {
                throw new IllegalStateException(
                    "Bootstrap contains duplicate department code: " + deptCode);
            }
            if (!bootstrapNames.add(departmentName)) {
                throw new IllegalStateException(
                    "Bootstrap contains duplicate department name: " + departmentName);
            }
            if (!Objects.equals(entry.getAbbreviation(), departmentName)) {
                throw new IllegalStateException(
                    "Bootstrap department abbreviation must equal its name: " + deptCode);
            }
            if (!StrUtils.isBlank(entry.getWardCode()) || !StrUtils.isBlank(entry.getWardName())) {
                throw new IllegalStateException(
                    "Bootstrap department ward-code and ward-name must be empty: " + deptCode);
            }
        }

        Set<String> licenceNames = getUniqueLicenceDepartmentNames();
        if (!bootstrapNames.equals(licenceNames)) {
            throw new IllegalStateException(describeSetMismatch(
                "Bootstrap department names do not match certificate department_licences",
                licenceNames,
                bootstrapNames));
        }
    }

    void validateDatabaseDepartmentsAgainstCertificate(List<Department> departments) {
        Set<String> departmentCodes = new LinkedHashSet<>();
        Set<String> departmentNames = new LinkedHashSet<>();
        for (Department department : departments) {
            String departmentCode = department.getDeptId();
            String departmentName = department.getName();
            requireTrimmed("Database department code", departmentCode);
            requireTrimmed("Database department name", departmentName);
            if (!departmentCodes.add(departmentCode)) {
                throw new IllegalStateException(
                    "Database contains duplicate active department code: " + departmentCode);
            }
            if (!departmentNames.add(departmentName)) {
                throw new IllegalStateException(
                    "Database contains duplicate active department name: " + departmentName);
            }
            if (!Objects.equals(
                department.getHospitalName(), hospitalLicensePb.getHospitalName())) {
                throw new IllegalStateException(String.format(
                    "ICIS department hospital does not match certificate: "
                        + "code=%s, department=%s, certificate=%s",
                    departmentCode,
                    department.getHospitalName(),
                    hospitalLicensePb.getHospitalName()));
            }
        }

        Set<String> licenceNames = getUniqueLicenceDepartmentNames();
        if (!departmentNames.equals(licenceNames)) {
            throw new IllegalStateException(describeSetMismatch(
                "Database ICIS department names do not match certificate department_licences",
                licenceNames,
                departmentNames));
        }
    }

    private Set<String> getUniqueLicenceDepartmentNames() {
        Set<String> names = new LinkedHashSet<>();
        for (DepartmentLicencePB departmentLicence
            : hospitalLicensePb.getDepartmentLicencesList()) {
            String departmentName = departmentLicence.getDepartmentName();
            if (!names.add(departmentName)) {
                throw new IllegalStateException(
                    "Certificate contains duplicate department_name: " + departmentName);
            }
        }
        return names;
    }

    private void validateCertificateUsableOrThrow() {
        if (validationError != null) {
            throw new IllegalStateException("Certificate validation failed: " + validationError);
        }
        if (!isCertificateTimeValid()) {
            throw new IllegalStateException(String.format(
                "Certificate is not in valid time range: issueTime=%s, expireTime=%s",
                issueTime,
                expireTime));
        }
    }

    private boolean isCertificateUsable() {
        return validationError == null
            && hospitalLicensePb != null
            && isCertificateTimeValid();
    }

    private boolean isCertificateTimeValid() {
        if (issueTime == null || expireTime == null) {
            return false;
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        return !nowUtc.isBefore(issueTime) && !nowUtc.isAfter(expireTime);
    }

    private void validatePositiveUniqueIds(String fieldName, List<Integer> ids) {
        Set<Integer> uniqueIds = new HashSet<>();
        for (Integer id : ids) {
            if (id == null || id <= 0) {
                throw new IllegalArgumentException(fieldName + " must contain only positive IDs");
            }
            if (!uniqueIds.add(id)) {
                throw new IllegalArgumentException(fieldName + " contains duplicate ID: " + id);
            }
        }
    }

    private void requireTrimmed(String fieldName, String value) {
        if (StrUtils.isBlank(value) || !value.equals(value.trim())) {
            throw new IllegalStateException(fieldName + " must be non-empty and trimmed");
        }
    }

    private String describeSetMismatch(
        String prefix,
        Set<String> expected,
        Set<String> actual
    ) {
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        Set<String> unexpected = new LinkedHashSet<>(actual);
        unexpected.removeAll(expected);
        return String.format("%s: missing=%s, unexpected=%s", prefix, missing, unexpected);
    }

    private static final BedLimits TEST_BED_LIMITS = new BedLimits(3, 0);

    private final boolean testMode;
    private final boolean invalidTestModeRequested;
    private final UserQuery userQuery;
    private final Map<String, DepartmentLicencePB> departmentLicencePbMap = new HashMap<>();

    private String validationError;
    private HospitalLicensePB hospitalLicensePb;
    private LocalDateTime issueTime;
    private LocalDateTime expireTime;
}
