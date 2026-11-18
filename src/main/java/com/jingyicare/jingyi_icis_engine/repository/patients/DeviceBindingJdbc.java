package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.HashSet;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DeviceBindingJdbc {
    private DeviceBindingJdbc() {}

    // 预读与锁后复核共用此查询，避免 JPA 一级缓存影响锁计划。
    public static Set<Long> findActivePatientIdsByDeviceId(
        JdbcTemplate jdbcTemplate, int deviceId
    ) {
        return new HashSet<>(jdbcTemplate.queryForList("""
            SELECT DISTINCT patient_id
            FROM patient_devices
            WHERE device_id = ? AND unbinding_time IS NULL AND is_deleted = FALSE
            """, Long.class, deviceId));
    }
}
