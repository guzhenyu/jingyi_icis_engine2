package com.jingyicare.jingyi_icis_engine.service.ca;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import com.google.protobuf.ByteString;
import io.grpc.Status;

import com.jingyicare.jingyi_cig.grpc.ca.CaSignImageSourcePB;
import com.jingyicare.jingyi_cig.grpc.ca.CaSourceSystemPB;
import com.jingyicare.jingyi_cig.grpc.ca.GetSignImageResponsePB;
import com.jingyicare.jingyi_cig.grpc.ca.SignImagePB;
import com.jingyicare.jingyi_icis_engine.service.ca.client.CigCaClient;
import com.jingyicare.jingyi_icis_engine.service.ca.config.CaClientProperties;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

class CaSignatureImageServiceTests {
    @Test
    void disabledClientOrInvalidIdSkipsLookup() {
        assertNoImage(service.getPng(55));
        when(client.isEnabled()).thenReturn(true);
        assertNoImage(service.getPng(0));
        verify(client, never()).getSignImage(anyLong());
    }

    @Test
    void returnsOriginalPngBytesAndDoesNotCacheFailure() throws Exception {
        byte[] png = encodedImage("png");
        when(client.isEnabled()).thenReturn(true);
        when(client.getSignImage(55))
            .thenThrow(Status.DEADLINE_EXCEEDED.asRuntimeException())
            .thenReturn(response(55, "image/png", png));

        assertNoImage(service.getPng(55));
        Pair<Boolean, byte[]> result = service.getPng(55);
        assertThat(result.getFirst()).isTrue();
        assertThat(result.getSecond()).isEqualTo(png);
        verify(client, times(2)).getSignImage(55);
    }

    @Test
    void convertsJpegToPngPreservingDecodedPixels() throws Exception {
        byte[] jpeg = encodedImage("jpeg");
        when(client.isEnabled()).thenReturn(true);
        when(client.getSignImage(55)).thenReturn(response(55, "image/jpeg", jpeg));

        Pair<Boolean, byte[]> result = service.getPng(55);
        assertThat(result.getFirst()).isTrue();
        assertThat(CaSignImageValidator.detectMediaType(result.getSecond())).isEqualTo("image/png");
        BufferedImage input = ImageIO.read(new ByteArrayInputStream(jpeg));
        BufferedImage output = ImageIO.read(new ByteArrayInputStream(result.getSecond()));
        assertThat(output.getWidth()).isEqualTo(input.getWidth());
        assertThat(output.getHeight()).isEqualTo(input.getHeight());
        assertThat(output.getRGB(0, 0)).isEqualTo(input.getRGB(0, 0));
        assertThat(output.getRGB(1, 0)).isEqualTo(input.getRGB(1, 0));
    }

    @Test
    void embedsFirstGifFrameInPdfWithTransparentAndOpaquePixels() throws Exception {
        byte[] gif = Base64.getDecoder().decode(TWO_FRAME_GIF);
        when(client.isEnabled()).thenReturn(true);
        when(client.getSignImage(55)).thenReturn(response(55, "image/gif", gif));

        Pair<Boolean, byte[]> result = service.getPng(55);
        assertThat(result.getFirst()).isTrue();
        byte[] bytes = result.getSecond();
        assertThat(CaSignImageValidator.detectMediaType(bytes)).isEqualTo("image/png");
        try (PDDocument document = new PDDocument()) {
            PDImageXObject signature = PDImageXObject.createFromByteArray(document, bytes, "sign");
            BufferedImage frame = signature.getImage();
            assertThat(frame.getWidth()).isEqualTo(2);
            assertThat(frame.getHeight()).isEqualTo(1);
            assertThat(frame.getRGB(0, 0) >>> 24).isZero();
            assertThat(frame.getRGB(1, 0)).isEqualTo(0xff000000);
        }
    }

    @Test
    void rejectsAnImageForADifferentAccount() throws Exception {
        when(client.isEnabled()).thenReturn(true);
        when(client.getSignImage(55)).thenReturn(response(3, "image/png", encodedImage("png")));
        assertNoImage(service.getPng(55));
    }

    private void assertNoImage(Pair<Boolean, byte[]> result) {
        assertThat(result.getFirst()).isFalse();
        assertThat(result.getSecond()).isEmpty();
    }

    private byte[] encodedImage(String format) throws IOException {
        BufferedImage input = new BufferedImage(2, 1, BufferedImage.TYPE_INT_RGB);
        input.setRGB(0, 0, 0xff2070c0);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertThat(ImageIO.write(input, format, output)).isTrue();
        return output.toByteArray();
    }

    private GetSignImageResponsePB response(long id, String mediaType, byte[] bytes)
        throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        return GetSignImageResponsePB.newBuilder()
            .setSourceSystem(CaSourceSystemPB.CA_SOURCE_SYSTEM_ICIS)
            .setAccountId(id)
            .setSource(CaSignImageSourcePB.CA_SIGN_IMAGE_SOURCE_CA_PROVIDER)
            .setImage(SignImagePB.newBuilder().setData(ByteString.copyFrom(bytes))
                .setMediaType(mediaType).setSha256(HexFormat.of().formatHex(digest))
                .setWidth(2).setHeight(1))
            .build();
    }

    // 首帧为透明和黑色两个像素，第二帧的两个像素均为黑色。
    private static final String TWO_FRAME_GIF =
        "R0lGODlhAgABAIAAAP///wAAACH5BAEAAAAALAAAAAACAAEAAAICRAoA"
        + "IfkEAAAAAAAsAAAAAAIAAQAAAgJMCgA7";
    private final CigCaClient client = mock(CigCaClient.class);
    private final CaSignatureImageService service = new CaSignatureImageService(
        client, new CaSignImageValidator(new CaClientProperties()));
}
