package com.jingyicare.jingyi_icis_engine.repository.skincares;

import com.jingyicare.jingyi_icis_engine.entity.skincares.SkincareType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkincareTypeRepository extends JpaRepository<SkincareType, Integer> {
    List<SkincareType> findByDeptIdAndIsDeletedFalse(String deptId);

    List<SkincareType> findByDeptIdAndTypeAndIsDeletedFalse(String deptId, String type);

    List<SkincareType> findByIdInAndIsDeletedFalse(List<Integer> ids);

    Optional<SkincareType> findByIdAndIsDeletedFalse(Integer id);

    Optional<SkincareType> findByDeptIdAndTypeAndNameAndIsDeletedFalse(
        String deptId, String type, String name
    );
}
