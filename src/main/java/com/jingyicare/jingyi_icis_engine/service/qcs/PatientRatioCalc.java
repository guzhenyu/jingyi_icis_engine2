package com.jingyicare.jingyi_icis_engine.service.qcs;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Component
@Slf4j
public class PatientRatioCalc {
    public PatientRatioCalc(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientConfig patientConfig,
        @Autowired ApacheIIScoreRepository apacheRepo,
        @Autowired VTECapriniScoreRepository vteCapriniRepo,
        @Autowired VTEPaduaScoreRepository vtePaduaRepo,
        @Autowired PatientScoreRepository patientScoreRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.painScoreGroupCodes = protoService.getConfig().getQualityControl().getPainScoreGroupCodeList();
        this.sedationScoreGroupCodes = protoService.getConfig().getQualityControl().getSedationScoreGroupCodeList();
        this.patientConfig = patientConfig;
        this.apacheRepo = apacheRepo;
        this.vteCapriniRepo = vteCapriniRepo;
        this.vtePaduaRepo = vtePaduaRepo;
        this.patientScoreRepo = patientScoreRepo;
    }

    List<QcPatientStatsPB> calculatePatientStats(
        String deptId, String itemCode, List<PatientRecord> patientRecords,
        LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        List<QcPatientStatsPB> statsList = new ArrayList<>();
        List<LocalDateTime> monthsUtc = QcUtils.buildMonthsUtc(queryStartUtc, queryEndUtc, ZONE_ID);
        Map<Long, List<ApacheIIScore>> apacheScoreMap = new HashMap<>();
        Map<Long, List<VTECapriniScore>> vteCapriniScoreMap = new HashMap<>();
        Map<Long, List<VTEPaduaScore>> vtePaduaScoreMap = new HashMap<>();
        Map<Long, List<PatientScore>> patientScoreMap = new HashMap<>();

        // 准备数据
        List<Long> pids = patientRecords.stream().map(PatientRecord::getId).toList();
        if (itemCode.equals(Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE)
            || itemCode.equals(Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO)
        ) {
            List<ApacheIIScore> apacheList = apacheRepo.findByPidInAndIsDeletedFalse(pids);
            apacheScoreMap = apacheList.stream().collect(Collectors.groupingBy(ApacheIIScore::getPid));
        }
        if (itemCode.equals(Consts.ICU_7_DVT_PREVENTION_RATE)) {
            List<VTECapriniScore> vteCapriniList = vteCapriniRepo.findByPidInAndIsDeletedFalse(pids);
            vteCapriniScoreMap = vteCapriniList.stream().collect(Collectors.groupingBy(VTECapriniScore::getPid));

            List<VTEPaduaScore> vtePaduaList = vtePaduaRepo.findByPidInAndIsDeletedFalse(pids);
            vtePaduaScoreMap = vtePaduaList.stream().collect(Collectors.groupingBy(VTEPaduaScore::getPid));
        }
        if (itemCode.equals(Consts.ICU_9_PAIN_ASSESSMENT_RATE)) {
            List<PatientScore> painScores = patientScoreRepo
                .findByPidInAndScoreGroupCodeInAndIsDeletedFalse(pids, painScoreGroupCodes);
            patientScoreMap = painScores.stream().collect(Collectors.groupingBy(PatientScore::getPid));
        } else if (itemCode.equals(Consts.ICU_10_SEDATION_ASSESSMENT_RATE)) {
            List<PatientScore> sedationScores = patientScoreRepo
                .findByPidInAndScoreGroupCodeInAndIsDeletedFalse(pids, sedationScoreGroupCodes);
            patientScoreMap = sedationScores.stream().collect(Collectors.groupingBy(PatientScore::getPid));
        }

        // 统计
        if (itemCode.equals(Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE)) {
            calculateUnplannedIcuAdmissionStats(patientRecords, monthsUtc, statsList);
            return statsList;
        }
        if (itemCode.equals(Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE)) {
            calculateReadmissionStats(patientRecords, monthsUtc, statsList);
            return statsList;
        }

        for (LocalDateTime monthUtc : monthsUtc) {
            QcPatientStatsPB.Builder patientStatsBuilder = QcPatientStatsPB.newBuilder();

            List<QcPatientInfoPB> qcPatientList = new ArrayList<>();
            for (PatientRecord record : patientRecords) {
                if (itemCode.equals(Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE)) {
                    QcPatientInfoPB patientInfoPb = getApache2PatientInfo(record, monthUtc, apacheScoreMap);
                    if (patientInfoPb != null) qcPatientList.add(patientInfoPb);
                } else if (itemCode.equals(Consts.ICU_7_DVT_PREVENTION_RATE)) {
                    QcPatientInfoPB patientInfoPb = getDVTPreventionPatientInfo(
                        record, monthUtc, vteCapriniScoreMap, vtePaduaScoreMap);
                    if (patientInfoPb != null) qcPatientList.add(patientInfoPb);
                } else if (itemCode.equals(Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO)) {
                    QcPatientInfoPB patientInfoPb = getStandardizedMortalityPatientInfo(record, monthUtc, apacheScoreMap);
                    if (patientInfoPb != null) qcPatientList.add(patientInfoPb);
                } else if (itemCode.equals(Consts.ICU_9_PAIN_ASSESSMENT_RATE) ||
                    itemCode.equals(Consts.ICU_10_SEDATION_ASSESSMENT_RATE)
                ) {
                    QcPatientInfoPB patientInfoPb = getPatientInfoByNursingScores(
                        record, monthUtc, patientScoreMap);
                    if (patientInfoPb != null) qcPatientList.add(patientInfoPb);
                }
            }

            QcPatientStatsPB statsPb = buildQcPatientStats(
                patientStatsBuilder, itemCode, monthUtc, qcPatientList
            );
            statsList.add(statsPb);
        }

        return statsList;
    }

    private QcPatientInfoPB getApache2PatientInfo(
        PatientRecord record, LocalDateTime monthUtc, Map<Long, List<ApacheIIScore>> apacheScoreMap
    ) {
        // 仅统计同期患者
        LocalDateTime admissionTime = record.getAdmissionTime();
        if (admissionTime == null || admissionTime.isBefore(monthUtc)
            || !admissionTime.isBefore(monthUtc.plusMonths(1))
        ) { 
            return null; 
        }

        // 统计apache评分，选取最高得分
        List<ApacheIIScore> apacheScores = apacheScoreMap.getOrDefault(record.getId(), Collections.emptyList());
        ApacheIIScore apacheScore = null;
        for (ApacheIIScore score : apacheScores) {
            LocalDateTime scoreTime = score.getScoreTime();
            if (scoreTime == null || scoreTime.isBefore(monthUtc)
                || !scoreTime.isBefore(monthUtc.plusMonths(1))
            ) { continue; }
            int apacheIiScore = score.getApacheIiScore() != null ? score.getApacheIiScore() : 0;
            if (apacheScore == null || apacheIiScore > apacheScore.getApacheIiScore()) {
                apacheScore = score;
            }
        }

        // 是否>=15分
        LocalDateTime scoreTime = apacheScore != null ? apacheScore.getScoreTime() : null;
        int apache2Score = (apacheScore != null && apacheScore.getApacheIiScore() != null) ?
            apacheScore.getApacheIiScore() : 0;
        boolean isOver15 = apache2Score >= 15;

        // 组装患者信息
        return QcPatientInfoPB.newBuilder()
            .setPatientId(record.getId())
            .setHisMrn(record.getHisMrn())
            .setPatientName(record.getIcuName())
            .setAdmissionTimeIso8601(TimeUtils.toIso8601String(record.getAdmissionTime(), ZONE_ID))
            .setDischargeTimeIso8601(TimeUtils.toIso8601String(record.getDischargeTime(), ZONE_ID))
            .setIsInNumerator(isOver15)
            .setNumeratorTimeIso8601(TimeUtils.toIso8601String(scoreTime, ZONE_ID))
            .setApache2Score(apache2Score)
            .setIsInDenominator(true)
            .build();
    }

    private QcPatientInfoPB getDVTPreventionPatientInfo(
        PatientRecord record, LocalDateTime monthUtc,
        Map<Long, List<VTECapriniScore>> vteCapriniScoreMap,
        Map<Long, List<VTEPaduaScore>> vtePaduaScoreMap
    ) {
        // 统计当月在科患者
        LocalDateTime admissionTime = record.getAdmissionTime();
        if (admissionTime == null || !admissionTime.isBefore(monthUtc.plusMonths(1))) return null;
        LocalDateTime dischargeTime = record.getDischargeTime();
        if (dischargeTime != null && dischargeTime.isBefore(monthUtc)) return null;

        // 统计VTE，有护理措施时，计入分子
        List<VTECapriniScore> vteCapriniScores = vteCapriniScoreMap
            .getOrDefault(record.getId(), Collections.emptyList());
        List<VTEPaduaScore> vtePaduaScores = vtePaduaScoreMap
            .getOrDefault(record.getId(), Collections.emptyList());
        boolean isInNumerator = false;
        LocalDateTime numeratorTime = null;
        for (VTECapriniScore score : vteCapriniScores) {
            LocalDateTime scoreTime = score.getScoreTime();
            if (scoreTime == null || scoreTime.isBefore(monthUtc)
                || !scoreTime.isBefore(monthUtc.plusMonths(1))
            ) { continue; }
            // 检查VTE预防措施执行情况
            if (score.getPreventionAnticoagulantOnlyExec() != null && score.getPreventionAnticoagulantOnlyExec()) {
                isInNumerator = true;
                numeratorTime = score.getScoreTime();
                break;
            }
            if (score.getPreventionPhysicalOnlyExec() != null && score.getPreventionPhysicalOnlyExec()) {
                isInNumerator = true;
                numeratorTime = score.getScoreTime();
                break;
            }
            if (score.getPreventionAnticoagulantPhysicalExec() != null && score.getPreventionAnticoagulantPhysicalExec()) {
                isInNumerator = true;
                numeratorTime = score.getScoreTime();
                break;
            }
        }
        String scoreNotes = null;
        if (isInNumerator) scoreNotes = "vte-caprin";
        else {
            for (VTEPaduaScore score : vtePaduaScores) {
                LocalDateTime scoreTime = score.getScoreTime();
                if (scoreTime == null || scoreTime.isBefore(monthUtc)
                    || !scoreTime.isBefore(monthUtc.plusMonths(1))
                ) { continue; }
                // 检查VTE预防措施执行情况
                if (score.getPreventionAnticoagulantOnlyExec() != null && score.getPreventionAnticoagulantOnlyExec()) {
                    isInNumerator = true;
                    break;
                }
                if (score.getPreventionPhysicalOnlyExec() != null && score.getPreventionPhysicalOnlyExec()) {
                    isInNumerator = true;
                    break;
                }
                if (score.getPreventionAnticoagulantPhysicalExec() != null && score.getPreventionAnticoagulantPhysicalExec()) {
                    isInNumerator = true;
                    break;
                }
            }
            if (isInNumerator) scoreNotes = "vte-padua";
        }

        // 组装患者信息
        return QcPatientInfoPB.newBuilder()
            .setPatientId(record.getId())
            .setHisMrn(record.getHisMrn())
            .setPatientName(record.getIcuName())
            .setAdmissionTimeIso8601(TimeUtils.toIso8601String(record.getAdmissionTime(), ZONE_ID))
            .setDischargeTimeIso8601(TimeUtils.toIso8601String(record.getDischargeTime(), ZONE_ID))
            .setIsInNumerator(isInNumerator)
            .setNumeratorNotes(scoreNotes)
            .setNumeratorTimeIso8601(TimeUtils.toIso8601String(numeratorTime, ZONE_ID))
            .setIsInDenominator(true)
            .build();
    }

    private QcPatientInfoPB getPatientInfoByNursingScores(
        PatientRecord record, LocalDateTime monthUtc, Map<Long, List<PatientScore>> patientScoreMap
    ) {
        // 统计当月在科患者
        LocalDateTime admissionTime = record.getAdmissionTime();
        if (admissionTime == null || !admissionTime.isBefore(monthUtc.plusMonths(1))) return null;
        LocalDateTime dischargeTime = record.getDischargeTime();
        if (dischargeTime != null && dischargeTime.isBefore(monthUtc)) return null;

        // 统计护理评分
        List<PatientScore> nursingScores = patientScoreMap.getOrDefault(record.getId(), Collections.emptyList());
        if (nursingScores.isEmpty()) return null;

        // 组装患者信息
        QcPatientInfoPB.Builder patientInfoBuilder = QcPatientInfoPB.newBuilder()
            .setPatientId(record.getId())
            .setHisMrn(record.getHisMrn())
            .setPatientName(record.getIcuName())
            .setAdmissionTimeIso8601(TimeUtils.toIso8601String(record.getAdmissionTime(), ZONE_ID))
            .setDischargeTimeIso8601(TimeUtils.toIso8601String(record.getDischargeTime(), ZONE_ID))
            .setIsInDenominator(true);

        // 统计护理评分
        boolean isInNumerator = false;
        LocalDateTime numeratorTime = null;
        String scoreNotes = null;
        for (PatientScore score : nursingScores) {
            LocalDateTime scoreTime = score.getEffectiveTime();
            if (scoreTime == null || scoreTime.isBefore(monthUtc)
                || !scoreTime.isBefore(monthUtc.plusMonths(1))
            ) { continue; }
            isInNumerator = true;
            numeratorTime = scoreTime;
            scoreNotes = score.getScoreGroupCode();
            break;
        }
        patientInfoBuilder.setIsInNumerator(isInNumerator);
        patientInfoBuilder.setNumeratorTimeIso8601(TimeUtils.toIso8601String(numeratorTime, ZONE_ID));
        patientInfoBuilder.setNumeratorNotes(scoreNotes);

        return patientInfoBuilder.build();
    }

    private QcPatientInfoPB getStandardizedMortalityPatientInfo(
        PatientRecord record, LocalDateTime monthUtc, Map<Long, List<ApacheIIScore>> apacheScoreMap
    ) {
        // 统计当月在科患者
        LocalDateTime admissionTime = record.getAdmissionTime();
        if (admissionTime == null || !admissionTime.isBefore(monthUtc.plusMonths(1))) return null;
        LocalDateTime dischargeTime = record.getDischargeTime();
        if (dischargeTime != null && dischargeTime.isBefore(monthUtc)) return null;

        // 统计真实死亡率
        QcPatientInfoPB.Builder patientInfoBuilder = QcPatientInfoPB.newBuilder()
            .setPatientId(record.getId())
            .setHisMrn(record.getHisMrn())
            .setPatientName(record.getIcuName())
            .setAdmissionTimeIso8601(TimeUtils.toIso8601String(record.getAdmissionTime(), ZONE_ID))
            .setDischargeTimeIso8601(TimeUtils.toIso8601String(record.getDischargeTime(), ZONE_ID));
        patientInfoBuilder.setIsInNumerator(true);
        patientInfoBuilder.setIsDead(Objects.equals(record.getDischargedType(), patientConfig.getDischargeTypeDeadId()));
        patientInfoBuilder.setNumeratorTimeIso8601(
            TimeUtils.toIso8601String(record.getDischargedDeathTime(), ZONE_ID)
        );

        // 统计预计死亡率，选取最高得分
        List<ApacheIIScore> apacheScores = apacheScoreMap.getOrDefault(record.getId(), Collections.emptyList());
        ApacheIIScore apacheScore = null;
        for (ApacheIIScore score : apacheScores) {
            LocalDateTime scoreTime = score.getScoreTime();
            if (scoreTime == null || scoreTime.isBefore(monthUtc)
                || !scoreTime.isBefore(monthUtc.plusMonths(1))
            ) { continue; }
            float predictedMortality = score.getPredictedMortalityRate() != null ? score.getPredictedMortalityRate() : 0;
            if (predictedMortality <= 0) continue;
            if (apacheScore == null || predictedMortality > apacheScore.getPredictedMortalityRate()) {
                apacheScore = score;
            }
        }
        if (apacheScore != null) {
            patientInfoBuilder.setIsInDenominator(true);
            patientInfoBuilder.setDenominatorTimeIso8601(
                TimeUtils.toIso8601String(apacheScore.getScoreTime(), ZONE_ID));
            patientInfoBuilder.setPredictedMortalityRate(
                apacheScore.getPredictedMortalityRate());
        }

        return patientInfoBuilder.build();
    }

    private void calculateUnplannedIcuAdmissionStats(
        List<PatientRecord> patientRecords, List<LocalDateTime> monthsUtc, List<QcPatientStatsPB> statsList
    ) {
        // patientRecords 已经按入科时间排序
        int patientIdx = 0;
        for (LocalDateTime monthUtc : monthsUtc) {
            QcPatientStatsPB.Builder patientStatsBuilder = QcPatientStatsPB.newBuilder();
            patientStatsBuilder.setMonthIso8601(TimeUtils.toIso8601String(monthUtc, ZONE_ID));
            List<QcPatientInfoPB> qcPatientList = new ArrayList<>();

            // 用patientIdx扫描取数
            while (patientIdx < patientRecords.size() &&
                patientRecords.get(patientIdx).getAdmissionTime().isBefore(monthUtc)
            ) {  // 快速跳过当月之前的记录
                patientIdx++;
            }
            while (patientIdx < patientRecords.size() &&
                !patientRecords.get(patientIdx).getAdmissionTime().isBefore(monthUtc) &&
                patientRecords.get(patientIdx).getAdmissionTime().isBefore(monthUtc.plusMonths(1))
            ) {
                PatientRecord record = patientRecords.get(patientIdx);
                // 筛选入科来源为“手术室”或者入科类型为“手术”
                boolean isSurgicalType = Objects.equals(record.getAdmissionType(), patientConfig.getAdmissionTypeSurgeryId());
                String src = record.getAdmissionSourceDeptName();
                boolean fromORorPACU = src != null && (
                        src.contains("手术室") || src.contains("复苏室") || src.contains("麻醉恢复室") || src.contains("PACU")
                );
                boolean isSurgical = isSurgicalType || fromORorPACU;
                if (!isSurgical) { patientIdx++; continue; }
                boolean isUnplanned = record.getIsPlannedAdmission() != null && !record.getIsPlannedAdmission();

                // 组装
                QcPatientInfoPB patientInfoPb = QcPatientInfoPB.newBuilder()
                    .setPatientId(record.getId())
                    .setHisMrn(record.getHisMrn())
                    .setPatientName(record.getIcuName())
                    .setAdmissionTimeIso8601(TimeUtils.toIso8601String(record.getAdmissionTime(), ZONE_ID))
                    .setDischargeTimeIso8601(TimeUtils.toIso8601String(record.getDischargeTime(), ZONE_ID))
                    .setIsInDenominator(true)
                    .setIsInNumerator(isUnplanned)
                    .build();
                qcPatientList.add(patientInfoPb);

                patientIdx++;
            }

            QcPatientStatsPB statsPb = buildQcPatientStats(
                patientStatsBuilder, Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE,
                monthUtc, qcPatientList
            );
            statsList.add(statsPb);
        }
    }

    private void calculateReadmissionStats(
        List<PatientRecord> patientRecords, List<LocalDateTime> monthsUtc, List<QcPatientStatsPB> statsList
    ) {
        // 按照MRN分组，组内按照入科时间排序
        Map<String, List<PatientRecord>> mrnRecordsMap = patientRecords.stream()
            .collect(Collectors.groupingBy(PatientRecord::getHisMrn));
        for (Map.Entry<String, List<PatientRecord>> entry : mrnRecordsMap.entrySet()) {
            List<PatientRecord> records = entry.getValue();
            records.sort(Comparator.comparing(PatientRecord::getAdmissionTime));
        }

        // 分月统计
        for (LocalDateTime monthUtc : monthsUtc) {
            QcPatientStatsPB.Builder patientStatsBuilder = QcPatientStatsPB.newBuilder();
            patientStatsBuilder.setMonthIso8601(TimeUtils.toIso8601String(monthUtc, ZONE_ID));
            List<QcPatientInfoPB> qcPatientList = new ArrayList<>();

            for (Map.Entry<String, List<PatientRecord>> entry : mrnRecordsMap.entrySet()) {
                List<PatientRecord> records = entry.getValue();
                int recordSize = records.size();

                // 统计出科患者
                List<Integer> dischargeIdxList = new ArrayList<>();
                for (int i = 0; i < records.size(); i++) {
                    PatientRecord record = records.get(i);
                    if (record.getAdmissionStatus() != patientConfig.getDischargedId()) continue;
                    LocalDateTime dischargeTime = record.getDischargeTime();
                    if (dischargeTime == null) continue;
                    if (!dischargeTime.isBefore(monthUtc) && dischargeTime.isBefore(monthUtc.plusMonths(1))) {
                        dischargeIdxList.add(i);
                    }
                }

                // 统计48h内再入科患者
                boolean isInDenominator = false, isInNumerator = false;
                for (Integer idx : dischargeIdxList) {
                    // 确认当前病人记录是否符合48h内再入科
                    PatientRecord record = records.get(idx);
                    LocalDateTime dischargeTime = record.getDischargeTime();
                    LocalDateTime readmissionTime = idx == recordSize - 1 ? null : records.get(idx + 1).getAdmissionTime();
                    boolean in48h = readmissionTime != null
                        && !readmissionTime.isBefore(dischargeTime)
                        && readmissionTime.isBefore(dischargeTime.plusHours(48));

                    // 组装QcPatientInfoPB
                    QcPatientInfoPB.Builder patientInfoBuilder = QcPatientInfoPB.newBuilder()
                        .setPatientId(record.getId())
                        .setHisMrn(record.getHisMrn())
                        .setPatientName(record.getIcuName())
                        .setAdmissionTimeIso8601(TimeUtils.toIso8601String(record.getAdmissionTime(), ZONE_ID))
                        .setDischargeTimeIso8601(TimeUtils.toIso8601String(record.getDischargeTime(), ZONE_ID));
                    if (!isInDenominator) { isInDenominator = true; patientInfoBuilder.setIsInDenominator(true); }
                    if (in48h) {
                        if (!isInNumerator) { isInNumerator = true; patientInfoBuilder.setIsInNumerator(true); }
                        patientInfoBuilder.setShownInNumerator(true);
                        patientInfoBuilder.setNumeratorTimeIso8601(TimeUtils.toIso8601String(readmissionTime, ZONE_ID));
                    }
                    qcPatientList.add(patientInfoBuilder.build());
                }
            }
            QcPatientStatsPB statsPb = buildQcPatientStats(
                patientStatsBuilder, Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE,
                monthUtc, qcPatientList
            );
            statsList.add(statsPb);
        }
    }

    private QcPatientStatsPB buildQcPatientStats(
        QcPatientStatsPB.Builder patientStatsBuilder, String itemCode,
        LocalDateTime monthStartUtc, List<QcPatientInfoPB> qcPatientList
    ) {
        patientStatsBuilder.setMonthIso8601(TimeUtils.toIso8601String(monthStartUtc, ZONE_ID));

        boolean addInfoList = false;
        float numerator = 0, denominator = 0;
        if (itemCode.equals(Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE))  {  // APACHEⅡ评分 ≥ 15分患者收治率
            for (QcPatientInfoPB patientInfo : qcPatientList) {
                if (patientInfo.getApache2Score() >= 15) {
                    numerator += 1;
                }
                denominator += 1;
            }
        } else if (itemCode.equals(Consts.ICU_7_DVT_PREVENTION_RATE)) {  // DVT预防措施执行率
            for (QcPatientInfoPB patientInfo : qcPatientList) {
                if (patientInfo.getIsInNumerator()) {
                    numerator += 1;
                }
                denominator += 1;
            }
        } else if (itemCode.equals(Consts.ICU_9_PAIN_ASSESSMENT_RATE)) {  // 镇痛评估率
            for (QcPatientInfoPB patientInfo : qcPatientList) {
                if (patientInfo.getIsInNumerator()) {
                    numerator += 1;
                }
                denominator += 1;
            }
        } else if (itemCode.equals(Consts.ICU_10_SEDATION_ASSESSMENT_RATE)) {  // 镇静评估率
            for (QcPatientInfoPB patientInfo : qcPatientList) {
                if (patientInfo.getIsInNumerator()) {
                    numerator += 1;
                }
                denominator += 1;
            }
        } else if (itemCode.equals(Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO)) {  // 标化病死指数
            double patientDeathCnt = 0, predictedMortalityRateSum = 0;
            int numeratorCnt = 0, denominatorCnt = 0;
            for (QcPatientInfoPB patientInfo : qcPatientList) {
                if (patientInfo.getIsInNumerator()) {
                    if (patientInfo.getIsDead()) patientDeathCnt += 1;
                    numeratorCnt += 1;
                }
                if (patientInfo.getIsInDenominator()) {
                    predictedMortalityRateSum += patientInfo.getPredictedMortalityRate();
                    denominatorCnt += 1;
                }
            }
            numerator = numeratorCnt > 0 ? (float) (patientDeathCnt / numeratorCnt) : 0f;
            denominator = denominatorCnt > 0 ? (float) (predictedMortalityRateSum / denominatorCnt) : 0f;
        } else if (itemCode.equals(Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE)) {  // 非计划转入ICU率
            for (QcPatientInfoPB patientInfo : qcPatientList) {
                if (patientInfo.getIsInNumerator()) {
                    numerator += 1;
                }
                denominator += 1;
            }
        } else if (itemCode.equals(Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE)) {  // 转出ICU后48h内重返率
            for (QcPatientInfoPB patientInfo : qcPatientList) {
                if (patientInfo.getIsInNumerator()) {
                    numerator += 1;
                }
                if (patientInfo.getIsInDenominator()) {
                    denominator += 1;
                }
            }
        }
        patientStatsBuilder.setNumerator(numerator);
        patientStatsBuilder.setDenominator(denominator);

        if (addInfoList) {
            patientStatsBuilder.addAllInfo(qcPatientList);
        }
        return patientStatsBuilder.build();
    }

    public static final String QCP_UNIT = "人";
    public static final String QCP_COL1_TITLE = "病人ID";
    public static final String QCP_COL1_KEY = "patientId";
    public static final String QCP_COL2_TITLE = "病人MRN";
    public static final String QCP_COL2_KEY = "hisMrn";
    public static final String QCP_COL3_TITLE = "病人姓名";
    public static final String QCP_COL3_KEY = "icuName";
    public static final String QCP_COL4_TITLE = "入院时间";
    public static final String QCP_COL4_KEY = "admissionTime";
    public static final String QCP_COL5_TITLE = "出院时间";
    public static final String QCP_COL5_KEY = "dischargeTime";

    public static final String QC4_NUMERATOR_TITLE = "APACHEⅡ评分 ≥ 15分患者";
    public static final String QC4_NUMERATOR_COL1_TITLE = "APACHEⅡ评分";
    public static final String QC4_NUMERATOR_COL1_KEY = "apacheIIScore";
    public static final String QC4_NUMERATOR_COL2_TITLE = "评分时间";
    public static final String QC4_NUMERATOR_COL2_KEY = "scoreTime";
    public static final String QC4_DENOMINATOR_TITLE = "APACHEⅡ评分患者数";
    public static final String QC4_DENOMINATOR_COL1_TITLE = "APACHEⅡ评分";
    public static final String QC4_DENOMINATOR_COL1_KEY = "apacheIIScore";
    public static final String QC4_DENOMINATOR_COL2_TITLE = "评分时间";
    public static final String QC4_DENOMINATOR_COL2_KEY = "scoreTime";

    public static final String QC7_NUMERATOR_TITLE = "DVT预防措施执行患者";
    public static final String QC7_NUMERATOR_COL1_TITLE = "DVT预防措施";
    public static final String QC7_NUMERATOR_COL1_KEY = "dvtPrevention";  // vte-caprin / vte-padua
    public static final String QC7_NUMERATOR_COL2_TITLE = "措施时间";
    public static final String QC7_NUMERATOR_COL2_KEY = "preventionTime";
    public static final String QC7_DENOMINATOR_TITLE = "同期ICU患者";

    public static final String QC9_NUMERATOR_TITLE = "镇痛评估患者";
    public static final String QC9_NUMERATOR_COL1_TITLE = "镇痛评估";  // cpot / bps / nrs
    public static final String QC9_NUMERATOR_COL1_KEY = "painAssessment";
    public static final String QC9_NUMERATOR_COL2_TITLE = "评估时间";
    public static final String QC9_NUMERATOR_COL2_KEY = "assessmentTime";
    public static final String QC9_DENOMINATOR_TITLE = "同期ICU患者";

    public static final String QC10_NUMERATOR_TITLE = "镇静评估患者";
    public static final String QC10_NUMERATOR_COL1_TITLE = "镇静评估";  // rass / sas(sas_sedation_agitation_assessment)
    public static final String QC10_NUMERATOR_COL1_KEY = "sedationAssessment";
    public static final String QC10_NUMERATOR_COL2_TITLE = "评估时间";
    public static final String QC10_NUMERATOR_COL2_KEY = "assessmentTime";
    public static final String QC10_DENOMINATOR_TITLE = "同期ICU患者";

    public static final String QC11_NUMERATOR_TITLE = "死亡患者";
    public static final String QC11_NUMERATOR_COL1_TITLE = "死亡时间";
    public static final String QC11_NUMERATOR_COL1_KEY = "deathTime";
    public static final String QC11_DENOMINATOR_TITLE = "同期ICU患者预计病死率";
    public static final String QC11_DENOMINATOR_COL1_TITLE = "预计病死率";
    public static final String QC11_DENOMINATOR_COL1_KEY = "predictedMortalityRate";
    public static final String QC11_DENOMINATOR_COL2_TITLE = "评估时间";
    public static final String QC11_DENOMINATOR_COL2_KEY = "assessmentTime";

    public static final String QC14_NUMERATOR_TITLE = "非计划入科手术患者";
    public static final String QC14_DENOMINATOR_TITLE = "同期转入ICU手术患者";

    public static final String QC15_NUMERATOR_TITLE = "转出ICU后48h内重返患者";
    public static final String QC15_NUMERATOR_COL1_TITLE = "重返时间";
    public static final String QC15_NUMERATOR_COL1_KEY = "readmissionTime";
    public static final String QC15_DENOMINATOR_TITLE = "同期转出ICU患者";

    public List<QcMonthDataPB> toMonthDataList(String itemCode, List<QcPatientStatsPB> statsList) {
        if (statsList == null || statsList.isEmpty()) return Collections.emptyList();

        final Pair<String, String> titles = getTableTitles(itemCode);
        if (titles == null) return Collections.emptyList();
        final String numeratorTblTitle = titles.getFirst();
        final String denominatorTblTitle = titles.getSecond();

        final List<QcHeaderPB> numeratorHeaders = buildNumeratorHeaders(itemCode);
        final List<QcHeaderPB> denominatorHeaders = buildDenominatorHeaders(itemCode);

        List<QcMonthDataPB> out = new ArrayList<>(statsList.size());
        for (QcPatientStatsPB statsPb : statsList) {
            // 分子表格
            QcTablePB.Builder numeratorTbl = QcTablePB.newBuilder().setTitle(numeratorTblTitle);
            numeratorHeaders.forEach(numeratorTbl::addHeader);

            // 分母表格
            QcTablePB.Builder denominatorTbl = QcTablePB.newBuilder().setTitle(denominatorTblTitle);
            denominatorHeaders.forEach(denominatorTbl::addHeader);

            // 填写表格数据
            for (QcPatientInfoPB qcPatient : statsPb.getInfoList()) {
                if (qcPatient.getShownInNumerator() || qcPatient.getIsInNumerator()) {  // 填写分子表格数据
                    QcRowPB.Builder row = QcRowPB.newBuilder();
                    fillQcpData(qcPatient, row);
                    fillQcNumeratorData(itemCode, qcPatient, row);
                    numeratorTbl.addRow(row.build());
                }
                if (qcPatient.getIsInDenominator()) {  // 填写分母表格数据
                    QcRowPB.Builder row = QcRowPB.newBuilder();
                    fillQcpData(qcPatient, row);
                    fillQcDenominatorData(itemCode, qcPatient, row);
                    denominatorTbl.addRow(row.build());
                }
            }

            // 汇总一个月
            QcMonthDataPB monthData = QcMonthDataPB.newBuilder()
                .setMonthIso8601(statsPb.getMonthIso8601())
                .setNumerator((float) statsPb.getNumerator())
                .setDenominator((float) statsPb.getDenominator())
                .setUnit(QCP_UNIT)
                .setNumeratorTbl(numeratorTbl.build())
                .setDenominatorTbl(denominatorTbl.build())
                .build();
            out.add(monthData);
        }

        return out;
    }

    // 公共列构造（title 按需透传，当前未在列里使用，保留接口以便后续需要）
    private static List<QcHeaderPB> buildCommonQcpHeaders() {
        return Arrays.asList(
            QcHeaderPB.newBuilder().setTitle(QCP_COL1_TITLE).setKey(QCP_COL1_KEY).build(),
            QcHeaderPB.newBuilder().setTitle(QCP_COL2_TITLE).setKey(QCP_COL2_KEY).build(),
            QcHeaderPB.newBuilder().setTitle(QCP_COL3_TITLE).setKey(QCP_COL3_KEY).build(),
            QcHeaderPB.newBuilder().setTitle(QCP_COL4_TITLE).setKey(QCP_COL4_KEY).build(),
            QcHeaderPB.newBuilder().setTitle(QCP_COL5_TITLE).setKey(QCP_COL5_KEY).build()
        );
    }

    private static List<QcHeaderPB> buildNumeratorHeaders(String code) {
        // 先加入公共列
        List<QcHeaderPB> headers = new ArrayList<>(buildCommonQcpHeaders());

        // 再追加各 code 独有列
        switch (code) {
            case Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE:
                headers.add(QcHeaderPB.newBuilder().setTitle(QC4_NUMERATOR_COL1_TITLE).setKey(QC4_NUMERATOR_COL1_KEY).build());
                headers.add(QcHeaderPB.newBuilder().setTitle(QC4_NUMERATOR_COL2_TITLE).setKey(QC4_NUMERATOR_COL2_KEY).build());
                break;

            case Consts.ICU_7_DVT_PREVENTION_RATE:
                headers.add(QcHeaderPB.newBuilder().setTitle(QC7_NUMERATOR_COL1_TITLE).setKey(QC7_NUMERATOR_COL1_KEY).build());
                headers.add(QcHeaderPB.newBuilder().setTitle(QC7_NUMERATOR_COL2_TITLE).setKey(QC7_NUMERATOR_COL2_KEY).build());
                break;

            case Consts.ICU_9_PAIN_ASSESSMENT_RATE:
                headers.add(QcHeaderPB.newBuilder().setTitle(QC9_NUMERATOR_COL1_TITLE).setKey(QC9_NUMERATOR_COL1_KEY).build());
                headers.add(QcHeaderPB.newBuilder().setTitle(QC9_NUMERATOR_COL2_TITLE).setKey(QC9_NUMERATOR_COL2_KEY).build());
                break;

            case Consts.ICU_10_SEDATION_ASSESSMENT_RATE:
                headers.add(QcHeaderPB.newBuilder().setTitle(QC10_NUMERATOR_COL1_TITLE).setKey(QC10_NUMERATOR_COL1_KEY).build());
                headers.add(QcHeaderPB.newBuilder().setTitle(QC10_NUMERATOR_COL2_TITLE).setKey(QC10_NUMERATOR_COL2_KEY).build());
                break;

            case Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO:
                headers.add(QcHeaderPB.newBuilder().setTitle(QC11_NUMERATOR_COL1_TITLE).setKey(QC11_NUMERATOR_COL1_KEY).build());
                break;

            case Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE:
                // 分子仅公共列：非计划入科手术患者
                break;

            case Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE:
                headers.add(QcHeaderPB.newBuilder().setTitle(QC15_NUMERATOR_COL1_TITLE).setKey(QC15_NUMERATOR_COL1_KEY).build());
                break;
        }

        return headers;
    }

    private static List<QcHeaderPB> buildDenominatorHeaders(String code) {
        // 先加入公共列
        List<QcHeaderPB> headers = new ArrayList<>(buildCommonQcpHeaders());

        // 再追加各 code 独有列
        switch (code) {
            case Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE:
                headers.add(QcHeaderPB.newBuilder().setTitle(QC4_DENOMINATOR_COL1_TITLE).setKey(QC4_DENOMINATOR_COL1_KEY).build());
                headers.add(QcHeaderPB.newBuilder().setTitle(QC4_DENOMINATOR_COL2_TITLE).setKey(QC4_DENOMINATOR_COL2_KEY).build());
                break;

            case Consts.ICU_7_DVT_PREVENTION_RATE:
                // 分母：同期ICU患者， 仅公共列即可
                break;

            case Consts.ICU_9_PAIN_ASSESSMENT_RATE:
                // 分母：同期ICU患者， 仅公共列即可
                break;

            case Consts.ICU_10_SEDATION_ASSESSMENT_RATE:
                // 分母：同期ICU患者， 仅公共列即可
                break;

            case Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO:
                headers.add(QcHeaderPB.newBuilder().setTitle(QC11_DENOMINATOR_COL1_TITLE).setKey(QC11_DENOMINATOR_COL1_KEY).build());
                headers.add(QcHeaderPB.newBuilder().setTitle(QC11_DENOMINATOR_COL2_TITLE).setKey(QC11_DENOMINATOR_COL2_KEY).build());
                break;

            case Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE:
                // 分母：同期转入ICU手术患者， 仅公共列即可
                break;

            case Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE:
                // 分母：同期转出ICU患者， 仅公共列即可
                break;
        }

        return headers;
    }

    private Pair<String/*numerator*/, String/*denominator*/> getTableTitles(String code) {
        switch (code) {
            case Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE:
                return new Pair<>(QC4_NUMERATOR_TITLE, QC4_DENOMINATOR_TITLE);
            case Consts.ICU_7_DVT_PREVENTION_RATE:
                return new Pair<>(QC7_NUMERATOR_TITLE, QC7_DENOMINATOR_TITLE);
            case Consts.ICU_9_PAIN_ASSESSMENT_RATE:
                return new Pair<>(QC9_NUMERATOR_TITLE, QC9_DENOMINATOR_TITLE);
            case Consts.ICU_10_SEDATION_ASSESSMENT_RATE:
                return new Pair<>(QC10_NUMERATOR_TITLE, QC10_DENOMINATOR_TITLE);
            case Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO:
                return new Pair<>(QC11_NUMERATOR_TITLE, QC11_DENOMINATOR_TITLE);
            case Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE:
                return new Pair<>(QC14_NUMERATOR_TITLE, QC14_DENOMINATOR_TITLE);
            case Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE:
                return new Pair<>(QC15_NUMERATOR_TITLE, QC15_DENOMINATOR_TITLE);
        }
        return null;
    }

    private void fillQcpData(QcPatientInfoPB qcPatient, QcRowPB.Builder rowBuilder) {
        rowBuilder.putData(QCP_COL1_KEY, String.valueOf(qcPatient.getPatientId()));
        rowBuilder.putData(QCP_COL2_KEY, qcPatient.getHisMrn());
        rowBuilder.putData(QCP_COL3_KEY, qcPatient.getPatientName());
        rowBuilder.putData(QCP_COL4_KEY, qcPatient.getAdmissionTimeIso8601());
        rowBuilder.putData(QCP_COL5_KEY, qcPatient.getDischargeTimeIso8601());
    }

    private void fillQcNumeratorData(
        String code, QcPatientInfoPB qcPatient, QcRowPB.Builder rowBuilder
    ) {
        switch (code) {
            case Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE:
                rowBuilder.putData(QC4_NUMERATOR_COL1_KEY, String.valueOf(qcPatient.getApache2Score()));
                rowBuilder.putData(QC4_NUMERATOR_COL2_KEY, qcPatient.getNumeratorTimeIso8601());
                return;
            case Consts.ICU_7_DVT_PREVENTION_RATE:
                rowBuilder.putData(QC7_NUMERATOR_COL1_KEY, qcPatient.getNumeratorNotes());
                rowBuilder.putData(QC7_NUMERATOR_COL2_KEY, qcPatient.getNumeratorTimeIso8601());
                return;
            case Consts.ICU_9_PAIN_ASSESSMENT_RATE:
                rowBuilder.putData(QC9_NUMERATOR_COL1_KEY, qcPatient.getNumeratorNotes());
                rowBuilder.putData(QC9_NUMERATOR_COL2_KEY, qcPatient.getNumeratorTimeIso8601());
                return;
            case Consts.ICU_10_SEDATION_ASSESSMENT_RATE:
                rowBuilder.putData(QC10_NUMERATOR_COL1_KEY, qcPatient.getNumeratorNotes());
                rowBuilder.putData(QC10_NUMERATOR_COL2_KEY, qcPatient.getNumeratorTimeIso8601());
                return;
            case Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO:
                rowBuilder.putData(QC11_NUMERATOR_COL1_KEY, qcPatient.getNumeratorTimeIso8601());
                return;
            case Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE:
                // 病人信息已足够，无需额外信息
                return;
            case Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE:
                rowBuilder.putData(QC15_NUMERATOR_COL1_KEY, qcPatient.getNumeratorTimeIso8601());
                return;
        }
    }

    private void fillQcDenominatorData(
        String code, QcPatientInfoPB qcPatient, QcRowPB.Builder rowBuilder
    ) {
        switch (code) {
            case Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE:
                rowBuilder.putData(QC4_DENOMINATOR_COL1_KEY, String.valueOf(qcPatient.getApache2Score()));
                rowBuilder.putData(QC4_DENOMINATOR_COL2_KEY, qcPatient.getDenominatorTimeIso8601());
                return;
            case Consts.ICU_7_DVT_PREVENTION_RATE:
                // 分母展示同期患者信息就够了
                return;
            case Consts.ICU_9_PAIN_ASSESSMENT_RATE:
                // 分母展示同期患者信息就够了
                return;
            case Consts.ICU_10_SEDATION_ASSESSMENT_RATE:
                // 分母展示同期患者信息就够了
                return;
            case Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO:
                rowBuilder.putData(QC11_DENOMINATOR_COL1_KEY, String.valueOf(qcPatient.getPredictedMortalityRate()));
                rowBuilder.putData(QC11_DENOMINATOR_COL2_KEY, qcPatient.getDenominatorTimeIso8601());
                return;
            case Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE:
                // 病人信息已足够，无需额外信息
                return;
            case Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE:
                return;
        }
    }

    final String ZONE_ID;
    final List<String> painScoreGroupCodes;
    final List<String> sedationScoreGroupCodes;
    final PatientConfig patientConfig;

    final ApacheIIScoreRepository apacheRepo;
    final VTECapriniScoreRepository vteCapriniRepo;
    final VTEPaduaScoreRepository vtePaduaRepo;
    final PatientScoreRepository patientScoreRepo;
}