package com.jingyicare.jingyi_icis_engine.service.monitorings.meta;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamRepository;

@Service
public class MonMetaReader {
    public MonMetaReader(MonitoringParamRepository paramRepo) {
        this.paramRepo = paramRepo;
    }

    public Optional<MonitoringParam> findGlobalParam(String paramCode) {
        return paramRepo.findByCode(paramCode).map(this::copy);
    }

    public List<MonitoringParam> listGlobalParams() {
        return paramRepo.findAll().stream().map(this::copy).toList();
    }

    public Map<String, MonitoringParam> findGlobalParams(Set<String> paramCodes) {
        if (paramCodes.isEmpty()) return Map.of();
        return paramRepo.findByCodeIn(paramCodes).stream()
            .collect(Collectors.toMap(MonitoringParam::getCode, this::copy));
    }

    private MonitoringParam copy(MonitoringParam param) {
        // 返回副本，避免调用方合并科室配置时改变持久化上下文中的全局实体。
        return param.toBuilder().build();
    }

    private final MonitoringParamRepository paramRepo;
}
