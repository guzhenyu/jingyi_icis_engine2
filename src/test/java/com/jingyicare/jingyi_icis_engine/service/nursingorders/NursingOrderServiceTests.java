package com.jingyicare.jingyi_icis_engine.service.nursingorders;

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
import com.jingyicare.jingyi_icis_engine.proto.config.IcisNursingOrder.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;

import com.jingyicare.jingyi_icis_engine.entity.patients.*;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.repository.nursingorders.*;
import com.jingyicare.jingyi_icis_engine.repository.patients.*;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.*;
import com.jingyicare.jingyi_icis_engine.utils.*;

public class NursingOrderServiceTests extends TestsBase {
    public NursingOrderServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired NursingOrderConfig orderConfig,
        @Autowired NursingOrderService orderService,
        @Autowired NursingOrderTemplateGroupRepository groupRepo,
        @Autowired NursingOrderTemplateRepository templateRepo,
        @Autowired NursingOrderRepository orderRepo,
        @Autowired NursingExecutionRecordRepository recordRepo,
        @Autowired RbacDepartmentRepository deptRepo,
        @Autowired PatientRecordRepository patientRecordRepo
    ) {
        this.ZONE_ID = protoService.getConfig().getZoneId();
        this.accountId = "admin";
        this.deptId = "99999";
        this.deptId2 = "10018";
        this.IN_ICU_VAL = protoService.getConfig().getPatient().getEnumsV2().getAdmissionStatusList()
            .stream()
            .filter(e -> e.getName().equals(protoService.getConfig().getPatient().getInIcuName()))
            .findFirst()
            .get()
            .getId();

        this.protoService = protoService;
        this.configPb = protoService.getConfig().getNursingOrder();
        this.orderConfig = orderConfig;
        this.orderService = orderService;

        this.groupRepo = groupRepo;
        this.templateRepo = templateRepo;
        this.orderRepo = orderRepo;
        this.recordRepo = recordRepo;
        this.deptRepo = deptRepo;
        this.patientRecordRepo = patientRecordRepo;

        this.patientTestUtils = new PatientTestUtils();

        initDepartments();
    }

    @Test
    public void testNursingOrderTemplates() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 查询护理计划模板
        GetNursingOrderTemplatesReq getReq = GetNursingOrderTemplatesReq.newBuilder()
            .setDeptId(deptId).build();
        String getReqJson = ProtoUtils.protoToJson(getReq);
        GetNursingOrderTemplatesResp getResp = orderService.getNursingOrderTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupList()).hasSize(2);
        assertThat(getResp.getGroup(0).getNursingOrderTemplateList()).hasSize(2);
        assertThat(getResp.getGroup(1).getNursingOrderTemplateList()).hasSize(3);

        // 新增模板组
        AddNursingOrderTemplateGroupReq addGroupReq = AddNursingOrderTemplateGroupReq.newBuilder()
            .setDeptId(deptId).setGroup(
                NursingOrderTemplateGroupPB.newBuilder()
                    .setName("group-3")
                    .setDisplayOrder(3)
                    .build()
            ).build();
        String addGroupReqJson = ProtoUtils.protoToJson(addGroupReq);
        AddNursingOrderTemplateGroupResp addGroupResp = orderService.addNursingOrderTemplateGroup(addGroupReqJson);
        assertThat(addGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer addedGroupId = addGroupResp.getId();

        getResp = orderService.getNursingOrderTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupList()).hasSize(3);

        addGroupResp = orderService.addNursingOrderTemplateGroup(addGroupReqJson);
        assertThat(addGroupResp.getRt().getCode()).isEqualTo(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_GROUP_ALREADY_EXISTS.ordinal());

        // 更新模板组名称
        UpdateNursingOrderTemplateGroupReq updateGroupReq = UpdateNursingOrderTemplateGroupReq.newBuilder()
            .setId(addedGroupId).setName("group-3-updated").build();
        String updateGroupReqJson = ProtoUtils.protoToJson(updateGroupReq);
        GenericResp updateGroupResp = orderService.updateNursingOrderTemplateGroup(updateGroupReqJson);
        assertThat(updateGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getResp = orderService.getNursingOrderTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupList()).hasSize(3);
        assertThat(getResp.getGroup(2).getName()).isEqualTo("group-3-updated");

        updateGroupReq = UpdateNursingOrderTemplateGroupReq.newBuilder()
            .setId(1000000/* 不存在的id */).setName("group-3-updated").build();
        updateGroupReqJson = ProtoUtils.protoToJson(updateGroupReq);
        updateGroupResp = orderService.updateNursingOrderTemplateGroup(updateGroupReqJson);
        assertThat(updateGroupResp.getRt().getCode()).isEqualTo(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_GROUP_NOT_EXISTS.ordinal());

        // 新增模板
        AddNursingOrderTemplateReq addTemplateReq = AddNursingOrderTemplateReq.newBuilder()
            .setDeptId(deptId).setTemplate(
                NursingOrderTemplatePB.newBuilder()
                    .setGroupId(addedGroupId)
                    .setName("template-g3-1")
                    .setDurationType(1)
                    .setMedicationFreqCode("test_qd")
                    .setDisplayOrder(1)
                    .build()
            ).build();
        String addTemplateReqJson = ProtoUtils.protoToJson(addTemplateReq);
        AddNursingOrderTemplateResp addTemplateResp = orderService.addNursingOrderTemplate(addTemplateReqJson);
        assertThat(addTemplateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        final Integer addedTemplateId = addTemplateResp.getId();

        addTemplateResp = orderService.addNursingOrderTemplate(addTemplateReqJson);
        assertThat(addTemplateResp.getRt().getCode()).isEqualTo(StatusCode.NURSING_ORDER_DEPT_TEMPLATE_ALREADY_EXISTS.ordinal());

        // 更新模板
        UpdateNursingOrderTemplateReq updateTemplateReq = UpdateNursingOrderTemplateReq.newBuilder()
            .setTemplate(
                addTemplateReq.getTemplate().toBuilder()
                    .setId(addedTemplateId)
                    .setName("template-g3-1-updated")
                    .build()
            ).build();
        String updateTemplateReqJson = ProtoUtils.protoToJson(updateTemplateReq);
        GenericResp updateTemplateResp = orderService.updateNursingOrderTemplate(updateTemplateReqJson);
        assertThat(updateTemplateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getResp = orderService.getNursingOrderTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroup(2).getNursingOrderTemplate(0).getName()).isEqualTo("template-g3-1-updated");


        // 删除模板
        DeleteNursingOrderTemplateReq deleteTemplateReq = DeleteNursingOrderTemplateReq.newBuilder()
            .setId(addedTemplateId).build();
        String deleteTemplateReqJson = ProtoUtils.protoToJson(deleteTemplateReq);
        GenericResp deleteTemplateResp = orderService.deleteNursingOrderTemplate(deleteTemplateReqJson);
        assertThat(deleteTemplateResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 删除模板组
        DeleteNursingOrderTemplateGroupReq deleteGroupReq = DeleteNursingOrderTemplateGroupReq.newBuilder()
            .setId(addedGroupId).build();
        String deleteGroupReqJson = ProtoUtils.protoToJson(deleteGroupReq);
        GenericResp deleteGroupResp = orderService.deleteNursingOrderTemplateGroup(deleteGroupReqJson);
        assertThat(deleteGroupResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
    }

    @Test
    public void testNursingOrderCreations() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        PatientRecord patient = patientTestUtils.newPatientRecord(1001L, IN_ICU_VAL, deptId2);
        patient.setAdmissionTime(LocalDateTime.of(2024, 12, 23, 0, 0, 0));
        patient = patientRecordRepo.save(patient);
        final Long patientId = patient.getId();

        // 获取模板基本信息
        GetNursingOrderTemplatesReq getReq = GetNursingOrderTemplatesReq.newBuilder()
            .setDeptId(deptId).build();
        String getReqJson = ProtoUtils.protoToJson(getReq);
        GetNursingOrderTemplatesResp getResp = orderService.getNursingOrderTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupList()).hasSize(2);
        /*
            nursing_record_g1:
                name: nursing_order_g1_t1
                duration_type: 1  # 长期
                medication_freq_code: "test_qd_q1d"

                name: nursing_order_g1_t2
                duration_type: 1  # 长期
                medication_freq_code: "test_qd_24w"

            nursing_record_g2:
                name: nursing_order_g2_t1
                duration_type: 1  # 长期
                medication_freq_code: "test_qd"

                name: nursing_order_g2_t2
                duration_type: 0  # 临时
                medication_freq_code: "test_qd"

                name: nursing_order_g2_t3
                duration_type: 1  # 长期
                medication_freq_code: "test_bid"
        */
        assertThat(getResp.getGroup(0).getNursingOrderTemplateList()).hasSize(2);
        assertThat(getResp.getGroup(1).getNursingOrderTemplateList()).hasSize(3);
        NursingOrderTemplatePB templateG1T1 = getResp.getGroup(0).getNursingOrderTemplate(0);
        Integer templateIdG1T1QdQ1d = getResp.getGroup(0).getNursingOrderTemplate(0).getId();
        NursingOrderTemplatePB templateG1T2 = getResp.getGroup(0).getNursingOrderTemplate(1);
        Integer templateIdG1T2Qd24w = getResp.getGroup(0).getNursingOrderTemplate(1).getId();
        NursingOrderTemplatePB templateG2T1 = getResp.getGroup(1).getNursingOrderTemplate(0);
        Integer templateIdG2T1Qd = getResp.getGroup(1).getNursingOrderTemplate(0).getId();
        NursingOrderTemplatePB templateG2T2 = getResp.getGroup(1).getNursingOrderTemplate(1);
        Integer templateIdG2T2Qd = getResp.getGroup(1).getNursingOrderTemplate(1).getId();
        NursingOrderTemplatePB templateG2T3 = getResp.getGroup(1).getNursingOrderTemplate(2);
        Integer templateIdG2T3Bid = getResp.getGroup(1).getNursingOrderTemplate(2).getId();

        // 新增
        LocalDateTime orderUtc = LocalDateTime.of(2024, 12, 24, 0, 0, 0);  // 周二
        String orderTimeIso8601 = TimeUtils.toIso8601String(orderUtc, ZONE_ID);
        AddNursingOrdersReq addOrderReq = AddNursingOrdersReq.newBuilder()
            .setPid(patientId)
            .addOrder(
                NursingOrderPB.newBuilder()
                    .setOrderTemplateId(templateIdG1T1QdQ1d)
                    .setName(templateG1T1.getName())
                    .setDurationType(templateG1T1.getDurationType())
                    .setMedicationFreqCode(templateG1T1.getMedicationFreqCode())
                    .setOrderTimeIso8601(orderTimeIso8601)
                    .setNote("note1")
                    .build()
            )
            .addOrder(
                NursingOrderPB.newBuilder()
                    .setOrderTemplateId(templateIdG1T2Qd24w)
                    .setName(templateG1T2.getName())
                    .setDurationType(templateG1T2.getDurationType())
                    .setMedicationFreqCode(templateG1T2.getMedicationFreqCode())
                    .setOrderTimeIso8601(orderTimeIso8601)
                    .setNote("note2")
                    .build()
            )
            .addOrder(
                NursingOrderPB.newBuilder()
                    .setOrderTemplateId(templateIdG2T1Qd)
                    .setName(templateG2T1.getName())
                    .setDurationType(templateG2T1.getDurationType())
                    .setMedicationFreqCode(templateG2T1.getMedicationFreqCode())
                    .setOrderTimeIso8601(orderTimeIso8601)
                    .setNote("note3")
                    .build()
            )
            .addOrder(
                NursingOrderPB.newBuilder()
                    .setOrderTemplateId(templateIdG2T2Qd)
                    .setName(templateG2T2.getName())
                    .setDurationType(templateG2T2.getDurationType())
                    .setMedicationFreqCode(templateG2T2.getMedicationFreqCode())
                    .setOrderTimeIso8601(orderTimeIso8601)
                    .setNote("note4")
                    .build()
            )
            .addOrder(
                NursingOrderPB.newBuilder()
                    .setOrderTemplateId(templateIdG2T3Bid)
                    .setName(templateG2T3.getName())
                    .setDurationType(templateG2T3.getDurationType())
                    .setMedicationFreqCode(templateG2T3.getMedicationFreqCode())
                    .setOrderTimeIso8601(orderTimeIso8601)
                    .setNote("note5")
                    .build()
            )
            .build();
        String addOrderReqJson = ProtoUtils.protoToJson(addOrderReq);
        AddNursingOrdersResp addOrderResp = orderService.addNursingOrders(addOrderReqJson);
        assertThat(addOrderResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 查询所有护嘱（无时间范围）
        GetNursingOrdersReq getOrdersReq = GetNursingOrdersReq.newBuilder()
            .setPid(patientId).build();
        String getOrdersReqJson = ProtoUtils.protoToJson(getOrdersReq);
        GetNursingOrdersResp getOrdersResp = orderService.getNursingOrders(getOrdersReqJson);
        assertThat(getOrdersResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getOrdersResp.getOrderList()).hasSize(5);

        // 分解护嘱 1/4
        LocalDateTime start1Utc = LocalDateTime.of(2024, 12, 24, 0, 0, 0);  // 周二
        String start1Iso8601 = TimeUtils.toIso8601String(start1Utc, ZONE_ID);
        LocalDateTime end1Utc = LocalDateTime.of(2024, 12, 25, 0, 0, 0);
        String end1Iso8601 = TimeUtils.toIso8601String(end1Utc, ZONE_ID);
        GetNursingOrderDetailsReq getDetailsReq = GetNursingOrderDetailsReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(start1Iso8601)
            .setQueryEndIso8601(end1Iso8601)
            .build();
        String getDetailsReqJson = ProtoUtils.protoToJson(getDetailsReq);
        GetNursingOrderDetailsResp getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroupList()).hasSize(2);
        assertThat(getDetailsResp.getGroup(0).getNursingOrderList()).hasSize(2);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecordList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(1).getExeRecordList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(1).getNursingOrderList()).hasSize(3);
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(0).getExeRecordList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(1).getExeRecordList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(2).getExeRecordList()).hasSize(2);

        GetNursingOrderReq getOrderReq = GetNursingOrderReq.newBuilder()
            .setId(getDetailsResp.getGroup(0).getNursingOrder(0).getId())
            .setQueryStartIso8601(start1Iso8601)
            .setQueryEndIso8601(end1Iso8601)
            .build();
        String getOrderReqJson = ProtoUtils.protoToJson(getOrderReq);
        GetNursingOrderResp getOrderResp = orderService.getNursingOrder(getOrderReqJson);
        assertThat(getOrderResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getOrderResp.getOrder().getExeRecordList()).hasSize(1);

        // 分解护嘱 2/4
        LocalDateTime start2Utc = LocalDateTime.of(2024, 12, 25, 0, 0, 0);  // 周三
        String start2Iso8601 = TimeUtils.toIso8601String(start2Utc, ZONE_ID);
        LocalDateTime end2Utc = LocalDateTime.of(2024, 12, 26, 0, 0, 0);
        String end2Iso8601 = TimeUtils.toIso8601String(end2Utc, ZONE_ID);
        getDetailsReq = GetNursingOrderDetailsReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(start2Iso8601)
            .setQueryEndIso8601(end2Iso8601)
            .build();
        getDetailsReqJson = ProtoUtils.protoToJson(getDetailsReq);
        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroupList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrderList()).hasSize(2);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getName()).isEqualTo("nursing_order_g2_t1");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getMedicationFreqCode()).isEqualTo("test_qd");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecordList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(1).getName()).isEqualTo("nursing_order_g2_t3");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(1).getMedicationFreqCode()).isEqualTo("test_bid");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(1).getExeRecordList()).hasSize(2);

        // 分解护嘱 3/4
        LocalDateTime start3Utc = LocalDateTime.of(2024, 12, 26, 0, 0, 0);  // 周四
        String start3Iso8601 = TimeUtils.toIso8601String(start3Utc, ZONE_ID);
        LocalDateTime end3Utc = LocalDateTime.of(2024, 12, 27, 0, 0, 0);
        String end3Iso8601 = TimeUtils.toIso8601String(end3Utc, ZONE_ID);
        getDetailsReq = GetNursingOrderDetailsReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(start3Iso8601)
            .setQueryEndIso8601(end3Iso8601)
            .build();
        getDetailsReqJson = ProtoUtils.protoToJson(getDetailsReq);
        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroupList()).hasSize(2);
        assertThat(getDetailsResp.getGroup(0).getNursingOrderList()).hasSize(2);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getName()).isEqualTo("nursing_order_g1_t1");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getMedicationFreqCode()).isEqualTo("test_qd_q1d");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecordList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(1).getName()).isEqualTo("nursing_order_g1_t2");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(1).getMedicationFreqCode()).isEqualTo("test_qd_24w");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(1).getExeRecordList()).hasSize(1);

        assertThat(getDetailsResp.getGroup(1).getNursingOrder(0).getName()).isEqualTo("nursing_order_g2_t1");
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(0).getMedicationFreqCode()).isEqualTo("test_qd");
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(0).getExeRecordList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(1).getName()).isEqualTo("nursing_order_g2_t3");
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(1).getMedicationFreqCode()).isEqualTo("test_bid");
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(1).getExeRecordList()).hasSize(2);

        // 分解护嘱 4/4
        LocalDateTime start4Utc = LocalDateTime.of(2024, 12, 28, 0, 0, 0);  // 周六
        String start4Iso8601 = TimeUtils.toIso8601String(start4Utc, ZONE_ID);
        LocalDateTime end4Utc = LocalDateTime.of(2024, 12, 29, 0, 0, 0);
        String end4Iso8601 = TimeUtils.toIso8601String(end4Utc, ZONE_ID);
        getDetailsReq = GetNursingOrderDetailsReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(start4Iso8601)
            .setQueryEndIso8601(end4Iso8601)
            .build();
        getDetailsReqJson = ProtoUtils.protoToJson(getDetailsReq);
        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroupList()).hasSize(2);
        assertThat(getDetailsResp.getGroup(0).getNursingOrderList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getName()).isEqualTo("nursing_order_g1_t1");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getMedicationFreqCode()).isEqualTo("test_qd_q1d");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecordList()).hasSize(1);

        assertThat(getDetailsResp.getGroup(1).getNursingOrder(0).getName()).isEqualTo("nursing_order_g2_t1");
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(0).getMedicationFreqCode()).isEqualTo("test_qd");
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(0).getExeRecordList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(1).getName()).isEqualTo("nursing_order_g2_t3");
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(1).getMedicationFreqCode()).isEqualTo("test_bid");
        assertThat(getDetailsResp.getGroup(1).getNursingOrder(1).getExeRecordList()).hasSize(2);

        // 删除护嘱
        DeleteNursingOrderReq deleteOrderReq = DeleteNursingOrderReq.newBuilder()
            .addId(getDetailsResp.getGroup(0).getNursingOrder(0).getId()).build();
        String deleteOrderReqJson = ProtoUtils.protoToJson(deleteOrderReq);
        GenericResp deleteOrderResp = orderService.deleteNursingOrder(deleteOrderReqJson);
        assertThat(deleteOrderResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroupList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrderList()).hasSize(2);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getName()).isEqualTo("nursing_order_g2_t1");
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(1).getName()).isEqualTo("nursing_order_g2_t3");
    }

        @Test
    public void testNursingOrderCreationsWithStopTime() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        PatientRecord patient = patientTestUtils.newPatientRecord(1002L, IN_ICU_VAL, deptId2);
        patient.setAdmissionTime(LocalDateTime.of(2024, 12, 23, 0, 0, 0));
        patient = patientRecordRepo.save(patient);
        final Long patientId = patient.getId();

        // 获取模板基本信息
        GetNursingOrderTemplatesReq getReq = GetNursingOrderTemplatesReq.newBuilder()
            .setDeptId(deptId).build();
        String getReqJson = ProtoUtils.protoToJson(getReq);
        GetNursingOrderTemplatesResp getResp = orderService.getNursingOrderTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupList()).hasSize(2);
        assertThat(getResp.getGroup(0).getNursingOrderTemplateList()).hasSize(2);
        assertThat(getResp.getGroup(1).getNursingOrderTemplateList()).hasSize(3);
        NursingOrderTemplatePB templateG2T1 = getResp.getGroup(1).getNursingOrderTemplate(0);
        Integer templateIdG2T1Qd = getResp.getGroup(1).getNursingOrderTemplate(0).getId();

        // 新增
        LocalDateTime orderUtc = LocalDateTime.of(2024, 12, 24, 0, 0, 0);  // 周二
        String orderTimeIso8601 = TimeUtils.toIso8601String(orderUtc, ZONE_ID);
        AddNursingOrdersReq addOrderReq = AddNursingOrdersReq.newBuilder()
            .setPid(patientId)
            .addOrder(
                NursingOrderPB.newBuilder()
                    .setOrderTemplateId(templateIdG2T1Qd)
                    .setName(templateG2T1.getName())
                    .setDurationType(templateG2T1.getDurationType())
                    .setMedicationFreqCode(templateG2T1.getMedicationFreqCode())
                    .setOrderTimeIso8601(orderTimeIso8601)
                    .setNote("note3")
                    .build()
            )
            .build();
        String addOrderReqJson = ProtoUtils.protoToJson(addOrderReq);
        AddNursingOrdersResp addOrderResp = orderService.addNursingOrders(addOrderReqJson);
        assertThat(addOrderResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 分解护嘱（正常分解）
        LocalDateTime start1Utc = LocalDateTime.of(2024, 12, 24, 0, 0, 0);  // 周二
        String start1Iso8601 = TimeUtils.toIso8601String(start1Utc, ZONE_ID);
        LocalDateTime end1Utc = LocalDateTime.of(2024, 12, 25, 0, 0, 0);
        String end1Iso8601 = TimeUtils.toIso8601String(end1Utc, ZONE_ID);
        GetNursingOrderDetailsReq getDetailsReq = GetNursingOrderDetailsReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(start1Iso8601)
            .setQueryEndIso8601(end1Iso8601)
            .build();
        String getDetailsReqJson = ProtoUtils.protoToJson(getDetailsReq);
        GetNursingOrderDetailsResp getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroupList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrderList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecordList()).hasSize(1);

        // 停止护嘱
        StopNursingOrderReq stopOrderReq = StopNursingOrderReq.newBuilder()
            .addId(getDetailsResp.getGroup(0).getNursingOrder(0).getId())
            .setStopTimeIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 12, 27, 0, 0, 0), ZONE_ID))
            .build();
        String stopOrderReqJson = ProtoUtils.protoToJson(stopOrderReq);
        GenericResp stopOrderResp = orderService.stopNursingOrder(stopOrderReqJson);
        assertThat(stopOrderResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 分解护嘱 （失败，超过stop_time）
        LocalDateTime start3Utc = LocalDateTime.of(2024, 12, 27, 0, 0, 0);  // 周三
        String start3Iso8601 = TimeUtils.toIso8601String(start3Utc, ZONE_ID);
        LocalDateTime end3Utc = LocalDateTime.of(2024, 12, 28, 0, 0, 0);
        String end3Iso8601 = TimeUtils.toIso8601String(end3Utc, ZONE_ID);
        getDetailsReq = GetNursingOrderDetailsReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(start3Iso8601)
            .setQueryEndIso8601(end3Iso8601)
            .build();
        getDetailsReqJson = ProtoUtils.protoToJson(getDetailsReq);
        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 分解护嘱 （失败，超过stop_time）
        LocalDateTime start2Utc = LocalDateTime.of(2024, 12, 28, 0, 0, 0);  // 周三
        String start2Iso8601 = TimeUtils.toIso8601String(start2Utc, ZONE_ID);
        LocalDateTime end2Utc = LocalDateTime.of(2024, 12, 29, 0, 0, 0);
        String end2Iso8601 = TimeUtils.toIso8601String(end2Utc, ZONE_ID);
        getDetailsReq = GetNursingOrderDetailsReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(start2Iso8601)
            .setQueryEndIso8601(end2Iso8601)
            .build();
        getDetailsReqJson = ProtoUtils.protoToJson(getDetailsReq);
        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
    }

    @Test
    public void testNursingExecutionRecords() {
        // 设置当前用户
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication = 
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);

        PatientRecord patient = patientTestUtils.newPatientRecord(1002L, IN_ICU_VAL, deptId2);
        patient.setAdmissionTime(LocalDateTime.of(2024, 12, 23, 0, 0, 0));
        patient = patientRecordRepo.save(patient);
        final Long patientId = patient.getId();

        // 获取模板基本信息
        GetNursingOrderTemplatesReq getReq = GetNursingOrderTemplatesReq.newBuilder()
            .setDeptId(deptId).build();
        String getReqJson = ProtoUtils.protoToJson(getReq);
        GetNursingOrderTemplatesResp getResp = orderService.getNursingOrderTemplates(getReqJson);
        assertThat(getResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getResp.getGroupList()).hasSize(2);
        assertThat(getResp.getGroup(0).getNursingOrderTemplateList()).hasSize(2);
        assertThat(getResp.getGroup(1).getNursingOrderTemplateList()).hasSize(3);
        NursingOrderTemplatePB templateG2T1 = getResp.getGroup(1).getNursingOrderTemplate(0);
        Integer templateIdG2T1Qd = getResp.getGroup(1).getNursingOrderTemplate(0).getId();

        // 新增
        LocalDateTime orderUtc = LocalDateTime.of(2024, 12, 24, 0, 0, 0);  // 周二
        String orderTimeIso8601 = TimeUtils.toIso8601String(orderUtc, ZONE_ID);
        AddNursingOrdersReq addOrderReq = AddNursingOrdersReq.newBuilder()
            .setPid(patientId)
            .addOrder(
                NursingOrderPB.newBuilder()
                    .setOrderTemplateId(templateIdG2T1Qd)
                    .setName(templateG2T1.getName())
                    .setDurationType(templateG2T1.getDurationType())
                    .setMedicationFreqCode(templateG2T1.getMedicationFreqCode())
                    .setOrderTimeIso8601(orderTimeIso8601)
                    .setNote("note3")
                    .build()
            )
            .build();
        String addOrderReqJson = ProtoUtils.protoToJson(addOrderReq);
        AddNursingOrdersResp addOrderResp = orderService.addNursingOrders(addOrderReqJson);
        assertThat(addOrderResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        // 分解护嘱 （一日一次，每天8：00）
        LocalDateTime start1Utc = LocalDateTime.of(2024, 12, 24, 0, 0, 0);  // 周二
        String start1Iso8601 = TimeUtils.toIso8601String(start1Utc, ZONE_ID);
        LocalDateTime end1Utc = LocalDateTime.of(2024, 12, 25, 0, 0, 0);
        String end1Iso8601 = TimeUtils.toIso8601String(end1Utc, ZONE_ID);
        GetNursingOrderDetailsReq getDetailsReq = GetNursingOrderDetailsReq.newBuilder()
            .setPid(patientId)
            .setQueryStartIso8601(start1Iso8601)
            .setQueryEndIso8601(end1Iso8601)
            .build();
        String getDetailsReqJson = ProtoUtils.protoToJson(getDetailsReq);
        GetNursingOrderDetailsResp getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroupList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrderList()).hasSize(1);
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecordList()).hasSize(1);

        // 更新护嘱执行记录 （完成）
        UpdateNursingExeRecordReq updateRecordReq = UpdateNursingExeRecordReq.newBuilder()
            .setId(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecord(0).getId())
            .setCompletedTimeIso8601(TimeUtils.toIso8601String(LocalDateTime.of(2024, 12, 24, 1, 0, 0), ZONE_ID))
            .build();
        String updateRecordReqJson = ProtoUtils.protoToJson(updateRecordReq);
        GenericResp updateRecordResp = orderService.updateNursingExeRecord(updateRecordReqJson);
        assertThat(updateRecordResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecord(0).getCompletedTimeIso8601())
            .isEqualTo(TimeUtils.toIso8601String(LocalDateTime.of(2024, 12, 24, 1, 0, 0), ZONE_ID));

        // 更新护嘱执行记录（取消完成）
        updateRecordReq = updateRecordReq.toBuilder()
            .setCompletedTimeIso8601("")
            .build();
        updateRecordReqJson = ProtoUtils.protoToJson(updateRecordReq);
        updateRecordResp = orderService.updateNursingExeRecord(updateRecordReqJson);
        assertThat(updateRecordResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecord(0).getCompletedTimeIso8601()).isEmpty();

        // 更新护嘱执行记录（删除）
        DeleteNursingExeRecordReq deleteRecordReq = DeleteNursingExeRecordReq.newBuilder()
            .setId(getDetailsResp.getGroup(0).getNursingOrder(0).getExeRecord(0).getId())
            .build();
        String deleteRecordReqJson = ProtoUtils.protoToJson(deleteRecordReq);
        GenericResp deleteRecordResp = orderService.deleteNursingExeRecord(deleteRecordReqJson);
        assertThat(deleteRecordResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        getDetailsResp = orderService.getNursingOrderDetails(getDetailsReqJson);
        assertThat(getDetailsResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(getDetailsResp.getGroupList()).hasSize(0);
    }

    private void initDepartments() {
        RbacDepartment dept2 = new RbacDepartment();
        dept2.setDeptId(deptId2);
        dept2.setDeptName("dept-2");
        deptRepo.save(dept2);

        orderConfig.initialize();
    }

    private final String ZONE_ID;
    private final String accountId;
    private final String deptId;
    private final String deptId2;
    final private Integer IN_ICU_VAL;

    private final ConfigProtoService protoService;
    private final NursingOrderConfigPB configPb;
    private final NursingOrderConfig orderConfig;
    private final NursingOrderService orderService;

    private final NursingOrderTemplateGroupRepository groupRepo;
    private final NursingOrderTemplateRepository templateRepo;
    private final NursingOrderRepository orderRepo;
    private final NursingExecutionRecordRepository recordRepo;
    private final RbacDepartmentRepository deptRepo;
    private final PatientRecordRepository patientRecordRepo;

    private PatientTestUtils patientTestUtils;
}