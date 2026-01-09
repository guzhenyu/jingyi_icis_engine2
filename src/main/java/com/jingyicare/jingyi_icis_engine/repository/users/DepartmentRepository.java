package com.jingyicare.jingyi_icis_engine.repository.users;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.users.Department;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DepartmentRepository extends JpaRepository<Department, Integer> {
    Optional<Department> findByIdAndIsDeletedFalse(Integer id);

    Optional<Department> findByDeptIdAndIsDeletedFalse(String deptId);

    Optional<Department> findByNameAndIsDeletedFalse(String name);

    List<Department> findByIsDeletedFalse();

    List<Department> findByDeptIdInAndIsDeletedFalse(List<String> deptIds);
}