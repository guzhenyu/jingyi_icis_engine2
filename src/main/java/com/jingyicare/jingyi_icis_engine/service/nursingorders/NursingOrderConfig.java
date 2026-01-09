package com.jingyicare.jingyi_icis_engine.service.nursingorders;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ch.qos.logback.classic.LoggerContext;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingOrder.*;

import com.jingyicare.jingyi_icis_engine.entity.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class NursingOrderConfig {
    public NursingOrderConfig(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired NursingOrderTemplateGroupRepository templateGroupRepo,
        @Autowired NursingOrderTemplateRepository deptTemplateRepo,
        @Autowired RbacDepartmentRepository deptRepo
    ) {
        this.context = context;
        this.protoService = protoService;
        this.configPb = protoService.getConfig().getNursingOrder();
        this.templateGroupRepo = templateGroupRepo;
        this.deptTemplateRepo = deptTemplateRepo;
        this.deptRepo = deptRepo;
    }

    @Transactional
    public void initialize() {
        initializeDeptTemplates();
    }

    @Transactional
    public void checkIntegrity() {
    }

    public void refresh() {
    }

    @Transactional
    public List<NursingOrderTemplateGroupPB> getDeptTemplates(String deptId) {
        return templateGroupRepo.findByDeptIdAndIsDeletedFalse(deptId).stream()
            .map(group -> {
                // 将模板组和模板转换为 Protobuf 格式
                NursingOrderTemplateGroupPB.Builder groupBuilder = NursingOrderTemplateGroupPB.newBuilder()
                    .setId(group.getId())
                    .setName(group.getName())
                    .setDisplayOrder(group.getDisplayOrder());

                // 收集对应的模板
                List<NursingOrderTemplatePB> templatePBs = deptTemplateRepo
                    .findByDeptIdAndGroupIdAndIsDeletedFalse(deptId, group.getId())
                    .stream()
                    .map(deptTemplate -> NursingOrderTemplatePB.newBuilder()
                        .setId(deptTemplate.getId())
                        .setGroupId(deptTemplate.getGroupId())
                        .setName(deptTemplate.getName())
                        .setDurationType(deptTemplate.getDurationType())
                        .setMedicationFreqCode(deptTemplate.getMedicationFreqCode())
                        .setDisplayOrder(deptTemplate.getDisplayOrder())
                        .build())
                    .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                    .toList();
                groupBuilder.addAllNursingOrderTemplate(templatePBs);

                return groupBuilder.build();
            })
            .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
            .toList();
    }

    private void initializeDeptTemplates() {
        // 获取所有部门ID
        List<String> departmentIds = deptRepo.findAll()
            .stream()
            .map(RbacDepartment::getDeptId)
            .toList();

        // 获取所有部门模板组，按deptId分组
        Map<String, List<NursingOrderTemplateGroup>> deptTemplateGroups = templateGroupRepo
            .findAllByIsDeletedFalse().stream()
            .collect(Collectors.groupingBy(NursingOrderTemplateGroup::getDeptId));

        // 获取所有部门模板
        Map<String, List<NursingOrderTemplate>> deptTemplatesMap = deptTemplateRepo
            .findAllByIsDeletedFalse().stream()
            .collect(Collectors.groupingBy(NursingOrderTemplate::getDeptId));

        // 遍历所有部门ID
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        for (String deptId : departmentIds) {
            // 如果该部门已经有模板组或模板，则跳过初始化
            if (deptTemplateGroups.containsKey(deptId) || deptTemplatesMap.containsKey(deptId)) {
                log.info("Department templates already exist for deptId: {}", deptId);
                continue;
            }

            log.info("Initializing nursing record department templates for deptId: {}", deptId);

            // 从configPb获取该部门的模板组
            List<NursingOrderTemplateGroupPB> defaultDeptGroups = configPb.getDefaultDeptGroupList();

            // 遍历所有的默认部门模板组
            for (int groupIndex = 0; groupIndex < defaultDeptGroups.size(); groupIndex++) {
                NursingOrderTemplateGroupPB groupPB = defaultDeptGroups.get(groupIndex);

                // 创建并保存NursingOrderTemplateGroup
                NursingOrderTemplateGroup group = NursingOrderTemplateGroup.builder()
                    .deptId(deptId)
                    .name(groupPB.getName())
                    .displayOrder(groupIndex)
                    .isDeleted(false)
                    .modifiedBy("system")
                    .modifiedAt(nowUtc)
                    .build();

                // 保存模板组
                NursingOrderTemplateGroup savedGroup = templateGroupRepo.save(group);

                // 遍历每个模板组中的模板
                for (int templateIndex = 0; templateIndex < groupPB.getNursingOrderTemplateList().size(); templateIndex++) {
                    NursingOrderTemplatePB templatePB = groupPB.getNursingOrderTemplate(templateIndex);

                    // 创建并保存NursingOrderTemplate
                    NursingOrderTemplate deptTemplate = NursingOrderTemplate.builder()
                        .deptId(deptId)
                        .groupId(savedGroup.getId())
                        .name(templatePB.getName())
                        .durationType(templatePB.getDurationType())
                        .medicationFreqCode(templatePB.getMedicationFreqCode())
                        .displayOrder(templateIndex)
                        .isDeleted(false)
                        .modifiedBy("system")
                        .modifiedAt(nowUtc)
                        .build();

                    // 保存部门模板
                    deptTemplateRepo.save(deptTemplate);
                }
            }
        }

        log.info("Department nursing record templates initialization completed.");
    }

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final NursingOrderConfigPB configPb;
    private final NursingOrderTemplateGroupRepository templateGroupRepo;
    private final NursingOrderTemplateRepository deptTemplateRepo;
    private final RbacDepartmentRepository deptRepo;
}