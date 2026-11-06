package com.medianexus.orchestrator.integration.bangumi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.BangumiProperties;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.CollectedSubject;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 官方匿名只读动画想看接口。完整分页数量、偏移和 ID 唯一性全部一致才返回；
 * 公开列表不代表已授权私有收藏，既有证据见 diagnostics/bgm-wishlist-research-2026-11-06/api-contract.md。
 */
@Component
public class BangumiClient {
    private static final Logger log = LoggerFactory.getLogger(BangumiClient.class);
    private final BangumiProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public BangumiClient(BangumiProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder().connectTimeout(properties.getTimeout()).build());
    }

    public BangumiClient(BangumiProperties properties, ObjectMapper json, HttpClient http) {
        this.properties = properties;
        this.json = json;
        this.http = http;
    }

    public List<CollectedSubject> readWishlist(String username) {
        List<CollectedSubject> subjects = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        int offset = 0;
        Integer total = null;
        do {
            JsonNode page = get("/v0/users/" + URLEncoder.encode(username, StandardCharsets.UTF_8)
                    + "/collections?subject_type=2&type=1&limit=50&offset=" + offset);
            JsonNode entries = page.path("data");
            if (!page.path("total").isIntegralNumber() || !page.path("total").canConvertToInt() || page.path("total").asInt() < 0
                    || !page.path("offset").isIntegralNumber() || !page.path("offset").canConvertToInt() || page.path("offset").asInt() != offset
                    || !page.path("limit").isIntegralNumber() || !page.path("limit").canConvertToInt() || page.path("limit").asInt() < 1
                    || page.path("limit").asInt() > 50 || !entries.isArray()
                    || entries.size() > page.path("limit").asInt()) throw incomplete();
            int pageTotal = page.path("total").intValue();
            if (total != null && total != pageTotal) throw incomplete();
            total = pageTotal;
            if (entries.isEmpty() && offset < total) throw incomplete();
            for (JsonNode entry : entries) {
                JsonNode subject = entry.path("subject");
                if (!entry.path("subject_id").isIntegralNumber() || entry.path("subject_id").asLong() <= 0
                        || entry.path("subject_type").asInt() != 2 || entry.path("type").asInt() != 1
                        || subject.path("type").asInt() != 2
                        || subject.path("id").asLong() != entry.path("subject_id").asLong()
                        || !ids.add(entry.path("subject_id").asLong())) throw incomplete();
                String original = text(subject, "name");
                String title = text(subject, "name_cn");
                if (title == null) title = original;
                if (title == null) throw incomplete();
                String updatedAt = text(entry, "updated_at");
                try {
                    Instant.parse(updatedAt == null ? "" : updatedAt);
                } catch (DateTimeParseException exception) {
                    throw incomplete();
                }
                String cover = text(subject.path("images"), "large");
                if (cover == null) cover = text(subject.path("images"), "common");
                subjects.add(new CollectedSubject(entry.path("subject_id").longValue(), title, original,
                        cover, year(text(subject, "date")), updatedAt));
            }
            offset += entries.size();
            if (offset > total) throw incomplete();
        } while (offset < total);
        subjects.sort(Comparator.comparing((CollectedSubject subject) -> Instant.parse(subject.updatedAt()))
                .reversed().thenComparingLong(CollectedSubject::subjectId));
        return List.copyOf(subjects);
    }

    private JsonNode get(String path) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + path))
                .timeout(properties.getTimeout()).header("Accept", "application/json")
                .header("User-Agent", properties.getUserAgent()).GET().build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) throw failedRead("HTTP_STATUS", response.statusCode(), null);
            JsonNode payload = json.readTree(response.body());
            if (payload == null || !payload.isObject()) throw failedRead("INVALID_JSON_OBJECT", 200, null);
            return payload;
        } catch (JsonProcessingException exception) {
            throw failedRead("JSON_PARSE", 200, exception);
        } catch (IOException exception) {
            throw failedRead("TRANSPORT", null, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failedRead("INTERRUPTED", null, exception);
        }
    }

    private BangumiReadException incomplete() {
        return failedRead("INCOMPLETE_COLLECTION", 200, null);
    }

    private BangumiReadException failedRead(String category, Integer status, Throwable cause) {
        // JSON/HTTP 异常消息可含响应体或 URL；仅保留异常类型和调用栈给运维。
        RuntimeException diagnostic = null;
        if (cause != null) {
            diagnostic = new RuntimeException(cause.getClass().getName());
            diagnostic.setStackTrace(cause.getStackTrace());
        }
        log.warn("BGM 想看读取失败 category={} status={}", category, status, diagnostic);
        return new BangumiReadException(cause);
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().trim() : null;
    }

    private Integer year(String date) {
        if (date == null || !date.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        return Integer.valueOf(date.substring(0, 4));
    }

    /** 不携带响应体、请求凭据或上游原始错误到页面与执行明细。 */
    public static class BangumiReadException extends RuntimeException {
        public BangumiReadException() {
            this(null);
        }

        private BangumiReadException(Throwable cause) {
            super("BGM 想看清单读取失败或分页不完整", cause);
        }
    }
}
