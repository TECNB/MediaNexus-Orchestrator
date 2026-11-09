package com.medianexus.orchestrator.integration.pansou;

import static com.medianexus.orchestrator.integration.pansou.PanSouClientException.Reason.AUTHENTICATION;
import static com.medianexus.orchestrator.integration.pansou.PanSouClientException.Reason.CONFIGURATION;
import static com.medianexus.orchestrator.integration.pansou.PanSouClientException.Reason.INVALID_RESPONSE;
import static com.medianexus.orchestrator.integration.pansou.PanSouClientException.Reason.TIMEOUT;
import static com.medianexus.orchestrator.integration.pansou.PanSouClientException.Reason.UPSTREAM;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.PanSouProperties;
import com.medianexus.orchestrator.service.TorrentEpisodeCoverage;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class PanSouClient implements AutoCloseable {

    private static final long TOKEN_REFRESH_SKEW_SECONDS = 300;
    private static final Pattern CONTENT_SIZE_LABEL = Pattern.compile(
            "(?<![\\p{L}\\p{N}_])(?:大小|Size)\\s*[:：]", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_SIZE_VALUE = Pattern.compile(
            "\\s*([0-9]+(?:\\.[0-9]+)?)\\s*(KiB|MiB|GiB|TiB|KB|MB|GB|TB|B)"
                    + "(?=$|[\\s|,，;；)）\\]】。])", Pattern.CASE_INSENSITIVE);
    private static final Pattern CONTENT_MAGNET_URL = Pattern.compile(
            "magnet:\\?[^\\s<>\\\"'|)）\\]】,，;；。]+", Pattern.CASE_INSENSITIVE);

    private final PanSouProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final TorrentEpisodeCoverage episodeCoverage;
    private final Object tokenLock = new Object();
    private volatile String accessToken;
    private volatile Instant tokenExpiresAt = Instant.EPOCH;

    public PanSouClient(PanSouProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, null);
    }

    @Autowired
    public PanSouClient(PanSouProperties properties, ObjectMapper objectMapper, TorrentEpisodeCoverage episodeCoverage) {
        this.episodeCoverage = episodeCoverage;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    public PanSouSearchResult search(PanSouSearchCommand command) {
        if (command == null || !StringUtils.hasText(command.keyword())) {
            throw new IllegalArgumentException("PanSou 搜索词不能为空");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kw", command.keyword().trim());
        payload.putArray("cloud_types").add("quark");
        payload.put("res", "all");
        payload.put("src", "all");
        payload.put("refresh", command.refresh());

        JsonNode root = sendAuthorized("/api/search", payload);
        JsonNode data = root.path("data");
        List<PanSouSearchEntry> result = new ArrayList<>();
        JsonNode messages = data.path("results");
        if (!messages.isArray()) {
            messages = root.path("results");
        }
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode links = message.path("links");
                if (!links.isArray()) {
                    continue;
                }
                for (JsonNode link : links) {
                    if (!"quark".equalsIgnoreCase(text(link, "type"))) {
                        continue;
                    }
                    result.add(new PanSouSearchEntry(
                            text(link, "url"),
                            firstText(link, "password", "pwd"),
                            firstNonBlank(
                                    firstText(link, "work_title", "note", "title", "name", "desc"),
                                    firstText(message, "title", "note", "name")
                            ),
                            firstNonBlank(
                                    firstText(link, "datetime", "time", "created_at"),
                                    firstText(message, "datetime", "time", "created_at")
                            ),
                            firstText(message, "channel", "source", "plugin")
                    ));
                }
            }
        }
        if (result.isEmpty()) {
            JsonNode entries = data.path("merged_by_type").path("quark");
            if (!entries.isArray()) {
                entries = root.path("merged_by_type").path("quark");
            }
            if (!entries.isArray()) {
                throw new PanSouClientException(INVALID_RESPONSE, "PanSou 搜索响应缺少 Quark 结果列表");
            }
            for (JsonNode entry : entries) {
                result.add(new PanSouSearchEntry(
                        text(entry, "url"),
                        firstText(entry, "password", "pwd"),
                        firstText(entry, "note", "title", "name", "work_title", "desc"),
                        firstText(entry, "datetime", "time", "created_at"),
                        firstText(entry, "source", "channel", "plugin")
                ));
            }
        }
        return new PanSouSearchResult(result);
    }

    /** 磁力候选只读取合并分类，原始正文仅按 BTIH 补充大小；不改变夸克入口及默认来源。 */
    public PanSouSearchResult searchMagnets(PanSouSearchCommand command) {
        if (command == null || !StringUtils.hasText(command.keyword())) {
            throw new IllegalArgumentException("PanSou 搜索词不能为空");
        }
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("kw", command.keyword().trim());
        payload.putArray("cloud_types").add("magnet");
        payload.put("res", "all");
        payload.put("src", "plugin");
        payload.putArray("plugins").add("nyaa").add("clxiong");
        payload.put("refresh", command.refresh());
        JsonNode root = sendAuthorized("/api/search", payload);
        if (root == null || !root.isObject()
                || (root.has("success") && !root.path("success").asBoolean())
                || root.hasNonNull("error")
                || (root.has("code") && root.path("code").asInt(-1) != 0
                    && root.path("code").asInt(-1) != 200)) {
            throw new PanSouClientException(INVALID_RESPONSE, "PanSou 磁力搜索响应无效");
        }
        JsonNode body = root.has("data") ? root.path("data") : root;
        JsonNode merged = body.path("merged_by_type");
        // 上游零命中时省略 merged_by_type，只返回 total: 0。
        if (merged.isMissingNode() && body.path("total").isIntegralNumber() && body.path("total").asLong() == 0) {
            return new PanSouSearchResult(List.of());
        }
        if (!merged.isObject()) {
            throw new PanSouClientException(INVALID_RESPONSE, "PanSou 磁力搜索响应缺少合并结果");
        }
        JsonNode entries = merged.path("magnet");
        if (entries.isMissingNode() && merged.isEmpty()) {
            return new PanSouSearchResult(List.of());
        }
        if (!entries.isArray()) {
            throw new PanSouClientException(INVALID_RESPONSE, "PanSou 磁力分类不是结果列表");
        }
        List<PanSouSearchEntry> result = new ArrayList<>();
        if (episodeCoverage != null) {
            episodeCoverage.registerSources(body.path("results"));
        }
        Map<PanSouMagnetIdentity, Long> sizes = magnetSizes(body.path("results"));
        for (JsonNode entry : entries) {
            String type = text(entry, "type");
            PanSouMagnetIdentity identity = PanSouMagnetIdentity.fromUrl(text(entry, "url")).orElse(null);
            if (!entry.isObject() || (StringUtils.hasText(type) && !"magnet".equalsIgnoreCase(type))
                    || identity == null) {
                throw new PanSouClientException(INVALID_RESPONSE, "PanSou 磁力分类包含无效链接");
            }
            result.add(new PanSouSearchEntry(
                    text(entry, "url"), "",
                    firstText(entry, "note", "title", "name", "work_title", "desc"),
                    firstText(entry, "datetime", "time", "created_at"),
                    firstText(entry, "source", "channel", "plugin"),
                    sizes.get(identity), sizes.containsKey(identity) && sizes.get(identity) == null
            ));
        }
        return new PanSouSearchResult(result);
    }

    private Map<PanSouMagnetIdentity, Long> magnetSizes(JsonNode messages) {
        Map<PanSouMagnetIdentity, Long> sizes = new HashMap<>();
        Set<PanSouMagnetIdentity> conflicts = new HashSet<>();
        if (!messages.isArray()) {
            return sizes;
        }
        for (JsonNode message : messages) {
            JsonNode links = message.path("links");
            if (!links.isArray()) {
                continue;
            }
            Set<PanSouMagnetIdentity> identities = new HashSet<>();
            for (JsonNode link : links) {
                if ("magnet".equalsIgnoreCase(text(link, "type"))) {
                    PanSouMagnetIdentity.fromUrl(text(link, "url")).ifPresent(identities::add);
                }
            }
            if (identities.size() != 1) {
                continue;
            }
            PanSouMagnetIdentity identity = identities.iterator().next();
            String content = text(message, "content");
            // 正文总大小不能分摊给多条种子；正文中额外出现的不同 BTIH 同样构成歧义。
            Matcher magnets = CONTENT_MAGNET_URL.matcher(content);
            while (magnets.find()) {
                PanSouMagnetIdentity.fromUrl(magnets.group()).ifPresent(identities::add);
            }
            if (identities.size() != 1 || conflicts.contains(identity)) {
                continue;
            }
            Long size = contentSizeBytes(content);
            if (size == null) {
                continue;
            }
            Long previous = sizes.putIfAbsent(identity, size);
            // 上游没有统一的舍入精度契约，不同字节值保守视为冲突，且不能被后续记录覆盖。
            if (previous != null && !previous.equals(size)) {
                sizes.put(identity, null);
                conflicts.add(identity);
            }
        }
        return sizes;
    }

    private Long contentSizeBytes(String content) {
        Matcher labels = CONTENT_SIZE_LABEL.matcher(content);
        Long sizeBytes = null;
        while (labels.find()) {
            Matcher size = CONTENT_SIZE_VALUE.matcher(content).region(labels.end(), content.length());
            if (!size.lookingAt()) {
                return null;
            }
            String unit = size.group(2).toUpperCase(Locale.ROOT);
            int exponent = switch (unit.charAt(0)) {
                case 'K' -> 1;
                case 'M' -> 2;
                case 'G' -> 3;
                case 'T' -> 4;
                default -> 0;
            };
            BigDecimal bytes = new BigDecimal(size.group(1)).multiply(
                    BigDecimal.valueOf(unit.contains("I") ? 1024 : 1000).pow(exponent));
            if (bytes.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
                return null;
            }
            long parsed = bytes.setScale(0, RoundingMode.HALF_UP).longValueExact();
            if (sizeBytes != null && sizeBytes != parsed) {
                return null;
            }
            sizeBytes = parsed;
        }
        return sizeBytes;
    }

    public List<PanSouLinkCheckResult> checkLinks(List<PanSouLinkCheckRequest> requests) {
        if (requests == null || requests.isEmpty()) {
            return List.of();
        }
        ObjectNode payload = objectMapper.createObjectNode();
        ArrayNode items = payload.putArray("items");
        for (PanSouLinkCheckRequest request : requests) {
            ObjectNode item = items.addObject();
            item.put("disk_type", "quark");
            item.put("url", request.url());
            item.put("password", request.password() == null ? "" : request.password());
        }
        JsonNode root = sendAuthorized("/api/check/links", payload);
        JsonNode results = root.path("results");
        if (!results.isArray()) {
            throw new PanSouClientException(INVALID_RESPONSE, "PanSou 链接检查响应缺少 results");
        }
        List<PanSouLinkCheckResult> checks = new ArrayList<>();
        for (JsonNode result : results) {
            checks.add(new PanSouLinkCheckResult(
                    text(result, "url"),
                    text(result, "normalized_url"),
                    text(result, "state"),
                    text(result, "summary")
            ));
        }
        return List.copyOf(checks);
    }

    private JsonNode sendAuthorized(String path, JsonNode payload) {
        String token = token(false);
        HttpResponse<String> response = send(path, payload, token);
        if (response.statusCode() == 401) {
            invalidateToken();
            response = send(path, payload, token(true));
        }
        if (response.statusCode() == 401 || response.statusCode() == 403) {
            throw new PanSouClientException(AUTHENTICATION, "PanSou 认证失败");
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PanSouClientException(UPSTREAM, upstreamMessage(response));
        }
        return parse(response.body(), "PanSou 返回了无法识别的数据");
    }

    private HttpResponse<String> send(String path, JsonNode payload, String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                    .timeout(properties.getTimeout())
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new PanSouClientException(TIMEOUT, "PanSou 请求超时", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PanSouClientException(UPSTREAM, "PanSou 请求被中断", exception);
        } catch (IOException exception) {
            throw new PanSouClientException(UPSTREAM, "PanSou 请求失败：" + safeMessage(exception), exception);
        }
    }

    private String token(boolean forceRefresh) {
        ensureConfigured();
        Instant refreshBefore = Instant.now().plusSeconds(TOKEN_REFRESH_SKEW_SECONDS);
        if (!forceRefresh && StringUtils.hasText(accessToken) && tokenExpiresAt.isAfter(refreshBefore)) {
            return accessToken;
        }
        synchronized (tokenLock) {
            refreshBefore = Instant.now().plusSeconds(TOKEN_REFRESH_SKEW_SECONDS);
            if (!forceRefresh && StringUtils.hasText(accessToken) && tokenExpiresAt.isAfter(refreshBefore)) {
                return accessToken;
            }
            login();
            return accessToken;
        }
    }

    private void login() {
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("username", properties.getUsername().trim());
        payload.put("password", properties.getPassword());
        HttpResponse<String> response = sendWithoutAuthorization("/api/auth/login", payload);
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new PanSouClientException(AUTHENTICATION, "PanSou 认证失败");
        }
        JsonNode body = parse(response.body(), "PanSou 登录响应无法识别");
        String token = text(body, "token");
        if (!StringUtils.hasText(token)) {
            throw new PanSouClientException(INVALID_RESPONSE, "PanSou 登录响应缺少 token");
        }
        accessToken = token;
        tokenExpiresAt = parseExpiry(body.path("expires_at"));
    }

    private HttpResponse<String> sendWithoutAuthorization(String path, JsonNode payload) {
        try {
            HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                    .timeout(properties.getTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (java.net.http.HttpTimeoutException exception) {
            throw new PanSouClientException(TIMEOUT, "PanSou 登录超时", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PanSouClientException(UPSTREAM, "PanSou 登录被中断", exception);
        } catch (IOException exception) {
            throw new PanSouClientException(UPSTREAM, "PanSou 登录失败：" + safeMessage(exception), exception);
        }
    }

    private Instant parseExpiry(JsonNode node) {
        if (node.isNumber()) {
            long value = node.asLong();
            return value > 10_000_000_000L ? Instant.ofEpochMilli(value) : Instant.ofEpochSecond(value);
        }
        String value = node.asText("").trim();
        if (value.matches("\\d+")) {
            long numeric = Long.parseLong(value);
            return numeric > 10_000_000_000L ? Instant.ofEpochMilli(numeric) : Instant.ofEpochSecond(numeric);
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            return Instant.now().plusSeconds(3600);
        }
    }

    private JsonNode parse(String body, String message) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException exception) {
            throw new PanSouClientException(INVALID_RESPONSE, message, exception);
        }
    }

    private URI endpoint(String path) {
        String baseUrl = properties.getBaseUrl().trim().replaceAll("/+$", "");
        return URI.create(baseUrl + path);
    }

    private void ensureConfigured() {
        if (properties.isDisabled()) throw new PanSouClientException(CONFIGURATION, "PanSou 已明确停用");
        if (!StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getUsername())
                || !StringUtils.hasText(properties.getPassword())) {
            throw new PanSouClientException(CONFIGURATION, "PanSou 搜索服务未配置");
        }
    }

    private String upstreamMessage(HttpResponse<String> response) {
        JsonNode body = parseQuietly(response.body());
        String message = firstText(body, "message", "msg", "error");
        return StringUtils.hasText(message)
                ? "PanSou 请求失败：" + message
                : "PanSou 请求失败（HTTP " + response.statusCode() + "）";
    }

    private JsonNode parseQuietly(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException ignored) {
            return objectMapper.createObjectNode();
        }
    }

    private String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field);
            if (StringUtils.hasText(value)) {
                return value;
            }
        }
        return "";
    }

    private String firstNonBlank(String first, String second) {
        return StringUtils.hasText(first) ? first : second;
    }

    private String text(JsonNode node, String field) {
        return node == null ? "" : node.path(field).asText("");
    }

    private String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName();
    }

    private void invalidateToken() {
        synchronized (tokenLock) {
            accessToken = null;
            tokenExpiresAt = Instant.EPOCH;
        }
    }

    @Override
    @PreDestroy
    public void close() {
        invalidateToken();
    }
}
