package com.jingyicare.jingyi_icis_engine.repository.nursingorders;

import java.util.List;
import java.util.Set;

import com.jingyicare.jingyi_icis_engine.entity.nursingorders.NursingOrderTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NursingOrderTemplateRepository extends JpaRepository<NursingOrderTemplate, Integer> {
    List<NursingOrderTemplate> findAllByIsDeletedFalse();

    List<NursingOrderTemplate> findByDeptIdAndGroupIdAndIsDeletedFalse(String deptId, Integer groupId);

    Boolean existsByDeptIdAndGroupIdAndNameAndIsDeletedFalse(String deptId, Integer groupId, String name);

    List<NursingOrderTemplate> findByIdIn(Set<Integer> ids);
}