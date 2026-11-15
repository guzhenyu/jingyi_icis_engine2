package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParam;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaParamRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
public class BgaMetaService {
    public BgaMetaService(
        ConfigProtoService protoService,
        BgaParamRepository bgaParamRepository
    ) {
        this.configuredCodes = new LinkedHashSet<>(
            protoService.getConfig().getBga().getBgaParamCodeList());
        this.bgaParamRepository = bgaParamRepository;
    }

    // 由 DepartmentCommand.initDept 统一持锁并提供事务。
    @Transactional(propagation = Propagation.MANDATORY)
    public void initDept(String deptId) {
        if (configuredCodes.isEmpty()) return;

        // 找到当前科室: 预设的参数，已存在的参数，以及缺失的参数
        List<BgaParam> params = bgaParamRepository
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId);
        Set<String> existingCodes = params.stream()
            .map(BgaParam::getMonitoringParamCode).collect(Collectors.toSet());
        List<String> missingCodes = configuredCodes.stream()
            .filter(code -> !existingCodes.contains(code)).toList();
        if (missingCodes.isEmpty()) return;

        // 初始化缺失的参数
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        int nextDisplayOrder = params.stream()
            .mapToInt(BgaParam::getDisplayOrder).max().orElse(0);
        List<BgaParam> paramsToSave = new ArrayList<>();
        for (String code : missingCodes) {
            paramsToSave.add(BgaParam.builder()
                .deptId(deptId)
                .monitoringParamCode(code)
                .displayOrder(++nextDisplayOrder)
                .enabled(false)
                .isDeleted(false)
                .modifiedBy(UserQuery.SYSTEM_ACCOUNT_ID)
                .modifiedAt(nowUtc)
                .build());
        }
        if (!paramsToSave.isEmpty()) bgaParamRepository.saveAll(paramsToSave);
    }

    private final Set<String> configuredCodes;
    private final BgaParamRepository bgaParamRepository;
}
