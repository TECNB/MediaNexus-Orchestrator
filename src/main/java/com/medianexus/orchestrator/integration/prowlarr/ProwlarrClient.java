package com.medianexus.orchestrator.integration.prowlarr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.ProwlarrProperties;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException.Reason;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProwlarrClient {

    private static final int MAX_REDIRECTS = 5;
    private static final byte[] INFO_MARKER = "4:info".getBytes(StandardCharsets.US_ASCII);

    private final ProwlarrProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public ProwlarrClient(ProwlarrProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public List<ProwlarrRelease> search(String query) {
        validateConfiguration();
        HttpRequest request = HttpRequest.newBuilder(buildSearchUri(query))
                .timeout(timeout())
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProwlarrClientException(Reason.UPSTREAM, "Prowlarr returned non-success search status");
            }

            JsonNode payload = objectMapper.readTree(response.body());
            if (!payload.isArray()) {
                throw new ProwlarrClientException(Reason.INVALID_RESPONSE, "Prowlarr search response is not an array");
            }

            List<ProwlarrRelease> releases = new ArrayList<>();
            for (JsonNode item : payload) {
                ProwlarrRelease release = toRelease(item);
                if (release != null) {
                    releases.add(release);
                }
            }
            return releases;
        } catch (IOException exception) {
            throw new ProwlarrClientException(Reason.UPSTREAM, "Prowlarr search request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProwlarrClientException(Reason.UPSTREAM, "Prowlarr search request interrupted", exception);
        }
    }

    public String resolveMagnet(ProwlarrRelease release) {
        return resolveMagnet(release.indexerId(), release.downloadRef(), release.title());
    }

    public String resolveMagnet(Integer indexerId, String downloadRef, String releaseTitle) {
        if (indexerId == null || indexerId < 1 || !StringUtils.hasText(downloadRef)) {
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr release reference is invalid");
        }
        if (isMagnet(downloadRef)) {
            return resolveMagnet(downloadRef, releaseTitle);
        }
        return resolveMagnet(buildDownloadUri(indexerId, downloadRef, releaseTitle).toString(), releaseTitle);
    }

    private String resolveMagnet(String value, String fallbackName) {
        String trimmed = value.trim();
        if (isMagnet(trimmed)) {
            return normalizeMagnet(trimmed);
        }

        URI currentUri = URI.create(trimmed);
        for (int redirectCount = 0; redirectCount <= MAX_REDIRECTS; redirectCount++) {
            HttpResponse<byte[]> response = fetchBytes(currentUri);
            if (isRedirect(response.statusCode())) {
                String location = response.headers().firstValue("location")
                        .orElseThrow(() -> new ProwlarrClientException(
                                Reason.MAGNET_RESOLUTION,
                                "Prowlarr download URL redirected without location"
                        ));
                if (isMagnet(location)) {
                    return normalizeMagnet(location);
                }
                currentUri = currentUri.resolve(location);
                continue;
            }

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr download URL returned non-success status");
            }

            byte[] body = response.body();
            String textBody = new String(body, StandardCharsets.UTF_8).trim();
            if (isMagnet(textBody)) {
                return normalizeMagnet(textBody);
            }
            if (looksLikeTorrent(currentUri, response, body)) {
                return torrentToMagnet(body, fallbackName);
            }
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr download URL did not resolve to magnet or torrent");
        }

        throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr download URL exceeded redirect limit");
    }

    private HttpResponse<byte[]> fetchBytes(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(timeout())
                .GET()
                .build();
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException exception) {
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr download URL request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr download URL request interrupted", exception);
        }
    }

    private URI buildSearchUri(String query) {
        return URI.create(baseUrl()
                + "/api/v1/search?apikey="
                + encode(cleanConfigValue(properties.getApiKey()))
                + "&query="
                + encode(query));
    }

    private URI buildDownloadUri(int indexerId, String downloadRef, String releaseTitle) {
        return URI.create(baseUrl()
                + "/"
                + indexerId
                + "/download?apikey="
                + encode(cleanConfigValue(properties.getApiKey()))
                + "&link="
                + encode(downloadRef.trim())
                + "&file="
                + encode(StringUtils.hasText(releaseTitle) ? releaseTitle.trim() : "release"));
    }

    private ProwlarrRelease toRelease(JsonNode item) {
        String protocol = textOrNull(item.get("protocol"));
        if (StringUtils.hasText(protocol) && !"torrent".equalsIgnoreCase(protocol)) {
            return null;
        }
        String title = textOrNull(item.get("title"));
        Integer indexerId = integerOrNull(item.get("indexerId"));
        String downloadRef = downloadRef(item);
        if (!StringUtils.hasText(title) || indexerId == null || indexerId < 1 || !StringUtils.hasText(downloadRef)) {
            return null;
        }
        return new ProwlarrRelease(
                title,
                longOrNull(item.get("size")),
                integerOrNull(item.get("seeders")),
                integerOrNull(item.get("leechers")),
                integerOrNull(item.get("grabs")),
                textOrNull(item.get("indexer")),
                textOrNull(item.get("publishDate")),
                indexerId,
                textOrNull(item.get("guid")),
                downloadRef
        );
    }

    private String downloadRef(JsonNode item) {
        String downloadRef = queryParameter(textOrNull(item.get("magnetUrl")), "link");
        if (!StringUtils.hasText(downloadRef)) {
            downloadRef = queryParameter(textOrNull(item.get("downloadUrl")), "link");
        }
        if (StringUtils.hasText(downloadRef)) {
            return downloadRef;
        }

        String magnetUrl = textOrNull(item.get("magnetUrl"));
        if (isMagnet(magnetUrl)) {
            return magnetUrl.trim();
        }
        String downloadUrl = textOrNull(item.get("downloadUrl"));
        if (isMagnet(downloadUrl)) {
            return downloadUrl.trim();
        }
        String guid = textOrNull(item.get("guid"));
        if (isMagnet(guid)) {
            return guid.trim();
        }
        return null;
    }

    private String queryParameter(String value, String parameterName) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            String query = URI.create(value.trim()).getRawQuery();
            if (!StringUtils.hasText(query)) {
                return null;
            }
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                String rawName = separator >= 0 ? pair.substring(0, separator) : pair;
                if (parameterName.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
                    String rawValue = separator >= 0 ? pair.substring(separator + 1) : "";
                    String decoded = URLDecoder.decode(rawValue, StandardCharsets.UTF_8);
                    return StringUtils.hasText(decoded) ? decoded.trim() : null;
                }
            }
            return null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private String baseUrl() {
        String baseUrl = cleanConfigValue(properties.getBaseUrl()).replaceAll("/+$", "");
        if (!StringUtils.hasText(baseUrl) || !StringUtils.hasText(cleanConfigValue(properties.getApiKey()))) {
            throw new ProwlarrClientException(Reason.CONFIGURATION, "Prowlarr configuration is incomplete");
        }
        String normalized = baseUrl.toLowerCase(Locale.ROOT);
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            throw new ProwlarrClientException(Reason.CONFIGURATION, "Prowlarr base URL must start with http:// or https://");
        }
        return baseUrl;
    }

    private void validateConfiguration() {
        baseUrl();
    }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301
                || statusCode == 302
                || statusCode == 303
                || statusCode == 307
                || statusCode == 308;
    }

    private boolean isMagnet(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).startsWith("magnet:");
    }

    private String normalizeMagnet(String value) {
        String normalized = value.trim()
                .replace("?btih:", "?xt=urn:btih:")
                .replace("&btih:", "&xt=urn:btih:")
                .replace("?btmh:", "?xt=urn:btmh:")
                .replace("&btmh:", "&xt=urn:btmh:");
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Resolved magnet has invalid format");
        }
        return normalized;
    }

    private boolean looksLikeTorrent(URI uri, HttpResponse<byte[]> response, byte[] body) {
        String contentType = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
        String path = uri.getPath() == null ? "" : uri.getPath().toLowerCase(Locale.ROOT);
        return contentType.contains("bittorrent")
                || path.endsWith(".torrent")
                || (body.length > 8 && body[0] == 'd' && indexOf(body, INFO_MARKER) >= 0);
    }

    private String torrentToMagnet(byte[] torrentBytes, String fallbackName) {
        byte[] infoBytes = extractInfoBytes(torrentBytes);
        String hash = sha1Hex(infoBytes);
        String name = extractInfoName(infoBytes);
        String displayName = StringUtils.hasText(name) ? name : fallbackName;
        String magnet = "magnet:?xt=urn:btih:" + hash;
        if (StringUtils.hasText(displayName)) {
            magnet = magnet + "&dn=" + encode(displayName);
        }
        return magnet;
    }

    private byte[] extractInfoBytes(byte[] torrentBytes) {
        if (torrentBytes.length == 0 || torrentBytes[0] != 'd') {
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Torrent payload is not a bencoded dictionary");
        }
        int index = 1;
        while (index < torrentBytes.length && torrentBytes[index] != 'e') {
            StringValue key = readString(torrentBytes, index);
            int valueStart = key.end();
            int valueEnd = readValueEnd(torrentBytes, valueStart);
            if ("info".equals(key.value())) {
                return Arrays.copyOfRange(torrentBytes, valueStart, valueEnd);
            }
            index = valueEnd;
        }
        throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Torrent payload has no info dictionary");
    }

    private String extractInfoName(byte[] infoBytes) {
        if (infoBytes.length == 0 || infoBytes[0] != 'd') {
            return null;
        }
        int index = 1;
        while (index < infoBytes.length && infoBytes[index] != 'e') {
            StringValue key = readString(infoBytes, index);
            int valueStart = key.end();
            int valueEnd = readValueEnd(infoBytes, valueStart);
            if (("name".equals(key.value()) || "name.utf-8".equals(key.value()))
                    && valueStart < infoBytes.length
                    && isDigit(infoBytes[valueStart])) {
                return readString(infoBytes, valueStart).value();
            }
            index = valueEnd;
        }
        return null;
    }

    private int readValueEnd(byte[] data, int index) {
        if (index >= data.length) {
            throw invalidBencode();
        }
        byte marker = data[index];
        if (marker == 'i') {
            int end = indexOf(data, (byte) 'e', index + 1);
            if (end < 0) {
                throw invalidBencode();
            }
            return end + 1;
        }
        if (marker == 'l' || marker == 'd') {
            int cursor = index + 1;
            while (cursor < data.length && data[cursor] != 'e') {
                cursor = readValueEnd(data, cursor);
            }
            if (cursor >= data.length) {
                throw invalidBencode();
            }
            return cursor + 1;
        }
        if (isDigit(marker)) {
            return readString(data, index).end();
        }
        throw invalidBencode();
    }

    private StringValue readString(byte[] data, int index) {
        int colon = indexOf(data, (byte) ':', index);
        if (colon < 0) {
            throw invalidBencode();
        }
        int length = 0;
        for (int cursor = index; cursor < colon; cursor++) {
            if (!isDigit(data[cursor])) {
                throw invalidBencode();
            }
            length = (length * 10) + (data[cursor] - '0');
        }
        int start = colon + 1;
        int end = start + length;
        if (end > data.length) {
            throw invalidBencode();
        }
        return new StringValue(new String(data, start, length, StandardCharsets.UTF_8), end);
    }

    private ProwlarrClientException invalidBencode() {
        return new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Torrent payload has invalid bencode");
    }

    private int indexOf(byte[] data, byte needle, int start) {
        for (int index = start; index < data.length; index++) {
            if (data[index] == needle) {
                return index;
            }
        }
        return -1;
    }

    private int indexOf(byte[] data, byte[] needle) {
        for (int index = 0; index <= data.length - needle.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < needle.length; offset++) {
                if (data[index + offset] != needle[offset]) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                return index;
            }
        }
        return -1;
    }

    private boolean isDigit(byte value) {
        return value >= '0' && value <= '9';
    }

    private String sha1Hex(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-1").digest(bytes);
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value & 0xff));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-1 digest is unavailable", exception);
        }
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer integerOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.isNumber() ? null : node.asInt();
    }

    private Long longOrNull(JsonNode node) {
        return node == null || node.isNull() || !node.isNumber() ? null : node.asLong();
    }

    private Duration timeout() {
        return properties.getTimeout();
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String cleanConfigValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private record StringValue(String value, int end) {
    }
}
