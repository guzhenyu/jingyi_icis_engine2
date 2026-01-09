package com.jingyicare.jingyi_icis_engine.service.scores;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.scores.*;
import com.jingyicare.jingyi_icis_engine.entity.settings.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.settings.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ScoreConfig {
    static public ScoreItemMetaPB getScoreItemMeta(ScoreGroupMetaPB scoreGroupMeta, String code) {
        for (ScoreItemMetaPB scoreItemMeta : scoreGroupMeta.getItemList()) {
            if (scoreItemMeta.getCode().equals(code)) {
                return scoreItemMeta;
            }
        }
        return null;
    }

    public ScoreConfig(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired DeptScoreGroupRepository deptScoreGroupRepo,
        @Autowired RbacDepartmentRepository departmentRepo,
        DeptSystemSettingsRepository deptSettingRepo
    ) {
        this.context = context;
        this.protoService = protoService;
        this.scorePb = protoService.getConfig().getScore();

        this.scoreGroupMetaMap = new HashMap<>();
        for (ScoreGroupMetaPB scoreGroupMetaPB : scorePb.getGroupList()) {
            scoreGroupMetaMap.put(scoreGroupMetaPB.getCode(), scoreGroupMetaPB.toBuilder()
                .setOrigName(scoreGroupMetaPB.getName())
                .build());
        }

        this.deptScoreGroupRepo = deptScoreGroupRepo;
        this.deptSettingRepo = deptSettingRepo;
        this.departmentRepo = departmentRepo;
    }

    @Transactional
    public void initialize() {
        initializeDeptScoreGroups();
    }

    @Transactional
    public void checkIntegrity() {
        checkDeptScoreGroupIntegrity();
    }

    public void refresh() {
    }

    public ScoreGroupMetaPB getScoreGroupMeta(String code) {
        return scoreGroupMetaMap.get(code);
    }

    @Transactional
    private void initializeDeptScoreGroups() {
        List<String> departmentIds = departmentRepo.findAll()
            .stream()
            .map(RbacDepartment::getDeptId)
            .toList();
        Set<String> existingDeptIds = deptScoreGroupRepo.findAllDeptIds();

        final LocalDateTime now = TimeUtils.getNowUtc();
        final String accountId = "System";

        for (String deptId : departmentIds) {
            if (existingDeptIds.contains(deptId)) {
                log.info("Department monitoring groups already exist for deptId: {}", deptId);
                continue;
            }

            for (int i = 0; i < scorePb.getDefaultScoreGroupCodeList().size(); ++i) {
                String scoreGroupCode = scorePb.getDefaultScoreGroupCodeList().get(i);
                ScoreGroupMetaPB scoreGroupMeta = scoreGroupMetaMap.get(scoreGroupCode);
                if (scoreGroupMeta == null) {
                    log.error("ScoreGroupMetaPB not found for code: {}", scoreGroupCode);
                    LogUtils.flushAndQuit(context);
                }
                String scoreGroupMetaStr = ProtoUtils.encodeScoreGroupMetaPB(scoreGroupMeta);

                DeptScoreGroup deptScoreGroup = DeptScoreGroup.builder()
                    .deptId(deptId)
                    .scoreGroupCode(scoreGroupCode)
                    .scoreGroupMeta(scoreGroupMetaStr)
                    .displayOrder(i + 1)
                    .isDeleted(false)
                    .modifiedBy(accountId)
                    .modifiedAt(now)
                    .build();
                deptScoreGroupRepo.save(deptScoreGroup);
                log.info("Initialized dept_score_group for deptId: {}, scoreGroupCode: {}", deptId, scoreGroupCode);
            }
        }

        log.info("Department score groups initialization completed.");
    }

    private void checkDeptScoreGroupIntegrity() {
        for (DeptScoreGroup deptScoreGroup : deptScoreGroupRepo.findAllByIsDeletedFalse()) {
            String deptId = deptScoreGroup.getDeptId();
            String scoreGroupCode = deptScoreGroup.getScoreGroupCode();
            ScoreGroupMetaPB scoreGroupMeta = scoreGroupMetaMap.get(scoreGroupCode);
            if (scoreGroupMeta == null && scoreGroupCode != null && !scoreGroupCode.equals("vte_caprini")) {
                log.error("ScoreGroupMetaPB not found for code: {}", scoreGroupCode);
                LogUtils.flushAndQuit(context);
            }
        }
    }

    public ScoreSettingsPB getDeptScoreSettings(String deptId) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_SCORE_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingRepo.findById(settingsId).orElse(null);

        ScoreSettingsPB settingsPb = null;
        if (entity != null) {
            settingsPb = ProtoUtils.decodeScoreSettings(entity.getSettingsPb());
        }
        if (settingsPb == null) return ScoreSettingsPB.newBuilder().build();

        return settingsPb;
    }

    public void setDeptScoreSettings(String deptId, ScoreSettingsPB settingsPb, String modifiedBy) {
        DeptSystemSettingsId settingsId = new DeptSystemSettingsId(
            deptId, SystemSettingFunctionId.GET_DEPT_NURSING_SCORE_SETTINGS.getNumber());
        DeptSystemSettings entity = deptSettingRepo.findById(settingsId).orElse(null);
        String settingsStr = ProtoUtils.encodeScoreSettings(settingsPb);

        // 保存到数据库
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (entity == null) entity = new DeptSystemSettings(settingsId, settingsStr, nowUtc, modifiedBy);
        else {
            entity.setSettingsPb(settingsStr);
            entity.setModifiedAt(nowUtc);
            entity.setModifiedBy(modifiedBy);
        }
        deptSettingRepo.save(entity);
    }

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final ScorePB scorePb;

    private final DeptScoreGroupRepository deptScoreGroupRepo;
    private final RbacDepartmentRepository departmentRepo;
    private final DeptSystemSettingsRepository deptSettingRepo;

    private Map<String /*ScoreGroupMetaPB.code*/, ScoreGroupMetaPB> scoreGroupMetaMap;
}