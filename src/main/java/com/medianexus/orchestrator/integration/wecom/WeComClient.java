package com.medianexus.orchestrator.integration.wecom;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.WeComProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** 单成员 news 发送及应用 token 缓存；API 受理不等于微信送达，不自动重发。 */
@Component
public class WeComClient {

    private final WeComProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final Clock clock;
    private String accessToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    @Autowired
    public WeComClient(WeComProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build(), Clock.systemUTC());
    }

    WeComClient(WeComProperties properties, ObjectMapper objectMapper, HttpClient httpClient, Clock clock) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
        this.clock = clock;
    }

    /** 纯文本异常消息不依赖封面或 TMDB。 */
    public void sendText(String content) {
        validateConfiguration();
        ObjectNode payload = objectMapper.createObjectNode()
                .put("touser", properties.getUserId().trim())
                .put("agentid", properties.getAgentId()).put("msgtype", "text");
        payload.putObject("text").put("content", content);
        String token = token();
        JsonNode response = request(HttpRequest.newBuilder(uri("/cgi-bin/message/send?access_token=" + encode(token)))
                .timeout(properties.getTimeout()).header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build());
        int code = response.path("errcode").asInt(-1);
        if (code == 40014 || code == 42001 || code == 40001) invalidateToken(token);
        requireSuccess(response);
    }

    public void sendNews(String title, String description, String imageUrl, String detailUrl) {
        validateConfiguration();
        ObjectNode article = objectMapper.createObjectNode()
                .put("title", title)
                .put("description", description)
                .put("picurl", imageUrl)
                .put("url", detailUrl);
        ObjectNode payload = objectMapper.createObjectNode()
                .put("touser", properties.getUserId().trim())
                .put("agentid", properties.getAgentId())
                .put("msgtype", "news");
        payload.putObject("news").putArray("articles").add(article);
        String token = token();
        JsonNode response = request(HttpRequest.newBuilder(uri("/cgi-bin/message/send?access_token=" + encode(token)))
                .timeout(properties.getTimeout())
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8))
                .build());
        int code = response.path("errcode").asInt(-1);
        if (code == 40014 || code == 42001 || code == 40001) {
            invalidateToken(token);
        }
        requireSuccess(response);
    }

    private synchronized String token() {
        if (accessToken != null && clock.instant().isBefore(tokenExpiresAt)) {
            return accessToken;
        }
        JsonNode response = request(HttpRequest.newBuilder(uri("/cgi-bin/gettoken?corpid="
                        + encode(properties.getCorpId().trim()) + "&corpsecret=" + encode(properties.getAppSecret())))
                .timeout(properties.getTimeout())
                .GET()
                .build());
        requireSuccess(response);
        String receivedToken = response.path("access_token").asText();
        long expiresIn = response.path("expires_in").asLong();
        if (!StringUtils.hasText(receivedToken) || expiresIn <= 0) {
            throw new IllegalStateException("企业微信 token 响应缺少有效 token 或有效期");
        }
        accessToken = receivedToken;
        tokenExpiresAt = clock.instant().plusSeconds(expiresIn - Math.min(60, expiresIn / 10));
        return accessToken;
    }

    private synchronized void invalidateToken(String rejectedToken) {
        if (rejectedToken.equals(accessToken)) {
            accessToken = null;
            tokenExpiresAt = Instant.EPOCH;
        }
    }

    private JsonNode request(HttpRequest request) {
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("企业微信 API 返回 HTTP " + response.statusCode());
            }
            JsonNode payload = objectMapper.readTree(response.body());
            if (payload == null || !payload.isObject()) {
                throw new IllegalStateException("企业微信 API 响应不是 JSON 对象");
            }
            return payload;
        } catch (IOException exception) {
            // 请求 URI 带有 Secret/token，异常链和原始响应均不得进入日志。
            throw new IllegalStateException("企业微信 API 请求或响应解析失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("企业微信 API 请求被中断");
        }
    }

    private void requireSuccess(JsonNode response) {
        int code = response.path("errcode").asInt(-1);
        if (code != 0) {
            throw new IllegalStateException("企业微信 API 业务错误码 " + code);
        }
        for (String field : new String[]{"invaliduser", "invalidparty", "invalidtag", "unlicenseduser"}) {
            if (StringUtils.hasText(response.path(field).asText())) {
                throw new IllegalStateException("企业微信 API 返回无效或无许可接收对象：" + field);
            }
        }
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(properties.getCorpId()) || !StringUtils.hasText(properties.getAppSecret())
                || properties.getAgentId() == null || properties.getAgentId() <= 0
                || !StringUtils.hasText(properties.getUserId())) {
            throw new IllegalStateException("企业微信通知缺少企业、应用或接收成员配置");
        }
        String userId = properties.getUserId().trim();
        if (userId.startsWith("@") || userId.contains("|") || userId.chars().anyMatch(Character::isWhitespace)) {
            throw new IllegalStateException("企业微信通知仅支持一个明确的接收成员");
        }
    }

    private URI uri(String path) {
        try {
            URI base = URI.create(properties.getApiBaseUrl().trim());
            if (!("https".equals(base.getScheme()) || "http".equals(base.getScheme())) || base.getHost() == null
                    || base.getRawUserInfo() != null || base.getRawQuery() != null || base.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return URI.create(base.toString().replaceAll("/+$", "") + path);
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException("企业微信 API 地址须为不含凭据、查询参数或片段的 HTTP(S) 地址");
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
