package com.jingyicare.jingyi_icis_engine.service.monitorings;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class DeviceDataFetcher {
    public DeviceDataFetcher(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientConfig patientConfig,
        @Autowired PatientDeviceService patientDevService,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired PatientMonitoringRecordRepository monRecRepo,
        @Autowired DeviceDataRepository devDataRepo,
        @Autowired DeviceDataHourlyRepository devDataHourlyRepo,
        @Autowired DeviceDataHourlyApproxRepository devDataHourlyApproxRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.APPROX_SECONDS = protoService.getConfig().getDevice().getApproxSeconds();
        this.paramCodesToApprox = new HashSet<>(
            protoService.getConfig().getDevice().getParamCodeToApproxList()
        );
        aliasCodeMap = new HashMap<>();
        for (AltParamCodePairPB pair : protoService.getConfig().getDevice().getAlternativeCodePairList()) {
            String origCode = pair.getReplacingCode();
            String aliasCode = pair.getReplacedCode();
            if (!aliasCodeMap.containsKey(origCode)) {
                aliasCodeMap.put(origCode, new ArrayList<>());
            }
            aliasCodeMap.get(origCode).add(aliasCode);
        }

        this.protoService = protoService;
        this.patientConfig = patientConfig;
        this.patientDevService = patientDevService;
        this.patientRepo = patientRepo;
        this.monRecRepo = monRecRepo;
        this.devDataRepo = devDataRepo;
        this.devDataHourlyRepo = devDataHourlyRepo;
        this.devDataHourlyApproxRepo = devDataHourlyApproxRepo;
    }

    public static class FetchContext {
        public StatusCode statusCode;
        public Long pid;
        public String deptId;
        public LocalDateTime from;
        public LocalDateTime to;
        public List<LocalDateTime> customTimes;
        public List<LocalDateTime> allTimes;
        public Map<String, MonitoringParamPB> paramMap;
        public PatientRecord patientRecord;
        public String modifiedBy;

        public FetchContext(Long pid, LocalDateTime from, LocalDateTime to, String modifiedBy) {
            this.statusCode = StatusCode.OK;
            this.pid = pid;
            this.deptId = null;
            this.from = from;
            this.to = to;
            this.customTimes = new ArrayList<>();
            this.allTimes = new ArrayList<>();
            this.paramMap = new HashMap<>();
            this.patientRecord = null;
            this.modifiedBy = modifiedBy;
        }
    }

    @AllArgsConstructor
    public static class DevDataQuery {
        public String devBedNum;
        public Integer devId;
        public LocalDateTime start;
        public LocalDateTime end;
        public LocalDateTime approxStart;
        public LocalDateTime approxEnd;

        String getDebugStr() {
            return String.format("DevDataQuery{devBedNum='%s', devId=%d, start=%s, end=%s, approxStart=%s, approxEnd=%s}",
                devBedNum, devId, start, end, approxStart, approxEnd);
        }
    }

    public Pair<StatusCode, List<PatientMonitoringRecord>/*recordsToAdd*/> fetch(
        Long pid, LocalDateTime from, LocalDateTime to,
        List<PatientMonitoringTimePointPB> customTimePoints,
        List<MonitoringGroupBetaPB> paramGroups,
        String modifiedBy
    ) {
        FetchContext ctx = getFetchContext(pid, from, to, customTimePoints, paramGroups, modifiedBy);
        if (ctx.statusCode != StatusCode.OK) return new Pair<>(ctx.statusCode, null);
        List<DevDataQuery> devDataQueries = getDeviceDataQueries(ctx);
        List<DeviceData> deviceDataList = getDeviceData(ctx,devDataQueries);
        Map<String, List<DeviceData>> mergedDeviceDataMap = getMergedDeviceData(ctx, deviceDataList);
        List<PatientMonitoringRecord> monRecsToAdd = generateMonitoringRecords(ctx, mergedDeviceDataMap);

        return new Pair<>(StatusCode.OK, monRecsToAdd);
    }

    // public 只是为了写单元测试
    public FetchContext getFetchContext(
        Long pid, LocalDateTime from, LocalDateTime to,
        List<PatientMonitoringTimePointPB> customTimePoints,
        List<MonitoringGroupBetaPB> paramGroups,
        String modifiedBy
    ) {
        FetchContext ctx = new FetchContext(pid, from, to, modifiedBy);

        PatientRecord patient = patientRepo.findById(pid).orElse(null);
        if (patient == null || patient.getAdmissionTime() == null) {
            log.error("Patient not found or not admitted: {}", pid);
            ctx.statusCode = StatusCode.PATIENT_NOT_FOUND;
            return ctx;
        }
        ctx.deptId = patient.getDeptId();
        ctx.patientRecord = patient;

        if (from.isBefore(patient.getAdmissionTime())) {
            ctx.from = patient.getAdmissionTime();
        }

        if (patient.getDischargeTime() != null && to.isAfter(patient.getDischargeTime())) {
            ctx.to = patient.getDischargeTime();
        }

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        if (ctx.to.isAfter(nowUtc)) ctx.to = nowUtc;

        for (PatientMonitoringTimePointPB timePoint : customTimePoints) {
            LocalDateTime customTime = TimeUtils.fromIso8601String(timePoint.getTimePointIso8601(), "UTC");
            if (customTime.isBefore(ctx.from) || !customTime.isBefore(ctx.to)) continue;
            ctx.customTimes.add(customTime);
        }

        LocalDateTime startHour = TimeUtils.truncateToHour(ctx.from);
        if (startHour.isBefore(ctx.from)) startHour = startHour.plusHours(1);
        while (startHour.isBefore(ctx.to)) {
            ctx.allTimes.add(startHour);
            startHour = startHour.plusHours(1);
        }
        ctx.allTimes.addAll(ctx.customTimes);
        ctx.allTimes.sort(Comparator.naturalOrder());

        for (MonitoringGroupBetaPB group : paramGroups) {
            for (MonitoringParamPB param : group.getParamList()) {
                ctx.paramMap.put(param.getCode(), param);
            }
        }

        return ctx;
    }

    public List<DevDataQuery> getDeviceDataQueries(FetchContext ctx) {
        List<DevDataQuery> queries = new ArrayList<>();
        LocalDateTime approxFrom = ctx.from.minusSeconds(APPROX_SECONDS);
        LocalDateTime approxTo = ctx.to.plusSeconds(APPROX_SECONDS);

        // 获取床位绑定历史[{bedNum, from, to}]
        PatientDeviceService.UsageHistory<PatientDeviceService.BedName> bedHistory =
            patientDevService.getBedHistory(ctx.patientRecord);
        final int bedHistorySize = bedHistory.usageRecords.size();
        for (int i = 0; i < bedHistorySize; ++i) {
            Pair<PatientDeviceService.BedName, LocalDateTime> bedTimePair = bedHistory.usageRecords.get(i);
            final String devBedNum = bedTimePair.getFirst().deviceBedNumber;

            // 用from/to对start/end进行裁剪
            LocalDateTime start = bedTimePair.getSecond();
            LocalDateTime approxStart = start;
            if (start.isBefore(ctx.from)) start = ctx.from;
            if (approxStart.isBefore(approxFrom)) approxStart = approxFrom;

            LocalDateTime end = (i == bedHistorySize - 1) ?
                bedHistory.endTime : bedHistory.usageRecords.get(i + 1).getSecond();
            LocalDateTime approxEnd = end;
            if (end == null || end.isAfter(ctx.to)) end = ctx.to;
            if (approxEnd == null || approxEnd.isAfter(approxTo)) approxEnd = approxTo;
            if (bedTimePair.getFirst().switchType.equals(patientDevService.getSwitchTypeReadmissionAdmitId())) {
                continue;  // 跳过[重返-出科， 重返-入科)之间的记录
            }
            if (!start.isBefore(end)) continue;
            queries.add(new DevDataQuery(devBedNum, null, start, end, approxStart, approxEnd));
        }

        // 获取设备绑定历史[{deviceId, from, to}]
        List<DeviceInfoWithBindingPB> devBindings = patientConfig.getPatientDeviceBindingHistory(
            ctx.pid, ctx.patientRecord.getIcuName(), null, ctx.from, ctx.to
        );
        for (DeviceInfoWithBindingPB devBinding : devBindings) {
            for (DeviceBindingPB binding : devBinding.getBindingList()) {
                LocalDateTime start = TimeUtils.fromIso8601String(binding.getBindingTimeIso8601(), "UTC");
                LocalDateTime approxStart = start;
                if (start.isBefore(ctx.from)) start = ctx.from;
                if (approxStart.isBefore(approxFrom)) approxStart = approxFrom;

                LocalDateTime end = TimeUtils.fromIso8601String(binding.getUnbindingTimeIso8601(), "UTC");
                LocalDateTime approxEnd = end;
                if (end == null || end.isAfter(ctx.to)) end = ctx.to;
                if (approxEnd == null || approxEnd.isAfter(approxTo)) approxEnd = approxTo;

                if (!start.isBefore(end)) continue;
                queries.add(new DevDataQuery(null, binding.getDeviceId(), start, end, approxStart, approxEnd));
            }
        }

        return queries;
    }

    List<DeviceData> getDeviceData(FetchContext ctx, List<DevDataQuery> deviceDataQueries) {
        List<DeviceDataHourlyApprox> approxDataList = new ArrayList<>();
        List<DeviceDataHourly> hourlyDataList = new ArrayList<>();
        List<DeviceData> deviceDataList = new ArrayList<>();
        for (DevDataQuery query : deviceDataQueries) {
            if (query.devBedNum != null) {
                approxDataList.addAll(devDataHourlyApproxRepo
                    .findByBedNumberAndRecordedAtAndParamCodeIn(
                        query.devBedNum, query.approxStart, query.approxEnd, paramCodesToApprox.stream().toList()
                    )
                );
                hourlyDataList.addAll(devDataHourlyRepo
                    .findByBedNumberAndRecordedAt(query.devBedNum, query.start, query.end)
                );
                deviceDataList.addAll(devDataRepo.findByBedNumberAndRecordedAtIn(query.devBedNum, ctx.customTimes));
            } else if (query.devId != null) {
                approxDataList.addAll(devDataHourlyApproxRepo
                    .findByDeviceIdAndRecordedAtAndParamCodeIn(
                        query.devId, query.approxStart, query.approxEnd, paramCodesToApprox.stream().toList()
                    )
                );
                hourlyDataList.addAll(devDataHourlyRepo
                    .findByDeviceIdAndRecordedAt(query.devId, query.start, query.end)
                );
                deviceDataList.addAll(devDataRepo.findByDeviceIdAndRecordedAtIn(query.devId, ctx.customTimes));
            } else {
                log.warn("Invalid DevDataQuery: {}", query);
            }
        }

        // 去重
        final Set<Long> seenIds = new HashSet<>();
        approxDataList = approxDataList.stream().filter(data -> seenIds.add(data.getId())).toList();
        seenIds.clear();
        hourlyDataList = hourlyDataList.stream().filter(
            data -> seenIds.add(data.getId()) && !paramCodesToApprox.contains(data.getParamCode())
        ).toList();
        seenIds.clear();
        List<DeviceData> uniqueDataList = new ArrayList<>();
        uniqueDataList.addAll(deviceDataList.stream().filter(
            data -> seenIds.add(data.getId()) && !paramCodesToApprox.contains(data.getParamCode())
        ).toList());

        // 合并，返回
        for (DeviceDataHourlyApprox approxData : approxDataList) {
            DeviceData devData = DeviceData.builder()
                .id(approxData.getId())
                .departmentId(approxData.getDepartmentId())
                .deviceId(approxData.getDeviceId())
                .deviceType(approxData.getDeviceType())
                .deviceBedNumber(approxData.getDeviceBedNumber())
                .paramCode(approxData.getParamCode())
                .recordedAt(approxData.getRecordedAt())
                .recordedStr(approxData.getRecordedStr())
                .build();
            uniqueDataList.add(devData);
        }
        for (DeviceDataHourly hourlyData : hourlyDataList) {
            DeviceData devData = DeviceData.builder()
                .id(hourlyData.getId())
                .departmentId(hourlyData.getDepartmentId())
                .deviceId(hourlyData.getDeviceId())
                .deviceType(hourlyData.getDeviceType())
                .deviceBedNumber(hourlyData.getDeviceBedNumber())
                .paramCode(hourlyData.getParamCode())
                .recordedAt(hourlyData.getRecordedAt())
                .recordedStr(hourlyData.getRecordedStr())
                .build();
            uniqueDataList.add(devData);
        }
        return uniqueDataList;
    }

    // public只是为了方便测试
    // todo(guzhenyu): 考虑兼容另一种算法 - "用过即弃"原则
    public Map<String/*paramCode*/, List<DeviceData>> getMergedDeviceData(
        FetchContext ctx, List<DeviceData> deviceDataList
    ) {
        // 1) 分桶
        Map<String, List<DeviceData>> devDataMap = bucketByParamCode(deviceDataList);

        // 2) 生成高优复制件
        Map<String, List<DeviceData>> highPriorityDevDataMap = buildHighPriority(devDataMap, aliasCodeMap);

        // 将高优先级的设备数据合并到原始设备数据中（未去重）
        for (Map.Entry<String, List<DeviceData>> entry : highPriorityDevDataMap.entrySet()) {
            String paramCode = entry.getKey();
            List<DeviceData> newDevDataList = entry.getValue();
            List<DeviceData> existingDevDataList = devDataMap.computeIfAbsent(paramCode, k -> new ArrayList<>());
            existingDevDataList.addAll(newDevDataList);
        }

        // 3) 按(参数, 时刻)去重（notes != null 优先，之后按 id 升序兜底），并按时间排序
        Map<String, List<DeviceData>> uniqueDevDataMap = dedupePerParam(devDataMap);

        // 就近原则参数，1 DeviceData => n RawMonitoringData
        Map<String/*paramCode*/, List<DeviceData>> approxDevDataMap = new HashMap<>();
        for (Map.Entry<String, List<DeviceData>> entry : uniqueDevDataMap.entrySet()) {
            String paramCode = entry.getKey();
            if (!paramCodesToApprox.contains(paramCode)) continue;

            List<DeviceData> devDataList = entry.getValue();
            if (devDataList.isEmpty()) continue;

            // 4）按照就近原则，生成近似数据
            List<DeviceData> approxDataList = approxSeries(
                devDataList, ctx.allTimes, APPROX_SECONDS,
                true /* rightOpen */ // 窗口右开
            );
            approxDevDataMap.put(paramCode, approxDataList);
        }

        for (Map.Entry<String, List<DeviceData>> entry : approxDevDataMap.entrySet()) {
            String paramCode = entry.getKey();
            List<DeviceData> approxDataList = entry.getValue();
            uniqueDevDataMap.put(paramCode, approxDataList);
        }

        // 返回合并后的设备数据
        return uniqueDevDataMap;
    }

    // getMergedDeviceData - helper methods(1)
    private Map<String, List<DeviceData>> bucketByParamCode(List<DeviceData> list) {
        Map<String, List<DeviceData>> map = new HashMap<>();
        for (DeviceData d : list) {
            map.computeIfAbsent(d.getParamCode(), k -> new ArrayList<>()).add(d);
        }
        return map;
    }

    // getMergedDeviceData - helper methods(2)
    private Map<String, List<DeviceData>> buildHighPriority(
        Map<String, List<DeviceData>> devDataMap,
        Map<String, List<String>> aliasCodeMap
    ) {
        Map<String, List<DeviceData>> out = new HashMap<>();
        for (Map.Entry<String, List<String>> e : aliasCodeMap.entrySet()) {
            String orig = e.getKey();
            List<String> aliases = e.getValue();
            if (aliases == null || aliases.isEmpty()) continue;

            List<DeviceData> src = devDataMap.get(orig);
            if (src == null) continue;

            for (String alias : aliases) {
                if (alias == null || alias.equals(orig)) continue;
                List<DeviceData> bucket = out.computeIfAbsent(alias, k -> new ArrayList<>(src.size()));
                for (DeviceData d : src) {
                    String origParamCode = d.getParamCode();
                    bucket.add(d.toBuilder().paramCode(alias).notes(origParamCode).build());
                }
            }
        }
        return out;
    }

    // getMergedDeviceData - helper methods(3)
    private Map<String, List<DeviceData>> dedupePerParam(Map<String, List<DeviceData>> devDataMap) {
        Map<String, List<DeviceData>> unique = new HashMap<>();
        for (Map.Entry<String, List<DeviceData>> e : devDataMap.entrySet()) {
            String code = e.getKey();
            List<DeviceData> list = e.getValue();

            // 每个时刻挑一条最佳
            Map<LocalDateTime, DeviceData> best = new HashMap<>();
            Set<LocalDateTime> notelessDataTimes = new HashSet<>();
            for (DeviceData d : list) {
                LocalDateTime t = d.getRecordedAt();
                if (StrUtils.isBlank(d.getNotes())) notelessDataTimes.add(t);
                DeviceData cur = best.get(t);

                // 规则：notes != null（高优复制件）优先；再按 id 升序兜底（null 最后）
                // 原参数存在值的时候，才能用有notes的覆盖
                boolean update = isNewDeviceDataPriorToOld(d, cur, true /* notes 优先 */);
                if (update) {
                    best.put(t, d);
                }
            }

            // 监护仪有数据时（notes为空），才允许呼吸机覆盖对应的数据
            List<DeviceData> out = new ArrayList<>();
            for (Map.Entry<LocalDateTime, DeviceData> e2 : best.entrySet()) {
                LocalDateTime t = e2.getKey();
                DeviceData d = e2.getValue();
                if (notelessDataTimes.contains(t)) out.add(d);
            }

            out.sort(Comparator.comparing(DeviceData::getRecordedAt)); // 保证后续近似用
            unique.put(code, out);
        }
        return unique;
    }

    private boolean isNewDeviceDataPriorToOld(
            DeviceData newData, DeviceData oldData, boolean isNotesPrior
    ) {
        if (oldData == null) return true;
        if (newData == null) return false;

        String curNotes = oldData.getNotes();
        String newNotes = newData.getNotes();
        Long curId = oldData.getId();
        Long newId = newData.getId();

        boolean oldHasNotes = !StrUtils.isBlank(curNotes);
        boolean newHasNotes = !StrUtils.isBlank(newNotes);

        if (isNotesPrior) {
            // notes 优先：旧无 / 新有 → 替换；旧有 / 新无 → 不替换；其余看 id
            if (!oldHasNotes && newHasNotes) return true;
            if (oldHasNotes && !newHasNotes) return false;
            return newerById(curId, newId);
        } else {
            // notes 不优先：特别要求——旧无 / 新有 → 保留旧（不替换）
            if (!oldHasNotes && newHasNotes) return false;
            // 旧有 / 新无：通常也应保留旧（信息更完整）
            if (oldHasNotes && !newHasNotes) return false;
            // 两边都无或两边都有 → 仅按 id 兜底
            return newerById(curId, newId);
        }
    }

    private boolean newerById(Long curId, Long newId) {
        // 保持你原来的“id 升序兜底”逻辑与空值规则：
        // curId == null 且 newId != null → 新的
        // curId  != null 且 (newId == null 或 curId < newId) → 新的
        if (curId == null) return newId != null;
        return newId == null || curId < newId;
    }

    // getMergedDeviceData - helper methods(4)
    /* 双指针近似逻辑（只在选 right 时 j++；窗口左闭右开/右闭可切换） */
    private List<DeviceData> approxSeries(
        List<DeviceData> devDataList, List<LocalDateTime> allTimes,
        long approxSeconds, boolean rightOpen
    ) {
        List<DeviceData> out = new ArrayList<>();
        int i = 0, j = 0;
        while (i < allTimes.size() && j < devDataList.size()) {
            LocalDateTime timeToFill = allTimes.get(i);
            LocalDateTime left = timeToFill.minusSeconds(approxSeconds);
            LocalDateTime right = timeToFill.plusSeconds(approxSeconds);

            DeviceData cur = devDataList.get(j);
            LocalDateTime ct = cur.getRecordedAt();

            // 已到最后一条：在你的推进不变量下，最后一条不劣于左邻
            if (j == devDataList.size() - 1) {
                if (inWindow(ct, left, right, rightOpen)) {
                    out.add(cur.toBuilder().recordedAt(timeToFill).build());
                }
                i++;
                continue;
            }

            LocalDateTime nt = devDataList.get(j + 1).getRecordedAt();

            // 两条都在左侧，推进 j
            if (ct.isBefore(timeToFill) && nt.isBefore(timeToFill)) {
                j++;
                continue;
            }

            // 左右包夹：择近；等距偏向 left（保持你现有 <= 0 的逻辑）；只在选 right 时 j++
            if (ct.isBefore(timeToFill) && !nt.isBefore(timeToFill)) {
                long dl = millisDiff(ct, timeToFill);
                long dr = millisDiff(nt, timeToFill);

                DeviceData pick = cur;
                LocalDateTime pt = ct;
                boolean chooseRight = false;

                if (dr < dl) { pick = devDataList.get(j + 1); pt = nt; chooseRight = true; }
                // 等距保持选 left（你的现有行为），如需按 notes 优先这里可改成 priority 判断

                if (inWindow(pt, left, right, rightOpen)) {
                    out.add(pick.toBuilder().recordedAt(timeToFill).build());
                }
                i++;
                if (chooseRight) j++;
                continue;
            }

            // 正好命中
            if (ct.equals(timeToFill)) {
                out.add(cur.toBuilder().recordedAt(timeToFill).build());
                i++;
                // 如需“用过即弃”，可同时 j++；当前保持不动以复用
                continue;
            }

            // cur 在右侧：它一定优于更右的 next
            if (inWindow(ct, left, right, rightOpen)) {
                out.add(cur.toBuilder().recordedAt(timeToFill).build());
            }
            i++;
        }
        return out;
    }

    // getMergedDeviceData - utils(1)
    private static long millisDiff(LocalDateTime a, LocalDateTime b) {
        return Math.abs(ChronoUnit.MILLIS.between(a, b));
    }

    // getMergedDeviceData - utils(2)
    private static boolean inWindow(
        LocalDateTime t, LocalDateTime left, LocalDateTime right, boolean rightOpen
    ) {
        return !t.isBefore(left) && (rightOpen ? t.isBefore(right) : !t.isAfter(right));
    }

    List<PatientMonitoringRecord> generateMonitoringRecords(
        FetchContext ctx,
        Map<String/*paramCode*/, List<DeviceData>> rawDeviceDataMap
    ) {
        Map<String, Set<LocalDateTime>> existingTimes = monRecRepo
            .findAllByPidAndEffectiveTimeRange(ctx.pid, ctx.from, ctx.to)
            .stream()
            .collect(Collectors.groupingBy(
                PatientMonitoringRecord::getMonitoringParamCode,
                Collectors.mapping(PatientMonitoringRecord::getEffectiveTime, Collectors.toSet())
            ));
        LocalDateTime nowUtc = TimeUtils.getNowUtc();

        int estimate = rawDeviceDataMap.values().stream().mapToInt(List::size).sum();
        List<PatientMonitoringRecord> recordsToAdd = new ArrayList<>(estimate);
        for (Map.Entry<String, List<DeviceData>> entry : rawDeviceDataMap.entrySet()) {
            List<DeviceData> devDataList = entry.getValue();
            if (devDataList.isEmpty()) continue;

            String paramCode = entry.getKey();
            Set<LocalDateTime> existingTimesForParam = existingTimes.getOrDefault(paramCode, Collections.emptySet());
            MonitoringParamPB paramPB = ctx.paramMap.get(paramCode);
            if (paramPB == null) continue;
            ValueMetaPB valueMeta = paramPB.getValueMeta();

            // 生成 PatientMonitoringRecord
            for (DeviceData devData : devDataList) {
                LocalDateTime effectiveTime = devData.getRecordedAt();

                // 跳过已经有值的PatientMonitoringRecord
                if (existingTimesForParam.contains(effectiveTime)) continue;

                // 处理设备数据值
                String recordedStr = devData.getRecordedStr();
                if (recordedStr == null || recordedStr.isEmpty()) continue;
                GenericValuePB genericVal = ValueMetaUtils.toGenericValue(recordedStr, valueMeta);
                genericVal = ValueMetaUtils.formatParamValue(genericVal, valueMeta);
                if (genericVal == null) continue;
                String paramValue = ProtoUtils.encodeMonitoringValue(
                    MonitoringValuePB.newBuilder().setValue(genericVal).build());
                String paramValueStr = ValueMetaUtils.extractAndFormatParamValue(genericVal, valueMeta);

                // 创建 PatientMonitoringRecord
                PatientMonitoringRecord record = PatientMonitoringRecord.builder()
                    .pid(ctx.pid)
                    .deptId(ctx.deptId)
                    .monitoringParamCode(paramCode)
                    .effectiveTime(effectiveTime)
                    .paramValue(paramValue)
                    .paramValueStr(paramValueStr)
                    .unit(valueMeta.getUnit())
                    .source("dev-" + String.valueOf(devData.getDeviceType()))
                    .modifiedBy(ctx.modifiedBy)
                    .modifiedAt(nowUtc)
                    .isDeleted(false)
                    .build();
                recordsToAdd.add(record);
            }
        }
        return recordsToAdd; // 返回生成的 PatientMonitoringRecord 列表
    }

    public void resetApproxSecondsForTesting(Integer approxSeconds) {
        this.APPROX_SECONDS = approxSeconds;
    }
    public void resetParamCodesToApproxForTesting(Set<String> paramCodes) {
        this.paramCodesToApprox = paramCodes;
    }
    public void resetAliasCodeMapForTesting(Map<String, List<String>> aliasCodeMap) {
        this.aliasCodeMap = aliasCodeMap;
    }

    private final String ZONE_ID;

    private Integer APPROX_SECONDS;

    private Set<String> paramCodesToApprox;
    private Map<String, List<String>> aliasCodeMap;

    private ConfigProtoService protoService;
    private PatientConfig patientConfig;
    private PatientDeviceService patientDevService;
    private PatientRecordRepository patientRepo;
    private PatientMonitoringRecordRepository monRecRepo;
    private DeviceDataRepository devDataRepo;
    private DeviceDataHourlyRepository devDataHourlyRepo;
    private DeviceDataHourlyApproxRepository devDataHourlyApproxRepo;
}