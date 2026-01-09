package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.List;
import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceData;

public interface DeviceDataRepository extends JpaRepository<DeviceData, Long> {
    List<DeviceData> findAll();

    List<DeviceData> findByDeviceId(Integer deviceId);

    List<DeviceData> findByRecordedAtBetween(LocalDateTime start, LocalDateTime end);

    List<DeviceData> findByRecordedAtIn(List<LocalDateTime> recordedAtList);

    @Query("SELECT d FROM DeviceData d WHERE d.deviceBedNumber = :bedNumber " +
        "AND d.recordedAt IN :recordedTimes")
    List<DeviceData> findByBedNumberAndRecordedAtIn(
        @Param("bedNumber") String bedNumber, 
        @Param("recordedTimes") List<LocalDateTime> recordedTimes
    );

    @Query("SELECT d FROM DeviceData d WHERE d.deviceId = :deviceId " +
        "AND d.recordedAt IN :recordedTimes")
    List<DeviceData> findByDeviceIdAndRecordedAtIn(
        @Param("deviceId") Integer deviceId, 
        @Param("recordedTimes") List<LocalDateTime> recordedTimes
    );
}
