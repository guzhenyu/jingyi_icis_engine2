package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.time.LocalDateTime;
import java.util.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.DeviceDataHourlyApprox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface DeviceDataHourlyApproxRepository extends JpaRepository<DeviceDataHourlyApprox, Long> {
    List<DeviceDataHourlyApprox> findAll();

    List<DeviceDataHourlyApprox> findByDeviceId(Integer deviceId);

    List<DeviceDataHourlyApprox> findByRecordedAtBetween(LocalDateTime start, LocalDateTime end);

    List<DeviceDataHourlyApprox> findByDeviceBedNumberAndRecordedAtBetweenAndParamCodeIn(
        String bedNumber, LocalDateTime start, LocalDateTime end, List<String> paramCodes
    );

    List<DeviceDataHourlyApprox> findByDeviceIdAndRecordedAtBetweenAndParamCodeIn(
        Integer deviceId, LocalDateTime start, LocalDateTime end, List<String> paramCodes
    );

    @Query("SELECT d FROM DeviceDataHourlyApprox d WHERE d.deviceBedNumber = :bedNumber " +
        "AND d.recordedAt >= :start AND d.recordedAt < :end " +
        "AND d.paramCode IN :paramCodes")
    List<DeviceDataHourlyApprox> findByBedNumberAndRecordedAtAndParamCodeIn(
        @Param("bedNumber") String bedNumber, 
        @Param("start") LocalDateTime start, 
        @Param("end") LocalDateTime end, 
        @Param("paramCodes") List<String> paramCodes
    );

    @Query("SELECT d FROM DeviceDataHourlyApprox d WHERE d.deviceId = :deviceId " +
        "AND d.recordedAt >= :start AND d.recordedAt < :end " +
        "AND d.paramCode IN :paramCodes")
    List<DeviceDataHourlyApprox> findByDeviceIdAndRecordedAtAndParamCodeIn(
        @Param("deviceId") Integer deviceId, 
        @Param("start") LocalDateTime start, 
        @Param("end") LocalDateTime end, 
        @Param("paramCodes") List<String> paramCodes
    );
}