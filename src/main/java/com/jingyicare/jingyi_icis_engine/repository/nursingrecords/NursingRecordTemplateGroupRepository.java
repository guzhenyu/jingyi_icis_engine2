package com.jingyicare.jingyi_icis_engine.repository.nursingrecords;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecordTemplateGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NursingRecordTemplateGroupRepository extends JpaRepository<NursingRecordTemplateGroup, Integer> {
    Optional<NursingRecordTemplateGroup> findByIdAndIsDeletedFalse(Integer id);

    List<NursingRecordTemplateGroup> findByEntityTypeAndIsDeletedFalse(Integer entityType);

    List<NursingRecordTemplateGroup> findByEntityTypeAndEntityIdAndIsDeletedFalse(
        Integer entityType, String entityId);

    Optional<NursingRecordTemplateGroup> findByEntityTypeAndEntityIdAndNameAndIsDeletedFalse(
        Integer entityType, String entityId, String name);

    // 根据 name 查询是否存在该模板组
    boolean existsByEntityTypeAndEntityIdAndNameAndIsDeletedFalse(
        Integer entityType, String entityId, String name);
}