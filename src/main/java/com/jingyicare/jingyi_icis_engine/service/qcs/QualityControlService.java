package com.jingyicare.jingyi_icis_engine.service.qcs;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.users.UserService;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class QualityControlService {
    public QualityControlService(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientService patientService,
        @Autowired BedUtilizationCalc bedUtilizationCalc,
        @Autowired AccountBedRatioCalc accountBedRatioCalc,
        @Autowired PatientRatioCalc patientRatioCalc
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        // 质控项字典
        QcConfigPB qcConfig = protoService.getConfig().getQualityControl();
        this.qcConfig = qcConfig;
        this.qcItemMap = qcConfig.getItemList().stream()
            .collect(Collectors.toMap(QcItemPB::getCode, item -> item, (a, b) -> a));

        this.protoService = protoService;
        this.patientService = patientService;

        this.bedUtilizationCalc = bedUtilizationCalc;
        this.accountBedRatioCalc = accountBedRatioCalc;
        this.patientRatioCalc = patientRatioCalc;
    }

    @Transactional
    public GetNHCQCItemsResp getNHCQCItems(String getNHCQCItemsReqJson) {
        final GetNHCQCItemsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNHCQCItemsReqJson, GetNHCQCItemsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNHCQCItemsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // TODO: 实现获取NHC质控项列表的逻辑
        List<StrKeyValPB> items = qcConfig.getItemList().stream()
            .map(item -> StrKeyValPB.newBuilder()
                .setKey(item.getCode())
                .setVal(item.getName())
                .build())
            .toList();

        return GetNHCQCItemsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK))
            .addAllItem(items)
            .build();
    }

    @Transactional
    public GetNHCQCDataResp getNHCQCData(String getNHCQCDataReqJson) {
        final GetNHCQCDataReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getNHCQCDataReqJson, GetNHCQCDataReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert JSON to proto: {}", e.getMessage(), e);
            return GetNHCQCDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取关键参数
        String deptId = req.getDeptId();
        if (StrUtils.isBlank(deptId)) {
            log.error("Department ID is required but not provided.");
            return GetNHCQCDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.DEPARTMENT_NOT_FOUND))
                .build();
        }
        String itemCode = req.getItemCode();
        QcItemPB qcItem = qcItemMap.get(itemCode);
        if (qcItem == null) {
            log.error("Invalid item code: {}", itemCode);
            return GetNHCQCDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.INVALID_QC_ITEM_CODE))
                .build();
        }
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            log.error("Invalid date range: {} - {}", req.getQueryStartIso8601(), req.getQueryEndIso8601());
            return GetNHCQCDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.INVALID_TIME_RANGE))
                .build();
        }
        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (queryEndUtc.isAfter(nowUtc)) queryEndUtc = nowUtc;

        // 获取符合条件的患者
        List<PatientRecord> patientRecords = patientService.getPatientRecords(deptId, queryStartUtc, queryEndUtc);
        patientRecords.sort(Comparator.comparing(PatientRecord::getAdmissionTime));

        // TODO: 实现获取质控数据的逻辑
        List<QcMonthDataPB> monthData = new ArrayList<>();
        if (itemCode.equals(Consts.ICU_1_ICU_BED_UTILIZATION_RATE)) {  // 2024 QC_01/19
            List<QcICUBedUtilizationRatioPB> rates = bedUtilizationCalc.calcRates(
                deptId, patientRecords, queryStartUtc, queryEndUtc, ZONE_ID
            );
            monthData = bedUtilizationCalc.toMonthDataList(rates);
        } else if (itemCode.equals(Consts.ICU_2_ICU_DOCTOR_BED_RATIO)) {  // 2024 QC_02/19
            List<QcICUAccountBedRatioPB> ratios = accountBedRatioCalc.calcMonthly(
                deptId, itemCode, queryStartUtc, queryEndUtc
            );
            monthData = accountBedRatioCalc.toMonthDataList(ratios);
        } else if (itemCode.equals(Consts.ICU_3_ICU_NURSE_BED_RATIO)) {  // 2024 QC_03/19
            List<QcICUAccountBedRatioPB> ratios = accountBedRatioCalc.calcMonthly(
                deptId, itemCode, queryStartUtc, queryEndUtc
            );
            monthData = accountBedRatioCalc.toMonthDataList(ratios);
        } else if (itemCode.equals(Consts.ICU_4_APACHE2_OVER15_ADMISSION_RATE) ||
            itemCode.equals(Consts.ICU_7_DVT_PREVENTION_RATE) ||
            itemCode.equals(Consts.ICU_9_PAIN_ASSESSMENT_RATE) ||
            itemCode.equals(Consts.ICU_10_SEDATION_ASSESSMENT_RATE) ||
            itemCode.equals(Consts.ICU_11_STANDARDIZED_MORTALITY_RATIO) ||
            itemCode.equals(Consts.ICU_14_UNPLANNED_ICU_ADMISSION_RATE) ||
            itemCode.equals(Consts.ICU_15_ICU_READMISSION_WITHIN_48H_RATE)
        ) {
            List<QcPatientStatsPB> statsList = patientRatioCalc.calculatePatientStats(
                deptId, itemCode, patientRecords, queryStartUtc, queryEndUtc
            );
            monthData = patientRatioCalc.toMonthDataList(itemCode, statsList);
        }
        String itemDescription = StrUtils.isBlank(qcItem.getDescription())
            ? qcItem.getName() : qcItem.getDescription();
        String calcFormula = qcItem.getCalcFormula();

        return GetNHCQCDataResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusMsgList, StatusCode.OK))
            .addAllMonthData(monthData)
            .setItemDescription(itemDescription)
            .setCalcFormula(calcFormula)
            .build();
    }

    private final String ZONE_ID;
    private final List<String> statusMsgList;
    private final QcConfigPB qcConfig;
    private final Map<String, QcItemPB> qcItemMap;

    private final ConfigProtoService protoService;
    private final PatientService patientService;

    private final BedUtilizationCalc bedUtilizationCalc;
    private final AccountBedRatioCalc accountBedRatioCalc;
    private final PatientRatioCalc patientRatioCalc;
}