package com.jingyicare.jingyi_icis_engine.service.ca;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Component;

import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

import com.jingyicare.jingyi_icis_engine.service.ca.client.CigCaClient;
import com.jingyicare.jingyi_icis_engine.utils.Pair;

@Component
@Slf4j
public class CaSignatureImageService {
    public CaSignatureImageService(CigCaClient client, CaSignImageValidator validator) {
        this.client = client;
        this.validator = validator;
    }

    public Pair<Boolean, byte[]> getPng(long accountId) {
        if (accountId <= 0 || !client.isEnabled()) {
            return NO_IMAGE;
        }
        try {
            CaSignImageValidator.ValidatedSignImage image =
                validator.validate(client.getSignImage(accountId), accountId);
            if ("image/png".equals(image.mediaType())) {
                return new Pair<>(true, image.data());
            }
            // JPEG 转为 PNG，GIF 仅读首帧并保留其透明度。
            BufferedImage frame = ImageIO.read(new ByteArrayInputStream(image.data()));
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            if (frame == null || !ImageIO.write(frame, "png", png)) {
                throw new IOException("Could not encode the signature as PNG");
            }
            return new Pair<>(true, png.toByteArray());
        } catch (StatusRuntimeException e) {
            log.warn("CA signature lookup failed, gRPC status={}", e.getStatus().getCode());
        } catch (RuntimeException | IOException e) {
            log.warn("CA signature lookup failed, reason={}", e.getClass().getSimpleName());
        }
        return NO_IMAGE;
    }

    private static final Pair<Boolean, byte[]> NO_IMAGE = new Pair<>(false, new byte[0]);
    private final CigCaClient client;
    private final CaSignImageValidator validator;
}
