package com.jingyicare.jingyi_icis_engine.service.certs;

import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_FIXED;
import static com.jingyicare.jingyi_icis_engine.service.patients.PatientEnums.BED_TYPE_TEMP;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.service.IcisBootstrapProperties;
import com.jingyicare.jingyi_icis_engine.service.certs.CertificateReader.BedLimits;
import com.jingyicare.jingyi_icis_engine.service.patients.bed.BedMetaReader;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

/** 启动时校验科室授权范围、RBAC 镜像和床位数，运行期间执行授权限制。 */
@Service
@Slf4j
public class CertificateService {
    CertificateService(
        CertificateReader certificate,
        UserQuery userQuery,
        IcisBootstrapProperties bootstrapProperties,
        BedMetaReader bedMetaReader
    ) {
        this.certificate = certificate;
        this.userQuery = userQuery;
        this.bootstrapProperties = bootstrapProperties;
        this.bedMetaReader = bedMetaReader;
    }

    public void validatePreInitializationOrThrow() {
        certificate.validateForStartupOrThrow();
        if (certificate.isTestMode()) return;

        boolean departmentTableEmpty = userQuery.getAllDeptInfo().isEmpty();
        if (departmentTableEmpty) {
            if (!bootstrapProperties.isConfigured()) {
                throw new IllegalStateException(
                    "jingyi.icis.bootstrap must be provided when the departments table is empty");
            }
            certificate.validateBootstrapAgainstCertificate(bootstrapProperties);
            return;
        }

        if (bootstrapProperties.isConfigured()) {
            certificate.validateBootstrapAgainstCertificate(bootstrapProperties);
        }
        certificate.validateDatabaseDepartmentsAgainstCertificate(resolveActiveDepartments());
    }

    /** 初始化后检查科室、RBAC 镜像与实际床位数。 */
    public void validateStartupOrThrow() {
        validatePreInitializationOrThrow();
        if (certificate.isTestMode()) {
            return;
        }

        List<Department> departments = resolveActiveDepartments();
        certificate.validateDatabaseDepartmentsAgainstCertificate(departments);
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
        return certificate.getMenuGroupIdList(deptId);
    }

    public Pair<Integer, Integer> getMaxBedCount(String deptId) {
        return certificate.findBedLimits(deptId)
            .map(limits -> new Pair<>(limits.maxFixedBeds(), limits.maxTempBeds()))
            .orElseGet(() -> new Pair<>(0, 0));
    }

    public Boolean checkBedAvailable(String deptId) {
        return bedMetaReader.checkBedAvailable(deptId);
    }

    public Boolean checkBedAvailable(String deptId, Integer bedType, Integer additionalBedCount) {
        if (certificate.isTestMode()) return true;
        if (additionalBedCount == null || additionalBedCount < 0) {
            log.error("Invalid additional bed count for department {}: {}",
                deptId, additionalBedCount);
            return false;
        }

        BedLimits limits = certificate.findBedLimits(deptId).orElse(null);
        if (limits == null) return false;
        int maxBeds;
        if (Objects.equals(bedType, BED_TYPE_FIXED)) {
            maxBeds = limits.maxFixedBeds();
        } else if (Objects.equals(bedType, BED_TYPE_TEMP)) {
            maxBeds = limits.maxTempBeds();
        } else {
            log.error("Unsupported bed type for department {}: {}", deptId, bedType);
            return false;
        }

        int current = bedMetaReader.countActiveBedsByType(deptId, bedType);
        if ((long) current + additionalBedCount > maxBeds) {
            log.error("Bed count exceeds limit for department {} and type {}: "
                + "current={}, additional={}, max={}", deptId, bedType, current,
                additionalBedCount, maxBeds);
            return false;
        }
        return true;
    }

    /** 生产环境的科室范围由证书确定。 */
    public boolean isDepartmentCreationAllowed() {
        return certificate.isTestMode();
    }

    /** 生产环境中仅允许修改科室简称及病区字段。 */
    public boolean isDepartmentIdentityUpdateAllowed(
        Integer id,
        String deptCode,
        String departmentName,
        String hospitalName
    ) {
        if (certificate.isTestMode()) {
            return true;
        }
        if (id == null) {
            return false;
        }
        Department current = userQuery.getAllDeptInfo().stream()
            .filter(dept -> Objects.equals(dept.getId(), id))
            .findFirst().orElse(null);
        return current != null
            && Objects.equals(current.getDeptId(), deptCode)
            && Objects.equals(current.getName(), departmentName)
            && Objects.equals(current.getHospitalName(), hospitalName);
    }

    /** 生产环境的授权科室须一直保留。 */
    public boolean isDepartmentDeletionAllowed() {
        return certificate.isTestMode();
    }

    private List<Department> resolveActiveDepartments() {
        List<Department> departments = userQuery.getAllDeptInfo();
        if (departments.isEmpty()) {
            throw new IllegalStateException(
                "ICIS departments table contains no active department after initialization");
        }
        return departments;
    }

    private void validateRbacDepartmentMirror(List<Department> departments) {
        Map<String, String> idNameMapFromDepartments = userQuery.getAllDeptInfo()
            .stream().collect(
                LinkedHashMap::new,
                (map, dept) -> map.put(dept.getDeptId(), dept.getName()),
                Map::putAll
            );
        Map<String, String> idNameMapFromRbac = userQuery.getAllRbacDeptInfo()
            .stream().collect(
                LinkedHashMap::new,
                (map, rbacDept) -> map.put(rbacDept.getDeptId(), rbacDept.getDeptName()),
                Map::putAll
            );
        if (!idNameMapFromDepartments.equals(idNameMapFromRbac)) {
            throw new IllegalStateException("RBAC mirror of departments is inconsistent with actual departments");
        }
    }

    private final CertificateReader certificate;
    private final UserQuery userQuery;
    private final IcisBootstrapProperties bootstrapProperties;
    private final BedMetaReader bedMetaReader;
}
