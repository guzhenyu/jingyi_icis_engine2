package com.jingyicare.jingyi_icis_engine.service.reports;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.reports.DragableFormTemplate;
import com.jingyicare.jingyi_icis_engine.repository.reports.DragableFormTemplateRepository;

/** 表单模板的只读查询，事务由调用方编排。 */
@Service
public class JfkTemplateReader {
    public JfkTemplateReader(DragableFormTemplateRepository repository) {
        this.repository = repository;
    }

    // 根据 ID 获取未删除的模板，找不到返回 null。
    public DragableFormTemplate getById(Integer id) {
        return repository.findByIdAndIsDeletedFalse(id).orElse(null);
    }

    // 根据 ID 列表获取未删除的模板，找不到返回可修改的空列表。
    public List<DragableFormTemplate> getByIds(List<Integer> ids) {
        if (ids == null || ids.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(repository.findByIdInAndIsDeletedFalse(ids));
    }

    // 根据科室 ID 获取未删除的模板，找不到返回可修改的空列表。
    public List<DragableFormTemplate> getByDeptId(String deptId) {
        return new ArrayList<>(repository.findByDeptIdAndIsDeletedFalse(deptId));
    }

    private final DragableFormTemplateRepository repository;
}
