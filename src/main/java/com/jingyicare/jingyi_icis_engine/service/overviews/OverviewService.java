package com.jingyicare.jingyi_icis_engine.service.overviews;

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

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisOverview.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

import com.jingyicare.jingyi_icis_engine.entity.lis.*;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.*;
import com.jingyicare.jingyi_icis_engine.entity.overviews.*;
import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.lis.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.*;
import com.jingyicare.jingyi_icis_engine.repository.overviews.*;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.service.lis.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class OverviewService {
    public OverviewService(
        @Autowired ConfigurableApplicationContext context,
        @Autowired ConfigProtoService protoService,
        @Autowired UserService userService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired PatientService patientService,
        @Autowired LisService lisService,
        @Autowired OverviewTemplateRepository templateRepo,
        @Autowired OverviewGroupRepository groupRepo,
        @Autowired OverviewParamRepository paramRepo,
        @Autowired BgaParamRepository bgaParamRepo,
        @Autowired ExternalLisParamRepository externalLisParamRepo,
        @Autowired PatientMonitoringRecordRepository pmrRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();

        List<EnumValue> paramTypeList = protoService.getConfig().getOverview().getEnums().getParamTypeList();
        this.PARAM_TYPE_BGA = paramTypeList.stream()
            .filter(e -> e.getName().equals("血气")).findFirst().orElse(null);
        this.PARAM_TYPE_LIS = paramTypeList.stream()
            .filter(e -> e.getName().equals("检验")).findFirst().orElse(null);
        this.PARAM_TYPE_VITAL_SIGNAL = paramTypeList.stream()
            .filter(e -> e.getName().equals("生命体征")).findFirst().orElse(null);
        this.PARAM_TYPE_BALANCE = paramTypeList.stream()
            .filter(e -> e.getName().equals("出入量")).findFirst().orElse(null);
        if (this.PARAM_TYPE_BGA == null || this.PARAM_TYPE_LIS == null ||
            this.PARAM_TYPE_VITAL_SIGNAL == null || this.PARAM_TYPE_BALANCE == null
        ) {
            log.error("Failed to get param type enums {}, {}, {}, {}",
                this.PARAM_TYPE_BGA, this.PARAM_TYPE_LIS, this.PARAM_TYPE_VITAL_SIGNAL, this.PARAM_TYPE_BALANCE);
            LogUtils.flushAndQuit(context);
        }
        this.vitalSignBlockedParams =  protoService.getConfig().getOverview()
            .getVitalSignBlockedParamList()
            .stream()
            .collect(Collectors.toSet());

        MonitoringPB monitoringPb = protoService.getConfig().getMonitoring();
        this.NET_HOURLY_IN_CODE = monitoringPb.getNetHourlyInCode();
        this.NET_HOURLY_OUT_CODE = monitoringPb.getNetHourlyOutCode();
        this.NET_HOURLY_NET_CODE = monitoringPb.getNetHourlyNetCode();

        this.statusCodeMsgList = protoService.getConfig().getText().getStatusCodeMsgList();
        this.protoService = protoService;
        this.userService = userService;
        this.monitoringConfig = monitoringConfig;
        this.patientService = patientService;
        this.lisService = lisService;

        this.templateRepo = templateRepo;
        this.groupRepo = groupRepo;
        this.paramRepo = paramRepo;
        this.bgaParamRepo = bgaParamRepo;
        this.externalLisParamRepo = externalLisParamRepo;
        this.pmrRepo = pmrRepo;
    }

    @Transactional
    public GetOverviewTemplatesResp getOverviewTemplates(String getOverviewTemplatesReqJson) {
        final GetOverviewTemplatesReq req;
        try {
            GetOverviewTemplatesReq.Builder builder = GetOverviewTemplatesReq.newBuilder();
            JsonFormat.parser().merge(getOverviewTemplatesReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetOverviewTemplatesResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Get user information
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GetOverviewTemplatesResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // Query templates by dept_id and account_id
        List<OverviewTemplate> deptTemplates = templateRepo.findByDeptIdAndIsDeletedFalse(req.getDeptId());
        List<OverviewTemplate> accountTemplates = templateRepo.findByAccountIdAndIsDeletedFalse(accountId);

        // Sort templates by display_order and then id
        Comparator<OverviewTemplate> templateComparator = Comparator
            .comparing(OverviewTemplate::getDisplayOrder, Comparator.nullsLast(Integer::compareTo))
            .thenComparing(OverviewTemplate::getId, Comparator.nullsLast(Long::compareTo));

        deptTemplates = deptTemplates.stream()
            .sorted(templateComparator)
            .collect(Collectors.toList());

        accountTemplates = accountTemplates.stream()
            .sorted(templateComparator)
            .collect(Collectors.toList());

        // Build response
        GetOverviewTemplatesResp.Builder respBuilder = GetOverviewTemplatesResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK));

        // Convert dept templates to Protobuf
        for (OverviewTemplate template : deptTemplates) {
            OverviewTemplatePB templatePB = toOverviewTemplatePB(template);
            respBuilder.addDeptTemplate(templatePB);
        }

        // Convert account templates to Protobuf
        for (OverviewTemplate template : accountTemplates) {
            OverviewTemplatePB templatePB = toOverviewTemplatePB(template);
            respBuilder.addAccountTemplate(templatePB);
        }

        return respBuilder.build();
    }

    @Transactional
    public GetOverviewTemplateResp getOverviewTemplate(String getOverviewTemplateReqJson) {
        final GetOverviewTemplateReq req;
        try {
            GetOverviewTemplateReq.Builder builder = GetOverviewTemplateReq.newBuilder();
            JsonFormat.parser().merge(getOverviewTemplateReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetOverviewTemplateResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // Query template by ID
        OverviewTemplate template = templateRepo.findByIdAndIsDeletedFalse(req.getTemplateId()).orElse(null);
        if (template == null) {
            return GetOverviewTemplateResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_NOT_FOUND))
                .build();
        }

        // Convert template to Protobuf
        OverviewTemplatePB templatePB = toOverviewTemplatePB(template);

        return GetOverviewTemplateResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setTemplate(templatePB)
            .build();
    }

    private OverviewTemplatePB toOverviewTemplatePB(OverviewTemplate template) {
        OverviewTemplatePB.Builder builder = OverviewTemplatePB.newBuilder()
            .setId(template.getId() != null ? template.getId() : 0)
            .setDeptId(template.getDeptId() != null ? template.getDeptId() : "")
            .setAccountId(template.getAccountId() != null ? template.getAccountId() : "")
            .setCreatedBy(template.getCreatedBy() != null ? template.getCreatedBy() : "")
            .setCreatedAtIso8601(template.getCreatedAt() != null 
                ? TimeUtils.toIso8601String(template.getCreatedAt(), ZONE_ID) : "")
            .setTemplateName(template.getTemplateName() != null ? template.getTemplateName() : "")
            .setNotes(template.getNotes() != null ? template.getNotes() : "")
            .setDisplayOrder(template.getDisplayOrder() != null ? template.getDisplayOrder() : 0);

        // Fetch and convert associated groups, sorted by display_order
        List<OverviewGroup> groups = groupRepo.findByTemplateIdAndIsDeletedFalse(template.getId()).stream()
            .sorted(Comparator.comparing(OverviewGroup::getDisplayOrder, Comparator.nullsLast(Integer::compareTo)))
            .collect(Collectors.toList());

        for (OverviewGroup group : groups) {
            OverviewGroupPB groupPB = toOverviewGroupPB(group);
            builder.addGroup(groupPB);
        }

        return builder.build();
    }

    private OverviewGroupPB toOverviewGroupPB(OverviewGroup group) {
        OverviewGroupPB.Builder builder = OverviewGroupPB.newBuilder()
            .setId(group.getId() != null ? group.getId() : 0)
            .setTemplateId(group.getTemplateId() != null ? group.getTemplateId() : 0)
            .setGroupName(group.getGroupName() != null ? group.getGroupName() : "")
            .setIsBalanceGroup(group.getIsBalanceGroup() != null ? group.getIsBalanceGroup() : false)
            .setDisplayOrder(group.getDisplayOrder() != null ? group.getDisplayOrder() : 0);

        // Fetch and convert associated parameters, sorted by display_order
        List<OverviewParam> params = paramRepo.findByGroupIdAndIsDeletedFalse(group.getId()).stream()
            .sorted(Comparator.comparing(OverviewParam::getDisplayOrder, Comparator.nullsLast(Integer::compareTo)))
            .collect(Collectors.toList());

        for (OverviewParam param : params) {
            OverviewParamPB paramPB = toOverviewParamPB(param);
            builder.addParam(paramPB);
        }

        return builder.build();
    }

    private OverviewParamPB toOverviewParamPB(OverviewParam param) {
        OverviewParamPB.Builder builder = OverviewParamPB.newBuilder()
            .setId(param.getId() != null ? param.getId() : 0)
            .setGroupId(param.getGroupId() != null ? param.getGroupId() : 0)
            .setParamName(param.getParamName() != null ? param.getParamName() : "")
            .setGraphType(param.getGraphType() != null ? param.getGraphType() : 0)
            .setColor(param.getColor() != null ? param.getColor() : "")
            .setPointIcon(param.getPointIcon() != null ? param.getPointIcon() : "")
            .setParamType(param.getParamType() != null ? param.getParamType() : 0)
            .setBgaCategoryId(param.getBgaCategoryId() != null ? param.getBgaCategoryId() : 0)
            .setParamCode(param.getParamCode() != null ? param.getParamCode() : "")
            .setBalanceTypeId(param.getBalanceTypeId() != null ? param.getBalanceTypeId() : 0)
            .setDisplayOrder(param.getDisplayOrder() != null ? param.getDisplayOrder() : 0);

        // Handle value_meta (assuming it’s a serialized base64 string in the entity)
        if (param.getValueMeta() != null && !param.getValueMeta().isEmpty()) {
            ValueMetaPB valueMetaPB = ProtoUtils.decodeValueMeta(param.getValueMeta());
            if (valueMetaPB != null) {
                builder.setValueMeta(valueMetaPB);
            }
        }

        return builder.build();
    }

    @Transactional
    public AddOverviewTemplateResp addOverviewTemplate(String addOverviewTemplateReqJson) {
        final AddOverviewTemplateReq req;
        try {
            AddOverviewTemplateReq.Builder builder = AddOverviewTemplateReq.newBuilder();
            JsonFormat.parser().merge(addOverviewTemplateReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddOverviewTemplateResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddOverviewTemplateResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取请求参数
        OverviewTemplatePB templatePB = req.getTemplate();
        boolean deptIdBlank = StrUtils.isBlank(templatePB.getDeptId());
        String templateDeptId = deptIdBlank ? null : templatePB.getDeptId();
        String templateAccountId = deptIdBlank ? accountId : null;

        // 查重
        String templateName = templatePB.getTemplateName();
        OverviewTemplate existingTemplate = templateRepo.findByDeptIdAndAccountIdAndTemplateNameAndIsDeletedFalse(
            templateDeptId, templateAccountId, templateName).orElse(null);
        if (existingTemplate != null) {
            return AddOverviewTemplateResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_EXISTS))
                .build();
        }

        // 转化
        OverviewTemplate template = new OverviewTemplate();
        template.setDeptId(templateDeptId);
        template.setAccountId(templateAccountId);
        template.setTemplateName(templateName);
        template.setNotes(templatePB.getNotes());
        template.setDisplayOrder(templatePB.getDisplayOrder() <= 0 ?
            getTemplateNextDisplayOrder(templateDeptId) : templatePB.getDisplayOrder()
        );
        template.setCreatedBy(accountId);
        LocalDateTime now = TimeUtils.getNowUtc();
        template.setCreatedAt(now);
        template.setIsDeleted(false);

        template.setModifiedBy(accountId);
        template.setModifiedByAccountName(accountName);
        template.setModifiedAt(now);

        // Save template
        template = templateRepo.save(template);

        return AddOverviewTemplateResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setId(template.getId())
            .build();
    }

    @Transactional
    public GenericResp updateOverviewTemplate(String updateOverviewTemplateReqJson) {
        final UpdateOverviewTemplateReq req;
        try {
            UpdateOverviewTemplateReq.Builder builder = UpdateOverviewTemplateReq.newBuilder();
            JsonFormat.parser().merge(updateOverviewTemplateReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取账号信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        String accountId = account.getFirst();
        String accountName = account.getSecond();

        OverviewTemplatePB templatePB = req.getTemplate();
        Long id = templatePB.getId();
        OverviewTemplate template = templateRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (template == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_NOT_FOUND))
                .build();
        }

        // 查重（如果名称修改了）
        if (!template.getTemplateName().equals(templatePB.getTemplateName())) {
            OverviewTemplate dup = templateRepo.findByDeptIdAndAccountIdAndTemplateNameAndIsDeletedFalse(
                template.getDeptId(), template.getAccountId(), templatePB.getTemplateName()).orElse(null);
            if (dup != null) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_EXISTS))
                    .build();
            }
            template.setTemplateName(templatePB.getTemplateName());
        }

        template.setNotes(templatePB.getNotes());
        template.setDisplayOrder(templatePB.getDisplayOrder());
        template.setModifiedBy(accountId);
        template.setModifiedByAccountName(accountName);
        template.setModifiedAt(TimeUtils.getNowUtc());

        templateRepo.save(template);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteOverviewTemplate(String deleteOverviewTemplateReqJson) {
        final DeleteOverviewTemplateReq req;
        try {
            DeleteOverviewTemplateReq.Builder builder = DeleteOverviewTemplateReq.newBuilder();
            JsonFormat.parser().merge(deleteOverviewTemplateReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取账号信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        String accountId = account.getFirst();
        String accountName = account.getSecond();

        final Long id = req.getTemplateId();
        OverviewTemplate template = templateRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (template == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        // 逻辑删除
        template.setIsDeleted(true);
        template.setDeletedBy(accountId);
        template.setDeletedAt(TimeUtils.getNowUtc());
        template.setModifiedBy(accountId);
        template.setModifiedByAccountName(accountName);
        template.setModifiedAt(TimeUtils.getNowUtc());
        templateRepo.save(template);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public AddOverviewGroupResp addOverviewGroup(String addOverviewGroupReqJson) {
        final AddOverviewGroupReq req;
        try {
            AddOverviewGroupReq.Builder builder = AddOverviewGroupReq.newBuilder();
            JsonFormat.parser().merge(addOverviewGroupReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddOverviewGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddOverviewGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取请求参数
        OverviewGroupPB groupPB = req.getGroup();
        Long templateId = groupPB.getTemplateId();

        // 查找模板是否存在
        OverviewTemplate template = templateRepo.findByIdAndIsDeletedFalse(templateId).orElse(null);
        if (template == null) {
            return AddOverviewGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_NOT_FOUND))
                .build();
        }

        // 查重
        String groupName = groupPB.getGroupName();
        OverviewGroup existingGroup = groupRepo
            .findByTemplateIdAndGroupNameAndIsDeletedFalse(templateId, groupName)
            .orElse(null);
        if (existingGroup != null) {
            return AddOverviewGroupResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_GROUP_ALREADY_EXISTS))
                .build();
        }

        // 转化成实体
        OverviewGroup group = new OverviewGroup();
        group.setTemplateId(templateId);
        group.setGroupName(groupName);
        group.setIsBalanceGroup(groupPB.getIsBalanceGroup());
        group.setDisplayOrder(groupPB.getDisplayOrder() <= 0 ?
            getGroupNextDisplayOrder(templateId) : groupPB.getDisplayOrder()
        );
        group.setIsDeleted(false);
        group.setModifiedBy(accountId);
        group.setModifiedByAccountName(accountName);
        LocalDateTime now = TimeUtils.getNowUtc();
        group.setModifiedAt(now);

        // Save group
        group = groupRepo.save(group);

        return AddOverviewGroupResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setId(group.getId())
            .build();
    }

    @Transactional
    public GenericResp updateOverviewGroup(String updateOverviewGroupReqJson) {
        final UpdateOverviewGroupReq req;
        try {
            UpdateOverviewGroupReq.Builder builder = UpdateOverviewGroupReq.newBuilder();
            JsonFormat.parser().merge(updateOverviewGroupReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        String accountId = account.getFirst();
        String accountName = account.getSecond();

        OverviewGroupPB groupPB = req.getGroup();
        Long id = groupPB.getId();
        OverviewGroup group = groupRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (group == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_GROUP_NOT_FOUND))
                .build();
        }

        // 查重（如果名称变更）
        if (!group.getGroupName().equals(groupPB.getGroupName())) {
            OverviewGroup dup = groupRepo.findByTemplateIdAndGroupNameAndIsDeletedFalse(
                group.getTemplateId(), groupPB.getGroupName()).orElse(null);
            if (dup != null) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_GROUP_ALREADY_EXISTS))
                    .build();
            }
            group.setGroupName(groupPB.getGroupName());
        }

        // 忽略出入量类型
        // group.setIsBalanceGroup(groupPB.getIsBalanceGroup());
        group.setDisplayOrder(groupPB.getDisplayOrder());
        group.setModifiedBy(accountId);
        group.setModifiedByAccountName(accountName);
        group.setModifiedAt(TimeUtils.getNowUtc());

        groupRepo.save(group);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteOverviewGroup(String deleteOverviewGroupReqJson) {
        final DeleteOverviewGroupReq req;
        try {
            DeleteOverviewGroupReq.Builder builder = DeleteOverviewGroupReq.newBuilder();
            JsonFormat.parser().merge(deleteOverviewGroupReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取账号信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        String accountId = account.getFirst();
        String accountName = account.getSecond();

        final Long id = req.getGroupId();
        OverviewGroup group = groupRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (group == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        // 逻辑删除
        group.setIsDeleted(true);
        group.setDeletedBy(accountId);
        group.setDeletedAt(TimeUtils.getNowUtc());
        group.setModifiedBy(accountId);
        group.setModifiedByAccountName(accountName);
        group.setModifiedAt(TimeUtils.getNowUtc());
        groupRepo.save(group);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GetOverviewParamCodesResp getOverviewParamCodes(String getOverviewParamCodesReqJson) {
        final GetOverviewParamCodesReq req;
        try {
            GetOverviewParamCodesReq.Builder builder = GetOverviewParamCodesReq.newBuilder();
            JsonFormat.parser().merge(getOverviewParamCodesReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetOverviewParamCodesResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        String deptId = req.getDeptId();

        // 查找bga参数
        Set<String> bgaParamCodes = bgaParamRepo.findByDeptIdAndIsDeletedFalseOrderByDisplayOrder(deptId)
            .stream()
            .map(BgaParam::getMonitoringParamCode)
            .collect(Collectors.toSet());

        // 填充bga和monitoring参数
        List<StringIdEntityPB> bgaParams = new ArrayList<>();
        List<StringIdEntityPB> vitalSignalParams = new ArrayList<>();
        List<StringIdEntityPB> balanceParams = new ArrayList<>();
        for (Map.Entry<String, MonitoringParamPB> entry : monitoringConfig.getMonitoringParams(deptId).entrySet()) {
            String paramCode = entry.getKey();
            MonitoringParamPB monParamPB = entry.getValue();
            if (!ValueMetaUtils.isNumber(monParamPB.getValueMeta())) continue;
            String paramName = entry.getValue().getName();
            Boolean isBalance = entry.getValue().getBalanceType() > 0;

            if (bgaParamCodes.contains(paramCode)) {
                bgaParams.add(StringIdEntityPB.newBuilder()
                    .setId(paramCode)
                    .setName(paramName)
                    .build());
            } else if (isBalance) {
                // 前端视图中放不下3个柱子，因此限制paramCode
                if (paramCode.equals("hourly_intake") ||
                    paramCode.equals("hourly_output") ||
                    paramCode.equals("hourly_balance")
                ) {
                    balanceParams.add(StringIdEntityPB.newBuilder()
                        .setId(paramCode)
                        .setName(paramName)
                        .build());
                }
            } else {
                if (vitalSignBlockedParams.contains(paramCode)) continue;
                vitalSignalParams.add(StringIdEntityPB.newBuilder()
                    .setId(paramCode)
                    .setName(paramName)
                    .build());
            }
        }

        List<StringIdEntityPB> lisParams = new ArrayList<>();
        for (ExternalLisParam param : externalLisParamRepo.findAll()) {
            ValueMetaPB valueMetaPB = ProtoUtils.decodeValueMeta(param.getTypePb());
            if (valueMetaPB == null || !ValueMetaUtils.isNumber(valueMetaPB)) continue;

            lisParams.add(StringIdEntityPB.newBuilder()
                .setId(param.getParamCode())
                .setName(param.getParamName())
                .build());
        }

        List<OverviewParamTypePB> paramTypes = new ArrayList<>();
        // paramTypes.add(OverviewParamTypePB.newBuilder()
        //     .setParamTypeId(PARAM_TYPE_BGA.getId())
        //     .setParamTypeName(PARAM_TYPE_BGA.getName())
        //     .setIsBalance(false)
        //     .addAllParam(bgaParams)
        //     .build()
        // );
        // paramTypes.add(OverviewParamTypePB.newBuilder()
        //     .setParamTypeId(PARAM_TYPE_LIS.getId())
        //     .setParamTypeName(PARAM_TYPE_LIS.getName())
        //     .setIsBalance(false)
        //     .addAllParam(lisParams)
        //     .build()
        // );
        paramTypes.add(OverviewParamTypePB.newBuilder()
            .setParamTypeId(PARAM_TYPE_VITAL_SIGNAL.getId())
            .setParamTypeName(PARAM_TYPE_VITAL_SIGNAL.getName())
            .setIsBalance(false)
            .addAllParam(vitalSignalParams)
            .build()
        );
        paramTypes.add(OverviewParamTypePB.newBuilder()
            .setParamTypeId(PARAM_TYPE_BALANCE.getId())
            .setParamTypeName(PARAM_TYPE_BALANCE.getName())
            .setIsBalance(true)
            .addAllParam(balanceParams)
            .build()
        );

        return GetOverviewParamCodesResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .addAllParamType(paramTypes)
            .build();
    }

    @Transactional
    public AddOverviewParamResp addOverviewParam(String addOverviewParamReqJson) {
        final AddOverviewParamReq req;
        try {
            AddOverviewParamReq.Builder builder = AddOverviewParamReq.newBuilder();
            JsonFormat.parser().merge(addOverviewParamReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return AddOverviewParamResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return AddOverviewParamResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();
        final String accountName = account.getSecond();

        // 获取请求参数
        OverviewParamPB paramPB = req.getParam();
        Long groupId = paramPB.getGroupId();

        // 查找组是否存在
        OverviewGroup group = groupRepo.findByIdAndIsDeletedFalse(groupId).orElse(null);
        if (group == null) {
            return AddOverviewParamResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_GROUP_NOT_FOUND))
                .build();
        }

        // Check for duplicate parameter
        String paramName = paramPB.getParamName();
        OverviewParam existingParam = paramRepo
            .findByGroupIdAndParamNameAndIsDeletedFalse(groupId, paramName)
            .orElse(null);
        if (existingParam != null) {
            return AddOverviewParamResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_PARAM_ALREADY_EXISTS))
                .build();
        }

        // Convert to entity
        OverviewParam param = new OverviewParam();
        param.setGroupId(groupId);
        param.setParamName(paramName);
        param.setGraphType(paramPB.getGraphType());
        param.setColor(paramPB.getColor());
        param.setPointIcon(paramPB.getPointIcon());
        param.setParamType(paramPB.getParamType());
        param.setBgaCategoryId(paramPB.getBgaCategoryId());
        param.setDisplayOrder(paramPB.getDisplayOrder() <= 0 ?
            getParamNextDisplayOrder(groupId) : paramPB.getDisplayOrder()
        );

        param.setParamCode(paramPB.getParamCode());
        String deptId = req.getDeptId();
        MonitoringParamPB monParamPB = monitoringConfig.getMonitoringParam(deptId, paramPB.getParamCode());
        ValueMetaPB valueMetaPB = monParamPB != null ? monParamPB.getValueMeta() : null;
        param.setBalanceTypeId(0);
        if (valueMetaPB == null) {
            valueMetaPB = lisService.getExternalLisParamValueMeta(paramPB.getParamCode());
        }
        if (valueMetaPB != null) {
            param.setValueMeta(ProtoUtils.encodeValueMeta(valueMetaPB));
            param.setBalanceTypeId(monParamPB.getBalanceType());
        } else {
            log.error("Failed to get value_meta for param: {}", paramPB.getParamCode());
            return AddOverviewParamResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_PARAM_CODE_META_NOT_FOUND))
                .build();
        }
        if (!ValueMetaUtils.isNumber(valueMetaPB)) {
            log.error("Param {} is not a number type", paramPB.getParamCode());
            return AddOverviewParamResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_PARAM_CODE_NOT_NUMBER))
                .build();
        }

        LocalDateTime now = TimeUtils.getNowUtc();
        param.setIsDeleted(false);
        param.setModifiedBy(accountId);
        param.setModifiedByAccountName(accountName);
        param.setModifiedAt(now);

        // Save parameter
        param = paramRepo.save(param);

        return AddOverviewParamResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setId(param.getId())
            .build();
    }

    @Transactional
    public GenericResp updateOverviewParam(String updateOverviewParamReqJson) {
        final UpdateOverviewParamReq req;
        try {
            UpdateOverviewParamReq.Builder builder = UpdateOverviewParamReq.newBuilder();
            JsonFormat.parser().merge(updateOverviewParamReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        String accountId = account.getFirst();
        String accountName = account.getSecond();

        OverviewParamPB paramPB = req.getParam();
        Long id = paramPB.getId();
        OverviewParam param = paramRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (param == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_PARAM_NOT_FOUND))
                .build();
        }

        // 查重（仅当 paramCode 或 groupId 发生变化）
        if (!param.getGroupId().equals(paramPB.getGroupId()) ||
            !param.getParamName().equals(paramPB.getParamName())
        ) {
            OverviewParam dup = paramRepo.findByGroupIdAndParamNameAndIsDeletedFalse(
                paramPB.getGroupId(), paramPB.getParamName()).orElse(null);
            if (dup != null) {
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_PARAM_ALREADY_EXISTS))
                    .build();
            }
        }

        param.setGroupId(paramPB.getGroupId());
        param.setParamName(paramPB.getParamName());
        param.setGraphType(paramPB.getGraphType());
        param.setColor(paramPB.getColor());
        param.setPointIcon(paramPB.getPointIcon());
        param.setValueMeta(ProtoUtils.encodeValueMeta(paramPB.getValueMeta()));
        param.setParamType(paramPB.getParamType());
        param.setBgaCategoryId(paramPB.getBgaCategoryId());
        param.setParamCode(paramPB.getParamCode());
        param.setDisplayOrder(paramPB.getDisplayOrder());

        param.setModifiedBy(accountId);
        param.setModifiedByAccountName(accountName);
        param.setModifiedAt(TimeUtils.getNowUtc());

        paramRepo.save(param);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GenericResp deleteOverviewParam(String deleteOverviewParamReqJson) {
        final DeleteOverviewParamReq req;
        try {
            DeleteOverviewParamReq.Builder builder = DeleteOverviewParamReq.newBuilder();
            JsonFormat.parser().merge(deleteOverviewParamReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

                // 获取账号信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        String accountId = account.getFirst();
        String accountName = account.getSecond();

        final Long id = req.getParamId();
        OverviewParam param = paramRepo.findByIdAndIsDeletedFalse(id).orElse(null);
        if (param == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
                .build();
        }

        // 逻辑删除
        param.setIsDeleted(true);
        param.setDeletedBy(accountId);
        param.setDeletedAt(TimeUtils.getNowUtc());
        param.setModifiedBy(accountId);
        param.setModifiedByAccountName(accountName);
        param.setModifiedAt(TimeUtils.getNowUtc());
        paramRepo.save(param);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .build();
    }

    @Transactional
    public GetOverviewDataResp getOverviewData(String getOverviewDataReqJson) {
        final GetOverviewDataReq req;
        try {
            GetOverviewDataReq.Builder builder = GetOverviewDataReq.newBuilder();
            JsonFormat.parser().merge(getOverviewDataReqJson, builder);
            req = builder.build();
        } catch (Exception e) {
            log.error("Failed to convert string to proto: ", e, "\n", e.getStackTrace());
            return GetOverviewDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 验证患者ID
        Long pid = req.getPid();
        final PatientRecord patientRecord = patientService.getPatientRecord(pid);
        if (patientRecord == null) {
            log.error("Patient not found.");
            return GetOverviewDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.PATIENT_NOT_FOUND))
                .build();
        }
        String deptId = patientRecord.getDeptId();

        // 查找模板信息
        OverviewTemplate template = templateRepo
            .findByIdAndIsDeletedFalse(req.getTemplateId())
            .orElse(null);
        if (template == null) {
            log.error("Template not found.");
            return GetOverviewDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OVERVIEW_TEMPLATE_NOT_FOUND))
                .build();
        }

        LocalDateTime startTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime endTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (startTime == null || endTime == null) {
            log.error("Invalid time range.");
            return GetOverviewDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.INVALID_TIME_RANGE))
                .build();
        }
        endTime = endTime.minusMinutes(1); // 结束时间前推1分钟

        // 提取观察项模板参数列表
        OverviewTemplatePB templatePB = toOverviewTemplatePB(template);
        List<String> paramCodes = new ArrayList<>();
        for (OverviewGroupPB group : templatePB.getGroupList()) {
            for (OverviewParamPB param : group.getParamList()) {
                paramCodes.add(param.getParamCode());
            }
        }

        // 根据参数列表查询数据
        List<PatientMonitoringRecord> records = pmrRepo
            .findByPidAndParamCodesAndEffectiveTimeRange(pid, paramCodes, startTime, endTime)
            .stream()
            .filter(pmr -> pmr.getEffectiveTime() != null // &&
                // pmr.getEffectiveTime().getMinute() == 0 &&
                // pmr.getEffectiveTime().getSecond() == 0 &&
                // pmr.getEffectiveTime().getNano() == 0
            )
            .sorted(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime))
            .toList();
        Map<String, List<PatientMonitoringRecord>> recordsByParamCode = records.stream()
            .collect(Collectors.groupingBy(PatientMonitoringRecord::getMonitoringParamCode));
        appendHourlyNetIfNeeded(deptId, paramCodes, recordsByParamCode);

        // 根据templatePB模板组织数据
        OverviewDataPB.Builder dataBuilder = OverviewDataPB.newBuilder().setTemplateId(template.getId());
        for (OverviewGroupPB group : templatePB.getGroupList()) {
            OverviewGroupDataPB.Builder groupDataBuilder = OverviewGroupDataPB.newBuilder()
                .setGroupId(group.getId())
                .setGroupName(group.getGroupName())
                .setIsBalanceGroup(group.getIsBalanceGroup());
            for (OverviewParamPB param : group.getParamList()) {
                String paramCode = param.getParamCode();
                ValueMetaPB valueMeta = param.getValueMeta();
                List<PatientMonitoringRecord> paramRecords = recordsByParamCode.getOrDefault(
                    paramCode, Collections.emptyList()
                );

                Long paramId = param.getId();
                List<TimedGenericValuePB> paramValues = new ArrayList<>();
                for (PatientMonitoringRecord record : paramRecords) {
                    MonitoringValuePB monValuePB = ProtoUtils.decodeMonitoringValue(record.getParamValue());
                    if (monValuePB == null) {
                        log.error("Failed to decode param value.");
                        continue;
                    }
                    GenericValuePB valuePB = monValuePB.getValue();
                    String valueStr = ValueMetaUtils.extractAndFormatParamValue(valuePB, valueMeta);
                    valuePB = ValueMetaUtils.formatParamValue(valuePB, valueMeta);
                    String recordedAtIso8601 = TimeUtils.toIso8601String(record.getEffectiveTime(), ZONE_ID);
                    paramValues.add(TimedGenericValuePB.newBuilder()
                        .setValue(valuePB)
                        .setValueStr(valueStr)
                        .setRecordedAtIso8601(recordedAtIso8601)
                        .build());
                }
                OverviewParamDataPB  paramData = OverviewParamDataPB.newBuilder()
                    .setParamId(paramId)
                    .setParam(param)
                    .addAllTimeValue(paramValues)
                    .build();
                groupDataBuilder.addParam(paramData);
            }
            dataBuilder.addGroup(groupDataBuilder.build());
        }

        return GetOverviewDataResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgList, StatusCode.OK))
            .setData(dataBuilder.build())
            .build();
    }

    private Integer getTemplateNextDisplayOrder(String deptId) {
        // return templateRepo.findMaxDisplayOrderByDeptIdAndIsDeletedFalse(deptId).orElse(0) + 1;
        Integer maxDisplayOrder = 0;
        for (OverviewTemplate template : templateRepo.findByDeptIdAndIsDeletedFalse(deptId)) {
            if (template.getDisplayOrder() > maxDisplayOrder) {
                maxDisplayOrder = template.getDisplayOrder();
            }
        }
        return maxDisplayOrder + 1;
    }

    private Integer getGroupNextDisplayOrder(Long templateId) {
        Integer maxDisplayOrder = 0;
        for (OverviewGroup group : groupRepo.findByTemplateIdAndIsDeletedFalse(templateId)) {
            if (group.getDisplayOrder() > maxDisplayOrder) {
                maxDisplayOrder = group.getDisplayOrder();
            }
        }
        return maxDisplayOrder + 1;
    }

    private Integer getParamNextDisplayOrder(Long groupId) {
        Integer maxDisplayOrder = 0;
        for (OverviewParam param : paramRepo.findByGroupIdAndIsDeletedFalse(groupId)) {
            if (param.getDisplayOrder() > maxDisplayOrder) {
                maxDisplayOrder = param.getDisplayOrder();
            }
        }
        return maxDisplayOrder + 1;
    }

    private void appendHourlyNetIfNeeded(
        String deptId, List<String> paramCodes,
        Map<String, List<PatientMonitoringRecord>> recordsByParamCode
    ) {
        // 如果没有小时入量/出量，那么就不用计算补充平衡量
        boolean shouldAppend = false;
        for (String paramCode : paramCodes) {
            if (paramCode.equals(NET_HOURLY_IN_CODE) || paramCode.equals(NET_HOURLY_OUT_CODE)) {
                shouldAppend = true;
                break;
            }
        }
        if (!shouldAppend) return;

        // 提取元数据
        ValueMetaPB inValueMeta = monitoringConfig.getMonitoringParamMeta(deptId, NET_HOURLY_IN_CODE);
        if (inValueMeta == null) return;
        ValueMetaPB outValueMeta = monitoringConfig.getMonitoringParamMeta(deptId, NET_HOURLY_OUT_CODE);
        if (outValueMeta == null) return;
        ValueMetaPB netValueMeta = monitoringConfig.getMonitoringParamMeta(deptId, NET_HOURLY_NET_CODE);
        if (netValueMeta == null) return;

        // 统计净入量
        Map<LocalDateTime, Float> netMap = new HashMap<>();
        List<PatientMonitoringRecord> inRecords = recordsByParamCode.getOrDefault(NET_HOURLY_IN_CODE, Collections.emptyList());
        for (PatientMonitoringRecord record : inRecords) {
            MonitoringValuePB monValuePB = ProtoUtils.decodeMonitoringValue(record.getParamValue());
            if (monValuePB == null) continue;
            GenericValuePB valuePB = monValuePB.getValue();
            float inMl = ValueMetaUtils.convertToFloat(valuePB, inValueMeta);

            LocalDateTime netDateTime = record.getEffectiveTime();
            Float netMl = netMap.computeIfAbsent(netDateTime, k -> 0f);
            netMl += inMl;
            netMap.put(netDateTime, netMl);
        }
        List<PatientMonitoringRecord> outRecords = recordsByParamCode.getOrDefault(NET_HOURLY_OUT_CODE, Collections.emptyList());
        for (PatientMonitoringRecord record : outRecords) {
            MonitoringValuePB monValuePB = ProtoUtils.decodeMonitoringValue(record.getParamValue());
            if (monValuePB == null) continue;
            GenericValuePB valuePB = monValuePB.getValue();
            float outMl = ValueMetaUtils.convertToFloat(valuePB, outValueMeta);

            LocalDateTime netDateTime = record.getEffectiveTime();
            Float netMl = netMap.computeIfAbsent(netDateTime, k -> 0f);
            netMl -= outMl;
            netMap.put(netDateTime, netMl);
        }

        List<PatientMonitoringRecord> netRecords = new ArrayList<>();
        for (Map.Entry<LocalDateTime, Float> entry : netMap.entrySet()) {
            LocalDateTime timestamp = entry.getKey();
            float netMl = entry.getValue() == null ? 0f : entry.getValue();
            PatientMonitoringRecord netRecord = new PatientMonitoringRecord();
            netRecord.setMonitoringParamCode(NET_HOURLY_NET_CODE);
            netRecord.setEffectiveTime(timestamp);
            netRecord.setParamValue(
                ProtoUtils.encodeMonitoringValue(
                    MonitoringValuePB.newBuilder()
                        .setValue(ValueMetaUtils.convertFloatTo(netMl, netValueMeta))
                        .build()
                )
            );
            netRecords.add(netRecord);
        }
        netRecords.sort(Comparator.comparing(PatientMonitoringRecord::getEffectiveTime));
        recordsByParamCode.put(NET_HOURLY_NET_CODE, netRecords);
    }

    private final String ZONE_ID;
    private final EnumValue PARAM_TYPE_BGA;
    private final EnumValue PARAM_TYPE_LIS;
    private final EnumValue PARAM_TYPE_VITAL_SIGNAL;
    private final EnumValue PARAM_TYPE_BALANCE;
    private Set<String> vitalSignBlockedParams;

    private final String NET_HOURLY_IN_CODE;
    private final String NET_HOURLY_OUT_CODE;
    private final String NET_HOURLY_NET_CODE;

    private final List<String> statusCodeMsgList;

    private final ConfigProtoService protoService;
    private final UserService userService;
    private final MonitoringConfig monitoringConfig;
    private final PatientService patientService;
    private final LisService lisService;

    private final OverviewTemplateRepository templateRepo;
    private final OverviewGroupRepository groupRepo;
    private final OverviewParamRepository paramRepo;
    private final BgaParamRepository bgaParamRepo;
    private final ExternalLisParamRepository externalLisParamRepo;
    private final PatientMonitoringRecordRepository pmrRepo;
}