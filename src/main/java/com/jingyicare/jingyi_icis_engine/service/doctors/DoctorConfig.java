package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDoctorScore.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class DoctorConfig {
    public DoctorConfig(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired DiseaseMetaRepository diseaseMetaRepo
    ) {
        this.context = context;
        this.diagnosisPb  = protoService.getConfig().getDiagnosis();
        this.doctorScorePb = protoService.getConfig().getDoctorScore();
        this.diseaseMetaRepo = diseaseMetaRepo;
    }

    @Transactional
    public void initialize() {
        // 初始化诊断疾病表
        Set<String> existingCodes = new HashSet<>();
        for (DiseaseMeta diseaseMeta : diseaseMetaRepo.findAll()) {
            existingCodes.add(diseaseMeta.getCode());
        }

        for (DiseaseMetaPB diseaseMetaPb : diagnosisPb.getDiseaseMetaList()) {
            if (existingCodes.contains(diseaseMetaPb.getCode())) {
                continue; // 如果疾病编码已存在，则跳过
            }
            DiseaseMeta diseaseMeta = new DiseaseMeta();
            diseaseMeta.setCode(diseaseMetaPb.getCode());
            diseaseMeta.setName(diseaseMetaPb.getName());
            diseaseMeta.setDescription(diseaseMetaPb.getDescription());
            diseaseMeta.setDiseaseMetaPbtxt(ProtoUtils.encodeDiseaseMeta(diseaseMetaPb)); // 序列化并base64
            diseaseMetaRepo.save(diseaseMeta);
            log.info("Initialized DiseaseMeta: {}, {}", diseaseMeta.getCode(), diseaseMeta.getName());
        }
    }

    @Transactional
    public void checkIntegrity() {
        // 检查诊断疾病表完整性(数据库中的疾病编码是否在配置文件中存在)
        Set<String> existingCodes = new HashSet<>();
        for (DiseaseMetaPB diseaseMetaPb : diagnosisPb.getDiseaseMetaList()) {
            existingCodes.add(diseaseMetaPb.getCode());
        }

        for (DiseaseMeta diseaseMeta : diseaseMetaRepo.findAll()) {
            if (!existingCodes.contains(diseaseMeta.getCode())) {
                log.error("Missing DiseaseMeta: {}", diseaseMeta);
                LogUtils.flushAndQuit(context);
            }
        }

        // 检查apache评分配置是否完整一致
        CheckApacheIIMeta();
    }

    @Transactional
    public void refresh() {
        // 刷新逻辑
    }

    // 检查apache评分编码是否存在重复，评分项和系数项是否一一对应
    private void CheckApacheIIMeta() {
        ApacheIIScoreMetaPB apacheMetaPb = doctorScorePb.getApacheIiMeta();
        Map<String, String> metaToFactorMap = new HashMap<>();

        // aps
        for (DoctorScoreFactorMetaPB factorMeta : apacheMetaPb.getApsParamList()) {
            if (metaToFactorMap.put(factorMeta.getCode(), "aps_param") != null) {
                log.error("Duplicate DoctorScoreFactorMetaPB code found in aps_param: {}", factorMeta.getCode());
                LogUtils.flushAndQuit(context);
            }
        }

        // chc
        for (DoctorScoreFactorMetaPB factorMeta : apacheMetaPb.getChcParamList()) {
            if (metaToFactorMap.put(factorMeta.getCode(), "chc_param") != null) {
                log.error("Duplicate DoctorScoreFactorMetaPB code found in chc_param: {}", factorMeta.getCode());
                LogUtils.flushAndQuit(context);
            }
        }

        // 非手术症状
        checkFactorCodeMapping(
            apacheMetaPb.getNopRespiratoryFailureList(),
            apacheMetaPb.getNopRespiratoryFailureCoefList(),
            "respiratory_failure",
            metaToFactorMap
        );
        checkFactorCodeMapping(
            apacheMetaPb.getNopCardiovascularFailureList(),
            apacheMetaPb.getNopCardiovascularFailureCoefList(),
            "cardiovascular_failure",
            metaToFactorMap
        );
        checkFactorCodeMapping(
            apacheMetaPb.getNopTraumaList(),
            apacheMetaPb.getNopTraumaCoefList(),
            "trauma",
            metaToFactorMap
        );
        checkFactorCodeMapping(
            apacheMetaPb.getNopNeurologicalDisorderList(),
            apacheMetaPb.getNopNeurologicalDisorderCoefList(),
            "neurological_disorder",
            metaToFactorMap
        );
        checkFactorCodeMapping(
            apacheMetaPb.getNopOtherConditionsList(),
            apacheMetaPb.getNopOtherConditionsCoefList(),
            "other_conditions",
            metaToFactorMap);
        checkFactorCodeMapping(
            apacheMetaPb.getNopOrganDamageList(),
            apacheMetaPb.getNopOrganDamageCoefList(),
            "organ_damage",
            metaToFactorMap
        );

        // 手术症状
        checkFactorCodeMapping(
            apacheMetaPb.getOpSurgeryList(),
            apacheMetaPb.getOpSurgeryCoefList(),
            "surgery",
            metaToFactorMap);
        checkFactorCodeMapping(
            apacheMetaPb.getOpPostoperativeComplicationsList(),
            apacheMetaPb.getOpPostoperativeComplicationsCoefList(),
            "postoperative_complications",
            metaToFactorMap
        );
        checkFactorCodeMapping(
            apacheMetaPb.getOpNosList(),
            apacheMetaPb.getOpNosCoefList(),
            "nos",
            metaToFactorMap
        );
    }

    private void checkFactorCodeMapping(
        List<DoctorScoreFactorMetaPB> metaList,
        List<DoctorScoreFactorPB> factorList,
        String category,
        Map<String, String> metaToFactorMap
    ) {
        Set<String> metaCodes = new HashSet<>();
        for (DoctorScoreFactorMetaPB meta : metaList) {
            metaCodes.add(meta.getCode());
        }

        for (DoctorScoreFactorPB factor : factorList) {
            if (!metaCodes.contains(factor.getCode())) {
                log.error("FactorPB code '{}' in category '{}' does not match any FactorMetaPB code", factor.getCode(), category);
                LogUtils.flushAndQuit(context);
            }
            if (metaToFactorMap.put(factor.getCode(), category) != null) {
                log.error("FactorPB code '{}' in category '{}' is already mapped to another category", factor.getCode(), category);
                LogUtils.flushAndQuit(context);
            }
        }
    }

    private final ConfigurableApplicationContext context;
    private final DiagnosisPB diagnosisPb;
    private final DoctorScorePB doctorScorePb;
    private final DiseaseMetaRepository diseaseMetaRepo;
}