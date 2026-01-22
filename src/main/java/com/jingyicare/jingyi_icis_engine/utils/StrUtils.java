package com.jingyicare.jingyi_icis_engine.utils;

import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinCaseType;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import net.sourceforge.pinyin4j.format.HanyuPinyinVCharType;
import net.sourceforge.pinyin4j.format.exception.BadHanyuPinyinOutputFormatCombination;

public class StrUtils {
    public static String snakeToCamel(String snakeStr) {
        StringBuilder sb = new StringBuilder();
        String[] parts = snakeStr.split("_");
        Boolean isHeadPart = true;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].isEmpty()) continue;
            if (isHeadPart) {
                sb.append(parts[i]);
                isHeadPart = false;
            } else {
                sb.append(parts[i].substring(0, 1).toUpperCase());
                sb.append(parts[i].substring(1));
            }
        }
        return sb.toString();
    }

    public static boolean isBlank(String str) {
        return str == null || str.trim().isEmpty();
    }

    public static String getStringOrDefault(String str, String defaultStr) {
        if (isBlank(str)) return defaultStr;
        return str;
    }

    public static String toPinyinInitials(String chineseStr) {
        StringBuilder pinyinInitials = new StringBuilder();
        try {
            HanyuPinyinOutputFormat outputFormat = new HanyuPinyinOutputFormat();

            // fix case type to lowercase firstly, change VChar and Tone
            // combination
            outputFormat.setCaseType(HanyuPinyinCaseType.LOWERCASE);
            outputFormat.setVCharType(HanyuPinyinVCharType.WITH_V);
            outputFormat.setToneType(HanyuPinyinToneType.WITHOUT_TONE);

            for (int i = 0; i < chineseStr.length(); i++) {
                // 一些汉字有多音字，因此返回的是一个数组
                String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(chineseStr.charAt(i), outputFormat);
                
                if (pinyinArray != null && pinyinArray.length > 0) {
                    pinyinInitials.append(pinyinArray[0].charAt(0));
                } else {
                    pinyinInitials.append(chineseStr.charAt(i));
                }
            }
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            e.printStackTrace();
        }
        return pinyinInitials.toString();
    }

    public static String toPinyin(String chineseStr) {
        StringBuilder pinyin = new StringBuilder();
        try {
            HanyuPinyinOutputFormat outputFormat = new HanyuPinyinOutputFormat();

            // fix case type to lowercase firstly, change VChar and Tone
            // combination
            outputFormat.setCaseType(HanyuPinyinCaseType.LOWERCASE);
            outputFormat.setVCharType(HanyuPinyinVCharType.WITH_V);
            outputFormat.setToneType(HanyuPinyinToneType.WITHOUT_TONE);

            for (int i = 0; i < chineseStr.length(); i++) {
                // 一些汉字有多音字，因此返回的是一个数组
                String[] pinyinArray = PinyinHelper.toHanyuPinyinStringArray(chineseStr.charAt(i), outputFormat);
                
                if (pinyinArray != null && pinyinArray.length > 0) {
                    pinyin.append(pinyinArray[0]);
                } else {
                    pinyin.append(chineseStr.charAt(i));
                }
            }
        } catch (BadHanyuPinyinOutputFormatCombination e) {
            e.printStackTrace();
        }
        return pinyin.toString();
    }

    public static double parseDoubleOrDefault(String str, double defaultValue) {
        if (isBlank(str)) return defaultValue;
        try {
            return Double.parseDouble(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static Integer parseIntOrDefault(String str, int defaultValue) {
        if (isBlank(str)) return defaultValue;
        try {
            return Integer.parseInt(str);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public static String trimRightSpaces(String s) {
        if (s == null || s.isEmpty()) return s;
        int i = s.length() - 1;
        while (i >= 0 && s.charAt(i) == ' ') i--;
        return s.substring(0, i + 1);
    }

    public static String formatDouble(double num, int decimalPlaces) {
        if (decimalPlaces < 0) decimalPlaces = 0;
        double scale = Math.pow(10, decimalPlaces);
        long rounded = Math.round(num * scale);
        if (decimalPlaces == 0) {
            return String.valueOf(rounded);
        }
        double result = rounded / scale;
        String format = "%." + decimalPlaces + "f";
        return String.format(format, result);
    }
}
