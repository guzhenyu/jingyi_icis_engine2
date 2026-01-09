package com.jingyicare.jingyi_icis_engine.service.tubes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

public class TubeServiceTests extends TestsBase {
    public TubeServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired TubeService tubeService,
        @Autowired TubeTypeRepository tubeTypeRepo,
        @Autowired TubeTypeAttributeRepository tubeTypeAttrRepo,
        @Autowired TubeTypeStatusRepository tubeTypeStatusRepo,
        @Autowired MonitoringParamRepository monitoringParamRepo
    ) {
        this.accountId = "admin";
        this.deptId = "10006";
        this.deptId2 = "10025";
        this.UI_TYPE_TEXT = protoService.getConfig().getTube().getEnums().getTubeUiTypeText().getId();
        this.UI_TYPE_SINGLE_SELECT = protoService.getConfig().getTube().getEnums().getTubeUiTypeSingleSelector().getId();
        this.UI_TYPE_MULTI_SELECT = protoService.getConfig().getTube().getEnums().getTubeUiTypeMultiSelector().getId();
        this.DRAINAGE_TUBE = protoService.getConfig().getTube().getTypeList().getTubeType(0);
        this.NON_DRAINAGE_TUBE1 = protoService.getConfig().getTube().getTypeList().getTubeType(1);
        this.NON_DRAINAGE_TUBE2 = protoService.getConfig().getTube().getTypeList().getTubeType(2);
        this.TUBE_ATTR1 = protoService.getConfig().getTube().getAttributeList().getAttribute(0);
        this.TUBE_ATTR2 = protoService.getConfig().getTube().getAttributeList().getAttribute(1);
        this.TUBE_STATUS1 = protoService.getConfig().getTube().getStatusList().getStatus(0);
        this.TUBE_STATUS2 = protoService.getConfig().getTube().getStatusList().getStatus(1);
        this.INVALID_ID = 1000000;

        this.tubeService = tubeService;
        this.tubeTypeRepo = tubeTypeRepo;
        this.tubeTypeAttrRepo = tubeTypeAttrRepo;
        this.tubeTypeStatusRepo = tubeTypeStatusRepo;
        this.monitoringParamRepo = monitoringParamRepo;
    }

    @Test
    public void testTubeSetting() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 查询部门的管道类型-1
        String getTubeTypesReqJson = String.format(
            "{\"department_id\": %s}", deptId
        );
        GetTubeTypesResp resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 为部门添加一个管道类型
        AddTubeTypeReq addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(NON_DRAINAGE_TUBE1)
            .build();
        String addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        AddTubeTypeResp resp2 = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeId = resp2.getAddedTubeTypeId();

        resp2 = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.TUBE_NAME_EXIST.ordinal());

        addTubeTypeReq = addTubeTypeReq.toBuilder()
            .setTubeType(addTubeTypeReq.getTubeType().toBuilder().setId(tubeTypeId).build())
            .build();
        addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        GenericResp resp3 = tubeService.updateTubeType(addTubeTypeReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        AddTubeTypeReq updateTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(addTubeTypeReq.getTubeType().toBuilder().setId(INVALID_ID).build())
            .build();
        String updateTubeTypeReqJson = ProtoUtils.protoToJson(updateTubeTypeReq);
        resp3 = tubeService.updateTubeType(updateTubeTypeReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.TUBE_TYPE_NOT_EXIST.ordinal());

        // 为部门&管道类型 添加一个管道属性
        // 测试重复添加失败
        AddTubeTypeAttrReq addTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setAttr(TUBE_ATTR1)
            .build();
        String addTubeTypeAttrReqJson = ProtoUtils.protoToJson(addTubeTypeAttrReq);
        AddTubeTypeAttrResp resp4 = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        assertThat(resp4.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeAttrId = resp4.getAddedTubeTypeAttrId();

        resp4 = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        assertThat(resp4.getRt().getCode()).isEqualTo(StatusCode.TUBE_TYPE_ATTRIBUTE_EXIST.ordinal());

        // 为部门&管道类型 更改一个属性
        // 更改一个不存在的管道属性失败
        AddTubeTypeAttrReq updateTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setAttr(addTubeTypeAttrReq.getAttr().toBuilder().setId(tubeTypeAttrId).build())
            .build();
        String updateTubeTypeAttrReqJson = ProtoUtils.protoToJson(updateTubeTypeAttrReq);
        resp3 = tubeService.updateTubeTypeAttr(updateTubeTypeAttrReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        updateTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setAttr(addTubeTypeAttrReq.getAttr().toBuilder().setCode(TUBE_ATTR2.getCode()).build())
            .build();
        updateTubeTypeAttrReqJson = ProtoUtils.protoToJson(updateTubeTypeAttrReq);
        resp3 = tubeService.updateTubeTypeAttr(updateTubeTypeAttrReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.TUBE_TYPE_ATTRIBUTE_NOT_EXIST.ordinal());

        updateTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setAttr(addTubeTypeAttrReq.getAttr().toBuilder().setId(INVALID_ID).build())
            .build();
        updateTubeTypeAttrReqJson = ProtoUtils.protoToJson(updateTubeTypeAttrReq);
        resp3 = tubeService.updateTubeTypeAttr(updateTubeTypeAttrReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.TUBE_TYPE_ATTRIBUTE_ID_NOT_MATCH.ordinal());

        // 为部门&管道类型 添加一个管道状态
        // 测试重复添加失败
        AddTubeTypeStatusReq addTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setStatus(TUBE_STATUS1)
            .build();
        String addTubeTypeStatusReqJson = ProtoUtils.protoToJson(addTubeTypeStatusReq);
        AddTubeTypeStatusResp resp5 = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        assertThat(resp5.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeStatusId = resp5.getAddedTubeTypeStatusId();

        resp5 = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        assertThat(resp5.getRt().getCode()).isEqualTo(StatusCode.TUBE_TYPE_STATUS_EXIST.ordinal());

        // 为部门&管道类型 更改一个状态
        // 更改一个不存在的管道状态失败
        AddTubeTypeStatusReq updateTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setStatus(addTubeTypeStatusReq.getStatus().toBuilder().setId(tubeTypeStatusId).build())
            .build();
        String updateTubeTypeStatusReqJson = ProtoUtils.protoToJson(updateTubeTypeStatusReq);
        resp3 = tubeService.updateTubeTypeStatus(updateTubeTypeStatusReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        updateTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setStatus(addTubeTypeStatusReq.getStatus().toBuilder().setCode(TUBE_STATUS2.getCode()).build())
            .build();
        updateTubeTypeStatusReqJson = ProtoUtils.protoToJson(updateTubeTypeStatusReq);
        resp3 = tubeService.updateTubeTypeStatus(updateTubeTypeStatusReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.TUBE_TYPE_STATUS_NOT_EXIST.ordinal());

        updateTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setStatus(addTubeTypeStatusReq.getStatus().toBuilder().setId(INVALID_ID).build())
            .build();
        updateTubeTypeStatusReqJson = ProtoUtils.protoToJson(updateTubeTypeStatusReq);
        resp3 = tubeService.updateTubeTypeStatus(updateTubeTypeStatusReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.TUBE_TYPE_STATUS_ID_NOT_MATCH.ordinal());

        // 查询部门的管道类型-2
        resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getDeptTubeTypeList().getTubeType(0).getAttributeList()).hasSize(1);
        assertThat(resp.getDeptTubeTypeList().getTubeType(0).getStatusList()).hasSize(1);

        // 删除管道属性
        DeleteTubeTypeAttrReq deleteTubeTypeAttrReq = DeleteTubeTypeAttrReq.newBuilder()
            .setTubeTypeAttrId(tubeTypeAttrId)
            .build();
        String deleteTubeTypeAttrReqJson = ProtoUtils.protoToJson(deleteTubeTypeAttrReq);
        resp3 = tubeService.deleteTubeTypeAttr(deleteTubeTypeAttrReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 删除管道状态
        DeleteTubeTypeStatusReq deleteTubeTypeStatusReq = DeleteTubeTypeStatusReq.newBuilder()
            .setTubeTypeStatusId(tubeTypeStatusId)
            .build();
        String deleteTubeTypeStatusReqJson = ProtoUtils.protoToJson(deleteTubeTypeStatusReq);
        resp3 = tubeService.deleteTubeTypeStatus(deleteTubeTypeStatusReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询部门的管道类型-3
        resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getDeptTubeTypeList().getTubeType(0).getAttributeList()).isEmpty();
        assertThat(resp.getDeptTubeTypeList().getTubeType(0).getStatusList()).isEmpty();

        // 删除管道类型
        DeleteTubeTypeReq deleteTubeTypeReq = DeleteTubeTypeReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .build();
        String deleteTubeTypeReqJson = ProtoUtils.protoToJson(deleteTubeTypeReq);
        resp3 = tubeService.deleteTubeType(deleteTubeTypeReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询部门的管道类型-4
        resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(resp.getDeptTubeTypeList().getTubeTypeList()).isEmpty();
    }

        @Test
    public void testRecoverADeletedTubeAttrAndStatus() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 为部门添加一个管道类型
        AddTubeTypeReq addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId2)
            .setTubeType(NON_DRAINAGE_TUBE1)
            .build();
        String addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        AddTubeTypeResp addTubeTypeResp = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(addTubeTypeResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeId = addTubeTypeResp.getAddedTubeTypeId();

        // 为部门&管道类型 添加一个管道属性
        AddTubeTypeAttrReq addTubeTypeAttrReq = AddTubeTypeAttrReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setAttr(TUBE_ATTR1)
            .build();
        String addTubeTypeAttrReqJson = ProtoUtils.protoToJson(addTubeTypeAttrReq);
        AddTubeTypeAttrResp addTubeTypeAttrResp = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        assertThat(addTubeTypeAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeAttrId = addTubeTypeAttrResp.getAddedTubeTypeAttrId();

        // 为部门&管道类型 添加一个管道状态
        AddTubeTypeStatusReq addTubeTypeStatusReq = AddTubeTypeStatusReq.newBuilder()
            .setTubeTypeId(tubeTypeId)
            .setStatus(TUBE_STATUS1)
            .build();
        String addTubeTypeStatusReqJson = ProtoUtils.protoToJson(addTubeTypeStatusReq);
        AddTubeTypeStatusResp addTubeTypeStatusResp = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        assertThat(addTubeTypeStatusResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeStatusId = addTubeTypeStatusResp.getAddedTubeTypeStatusId();

        // 删除管道类型属性
        DeleteTubeTypeAttrReq deleteTubeTypeAttrReq = DeleteTubeTypeAttrReq.newBuilder()
            .setTubeTypeAttrId(tubeTypeAttrId)
            .build();
        String deleteTubeTypeAttrReqJson = ProtoUtils.protoToJson(deleteTubeTypeAttrReq);
        GenericResp deleteTubeTypeAttrResp = tubeService.deleteTubeTypeAttr(deleteTubeTypeAttrReqJson);
        assertThat(deleteTubeTypeAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增相同的管道属性
        addTubeTypeAttrResp = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        assertThat(addTubeTypeAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeAttrId2 = addTubeTypeAttrResp.getAddedTubeTypeAttrId();

        // 检查新增的管道属性和原管道属性id一样
        assertThat(tubeTypeAttrId).isEqualTo(tubeTypeAttrId2);

        // 删除管道类型状态
        DeleteTubeTypeStatusReq deleteTubeTypeStatusReq = DeleteTubeTypeStatusReq.newBuilder()
            .setTubeTypeStatusId(tubeTypeStatusId)
            .build();
        String deleteTubeTypeStatusReqJson = ProtoUtils.protoToJson(deleteTubeTypeStatusReq);
        GenericResp deleteTubeTypeStatusResp = tubeService.deleteTubeTypeStatus(deleteTubeTypeStatusReqJson);
        assertThat(deleteTubeTypeStatusResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 新增相同的管道状态
        addTubeTypeStatusResp = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        assertThat(addTubeTypeStatusResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeStatusId2 = addTubeTypeStatusResp.getAddedTubeTypeStatusId();

        // 检查新增的管道状态和原管道状态id一样
        assertThat(tubeTypeStatusId).isEqualTo(tubeTypeStatusId2);
    }

    @Test
    public void testDrainageTubeSetting() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 查询部门的管道类型-1
        String getTubeTypesReqJson = String.format(
            "{\"department_id\": %s}", deptId
        );
        GetTubeTypesResp resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 为部门添加一个管道类型
        AddTubeTypeReq addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(DRAINAGE_TUBE)
            .build();
        String addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        AddTubeTypeResp resp2 = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeId = resp2.getAddedTubeTypeId();

        addTubeTypeReq = addTubeTypeReq.toBuilder()
            .setTubeType(addTubeTypeReq.getTubeType().toBuilder().setName("DrainageTube_2nd").build())
            .build();
        addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        resp2 = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeId2 = resp2.getAddedTubeTypeId();

        resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        assertThat(tubeTypeId).isNotEqualTo(tubeTypeId2);

        // 检查monitoring_params表，存在tube_ylg_ylg和tube_ylg_DrainageTube_2nd
        MonitoringParam monitoringParam = monitoringParamRepo.findByCode("tube_ylg_ylg").orElse(null);
        assertThat(monitoringParam).isNotNull();
        monitoringParam = monitoringParamRepo.findByCode("tube_ylg_DrainageTube_2nd").orElse(null);
        assertThat(monitoringParam).isNotNull();
    }

    @Test
    public void testUpdateTubeName() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 查询部门的管道类型-1
        String getTubeTypesReqJson = String.format(
            "{\"department_id\": %s}", deptId
        );
        GetTubeTypesResp resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 为部门添加一个管道类型
        AddTubeTypeReq addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(NON_DRAINAGE_TUBE1)
            .build();
        String addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        AddTubeTypeResp resp2 = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeId = resp2.getAddedTubeTypeId();

        // 检测-1
        resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 更名
        AddTubeTypeReq updateTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(addTubeTypeReq.getTubeType().toBuilder().setId(tubeTypeId).setName("气管插管-2").build())
            .build();
        String updateTubeTypeReqJson = ProtoUtils.protoToJson(updateTubeTypeReq);
        GenericResp resp3 = tubeService.updateTubeType(updateTubeTypeReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 检测-2
        resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 部门不匹配
        updateTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId("10007")
            .setTubeType(addTubeTypeReq.getTubeType().toBuilder().setId(tubeTypeId).setName("气管插管-3").build())
            .build();
        updateTubeTypeReqJson = ProtoUtils.protoToJson(updateTubeTypeReq);
        resp3 = tubeService.updateTubeType(updateTubeTypeReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.TUBE_TYPE_ID_NOT_MATCH.ordinal());

        // 新增管道，测试改名冲突
        addTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(NON_DRAINAGE_TUBE2)
            .build();
        addTubeTypeReqJson = ProtoUtils.protoToJson(addTubeTypeReq);
        resp2 = tubeService.addTubeType(addTubeTypeReqJson);
        assertThat(resp2.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer tubeTypeId2 = resp2.getAddedTubeTypeId();

        updateTubeTypeReq = AddTubeTypeReq.newBuilder()
            .setDepartmentId(deptId)
            .setTubeType(addTubeTypeReq.getTubeType().toBuilder().setId(tubeTypeId2).setName("气管插管-2").build())
            .build();
        updateTubeTypeReqJson = ProtoUtils.protoToJson(updateTubeTypeReq);
        resp3 = tubeService.updateTubeType(updateTubeTypeReqJson);
        assertThat(resp3.getRt().getCode()).isEqualTo(StatusCode.TUBE_NAME_EXIST.ordinal());
    }

    private final String accountId;
    private final String deptId;
    private final String deptId2;
    private final Integer UI_TYPE_TEXT;
    private final Integer UI_TYPE_SINGLE_SELECT;
    private final Integer UI_TYPE_MULTI_SELECT;

    private final TubeTypePB DRAINAGE_TUBE;
    private final TubeTypePB NON_DRAINAGE_TUBE1;
    private final TubeTypePB NON_DRAINAGE_TUBE2;
    private final TubeValueSpecPB TUBE_ATTR1;
    private final TubeValueSpecPB TUBE_ATTR2;
    private final TubeValueSpecPB TUBE_STATUS1;
    private final TubeValueSpecPB TUBE_STATUS2;
    private final Integer INVALID_ID;

    private final TubeService tubeService;
    private final TubeTypeRepository tubeTypeRepo;
    private final TubeTypeAttributeRepository tubeTypeAttrRepo;
    private final TubeTypeStatusRepository tubeTypeStatusRepo;
    private final MonitoringParamRepository monitoringParamRepo;
}