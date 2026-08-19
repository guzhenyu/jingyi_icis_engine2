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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import javax.crypto.Cipher;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Service;

import com.google.protobuf.TextFormat;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.CertificatePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.DepartmentLicencePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisCertificate.HospitalLicensePB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.DeviceConfigPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.repository.patients.BedConfigRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.IcisBootstrapProperties;
import com.jingyicare.jingyi_icis_engine.utils.Pair;
import com.jingyicare.jingyi_icis_engine.utils.RsaUtils;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

/**
 * Enforces the ICIS production licence boundary.
 *
 * <p>All active rows in {@code departments} are ICIS-enabled departments. Production
 * startup therefore requires an exact hospital/name match between that table and the
 * certificate. An empty table may be initialized only from a validated deployment
 * bootstrap. No production path falls back to the bundled Jingyi demo department or
 * to a default licence.</p>
 */
@Service
@Slf4j
public class CertificateService {
    public CertificateService(
        @Value("${public_key_file_path:}") String publicKeyFilePath,
        @Value("${cert_pb_txt:}") String certPbTxtPath,
        @Value("${cert_test_mode:false}") Boolean requestedTestMode,
        @Autowired ConfigProtoService configProtoService,
        @Autowired DepartmentRepository departmentRepository,
        @Autowired RbacDepartmentRepository rbacDepartmentRepository,
        @Autowired BedConfigRepository bedConfigRepository,
        @Autowired IcisBootstrapProperties bootstrapProperties,
        @Autowired Environment environment
    ) {
        boolean testProfileOnly = environment.acceptsProfiles(Profiles.of("test"))
            && !environment.acceptsProfiles(Profiles.of("prod"));
        this.testMode = Boolean.TRUE.equals(requestedTestMode) && testProfileOnly;
        this.invalidTestModeRequested = Boolean.TRUE.equals(requestedTestMode) && !testProfileOnly;

        DeviceConfigPB deviceConfig = configProtoService.getConfig().getDevice();
        this.fixedBedTypeId = findBedTypeId(deviceConfig, "固定授权");
        this.tempBedTypeId = findBedTypeId(deviceConfig, "临时授权");
        this.departmentRepository = departmentRepository;
        this.rbacDepartmentRepository = rbacDepartmentRepository;
        this.bedConfigRepository = bedConfigRepository;
        this.bootstrapProperties = bootstrapProperties;
        this.departmentLicencePbMap = new HashMap<>();

        loadCertificate(publicKeyFilePath, certPbTxtPath);
    }

    /** Validates deployment inputs before any database initializer is allowed to write. */
    public void validatePreInitializationOrThrow() {
        if (invalidTestModeRequested) {
            throw new IllegalStateException(
                "cert_test_mode may only be enabled when the test profile is active without prod");
        }
        if (testMode) {
            return;
        }
        validateCertificateUsableOrThrow();

        boolean departmentTableEmpty = departmentRepository.count() == 0;
        if (departmentTableEmpty) {
            if (!bootstrapProperties.isConfigured()) {
                throw new IllegalStateException(
                    "jingyi.icis.bootstrap must be provided when the departments table is empty");
            }
            validateBootstrapAgainstCertificate();
            return;
        }

        if (bootstrapProperties.isConfigured()) {
            validateBootstrapAgainstCertificate();
        }
        validateDatabaseDepartmentsAgainstCertificate(resolveActiveDepartments());
    }

    /** Validates the initialized database, RBAC mirror and current bed counts. */
    public void validateStartupOrThrow() {
        validatePreInitializationOrThrow();
        if (testMode) {
            return;
        }

        List<Department> departments = resolveActiveDepartments();
        validateDatabaseDepartmentsAgainstCertificate(departments);
        validateRbacDepartmentMirror(departments);
        for (Department department : departments) {
            if (!checkBedAvailable(department.getDeptId())) {
                throw new IllegalStateException(String.format(
                    "Bed count exceeds certificate limit: deptId=%s, deptName=%s",
                    department.getDeptId(),
                    department.getName()));
            }
        }
    }

    public Set<Integer> getMenuGroupIdList(String deptId) {
        if (testMode) {
            return Set.of(1, 15);
        }

        Department department = findLicensedDepartment(deptId);
        if (department == null) {
            return Set.of();
        }
        DepartmentLicencePB departmentLicence = departmentLicencePbMap.get(department.getName());
        if (departmentLicence == null) {
            return Set.of();
        }

        Set<Integer> menuGroupIds = new LinkedHashSet<>();
        menuGroupIds.addAll(hospitalLicensePb.getMenuGroupIdList());
        menuGroupIds.addAll(departmentLicence.getMenuGroupIdList());
        return menuGroupIds;
    }

    public Pair<Integer, Integer> getMaxBedCount(String deptId) {
        if (testMode) {
            return new Pair<>(TEST_FIXED_BED_LIMIT, TEST_TEMP_BED_LIMIT);
        }

        Department department = findLicensedDepartment(deptId);
        if (department == null) {
            return new Pair<>(0, 0);
        }
        DepartmentLicencePB departmentLicence = departmentLicencePbMap.get(department.getName());
        if (departmentLicence == null) {
            return new Pair<>(0, 0);
        }

        int fixedBedLimit = departmentLicence.getMaxBedCount() == 0
            ? hospitalLicensePb.getMaxBedCount()
            : departmentLicence.getMaxBedCount();
        return new Pair<>(fixedBedLimit, departmentLicence.getMaxTempBedCount());
    }

    public Boolean checkBedAvailable(String deptId) {
        if (testMode) {
            return true;
        }
        Pair<Integer, Integer> maxBedCount = getMaxBedCount(deptId);
        if (findLicensedDepartment(deptId) == null) {
            return false;
        }

        int currentFixedBedCount = safeCount(
            bedConfigRepository.countByDepartmentIdAndBedTypeAndIsDeletedFalse(
                deptId, fixedBedTypeId));
        int currentTempBedCount = safeCount(
            bedConfigRepository.countByDepartmentIdAndBedTypeAndIsDeletedFalse(
                deptId, tempBedTypeId));
        return checkBedCountWithinLimit(
            deptId, "Fixed", currentFixedBedCount, 0, maxBedCount.getFirst())
            && checkBedCountWithinLimit(
                deptId, "Temporary", currentTempBedCount, 0, maxBedCount.getSecond());
    }

    public Boolean checkBedAvailable(String deptId, Integer bedType, Integer additionalBedCount) {
        if (testMode) {
            return true;
        }
        if (additionalBedCount == null || additionalBedCount < 0) {
            log.error("Invalid additional bed count for department {}: {}", deptId, additionalBedCount);
            return false;
        }
        if (findLicensedDepartment(deptId) == null) {
            return false;
        }

        Pair<Integer, Integer> maxBedCount = getMaxBedCount(deptId);
        if (Objects.equals(fixedBedTypeId, bedType)) {
            int currentFixedBedCount = safeCount(
                bedConfigRepository.countByDepartmentIdAndBedTypeAndIsDeletedFalse(
                    deptId, fixedBedTypeId));
            return checkBedCountWithinLimit(
                deptId, "Fixed", currentFixedBedCount, additionalBedCount, maxBedCount.getFirst());
        }
        if (Objects.equals(tempBedTypeId, bedType)) {
            int currentTempBedCount = safeCount(
                bedConfigRepository.countByDepartmentIdAndBedTypeAndIsDeletedFalse(
                    deptId, tempBedTypeId));
            return checkBedCountWithinLimit(
                deptId, "Temporary", currentTempBedCount, additionalBedCount, maxBedCount.getSecond());
        }

        log.error("Unsupported bed type for department {}: {}", deptId, bedType);
        return false;
    }

    /** Production department scope is fixed by the certificate and cannot grow online. */
    public boolean isDepartmentCreationAllowed() {
        return testMode;
    }

    /** Only abbreviation and ward fields may change after production initialization. */
    public boolean isDepartmentIdentityUpdateAllowed(
        Integer departmentId,
        String deptCode,
        String departmentName,
        String hospitalName
    ) {
        if (testMode) {
            return true;
        }
        if (departmentId == null) {
            return false;
        }
        Department current = departmentRepository
            .findByIdAndIsDeletedFalse(departmentId)
            .orElse(null);
        return current != null
            && Objects.equals(current.getDeptId(), deptCode)
            && Objects.equals(current.getName(), departmentName)
            && Objects.equals(current.getHospitalName(), hospitalName);
    }

    /** Production department scope cannot shrink online. */
    public boolean isDepartmentDeletionAllowed() {
        return testMode;
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

    private void validateBootstrapAgainstCertificate() {
        String bootstrapHospitalName = bootstrapProperties.getHospitalName();
        requireTrimmed("jingyi.icis.bootstrap.hospital-name", bootstrapHospitalName);
        if (!bootstrapHospitalName.equals(hospitalLicensePb.getHospitalName())) {
            throw new IllegalStateException(String.format(
                "Bootstrap hospital does not match certificate hospital: bootstrap=%s, certificate=%s",
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

    private List<Department> resolveActiveDepartments() {
        List<Department> departments = departmentRepository.findByIsDeletedFalse();
        if (departments.isEmpty()) {
            throw new IllegalStateException(
                "ICIS departments table contains no active department after initialization");
        }
        return departments;
    }

    private void validateDatabaseDepartmentsAgainstCertificate(List<Department> departments) {
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
            if (!Objects.equals(department.getHospitalName(), hospitalLicensePb.getHospitalName())) {
                throw new IllegalStateException(String.format(
                    "ICIS department hospital does not match certificate: code=%s, department=%s, certificate=%s",
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

    private void validateRbacDepartmentMirror(List<Department> departments) {
        Map<String, RbacDepartment> rbacByCode = new LinkedHashMap<>();
        for (RbacDepartment rbacDepartment : rbacDepartmentRepository.findAll()) {
            rbacByCode.put(rbacDepartment.getDeptId(), rbacDepartment);
        }
        for (Department department : departments) {
            RbacDepartment rbacDepartment = rbacByCode.get(department.getDeptId());
            if (rbacDepartment == null
                || !Objects.equals(rbacDepartment.getDeptName(), department.getName())) {
                throw new IllegalStateException(String.format(
                    "rbac_departments is missing or inconsistent for ICIS department: code=%s, name=%s",
                    department.getDeptId(),
                    department.getName()));
            }
        }
    }

    private Department findLicensedDepartment(String deptId) {
        if (!isCertificateUsable() || StrUtils.isBlank(deptId)) {
            return null;
        }
        Department department = departmentRepository
            .findByDeptIdAndIsDeletedFalse(deptId)
            .orElse(null);
        if (department == null
            || !Objects.equals(department.getHospitalName(), hospitalLicensePb.getHospitalName())
            || !departmentLicencePbMap.containsKey(department.getName())) {
            return null;
        }
        return department;
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

    private boolean checkBedCountWithinLimit(
        String deptId,
        String bedType,
        Integer currentBedCount,
        Integer additionalBedCount,
        Integer maxBedCount
    ) {
        if (maxBedCount < currentBedCount + additionalBedCount) {
            log.error(
                "{} bed count exceeds the limit for department {}: current={}, additional={}, max={}",
                bedType,
                deptId,
                currentBedCount,
                additionalBedCount,
                maxBedCount);
            return false;
        }
        return true;
    }

    private Integer findBedTypeId(DeviceConfigPB deviceConfig, String expectedName) {
        EnumValue bedType = deviceConfig.getEnums().getBedTypeList().stream()
            .filter(value -> expectedName.equals(value.getName()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException(
                "Required bed type not found in config proto: " + expectedName));
        return bedType.getId();
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

    private int safeCount(Integer count) {
        return count == null ? 0 : count;
    }

    private static final int TEST_FIXED_BED_LIMIT = 3;
    private static final int TEST_TEMP_BED_LIMIT = 0;

    private final boolean testMode;
    private final boolean invalidTestModeRequested;
    private final Integer fixedBedTypeId;
    private final Integer tempBedTypeId;
    private final DepartmentRepository departmentRepository;
    private final RbacDepartmentRepository rbacDepartmentRepository;
    private final BedConfigRepository bedConfigRepository;
    private final IcisBootstrapProperties bootstrapProperties;
    private final Map<String, DepartmentLicencePB> departmentLicencePbMap;

    private String validationError;
    private HospitalLicensePB hospitalLicensePb;
    private LocalDateTime issueTime;
    private LocalDateTime expireTime;
}
