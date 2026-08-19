package com.jingyicare.jingyi_icis_engine.service;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

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
    }

    public List<DepartmentEntry> getDepartments() {
        return departments;
    }

    public void setDepartments(List<DepartmentEntry> departments) {
        this.departments = departments == null ? new ArrayList<>() : new ArrayList<>(departments);
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
}
