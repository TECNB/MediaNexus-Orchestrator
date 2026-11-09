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
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
    private Application tokenApplication;
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

    public record Scope(String corpId, Long agentId) { }
    public record Member(String id, String name) { }
    public record Recipient(Scope scope, String memberId) { }
    public record Directory(Scope scope, List<Member> members) { }
    private record Application(Scope scope, String baseUrl, String secret, Duration timeout) { }

    public Scope scope() {
        String corpId = properties.getCorpId();
        return new Scope(corpId == null ? "" : corpId.trim(), properties.getAgentId());
    }

    public Directory visibleMembers() {
        Application app = application();
        JsonNode agent = get(app, "/cgi-bin/agent/get", "&agentid=" + app.scope().agentId());
        Map<String, Member> members = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (JsonNode member : agent.path("allow_userinfos").path("user")) {
            String id = member.path("userid").asText();
            if (isMemberId(id)) addMember(members, get(app, "/cgi-bin/user/get", "&userid=" + encode(id)));
        }

        var departments = new ArrayDeque<Long>();
        var visited = new HashSet<Long>();
        agent.path("allow_partys").path("partyid").forEach(node -> departments.add(node.asLong()));
        while (!departments.isEmpty()) {
            long id = departments.remove();
            if (id <= 0 || !visited.add(id)) continue;
            String query = "&department_id=" + id;
            for (JsonNode member : get(app, "/cgi-bin/user/simplelist", query).path("userlist")) addMember(members, member);
            for (JsonNode child : get(app, "/cgi-bin/department/simplelist", "&id=" + id).path("department_id")) {
                departments.add(child.path("id").asLong());
            }
        }
        for (JsonNode tag : agent.path("allow_tags").path("tagid")) {
            for (JsonNode member : get(app, "/cgi-bin/tag/get", "&tagid=" + tag.asLong()).path("userlist")) addMember(members, member);
        }
        return new Directory(app.scope(), members.values().stream()
                .sorted(Comparator.comparing(Member::name).thenComparing(Member::id)).toList());
    }

    public Recipient verifyMember(String memberId) {
        if (!isMemberId(memberId)) throw new IllegalArgumentException("企业微信成员 ID 无效");
        Application app = application();
        JsonNode member = get(app, "/cgi-bin/user/get", "&userid=" + encode(memberId));
        String id = member.path("userid").asText();
        if (!memberId.equalsIgnoreCase(id)) throw new IllegalStateException("企业微信未返回该成员");
        return new Recipient(app.scope(), id);
    }

    private void addMember(Map<String, Member> members, JsonNode member) {
        String id = member.path("userid").asText();
        if (isMemberId(id)) members.put(id, new Member(id, member.path("name").asText(id)));
    }

    public static boolean isMemberId(String id) {
        return StringUtils.hasText(id) && id.getBytes(StandardCharsets.UTF_8).length <= 64
                && !id.startsWith("@") && !id.contains("|") && !id.contains("/")
                && id.chars().noneMatch(Character::isWhitespace);
    }

    /** 纯文本异常消息不依赖封面或 TMDB。 */
    public void sendText(Recipient recipient, String content) {
        Application app = application();
        ObjectNode payload = message(app, recipient, "text");
        payload.putObject("text").put("content", content);
        send(app, payload);
    }

    public void sendNews(Recipient recipient, String title, String description, String imageUrl, String detailUrl) {
        Application app = application();
        ObjectNode payload = message(app, recipient, "news");
        payload.putObject("news").putArray("articles").addObject()
                .put("title", title).put("description", description).put("picurl", imageUrl).put("url", detailUrl);
        send(app, payload);
    }

    public record Article(String title, String description, String imageUrl, String detailUrl) {}

    public void sendTextTo(String memberId, String content) {
        sendText(new Recipient(scope(), memberId), content);
    }

    public void sendNewsTo(String memberId, List<Article> articles) {
        Application app = application();
        ObjectNode payload = message(app, new Recipient(app.scope(), memberId), "news");
        var items = payload.putObject("news").putArray("articles");
        for (Article article : articles) {
            items.addObject().put("title", article.title()).put("description", article.description())
                    .put("picurl", article.imageUrl()).put("url", article.detailUrl());
        }
        send(app, payload);
    }

    /** 只预取应用 token，不发送消息。 */
    public void warmToken() { token(application()); }

    private ObjectNode message(Application app, Recipient recipient, String type) {
        if (!app.scope().equals(recipient.scope()) || !isMemberId(recipient.memberId())) {
            throw new IllegalStateException("企业微信接收成员无效或所属应用已变更");
        }
        return objectMapper.createObjectNode().put("touser", recipient.memberId())
                .put("agentid", app.scope().agentId()).put("msgtype", type);
    }

    private void send(Application app, ObjectNode payload) {
        String token = token(app);
        JsonNode response = request(HttpRequest.newBuilder(uri(app, "/cgi-bin/message/send?access_token=" + encode(token)))
                .timeout(app.timeout()).header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build());
        checkTokenResponse(response, token);
    }

    private JsonNode get(Application app, String path, String query) {
        String token = token(app);
        JsonNode response = request(HttpRequest.newBuilder(uri(app, path + "?access_token=" + encode(token) + query))
                .timeout(app.timeout()).GET().build());
        checkTokenResponse(response, token);
        return response;
    }

    private void checkTokenResponse(JsonNode response, String token) {
        int code = response.path("errcode").asInt(-1);
        if (code == 40014 || code == 42001 || code == 40001) invalidateToken(token);
        requireSuccess(response);
    }

    private synchronized String token(Application app) {
        if (accessToken != null && app.equals(tokenApplication) && clock.instant().isBefore(tokenExpiresAt)) {
            return accessToken;
        }
        JsonNode response = request(HttpRequest.newBuilder(uri(app, "/cgi-bin/gettoken?corpid="
                        + encode(app.scope().corpId()) + "&corpsecret=" + encode(app.secret())))
                .timeout(app.timeout()).GET().build());
        requireSuccess(response);
        String receivedToken = response.path("access_token").asText();
        long expiresIn = response.path("expires_in").asLong();
        if (!StringUtils.hasText(receivedToken) || expiresIn <= 0) {
            throw new IllegalStateException("企业微信 token 响应缺少有效 token 或有效期");
        }
        accessToken = receivedToken;
        tokenApplication = app;
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

    private Application application() {
        Scope scope = scope();
        String secret = properties.getAppSecret();
        if (!StringUtils.hasText(scope.corpId()) || !StringUtils.hasText(secret)
                || scope.agentId() == null || scope.agentId() <= 0) {
            throw new IllegalStateException("企业微信缺少企业、应用或凭据配置");
        }
        return new Application(scope, properties.getApiBaseUrl(), secret, properties.getTimeout());
    }

    private URI uri(Application app, String path) {
        try {
            URI base = URI.create(app.baseUrl().trim());
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
