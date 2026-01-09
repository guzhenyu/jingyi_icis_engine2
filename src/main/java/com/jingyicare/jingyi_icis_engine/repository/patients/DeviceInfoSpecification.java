package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.domain.Specification;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceInfo;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class DeviceInfoSpecification {
    public static Specification<DeviceInfo> hasDepartmentId(String deptId) {
        return (root, query, criteriaBuilder) -> StrUtils.isBlank(deptId) ?
            criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("departmentId"), deptId);
    }

    public static Specification<DeviceInfo> hasDeviceId(Integer devId) {
        return (root, query, criteriaBuilder) -> (devId == null || devId <= 0) ?
            criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("id"), devId);
    }

    public static Specification<DeviceInfo> hasDeviceBedNumer(String devBedNum) {
        return (root, query, criteriaBuilder) -> StrUtils.isBlank(devBedNum) ?
            criteriaBuilder.conjunction() : criteriaBuilder.like(root.get("deviceBedNumber"), "%" + devBedNum + "%");
    }

    public static Specification<DeviceInfo> hasDeviceType(Integer devType) {
        return (root, query, criteriaBuilder) -> (devType == null || devType <= 0) ?
            criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("deviceType"), devType);
    }

    public static Specification<DeviceInfo> hasDeviceName(String devName) {
        return (root, query, criteriaBuilder) -> StrUtils.isBlank(devName) ?
            criteriaBuilder.conjunction() : criteriaBuilder.like(root.get("deviceName"), "%" + devName + "%");
    }

    public static Specification<DeviceInfo> hasDeviceIdIn(List<Integer> devIds) {
        return (root, query, criteriaBuilder) -> root.get("id").in(devIds);
    }

    public static Specification<DeviceInfo> isDeleted(Boolean isDeleted) {
        return (root, query, criteriaBuilder) -> isDeleted == null ?
            criteriaBuilder.conjunction() : criteriaBuilder.equal(root.get("isDeleted"), isDeleted);
    }
}