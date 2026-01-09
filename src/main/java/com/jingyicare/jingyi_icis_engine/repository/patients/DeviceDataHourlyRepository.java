package com.jingyicare.jingyi_icis_engine.repository.patients;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceDataHourly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface DeviceDataHourlyRepository extends JpaRepository<DeviceDataHourly, Long> {

    List<DeviceDataHourly> findAll();

    List<DeviceDataHourly> findByDeviceId(Integer deviceId);

    List<DeviceDataHourly> findByRecordedAtBetween(LocalDateTime start, LocalDateTime end);

    List<DeviceDataHourly> findByDeviceBedNumberAndRecordedAtBetween(String bedNumber, LocalDateTime start, LocalDateTime end);

    List<DeviceDataHourly> findByDeviceIdAndRecordedAtBetween(Integer deviceId, LocalDateTime start, LocalDateTime end);

    @Query("SELECT d FROM DeviceDataHourly d WHERE d.deviceBedNumber = :bedNumber " +
        "AND d.recordedAt >= :start AND d.recordedAt < :end")
    List<DeviceDataHourly> findByBedNumberAndRecordedAt(
        @Param("bedNumber") String bedNumber, 
        @Param("start") LocalDateTime start, 
        @Param("end") LocalDateTime end
    );

    @Query("SELECT d FROM DeviceDataHourly d WHERE d.deviceId = :deviceId " +
        "AND d.recordedAt >= :start AND d.recordedAt < :end")
    List<DeviceDataHourly> findByDeviceIdAndRecordedAt(
        @Param("deviceId") Integer deviceId, 
        @Param("start") LocalDateTime start, 
        @Param("end") LocalDateTime end
    );
}