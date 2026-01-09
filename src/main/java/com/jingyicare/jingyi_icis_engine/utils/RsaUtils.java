package com.jingyicare.jingyi_icis_engine.utils;

import javax.crypto.Cipher;
import java.io.BufferedReader;
import java.io.FileWriter;
import java.io.FileReader;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.X509EncodedKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class RsaUtils {
    public static KeyPair generateKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE, SecureRandom.getInstanceStrong());
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static boolean generateKeyPairFiles(final String publicKeyFilePath, final String privateKeyFilePath) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(KEY_SIZE, SecureRandom.getInstanceStrong());

            KeyPair keyPair = generator.generateKeyPair();
            PublicKey publicKey = keyPair.getPublic();
            PrivateKey privateKey = keyPair.getPrivate();

            try (FileWriter pubWriter = new FileWriter(publicKeyFilePath)) {
                pubWriter.write("-----BEGIN PUBLIC KEY-----\n");
                pubWriter.write(Base64.getEncoder().encodeToString(publicKey.getEncoded()));
                pubWriter.write("\n-----END PUBLIC KEY-----\n");
            }

            try (FileWriter privWriter = new FileWriter(privateKeyFilePath)) {
                privWriter.write("-----BEGIN PRIVATE KEY-----\n");
                privWriter.write(Base64.getEncoder().encodeToString(privateKey.getEncoded()));
                privWriter.write("\n-----END PRIVATE KEY-----\n");
            }

            return true;

        } catch (NoSuchAlgorithmException | IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static PublicKey getJingyiPublicKey(String publicKeyStr) throws Exception {
        byte[] keyBytes = Base64.getDecoder().decode(publicKeyStr);
        // XOR the public key bytes with the CERT_XOR_KEY
        for (int i = 0; i < keyBytes.length; i++) {
            keyBytes[i] ^= CERT_XOR_KEY[i % CERT_XOR_KEY.length];
        }
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
    }

    public static String encodePassword(String password) {
        byte[] passwordBytes = password.getBytes();
        for (int i = 0; i < passwordBytes.length; i++) {
            passwordBytes[i] ^= PASSWD_XOR_KEY[i % PASSWD_XOR_KEY.length];
        }
        return Base64.getEncoder().encodeToString(passwordBytes);
    }

    public static String encodePassword(String password, BCryptPasswordEncoder encoder) {
        return encoder.encode(encodePassword(password));
    }

    public RsaUtils(String publicKeyStr) {
        if (!loadPublicKey(publicKeyStr)) {
            logger.error("Failed to load public key from application.properties:jingyi.rsa.publickey");
            throw new RuntimeException("Failed to load public key from application.properties:jingyi.rsa.publickey");
        }
    }

    public boolean loadPublicKey(final String publicKeyStr) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(publicKeyStr);
            publicKey = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(keyBytes));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean loadPublicKeyFromFile(final String publicKeyFilePath) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(publicKeyFilePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("-----")) sb.append(line);
                }
            }
            return loadPublicKey(sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean loadPrivateKey(final String privateKeyStr) {
        try {
            byte[] keyBytes = Base64.getDecoder().decode(privateKeyStr);
            privateKey = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(keyBytes));
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public boolean loadPrivateKeyFromFile(final String privateKeyFilePath) {
        try {
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new FileReader(privateKeyFilePath))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("-----")) sb.append(line);
                }
            }
            return loadPrivateKey(sb.toString());
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    public String encrypt(final String str) {
        try {
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey);
            byte[] encryptedData = cipher.doFinal(str.getBytes());
            return Base64.getEncoder().encodeToString(encryptedData);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public String decrypt(final String encryptedStr) {
        try {
            byte[] encryptedData = Base64.getDecoder().decode(encryptedStr);
            Cipher cipher = Cipher.getInstance("RSA");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] decryptedData = cipher.doFinal(encryptedData);
            return new String(decryptedData);
        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    private static final int KEY_SIZE = 4096;
    private static final byte[] CERT_XOR_KEY = new byte[] {
        (byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE, 
        (byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF
    };
    private static final byte[] PASSWD_XOR_KEY = new byte[] {
        (byte) 0x9A, (byte) 0xF3, (byte) 0xB8, (byte) 0xBA
    };
    private static final Logger logger = LoggerFactory.getLogger(RsaUtils.class);

    private PublicKey publicKey;
    private PrivateKey privateKey;
}
