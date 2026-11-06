package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.http.HttpStatus;

/** 部署主密钥不进入数据库；AAD 将密文绑定到配置组与字段，防止跨字段交换。 */
@org.springframework.stereotype.Component
public final class SettingsCipher {
    private final String deploymentKey;
    private final SecureRandom random = new SecureRandom();

    public SettingsCipher(@org.springframework.beans.factory.annotation.Value("${MEDIANEXUS_SETTINGS_ENCRYPTION_KEY:}") String deploymentKey) { this.deploymentKey = deploymentKey; }

    public String encrypt(String scope, String plaintext) {
        byte[] nonce = new byte[12]; random.nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, scope, nonce);
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return "gcm1:" + Base64.getEncoder().encodeToString(
                    ByteBuffer.allocate(nonce.length + encrypted.length).put(nonce).put(encrypted).array());
        } catch (GeneralSecurityException exception) { throw unavailable(); }
    }

    public String decrypt(String scope, String ciphertext) {
        try {
            if (!ciphertext.startsWith("gcm1:")) { throw unavailable(); }
            ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(ciphertext.substring(5)));
            byte[] nonce = new byte[12]; buffer.get(nonce);
            byte[] encrypted = new byte[buffer.remaining()]; buffer.get(encrypted);
            return new String(cipher(Cipher.DECRYPT_MODE, scope, nonce).doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException | java.nio.BufferUnderflowException exception) {
            throw unavailable();
        }
    }

    /** 有域隔离的认证摘要；不暴露可离线穷举的凭据裸哈希。 */
    public String fingerprint(String scope, String canonicalJson) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            mac.update(scope.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return "hmac1:" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(canonicalJson.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) { throw unavailable(); }
    }

    private byte[] key() {
        byte[] key;
        try { key = Base64.getDecoder().decode(deploymentKey); }
        catch (IllegalArgumentException exception) { throw unavailable(); }
        if (key.length != 32) { throw unavailable(); }
        return key;
    }

    private Cipher cipher(int mode, String scope, byte[] nonce) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key(), "AES"), new GCMParameterSpec(128, nonce));
        cipher.updateAAD(scope.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }

    private BusinessException unavailable() {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE,
                "设置加密密钥缺失、无效或无法解密；请检查 MEDIANEXUS_SETTINGS_ENCRYPTION_KEY", HttpStatus.SERVICE_UNAVAILABLE);
    }
}
