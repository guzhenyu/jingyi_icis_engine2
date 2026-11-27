package com.jingyicare.jingyi_icis_engine.service.monitorings;

import static com.jingyicare.jingyi_icis_engine.utils.StrUtils.getStringOrDefault;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonEnumsPB;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisMonitoring.MonitoringEnumsPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Component
public final class MonEnums {
    public MonEnums(ProtoConfig protoConfig) {
        MonEnumsPB enums = protoConfig.getConfig().getMonitoring().getEnums();
        this.groupTypeNames = Map.of(
            GROUP_TYPE_BALANCE, getStringOrDefault(enums.getGroupTypeBalance(), "出入量分组"),
            GROUP_TYPE_MONITORING, getStringOrDefault(enums.getGroupTypeMonitoring(), "观察项分组")
        );
        this.balanceRoleNames = Map.of(
            BALANCE_TYPE_NAN, getStringOrDefault(enums.getBalanceNan(), "不进入平衡统计的量"),
            BALANCE_TYPE_IN, getStringOrDefault(enums.getBalanceIn(), "入量"),
            BALANCE_TYPE_OUT, getStringOrDefault(enums.getBalanceOut(), "出量"),
            BALANCE_TYPE_NET, getStringOrDefault(enums.getBalanceNet(), "平衡量")
        );
        this.paramCategoryNames = Map.of(
            PARAM_CATEGORY_PATIENT, getStringOrDefault(enums.getParamCategoryPatient(), "患者"),
            PARAM_CATEGORY_MONITOR, getStringOrDefault(enums.getParamCategoryMonitor(), "监护仪"),
            PARAM_CATEGORY_VENT, getStringOrDefault(enums.getParamCategoryVent(), "呼吸机"),
            PARAM_CATEGORY_CRRT, getStringOrDefault(enums.getParamCategoryCrrt(), "血滤机"),
            PARAM_CATEGORY_BGA, getStringOrDefault(enums.getParamCategoryBga(), "血气机"),
            PARAM_CATEGORY_ECMO,
            getStringOrDefault(enums.getParamCategoryEcmo(), "体外膜肺氧合机")
        );
        String drainageTubeType = protoConfig.getConfig().getTube().getDrainageTubeType();
        this.drainageTubeParamPrefix = "tube_" + StrUtils.toPinyinInitials(drainageTubeType) + "_";
    }

    public Map<Integer, String> getGroupTypeNames() {
        return groupTypeNames;
    }

    public Map<Integer, String> getBalanceRoleNames() {
        return balanceRoleNames;
    }

    public Map<Integer, String> getParamCategoryNames() {
        return paramCategoryNames;
    }

    public boolean isDrainageTubeParam(String paramCode) {
        return paramCode.startsWith(drainageTubeParamPrefix);
    }

    public MonitoringEnumsPB toMonitoringEnumsPB() {
        return MonitoringEnumsPB.newBuilder()
            .setGroupTypeBalance(value(GROUP_TYPE_BALANCE, groupTypeNames.get(GROUP_TYPE_BALANCE)))
            .setGroupTypeMonitoring(
                value(GROUP_TYPE_MONITORING, groupTypeNames.get(GROUP_TYPE_MONITORING)))
            .setBalanceNan(value(BALANCE_TYPE_NAN, balanceRoleNames.get(BALANCE_TYPE_NAN)))
            .setBalanceIn(value(BALANCE_TYPE_IN, balanceRoleNames.get(BALANCE_TYPE_IN)))
            .setBalanceOut(value(BALANCE_TYPE_OUT, balanceRoleNames.get(BALANCE_TYPE_OUT)))
            .setBalanceNet(value(BALANCE_TYPE_NET, balanceRoleNames.get(BALANCE_TYPE_NET)))
            .setParamCategoryPatient(
                value(PARAM_CATEGORY_PATIENT, paramCategoryNames.get(PARAM_CATEGORY_PATIENT)))
            .setParamCategoryMonitor(
                value(PARAM_CATEGORY_MONITOR, paramCategoryNames.get(PARAM_CATEGORY_MONITOR)))
            .setParamCategoryVent(
                value(PARAM_CATEGORY_VENT, paramCategoryNames.get(PARAM_CATEGORY_VENT)))
            .setParamCategoryCrrt(
                value(PARAM_CATEGORY_CRRT, paramCategoryNames.get(PARAM_CATEGORY_CRRT)))
            .setParamCategoryBga(
                value(PARAM_CATEGORY_BGA, paramCategoryNames.get(PARAM_CATEGORY_BGA)))
            .setParamCategoryEcmo(
                value(PARAM_CATEGORY_ECMO, paramCategoryNames.get(PARAM_CATEGORY_ECMO)))
            // 保留旧配置响应中的字典项。
            .setParamUiModalNan(value(1, "无特殊编辑对话框"))
            .build();
    }

    private static EnumValue value(int id, String name) {
        return EnumValue.newBuilder().setId(id).setName(name).build();
    }

    public static final int GROUP_TYPE_BALANCE = 1;
    public static final int GROUP_TYPE_MONITORING = 2;

    // 观察项的 balance_type 与科室分组的 balance_role 共用这组编号。
    public static final int BALANCE_TYPE_NAN = 0;
    public static final int BALANCE_TYPE_IN = 1;
    public static final int BALANCE_TYPE_OUT = 2;
    public static final int BALANCE_TYPE_NET = 3;

    public static final int PARAM_CATEGORY_PATIENT = 1;
    public static final int PARAM_CATEGORY_MONITOR = 2;
    public static final int PARAM_CATEGORY_VENT = 3;
    public static final int PARAM_CATEGORY_CRRT = 4;
    public static final int PARAM_CATEGORY_BGA = 5;
    public static final int PARAM_CATEGORY_ECMO = 6;

    // 出入量合计行与统计使用的固定观察项编码。
    public static final String PARAM_CODE_SUMMARY = "all";
    public static final String PARAM_CODE_SUMMARY_TXT = "合计";
    public static final String NET_HOURLY_IN_CODE = "hourly_intake";
    public static final String NET_HOURLY_OUT_CODE = "hourly_output";
    public static final String NET_HOURLY_NET_CODE = "hourly_balance";
    public static final String NET_ACCUMULATED_IN_CODE = "total_intake";
    public static final String NET_ACCUMULATED_OUT_CODE = "total_output";
    public static final String NET_ACCUMULATED_NET_CODE = "total_balance";
    public static final String DAILY_TOTAL_INTAKE_CODE = "daily_total_intake";
    public static final String DAILY_TOTAL_OUTPUT_CODE = "daily_total_output";
    public static final String DAILY_TOTAL_BALANCE_CODE = "daily_total_balance";

    public static final int DEFAULT_HOURLY_APPROX_SECONDS = 600;
    public static final int DEFAULT_PARAM_APPROX_SECONDS = 1800;
    public static final List<String> DEFAULT_PARAM_CODE_TO_APPROX =
        List.of("nibp_s", "nibp_d", "nibp_m");

    private final Map<Integer, String> groupTypeNames;
    private final Map<Integer, String> balanceRoleNames;
    private final Map<Integer, String> paramCategoryNames;
    private final String drainageTubeParamPrefix;
}
