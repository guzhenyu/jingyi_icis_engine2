package com.jingyicare.jingyi_icis_engine.service.reports;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.google.protobuf.TextFormat;
import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisShift.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.reports.*;
import com.jingyicare.jingyi_icis_engine.entity.users.Account;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartmentAccount;
import com.jingyicare.jingyi_icis_engine.repository.reports.*;
import com.jingyicare.jingyi_icis_engine.repository.users.AccountRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentAccountRepository;
import com.jingyicare.jingyi_icis_engine.service.*;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class ReportService {
    public ReportService(
        @Value("${report_root_path}") String reportRootPath,
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftUtils shiftUtils,
        @Autowired UserService userService,
        @Autowired PatientService patientService,
        @Autowired MonitoringService monitoringService,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired MonitoringReportService monitoringReportService,
        @Autowired Ah2ReportService ah2ReportService,
        @Autowired JfkDataService jfkDataService,
        @Autowired RbacDepartmentAccountRepository rbacDepartmentAccountRepo,
        @Autowired AccountRepository accountRepo,
        @Autowired DragableFormTemplateRepository dragableTemplateRepo,
        @Autowired DragableFormRepository dragableFormRepo,
        @Autowired WardReportRepository wardReportRepo
    ) {
        this.REPORT_ROOT = !StrUtils.isBlank(reportRootPath) ? reportRootPath : "./reports/";
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.GROUP_TYPE_BALANCE_ID = protoService.getConfig().getMonitoring()
            .getEnums().getGroupTypeBalance().getId();
        this.MONITORING_REPORT_TEMPLATE_NAME = protoService.getConfig().getMonitoringReport().getTemplateName();

        this.statusCodeMsgs = protoService.getConfig().getText().getStatusCodeMsgList();
        this.nursingRoleIdSet = new HashSet<>(protoService.getConfig().getJfk().getNursingRoleIdsList());
        this.doctorRoleIdSet = new HashSet<>(protoService.getConfig().getJfk().getDoctorRoleIdsList());

        this.protoService = protoService;
        this.shiftUtils = shiftUtils;
        this.userService = userService;
        this.patientService = patientService;
        this.monitoringService = monitoringService;
        this.patientMonitoringService = patientMonitoringService;

        this.monitoringReportService = monitoringReportService;
        this.ah2ReportService = ah2ReportService;
        this.jfkDataService = jfkDataService;

        this.rbacDepartmentAccountRepo = rbacDepartmentAccountRepo;
        this.accountRepo = accountRepo;

        this.dragableTemplateRepo = dragableTemplateRepo;
        this.dragableFormRepo = dragableFormRepo;
        this.wardReportRepo = wardReportRepo;
    }

    @Transactional
    public GetFormTemplatesResp getFormTemplates(String getFormTemplatesReqJson) {
        GetFormTemplatesReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getFormTemplatesReqJson, GetFormTemplatesReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GetFormTemplatesResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        List<DragableFormTemplate> templates = dragableTemplateRepo
            .findByDeptIdAndIsDeletedFalse(req.getDeptId())
            .stream()
            .sorted(Comparator.comparing(DragableFormTemplate::getId))
            .toList();
        List<JfkTemplatePB> templatePbs = new ArrayList<>();
        for (DragableFormTemplate template : templates) {
            JfkTemplatePB templatePb = ProtoUtils.decodeJfkTemplate(template.getTemplatePb());
            if (templatePb != null) {
                templatePb = templatePb.toBuilder()
                    .setId(template.getId()).setDeptId(template.getDeptId()).build();
                templatePbs.add(templatePb);
            }
        }

        return GetFormTemplatesResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .addAllJfkTemplate(templatePbs)
            .build();
    }

    @Transactional
    public SaveFormTemplateResp saveFormTemplate(String saveFormTemplateReqJson) {
        SaveFormTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(saveFormTemplateReqJson, SaveFormTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return SaveFormTemplateResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return SaveFormTemplateResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 提取元数据
        JfkTemplatePB templatePb = req.getJfkTemplate();
        final int templateId = templatePb.getId();
        final String deptId = templatePb.getDeptId();
        final String name = templatePb.getName();

        // 检查同名模板是否已存在
        DragableFormTemplate template = dragableTemplateRepo
            .findByDeptIdAndNameAndIsDeletedFalse(deptId, name).orElse(null);

        // 合法性检查
        if (templateId > 0) {  // 更新已有模板
            if (template == null) {
                return SaveFormTemplateResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.DRAGABLE_FORM_TEMPLATE_NOT_EXISTS))
                    .build();
            }
            if (template.getId() != templateId) {
                return SaveFormTemplateResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.DRAGABLE_FORM_TEMPLATE_ALREADY_EXISTS))
                    .build();
            }
            template.setTemplatePb(ProtoUtils.encodeJfkTemplate(templatePb));
        } else {  // 新增模板
            if (template != null) {
                return SaveFormTemplateResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.DRAGABLE_FORM_TEMPLATE_ALREADY_EXISTS))
                    .build();
            }
            template = DragableFormTemplate.builder()
                .deptId(deptId).name(name)
                .templatePb(ProtoUtils.encodeJfkTemplate(templatePb))
                .isDeleted(false)
                .build();
        }

        // 保存模板
        template = dragableTemplateRepo.save(template);
        log.info("Added form template: {}, by {}", template, accountId);

        return SaveFormTemplateResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .setId(template.getId())
            .build();
    }

    @Transactional
    public GenericResp deleteFormTemplate(String deleteFormTemplateReqJson) {
        DeleteFormTemplateReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteFormTemplateReqJson, DeleteFormTemplateReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        DragableFormTemplate template = dragableTemplateRepo
            .findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (template == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
                .build();
        }

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        template.setIsDeleted(true);
        template.setDeletedBy(accountId);
        template.setDeletedAt(nowUtc);
        dragableTemplateRepo.save(template);
        log.info("Deleted form template: {}, by {}", template, accountId);

        return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
                .build();
    }

    @Transactional
    public GetJfkDataResp getJfkData(String getJfkDataReqJson) {
        GetJfkDataReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getJfkDataReqJson, GetJfkDataReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GetJfkDataResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        List<JfkDataSourcePB> outputList = new ArrayList<>();
        for (JfkDataSourcePB inputDataSource : req.getInputList()) {
            if (inputDataSource.getMetaId().equals("patient_info")) {
                Pair<ReturnCode, JfkDataSourcePB> result = jfkDataService.getPatientInfo(inputDataSource);
                if (result.getFirst().getCode() != 0) {
                    return GetJfkDataResp.newBuilder()
                        .setRt(result.getFirst())
                        .build();
                }
                outputList.add(result.getSecond());
            }
            if (inputDataSource.getMetaId().equals("test_data_source1")) {
                Pair<ReturnCode, JfkDataSourcePB> result = jfkDataService.getTestDataSource1(inputDataSource);
                if (result.getFirst().getCode() != 0) {
                    return GetJfkDataResp.newBuilder()
                        .setRt(result.getFirst())
                        .build();
                }
                outputList.add(result.getSecond());
            }
            if (inputDataSource.getMetaId().equals("rass")) {
                Pair<ReturnCode, JfkDataSourcePB> result = jfkDataService.getRassDataSource(inputDataSource);
                if (result.getFirst().getCode() != 0) {
                    return GetJfkDataResp.newBuilder()
                        .setRt(result.getFirst())
                        .build();
                }
                outputList.add(result.getSecond());
            }
            if (inputDataSource.getMetaId().equals("cpot")) {
                Pair<ReturnCode, JfkDataSourcePB> result = jfkDataService.getCpotDataSource(inputDataSource);
                if (result.getFirst().getCode() != 0) {
                    return GetJfkDataResp.newBuilder()
                        .setRt(result.getFirst())
                        .build();
                }
                outputList.add(result.getSecond());
            }
            if (inputDataSource.getMetaId().equals("ward_report_summary")) {
                Pair<ReturnCode, JfkDataSourcePB> result = jfkDataService.getWardReportSummary(inputDataSource);
                if (result.getFirst().getCode() != 0) {
                    return GetJfkDataResp.newBuilder()
                        .setRt(result.getFirst())
                        .build();
                }
                outputList.add(result.getSecond());
            }
            if (inputDataSource.getMetaId().equals("ward_report_patients")) {
                Pair<ReturnCode, JfkDataSourcePB> result = jfkDataService.getWardReportPatients(inputDataSource);
                if (result.getFirst().getCode() != 0) {
                    return GetJfkDataResp.newBuilder()
                        .setRt(result.getFirst())
                        .build();
                }
                outputList.add(result.getSecond());
            }
        }

        return GetJfkDataResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .addAllOutput(outputList)
            .build();
    }

    @Transactional
    public GetJfkSignPicsResp getJfkSignPics(String getJfkSignPicsReqJson) {
        GetJfkSignPicsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getJfkSignPicsReqJson, GetJfkSignPicsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GetJfkSignPicsResp.newBuilder().build();
        }

        String deptId = req.getDeptId();
        if (StrUtils.isBlank(deptId)) {
            log.error("Department ID is empty.");
            return GetJfkSignPicsResp.newBuilder().build();
        }

        List<StrKeyValPB> nursingSignPics = loadSignPics(deptId, nursingRoleIdSet);
        List<StrKeyValPB> doctorSignPics = loadSignPics(deptId, doctorRoleIdSet);

        return GetJfkSignPicsResp.newBuilder()
            .addAllNursingSignPics(nursingSignPics)
            .addAllDoctorSignPics(doctorSignPics)
            .build();
    }

    private List<StrKeyValPB> loadSignPics(String deptId, Set<Integer> roleIdSet) {
        if (StrUtils.isBlank(deptId) || roleIdSet == null || roleIdSet.isEmpty()) {
            return Collections.emptyList();
        }

        List<RbacDepartmentAccount> deptAccounts = rbacDepartmentAccountRepo.findByIdDeptId(deptId);
        if (deptAccounts.isEmpty()) return Collections.emptyList();

        Set<String> accountIds = new HashSet<>();
        for (RbacDepartmentAccount deptAccount : deptAccounts) {
            if (deptAccount == null) continue;
            Integer primaryRoleId = deptAccount.getPrimaryRoleId();
            if (primaryRoleId == null || !roleIdSet.contains(primaryRoleId)) continue;
            String accountId = deptAccount.getId() == null ? "" : deptAccount.getId().getAccountId();
            if (StrUtils.isBlank(accountId)) continue;
            accountIds.add(accountId);
        }
        if (accountIds.isEmpty()) return Collections.emptyList();

        List<Account> accounts = accountRepo.findByAccountIdInAndIsDeletedFalse(new ArrayList<>(accountIds));
        return accounts.stream()
            .sorted(Comparator.comparing(Account::getAccountId))
            .map(account -> StrKeyValPB.newBuilder()
                .setKey(account.getName() == null ? "" : account.getName())
                .setVal(account.getSignPic() == null ? "" : account.getSignPic())
                .build())
            .toList();
    }

    @Transactional
    public GetPatientFormsResp getPatientForms(String getPatientFormsReqJson) {
        GetPatientFormsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getPatientFormsReqJson, GetPatientFormsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GetPatientFormsResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        LocalDateTime startTime = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        if (startTime == null) startTime = TimeUtils.getLocalTime(1900, 1, 1);
        LocalDateTime endTime = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (endTime == null) endTime = TimeUtils.getLocalTime(9999, 1, 1);

        List<DragableForm> forms = dragableFormRepo
            .findByPidAndDeptIdAndTemplateIdAndDocumentedAtBetweenAndIsDeletedFalse(
                req.getPid(), req.getDeptId(), req.getTemplateId(), startTime, endTime)
            .stream()
            .sorted(Comparator.comparing(DragableForm::getDocumentedAt).reversed())
            .toList();
        List<JfkFormInstancePB> formPbs = new ArrayList<>();
        for (DragableForm form : forms) {
            JfkFormInstancePB formPb = ProtoUtils.decodeJfkFormInstance(form.getFormPb());
            if (formPb != null) formPbs.add(formPb.toBuilder().setId(form.getId()).build());
        }

        return GetPatientFormsResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .addAllForm(formPbs)
            .build();
    }

    @Transactional
    public SaveFormResp saveForm(String saveFormReqJson) {
        SaveFormReq req;
        try {
            req = ProtoUtils.parseJsonToProto(saveFormReqJson, SaveFormReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return SaveFormResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
            .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return SaveFormResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
            .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 提取元数据
        JfkFormInstancePB formPb = req.getForm();
        final long formId = formPb.getId();
        final Long pid = formPb.getPid();
        final String deptId = formPb.getDeptId();
        final Integer templateId = formPb.getTemplateId();
        final LocalDateTime documentedAt = TimeUtils.fromIso8601String(formPb.getCreatedAtIso8601(), "UTC");
        if (documentedAt == null) {
            return SaveFormResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_TIME_FORMAT))
            .build();
        }

        // 检查同记录时间表单是否已存在
        DragableForm dragableForm = dragableFormRepo.findByPidAndTemplateIdAndDocumentedAtAndIsDeletedFalse(
            pid, templateId, documentedAt).orElse(null);

        // 合法性检查
        if (formId > 0) {  // 更新已有表单
            if (dragableForm == null) {
                return SaveFormResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.DRAGABLE_FORM_NOT_EXISTS))
                    .build();
            }
            if (dragableForm.getId() != formId) {
                return SaveFormResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.DRAGABLE_FORM_ALREADY_EXISTS))
                    .build();
            }

            // 更新表单数据
            dragableForm.setFormPb(ProtoUtils.encodeJfkFormInstance(formPb));
            dragableForm.setModifiedBy(accountId);
            dragableForm.setModifiedAt(TimeUtils.getNowUtc());
        } else {  // 新增表单
            if (dragableForm != null) {
                return SaveFormResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.DRAGABLE_FORM_ALREADY_EXISTS))
                    .build();
            }
            dragableForm = DragableForm.builder()
                .pid(pid).deptId(deptId).templateId(templateId)
                .formPb(ProtoUtils.encodeJfkFormInstance(formPb))
                .documentedAt(documentedAt)
                .modifiedBy(accountId).modifiedAt(TimeUtils.getNowUtc())
                .isDeleted(false)
                .build();
        }

        // 保存表单
        dragableForm = dragableFormRepo.save(dragableForm);
        log.info("Saved form: {}, by {}", dragableForm, accountId);

        return SaveFormResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .setId(dragableForm.getId())
            .build();
    }

    @Transactional
    public GenericResp deleteForm(String deleteFormReqJson) {
        DeleteFormReq req;
        try {
            req = ProtoUtils.parseJsonToProto(deleteFormReqJson, DeleteFormReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("accountId is empty.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        DragableForm dragableForm = dragableFormRepo.findByIdAndIsDeletedFalse(req.getId()).orElse(null);
        if (dragableForm == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
                .build();
        }

        LocalDateTime nowUtc = TimeUtils.getNowUtc();
        dragableForm.setIsDeleted(true);
        dragableForm.setDeletedBy(accountId);
        dragableForm.setDeletedAt(nowUtc);
        dragableFormRepo.save(dragableForm);
        log.info("Deleted form: {}, by {}", dragableForm, accountId);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .build();
    }

    @Transactional
    public GetMonitoringReportResp getMonitoringReport(String getMonitoringReportReqJson) {
        GetPatientMonitoringGroupsReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getMonitoringReportReqJson, GetPatientMonitoringGroupsReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse JSON: {}", e.getMessage());
            return GetMonitoringReportResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                    .build();
        }

        // 获取用户信息
        Pair<String, String> account = userService.getAccountWithAutoId();
        if (account == null) {
            return GetMonitoringReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = account.getFirst();

        // 提取输入参数
        final Long pid = req.getPid();
        final String deptId = req.getDeptId();
        final Integer groupType = req.getGroupType();
        LocalDateTime queryStartUtc = TimeUtils.fromIso8601String(req.getQueryStartIso8601(), "UTC");
        LocalDateTime queryEndUtc = TimeUtils.fromIso8601String(req.getQueryEndIso8601(), "UTC");
        if (queryStartUtc == null || queryEndUtc == null) {
            return GetMonitoringReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        // 获取当前病人班次信息
        PatientService.PatientShift patientShift = patientService.getShiftSettings(pid);
        if (patientShift.statusCode != StatusCode.OK) {
            return GetMonitoringReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, patientShift.statusCode))
                .build();
        }

        // 检查报表时间的合法性
        Pair<StatusCode, LocalDateTime> shiftStartPair = getLocalShiftStart(
            req.getQueryStartIso8601(), req.getQueryEndIso8601(), patientShift);
        if (shiftStartPair.getFirst() != StatusCode.OK) {
            return GetMonitoringReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, shiftStartPair.getFirst()))
                .build();
        }
        final LocalDateTime shiftStartTime = shiftStartPair.getSecond();

        // 获取监测组报表数据
        GetPatientMonitoringGroupsResp groupsResp = monitoringService.getPatientMonitoringGroups(getMonitoringReportReqJson);
        if (groupsResp.getRt().getCode() != StatusCode.OK.ordinal()) {
            return GetMonitoringReportResp.newBuilder()
                .setRt(groupsResp.getRt())
                .build();
        }

        GetPatientMonitoringRecordsResp recordsResp = patientMonitoringService.getPatientMonitoringRecords(getMonitoringReportReqJson);
        if (recordsResp.getRt().getCode() != StatusCode.OK.ordinal()) {
            return GetMonitoringReportResp.newBuilder()
                .setRt(recordsResp.getRt())
                .build();
        }

        // 生成报表路径 ./reports/yyyymm/{$pid}_${patient_name}_{$yyyymmdd}_(monitoring|balance)_report.pdf
        final Boolean isBalanceReport = groupType == GROUP_TYPE_BALANCE_ID;
        Pair<StatusCode, Pair<String, String>> reportPaths = getReportPath(
            shiftStartTime, patientShift.patient, isBalanceReport ? "balance" : "monitoring");
        if (reportPaths.getFirst() != StatusCode.OK) {
            return GetMonitoringReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, reportPaths.getFirst()))
                .build();
        }
        final String reportPath = reportPaths.getSecond().getFirst();
        final String urlPath = reportPaths.getSecond().getSecond();

        // 生成报表
        if (!isBalanceReport && MONITORING_REPORT_TEMPLATE_NAME.equals(Consts.REPORT_TEMPLATE_AH2)) {
            GetMonitoringReportResp.Builder respBuilder = GetMonitoringReportResp.newBuilder();
            ReturnCode returnCode = ah2ReportService.drawPdf(
                pid, deptId, queryStartUtc, queryEndUtc, accountId, reportPath
            );
            respBuilder.setRt(returnCode);
            if (returnCode.getCode() == StatusCode.OK.ordinal()) {
                respBuilder.setPdfUrl(urlPath).setRotationDegree(0);
            }
            return respBuilder.build();
        }

        Pair<StatusCode, Integer> reportStatus = monitoringReportService.generateMonitoringReport(
            reportPath, shiftStartTime, isBalanceReport, patientShift.patient, groupsResp, recordsResp);
        if (reportStatus.getFirst() != StatusCode.OK) {
            return GetMonitoringReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, reportStatus.getFirst()))
                .build();
        }

        return GetMonitoringReportResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .setPdfUrl(urlPath)
            .setRotationDegree(reportStatus.getSecond())
            .build();
    }

    @Transactional
    public GetWardReportResp getWardReport(String getWardReportReqJson) {
        final GetWardReportReq req;
        try {
            req = ProtoUtils.parseJsonToProto(getWardReportReqJson, GetWardReportReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse GetWardReportReq JSON: {}", e.getMessage());
            return GetWardReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        final LocalDateTime shiftStartTime = TimeUtils.fromIso8601String(req.getShiftStartIso8601(), "UTC");
        if (shiftStartTime == null) {
            return GetWardReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        final String deptId = String.valueOf(req.getDeptId());
        WardReport wardReport = wardReportRepo
            .findByDeptIdAndShiftStartTimeAndIsDeletedFalse(deptId, shiftStartTime)
            .orElse(null);

        GetWardReportResp.Builder respBuilder = GetWardReportResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .setPdfUrl("");  // TODO: generate pdf_url when ward report PDF generation is supported

        if (wardReport == null) {
            return respBuilder.build();
        }

        WardReportPB wardReportPb = ProtoUtils.decodeWardReport(wardReport.getWardReportPb());
        if (wardReportPb == null) {
            log.error("Failed to decode WardReportPB for dept {} shift {}", deptId, shiftStartTime);
            return GetWardReportResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
                .setExists(false)
                .build();
        }

        respBuilder
            .setExists(true)
            .setDayShiftCount(wardReportPb.getDayShiftCount())
            .setEveningShiftCount(wardReportPb.getEveningShiftCount())
            .setNightShiftCount(wardReportPb.getNightShiftCount())
            .addAllPatientStats(wardReportPb.getPatientStatsList());

        return respBuilder.build();
    }

    @Transactional
    public GenericResp setWardReport(String setWardReportReqJson) {
        final SetWardReportReq req;
        try {
            req = ProtoUtils.parseJsonToProto(setWardReportReqJson, SetWardReportReq.newBuilder());
        } catch (Exception e) {
            log.error("Failed to parse SetWardReportReq JSON: {}", e.getMessage());
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.PARSE_JSON_FAILED))
                .build();
        }

        // 获取用户信息
        final Pair<String, String> accountWithAutoId = userService.getAccountWithAutoId();
        if (accountWithAutoId == null) {
            log.error("Account ID is empty while setting ward report.");
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.ACCOUNT_NOT_FOUND))
                .build();
        }
        final String accountId = accountWithAutoId.getFirst();

        // 提取班次开始时间
        final LocalDateTime shiftStartTime = TimeUtils.fromIso8601String(req.getShiftStartIso8601(), "UTC");
        if (shiftStartTime == null) {
            return GenericResp.newBuilder()
                .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INVALID_TIME_FORMAT))
                .build();
        }

        final String deptId = String.valueOf(req.getDeptId());
        WardReport wardReport = wardReportRepo
            .findByDeptIdAndShiftStartTimeAndIsDeletedFalse(deptId, shiftStartTime)
            .orElse(null);

        final WardReportPB.Builder wardReportBuilder;
        if (wardReport != null) {
            WardReportPB wardReportPb = ProtoUtils.decodeWardReport(wardReport.getWardReportPb());
            if (wardReportPb == null) {
                log.error("Failed to decode WardReportPB while updating dept {} shift {}", deptId, shiftStartTime);
                return GenericResp.newBuilder()
                    .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.INTERNAL_EXCEPTION))
                    .build();
            }
            wardReportBuilder = wardReportPb.toBuilder();
        } else {
            wardReportBuilder = WardReportPB.newBuilder();
        }

        if (req.getSetDayShiftCount()) {
            wardReportBuilder.setDayShiftCount(req.getDayShiftCount());
        }
        if (req.getSetEveningShiftCount()) {
            wardReportBuilder.setEveningShiftCount(req.getEveningShiftCount());
        }
        if (req.getSetNightShiftCount()) {
            wardReportBuilder.setNightShiftCount(req.getNightShiftCount());
        }

        if (req.getSetPatientStats()) {
            wardReportBuilder.clearPatientStats();
            wardReportBuilder.addAllPatientStats(req.getPatientStatsList());
        }

        WardReportPB wardReportPb = wardReportBuilder.build();
        final String wardReportBase64 = ProtoUtils.encodeWardReport(wardReportPb);
        final LocalDateTime nowUtc = TimeUtils.getNowUtc();

        if (wardReport == null) {
            wardReport = WardReport.builder()
                .deptId(deptId)
                .shiftStartTime(shiftStartTime)
                .wardReportPb(wardReportBase64)
                .modifiedAt(nowUtc)
                .modifiedBy(accountId)
                .isDeleted(false)
                .build();
        } else {
            wardReport.setWardReportPb(wardReportBase64);
            wardReport.setModifiedAt(nowUtc);
            wardReport.setModifiedBy(accountId);
        }

        wardReportRepo.save(wardReport);
        log.info("Saved ward report for dept {} shift {}, by {}", deptId, shiftStartTime, accountId);

        return GenericResp.newBuilder()
            .setRt(ReturnCodeUtils.getReturnCode(statusCodeMsgs, StatusCode.OK))
            .build();
    }

    private Pair<StatusCode, LocalDateTime> getLocalShiftStart(
        String startTimeIso8601, String endTimeIso8601,
        PatientService.PatientShift patientShift
    ) {
        final LocalDateTime startTime = TimeUtils.fromIso8601String(startTimeIso8601, "UTC");
        final LocalDateTime endTime = TimeUtils.fromIso8601String(endTimeIso8601, "UTC").minusMinutes(1);
        if (startTime == null || endTime == null) return new Pair<>(StatusCode.INVALID_TIME_FORMAT, null);

        StatusCode statusCode = patientService.validateTimeRange(patientShift.patient, startTime, endTime);
        if (statusCode != StatusCode.OK) return new Pair<>(statusCode, null);

        final ShiftSettingsPB shiftSettings = patientShift.shiftSettings;
        LocalDateTime shiftStartTime = shiftUtils.getShiftStartTime(shiftSettings, startTime, ZONE_ID);
        // if (!shiftStartTime.equals(shiftUtils.getShiftStartTime(shiftSettings, endTime, ZONE_ID)))
        //     return new Pair<>(StatusCode.INVALID_TIME_RANGE, null);

        return new Pair<>(StatusCode.OK, shiftStartTime);
    }

    private Pair<StatusCode, Pair<String, String>> getReportPath(
        LocalDateTime shiftStartTime, PatientRecord patient, String reportName
    ) {
        StringBuilder pathBuilder = new StringBuilder("/")
            .append(TimeUtils.getYearMonth(shiftStartTime)).append("/")
            .append(patient.getId()).append("_")
            .append(patient.getIcuName()).append("_")
            .append(TimeUtils.getYearMonthDay(shiftStartTime)).append("_")
            .append(reportName).append("_report.pdf");
        String relativePath = pathBuilder.toString();

        StringBuilder reportPathBuilder = new StringBuilder(REPORT_ROOT);
        String reportPath = Paths.get(reportPathBuilder.append(relativePath).toString()).normalize().toString();

        StringBuilder urlPathBuilder = new StringBuilder(".");
        String urlPath = Paths.get(urlPathBuilder.append(relativePath).toString()).normalize().toString();

        // 创建必要的目录
        Path reportPathObj = Paths.get(reportPath).normalize();
        try {
            Files.createDirectories(reportPathObj.getParent());
        } catch (IOException e) {
            log.error("Failed to create directories for report path: {}", reportPath, e);
            return new Pair<>(StatusCode.DIRECTORY_CREATION_FAILED, new Pair<>(null, null));
        }

        return new Pair<>(StatusCode.OK, new Pair<>(reportPath, urlPath));
    }

    // for debug purpose
    public JfkFormDebugPB debugForm(Long jfkFormId) {
        // 获取数据信息
        DragableForm dragableForm = dragableFormRepo.findByIdAndIsDeletedFalse(jfkFormId).orElse(null);
        if (dragableForm == null) return JfkFormDebugPB.newBuilder().build();

        JfkFormInstancePB formPb = ProtoUtils.decodeJfkFormInstance(dragableForm.getFormPb());
        if (formPb == null) return JfkFormDebugPB.newBuilder().build();

        // 获取模版信息
        DragableFormTemplate template = dragableTemplateRepo
            .findByIdAndIsDeletedFalse(formPb.getTemplateId()).orElse(null);
        if (template == null) return JfkFormDebugPB.newBuilder().build();

        JfkTemplatePB templatePb = ProtoUtils.decodeJfkTemplate(template.getTemplatePb());
        if (templatePb == null) return JfkFormDebugPB.newBuilder().build();

        // 返回调试元数据
        return JfkFormDebugPB.newBuilder()
            .setTemplate(templatePb)
            .setInstance(formPb)
            .build();
    }

    private final String REPORT_ROOT;
    private final String ZONE_ID;
    private final Integer GROUP_TYPE_BALANCE_ID;
    private final String MONITORING_REPORT_TEMPLATE_NAME;

    private final List<String> statusCodeMsgs;
    private final Set<Integer> nursingRoleIdSet;
    private final Set<Integer> doctorRoleIdSet;

    private final ConfigProtoService protoService;
    private final ConfigShiftUtils shiftUtils;
    private final UserService userService;
    private final PatientService patientService;
    private final MonitoringService monitoringService;
    private final PatientMonitoringService patientMonitoringService;

    private final MonitoringReportService monitoringReportService;
    private final Ah2ReportService ah2ReportService;
    private final JfkDataService jfkDataService;

    private final RbacDepartmentAccountRepository rbacDepartmentAccountRepo;
    private final AccountRepository accountRepo;

    private final DragableFormTemplateRepository dragableTemplateRepo;
    private final DragableFormRepository dragableFormRepo;
    private final WardReportRepository wardReportRepo;
}
