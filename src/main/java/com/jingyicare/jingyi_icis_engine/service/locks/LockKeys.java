package com.jingyicare.jingyi_icis_engine.service.locks;

import java.time.LocalDate;
import java.time.LocalDateTime;

import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

public final class LockKeys {
    private static final String NURSING_RECORD_TEMPLATE_GROUPS = "nursing_record_template_groups";
    private static final String NURSING_RECORD_TEMPLATES = "nursing_record_templates";

    private LockKeys() {}

    public static String department(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        return "department:" + deptId;
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

    // 协调科室评分组的新增、删除及显示顺序调整。
    public static String deptScoreGroup(String deptId) {
        StrUtils.requireNonBlank(deptId, "deptId");
        return "dept_score_groups:" + deptId;
    }

    public static String deptScoreGroup(String deptId, String scoreGroupCode) {
        StrUtils.requireNonBlank(scoreGroupCode, "scoreGroupCode");
        return deptScoreGroup(deptId) + ":" + scoreGroupCode;
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
}
