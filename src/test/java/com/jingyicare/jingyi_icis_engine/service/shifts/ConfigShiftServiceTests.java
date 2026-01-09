package com.jingyicare.jingyi_icis_engine.service.shifts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class ConfigShiftServiceTests extends TestsBase {
    public ConfigShiftServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftService shiftService,
        @Autowired ConfigShiftUtils shiftUtils
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.accountId = "admin";
        this.deptId = "10016";
        this.shiftSettings = protoService.getConfig().getShift().getDefaultSetting();
        this.shiftService = shiftService;
        this.shiftUtils = shiftUtils;
    }

    // 班次接口
    @Test
    public void testShiftSettings() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // // 获取班次
        // GetDeptShiftReq getReq = GetDeptShiftReq.newBuilder().setDeptId(deptId).build();
        // String getReqStr = ProtoUtils.protoToJson(getReq);
        // GetDeptShiftResp getResp = shiftService.getDeptShift(getReqStr);
        // assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        // assertThat(getResp.getShift().getShift(0).getDefaultShift().getStartHour()).isEqualTo(8);

        // // 更新班次
        // ShiftSettingsPB origShift = getResp.getShift();
        // ShiftSettingsPB shiftSettingsPb = getResp.getShift().newBuilder()
        //     .setFunctionId(origShift.getFunctionId())
        //     .setFunctionName(origShift.getFunctionName())
        //     .addShift(
        //         ShiftSettingsPB.Shift.newBuilder()
        //             .setName("早班")
        //             .setDefaultShift(
        //                 ShiftSettingsPB.TimeRange.newBuilder()
        //                     .setStartHour(0)
        //                     .setHours(12)
        //                     .build()
        //             )
        //             .build()
        //     )
        //     .addShift(
        //         ShiftSettingsPB.Shift.newBuilder()
        //             .setName("晚班")
        //             .setDefaultShift(
        //                 ShiftSettingsPB.TimeRange.newBuilder()
        //                     .setStartHour(12)
        //                     .setHours(12)
        //                     .build()
        //             )
        //             .build()
        //     )
        //     .build();
        // UpdateDeptShiftReq updateReq = UpdateDeptShiftReq.newBuilder()
        //     .setDeptId(deptId)
        //     .setShift(shiftSettingsPb)
        //     .build();
        // String updateReqStr = ProtoUtils.protoToJson(updateReq);
        // GenericResp updateResp = shiftService.updateDeptShift(updateReqStr);
        // assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // getResp = shiftService.getDeptShift(getReqStr);
        // assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        // assertThat(getResp.getShift().getShift(0).getDefaultShift().getStartHour()).isEqualTo(0);
    }

    // 工具函数
    @Test
    public void testGetShiftTimes() {
        assertThat(shiftUtils.getShiftStartTime(shiftSettings, TimeUtils.getLocalTime(2024, 12, 12, 0, 0), ZONE_ID))
            .isEqualTo(TimeUtils.getLocalTime(2024, 12, 12, 8, 0));

        assertThat(shiftUtils.getShiftStartTime(shiftSettings, TimeUtils.getLocalTime(2024, 12, 11, 23, 59), ZONE_ID))
            .isEqualTo(TimeUtils.getLocalTime(2024, 12, 11, 8, 0));

        List<LocalDateTime> shiftTimes = shiftUtils.getShiftStartTimes(
            shiftSettings,
            TimeUtils.getLocalTime(2024, 12, 12, 5, 0),  // shanghai 2024-12-12 13:00
            TimeUtils.getLocalTime(2024, 12, 15, 10, 0), // shanghai 2024-12-15 18:00
            ZONE_ID
        );
        assertThat(shiftTimes.size()).isEqualTo(4);
        assertThat(shiftTimes.get(0)).isEqualTo(TimeUtils.getLocalTime(2024, 12, 12, 8, 0));
        assertThat(shiftTimes.get(1)).isEqualTo(TimeUtils.getLocalTime(2024, 12, 13, 8, 0));
        assertThat(shiftTimes.get(2)).isEqualTo(TimeUtils.getLocalTime(2024, 12, 14, 8, 0));
        assertThat(shiftTimes.get(3)).isEqualTo(TimeUtils.getLocalTime(2024, 12, 15, 8, 0));
    }

    final private String ZONE_ID;
    final private String accountId;
    final private String deptId;
    final private ShiftSettingsPB shiftSettings;
    final private ConfigShiftService shiftService;
    final private ConfigShiftUtils shiftUtils;
}