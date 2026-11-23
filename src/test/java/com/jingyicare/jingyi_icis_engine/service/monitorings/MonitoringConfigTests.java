package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.EntityManager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroup;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringGroupParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParam;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.DeptMonitoringParamId;
import com.jingyicare.jingyi_icis_engine.entity.monitorings.MonitoringParam;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringGroupBetaPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringParamPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.TypeEnumPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringGroupRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.DeptMonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.repository.monitorings.MonitoringParamRepository;
import com.jingyicare.jingyi_icis_engine.service.monitorings.meta.DeptMonMetaReader;
import com.jingyicare.jingyi_icis_engine.testutils.TestsBase;
import com.jingyicare.jingyi_icis_engine.utils.ProtoUtils;

@Transactional
class MonitoringConfigTests extends TestsBase {
    @BeforeEach
    void prepareGroups() {
        params.saveAll(List.of(
            MonitoringParam.builder().code(OVERRIDE_CODE).name("定制参数")
                .nameEn("Custom parameter").typePb(ProtoUtils.encodeValueMeta(GLOBAL_META))
                .balanceType(1).category(3).uiModalCode("custom-modal").chartSign("X")
                .displayOrder(91).build(),
            MonitoringParam.builder().code(FALLBACK_CODE).name("全局参数")
                .typePb(ProtoUtils.encodeValueMeta(GLOBAL_META))
                .balanceType(0).displayOrder(92).build()));
        deptParams.save(DeptMonitoringParam.builder()
            .id(new DeptMonitoringParamId(OVERRIDE_CODE, OVERRIDE_DEPT))
            .typePb(ProtoUtils.encodeValueMeta(DEPT_META)).build());

        // 插入顺序故意与显示顺序相反，保留分组和组内参数的排序语义。
        laterGroup = saveGroup(OVERRIDE_DEPT, "后组", 20);
        firstGroup = saveGroup(OVERRIDE_DEPT, "前组", 10);
        fallbackGroup = saveGroup(FALLBACK_DEPT, "默认组", 10);
        firstOverride = saveGroupParam(firstGroup, OVERRIDE_CODE, 20);
        firstFallback = saveGroupParam(firstGroup, FALLBACK_CODE, 10);
        laterOverride = saveGroupParam(laterGroup, OVERRIDE_CODE, 30);
        otherOverride = saveGroupParam(fallbackGroup, OVERRIDE_CODE, 20);
        otherFallback = saveGroupParam(fallbackGroup, FALLBACK_CODE, 10);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    void shouldApplyDeptOverridesWithoutChangingGlobalParamsInWritableTransaction() {
        List<MonitoringGroupBetaPB> overridden = deptMonMetaReader.listDeptMonitoringGroups(
            OVERRIDE_DEPT, MonEnums.GROUP_TYPE_MONITORING, List.of(), false);
        assertThat(overridden).containsExactly(
            expectedGroup(firstGroup, List.of(
                expectedFallback(firstFallback), expectedOverride(firstOverride, DEPT_META))),
            expectedGroup(laterGroup, List.of(expectedOverride(laterOverride, DEPT_META))));

        // 同一持久化上下文中切换科室，不应继承上一个科室的覆盖值。
        List<MonitoringGroupBetaPB> fallback = deptMonMetaReader.listDeptMonitoringGroups(
            FALLBACK_DEPT, MonEnums.GROUP_TYPE_MONITORING, List.of(), false);
        assertThat(fallback).containsExactly(expectedGroup(fallbackGroup, List.of(
            expectedFallback(otherFallback), expectedOverride(otherOverride, GLOBAL_META))));

        entityManager.flush();
        entityManager.clear();
        assertThat(params.findByCode(OVERRIDE_CODE).orElseThrow().getTypePb())
            .isEqualTo(ProtoUtils.encodeValueMeta(GLOBAL_META));
        assertThat(params.findByCode(FALLBACK_CODE).orElseThrow().getTypePb())
            .isEqualTo(ProtoUtils.encodeValueMeta(GLOBAL_META));
        assertThat(deptParams.findById(new DeptMonitoringParamId(OVERRIDE_CODE, OVERRIDE_DEPT))
            .orElseThrow().getTypePb()).isEqualTo(ProtoUtils.encodeValueMeta(DEPT_META));
    }

    @Test
    void readsOnlyRequestedParamsAndPreservesInvalidOverrideBehavior() {
        assertThat(deptMonMetaReader.getMonitoringParams(OVERRIDE_DEPT, Set.of())).isEmpty();
        Map<String, MonitoringParamPB> selected = deptMonMetaReader.getMonitoringParams(
            OVERRIDE_DEPT, Set.of(OVERRIDE_CODE, "missing"));
        assertThat(selected).containsOnlyKeys(OVERRIDE_CODE);
        assertThat(selected.get(OVERRIDE_CODE).getValueMeta()).isEqualTo(DEPT_META);
        assertThat(deptMonMetaReader.getMonitoringParam(FALLBACK_DEPT, OVERRIDE_CODE)
            .getValueMeta()).isEqualTo(GLOBAL_META);

        DeptMonitoringParam override = deptParams
            .findById(new DeptMonitoringParamId(OVERRIDE_CODE, OVERRIDE_DEPT)).orElseThrow();
        override.setTypePb("invalid base64");
        deptParams.saveAndFlush(override);

        assertThat(deptMonMetaReader.getMonitoringParam(OVERRIDE_DEPT, OVERRIDE_CODE)).isNull();
        assertThat(deptMonMetaReader.getMonitoringParams(
            OVERRIDE_DEPT, Set.of(OVERRIDE_CODE, FALLBACK_CODE))).containsOnlyKeys(FALLBACK_CODE);
        assertThat(deptMonMetaReader.getMonitoringParams(OVERRIDE_DEPT))
            .doesNotContainKey(OVERRIDE_CODE);
    }

    @Test
    void expandsDrainageDetailsInTheFullDictionary() {
        String tubeCode = "tube_ylg_configtest";
        String colorCode = tubeCode + "_color";
        params.save(MonitoringParam.builder().code(tubeCode).name("引流管")
            .typePb(ProtoUtils.encodeValueMeta(GLOBAL_META))
            .balanceType(MonEnums.BALANCE_TYPE_OUT).displayOrder(93).build());

        Map<String, MonitoringParamPB> allParams =
            deptMonMetaReader.getMonitoringParams(OVERRIDE_DEPT);
        assertThat(allParams).containsKeys(tubeCode, colorCode);
        assertThat(allParams.get(colorCode).getValueMeta().getValueType())
            .isEqualTo(TypeEnumPB.STRING);
        assertThat(deptMonMetaReader.getMonitoringParams(
            OVERRIDE_DEPT, Set.of(tubeCode, colorCode))).containsOnlyKeys(tubeCode);
        assertThat(deptMonMetaReader.getMonitoringParam(OVERRIDE_DEPT, colorCode)).isNull();
    }

    @Test
    void shouldNotInitializeDepartmentGroupsWhileReading() {
        String uninitializedDept = "test-config-uninitialized-dept";

        assertThat(deptMonMetaReader.listDeptMonitoringGroups(
            uninitializedDept, MonEnums.GROUP_TYPE_MONITORING, List.of(), false))
            .isEmpty();
        entityManager.flush();
        entityManager.clear();

        assertThat(groups.findByDeptIdAndIsDeletedFalse(uninitializedDept)).isEmpty();
    }

    @Test
    void shouldAppendDrainageParamsToOutputGroupWithCustomName() {
        int balanceGroupType = MonEnums.GROUP_TYPE_BALANCE;
        groups.save(DeptMonitoringGroup.builder()
            .deptId(OVERRIDE_DEPT).name("排出量").groupType(balanceGroupType)
            .balanceRole(MonEnums.BALANCE_TYPE_OUT).displayOrder(0)
            .sumTypePb(ProtoUtils.encodeValueMeta(GLOBAL_META)).isDeleted(false)
            .modifiedBy("test-account").modifiedAt(NOW).build());

        assertThat(deptMonMetaReader.listDeptMonitoringGroups(
            OVERRIDE_DEPT, balanceGroupType, List.of(OVERRIDE_CODE), true))
            .singleElement().satisfies(group -> {
                assertThat(group.getName()).isEqualTo("排出量");
                assertThat(group.getBalanceRole()).isEqualTo(MonEnums.BALANCE_TYPE_OUT);
                assertThat(group.getParamList()).extracting(MonitoringParamPB::getCode)
                    .contains(OVERRIDE_CODE);
            });
    }

    @Test
    void shouldKeepManagedParamsAvailableForSubsequentUpdates() {
        MonitoringParam managed = params.findByCode(OVERRIDE_CODE).orElseThrow();

        deptMonMetaReader.listDeptMonitoringGroups(
            OVERRIDE_DEPT, MonEnums.GROUP_TYPE_MONITORING, List.of(), false);

        // 不得通过 detach 或 clear 中断调用方的后续更新。
        assertThat(entityManager.contains(managed)).isTrue();
        assertThat(managed.getTypePb()).isEqualTo(ProtoUtils.encodeValueMeta(GLOBAL_META));
        managed.setName("调用方正常更新");
        entityManager.flush();
        entityManager.clear();

        MonitoringParam reloaded = params.findByCode(OVERRIDE_CODE).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("调用方正常更新");
        assertThat(reloaded.getTypePb()).isEqualTo(ProtoUtils.encodeValueMeta(GLOBAL_META));
    }

    private DeptMonitoringGroup saveGroup(String deptId, String name, int displayOrder) {
        return groups.save(DeptMonitoringGroup.builder().deptId(deptId).name(name)
            .groupType(MonEnums.GROUP_TYPE_MONITORING).displayOrder(displayOrder)
            .sumTypePb(ProtoUtils.encodeValueMeta(GLOBAL_META)).isDeleted(false)
            .modifiedBy("test-account").modifiedAt(NOW).build());
    }

    private DeptMonitoringGroupParam saveGroupParam(
        DeptMonitoringGroup group, String code, int displayOrder
    ) {
        return groupParams.save(DeptMonitoringGroupParam.builder()
            .deptMonitoringGroupId(group.getId()).monitoringParamCode(code)
            .displayOrder(displayOrder).isDeleted(false)
            .modifiedBy("test-account").modifiedAt(NOW).build());
    }

    private MonitoringGroupBetaPB expectedGroup(
        DeptMonitoringGroup group, List<MonitoringParamPB> expectedParams
    ) {
        return MonitoringGroupBetaPB.newBuilder().setId(group.getId()).setName(group.getName())
            .setGroupType(MonEnums.GROUP_TYPE_MONITORING).setDisplayOrder(group.getDisplayOrder())
            .setSumValueMeta(GLOBAL_META).addAllParam(expectedParams).build();
    }

    private MonitoringParamPB expectedOverride(
        DeptMonitoringGroupParam groupParam, ValueMetaPB meta
    ) {
        return MonitoringParamPB.newBuilder().setId(groupParam.getId()).setCode(OVERRIDE_CODE)
            .setName("定制参数").setNameEn("Custom parameter").setValueMeta(meta)
            .setBalanceType(1).setCategory(3).setUiModalCode("custom-modal").setChartSign("X")
            .setDisplayOrder(groupParam.getDisplayOrder()).build();
    }

    private MonitoringParamPB expectedFallback(DeptMonitoringGroupParam groupParam) {
        return MonitoringParamPB.newBuilder().setId(groupParam.getId()).setCode(FALLBACK_CODE)
            .setName("全局参数").setValueMeta(GLOBAL_META)
            .setDisplayOrder(groupParam.getDisplayOrder()).build();
    }

    @Autowired private DeptMonMetaReader deptMonMetaReader;
    @Autowired private MonitoringParamRepository params;
    @Autowired private DeptMonitoringParamRepository deptParams;
    @Autowired private DeptMonitoringGroupRepository groups;
    @Autowired private DeptMonitoringGroupParamRepository groupParams;
    @Autowired private EntityManager entityManager;

    private DeptMonitoringGroup firstGroup;
    private DeptMonitoringGroup laterGroup;
    private DeptMonitoringGroup fallbackGroup;
    private DeptMonitoringGroupParam firstOverride;
    private DeptMonitoringGroupParam firstFallback;
    private DeptMonitoringGroupParam laterOverride;
    private DeptMonitoringGroupParam otherOverride;
    private DeptMonitoringGroupParam otherFallback;

    private static final String OVERRIDE_CODE = "test_config_override";
    private static final String FALLBACK_CODE = "test_config_fallback";
    private static final String OVERRIDE_DEPT = "test-config-override-dept";
    private static final String FALLBACK_DEPT = "test-config-fallback-dept";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 19, 0, 0);
    private static final ValueMetaPB GLOBAL_META = ValueMetaPB.newBuilder()
        .setValueType(TypeEnumPB.FLOAT).setDecimalPlaces(2).setUnit("ml").build();
    private static final ValueMetaPB DEPT_META = ValueMetaPB.newBuilder()
        .setValueType(TypeEnumPB.FLOAT).setDecimalPlaces(3).setUnit("L").build();
}
