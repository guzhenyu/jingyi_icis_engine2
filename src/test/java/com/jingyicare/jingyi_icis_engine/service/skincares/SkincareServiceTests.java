package com.jingyicare.jingyi_icis_engine.service.skincares;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.patients.PatientRecord;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisSkincare.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.GenericResp;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.PatientRecordRepository;
import com.jingyicare.jingyi_icis_engine.testutils.PatientTestUtils;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

public class SkincareServiceTests extends TestsBase {
    public SkincareServiceTests(
        @Autowired SkincareService skincareService,
        @Autowired PatientRecordRepository patientRepo
    ) {
        this.skincareService = skincareService;
        this.patientRepo = patientRepo;
    }

    @Test
    @Transactional
    public void testSkincareTypeAndAttributeCrud() {
        loginAsAdmin();

        String deptId = "10035";
        AddSkincareTypeResp addTypeResp = skincareService.addSkincareType(ProtoUtils.protoToJson(
            AddSkincareTypeReq.newBuilder()
                .setSkincareType(SkincareTypePB.newBuilder()
                    .setDeptId(deptId)
                    .setType("wound_assessment")
                    .setName("type_crud_case")
                    .build())
                .build()));
        assertThat(addTypeResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        int typeId = addTypeResp.getId();

        AddSkincareTypeResp duplicateTypeResp = skincareService.addSkincareType(ProtoUtils.protoToJson(
            AddSkincareTypeReq.newBuilder()
                .setSkincareType(SkincareTypePB.newBuilder()
                    .setDeptId(deptId)
                    .setType("wound_assessment")
                    .setName("type_crud_case")
                    .build())
                .build()));
        assertThat(duplicateTypeResp.getRt().getCode()).isEqualTo(StatusCode.SKINCARE_TYPE_ALREADY_EXISTS.getNumber());

        AddSkincareTypeAttributeResp addAttrResp1 = skincareService.addSkincareTypeAttribute(ProtoUtils.protoToJson(
            AddSkincareTypeAttributeReq.newBuilder()
                .setAttr(SkincareTypeAttributePB.newBuilder()
                    .setSkincareTypeId(typeId)
                    .setAttrCode("attr_assessment")
                    .setAttrName("attr_assessment_name")
                    .setCategoryId(1)
                    .setAttrType(newStringMeta("assessment_tip"))
                    .setIsInitial(true)
                    .setIsMaintenance(false)
                    .setShowInTable(true)
                    .build())
                .build()));
        assertThat(addAttrResp1.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        int attrId1 = addAttrResp1.getId();

        AddSkincareTypeAttributeResp addAttrResp2 = skincareService.addSkincareTypeAttribute(ProtoUtils.protoToJson(
            AddSkincareTypeAttributeReq.newBuilder()
                .setAttr(SkincareTypeAttributePB.newBuilder()
                    .setSkincareTypeId(typeId)
                    .setAttrCode("attr_measure")
                    .setAttrName("attr_measure_name")
                    .setCategoryId(3)
                    .setAttrType(newStringMeta("measure_tip"))
                    .setIsInitial(false)
                    .setIsMaintenance(true)
                    .setShowInTable(true)
                    .build())
                .build()));
        assertThat(addAttrResp2.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        int attrId2 = addAttrResp2.getId();

        AddSkincareTypeAttributeResp addAttrResp3 = skincareService.addSkincareTypeAttribute(ProtoUtils.protoToJson(
            AddSkincareTypeAttributeReq.newBuilder()
                .setAttr(SkincareTypeAttributePB.newBuilder()
                    .setSkincareTypeId(typeId)
                    .setAttrCode("attr_duplicate_order")
                    .setAttrName("attr_duplicate_order_name")
                    .setCategoryId(2)
                    .setDisplayOrder(1)
                    .setAttrType(newStringMeta("duplicate_tip"))
                    .setIsInitial(false)
                    .setIsMaintenance(true)
                    .setShowInTable(false)
                    .build())
                .build()));
        assertThat(addAttrResp3.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        int attrId3 = addAttrResp3.getId();

        GetSkincareTypesResp getTypesResp = skincareService.getSkincareTypes(ProtoUtils.protoToJson(
            GetSkincareTypesReq.newBuilder()
                .setDeptId(deptId)
                .setType("wound_assessment")
                .build()));
        assertThat(getTypesResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getTypesResp.getSkincareTypeCount()).isEqualTo(1);
        assertThat(getTypesResp.getSkincareType(0).getId()).isEqualTo(typeId);
        assertThat(getTypesResp.getSkincareType(0).getAttrCount()).isEqualTo(3);

        GetSkincareTypeAttributesResp getAttrsResp = skincareService.getSkincareTypeAttributes(ProtoUtils.protoToJson(
            GetSkincareTypeAttributesReq.newBuilder()
                .setSkincareTypeId(typeId)
                .setIsInitial(-1)
                .setIsMaintenance(-1)
                .setShowInTable(-1)
                .build()));
        assertThat(getAttrsResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getAttrsResp.getAttrCount()).isEqualTo(3);
        assertThat(getAttrsResp.getAttr(0).getId()).isEqualTo(attrId1);
        assertThat(getAttrsResp.getAttr(0).getDisplayOrder()).isEqualTo(1);
        assertThat(getAttrsResp.getAttr(1).getId()).isEqualTo(attrId3);
        assertThat(getAttrsResp.getAttr(1).getDisplayOrder()).isEqualTo(1);
        assertThat(getAttrsResp.getAttr(2).getId()).isEqualTo(attrId2);
        assertThat(getAttrsResp.getAttr(2).getDisplayOrder()).isEqualTo(2);

        GenericResp updateTypeResp = skincareService.updateSkincareType(ProtoUtils.protoToJson(
            UpdateSkincareTypeReq.newBuilder()
                .setSkincareType(SkincareTypePB.newBuilder()
                    .setId(typeId)
                    .setDeptId(deptId)
                    .setType("wound_assessment")
                    .setName("type_crud_case_updated")
                    .build())
                .build()));
        assertThat(updateTypeResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GenericResp updateAttrResp = skincareService.updateSkincareTypeAttribute(ProtoUtils.protoToJson(
            UpdateSkincareTypeAttributeReq.newBuilder()
                .setAttr(SkincareTypeAttributePB.newBuilder()
                    .setId(attrId1)
                    .setSkincareTypeId(typeId)
                    .setAttrCode("attr_assessment")
                    .setAttrName("attr_assessment_name_updated")
                    .setCategoryId(2)
                    .setDisplayOrder(3)
                    .setAttrType(newStringMeta("assessment_tip_updated"))
                    .setIsInitial(true)
                    .setIsMaintenance(false)
                    .setShowInTable(false)
                    .build())
                .build()));
        assertThat(updateAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GetSkincareTypeAttributesResp getUpdatedAttrResp = skincareService.getSkincareTypeAttributes(ProtoUtils.protoToJson(
            GetSkincareTypeAttributesReq.newBuilder()
                .setId(attrId1)
                .setIsInitial(-1)
                .setIsMaintenance(-1)
                .setShowInTable(-1)
                .build()));
        assertThat(getUpdatedAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getUpdatedAttrResp.getAttrCount()).isEqualTo(1);
        SkincareTypeAttributePB updatedAttr = getUpdatedAttrResp.getAttr(0);
        assertThat(updatedAttr.getAttrName()).isEqualTo("attr_assessment_name_updated");
        assertThat(updatedAttr.getCategoryId()).isEqualTo(2);
        assertThat(updatedAttr.getDisplayOrder()).isEqualTo(3);
        assertThat(updatedAttr.getShowInTable()).isFalse();
        assertThat(updatedAttr.getAttrType().getTooltip()).isEqualTo("assessment_tip_updated");

        GenericResp deleteAttrResp = skincareService.deleteSkincareTypeAttribute(ProtoUtils.protoToJson(
            DeleteSkincareTypeAttributeReq.newBuilder().setId(attrId2).build()));
        assertThat(deleteAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GetSkincareTypeAttributesResp getDeletedAttrResp = skincareService.getSkincareTypeAttributes(ProtoUtils.protoToJson(
            GetSkincareTypeAttributesReq.newBuilder()
                .setId(attrId2)
                .setIsInitial(-1)
                .setIsMaintenance(-1)
                .setShowInTable(-1)
                .setIsDeleted(1)
                .build()));
        assertThat(getDeletedAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getDeletedAttrResp.getAttrCount()).isEqualTo(1);

        GenericResp deleteTypeResp = skincareService.deleteSkincareType(ProtoUtils.protoToJson(
            DeleteSkincareTypeReq.newBuilder().setId(typeId).build()));
        assertThat(deleteTypeResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GetSkincareTypesResp getDeletedTypeResp = skincareService.getSkincareTypes(ProtoUtils.protoToJson(
            GetSkincareTypesReq.newBuilder()
                .setId(typeId)
                .setIsDeleted(1)
                .build()));
        assertThat(getDeletedTypeResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getDeletedTypeResp.getSkincareTypeCount()).isEqualTo(1);
        assertThat(getDeletedTypeResp.getSkincareType(0).getName()).isEqualTo("type_crud_case_updated");
        assertThat(getDeletedTypeResp.getSkincareType(0).getAttrCount()).isEqualTo(3);
    }

    @Test
    @Transactional
    public void testPatientSkincarePlanAndRecordCrud() {
        loginAsAdmin();

        String deptId = "10036";
        long pid = savePatient(920001L, deptId);

        AddSkincareTypeResp addTypeResp = skincareService.addSkincareType(ProtoUtils.protoToJson(
            AddSkincareTypeReq.newBuilder()
                .setSkincareType(SkincareTypePB.newBuilder()
                    .setDeptId(deptId)
                    .setType("wound_record")
                    .setName("patient_flow_type")
                    .build())
                .build()));
        assertThat(addTypeResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        int typeId = addTypeResp.getId();

        AddSkincareTypeAttributeResp addAttrResp1 = skincareService.addSkincareTypeAttribute(ProtoUtils.protoToJson(
            AddSkincareTypeAttributeReq.newBuilder()
                .setAttr(SkincareTypeAttributePB.newBuilder()
                    .setSkincareTypeId(typeId)
                    .setAttrCode("attr_initial")
                    .setAttrName("attr_initial_name")
                    .setCategoryId(1)
                    .setAttrType(newStringMeta("initial_tip"))
                    .setIsInitial(true)
                    .setIsMaintenance(false)
                    .setShowInTable(true)
                    .build())
                .build()));
        assertThat(addAttrResp1.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        int attrId1 = addAttrResp1.getId();

        AddSkincareTypeAttributeResp addAttrResp2 = skincareService.addSkincareTypeAttribute(ProtoUtils.protoToJson(
            AddSkincareTypeAttributeReq.newBuilder()
                .setAttr(SkincareTypeAttributePB.newBuilder()
                    .setSkincareTypeId(typeId)
                    .setAttrCode("attr_maintenance")
                    .setAttrName("attr_maintenance_name")
                    .setCategoryId(3)
                    .setAttrType(newStringMeta("maintenance_tip"))
                    .setIsInitial(false)
                    .setIsMaintenance(true)
                    .setShowInTable(true)
                    .build())
                .build()));
        assertThat(addAttrResp2.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        int attrId2 = addAttrResp2.getId();

        AddPatientSkincarePlanResp addPlanResp = skincareService.addPatientSkincarePlan(ProtoUtils.protoToJson(
            AddPatientSkincarePlanReq.newBuilder()
                .setPlan(PatientSkincarePlanPB.newBuilder()
                    .setDeptId(deptId)
                    .setPid(pid)
                    .setSkincareTypeId(typeId)
                    .addAttr(PatientSkincarePlanAttrPB.newBuilder()
                        .setSkincareAttrId(attrId1)
                        .setValue(newStringValue("initial_value"))
                        .build())
                    .build())
                .build()));
        assertThat(addPlanResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        long planId = addPlanResp.getId();

        AddPatientSkincarePlanResp addPlanResp2 = skincareService.addPatientSkincarePlan(ProtoUtils.protoToJson(
            AddPatientSkincarePlanReq.newBuilder()
                .setPlan(PatientSkincarePlanPB.newBuilder()
                    .setDeptId(deptId)
                    .setPid(pid)
                    .setSkincareTypeId(typeId)
                    .addAttr(PatientSkincarePlanAttrPB.newBuilder()
                        .setSkincareAttrId(attrId1)
                        .setValue(newStringValue("initial_value_2"))
                        .build())
                    .build())
                .build()));
        assertThat(addPlanResp2.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        long planId2 = addPlanResp2.getId();

        GetPatientSkincarePlansResp getPlansResp = skincareService.getPatientSkincarePlans(ProtoUtils.protoToJson(
            GetPatientSkincarePlansReq.newBuilder()
                .setPid(pid)
                .build()));
        assertThat(getPlansResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getPlansResp.getPlanCount()).isEqualTo(2);
        assertThat(getPlansResp.getPlan(0).getId()).isEqualTo(planId2);
        assertThat(getPlansResp.getPlan(1).getId()).isEqualTo(planId);
        assertThat(getPlansResp.getPlan(0).getAttrCount()).isEqualTo(1);
        assertThat(getPlansResp.getPlan(1).getAttr(0).getValue().getStrVal()).isEqualTo("initial_value");

        GenericResp updatePlanResp = skincareService.updatePatientSkincarePlan(ProtoUtils.protoToJson(
            UpdatePatientSkincarePlanReq.newBuilder()
                .setPlan(PatientSkincarePlanPB.newBuilder()
                    .setId(planId)
                    .setDeptId(deptId)
                    .setPid(pid)
                    .setSkincareTypeId(typeId)
                    .build())
                .build()));
        assertThat(updatePlanResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        AddPatientSkincarePlanAttrResp addPlanAttrResp = skincareService.addPatientSkincarePlanAttr(ProtoUtils.protoToJson(
            AddPatientSkincarePlanAttrReq.newBuilder()
                .setAttr(PatientSkincarePlanAttrPB.newBuilder()
                    .setPatientSkincarePlanId(planId)
                    .setSkincareAttrId(attrId2)
                    .setValue(newStringValue("maintenance_value"))
                    .build())
                .build()));
        assertThat(addPlanAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        long planAttrId = addPlanAttrResp.getId();

        GetPatientSkincarePlanAttrsResp getPlanAttrsResp = skincareService.getPatientSkincarePlanAttrs(ProtoUtils.protoToJson(
            GetPatientSkincarePlanAttrsReq.newBuilder()
                .setPatientSkincarePlanId(planId)
                .build()));
        assertThat(getPlanAttrsResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getPlanAttrsResp.getAttrCount()).isEqualTo(2);

        GenericResp updatePlanAttrResp = skincareService.updatePatientSkincarePlanAttr(ProtoUtils.protoToJson(
            UpdatePatientSkincarePlanAttrReq.newBuilder()
                .setAttr(PatientSkincarePlanAttrPB.newBuilder()
                    .setId(planAttrId)
                    .setPatientSkincarePlanId(planId)
                    .setSkincareAttrId(attrId2)
                    .setValue(newStringValue("maintenance_value_updated"))
                    .build())
                .build()));
        assertThat(updatePlanAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GetPatientSkincarePlanAttrsResp getUpdatedPlanAttrResp = skincareService.getPatientSkincarePlanAttrs(ProtoUtils.protoToJson(
            GetPatientSkincarePlanAttrsReq.newBuilder()
                .setId(planAttrId)
                .build()));
        assertThat(getUpdatedPlanAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getUpdatedPlanAttrResp.getAttr(0).getValue().getStrVal()).isEqualTo("maintenance_value_updated");

        AddPatientSkincareRecordResp addRecordResp = skincareService.addPatientSkincareRecord(ProtoUtils.protoToJson(
            AddPatientSkincareRecordReq.newBuilder()
                .setRecord(PatientSkincareRecordPB.newBuilder()
                    .setDeptId(deptId)
                    .setPid(pid)
                    .setPatientSkincarePlanId(planId)
                    .addAttr(PatientSkincareRecordAttrPB.newBuilder()
                        .setSkincareAttrId(attrId1)
                        .setValue(newStringValue("record_initial_value"))
                        .build())
                    .build())
                .build()));
        assertThat(addRecordResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        long recordId = addRecordResp.getId();

        AddPatientSkincareRecordResp addRecordResp2 = skincareService.addPatientSkincareRecord(ProtoUtils.protoToJson(
            AddPatientSkincareRecordReq.newBuilder()
                .setRecord(PatientSkincareRecordPB.newBuilder()
                    .setDeptId(deptId)
                    .setPid(pid)
                    .setPatientSkincarePlanId(planId)
                    .addAttr(PatientSkincareRecordAttrPB.newBuilder()
                        .setSkincareAttrId(attrId1)
                        .setValue(newStringValue("record_initial_value_2"))
                        .build())
                    .build())
                .build()));
        assertThat(addRecordResp2.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        long recordId2 = addRecordResp2.getId();

        GetPatientSkincareRecordsResp getRecordsResp = skincareService.getPatientSkincareRecords(ProtoUtils.protoToJson(
            GetPatientSkincareRecordsReq.newBuilder()
                .setPid(pid)
                .build()));
        assertThat(getRecordsResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getRecordsResp.getRecordCount()).isEqualTo(2);
        assertThat(getRecordsResp.getRecord(0).getId()).isEqualTo(recordId2);
        assertThat(getRecordsResp.getRecord(1).getId()).isEqualTo(recordId);
        assertThat(getRecordsResp.getRecord(0).getAttrCount()).isEqualTo(1);
        assertThat(getRecordsResp.getRecord(1).getAttr(0).getValue().getStrVal()).isEqualTo("record_initial_value");

        GenericResp updateRecordResp = skincareService.updatePatientSkincareRecord(ProtoUtils.protoToJson(
            UpdatePatientSkincareRecordReq.newBuilder()
                .setRecord(PatientSkincareRecordPB.newBuilder()
                    .setId(recordId)
                    .setDeptId(deptId)
                    .setPid(pid)
                    .setPatientSkincarePlanId(planId)
                    .build())
                .build()));
        assertThat(updateRecordResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        AddPatientSkincareRecordAttrResp addRecordAttrResp = skincareService.addPatientSkincareRecordAttr(ProtoUtils.protoToJson(
            AddPatientSkincareRecordAttrReq.newBuilder()
                .setAttr(PatientSkincareRecordAttrPB.newBuilder()
                    .setPatientSkincareRecordId(recordId)
                    .setSkincareAttrId(attrId2)
                    .setValue(newStringValue("record_maintenance_value"))
                    .build())
                .build()));
        assertThat(addRecordAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        long recordAttrId = addRecordAttrResp.getId();

        GetPatientSkincareRecordAttrsResp getRecordAttrsResp = skincareService.getPatientSkincareRecordAttrs(ProtoUtils.protoToJson(
            GetPatientSkincareRecordAttrsReq.newBuilder()
                .setPatientSkincareRecordId(recordId)
                .build()));
        assertThat(getRecordAttrsResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getRecordAttrsResp.getAttrCount()).isEqualTo(2);

        GenericResp updateRecordAttrResp = skincareService.updatePatientSkincareRecordAttr(ProtoUtils.protoToJson(
            UpdatePatientSkincareRecordAttrReq.newBuilder()
                .setAttr(PatientSkincareRecordAttrPB.newBuilder()
                    .setId(recordAttrId)
                    .setPatientSkincareRecordId(recordId)
                    .setSkincareAttrId(attrId2)
                    .setValue(newStringValue("record_maintenance_value_updated"))
                    .build())
                .build()));
        assertThat(updateRecordAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GetPatientSkincareRecordAttrsResp getUpdatedRecordAttrResp = skincareService.getPatientSkincareRecordAttrs(ProtoUtils.protoToJson(
            GetPatientSkincareRecordAttrsReq.newBuilder()
                .setId(recordAttrId)
                .build()));
        assertThat(getUpdatedRecordAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getUpdatedRecordAttrResp.getAttr(0).getValue().getStrVal()).isEqualTo("record_maintenance_value_updated");

        GenericResp deleteRecordAttrResp = skincareService.deletePatientSkincareRecordAttr(ProtoUtils.protoToJson(
            DeletePatientSkincareRecordAttrReq.newBuilder()
                .setId(recordAttrId)
                .build()));
        assertThat(deleteRecordAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GenericResp deletePlanAttrResp = skincareService.deletePatientSkincarePlanAttr(ProtoUtils.protoToJson(
            DeletePatientSkincarePlanAttrReq.newBuilder()
                .setId(planAttrId)
                .build()));
        assertThat(deletePlanAttrResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GenericResp deleteRecordResp = skincareService.deletePatientSkincareRecord(ProtoUtils.protoToJson(
            DeletePatientSkincareRecordReq.newBuilder()
                .setId(recordId)
                .build()));
        assertThat(deleteRecordResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GetPatientSkincareRecordsResp getDeletedRecordResp = skincareService.getPatientSkincareRecords(ProtoUtils.protoToJson(
            GetPatientSkincareRecordsReq.newBuilder()
                .setId(recordId)
                .setIsDeleted(1)
                .build()));
        assertThat(getDeletedRecordResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getDeletedRecordResp.getRecordCount()).isEqualTo(1);
        assertThat(getDeletedRecordResp.getRecord(0).getAttrCount()).isEqualTo(2);

        GetPatientSkincareRecordAttrsResp getDeletedRecordAttrsResp = skincareService.getPatientSkincareRecordAttrs(ProtoUtils.protoToJson(
            GetPatientSkincareRecordAttrsReq.newBuilder()
                .setPatientSkincareRecordId(recordId)
                .setIsDeleted(1)
                .build()));
        assertThat(getDeletedRecordAttrsResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getDeletedRecordAttrsResp.getAttrCount()).isEqualTo(2);

        GenericResp deletePlanResp = skincareService.deletePatientSkincarePlan(ProtoUtils.protoToJson(
            DeletePatientSkincarePlanReq.newBuilder()
                .setId(planId)
                .build()));
        assertThat(deletePlanResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());

        GetPatientSkincarePlansResp getDeletedPlanResp = skincareService.getPatientSkincarePlans(ProtoUtils.protoToJson(
            GetPatientSkincarePlansReq.newBuilder()
                .setId(planId)
                .setIsDeleted(1)
                .build()));
        assertThat(getDeletedPlanResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getDeletedPlanResp.getPlanCount()).isEqualTo(1);
        assertThat(getDeletedPlanResp.getPlan(0).getAttrCount()).isEqualTo(2);

        GetPatientSkincarePlanAttrsResp getDeletedPlanAttrsResp = skincareService.getPatientSkincarePlanAttrs(ProtoUtils.protoToJson(
            GetPatientSkincarePlanAttrsReq.newBuilder()
                .setPatientSkincarePlanId(planId)
                .setIsDeleted(1)
                .build()));
        assertThat(getDeletedPlanAttrsResp.getRt().getCode()).isEqualTo(StatusCode.OK.getNumber());
        assertThat(getDeletedPlanAttrsResp.getAttrCount()).isEqualTo(2);
    }

    private void loginAsAdmin() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken("admin", null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private long savePatient(long seedPid, String deptId) {
        PatientRecord patient = PatientTestUtils.newPatientRecord(seedPid, 1, deptId);
        return patientRepo.save(patient).getId();
    }

    private ValueMetaPB newStringMeta(String tooltip) {
        return ValueMetaPB.newBuilder()
            .setValueType(TypeEnumPB.STRING)
            .setRequired(true)
            .setTooltip(tooltip)
            .build();
    }

    private GenericValuePB newStringValue(String value) {
        return GenericValuePB.newBuilder()
            .setStrVal(value)
            .build();
    }

    private final SkincareService skincareService;
    private final PatientRecordRepository patientRepo;
}
