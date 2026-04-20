package com.jingyicare.jingyi_icis_engine.service.reports.common;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.util.Base64;
import java.util.Optional;

import javax.imageio.ImageIO;

import com.jingyicare.jingyi_icis_engine.utils.StrUtils;

public final class JfkImageUtils {
    private JfkImageUtils() {}

    public static Optional<byte[]> decodeSupportedImageBytes(String value) {
        if (StrUtils.isBlank(value)) return Optional.empty();
        String base64 = extractBase64(value);
        if (StrUtils.isBlank(base64)) return Optional.empty();
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        if (!isPng(bytes) && !isJpeg(bytes)) {
            return Optional.empty();
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                return Optional.empty();
            }
        } catch (Exception e) {
            return Optional.empty();
        }
        return Optional.of(bytes);
    }

    public static boolean isSupportedImageValue(String value) {
        return decodeSupportedImageBytes(value).isPresent();
    }

    private static String extractBase64(String value) {
        String trimmed = value == null ? "" : value.trim();
        int commaIdx = trimmed.indexOf(',');
        return commaIdx >= 0 ? trimmed.substring(commaIdx + 1).trim() : trimmed;
    }

    private static boolean isPng(byte[] bytes) {
        return bytes != null
            && bytes.length >= 8
            && (bytes[0] & 0xff) == 0x89
            && bytes[1] == 0x50
            && bytes[2] == 0x4e
            && bytes[3] == 0x47
            && bytes[4] == 0x0d
            && bytes[5] == 0x0a
            && bytes[6] == 0x1a
            && bytes[7] == 0x0a;
    }

    private static boolean isJpeg(byte[] bytes) {
        return bytes != null
            && bytes.length >= 3
            && (bytes[0] & 0xff) == 0xff
            && (bytes[1] & 0xff) == 0xd8
            && (bytes[2] & 0xff) == 0xff;
    }
}
