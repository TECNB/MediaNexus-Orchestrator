package com.medianexus.orchestrator.integration.prowlarr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.ProwlarrProperties;
import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException.Reason;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class ProwlarrClient {
    private static final int MAX_REQUESTS = 6;
    private static final Duration REQUEST_LIMIT = Duration.ofSeconds(8);

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
        HttpResponse<byte[]> response = fetchBytes(buildSearchUri(query), deadline(timeout()), Reason.UPSTREAM);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new ProwlarrClientException(Reason.UPSTREAM, "Prowlarr returned non-success search status");
        }
        try {
            JsonNode payload = objectMapper.readTree(response.body());
            if (payload == null || !payload.isArray()) {
                throw new ProwlarrClientException(Reason.INVALID_RESPONSE, "Prowlarr search response is not an array");
            }
            List<ProwlarrRelease> releases = new ArrayList<>();
            for (JsonNode item : payload) {
                ProwlarrRelease release = toRelease(item);
                if (release != null) releases.add(release);
            }
            return releases;
        } catch (IOException exception) {
            throw new ProwlarrClientException(Reason.INVALID_RESPONSE, "Prowlarr search response is not valid JSON");
        }
    }

    public String resolveMagnet(ProwlarrRelease release) {
        String known = knownHash(release);
        if (isMagnet(release.downloadRef())) return resolvedMagnet(release.downloadRef(), known).magnet();
        if (known != null) return "magnet:?xt=urn:btih:" + known;
        return resolveMagnet(release.indexerId(), release.downloadRef(), release.title());
    }

    public String resolveMagnet(Integer indexerId, String downloadRef, String releaseTitle) {
        return resolveTorrent(new ProwlarrRelease(releaseTitle, null, null, null, null,
                null, null, indexerId, null, downloadRef), timeout()).magnet();
    }

    /**
     * 完整只读元数据操作。存在 torrentRef 时总是尝试该引用，而不被搜索 magnet/hash 短路；
     * 调用方可先消费已知 magnet，在其他证据来源不可用时再调用本操作。remaining 是共享轮次的剩余时间。
     */
    public ResolvedTorrent resolveTorrent(ProwlarrRelease release, Duration remaining) {
        long deadline = deadline(remaining);
        String known = knownHash(release);
        String reference = StringUtils.hasText(release.torrentRef()) ? release.torrentRef() : release.downloadRef();
        if (!StringUtils.hasText(reference)) {
            if (known != null) return resolvedMagnet("magnet:?xt=urn:btih:" + known, known);
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr release reference is invalid");
        }
        if (isMagnet(reference)) return resolvedMagnet(reference, known);
        if (release.indexerId() == null || release.indexerId() < 1) {
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr release indexer is invalid");
        }
        URI current = isHttp(reference) ? URI.create(reference) : buildDownloadUri(release.indexerId(), reference, release.title());
        for (int request = 0; request < MAX_REQUESTS; request++) {
            HttpResponse<byte[]> response = fetchBytes(current, Math.min(deadline, deadline(REQUEST_LIMIT)), Reason.MAGNET_RESOLUTION);
            if (isRedirect(response.statusCode())) {
                String location = response.headers().firstValue("location").orElseThrow(() ->
                        new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr redirect has no location"));
                if (isMagnet(location)) return resolvedMagnet(location, known);
                try { current = current.resolve(location); }
                catch (IllegalArgumentException exception) {
                    throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr redirect is invalid");
                }
                continue;
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr download reference returned non-success status");
            }
            byte[] body = response.body();
            String text = new String(body, StandardCharsets.UTF_8).trim();
            if (isMagnet(text)) return resolvedMagnet(text, known);
            String type = response.headers().firstValue("content-type").orElse("").toLowerCase(Locale.ROOT);
            String path = current.getPath();
            if ((body.length > 0 && body[0] == 'd') || type.contains("bittorrent")
                    || (path != null && path.toLowerCase(Locale.ROOT).endsWith(".torrent"))) {
                TorrentFileTable table = TorrentMetadata.parse(body, known);
                if (System.nanoTime() - deadline >= 0) throw exhausted();
                String magnet = "magnet:?xt=urn:btih:" + table.identity().btih() + "&dn=" + encode(table.rootName());
                return new ResolvedTorrent(magnet, Optional.of(table));
            }
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr reference did not return magnet or torrent");
        }
        throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr reference exceeded HTTP request limit");
    }

    private ResolvedTorrent resolvedMagnet(String value, String known) {
        String magnet = normalizeMagnet(value);
        Optional<PanSouMagnetIdentity> identity = magnetIdentity(magnet);
        // BTIH-less magnets (for example pure v2 BTMH) remain compatible, but cannot prove a known BTIH.
        if (identity.isEmpty() && known != null) {
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Resolved magnet cannot confirm search BTIH");
        }
        if (known != null && identity.isPresent() && !known.equals(identity.get().btih())) {
            throw new ProwlarrClientException(Reason.INVALID_RESPONSE, "Resolved magnet conflicts with search BTIH");
        }
        return new ResolvedTorrent(magnet, Optional.empty());
    }

    private String knownHash(ProwlarrRelease release) {
        String known = canonicalHash(release.infoHash());
        for (String reference : new String[] {release.downloadRef(), release.torrentRef(), release.guid()}) {
            Optional<PanSouMagnetIdentity> identity = isMagnet(reference) ? magnetIdentity(normalizeMagnet(reference)) : Optional.empty();
            if (identity.isPresent()) {
                if (known != null && !known.equals(identity.get().btih())) {
                    throw new ProwlarrClientException(Reason.INVALID_RESPONSE, "Prowlarr search identities conflict");
                }
                known = identity.get().btih();
            }
        }
        return known;
    }

    private String canonicalHash(String hash) {
        if (!StringUtils.hasText(hash)) return null;
        String value = hash.trim();
        if (isMagnet(value)) return magnetIdentity(normalizeMagnet(value)).map(PanSouMagnetIdentity::btih).orElse(null);
        if (!value.matches("(?i)[a-f0-9]{40}|[a-zA-Z2-7]{32}")) return null;
        return PanSouMagnetIdentity.fromUrl("magnet:?xt=urn:btih:" + value).map(PanSouMagnetIdentity::btih).orElse(null);
    }

    private Optional<PanSouMagnetIdentity> magnetIdentity(String magnet) {
        Optional<PanSouMagnetIdentity> identity = PanSouMagnetIdentity.fromUrl(magnet);
        if (identity.isPresent()) return identity;
        // fromUrl 拒绝多个冲突 xt；逐项识别以区分明确冲突与没有可用 BTIH。
        String known = null;
        int question = magnet.indexOf('?');
        if (question >= 0) {
            for (String parameter : magnet.substring(question + 1).split("&")) {
                Optional<PanSouMagnetIdentity> topic = PanSouMagnetIdentity.fromUrl("magnet:?" + parameter);
                if (topic.isPresent()) {
                    if (known != null && !known.equals(topic.get().btih())) {
                        throw new ProwlarrClientException(Reason.INVALID_RESPONSE, "Magnet contains conflicting BTIH identities");
                    }
                    known = topic.get().btih();
                }
            }
        }
        return Optional.empty();
    }

    /** get(timeout) 等待完整订阅体；取消同时中止慢读，不能只对返回响应头计时。 */
    private HttpResponse<byte[]> fetchBytes(URI uri, long deadline, Reason unavailable) {
        long nanos = Math.min(timeout().toNanos(), deadline - System.nanoTime());
        if (nanos <= 0) throw exhausted();
        if (!List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new ProwlarrClientException(unavailable, "Prowlarr reference is not an HTTP URL");
        }
        long requestDeadline = System.nanoTime() + nanos;
        CompletableFuture<HttpResponse<byte[]>> pending;
        try {
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(nanos)).GET().build();
            pending = httpClient.sendAsync(request, ignored -> new BoundedBody());
        } catch (IllegalArgumentException exception) {
            throw new ProwlarrClientException(unavailable, "Prowlarr reference request is invalid");
        }
        try {
            return pending.get(Math.max(1, Math.min(requestDeadline, deadline) - System.nanoTime()), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            pending.cancel(true);
            throw new ProwlarrClientException(unavailable, "Prowlarr reference deadline exceeded");
        } catch (InterruptedException exception) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ProwlarrClientException(unavailable, "Prowlarr reference request interrupted");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            while (cause != null) {
                if (cause instanceof ProwlarrClientException failure) throw failure;
                cause = cause.getCause();
            }
            // JDK 异常可能包含带密钥的 URL；不向业务层携带原始异常。
            throw new ProwlarrClientException(unavailable, "Prowlarr reference request failed");
        }
    }

    private long deadline(Duration remaining) {
        if (remaining == null || remaining.isZero() || remaining.isNegative()) throw exhausted();
        Duration bounded = remaining.compareTo(Duration.ofDays(1)) > 0 ? Duration.ofDays(1) : remaining;
        return System.nanoTime() + bounded.toNanos();
    }

    private ProwlarrClientException exhausted() {
        return new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Prowlarr metadata budget exhausted");
    }

    private URI buildSearchUri(String query) {
        return URI.create(baseUrl() + "/api/v1/search?apikey=" + encode(cleanConfigValue(properties.getApiKey()))
                + "&query=" + encode(query));
    }

    private URI buildDownloadUri(int indexerId, String downloadRef, String releaseTitle) {
        return URI.create(baseUrl() + "/" + indexerId + "/download?apikey="
                + encode(cleanConfigValue(properties.getApiKey())) + "&link=" + encode(downloadRef.trim())
                + "&file=" + encode(StringUtils.hasText(releaseTitle) ? releaseTitle.trim() : "release"));
    }

    private ProwlarrRelease toRelease(JsonNode item) {
        String protocol = textOrNull(item.get("protocol"));
        if (StringUtils.hasText(protocol) && !"torrent".equalsIgnoreCase(protocol)) return null;
        String title = textOrNull(item.get("title"));
        Integer indexerId = integerOrNull(item.get("indexerId"));
        String hash = canonicalHash(textOrNull(item.get("infoHash")));
        String torrentRef = torrentRef(textOrNull(item.get("downloadUrl")));
        String reference = downloadRef(item);
        if (reference == null && hash != null) reference = "magnet:?xt=urn:btih:" + hash;
        if (isHttp(reference)) reference = privateReference(reference);
        if (!StringUtils.hasText(title) || !StringUtils.hasText(reference)
                || (!isMagnet(reference) && (indexerId == null || indexerId < 1))) return null;
        return new ProwlarrRelease(title, longOrNull(item.get("size")), integerOrNull(item.get("seeders")),
                integerOrNull(item.get("leechers")), integerOrNull(item.get("grabs")), textOrNull(item.get("indexer")),
                textOrNull(item.get("publishDate")), indexerId, withoutApiKey(textOrNull(item.get("guid"))),
                reference, hash, torrentRef);
    }

    private String downloadRef(JsonNode item) {
        for (String field : List.of("magnetUrl", "downloadUrl", "guid")) {
            String value = textOrNull(item.get(field));
            if (isMagnet(value)) return value.trim();
        }
        for (String field : List.of("magnetUrl", "downloadUrl")) {
            String value = textOrNull(item.get(field));
            if (!isProwlarrDownload(value)) continue;
            String reference = queryParameter(value, "link");
            if (StringUtils.hasText(reference)) return reference;
        }
        return torrentRef(textOrNull(item.get("downloadUrl")));
    }

    private String torrentRef(String value) {
        if (!StringUtils.hasText(value) || isMagnet(value)) return null;
        String link = isProwlarrDownload(value) ? queryParameter(value, "link") : null;
        String reference = StringUtils.hasText(link) ? link : value.trim();
        return isMagnet(reference) ? null : reference;
    }

    private boolean isHttp(String value) {
        return value != null && (value.startsWith("http://") || value.startsWith("https://"));
    }

    private boolean isProwlarrDownload(String value) {
        if (!isHttp(value)) return false;
        try {
            URI uri = URI.create(value), base = URI.create(baseUrl());
            return java.util.Objects.equals(uri.getScheme(), base.getScheme())
                    && java.util.Objects.equals(uri.getAuthority(), base.getAuthority())
                    && uri.getPath().matches(".*/[0-9]+/download");
        } catch (IllegalArgumentException exception) { return false; }
    }

    private String privateReference(String url) {
        try {
            return "torrent-ref:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String withoutApiKey(String value) {
        if (value == null) return null;
        int question = value.indexOf('?');
        if (question < 0) return value;
        List<String> parameters = new ArrayList<>();
        for (String pair : value.substring(question + 1).split("&")) {
            String key = pair.split("=", 2)[0];
            try {
                if ("apikey".equalsIgnoreCase(URLDecoder.decode(key, StandardCharsets.UTF_8))) continue;
            } catch (IllegalArgumentException exception) { return null; }
            parameters.add(pair);
        }
        return value.substring(0, question) + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
    }

    private String queryParameter(String value, String parameterName) {
        if (!StringUtils.hasText(value)) return null;
        try {
            String query = URI.create(value.trim()).getRawQuery();
            if (!StringUtils.hasText(query)) return null;
            for (String pair : query.split("&")) {
                int separator = pair.indexOf('=');
                String rawName = separator >= 0 ? pair.substring(0, separator) : pair;
                if (parameterName.equals(URLDecoder.decode(rawName, StandardCharsets.UTF_8))) {
                    String decoded = URLDecoder.decode(separator >= 0 ? pair.substring(separator + 1) : "", StandardCharsets.UTF_8);
                    return StringUtils.hasText(decoded) ? decoded.trim() : null;
                }
            }
        } catch (IllegalArgumentException exception) { return null; }
        return null;
    }

    private String baseUrl() {
        if (properties.isDisabled()) throw new ProwlarrClientException(Reason.CONFIGURATION, "Prowlarr 已明确停用");
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

    private void validateConfiguration() { baseUrl(); }

    private boolean isRedirect(int statusCode) {
        return statusCode == 301 || statusCode == 302 || statusCode == 303 || statusCode == 307 || statusCode == 308;
    }

    private boolean isMagnet(String value) {
        return value != null && value.trim().toLowerCase(Locale.ROOT).startsWith("magnet:");
    }

    private String normalizeMagnet(String value) {
        String normalized = value.trim().replace("?btih:", "?xt=urn:btih:").replace("&btih:", "&xt=urn:btih:")
                .replace("?btmh:", "?xt=urn:btmh:").replace("&btmh:", "&xt=urn:btmh:");
        if (!normalized.toLowerCase(Locale.ROOT).startsWith("magnet:?")) {
            throw new ProwlarrClientException(Reason.MAGNET_RESOLUTION, "Resolved magnet has invalid format");
        }
        return normalized;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) return null;
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private Integer integerOrNull(JsonNode node) { return node == null || !node.isNumber() ? null : node.asInt(); }
    private Long longOrNull(JsonNode node) { return node == null || !node.isNumber() ? null : node.asLong(); }
    private Duration timeout() { return properties.getTimeout().compareTo(REQUEST_LIMIT) < 0 ? properties.getTimeout() : REQUEST_LIMIT; }
    private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private String cleanConfigValue(String value) {
        if (value == null) return "";
        String cleaned = value.trim();
        if (cleaned.length() >= 2 && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > TorrentMetadata.MAX_BYTES - bytes.size()) {
                    subscription.cancel();
                    result.completeExceptionally(new ProwlarrClientException(Reason.INVALID_RESPONSE, "Prowlarr 响应字节体积超限"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
