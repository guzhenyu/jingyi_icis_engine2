package com.jingyicare.jingyi_icis_engine.service.locks;

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
}
