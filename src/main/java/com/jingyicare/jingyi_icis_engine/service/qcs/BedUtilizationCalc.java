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

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

// todo: 添加开关，同一个床位同一天被多个病人占用时，是否算做多个床日

@Component
@Slf4j
public class BedUtilizationCalc {
    public BedUtilizationCalc(
        @Autowired ConfigProtoService protoService,
        @Autowired PatientDeviceService patientDeviceService,
        @Autowired BedConfigRepository bedConfigRepo,
        @Autowired PatientBedHistoryRepository patientBedHistoryRepo,
        @Autowired BedCountRepository bedCountRepo
    ) {
        this.statusMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.patientDeviceService = patientDeviceService;
        this.bedConfigRepo = bedConfigRepo;
        this.patientBedHistoryRepo = patientBedHistoryRepo;
        this.bedCountRepo = bedCountRepo;
    }

    ///////////// 床位使用率常量
    public static final String QC1_UNIT = "床日";
    public static final String QC1_NORMINATOR_TITLE = "实际占用总床日数";
    public static final String QC1_NORMINATOR_COL1_TITLE = "床号";
    public static final String QC1_NORMINATOR_COL1_KEY = "bedNumber";
    public static final String QC1_NORMINATOR_COL2_TITLE = "患者姓名";
    public static final String QC1_NORMINATOR_COL2_KEY = "patientName";
    public static final String QC1_NORMINATOR_COL3_TITLE = "开始时间";
    public static final String QC1_NORMINATOR_COL3_KEY = "startTime";
    public static final String QC1_NORMINATOR_COL4_TITLE = "结束时间";
    public static final String QC1_NORMINATOR_COL4_KEY = "endTime";
    public static final String QC1_NORMINATOR_COL5_TITLE = "床日数";
    public static final String QC1_NORMINATOR_COL5_KEY = "bedDays";
    public static final String QC1_DENOMINATOR_TITLE = "实际开放总床日数";
    public static final String QC1_DENOMINATOR_COL1_TITLE = "开始时间";
    public static final String QC1_DENOMINATOR_COL1_KEY = "startDateIso8601";
    public static final String QC1_DENOMINATOR_COL2_TITLE = "结束时间";
    public static final String QC1_DENOMINATOR_COL2_KEY = "endDateIso8601";
    public static final String QC1_DENOMINATOR_COL3_TITLE = "床日数";
    public static final String QC1_DENOMINATOR_COL3_KEY = "bedDays";

    public List<QcMonthDataPB> toMonthDataList(List<QcICUBedUtilizationRatioPB> rates) {
        // 统计月度信息
        List<QcMonthDataPB> monthDataList = new ArrayList<>();
        for (QcICUBedUtilizationRatioPB rate : rates) {
            QcMonthDataPB monthData = QcMonthDataPB.newBuilder()
                .setMonthIso8601(rate.getMonthIso8601())
                .setNumerator(rate.getUsedBedDays())
                .setDenominator(rate.getTotalBedDays())
                .setUnit(QC1_UNIT)
                .setNumeratorTbl(toBedUsageTable(rate.getUsageList()))
                .setDenominatorTbl(toBedAvailableTable(rate.getAvailableList()))
                .build();
            monthDataList.add(monthData);
        }
        return monthDataList;
    }

    private QcTablePB toBedUsageTable(List<QcICUBedUsagePB> usageList) {
        QcTablePB.Builder tableBuilder = QcTablePB.newBuilder()
            .setTitle(QC1_NORMINATOR_TITLE);

        // 添加表头
        tableBuilder.addHeader(QcHeaderPB.newBuilder()
            .setTitle(QC1_NORMINATOR_COL1_TITLE)
            .setKey(QC1_NORMINATOR_COL1_KEY));
        tableBuilder.addHeader(QcHeaderPB.newBuilder()
            .setTitle(QC1_NORMINATOR_COL2_TITLE)
            .setKey(QC1_NORMINATOR_COL2_KEY));
        tableBuilder.addHeader(QcHeaderPB.newBuilder()
            .setTitle(QC1_NORMINATOR_COL3_TITLE)
            .setKey(QC1_NORMINATOR_COL3_KEY));
        tableBuilder.addHeader(QcHeaderPB.newBuilder()
            .setTitle(QC1_NORMINATOR_COL4_TITLE)
            .setKey(QC1_NORMINATOR_COL4_KEY));
        tableBuilder.addHeader(QcHeaderPB.newBuilder()
            .setTitle(QC1_NORMINATOR_COL5_TITLE)
            .setKey(QC1_NORMINATOR_COL5_KEY));

        // 添加数据行
        for (QcICUBedUsagePB usage : usageList) {
            QcRowPB row = QcRowPB.newBuilder()
                .putData(QC1_NORMINATOR_COL1_KEY, usage.getDisplayBedNumber())
                .putData(QC1_NORMINATOR_COL2_KEY, usage.getPatientIcuName())
                .putData(QC1_NORMINATOR_COL3_KEY, usage.getStartDateIso8601())
                .putData(QC1_NORMINATOR_COL4_KEY, usage.getEndDateIso8601())
                .putData(QC1_NORMINATOR_COL5_KEY,
                    String.valueOf(ValueMetaUtils.normalize(usage.getBedDays(), 2))
                ).build();
            tableBuilder.addRow(row);
        }
        return tableBuilder.build();
    }

    private QcTablePB toBedAvailableTable(List<QcICUBedAvailablePB> availableList) {
        QcTablePB.Builder tableBuilder = QcTablePB.newBuilder()
            .setTitle(QC1_DENOMINATOR_TITLE);
        
        // 添加表头
        tableBuilder.addHeader(QcHeaderPB.newBuilder()
            .setTitle(QC1_DENOMINATOR_COL1_TITLE)
            .setKey(QC1_DENOMINATOR_COL1_KEY));
        tableBuilder.addHeader(QcHeaderPB.newBuilder()
            .setTitle(QC1_DENOMINATOR_COL2_TITLE)
            .setKey(QC1_DENOMINATOR_COL2_KEY));
        tableBuilder.addHeader(QcHeaderPB.newBuilder()
            .setTitle(QC1_DENOMINATOR_COL3_TITLE)
            .setKey(QC1_DENOMINATOR_COL3_KEY));

        // 添加数据行
        for (QcICUBedAvailablePB available : availableList) {
            QcRowPB row = QcRowPB.newBuilder()
                .putData(QC1_DENOMINATOR_COL1_KEY, available.getStartDateIso8601())
                .putData(QC1_DENOMINATOR_COL2_KEY, available.getEndDateIso8601())
                .putData(QC1_DENOMINATOR_COL3_KEY,
                    String.valueOf(ValueMetaUtils.normalize(available.getBedDays(), 2))
                ).build();
            tableBuilder.addRow(row);
        }
        return tableBuilder.build();
    }

    public List<QcICUBedUtilizationRatioPB> calcRates(
        String deptId, List<PatientRecord> patients,
        LocalDateTime startUtc, LocalDateTime endUtc, String zoneId
    ) {
        // 计算整体可用床位数
        Map<LocalDateTime, List<QcICUBedAvailablePB>> bedAvailableMap = calcAvailableBeds(deptId, startUtc, endUtc, zoneId);

        // 计算床位使用情况
        Map<LocalDateTime, List<QcICUBedUsagePB>> bedUsageMap = calcBedUsage(deptId, patients, startUtc, endUtc, zoneId);

        // 统计月份
        Set<LocalDateTime> months = new HashSet<>();
        months.addAll(bedAvailableMap.keySet());
        months.addAll(bedUsageMap.keySet());

        // 按月份统计床位使用率
        List<QcICUBedUtilizationRatioPB> rates = new ArrayList<>();
        for (LocalDateTime month : months) {
            List<QcICUBedAvailablePB> availableList = bedAvailableMap.getOrDefault(month, Collections.emptyList());
            List<QcICUBedUsagePB> usageList = bedUsageMap.getOrDefault(month, Collections.emptyList());
            
            float totalBedDays = (float) availableList.stream()
                .mapToDouble(QcICUBedAvailablePB::getBedDays)
                .sum();
            
            float usedBedDays = (float) usageList.stream()
                .mapToDouble(QcICUBedUsagePB::getBedDays)
                .sum();
            
            rates.add(QcICUBedUtilizationRatioPB.newBuilder()
                .setMonthIso8601(TimeUtils.toIso8601String(month, zoneId))
                .setTotalBedDays(totalBedDays)
                .setUsedBedDays(usedBedDays)
                .addAllUsage(usageList)
                .addAllAvailable(availableList)
                .build());
        }

        rates.sort(Comparator.comparing(QcICUBedUtilizationRatioPB::getMonthIso8601));
        return rates;
    }

    @Transactional
    public Map<LocalDateTime, List<QcICUBedAvailablePB>> calcAvailableBeds(
        String deptId, LocalDateTime startUtc, LocalDateTime endUtc, String zoneId
    ) {
        // 获取本地时间
        LocalDateTime startLocal = TimeUtils.truncateToDay(TimeUtils.getLocalDateTimeFromUtc(startUtc, zoneId));
        LocalDateTime endLocal = TimeUtils.truncateToDay(TimeUtils.getLocalDateTimeFromUtc(endUtc, zoneId));

        // 获取科室床位历史
        List<Pair<LocalDateTime, Integer>> bedTimeline = new ArrayList<>();
        bedTimeline.add(new Pair<>(Consts.MIN_TIME, 0));
        bedTimeline.addAll(bedCountRepo.findByDeptIdAndIsDeletedFalse(deptId)
            .stream().sorted(Comparator.comparing(BedCount::getEffectiveTime))
            .map(bc -> new Pair<>(
                TimeUtils.getLocalDateTimeFromUtc(bc.getEffectiveTime(), zoneId), bc.getBedCount()
            ))
            .toList());

        // 设定结果变量
        Map<LocalDateTime/*月份*/, List<QcICUBedAvailablePB>> bedAvailableMap = new HashMap<>();
        if (startLocal.isBefore(Consts.MIN_TIME) || startLocal.isAfter(Consts.MAX_TIME) ||
            endLocal.isBefore(Consts.MIN_TIME) || endLocal.isAfter(Consts.MAX_TIME)
        ) {
            log.error("Start time {} is before the first bed count record {}", startLocal, Consts.MIN_TIME);
            return bedAvailableMap;
        }

        // 将bedTimeline和[startLocal, endLocal]用归并排序的思路合并处理
        for (int i = 0; i < bedTimeline.size(); i++) {
            LocalDateTime current = TimeUtils.truncateToDay(bedTimeline.get(i).getFirst());
            int bedCount = bedTimeline.get(i).getSecond();
            LocalDateTime next = (i + 1 < bedTimeline.size()) ?
                TimeUtils.truncateToDay(bedTimeline.get(i + 1).getFirst()) : Consts.MAX_TIME;
            if (bedCount <= 0) continue;  // 跳过无效的床位数

            //                current               next
            // 1. start  end                                          (start < end <= current < next, 结束统计)
            // 2. start                end                            (start < current < end <= next, 结束统计)
            // 3. start                                   end         (start < current < next < end, 结束统计)
            // 4.                      start  end                     (current <= start < end <= next, 结束统计)
            // 5.                      start              end         (current <= start < next < end, 结束统计)
            // 6.                                         start  end  (current < next <= start < end, noop & continue)

            // 跳过当前段在统计区间在查询区间右边的情况 (情况1)
            if (!current.isBefore(endLocal)) break;

            // 跳过当前段在统计区间左边的情况 （情况6）
            if (!startLocal.isBefore(next)) continue;

            // 有交集，取交集起止，按月切割，计算月内天数
            LocalDateTime overlapStart = current.isBefore(startLocal) ? startLocal : current;
            LocalDateTime overlapEnd = next.isAfter(endLocal) ? endLocal : next;
            mergeToBedAvailableMap(
                bedAvailableMap, overlapStart, overlapEnd, bedCount, zoneId
            );
        }

        return bedAvailableMap;
    }

    private void mergeToBedAvailableMap(
        Map<LocalDateTime, List<QcICUBedAvailablePB>> bedAvailableMap,
        LocalDateTime start, LocalDateTime end, int bedCount, String zoneId
    ) {
        // 按月切割
        LocalDateTime current = TimeUtils.truncateToMonthStart(start);
        while (current.isBefore(end)) {
            LocalDateTime next = TimeUtils.truncateToMonthStart(current.plusMonths(1));
            LocalDateTime overlapStart = current.isBefore(start) ? start : current;
            LocalDateTime overlapEnd = next.isAfter(end) ? end : next;
            if (!overlapEnd.isAfter(overlapStart)) {
                current = next;
                continue;
            }

            // 计算月内天数
            int daysInOverlap = (int) ChronoUnit.DAYS.between(overlapStart, overlapEnd);

            // 添加到结果中
            LocalDateTime currentUtc = TimeUtils.getUtcFromLocalDateTime(current, zoneId);
            bedAvailableMap.computeIfAbsent(currentUtc, k -> new ArrayList<>())
                .add(QcICUBedAvailablePB.newBuilder()
                    .setStartDateIso8601(TimeUtils.toIso8601String(
                        TimeUtils.getUtcFromLocalDateTime(overlapStart, zoneId),
                        zoneId
                    ))
                    .setEndDateIso8601(TimeUtils.toIso8601String(
                        TimeUtils.getUtcFromLocalDateTime(overlapEnd, zoneId),
                        zoneId
                    ))
                    .setBedDays(daysInOverlap * bedCount)
                    .build()
            );

            current = next;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    private static class QcICUBedUsage {
        public String displayBedNumber;
        public Long pid;
        public String patientIcuName;
        public LocalDateTime startLocal;
        public LocalDateTime endLocal;

        public LocalDateTime monthLocal;
        public float bedDays;
        public String exception;
    }

    @Transactional
    public Map<LocalDateTime, List<QcICUBedUsagePB>> calcBedUsage(
        String deptId, List<PatientRecord> patients,
        LocalDateTime startUtc, LocalDateTime endUtc, String zoneId
    ) {
        // 获取本地时间
        LocalDateTime startLocal = TimeUtils.truncateToDay(TimeUtils.getLocalDateTimeFromUtc(startUtc, zoneId));
        LocalDateTime endLocal = TimeUtils.truncateToDay(TimeUtils.getLocalDateTimeFromUtc(endUtc, zoneId));

        // 获取 his床位号 到 显示床位号 的映射
        Map<String/*hisBed*/, String/*displayBed*/> bedMap = bedConfigRepo
            .findByDepartmentIdAndIsDeletedFalse(deptId)
            .stream().collect(Collectors.toMap(
                BedConfig::getHisBedNumber, BedConfig::getDisplayBedNumber
            ));

        // 获取患者床位历史(按月切割)
        List<QcICUBedUsage> allBedUsage = new ArrayList<>();
        Map<Long/*pid*/, List<PatientBedHistory>> bedHistoryMap = patientBedHistoryRepo
            .findByPatientIdIn(patients.stream().map(PatientRecord::getId).toList())
            .stream().collect(Collectors.groupingBy(PatientBedHistory::getPatientId));
        for (PatientRecord patient : patients) {
            // 获取患者的开始时间，结束时间，姓名，id
            LocalDateTime patBedStartLocal = TimeUtils.getLocalDateTimeFromUtc(patient.getAdmissionTime(), zoneId);
            if (patBedStartLocal == null) {
                log.warn("Patient {} has no admission time, skipping bed usage calculation", patient.getId());
                continue;
            }
            LocalDateTime patBedEndLocal = Consts.MAX_TIME;
            if (patient.getDischargeTime() != null) {
                patBedEndLocal = TimeUtils.getLocalDateTimeFromUtc(patient.getDischargeTime(), zoneId);
            }
            Long pid = patient.getId();  // **
            String patientIcuName = patient.getIcuName();  // **
            String curDisplayBedNum = bedMap.get(patient.getHisBedNumber());
            if (curDisplayBedNum == null) {
                log.warn("Patient {} has no bed mapping for his bed {}, skipping bed usage calculation",
                    pid, patient.getHisBedNumber());
                continue;
            }

            // 根据病人床位历史构建床位使用记录
            List<PatientBedHistory> bedHistories = bedHistoryMap.getOrDefault(patient.getId(), Collections.emptyList());
            for (int i = 0; i < bedHistories.size(); i++) {
                PatientBedHistory bedHistory = bedHistories.get(i);
                String displayBedNum = bedHistory.getDisplayBedNumber();  // **
                LocalDateTime switchTimeLocal = TimeUtils.getLocalDateTimeFromUtc(bedHistory.getSwitchTime(), zoneId);  // **
                if (bedHistory.getSwitchType().equals(patientDeviceService.getSwitchTypeReadmissionAdmitId())) {
                    patBedStartLocal = switchTimeLocal;
                    continue;
                }

                allBedUsage.addAll(getMonthSegments(
                    displayBedNum, pid, patientIcuName, startLocal, endLocal, patBedStartLocal, switchTimeLocal
                ));
                patBedStartLocal = switchTimeLocal;  // 更新开始时间为当前切换时间
            }
            // 添加最后一个床位使用记录
            allBedUsage.addAll(getMonthSegments(
                curDisplayBedNum, pid, patientIcuName, startLocal, endLocal, patBedStartLocal, patBedEndLocal
            ));
        }

        // 按月分组
        Map<LocalDateTime, List<QcICUBedUsage>> bedUsageMap = allBedUsage.stream()
            .collect(Collectors.groupingBy(QcICUBedUsage::getMonthLocal));

        // 转换为PB格式，组内按照displayBedNumber, startLocal排序
        Map<LocalDateTime, List<QcICUBedUsagePB>> bedUsagePBMap = new HashMap<>();
        for (Map.Entry<LocalDateTime, List<QcICUBedUsage>> entry : bedUsageMap.entrySet()) {
            LocalDateTime monthUtc = TimeUtils.getUtcFromLocalDateTime(entry.getKey(), zoneId);
            List<QcICUBedUsagePB> usagePBList = toProtoList(entry.getValue(), zoneId);
            bedUsagePBMap.put(monthUtc, usagePBList);
        }

        return bedUsagePBMap;
    }

    private List<QcICUBedUsage> getMonthSegments(
        String displayBedNum, Long pid, String patientIcuName,
        LocalDateTime queryStartDay, LocalDateTime queryEndDay,
        LocalDateTime patBedStart, LocalDateTime patBedEnd
    ) {
        // LocalDateTime bedStartDay = TimeUtils.truncateToDay();
        // LocalDateTime bedEndDay = TimeUtils.truncateToDay();

        List<QcICUBedUsage> bedUsageList = new ArrayList<>();
        if (!patBedStart.isBefore(queryEndDay)) return bedUsageList;  // 如果病人床位开始时间在统计结束时间之后，直接返回空列表
        if (!queryStartDay.isBefore(patBedEnd)) return bedUsageList;  // 如果统计开始时间在病人床位结束时间之后，直接返回空列表

        // 有交集，取交集起止，按月切割
        LocalDateTime start = patBedStart.isBefore(queryStartDay) ? queryStartDay : patBedStart;
        LocalDateTime end = patBedEnd.isAfter(queryEndDay) ? queryEndDay : patBedEnd;
        while (start.isBefore(end)) {
            LocalDateTime segEnd = TimeUtils.truncateToMonthStart(start.plusMonths(1));
            segEnd = segEnd.isAfter(end) ? end : segEnd;  // 确保不超过结束时间
            bedUsageList.add(new QcICUBedUsage(
                displayBedNum, pid, patientIcuName, start, segEnd,
                TimeUtils.truncateToMonthStart(start), 0f, ""
            ));
            start = segEnd;
        }
        return bedUsageList;
    }

    // 计算bedUsageList中的bedDays，转化成PB
    private List<QcICUBedUsagePB> toProtoList(
        List<QcICUBedUsage> bedUsageList, String zoneId
    ) {
        Map<String/*displayBedNumber*/, List<QcICUBedUsage>> groupedByBed = bedUsageList.stream()
            .collect(Collectors.groupingBy(QcICUBedUsage::getDisplayBedNumber));

        // 统计床位使用情况，如果同一天这个床位被x个人用了，则每次使用占1/x床日
        List<QcICUBedUsagePB> usagePBList = new ArrayList<>();
        for (List<QcICUBedUsage> usageList : groupedByBed.values()) {
            usageList.sort(Comparator.comparing(QcICUBedUsage::getStartLocal));
            int sharedIdx = 0;
            int curIdx = 0;

            while (curIdx < usageList.size()) {
                QcICUBedUsage curUsage = usageList.get(curIdx);
                curUsage.bedDays = (float) ChronoUnit.DAYS.between(
                    curUsage.startLocal, curUsage.endLocal.minusMinutes(1)
                ) + 1f;  // 包含结束日

                // 1. sharedIdx == curIdx: curIdx ++;
                // 2. sharedIdx < curIdx:
                // 2.1 usageList[sharedIdx].endLocal 与 usageList[curIdx].startLocal 在同一天
                //     2.1.1 如果curIdx == usageList.size() - 1:
                //         usageList[sharedIdx .. curIdx].bedDays -= 1
                //         usageList[sharedIdx .. curIdx].bedDays += 1f / (curIdx - sharedIdx + 1);
                //         break;
                //     2.1.2 如果curIdx < usageList.size() - 1:
                //         curIdx ++;
                // 2.2 usageList[sharedIdx].endLocal 的日期在 usageList[curIdx].startLocal 之前
                //     usageList[sharedIdx .. curIdx - 1].bedDays -= 1
                //     usageList[sharedIdx .. curIdx - 1].bedDays += 1f / (curIdx - sharedIdx);
                //     sharedIdx = curIdx;
                //     curIdx ++;
                // 2.3 usageList[sharedIdx].endLocal 的日期在 usageList[curIdx].startLocal 之后
                //     记录异常
                //     sharedIdx = curIdx;
                //     curIdx ++;
                if (sharedIdx == curIdx) {
                    curIdx++;
                } else if (sharedIdx < curIdx) {
                    QcICUBedUsage sharedUsage = usageList.get(sharedIdx);
                    if (TimeUtils.isSameDay(sharedUsage.endLocal, curUsage.startLocal)) {
                        // 同一天
                        if (curIdx == usageList.size() - 1) {
                            // 最后一个
                            applySharedDayAdjustment(usageList, sharedIdx, curIdx);
                            break;
                        } else {
                            curIdx++;
                        }
                    } else if (sharedUsage.endLocal.isBefore(curUsage.startLocal)) {
                        // 在前面
                        applySharedDayAdjustment(usageList, sharedIdx, curIdx);
                        sharedIdx = curIdx;
                        curIdx++;
                    } else {
                        // 在后面
                        curUsage.exception = "和前面时间有重叠";
                        sharedIdx = curIdx;
                        curIdx++;
                    }
                }
            }
            // 将处理后的数据转换为PB格式
            for (QcICUBedUsage usage : usageList) {
                usagePBList.add(QcICUBedUsagePB.newBuilder()
                    .setDisplayBedNumber(usage.getDisplayBedNumber())
                    .setPatientIcuName(usage.getPatientIcuName())
                    .setStartDateIso8601(TimeUtils.toIso8601String(
                        TimeUtils.getUtcFromLocalDateTime(usage.getStartLocal(), zoneId), zoneId
                    ))
                    .setEndDateIso8601(TimeUtils.toIso8601String(
                        TimeUtils.getUtcFromLocalDateTime(usage.getEndLocal(), zoneId), zoneId
                    ))
                    .setBedDays(usage.getBedDays())
                    .setException(usage.getException())
                    .build()
                );
            }
        }
        usagePBList.sort(Comparator
            .comparing(QcICUBedUsagePB::getDisplayBedNumber)
            .thenComparing(QcICUBedUsagePB::getStartDateIso8601)
        );
        return usagePBList;
    }

    private void applySharedDayAdjustment(List<QcICUBedUsage> list, int startIdx, int endIdx) {
        for (int i = startIdx; i <= endIdx; i++) {
            list.get(i).bedDays -= 1f;
            list.get(i).bedDays += 1f / (endIdx - startIdx + 1);
        }
    }

    private final List<String> statusMsgList;

    private final BedConfigRepository bedConfigRepo;
    private final PatientDeviceService patientDeviceService;
    private final PatientBedHistoryRepository patientBedHistoryRepo;
    private final BedCountRepository bedCountRepo;
}