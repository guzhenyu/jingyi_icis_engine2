package com.jingyicare.jingyi_icis_engine.service;

import java.util.*;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisJfk.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisQualityControl.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSettings.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.grpc.*;
import com.jingyicare.jingyi_icis_engine.service.checklists.*;
import com.jingyicare.jingyi_icis_engine.service.doctors.*;
import com.jingyicare.jingyi_icis_engine.service.exturls.*;
import com.jingyicare.jingyi_icis_engine.service.lis.*;
import com.jingyicare.jingyi_icis_engine.service.medications.MedicationService;
import com.jingyicare.jingyi_icis_engine.service.metrics.PrometheusMetricService;
import com.jingyicare.jingyi_icis_engine.service.monitorings.*;
import com.jingyicare.jingyi_icis_engine.service.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.service.nursingrecords.*;
import com.jingyicare.jingyi_icis_engine.service.overviews.*;
import com.jingyicare.jingyi_icis_engine.service.patients.*;
import com.jingyicare.jingyi_icis_engine.service.patientshifts.*;
import com.jingyicare.jingyi_icis_engine.service.reports.*;
import com.jingyicare.jingyi_icis_engine.service.qcs.*;
import com.jingyicare.jingyi_icis_engine.service.scores.ScoreService;
import com.jingyicare.jingyi_icis_engine.service.settings.*;
import com.jingyicare.jingyi_icis_engine.service.shifts.*;
import com.jingyicare.jingyi_icis_engine.service.tubes.*;
import com.jingyicare.jingyi_icis_engine.service.users.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

@Service
@Slf4j
public class WebApiService {
    public WebApiService(
        @Autowired ConfigProtoService protoService,
        @Autowired ConfigShiftService shiftService,
        @Autowired UserService userService,
        @Autowired MenuService menuService,
        @Autowired PatientService patientService,
        @Autowired PatientDeviceService patientDeviceService,
        @Autowired PatientDiagnoseService patientDiagnoseService,
        @Autowired PatientOverviewService patientOverviewService,
        @Autowired DoctorScoreService doctorScoreService,
        @Autowired OverviewService overviewService,
        @Autowired TubeService tubeService,
        @Autowired PatientTubeService patientTubeService,
        @Autowired MonitoringService monitoringService,
        @Autowired MedicationService medicationService,
        @Autowired PatientMonitoringService patientMonitoringService,
        @Autowired BgaService bgaService,
        @Autowired LisService lisService,
        @Autowired ScoreService scoreService,
        @Autowired NursingRecordService nursingRecordService,
        @Autowired PatientCriticalLisHandlingService patientCriticalLisHandlingService,
        @Autowired NursingOrderService nursingOrderService,
        @Autowired PatientShiftService patientShiftService,
        @Autowired ChecklistService checklistService,
        @Autowired ExtUrlService extUrlService,
        @Autowired QualityControlService qualityControlService,
        @Autowired SettingService settingService,
        @Autowired ReportService reportService,
        @Autowired EngineExtClient engineExtClient,
        @Autowired PrometheusMetricService metricService
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.protoService = protoService;
        this.shiftService = shiftService;
        this.userService = userService;
        this.menuService = menuService;
        this.patientService = patientService;
        this.patientDeviceService = patientDeviceService;
        this.patientDiagnoseService = patientDiagnoseService;
        this.doctorScoreService = doctorScoreService;
        this.patientOverviewService = patientOverviewService;
        this.overviewService = overviewService;
        this.tubeService = tubeService;
        this.patientTubeService = patientTubeService;
        this.monitoringService = monitoringService;
        this.medicationService = medicationService;
        this.patientMonitoringService = patientMonitoringService;
        this.bgaService = bgaService;
        this.lisService = lisService;
        this.scoreService = scoreService;
        this.nursingRecordService = nursingRecordService;
        this.patientCriticalLisHandlingService = patientCriticalLisHandlingService;
        this.nursingOrderService = nursingOrderService;
        this.patientShiftService = patientShiftService;
        this.checklistService = checklistService;
        this.extUrlService = extUrlService;
        this.qualityControlService = qualityControlService;
        this.settingService = settingService;

        this.reportService = reportService;
        this.engineExtClient = engineExtClient;

        this.metricService = metricService;
    }

    public GetConfigResp getConfig() {
        GetConfigResp resp = protoService.getWebApiConfig();
        // resp = metricService.recordApiMetrics(resp, GetConfigResp::getRt);
        return resp;
    }

    public GetUsernameResp getUsername() {
        GetUsernameResp resp = userService.getUsername();
        // resp = metricService.recordApiMetrics(resp, GetUsernameResp::getRt);
        return resp;
    }

    public GetUserInfoResp getUserInfo() {
        GetUserInfoResp resp = userService.getUserInfo();
        resp = metricService.recordApiMetrics(resp, GetUserInfoResp::getRt);
        return resp;
    }

    public GetAllDeptNamesResp getAllDeptNames(String getAllDeptNamesReqJson) {
    GetAllDeptNamesResp resp = userService.getAllDeptNames(getAllDeptNamesReqJson);
    resp = metricService.recordApiMetrics(resp, GetAllDeptNamesResp::getRt);
    return resp;
}

    public GetAllDeptsResp getAllDepts(String getAllDeptsReqJson) {
        GetAllDeptsResp resp = userService.getAllDepts(getAllDeptsReqJson);
        resp = metricService.recordApiMetrics(resp, GetAllDeptsResp::getRt);
        return resp;
    }

    public AddDeptResp addDept(String addDeptReqJson) {
        AddDeptResp resp = userService.addDept(addDeptReqJson);
        resp = metricService.recordApiMetrics(resp, AddDeptResp::getRt);
        return resp;
    }

    public GenericResp updateDept(String updateDeptReqJson) {
        GenericResp resp = userService.updateDept(updateDeptReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteDept(String deleteDeptReqJson) {
        GenericResp resp = userService.deleteDept(deleteDeptReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetAllAccountsResp getAllAccounts(String getAllAccountsReqJson) {
        GetAllAccountsResp resp = userService.getAllAccounts(getAllAccountsReqJson);
        resp = metricService.recordApiMetrics(resp, GetAllAccountsResp::getRt);
        return resp;
    }

    public GenericResp addAccount(String addAccountReqJson) {
        GenericResp resp = userService.addAccount(addAccountReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateAccount(String updateAccountReqJson) {
        GenericResp resp = userService.updateAccount(updateAccountReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteAccount(String deleteAccountReqJson) {
        GenericResp resp = userService.deleteAccount(deleteAccountReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp changePassword(String changePasswordReqJson) {
        GenericResp resp = userService.changePassword(changePasswordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp resetPassword(String resetPasswordReqJson) {
        GenericResp resp = userService.resetPassword(resetPasswordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetRolesResp getRoles(String getRolesReqJson) {
    GetRolesResp resp = userService.getRoles(getRolesReqJson);
    resp = metricService.recordApiMetrics(resp, GetRolesResp::getRt);
    return resp;
}

    public GetPermissionsResp getPermissions(String getPermissionsReqJson) {
        GetPermissionsResp resp = userService.getPermissions(getPermissionsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPermissionsResp::getRt);
        return resp;
    }

    public GenericResp addRolePermission(String addRolePermissionReqJson) {
        GenericResp resp = userService.addRolePermission(addRolePermissionReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp revokeRolePermission(String revokeRolePermissionReqJson) {
        GenericResp resp = userService.revokeRolePermission(revokeRolePermissionReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp addAccountPermission(String addAccountPermissionReqJson) {
        GenericResp resp = userService.addAccountPermission(addAccountPermissionReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp revokeAccountPermission(String revokeAccountPermissionReqJson) {
        GenericResp resp = userService.revokeAccountPermission(revokeAccountPermissionReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetDeptMenuConfigResp getDeptMenuConfig(String getDeptMenuConfigReqJson) {
        GetDeptMenuConfigResp resp = menuService.getDeptMenuConfig(getDeptMenuConfigReqJson);
        resp = metricService.recordApiMetrics(resp, GetDeptMenuConfigResp::getRt);
        return resp;
    }

    public GetMenuItemResp getMenuItem(String getMenuItemReqJson) {
        GetMenuItemResp resp = menuService.getMenuItem(getMenuItemReqJson);
        resp = metricService.recordApiMetrics(resp, GetMenuItemResp::getRt);
        return resp;
    }

    public GenericResp addMenuItem(String addMenuItemReqJson) {
        GenericResp resp = menuService.addMenuItem(addMenuItemReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateMenuItemName(String updateMenuItemNameReqJson) {
        GenericResp resp = menuService.updateMenuItemName(updateMenuItemNameReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateMenuItemWithOneForm(String updateMenuItemWithOneFormReqJson) {
        GenericResp resp = menuService.updateMenuItemWithOneForm(updateMenuItemWithOneFormReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteMenuItem(String deleteMenuItemReqJson) {
        GenericResp resp = menuService.deleteMenuItem(deleteMenuItemReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderMenuItems(String reorderMenuItemsReqJson) {
        GenericResp resp = menuService.reorderMenuItems(reorderMenuItemsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp addMenuItemFormTemplate(String addMenuItemFormTemplateReqJson) {
        GenericResp resp = menuService.addMenuItemFormTemplate(addMenuItemFormTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteMenuItemFormTemplate(String deleteMenuItemFormTemplateReqJson) {
        GenericResp resp = menuService.deleteMenuItemFormTemplate(deleteMenuItemFormTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderMenuItemFormTemplates(String reorderMenuItemFormTemplatesReqJson) {
        GenericResp resp = menuService.reorderMenuItemFormTemplates(reorderMenuItemFormTemplatesReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetInlinePatientsV2Resp getInlinePatientsV2(String getInlinePatientsV2ReqJson) {
        GetInlinePatientsV2Resp resp = patientService.getInlinePatientsV2(getInlinePatientsV2ReqJson);
        resp = metricService.recordApiMetrics(resp, GetInlinePatientsV2Resp::getRt);
        return resp;
    }

    public GetDischargedPatientsV2Resp getDischargedPatientsV2(String getDischargedPatientsV2ReqJson) {
        GetDischargedPatientsV2Resp resp = patientService.getDischargedPatientsV2(getDischargedPatientsV2ReqJson);
        resp = metricService.recordApiMetrics(resp, GetDischargedPatientsV2Resp::getRt);
        return resp;
    }

    public GenericResp newPatient(String newPatientReqJson) {
        GenericResp resp = patientService.newPatient(newPatientReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp admitPatient(String admitPatientReqJson) {
        GenericResp resp = patientService.admitPatient(admitPatientReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp dischargePatient(String dischargePatientReqJson) {
        GenericResp resp = patientService.dischargePatient(dischargePatientReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientInfoResp getPatientInfo(Long patientId) {
        GetPatientInfoResp resp = patientService.getPatientInfo(patientId);
        // resp = metricService.recordApiMetrics(resp, GetPatientInfoResp::getRt);
        return resp;
    }

    public GenericResp updatePatientInfo(String updatePatientInfoReqJson) {
        GenericResp resp = patientService.updatePatientInfo(updatePatientInfoReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientInfoV2Resp getPatientInfoV2(String getPatientInfoV2ReqJson) {
        GetPatientInfoV2Resp resp = patientService.getPatientInfoV2(getPatientInfoV2ReqJson);
        // resp = metricService.recordApiMetrics(resp, GetPatientInfoV2Resp::getRt);
        return resp;
    }

    public GenericResp updatePatientInfoV2(String updatePatientInfoV2ReqJson) {
        GenericResp resp = patientService.updatePatientInfoV2(updatePatientInfoV2ReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientSettingsResp getPatientSettings(String getPatientSettingsReqJson) {
        GetPatientSettingsResp resp = patientService.getPatientSettings(getPatientSettingsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientSettingsResp::getRt);
        return resp;
    }

    public GenericResp updatePatientSettings(String updatePatientSettingsReqJson) {
        GenericResp resp = patientService.updatePatientSettings(updatePatientSettingsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetDiagnosisHistoryResp getDiagnosisHistory(String getDiagnosisHistoryReqJson) {
        GetDiagnosisHistoryResp resp = patientService.getDiagnosisHistory(getDiagnosisHistoryReqJson);
        resp = metricService.recordApiMetrics(resp, GetDiagnosisHistoryResp::getRt);
        return resp;
    }

    public AddDiagnosisHistoryResp addDiagnosisHistory(String addDiagnosisHistoryReqJson) {
        AddDiagnosisHistoryResp resp = patientService.addDiagnosisHistory(addDiagnosisHistoryReqJson);
        resp = metricService.recordApiMetrics(resp, AddDiagnosisHistoryResp::getRt);
        return resp;
    }

    public GenericResp updateDiagnosisHistory(String updateDiagnosisHistoryReqJson) {
        GenericResp resp = patientService.updateDiagnosisHistory(updateDiagnosisHistoryReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteDiagnosisHistory(String deleteDiagnosisHistoryReqJson) {
        GenericResp resp = patientService.deleteDiagnosisHistory(deleteDiagnosisHistoryReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public SyncHisPatientResp syncHisPatient(String syncHisPatientReqJson) {
        SyncHisPatientResp resp = patientService.syncHisPatient(syncHisPatientReqJson);
        resp = metricService.recordApiMetrics(resp, SyncHisPatientResp::getRt);
        return resp;
    }

    public GetTubeTypesResp getTubeTypes(String getTubeTypesReqJson) {
        GetTubeTypesResp resp = tubeService.getTubeTypes(getTubeTypesReqJson);
        // resp = metricService.recordApiMetrics(resp, GetTubeTypesResp::getRt);
        return resp;
    }

    public AddTubeTypeResp addTubeType(String addTubeTypeReqJson) {
        AddTubeTypeResp resp = tubeService.addTubeType(addTubeTypeReqJson);
        resp = metricService.recordApiMetrics(resp, AddTubeTypeResp::getRt);
        return resp;
    }

    public GenericResp updateTubeType(String addTubeTypeReqJson) {
        GenericResp resp = tubeService.updateTubeType(addTubeTypeReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp disableTubeType(String disableTubeTypeReqJson) {
        GenericResp resp = tubeService.disableTubeType(disableTubeTypeReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteTubeType(String deleteTubeTypeReqJson) {
        GenericResp resp = tubeService.deleteTubeType(deleteTubeTypeReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public AddTubeTypeAttrResp addTubeTypeAttr(String addTubeTypeAttrReqJson) {
        AddTubeTypeAttrResp resp = tubeService.addTubeTypeAttr(addTubeTypeAttrReqJson);
        resp = metricService.recordApiMetrics(resp, AddTubeTypeAttrResp::getRt);
        return resp;
    }

    public GenericResp updateTubeTypeAttr(String updateTubeTypeAttrReqJson) {
    GenericResp resp = tubeService.updateTubeTypeAttr(updateTubeTypeAttrReqJson);
    resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
    return resp;
}

    public GenericResp deleteTubeTypeAttr(String deleteTubeTypeAttrReqJson) {
        GenericResp resp = tubeService.deleteTubeTypeAttr(deleteTubeTypeAttrReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public AddTubeTypeStatusResp addTubeTypeStatus(String addTubeTypeStatusReqJson) {
        AddTubeTypeStatusResp resp = tubeService.addTubeTypeStatus(addTubeTypeStatusReqJson);
        resp = metricService.recordApiMetrics(resp, AddTubeTypeStatusResp::getRt);
        return resp;
    }

    public GenericResp updateTubeTypeStatus(String updateTubeTypeStatusReqJson) {
        GenericResp resp = tubeService.updateTubeTypeStatus(updateTubeTypeStatusReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteTubeTypeStatus(String deleteTubeTypeStatusReqJson) {
        GenericResp resp = tubeService.deleteTubeTypeStatus(deleteTubeTypeStatusReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp adjustTubeOrder(String adjustTubeOrderReqJson) {
        GenericResp resp = tubeService.adjustTubeOrder(adjustTubeOrderReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientTubeRecordsResp getPatientTubeRecords(String getPatientTubeRecordsReqJson) {
        GetPatientTubeRecordsResp resp = patientTubeService.getPatientTubeRecords(getPatientTubeRecordsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientTubeRecordsResp::getRt);
        return resp;
    }

    public NewPatientTubeResp newPatientTube(String newPatientTubeReqJson) {
        NewPatientTubeResp resp = patientTubeService.newPatientTube(newPatientTubeReqJson);
        resp = metricService.recordApiMetrics(resp, NewPatientTubeResp::getRt);
        return resp;
    }

    public GenericResp removePatientTube(String removePatientTubeReqJson) {
        GenericResp resp = patientTubeService.removePatientTube(removePatientTubeReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deletePatientTube(String deletePatientTubeReqJson) {
        GenericResp resp = patientTubeService.deletePatientTube(deletePatientTubeReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp retainTubeOnDischarge(String retainTubeOnDischargeReqJson) {
        GenericResp resp = patientTubeService.retainTubeOnDischarge(retainTubeOnDischargeReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public NewPatientTubeResp replacePatientTube(String replacePatientTubeReqJson) {
        NewPatientTubeResp resp = patientTubeService.replacePatientTube(replacePatientTubeReqJson);
        resp = metricService.recordApiMetrics(resp, NewPatientTubeResp::getRt);
        return resp;
    }

    public GenericResp updatePatientTubeAttr(String updatePatientTubeAttrReqJson) {
        GenericResp resp = patientTubeService.updatePatientTubeAttr(updatePatientTubeAttrReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientTubeStatusResp getPatientTubeStatus(String getPatientTubeStatusReqJson) {
        GetPatientTubeStatusResp resp = patientTubeService.getPatientTubeStatus(getPatientTubeStatusReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientTubeStatusResp::getRt);
        return resp;
    }

    public GenericResp newPatientTubeStatus(String newPatientTubeStatusReqJson) {
        GenericResp resp = patientTubeService.newPatientTubeStatus(newPatientTubeStatusReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deletePatientTubeStatus(String deletePatientTubeStatusReqJson) {
        GenericResp resp = patientTubeService.deletePatientTubeStatus(deletePatientTubeStatusReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetMonitoringParamsResp getMonitoringParams(String getMonitoringParamsReqJson) {
        GetMonitoringParamsResp resp = monitoringService.getMonitoringParams(getMonitoringParamsReqJson);
        resp = metricService.recordApiMetrics(resp, GetMonitoringParamsResp::getRt);
        return resp;
    }

    public GetMonitoringParamResp getMonitoringParam(String getMonitoringParamReqJson) {
        GetMonitoringParamResp resp = monitoringService.getMonitoringParam(getMonitoringParamReqJson);
        resp = metricService.recordApiMetrics(resp, GetMonitoringParamResp::getRt);
        return resp;
    }

    public GenericResp addMonitoringParam(String addMonitoringParamReqJson) {
        GenericResp resp = monitoringService.addMonitoringParam(addMonitoringParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateMonitoringParam(String addMonitoringParamReqJson) {
        GenericResp resp = monitoringService.updateMonitoringParam(addMonitoringParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteMonitoringParam(String deleteMonitoringParamReqJson) {
        GenericResp resp = monitoringService.deleteMonitoringParam(deleteMonitoringParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateDeptMonitoringParam(String updateDeptMonitoringParamReqJson) {
        GenericResp resp = monitoringService.updateDeptMonitoringParam(updateDeptMonitoringParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetDeptMonitoringGroupsResp getDeptMonitoringGroups(String getDeptMonitoringGroupsReqJson) {
        GetDeptMonitoringGroupsResp resp = monitoringService.getDeptMonitoringGroups(getDeptMonitoringGroupsReqJson);
        resp = metricService.recordApiMetrics(resp, GetDeptMonitoringGroupsResp::getRt);
        return resp;
    }

    public AddDeptMonitoringGroupResp addDeptMonitoringGroup(String addDeptMonitoringGroupReqJson) {
        AddDeptMonitoringGroupResp resp = monitoringService.addDeptMonitoringGroup(addDeptMonitoringGroupReqJson);
        resp = metricService.recordApiMetrics(resp, AddDeptMonitoringGroupResp::getRt);
        return resp;
    }

    public GenericResp updateDeptMonGroupName(String updateDeptMonGroupNameReqJson) {
        GenericResp resp = monitoringService.updateDeptMonGroupName(updateDeptMonGroupNameReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteDeptMonitoringGroup(String deleteDeptMonitoringGroupReqJson) {
        GenericResp resp = monitoringService.deleteDeptMonitoringGroup(deleteDeptMonitoringGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderDeptMonitoringGroup(String reorderDeptMonitoringGroupReqJson) {
        GenericResp resp = monitoringService.reorderDeptMonitoringGroup(reorderDeptMonitoringGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public AddDeptMonitoringGroupParamResp addDeptMonitoringGroupParam(String addDeptMonitoringGroupParamReqJson) {
    AddDeptMonitoringGroupParamResp resp = monitoringService.addDeptMonitoringGroupParam(addDeptMonitoringGroupParamReqJson);
    resp = metricService.recordApiMetrics(resp, AddDeptMonitoringGroupParamResp::getRt);
    return resp;
}

    public GenericResp deleteDeptMonitoringGroupParam(String deleteDeptMonitoringGroupParamReqJson) {
        GenericResp resp = monitoringService.deleteDeptMonitoringGroupParam(deleteDeptMonitoringGroupParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderDeptMonitoringGroupParam(String reorderDeptMonitoringGroupParamReqJson) {
        GenericResp resp = monitoringService.reorderDeptMonitoringGroupParam(reorderDeptMonitoringGroupParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientMonitoringGroupsResp getPatientMonitoringGroups(String getPatientMonitoringGroupsReqJson) {
        GetPatientMonitoringGroupsResp resp = monitoringService.getPatientMonitoringGroups(getPatientMonitoringGroupsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientMonitoringGroupsResp::getRt);
        return resp;
    }

    public GenericResp updatePatientMonitoringGroup(String updatePatientMonitoringGroupReqJson) {
        GenericResp resp = monitoringService.updatePatientMonitoringGroup(updatePatientMonitoringGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp syncPatientMonitoringGroupsFromDept(String syncPatientMonitoringGroupsFromDeptReqJson) {
        GenericResp resp = monitoringService.syncPatientMonitoringGroupsFromDept(syncPatientMonitoringGroupsFromDeptReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientMonitoringRecordsResp getPatientMonitoringRecords(String getPatientMonitoringRecordsReqJson) {
        GetPatientMonitoringRecordsResp resp = patientMonitoringService.getPatientMonitoringRecords(getPatientMonitoringRecordsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientMonitoringRecordsResp::getRt);
        return resp;
    }

    public AddPatientMonitoringRecordResp addPatientMonitoringRecord(String addPatientMonitoringRecordReqJson) {
        AddPatientMonitoringRecordResp resp = patientMonitoringService.addPatientMonitoringRecord(addPatientMonitoringRecordReqJson);
        resp = metricService.recordApiMetrics(resp, AddPatientMonitoringRecordResp::getRt);
        return resp;
    }

    public GenericResp updatePatientMonitoringRecord(String updatePatientMonitoringRecordReqJson) {
        GenericResp resp = patientMonitoringService.updatePatientMonitoringRecord(updatePatientMonitoringRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deletePatientMonitoringRecord(String deletePatientMonitoringRecordReqJson) {
        GenericResp resp = patientMonitoringService.deletePatientMonitoringRecord(deletePatientMonitoringRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public AddPatientMonitoringTimePointsResp addPatientMonitoringTimePoints(String addPatientMonitoringTimePointsReqJson) {
        AddPatientMonitoringTimePointsResp resp = patientMonitoringService.addPatientMonitoringTimePoints(addPatientMonitoringTimePointsReqJson);
        resp = metricService.recordApiMetrics(resp, AddPatientMonitoringTimePointsResp::getRt);
        return resp;
    }

    public GenericResp deletePatientMonitoringTimePoints(String deletePatientMonitoringTimePointsReqJson) {
        GenericResp resp = patientMonitoringService.deletePatientMonitoringTimePoints(deletePatientMonitoringTimePointsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetOrderGroupsResp getOrderGroups(String getOrderGroupsReqJson) {
        GetOrderGroupsResp resp = medicationService.getOrderGroups(getOrderGroupsReqJson);
        resp = metricService.recordApiMetrics(resp, GetOrderGroupsResp::getRt);
        return resp;
    }

    public GetOrderGroupResp getOrderGroup(String getOrderGroupReqJson) {
        GetOrderGroupResp resp = medicationService.getOrderGroup(getOrderGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GetOrderGroupResp::getRt);
        return resp;
    }

    public NewOrderGroupResp newOrderGroup(String newOrderGroupReqJson) {
        NewOrderGroupResp resp = medicationService.newOrderGroup(newOrderGroupReqJson);
        resp = metricService.recordApiMetrics(resp, NewOrderGroupResp::getRt);
        return resp;
    }

    public AddOrderExeActionResp addOrderExeAction(String addOrderExeActionReqJson) {
        AddOrderExeActionResp resp = medicationService.addOrderExeAction(addOrderExeActionReqJson);
        resp = metricService.recordApiMetrics(resp, AddOrderExeActionResp::getRt);
        return resp;
    }

    public DelOrderExeActionResp delOrderExeAction(String delOrderExeActionReqJson) {
        DelOrderExeActionResp resp = medicationService.delOrderExeAction(delOrderExeActionReqJson);
        resp = metricService.recordApiMetrics(resp, DelOrderExeActionResp::getRt);
        return resp;
    }

    public GenericResp updateExeRecord(String updateExeRecordReqJson) {
        GenericResp resp = medicationService.updateExeRecord(updateExeRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp delExeRecord(String delExeRecordReqJson) {
        GenericResp resp = medicationService.delExeRecord(delExeRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetDeletedExeRecordsResp getDeletedExeRecords(String getDeletedExeRecordsReqJson) {
        GetDeletedExeRecordsResp resp = medicationService.getDeletedExeRecords(getDeletedExeRecordsReqJson);
        resp = metricService.recordApiMetrics(resp, GetDeletedExeRecordsResp::getRt);
        return resp;
    }

    public GenericResp revertDeletedExeRecord(String revertDeletedExeRecordReqJson) {
        GenericResp resp = medicationService.revertDeletedExeRecord(revertDeletedExeRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public LookupMedicationResp lookupMedication(String lookupMedicationReqJson) {
        LookupMedicationResp resp = medicationService.lookupMedication(lookupMedicationReqJson);
        resp = metricService.recordApiMetrics(resp, LookupMedicationResp::getRt);
        return resp;
    }

    public LookupMedicationV2Resp lookupMedicationV2(String lookupMedicationReqJson) {
        LookupMedicationV2Resp resp = medicationService.lookupMedicationV2(lookupMedicationReqJson);
        resp = metricService.recordApiMetrics(resp, LookupMedicationV2Resp::getRt);
        return resp;
    }

    public GenericResp addMedication(String addMedicationReqJson) {
        GenericResp resp = medicationService.addMedication(addMedicationReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateMedication(String updateMedicationReqJson) {
        GenericResp resp = medicationService.updateMedication(updateMedicationReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteMedication(String deleteMedicationReqJson) {
        GenericResp resp = medicationService.deleteMedication(deleteMedicationReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public LookupRouteResp lookupRoute(String lookupRouteReqJson) {
        LookupRouteResp resp = medicationService.lookupRoute(lookupRouteReqJson);
        resp = metricService.recordApiMetrics(resp, LookupRouteResp::getRt);
        return resp;
    }

    public AddRouteResp addRoute(String addRouteReqJson) {
        AddRouteResp resp = medicationService.addRoute(addRouteReqJson);
        resp = metricService.recordApiMetrics(resp, AddRouteResp::getRt);
        return resp;
    }

    public GenericResp updateRoute(String updateRouteReqJson) {
        GenericResp resp = medicationService.updateRoute(updateRouteReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteRoute(String deleteRouteReqJson) {
        GenericResp resp = medicationService.deleteRoute(deleteRouteReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public LookupFreqResp lookupFreq(String lookupFreqReqJson) {
        LookupFreqResp resp = medicationService.lookupFreq(lookupFreqReqJson);
        resp = metricService.recordApiMetrics(resp, LookupFreqResp::getRt);
        return resp;
    }

    public LookupFreqV2Resp lookupFreqV2(String lookupFreqReqJson) {
        LookupFreqV2Resp resp = medicationService.lookupFreqV2(lookupFreqReqJson);
        resp = metricService.recordApiMetrics(resp, LookupFreqV2Resp::getRt);
        return resp;
    }

    public AddFreqResp addFreq(String addFreqReqJson) {
        AddFreqResp resp = medicationService.addFreq(addFreqReqJson);
        resp = metricService.recordApiMetrics(resp, AddFreqResp::getRt);
        return resp;
    }

    public GenericResp updateFreq(String updateFreqReqJson) {
        GenericResp resp = medicationService.updateFreq(updateFreqReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteFreq(String deleteFreqReqJson) {
        GenericResp resp = medicationService.deleteFreq(deleteFreqReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetDosageGroupExtResp getDosageGroupExt(String getDosageGroupExtReqJson) {
        GetDosageGroupExtResp resp = medicationService.getDosageGroupExt(getDosageGroupExtReqJson);
        resp = metricService.recordApiMetrics(resp, GetDosageGroupExtResp::getRt);
        return resp;
    }

    public CalcMedRateResp calcMedRate(String calcMedRateReqJson) {
        CalcMedRateResp resp = medicationService.calcMedRate(calcMedRateReqJson);
        resp = metricService.recordApiMetrics(resp, CalcMedRateResp::getRt);
        return resp;
    }

    public GetDeptShiftResp getDeptShift(String getDeptShiftReqJson) {
        GetDeptShiftResp resp = shiftService.getDeptShift(getDeptShiftReqJson);
        // resp = metricService.recordApiMetrics(resp, GetDeptShiftResp::getRt);
        return resp;
    }

    public GenericResp updateDeptShift(String updateDeptShiftReqJson) {
        GenericResp resp = shiftService.updateDeptShift(updateDeptShiftReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetDeptBalanceStatsShiftsResp getDeptBalanceStatsShifts(String getDeptBalanceStatsShiftsReqJson) {
        GetDeptBalanceStatsShiftsResp resp = shiftService.getDeptBalanceStatsShifts(getDeptBalanceStatsShiftsReqJson);
        resp = metricService.recordApiMetrics(resp, GetDeptBalanceStatsShiftsResp::getRt);
        return resp;
    }

    public AddDeptBalanceStatsShiftResp addDeptBalanceStatsShift(String addDeptBalanceStatsShiftReqJson) {
        AddDeptBalanceStatsShiftResp resp = shiftService.addDeptBalanceStatsShift(addDeptBalanceStatsShiftReqJson);
        resp = metricService.recordApiMetrics(resp, AddDeptBalanceStatsShiftResp::getRt);
        return resp;
    }

    public GenericResp updateDeptBalanceStatsShift(String updateDeptBalanceStatsShiftReqJson) {
        GenericResp resp = shiftService.updateDeptBalanceStatsShift(updateDeptBalanceStatsShiftReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteDeptBalanceStatsShift(String deleteDeptBalanceStatsShiftReqJson) {
        GenericResp resp = shiftService.deleteDeptBalanceStatsShift(deleteDeptBalanceStatsShiftReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetScoreGroupMetaResp getScoreGroupMeta(String getScoreGroupMetaReqJson) {
        GetScoreGroupMetaResp resp = scoreService.getScoreGroupMeta(getScoreGroupMetaReqJson);
        resp = metricService.recordApiMetrics(resp, GetScoreGroupMetaResp::getRt);
        return resp;
    }

    public AddDeptScoreGroupResp addDeptScoreGroup(String addDeptScoreGroupReqJson) {
        AddDeptScoreGroupResp resp = scoreService.addDeptScoreGroup(addDeptScoreGroupReqJson);
        resp = metricService.recordApiMetrics(resp, AddDeptScoreGroupResp::getRt);
        return resp;
    }

    public AddDeptScoreGroupsResp addDeptScoreGroups(String addDeptScoreGroupsReqJson) {
        AddDeptScoreGroupsResp resp = scoreService.addDeptScoreGroups(addDeptScoreGroupsReqJson);
        resp = metricService.recordApiMetrics(resp, AddDeptScoreGroupsResp::getRt);
        return resp;
    }

    public GenericResp deleteDeptScoreGroup(String deleteDeptScoreGroupReqJson) {
        GenericResp resp = scoreService.deleteDeptScoreGroup(deleteDeptScoreGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderDeptScoreGroups(String reorderDeptScoreGroupsReqJson) {
        GenericResp resp = scoreService.reorderDeptScoreGroups(reorderDeptScoreGroupsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientScoresResp getPatientScores(String getPatientScoresReqJson) {
        GetPatientScoresResp resp = scoreService.getPatientScores(getPatientScoresReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientScoresResp::getRt);
        return resp;
    }

    public GetPatientScoreResp getPatientScore(String getPatientScoreReqJson) {
        GetPatientScoreResp resp = scoreService.getPatientScore(getPatientScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientScoreResp::getRt);
        return resp;
    }

    public AddPatientScoreResp addPatientScore(String addPatientScoreReqJson) {
        AddPatientScoreResp resp = scoreService.addPatientScore(addPatientScoreReqJson);
        resp = metricService.recordApiMetrics(resp, AddPatientScoreResp::getRt);
        return resp;
    }

    public GenericResp updatePatientScore(String updatePatientScoreReqJson) {
        GenericResp resp = scoreService.updatePatientScore(updatePatientScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public SavePatientScoreResp savePatientScore(String savePatientScoreReqJson) {
        SavePatientScoreResp resp = scoreService.savePatientScore(savePatientScoreReqJson);
        resp = metricService.recordApiMetrics(resp, SavePatientScoreResp::getRt);
        return resp;
    }

    public GenericResp deletePatientScore(String deletePatientScoreReqJson) {
        GenericResp resp = scoreService.deletePatientScore(deletePatientScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public CalcPatientScoreResp calcPatientScore(String calcPatientScoreReqJson) {
        CalcPatientScoreResp resp = scoreService.calcPatientScore(calcPatientScoreReqJson);
        resp = metricService.recordApiMetrics(resp, CalcPatientScoreResp::getRt);
        return resp;
    }

    public GetOneScoreGroupMetaResp getOneScoreGroupMeta(String getOneScoreGroupMetaReqJson) {
        GetOneScoreGroupMetaResp resp = scoreService.getOneScoreGroupMeta(getOneScoreGroupMetaReqJson);
        resp = metricService.recordApiMetrics(resp, GetOneScoreGroupMetaResp::getRt);
        return resp;
    }

    public GenericResp updateScoreGroupMeta(String updateScoreGroupMetaReqJson) {
        GenericResp resp = scoreService.updateScoreGroupMeta(updateScoreGroupMetaReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateScoreItemName(String updateScoreItemNameReqJson) {
        GenericResp resp = scoreService.updateScoreItemName(updateScoreItemNameReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateScoreOptionName(String updateScoreOptionNameReqJson) {
        GenericResp resp = scoreService.updateScoreOptionName(updateScoreOptionNameReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp addScoreGroupMeasureMeta(String addScoreGroupMeasureMetaReqJson) {
        GenericResp resp = scoreService.addScoreGroupMeasureMeta(addScoreGroupMeasureMetaReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateScoreGroupMeasureMeta(String updateScoreGroupMeasureMetaReqJson) {
        GenericResp resp = scoreService.updateScoreGroupMeasureMeta(updateScoreGroupMeasureMetaReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteScoreGroupMeasureMeta(String deleteScoreGroupMeasureMetaReqJson) {
        GenericResp resp = scoreService.deleteScoreGroupMeasureMeta(deleteScoreGroupMeasureMetaReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetNursingRecordTemplatesResp getNursingRecordTemplates(String getNursingRecordTemplatesReqJson) {
        GetNursingRecordTemplatesResp resp = nursingRecordService.getNursingRecordTemplates(getNursingRecordTemplatesReqJson);
        resp = metricService.recordApiMetrics(resp, GetNursingRecordTemplatesResp::getRt);
        return resp;
    }

    public AddNursingRecordTemplateGroupResp addNursingRecordTemplateGroup(String addNursingRecordTemplateGroupReqJson) {
        AddNursingRecordTemplateGroupResp resp = nursingRecordService.addNursingRecordTemplateGroup(addNursingRecordTemplateGroupReqJson);
        resp = metricService.recordApiMetrics(resp, AddNursingRecordTemplateGroupResp::getRt);
        return resp;
    }

    public GenericResp updateNursingRecordTemplateGroups(String updateNursingRecordTemplateGroupsReqJson) {
        GenericResp resp = nursingRecordService.updateNursingRecordTemplateGroups(updateNursingRecordTemplateGroupsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteNursingRecordTemplateGroup(String deleteNursingRecordTemplateGroupReqJson) {
        GenericResp resp = nursingRecordService.deleteNursingRecordTemplateGroup(deleteNursingRecordTemplateGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public AddNursingRecordTemplateResp addNursingRecordTemplate(String addNursingRecordTemplateReqJson) {
        AddNursingRecordTemplateResp resp = nursingRecordService.addNursingRecordTemplate(addNursingRecordTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, AddNursingRecordTemplateResp::getRt);
        return resp;
    }

    public GenericResp updateNursingRecordTemplate(String updateNursingRecordTemplateReqJson) {
        GenericResp resp = nursingRecordService.updateNursingRecordTemplate(updateNursingRecordTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteNursingRecordTemplate(String deleteNursingRecordTemplateReqJson) {
        GenericResp resp = nursingRecordService.deleteNursingRecordTemplate(deleteNursingRecordTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetNursingRecordResp getNursingRecord(String getNursingRecordReqJson) {
        GetNursingRecordResp resp = nursingRecordService.getNursingRecord(getNursingRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GetNursingRecordResp::getRt);
        return resp;
    }

    public AddNursingRecordResp addNursingRecord(String addNursingRecordReqJson) {
        AddNursingRecordResp resp = nursingRecordService.addNursingRecord(addNursingRecordReqJson);
        resp = metricService.recordApiMetrics(resp, AddNursingRecordResp::getRt);
        return resp;
    }

    public GenericResp updateNursingRecord(String updateNursingRecordReqJson) {
        GenericResp resp = nursingRecordService.updateNursingRecord(updateNursingRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteNursingRecord(String deleteNursingRecordReqJson) {
        GenericResp resp = nursingRecordService.deleteNursingRecord(deleteNursingRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientCriticalLisHandlingsResp getPatientCriticalLisHandlings(String getPatientCriticalLisHandlingsReqJson) {
        GetPatientCriticalLisHandlingsResp resp = patientCriticalLisHandlingService.getPatientCriticalLisHandlings(getPatientCriticalLisHandlingsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientCriticalLisHandlingsResp::getRt);
        return resp;
    }

    public SavePatientCriticalLisHandlingResp savePatientCriticalLisHandling(String savePatientCriticalLisHandlingReqJson) {
        SavePatientCriticalLisHandlingResp resp = patientCriticalLisHandlingService.savePatientCriticalLisHandling(savePatientCriticalLisHandlingReqJson);
        resp = metricService.recordApiMetrics(resp, SavePatientCriticalLisHandlingResp::getRt);
        return resp;
    }

    public GenericResp deletePatientCriticalLisHandling(String deletePatientCriticalLisHandlingReqJson) {
        GenericResp resp = patientCriticalLisHandlingService.deletePatientCriticalLisHandling(deletePatientCriticalLisHandlingReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetNursingRecordShortcutsResp getNursingRecordShortcuts(String getNursingRecordShortcutsReqJson) {
        GetNursingRecordShortcutsResp resp = nursingRecordService.getNursingRecordShortcuts(getNursingRecordShortcutsReqJson);
        resp = metricService.recordApiMetrics(resp, GetNursingRecordShortcutsResp::getRt);
        return resp;
    }

    public GetNursingRecordShortcuts2Resp getNursingRecordShortcuts2(String getNursingRecordShortcuts2ReqJson) {
        GetNursingRecordShortcuts2Resp resp = nursingRecordService.getNursingRecordShortcuts2(getNursingRecordShortcuts2ReqJson);
        resp = metricService.recordApiMetrics(resp, GetNursingRecordShortcuts2Resp::getRt);
        return resp;
    }

    public GetNursingOrderTemplatesResp getNursingOrderTemplates(String getNursingOrderTemplatesReqJson) {
        GetNursingOrderTemplatesResp resp = nursingOrderService.getNursingOrderTemplates(getNursingOrderTemplatesReqJson);
        resp = metricService.recordApiMetrics(resp, GetNursingOrderTemplatesResp::getRt);
        return resp;
    }

    public AddNursingOrderTemplateGroupResp addNursingOrderTemplateGroup(String addNursingOrderTemplateGroupReqJson) {
        AddNursingOrderTemplateGroupResp resp = nursingOrderService.addNursingOrderTemplateGroup(addNursingOrderTemplateGroupReqJson);
        resp = metricService.recordApiMetrics(resp, AddNursingOrderTemplateGroupResp::getRt);
        return resp;
    }

    public GenericResp updateNursingOrderTemplateGroup(String updateNursingOrderTemplateGroupReqJson) {
        GenericResp resp = nursingOrderService.updateNursingOrderTemplateGroup(updateNursingOrderTemplateGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteNursingOrderTemplateGroup(String deleteNursingOrderTemplateGroupReqJson) {
        GenericResp resp = nursingOrderService.deleteNursingOrderTemplateGroup(deleteNursingOrderTemplateGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public AddNursingOrderTemplateResp addNursingOrderTemplate(String addNursingOrderTemplateReqJson) {
        AddNursingOrderTemplateResp resp = nursingOrderService.addNursingOrderTemplate(addNursingOrderTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, AddNursingOrderTemplateResp::getRt);
        return resp;
    }

    public GenericResp updateNursingOrderTemplate(String updateNursingOrderTemplateReqJson) {
        GenericResp resp = nursingOrderService.updateNursingOrderTemplate(updateNursingOrderTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteNursingOrderTemplate(String deleteNursingOrderTemplateReqJson) {
    GenericResp resp = nursingOrderService.deleteNursingOrderTemplate(deleteNursingOrderTemplateReqJson);
    resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
    return resp;
}

    public GetNursingOrderDetailsResp getNursingOrderDetails(String getNursingOrderDetailsReqJson) {
        GetNursingOrderDetailsResp resp = nursingOrderService.getNursingOrderDetails(getNursingOrderDetailsReqJson);
        resp = metricService.recordApiMetrics(resp, GetNursingOrderDetailsResp::getRt);
        return resp;
    }

    public GetNursingOrdersResp getNursingOrders(String getNursingOrdersReqJson) {
        GetNursingOrdersResp resp = nursingOrderService.getNursingOrders(getNursingOrdersReqJson);
        resp = metricService.recordApiMetrics(resp, GetNursingOrdersResp::getRt);
        return resp;
    }

    public GetNursingOrderResp getNursingOrder(String getNursingOrderReqJson) {
        GetNursingOrderResp resp = nursingOrderService.getNursingOrder(getNursingOrderReqJson);
        resp = metricService.recordApiMetrics(resp, GetNursingOrderResp::getRt);
        return resp;
    }

    public AddNursingOrdersResp addNursingOrders(String addNursingOrdersReqJson) {
        AddNursingOrdersResp resp = nursingOrderService.addNursingOrders(addNursingOrdersReqJson);
        resp = metricService.recordApiMetrics(resp, AddNursingOrdersResp::getRt);
        return resp;
    }

    public GenericResp stopNursingOrder(String stopNursingOrderReqJson) {
        GenericResp resp = nursingOrderService.stopNursingOrder(stopNursingOrderReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteNursingOrder(String deleteNursingOrderReqJson) {
        GenericResp resp = nursingOrderService.deleteNursingOrder(deleteNursingOrderReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateNursingExeRecord(String updateNursingExeRecordReqJson) {
        GenericResp resp = nursingOrderService.updateNursingExeRecord(updateNursingExeRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteNursingExeRecord(String deleteNursingExeRecordReqJson) {
        GenericResp resp = nursingOrderService.deleteNursingExeRecord(deleteNursingExeRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientShiftRecordsResp getPatientShiftRecords(String getPatientShiftRecordsReqJson) {
        GetPatientShiftRecordsResp resp = patientShiftService.getPatientShiftRecords(getPatientShiftRecordsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientShiftRecordsResp::getRt);
        return resp;
    }

    public PatientShiftRecordExistsResp patientShiftRecordExists(String patientShiftRecordExistsReq) {
        PatientShiftRecordExistsResp resp = patientShiftService.patientShiftRecordExists(patientShiftRecordExistsReq);
        resp = metricService.recordApiMetrics(resp, PatientShiftRecordExistsResp::getRt);
        return resp;
    }

    public AddPatientShiftRecordResp addPatientShiftRecord(String addPatientShiftRecordReqJson) {
        AddPatientShiftRecordResp resp = patientShiftService.addPatientShiftRecord(addPatientShiftRecordReqJson);
        resp = metricService.recordApiMetrics(resp, AddPatientShiftRecordResp::getRt);
        return resp;
    }

    public GenericResp updatePatientShiftRecord(String updatePatientShiftRecordReqJson) {
        GenericResp resp = patientShiftService.updatePatientShiftRecord(updatePatientShiftRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deletePatientShiftRecord(String deletePatientShiftRecordReqJson) {
        GenericResp resp = patientShiftService.deletePatientShiftRecord(deletePatientShiftRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetFormTemplatesResp getFormTemplates(String getFormTemplatesReqJson) {
        GetFormTemplatesResp resp = reportService.getFormTemplates(getFormTemplatesReqJson);
        // resp = metricService.recordApiMetrics(resp, GetFormTemplatesResp::getRt);
        return resp;
    }

    public SaveFormTemplateResp saveFormTemplate(String saveFormTemplateReqJson) {
        SaveFormTemplateResp resp = reportService.saveFormTemplate(saveFormTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, SaveFormTemplateResp::getRt);
        return resp;
    }

    public GenericResp deleteFormTemplate(String deleteFormTemplateReqJson) {
        GenericResp resp = reportService.deleteFormTemplate(deleteFormTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetJfkDataResp getJfkData(String getJfkDataReqJson) {
        GetJfkDataResp resp = reportService.getJfkData(getJfkDataReqJson);
        resp = metricService.recordApiMetrics(resp, GetJfkDataResp::getRt);
        return resp;
    }

    public GetJfkSignPicsResp getJfkSignPics(String getJfkSignPicsReqJson) {
        GetJfkSignPicsResp resp = reportService.getJfkSignPics(getJfkSignPicsReqJson);
        resp = metricService.recordApiMetrics(resp, GetJfkSignPicsResp::getRt);
        return resp;
    }

    public GetPatientFormsResp getPatientForms(String getPatientFormsReqJson) {
        GetPatientFormsResp resp = reportService.getPatientForms(getPatientFormsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientFormsResp::getRt);
        return resp;
    }

    public SaveFormResp saveForm(String saveFormReqJson) {
        SaveFormResp resp = reportService.saveForm(saveFormReqJson);
        resp = metricService.recordApiMetrics(resp, SaveFormResp::getRt);
        return resp;
    }

    public GenericResp deleteForm(String deleteFormReqJson) {
        GenericResp resp = reportService.deleteForm(deleteFormReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetMonitoringReportResp getMonitoringReport(String getMonitoringReportReqJson) {
        GetMonitoringReportResp resp = reportService.getMonitoringReport(getMonitoringReportReqJson);
        resp = metricService.recordApiMetrics(resp, GetMonitoringReportResp::getRt);
        return resp;
    }

    public GetWardReportResp getWardReport(String getWardReportReqJson) {
        GetWardReportResp resp = reportService.getWardReport(getWardReportReqJson);
        resp = metricService.recordApiMetrics(resp, GetWardReportResp::getRt);
        return resp;
    }

    public GenericResp setWardReport(String setWardReportReqJson) {
        GenericResp resp = reportService.setWardReport(setWardReportReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetBedConfigResp getBedConfig(String getBedConfigReqJson) {
        GetBedConfigResp resp = patientDeviceService.getBedConfig(getBedConfigReqJson);
        resp = metricService.recordApiMetrics(resp, GetBedConfigResp::getRt);
        return resp;
    }

    public AddBedConfigResp addBedConfig(String addBedConfigReqJson) {
        AddBedConfigResp resp = patientDeviceService.addBedConfig(addBedConfigReqJson);
        resp = metricService.recordApiMetrics(resp, AddBedConfigResp::getRt);
        return resp;
    }

    public GenericResp updateBedConfig(String updateBedConfigReqJson) {
        GenericResp resp = patientDeviceService.updateBedConfig(updateBedConfigReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteBedConfig(String deleteBedConfigReqJson) {
        GenericResp resp = patientDeviceService.deleteBedConfig(deleteBedConfigReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetBedCountsResp getBedCounts(String getBedCountsReqJson) {
        GetBedCountsResp resp = patientDeviceService.getBedCounts(getBedCountsReqJson);
        resp = metricService.recordApiMetrics(resp, GetBedCountsResp::getRt);
        return resp;
    }

    public GenericResp addBedCount(String addBedCountReqJson) {
        GenericResp resp = patientDeviceService.addBedCount(addBedCountReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteBedCount(String deleteBedCountReqJson) {
        GenericResp resp = patientDeviceService.deleteBedCount(deleteBedCountReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetDeviceInfoResp getDeviceInfo(String getDeviceInfoReqJson) {
        GetDeviceInfoResp resp = patientDeviceService.getDeviceInfo(getDeviceInfoReqJson);
        resp = metricService.recordApiMetrics(resp, GetDeviceInfoResp::getRt);
        return resp;
    }

    public GetDeviceBindingHistoryResp getDeviceBindingHistory(String getDeviceBindingHistoryReqJson) {
        GetDeviceBindingHistoryResp resp = patientDeviceService.getDeviceBindingHistory(getDeviceBindingHistoryReqJson);
        resp = metricService.recordApiMetrics(resp, GetDeviceBindingHistoryResp::getRt);
        return resp;
    }

    public GetPatientDeviceBindingHistoryResp getPatientDeviceBindingHistory(String getPatientDeviceBindingHistoryReqJson) {
        GetPatientDeviceBindingHistoryResp resp = patientDeviceService.getPatientDeviceBindingHistory(getPatientDeviceBindingHistoryReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientDeviceBindingHistoryResp::getRt);
        return resp;
    }

    public AddDeviceInfoResp addDeviceInfo(String addDeviceInfoReqJson) {
        AddDeviceInfoResp resp = patientDeviceService.addDeviceInfo(addDeviceInfoReqJson);
        resp = metricService.recordApiMetrics(resp, AddDeviceInfoResp::getRt);
        return resp;
    }

    public GenericResp updateDeviceInfo(String updateDeviceInfoReqJson) {
        GenericResp resp = patientDeviceService.updateDeviceInfo(updateDeviceInfoReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteDeviceInfo(String deleteDeviceInfoReqJson) {
        GenericResp resp = patientDeviceService.deleteDeviceInfo(deleteDeviceInfoReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public BindPatientDeviceResp bindPatientDevice(String bindPatientDeviceReqJson) {
        BindPatientDeviceResp resp = patientDeviceService.bindPatientDevice(bindPatientDeviceReqJson);
        resp = metricService.recordApiMetrics(resp, BindPatientDeviceResp::getRt);
        return resp;
    }

    public GetDiseaseMetaResp getDiseaseMeta(String getDiseaseMetaReqJson) {
        GetDiseaseMetaResp resp = patientDiagnoseService.getDiseaseMeta(getDiseaseMetaReqJson);
        resp = metricService.recordApiMetrics(resp, GetDiseaseMetaResp::getRt);
        return resp;
    }

    public CalcDiseaseMetricResp calcDiseaseMetric(String calcDiseaseMetricReqJson) {
        CalcDiseaseMetricResp resp = patientDiagnoseService.calcDiseaseMetric(calcDiseaseMetricReqJson);
        resp = metricService.recordApiMetrics(resp, CalcDiseaseMetricResp::getRt);
        return resp;
    }

    public ConfirmDiseaseResp confirmDisease(String confirmDiseaseReqJson) {
        ConfirmDiseaseResp resp = patientDiagnoseService.confirmDisease(confirmDiseaseReqJson);
        resp = metricService.recordApiMetrics(resp, ConfirmDiseaseResp::getRt);
        return resp;
    }

    public GenericResp excludeDisease(String excludeDiseaseReqJson) {
        GenericResp resp = patientDiagnoseService.excludeDisease(excludeDiseaseReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetConfirmedDiseasesResp getConfirmedDiseases(String getConfirmedDiseasesReqJson) {
        GetConfirmedDiseasesResp resp = patientDiagnoseService.getConfirmedDiseases(getConfirmedDiseasesReqJson);
        resp = metricService.recordApiMetrics(resp, GetConfirmedDiseasesResp::getRt);
        return resp;
    }

    public GetExcludedDiseasesResp getExcludedDiseases(String getExcludedDiseasesReqJson) {
        GetExcludedDiseasesResp resp = patientDiagnoseService.getExcludedDiseases(getExcludedDiseasesReqJson);
        resp = metricService.recordApiMetrics(resp, GetExcludedDiseasesResp::getRt);
        return resp;
    }

    public GetDiagnosisDetailsResp getDiagnosisDetails(String getDiagnosisDetailsReqJson) {
        GetDiagnosisDetailsResp resp = patientDiagnoseService.getDiagnosisDetails(getDiagnosisDetailsReqJson);
        resp = metricService.recordApiMetrics(resp, GetDiagnosisDetailsResp::getRt);
        return resp;
    }

    public GetDiseaseItemDetailsResp getDiseaseItemDetails(String getDiseaseItemDetailsReqJson) {
        GetDiseaseItemDetailsResp resp = patientDiagnoseService.getDiseaseItemDetails(getDiseaseItemDetailsReqJson);
        resp = metricService.recordApiMetrics(resp, GetDiseaseItemDetailsResp::getRt);
        return resp;
    }

    public GetVitalDetailsResp getVitalDetails(String getVitalDetailsReqJson) {
        GetVitalDetailsResp resp = patientOverviewService.getVitalDetails(getVitalDetailsReqJson);
        resp = metricService.recordApiMetrics(resp, GetVitalDetailsResp::getRt);
        return resp;
    }

    public GetDailyBalanceResp getDailyBalance(String getDailyBalanceReqJson) {
        GetDailyBalanceResp resp = patientOverviewService.getDailyBalance(getDailyBalanceReqJson);
        resp = metricService.recordApiMetrics(resp, GetDailyBalanceResp::getRt);
        return resp;
    }

    public GenericResp setTargetBalance(String setTargetBalanceReqJson) {
        GenericResp resp = patientOverviewService.setTargetBalance(setTargetBalanceReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetTargetBalancesResp getTargetBalances(String getTargetBalancesReqJson) {
        GetTargetBalancesResp resp = patientOverviewService.getTargetBalances(getTargetBalancesReqJson);
        resp = metricService.recordApiMetrics(resp, GetTargetBalancesResp::getRt);
        return resp;
    }

    public GetMedicationOrderViewsResp getMedicationOrderViews(String getMedicationOrderViewsReqJson) {
        GetMedicationOrderViewsResp resp = patientOverviewService.getMedicationOrderViews(getMedicationOrderViewsReqJson);
        resp = metricService.recordApiMetrics(resp, GetMedicationOrderViewsResp::getRt);
        return resp;
    }

    public GetTubeViewsResp getTubeViews(String getTubeViewsReqJson) {
        GetTubeViewsResp resp = patientOverviewService.getTubeViews(getTubeViewsReqJson);
        resp = metricService.recordApiMetrics(resp, GetTubeViewsResp::getRt);
        return resp;
    }

    public GetDeptDoctorScoreTypesResp getDeptDoctorScoreTypes(String getDeptDoctorScoreTypesReqJson) {
        GetDeptDoctorScoreTypesResp resp = doctorScoreService.getDeptDoctorScoreTypes(getDeptDoctorScoreTypesReqJson);
        resp = metricService.recordApiMetrics(resp, GetDeptDoctorScoreTypesResp::getRt);
        return resp;
    }

    public GenericResp addDeptDoctorScoreTypes(String addDeptDoctorScoreTypesReqJson) {
        GenericResp resp = doctorScoreService.addDeptDoctorScoreTypes(addDeptDoctorScoreTypesReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteDeptDoctorScoreType(String deleteDeptDoctorScoreTypeReqJson) {
        GenericResp resp = doctorScoreService.deleteDeptDoctorScoreType(deleteDeptDoctorScoreTypeReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderDeptDoctorScoreTypes(String reorderDeptDoctorScoreTypesReqJson) {
        GenericResp resp = doctorScoreService.reorderDeptDoctorScoreTypes(reorderDeptDoctorScoreTypesReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetApacheScoresResp getApacheScores(String getApacheScoresReqJson) {
        GetApacheScoresResp resp = doctorScoreService.getApacheScores(getApacheScoresReqJson);
        resp = metricService.recordApiMetrics(resp, GetApacheScoresResp::getRt);
        return resp;
    }

    public GetApacheScoreFactorsResp getApacheScoreFactors(String getApacheScoreFactorsReqJson) {
        GetApacheScoreFactorsResp resp = doctorScoreService.getApacheScoreFactors(getApacheScoreFactorsReqJson);
        resp = metricService.recordApiMetrics(resp, GetApacheScoreFactorsResp::getRt);
        return resp;
    }

    public AddApacheScoreResp addApacheScore(String addApacheScoreReqJson) {
        AddApacheScoreResp resp = doctorScoreService.addApacheScore(addApacheScoreReqJson);
        resp = metricService.recordApiMetrics(resp, AddApacheScoreResp::getRt);
        return resp;
    }

    public GenericResp updateApacheScore(String updateApacheScoreReqJson) {
        GenericResp resp = doctorScoreService.updateApacheScore(updateApacheScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteApacheScore(String deleteApacheScoreReqJson) {
        GenericResp resp = doctorScoreService.deleteApacheScore(deleteApacheScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetApacheFactorDetailsResp getApacheFactorDetails(String getApacheFactorDetailsReqJson) {
        GetApacheFactorDetailsResp resp = doctorScoreService.getApacheFactorDetails(getApacheFactorDetailsReqJson);
        resp = metricService.recordApiMetrics(resp, GetApacheFactorDetailsResp::getRt);
        return resp;
    }

    public GetSofaScoresResp getSofaScores(String getSofaScoresReqJson) {
        GetSofaScoresResp resp = doctorScoreService.getSofaScores(getSofaScoresReqJson);
        resp = metricService.recordApiMetrics(resp, GetSofaScoresResp::getRt);
        return resp;
    }

    public GetSofaScoreFactorsResp getSofaScoreFactors(String getSofaScoreFactorsReqJson) {
        GetSofaScoreFactorsResp resp = doctorScoreService.getSofaScoreFactors(getSofaScoreFactorsReqJson);
        resp = metricService.recordApiMetrics(resp, GetSofaScoreFactorsResp::getRt);
        return resp;
    }

    public AddSofaScoreResp addSofaScore(String addSofaScoreReqJson) {
        AddSofaScoreResp resp = doctorScoreService.addSofaScore(addSofaScoreReqJson);
        resp = metricService.recordApiMetrics(resp, AddSofaScoreResp::getRt);
        return resp;
    }

    public GenericResp updateSofaScore(String updateSofaScoreReqJson) {
        GenericResp resp = doctorScoreService.updateSofaScore(updateSofaScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteSofaScore(String deleteSofaScoreReqJson) {
        GenericResp resp = doctorScoreService.deleteSofaScore(deleteSofaScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetSofaFactorDetailsResp getSofaFactorDetails(String getSofaFactorDetailsReqJson) {
        GetSofaFactorDetailsResp resp = doctorScoreService.getSofaFactorDetails(getSofaFactorDetailsReqJson);
        resp = metricService.recordApiMetrics(resp, GetSofaFactorDetailsResp::getRt);
        return resp;
    }

    public GetVteCapriniScoresResp getVteCapriniScores(String getVteCapriniScoresReqJson) {
        GetVteCapriniScoresResp resp = doctorScoreService.getVteCapriniScores(getVteCapriniScoresReqJson);
        resp = metricService.recordApiMetrics(resp, GetVteCapriniScoresResp::getRt);
        return resp;
    }

    public AddVteCapriniScoreResp addVteCapriniScore(String addVteCapriniScoreReqJson) {
        AddVteCapriniScoreResp resp = doctorScoreService.addVteCapriniScore(addVteCapriniScoreReqJson);
        resp = metricService.recordApiMetrics(resp, AddVteCapriniScoreResp::getRt);
        return resp;
    }

    public GenericResp updateVteCapriniScore(String updateVteCapriniScoreReqJson) {
        GenericResp resp = doctorScoreService.updateVteCapriniScore(updateVteCapriniScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteVteCapriniScore(String deleteVteCapriniScoreReqJson) {
        GenericResp resp = doctorScoreService.deleteVteCapriniScore(deleteVteCapriniScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetVtePaduaScoresResp getVtePaduaScores(String getVtePaduaScoresReqJson) {
        GetVtePaduaScoresResp resp = doctorScoreService.getVtePaduaScores(getVtePaduaScoresReqJson);
        resp = metricService.recordApiMetrics(resp, GetVtePaduaScoresResp::getRt);
        return resp;
    }

    public AddVtePaduaScoreResp addVtePaduaScore(String addVtePaduaScoreReqJson) {
        AddVtePaduaScoreResp resp = doctorScoreService.addVtePaduaScore(addVtePaduaScoreReqJson);
        resp = metricService.recordApiMetrics(resp, AddVtePaduaScoreResp::getRt);
        return resp;
    }

    public GenericResp updateVtePaduaScore(String updateVtePaduaScoreReqJson) {
        GenericResp resp = doctorScoreService.updateVtePaduaScore(updateVtePaduaScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteVtePaduaScore(String deleteVtePaduaScoreReqJson) {
        GenericResp resp = doctorScoreService.deleteVtePaduaScore(deleteVtePaduaScoreReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetBgaParamsResp getBgaParams(String getBgaParamsReqJson) {
        GetBgaParamsResp resp = bgaService.getBgaParams(getBgaParamsReqJson);
        resp = metricService.recordApiMetrics(resp, GetBgaParamsResp::getRt);
        return resp;
    }

    public GenericResp enableBgaParam(String enableBgaParamReqJson) {
        GenericResp resp = bgaService.enableBgaParam(enableBgaParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderBgaParams(String reorderBgaParamsReqJson) {
        GenericResp resp = bgaService.reorderBgaParams(reorderBgaParamsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientBgaRecordsResp getPatientBgaRecords(String getPatientBgaRecordsReqJson) {
        GetPatientBgaRecordsResp resp = bgaService.getPatientBgaRecords(getPatientBgaRecordsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientBgaRecordsResp::getRt);
        return resp;
    }

    public AddPatientBgaRecordResp addPatientBgaRecord(String addPatientBgaRecordReqJson) {
        AddPatientBgaRecordResp resp = bgaService.addPatientBgaRecord(addPatientBgaRecordReqJson);
        resp = metricService.recordApiMetrics(resp, AddPatientBgaRecordResp::getRt);
        return resp;
    }

    public GenericResp updatePatientBgaRecord(String updatePatientBgaRecordReqJson) {
        GenericResp resp = bgaService.updatePatientBgaRecord(updatePatientBgaRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deletePatientBgaRecord(String deletePatientBgaRecordReqJson) {
        GenericResp resp = bgaService.deletePatientBgaRecord(deletePatientBgaRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reviewPatientBgaRecords(String reviewPatientBgaRecordsReqJson) {
        GenericResp resp = bgaService.reviewPatientBgaRecords(reviewPatientBgaRecordsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetExternalLisParamsResp getExternalLisParams(String getExternalLisParamsReqJson) {
        GetExternalLisParamsResp resp = lisService.getExternalLisParams(getExternalLisParamsReqJson);
        resp = metricService.recordApiMetrics(resp, GetExternalLisParamsResp::getRt);
        return resp;
    }

    public GenericResp reorderExternalLisParams(String reorderExternalLisParamsReqJson) {
        GenericResp resp = lisService.reorderExternalLisParams(reorderExternalLisParamsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateExternalLisParam(String updateExternalLisParamReqJson) {
        GenericResp resp = lisService.updateExternalLisParam(updateExternalLisParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteExternalLisParam(String deleteExternalLisParamReqJson) {
        GenericResp resp = lisService.deleteExternalLisParam(deleteExternalLisParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetLisParamsResp getLisParams(String getLisParamsReqJson) {
        GetLisParamsResp resp = lisService.getLisParams(getLisParamsReqJson);
        resp = metricService.recordApiMetrics(resp, GetLisParamsResp::getRt);
        return resp;
    }

    public GenericResp reorderLisParams(String reorderLisParamsReqJson) {
        GenericResp resp = lisService.reorderLisParams(reorderLisParamsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updateLisParam(String updateLisParamReqJson) {
        GenericResp resp = lisService.updateLisParam(updateLisParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientLisResultsResp getPatientLisResults(String getPatientLisResultsReqJson) {
        GetPatientLisResultsResp resp = lisService.getPatientLisResults(getPatientLisResultsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientLisResultsResp::getRt);
        return resp;
    }

    public GenericResp deletePatientLisRecord(String deletePatientLisRecordReqJson) {
        GenericResp resp = lisService.deletePatientLisRecord(deletePatientLisRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetOverviewTemplatesResp getOverviewTemplates(String getOverviewTemplatesReqJson) {
        GetOverviewTemplatesResp resp = overviewService.getOverviewTemplates(getOverviewTemplatesReqJson);
        resp = metricService.recordApiMetrics(resp, GetOverviewTemplatesResp::getRt);
        return resp;
    }

    public GetOverviewTemplateResp getOverviewTemplate(String getOverviewTemplateReqJson) {
        GetOverviewTemplateResp resp = overviewService.getOverviewTemplate(getOverviewTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GetOverviewTemplateResp::getRt);
        return resp;
    }

    public AddOverviewTemplateResp addOverviewTemplate(String addOverviewTemplateReqJson) {
        AddOverviewTemplateResp resp = overviewService.addOverviewTemplate(addOverviewTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, AddOverviewTemplateResp::getRt);
        return resp;
    }

    public GenericResp updateOverviewTemplate(String updateOverviewTemplateReqJson) {
        GenericResp resp = overviewService.updateOverviewTemplate(updateOverviewTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteOverviewTemplate(String deleteOverviewTemplateReqJson) {
        GenericResp resp = overviewService.deleteOverviewTemplate(deleteOverviewTemplateReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public AddOverviewGroupResp addOverviewGroup(String addOverviewGroupReqJson) {
        AddOverviewGroupResp resp = overviewService.addOverviewGroup(addOverviewGroupReqJson);
        resp = metricService.recordApiMetrics(resp, AddOverviewGroupResp::getRt);
        return resp;
    }

    public GenericResp updateOverviewGroup(String updateOverviewGroupReqJson) {
        GenericResp resp = overviewService.updateOverviewGroup(updateOverviewGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteOverviewGroup(String deleteOverviewGroupReqJson) {
        GenericResp resp = overviewService.deleteOverviewGroup(deleteOverviewGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetOverviewParamCodesResp getOverviewParamCodes(String getOverviewParamCodesReqJson) {
        GetOverviewParamCodesResp resp = overviewService.getOverviewParamCodes(getOverviewParamCodesReqJson);
        resp = metricService.recordApiMetrics(resp, GetOverviewParamCodesResp::getRt);
        return resp;
    }

    public AddOverviewParamResp addOverviewParam(String addOverviewParamReqJson) {
        AddOverviewParamResp resp = overviewService.addOverviewParam(addOverviewParamReqJson);
        resp = metricService.recordApiMetrics(resp, AddOverviewParamResp::getRt);
        return resp;
    }

    public GenericResp updateOverviewParam(String updateOverviewParamReqJson) {
        GenericResp resp = overviewService.updateOverviewParam(updateOverviewParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteOverviewParam(String deleteOverviewParamReqJson) {
        GenericResp resp = overviewService.deleteOverviewParam(deleteOverviewParamReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetOverviewDataResp getOverviewData(String getOverviewDataReqJson) {
        GetOverviewDataResp resp = overviewService.getOverviewData(getOverviewDataReqJson);
        resp = metricService.recordApiMetrics(resp, GetOverviewDataResp::getRt);
        return resp;
    }

    public GetDeptChecklistGroupsResp getDeptChecklistGroups(String getDeptChecklistGroupsReqJson) {
        GetDeptChecklistGroupsResp resp = checklistService.getDeptChecklistGroups(getDeptChecklistGroupsReqJson);
        resp = metricService.recordApiMetrics(resp, GetDeptChecklistGroupsResp::getRt);
        return resp;
    }

    public AddDeptChecklistGroupResp addDeptChecklistGroup(String addDeptChecklistGroupReqJson) {
        AddDeptChecklistGroupResp resp = checklistService.addDeptChecklistGroup(addDeptChecklistGroupReqJson);
        resp = metricService.recordApiMetrics(resp, AddDeptChecklistGroupResp::getRt);
        return resp;
    }

    public GenericResp deleteDeptChecklistGroup(String deleteDeptChecklistGroupReqJson) {
        GenericResp resp = checklistService.deleteDeptChecklistGroup(deleteDeptChecklistGroupReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderDeptChecklistGroups(String reorderDeptChecklistGroupsReqJson) {
        GenericResp resp = checklistService.reorderDeptChecklistGroups(reorderDeptChecklistGroupsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public AddDeptChecklistItemResp addDeptChecklistItem(String addDeptChecklistItemReqJson) {
        AddDeptChecklistItemResp resp = checklistService.addDeptChecklistItem(addDeptChecklistItemReqJson);
        resp = metricService.recordApiMetrics(resp, AddDeptChecklistItemResp::getRt);
        return resp;
    }

    public GenericResp deleteDeptChecklistItem(String deleteDeptChecklistItemReqJson) {
        GenericResp resp = checklistService.deleteDeptChecklistItem(deleteDeptChecklistItemReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp reorderDeptChecklistItems(String reorderDeptChecklistItemsReqJson) {
        GenericResp resp = checklistService.reorderDeptChecklistItems(reorderDeptChecklistItemsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetPatientChecklistRecordsResp getPatientChecklistRecords(String getPatientChecklistRecordsReqJson) {
        GetPatientChecklistRecordsResp resp = checklistService.getPatientChecklistRecords(getPatientChecklistRecordsReqJson);
        resp = metricService.recordApiMetrics(resp, GetPatientChecklistRecordsResp::getRt);
        return resp;
    }

    public AddPatientChecklistRecordResp addPatientChecklistRecord(String addPatientChecklistRecordReqJson) {
        AddPatientChecklistRecordResp resp = checklistService.addPatientChecklistRecord(addPatientChecklistRecordReqJson);
        resp = metricService.recordApiMetrics(resp, AddPatientChecklistRecordResp::getRt);
        return resp;
    }

    public GenericResp deletePatientChecklistRecord(String deletePatientChecklistRecordReqJson) {
        GenericResp resp = checklistService.deletePatientChecklistRecord(deletePatientChecklistRecordReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp updatePatientChecklistItem(String updatePatientChecklistItemReqJson) {
        GenericResp resp = checklistService.updatePatientChecklistItem(updatePatientChecklistItemReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GetAllExtUrlsResp getAllExtUrls(String getAllExtUrlsReqJson) {
        GetAllExtUrlsResp resp = extUrlService.getAllExtUrls(getAllExtUrlsReqJson);
        resp = metricService.recordApiMetrics(resp, GetAllExtUrlsResp::getRt);
        return resp;
    }

    public GenericResp saveExtUrl(String saveExtUrlReqJson) {
        GenericResp resp = extUrlService.saveExtUrl(saveExtUrlReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public GenericResp deleteExtUrl(String deleteExtUrlReqJson) {
        GenericResp resp = extUrlService.deleteExtUrl(deleteExtUrlReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public ComposeExtUrlResp composeExtUrl(String composeExtUrlReqJson) {
        ComposeExtUrlResp resp = extUrlService.composeExtUrl(composeExtUrlReqJson);
        resp = metricService.recordApiMetrics(resp, ComposeExtUrlResp::getRt);
        return resp;
    }

    public GetNHCQCItemsResp getNHCQCItems(String getNHCQCItemsReqJson) {
        GetNHCQCItemsResp resp = qualityControlService.getNHCQCItems(getNHCQCItemsReqJson);
        resp = metricService.recordApiMetrics(resp, GetNHCQCItemsResp::getRt);
        return resp;
    }

    public GetNHCQCDataResp getNHCQCData(String getNHCQCDataReqJson) {
        GetNHCQCDataResp resp = qualityControlService.getNHCQCData(getNHCQCDataReqJson);
        resp = metricService.recordApiMetrics(resp, GetNHCQCDataResp::getRt);
        return resp;
    }

    public GetAppSettingsResp getAppSettings(String getAppSettingsReqJson) {
        GetAppSettingsResp resp = settingService.getAppSettings(getAppSettingsReqJson);
        resp = metricService.recordApiMetrics(resp, GetAppSettingsResp::getRt);
        return resp;
    }

    public GenericResp updateAppSettings(String updateAppSettingsReqJson) {
        GenericResp resp = settingService.updateAppSettings(updateAppSettingsReqJson);
        resp = metricService.recordApiMetrics(resp, GenericResp::getRt);
        return resp;
    }

    public JfkFormDebugPB debugForm(Long jfkFormId) {
        return  reportService.debugForm(jfkFormId);
    }

    public GenericResp test() {
        // com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.SyncHisPatientReq req = com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.SyncHisPatientReq.newBuilder()
        //     .setForceSync(false)
        //     .build();
        GetNursingRecordShortcuts2Req req = GetNursingRecordShortcuts2Req.newBuilder()
            .setPid(1)
            .setShiftStartIso8601("2025-12-23T00:00:00+00:00")
            .build();
        String reqJson = ProtoUtils.protoToJson(req);
        System.out.println("\n\n\n\n\n\n" +
            reqJson + "\n\n" +
            ProtoUtils.protoToJson(getNursingRecordShortcuts2(reqJson))
        );

        // String reqJson = "{\"pid\":\"1\",\"dosageGroup\":{\"md\":[{\"code\":\"单药物1-长嘱-一日2次-点滴\",\"name\":\"单药物1-长嘱-一日2次-点滴\",\"spec\":\"100ml/袋\",\"dose\":1000,\"doseUnit\":\"ml\",\"intakeVolMl\":1000},{\"code\":\"med006\",\"name\":\"广谱抗菌药1\",\"spec\":\"2mg/粒\",\"dose\":11,\"doseUnit\":\"mg\",\"nameInitials\":\"gpkjy1\"},{\"code\":\"单药物1-长嘱-一日2次-点滴\",\"name\":\"单药物1-长嘱-一日2次-点滴\",\"spec\":\"100ml/袋\",\"dose\":1000,\"doseUnit\":\"ml\",\"intakeVolMl\":1000,\"nameInitials\":\"dyw1-zz-yr2c-dd\"}],\"displayName\":\"单药物1-长嘱-一日2次-点滴\"}}";
        // System.out.println("\n\n\n\n\n\n\n\n\n\n" + ProtoUtils.protoToJson(medicationService.getDosageGroupExt(reqJson)) + "\n\n");

        // QcTablePB tbl = QcTablePB.newBuilder()
        //     .setTitle("test title")
        //     .addRow(QcRowPB.newBuilder().putData("name", "张三").putData("age", "30").build())
        //     .addRow(QcRowPB.newBuilder().putData("name", "李四").putData("age", "25").build())
        //     .build();
        // System.out.println("\n\n\n\n\n\n\n\n\n\n" + ProtoUtils.protoToJson(tbl) + "\n\n");

        return GenericResp.newBuilder().setRt(ReturnCode.newBuilder().setCode(0).build()).build();
    }

    private String ZONE_ID;

    private ConfigProtoService protoService;
    private ConfigShiftService shiftService;
    private UserService userService;
    private MenuService menuService;
    private PatientService patientService;
    private PatientDeviceService patientDeviceService;
    private PatientDiagnoseService patientDiagnoseService;
    private PatientOverviewService patientOverviewService;
    private DoctorScoreService doctorScoreService;
    private OverviewService overviewService;
    private TubeService tubeService;
    private PatientTubeService patientTubeService;
    private MonitoringService monitoringService;
    private MedicationService medicationService;
    private PatientMonitoringService patientMonitoringService;
    private BgaService bgaService;
    private LisService lisService;
    private ScoreService scoreService;
    private NursingRecordService nursingRecordService;
    private PatientCriticalLisHandlingService patientCriticalLisHandlingService;
    private NursingOrderService nursingOrderService;
    private PatientShiftService patientShiftService;
    private ChecklistService checklistService;
    private ExtUrlService extUrlService;
    private QualityControlService qualityControlService;
    private SettingService settingService;

    private ReportService reportService;
    private EngineExtClient engineExtClient;

    private PrometheusMetricService metricService;
}
