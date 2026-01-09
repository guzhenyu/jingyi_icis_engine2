package com.jingyicare.jingyi_icis_engine.testutils;

import java.time.LocalDateTime;

import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

public class ValueMetaTestUtils {
    public static ValueMetaPB newValueMetaFloat(Integer decimalPlaces, Boolean padZeroDecimal) {
        return ValueMetaPB.newBuilder()
            .setIsMultipleSelection(false)
            .setValueType(TypeEnumPB.FLOAT)
            .setDecimalPlaces(decimalPlaces)
            .setPadZeroDecimal(padZeroDecimal)
            .build();
    }
}