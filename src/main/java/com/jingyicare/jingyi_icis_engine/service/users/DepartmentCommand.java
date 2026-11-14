package com.jingyicare.jingyi_icis_engine.service.users;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.StatusCode;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisUser.DepartmentPB;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentAccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.DepartmentRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.service.monitorings.BgaMetaService;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
@Slf4j
public class DepartmentCommand {
    public DepartmentCommand(
        DepartmentRepository deptRepo,
        RbacDepartmentRepository rbacDeptRepo,
        RbacDepartmentAccountRepository rbacDeptAcctRepo,
        DepartmentAccountRepository deptAcctRepo,
        BgaMetaService bgaMetaService,
        LockExecutor lockExecutor
    ) {
        this.deptRepo = deptRepo;
        this.rbacDeptRepo = rbacDeptRepo;
        this.rbacDeptAcctRepo = rbacDeptAcctRepo;
        this.deptAcctRepo = deptAcctRepo;
        this.bgaMetaService = bgaMetaService;
        this.lockExecutor = lockExecutor;
    }

    // 目录锁保护名称唯一和至少保留一个科室；科室锁与账号归属操作互斥。
    public StatusCode addDepartment(DepartmentPB deptPb, String modifiedByAutoAcctId) {
        String deptId = deptPb.getDeptId();
        if (deptId.isBlank()) return StatusCode.DEPT_IS_EMPTY;

        StatusCode statusCode = lockExecutor.execute(
            List.of(LockKeys.departments(), LockKeys.department(deptId)), () -> {
                String deptName = deptPb.getDeptName();
                if (deptRepo.findByDeptIdAndIsDeletedFalse(deptId).isPresent()) {
                    log.error("Department id already exists: {}", deptId);
                    return StatusCode.DEPARTMENT_ALREADY_EXISTS;
                }
                if (deptRepo.findByNameAndIsDeletedFalse(deptName).isPresent()) {
                    log.error("Department name already exists: {}", deptName);
                    return StatusCode.DEPARTMENT_ALREADY_EXISTS;
                }
                if (rbacDeptRepo.findByDeptId(deptId).isPresent()) {
                    log.error("RBAC Department id already exists: {}", deptId);
                    return StatusCode.DEPARTMENT_ALREADY_EXISTS;
                }
                if (rbacDeptRepo.findByDeptName(deptName).isPresent()) {
                    log.error("RBAC Department name already exists: {}", deptName);
                    return StatusCode.DEPARTMENT_ALREADY_EXISTS;
                }

                Department dept = new Department();
                dept.setDeptId(deptId);
                dept.setName(deptName);
                dept.setAbbreviation(deptPb.getAbbreviation());
                dept.setWardCode(deptPb.getWardCode());
                dept.setWardName(deptPb.getWardName());
                dept.setHospitalName(deptPb.getHospitalName());
                dept.setIsDeleted(false);
                dept.setModifiedBy(modifiedByAutoAcctId);
                dept.setModifiedAt(TimeUtils.getNowUtc());
                dept = deptRepo.save(dept);
                rbacDeptRepo.save(new RbacDepartment(deptId, deptName));
                log.info("Department added: {}, by {}", dept, modifiedByAutoAcctId);
                return StatusCode.OK;
            });
        if (statusCode == StatusCode.OK) bgaMetaService.initBgaParams(deptId);
        return statusCode;
    }

    public StatusCode updateDepartment(
        Integer deptIntegerId, String deptId, String deptName,
        String abbreviation, String wardCode, String wardName,
        String hospitalName, String modifiedByAutoAcctId
    ) {
        if (deptIntegerId == null) return StatusCode.DEPARTMENT_NOT_FOUND;
        if (deptId == null || deptId.isBlank()) return StatusCode.DEPT_IS_EMPTY;

        return lockExecutor.execute(
            List.of(LockKeys.departments(), LockKeys.department(deptId)), () -> {
                Department dept = deptRepo.findByIdAndIsDeletedFalse(deptIntegerId).orElse(null);
                if (dept == null) return StatusCode.DEPARTMENT_NOT_FOUND;
                if (!dept.getDeptId().equals(deptId)) return StatusCode.INVALID_DEPARTMENT_ID;

                // 所有检查通过后再修改实体，避免失败响应提交部分资料。
                Department sameNameDept = deptRepo
                    .findByNameAndIsDeletedFalse(deptName).orElse(null);
                if (sameNameDept != null && !sameNameDept.getId().equals(deptIntegerId)) {
                    return StatusCode.DEPARTMENT_ALREADY_EXISTS;
                }
                RbacDepartment sameNameRbacDept = rbacDeptRepo
                    .findByDeptName(deptName).orElse(null);
                if (sameNameRbacDept != null && !sameNameRbacDept.getDeptId().equals(deptId)) {
                    return StatusCode.DEPARTMENT_ALREADY_EXISTS;
                }

                dept.setName(deptName);
                dept.setAbbreviation(abbreviation);
                dept.setWardCode(wardCode);
                dept.setWardName(wardName);
                dept.setHospitalName(hospitalName);
                dept.setModifiedBy(modifiedByAutoAcctId);
                dept.setModifiedAt(TimeUtils.getNowUtc());
                deptRepo.save(dept);

                RbacDepartment rbacDept = rbacDeptRepo.findByDeptId(deptId)
                    .orElseGet(() -> new RbacDepartment(deptId, deptName));
                rbacDept.setDeptName(deptName);
                rbacDeptRepo.save(rbacDept);
                log.info("Department updated: {}, by {}", dept, modifiedByAutoAcctId);
                return StatusCode.OK;
            });
    }

    public StatusCode deleteDepartment(String deptId, String modifiedByAutoAcctId) {
        if (deptId == null || deptId.isBlank()) return StatusCode.DEPARTMENT_NOT_FOUND;

        return lockExecutor.execute(
            List.of(LockKeys.departments(), LockKeys.department(deptId)), () -> {
                Department dept = deptRepo.findByDeptIdAndIsDeletedFalse(deptId).orElse(null);
                if (dept == null) return StatusCode.DEPARTMENT_NOT_FOUND;
                if (rbacDeptAcctRepo.existsByIdDeptId(deptId)
                    || deptAcctRepo.existsByDeptIdAndIsDeletedFalse(deptId)) {
                    return StatusCode.DEPARTMENT_HAS_ACCOUNTS;
                }
                if (deptRepo.countByIsDeletedFalse() <= 1) {
                    return StatusCode.DEPARTMENT_AT_LEAST_ONE;
                }

                dept.setIsDeleted(true);
                dept.setDeletedBy(modifiedByAutoAcctId);
                dept.setDeletedAt(TimeUtils.getNowUtc());
                deptRepo.save(dept);
                rbacDeptRepo.deleteById(deptId);
                log.info("Department deleted: {}, by {}", dept, modifiedByAutoAcctId);
                return StatusCode.OK;
            });
    }

    private final DepartmentRepository deptRepo;
    private final RbacDepartmentRepository rbacDeptRepo;
    private final RbacDepartmentAccountRepository rbacDeptAcctRepo;
    private final DepartmentAccountRepository deptAcctRepo;
    private final BgaMetaService bgaMetaService;
    private final LockExecutor lockExecutor;
}
