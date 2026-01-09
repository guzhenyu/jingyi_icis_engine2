package com.jingyicare.jingyi_icis_engine.service.doctors;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDiagnosis.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingScore.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.doctors.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.scores.*;
import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.repository.doctors.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.scores.*;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.scores.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class DoctorDataFetcher {
    // 观察项
    public static final String SYSTOLIC_PRESSURE_LE_100MMHG = "SYSTOLIC_PRESSURE_LE_100MMHG";
    public static final String RESPIRATORY_RATE_GT_22 = "RESPIRATORY_RATE_GT_22";
    public static final String FEVER_GT_38C = "FEVER_GT_38C";

    // 评分
    public static final String GCS_LT_13 = "GCS_LT_13";

    // 管道
    public static final String PICC_GT_48H = "PICC_GT_48H";
    public static final String CATHETER_GT_48H = "CATHETER_GT_48H";

    public enum CompOp {
        LE,  // 小于等于 (<=)
        GE,  // 大于等于 (>=)
        GT,  // 大于 (>)
        LT,  // 小于 (<)
        EQ   // 等于 (=)
    }

    @AllArgsConstructor
    public static class MonitoringItemConfig {
        public List<String> monitoringCodes;  // ibp_s
        public boolean useMinValue;
        public float threshold;
        public CompOp op;
    }

    @AllArgsConstructor
    public static class TubeDurationConfig {
        public String tubeType;
        public boolean useMinValue;
        public int durationMinutes;
        public CompOp op;
    }

    public DoctorDataFetcher(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired ScoreConfig scoreConfig,
        @Autowired TubeSetting tubeSetting,
        @Autowired PatientMonitoringRecordRepository patientMonRecRepo,
        @Autowired PatientScoreRepository patientScoreRepo,
        @Autowired PatientTubeRecordRepository patientTubeRecordRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();

        // 观察项
        this.IBP_S_MONITORING_CODE = protoService.getConfig().getDiagnosis().getIbpSMonitoringCode();
        this.NIBP_S_MONITORING_CODE = protoService.getConfig().getDiagnosis().getNibpSMonitoringCode();
        this.RESPIRATORY_RATE_MONITORING_CODE = protoService.getConfig().getDiagnosis().getRespiratoryRateMonitoringCode();
        this.TEMPERATURE_MONITORING_CODE = protoService.getConfig().getDiagnosis().getTemperatureMonitoringCode();
        this.monitoringItemConfigs = Map.of(
            SYSTOLIC_PRESSURE_LE_100MMHG, new MonitoringItemConfig(
                List.of(IBP_S_MONITORING_CODE, NIBP_S_MONITORING_CODE), true, 100.0f, CompOp.LE),
            RESPIRATORY_RATE_GT_22, new MonitoringItemConfig(
                List.of(RESPIRATORY_RATE_MONITORING_CODE), false, 22.0f, CompOp.GT),
            FEVER_GT_38C, new MonitoringItemConfig(
                List.of(TEMPERATURE_MONITORING_CODE), false, 38.0f, CompOp.GT)
        );

        // 评分项
        this.GCS_SCORE_CODE = protoService.getConfig().getDiagnosis().getGcsScoreCode();
        this.scoreItemConfigs = Map.of(
            GCS_LT_13, new MonitoringItemConfig(List.of(GCS_SCORE_CODE), true, 13.0f, CompOp.LE)
        );

        // 管道
        this.PICC_TUBE_TYPE = protoService.getConfig().getDiagnosis().getPiccTubeType();
        this.CATHETER_TUBE_TYPE = protoService.getConfig().getDiagnosis().getCatheterTubeType();
        this.tubeDurationConfigs = Map.of(
            PICC_GT_48H, new TubeDurationConfig(PICC_TUBE_TYPE, false, 48 * 60, CompOp.GT),
            CATHETER_GT_48H, new TubeDurationConfig(CATHETER_TUBE_TYPE, false, 48 * 60, CompOp.GT)
        );

        this.protoService = protoService;
        this.monitoringConfig = monitoringConfig;
        this.scoreConfig = scoreConfig;
        this.tubeSetting = tubeSetting;
        this.patientMonRecRepo = patientMonRecRepo;
        this.patientScoreRepo = patientScoreRepo;
        this.patientTubeRecordRepo = patientTubeRecordRepo;
    }

    public DiseaseItemPB fetchDiseaseItem(
        Long pid, String deptId, String fetchCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        if (fetchCode.equals(SYSTOLIC_PRESSURE_LE_100MMHG) ||
            fetchCode.equals(RESPIRATORY_RATE_GT_22) ||
            fetchCode.equals(FEVER_GT_38C)
        ) {
            Pair<Boolean/*found*/, DiseaseItemDetailsPB> result = fetchMonitoringItem(
                pid, deptId, fetchCode, queryStartUtc, queryEndUtc, monitoringItemConfigs.get(fetchCode));
            if (result == null || !result.getFirst()) return null;
            return result.getSecond().getItem();
        }

        if (fetchCode.equals(GCS_LT_13)) {
            Pair<Boolean/*found*/, DiseaseItemDetailsPB> result = fetchScoreItem(
                pid, deptId, fetchCode, queryStartUtc, queryEndUtc, scoreItemConfigs.get(fetchCode));
            if (result == null || !result.getFirst()) return null;
            return result.getSecond().getItem();
        }

        if (fetchCode.equals(PICC_GT_48H) || fetchCode.equals(CATHETER_GT_48H)) {
            return fetchTubeItem(pid, deptId, fetchCode, queryStartUtc, queryEndUtc, tubeDurationConfigs.get(fetchCode));
        }

        return null;
    }

    public DiseaseItemDetailsPB fetchDiseaseItemDetails(
        Long pid, String deptId, String fetchCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc
    ) {
        if (fetchCode.equals(SYSTOLIC_PRESSURE_LE_100MMHG) ||
            fetchCode.equals(RESPIRATORY_RATE_GT_22) ||
            fetchCode.equals(FEVER_GT_38C)
        ) {
            Pair<Boolean/*found*/, DiseaseItemDetailsPB> result = fetchMonitoringItem(
                pid, deptId, fetchCode, queryStartUtc, queryEndUtc, monitoringItemConfigs.get(fetchCode));
            if (result == null) return null;
            return result.getSecond();
        }

        if (fetchCode.equals(GCS_LT_13)) {
            Pair<Boolean/*found*/, DiseaseItemDetailsPB> result = fetchScoreItem(
                pid, deptId, fetchCode, queryStartUtc, queryEndUtc, scoreItemConfigs.get(fetchCode));
            if (result == null) return null;
            return result.getSecond();
        }

        return null;
    }

    public Pair<Boolean/*found*/, DiseaseItemDetailsPB> fetchMonitoringItem(
        Long pid, String deptId, String fetchCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc,
        MonitoringItemConfig config
    ) {
        if (config == null) return null;
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        queryEndUtc = queryEndUtc.isAfter(nowUtc) ? nowUtc : queryEndUtc;

        // 获取监测参数元数据
        Map<String, ValueMetaPB> valueMetaMap = monitoringConfig.getMonitoringParamMetas(
            deptId, Set.copyOf(config.monitoringCodes));
        if (valueMetaMap.isEmpty()) {
            log.error("No monitoring param meta found for deptId: {}, codes: {}", deptId, config.monitoringCodes);
            return new Pair<>(false, null);
        }

        // 初始化返回值相关变量
        float extremeValue = config.useMinValue ? 10000.0f : -1.0f; // 取最小值时初始化为大值，取最大值时初始化为小值
        GenericValuePB genericValue = null;
        ValueMetaPB valueMeta = null;
        String source = null;
        LocalDateTime effectiveTime = null;
        List<TimedGenericValuePB> origValues = new ArrayList<>();

        // 遍历监测代码（支持优先级）
        for (String monitoringCode : config.monitoringCodes) {
            ValueMetaPB meta = valueMetaMap.get(monitoringCode);
            if (meta == null) continue;

            List<PatientMonitoringRecord> records = patientMonRecRepo
                .findByPidAndParamCodeAndEffectiveTimeRange(
                    pid, monitoringCode, queryStartUtc, queryEndUtc
                ).stream()
                .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
                .toList();

            origValues = new ArrayList<>();
            for (PatientMonitoringRecord record : records) {
                MonitoringValuePB monValPb = ProtoUtils.decodeMonitoringValue(record.getParamValue());
                if (monValPb == null) continue;

                float rawValue = ValueMetaUtils.convertToFloat(monValPb.getValue(), meta);
                if (rawValue <= 0) continue;

                origValues.add(TimedGenericValuePB.newBuilder()
                    .setValue(monValPb.getValue())
                    .setValueStr(getValueStr(monValPb.getValue(), meta))
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                    .build());

                if (config.useMinValue) {
                    if (rawValue < extremeValue) {
                        extremeValue = rawValue;
                        genericValue = monValPb.getValue();
                        valueMeta = meta;
                        source = monitoringCode;
                        effectiveTime = record.getEffectiveTime();
                    }
                } else {
                    if (rawValue > extremeValue) {
                        extremeValue = rawValue;
                        genericValue = monValPb.getValue();
                        valueMeta = meta;
                        source = monitoringCode;
                        effectiveTime = record.getEffectiveTime();
                    }
                }
            }

            // 如果已经找到优先级更高的数据（例如有创数据），直接跳出
            if (genericValue != null) break;
        }

        // 构建 DiseaseItemPB
        if (genericValue == null) {
            log.info("No monitoring data found for pid: {}, code: {}, time range: {} to {}",
                    pid, fetchCode, queryStartUtc, queryEndUtc);
            return new Pair<>(false, null);
        }

        final String origValueStr = getValueStr(genericValue, valueMeta);
        final boolean qualified = switch (config.op) {
            case LE -> extremeValue <= config.threshold;
            case GE -> extremeValue >= config.threshold;
            case GT -> extremeValue > config.threshold;
            case LT -> extremeValue < config.threshold;
            case EQ -> extremeValue == config.threshold;
        };

        DiseaseItemPB diseaseItemPb = DiseaseItemPB.newBuilder()
            .setCode(fetchCode)
            .setValue(GenericValuePB.newBuilder().setBoolVal(qualified).build())
            .setOrigValueStr(origValueStr)
            .setSource(source)
            .setEffectiveTimeIso8601(TimeUtils.toIso8601String(effectiveTime, ZONE_ID))
            .build();

        return new Pair<>(true, DiseaseItemDetailsPB.newBuilder()
            .setOrigValueMeta(valueMeta)
            .addAllOrigValue(origValues)
            .setItem(diseaseItemPb)
            .build());
    }

    public Pair<Boolean/*found*/, DiseaseItemDetailsPB> fetchScoreItem(
        Long pid, String deptId, String fetchCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc,
        MonitoringItemConfig config
    ) {
        if (config == null) return new Pair<>(false, null);
        if (config.monitoringCodes.size() != 1) {
            log.error("Invalid monitoring codes for score item: {}, {}", fetchCode, config.monitoringCodes);
            return new Pair<>(false, null);
        }
        final String code = config.monitoringCodes.get(0);
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        queryEndUtc = queryEndUtc.isAfter(nowUtc) ? nowUtc : queryEndUtc;

        // 获取监测参数元数据
        ScoreGroupMetaPB scoreGroupMeta = scoreConfig.getScoreGroupMeta(code);
        if (scoreGroupMeta == null) {
            log.error("No score group meta found for code: {}", code);
            return new Pair<>(false, null);
        }
        ValueMetaPB valueMeta = scoreGroupMeta.getValueMeta();

        // 初始化返回值相关变量
        float extremeValue = config.useMinValue ? 10000.0f : -1.0f; // 取最小值时初始化为大值，取最大值时初始化为小值
        GenericValuePB genericValue = null;
        String source = null;
        LocalDateTime effectiveTime = null;
        List<TimedGenericValuePB> origValues = new ArrayList<>();

        for (PatientScore record : patientScoreRepo
            .findByPidAndScoreGroupCodeAndEffectiveTimeBetweenAndIsDeletedFalse(pid, code, queryStartUtc, queryEndUtc)
            .stream().sorted(Comparator.comparing(PatientScore::getEffectiveTime)).toList()
        ) {
            ScoreGroupPB scoreGroupPb = ProtoUtils.decodeScoreGroupPB(record.getScore());
            if (scoreGroupPb == null) continue;
            GenericValuePB scoreGenericValue = scoreGroupPb.getGroupScore();

            origValues.add(TimedGenericValuePB.newBuilder()
                .setValue(scoreGenericValue)
                .setValueStr(getValueStr(scoreGenericValue, valueMeta))
                .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                .build());

            float rawValue = ValueMetaUtils.convertToFloat(scoreGenericValue, valueMeta);
            if (rawValue <= 0) continue;

            if (config.useMinValue) {
                if (rawValue < extremeValue) {
                    extremeValue = rawValue;
                    genericValue = scoreGenericValue;
                    source = code;
                    effectiveTime = record.getEffectiveTime();
                }
            } else {
                if (rawValue > extremeValue) {
                    extremeValue = rawValue;
                    genericValue = scoreGenericValue;
                    source = code;
                    effectiveTime = record.getEffectiveTime();
                }
            }
        }

        // 构建 DiseaseItemPB
        if (genericValue == null) {
            log.info("No monitoring data found for pid: {}, code: {}, time range: {} to {}",
                    pid, fetchCode, queryStartUtc, queryEndUtc);
            return new Pair<>(false, DiseaseItemDetailsPB.newBuilder()
                .setOrigValueMeta(valueMeta)
                .addAllOrigValue(origValues)
                .build());
        }

        final String origValueStr = getValueStr(genericValue, valueMeta);
        final boolean qualified = switch (config.op) {
            case LE -> extremeValue <= config.threshold;
            case GE -> extremeValue >= config.threshold;
            case GT -> extremeValue > config.threshold;
            case LT -> extremeValue < config.threshold;
            case EQ -> extremeValue == config.threshold;
        };

        DiseaseItemPB diseaseItemPb = DiseaseItemPB.newBuilder()
            .setCode(fetchCode)
            .setValue(GenericValuePB.newBuilder().setBoolVal(qualified).build())
            .setOrigValueStr(origValueStr)
            .setSource(source)
            .setEffectiveTimeIso8601(TimeUtils.toIso8601String(effectiveTime, ZONE_ID))
            .build();

        return new Pair<>(true, DiseaseItemDetailsPB.newBuilder()
            .setOrigValueMeta(valueMeta)
            .addAllOrigValue(origValues)
            .setItem(diseaseItemPb)
            .build());
    }

    public DiseaseItemPB fetchTubeItem(
        Long pid, String deptId, String fetchCode, LocalDateTime queryStartUtc, LocalDateTime queryEndUtc,
        TubeDurationConfig config
    ) {
        if (config == null) return null;
        List<TubeType> tubeTypes = tubeSetting.findTubeTypes(deptId, config.tubeType);
        if (tubeTypes.size() != 1) {
            log.error("Invalid tube types for tube item: {}, {}", fetchCode, tubeTypes);
            return null;
        }
        final Integer tubeTypeId = tubeTypes.get(0).getId();
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        queryEndUtc = queryEndUtc.isAfter(nowUtc) ? nowUtc : queryEndUtc;

        // 查找插管记录，按照管道类型过滤
        List<Long> rootRecordIds = patientTubeRecordRepo
            .findByPidAndDateRange(pid, queryStartUtc, queryEndUtc)
            .stream()
            .filter(record -> record.getTubeTypeId() == tubeTypeId)
            .map(PatientTubeRecord::getRootTubeRecordId)
            .collect(Collectors.toSet())
            .stream()
            .toList();

        // 根据 rootId 分组
        Map<Long, List<PatientTubeRecord>> rootRecordMap = patientTubeRecordRepo
            .findByRootTubeRecordIdInAndIsDeletedFalse(rootRecordIds)
            .stream()
            .collect(Collectors.groupingBy(PatientTubeRecord::getRootTubeRecordId));
        
        // 统计各分组的累计插管时常，终止时间为 queryEndUtc或removedAt，取最小值
        String source = null;
        LocalDateTime effectiveTime = null;
        Integer extremeDurationMinutes = config.useMinValue ? 1000000 : -1;
        Integer totalMinutes = 0;
        for (List<PatientTubeRecord> records : rootRecordMap.values()) {
            int durationMinutes = 0;
            LocalDateTime insertedAt = null;
            LocalDateTime removedAt = null;
            for (PatientTubeRecord record : records) {
                insertedAt = record.getInsertedAt();
                if (record.getRemovedAt() == null || record.getRemovedAt().isAfter(queryEndUtc)) {
                    removedAt = queryEndUtc;
                } else {
                    removedAt = record.getRemovedAt();
                }
                durationMinutes += Duration.between(insertedAt, removedAt).toMinutes();
            }
            if (config.useMinValue) {
                if (durationMinutes < extremeDurationMinutes) {
                    extremeDurationMinutes = durationMinutes;
                    source = config.tubeType;
                    effectiveTime = insertedAt;
                }
            } else {
                if (durationMinutes > extremeDurationMinutes) {
                    extremeDurationMinutes = durationMinutes;
                    source = config.tubeType;
                    effectiveTime = insertedAt;
                }
            }
            totalMinutes += durationMinutes;
        }

        // 构建 DiseaseItemPB
        if (source == null) {
            log.info("No tube data found for pid: {}, code: {}, time range: {} to {}",
                    pid, fetchCode, queryStartUtc, queryEndUtc);
            return null;
        }

        final int totalHours = totalMinutes / 60;
        final String origValueStr = String.format("%d小时", totalHours);
        final boolean qualified = switch (config.op) {
            case LE -> totalMinutes <= config.durationMinutes;
            case GE -> totalMinutes >= config.durationMinutes;
            case GT -> totalMinutes > config.durationMinutes;
            case LT -> totalMinutes < config.durationMinutes;
            case EQ -> totalMinutes == config.durationMinutes;
        };
        return DiseaseItemPB.newBuilder()
            .setCode(fetchCode)
            .setValue(GenericValuePB.newBuilder().setBoolVal(qualified).build())
            .setOrigValueStr(origValueStr)
            .setSource(source)
            .setEffectiveTimeIso8601(TimeUtils.toIso8601String(effectiveTime, ZONE_ID))
            .build();
    }

    private String getValueStr(GenericValuePB genericValue, ValueMetaPB valueMeta) {
        if (genericValue == null || valueMeta == null) return "";
        return ValueMetaUtils.extractAndFormatParamValue(genericValue, valueMeta) + valueMeta.getUnit();
    }

    private final String ZONE_ID;
    private final String IBP_S_MONITORING_CODE;
    private final String NIBP_S_MONITORING_CODE;
    private final String RESPIRATORY_RATE_MONITORING_CODE;
    private final String TEMPERATURE_MONITORING_CODE;
    private final String GCS_SCORE_CODE;
    private final String PICC_TUBE_TYPE;
    private final String CATHETER_TUBE_TYPE;
    private final Map<String, MonitoringItemConfig> monitoringItemConfigs;
    private final Map<String, MonitoringItemConfig> scoreItemConfigs;
    private final Map<String, TubeDurationConfig> tubeDurationConfigs;

    private final ConfigProtoService protoService;
    private final MonitoringConfig monitoringConfig;
    private final ScoreConfig scoreConfig;
    private final TubeSetting tubeSetting;
    private final PatientMonitoringRecordRepository patientMonRecRepo;
    private final PatientScoreRepository patientScoreRepo;
    private final PatientTubeRecordRepository patientTubeRecordRepo;
}

/*
items {  // todo: 新增血气表；血气参数(device_data => 单独的血气表 => 从血气表中取出)  时间范围内最高值
    code: "LACTATE_GE_2MMOL_L"
    name: "血乳酸>=2mmol/L"
    description: "血乳酸浓度大于等于2mmol/L"
    value_meta {
        value_type: BOOL
    }
    auto_calculated: true
}
*/