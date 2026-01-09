package com.jingyicare.jingyi_icis_engine.service.lis;

import java.time.*;
import java.util.*;
import java.util.stream.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisLis.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.lis.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.lis.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class LisService {
    public LisService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired ExternalLisParamRepository externalLisParamRepo,
        @Autowired PatientLisItemRepository patientLisItemRepo,
        @Autowired PatientLisResultRepository patientLisResultRepo,
        @Autowired LisParamRepository paramRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.protoService = protoService;
        this.patientService = patientService;
        this.userService = userService;

        this.externalLisParamRepo = externalLisParamRepo;
        this.patientLisItemRepo = patientLisItemRepo;
        this.patientLisResultRepo = patientLisResultRepo;
        this.paramRepo = paramRepo;
    }

    public GetExternalLisParamsResp getExternalLisParams(String reqJson) {
        final GetExternalLisParamsReq req;
        try {
            GetExternalLisParamsReq.Builder builder = GetExternalLisParamsReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GetExternalLisParamsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<ExternalLisParamPB> externalLisParams = getExternalLisParamList();

        return GetExternalLisParamsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllParam(externalLisParams)
            .build();
    }

    public GenericResp reorderExternalLisParams(String reqJson) {
        final ReorderExternalLisParamsReq req;
        try {
            ReorderExternalLisParamsReq.Builder builder = ReorderExternalLisParamsReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Map<String, ExternalLisParam> externalLisParams = externalLisParamRepo.findAll()
            .stream()
            .collect(Collectors.toMap(ExternalLisParam::getParamCode, p -> p));
        List<ExternalLisParam> updatedParams = new ArrayList<>();
        for (ExternalLisParamPB paramPb : req.getParamList()) {
            ExternalLisParam param = externalLisParams.get(paramPb.getParamCode());
            if (param == null) continue;

            // 判断displayOrder是否有变化
            if (param.getDisplayOrder() != paramPb.getDisplayOrder()) {
                param.setDisplayOrder(paramPb.getDisplayOrder());
                updatedParams.add(param);
            }
        }
        if (!updatedParams.isEmpty()) {
            externalLisParamRepo.saveAll(updatedParams);
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    public GenericResp updateExternalLisParam(String reqJson) {
        final UpdateExternalLisParamReq req;
        try {
            UpdateExternalLisParamReq.Builder builder = UpdateExternalLisParamReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String paramCode = req.getParam().getParamCode();
        ExternalLisParam externalLisParam = externalLisParamRepo.findByParamCode(paramCode)
            .orElse(null);
        if (externalLisParam == null) {
            log.error("External LIS parameter not found: {}", paramCode);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.EXTERNAL_LIS_PARAM_NOT_FOUND, paramCode))
                .build();
        }

        // Update the external LIS parameter
        externalLisParam.setParamName(req.getParam().getParamName());
        externalLisParam.setTypePb(ProtoUtils.encodeValueMeta(req.getParam().getValueMeta()));
        externalLisParam.setDangerMax(ProtoUtils.encodeGenericValue(req.getParam().getDangerMax()));
        externalLisParam.setDangerMin(ProtoUtils.encodeGenericValue(req.getParam().getDangerMin()));
        externalLisParamRepo.save(externalLisParam);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    public GenericResp deleteExternalLisParam(String reqJson) {
        final DeleteExternalLisParamReq req;
        try {
            DeleteExternalLisParamReq.Builder builder = DeleteExternalLisParamReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String paramCode = req.getParamCode();
        ExternalLisParam externalLisParam = externalLisParamRepo.findByParamCode(paramCode)
            .orElse(null);
        if (externalLisParam == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }
        // Delete the external LIS parameter
        externalLisParamRepo.delete(externalLisParam);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    public GetLisParamsResp getLisParams(String reqJson) {
        final GetLisParamsReq req;
        try {
            GetLisParamsReq.Builder builder = GetLisParamsReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GetLisParamsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<LisParamPB> lisParams = paramRepo.findAll()
            .stream()
            .map(p -> {
                LisParamPB.Builder builder = LisParamPB.newBuilder();
                builder.setParamCode(p.getParamCode());
                builder.setParamName(p.getParamName());
                builder.setDescription(StrUtils.getStringOrDefault(p.getParamDescription(), ""));

                String externalParamCodes = StrUtils.getStringOrDefault(p.getExternalParamCode(), "");
                List<String> externalParamCodeList = StrUtils.isBlank(externalParamCodes) ?
                    Collections.emptyList() : Arrays.asList(externalParamCodes.split(","));
                builder.addAllExternalParamCode(externalParamCodeList);

                builder.setDisplayOrder(p.getDisplayOrder());
                return builder.build();
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(LisParamPB::getDisplayOrder))
            .toList();

        return GetLisParamsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllParam(lisParams)
            .build();
    }

    public GenericResp reorderLisParams(String reqJson) {
        final ReorderLisParamsReq req;
        try {
            ReorderLisParamsReq.Builder builder = ReorderLisParamsReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Map<String, LisParam> lisParams = paramRepo.findAll()
            .stream()
            .collect(Collectors.toMap(LisParam::getParamCode, p -> p));
        List<LisParam> updatedParams = new ArrayList<>();
        for (LisParamPB paramPb : req.getParamList()) {
            LisParam param = lisParams.get(paramPb.getParamCode());
            if (param == null) continue;

            // 判断displayOrder是否有变化
            if (param.getDisplayOrder() != paramPb.getDisplayOrder()) {
                param.setDisplayOrder(paramPb.getDisplayOrder());
                updatedParams.add(param);
            }
        }
        if (!updatedParams.isEmpty()) {
            paramRepo.saveAll(updatedParams);
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    public GenericResp updateLisParam(String reqJson) {
        final UpdateLisParamReq req;
        try {
            UpdateLisParamReq.Builder builder = UpdateLisParamReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String paramCode = req.getParam().getParamCode();
        LisParam lisParam = paramRepo.findByParamCode(paramCode)
            .orElse(null);
        if (lisParam == null) {
            log.error("LIS parameter not found: {}", paramCode);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.LIS_PARAM_NOT_FOUND, paramCode))
                .build();
        }

        // Update the LIS parameter
        lisParam.setExternalParamCode(String.join(",", req.getParam().getExternalParamCodeList()));
        paramRepo.save(lisParam);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    public GetPatientLisResultsResp getPatientLisResults(String reqJson) {
        final GetPatientLisResultsReq req;
        try {
            GetPatientLisResultsReq.Builder builder = GetPatientLisResultsReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GetPatientLisResultsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 提取参数
        Long pid = req.getPid();
        LocalDateTime queryStart = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEnd = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStart == null || queryEnd == null) {
            log.error("Invalid query start or end time.");
            return GetPatientLisResultsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.INVALID_TIME_RANGE))
                .build();
        }

        // 获取患者信息
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetPatientLisResultsResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PATIENT_NOT_FOUND))
                .build();
        }

        // 获取患者检验结果
        Pair<StatusCode, List<OverviewLisItemPB>> pair = getPatientLisItems(patientRecord, queryStart, queryEnd);
        StatusCode statusCode = pair.getFirst();
        List<OverviewLisItemPB> overviewLisItems = pair.getSecond();
        if (statusCode != StatusCode.OK) {
            return GetPatientLisResultsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, statusCode))
                .build();
        }

        return GetPatientLisResultsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllItem(overviewLisItems)
            .build();
    }

    @Transactional
    public Pair<StatusCode, List<OverviewLisItemPB>> getPatientLisItems(
        PatientRecord patientRecord, LocalDateTime queryStart, LocalDateTime queryEnd
    ) {
        if (patientRecord == null) {
            log.error("Patient not found.");
            return new Pair<>(StatusCode.PATIENT_NOT_FOUND, Collections.emptyList());
        }

        Long pid = patientRecord.getId();
        String deptId = patientRecord.getDeptId();
        String hisPid = patientRecord.getHisPatientId();

        // 获取参数信息
        Map<String, ExternalLisParamPB> externalLisParamMap = getExternalLisParamList()
            .stream()
            .collect(Collectors.toMap(ExternalLisParamPB::getParamCode, p -> p));
        Map<String, Integer> paramCodeToDisplayOrderMap = externalLisParamMap
            .values()
            .stream()
            .collect(Collectors.toMap(ExternalLisParamPB::getParamCode, ExternalLisParamPB::getDisplayOrder));

        // 获取所有检验项目
        List<PatientLisItem> patientLisItems = patientLisItemRepo.findByHisPidAndAuthTimeBetween(hisPid, queryStart, queryEnd);
        {
            List<PatientLisItem> itemsWithNullAuthTime = patientLisItemRepo.findByHisPidAndAuthTimeIsNull(hisPid);
            for (PatientLisItem item : itemsWithNullAuthTime) {
                if (item.getCollectTime() != null &&
                    !item.getCollectTime().isBefore(queryStart) &&
                    !item.getCollectTime().isAfter(queryEnd)
                ) {
                    item.setAuthTime(item.getCollectTime());
                    patientLisItems.add(item);
                }
            }
        }
        Map<String, PatientLisItem> reportIdToLisItemMap = patientLisItems
            .stream()
            .collect(Collectors.toMap(PatientLisItem::getReportId, item -> item));
        List<String> reportIds = patientLisItems.stream().map(PatientLisItem::getReportId).toList();
        Map<String/*report_id*/, String/*lis_item_name*/> reportIdToLisItemNameMap = patientLisItems
            .stream()
            .collect(Collectors.toMap(PatientLisItem::getReportId, PatientLisItem::getLisItemName));

        // 给定检验名称，获取最新的检验项目: lis_item_name => PatientLisItem
        Map<String, PatientLisItem> latestReportMap = new HashMap<>();
        for (PatientLisItem item : patientLisItems) {
            String lisItemName = item.getLisItemName();

            if (latestReportMap.containsKey(lisItemName)) {
                PatientLisItem existingItem = latestReportMap.get(lisItemName);
                if (item.getAuthTime().isAfter(existingItem.getAuthTime())) {
                    latestReportMap.put(lisItemName, item);
                }
            } else {
                latestReportMap.put(lisItemName, item);
            }
        }

        // 根据reportIds获取所有检验结果
        List<PatientLisResult> patientLisResults = patientLisResultRepo.findByReportIdInAndIsDeletedFalse(reportIds);

        // 将检验结果按照 检验项目名称，外部参数编码 分组 lis_item_name => (external_param_code => List<PatientLisResult>)
        Map<String, Map<String, List<PatientLisResult>>> groupedResults = new HashMap<>();
        for (PatientLisResult result : patientLisResults) {
            String reportId = result.getReportId();
            String lisItemName = reportIdToLisItemNameMap.get(reportId);
            if (lisItemName == null) continue;

            groupedResults.computeIfAbsent(lisItemName, k -> new HashMap<>())
                .computeIfAbsent(result.getExternalParamCode(), k -> new ArrayList<>())
                .add(result);
        }

        // 检查每个参数的类型信息是否存在，如果不存在，则重新生成元数据以及解析参数数据
        autoCompleteExternalLisParams(groupedResults, externalLisParamMap);

        // 根据lis_item_name组装OverviewLisItemPB
        List<OverviewLisItemPB> overviewLisItems = new ArrayList<>();
        for (Map.Entry<String, PatientLisItem> entry : latestReportMap.entrySet()) {
            String lisItemName = entry.getKey();
            String authTimeIso8601 = TimeUtils.toIso8601String(entry.getValue().getAuthTime(), ZONE_ID);

            Map<String, List<PatientLisResult>> resultsMap = groupedResults.get(lisItemName);
            if (resultsMap == null) continue;

            List<OverviewLisResultPB> overviewLisResults = new ArrayList<>();
            for (Map.Entry<String, List<PatientLisResult>> resultEntry : resultsMap.entrySet()) {
                String externalParamCode = resultEntry.getKey();
                List<PatientLisResult> results = resultEntry.getValue();

                // 获取外部参数信息
                ExternalLisParamPB externalLisParam = externalLisParamMap.get(externalParamCode);
                if (externalLisParam == null) continue;

                OverviewLisResultPB overviewLisResult = getOverviewLisResultPB(reportIdToLisItemMap, results, externalLisParam);
                if (overviewLisResult == null) continue;
                overviewLisResults.add(overviewLisResult);
            }
            overviewLisResults.sort(Comparator.comparing(result -> 
                paramCodeToDisplayOrderMap.getOrDefault(result.getLisResultCode(), Integer.MAX_VALUE)));

            OverviewLisItemPB overviewLisItem = OverviewLisItemPB.newBuilder()
                .setLisItemName(lisItemName)
                .setAuthTimeIso8601(authTimeIso8601)
                .addAllResult(overviewLisResults)
                .build();
            overviewLisItems.add(overviewLisItem);
        }
        overviewLisItems.sort(Comparator.comparing(OverviewLisItemPB::getLisItemName));
        return new Pair<>(StatusCode.OK, overviewLisItems);
    }

    public GenericResp deletePatientLisRecord(String reqJson) {
        final DeletePatientLisRecordReq req;
        try {
            DeletePatientLisRecordReq.Builder builder = DeletePatientLisRecordReq.newBuilder();
            JsonFormat.parser().merge(reqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e);
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        String reportId = req.getReportId();
        PatientLisItem patientLisItem = patientLisItemRepo.findByReportId(reportId).orElse(null);
        if (patientLisItem != null) {
            // 删除患者检验结果
            List<PatientLisResult> patientLisResults = patientLisResultRepo.findByReportIdAndIsDeletedFalse(reportId);
            for (PatientLisResult result : patientLisResults) {
                result.setIsDeleted(true);
                result.setDeletedBy(accountId);
                result.setDeletedByAccountName(accountName);
            }
            patientLisResultRepo.saveAll(patientLisResults);

            // 删除患者检验项目
            patientLisItemRepo.delete(patientLisItem);
        }

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public ValueMetaPB getExternalLisParamValueMeta(String paramCode) {
        ExternalLisParam externalLisParam = externalLisParamRepo.findByParamCode(paramCode)
            .orElse(null);
        if (externalLisParam == null) {
            log.error("External LIS parameter not found: {}", paramCode);
            return null;
        }
        return ProtoUtils.decodeValueMeta(externalLisParam.getTypePb());
    }

    private List<ExternalLisParamPB> getExternalLisParamList() {
        return externalLisParamRepo.findAll()
            .stream()
            .map(p -> {
                ExternalLisParamPB.Builder builder = ExternalLisParamPB.newBuilder();
                builder.setParamCode(p.getParamCode());
                builder.setParamName(p.getParamName());
                ValueMetaPB valueMeta = ProtoUtils.decodeValueMeta(p.getTypePb());
                if (valueMeta == null) {
                    log.error("Failed to decode value meta for param: {}", p.getParamCode());
                    return null;
                }
                builder.setValueMeta(valueMeta);

                GenericValuePB dangerMax = ProtoUtils.decodeGenericValue(p.getDangerMax());
                if (dangerMax == null) dangerMax = GenericValuePB.getDefaultInstance();
                builder.setDangerMax(dangerMax);

                GenericValuePB dangerMin = ProtoUtils.decodeGenericValue(p.getDangerMin());
                if (dangerMin == null) dangerMin = GenericValuePB.getDefaultInstance();
                builder.setDangerMin(dangerMin);

                builder.setDisplayOrder(p.getDisplayOrder());
                return builder.build();
            })
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(ExternalLisParamPB::getDisplayOrder))
            .toList();
    }

    private OverviewLisResultPB getOverviewLisResultPB(Map<String, PatientLisItem> reportIdToLisItemMap, List<PatientLisResult> results, ExternalLisParamPB externalLisParam) {
        // 获取最新的检验结果
        PatientLisResult latestResult = null;
        for (PatientLisResult result : results) {
            if (result.getAuthTime() == null) {
                PatientLisItem lisItem = reportIdToLisItemMap.get(result.getReportId());
                result.setAuthTime(lisItem == null ? null : lisItem.getCollectTime());
            }
            if (latestResult == null || result.getAuthTime().isAfter(latestResult.getAuthTime())) {
                latestResult = result;
            }
        }
        if (latestResult == null) return null;

        String lisResultCode = latestResult.getExternalParamCode();
        String lisResultName = latestResult.getExternalParamName();
        String alarmFlag = latestResult.getAlarmFlag() == null ? "" : latestResult.getAlarmFlag();
        String dangerFlag = latestResult.getDangerFlag() == null ? "" : latestResult.getDangerFlag();

        ValueMetaPB valueMeta = externalLisParam.getValueMeta();
        Boolean isNumber = ValueMetaUtils.isNumber(valueMeta);
        GenericValuePB defaultValue = GenericValuePB.getDefaultInstance();

        GenericValuePB normalMax = isNumber ? valueMeta.getUpperLimit() : defaultValue;
        String normalMaxStr = isNumber ? ValueMetaUtils.extractAndFormatParamValue(normalMax, valueMeta) : "";
        GenericValuePB normalMin = valueMeta.getLowerLimit();
        String normalMinStr = isNumber ? ValueMetaUtils.extractAndFormatParamValue(normalMin, valueMeta) : "";
        GenericValuePB dangerMax = externalLisParam.getDangerMax();
        String dangerMaxStr = isNumber ? ValueMetaUtils.extractAndFormatParamValue(dangerMax, valueMeta) : "";
        GenericValuePB dangerMin = externalLisParam.getDangerMin();
        String dangerMinStr = isNumber ? ValueMetaUtils.extractAndFormatParamValue(dangerMin, valueMeta) : "";
        GenericValuePB result = ValueMetaUtils.toGenericValue(latestResult.getResultStr(), valueMeta);
        if (result == null) result = defaultValue;
        String resultStr = isNumber ? ValueMetaUtils.extractAndFormatParamValue(result, valueMeta) : "";
        String unit = latestResult.getUnit();

        List<LisTimeValuePB> timedValues = new ArrayList<>();
        if (isNumber) {
            for (PatientLisResult resultItem :
                results.stream().sorted(Comparator.comparing(PatientLisResult::getAuthTime).reversed()).toList()
            ) {
                GenericValuePB resultValue = ValueMetaUtils.toGenericValue(resultItem.getResultStr(), valueMeta);
                if (resultValue == null) resultValue = defaultValue;
                String resultValueStr = isNumber ? ValueMetaUtils.extractAndFormatParamValue(resultValue, valueMeta) : "";
                String authTimeIso8601 = TimeUtils.toIso8601String(resultItem.getAuthTime(), ZONE_ID);
                String resAlarmFlag = resultItem.getAlarmFlag() == null ? "" : resultItem.getAlarmFlag();
                String resDangerFlag = resultItem.getDangerFlag() == null ? "" : resultItem.getDangerFlag();
                LisTimeValuePB timedValue = LisTimeValuePB.newBuilder()
                    .setValue(resultValue)
                    .setValueStr(resultValueStr)
                    .setRecordedAtIso8601(authTimeIso8601)
                    .setAlarmFlag(resAlarmFlag)
                    .setDangerFlag(resDangerFlag)
                    .build();
                timedValues.add(timedValue);
                if (timedValues.size() >= 3) break;
            }
        }

        // 组装OverviewLisResultPB
        return OverviewLisResultPB.newBuilder()
            .setLisResultCode(lisResultCode)
            .setLisResultName(lisResultName)
            .setAlarmFlag(alarmFlag)
            .setDangerFlag(dangerFlag)
            .setNormalMax(normalMax)
            .setNormalMaxStr(normalMaxStr)
            .setNormalMin(normalMin)
            .setNormalMinStr(normalMinStr)
            .setDangerMax(dangerMax)
            .setDangerMaxStr(dangerMaxStr)
            .setDangerMin(dangerMin)
            .setDangerMinStr(dangerMinStr)
            .setResultValue(result)
            .setResultValueStr(resultStr)
            .setResultUnit(unit)
            .addAllHisResult(timedValues)
            .build();
    }

    private void autoCompleteExternalLisParams(
        Map<String, Map<String, List<PatientLisResult>>> groupedResults,
        Map<String, ExternalLisParamPB> externalLisParamMap
    ) {
        // 获取检验参数
        Integer displayOrder = 1;
        for (ExternalLisParam param : externalLisParamRepo.findAll()) {
            if (param.getDisplayOrder() != null && param.getDisplayOrder() >= displayOrder) {
                displayOrder = param.getDisplayOrder() + 1;
            }
        }

        // 获取未标准化的检验结果
        Map<String /*external_param_code*/ , List<PatientLisResult>> resultMap = new HashMap<>();
        List<ExternalLisParamPB> newExternalLisParamPbs = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<PatientLisResult>>> entry : groupedResults.entrySet()) {
            for (Map.Entry<String, List<PatientLisResult>> resultEntry : entry.getValue().entrySet()) {
                for (PatientLisResult result : resultEntry.getValue()) {
                    String externalParamCode = result.getExternalParamCode();
                    resultMap.computeIfAbsent(externalParamCode, k -> new ArrayList<>()).add(result);
                    if (externalLisParamMap.containsKey(externalParamCode)) continue;

                    String externalParamName = result.getExternalParamName();
                    String unit = result.getUnit();

                    TypeEnumPB typeEnumPb = TypeEnumPB.STRING;
                    Float resultValue = null;
                    Float max = null;
                    Float min = null;
                    Float dangerMax = null;
                    Float dangerMin = null;
                    try {
                        resultValue = Float.parseFloat(result.getResultStr());
                        typeEnumPb = TypeEnumPB.FLOAT;
                        if (!StrUtils.isBlank(result.getNormalMaxStr())) {
                            max = Float.parseFloat(result.getNormalMaxStr());
                        }
                        if (!StrUtils.isBlank(result.getNormalMinStr())) {
                            min = Float.parseFloat(result.getNormalMinStr());
                        }
                        if (!StrUtils.isBlank(result.getDangerMaxStr())) {
                            dangerMax = Float.parseFloat(result.getDangerMaxStr());
                        }
                        if (!StrUtils.isBlank(result.getDangerMinStr())) {
                            dangerMin = Float.parseFloat(result.getDangerMinStr());
                        }
                    } catch (NumberFormatException e) {
                        ;
                    }

                    // 创建新的外部参数
                    ValueMetaPB.Builder valueMetaBuilder = ValueMetaPB.newBuilder()
                        .setValueType(typeEnumPb)
                        .setUnit(unit);
                    if (typeEnumPb == TypeEnumPB.FLOAT) {
                        if (max != null) valueMetaBuilder.setUpperLimit(GenericValuePB.newBuilder().setFloatVal(max).build());
                        if (min != null) valueMetaBuilder.setLowerLimit(GenericValuePB.newBuilder().setFloatVal(min).build());
                        valueMetaBuilder.setDecimalPlaces(2);
                    }
                    ExternalLisParamPB.Builder paramBuilder = ExternalLisParamPB.newBuilder()
                        .setParamCode(externalParamCode)
                        .setParamName(externalParamName)
                        .setValueMeta(valueMetaBuilder.build())
                        .setDisplayOrder(displayOrder++);
                    if (typeEnumPb == TypeEnumPB.FLOAT) {
                        if (dangerMax != null) {
                            paramBuilder.setDangerMax(GenericValuePB.newBuilder().setFloatVal(dangerMax).build());
                        }
                        if (dangerMin != null) {
                            paramBuilder.setDangerMin(GenericValuePB.newBuilder().setFloatVal(dangerMin).build());
                        }
                    }
                    ExternalLisParamPB externalLisParam = paramBuilder.build();
                    newExternalLisParamPbs.add(externalLisParam);
                    externalLisParamMap.put(externalParamCode, externalLisParam);
                }
            }
        }
        
        if (!newExternalLisParamPbs.isEmpty()) {
            List<ExternalLisParam> newExternalLisParams = new ArrayList<>();
            List<PatientLisResult> resultsToUpdate = new ArrayList<>();
            for (ExternalLisParamPB paramPb : newExternalLisParamPbs) {
                ExternalLisParam newExternalLisParam = new ExternalLisParam();
                newExternalLisParam.setParamCode(paramPb.getParamCode());
                newExternalLisParam.setParamName(paramPb.getParamName());
                newExternalLisParam.setTypePb(ProtoUtils.encodeValueMeta(paramPb.getValueMeta()));
                newExternalLisParam.setDangerMax(ProtoUtils.encodeGenericValue(paramPb.getDangerMax()));
                newExternalLisParam.setDangerMin(ProtoUtils.encodeGenericValue(paramPb.getDangerMin()));
                newExternalLisParam.setDisplayOrder(paramPb.getDisplayOrder());
                newExternalLisParams.add(newExternalLisParam);

                // 更新检验结果
                List<PatientLisResult> results = resultMap.get(paramPb.getParamCode());
                if (results == null) continue;
                resultsToUpdate.addAll(results);
            }
            externalLisParamRepo.saveAll(newExternalLisParams);
            patientLisResultRepo.saveAll(resultsToUpdate);
        }
    }

    private final String ZONE_ID;
    private final List<String> statusCodeMsgList;
    private final ConfigProtoService protoService;
    private final PatientService patientService;
    private final UserService userService;

    private final ExternalLisParamRepository externalLisParamRepo;
    private final PatientLisItemRepository patientLisItemRepo;
    private final PatientLisResultRepository patientLisResultRepo;
    private final LisParamRepository paramRepo;
}