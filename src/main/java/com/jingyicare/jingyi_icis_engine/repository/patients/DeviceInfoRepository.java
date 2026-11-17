package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceInfo;

public interface DeviceInfoRepository
    extends JpaRepository<DeviceInfo, Integer>, JpaSpecificationExecutor<DeviceInfo>
{
    List<DeviceInfo> findByDepartmentIdAndDeviceBedNumberAndIsDeletedFalse(
        String departmentId, String deviceBedNumber);
    boolean existsByDeviceIpAndIsDeletedFalse(String deviceIp);
    boolean existsByDeviceIpAndIsDeletedFalseAndIdNot(String deviceIp, Integer id);
    Optional<DeviceInfo> findByDepartmentIdAndDeviceNameAndIsDeletedFalse(
        String departmentId, String deviceName);
}
