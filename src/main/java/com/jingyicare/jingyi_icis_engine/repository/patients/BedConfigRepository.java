package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.patients.BedConfig;

public interface BedConfigRepository extends JpaRepository<BedConfig, Long> {
    List<BedConfig> findAll();

    List<BedConfig> findAllByIsDeletedFalse();

    List<BedConfig> findByDepartmentIdAndIsDeletedFalse(String departmentId);

    List<BedConfig> findByHisBedNumberInAndIsDeletedFalse(List<String> hisBedNumbers);

    Optional<BedConfig> findByIdAndIsDeletedFalse(Long id);

    Optional<BedConfig> findByDepartmentIdAndHisBedNumberAndIsDeletedFalse(String departmentId, String hisBedNumber);
    Optional<BedConfig> findByDepartmentIdAndDisplayBedNumberAndIsDeletedFalse(String departmentId, String displayBedNumber);
    Optional<BedConfig> findByDepartmentIdAndDeviceBedNumberAndIsDeletedFalse(String departmentId, String deviceBedNumber);

    Integer countByDepartmentIdAndBedTypeAndIsDeletedFalse(String deptId, Integer bedType);
}