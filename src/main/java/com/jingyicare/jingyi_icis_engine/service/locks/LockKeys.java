package com.jingyicare.jingyi_icis_engine.service.locks;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

import com.jingyicare.jingyi_icis_engine.utils.StrUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public final class LockKeys {
    private LockKeys() {}

    public static String account(String rbacAcctId) {
        StrUtils.requireNonBlank(rbacAcctId, "rbacAcctId");
        return "account:" + rbacAcctId;
    }

    public static String role(int roleId) {
        if (roleId <= 0) {
            throw new IllegalArgumentException("roleId must be positive");
        }
        return "role:" + roleId;
    }

    public static String departments() {
        return "departments";
    }

    public static String department(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        return "department:" + deptId;
    }

    public static String bedConfig(String deptId) {
        Objects.requireNonNull(deptId, "deptId");
        return truncateResourceKey("bed_configs:" + deptId);
    }

    public static String BedCount(String deptId, LocalDateTime effectiveTimeUtc) {
        Objects.requireNonNull(deptId, "deptId");
        Objects.requireNonNull(effectiveTimeUtc, "effectiveTimeUtc");
        return truncateResourceKey("bed_counts:" + deptId + ":"
            + TimeUtils.roundToMicros(effectiveTimeUtc));
    }

    public static String deviceInfo(String deptId, String deviceName) {
        Objects.requireNonNull(deptId, "deptId");
        Objects.requireNonNull(deviceName, "deviceName");
        // 转义科室中的分隔符，最后一段设备名可以原样保留。
        String deptKey = deptId.replace("%", "%25").replace(":", "%3A");
        return truncateResourceKey("device_infos:" + deptKey + ":" + deviceName);
    }

    public static String deviceIp(String deviceIp) {
        if (deviceIp == null || deviceIp.isEmpty()) {
            throw new IllegalArgumentException("deviceIp must not be empty");
        }
        return truncateResourceKey("device_ip:" + deviceIp);
    }

    public static String deviceBinding(int deviceId) {
        return "device_binding:" + deviceId;
    }

    public static String patientDeviceBindings(long patientId) {
        return "patient_device_bindings:" + patientId;
    }

    public static String patientSync() {
        return "patient_sync";
    }

    // 锁表的 resource_key 为 VARCHAR(255)，完整拼接后按 Unicode 码点截取前 255 个字符。
    private static String truncateResourceKey(String key) {
        if (key.codePointCount(0, key.length()) <= MAX_RESOURCE_KEY_LENGTH) {
            return key;
        }
        return key.substring(0, key.offsetByCodePoints(0, MAX_RESOURCE_KEY_LENGTH));
    }

    public static String deptSystemSettings(String deptId, int functionId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        if (functionId <= 0) {
            throw new IllegalArgumentException("functionId must be positive");
        }
        return "dept_system_settings:" + deptId + ":" + functionId;
    }

    public static String systemSettings(int functionId) {
        if (functionId <= 0) {
            throw new IllegalArgumentException("functionId must be positive");
        }
        return "system_settings:" + functionId;
    }

    /**
     * 观察项，血气
     */
    public static String monitoringParam() {
        return "monitoring_params";
    }

    // 同一科室的分组和参数配置共用此锁，覆盖 dept_monitoring_groups、
    // dept_monitoring_group_params 和 dept_monitoring_params。
    public static String deptMonitoringGroups(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        return "dept_monitoring_groups:" + deptId;
    }

    // 同一患者的监测分组及组内参数元数据共用此锁。
    public static String patientMonitoringGroups(long pid) {
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        return "patient_monitoring_groups:" + pid;
    }

    public static String bgaParam(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        return "bga_params:" + deptId;
    }

    public static String patientMonitoringRecord(
        long pid, String code, LocalDateTime effectiveTimeUtc
    ) {
        StrUtils.requireNonBlank(code, "monitoringParamCode");
        return patientTimedResource("patient_monitoring_record", pid, code, effectiveTimeUtc);
    }

    /**
     * 护理评分
     */
    public static String deptScoreGroup(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        return "dept_score_groups:" + deptId;
    }

    public static String deptScoreGroup(String deptId, String scoreGroupCode) {
        StrUtils.requireNonBlank(scoreGroupCode, "scoreGroupCode");
        return deptScoreGroup(deptId) + ":" + scoreGroupCode;
    }

    public static String patientScore(long pid, String code, LocalDateTime effectiveTimeUtc) {
        StrUtils.requireNonBlank(code, "scoreGroupCode");
        return patientTimedResource("patient_score", pid, code, effectiveTimeUtc);
    }

    /**
     * 护理记录
     */

    // type == 0: 个人模板, entityId 为个人 ID; type == 1: 科室模板, entityId 为deptId(dept_code);
    public static String nursingRecordTemplateGroup(
        int entityType, String entityId, String groupName
    ) {
        if (entityType != 0 && entityType != 1) {
            throw new IllegalArgumentException("entityType must be 0 (account) or 1 (department)");
        }
        StrUtils.requireNonBlank(entityId, "entityId");
        StrUtils.requireNonBlank(groupName, "groupName");

        return NURSING_RECORD_TEMPLATE_GROUPS + ":" + entityType + ":"
            + entityId + ":" + groupName;
    }

    public static String nursingRecordTemplate(
        int entityType, String entityId, String templateName
    ) {
        if (entityType != 0 && entityType != 1) {
            throw new IllegalArgumentException("entityType must be 0 (account) or 1 (department)");
        }
        StrUtils.requireNonBlank(entityId, "entityId");
        StrUtils.requireNonBlank(templateName, "templateName");

        return NURSING_RECORD_TEMPLATES + ":" + entityType + ":" + entityId + ":" + templateName;
    }

    public static String nursingRecord(long pid, LocalDateTime effectiveTimeUtc) {
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        if (effectiveTimeUtc == null) {
            throw new IllegalArgumentException("effectiveTimeUtc must not be null");
        }
        return "nursing_record:" + pid + ":" + effectiveTimeUtc;
    }

    /**
     * 患者快照
     */

    public static String patientSnapshot(long pid, LocalDate statisticsDate) {
        if (pid <= 0) {
            throw new IllegalArgumentException("pid must be positive");
        }
        if (statisticsDate == null) {
            throw new IllegalArgumentException("statisticsDate must not be null");
        }
        return "patient_snapshot:" + pid + ":" + statisticsDate;
    }

    private static String patientTimedResource(
        String resource, long pid, String code, LocalDateTime effectiveTimeUtc
    ) {
        if (pid <= 0 || effectiveTimeUtc == null) {
            throw new IllegalArgumentException("Patient and effective time are required");
        }
        return resource + ":" + pid + ":" + code + ":"
            + TimeUtils.roundToMicros(effectiveTimeUtc);
    }

    private static final int MAX_RESOURCE_KEY_LENGTH = 255;
    private static final String NURSING_RECORD_TEMPLATE_GROUPS = "nursing_record_template_groups";
    private static final String NURSING_RECORD_TEMPLATES = "nursing_record_templates";
}
