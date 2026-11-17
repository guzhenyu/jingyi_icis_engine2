package com.jingyicare.jingyi_icis_engine.service.patients;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_icis_engine.proto.IcisConfig.Config;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisPatient.PatientEnumsV2;
import com.jingyicare.jingyi_icis_engine.proto.config.IcisText.EnumTextPB;
import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;
import com.jingyicare.jingyi_icis_engine.service.ProtoConfig;
import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

@Component
public class PatientEnums {
    public static final Integer BED_TYPE_FIXED = 1;
    public static final Integer BED_TYPE_TEMP = 2;

    public static final Integer BLOOD_TYPE_UNKNOWN = 0;
    public static final Integer BLOOD_TYPE_A = 1;
    public static final Integer BLOOD_TYPE_B = 2;
    public static final Integer BLOOD_TYPE_O = 3;
    public static final Integer BLOOD_TYPE_AB = 4;
    public static final Integer BLOOD_RH_UNKNOWN = 0;
    public static final Integer BLOOD_RH_NEGATIVE = 1;
    public static final Integer BLOOD_RH_POSITIVE = 2;

    public static final Integer ADMISSION_STATUS_PRE_ADMISSION = 0;  // 待入科
    public static final Integer ADMISSION_STATUS_ADMITTED = 1;  // 已入科
    public static final Integer ADMISSION_STATUS_PRE_DISCHARGE = 2;  // 出科前
    public static final Integer ADMISSION_STATUS_DISCHARGED = 3;  // 已出科

    // SWITCH_TYPE_READMISSION_DISCHARGE SWITCH_TYPE_READMISSION_ADMIT 成对出现
    // 对应于patient_bed_history的两条记录
    public static final Integer SWITCH_TYPE_NORMAL = 0;  // 普通换床
    public static final Integer SWITCH_TYPE_READMISSION_DISCHARGE = 1;  // 重返出科
    public static final Integer SWITCH_TYPE_READMISSION_ADMIT = 2;  // 重返入科

    // 病人血型转换与前端配置共用标准字典。
    public static final List<EnumValue> BLOOD_TYPES = List.of(
        value(BLOOD_TYPE_A, "A"),
        value(BLOOD_TYPE_B, "B"),
        value(BLOOD_TYPE_O, "O"),
        value(BLOOD_TYPE_AB, "AB")
    );
    public static final List<EnumValue> BLOOD_RHS = List.of(
        value(BLOOD_RH_NEGATIVE, "RH-"),
        value(BLOOD_RH_POSITIVE, "RH+")
    );

    public PatientEnums(ProtoConfig protoConfig) {
        Config config = protoConfig.getConfig();
        PatientEnumsV2 patientEnums = config.getPatient().getEnumsV2();
        this.admissionStatusNames = Map.of(
            ADMISSION_STATUS_PRE_ADMISSION, patientEnums.getPendingAdmissionName(),
            ADMISSION_STATUS_ADMITTED, patientEnums.getInIcuName(),
            ADMISSION_STATUS_PRE_DISCHARGE, patientEnums.getPendingDischargedName(),
            ADMISSION_STATUS_DISCHARGED, patientEnums.getDischargedName());
        // 兼容尚未配置床型显示名称的旧版本配置。
        this.bedTypeNames = Map.of(
            BED_TYPE_FIXED, StrUtils.getStringOrDefault(patientEnums.getBedTypeFixed(), "固定授权"),
            BED_TYPE_TEMP, StrUtils.getStringOrDefault(patientEnums.getBedTypeTemp(), "临时授权"));

        Map<String, Integer> rhIds = new HashMap<>();
        for (EnumValue value : BLOOD_RHS) {
            rhIds.put(value.getName(), value.getId());
        }
        EnumTextPB enumText = config.getText().getEnumText();
        addRhAliases(rhIds, enumText.getBloodRhNegativeStrList(), BLOOD_RH_NEGATIVE);
        addRhAliases(rhIds, enumText.getBloodRhPositiveStrList(), BLOOD_RH_POSITIVE);
        this.bloodRhIds = Map.copyOf(rhIds);
    }

    public List<EnumValue> getBedTypes() {
        return List.of(
            value(BED_TYPE_FIXED, bedTypeNames.get(BED_TYPE_FIXED)),
            value(BED_TYPE_TEMP, bedTypeNames.get(BED_TYPE_TEMP))
        );
    }

    public String getAdmissionStatusName(Integer id) {
        if (id == null) return "";
        return admissionStatusNames.getOrDefault(id, "");
    }

    public Integer getBloodTypeId(String bloodType) {
        return BLOOD_TYPES.stream()
            .filter(value -> value.getName().equals(bloodType))
            .map(EnumValue::getId)
            .findFirst()
            .orElse(BLOOD_TYPE_UNKNOWN);
    }

    public Integer getBloodRhId(String bloodRh) {
        if (bloodRh == null) return BLOOD_RH_UNKNOWN;
        return bloodRhIds.getOrDefault(bloodRh, BLOOD_RH_UNKNOWN);
    }

    public String getBloodTypeName(Integer id) {
        return findEnumNameById(id, BLOOD_TYPES);
    }

    public String getBloodRhName(Integer id) {
        return findEnumNameById(id, BLOOD_RHS);
    }

    private static EnumValue value(int id, String name) {
        return EnumValue.newBuilder().setId(id).setName(name).build();
    }

    private void addRhAliases(Map<String, Integer> ids, List<String> aliases, Integer id) {
        for (String alias : aliases) {
            if (alias.isBlank()) {
                throw new IllegalArgumentException("Rh 别名不能为空或空白字符串");
            }
            Integer previousId = ids.putIfAbsent(alias, id);
            if (previousId != null && !previousId.equals(id)) {
                throw new IllegalArgumentException("Rh 别名冲突: " + alias);
            }
        }
    }

    private String findEnumNameById(Integer id, List<EnumValue> values) {
        if (id == null) return "";
        return values.stream()
            .filter(value -> value.getId() == id)
            .map(EnumValue::getName)
            .findFirst()
            .orElse("");
    }

    private final Map<Integer, String> admissionStatusNames;
    private final Map<Integer, String> bedTypeNames;
    private final Map<String, Integer> bloodRhIds;
}
