package com.jingyicare.jingyi_icis_engine.consts;

import java.util.List;

import com.jingyicare.jingyi_icis_engine.proto.shared.Shared.EnumValue;

public final class PatientConsts {
    private PatientConsts() {
    }

    // 患者血型编码及标准名称，统一生成 PatientEnumsV2 中的血型字典
    public static final Integer BLOOD_TYPE_UNKNOWN = 0;
    public static final Integer BLOOD_TYPE_A = 1;
    public static final Integer BLOOD_TYPE_B = 2;
    public static final Integer BLOOD_TYPE_O = 3;
    public static final Integer BLOOD_TYPE_AB = 4;
    public static final Integer BLOOD_RH_UNKNOWN = 0;
    public static final Integer BLOOD_RH_NEGATIVE = 1;
    public static final Integer BLOOD_RH_POSITIVE = 2;

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

    private static EnumValue value(int id, String name) {
        return EnumValue.newBuilder().setId(id).setName(name).build();
    }

    public static final Integer ADMISSION_STATUS_PRE_ADMISSION = 0;  // 待入科
    public static final Integer ADMISSION_STATUS_ADMITTED = 1;  // 已入科
    public static final Integer ADMISSION_STATUS_PRE_DISCHARGE = 2;  // 出科前
    public static final Integer ADMISSION_STATUS_DISCHARGED = 3;  // 已出科

}
