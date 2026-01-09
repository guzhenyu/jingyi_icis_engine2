package com.jingyicare.jingyi_icis_engine.service.nursingrecords;

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

import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingRecord.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;

import com.jingyicare.jingyi_icis_engine.entity.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.LogUtils;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class NursingRecordConfig {
    public NursingRecordConfig(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired NursingRecordTemplateGroupRepository templateGroupRepo,
        @Autowired NursingRecordTemplateRepository templateRepo,
        @Autowired NursingRecordRepository recordRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired DeptSystemSettingsRepository deptSettingRepo
    ) {
        this.ENTITY_TYPE_ACCOUNT = 0;
        this.ENTITY_TYPE_DEPT = 1;

        this.context = context;
        this.protoService = protoService;
        this.configPb = protoService.getConfig().getNursingRecord();
        this.templateGroupRepo = templateGroupRepo;
        this.templateRepo = templateRepo;
        this.recordRepo = recordRepo;
        this.patientRepo = patientRepo;
        this.deptRepo = deptRepo;
        this.deptSettingRepo = deptSettingRepo;
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
    public List<NursingRecordTemplateGroupPB> getTemplates(Integer entityType, String entityId) {
        return templateGroupRepo.findByEntityTypeAndEntityIdAndIsDeletedFalse(entityType, entityId).stream()
            .map(group -> {
                // 将模板组和模板转换为 Protobuf 格式
                NursingRecordTemplateGroupPB.Builder groupBuilder = NursingRecordTemplateGroupPB.newBuilder()
                    .setId(group.getId())
                    .setName(group.getName())
                    .setDisplayOrder(group.getDisplayOrder());

                // 收集对应的模板
                List<NursingRecordTemplatePB> templatePBs = templateRepo
                    .findByEntityTypeAndEntityIdAndGroupIdAndIsDeletedFalse(entityType, entityId, group.getId())
                    .stream()
                    .map(deptTemplate -> NursingRecordTemplatePB.newBuilder()
                        .setId(deptTemplate.getId())
                        .setName(deptTemplate.getName())
                        .setContent(deptTemplate.getContent())
                        .setDisplayOrder(deptTemplate.getDisplayOrder())
                        .setIsCommon(deptTemplate.getIsCommon())
                        .setGroupId(deptTemplate.getGroupId())
                        .build())
                    .sorted((a, b) -> Integer.compare(a.getDisplayOrder(), b.getDisplayOrder()))
                    .toList();
                groupBuilder.addAllNursingRecordTemplate(templatePBs);

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
        Map<String, List<NursingRecordTemplateGroup>> deptTemplateGroups = templateGroupRepo
            .findByEntityTypeAndIsDeletedFalse(ENTITY_TYPE_DEPT).stream()
            .collect(Collectors.groupingBy(NursingRecordTemplateGroup::getEntityId));

        // 获取所有部门模板
        Map<String, List<NursingRecordTemplate>> deptTemplatesMap = templateRepo
            .findByEntityTypeAndIsDeletedFalse(ENTITY_TYPE_DEPT).stream()
            .collect(Collectors.groupingBy(NursingRecordTemplate::getEntityId));

        // 遍历所有部门ID
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        for (String deptId : departmentIds) {
            // 如果该部门已经有模板组或模板，则跳过初始化
            if (deptTemplateGroups.containsKey(deptId) || deptTemplatesMap.containsKey(deptId)) {
                log.info("Department nursing record templates already exist for deptId: {}", deptId);
                continue;
            }

            log.info("Initializing nursing record department templates for deptId: {}", deptId);

            // 从configPb获取该部门的模板组
            List<NursingRecordTemplateGroupPB> defaultDeptGroups = configPb.getDefaultDeptGroupList();

            // 遍历所有的默认部门模板组
            for (int groupIndex = 0; groupIndex < defaultDeptGroups.size(); groupIndex++) {
                NursingRecordTemplateGroupPB groupPB = defaultDeptGroups.get(groupIndex);

                // 创建并保存NursingRecordTemplateGroup
                NursingRecordTemplateGroup group = NursingRecordTemplateGroup.builder()
                    .entityType(ENTITY_TYPE_DEPT)
                    .entityId(deptId)
                    .name(groupPB.getName())
                    .displayOrder(groupIndex)
                    .isDeleted(false)
                    .modifiedBy("system")
                    .modifiedAt(nowUtc)
                    .build();

                // 保存模板组
                NursingRecordTemplateGroup savedGroup = templateGroupRepo.save(group);

                // 遍历每个模板组中的模板
                for (int templateIndex = 0; templateIndex < groupPB.getNursingRecordTemplateList().size(); templateIndex++) {
                    NursingRecordTemplatePB templatePB = groupPB.getNursingRecordTemplate(templateIndex);

                    // 创建并保存NursingRecordTemplate
                    NursingRecordTemplate deptTemplate = NursingRecordTemplate.builder()
                        .entityType(ENTITY_TYPE_DEPT)
                        .entityId(deptId)
                        .name(templatePB.getName())
                        .content(templatePB.getContent())
                        .displayOrder(templateIndex)
                        .isCommon(templatePB.getIsCommon())  // 常用模板
                        .groupId(savedGroup.getId())  // 使用上面保存的模板组ID
                        .isDeleted(false)
                        .modifiedBy("system")
                        .modifiedAt(nowUtc)
                        .build();

                    // 保存部门模板
                    templateRepo.save(deptTemplate);
                }
            }
        }

        log.info("Department nursing record templates initialization completed.");
    }

    public NursingRecordSettingsPB getNursingRecordSettings(String deptId) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingRepo.findById(settingsId).orElse(null);

        NursingRecordSettingsPB settingsPb = null;
        if (entity != null) settingsPb = ProtoUtils.decodeNursingRecordSettings(entity.getSettingsPb());
        if (settingsPb == null) settingsPb = NursingRecordSettingsPB.newBuilder().build();

        return settingsPb;
    }

    public boolean getEnableUpdatingCreatedBy(Long nursingRecordId) {
        // 获取病人id
        NursingRecord record = recordRepo.findByIdAndIsDeletedFalse(nursingRecordId).orElse(null);
        if (record == null) return false;
        Long pid = record.getPatientId();

        // 获取病人部门
        PatientRecord patientRec = patientRepo.findById(pid).orElse(null);
        if (patientRec == null) return false;
        String deptId = patientRec.getDeptId();

        NursingRecordSettingsPB settingsPb = getNursingRecordSettings(deptId);
        return settingsPb.getEnableUpdatingCreatedBy();
    }

    public void setNursingRecordSettings(String deptId, NursingRecordSettingsPB settingsPb, String modifiedBy) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_RECORD_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingRepo.findById(settingsId).orElse(null);
        String settingsStr = ProtoUtils.encodeNursingRecordSettings(settingsPb);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (entity == null) {
            entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, modifiedBy);
        } else {
            entity.setSettingsPb(settingsStr);
            entity.setModifiedAt(nowUtc);
            entity.setModifiedBy(modifiedBy);
        }
        deptSettingRepo.save(entity);
    }

    private final Integer ENTITY_TYPE_ACCOUNT;
    private final Integer ENTITY_TYPE_DEPT;

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final NursingRecordConfigPB configPb;
    private final NursingRecordTemplateGroupRepository templateGroupRepo;
    private final NursingRecordTemplateRepository templateRepo;
    private final NursingRecordRepository recordRepo;
    private final PatientRecordRepository patientRepo;
    private final RbacDepartmentRepository deptRepo;
    private final DeptSystemSettingsRepository deptSettingRepo;
}