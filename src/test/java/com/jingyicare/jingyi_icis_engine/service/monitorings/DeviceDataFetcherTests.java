package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDateTime;
import java.util.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.grpc.*;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisDevice.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class DeviceDataFetcherTests extends TestsBase {
    public DeviceDataFetcherTests(
        @Autowired ConfigProtoService protoService,
        @Autowired DeviceDataFetcher fetcher,
        @Autowired BedConfigRepository bedConfigRepo,
        @Autowired DeviceInfoRepository devInfoRepo,
        @Autowired PatientBedHistoryRepository bedHisRepo,
        @Autowired PatientDeviceRepository patientDevRepo,
        @Autowired PatientRecordRepository patientRepo,
        @Autowired PatientMonitoringRecordRepository monRecRepo,
        @Autowired DeviceDataRepository devDataRepo,
        @Autowired DeviceDataHourlyRepository devDataHourlyRepo,
        @Autowired DeviceDataHourlyApproxRepository devDataHourlyApproxRepo
    ) {
        this.deptId = "10040";
        DeviceEnums devEnums = protoService.getConfig().getDevice().getEnums();
        this.SWITCH_TYPE_NORMAL = devEnums.getSwitchTypeNormal().getId();
        this.SWITCH_TYPE_READMISSION_DISCHARGE = devEnums.getSwitchTypeReadmissionDischarge().getId();
        this.SWITCH_TYPE_READMISSION_ADMIT = devEnums.getSwitchTypeReadmissionAdmit().getId();

        this.fetcher = fetcher;
        this.bedConfigRepo = bedConfigRepo;
        this.devInfoRepo = devInfoRepo;
        this.bedHisRepo = bedHisRepo;
        this.patientDevRepo = patientDevRepo;
        this.patientRepo = patientRepo;
        this.monRecRepo = monRecRepo;
        this.devDataRepo = devDataRepo;
        this.devDataHourlyRepo = devDataHourlyRepo;
        this.devDataHourlyApproxRepo = devDataHourlyApproxRepo;
    }

    @Test
    void testGetFetchContext() {
        PatientRecord patient = PatientTestUtils.newPatientRecord(2201L, 3, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 6, 1, 0));  // shanghai: 2025-8-6 9:00
        patient.setDischargeTime(TimeUtils.getLocalTime(2025, 8, 8, 7, 0));  // shanghai: 2025-8-8 15:00
        patient = patientRepo.save(patient);

        List<PatientMonitoringTimePointPB> customTimePoints = new ArrayList<>();
        customTimePoints.add(PatientMonitoringTimePointPB.newBuilder()
            .setTimePointIso8601("2025-08-05T10:01:00+08:00").build());  // 被admissionTime过滤
        customTimePoints.add(PatientMonitoringTimePointPB.newBuilder()
            .setTimePointIso8601("2025-08-06T09:02:00+08:00").build());  // 头算
        customTimePoints.add(PatientMonitoringTimePointPB.newBuilder()
            .setTimePointIso8601("2025-08-06T12:03:00+08:00").build());  // 中间算
        customTimePoints.add(PatientMonitoringTimePointPB.newBuilder()
            .setTimePointIso8601("2025-08-08T15:04:00+08:00").build());  // 被dischargeTime过滤
        customTimePoints.add(PatientMonitoringTimePointPB.newBuilder()
            .setTimePointIso8601("2025-08-09T15:05:00+08:00").build());  // 被dischargeTime过滤

        List<MonitoringGroupBetaPB> paramGroups = new ArrayList<>();
        MonitoringGroupBetaPB group = MonitoringGroupBetaPB.newBuilder()
            .addParam(MonitoringParamPB.newBuilder().setCode("code1")
                .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING))
            )
            .addParam(MonitoringParamPB.newBuilder().setCode("code2")
                .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING))
            )
            .build();
        paramGroups.add(group);

        DeviceDataFetcher.FetchContext context = fetcher.getFetchContext(
            patient.getId(),
            TimeUtils.getLocalTime(2025, 8, 5, 0, 0),
            TimeUtils.getLocalTime(2025, 8, 9, 0, 0),
            customTimePoints,
            paramGroups,
            "admin");

        assertThat(context).isNotNull();
        assertThat(context.pid).isEqualTo(patient.getId());
        assertThat(context.deptId).isEqualTo(deptId);
        assertThat(context.from).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 1, 0));
        assertThat(context.to).isEqualTo(TimeUtils.getLocalTime(2025, 8, 8, 7, 0));
        assertThat(context.customTimes).hasSize(2);
        assertThat(context.customTimes.get(0)).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 1, 2));
        assertThat(context.customTimes.get(1)).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 4, 3));
        List<LocalDateTime> allTimeList = new ArrayList<>();
        for (LocalDateTime start = TimeUtils.getLocalTime(2025, 8, 6, 1, 0);
            start.isBefore(TimeUtils.getLocalTime(2025, 8, 8, 7, 0));
            start = start.plusHours(1)
        ) {
            allTimeList.add(start);
            if (start.equals(TimeUtils.getLocalTime(2025, 8, 6, 1, 0))) {
                allTimeList.add(TimeUtils.getLocalTime(2025, 8, 6, 1, 2));
            }
            if (start.equals(TimeUtils.getLocalTime(2025, 8, 6, 4, 0))) {
                allTimeList.add(TimeUtils.getLocalTime(2025, 8, 6, 4, 3));
            }
        }
        assertThat(allTimeList).containsExactlyInAnyOrderElementsOf(context.allTimes);
    }

    @Test
    void testGetDeviceDataQueries() {
        /*
        ctx.from = 2025-08-06T01:00
        ctx.to   = 2025-08-06T10:00
        APPROX_SECONDS = 30
        approxFrom = 2025-08-06T00:59:30
        approxTo   = 2025-08-06T10:00:30

        床位 hisBedNumber2202：
            开始时间 2025-08-06T00:30（早于 from，要被裁剪到 from）
            结束时间 2025-08-06T03:00（正常）

        床位 hisBedNumber2203
            开始时间 2025-08-06T03:00（正好等于上一段结束）
            结束时间 null（表示持续到 endTime，要被裁剪到 ctx.to）

        Device X：
            bindingTime = 2025-08-06T00:30（早于 from，要裁到 from）
            unbindingTime = 2025-08-06T05:00（正常）
        Device Y：
            bindingTime = 2025-08-06T06:00（正常）
            unbindingTime = null（表示持续到 to，要裁到 ctx.to）
        */
        // 配置病人（以及当前床位2203）
        Long bedAPid = 2202L;
        Long bedBPid = 2203L;
        PatientRecord patient = PatientTestUtils.newPatientRecord(bedAPid, 3, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 6, 0, 30));  // shanghai: 2025-8-6 9:00
        patient.setHisBedNumber("hisBedNumber" + bedBPid);
        patient = patientRepo.save(patient);

        // 配置床位 hisBedNumber2202, hisBedNumber2203
        BedConfig bedConfig = BedConfig.builder()
            .departmentId(deptId)
            .hisBedNumber("hisBedNumber" + bedAPid)
            .deviceBedNumber("deviceBedNumber" + bedAPid)
            .displayBedNumber("displayBedNumber" + bedAPid)
            .bedType(1/*固定床位*/)
            .isDeleted(false)
            .build();
        bedConfigRepo.save(bedConfig);
        bedConfig.setId(null);
        bedConfig.setHisBedNumber("hisBedNumber" + bedBPid);
        bedConfig.setDeviceBedNumber("deviceBedNumber" + bedBPid);
        bedConfig.setDisplayBedNumber("displayBedNumber" + bedBPid);
        bedConfigRepo.save(bedConfig);

        // 配置病人床位历史
        PatientBedHistory bedA = PatientBedHistory.builder()
            .patientId(patient.getId())
            .hisBedNumber("hisBedNumber" + bedAPid)
            .deviceBedNumber("deviceBedNumber" + bedAPid)
            .displayBedNumber("displayBedNumber" + bedAPid)
            .switchTime(TimeUtils.getLocalTime(2025, 8, 6, 3, 0))
            .switchType(SWITCH_TYPE_NORMAL)
            .modifiedBy("admin")
            .modifiedAt(TimeUtils.getLocalTime(2025, 8, 6, 3, 0))
            .build();
        bedHisRepo.save(bedA);

        // 配置设备
        DeviceInfo devInfoA = DeviceInfo.builder()
            .departmentId(deptId)
            .deviceSn("deviceSn-A")
            .deviceType(2)  // 呼吸机
            .deviceName("Device A")
            .isDeleted(false)
            .build();
        devInfoA = devInfoRepo.save(devInfoA);
        final Integer devAId = devInfoA.getId();
        DeviceInfo devInfoB = DeviceInfo.builder()
            .departmentId(deptId)
            .deviceSn("deviceSn-B")
            .deviceType(2)  // 呼吸机
            .deviceName("Device B")
            .isDeleted(false)
            .build();
        devInfoB = devInfoRepo.save(devInfoB);
        final Integer devBId = devInfoB.getId();

        // 配置设备绑定历史
        PatientDevice bindingX = PatientDevice.builder()
            .patientId(patient.getId())
            .deviceId(devAId)
            .bindingTime(TimeUtils.getLocalTime(2025, 8, 6, 0, 30))
            .unbindingTime(TimeUtils.getLocalTime(2025, 8, 6, 5, 0))
            .isDeleted(false)
            .build();
        bindingX = patientDevRepo.save(bindingX);
        PatientDevice bindingY = PatientDevice.builder()
            .patientId(patient.getId())
            .deviceId(devBId)
            .bindingTime(TimeUtils.getLocalTime(2025, 8, 6, 6, 0))
            .unbindingTime(null)
            .isDeleted(false)
            .build();
        bindingY = patientDevRepo.save(bindingY);

        /*
        测试断言思路
        数量：期望返回的 queries 数量正确（床位段数 + 设备段数）
        内容：
            DevDataQuery.bedNum 仅床位有值，deviceId 为 null
            DevDataQuery.deviceId 仅设备有值，bedNum 为 null
            start / end 已被裁剪到 ctx.from / ctx.to 范围内
            approxStart / approxEnd 在裁剪前的基础上扩展 APPROX_SECONDS，但也限制在 approxFrom / approxTo 范围

        DevDataQuery{devBedNum='deviceBedNumber2202', devId=null, start=2025-08-06T01:00, end=2025-08-06T03:00, approxStart=2025-08-06T00:59:30, approxEnd=2025-08-06T03:00}
        DevDataQuery{devBedNum='deviceBedNumber2203', devId=null, start=2025-08-06T03:00, end=2025-08-06T10:00, approxStart=2025-08-06T03:00, approxEnd=2025-08-06T10:00:30}
        DevDataQuery{devBedNum='null', devId=1, start=2025-08-06T01:00, end=2025-08-06T05:00, approxStart=2025-08-06T00:59:30, approxEnd=2025-08-06T05:00}
        DevDataQuery{devBedNum='null', devId=2, start=2025-08-06T06:00, end=2025-08-06T10:00, approxStart=2025-08-06T06:00, approxEnd=2025-08-06T10:00:30}
        */
        DeviceDataFetcher.FetchContext context = new DeviceDataFetcher.FetchContext(
            patient.getId(), TimeUtils.getLocalTime(2025, 8, 6, 1, 0),
            TimeUtils.getLocalTime(2025, 8, 6, 10, 0), "admin"
        );
        context.patientRecord = patient;
        fetcher.resetApproxSecondsForTesting(30);
        List<DeviceDataFetcher.DevDataQuery> queries = fetcher.getDeviceDataQueries(context);
        assertThat(queries).hasSize(4);

        // 床位查询 deviceBedNumber2202
        DeviceDataFetcher.DevDataQuery bedQuery1 = queries.stream()
            .filter(q -> "deviceBedNumber2202".equals(q.devBedNum))
            .findFirst().orElse(null);
        assertThat(bedQuery1).isNotNull();
        assertThat(bedQuery1.devId).isNull();
        assertThat(bedQuery1.start).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 1, 0));
        assertThat(bedQuery1.end).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 3, 0));
        assertThat(bedQuery1.approxStart).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 0, 59).withSecond(30));
        assertThat(bedQuery1.approxEnd).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 3, 0));

        // 床位查询 deviceBedNumber2203
        DeviceDataFetcher.DevDataQuery bedQuery2 = queries.stream()
            .filter(q -> "deviceBedNumber2203".equals(q.devBedNum))
            .findFirst().orElse(null);
        assertThat(bedQuery2).isNotNull();
        assertThat(bedQuery2.devId).isNull();
        assertThat(bedQuery2.start).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 3, 0));
        assertThat(bedQuery2.end).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 10, 0));
        assertThat(bedQuery2.approxStart).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 3, 0));
        assertThat(bedQuery2.approxEnd).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 10, 0).withSecond(30));

        // 设备查询 Device A (ID=1)
        DeviceDataFetcher.DevDataQuery deviceQuery1 = queries.stream()
            .filter(q -> devAId.equals(q.devId))
            .findFirst().orElse(null);
        assertThat(deviceQuery1).isNotNull();
        assertThat(deviceQuery1.devBedNum).isNull();
        assertThat(deviceQuery1.start).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 1, 0));
        assertThat(deviceQuery1.end).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 5, 0));
        assertThat(deviceQuery1.approxStart).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 0, 59).withSecond(30));
        assertThat(deviceQuery1.approxEnd).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 5, 0));

        // 设备查询 Device B (ID=2)
        DeviceDataFetcher.DevDataQuery deviceQuery2 = queries.stream()
            .filter(q -> devBId.equals(q.devId))
            .findFirst().orElse(null);
        assertThat(deviceQuery2).isNotNull();
        assertThat(deviceQuery2.devBedNum).isNull();
        assertThat(deviceQuery2.start).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 6, 0));
        assertThat(deviceQuery2.end).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 10, 0));
        assertThat(deviceQuery2.approxStart).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 6, 0));
        assertThat(deviceQuery2.approxEnd).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 10, 0).withSecond(30));
    }

    @Test
    void testGetMergedDeviceData() {
        // 1) 空输入
        DeviceDataFetcher.FetchContext ctx = new DeviceDataFetcher.FetchContext(
            2204L, TimeUtils.getLocalTime(2025, 8, 6, 0, 0),
            TimeUtils.getLocalTime(2025, 8, 7, 0, 0), "admin"
        );
        for (int i = 0; i < 24; ++i) {
            ctx.allTimes.add(TimeUtils.getLocalTime(2025, 8, 6, i, 0));
            if (i == 0) {
                ctx.allTimes.add(TimeUtils.getLocalTime(2025, 8, 6, 0, 5));
                ctx.allTimes.add(TimeUtils.getLocalTime(2025, 8, 6, 0, 10));
            }
        }
        ctx.paramMap.put("code1", MonitoringParamPB.newBuilder()
            .setCode("code1")
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING).build())
            .build());
        ctx.paramMap.put("code2", MonitoringParamPB.newBuilder()
            .setCode("code2")
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING).build())
            .build());
        ctx.paramMap.put("code3", MonitoringParamPB.newBuilder()
            .setCode("code3")
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING).build())
            .build());
        ctx.paramMap.put("approxCode1", MonitoringParamPB.newBuilder()
            .setCode("approxCode1")
            .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING).build())
            .build());
        fetcher.resetParamCodesToApproxForTesting(new HashSet<>(Arrays.asList("approxCode1")));
        List<DeviceData> deviceDataList = new ArrayList<>();
        Map<String, List<DeviceData>> result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).isEmpty();

        // 2) 非近似参数保持原样
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 10), "code1-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code1",
            TimeUtils.getLocalTime(2025, 8, 6, 2, 0), "code1-val2", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code2",
            TimeUtils.getLocalTime(2025, 8, 6, 3, 0), "code2-val1", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(2);
        List<DeviceData> code1Data = result.get("code1");
        List<DeviceData> code2Data = result.get("code2");
        assertThat(code1Data).hasSize(2);
        assertThat(code1Data.get(0).getRecordedStr()).isEqualTo("code1-val1");
        assertThat(code1Data.get(1).getRecordedStr()).isEqualTo("code1-val2");
        assertThat(code2Data).hasSize(1);
        assertThat(code2Data.get(0).getRecordedStr()).isEqualTo("code2-val1");

        // 3) 高优覆盖(Notes优先)：code1 和 code2 同时有值时，code1的值覆盖code2的值
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 10), "code1-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code1",
            TimeUtils.getLocalTime(2025, 8, 6, 2, 0), "code1-val2", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code2",
            TimeUtils.getLocalTime(2025, 8, 6, 2, 0), "code2-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code2",
            TimeUtils.getLocalTime(2025, 8, 6, 3, 0), "code2-val2", ""
        ));
        fetcher.resetAliasCodeMapForTesting(Collections.singletonMap("code1", Arrays.asList("code2")));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        fetcher.resetAliasCodeMapForTesting(Collections.emptyMap());
        assertThat(result).hasSize(2);
        code1Data = result.get("code1");
        code2Data = result.get("code2");
        assertThat(code1Data).hasSize(2);
        assertThat(code1Data.get(0).getRecordedStr()).isEqualTo("code1-val1");
        assertThat(code1Data.get(1).getRecordedStr()).isEqualTo("code1-val2");
        assertThat(code2Data).hasSize(2);
        // code1 有数据，code2 没数据时，code2不能被覆盖
        assertThat(code2Data.get(0).getRecordedStr()).isEqualTo("code1-val2");
        assertThat(code2Data.get(0).getNotes()).isEqualTo("code1");
        assertThat(code2Data.get(1).getRecordedStr()).isEqualTo("code2-val2");
        assertThat(StrUtils.isBlank(code2Data.get(1).getNotes())).isTrue();

        // 4) 近似：精确命中
        fetcher.resetApproxSecondsForTesting(30);
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 10), "approxCode1-val1", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(1);
        List<DeviceData> approxCode1Data = result.get("approxCode1");
        assertThat(approxCode1Data).hasSize(1);
        assertThat(approxCode1Data.get(0).getRecordedStr()).isEqualTo("approxCode1-val1");

        // 5) 近似：双侧包夹，取更近
        fetcher.resetApproxSecondsForTesting(30);
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 9).withSecond(50), "approxCode1-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 10).withSecond(5), "approxCode1-val2", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(1);
        approxCode1Data = result.get("approxCode1");
        assertThat(approxCode1Data).hasSize(1);
        assertThat(approxCode1Data.get(0).getRecordedStr()).isEqualTo("approxCode1-val2");

        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 9).withSecond(50), "approxCode1-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 10).withSecond(15), "approxCode1-val2", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(1);
        approxCode1Data = result.get("approxCode1");
        assertThat(approxCode1Data).hasSize(1);
        assertThat(approxCode1Data.get(0).getRecordedStr()).isEqualTo("approxCode1-val1");

        // 6) 近似：等距偏左
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 9).withSecond(50), "approxCode1-val3", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 10).withSecond(10), "approxCode1-val4", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(1);
        approxCode1Data = result.get("approxCode1");
        assertThat(approxCode1Data).hasSize(1);
        assertThat(approxCode1Data.get(0).getRecordedStr()).isEqualTo("approxCode1-val3");

        // 7) 近似：窗口外不生成
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 9).withSecond(20), "approxCode1-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 10).withSecond(35), "approxCode1-val2", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(1);
        approxCode1Data = result.get("approxCode1");
        assertThat(approxCode1Data).hasSize(0);

        // 8) 近似：j==last 分支（最后一条在右侧/左侧）
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 0, 9).withSecond(40), "approxCode1-val1", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(1);
        approxCode1Data = result.get("approxCode1");
        assertThat(approxCode1Data).hasSize(1);
        assertThat(approxCode1Data.get(0).getRecordedStr()).isEqualTo("approxCode1-val1");

        // 9) 近似：不“用过即弃”，且[j-1, j]包含了两个时刻
        ctx.allTimes = new ArrayList<>();
        ctx.allTimes.add(TimeUtils.getLocalTime(2025, 8, 6, 1, 10));
        ctx.allTimes.add(TimeUtils.getLocalTime(2025, 8, 6, 1, 20));
        fetcher.resetApproxSecondsForTesting(3600);
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 1, 0), "approxCode1-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 2, 0), "approxCode1-val2", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(1);
        approxCode1Data = result.get("approxCode1");
        assertThat(approxCode1Data).hasSize(2);
        assertThat(approxCode1Data.get(0).getRecordedStr()).isEqualTo("approxCode1-val1");
        assertThat(approxCode1Data.get(0).getRecordedAt()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 1, 10));
        assertThat(approxCode1Data.get(1).getRecordedStr()).isEqualTo("approxCode1-val1");
        assertThat(approxCode1Data.get(1).getRecordedAt()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 1, 20));

        // 10) 多别名：code1 -> [code2, code3]
        ctx.allTimes = new ArrayList<>();
        ctx.allTimes.add(TimeUtils.getLocalTime(2025, 8, 6, 1, 0));
        fetcher.resetAliasCodeMapForTesting(Collections.singletonMap("code1", Arrays.asList("code2", "code3")));
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code1",
            TimeUtils.getLocalTime(2025, 8, 6, 1, 0), "code1-val1", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(3);
        code1Data = result.get("code1");
        assertThat(code1Data).hasSize(1);
        assertThat(code1Data.get(0).getRecordedStr()).isEqualTo("code1-val1");
        code2Data = result.get("code2");
        assertThat(code2Data).hasSize(0);
        List<DeviceData> code3Data = result.get("code3");
        assertThat(code3Data).hasSize(0);

        // 11) 非近似参数 + 近似参数并存
        fetcher.resetAliasCodeMapForTesting(new HashMap<>());
        fetcher.resetApproxSecondsForTesting(1800);
        ctx.allTimes = new ArrayList<>();
        ctx.allTimes.add(TimeUtils.getLocalTime(2025, 8, 6, 1, 0));
        ctx.allTimes.add(TimeUtils.getLocalTime(2025, 8, 6, 2, 0));
        deviceDataList = new ArrayList<>();
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code1",
            TimeUtils.getLocalTime(2025, 8, 6, 1, 0), "code1-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "code1",
            TimeUtils.getLocalTime(2025, 8, 6, 2, 0), "code1-val2", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 1, 50), "approxCode1-val1", ""
        ));
        deviceDataList.add(new DeviceData(
            null, deptId, null, 1, "hisBedNumber2204", "approxCode1",
            TimeUtils.getLocalTime(2025, 8, 6, 2, 5), "approxCode1-val2", ""
        ));
        result = fetcher.getMergedDeviceData(ctx, deviceDataList);
        assertThat(result).hasSize(2);
        code1Data = result.get("code1");
        assertThat(code1Data).hasSize(2);
        assertThat(code1Data.get(0).getRecordedStr()).isEqualTo("code1-val1");
        assertThat(code1Data.get(0).getRecordedAt()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 1, 0));
        assertThat(code1Data.get(1).getRecordedStr()).isEqualTo("code1-val2");
        assertThat(code1Data.get(1).getRecordedAt()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 2, 0));
        approxCode1Data = result.get("approxCode1");
        assertThat(approxCode1Data).hasSize(1);
        assertThat(approxCode1Data.get(0).getRecordedStr()).isEqualTo("approxCode1-val2");
        assertThat(approxCode1Data.get(0).getRecordedAt()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 2, 0));

        // 12) 边界：[from, to) + 右开窗口
        PatientRecord patient = PatientTestUtils.newPatientRecord(2205L, 1, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 5, 1, 0));  // shanghai: 2025-8-5 9:00
        patient = patientRepo.save(patient);
        ctx = fetcher.getFetchContext(
            patient.getId(), TimeUtils.getLocalTime(2025, 8, 6, 0, 0),
            TimeUtils.getLocalTime(2025, 8, 7, 0, 0), new ArrayList<>(),
            new ArrayList<>(), "admin"
        );
        assertThat(ctx.allTimes).hasSize(24);
        assertThat(ctx.allTimes.get(23)).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 23, 0));
    }

    @Test
    void testFetch() {
        // 综合测试
        /*
        测试场景：fetcher.resetApproxSecondsForTesting(1800);
        病人：2025-08-06 00:30入科；未出科
        床位：hisBedNumber2206，2025-08-06 00:30入科
        设备：deviceSn-A，2025-08-06 02:00绑定，2025-08-06 05:00解绑
        查询时间范围：
            from: 2025-08-06 01:00
            to: 2025-08-06 04:00
            customTimePoints: [2025-08-06 03:55]
        观察参数
            code1: string  // 由床位产出
            approxCode1: string  // 由设备产出
        设备数据：
            device_data:
                (hisBedNumber2206, code1, "2025-08-06 01:00", "dd-code1-val1")  // 不会被取到
                (hisBedNumber2206, code1, "2025-08-06 03:55", "dd-code1-val2")
            device_data_hourly:
                (hisBedNumber2206, code1, "2025-08-06 00:00", "ddh-code1-val0")  // 被查询开始时间过滤
                (hisBedNumber2206, code1, "2025-08-06 01:00", "ddh-code1-val1")  // 被观察项数据过滤
                (hisBedNumber2206, code1, "2025-08-06 02:00", "ddh-code1-val2")
                (hisBedNumber2206, code1, "2025-08-06 03:00", "ddh-code1-val3")
                (hisBedNumber2206, code1, "2025-08-06 04:00", "ddh-code1-val4")  // 被查询结束时间过滤
            device_data_hourly_approx:
                (deviceSn-A.id, approxCode1, "2025-08-06 01:55", "ddha-approxCode1-val1")  // 未绑定，被过滤
                (deviceSn-A.id, approxCode1, "2025-08-06 02:55", "ddha-approxCode1-val2")
                (deviceSn-A.id, approxCode1, "2025-08-06 03:10", "ddha-approxCode1-val3")
                (deviceSn-A.id, approxCode1, "2025-08-06 04:10", "ddha-approxCode1-val4")
        观察项数据:
            (hisBedNumber2206, code1, "2025-08-06 01:00", "dd-code1-val1", isDeleted = true)
        */
        fetcher.resetApproxSecondsForTesting(1800);
        fetcher.resetParamCodesToApproxForTesting(new HashSet<>(Arrays.asList("approxCode1")));
        Long hisPid = 2206L;
        PatientRecord patient = PatientTestUtils.newPatientRecord(hisPid, 3, deptId);
        patient.setAdmissionTime(TimeUtils.getLocalTime(2025, 8, 6, 0, 30));  // shanghai: 2025-8-6 9:00
        patient = patientRepo.save(patient);

        // 配置床位 hisBedNumber2206
        BedConfig bedConfig = BedConfig.builder()
            .departmentId(deptId)
            .hisBedNumber("hisBedNumber" + hisPid)
            .deviceBedNumber("deviceBedNumber" + hisPid)
            .displayBedNumber("displayBedNumber" + hisPid)
            .bedType(1/*固定床位*/)
            .isDeleted(false)
            .build();
        bedConfigRepo.save(bedConfig);

        // 配置设备
        DeviceInfo devInfoA = DeviceInfo.builder()
            .departmentId(deptId)
            .deviceSn("deviceSn-A")
            .deviceType(2)  // 呼吸机
            .deviceName("Device A")
            .isDeleted(false)
            .build();
        devInfoA = devInfoRepo.save(devInfoA);
        final Integer devAId = devInfoA.getId();

        // 配置设备绑定历史
        PatientDevice bindingX = PatientDevice.builder()
            .patientId(patient.getId())
            .deviceId(devAId)
            .bindingTime(TimeUtils.getLocalTime(2025, 8, 6, 2, 0))
            .unbindingTime(TimeUtils.getLocalTime(2025, 8, 6, 5, 0))
            .isDeleted(false)
            .build();
        bindingX = patientDevRepo.save(bindingX);

        // 查询时间范围
        LocalDateTime from = TimeUtils.getLocalTime(2025, 8, 6, 1, 0);
        LocalDateTime to = TimeUtils.getLocalTime(2025, 8, 6, 4, 0);
        List<PatientMonitoringTimePointPB> customTimePoints = new ArrayList<>();
        customTimePoints.add(PatientMonitoringTimePointPB.newBuilder()
            .setTimePointIso8601("2025-08-06T11:55:00+08:00").build());

        // 观察参数
        List<MonitoringGroupBetaPB> paramGroups = new ArrayList<>();
        MonitoringGroupBetaPB group = MonitoringGroupBetaPB.newBuilder()
            .addParam(MonitoringParamPB.newBuilder().setCode("code1")
                .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING))
            )
            .addParam(MonitoringParamPB.newBuilder().setCode("approxCode1")
                .setValueMeta(ValueMetaPB.newBuilder().setValueType(TypeEnumPB.STRING))
            )
            .build();
        paramGroups.add(group);

        // 准备设备数据
        List<DeviceData> deviceDataList = new ArrayList<>();
        deviceDataList.add(DeviceData.builder()
            .departmentId(deptId)
            .deviceId(null)
            .deviceType(1)
            .deviceBedNumber("deviceBedNumber" + hisPid)
            .paramCode("code1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 1, 0))
            .recordedStr("dd-code1-val1")
            .build());
        deviceDataList.add(DeviceData.builder()
            .departmentId(deptId)
            .deviceId(null)
            .deviceType(1)
            .deviceBedNumber("deviceBedNumber" + hisPid)
            .paramCode("code1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 3, 55))
            .recordedStr("dd-code1-val2")
            .build());
        devDataRepo.saveAll(deviceDataList);

        List<DeviceDataHourly> deviceDataHourlyList = new ArrayList<>();
        deviceDataHourlyList.add(DeviceDataHourly.builder()
            .departmentId(deptId)
            .deviceId(null)
            .deviceType(1)
            .deviceBedNumber("deviceBedNumber" + hisPid)
            .paramCode("code1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 0, 0))
            .recordedStr("ddh-code1-val0")
            .build());
        deviceDataHourlyList.add(DeviceDataHourly.builder()
            .departmentId(deptId)
            .deviceId(null)
            .deviceType(1)
            .deviceBedNumber("deviceBedNumber" + hisPid)
            .paramCode("code1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 1, 0))
            .recordedStr("ddh-code1-val1")
            .build());
        deviceDataHourlyList.add(DeviceDataHourly.builder()
            .departmentId(deptId)
            .deviceId(null)
            .deviceType(1)
            .deviceBedNumber("deviceBedNumber" + hisPid)
            .paramCode("code1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 2, 0))
            .recordedStr("ddh-code1-val2")
            .build());
        deviceDataHourlyList.add(DeviceDataHourly.builder()
            .departmentId(deptId)
            .deviceId(null)
            .deviceType(1)
            .deviceBedNumber("deviceBedNumber" + hisPid)
            .paramCode("code1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 3, 0))
            .recordedStr("ddh-code1-val3")
            .build());
        deviceDataHourlyList.add(DeviceDataHourly.builder()
            .departmentId(deptId)
            .deviceId(null)
            .deviceType(1)
            .deviceBedNumber("deviceBedNumber" + hisPid)
            .paramCode("code1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 4, 0))
            .recordedStr("ddh-code1-val4")
            .build());
        devDataHourlyRepo.saveAll(deviceDataHourlyList);

        List<DeviceDataHourlyApprox> deviceDataHourlyApproxList = new ArrayList<>();
        deviceDataHourlyApproxList.add(DeviceDataHourlyApprox.builder()
            .departmentId(deptId)
            .deviceId(devAId)
            .deviceType(2)
            .deviceBedNumber(null)
            .paramCode("approxCode1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 1, 55))
            .recordedStr("ddha-approxCode1-val1")
            .build());
        deviceDataHourlyApproxList.add(DeviceDataHourlyApprox.builder()
            .departmentId(deptId)
            .deviceId(devAId)
            .deviceType(2)
            .deviceBedNumber(null)
            .paramCode("approxCode1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 2, 55))
            .recordedStr("ddha-approxCode1-val2")
            .build());
        deviceDataHourlyApproxList.add(DeviceDataHourlyApprox.builder()
            .departmentId(deptId)
            .deviceId(devAId)
            .deviceType(2)
            .deviceBedNumber(null)
            .paramCode("approxCode1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 3, 10))
            .recordedStr("ddha-approxCode1-val3")
            .build());
        deviceDataHourlyApproxList.add(DeviceDataHourlyApprox.builder()
            .departmentId(deptId)
            .deviceId(devAId)
            .deviceType(2)
            .deviceBedNumber(null)
            .paramCode("approxCode1")
            .recordedAt(TimeUtils.getLocalTime(2025, 8, 6, 4, 10))
            .recordedStr("ddha-approxCode1-val4")
            .build());
        devDataHourlyApproxRepo.saveAll(deviceDataHourlyApproxList);

        // 观察项数据
        PatientMonitoringRecord monitoringRecord = PatientMonitoringRecord.builder()
            .pid(patient.getId())
            .deptId(deptId)
            .monitoringParamCode("code1")
            .effectiveTime(TimeUtils.getLocalTime(2025, 8, 6, 1, 0))
            .paramValue("value1")
            .paramValueStr("dd-code1-val1")
            .source("")
            .modifiedAt(TimeUtils.getLocalTime(2025, 8, 6, 1, 10))
            .isDeleted(true)
            .build();
        monRecRepo.save(monitoringRecord);

        Pair<StatusCode, List<PatientMonitoringRecord>> resultPair = fetcher.fetch(
            patient.getId(), from, to, customTimePoints, paramGroups, "admin"
        );
        List<PatientMonitoringRecord> recordsToAdd = resultPair.getSecond();
        assertThat(recordsToAdd).hasSize(5);
        assertThat(recordsToAdd.get(0).getEffectiveTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 2, 0));
        assertThat(recordsToAdd.get(0).getMonitoringParamCode()).isEqualTo("code1");
        assertThat(recordsToAdd.get(0).getParamValueStr()).isEqualTo("ddh-code1-val2");
        assertThat(recordsToAdd.get(1).getEffectiveTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 3, 0));
        assertThat(recordsToAdd.get(1).getMonitoringParamCode()).isEqualTo("code1");
        assertThat(recordsToAdd.get(1).getParamValueStr()).isEqualTo("ddh-code1-val3");
        assertThat(recordsToAdd.get(2).getEffectiveTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 3, 55));
        assertThat(recordsToAdd.get(2).getMonitoringParamCode()).isEqualTo("code1");
        assertThat(recordsToAdd.get(2).getParamValueStr()).isEqualTo("dd-code1-val2");
        assertThat(recordsToAdd.get(3).getEffectiveTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 3, 0));
        assertThat(recordsToAdd.get(3).getMonitoringParamCode()).isEqualTo("approxCode1");
        assertThat(recordsToAdd.get(3).getParamValueStr()).isEqualTo("ddha-approxCode1-val2");
        assertThat(recordsToAdd.get(4).getEffectiveTime()).isEqualTo(TimeUtils.getLocalTime(2025, 8, 6, 3, 55));
        assertThat(recordsToAdd.get(4).getMonitoringParamCode()).isEqualTo("approxCode1");
        assertThat(recordsToAdd.get(4).getParamValueStr()).isEqualTo("ddha-approxCode1-val4");
    }


    private final String deptId;

    private final Integer SWITCH_TYPE_NORMAL;
    private final Integer SWITCH_TYPE_READMISSION_DISCHARGE;
    private final Integer SWITCH_TYPE_READMISSION_ADMIT;

    private final DeviceDataFetcher fetcher;

    private final BedConfigRepository bedConfigRepo;
    private final DeviceInfoRepository devInfoRepo;
    private final PatientBedHistoryRepository bedHisRepo;
    private final PatientDeviceRepository patientDevRepo;
    private final PatientRecordRepository patientRepo;
    private final PatientMonitoringRecordRepository monRecRepo;
    private final DeviceDataRepository devDataRepo;
    private final DeviceDataHourlyRepository devDataHourlyRepo;
    private final DeviceDataHourlyApproxRepository devDataHourlyApproxRepo;
}