package com.medianexus.orchestrator.integration.wecom;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;

/** 验证企业微信签名、时间窗、填充和 CorpID；失败不回显输入或密钥。 */
public final class WeComCallbackCipher {
    private WeComCallbackCipher() {}

    public static byte[] decrypt(String signature, String timestamp, String nonce, String encrypted,
                                 String token, String aesKey, String corpId, Instant now) {
        try {
            if (signature == null || !signature.matches("[0-9a-f]{40}") || nonce == null || nonce.length() > 128
                    || timestamp == null || !timestamp.matches("[0-9]{1,12}")
                    || Math.abs(now.getEpochSecond() - Long.parseLong(timestamp)) > 300
                    || encrypted == null || encrypted.length() > 16384) throw new IllegalArgumentException();
            String[] signed = {token, timestamp, nonce, encrypted};
            Arrays.sort(signed);
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(String.join("", signed).getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(HexFormat.of().parseHex(signature), digest)) throw new IllegalArgumentException();
            byte[] key = Base64.getDecoder().decode(aesKey);
            if (key.length != 32) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/CBC/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(key, 0, 16));
            byte[] clear = cipher.doFinal(Base64.getDecoder().decode(encrypted));
            if (clear.length < 32) throw new IllegalArgumentException();
            int padding = Byte.toUnsignedInt(clear[clear.length - 1]);
            if (padding < 1 || padding > 32 || clear.length - padding < 20) throw new IllegalArgumentException();
            for (int i = clear.length - padding; i < clear.length; i++) {
                if (clear[i] != (byte) padding) throw new IllegalArgumentException();
            }
            int size = ByteBuffer.wrap(clear, 16, 4).getInt();
            if (size < 0 || size > clear.length - padding - 20
                    || !MessageDigest.isEqual(Arrays.copyOfRange(clear, 20 + size, clear.length - padding),
                    corpId.getBytes(StandardCharsets.UTF_8))) throw new IllegalArgumentException();
            return Arrays.copyOfRange(clear, 20, 20 + size);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("企业微信回调校验失败");
        }
    }

    public static Map<String, String> fields(byte[] xml) {
        try {
            XMLInputFactory factory = XMLInputFactory.newFactory();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            var parser = factory.createXMLStreamReader(new ByteArrayInputStream(xml), "UTF-8");
            try {
                if (parser.nextTag() != XMLStreamConstants.START_ELEMENT || !"xml".equals(parser.getLocalName())) {
                    throw new IllegalArgumentException();
                }
                var fields = new HashMap<String, String>();
                while (parser.nextTag() == XMLStreamConstants.START_ELEMENT) {
                    String tag = parser.getLocalName();
                    if (fields.putIfAbsent(tag, parser.getElementText()) != null) throw new IllegalArgumentException();
                }
                return fields;
            } finally { parser.close(); }
        } catch (Exception invalid) {
            throw new IllegalArgumentException("企业微信回调 XML 无效");
        }
    }
}
