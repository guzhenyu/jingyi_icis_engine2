package com.jingyicare.jingyi_icis_engine.service.tubes;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.util.JsonFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisTube.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.tubes.*;
import com.jingyicare.jingyi_icis_engine.entity.users.CurrentAccount;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.users.UserQuery;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class TubeService {
    public TubeService(
        @Autowired ConfigProtoService protoService,
        @Autowired UserQuery userQuery,
        @Autowired TubeSetting setting
    ) {
        this.DRAINAGE_TUBE_TYPE = protoService.getConfig().getTube().getDrainageTubeType();
        this.protoService = protoService;
        this.userQuery = userQuery;
        this.setting = setting;
    }

    public GetTubeTypesResp getTubeTypes(String getTubeTypesReqJson) {
        final GetTubeTypesReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getTubeTypesReqJson, GetTubeTypesReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GetTubeTypesResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        return GetTubeTypesResp.newBuilder()
            .setRt(protoService.getReturnCode(StatusCode.OK))
            .setDeptTubeTypeList(setting.getDeptTubeTypes(req.getDepartmentId()))
            .build();
    }

    @Transactional
    public AddTubeTypeResp addTubeType(String addTubeTypeReqJson) {
        final AddTubeTypeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addTubeTypeReqJson, AddTubeTypeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddTubeTypeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final String deptId = req.getDepartmentId();
        final TubeTypePB tubeTypePb = req.getTubeType();
        final String type = tubeTypePb.getType();
        final String name = tubeTypePb.getName();
        if (setting.findTubeType(deptId, name) != null) {
            log.warn("Failed to add a tube type, deptId {}, tubetypepb {}", deptId, tubeTypePb);
            return AddTubeTypeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_NAME_EXIST))
                .build();
        }

        final List<TubeType> tubeTypes = setting.findTubeTypes(deptId, type);
        if (tubeTypes == null || (!tubeTypes.isEmpty() && !type.equals(DRAINAGE_TUBE_TYPE))) {
            log.warn("Failed to add a tube type, deptId {}, tubetypepb {}", deptId, tubeTypePb);
            return AddTubeTypeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.NON_DRAINAGE_TUBE_SINGLE_INSTANCE))
                .build();
        }

        // 获取当前用户，新增管道类型
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return AddTubeTypeResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final Integer addedTubeTypeId = setting.addOrUpdateTubeType(
            deptId, tubeTypePb, null, account.autoAcctId());
        log.info("Add a tube type, deptId {}, tubetypepb {}, accountId {}",
            deptId, tubeTypePb, account.autoAcctId());

        return AddTubeTypeResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK))
            .setAddedTubeTypeId(addedTubeTypeId).build();
    }

    @Transactional
    public GenericResp updateTubeType(String addTubeTypeReqJson) {
        final AddTubeTypeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addTubeTypeReqJson, AddTubeTypeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获得需要更新的tubeTypeId
        final String deptId = req.getDepartmentId();
        final TubeTypePB tubeTypePb = req.getTubeType();
        final Integer tubeTypeId = tubeTypePb.getId();
        final String name = tubeTypePb.getName();
        final TubeType tubeType = setting.findTubeType(tubeTypeId);
        if (tubeType == null) {
            log.warn("Failed to update a tube type, tubetypepb {}", tubeTypePb);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_NOT_EXIST))
                .build();
        }
        if (!tubeType.getDeptId().equals(deptId)) {
            log.warn("Failed to update a tube type, tubetypepb {}", tubeTypePb);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ID_NOT_MATCH))
                .build();
        }
        if (!tubeType.getName().equals(name)) {
            final TubeType tubeTypeByName = setting.findTubeType(deptId, name);
            if (tubeTypeByName != null) {
                log.warn("Failed to update a tube type, tubetypepb {}", tubeTypePb);
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.TUBE_NAME_EXIST))
                    .build();
            }
        }

        // 获取当前用户，更新管道类型
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        setting.addOrUpdateTubeType(deptId, tubeTypePb, tubeTypeId, account.autoAcctId());
        log.info("Update a tube type, deptId {}, tubetypepb {}, accountId {}",
            deptId, tubeTypePb, account.autoAcctId());

        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    @Transactional
    public GenericResp disableTubeType(String disableTubeTypeReqJson) {
        final DisableTubeTypeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(disableTubeTypeReqJson, DisableTubeTypeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取当前用户，更新管道类型
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 获得需要更新的tubeTypeId
        final Integer tubeTypeId = req.getTubeTypeId();
        final Boolean isDisabled = req.getIsDisabled() > 0;
        if (setting.isDisablingTheLastTubeType(tubeTypeId, isDisabled)) {
            log.warn("Failed to disable a tube type, tubeTypeId {}", tubeTypeId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.LAST_TUBE_TYPE_CANNOT_BE_DISABLED))
                .build();
        }

        setting.disableTubeType(tubeTypeId, isDisabled, account.autoAcctId());

        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    public GenericResp deleteTubeType(String deleteTubeTypeReqJson) {
        final DeleteTubeTypeReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteTubeTypeReqJson, DeleteTubeTypeReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 删除管道类型
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        setting.deleteDeptTubeType(req.getTubeTypeId(), account.autoAcctId());

        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    public AddTubeTypeAttrResp addTubeTypeAttr(String addTubeTypeAttrReqJson) {
        final AddTubeTypeAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addTubeTypeAttrReqJson, AddTubeTypeAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddTubeTypeAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找管道类型
        final Integer tubeTypeId = req.getTubeTypeId();
        if (setting.findTubeType(tubeTypeId) == null) {
            log.warn("TubeType not found, tubeTypeId {}", tubeTypeId);
            return AddTubeTypeAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_NOT_EXIST))
                .build();
        }

        // 获取当前用户
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return AddTubeTypeAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查管道属性是否已经存在
        final TubeValueSpecPB attrPb = req.getAttr();
        final String attrCode = attrPb.getCode();
        if (setting.findTubeTypeAttribute(tubeTypeId, attrCode, false) != null) {
            log.error("Tube type attr exists, tubetypeid {}, attr {}", tubeTypeId, attrCode);
            return AddTubeTypeAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_EXIST))
                .build();
        }

        if (setting.findTubeTypeAttrName(tubeTypeId, attrPb.getName()) != null) {
            log.error("Tube type status exists, tubetypeid {}, attr {}", tubeTypeId, attrPb.getName());
            return AddTubeTypeAttrResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_STATUS_EXIST))
                .build();
        }

        // 检查是否有已删除的属性，如果没有则添加，如果有则更新
        Integer tubeTypeAttrId = -1;
        TubeTypeAttribute deletedAttr = setting.findTubeTypeAttribute(tubeTypeId, attrCode, true);
        if (deletedAttr == null) {
            tubeTypeAttrId = setting.addOrUpdateTubeTypeAttribute(
                tubeTypeId, attrPb, null, account.autoAcctId());
        } else {
            tubeTypeAttrId = setting.addOrUpdateTubeTypeAttribute(
                tubeTypeId, attrPb, deletedAttr.getId(), account.autoAcctId());
        }

        return AddTubeTypeAttrResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK))
            .setAddedTubeTypeAttrId(tubeTypeAttrId).build();
    }

    public GenericResp updateTubeTypeAttr(String updateTubeTypeAttrReqJson) {
        final AddTubeTypeAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateTubeTypeAttrReqJson, AddTubeTypeAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找管道类型
        final Integer tubeTypeId = req.getTubeTypeId();
        if (setting.findTubeType(tubeTypeId) == null) {
            log.warn("TubeType not found, tubeTypeId {}", tubeTypeId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_NOT_EXIST))
                .build();
        }

        // 获取 TubeTypeAttribute
        final TubeValueSpecPB attrPb = req.getAttr();
        final String attrCode = attrPb.getCode();
        Integer tubeTypeAttrId = null;
        {
            TubeTypeAttribute attr = setting.findTubeTypeAttribute(tubeTypeId, attrCode, false);
            if (attr != null) tubeTypeAttrId = attr.getId();
        }
        if (tubeTypeAttrId == null) {
            log.warn("TubeTypeAttribute not found for tubeTypeId {} and attribute {}", tubeTypeId, attrCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_NOT_EXIST))
                .build();
        }
        if (!tubeTypeAttrId.equals(attrPb.getId())) {
            log.warn("TubeTypeAttribute id not match, tubeTypeAttrId {}, attrPb {}", tubeTypeAttrId, attrPb);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_ID_NOT_MATCH))
                .build();
        }

        // 确认名称不一样
        {
            Integer tubeTypeAttrIdByName = null;
            TubeTypeAttribute attr = setting.findTubeTypeAttrName(tubeTypeId, attrPb.getName());
            if (attr != null && attr.getId() != tubeTypeAttrId) {
                log.warn("TubeTypeAttribute name exists, tubeTypeId {}, attr {}", tubeTypeId, attrPb.getName());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_ATTRIBUTE_EXIST))
                    .build();
            }
        }

        // 获取当前用户, 更新属性
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        setting.addOrUpdateTubeTypeAttribute(
            tubeTypeId, attrPb, tubeTypeAttrId, account.autoAcctId());

        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    public GenericResp deleteTubeTypeAttr(String deleteTubeTypeAttrReqJson) {
        final DeleteTubeTypeAttrReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteTubeTypeAttrReqJson, DeleteTubeTypeAttrReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 删除 TubeTypeAttribute
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        setting.deleteTubeTypeAttribute(req.getTubeTypeAttrId(), account.autoAcctId());

        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    public AddTubeTypeStatusResp addTubeTypeStatus(String addTubeTypeStatusReqJson) {
        final AddTubeTypeStatusReq req;
        try {
            req = ProtoUtils.parseJsonToProto(addTubeTypeStatusReqJson, AddTubeTypeStatusReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return AddTubeTypeStatusResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找管道类型
        final Integer tubeTypeId = req.getTubeTypeId();
        if (setting.findTubeType(tubeTypeId) == null) {
            log.warn("TubeType not found, tubeTypeId {}", tubeTypeId);
            return AddTubeTypeStatusResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_NOT_EXIST))
                .build();
        }

        // 获取当前用户
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return AddTubeTypeStatusResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }

        // 检查管道状态是否已经存在
        final TubeValueSpecPB statusPb = req.getStatus();
        final String statusCode = statusPb.getCode();
        if (setting.findTubeTypeStatus(tubeTypeId, statusCode, false) != null) {
            log.error("Tube type status exists, tubeTypeId {}, status {}", tubeTypeId, statusCode);
            return AddTubeTypeStatusResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_STATUS_EXIST))
                .build();
        }
        if (setting.findTubeTypeStatusName(tubeTypeId, statusPb.getName()) != null) {
            log.error("Tube type status exists, tubeTypeId {}, status {}", tubeTypeId, statusPb.getName());
            return AddTubeTypeStatusResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_STATUS_EXIST))
                .build();
        }

        // 添加 TubeTypeStatus
        Integer tubeTypeStatusId = -1;
        TubeTypeStatus deletedStatus = setting.findTubeTypeStatus(tubeTypeId, statusCode, true);
        if (deletedStatus == null) {
            tubeTypeStatusId = setting.addOrUpdateTubeTypeStatus(
                tubeTypeId, statusPb, null, account.autoAcctId());
        } else {
            tubeTypeStatusId = setting.addOrUpdateTubeTypeStatus(
                tubeTypeId, statusPb, deletedStatus.getId(), account.autoAcctId());
        }

        return AddTubeTypeStatusResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK))
            .setAddedTubeTypeStatusId(tubeTypeStatusId).build();
    }

    public GenericResp updateTubeTypeStatus(String updateTubeTypeStatusReqJson) {
        final AddTubeTypeStatusReq req;
        try {
            req = ProtoUtils.parseJsonToProto(updateTubeTypeStatusReqJson, AddTubeTypeStatusReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 查找管道类型
        final Integer tubeTypeId = req.getTubeTypeId();
        if (setting.findTubeType(tubeTypeId) == null) {
            log.warn("TubeType not found, tubeTypeId {}", tubeTypeId);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_NOT_EXIST))
                .build();
        }

        // 获取 TubeTypeStatus
        final TubeValueSpecPB statusPb = req.getStatus();
        final String statusCode = statusPb.getCode();
        Integer tubeTypeStatusId = null;
        {
            TubeTypeStatus status = setting.findTubeTypeStatus(tubeTypeId, statusCode, false);
            if (status != null) tubeTypeStatusId = status.getId();
        }
        if (tubeTypeStatusId == null) {
            log.warn("TubeTypeStatus not found for tubeTypeId {} and status {}", tubeTypeId, statusCode);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_STATUS_NOT_EXIST))
                .build();
        }
        if (!tubeTypeStatusId.equals(statusPb.getId())) {
            log.warn("TubeTypeStatus id not match, tubeTypeStatusId {}, statusPb {}", tubeTypeStatusId, statusPb);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_STATUS_ID_NOT_MATCH))
                .build();
        }

        // 确认名称不一样
        {
            Integer tubeTypeStatusIdByName = null;
            TubeTypeStatus status = setting.findTubeTypeStatusName(tubeTypeId, statusPb.getName());
            if (status != null && status.getId() != tubeTypeStatusId) {
                log.warn("TubeTypeStatus name exists, tubeTypeId {}, status {}", tubeTypeId, statusPb.getName());
                return GenericResp.newBuilder()
                    .setRt(protoService.getReturnCode(StatusCode.TUBE_TYPE_STATUS_EXIST))
                    .build();
            }
        }

        // 获取当前用户, 更新状态
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        setting.addOrUpdateTubeTypeStatus(
            tubeTypeId, statusPb, tubeTypeStatusId, account.autoAcctId());

        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }


    public GenericResp deleteTubeTypeStatus(String deleteTubeTypeStatusReqJson) {
        final DeleteTubeTypeStatusReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteTubeTypeStatusReqJson, DeleteTubeTypeStatusReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 删除 TubeTypeStatus
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        setting.deleteTubeTypeStatus(req.getTubeTypeStatusId(), account.autoAcctId());

        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    public GenericResp adjustTubeOrder(String adjustTubeOrderReqJson) {
        final AdjustTubeOrderReq req;
        try {
            req = ProtoUtils.parseJsonToProto(adjustTubeOrderReqJson, AdjustTubeOrderReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to convert string to proto: {}", e);
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 调整管道类型顺序
        final CurrentAccount account = userQuery.getCurrentAccount();
        if (account == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(protoService.getReturnCode(StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        setting.adjustTubeOrder(req, account.autoAcctId());
        return GenericResp.newBuilder().setRt(protoService.getReturnCode(StatusCode.OK)).build();
    }

    private final String DRAINAGE_TUBE_TYPE;  // 引流管
    private final ConfigProtoService protoService;
    private final UserQuery userQuery;
    private final TubeSetting setting;
}