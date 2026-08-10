package com.jingyicare.jingyi_icis_engine.service.ca;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import com.jingyicare.jingyi_cig.grpc.ca.CaSignImageSourcePB;
import com.jingyicare.jingyi_cig.grpc.ca.CaSourceSystemPB;
import com.jingyicare.jingyi_cig.grpc.ca.GetSignImageResponsePB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.RealtimeCaSignImageSourcePB;
import com.jingyicare.jingyi_icis_engine.service.ca.config.CaClientProperties;

@Component
public class CaSignImageValidator {
    public CaSignImageValidator(CaClientProperties properties) {
        this.maxImageBytes = properties.getMaxImageBytes();
    }

    public ValidatedSignImage validate(GetSignImageResponsePB response, long expectedAccountId) {
        if (response == null
            || response.getSourceSystem() != CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS
            || response.getAccountId() != expectedAccountId
            || !response.hasImage()) {
            throw new IllegalArgumentException("CIG CA response identity does not match request");
        }
        RealtimeCaSignImageSourcePB source = SOURCE_MAP.get(response.getSource());
        if (source == null) throw new IllegalArgumentException("CIG CA response source is unspecified");

        byte[] data = response.getImage().getData().toByteArray();
        if (data.length == 0 || data.length > maxImageBytes) {
            throw new IllegalArgumentException("CIG CA image is empty or exceeds the size limit");
        }
        String detectedMediaType = detectMediaType(data);
        if (detectedMediaType == null || !detectedMediaType.equals(response.getImage().getMediaType())) {
            throw new IllegalArgumentException("CIG CA image media type does not match its bytes");
        }
        String digest = sha256(data);
        if (!digest.equals(response.getImage().getSha256())) {
            throw new IllegalArgumentException("CIG CA image SHA-256 does not match its bytes");
        }

        int declaredWidth = response.getImage().getWidth();
        int declaredHeight = response.getImage().getHeight();
        if (declaredWidth <= 0 || declaredWidth > MAX_IMAGE_DIMENSION
            || declaredHeight <= 0 || declaredHeight > MAX_IMAGE_DIMENSION
            || (long) declaredWidth * declaredHeight > MAX_IMAGE_PIXELS) {
            throw new IllegalArgumentException("CIG CA image dimensions exceed the safety limit");
        }

        BufferedImage image = decode(data);
        if (image.getWidth() != declaredWidth || image.getHeight() != declaredHeight) {
            throw new IllegalArgumentException("CIG CA image dimensions do not match its bytes");
        }
        return new ValidatedSignImage(data, detectedMediaType, digest, image.getWidth(), image.getHeight(), source);
    }

    private static BufferedImage decode(byte[] data) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(data));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IllegalArgumentException("CIG CA image cannot be decoded");
            }
            return image;
        } catch (IOException e) {
            throw new IllegalArgumentException("CIG CA image cannot be decoded", e);
        }
    }

    static String detectMediaType(byte[] data) {
        if (data.length >= 8
            && data[0] == (byte) 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G'
            && data[4] == '\r' && data[5] == '\n' && data[6] == 0x1a && data[7] == '\n') return "image/png";
        if (data.length >= 3
            && data[0] == (byte) 0xff && data[1] == (byte) 0xd8 && data[2] == (byte) 0xff) return "image/jpeg";
        if (data.length >= 6
            && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8'
            && (data[4] == '7' || data[4] == '9') && data[5] == 'a') return "image/gif";
        return null;
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record ValidatedSignImage(
        byte[] data,
        String mediaType,
        String sha256,
        int width,
        int height,
        RealtimeCaSignImageSourcePB source
    ) {}

    private static final Map<CaSignImageSourcePB, RealtimeCaSignImageSourcePB> SOURCE_MAP = Map.of(
        CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER,
        RealtimeCaSignImageSourcePB.REALTIME_CA_SIGN_IMAGE_SOURCE_CA_PROVIDER,
        CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_ACCOUNT_CA_SIGN_PIC_FALLBACK,
        RealtimeCaSignImageSourcePB.REALTIME_CA_SIGN_IMAGE_SOURCE_ACCOUNT_CA_SIGN_PIC_FALLBACK
    );

    private final int maxImageBytes;
    private static final int MAX_IMAGE_DIMENSION = 4096;
    private static final long MAX_IMAGE_PIXELS = 16L * 1024 * 1024;
}
