package com.jingyicare.jingyi_icis_engine.service.locks;

import java.util.Objects;

import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

public final class LockKeys {
    private static final String NURSING_RECORD_TEMPLATE_GROUPS = "nursing_record_template_groups";

    private LockKeys() {}

    // type == 0: 个人模板, entityId 为个人 ID; type == 1: 科室模板, entityId 为deptId(dept_code);
    public static String nursingRecordTemplateGroup(int entityType, String entityId, String groupName) {
        if (entityType != 0 && entityType != 1) {
            throw new IllegalArgumentException("entityType must be 0 (account) or 1 (department)");
        }
        StrUtils.requireNonBlank(entityId, "entityId");
        StrUtils.requireNonBlank(groupName, "groupName");

        return NURSING_RECORD_TEMPLATE_GROUPS + ":" + entityType + ":"
            + entityId + ":" + groupName;
    }
}
