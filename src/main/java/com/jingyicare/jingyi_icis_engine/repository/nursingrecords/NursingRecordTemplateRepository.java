package com.jingyicare.jingyi_icis_engine.repository.nursingrecords;

import java.util.List;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.NursingRecordTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NursingRecordTemplateRepository extends JpaRepository<NursingRecordTemplate, Integer> {
    List<NursingRecordTemplate> findByEntityTypeAndIsDeletedFalse(Integer entityType);

    List<NursingRecordTemplate> findByEntityTypeAndEntityIdAndIsDeletedFalse(
        Integer entityType, String entityId);

    List<NursingRecordTemplate> findByEntityTypeAndEntityIdAndGroupIdAndIsDeletedFalse(
        Integer entityType, String entityId, Integer groupId);

    // 根据 名称 查询是否存在该模板
    boolean existsByEntityTypeAndEntityIdAndNameAndIsDeletedFalse(
        Integer entityType, String entityId, String name);
}