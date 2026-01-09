package com.jingyicare.jingyi_icis_engine.repository.reports;

import java.util.List;
import java.util.Optional;

import com.jingyicare.jingyi_icis_engine.entity.reports.DragableFormTemplate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface DragableFormTemplateRepository extends JpaRepository<DragableFormTemplate, Integer> {
    List<DragableFormTemplate> findByDeptId(String deptId);

    List<DragableFormTemplate> findByDeptIdAndIsDeletedFalse(String deptId);

    Optional<DragableFormTemplate> findByIdAndIsDeletedFalse(Integer id);

    List<DragableFormTemplate> findByIdInAndIsDeletedFalse(List<Integer> ids);

    Optional<DragableFormTemplate> findByDeptIdAndNameAndIsDeletedFalse(String deptId, String name);
}
