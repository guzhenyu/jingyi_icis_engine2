package com.jingyicare.jingyi_icis_engine.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.DepartmentPB;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

/**
 * Production empty-database seed supplied by deployment-specific Spring configuration.
 * The release builder owns these values; the engine validates them against the licence
 * before any initializer is allowed to write to the database.
 */
@Component
@ConfigurationProperties(prefix = "jingyi.icis.bootstrap")
public class IcisBootstrapProperties {
    public String getHospitalName() {
        return hospitalName;
    }

    public void setHospitalName(String hospitalName) {
        this.hospitalName = hospitalName;
        // 属性绑定顺序不固定，医院名称变化时同步派生的科室配置。
        updateDeptPbList();
    }

    public List<DepartmentEntry> getDepartments() {
        return departments;
    }

    /**
     * jingyi.icis.bootstrap.departments[0].dept-code=ICU01
     * jingyi.icis.bootstrap.departments[0].name=重症医学科
     * ...
     */
    public void setDepartments(List<DepartmentEntry> departments) {
        this.departments = departments == null ? new ArrayList<>() : new ArrayList<>(departments);
        updateDeptPbList();
    }

    public List<DepartmentPB> getDeptPbList() {
        return deptPbList;
    }

    private void updateDeptPbList() {
        deptPbList = departments.stream().map(this::toDepartmentPb).toList();
    }

    private DepartmentPB toDepartmentPb(DepartmentEntry entry) {
        String deptName = StrUtils.getStringOrDefault(entry.getName(), "").trim();
        // 沿用初始化规则：简称取科室名称，病区信息为空；原始字段仍供许可证校验使用。
        return DepartmentPB.newBuilder()
            .setDeptId(StrUtils.getStringOrDefault(entry.getDeptCode(), "").trim())
            .setDeptName(deptName)
            .setAbbreviation(deptName)
            .setHospitalName(StrUtils.getStringOrDefault(hospitalName, "").trim())
            .build();
    }

    public boolean isConfigured() {
        return (hospitalName != null && !hospitalName.isBlank()) || !departments.isEmpty();
    }

    public static class DepartmentEntry {
        public String getDeptCode() {
            return deptCode;
        }

        public void setDeptCode(String deptCode) {
            this.deptCode = deptCode;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getAbbreviation() {
            return abbreviation;
        }

        public void setAbbreviation(String abbreviation) {
            this.abbreviation = abbreviation;
        }

        public String getWardCode() {
            return wardCode;
        }

        public void setWardCode(String wardCode) {
            this.wardCode = wardCode;
        }

        public String getWardName() {
            return wardName;
        }

        public void setWardName(String wardName) {
            this.wardName = wardName;
        }

        private String deptCode;
        private String name;
        private String abbreviation;
        private String wardCode;
        private String wardName;
    }

    private String hospitalName;
    private List<DepartmentEntry> departments = new ArrayList<>();
    private List<DepartmentPB> deptPbList = new ArrayList<>();
}
