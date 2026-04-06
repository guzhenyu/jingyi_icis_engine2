package com.jingyicare.jingyi_icis_engine.repository.skincares;

import com.jingyicare.jingyi_icis_engine.entity.skincares.SkincareTypeAttribute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SkincareTypeAttributeRepository extends JpaRepository<SkincareTypeAttribute, Integer> {
    List<SkincareTypeAttribute> findBySkincareTypeIdAndIsDeletedFalse(Integer skincareTypeId);

    List<SkincareTypeAttribute> findBySkincareTypeIdInAndIsDeletedFalse(List<Integer> skincareTypeIds);

    List<SkincareTypeAttribute> findByIdInAndIsDeletedFalse(List<Integer> ids);

    Optional<SkincareTypeAttribute> findByIdAndIsDeletedFalse(Integer id);

    Optional<SkincareTypeAttribute> findBySkincareTypeIdAndAttrCodeAndIsDeletedFalse(
        Integer skincareTypeId, String attrCode
    );
}
