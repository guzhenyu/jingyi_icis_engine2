package com.jingyicare.jingyi_icis_engine.service.lis;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisLis.*;

import com.jingyicare.jingyi_icis_engine.entity.lis.*;
import com.jingyicare.jingyi_icis_engine.repository.lis.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class LisConfig {
    public LisConfig(
        ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired LisParamRepository paramRepo
    ) {
        this.context = context;
        this.protoService = protoService;
        this.paramRepo = paramRepo;
        this.paramMap = new HashMap<>();
    }

    @Transactional
    public void initialize() {
        initializeLisParams();
    }

    @Transactional
    public void checkIntegrity() {
    }

    @Transactional
    public void refresh() {
    }

    private void initializeLisParams() {
        List<LisParam> params = paramRepo.findAll();
        Set<String> paramCodes = new HashSet<>();
        Integer displayOrder = 1;
        for (LisParam param : params) {
            paramCodes.add(param.getParamCode());
            if (param.getDisplayOrder() >= displayOrder) {
                displayOrder = param.getDisplayOrder() + 1;
            }
        }

        List<LisParam> newParams = new ArrayList<>();
        for (LisParamWithTypePB paramWithTypePb : protoService.getConfig().getLis().getParamList()) {
            String paramCode = paramWithTypePb.getParam().getParamCode();
            paramMap.put(paramCode, paramWithTypePb);
            if (paramCodes.contains(paramCode)) continue;

            LisParamPB paramPb = paramWithTypePb.getParam();
            LisParam param = LisParam.builder()
                .paramCode(paramPb.getParamCode())
                .paramName(paramPb.getParamName())
                .paramDescription(paramPb.getDescription())
                .displayOrder(displayOrder++)
                .build();
            newParams.add(param);
        }
        paramRepo.saveAll(newParams);
    }

    private final ConfigurableApplicationContext context;
    private final ConfigProtoService protoService;
    private final LisParamRepository paramRepo;
    private final Map<String, LisParamWithTypePB> paramMap;
}