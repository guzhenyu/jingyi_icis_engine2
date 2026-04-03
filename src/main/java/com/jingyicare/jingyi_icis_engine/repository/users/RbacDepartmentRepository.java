package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;

public interface RbacDepartmentRepository extends JpaRepository<RbacDepartment, String> {
    List<RbacDepartment> findAll();
    List<RbacDepartment> findByDeptIdIn(List<String> deptIds);
    Optional<RbacDepartment> findByDeptId(String deptId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT dept FROM RbacDepartment dept WHERE dept.deptId = :deptId")
    Optional<RbacDepartment> findByDeptIdForUpdate(@Param("deptId") String deptId);
    Optional<RbacDepartment> findByDeptName(String deptName);
    Boolean existsByDeptId(String deptId);
}
