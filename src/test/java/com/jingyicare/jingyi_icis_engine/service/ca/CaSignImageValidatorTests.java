package com.jingyicare.jingyi_icis_engine.service.ca;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import com.jingyicare.jingyi_cig.grpc.ca.CaProviderTypePB;
import com.jingyicare.jingyi_cig.grpc.ca.CaSignImageSourcePB;
import com.jingyicare.jingyi_cig.grpc.ca.CaSourceSystemPB;
import com.jingyicare.jingyi_cig.grpc.ca.GetSignImageResponsePB;
import com.jingyicare.jingyi_cig.grpc.ca.SignImagePB;
import com.jingyicare.jingyi_icis_engine.proto.IcisWebApi.RealtimeCaSignImageSourcePB;
import com.jingyicare.jingyi_icis_engine.service.ca.config.CaClientProperties;

class CaSignImageValidatorTests {
    @BeforeEach
    void setUp() {
        CaClientProperties properties = new CaClientProperties();
        properties.setMaxImageBytes(1024);
        validator = new CaSignImageValidator(properties);
    }

    @Test
    void validatesPngIdentitySourceMagicDigestAndDimensions() throws Exception {
        var result = validator.validate(response(
            PNG, "image/png", sha256(PNG), 1, 1, 7,
            CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS,
            CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER
        ), 7);

        assertEquals("image/png", result.mediaType());
        assertEquals(1, result.width());
        assertEquals(1, result.height());
        assertEquals(
            RealtimeCaSignImageSourcePB.REALTIME_CA_SIGN_IMAGE_SOURCE_CA_PROVIDER,
            result.source()
        );
    }

    @Test
    void validatesGifAndMapsTheCigAccountFallbackSource() throws Exception {
        var result = validator.validate(response(
            GIF, "image/gif", sha256(GIF), 1, 1, 7,
            CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS,
            CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_ACCOUNT_CA_SIGN_PIC_FALLBACK
        ), 7);

        assertEquals("image/gif", result.mediaType());
        assertEquals(
            RealtimeCaSignImageSourcePB.REALTIME_CA_SIGN_IMAGE_SOURCE_ACCOUNT_CA_SIGN_PIC_FALLBACK,
            result.source()
        );
    }

    @Test
    void rejectsMismatchedDigestAccountSourceMediaTypeAndDimensions() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(response(
            PNG, "image/png", "0".repeat(64), 1, 1, 7,
            CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS,
            CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER
        ), 7));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(response(
            PNG, "image/png", sha256(PNG), 1, 1, 8,
            CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS,
            CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER
        ), 7));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(response(
            PNG, "image/png", sha256(PNG), 1, 1, 7,
            CaSourceSystemPB.CA_SOURCE_SYSTEM_AIMS,
            CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER
        ), 7));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(response(
            PNG, "image/gif", sha256(PNG), 1, 1, 7,
            CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS,
            CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER
        ), 7));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(response(
            PNG, "image/png", sha256(PNG), 2, 1, 7,
            CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS,
            CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER
        ), 7));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(response(
            PNG, "image/png", sha256(PNG), 4097, 1, 7,
            CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS,
            CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER
        ), 7));
    }

    private static GetSignImageResponsePB response(
        byte[] bytes,
        String mediaType,
        String sha256,
        int width,
        int height,
        long accountId,
        CaSourceSystemPB sourceSystem,
        CaSignImageSourcePB source
    ) {
        return GetSignImageResponsePB.newBuilder()
            .setSourceSystem(sourceSystem)
            .setAccountId(accountId)
            .setProviderType(CaProviderTypePB.CA_PROVIDER_TYPE_BEIJING_CA)
            .setSource(source)
            .setImage(SignImagePB.newBuilder()
                .setData(ByteString.copyFrom(bytes))
                .setMediaType(mediaType)
                .setSha256(sha256)
                .setWidth(width)
                .setHeight(height))
            .build();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static final byte[] PNG = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    );
    private static final byte[] GIF = Base64.getDecoder().decode(
        "R0lGODlhAQABAIAAAAAAAP///ywAAAAAAQABAAACAUwAOw=="
    );
    private CaSignImageValidator validator;
}
