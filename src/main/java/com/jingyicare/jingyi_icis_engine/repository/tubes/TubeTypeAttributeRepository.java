package com.jingyicare.jingyi_icis_engine.repository.tubes;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.jingyicare.jingyi_icis_engine.entity.tubes.TubeTypeAttribute;

public interface TubeTypeAttributeRepository extends JpaRepository<TubeTypeAttribute, Integer> {
    List<TubeTypeAttribute> findAll();

    Optional<TubeTypeAttribute> findById(Integer id);

    Optional<TubeTypeAttribute> findByIdAndIsDeletedFalse(Integer id);

    Optional<TubeTypeAttribute> findByTubeTypeIdAndNameAndIsDeletedFalse(Integer tubeTypeId, String name);

    List<TubeTypeAttribute> findByTubeTypeIdAndIsDeletedFalse(Integer tubeTypeId);

    List<TubeTypeAttribute> findByTubeTypeIdAndAttributeAndIsDeleted(Integer tubeTypeId, String attributeCode, Boolean isDeleted);

    @Query("SELECT tta.id as id, tta.displayOrder as displayOrder " +
        "FROM TubeTypeAttribute tta " +
        "WHERE tta.id IN :attributeIds and tta.isDeleted = false")
    List<AttributeDisplayOrder> findDisplayOrderByAttributeIds(@Param("attributeIds") List<Integer> attributeIds);
}
