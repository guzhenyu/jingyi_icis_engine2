package com.jingyicare.jingyi_icis_engine.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.jingyicare.jingyi_icis_engine.service.WebApiService;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@RestController
@RequestMapping("/api")
public class IcisController {
    public IcisController(@Autowired WebApiService webApiService) {
        this.webApiService = webApiService;
    }

    @GetMapping("/config/getconfig")
    public ResponseEntity<String> getConfig() {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getConfig()));
    }

    @PostMapping("/config/getdeptshift")
    public ResponseEntity<String> getDeptShift(@RequestBody String getDeptShiftReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeptShift(getDeptShiftReqJson)));
    }

    // TODO(guzhenyu): 优化前端，删除这个重复函数
    @PostMapping("/config/getdeptshiftwoutsideeffect")
    public ResponseEntity<String> getDeptShiftWoutSideEffect(@RequestBody String getDeptShiftReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeptShift(getDeptShiftReqJson)));
    }

    @PostMapping("/config/updatedeptshift")
    public ResponseEntity<String> updateDeptShift(@RequestBody String updateDeptShiftReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateDeptShift(updateDeptShiftReqJson)));
    }

    @PostMapping("/config/getdeptbalancestatsshifts")
    public ResponseEntity<String> getDeptBalanceStatsShifts(@RequestBody String getDeptBalanceStatsShiftsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeptBalanceStatsShifts(getDeptBalanceStatsShiftsReqJson)));
    }

    @PostMapping("/config/adddeptbalancestatsshift")
    public ResponseEntity<String> addDeptBalanceStatsShift(@RequestBody String addDeptBalanceStatsShiftReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeptBalanceStatsShift(addDeptBalanceStatsShiftReqJson)));
    }

    @PostMapping("/config/updatedeptbalancestatsshift")
    public ResponseEntity<String> updateDeptBalanceStatsShift(@RequestBody String updateDeptBalanceStatsShiftReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateDeptBalanceStatsShift(updateDeptBalanceStatsShiftReqJson)));
    }

    @PostMapping("/config/deletedeptbalancestatsshift")
    public ResponseEntity<String> deleteDeptBalanceStatsShift(@RequestBody String deleteDeptBalanceStatsShiftReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDeptBalanceStatsShift(deleteDeptBalanceStatsShiftReqJson)));
    }

    @GetMapping("/user/getusername")
    public ResponseEntity<String> getUsername() {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getUsername()));
    }

    @GetMapping("/user/getuserinfo")
    public ResponseEntity<String> getUserInfo() {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getUserInfo()));
    }

    @PostMapping("/user/getalldeptnames")
    public ResponseEntity<String> getAllDeptNames(@RequestBody String getAllDeptNamesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getAllDeptNames(getAllDeptNamesReqJson)));
    }

    @PostMapping("/user/getalldepts")
    public ResponseEntity<String> getAllDepts(@RequestBody String getAllDeptsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getAllDepts(getAllDeptsReqJson)));
    }

    @PostMapping("/user/adddept")
    public ResponseEntity<String> addDept(@RequestBody String addDeptReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDept(addDeptReqJson)));
    }

    @PostMapping("/user/updatedept")
    public ResponseEntity<String> updateDept(@RequestBody String updateDeptReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateDept(updateDeptReqJson)));
    }

    @PostMapping("/user/deletedept")
    public ResponseEntity<String> deleteDept(@RequestBody String deleteDeptReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDept(deleteDeptReqJson)));
    }

    @PostMapping("/user/getallaccounts")
    public ResponseEntity<String> getAllAccounts(@RequestBody String getAllAccountsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getAllAccounts(getAllAccountsReqJson)));
    }

    @PostMapping("/user/addaccount")
    public ResponseEntity<String> addAccount(@RequestBody String addAccountReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addAccount(addAccountReqJson)));
    }

    @PostMapping("/user/updateaccount")
    public ResponseEntity<String> updateAccount(@RequestBody String updateAccountReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateAccount(updateAccountReqJson)));
    }

    @PostMapping("/user/deleteaccount")
    public ResponseEntity<String> deleteAccount(@RequestBody String deleteAccountReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteAccount(deleteAccountReqJson)));
    }

    @PostMapping("/user/changepassword")
    public ResponseEntity<String> changePassword(@RequestBody String changePasswordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.changePassword(changePasswordReqJson)));
    }

    @PostMapping("/user/resetpassword")
    public ResponseEntity<String> resetPassword(@RequestBody String resetPasswordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.resetPassword(resetPasswordReqJson)));
    }

    @PostMapping("/user/getroles")
    public ResponseEntity<String> getRoles(@RequestBody String getRolesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getRoles(getRolesReqJson)));
    }

    @PostMapping("/user/getpermissions")
    public ResponseEntity<String> getPermissions(@RequestBody String getPermissionsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPermissions(getPermissionsReqJson)));
    }

    @PostMapping("/user/addrolepermission")
    public ResponseEntity<String> addRolePermission(@RequestBody String addRolePermissionReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addRolePermission(addRolePermissionReqJson)));
    }

    @PostMapping("/user/revokerolepermission")
    public ResponseEntity<String> revokeRolePermission(@RequestBody String revokeRolePermissionReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.revokeRolePermission(revokeRolePermissionReqJson)));
    }

    @PostMapping("/user/addaccountpermission")
    public ResponseEntity<String> addAccountPermission(@RequestBody String addAccountPermissionReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addAccountPermission(addAccountPermissionReqJson)));
    }

    @PostMapping("/user/revokeaccountpermission")
    public ResponseEntity<String> revokeAccountPermission(@RequestBody String revokeAccountPermissionReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.revokeAccountPermission(revokeAccountPermissionReqJson)));
    }

    @PostMapping("/user/getdeptmenuconfig")
    public ResponseEntity<String> getDeptMenuConfig(@RequestBody String getDeptMenuConfigReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeptMenuConfig(getDeptMenuConfigReqJson)));
    }

    @PostMapping("/user/getmenuitem")
    public ResponseEntity<String> getMenuItem(@RequestBody String getMenuItemReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getMenuItem(getMenuItemReqJson)));
    }

    @PostMapping("/user/addmenuitem")
    public ResponseEntity<String> addMenuItem(@RequestBody String addMenuItemReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addMenuItem(addMenuItemReqJson)));
    }

    @PostMapping("/user/updatemenuitemname")
    public ResponseEntity<String> updateMenuItemName(@RequestBody String updateMenuItemNameReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateMenuItemName(updateMenuItemNameReqJson)));
    }

    @PostMapping("/user/updatemenuitemwithoneform")
    public ResponseEntity<String> updateMenuItemWithOneForm(@RequestBody String updateMenuItemWithOneFormReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateMenuItemWithOneForm(updateMenuItemWithOneFormReqJson)));
    }

    @PostMapping("/user/deletemenuitem")
    public ResponseEntity<String> deleteMenuItem(@RequestBody String deleteMenuItemReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteMenuItem(deleteMenuItemReqJson)));
    }

    @PostMapping("/user/reordermenuitems")
    public ResponseEntity<String> reorderMenuItems(@RequestBody String reorderMenuItemsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderMenuItems(reorderMenuItemsReqJson)));
    }

    @PostMapping("/user/addmenuitemformtemplate")
    public ResponseEntity<String> addMenuItemFormTemplate(@RequestBody String addMenuItemFormTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addMenuItemFormTemplate(addMenuItemFormTemplateReqJson)));
    }

    @PostMapping("/user/deletemenuitemformtemplate")
    public ResponseEntity<String> deleteMenuItemFormTemplate(@RequestBody String deleteMenuItemFormTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteMenuItemFormTemplate(deleteMenuItemFormTemplateReqJson)));
    }

    @PostMapping("/user/reordermenuitemformtemplates")
    public ResponseEntity<String> reorderMenuItemFormTemplates(@RequestBody String reorderMenuItemFormTemplatesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderMenuItemFormTemplates(reorderMenuItemFormTemplatesReqJson)));
    }

    @PostMapping("/patient/getinlinepatientsv2")
    public ResponseEntity<String> getInlinePatientsV2(@RequestBody String getInlinePatientsV2ReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getInlinePatientsV2(getInlinePatientsV2ReqJson)));
    }

    @PostMapping("/patient/getdischargedpatientsv2")
    public ResponseEntity<String> getDischargedPatientsV2(@RequestBody String getDischargedPatientsV2ReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDischargedPatientsV2(getDischargedPatientsV2ReqJson)));
    }

    @PostMapping("/patient/new")
    public ResponseEntity<String> newPatient(@RequestBody String newPatientReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.newPatient(newPatientReqJson)));
    }

    @PostMapping("/patient/admit")
    public ResponseEntity<String> admitPatient(@RequestBody String admitPatientReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.admitPatient(admitPatientReqJson)));
    }

    @PostMapping("/patient/discharge")
    public ResponseEntity<String> dischargePatient(@RequestBody String dischargePatientReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.dischargePatient(dischargePatientReqJson)));
    }

    @GetMapping("/patient/getpatientinfo")
    public ResponseEntity<String> getPatientInfo(@RequestParam("id") Long patientId) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientInfo(patientId)));
    }

    @PostMapping("/patient/updatepatientinfo")
    public ResponseEntity<String> updatePatientInfo(@RequestBody String updatePatientInfoReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientInfo(updatePatientInfoReqJson)));
    }

    @PostMapping("/patient/getpatientinfov2")
    public ResponseEntity<String> getPatientInfoV2(@RequestBody String getPatientInfoV2ReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientInfoV2(getPatientInfoV2ReqJson)));
    }

    @PostMapping("/patient/updatepatientinfov2")
    public ResponseEntity<String> updatePatientInfoV2(@RequestBody String updatePatientInfoV2ReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientInfoV2(updatePatientInfoV2ReqJson)));
    }

    @PostMapping("/patient/getdiagnosishistory")
    public ResponseEntity<String> getDiagnosisHistory(@RequestBody String getDiagnosisHistoryReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDiagnosisHistory(getDiagnosisHistoryReqJson)));
    }

    @PostMapping("/patient/adddiagnosishistory")
    public ResponseEntity<String> addDiagnosisHistory(@RequestBody String addDiagnosisHistoryReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDiagnosisHistory(addDiagnosisHistoryReqJson)));
    }

    @PostMapping("/patient/updatediagnosishistory")
    public ResponseEntity<String> updateDiagnosisHistory(@RequestBody String updateDiagnosisHistoryReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateDiagnosisHistory(updateDiagnosisHistoryReqJson)));
    }

    @PostMapping("/patient/deletediagnosishistory")
    public ResponseEntity<String> deleteDiagnosisHistory(@RequestBody String deleteDiagnosisHistoryReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDiagnosisHistory(deleteDiagnosisHistoryReqJson)));
    }

    @PostMapping("/patient/synchispatient")
    public ResponseEntity<String> syncHisPatient(@RequestBody String syncHisPatientReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.syncHisPatient(syncHisPatientReqJson)));
    }

    @PostMapping("/tube/gettubetypes")
    public ResponseEntity<String> getTubeTypes(@RequestBody String getTubeTypesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getTubeTypes(getTubeTypesReqJson)));
    }

    @PostMapping("/tube/addtubetype")
    public ResponseEntity<String> addTubeType(@RequestBody String addTubeTypeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addTubeType(addTubeTypeReqJson)));
    }

    @PostMapping("/tube/updatetubetype")
    public ResponseEntity<String> updateTubeType(@RequestBody String addTubeTypeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateTubeType(addTubeTypeReqJson)));
    }

    @PostMapping("/tube/disabletubetype")
    public ResponseEntity<String> disableTubeType(@RequestBody String disableTubeTypeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.disableTubeType(disableTubeTypeReqJson)));
    }

    @PostMapping("/tube/deletetubetype")
    public ResponseEntity<String> deleteTubeType(@RequestBody String deleteTubeTypeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteTubeType(deleteTubeTypeReqJson)));
    }

    @PostMapping("/tube/addtubetypeattr")
    public ResponseEntity<String> addTubeTypeAttr(@RequestBody String addTubeTypeAttrReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addTubeTypeAttr(addTubeTypeAttrReqJson)));
    }

    @PostMapping("/tube/updatetubetypeattr")
    public ResponseEntity<String> updateTubeTypeAttr(@RequestBody String updateTubeTypeAttrReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateTubeTypeAttr(updateTubeTypeAttrReqJson)));
    }

    @PostMapping("/tube/deletetubetypeattr")
    public ResponseEntity<String> deleteTubeTypeAttr(@RequestBody String deleteTubeTypeAttrReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteTubeTypeAttr(deleteTubeTypeAttrReqJson)));
    }

    @PostMapping("/tube/addtubetypestatus")
    public ResponseEntity<String> addTubeTypeStatus(@RequestBody String addTubeTypeStatusReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addTubeTypeStatus(addTubeTypeStatusReqJson)));
    }

    @PostMapping("/tube/updatetubetypestatus")
    public ResponseEntity<String> updateTubeTypeStatus(@RequestBody String updateTubeTypeStatusReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateTubeTypeStatus(updateTubeTypeStatusReqJson)));
    }

    @PostMapping("/tube/deletetubetypestatus")
    public ResponseEntity<String> deleteTubeTypeStatus(@RequestBody String deleteTubeTypeStatusReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteTubeTypeStatus(deleteTubeTypeStatusReqJson)));
    }

    @PostMapping("/tube/adjustorder")
    public ResponseEntity<String> adjustTubeOrder(@RequestBody String adjustTubeOrderReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.adjustTubeOrder(adjustTubeOrderReqJson)));
    }

    @PostMapping("/tube/getpatienttuberecords")
    public ResponseEntity<String> getPatientTubeRecords(@RequestBody String getPatientTubeRecordsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientTubeRecords(getPatientTubeRecordsReqJson)));
    }

    @PostMapping("/tube/newpatienttube")
    public ResponseEntity<String> newPatientTube(@RequestBody String newPatientTubeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.newPatientTube(newPatientTubeReqJson)));
    }

    @PostMapping("/tube/removepatienttube")
    public ResponseEntity<String> removePatientTube(@RequestBody String removePatientTubeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.removePatientTube(removePatientTubeReqJson)));
    }

    @PostMapping("/tube/deletepatienttube")
    public ResponseEntity<String> deletePatientTube(@RequestBody String deletePatientTubeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientTube(deletePatientTubeReqJson)));
    }

    @PostMapping("/tube/retaintubeondischarge")
    public ResponseEntity<String> retainTubeOnDischarge(@RequestBody String retainTubeOnDischargeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.retainTubeOnDischarge(retainTubeOnDischargeReqJson)));
    }

    @PostMapping("/tube/replacepatienttube")
    public ResponseEntity<String> replacePatientTube(@RequestBody String replacePatientTubeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.replacePatientTube(replacePatientTubeReqJson)));
    }

    @PostMapping("/tube/updatepatienttubeattr")
    public ResponseEntity<String> updatePatientTubeAttr(@RequestBody String updatePatientTubeAttrReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientTubeAttr(updatePatientTubeAttrReqJson)));
    }

    @PostMapping("/tube/getpatienttubestatus")
    public ResponseEntity<String> getPatientTubeStatus(@RequestBody String getPatientTubeStatusReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientTubeStatus(getPatientTubeStatusReqJson)));
    }

    @PostMapping("/tube/newpatienttubestatus")
    public ResponseEntity<String> newPatientTubeStatus(@RequestBody String newPatientTubeStatusReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.newPatientTubeStatus(newPatientTubeStatusReqJson)));
    }

    @PostMapping("/tube/deletepatienttubestatus")
    public ResponseEntity<String> deletePatientTubeStatus(@RequestBody String deletePatientTubeStatusReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientTubeStatus(deletePatientTubeStatusReqJson)));
    }

    @PostMapping("/monitoring/getmonitoringparams")
    public ResponseEntity<String> getMonitoringParams(@RequestBody String getMonitoringParamsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getMonitoringParams(getMonitoringParamsReqJson)));
    }

    @PostMapping("/monitoring/getmonitoringparam")
    public ResponseEntity<String> getMonitoringParam(@RequestBody String getMonitoringParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getMonitoringParam(getMonitoringParamReqJson)));
    }

    @PostMapping("/monitoring/addmonitoringparam")
    public ResponseEntity<String> addMonitoringParam(@RequestBody String addMonitoringParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addMonitoringParam(addMonitoringParamReqJson)));
    }

    @PostMapping("/monitoring/updatemonitoringparam")
    public ResponseEntity<String> updateMonitoringParam(@RequestBody String addMonitoringParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateMonitoringParam(addMonitoringParamReqJson)));
    }

    @PostMapping("/monitoring/deletemonitoringparam")
    public ResponseEntity<String> deleteMonitoringParam(@RequestBody String deleteMonitoringParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteMonitoringParam(deleteMonitoringParamReqJson)));
    }

    @PostMapping("/monitoring/updatedeptmonitoringparam")
    public ResponseEntity<String> updateDeptMonitoringParam(@RequestBody String updateDeptMonitoringParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateDeptMonitoringParam(updateDeptMonitoringParamReqJson)));
    }

    @PostMapping("/monitoring/getdeptmonitoringgroups")
    public ResponseEntity<String> getDeptMonitoringGroups(@RequestBody String getDeptMonitoringGroupsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeptMonitoringGroups(getDeptMonitoringGroupsReqJson)));
    }

    @PostMapping("/monitoring/adddeptmonitoringgroup")
    public ResponseEntity<String> addDeptMonitoringGroup(@RequestBody String addDeptMonitoringGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeptMonitoringGroup(addDeptMonitoringGroupReqJson)));
    }

    @PostMapping("/monitoring/updatedeptmongroupname")
    public ResponseEntity<String> updateDeptMonGroupName(@RequestBody String updateDeptMonGroupNameReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateDeptMonGroupName(updateDeptMonGroupNameReqJson)));
    }

    @PostMapping("/monitoring/deletedeptmonitoringgroup")
    public ResponseEntity<String> deleteDeptMonitoringGroup(@RequestBody String deleteDeptMonitoringGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDeptMonitoringGroup(deleteDeptMonitoringGroupReqJson)));
    }

    @PostMapping("/monitoring/reorderdeptmonitoringgroup")
    public ResponseEntity<String> reorderDeptMonitoringGroup(@RequestBody String reorderDeptMonitoringGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderDeptMonitoringGroup(reorderDeptMonitoringGroupReqJson)));
    }

    @PostMapping("/monitoring/adddeptmonitoringgroupparam")
    public ResponseEntity<String> addDeptMonitoringGroupParam(@RequestBody String addDeptMonitoringGroupParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeptMonitoringGroupParam(addDeptMonitoringGroupParamReqJson)));
    }

    @PostMapping("/monitoring/deletedeptmonitoringgroupparam")
    public ResponseEntity<String> deleteDeptMonitoringGroupParam(@RequestBody String deleteDeptMonitoringGroupParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDeptMonitoringGroupParam(deleteDeptMonitoringGroupParamReqJson)));
    }

    @PostMapping("/monitoring/reorderdeptmonitoringgroupparam")
    public ResponseEntity<String> reorderDeptMonitoringGroupParam(@RequestBody String reorderDeptMonitoringGroupParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderDeptMonitoringGroupParam(reorderDeptMonitoringGroupParamReqJson)));
    }

    @PostMapping("/monitoring/getpatientmonitoringgroups")
    public ResponseEntity<String> getPatientMonitoringGroups(@RequestBody String getPatientMonitoringGroupsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientMonitoringGroups(getPatientMonitoringGroupsReqJson)));
    }

    @PostMapping("/monitoring/updatepatientmonitoringgroup")
    public ResponseEntity<String> updatePatientMonitoringGroup(@RequestBody String updatePatientMonitoringGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientMonitoringGroup(updatePatientMonitoringGroupReqJson)));
    }

    @PostMapping("/monitoring/syncpatientmonitoringgroupsfromdept")
    public ResponseEntity<String> syncPatientMonitoringGroupsFromDept(@RequestBody String syncPatientMonitoringGroupsFromDeptReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.syncPatientMonitoringGroupsFromDept(syncPatientMonitoringGroupsFromDeptReqJson)));
    }

    @PostMapping("/monitoring/getpatientmonitoringrecords")
    public ResponseEntity<String> getPatientMonitoringRecords(@RequestBody String getPatientMonitoringRecordsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientMonitoringRecords(getPatientMonitoringRecordsReqJson)));
    }

    @PostMapping("/monitoring/addpatientmonitoringrecord")
    public ResponseEntity<String> addPatientMonitoringRecord(@RequestBody String addPatientMonitoringRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addPatientMonitoringRecord(addPatientMonitoringRecordReqJson)));
    }

    @PostMapping("/monitoring/updatepatientmonitoringrecord")
    public ResponseEntity<String> updatePatientMonitoringRecord(@RequestBody String updatePatientMonitoringRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientMonitoringRecord(updatePatientMonitoringRecordReqJson)));
    }

    @PostMapping("/monitoring/deletepatientmonitoringrecord")
    public ResponseEntity<String> deletePatientMonitoringRecord(@RequestBody String deletePatientMonitoringRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientMonitoringRecord(deletePatientMonitoringRecordReqJson)));
    }

    @PostMapping("/monitoring/addpatientmonitoringtimepoints")
    public ResponseEntity<String> addPatientMonitoringTimePoints(@RequestBody String addPatientMonitoringTimePointsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addPatientMonitoringTimePoints(addPatientMonitoringTimePointsReqJson)));
    }

    @PostMapping("/monitoring/deletepatientmonitoringtimepoints")
    public ResponseEntity<String> deletePatientMonitoringTimePoints(@RequestBody String deletePatientMonitoringTimePointsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientMonitoringTimePoints(deletePatientMonitoringTimePointsReqJson)));
    }

    @PostMapping("/medication/getordergroups")
    public ResponseEntity<String> getOrderGroups(@RequestBody String getOrderGroupsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getOrderGroups(getOrderGroupsReqJson)));
    }

    @PostMapping("/medication/getordergroup")
    public ResponseEntity<String> getOrderGroup(@RequestBody String getOrderGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getOrderGroup(getOrderGroupReqJson)));
    }

    @PostMapping("/medication/newordergroup")
    public ResponseEntity<String> newOrderGroup(@RequestBody String newOrderGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.newOrderGroup(newOrderGroupReqJson)));
    }

    @PostMapping("/medication/addorderexeaction")
    public ResponseEntity<String> addOrderExeAction(@RequestBody String addOrderExeActionReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addOrderExeAction(addOrderExeActionReqJson)));
    }

    @PostMapping("/medication/delorderexeaction")
    public ResponseEntity<String> delOrderExeAction(@RequestBody String delOrderExeActionReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.delOrderExeAction(delOrderExeActionReqJson)));
    }

    @PostMapping("/medication/updateexerecord")
    public ResponseEntity<String> updateExeRecord(@RequestBody String updateExeRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateExeRecord(updateExeRecordReqJson)));
    }

    @PostMapping("/medication/delexerecord")
    public ResponseEntity<String> delExeRecord(@RequestBody String delExeRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.delExeRecord(delExeRecordReqJson)));
    }

    @PostMapping("/medication/getdeletedexerecords")
    public ResponseEntity<String> getDeletedExeRecords(@RequestBody String getDeletedExeRecordsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeletedExeRecords(getDeletedExeRecordsReqJson)));
    }

    @PostMapping("/medication/revertdeletedexerecord")
    public ResponseEntity<String> revertDeletedExeRecord(@RequestBody String revertDeletedExeRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.revertDeletedExeRecord(revertDeletedExeRecordReqJson)));
    }

    @PostMapping("/medication/lookupmedication")
    public ResponseEntity<String> lookupMedication(@RequestBody String lookupMedicationReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.lookupMedication(lookupMedicationReqJson)));
    }

    @PostMapping("/medication/lookupmedicationv2")
    public ResponseEntity<String> lookupMedicationV2(@RequestBody String lookupMedicationReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.lookupMedicationV2(lookupMedicationReqJson)));
    }

    @PostMapping("/medication/addmedication")
    public ResponseEntity<String> addMedication(@RequestBody String addMedicationReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addMedication(addMedicationReqJson)));
    }

    @PostMapping("/medication/updatemedication")
    public ResponseEntity<String> updateMedication(@RequestBody String updateMedicationReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateMedication(updateMedicationReqJson)));
    }

    @PostMapping("/medication/deletemedication")
    public ResponseEntity<String> deleteMedication(@RequestBody String deleteMedicationReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteMedication(deleteMedicationReqJson)));
    }

    @PostMapping("/medication/lookuproute")
    public ResponseEntity<String> lookupRoute(@RequestBody String lookupRouteReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.lookupRoute(lookupRouteReqJson)));
    }

    @PostMapping("/medication/addroute")
    public ResponseEntity<String> addRoute(@RequestBody String addRouteReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addRoute(addRouteReqJson)));
    }

    @PostMapping("/medication/updateroute")
    public ResponseEntity<String> updateRoute(@RequestBody String updateRouteReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateRoute(updateRouteReqJson)));
    }

    @PostMapping("/medication/deleteroute")
    public ResponseEntity<String> deleteRoute(@RequestBody String deleteRouteReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteRoute(deleteRouteReqJson)));
    }

    @PostMapping("/medication/lookupfreq")
    public ResponseEntity<String> lookupFreq(@RequestBody String lookupFreqReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.lookupFreq(lookupFreqReqJson)));
    }

    @PostMapping("/medication/lookupfreqv2")
    public ResponseEntity<String> lookupFreqV2(@RequestBody String lookupFreqReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.lookupFreqV2(lookupFreqReqJson)));
    }

    @PostMapping("/medication/addfreq")
    public ResponseEntity<String> addFreq(@RequestBody String addFreqReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addFreq(addFreqReqJson)));
    }

    @PostMapping("/medication/updatefreq")
    public ResponseEntity<String> updateFreq(@RequestBody String updateFreqReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateFreq(updateFreqReqJson)));
    }

    @PostMapping("/medication/deletefreq")
    public ResponseEntity<String> deleteFreq(@RequestBody String deleteFreqReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteFreq(deleteFreqReqJson)));
    }

    @PostMapping("/medication/getdosagegroupext")
    public ResponseEntity<String> getDosageGroupExt(@RequestBody String getDosageGroupExtReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDosageGroupExt(getDosageGroupExtReqJson)));
    }

    @PostMapping("/medication/calcmedrate")
    public ResponseEntity<String> calcMedRate(@RequestBody String calcMedRateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.calcMedRate(calcMedRateReqJson)));
    }

    @PostMapping("/score/getscoregroupmeta")
    public ResponseEntity<String> getScoreGroupMeta(@RequestBody String getScoreGroupMetaReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getScoreGroupMeta(getScoreGroupMetaReqJson)));
    }

    @PostMapping("/score/adddeptscoregroup")
    public ResponseEntity<String> addDeptScoreGroup(@RequestBody String addDeptScoreGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeptScoreGroup(addDeptScoreGroupReqJson)));
    }

    @PostMapping("/score/adddeptscoregroups")
    public ResponseEntity<String> addDeptScoreGroups(@RequestBody String addDeptScoreGroupsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeptScoreGroups(addDeptScoreGroupsReqJson)));
    }

    @PostMapping("/score/deletedeptscoregroup")
    public ResponseEntity<String> deleteDeptScoreGroup(@RequestBody String deleteDeptScoreGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDeptScoreGroup(deleteDeptScoreGroupReqJson)));
    }

    @PostMapping("/score/reorderdeptscoregroups")
    public ResponseEntity<String> reorderDeptScoreGroups(@RequestBody String reorderDeptScoreGroupsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderDeptScoreGroups(reorderDeptScoreGroupsReqJson)));
    }

    @PostMapping("/score/getpatientscores")
    public ResponseEntity<String> getPatientScores(@RequestBody String getPatientScoresReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientScores(getPatientScoresReqJson)));
    }

    @PostMapping("/score/getpatientscore")
    public ResponseEntity<String> getPatientScore(@RequestBody String getPatientScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientScore(getPatientScoreReqJson)));
    }

    @PostMapping("/score/addpatientscore")
    public ResponseEntity<String> addPatientScore(@RequestBody String addPatientScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addPatientScore(addPatientScoreReqJson)));
    }

    @PostMapping("/score/updatepatientscore")
    public ResponseEntity<String> updatePatientScore(@RequestBody String updatePatientScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientScore(updatePatientScoreReqJson)));
    }

    @PostMapping("/score/savepatientscore")
    public ResponseEntity<String> savePatientScore(@RequestBody String savePatientScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.savePatientScore(savePatientScoreReqJson)));
    }

    @PostMapping("/score/deletepatientscore")
    public ResponseEntity<String> deletePatientScore(@RequestBody String deletePatientScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientScore(deletePatientScoreReqJson)));
    }

    @PostMapping("/score/calcpatientscore")
    public ResponseEntity<String> calcPatientScore(@RequestBody String calcPatientScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.calcPatientScore(calcPatientScoreReqJson)));
    }

    @PostMapping("/score/getonescoregroupmeta")
    public ResponseEntity<String> getOneScoreGroupMeta(@RequestBody String getOneScoreGroupMetaReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getOneScoreGroupMeta(getOneScoreGroupMetaReqJson)));
    }

    @PostMapping("/score/updatescoregroupmeta")
    public ResponseEntity<String> updateScoreGroupMeta(@RequestBody String updateScoreGroupMetaReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateScoreGroupMeta(updateScoreGroupMetaReqJson)));
    }

    @PostMapping("/score/updatescoreitemname")
    public ResponseEntity<String> updateScoreItemName(@RequestBody String updateScoreItemNameReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateScoreItemName(updateScoreItemNameReqJson)));
    }

    @PostMapping("/score/updatescoreoptionname")
    public ResponseEntity<String> updateScoreOptionName(@RequestBody String updateScoreOptionNameReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateScoreOptionName(updateScoreOptionNameReqJson)));
    }

    @PostMapping("/score/addscoregroupmeasuremeta")
    public ResponseEntity<String> addScoreGroupMeasureMeta(@RequestBody String addScoreGroupMeasureMetaReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addScoreGroupMeasureMeta(addScoreGroupMeasureMetaReqJson)));
    }

    @PostMapping("/score/updatescoregroupmeasuremeta")
    public ResponseEntity<String> updateScoreGroupMeasureMeta(@RequestBody String updateScoreGroupMeasureMetaReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateScoreGroupMeasureMeta(updateScoreGroupMeasureMetaReqJson)));
    }

    @PostMapping("/score/deletescoregroupmeasuremeta")
    public ResponseEntity<String> deleteScoreGroupMeasureMeta(@RequestBody String deleteScoreGroupMeasureMetaReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteScoreGroupMeasureMeta(deleteScoreGroupMeasureMetaReqJson)));
    }

    @PostMapping("/nursingrecord/getnursingrecordtemplates")
    public ResponseEntity<String> getNursingRecordTemplates(@RequestBody String getNursingRecordTemplatesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNursingRecordTemplates(getNursingRecordTemplatesReqJson)));
    }

    @PostMapping("/nursingrecord/addnursingrecordtemplategroup")
    public ResponseEntity<String> addNursingRecordTemplateGroup(@RequestBody String addNursingRecordTemplateGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addNursingRecordTemplateGroup(addNursingRecordTemplateGroupReqJson)));
    }

    @PostMapping("/nursingrecord/updatenursingrecordtemplategroups")
    public ResponseEntity<String> updateNursingRecordTemplateGroups(@RequestBody String updateNursingRecordTemplateGroupsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateNursingRecordTemplateGroups(updateNursingRecordTemplateGroupsReqJson)));
    }

    @PostMapping("/nursingrecord/deletenursingrecordtemplategroup")
    public ResponseEntity<String> deleteNursingRecordTemplateGroup(@RequestBody String deleteNursingRecordTemplateGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteNursingRecordTemplateGroup(deleteNursingRecordTemplateGroupReqJson)));
    }

    @PostMapping("/nursingrecord/addnursingrecordtemplate")
    public ResponseEntity<String> addNursingRecordTemplate(@RequestBody String addNursingRecordTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addNursingRecordTemplate(addNursingRecordTemplateReqJson)));
    }

    @PostMapping("/nursingrecord/updatenursingrecordtemplate")
    public ResponseEntity<String> updateNursingRecordTemplate(@RequestBody String updateNursingRecordTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateNursingRecordTemplate(updateNursingRecordTemplateReqJson)));
    }

    @PostMapping("/nursingrecord/deletenursingrecordtemplate")
    public ResponseEntity<String> deleteNursingRecordTemplate(@RequestBody String deleteNursingRecordTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteNursingRecordTemplate(deleteNursingRecordTemplateReqJson)));
    }

    @PostMapping("/nursingrecord/getnursingrecord")
    public ResponseEntity<String> getNursingRecord(@RequestBody String getNursingRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNursingRecord(getNursingRecordReqJson)));
    }

    @PostMapping("/nursingrecord/addnursingrecord")
    public ResponseEntity<String> addNursingRecord(@RequestBody String addNursingRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addNursingRecord(addNursingRecordReqJson)));
    }

    @PostMapping("/nursingrecord/updatenursingrecord")
    public ResponseEntity<String> updateNursingRecord(@RequestBody String updateNursingRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateNursingRecord(updateNursingRecordReqJson)));
    }

    @PostMapping("/nursingrecord/deletenursingrecord")
    public ResponseEntity<String> deleteNursingRecord(@RequestBody String deleteNursingRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteNursingRecord(deleteNursingRecordReqJson)));
    }

    @PostMapping("/nursingrecord/getpatientcriticallishandlings")
    public ResponseEntity<String> getPatientCriticalLisHandlings(@RequestBody String getPatientCriticalLisHandlingsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientCriticalLisHandlings(getPatientCriticalLisHandlingsReqJson)));
    }

    @PostMapping("/nursingrecord/savepatientcriticallishandling")
    public ResponseEntity<String> savePatientCriticalLisHandling(@RequestBody String savePatientCriticalLisHandlingReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.savePatientCriticalLisHandling(savePatientCriticalLisHandlingReqJson)));
    }

    @PostMapping("/nursingrecord/deletepatientcriticallishandling")
    public ResponseEntity<String> deletePatientCriticalLisHandling(@RequestBody String deletePatientCriticalLisHandlingReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientCriticalLisHandling(deletePatientCriticalLisHandlingReqJson)));
    }

    @PostMapping("/nursingrecord/getnursingrecordshortcuts")
    public ResponseEntity<String> getNursingRecordShortcuts(@RequestBody String getNursingRecordShortcutsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNursingRecordShortcuts(getNursingRecordShortcutsReqJson)));
    }

    @PostMapping("/nursingrecord/getnursingrecordshortcuts2")
    public ResponseEntity<String> getNursingRecordShortcuts2(@RequestBody String getNursingRecordShortcuts2ReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNursingRecordShortcuts2(getNursingRecordShortcuts2ReqJson)));
    }

    @PostMapping("/nursingorder/getnursingordertemplates")
    public ResponseEntity<String> getNursingOrderTemplates(@RequestBody String getNursingOrderTemplatesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNursingOrderTemplates(getNursingOrderTemplatesReqJson)));
    }

    @PostMapping("/nursingorder/addnursingordertemplategroup")
    public ResponseEntity<String> addNursingOrderTemplateGroup(@RequestBody String addNursingOrderTemplateGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addNursingOrderTemplateGroup(addNursingOrderTemplateGroupReqJson)));
    }

    @PostMapping("/nursingorder/updatenursingordertemplategroup")
    public ResponseEntity<String> updateNursingOrderTemplateGroup(@RequestBody String updateNursingOrderTemplateGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateNursingOrderTemplateGroup(updateNursingOrderTemplateGroupReqJson)));
    }

    @PostMapping("/nursingorder/deletenursingordertemplategroup")
    public ResponseEntity<String> deleteNursingOrderTemplateGroup(@RequestBody String deleteNursingOrderTemplateGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteNursingOrderTemplateGroup(deleteNursingOrderTemplateGroupReqJson)));
    }

    @PostMapping("/nursingorder/addnursingordertemplate")
    public ResponseEntity<String> addNursingOrderTemplate(@RequestBody String addNursingOrderTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addNursingOrderTemplate(addNursingOrderTemplateReqJson)));
    }

    @PostMapping("/nursingorder/updatenursingordertemplate")
    public ResponseEntity<String> updateNursingOrderTemplate(@RequestBody String updateNursingOrderTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateNursingOrderTemplate(updateNursingOrderTemplateReqJson)));
    }

    @PostMapping("/nursingorder/deletenursingordertemplate")
    public ResponseEntity<String> deleteNursingOrderTemplate(@RequestBody String deleteNursingOrderTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteNursingOrderTemplate(deleteNursingOrderTemplateReqJson)));
    }

    @PostMapping("/nursingorder/getnursingorderdetails")
    public ResponseEntity<String> getNursingOrderDetails(@RequestBody String getNursingOrderDetailsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNursingOrderDetails(getNursingOrderDetailsReqJson)));
    }

    @PostMapping("/nursingorder/getnursingorders")
    public ResponseEntity<String> getNursingOrders(@RequestBody String getNursingOrdersReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNursingOrders(getNursingOrdersReqJson)));
    }

    @PostMapping("/nursingorder/getnursingorder")
    public ResponseEntity<String> getNursingOrder(@RequestBody String getNursingOrderReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNursingOrder(getNursingOrderReqJson)));
    }

    @PostMapping("/nursingorder/addnursingorders")
    public ResponseEntity<String> addNursingOrders(@RequestBody String addNursingOrdersReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addNursingOrders(addNursingOrdersReqJson)));
    }

    @PostMapping("/nursingorder/stopnursingorder")
    public ResponseEntity<String> stopNursingOrder(@RequestBody String stopNursingOrderReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.stopNursingOrder(stopNursingOrderReqJson)));
    }

    @PostMapping("/nursingorder/deletenursingorder")
    public ResponseEntity<String> deleteNursingOrder(@RequestBody String deleteNursingOrderReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteNursingOrder(deleteNursingOrderReqJson)));
    }

    @PostMapping("/nursingorder/updatenursingexerecord")
    public ResponseEntity<String> updateNursingExeRecord(@RequestBody String updateNursingExeRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateNursingExeRecord(updateNursingExeRecordReqJson)));
    }

    @PostMapping("/nursingorder/deletenursingexerecord")
    public ResponseEntity<String> deleteNursingExeRecord(@RequestBody String deleteNursingExeRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteNursingExeRecord(deleteNursingExeRecordReqJson)));
    }

    @PostMapping("/patientshift/getpatientshiftrecords")
    public ResponseEntity<String> getPatientShiftRecords(@RequestBody String getPatientShiftRecordsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientShiftRecords(getPatientShiftRecordsReqJson)));
    }

    @PostMapping("/patientshift/patientshiftrecordexists")
    public ResponseEntity<String> patientShiftRecordExists(@RequestBody String patientShiftRecordExistsReq) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.patientShiftRecordExists(patientShiftRecordExistsReq)));
    }

    @PostMapping("/patientshift/addpatientshiftrecord")
    public ResponseEntity<String> addPatientShiftRecord(@RequestBody String addPatientShiftRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addPatientShiftRecord(addPatientShiftRecordReqJson)));
    }

    @PostMapping("/patientshift/updatepatientshiftrecord")
    public ResponseEntity<String> updatePatientShiftRecord(@RequestBody String addPatientShiftRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientShiftRecord(addPatientShiftRecordReqJson)));
    }

    @PostMapping("/patientshift/deletepatientshiftrecord")
    public ResponseEntity<String> deletePatientShiftRecord(@RequestBody String deletePatientShiftRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientShiftRecord(deletePatientShiftRecordReqJson)));
    }

    @PostMapping("/report/getformtemplates")
    public ResponseEntity<String> getFormTemplates(@RequestBody String getFormTemplatesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getFormTemplates(getFormTemplatesReqJson)));
    }

    @PostMapping("/report/saveformtemplate")
    public ResponseEntity<String> saveFormTemplate(@RequestBody String saveFormTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.saveFormTemplate(saveFormTemplateReqJson)));
    }

    @PostMapping("/report/deleteformtemplate")
    public ResponseEntity<String> deleteFormTemplate(@RequestBody String deleteFormTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteFormTemplate(deleteFormTemplateReqJson)));
    }

    @PostMapping("/report/getjfkdata")
    public ResponseEntity<String> getJfkData(@RequestBody String getJfkDataReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getJfkData(getJfkDataReqJson)));
    }

    @PostMapping("/report/getjfksignpics")
    public ResponseEntity<String> getJfkSignPics(@RequestBody String getJfkSignPicsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getJfkSignPics(getJfkSignPicsReqJson)));
    }

    @PostMapping("/report/getpatientforms")
    public ResponseEntity<String> getPatientForms(@RequestBody String getPatientFormsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientForms(getPatientFormsReqJson)));
    }

    @PostMapping("/report/saveform")
    public ResponseEntity<String> saveForm(@RequestBody String saveFormReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.saveForm(saveFormReqJson)));
    }

    @PostMapping("/report/deleteform")
    public ResponseEntity<String> deleteForm(@RequestBody String deleteFormReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteForm(deleteFormReqJson)));
    }

    @PostMapping("/report/getmonitoringreport")
    public ResponseEntity<String> getMonitoringReport(@RequestBody String getMonitoringReportReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getMonitoringReport(getMonitoringReportReqJson)));
    }

    @PostMapping("/report/getwardreport")
    public ResponseEntity<String> getWardReport(@RequestBody String getWardReportReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getWardReport(getWardReportReqJson)));
    }

    @PostMapping("/report/setwardreport")
    public ResponseEntity<String> setWardReport(@RequestBody String setWardReportReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.setWardReport(setWardReportReqJson)));
    }

    @PostMapping("/device/getbedconfig")
    public ResponseEntity<String> getBedConfig(@RequestBody String getBedConfigReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getBedConfig(getBedConfigReqJson)));
    }

    @PostMapping("/device/addbedconfig")
    public ResponseEntity<String> addBedConfig(@RequestBody String addBedConfigReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addBedConfig(addBedConfigReqJson)));
    }

    @PostMapping("/device/updatebedconfig")
    public ResponseEntity<String> updateBedConfig(@RequestBody String updateBedConfigReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateBedConfig(updateBedConfigReqJson)));
    }

    @PostMapping("/device/deletebedconfig")
    public ResponseEntity<String> deleteBedConfig(@RequestBody String deleteBedConfigReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteBedConfig(deleteBedConfigReqJson)));
    }

    @PostMapping("/device/getbedcounts")
    public ResponseEntity<String> getBedCounts(@RequestBody String getBedCountsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getBedCounts(getBedCountsReqJson)));
    }

    @PostMapping("/device/addbedcount")
    public ResponseEntity<String> addBedCount(@RequestBody String addBedCountReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addBedCount(addBedCountReqJson)));
    }

    @PostMapping("/device/deletebedcount")
    public ResponseEntity<String> deleteBedCount(@RequestBody String deleteBedCountReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteBedCount(deleteBedCountReqJson)));
    }

    @PostMapping("/device/getdeviceinfo")
    public ResponseEntity<String> getDeviceInfo(@RequestBody String getDeviceInfoReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeviceInfo(getDeviceInfoReqJson)));
    }

    @PostMapping("/device/getdevicebindinghistory")
    public ResponseEntity<String> getDeviceBindingHistory(@RequestBody String getDeviceBindingHistoryReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeviceBindingHistory(getDeviceBindingHistoryReqJson)));
    }

    @PostMapping("/device/getpatientdevicebindinghistory")
    public ResponseEntity<String> getPatientDeviceBindingHistory(@RequestBody String getPatientDeviceBindingHistoryReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientDeviceBindingHistory(getPatientDeviceBindingHistoryReqJson)));
    }

    @PostMapping("/device/adddeviceinfo")
    public ResponseEntity<String> addDeviceInfo(@RequestBody String addDeviceInfoReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeviceInfo(addDeviceInfoReqJson)));
    }

    @PostMapping("/device/updatedeviceinfo")
    public ResponseEntity<String> updateDeviceInfo(@RequestBody String updateDeviceInfoReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateDeviceInfo(updateDeviceInfoReqJson)));
    }

    @PostMapping("/device/deletedeviceinfo")
    public ResponseEntity<String> deleteDeviceInfo(@RequestBody String deleteDeviceInfoReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDeviceInfo(deleteDeviceInfoReqJson)));
    }

    @PostMapping("/device/bindpatientdevice")
    public ResponseEntity<String> bindPatientDevice(@RequestBody String bindPatientDeviceReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.bindPatientDevice(bindPatientDeviceReqJson)));
    }

    @PostMapping("/diagnosis/getdiseasemeta")
    public ResponseEntity<String> getDiseaseMeta(@RequestBody String getDiseaseMetaReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDiseaseMeta(getDiseaseMetaReqJson)));
    }

    @PostMapping("/diagnosis/calcdiseasemetric")
    public ResponseEntity<String> calcDiseaseMetric(@RequestBody String calcDiseaseMetricReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.calcDiseaseMetric(calcDiseaseMetricReqJson)));
    }

    @PostMapping("/diagnosis/confirmdisease")
    public ResponseEntity<String> confirmDisease(@RequestBody String confirmDiseaseReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.confirmDisease(confirmDiseaseReqJson)));
    }

    @PostMapping("/diagnosis/excludedisease")
    public ResponseEntity<String> excludeDisease(@RequestBody String excludeDiseaseReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.excludeDisease(excludeDiseaseReqJson)));
    }

    @PostMapping("/diagnosis/getconfirmeddiseases")
    public ResponseEntity<String> getConfirmedDiseases(@RequestBody String getConfirmedDiseasesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getConfirmedDiseases(getConfirmedDiseasesReqJson)));
    }

    @PostMapping("/diagnosis/getexcludeddiseases")
    public ResponseEntity<String> getExcludedDiseases(@RequestBody String getExcludedDiseasesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getExcludedDiseases(getExcludedDiseasesReqJson)));
    }

    @PostMapping("/diagnosis/getdiagnosisdetails")
    public ResponseEntity<String> getDiagnosisDetails(@RequestBody String getDiagnosisDetailsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDiagnosisDetails(getDiagnosisDetailsReqJson)));
    }

    @PostMapping("/diagnosis/getdiseaseitemdetails")
    public ResponseEntity<String> getDiseaseItemDetails(@RequestBody String getDiseaseItemDetailsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDiseaseItemDetails(getDiseaseItemDetailsReqJson)));
    }

    @PostMapping("/doctor/getvitaldetails")
    public ResponseEntity<String> getVitalDetails(@RequestBody String getVitalDetailsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getVitalDetails(getVitalDetailsReqJson)));
    }

    @PostMapping("/doctor/getdailybalance")
    public ResponseEntity<String> getDailyBalance(@RequestBody String getDailyBalanceReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDailyBalance(getDailyBalanceReqJson)));
    }

    @PostMapping("/doctor/settargetbalance")
    public ResponseEntity<String> setTargetBalance(@RequestBody String setTargetBalanceReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.setTargetBalance(setTargetBalanceReqJson)));
    }

    @PostMapping("/doctor/gettargetbalances")
    public ResponseEntity<String> getTargetBalances(@RequestBody String getTargetBalancesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getTargetBalances(getTargetBalancesReqJson)));
    }

    @PostMapping("/doctor/getmedicationorderviews")
    public ResponseEntity<String> getMedicationOrderViews(@RequestBody String getMedicationOrderViewsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getMedicationOrderViews(getMedicationOrderViewsReqJson)));
    }

    @PostMapping("/doctor/gettubeviews")
    public ResponseEntity<String> getTubeViews(@RequestBody String getTubeViewsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getTubeViews(getTubeViewsReqJson)));
    }

    @PostMapping("/doctor/getdeptdoctorscoretypes")
    public ResponseEntity<String> getDeptDoctorScoreTypes(@RequestBody String getDeptDoctorScoreTypesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeptDoctorScoreTypes(getDeptDoctorScoreTypesReqJson)));
    }

    @PostMapping("/doctor/adddeptdoctorscoretypes")
    public ResponseEntity<String> addDeptDoctorScoreTypes(@RequestBody String addDeptDoctorScoreTypesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeptDoctorScoreTypes(addDeptDoctorScoreTypesReqJson)));
    }

    @PostMapping("/doctor/deletedeptdoctorscoretype")
    public ResponseEntity<String> deleteDeptDoctorScoreType(@RequestBody String deleteDeptDoctorScoreTypeReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDeptDoctorScoreType(deleteDeptDoctorScoreTypeReqJson)));
    }

    @PostMapping("/doctor/reorderdeptdoctorscoretypes")
    public ResponseEntity<String> reorderDeptDoctorScoreTypes(@RequestBody String reorderDeptDoctorScoreTypesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderDeptDoctorScoreTypes(reorderDeptDoctorScoreTypesReqJson)));
    }

    @PostMapping("/doctor/getapachescores")
    public ResponseEntity<String> getApacheScores(@RequestBody String getApacheScoresReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getApacheScores(getApacheScoresReqJson)));
    }

    @PostMapping("/doctor/getapachescorefactors")
    public ResponseEntity<String> getApacheScoreFactors(@RequestBody String getApacheScoreFactorsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getApacheScoreFactors(getApacheScoreFactorsReqJson)));
    }

    @PostMapping("/doctor/addapachescore")
    public ResponseEntity<String> addApacheScore(@RequestBody String addApacheScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addApacheScore(addApacheScoreReqJson)));
    }

    @PostMapping("/doctor/updateapachescore")
    public ResponseEntity<String> updateApacheScore(@RequestBody String updateApacheScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateApacheScore(updateApacheScoreReqJson)));
    }

    @PostMapping("/doctor/deleteapachescore")
    public ResponseEntity<String> deleteApacheScore(@RequestBody String deleteApacheScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteApacheScore(deleteApacheScoreReqJson)));
    }

    @PostMapping("/doctor/getapachefactordetails")
    public ResponseEntity<String> getApacheFactorDetails(@RequestBody String getApacheFactorDetailsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getApacheFactorDetails(getApacheFactorDetailsReqJson)));
    }

    @PostMapping("/doctor/getsofascores")
    public ResponseEntity<String> getSofaScores(@RequestBody String getSofaScoresReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getSofaScores(getSofaScoresReqJson)));
    }

    @PostMapping("/doctor/getsofascorefactors")
    public ResponseEntity<String> getSofaScoreFactors(@RequestBody String getSofaScoreFactorsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getSofaScoreFactors(getSofaScoreFactorsReqJson)));
    }

    @PostMapping("/doctor/addsofascore")
    public ResponseEntity<String> addSofaScore(@RequestBody String addSofaScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addSofaScore(addSofaScoreReqJson)));
    }

    @PostMapping("/doctor/updatesofascore")
    public ResponseEntity<String> updateSofaScore(@RequestBody String updateSofaScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateSofaScore(updateSofaScoreReqJson)));
    }

    @PostMapping("/doctor/deletesofascore")
    public ResponseEntity<String> deleteSofaScore(@RequestBody String deleteSofaScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteSofaScore(deleteSofaScoreReqJson)));
    }

    @PostMapping("/doctor/getsofafactordetails")
    public ResponseEntity<String> getSofaFactorDetails(@RequestBody String getSofaFactorDetailsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getSofaFactorDetails(getSofaFactorDetailsReqJson)));
    }

    @PostMapping("/doctor/getvtecapriniscores")
    public ResponseEntity<String> getVteCapriniScores(@RequestBody String getVteCapriniScoresReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getVteCapriniScores(getVteCapriniScoresReqJson)));
    }

    @PostMapping("/doctor/addvtecapriniscore")
    public ResponseEntity<String> addVteCapriniScore(@RequestBody String addVteCapriniScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addVteCapriniScore(addVteCapriniScoreReqJson)));
    }

    @PostMapping("/doctor/updatevtecapriniscore")
    public ResponseEntity<String> updateVteCapriniScore(@RequestBody String updateVteCapriniScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateVteCapriniScore(updateVteCapriniScoreReqJson)));
    }

    @PostMapping("/doctor/deletevtecapriniscore")
    public ResponseEntity<String> deleteVteCapriniScore(@RequestBody String deleteVteCapriniScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteVteCapriniScore(deleteVteCapriniScoreReqJson)));
    }

    @PostMapping("/doctor/getvtepaduascores")
    public ResponseEntity<String> getVtePaduaScores(@RequestBody String getVtePaduaScoresReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getVtePaduaScores(getVtePaduaScoresReqJson)));
    }

    @PostMapping("/doctor/addvtepaduascore")
    public ResponseEntity<String> addVtePaduaScore(@RequestBody String addVtePaduaScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addVtePaduaScore(addVtePaduaScoreReqJson)));
    }

    @PostMapping("/doctor/updatevtepaduascore")
    public ResponseEntity<String> updateVtePaduaScore(@RequestBody String updateVtePaduaScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateVtePaduaScore(updateVtePaduaScoreReqJson)));
    }

    @PostMapping("/doctor/deletevtepaduascore")
    public ResponseEntity<String> deleteVtePaduaScore(@RequestBody String deleteVtePaduaScoreReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteVtePaduaScore(deleteVtePaduaScoreReqJson)));
    }

    @PostMapping("/bga/getbgaparams")
    public ResponseEntity<String> getBgaParams(@RequestBody String getBgaParamsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getBgaParams(getBgaParamsReqJson)));
    }

    @PostMapping("/bga/enableparam")
    public ResponseEntity<String> enableBgaParam(@RequestBody String enableBgaParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.enableBgaParam(enableBgaParamReqJson)));
    }

    @PostMapping("/bga/reorderbgaparams")
    public ResponseEntity<String> reorderBgaParams(@RequestBody String reorderBgaParamsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderBgaParams(reorderBgaParamsReqJson)));
    }

    @PostMapping("/bga/getpatientbgarecords")
    public ResponseEntity<String> getPatientBgaRecords(@RequestBody String getPatientBgaRecordsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientBgaRecords(getPatientBgaRecordsReqJson)));
    }

    @PostMapping("/bga/addpatientbgarecord")
    public ResponseEntity<String> addPatientBgaRecord(@RequestBody String addPatientBgaRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addPatientBgaRecord(addPatientBgaRecordReqJson)));
    }

    @PostMapping("/bga/updatepatientbgarecord")
    public ResponseEntity<String> updatePatientBgaRecord(@RequestBody String updatePatientBgaRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientBgaRecord(updatePatientBgaRecordReqJson)));
    }

    @PostMapping("/bga/deletepatientbgarecord")
    public ResponseEntity<String> deletePatientBgaRecord(@RequestBody String deletePatientBgaRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientBgaRecord(deletePatientBgaRecordReqJson)));
    }

    @PostMapping("/bga/reviewpatientbgarecords")
    public ResponseEntity<String> reviewPatientBgaRecords(@RequestBody String reviewPatientBgaRecordsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reviewPatientBgaRecords(reviewPatientBgaRecordsReqJson)));
    }

    @PostMapping("/lis/getexternallisparams")
    public ResponseEntity<String> getExternalLisParams(@RequestBody String getExternalLisParamsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getExternalLisParams(getExternalLisParamsReqJson)));
    }

    @PostMapping("/lis/reorderexternallisparams")
    public ResponseEntity<String> reorderExternalLisParams(@RequestBody String reorderExternalLisParamsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderExternalLisParams(reorderExternalLisParamsReqJson)));
    }

    @PostMapping("/lis/updateexternallisparam")
    public ResponseEntity<String> updateExternalLisParam(@RequestBody String updateExternalLisParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateExternalLisParam(updateExternalLisParamReqJson)));
    }

    @PostMapping("/lis/deleteexternallisparam")
    public ResponseEntity<String> deleteExternalLisParam(@RequestBody String deleteExternalLisParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteExternalLisParam(deleteExternalLisParamReqJson)));
    }

    @PostMapping("/lis/getlisparams")
    public ResponseEntity<String> getLisParams(@RequestBody String getLisParamsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getLisParams(getLisParamsReqJson)));
    }

    @PostMapping("/lis/reorderlisparams")
    public ResponseEntity<String> reorderLisParams(@RequestBody String reorderLisParamsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderLisParams(reorderLisParamsReqJson)));
    }

    @PostMapping("/lis/updatelisparam")
    public ResponseEntity<String> updateLisParam(@RequestBody String updateLisParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateLisParam(updateLisParamReqJson)));
    }

    @PostMapping("/lis/getpatientlisresults")
    public ResponseEntity<String> getPatientLisResults(@RequestBody String getPatientLisResultsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientLisResults(getPatientLisResultsReqJson)));
    }

    @PostMapping("/lis/deletepatientlisrecord")
    public ResponseEntity<String> deletePatientLisRecord(@RequestBody String deletePatientLisRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientLisRecord(deletePatientLisRecordReqJson)));
    }

    @PostMapping("/overview/getoverviewtemplates")
    public ResponseEntity<String> getOverviewTemplates(@RequestBody String getOverviewTemplatesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getOverviewTemplates(getOverviewTemplatesReqJson)));
    }

    @PostMapping("/overview/getoverviewtemplate")
    public ResponseEntity<String> getOverviewTemplate(@RequestBody String getOverviewTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getOverviewTemplate(getOverviewTemplateReqJson)));
    }

    @PostMapping("/overview/addoverviewtemplate")
    public ResponseEntity<String> addOverviewTemplate(@RequestBody String addOverviewTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addOverviewTemplate(addOverviewTemplateReqJson)));
    }

    @PostMapping("/overview/updateoverviewtemplate")
    public ResponseEntity<String> updateOverviewTemplate(@RequestBody String updateOverviewTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateOverviewTemplate(updateOverviewTemplateReqJson)));
    }

    @PostMapping("/overview/deleteoverviewtemplate")
    public ResponseEntity<String> deleteOverviewTemplate(@RequestBody String deleteOverviewTemplateReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteOverviewTemplate(deleteOverviewTemplateReqJson)));
    }

    @PostMapping("/overview/addoverviewgroup")
    public ResponseEntity<String> addOverviewGroup(@RequestBody String addOverviewGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addOverviewGroup(addOverviewGroupReqJson)));
    }

    @PostMapping("/overview/updateoverviewgroup")
    public ResponseEntity<String> updateOverviewGroup(@RequestBody String updateOverviewGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateOverviewGroup(updateOverviewGroupReqJson)));
    }

    @PostMapping("/overview/deleteoverviewgroup")
    public ResponseEntity<String> deleteOverviewGroup(@RequestBody String deleteOverviewGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteOverviewGroup(deleteOverviewGroupReqJson)));
    }

    @PostMapping("/overview/getoverviewparamcodes")
    public ResponseEntity<String> getOverviewParamCodes(@RequestBody String getOverviewParamCodesReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getOverviewParamCodes(getOverviewParamCodesReqJson)));
    }

    @PostMapping("/overview/addoverviewparam")
    public ResponseEntity<String> addOverviewParam(@RequestBody String addOverviewParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addOverviewParam(addOverviewParamReqJson)));
    }

    @PostMapping("/overview/updateoverviewparam")
    public ResponseEntity<String> updateOverviewParam(@RequestBody String updateOverviewParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateOverviewParam(updateOverviewParamReqJson)));
    }

    @PostMapping("/overview/deleteoverviewparam")
    public ResponseEntity<String> deleteOverviewParam(@RequestBody String deleteOverviewParamReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteOverviewParam(deleteOverviewParamReqJson)));
    }

    @PostMapping("/overview/getoverviewdata")
    public ResponseEntity<String> getOverviewData(@RequestBody String getOverviewDataReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getOverviewData(getOverviewDataReqJson)));
    }

    @PostMapping("/checklist/getdeptchecklistgroups")
    public ResponseEntity<String> getDeptChecklistGroups(@RequestBody String getDeptChecklistGroupsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getDeptChecklistGroups(getDeptChecklistGroupsReqJson)));
    }

    @PostMapping("/checklist/adddeptchecklistgroup")
    public ResponseEntity<String> addDeptChecklistGroup(@RequestBody String addDeptChecklistGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeptChecklistGroup(addDeptChecklistGroupReqJson)));
    }

    @PostMapping("/checklist/deletedeptchecklistgroup")
    public ResponseEntity<String> deleteDeptChecklistGroup(@RequestBody String deleteDeptChecklistGroupReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDeptChecklistGroup(deleteDeptChecklistGroupReqJson)));
    }

    @PostMapping("/checklist/reorderdeptchecklistgroups")
    public ResponseEntity<String> reorderDeptChecklistGroups(@RequestBody String reorderDeptChecklistGroupsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderDeptChecklistGroups(reorderDeptChecklistGroupsReqJson)));
    }

    @PostMapping("/checklist/adddeptchecklistitem")
    public ResponseEntity<String> addDeptChecklistItem(@RequestBody String addDeptChecklistItemReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addDeptChecklistItem(addDeptChecklistItemReqJson)));
    }

    @PostMapping("/checklist/deletedeptchecklistitem")
    public ResponseEntity<String> deleteDeptChecklistItem(@RequestBody String deleteDeptChecklistItemReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteDeptChecklistItem(deleteDeptChecklistItemReqJson)));
    }

    @PostMapping("/checklist/reorderdeptchecklistitems")
    public ResponseEntity<String> reorderDeptChecklistItems(@RequestBody String reorderDeptChecklistItemsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.reorderDeptChecklistItems(reorderDeptChecklistItemsReqJson)));
    }

    @PostMapping("/checklist/getpatientchecklistrecords")
    public ResponseEntity<String> getPatientChecklistRecords(@RequestBody String getPatientChecklistRecordsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getPatientChecklistRecords(getPatientChecklistRecordsReqJson)));
    }

    @PostMapping("/checklist/addpatientchecklistrecord")
    public ResponseEntity<String> addPatientChecklistRecord(@RequestBody String addPatientChecklistRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.addPatientChecklistRecord(addPatientChecklistRecordReqJson)));
    }

    @PostMapping("/checklist/deletepatientchecklistrecord")
    public ResponseEntity<String> deletePatientChecklistRecord(@RequestBody String deletePatientChecklistRecordReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deletePatientChecklistRecord(deletePatientChecklistRecordReqJson)));
    }

    @PostMapping("/checklist/updatepatientchecklistitem")
    public ResponseEntity<String> updatePatientChecklistItem(@RequestBody String updatePatientChecklistItemReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updatePatientChecklistItem(updatePatientChecklistItemReqJson)));
    }

    @PostMapping("/urls/getallexturls")
    public ResponseEntity<String> getAllExtUrls(@RequestBody String getAllExtUrlsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getAllExtUrls(getAllExtUrlsReqJson)));
    }

    @PostMapping("/urls/saveexturl")
    public ResponseEntity<String> saveExtUrl(@RequestBody String saveExtUrlReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.saveExtUrl(saveExtUrlReqJson)));
    }

    @PostMapping("/urls/deleteexturl")
    public ResponseEntity<String> deleteExtUrl(@RequestBody String deleteExtUrlReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.deleteExtUrl(deleteExtUrlReqJson)));
    }

    @PostMapping("/urls/composeexturl")
    public ResponseEntity<String> composeExtUrl(@RequestBody String composeExtUrlReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.composeExtUrl(composeExtUrlReqJson)));
    }

    @PostMapping("/nhcqc/getqcitems")
    public ResponseEntity<String> getNHCQCItems(@RequestBody String getNHCQCItemsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNHCQCItems(getNHCQCItemsReqJson)));
    }

    @PostMapping("/nhcqc/getqcdata")
    public ResponseEntity<String> getNHCQCData(@RequestBody String getNHCQCDataReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getNHCQCData(getNHCQCDataReqJson)));
    }

    @PostMapping("/settings/getappsettings")
    public ResponseEntity<String> getAppSettings(@RequestBody String getAppSettingsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.getAppSettings(getAppSettingsReqJson)));
    }

    @PostMapping("/settings/updateappsettings")
    public ResponseEntity<String> updateAppSettings(@RequestBody String updateAppSettingsReqJson) {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.updateAppSettings(updateAppSettingsReqJson)));
    }

    @GetMapping("/debugform")
    public ResponseEntity<String> debugForm(@RequestParam("id") Long id) {
        return ResponseEntity.ok(ProtoUtils.protoToTxt(webApiService.debugForm(id)));
    }

    @GetMapping("/test")
    public ResponseEntity<String> test() {
        return ResponseEntity.ok(ProtoUtils.protoToJson(webApiService.test()));
    }

    private WebApiService webApiService;
}
