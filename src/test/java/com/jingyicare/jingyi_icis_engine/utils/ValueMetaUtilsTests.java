package com.jingyicare.jingyi_icis_engine.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.ValueMetaPB;

public class ValueMetaUtilsTests {
    @Test
    public void testFormatNumberValue() {
        Float val = 1.23456789f;

        ValueMetaPB valueMeta = ValueMetaPB.newBuilder()
            .setDecimalPlaces(2)
            .setPadZeroDecimal(true)
            .build();

        String str = ValueMetaUtils.formatNumberValue(val, valueMeta);
        assertEquals("1.23", str);

        val = 1.2f;
        str = ValueMetaUtils.formatNumberValue(val, valueMeta);
        assertEquals("1.20", str);

        valueMeta = ValueMetaPB.newBuilder()
            .setDecimalPlaces(2)
            .setPadZeroDecimal(false)
            .build();

        val = 1.23456789f;
        str = ValueMetaUtils.formatNumberValue(val, valueMeta);
        assertEquals("1.23", str);

        val = 1.2f;
        str = ValueMetaUtils.formatNumberValue(val, valueMeta);
        assertEquals("1.2", str);

        val = 1.0f;
        str = ValueMetaUtils.formatNumberValue(val, valueMeta);
        assertEquals("1", str);

        val = 85f;
        valueMeta = ValueMetaPB.newBuilder()
            .setDecimalPlaces(0)
            .setPadZeroDecimal(false)
            .build();
        str = ValueMetaUtils.formatNumberValue(val, valueMeta);
        assertEquals("85", str);

        val = 0.123456789f;
        valueMeta = ValueMetaPB.newBuilder()
            .setDecimalPlaces(4)
            .setPadZeroDecimal(false)
            .build();
        str = ValueMetaUtils.formatNumberValue(val, valueMeta);
        assertEquals("0.1235", str);

        valueMeta = ValueMetaPB.newBuilder()
            .setDecimalPlaces(0)
            .setPadZeroDecimal(false)
            .build();
        str = ValueMetaUtils.formatNumberValue(val, valueMeta);
        assertEquals("0", str);
    }
}