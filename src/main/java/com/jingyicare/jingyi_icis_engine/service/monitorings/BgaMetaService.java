package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParam;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaParamRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.locks.LockExecutor;
import com.jingyicare.jingyi_icis_engine.service.locks.LockKeys;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

@Service
public class BgaMetaService {
    public BgaMetaService(
        ConfigProtoService protoService,
        BgaParamRepository bgaParamRepository,
        LockExecutor lockExecutor
    ) {
        this.configuredCodes = new LinkedHashSet<>(
            protoService.getConfig().getBga().getBgaParamCodeList());
        this.bgaParamRepository = bgaParamRepository;
        this.lockExecutor = lockExecutor;
    }

    public void initBgaParams(String deptId) {
        if (configuredCodes.isEmpty()) return;
        List<BgaParam> params = bgaParamRepository
            .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId);
        Set<String> existingCodes = params.stream()
            .map(BgaParam::getMonitoringParamCode).collect(Collectors.toSet());
        List<String> missingCodes = configuredCodes.stream()
            .filter(code -> !existingCodes.contains(code)).toList();
        if (missingCodes.isEmpty()) return;

        lockExecutor.execute(List.of(LockKeys.bgaParam(deptId)), () -> {
            // 等待锁期间其他请求可能已完成初始化，取得锁后重新计算差集。
            List<BgaParam> existingParams = bgaParamRepository
                .findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId);
            Set<String> lockedCodes = existingParams.stream()
                .map(BgaParam::getMonitoringParamCode).collect(Collectors.toSet());
            int nextDisplayOrder = existingParams.stream()
                .mapToInt(BgaParam::getDisplayOrder).max().orElse(0);
            LocalDateTime nowUtc = TimeUtils.getNowUtc();
            List<BgaParam> paramsToSave = new ArrayList<>();
            for (String code : missingCodes) {
                if (!lockedCodes.add(code)) continue;
                paramsToSave.add(BgaParam.builder()
                    .deptId(deptId)
                    .monitoringParamCode(code)
                    .displayOrder(++nextDisplayOrder)
                    .enabled(false)
                    .isDeleted(false)
                    .modifiedBy("system")
                    .modifiedAt(nowUtc)
                    .build());
            }
            if (!paramsToSave.isEmpty()) bgaParamRepository.saveAll(paramsToSave);
            return null;
        });
    }

    private final Set<String> configuredCodes;
    private final BgaParamRepository bgaParamRepository;
    private final LockExecutor lockExecutor;
}
