package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.DeptMonMetaReader;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.reports.common.PatientNursingReportInvalidationService;
import com.jingyicare.jingyi_icis_engine.utils.*;


/*
 *  getGroupRecordsList 统计一组观察组(比如：入量组，出量组，平衡量组)
 *      => getGroupRecords 统计一个观察项组的行（一个观察项）和列（一个时间段）
 *          => getCodeRecords 统计一行（一个观察项）
 *             * MonitoringCodeRecordsPB (从PatientMonitoringRecord转化而来, 必要时计算合计值)
 *             * Map<LocalDateTime, GenericValuePB> groupSumAtValMap （将每一列的合计存储到对应的分组）
 *      => 提取出量观察组，入量观察组
 *      => getNetGroupRecords 合并平衡量观察组
 */
@Service
@Slf4j
public class BalanceCalculator {
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class GetGroupRecordsListArgs {
        public Long pid;
        public String deptId;
        public LocalDateTime startTime;
        public LocalDateTime endTime;
        public List<LocalDateTime> balanceStatTimeUtcHistory;
        public Map<String, MonitoringParamPB> paramMap;
        public List<MonitoringGroupBetaPB> groupBetaList;
        public List<PatientMonitoringRecord> recordList;
        public String modifiedBy;
    }

    public BalanceCalculator(
        @Autowired ConfigProtoService protoService,
        @Autowired DeptMonMetaReader deptMonMetaReader,
        @Autowired MonitoringRecordUtils monitoringRecordUtils,
        @Autowired PatientNursingReportInvalidationService pnrUtils
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.deptMonMetaReader = deptMonMetaReader;
        this.monitoringRecordUtils = monitoringRecordUtils;
        this.pnrUtils = pnrUtils;
    }

    // 统计一个观察项（行）：
    // - 将多个PatientMonitoringRecord转化成对应的MonitoringRecordValPB，加总，返回MonitoringCodeRecordsPB
    // - 将多个PatientMonitoringRecord加总到对应的groupSumAtValMap列中
    public MonitoringCodeRecordsPB getCodeRecords(
        MonitoringParamPB monParamPB/*in*/,
        List<PatientMonitoringRecord> recordList/*in*/,
        ValueMetaPB groupValueMeta/*in*/,
        Map<LocalDateTime, GenericValuePB> groupSumAtValMap/*in & out*/
    ) {
        // 构建返回值
        MonitoringCodeRecordsPB.Builder codeRecordsBuilder = MonitoringCodeRecordsPB.newBuilder()
            .setParamCode(monParamPB.getCode());
        if (recordList == null || recordList.isEmpty()) {
            return codeRecordsBuilder.build();
        }

        // 准备 合计值 的计算要素
        final boolean toSummarizeCode = (
            monParamPB.getBalanceType() == MonEnums.BALANCE_TYPE_IN ||
            monParamPB.getBalanceType() == MonEnums.BALANCE_TYPE_OUT
        );

        // 构造一个观察项行，必要时计算合计值
        List<MonitoringRecordValPB> recordValPBList = new ArrayList<>();
        final ValueMetaPB codeValueMeta = monParamPB.getValueMeta();
        GenericValuePB codeSumValue = ValueMetaUtils.getDefaultValue(codeValueMeta);
        for (PatientMonitoringRecord record : recordList) {
            // 解析Entity中的MonitoringValuePB
            MonitoringValuePB monitoringValue = ProtoUtils.decodeMonitoringValue(record.getParamValue());
            GenericValuePB value = monitoringValue.getValue();
            List<GenericValuePB> values = monitoringValue.getValuesList();
            String paramValueStr = record.getParamValueStr();
            LocalDateTime recordedAt = record.getEffectiveTime();
            String recordedAtIso8601 = TimeUtils.toIso8601String(recordedAt, ZONE_ID);

            // 构造一个观察项单元(cell)的PB值
            recordValPBList.add(MonitoringRecordValPB.newBuilder()
                .setId(record.getId())
                .setValue(value)
                .addAllValues(values)
                .setValueStr(paramValueStr)
                .setRecordedAtIso8601(recordedAtIso8601)
                .build()
            );

            // 计算汇总值
            if (toSummarizeCode) {
                // codeSumValue += value
                codeSumValue = ValueMetaUtils.addGenericValue(codeSumValue, value, codeValueMeta);
                // groupSumAtValMap[recordedAt] += value
                GenericValuePB groupSumAtTimeVal = groupSumAtValMap.getOrDefault(
                    recordedAt, ValueMetaUtils.getDefaultValue(groupValueMeta));
                GenericValuePB valueInGroupType = ValueMetaUtils.convertGenericValue(value, codeValueMeta, groupValueMeta);
                groupSumAtTimeVal = ValueMetaUtils.addGenericValue(
                    groupSumAtTimeVal, valueInGroupType, groupValueMeta);
                groupSumAtValMap.put(recordedAt, groupSumAtTimeVal);
            }
        }
        if (recordValPBList.isEmpty()) return codeRecordsBuilder.build();

        if (toSummarizeCode) {
            recordValPBList.add(MonitoringRecordValPB.newBuilder()
                .setId(Consts.VIRTUAL_RECORD_ID)
                .setValue(codeSumValue)
                .setValueStr(ValueMetaUtils.extractAndFormatParamValue(codeSumValue, codeValueMeta))
                .setRecordedAtIso8601("")
                .build()
            );
        }

        return codeRecordsBuilder.addAllRecordValue(recordValPBList).build();
    }

    // 统计一个观察组：
    // - 调用 getCodeRecords ，将recordsMap转化成对应的 codeRecordsList
    //   * 每一个观察项（行）转化成 codeRecords
    //   * codeRecords 合并到 codeRecordsList
    //   * 将每一列（按时间）合并成 groupSumAtValMap
    // - 计算合计列，以及合计列的汇总值
    //   * 将 groupSumAtValMap 转化为 groupRecordVals （list)
    //   * 计算 groupRecordVals 的合计值 sum
    //   * 将 groupRecordVals 添加到 codeRecordsList
    // - codeRecordsList 合并成 MonitoringGroupRecordsPB ，返回
    public MonitoringGroupRecordsPB getGroupRecords(
        Long pid, String deptId, LocalDateTime startTime/*in*/, LocalDateTime endTime/*in*/,
        String modifiedBy/*in*/, MonitoringGroupBetaPB groupBeta/*in*/,
        Map<String /*param_code*/, List<PatientMonitoringRecord>> recordsMap/*in*/
    ) {
        // 从groupBeta提取关键参数
        Integer deptMonGroupId = groupBeta.getId();
        boolean toSumGroup = getHourlySumCode(groupBeta.getBalanceRole()) != null;
        ValueMetaPB origGroupValueMeta = groupBeta.getSumValueMeta();
        List<MonitoringParamPB> groupParamList = groupBeta.getParamList();

        // 构建返回值
        MonitoringGroupRecordsPB.Builder groupRecordsBuilder = MonitoringGroupRecordsPB.newBuilder()
            .setDeptMonitoringGroupId(deptMonGroupId);
        if (recordsMap == null || recordsMap.isEmpty() || groupParamList.isEmpty()) return groupRecordsBuilder.build();

        // 构建组内每行观察项的值，统计每列时间的合计值
        List<MonitoringCodeRecordsPB> codeRecordsList = new ArrayList<>();
        Map<LocalDateTime, GenericValuePB> groupSumAtValMap = new HashMap<>();
        Integer maxDecimalPlaces = 0;
        for (MonitoringParamPB monParamPB : groupParamList) {
            // 提取观察项参数
            String paramCode = monParamPB.getCode();
            Integer decimalPlaces = monParamPB.getValueMeta().getDecimalPlaces();

            // 构建一行数据
            List<PatientMonitoringRecord> recordList = recordsMap.get(paramCode);
            MonitoringCodeRecordsPB codeRecords = getCodeRecords(
                monParamPB, recordList, origGroupValueMeta, groupSumAtValMap
            );
            if (codeRecords != null && !codeRecords.getRecordValueList().isEmpty()) {
                // 构建一行观察项的值
                codeRecordsList.add(codeRecords);
                // 计算组内所有观察项的最大小数位数
                maxDecimalPlaces = Math.max(maxDecimalPlaces, decimalPlaces);
            }
        }
        final ValueMetaPB groupValueMeta = origGroupValueMeta.toBuilder().setDecimalPlaces(maxDecimalPlaces).build();

        // 构建组合计行
        if (toSumGroup) {
            MonitoringCodeRecordsPB.Builder groupSumRecordsBuilder = MonitoringCodeRecordsPB.newBuilder()
                .setParamCode(MonEnums.PARAM_CODE_SUMMARY);

            // groupSumAtValMap => groupRecordVals
            // sum = sum(groupRecordVals)
            // groupRecordVals.append(sum)
            List<MonitoringRecordValPB> groupRecordVals = new ArrayList<>();
            for (Map.Entry<LocalDateTime, GenericValuePB> entry : 
                groupSumAtValMap.entrySet().stream().sorted(Map.Entry.comparingByKey()).toList()
            ) {
                if (entry.getValue() == null) {
                    log.warn("Null value for group sum at time {} in group {}, pid {}",
                        entry.getKey(), groupBeta.getName(), pid);
                    continue;
                }
                groupRecordVals.add(newRecordValPB(
                    entry.getKey(), entry.getValue(), groupValueMeta
                ));
            }
            GenericValuePB sum = groupRecordVals.stream()
                .map(MonitoringRecordValPB::getValue)
                .reduce((val1, val2) -> ValueMetaUtils.addGenericValue(val1, val2, groupValueMeta))
                .orElse(ValueMetaUtils.getDefaultValue(groupValueMeta));
            groupRecordVals.add(newRecordValPB(null, sum, groupValueMeta));

            // 将合计值添加到 codeRecordsList
            codeRecordsList.add(MonitoringCodeRecordsPB.newBuilder()
                .setParamCode(MonEnums.PARAM_CODE_SUMMARY)
                .addAllRecordValue(groupRecordVals)
                .build()
            );
        }
        if (!codeRecordsList.isEmpty()) groupRecordsBuilder.addAllCodeRecords(codeRecordsList);
        return groupRecordsBuilder.build();
    }

    // 计算平衡量观察组：
    // - inGroupRecords => List<MonitoringRecordValPB> inList
    // - outGroupRecords => List<MonitoringRecordValPB> outList
    // - inlist + outList (归并)=> List<MonitoringRecordValPB> netList
    // - 调用函数 getNetGroupRecordsMap 计算平衡组  内的6个观察项编码的值
    // - 构造 MonitoringGroupRecordsPB
    public MonitoringGroupRecordsPB getNetGroupRecords(
        Map<String, MonitoringParamPB> deptParamMap,
        MonitoringGroupBetaPB inGroupBeta, MonitoringGroupBetaPB outGroupBeta, MonitoringGroupBetaPB netGroupBeta,
        MonitoringGroupRecordsPB inGroupRecords, MonitoringGroupRecordsPB outGroupRecords
    ) {
        if (inGroupBeta == null || outGroupBeta == null || netGroupBeta == null) {
            log.error("Failed to get net group records for \nin group {}\n, out group {}\n, net group {}",
                inGroupBeta, outGroupBeta, netGroupBeta);
            return null;
        }

        // 提取入量合计
        ValueMetaPB inGroupValueMeta = inGroupBeta.getSumValueMeta();
        ValueMetaPB outGroupValueMeta = outGroupBeta.getSumValueMeta();
        ValueMetaPB netGroupValueMeta = netGroupBeta.getSumValueMeta();
        List<MonitoringRecordValPB> inList = getRecordValPBList(inGroupRecords);
        List<MonitoringRecordValPB> outList = getRecordValPBList(outGroupRecords);

        // 计算净量
        List<MonitoringRecordValPB> netList = new ArrayList<>();
        int i = 0, j = 0;
        while (i < inList.size() || j < outList.size()) {
            // 提取入量和出量的记录
            MonitoringRecordValPB inRecord = i < inList.size() ? inList.get(i) : null;
            LocalDateTime inRecordedAt = TimeUtils.fromIso8601String(
                (inRecord != null ? inRecord.getRecordedAtIso8601() : ""),
                "UTC"
            );
            MonitoringRecordValPB outRecord = j < outList.size() ? outList.get(j) : null;
            LocalDateTime outRecordedAt = TimeUtils.fromIso8601String(
                (outRecord != null ? outRecord.getRecordedAtIso8601() : ""),
                "UTC"
            );

            // 忽略汇总值
            if (inRecord != null && inRecordedAt == null) { ++i; continue; }
            if (outRecord != null && outRecordedAt == null) { ++j; continue; }

            // 比较时间，计算净量
            // inRecordedAt != null && outRecordedAt != null
            //    inRecordedAt > outRecordedAt
            //    inRecordedAt < outRecordedAt
            //    inRecordedAt == outRecordedAt
            // inRecordedAt == null && outRecordedAt != null
            // inRecordedAt != null && outRecordedAt == null
            if (inRecordedAt != null &&
                (outRecordedAt == null || inRecordedAt.isBefore(outRecordedAt))
            ) {
                GenericValuePB net = ValueMetaUtils.convertGenericValue(
                    inRecord.getValue(), inGroupValueMeta, netGroupValueMeta);
                netList.add(newRecordValPB(inRecordedAt, net, netGroupValueMeta));
                ++i;
            } else if (outRecordedAt != null &&
                (inRecordedAt == null || outRecordedAt.isBefore(inRecordedAt))
            ) {
                GenericValuePB net = ValueMetaUtils.subtractGenericValue(
                    ValueMetaUtils.getDefaultValue(netGroupValueMeta),
                    ValueMetaUtils.convertGenericValue(outRecord.getValue(), outGroupValueMeta, netGroupValueMeta),
                    netGroupValueMeta);
                netList.add(newRecordValPB(outRecordedAt, net, netGroupValueMeta));
                ++j;
            } else {
                GenericValuePB net = ValueMetaUtils.subtractGenericValue(
                    ValueMetaUtils.convertGenericValue(inRecord.getValue(), inGroupValueMeta, netGroupValueMeta),
                    ValueMetaUtils.convertGenericValue(outRecord.getValue(), outGroupValueMeta, netGroupValueMeta),
                    netGroupValueMeta);
                netList.add(newRecordValPB(inRecordedAt, net, netGroupValueMeta));
                ++i;
                ++j;
            }
        }

        // 计算汇总值
        GenericValuePB netSum = netList.stream()
            .map(MonitoringRecordValPB::getValue)
            .reduce((val1, val2) -> ValueMetaUtils.addGenericValue(val1, val2, netGroupValueMeta))
            .orElse(ValueMetaUtils.getDefaultValue(netGroupValueMeta));
        netList.add(newRecordValPB(null, netSum, netGroupValueMeta));

        // 构造平衡组可用的观察项编码
        Map<String, MonitoringCodeRecordsPB> netCodeRecordsMap = getNetGroupRecordsMap(
            deptParamMap, inGroupBeta, outGroupBeta, netGroupBeta, inList, outList, netList
        );

        List<MonitoringCodeRecordsPB> netCodeRecordsList = new ArrayList<>();
        for (MonitoringParamPB paramMeta : netGroupBeta.getParamList()) {
            MonitoringCodeRecordsPB codeRecords = netCodeRecordsMap.get(paramMeta.getCode());
            if (codeRecords != null) netCodeRecordsList.add(codeRecords);
        }

        return MonitoringGroupRecordsPB.newBuilder()
            .setDeptMonitoringGroupId(netGroupBeta.getId())
            .addAllCodeRecords(netCodeRecordsList)
            .build();
    }

    public List<MonitoringGroupRecordsPB> getGroupRecordsList(GetGroupRecordsListArgs args) {
        List<PatientMonitoringRecord> recordList = args.recordList;
        if (recordList == null || recordList.isEmpty()) return new ArrayList<>();
        Map<String /*param_code*/, List<PatientMonitoringRecord>> recordsMap =
             recordList.stream().collect(Collectors.groupingBy(
                PatientMonitoringRecord::getMonitoringParamCode,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    list -> list.stream()
                        .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
                        .collect(Collectors.toList())
                )
            ));

        List<MonitoringGroupRecordsPB> groupRecordsList = new ArrayList<>();
        MonitoringGroupBetaPB inGroupBeta = null;
        MonitoringGroupBetaPB outGroupBeta = null;
        MonitoringGroupBetaPB netGroupBeta = null;
        MonitoringGroupRecordsPB inGroupRecords = null;
        MonitoringGroupRecordsPB outGroupRecords = null;
        for (MonitoringGroupBetaPB groupBeta : args.groupBetaList) {
            MonitoringGroupRecordsPB groupRecords = getGroupRecords(
                args.pid, args.deptId, args.startTime, args.endTime,
                args.modifiedBy, groupBeta, recordsMap
            );
            int balanceRole = groupBeta.getBalanceRole();
            if (balanceRole == MonEnums.BALANCE_TYPE_IN) {
                inGroupBeta = groupBeta;
                inGroupRecords = groupRecords;
            } else if (balanceRole == MonEnums.BALANCE_TYPE_OUT) {
                outGroupBeta = groupBeta;
                outGroupRecords = groupRecords;
            } else if (balanceRole == MonEnums.BALANCE_TYPE_NET) {
                netGroupBeta = groupBeta;
            }
        }

        MonitoringGroupRecordsPB netGroupRecords = getNetGroupRecords(
            args.paramMap, inGroupBeta, outGroupBeta, netGroupBeta,
            inGroupRecords, outGroupRecords);
        if (inGroupRecords != null) groupRecordsList.add(inGroupRecords);
        if (outGroupRecords != null) groupRecordsList.add(outGroupRecords);
        if (netGroupRecords != null) groupRecordsList.add(netGroupRecords);

        return groupRecordsList;
    }

    private Map<String, MonitoringCodeRecordsPB> getNetGroupRecordsMap(
        Map<String, MonitoringParamPB> deptParamMap,
        MonitoringGroupBetaPB inGroupBeta, MonitoringGroupBetaPB outGroupBeta, MonitoringGroupBetaPB netGroupBeta,
        List<MonitoringRecordValPB> inList, List<MonitoringRecordValPB> outList, List<MonitoringRecordValPB> netList
    ) {
        Map<String, MonitoringCodeRecordsPB> netCodeRecordsMap = new HashMap<>();

        setNetGroupRecordsMap(deptParamMap, MonEnums.NET_HOURLY_IN_CODE,
            inGroupBeta, inList, netCodeRecordsMap);
        setNetGroupRecordsMap(deptParamMap, MonEnums.NET_HOURLY_OUT_CODE,
            outGroupBeta, outList, netCodeRecordsMap);
        setNetGroupRecordsMap(deptParamMap, MonEnums.NET_HOURLY_NET_CODE,
            netGroupBeta, netList, netCodeRecordsMap);

        // 计算累积值
        setNetGroupRecordsMap(deptParamMap, MonEnums.NET_ACCUMULATED_IN_CODE, inGroupBeta,
            getAccumulatedVals(inList, inGroupBeta.getSumValueMeta()), netCodeRecordsMap);
        setNetGroupRecordsMap(deptParamMap, MonEnums.NET_ACCUMULATED_OUT_CODE, outGroupBeta,
            getAccumulatedVals(outList, outGroupBeta.getSumValueMeta()), netCodeRecordsMap);
        setNetGroupRecordsMap(deptParamMap, MonEnums.NET_ACCUMULATED_NET_CODE, netGroupBeta,
            getAccumulatedVals(netList, netGroupBeta.getSumValueMeta()), netCodeRecordsMap);

        return netCodeRecordsMap;
    }

    private List<MonitoringRecordValPB> getAccumulatedVals(
        List<MonitoringRecordValPB> vals, ValueMetaPB valueMeta
    ) {
        List<MonitoringRecordValPB> accumulatedVals = new ArrayList<>();

        GenericValuePB accumulatedValue = ValueMetaUtils.getDefaultValue(valueMeta);
        for (MonitoringRecordValPB val : vals) {
            if (!val.getRecordedAtIso8601().isEmpty()) {
                accumulatedValue = ValueMetaUtils.addGenericValue(accumulatedValue, val.getValue(), valueMeta);
            }
            accumulatedVals.add(MonitoringRecordValPB.newBuilder()
                .setId(Consts.VIRTUAL_RECORD_ID)
                .setValue(accumulatedValue.toBuilder().build())
                .setValueStr(ValueMetaUtils.extractAndFormatParamValue(accumulatedValue, valueMeta))
                .setRecordedAtIso8601(val.getRecordedAtIso8601())
                .build()
            );
        }
        return accumulatedVals;
    }

    private void setNetGroupRecordsMap(
        Map<String, MonitoringParamPB> deptParamMap/*in*/,
        String code/*in*/,
        MonitoringGroupBetaPB groupBeta/*in*/,
        List<MonitoringRecordValPB> sumRecordValues/*in*/,
        Map<String, MonitoringCodeRecordsPB> netCodeRecordsMap/*out*/
    ) {
        // 获取元数据
        ValueMetaPB groupValueMeta = groupBeta.getSumValueMeta();
        ValueMetaPB resultValueMeta = null;
        if (deptParamMap != null) {
            MonitoringParamPB paramMeta = deptParamMap.get(code);
            resultValueMeta = paramMeta == null ? null : paramMeta.getValueMeta();
        }

        // 转化结果数据
        List<MonitoringRecordValPB> resultSumRecordValues = new ArrayList<>();
        for (MonitoringRecordValPB val : sumRecordValues) {
            GenericValuePB value = val.getValue();
            String valueStr = "";
            if (resultValueMeta != null) {
                value = ValueMetaUtils.convertGenericValue(value, groupValueMeta, resultValueMeta);
                valueStr = ValueMetaUtils.extractAndFormatParamValue(value, resultValueMeta);
            } else {
                valueStr = ValueMetaUtils.extractAndFormatParamValue(value, groupValueMeta);
            }
            resultSumRecordValues.add(val.toBuilder()
                .setValue(value)
                .setValueStr(valueStr)
                .build()
            );
        }
        netCodeRecordsMap.put(code, MonitoringCodeRecordsPB.newBuilder()
            .setParamCode(code)
            .addAllRecordValue(resultSumRecordValues)
            .build()
        );
    }

    private MonitoringRecordValPB newRecordValPB(
        LocalDateTime recordedAt, GenericValuePB value, ValueMetaPB valueMeta
    ) {
        String recordedAtIso8601 = TimeUtils.toIso8601String(recordedAt, ZONE_ID);
        String valueStr = ValueMetaUtils.extractAndFormatParamValue(value, valueMeta);
        return MonitoringRecordValPB.newBuilder()
            .setId(Consts.VIRTUAL_RECORD_ID)
            .setValue(value)
            .setValueStr(valueStr)
            .setRecordedAtIso8601(recordedAtIso8601)
            .build();
    }

    private List<MonitoringRecordValPB> getRecordValPBList(MonitoringGroupRecordsPB inGroupRecords) {
        if (inGroupRecords == null) return new ArrayList<>();
        List<MonitoringCodeRecordsPB> codeRecordsList = inGroupRecords.getCodeRecordsList();
        MonitoringCodeRecordsPB summaryRecord = codeRecordsList.stream()
            .filter(r -> MonEnums.PARAM_CODE_SUMMARY.equals(r.getParamCode()))
            .findFirst().orElse(null);
        if (summaryRecord == null) {
            log.warn("No summary record found in group {} records {}",
                inGroupRecords.getDeptMonitoringGroupId(), inGroupRecords);
            return new ArrayList<>();
        }
        return summaryRecord.getRecordValueList();
    }

    public List<MonitoringGroupRecordsPB> getNonBalanceGroupRecordsList(GetGroupRecordsListArgs args) {
        List<PatientMonitoringRecord> recordList = args.recordList;
        if (recordList == null || recordList.isEmpty()) return new ArrayList<>();
        Map<String /*param_code*/, List<PatientMonitoringRecord>> recordsMap =
             recordList.stream().collect(Collectors.groupingBy(
                PatientMonitoringRecord::getMonitoringParamCode,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    list -> list.stream()
                        .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
                        .collect(Collectors.toList())
                )
            ));

        List<MonitoringGroupRecordsPB> groupRecordsList = new ArrayList<>();
        for (MonitoringGroupBetaPB groupBeta : args.groupBetaList) {
            MonitoringGroupRecordsPB groupRecords = getNonBalanceGroupRecords(
                args.pid, args.deptId, args.startTime, args.endTime, args.modifiedBy, groupBeta, recordsMap
            );
            if (groupRecords != null && !groupRecords.getCodeRecordsList().isEmpty()) {
                groupRecordsList.add(groupRecords);
            }
        }
        return groupRecordsList;
    }

    public MonitoringGroupRecordsPB getNonBalanceGroupRecords(
        Long pid, String deptId, LocalDateTime startTime/*in*/, LocalDateTime endTime/*in*/,String modifiedBy/*in*/,
        MonitoringGroupBetaPB groupBeta/*in*/, Map<String /*param_code*/, List<PatientMonitoringRecord>> recordsMap/*in*/
    ) {
        // 从groupBeta提取关键参数
        Integer deptMonGroupId = groupBeta.getId();
        List<MonitoringParamPB> groupParamList = groupBeta.getParamList();

        // 构建返回值
        MonitoringGroupRecordsPB.Builder groupRecordsBuilder = MonitoringGroupRecordsPB.newBuilder()
            .setDeptMonitoringGroupId(deptMonGroupId);
        if (recordsMap == null || recordsMap.isEmpty() || groupParamList.isEmpty()) return groupRecordsBuilder.build();

        // 构建组内每行观察项的值，统计每列时间的合计值
        List<MonitoringCodeRecordsPB> codeRecordsList = new ArrayList<>();
        for (MonitoringParamPB monParamPB : groupParamList) {
            // 提取观察项参数
            String paramCode = monParamPB.getCode();

            // 构建一行数据
            List<PatientMonitoringRecord> recordList = recordsMap.get(paramCode);
            if (recordList == null || recordList.isEmpty()) continue;

            MonitoringCodeRecordsPB.Builder codeRecordsBuilder = MonitoringCodeRecordsPB
                .newBuilder().setParamCode(paramCode);
            List<MonitoringRecordValPB> recordValPBList = new ArrayList<>();
            for (PatientMonitoringRecord record : recordList) {
                MonitoringValuePB monitoringValue = ProtoUtils.decodeMonitoringValue(record.getParamValue());
                if (monitoringValue == null) continue;

                recordValPBList.add(MonitoringRecordValPB.newBuilder()
                    .setId(record.getId())
                    .setValue(monitoringValue.getValue())
                    .addAllValues(monitoringValue.getValuesList())
                    .setValueStr(record.getParamValueStr())
                    .setRecordedAtIso8601(TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID))
                    .build()
                );
            }
            if (recordValPBList.isEmpty()) continue;
            codeRecordsList.add(codeRecordsBuilder.addAllRecordValue(recordValPBList).build());
        }

        if (!codeRecordsList.isEmpty()) groupRecordsBuilder.addAllCodeRecords(codeRecordsList);
        return groupRecordsBuilder.build();
    }

    // 存储小时汇总数据，天汇总数据
    public void storeGroupRecordsStats(GetGroupRecordsListArgs args) {
        List<PatientMonitoringRecord> recordList = args.recordList == null ? new ArrayList<>() : args.recordList;
        Map<String /*param_code*/, List<PatientMonitoringRecord>> recordsMap =
             recordList.stream().collect(Collectors.groupingBy(
                PatientMonitoringRecord::getMonitoringParamCode,
                Collectors.collectingAndThen(
                    Collectors.toList(),
                    list -> list.stream()
                        .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
                        .collect(Collectors.toList())
                )
            ));

        boolean sumRecordsUpdated = false;
        for (MonitoringGroupBetaPB groupBeta : args.groupBetaList) {
            // 提取元数据
            String hourlySumCode = getHourlySumCode(groupBeta.getBalanceRole());
            if (hourlySumCode == null) continue;
            ValueMetaPB hourlySumValueMeta = groupBeta.getSumValueMeta();
            List<MonitoringParamPB> paramList = groupBeta.getParamList();

            // 合计结果暂存Map
            Map<LocalDateTime, GenericValuePB> sumAtValMap = new HashMap<>();
            Integer maxDecimalPlaces = 0;
            for (MonitoringParamPB param : paramList) {
                String paramCode = param.getCode();
                Integer decimalPlaces = param.getValueMeta().getDecimalPlaces();

                // 获取对应的观察项记录
                List<PatientMonitoringRecord> paramRecordList = recordsMap.get(paramCode);
                MonitoringCodeRecordsPB codeRecords = getCodeRecords(
                    param, paramRecordList, hourlySumValueMeta, sumAtValMap
                );
                if (codeRecords != null && !codeRecords.getRecordValueList().isEmpty()) {
                    maxDecimalPlaces = Math.max(maxDecimalPlaces, decimalPlaces);
                }
            }
            hourlySumValueMeta = hourlySumValueMeta.toBuilder()
                .setDecimalPlaces(maxDecimalPlaces)
                .build();

            // 存储小时合计值
            if (monitoringRecordUtils.saveBalanceSumRecords(
                args.startTime, args.endTime, args.modifiedBy, args.pid, args.deptId,
                hourlySumCode, hourlySumValueMeta, sumAtValMap
            )) sumRecordsUpdated = true;

            // 存储对应的日平衡量
            sumDailyBalanceStats(args, hourlySumCode, hourlySumValueMeta, sumAtValMap);
        }

        if (sumRecordsUpdated) {
            pnrUtils.updateLatestDataTime(
                args.pid, args.deptId, args.startTime, args.endTime, TimeUtils.getNowUtc()
            );
        }
    }

    private static String getHourlySumCode(int balanceRole) {
        return switch (balanceRole) {
            case MonEnums.BALANCE_TYPE_IN -> MonEnums.NET_HOURLY_IN_CODE;
            case MonEnums.BALANCE_TYPE_OUT -> MonEnums.NET_HOURLY_OUT_CODE;
            default -> null;
        };
    }

    private void sumDailyBalanceStats(
        GetGroupRecordsListArgs args, String hourlySumCode, ValueMetaPB hourlySumValueMeta,
        Map<LocalDateTime, GenericValuePB> sumAtValMap
    ) {
        // 从args中提取关键参数
        Long pid = args.pid;
        String deptId = args.deptId;
        LocalDateTime startTimeUtc = args.startTime;
        LocalDateTime endTimeUtc = args.endTime;
        List<LocalDateTime> statsUtcs = args.balanceStatTimeUtcHistory;
        String modifiedBy = args.modifiedBy;

        // 获取每日合计的paramCode和ValueMetaPB
        String dailySumCode = switch (hourlySumCode) {
            case MonEnums.NET_HOURLY_IN_CODE -> MonEnums.DAILY_TOTAL_INTAKE_CODE;
            case MonEnums.NET_HOURLY_OUT_CODE -> MonEnums.DAILY_TOTAL_OUTPUT_CODE;
            default -> null;
        };
        if (dailySumCode == null) return;
        MonitoringParamPB dailySumParam =
            deptMonMetaReader.getMonitoringParam(deptId, dailySumCode);
        if (dailySumParam == null) return;
        ValueMetaPB dailySumValueMeta = dailySumParam.getValueMeta();

        // 初始化每日合计Map, 将每小时合计转换为每日合计
        Map<LocalDateTime/*localMidnightUtc*/, Float> dailySumMap = new HashMap<>();

        List<Pair<LocalDateTime, GenericValuePB>> hourlySumList = new ArrayList<>();
        for (Map.Entry<LocalDateTime, GenericValuePB> entry : sumAtValMap.entrySet()) {
            hourlySumList.add(new Pair<>(entry.getKey(), entry.getValue()));
        }
        hourlySumList.sort(Comparator.comparing(Pair::getFirst));

        // timeUtc => timeLocal => timeLocalMidnight => timeMidnightUtc
        LocalDateTime startTimeLocal = TimeUtils.getLocalDateTimeFromUtc(startTimeUtc, ZONE_ID);
        LocalDateTime startTimeLocalMidnight = TimeUtils.truncateToDay(startTimeLocal);
        LocalDateTime startTimeMidnightUtc = TimeUtils.getUtcFromLocalDateTime(startTimeLocalMidnight, ZONE_ID);
        LocalDateTime endTimeLocal = TimeUtils.getLocalDateTimeFromUtc(endTimeUtc, ZONE_ID);
        LocalDateTime endTimeLocalMidnight = TimeUtils.truncateToDay(endTimeLocal);
        LocalDateTime endTimeMidnightUtc = TimeUtils.getUtcFromLocalDateTime(endTimeLocalMidnight, ZONE_ID);

        // 统计[startTimeLocalMidnight, endTimeLocalMidnight)之间的每日合计，移动statsUtcIdx和hourlySumIdx到合适的位置
        LocalDateTime timeLocalMidnight = startTimeLocalMidnight;
        LocalDateTime timeMidnightUtc = TimeUtils.getUtcFromLocalDateTime(timeLocalMidnight, ZONE_ID);
        int statsUtcIdx = 0, statsUtcSize = statsUtcs.size();
        int hourlySumIdx = 0, hourlySumSize = hourlySumList.size();
        while (timeLocalMidnight.isBefore(endTimeLocalMidnight)) {
            // 移动 statsUtcIdx 到合适的位置，推算 timeLocalMidnight 对应的统计开始小时数
            while (statsUtcIdx < statsUtcSize - 1) {
                LocalDateTime nextStatsUtc = statsUtcs.get(statsUtcIdx + 1);
                LocalDateTime nextStatsLocal = TimeUtils.getLocalDateTimeFromUtc(nextStatsUtc, ZONE_ID);
                LocalDateTime nextStatsLocalMidnight = TimeUtils.truncateToDay(nextStatsLocal);
                if (!timeLocalMidnight.isBefore(nextStatsLocalMidnight)) statsUtcIdx++;
                else break;
            }
            LocalDateTime statsUtc = statsUtcs.get(statsUtcIdx);
            int startHours = TimeUtils.getLocalDateTimeFromUtc(statsUtc, ZONE_ID).getHour();
            LocalDateTime statsStartUtc = TimeUtils.getUtcFromLocalDateTime(
                timeLocalMidnight.withHour(startHours), ZONE_ID);

            // 移动 hourlySumIdx 到 statsStartUtc 对应的位置
            while (hourlySumIdx < hourlySumSize) {
                if (hourlySumList.get(hourlySumIdx).getFirst().isBefore(statsStartUtc)) hourlySumIdx++;
                else break;
            }
            if (hourlySumIdx >= hourlySumSize) break;

            // 统计 dailySumMap
            float dailySum = 0;
            LocalDateTime statsEndUtc = TimeUtils.getUtcFromLocalDateTime(
                timeLocalMidnight.plusDays(1).withHour(startHours), ZONE_ID);
            for (int sumIdx = hourlySumIdx;
                sumIdx < hourlySumSize && hourlySumList.get(sumIdx).getFirst().isBefore(statsEndUtc);
                sumIdx++
            ) {
                GenericValuePB hourlySum = hourlySumList.get(sumIdx).getSecond();
                dailySum += ValueMetaUtils.convertToFloat(hourlySum, hourlySumValueMeta);
            }
            dailySumMap.put(timeMidnightUtc, dailySum);
            timeLocalMidnight = timeLocalMidnight.plusDays(1);
            timeMidnightUtc = TimeUtils.getUtcFromLocalDateTime(timeLocalMidnight, ZONE_ID);
        }
        monitoringRecordUtils.saveDailyStats(
            startTimeMidnightUtc, endTimeMidnightUtc, modifiedBy, pid, deptId,
            dailySumCode, dailySumValueMeta, dailySumMap
        );
    }

    public List<BalanceGroupSummaryPB> summarizeBalanceGroups(GetGroupRecordsListArgs args) {
        List<MonitoringGroupRecordsPB> groupRecordsList = getGroupRecordsList(args);
        Map<Integer, MonitoringGroupBetaPB> groupBetaMap = args.groupBetaList.stream()
            .collect(Collectors.toMap(MonitoringGroupBetaPB::getId, gb -> gb));
        Map<String, MonitoringParamPB> paramMap = args.paramMap;
        List<BalanceGroupSummaryPB> summaries = new ArrayList<>();
        for (MonitoringGroupRecordsPB recordsPb : groupRecordsList) {
            MonitoringGroupBetaPB groupMeta = groupBetaMap.get(recordsPb.getDeptMonitoringGroupId());
            if (groupMeta == null) continue;

            Double sumMl = null;
            String sumStr = null;
            List<BalanceParamRecordPB> bpRecords = new ArrayList<>();
            for (MonitoringCodeRecordsPB codeRecords : recordsPb.getCodeRecordsList()) {
                // 处理平衡量
                if (MonEnums.NET_ACCUMULATED_NET_CODE.equals(codeRecords.getParamCode())) {
                    // 只取汇总值
                    for (MonitoringRecordValPB valPb : codeRecords.getRecordValueList()) {
                        if (StrUtils.isBlank(valPb.getRecordedAtIso8601())) {
                            sumMl = ValueMetaUtils.convertToDouble(valPb.getValue(), groupMeta.getSumValueMeta());
                            sumStr = valPb.getValueStr();
                        }
                    }
                    bpRecords.clear();
                    break;
                }
                // 处理出量、入量
                if (MonEnums.PARAM_CODE_SUMMARY.equals(codeRecords.getParamCode())) {
                    // 只取汇总值
                    for (MonitoringRecordValPB valPb : codeRecords.getRecordValueList()) {
                        if (StrUtils.isBlank(valPb.getRecordedAtIso8601())) {
                            sumMl = ValueMetaUtils.convertToDouble(valPb.getValue(), groupMeta.getSumValueMeta());
                            sumStr = valPb.getValueStr();
                        }
                    }
                } else {
                    MonitoringParamPB paramMeta = paramMap.get(codeRecords.getParamCode());
                    if (paramMeta == null) continue;
                    String paramName = paramMeta.getName();
                    ValueMetaPB paramValueMeta = paramMeta.getValueMeta();

                    Double paramSumMl = null;
                    String paramSumStr = null;
                    for (MonitoringRecordValPB valPb : codeRecords.getRecordValueList()) {
                        // 只取汇总值
                        if (StrUtils.isBlank(valPb.getRecordedAtIso8601())) {
                            paramSumMl = ValueMetaUtils.convertToDouble(valPb.getValue(), paramValueMeta);
                            paramSumStr = valPb.getValueStr();
                        }
                    }
                    if (paramSumMl != null && paramSumStr != null) {
                        bpRecords.add(BalanceParamRecordPB.newBuilder()
                            .setParamCode(codeRecords.getParamCode())
                            .setParamName(paramName)
                            .setSumMl(paramSumMl)
                            .setSumStr(paramSumStr)
                            .build()
                        );
                    }
                }
            }
            if (sumMl != null && sumStr != null) {
                summaries.add(BalanceGroupSummaryPB.newBuilder()
                    .setDeptMonitoringGroupId(recordsPb.getDeptMonitoringGroupId())
                    .setDeptMonitoringGroupName(groupMeta.getName())
                    .setBalanceRole(groupMeta.getBalanceRole())
                    .setSumMl(sumMl)
                    .setSumStr(sumStr)
                    .addAllParamRecord(bpRecords)
                    .build()
                );
            }
        }
        return summaries;
    }

    private final String ZONE_ID;

    private final DeptMonMetaReader deptMonMetaReader;
    private final MonitoringRecordUtils monitoringRecordUtils;
    private final PatientNursingReportInvalidationService pnrUtils;
}
