package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class MonitoringServiceTests extends TestsBase {
    public MonitoringServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired MonitoringService monitoringService,
        @Autowired MonitoringParamHistoryRepository monitoringParamHistoryRepo,
        @Autowired PatientMonitoringGroupRepository patientMonitoringGroupRepo,
        @Autowired PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepo,
        @Autowired RbacDepartmentRepository rbacDepartmentRepo,
        @Autowired PatientRecordRepository patientRecordRepo
    ) {
        this.accountId = "admin";
        this.deptId = "10008";
        this.deptId2 = "10009";
        this.deptId3 = "10010";
        this.deptId4 = "10011";
        this.patientId = 501L;
        this.monitoringPb = protoService.getConfig().getMonitoring();

        this.BALANCE_TYPE_IN = monitoringPb.getEnums().getBalanceIn().getId();
        this.GROUP_TYPE_BALANCE_ID = monitoringPb.getEnums().getGroupTypeBalance().getId();
        this.GROUP_TYPE_MONITORING_ID = monitoringPb.getEnums().getGroupTypeMonitoring().getId();

        this.protoService = protoService;
        this.monitoringConfig = monitoringConfig;
        this.monitoringService = monitoringService;
        this.monitoringParamHistoryRepo = monitoringParamHistoryRepo;
        this.patientMonitoringGroupRepo = patientMonitoringGroupRepo;
        this.patientMonitoringGroupParamRepo = patientMonitoringGroupParamRepo;
        this.rbacDepartmentRepo = rbacDepartmentRepo;
        this.patientRecordRepo = patientRecordRepo;
    }

    @Test
    public void testMonitoringParams() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // Q: 查询观察参数 (全局)
        String queryReqJson = ProtoUtils.protoToJson(GetMonitoringParamsReq.newBuilder().build());
        GetMonitoringParamsResp globalParamsResp = monitoringService.getMonitoringParams(queryReqJson);
        assertThat(globalParamsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // A: 新增观察参数 param10001 和 param10002
        AddMonitoringParamReq addParamReq1 = AddMonitoringParamReq.newBuilder()
            .setParam(
                MonitoringParamPB.newBuilder()
                    .setCode("test_param10001_in")
                    .setName("测试参数10001")
                    .setValueMeta(
                        ValueMetaPB.newBuilder()
                            .setValueType(TypeEnumPB.FLOAT)
                            .setDecimalPlaces(2)
                            .setUnit("ml(毫升)")
                            .build())
                    .setBalanceType(BALANCE_TYPE_IN)
                    .build()
            )
            .build();
        String addReqJson1 = ProtoUtils.protoToJson(addParamReq1);
        GenericResp addResp1 = monitoringService.addMonitoringParam(addReqJson1);
        assertThat(addResp1.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        AddMonitoringParamReq addParamReq2 = AddMonitoringParamReq.newBuilder()
                .setParam(
                        MonitoringParamPB.newBuilder()
                                .setCode("test_param10002_in")
                                .setName("测试参数10002")
                                .setValueMeta(
                                        ValueMetaPB.newBuilder()
                                                .setValueType(TypeEnumPB.FLOAT)
                                                .setDecimalPlaces(2)
                                                .setUnit("ml(毫升)")
                                                .build())
                                .setBalanceType(1)
                                .build()
                )
                .build();
        String addReqJson2 = ProtoUtils.protoToJson(addParamReq2);
        GenericResp addResp2 = monitoringService.addMonitoringParam(addReqJson2);
        assertThat(addResp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        addResp2 = monitoringService.addMonitoringParam(addReqJson2);
        assertThat(addResp2.getRt().getCode()).isEqualTo(StatusCode.MONITORING_PARAM_CODE_EXIST.ordinal());

        addParamReq2 = AddMonitoringParamReq.newBuilder()
                .setParam(
                        MonitoringParamPB.newBuilder()
                                .setCode("test_param10003_in")
                                .setName("测试参数10003")
                                .setValueMeta(
                                        ValueMetaPB.newBuilder()
                                                .setValueType(TypeEnumPB.BOOL)
                                                .setUnit("")
                                                .build())
                                .setBalanceType(1)
                                .build()
                )
                .build();
        addReqJson2 = ProtoUtils.protoToJson(addParamReq2);
        addResp2 = monitoringService.addMonitoringParam(addReqJson2);
        assertThat(addResp2.getRt().getCode()).isEqualTo(StatusCode.MONITORING_PARAM_BALANCE_TYPE_VALUE_TYPE_NOT_MATCH.ordinal());

        // U: 更新观察项参数
        AddMonitoringParamReq updateParamReq = AddMonitoringParamReq.newBuilder()
                .setParam(
                        MonitoringParamPB.newBuilder()
                                .setCode("test_param10002_in")
                                .setName("测试参数10002")
                                .setValueMeta(
                                        ValueMetaPB.newBuilder()
                                                .setValueType(TypeEnumPB.FLOAT)
                                                .setDecimalPlaces(3)
                                                .setUnit("ml(毫升)")
                                                .build())
                                .setBalanceType(1)
                                .build()
                )
                .build();
        String updateReqJson = ProtoUtils.protoToJson(updateParamReq);
        GenericResp updateResp = monitoringService.updateMonitoringParam(updateReqJson);
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        globalParamsResp = monitoringService.getMonitoringParams(queryReqJson);
        assertThat(globalParamsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        Integer decimalPlaces = null;
        for (MonitoringParamPB param : globalParamsResp.getParamsList()) {
            if (param.getCode().equals("test_param10002_in")) {
                decimalPlaces = param.getValueMeta().getDecimalPlaces();
                break;
            }
        }
        assertThat(decimalPlaces).isEqualTo(3);

        // D: 删除观察参数 param10002
        DeleteMonitoringParamReq deleteParamReq = DeleteMonitoringParamReq.newBuilder()
                .setCode("test_param10002_in")
                .build();
        String deleteReqJson = ProtoUtils.protoToJson(deleteParamReq);
        GenericResp deleteResp = monitoringService.deleteMonitoringParam(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        deleteParamReq = DeleteMonitoringParamReq.newBuilder()
                .setCode("")
                .build();
        deleteReqJson = ProtoUtils.protoToJson(deleteParamReq);
        deleteResp = monitoringService.deleteMonitoringParam(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.MONITORING_PARAM_CODE_EMPTY.ordinal());

        // U: 更新部门1的观察参数 dept-1 10001
        UpdateDeptMonitoringParamReq updateDeptParamReq = UpdateDeptMonitoringParamReq.newBuilder()
            .setDepartmentId(deptId)
            .setCode("test_param10001_in")
            .setValueMeta(
                ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setDecimalPlaces(1) // 修改小数位数
                    .setUnit("liters(升)") // 修改单位
                    .build())
            .build();
        String updateDeptReqJson = ProtoUtils.protoToJson(updateDeptParamReq);
        GenericResp updateDeptResp = monitoringService.updateDeptMonitoringParam(updateDeptReqJson);
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        updateDeptParamReq = UpdateDeptMonitoringParamReq.newBuilder()
            .setDepartmentId(deptId)
            .setCode("test_param10001_in")
            .setValueMeta(
                ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.BOOL)
                    .build())
            .build();
        updateDeptReqJson = ProtoUtils.protoToJson(updateDeptParamReq);
        updateDeptResp = monitoringService.updateDeptMonitoringParam(updateDeptReqJson);
        assertThat(updateDeptResp.getRt().getCode()).isEqualTo(StatusCode.MONITORING_PARAM_BALANCE_TYPE_VALUE_TYPE_NOT_MATCH.ordinal());

        // Q: 查询部门1观察参数
        String dept1QueryReqJson = ProtoUtils.protoToJson(GetMonitoringParamsReq.newBuilder()
                .setDepartmentId(deptId)
                .build());
        GetMonitoringParamsResp dept1ParamsResp = monitoringService.getMonitoringParams(dept1QueryReqJson);
        assertThat(dept1ParamsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // Q: 查询观察参数test_param10001_in
        GetMonitoringParamReq getParamReq = GetMonitoringParamReq.newBuilder()
            .setParamCode("test_param10001_in")
            .build();
        String getParamReqJson = ProtoUtils.protoToJson(getParamReq);
        GetMonitoringParamResp getParamResp = monitoringService.getMonitoringParam(getParamReqJson);
        assertThat(getParamResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getParamResp.getParam().getCode()).isEqualTo("test_param10001_in");
        assertThat(getParamResp.getDeptParamList()).hasSize(1);
        assertThat(getParamResp.getDeptParam(0).getDeptId()).isEqualTo(deptId);
        assertThat(getParamResp.getDeptParam(0).getParam().getValueMeta().getDecimalPlaces()).isEqualTo(1);

        // Q: 查询部门2观察参数
        String dept2QueryReqJson = ProtoUtils.protoToJson(GetMonitoringParamsReq.newBuilder()
                .setDepartmentId(deptId2)
                .build());
        GetMonitoringParamsResp dept2ParamsResp = monitoringService.getMonitoringParams(dept2QueryReqJson);
        assertThat(dept2ParamsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // D: 删除观察参数 param10001 失败（存在部门个性化的观察参数）
        deleteParamReq = DeleteMonitoringParamReq.newBuilder()
                .setCode("test_param10001_in")
                .build();
        deleteReqJson = ProtoUtils.protoToJson(deleteParamReq);
        deleteResp = monitoringService.deleteMonitoringParam(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.MONITORING_PARAM_HAS_DEPT_CONFIG.ordinal());

        // D: 删除个性化部门观察参数 dept-1 10001
        deleteParamReq = DeleteMonitoringParamReq.newBuilder()
                .setDepartmentId(deptId)
                .setCode("test_param10001_in")
                .build();
        deleteReqJson = ProtoUtils.protoToJson(deleteParamReq);
        deleteResp = monitoringService.deleteMonitoringParam(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // D: 删除全局观察参数 param10001
        deleteParamReq = DeleteMonitoringParamReq.newBuilder()
                .setCode("test_param10001_in")
                .build();
        deleteReqJson = ProtoUtils.protoToJson(deleteParamReq);
        deleteResp = monitoringService.deleteMonitoringParam(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // Q: 查询观察参数
        globalParamsResp = monitoringService.getMonitoringParams(queryReqJson);
        assertThat(globalParamsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        List<MonitoringParamHistory> paramHistories = monitoringParamHistoryRepo.findAll().stream()
            .filter(h -> h.getCode().equals("test_param10001_in") || h.getCode().equals("test_param10002_in"))
            .toList();
        assertThat(paramHistories).hasSize(7);  // 2 adds, 2 updates, 3 deletes
    }


    @Test
    public void testDeptBalanceGroup() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initDepartments();

        // Q: 查询部门 deptId2 的出入量分组
        String queryReqJson = ProtoUtils.protoToJson(GetDeptMonitoringGroupsReq.newBuilder()
            .setDepartmentId(deptId2)
            .setGroupType(GROUP_TYPE_BALANCE_ID)
            .build());
        GetDeptMonitoringGroupsResp queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).hasSize(3);

        // U: 更新部门deptId2的参数
        UpdateDeptMonitoringParamReq updateParamReq = UpdateDeptMonitoringParamReq.newBuilder()
            .setDepartmentId(deptId2)
            .setCode("test_param1_in")
            .setValueMeta(
                ValueMetaPB.newBuilder()
                    .setValueType(TypeEnumPB.FLOAT)
                    .setDecimalPlaces(5) // 修改小数位数
                    .setUnit("liters(升)") // 修改单位
                    .build())
            .build();
        String updateReqJson = ProtoUtils.protoToJson(updateParamReq);
        GenericResp updateResp = monitoringService.updateDeptMonitoringParam(updateReqJson);
        assertThat(updateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // Q: 查询部门 deptId2 的出入量分组 （确认DecimalPlaces已更改）
        queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).hasSize(3);
        assertThat(queryResp.getMonitoringGroup(0).getParam(0).getValueMeta().getDecimalPlaces()).isEqualTo(5);

        // A: 出入量分组属于预设分组，新增出入量分组失败
        AddDeptMonitoringGroupReq addReq = AddDeptMonitoringGroupReq.newBuilder()
            .setDepartmentId(deptId2)
            .setName("新增分组")
            .setGroupType(GROUP_TYPE_BALANCE_ID)
            .build();
        String addReqJson = ProtoUtils.protoToJson(addReq);
        AddDeptMonitoringGroupResp addResp = monitoringService.addDeptMonitoringGroup(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE.ordinal());

        // D: 出入量分组属于预设分组，删除出入量分组失败
        DeleteDeptMonitoringGroupReq deleteReq = DeleteDeptMonitoringGroupReq.newBuilder()
            .setId(queryResp.getMonitoringGroup(0).getId())
            .build();
        String deleteReqJson = ProtoUtils.protoToJson(deleteReq);
        GenericResp deleteResp = monitoringService.deleteDeptMonitoringGroup(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE.ordinal());

        // R: 出入量分组属于预设分组，调整次序失败
        ReorderDeptMonitoringGroupReq reorderReq = ReorderDeptMonitoringGroupReq.newBuilder()
            .setDepartmentId(deptId2)
            .addGroupOrder(
                MonitoringGroupOrder.newBuilder()
                .setMonitoringGroupId(queryResp.getMonitoringGroup(0).getId())
                .setDisplayOrder(10000)
                .build())
            .build();
        String reorderReqJson = ProtoUtils.protoToJson(reorderReq);
        GenericResp reorderResp = monitoringService.reorderDeptMonitoringGroup(reorderReqJson);
        assertThat(reorderResp.getRt().getCode()).isEqualTo(StatusCode.GROUP_TYPE_BALANCE_NOT_EDITABLE.ordinal());

        // A: 新增分组入量参数成功 (test_param2_in)
        AddDeptMonitoringGroupParamReq addParamReq = AddDeptMonitoringGroupParamReq.newBuilder()
            .setDeptId(deptId2)
            .setDeptMonitoringGroupId(queryResp.getMonitoringGroup(0).getId()) // 入量分组 ID
            .setCode("test_param2_in")
            .setDisplayOrder(5)
            .build();
        String addParamReqJson = ProtoUtils.protoToJson(addParamReq);
        AddDeptMonitoringGroupParamResp addParamResp = monitoringService.addDeptMonitoringGroupParam(addParamReqJson);
        assertThat(addParamResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // A: 新增分组入量参数成功 (test_param5_in)
        addParamReq = AddDeptMonitoringGroupParamReq.newBuilder()
            .setDeptId(deptId2)
            .setDeptMonitoringGroupId(queryResp.getMonitoringGroup(0).getId())
            .setCode("test_param5_in")
            .setDisplayOrder(6)
            .build();
        addParamReqJson = ProtoUtils.protoToJson(addParamReq);
        addParamResp = monitoringService.addDeptMonitoringGroupParam(addParamReqJson);
        assertThat(addParamResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // A: 新增分组入量参数 MONITORING_GROUP_PARAM_EXIST (test_param2_in)
        addParamReq = AddDeptMonitoringGroupParamReq.newBuilder()
            .setDeptId(deptId2)
            .setDeptMonitoringGroupId(queryResp.getMonitoringGroup(0).getId())
            .setCode("test_param2_in")
            .setDisplayOrder(4)
            .build();
        addParamReqJson = ProtoUtils.protoToJson(addParamReq);
        addParamResp = monitoringService.addDeptMonitoringGroupParam(addParamReqJson);
        assertThat(addParamResp.getRt().getCode()).isEqualTo(StatusCode.MONITORING_GROUP_PARAM_EXIST.ordinal());

        // A: 新增分组入量参数 MONITORING_PARAM_CODE_NOT_EXIST (test_param10004_in)
        addParamReq = AddDeptMonitoringGroupParamReq.newBuilder()
            .setDeptId(deptId2)
            .setDeptMonitoringGroupId(queryResp.getMonitoringGroup(0).getId())
            .setCode("test_param10004_in") // 不存在的参数
            .setDisplayOrder(5)
            .build();
        addParamReqJson = ProtoUtils.protoToJson(addParamReq);
        addParamResp = monitoringService.addDeptMonitoringGroupParam(addParamReqJson);
        assertThat(addParamResp.getRt().getCode()).isEqualTo(StatusCode.MONITORING_PARAM_CODE_NOT_EXIST.ordinal());

        // A: 新增入量分组出量参数 GROUP_PARAM_BALANCE_NOT_MATCH (test_param3_out)
        addParamReq = AddDeptMonitoringGroupParamReq.newBuilder()
            .setDeptId(deptId2)
            .setDeptMonitoringGroupId(queryResp.getMonitoringGroup(0).getId()) // 入量分组
            .setCode("test_param3_out") // 出量参数
            .setDisplayOrder(6)
            .build();
        addParamReqJson = ProtoUtils.protoToJson(addParamReq);
        addParamResp = monitoringService.addDeptMonitoringGroupParam(addParamReqJson);
        assertThat(addParamResp.getRt().getCode()).isEqualTo(StatusCode.GROUP_PARAM_BALANCE_NOT_MATCH.ordinal());

        // A: 新增出量分组入量参数 GROUP_PARAM_BALANCE_NOT_MATCH (test_param1_in)
        addParamReq = AddDeptMonitoringGroupParamReq.newBuilder()
            .setDeptId(deptId2)
            .setDeptMonitoringGroupId(queryResp.getMonitoringGroup(1).getId()) // 出量分组
            .setCode("test_param1_in") // 入量参数
            .setDisplayOrder(7)
            .build();
        addParamReqJson = ProtoUtils.protoToJson(addParamReq);
        addParamResp = monitoringService.addDeptMonitoringGroupParam(addParamReqJson);
        assertThat(addParamResp.getRt().getCode()).isEqualTo(StatusCode.GROUP_PARAM_BALANCE_NOT_MATCH.ordinal());

        // 确认入量分组参数位置
        queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).hasSize(3);
        assertThat(queryResp.getMonitoringGroup(0).getParamList()).hasSize(6);
        assertThat(queryResp.getMonitoringGroup(0).getParam(0).getCode()).isEqualTo("test_param1_in");
        assertThat(queryResp.getMonitoringGroup(0).getParam(0).getDisplayOrder()).isEqualTo(1);
        assertThat(queryResp.getMonitoringGroup(0).getParam(1).getCode()).isEqualTo("intravenous_intake");
        assertThat(queryResp.getMonitoringGroup(0).getParam(1).getDisplayOrder()).isEqualTo(2);
        assertThat(queryResp.getMonitoringGroup(0).getParam(2).getCode()).isEqualTo("gastric_intake");
        assertThat(queryResp.getMonitoringGroup(0).getParam(2).getDisplayOrder()).isEqualTo(3);
        assertThat(queryResp.getMonitoringGroup(0).getParam(3).getCode()).isEqualTo("transfusion_intake");
        assertThat(queryResp.getMonitoringGroup(0).getParam(3).getDisplayOrder()).isEqualTo(4);
        assertThat(queryResp.getMonitoringGroup(0).getParam(4).getCode()).isEqualTo("test_param2_in");
        assertThat(queryResp.getMonitoringGroup(0).getParam(4).getDisplayOrder()).isEqualTo(5);
        assertThat(queryResp.getMonitoringGroup(0).getParam(5).getCode()).isEqualTo("test_param5_in");
        assertThat(queryResp.getMonitoringGroup(0).getParam(5).getDisplayOrder()).isEqualTo(6);

        // U: 调整分组内参数位置
        ReorderDeptMonitoringGroupParamReq reorderParamReq = ReorderDeptMonitoringGroupParamReq.newBuilder()
            .setDeptId(deptId2)
            .addParamOrder(
                MonitoringGroupParamOrder.newBuilder()
                .setId(queryResp.getMonitoringGroup(0).getParam(2).getId())
                .setDisplayOrder(1)
                .build())
            .addParamOrder(
                MonitoringGroupParamOrder.newBuilder()
                .setId(queryResp.getMonitoringGroup(0).getParam(0).getId())
                .setDisplayOrder(2)
                .build())
            .addParamOrder(
                MonitoringGroupParamOrder.newBuilder()
                .setId(queryResp.getMonitoringGroup(0).getParam(1).getId())
                .setDisplayOrder(3)
                .build())
            .build();
        String reorderParamReqJson = ProtoUtils.protoToJson(reorderParamReq);
        GenericResp reorderParamResp = monitoringService.reorderDeptMonitoringGroupParam(reorderParamReqJson);
        assertThat(reorderParamResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).hasSize(3);
        assertThat(queryResp.getMonitoringGroup(0).getParamList()).hasSize(6);
        assertThat(queryResp.getMonitoringGroup(0).getParam(0).getCode()).isEqualTo("gastric_intake");
        assertThat(queryResp.getMonitoringGroup(0).getParam(0).getDisplayOrder()).isEqualTo(1);
        assertThat(queryResp.getMonitoringGroup(0).getParam(1).getCode()).isEqualTo("test_param1_in");
        assertThat(queryResp.getMonitoringGroup(0).getParam(1).getDisplayOrder()).isEqualTo(2);
        assertThat(queryResp.getMonitoringGroup(0).getParam(2).getCode()).isEqualTo("intravenous_intake");
        assertThat(queryResp.getMonitoringGroup(0).getParam(2).getDisplayOrder()).isEqualTo(3);

        // D: 删除分组入量参数
        DeleteDeptMonitoringGroupParamReq deleteParamReq = DeleteDeptMonitoringGroupParamReq.newBuilder()
            .setId(queryResp.getMonitoringGroup(0).getParam(0).getId())
            .build();
        String deleteParamReqJson = ProtoUtils.protoToJson(deleteParamReq);
        GenericResp deleteParamResp = monitoringService.deleteDeptMonitoringGroupParam(deleteParamReqJson);
        assertThat(deleteParamResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // D: 删除分组入量参数
        deleteParamReq = DeleteDeptMonitoringGroupParamReq.newBuilder()
            .setId(queryResp.getMonitoringGroup(0).getParam(2).getId())
            .build();
        deleteParamReqJson = ProtoUtils.protoToJson(deleteParamReq);
        deleteParamResp = monitoringService.deleteDeptMonitoringGroupParam(deleteParamReqJson);

        queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).hasSize(3);
        assertThat(queryResp.getMonitoringGroup(0).getParamList()).hasSize(4);
        assertThat(queryResp.getMonitoringGroup(0).getParam(0).getCode()).isEqualTo("test_param1_in");
        assertThat(queryResp.getMonitoringGroup(0).getParam(1).getCode()).isEqualTo("transfusion_intake");
        assertThat(queryResp.getMonitoringGroup(0).getParam(2).getCode()).isEqualTo("test_param2_in");
        assertThat(queryResp.getMonitoringGroup(0).getParam(3).getCode()).isEqualTo("test_param5_in");
    }

    @Test
    public void testDeptMonitoringGroup() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 查询部门deptId3的观察项分组
        String queryReqJson = ProtoUtils.protoToJson(GetDeptMonitoringGroupsReq.newBuilder()
                .setDepartmentId(deptId3)
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .build());
        GetDeptMonitoringGroupsResp queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).isEmpty(); // 初始化应该无分组

        // 新增观察项分组1
        AddDeptMonitoringGroupReq addReq = AddDeptMonitoringGroupReq.newBuilder()
                .setDepartmentId(deptId3)
                .setName("monitoring_group_1")
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .setDisplayOrder(1)
                .build();
        String addReqJson = ProtoUtils.protoToJson(addReq);
        AddDeptMonitoringGroupResp addResp = monitoringService.addDeptMonitoringGroup(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        int group1Id = addResp.getId();

        // 新增观察项分组2
        addReq = AddDeptMonitoringGroupReq.newBuilder()
                .setDepartmentId(deptId3)
                .setName("monitoring_group_2")
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .setDisplayOrder(2)
                .build();
        addReqJson = ProtoUtils.protoToJson(addReq);
        addResp = monitoringService.addDeptMonitoringGroup(addReqJson);
        assertThat(addResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        int group2Id = addResp.getId();

        // 查询新增后的观察项分组
        queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).hasSize(2);
        assertThat(queryResp.getMonitoringGroup(0).getId()).isEqualTo(group1Id);
        assertThat(queryResp.getMonitoringGroup(1).getId()).isEqualTo(group2Id);

        // 调整观察项分组次序
        ReorderDeptMonitoringGroupReq reorderReq = ReorderDeptMonitoringGroupReq.newBuilder()
                .setDepartmentId(deptId3)
                .addGroupOrder(MonitoringGroupOrder.newBuilder()
                        .setMonitoringGroupId(group1Id)
                        .setDisplayOrder(2)
                        .build())
                .addGroupOrder(MonitoringGroupOrder.newBuilder()
                        .setMonitoringGroupId(group2Id)
                        .setDisplayOrder(1)
                        .build())
                .build();
        String reorderReqJson = ProtoUtils.protoToJson(reorderReq);
        GenericResp reorderResp = monitoringService.reorderDeptMonitoringGroup(reorderReqJson);
        assertThat(reorderResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询调整次序后的观察项分组
        queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).hasSize(2);
        assertThat(queryResp.getMonitoringGroup(0).getId()).isEqualTo(group2Id);
        assertThat(queryResp.getMonitoringGroup(1).getId()).isEqualTo(group1Id);

        // 删除观察项分组1
        DeleteDeptMonitoringGroupReq deleteReq = DeleteDeptMonitoringGroupReq.newBuilder()
                .setId(group1Id)
                .build();
        String deleteReqJson = ProtoUtils.protoToJson(deleteReq);
        GenericResp deleteResp = monitoringService.deleteDeptMonitoringGroup(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 删除观察项分组2
        deleteReq = DeleteDeptMonitoringGroupReq.newBuilder()
                .setId(group2Id)
                .build();
        deleteReqJson = ProtoUtils.protoToJson(deleteReq);
        deleteResp = monitoringService.deleteDeptMonitoringGroup(deleteReqJson);
        assertThat(deleteResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 确认观察项分组被删除
        queryResp = monitoringService.getDeptMonitoringGroups(queryReqJson);
        assertThat(queryResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(queryResp.getMonitoringGroupList()).isEmpty();
    }

    @Test
    public void testPatientMonitoringParams() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        initDepartments();

        // 添加部门观察项分组group_x1(test_param1_in), group_x2(test_param3_out);
        AddDeptMonitoringGroupReq addGroupReq1 = AddDeptMonitoringGroupReq.newBuilder()
                .setDepartmentId(deptId4)
                .setName("group_x1")
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .setDisplayOrder(1)
                .build();
        String addGroupReqJson1 = ProtoUtils.protoToJson(addGroupReq1);
        AddDeptMonitoringGroupResp addGroupResp1 = monitoringService.addDeptMonitoringGroup(addGroupReqJson1);
        assertThat(addGroupResp1.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        int groupX1Id = addGroupResp1.getId();

        AddDeptMonitoringGroupParamReq addGroupParamReq1 = AddDeptMonitoringGroupParamReq.newBuilder()
                .setDeptId(deptId4)
                .setDeptMonitoringGroupId(groupX1Id)
                .setCode("test_param1_in")
                .setDisplayOrder(1)
                .build();
        String addGroupParamReqJson1 = ProtoUtils.protoToJson(addGroupParamReq1);
        AddDeptMonitoringGroupParamResp addGroupParamResp1 = monitoringService.addDeptMonitoringGroupParam(addGroupParamReqJson1);
        assertThat(addGroupParamResp1.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        AddDeptMonitoringGroupReq addGroupReq2 = AddDeptMonitoringGroupReq.newBuilder()
                .setDepartmentId(deptId4)
                .setName("group_x2")
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .setDisplayOrder(2)
                .build();
        String addGroupReqJson2 = ProtoUtils.protoToJson(addGroupReq2);
        AddDeptMonitoringGroupResp addGroupResp2 = monitoringService.addDeptMonitoringGroup(addGroupReqJson2);
        assertThat(addGroupResp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        int groupX2Id = addGroupResp2.getId();

        AddDeptMonitoringGroupParamReq addGroupParamReq2 = AddDeptMonitoringGroupParamReq.newBuilder()
                .setDeptId(deptId4)
                .setDeptMonitoringGroupId(groupX2Id)
                .setCode("test_param3_out")
                .setDisplayOrder(1)
                .build();
        String addGroupParamReqJson2 = ProtoUtils.protoToJson(addGroupParamReq2);
        AddDeptMonitoringGroupParamResp addGroupParamResp2 = monitoringService.addDeptMonitoringGroupParam(addGroupParamReqJson2);
        assertThat(addGroupParamResp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询部门观察项分组 group_x1(test_param1_in), group_x2(test_param3_out) 是否符合预期
        String deptMonitoringGroupsReqJson = ProtoUtils.protoToJson(GetDeptMonitoringGroupsReq.newBuilder()
                .setDepartmentId(deptId4)
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .build());
        GetDeptMonitoringGroupsResp deptMonitoringGroupsResp = monitoringService.getDeptMonitoringGroups(deptMonitoringGroupsReqJson);
        assertThat(deptMonitoringGroupsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(deptMonitoringGroupsResp.getMonitoringGroupList()).hasSize(2);
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(0).getId()).isEqualTo(groupX1Id);
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(0).getParamList()).hasSize(1);
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(0).getParam(0).getCode()).isEqualTo("test_param1_in");
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(1).getId()).isEqualTo(groupX2Id);
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(1).getParamList()).hasSize(1);
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(1).getParam(0).getCode()).isEqualTo("test_param3_out");

        // 新增病人
        final LocalDateTime admissionTime = TimeUtils.getUtcFromLocalDateTime(
                TimeUtils.getLocalTime(2025, 7, 15, 0, 0), "UTC");
        PatientRecord patient = PatientTestUtils.newPatientRecord(patientId, 1 /*admission_status_in_icu*/, deptId4);
        patient.setHisAdmissionTime(admissionTime);
        patient.setAdmissionTime(admissionTime);
        patient = patientRecordRepo.save(patient);
        Long pid = patient.getId();

        // 查询病人观察项分组，预期与部门观察项分组一致，即 group_x1(test_param1_in), group_x2(test_param3_out)
        GetPatientMonitoringGroupsReq patientMonitoringGroupsReq = GetPatientMonitoringGroupsReq.newBuilder()
                .setPid(pid)
                .setDeptId(deptId4)
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .build();
        String patientMonitoringGroupsReqJson = ProtoUtils.protoToJson(patientMonitoringGroupsReq);
        GetPatientMonitoringGroupsResp patientMonitoringGroupsResp = monitoringService.getPatientMonitoringGroups(patientMonitoringGroupsReqJson);
        assertThat(patientMonitoringGroupsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(patientMonitoringGroupsResp.getMonitoringGroupList()).hasSize(2);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(0).getId()).isEqualTo(groupX1Id);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(0).getParamList()).hasSize(1);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(0).getParam(0).getCode()).isEqualTo("test_param1_in");
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(1).getId()).isEqualTo(groupX2Id);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(1).getParamList()).hasSize(1);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(1).getParam(0).getCode()).isEqualTo("test_param3_out");

        // 将病人观察项分组 group_x1(test_param1_in) 改成 group_x1(test_param1_in, test_param2_in)
        UpdatePatientMonitoringGroupReq updatePatientGroupReq = UpdatePatientMonitoringGroupReq.newBuilder()
                .setPid(pid)
                .setMonitoringGroupId(groupX1Id) // group_x1 的 ID
                .addParams(MonitoringGroupParamPB.newBuilder().setCode("test_param1_in").setDisplayOrder(1).build())
                .addParams(MonitoringGroupParamPB.newBuilder().setCode("test_param2_in").setDisplayOrder(2).build())
                .build();
        String updatePatientGroupReqJson = ProtoUtils.protoToJson(updatePatientGroupReq);
        GenericResp updatePatientGroupResp = monitoringService.updatePatientMonitoringGroup(updatePatientGroupReqJson);
        assertThat(updatePatientGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 更新部门观察项分组：删除group_x1, 新增group_x3(test_param4_out)
        DeleteDeptMonitoringGroupReq deleteGroupReq = DeleteDeptMonitoringGroupReq.newBuilder()
                .setId(groupX1Id)
                .build();
        String deleteGroupReqJson = ProtoUtils.protoToJson(deleteGroupReq);
        GenericResp deleteGroupResp = monitoringService.deleteDeptMonitoringGroup(deleteGroupReqJson);
        assertThat(deleteGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        AddDeptMonitoringGroupReq addGroupReq3 = AddDeptMonitoringGroupReq.newBuilder()
                .setDepartmentId(deptId4)
                .setName("group_x3")
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .setDisplayOrder(3)
                .build();
        String addGroupReqJson3 = ProtoUtils.protoToJson(addGroupReq3);
        AddDeptMonitoringGroupResp addGroupResp3 = monitoringService.addDeptMonitoringGroup(addGroupReqJson3);
        assertThat(addGroupResp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        int groupX3Id = addGroupResp3.getId();

        AddDeptMonitoringGroupParamReq addGroupParamReq3 = AddDeptMonitoringGroupParamReq.newBuilder()
                .setDeptId(deptId4)
                .setDeptMonitoringGroupId(groupX3Id)
                .setCode("test_param4_out")
                .setDisplayOrder(1)
                .build();
        String addGroupParamReqJson3 = ProtoUtils.protoToJson(addGroupParamReq3);
        AddDeptMonitoringGroupParamResp addGroupParamResp3 = monitoringService.addDeptMonitoringGroupParam(addGroupParamReqJson3);
        assertThat(addGroupParamResp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询部门观察项分组，预期为 group_x2(test_param3_out), group_x3(test_param4_out)
        deptMonitoringGroupsReqJson = ProtoUtils.protoToJson(GetDeptMonitoringGroupsReq.newBuilder()
                .setDepartmentId(deptId4)
                .setGroupType(GROUP_TYPE_MONITORING_ID)
                .build());
        deptMonitoringGroupsResp = monitoringService.getDeptMonitoringGroups(deptMonitoringGroupsReqJson);
        assertThat(deptMonitoringGroupsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(deptMonitoringGroupsResp.getMonitoringGroupList()).hasSize(2);
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(0).getName()).isEqualTo("group_x2");
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(0).getParamList()).hasSize(1);
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(0).getParam(0).getCode()).isEqualTo("test_param3_out");
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(1).getName()).isEqualTo("group_x3");
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(1).getParamList()).hasSize(1);
        assertThat(deptMonitoringGroupsResp.getMonitoringGroup(1).getParam(0).getCode()).isEqualTo("test_param4_out");

        // 查询病人观察项分组，预期为 group_x1(test_param1_in, test_param2_in), group_x2(test_param3_out)
        patientMonitoringGroupsResp = monitoringService.getPatientMonitoringGroups(patientMonitoringGroupsReqJson);
        assertThat(patientMonitoringGroupsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(patientMonitoringGroupsResp.getMonitoringGroupList()).hasSize(2);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(0).getId()).isEqualTo(groupX1Id);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(0).getParamList()).hasSize(2);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(0).getParam(0).getCode()).isEqualTo("test_param1_in");
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(0).getParam(1).getCode()).isEqualTo("test_param2_in");
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(1).getId()).isEqualTo(groupX2Id);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(1).getParamList()).hasSize(1);
        assertThat(patientMonitoringGroupsResp.getMonitoringGroup(1).getParam(0).getCode()).isEqualTo("test_param3_out");
    }

    private void initDepartments() {
        Map<String, RbacDepartment> deptMap = rbacDepartmentRepo.findAll().stream()
            .collect(Collectors.toMap(RbacDepartment::getDeptId, Function.identity()));
        for (String departmentId : List.of(deptId, deptId2, deptId3, deptId4)) {
            if (deptMap.containsKey(departmentId)) continue;
            RbacDepartment dept = new RbacDepartment();
            dept.setDeptId(departmentId);
            dept.setDeptName("dept-" + departmentId);
            rbacDepartmentRepo.save(dept);
        }

        monitoringConfig.initialize();
    }

    private final Integer BALANCE_TYPE_IN;
    private final Integer GROUP_TYPE_BALANCE_ID;
    private final Integer GROUP_TYPE_MONITORING_ID;

    private final String accountId;
    private final String deptId;
    private final String deptId2;
    private final String deptId3;
    private final String deptId4;
    private final Long patientId;
    private final MonitoringPB monitoringPb;

    private final ConfigProtoService protoService;
    private final MonitoringConfig monitoringConfig;
    private final MonitoringService monitoringService;
    private final MonitoringParamHistoryRepository monitoringParamHistoryRepo;
    private final PatientMonitoringGroupRepository patientMonitoringGroupRepo;
    private final PatientMonitoringGroupParamRepository patientMonitoringGroupParamRepo;

    private final RbacDepartmentRepository rbacDepartmentRepo;
    private final PatientRecordRepository patientRecordRepo;
}