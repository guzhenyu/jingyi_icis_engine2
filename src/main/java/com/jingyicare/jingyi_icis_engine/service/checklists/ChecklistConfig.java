package com.jingyicare.jingyi_icis_engine.service.checklists;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisChecklist.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.entity.checklists.*;
import com.jingyicare.jingyi_icis_engine.repository.checklists.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ChecklistConfig {
    public ChecklistConfig(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired DeptChecklistGroupRepository groupRepo,
        @Autowired DeptChecklistItemRepository itemRepo
    ) {
        this.context = context;

        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.configPb = protoService.getConfig().getChecklist();
        this.groupRepo = groupRepo;
        this.itemRepo = itemRepo;
    };

    public void initialize() {
        log.info("ChecklistConfig initialized");
    }

    public void checkIntegrity() {
    }

    @Transactional
    public void refresh() {
    }

    private void initDeptChecklistGroups() {
        initDeptChecklistGroupsImpl();
        initDeptChecklistItemsImpl();
    }

    @Transactional
    private void initDeptChecklistGroupsImpl() {
        List<DeptChecklistGroup> newGroups = new ArrayList<>();
        LocalDateTime now = TimeUtils.getNowUtc();

        Map<String/*deptId*/, List<DeptChecklistGroupPB>> groupMap = configPb.getGroupList()
            .stream()
            .collect(Collectors.groupingBy(DeptChecklistGroupPB::getDeptId));
        
        for (Map.Entry<String, List<DeptChecklistGroupPB>> entry : groupMap.entrySet()) {
            String deptId = entry.getKey();
            List<DeptChecklistGroupPB> groupPbList = entry.getValue();

            // 如果部门内已存在组长质控分组，则跳过初始化
            List<DeptChecklistGroup> existingGroups = groupRepo.findByDeptId(deptId);
            if (!existingGroups.isEmpty()) continue;

            // 初始化DeptChecklistGroup
            Integer displayOrder = 1;
            for (DeptChecklistGroupPB groupPb : groupPbList) {
                DeptChecklistGroup group = ChecklistUtils.toEntity(groupPb);
                group.setDisplayOrder(displayOrder++);
                group.setModifiedBy("system");
                group.setModifiedAt(now);
                newGroups.add(group);
            }
        }

        if (!newGroups.isEmpty()) {
            groupRepo.saveAll(newGroups);
            log.info("Initialized {} DeptChecklistGroup(s)", newGroups.size());
        } else {
            log.info("No DeptChecklistGroup to initialize");
        }
    }

    @Transactional
    private void initDeptChecklistItemsImpl() {
        List<DeptChecklistItem> newItems = new ArrayList<>();
        LocalDateTime now = TimeUtils.getNowUtc();

        // 部门是否需要初始化组长质控项（如果该部门没有任何组长质控项，则需要；否则不需要）
        Set<String/*deptId*/> deptWithItemsSet = new HashSet<>();
        Map<Integer/*groupId*/, String/*deptId*/> groupDeptIdMap = groupRepo.findAllByIsDeletedFalse()
            .stream()
            .collect(Collectors.toMap(DeptChecklistGroup::getId, DeptChecklistGroup::getDeptId));
        for (DeptChecklistItem item : itemRepo.findAllByIsDeletedFalse()) {
            String deptId = groupDeptIdMap.get(item.getGroupId());
            if (deptId != null) {
                deptWithItemsSet.add(deptId);
            }
        }

        // 初始化组长质控项
        for (DeptChecklistGroupPB groupPb : configPb.getGroupList()) {
            String deptId = groupPb.getDeptId();
            if (deptWithItemsSet.contains(deptId)) continue; // 如果该部门已存在组长质控项，则跳过

            List<DeptChecklistItemPB> itemPbList = groupPb.getItemList();
            if (itemPbList.isEmpty()) continue; // 如果组长质控分组下没有质控项，则跳过

            Integer displayOrder = 1;
            for (DeptChecklistItemPB itemPb : itemPbList) {
                DeptChecklistItem item = ChecklistUtils.toEntity(itemPb);
                item.setGroupId(groupPb.getId());
                item.setDisplayOrder(displayOrder++);
                item.setModifiedBy("system");
                item.setModifiedAt(now);
                newItems.add(item);
            }
        }
        if (!newItems.isEmpty()) {
            itemRepo.saveAll(newItems);
            log.info("Initialized {} DeptChecklistItem(s)", newItems.size());
        } else {
            log.info("No DeptChecklistItem to initialize");
        }
    }

    private final String ZONE_ID;

    private ConfigurableApplicationContext context;
    private ChecklistConfigPB configPb;
    private DeptChecklistGroupRepository groupRepo;
    private DeptChecklistItemRepository itemRepo;
}