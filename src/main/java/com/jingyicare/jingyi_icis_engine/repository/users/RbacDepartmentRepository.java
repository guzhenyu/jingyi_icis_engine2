package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;

public interface RbacDepartmentRepository extends JpaRepository<RbacDepartment, String> {
    List<RbacDepartment> findAll();
    List<RbacDepartment> findByDeptIdIn(List<String> deptIds);
    Optional<RbacDepartment> findByDeptId(String deptId);
    Optional<RbacDepartment> findByDeptName(String deptName);
    Boolean existsByDeptId(String deptId);
}