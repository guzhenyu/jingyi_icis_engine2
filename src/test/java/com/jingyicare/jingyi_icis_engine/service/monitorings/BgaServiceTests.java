package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaCategoryMapping;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.BgaParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.users.RbacDepartment;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.*;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisBga.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.*;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaCategoryMappingRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.BgaParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.users.RbacDepartmentRepository;
import com.jingyicare.jingyi_icis_engine.service.ConfigProtoService;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.testutils.ValueMetaTestUtils;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;
import com.jingyicare.jingyi_icis_engine.utils.TimeUtils;

public class BgaServiceTests extends TestsBase {
    public BgaServiceTests(
        @Autowired ConfigProtoService protoService,
        @Autowired MonitoringConfig monitoringConfig,
        @Autowired BgaService bgaService,
        @Autowired BgaParamRepository bgaParamRepository,
        @Autowired BgaCategoryMappingRepository bgaCategoryMappingRepository,
        @Autowired MonitoringParamRepository monitoringParamRepository,
        @Autowired RbacDepartmentRepository deptRepository
    ) {
        this.accountId = "admin";
        this.protoService = protoService;
        this.monitoringConfig = monitoringConfig;
        this.bgaService = bgaService;
        this.bgaParamRepository = bgaParamRepository;
        this.bgaCategoryMappingRepository = bgaCategoryMappingRepository;
        this.monitoringParamRepository = monitoringParamRepository;
        this.deptRepository = deptRepository;
    }

    @Test
    public void testGetBgaParamsInitializesDefaults() {
        setCurrentUser();
        Assumptions.assumeFalse(protoService.getConfig().getBga().getBgaParamCodeList().isEmpty());
        String deptId = initDepartment("get-params");

        GetBgaParamsResp resp = bgaService.getBgaParams(ProtoUtils.protoToJson(
            GetBgaParamsReq.newBuilder().setDeptId(deptId).build()
        ));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        List<String> expectedParamCodes = protoService.getConfig().getBga().getBgaParamCodeList();
        assertThat(resp.getParamList()).hasSize(expectedParamCodes.size());
        assertThat(resp.getParamList()).extracting(BgaParamPB::getParamCode)
            .containsExactlyElementsOf(expectedParamCodes);
        assertThat(resp.getParamList()).allSatisfy(param -> {
            assertThat(param.getEnabled()).isFalse();
            assertThat(param.getLisResultCode()).isEmpty();
        });
    }

    @Test
    public void testSaveBgaParamPersistsLisResultCodeAndEnabled() {
        setCurrentUser();
        String deptId = initDepartment("save-param");
        String paramCode = createBgaParam(deptId);

        GetBgaParamsResp initialResp = bgaService.getBgaParams(ProtoUtils.protoToJson(
            GetBgaParamsReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaParamPB targetParam = initialResp.getParamList().stream()
            .filter(param -> param.getParamCode().equals(paramCode))
            .findFirst()
            .orElseThrow();

        GenericResp saveResp = bgaService.saveBgaParam(ProtoUtils.protoToJson(
            SaveBgaParamReq.newBuilder()
                .setDeptId(deptId)
                .setParamCode(targetParam.getParamCode())
                .setLisResultCode("  pH_result_code  ")
                .setEnabled(true)
                .build()
        ));
        assertThat(saveResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        BgaParam savedParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            deptId, targetParam.getParamCode()
        ).orElse(null);
        assertThat(savedParam).isNotNull();
        assertThat(savedParam.getEnabled()).isTrue();
        assertThat(savedParam.getLisResultCode()).isEqualTo("pH_result_code");

        GetBgaParamsResp updatedResp = bgaService.getBgaParams(ProtoUtils.protoToJson(
            GetBgaParamsReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaParamPB updatedParam = updatedResp.getParamList().stream()
            .filter(param -> param.getParamCode().equals(targetParam.getParamCode()))
            .findFirst()
            .orElseThrow();
        assertThat(updatedParam.getEnabled()).isTrue();
        assertThat(updatedParam.getLisResultCode()).isEqualTo("pH_result_code");

        GenericResp clearResp = bgaService.saveBgaParam(ProtoUtils.protoToJson(
            SaveBgaParamReq.newBuilder()
                .setDeptId(deptId)
                .setParamCode(targetParam.getParamCode())
                .setLisResultCode("   ")
                .setEnabled(false)
                .build()
        ));
        assertThat(clearResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        savedParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            deptId, targetParam.getParamCode()
        ).orElse(null);
        assertThat(savedParam).isNotNull();
        assertThat(savedParam.getEnabled()).isFalse();
        assertThat(savedParam.getLisResultCode()).isNull();
    }

    @Test
    public void testGetBgaParamsRestoresSoftDeletedDefaultParam() {
        setCurrentUser();
        Assumptions.assumeFalse(protoService.getConfig().getBga().getBgaParamCodeList().isEmpty());
        String deptId = initDepartment("restore-param");

        monitoringConfig.getBgaParamList(deptId);
        String targetParamCode = protoService.getConfig().getBga().getBgaParamCodeList().get(0);

        BgaParam deletedParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            deptId, targetParamCode
        ).orElseThrow();
        deletedParam.setEnabled(true);
        deletedParam.setLisResultCode("restore_me");
        softDelete(deletedParam);
        bgaParamRepository.save(deletedParam);

        GetBgaParamsResp restoredResp = bgaService.getBgaParams(ProtoUtils.protoToJson(
            GetBgaParamsReq.newBuilder().setDeptId(deptId).build()
        ));

        assertThat(restoredResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(restoredResp.getParamList()).extracting(BgaParamPB::getParamCode).contains(targetParamCode);

        BgaParam restoredParam = bgaParamRepository.findByDeptIdAndMonitoringParamCodeAndIsDeletedFalse(
            deptId, targetParamCode
        ).orElseThrow();
        assertThat(restoredParam.getIsDeleted()).isFalse();
        assertThat(restoredParam.getDeletedAt()).isNull();
        assertThat(restoredParam.getEnabled()).isTrue();
        assertThat(restoredParam.getLisResultCode()).isEqualTo("restore_me");
    }

    @Test
    public void testGetBgaCategoryInitializesConfiguredMappings() {
        setCurrentUser();
        String deptId = initDepartment("get-category");

        GetBgaCategoryResp resp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));

        assertThat(resp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        List<Integer> expectedCategoryIds = protoService.getConfig().getBga().getEnums().getBgaCategoryList()
            .stream()
            .map(EnumValue::getId)
            .toList();
        assertThat(resp.getMappingList()).hasSize(expectedCategoryIds.size());
        assertThat(resp.getMappingList()).extracting(BgaCategoryMappingPB::getBgaCategoryId)
            .containsExactlyElementsOf(expectedCategoryIds);
        assertThat(resp.getMappingList()).allSatisfy(mapping -> {
            assertThat(mapping.getDeptId()).isEqualTo(deptId);
            assertThat(mapping.getLisItemCode()).isEmpty();
        });
    }

    @Test
    public void testSaveBgaCategoryPersistsLisItemCode() {
        setCurrentUser();
        String deptId = initDepartment("save-category");

        GetBgaCategoryResp initialResp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaCategoryMappingPB targetMapping = initialResp.getMapping(0);

        GenericResp saveResp = bgaService.saveBgaCategory(ProtoUtils.protoToJson(
            SaveBgaCategoryReq.newBuilder()
                .setBgaCategoryMapping(targetMapping.toBuilder()
                    .setLisItemCode("  BLOOD_GAS_PANEL  ")
                    .build())
                .build()
        ));
        assertThat(saveResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        BgaCategoryMapping savedMapping = bgaCategoryMappingRepository
            .findByIdAndIsDeletedFalse(targetMapping.getId())
            .orElse(null);
        assertThat(savedMapping).isNotNull();
        assertThat(savedMapping.getDeptId()).isEqualTo(deptId);
        assertThat(savedMapping.getBgaCategoryId()).isEqualTo(targetMapping.getBgaCategoryId());
        assertThat(savedMapping.getLisItemCode()).isEqualTo("BLOOD_GAS_PANEL");

        GetBgaCategoryResp updatedResp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaCategoryMappingPB updatedMapping = updatedResp.getMappingList().stream()
            .filter(mapping -> mapping.getId() == targetMapping.getId())
            .findFirst()
            .orElseThrow();
        assertThat(updatedMapping.getLisItemCode()).isEqualTo("BLOOD_GAS_PANEL");

        GenericResp clearResp = bgaService.saveBgaCategory(ProtoUtils.protoToJson(
            SaveBgaCategoryReq.newBuilder()
                .setBgaCategoryMapping(updatedMapping.toBuilder()
                    .setLisItemCode("")
                    .build())
                .build()
        ));
        assertThat(clearResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());

        savedMapping = bgaCategoryMappingRepository.findByIdAndIsDeletedFalse(targetMapping.getId()).orElse(null);
        assertThat(savedMapping).isNotNull();
        assertThat(savedMapping.getLisItemCode()).isNull();
    }

    @Test
    public void testGetBgaCategoryRestoresSoftDeletedMapping() {
        setCurrentUser();
        String deptId = initDepartment("restore-category");

        GetBgaCategoryResp initialResp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));
        BgaCategoryMappingPB targetMapping = initialResp.getMapping(0);

        BgaCategoryMapping deletedMapping = bgaCategoryMappingRepository
            .findByIdAndIsDeletedFalse(targetMapping.getId())
            .orElseThrow();
        deletedMapping.setLisItemCode("RESTORE_CATEGORY");
        softDelete(deletedMapping);
        bgaCategoryMappingRepository.save(deletedMapping);

        GetBgaCategoryResp restoredResp = bgaService.getBgaCategory(ProtoUtils.protoToJson(
            GetBgaCategoryReq.newBuilder().setDeptId(deptId).build()
        ));

        assertThat(restoredResp.getRt().getCode()).isEqualTo(StatusCode.OK.ordinal());
        assertThat(restoredResp.getMappingList()).extracting(BgaCategoryMappingPB::getBgaCategoryId)
            .contains(targetMapping.getBgaCategoryId());

        BgaCategoryMapping restoredMapping = bgaCategoryMappingRepository
            .findByDeptIdAndBgaCategoryIdAndIsDeletedFalse(deptId, targetMapping.getBgaCategoryId())
            .orElseThrow();
        assertThat(restoredMapping.getIsDeleted()).isFalse();
        assertThat(restoredMapping.getDeletedAt()).isNull();
        assertThat(restoredMapping.getLisItemCode()).isEqualTo("RESTORE_CATEGORY");
    }

    private void setCurrentUser() {
        List<GrantedAuthority> authorities = AuthorityUtils.createAuthorityList("ROLE_1");
        UsernamePasswordAuthenticationToken authentication =
            new UsernamePasswordAuthenticationToken(accountId, null, authorities);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    private String initDepartment(String scenario) {
        String deptId = "bga-test-" + scenario + "-" + UUID.randomUUID().toString().substring(0, 8);
        RbacDepartment dept = new RbacDepartment();
        dept.setDeptId(deptId);
        dept.setDeptName("dept-" + scenario);
        deptRepository.save(dept);
        monitoringConfig.initialize();
        return deptId;
    }

    private String createBgaParam(String deptId) {
        String paramCode = "test_bga_param_" + UUID.randomUUID().toString().substring(0, 8);
        ValueMetaPB valueMeta = ValueMetaTestUtils.newValueMetaFloat(1, false);

        monitoringParamRepository.save(MonitoringParam.builder()
            .code(paramCode)
            .name("测试血气参数")
            .typePb(ProtoUtils.encodeValueMeta(valueMeta))
            .balanceType(0)
            .displayOrder(9999)
            .build());

        bgaParamRepository.save(BgaParam.builder()
            .deptId(deptId)
            .monitoringParamCode(paramCode)
            .displayOrder(1)
            .enabled(false)
            .isDeleted(false)
            .modifiedBy("system")
            .modifiedAt(TimeUtils.getNowUtc())
            .build());
        return paramCode;
    }

    private void softDelete(BgaParam param) {
        param.setIsDeleted(true);
        param.setDeletedBy(accountId);
        param.setDeletedByAccountName(accountId);
        param.setDeletedAt(TimeUtils.getNowUtc());
        param.setModifiedBy(accountId);
        param.setModifiedAt(TimeUtils.getNowUtc());
    }

    private void softDelete(BgaCategoryMapping mapping) {
        mapping.setIsDeleted(true);
        mapping.setDeletedBy(accountId);
        mapping.setDeletedByAccountName(accountId);
        mapping.setDeletedAt(TimeUtils.getNowUtc());
        mapping.setModifiedBy(accountId);
        mapping.setModifiedAt(TimeUtils.getNowUtc());
    }

    private final String accountId;

    private final ConfigProtoService protoService;
    private final MonitoringConfig monitoringConfig;
    private final BgaService bgaService;
    private final BgaParamRepository bgaParamRepository;
    private final BgaCategoryMappingRepository bgaCategoryMappingRepository;
    private final MonitoringParamRepository monitoringParamRepository;
    private final RbacDepartmentRepository deptRepository;
}
