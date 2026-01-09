package com.jingyicare.jingyi_icis_engine.repository.nursingorders;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.jingyicare.jingyi_icis_engine.entity.nursingorders.NursingOrderTemplateGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface NursingOrderTemplateGroupRepository extends JpaRepository<NursingOrderTemplateGroup, Integer> {
    List<NursingOrderTemplateGroup> findAllByIsDeletedFalse();

    List<NursingOrderTemplateGroup> findByDeptIdAndIsDeletedFalse(String deptId);

    List<NursingOrderTemplateGroup> findByIdIn(Set<Integer> ids);

    Optional<NursingOrderTemplateGroup> findByIdAndIsDeletedFalse(Integer id);

    Optional<NursingOrderTemplateGroup> findByDeptIdAndNameAndIsDeletedFalse(String deptId, String name);
}