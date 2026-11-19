package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientDevice;

public interface PatientDeviceRepository extends JpaRepository<PatientDevice, Long> {
    List<PatientDevice> findAll();

    List<PatientDevice> findAllByIsDeletedFalse();

    List<PatientDevice> findAllByIsDeletedFalseAndUnbindingTimeIsNull();

    boolean existsByPatientId(Long patientId);

    List<PatientDevice> findByDeviceIdInAndUnbindingTimeIsNullAndIsDeletedFalse(
        Collection<Integer> deviceIds);

    // 当前设备沿用旧数据中 is_deleted 为 NULL 时视为未删除的约定。
    @Query("""
        SELECT COUNT(binding) > 0
        FROM PatientDevice binding, DeviceInfo device
        WHERE binding.deviceId = device.id AND binding.patientId = :patientId
            AND binding.unbindingTime IS NULL AND binding.bindingTime < :maxBindingTime
            AND (binding.isDeleted = FALSE OR binding.isDeleted IS NULL)
            AND (device.isDeleted = FALSE OR device.isDeleted IS NULL)
            AND (:deviceType IS NULL OR :deviceType <= 0 OR device.deviceType = :deviceType)
        """)
    boolean existsCurrentDeviceOfType(
        @Param("patientId") Long patientId,
        @Param("deviceType") Integer deviceType,
        @Param("maxBindingTime") LocalDateTime maxBindingTime);

    // 解绑历史保留已软删除设备的类型信息。
    @Query("""
        SELECT MAX(binding.unbindingTime)
        FROM PatientDevice binding, DeviceInfo device
        WHERE binding.deviceId = device.id AND binding.patientId = :patientId
            AND binding.isDeleted = FALSE AND binding.unbindingTime IS NOT NULL
            AND (:deviceType IS NULL OR :deviceType <= 0 OR device.deviceType = :deviceType)
        """)
    LocalDateTime findLatestUnbindingTime(
        @Param("patientId") Long patientId, @Param("deviceType") Integer deviceType);

    List<PatientDevice>
        findByPatientIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
        Long patientId,
        LocalDateTime start,
        LocalDateTime end
    );

    List<PatientDevice> findByPatientIdAndUnbindingTimeIsNullAndBindingTimeBefore(
        Long patientId,
        LocalDateTime end
    );

    List<PatientDevice>
        findByDeviceIdAndUnbindingTimeIsNotNullAndUnbindingTimeAfterAndBindingTimeBefore(
        Integer deviceId,
        LocalDateTime start,
        LocalDateTime end
    );

    List<PatientDevice> findByDeviceIdAndUnbindingTimeIsNullAndBindingTimeBefore(
        Integer deviceId,
        LocalDateTime end
    );

    List<PatientDevice> findByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(Integer deviceId);

    boolean existsByDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(Integer deviceId);

    List<PatientDevice> findByPatientIdAndUnbindingTimeIsNotNullAndIsDeletedFalse(Long patientId);

    List<PatientDevice> findByPatientIdAndUnbindingTimeNullAndIsDeletedFalse(Long patientId);

    Optional<PatientDevice> findByPatientIdAndDeviceIdAndBindingTimeAndIsDeletedFalse(
            Long patientId,
            Integer deviceId,
            LocalDateTime bindingTime
    );

    Optional<PatientDevice> findByPatientIdAndDeviceIdAndUnbindingTimeIsNullAndIsDeletedFalse(
            Long patientId,
            Integer deviceId
    );
}
