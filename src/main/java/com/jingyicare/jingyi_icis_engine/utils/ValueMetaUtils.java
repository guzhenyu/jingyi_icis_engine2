package com.jingyicare.jingyi_icis_engine.utils;

import java.lang.Math;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import lombok.*;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.proto.shared.ValueMeta.*;

@Slf4j
public class ValueMetaUtils {
     public static String extractAndFormatParamValue(
        GenericValuePB value, List<GenericValuePB> valueList, ValueMetaPB valueMeta
     ) {
        List<GenericValuePB> allValues = new ArrayList<>();
        if (valueMeta.getIsMultipleSelection()) {
            if (valueList.isEmpty()) allValues.add(value);
            else allValues.addAll(valueList);
        } else {
            allValues.add(value);
        }

        List<String> allValueStrs = allValues.stream()
            .map(v -> extractAndFormatParamValue(v, valueMeta))
            .collect(Collectors.toList());
        
        return String.join(", ", allValueStrs);
    }

    public static String extractAndFormatParamValue(
        GenericValuePB value, ValueMetaPB valueMeta
    ) {
        switch (valueMeta.getValueType()) {
            case FLOAT:
                return formatNumberValue(value.getFloatVal(), valueMeta);
            case DOUBLE:
                return formatNumberValue(value.getDoubleVal(), valueMeta);
            case BOOL:
                return String.valueOf(value.getBoolVal());
            case INT32:
                return String.valueOf(value.getInt32Val());
            case INT64:
                return String.valueOf(value.getInt64Val());
            case STRING:
                return value.getStrVal();
            default:
                log.error("Unsupported value type: {}", valueMeta.getValueType());
                return "";
        }
    }

    public static String getValueStrWithUnit(GenericValuePB genericValue, ValueMetaPB valueMeta) {
        if (genericValue == null || valueMeta == null) return "";
        return extractAndFormatParamValue(genericValue, valueMeta) + valueMeta.getUnit();
    }

    public static GenericValuePB getDefaultValue(ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case FLOAT:
                return GenericValuePB.newBuilder().setFloatVal(0).build();
            case DOUBLE:
                return GenericValuePB.newBuilder().setDoubleVal(0).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val(0).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val(0).build();
            case BOOL:
                return GenericValuePB.newBuilder().setBoolVal(false).build();
            case STRING:
                return GenericValuePB.newBuilder().setStrVal("").build();
            default:
                log.error("Unsupported value type: {}, for getting default value", valueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
    }

    public static GenericValuePB getValue(ValueMetaPB valueMetaPb, Double val) {
        switch (valueMetaPb.getValueType()) {
            case FLOAT:
                return GenericValuePB.newBuilder().setFloatVal(val.floatValue()).build();
            case DOUBLE:
                return GenericValuePB.newBuilder().setDoubleVal(val).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val(val.intValue()).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val(val.longValue()).build();
            default:
                log.error("Unsupported value type: {}, for getting value", valueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
    }

    public static GenericValuePB fromFloat(ValueMetaPB valueMetaPb, Float val) {
        GenericValuePB genericValuePB = null;
        switch (valueMetaPb.getValueType()) {
            case FLOAT:
                genericValuePB = GenericValuePB.newBuilder().setFloatVal(val).build();
                break;
            case DOUBLE:
                genericValuePB = GenericValuePB.newBuilder().setDoubleVal(val.doubleValue()).build();
                break;
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val(val.intValue()).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val(val.longValue()).build();
            default:
                log.error("Unsupported value type: {}, for getting value", valueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
        genericValuePB = formatParamValue(genericValuePB, valueMetaPb);
        return genericValuePB;
    }

    public static GenericValuePB addGenericValue(GenericValuePB val1, GenericValuePB val2, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case FLOAT:
                return GenericValuePB.newBuilder().setFloatVal(val1.getFloatVal() + val2.getFloatVal()).build();
            case DOUBLE:
                return GenericValuePB.newBuilder().setDoubleVal(val1.getDoubleVal() + val2.getDoubleVal()).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val(val1.getInt32Val() + val2.getInt32Val()).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val(val1.getInt64Val() + val2.getInt64Val()).build();
            default:
                log.error("Unsupported value type: {}, for adding generic values", valueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
    }

    public static GenericValuePB subtractGenericValue(GenericValuePB val1, GenericValuePB val2, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case FLOAT:
                return GenericValuePB.newBuilder().setFloatVal(val1.getFloatVal() - val2.getFloatVal()).build();
            case DOUBLE:
                return GenericValuePB.newBuilder().setDoubleVal(val1.getDoubleVal() - val2.getDoubleVal()).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val(val1.getInt32Val() - val2.getInt32Val()).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val(val1.getInt64Val() - val2.getInt64Val()).build();
            default:
                log.error("Unsupported value type: {}, for substracting generic values", valueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
    }

    public static GenericValuePB multiplyGenericValue(GenericValuePB val, int multiplier, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case FLOAT:
                return GenericValuePB.newBuilder().setFloatVal(val.getFloatVal() * multiplier).build();
            case DOUBLE:
                return GenericValuePB.newBuilder().setDoubleVal(val.getDoubleVal() * multiplier).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val(val.getInt32Val() * multiplier).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val(val.getInt64Val() * multiplier).build();
            default:
                log.error("Unsupported value type: {}, for multiplying generic values", valueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
    }

    public static GenericValuePB maxGenericValue(GenericValuePB val1, GenericValuePB val2, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case FLOAT:
                return GenericValuePB.newBuilder().setFloatVal(Math.max(val1.getFloatVal(), val2.getFloatVal())).build();
            case DOUBLE:
                return GenericValuePB.newBuilder().setDoubleVal(Math.max(val1.getDoubleVal(), val2.getDoubleVal())).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val(Math.max(val1.getInt32Val(), val2.getInt32Val())).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val(Math.max(val1.getInt64Val(), val2.getInt64Val())).build();
            default:
                log.error("Unsupported value type: {}, for maxing generic values", valueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
    }

    public static Boolean convertable(ValueMetaPB srcValueMetaPb, ValueMetaPB dstValueMetaPb) {
        TypeEnumPB srcType = srcValueMetaPb.getValueType();
        TypeEnumPB dstType = dstValueMetaPb.getValueType();
        if (srcType != TypeEnumPB.FLOAT && srcType != TypeEnumPB.DOUBLE && srcType != TypeEnumPB.INT32 && srcType != TypeEnumPB.INT64) return false;
        if (dstType != TypeEnumPB.FLOAT && dstType != TypeEnumPB.DOUBLE && dstType != TypeEnumPB.INT32 && dstType != TypeEnumPB.INT64) return false;
        return true;
    }

    public static GenericValuePB convertGenericValue(GenericValuePB value, ValueMetaPB valueMetaPb, ValueMetaPB targetValueMetaPb) {
        switch (targetValueMetaPb.getValueType()) {
            case FLOAT:
                return GenericValuePB.newBuilder().setFloatVal(convertToFloat(value, valueMetaPb)).build();
            case DOUBLE:
                return GenericValuePB.newBuilder().setDoubleVal(convertToDouble(value, valueMetaPb)).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val(convertToInt32(value, valueMetaPb)).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val(convertToInt64(value, valueMetaPb)).build();
            default:
                log.error("Unsupported value type: {}, for converting generic values", targetValueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
    }

    public static int convertToInt32(GenericValuePB value, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case INT32:
                return value.getInt32Val();
            case INT64:
                return (int) value.getInt64Val();
            case FLOAT:
                return (int) value.getFloatVal();
            case DOUBLE:
                return (int) value.getDoubleVal();
            default:
                return 0;
        }
    }

    public static Integer convertToInt32Obj(GenericValuePB value, ValueMetaPB valueMetaPb) {
        if (value == null) return null;
        return convertToInt32(value, valueMetaPb);
    }

    public static long convertToInt64(GenericValuePB value, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case INT32:
                return (long) value.getInt32Val();
            case INT64:
                return value.getInt64Val();
            case FLOAT:
                return (long) value.getFloatVal();
            case DOUBLE:
                return (long) value.getDoubleVal();
            default:
                return 0L;
        }
    }

    public static float convertToFloat(GenericValuePB value, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case INT32:
                return (float) value.getInt32Val();
            case INT64:
                return (float) value.getInt64Val();
            case FLOAT:
                return value.getFloatVal();
            case DOUBLE:
                return (float) value.getDoubleVal();
            default:
                return 0.0f;
        }
    }

    public static Float convertToFloatObj(GenericValuePB value, ValueMetaPB valueMetaPb) {
        if (value == null) return null;
        return convertToFloat(value, valueMetaPb);
    }

    public static double convertToDouble(GenericValuePB value, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case INT32:
                return (double) value.getInt32Val();
            case INT64:
                return (double) value.getInt64Val();
            case FLOAT:
                return (double) value.getFloatVal();
            case DOUBLE:
                return value.getDoubleVal();
            default:
                return 0.0;
        }
    }

    public static GenericValuePB convertFloatTo(float value, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case FLOAT:
                return GenericValuePB.newBuilder().setFloatVal(value).build();
            case DOUBLE:
                return GenericValuePB.newBuilder().setDoubleVal(value).build();
            case INT32:
                return GenericValuePB.newBuilder().setInt32Val((int) value).build();
            case INT64:
                return GenericValuePB.newBuilder().setInt64Val((long) value).build();
            default:
                log.error("Unsupported value type: {}, for converting float to generic value", valueMetaPb.getValueType());
                return GenericValuePB.newBuilder().setFloatVal(0).build();
        }
    }

    public static GenericValuePB toGenericValue(String valueStr, ValueMetaPB valueMeta) {
        try {
            switch (valueMeta.getValueType()) {
                case FLOAT:
                    return GenericValuePB.newBuilder().setFloatVal(Float.parseFloat(valueStr)).build();
                case DOUBLE:
                    return GenericValuePB.newBuilder().setDoubleVal(Double.parseDouble(valueStr)).build();
                case BOOL:
                    return GenericValuePB.newBuilder().setBoolVal(Boolean.parseBoolean(valueStr)).build();
                case INT32:
                    return GenericValuePB.newBuilder().setInt32Val(Integer.parseInt(valueStr)).build();
                case INT64:
                    return GenericValuePB.newBuilder().setInt64Val(Long.parseLong(valueStr)).build();
                case STRING:
                    return GenericValuePB.newBuilder().setStrVal(valueStr).build();
                default:
                    log.error("Unsupported value type: {}, for converting string to generic value", valueMeta.getValueType());
                    return null;
            }
        } catch (NumberFormatException e) {
            log.error("Failed to parse value: {}", valueStr);
            return null;
        }
    }

    public static Boolean convertToBool(GenericValuePB value, ValueMetaPB valueMetaPb) {
        switch (valueMetaPb.getValueType()) {
            case BOOL:
                return value.getBoolVal();
            case INT32:
                return value.getInt32Val() != 0;
            case INT64:
                return value.getInt64Val() != 0;
            case FLOAT:
                return value.getFloatVal() != 0;
            case DOUBLE:
                return value.getDoubleVal() != 0;
            case STRING:
                return value.getStrVal() != null && !value.getStrVal().isEmpty();
            default:
                log.error("Unsupported value type: {}, for converting to boolean", valueMetaPb.getValueType());
                return false;
        }
    }

    public static Boolean getBooleanObj(GenericValuePB value) {
        if (value == null) return null;
        return value.getBoolVal();
    }

    public static String getStringObj(GenericValuePB value) {
        if (value == null) return null;
        return value.getStrVal();
    }

    public static String formatNumberValue(Number value, ValueMetaPB valueMeta) {
        final int decimalPlaces = valueMeta.getDecimalPlaces();
        final boolean padZeroDecimal = valueMeta.getPadZeroDecimal();

        // 整数部分使用 "#"，表示没有前导零；如果你想至少显示一位整数，也可以用 "0" 代替
        StringBuilder pattern = new StringBuilder("0");

        // 只有当 decimalPlaces > 0 时，才加小数点与小数占位符
        if (decimalPlaces > 0) {
            pattern.append(".");
            for (int i = 0; i < decimalPlaces; i++) {
                pattern.append(padZeroDecimal ? "0" : "#");
            }
        }

        DecimalFormat decimalFormat = new DecimalFormat(pattern.toString());
        return decimalFormat.format(value);
    }

    public static Boolean isNumber(ValueMetaPB valueMeta) {
        return valueMeta.getValueType() == TypeEnumPB.FLOAT || valueMeta.getValueType() == TypeEnumPB.DOUBLE ||
            valueMeta.getValueType() == TypeEnumPB.INT32 || valueMeta.getValueType() == TypeEnumPB.INT64;
    }

    public static GenericValuePB normalize(GenericValuePB value, ValueMetaPB valueMeta) {
        if (valueMeta == null || !isNumber(valueMeta)) return value;

        switch (valueMeta.getValueType()) {
            case FLOAT: {
                float scale = (float) Math.pow(10, valueMeta.getDecimalPlaces());
                float rounded = Math.round(value.getFloatVal() * scale) / scale;
                return GenericValuePB.newBuilder().setFloatVal(rounded).build();
            }
            case DOUBLE: {
                double scale = Math.pow(10, valueMeta.getDecimalPlaces());
                double rounded = Math.round(value.getDoubleVal() * scale) / scale;
                return GenericValuePB.newBuilder().setDoubleVal(rounded).build();
            }
            default:
                return value;
        }
    }

    public static GenericValuePB formatParamValue(GenericValuePB value, ValueMetaPB valueMeta) {
        switch (valueMeta.getValueType()) {
            case FLOAT: {
                float scale = (float) Math.pow(10, valueMeta.getDecimalPlaces());
                return GenericValuePB.newBuilder().setFloatVal(
                    Math.round(value.getFloatVal() * scale) / scale
                ).build();
            }
            case DOUBLE: {
                double scale = Math.pow(10, valueMeta.getDecimalPlaces());
                return GenericValuePB.newBuilder().setDoubleVal(
                    Math.round(value.getDoubleVal() * scale) / scale
                ).build();
            }
            default:
                return value;
        }
    }

    public static float normalize(float floatVal, ValueMetaPB valueMeta) {
        if (valueMeta == null || !isNumber(valueMeta)) return floatVal;

        float scale = (float) Math.pow(10, valueMeta.getDecimalPlaces());
        float rounded = Math.round(floatVal * scale) / scale;
        return rounded;
    }

    public static float normalize(float floatVal, int decimalPlaces) {
        if (decimalPlaces < 0) return floatVal;

        float scale = (float) Math.pow(10, decimalPlaces);
        float rounded = Math.round(floatVal * scale) / scale;
        return rounded;
    }

    public static double normalize(double doubleVal, int decimalPlaces) {
        if (decimalPlaces < 0) return doubleVal;

        double scale = Math.pow(10, decimalPlaces);
        double rounded = Math.round(doubleVal * scale) / scale;
        return rounded;
    }
}