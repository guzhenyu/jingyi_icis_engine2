package com.jingyicare.jingyi_icis_engine.repository.tubes;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.tubes.TubeTypeStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TubeTypeStatusRepository extends JpaRepository<TubeTypeStatus, Integer> {
    List<TubeTypeStatus> findAll();

    Optional<TubeTypeStatus> findById(Integer id);

    Optional<TubeTypeStatus> findByIdAndIsDeletedFalse(Integer id);

    Optional<TubeTypeStatus> findByTubeTypeIdAndNameAndIsDeletedFalse(Integer tubeTypeId, String name);

    List<TubeTypeStatus> findByTubeTypeIdAndIsDeletedFalse(Integer tubeTypeId);

    List<TubeTypeStatus> findByTubeTypeIdAndStatusAndIsDeleted(Integer tubeTypeId, String status, boolean isDeleted);

    Optional<TubeTypeStatus> findByTubeTypeIdAndStatusAndIsDeletedFalse(Integer tubeTypeId, String statusCode);

    @Query("SELECT tts.id as id, tts.displayOrder as displayOrder " +
        "FROM TubeTypeStatus tts " +
        "WHERE tts.id IN :statusIds and tts.isDeleted = false")
    List<AttributeDisplayOrder> findDisplayOrderByStatusIds(@Param("statusIds") List<Integer> statusIds);
}