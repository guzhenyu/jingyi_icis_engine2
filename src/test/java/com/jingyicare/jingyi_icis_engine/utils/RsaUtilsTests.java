package com.jingyicare.jingyi_icis_engine.utils;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.util.Base64;

public class RsaUtilsTests {
    @Test
    public void testKeyGenerationAndEncryptionDecryption() {
        try {
            // Generate a new key pair
            KeyPair keyPair = RsaUtils.generateKeyPair();
            assertNotNull(keyPair);

            // Encode the public and private keys to Base64
            String base64PublicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
            String base64PrivateKey = Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded());

            // Create an instance of RsaUtils
            RsaUtils rsaUtils = new RsaUtils(base64PublicKey);
            assertTrue(rsaUtils.loadPrivateKey(base64PrivateKey));

            // Encrypt a string
            String originalString = "hello, jingyi";
            String encryptedString = rsaUtils.encrypt(originalString);
            assertNotEquals(originalString, encryptedString);

            // Decrypt the string
            String decryptedString = rsaUtils.decrypt(encryptedString);

            // Assert that the decrypted string is equal to the original string
            assertEquals(originalString, decryptedString);
        } catch (Exception e) {
            fail("Exception should not be thrown");
        }
    }
}