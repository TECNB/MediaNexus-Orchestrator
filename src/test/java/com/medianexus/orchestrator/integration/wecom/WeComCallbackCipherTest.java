package com.medianexus.orchestrator.integration.wecom;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class WeComCallbackCipherTest {
    @Test void acceptsSignedCallbackAndRejectsTamperingWrongCorpAndExternalEntities() throws Exception {
        Instant now = Instant.parse("2026-11-09T08:30:00Z");
        String timestamp = Long.toString(now.getEpochSecond());
        String token = "sampleToken", nonce = "1234", corp = "wwsample";
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) 5);
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(key);
        String xml = "<xml><MsgType>text</MsgType><Content><![CDATA[怪奇物语 S3]]></Content></xml>";
        byte[] message = xml.getBytes(StandardCharsets.UTF_8);
        byte[] receiver = corp.getBytes(StandardCharsets.UTF_8);
        int size = 20 + message.length + receiver.length;
        int pad = 32 - size % 32;
        ByteBuffer envelope = ByteBuffer.allocate(size + pad).put(new byte[16]).putInt(message.length)
                .put(message).put(receiver);
        Arrays.fill(envelope.array(), size, size + pad, (byte) pad);
        Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
        String encrypted = Base64.getEncoder().encodeToString(cipher.doFinal(envelope.array()));
        String[] signed = {token, timestamp, nonce, encrypted};
        Arrays.sort(signed);
        String signature = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
                .digest(String.join("", signed).getBytes(StandardCharsets.UTF_8)));

        assertEquals("怪奇物语 S3", WeComCallbackCipher.fields(
                WeComCallbackCipher.decrypt(signature, timestamp, nonce, encrypted, token, encoded, corp, now)).get("Content"));
        assertThrows(IllegalArgumentException.class, () -> WeComCallbackCipher.decrypt(
                signature, timestamp, "other", encrypted, token, encoded, corp, now));
        assertThrows(IllegalArgumentException.class, () -> WeComCallbackCipher.decrypt(
                signature, timestamp, nonce, encrypted, token, encoded, "wrongCorp", now));
        assertThrows(IllegalArgumentException.class, () -> WeComCallbackCipher.decrypt(
                signature, timestamp, nonce, encrypted, token, encoded, corp, now.plusSeconds(301)));
        assertThrows(IllegalArgumentException.class, () -> WeComCallbackCipher.fields(
                "<!DOCTYPE xml [<!ENTITY ext SYSTEM 'file:///etc/passwd'>]><xml><Content>&ext;</Content></xml>"
                        .getBytes(StandardCharsets.UTF_8)));
    }
}
