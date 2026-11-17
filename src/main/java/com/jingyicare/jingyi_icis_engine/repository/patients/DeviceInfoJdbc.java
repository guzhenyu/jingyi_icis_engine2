package com.jingyicare.jingyi_icis_engine.repository.patients;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;

public final class DeviceInfoJdbc {
    private DeviceInfoJdbc() {}

    public record DeviceKey(String deptId, String deviceName) {}

    // 在调用方事务中锁定设备行，包含已软删除的设备；没有对应行时返回 false。
    public static boolean lockById(JdbcTemplate jdbcTemplate, int deviceId) {
        return !jdbcTemplate.queryForList(
            "SELECT id FROM device_infos WHERE id = ? FOR UPDATE", Integer.class, deviceId
        ).isEmpty();
    }

    // 预读和锁后复查共用同一查询，可同时定位当前设备和上游设备的科室、名称。
    public static Map<Integer, DeviceKey> findActiveKeys(
        JdbcTemplate jdbcTemplate, Integer deviceId, Integer upstreamId
    ) {
        Map<Integer, DeviceKey> keys = new HashMap<>();
        jdbcTemplate.query("""
            SELECT id, department_id, device_name
            FROM device_infos
            WHERE is_deleted = FALSE AND id IN (?, ?)
            """, rs -> {
                keys.put(rs.getInt("id"),
                    new DeviceKey(rs.getString("department_id"), rs.getString("device_name")));
            }, deviceId, upstreamId);
        return keys;
    }
}
