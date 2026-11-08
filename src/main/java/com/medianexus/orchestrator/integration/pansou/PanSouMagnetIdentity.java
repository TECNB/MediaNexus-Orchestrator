package com.medianexus.orchestrator.integration.pansou;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/** BTIH 以小写十六进制统一身份；保留调用方的原始 magnet 作为下载引用。 */
public record PanSouMagnetIdentity(String btih) {

    public static Optional<PanSouMagnetIdentity> fromUrl(String url) {
        if (url == null) return Optional.empty();
        try {
            URI uri = URI.create(url.trim());
            String query = uri.getRawSchemeSpecificPart();
            if (!"magnet".equalsIgnoreCase(uri.getScheme()) || query == null || !query.startsWith("?")
                    || uri.getRawFragment() != null) {
                return Optional.empty();
            }
            String identity = null;
            for (String parameter : query.substring(1).split("&")) {
                String[] pair = parameter.split("=", 2);
                if (pair.length != 2 || !"xt".equalsIgnoreCase(pair[0])) continue;
                String topic = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                if (!topic.regionMatches(true, 0, "urn:btih:", 0, 9)) continue;
                String hash = topic.substring(9).toLowerCase(Locale.ROOT);
                if (hash.matches("[a-f0-9]{40}")) {
                    // 已是规范的十六进制身份。
                } else if (hash.matches("[a-z2-7]{32}")) {
                    byte[] bytes = new byte[20];
                    int buffer = 0;
                    int bits = 0;
                    int index = 0;
                    for (char digit : hash.toCharArray()) {
                        buffer = (buffer << 5) | "abcdefghijklmnopqrstuvwxyz234567".indexOf(digit);
                        bits += 5;
                        if (bits >= 8) {
                            bits -= 8;
                            bytes[index++] = (byte) (buffer >> bits);
                        }
                    }
                    hash = HexFormat.of().formatHex(bytes);
                } else {
                    return Optional.empty();
                }
                if (identity != null && !identity.equals(hash)) return Optional.empty();
                identity = hash;
            }
            return identity == null ? Optional.empty() : Optional.of(new PanSouMagnetIdentity(identity));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
