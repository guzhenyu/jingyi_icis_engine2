package com.jingyicare.jingyi_icis_engine.service.scores;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.scores.DeptScoreGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;

@Service
@Slf4j
public class ScoreConfig {
    public ScoreConfig(
        @Autowired ScoreMetaService scoreMetaService,
        @Autowired DeptScoreGroupRepository deptScoreGroupRepo,
        @Autowired RbacDepartmentRepository departmentRepo
    ) {
        this.scoreMetaService = scoreMetaService;
        this.deptScoreGroupRepo = deptScoreGroupRepo;
        this.departmentRepo = departmentRepo;
    }

    public void initialize() {
        initializeDeptScoreGroups();
    }

    @Transactional
    public void checkIntegrity() {
    }

    public void refresh() {
    }

    private void initializeDeptScoreGroups() {
        List<String> departmentIds = departmentRepo.findAll()
            .stream()
            .map(RbacDepartment::getDeptId)
            .toList();
        Set<String> existingDeptIds = deptScoreGroupRepo.findAllDeptIds();

        for (String deptId : departmentIds) {
            if (existingDeptIds.contains(deptId)) {
                log.info("Department score groups already exist for deptId: {}", deptId);
                continue;
            }

            scoreMetaService.initDept(deptId);
        }

        log.info("Department score groups initialization completed.");
    }

    private final ScoreMetaService scoreMetaService;
    private final DeptScoreGroupRepository deptScoreGroupRepo;
    private final RbacDepartmentRepository departmentRepo;
}
