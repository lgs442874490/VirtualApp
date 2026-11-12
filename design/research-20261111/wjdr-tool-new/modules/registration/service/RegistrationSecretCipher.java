/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.springframework.beans.factory.annotation.Autowired
 *  org.springframework.beans.factory.annotation.Value
 *  org.springframework.stereotype.Service
 */
package com.wjdr.tool.modules.registration.service;

import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class RegistrationSecretCipher {
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] key;

    @Autowired
    public RegistrationSecretCipher(@Value(value="${wjdr.jwt.secret}") String jwtSecret) {
        this(RegistrationSecretCipher.keyBytes(jwtSecret));
    }

    RegistrationSecretCipher(byte[] key) {
        this.key = Arrays.copyOf(key, key.length);
    }

    public String encrypt(String plainText) {
        if (plainText == null || plainText.isEmpty()) {
            throw new IllegalArgumentException("\u5f85\u52a0\u5bc6\u6587\u672c\u4e0d\u80fd\u4e3a\u7a7a");
        }
        try {
            byte[] nonce = new byte[12];
            RANDOM.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(1, (Key)new SecretKeySpec(this.key, "AES"), new GCMParameterSpec(128, nonce));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] combined = new byte[nonce.length + encrypted.length];
            System.arraycopy(nonce, 0, combined, 0, nonce.length);
            System.arraycopy(encrypted, 0, combined, nonce.length, encrypted.length);
            return Base64.getEncoder().encodeToString(combined);
        }
        catch (Exception exception) {
            throw new IllegalStateException("\u654f\u611f\u914d\u7f6e\u52a0\u5bc6\u5931\u8d25", exception);
        }
    }

    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isBlank()) {
            throw new IllegalArgumentException("\u5f85\u89e3\u5bc6\u6587\u672c\u4e0d\u80fd\u4e3a\u7a7a");
        }
        try {
            byte[] combined = Base64.getDecoder().decode(cipherText);
            if (combined.length <= 12) {
                throw new IllegalArgumentException("\u654f\u611f\u914d\u7f6e\u5bc6\u6587\u957f\u5ea6\u4e0d\u6b63\u786e");
            }
            byte[] nonce = Arrays.copyOfRange(combined, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(combined, 12, combined.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(2, (Key)new SecretKeySpec(this.key, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (IllegalArgumentException exception) {
            throw exception;
        }
        catch (Exception exception) {
            throw new IllegalStateException("\u654f\u611f\u914d\u7f6e\u89e3\u5bc6\u5931\u8d25", exception);
        }
    }

    private static byte[] keyBytes(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalArgumentException("wjdr.jwt.secret \u4e0d\u80fd\u4e3a\u7a7a");
        }
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        }
        catch (Exception exception) {
            throw new IllegalStateException("\u6d3e\u751f\u63a5\u7801\u914d\u7f6e\u5bc6\u94a5\u5931\u8d25", exception);
        }
    }
}

