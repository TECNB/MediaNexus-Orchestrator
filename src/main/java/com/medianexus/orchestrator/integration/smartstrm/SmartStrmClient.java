package com.medianexus.orchestrator.integration.smartstrm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.SmartStrmProperties;
import com.medianexus.orchestrator.integration.autosymlink.AutoSymlinkClientException;
import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** v0.5.1 cookie 会话；只读认证恢复最多一次，任何写入结果未知都不重放。 */
@Component
public class SmartStrmClient {
    private final SmartStrmProperties properties;
    private final ObjectMapper json;
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    private final HttpClient http = HttpClient.newBuilder().build();
    private volatile boolean authenticated;
    private volatile String webhookPath;

    public SmartStrmClient(SmartStrmProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public boolean isEnabled() { return properties.isEnabled(); }
    public JsonNode tasks() { return readJson("/api/tasks"); }
    public JsonNode storages() { return readJson("/api/storages"); }
    public JsonNode browse(String storage, String path) { return readJson("/api/storage/" + encode(storage) + "/browse?path=" + encode(path)); }
    public String taskLog(String name) { return read("/api/tasks/" + encode(name) + "/log"); }
    public void saveTask(String name, JsonNode config) { write("/api/tasks/" + encode(name), config); }
    public void saveStorage(String name, JsonNode config) { write("/api/storages/" + encode(name), config); }
    public void run(String name, String path) {
        run(json.createObjectNode().put("name", name).put("storage_path", path).put("incremental", true).put("keep_local_asset", true));
    }

    /** 官方 a_task 一次提交；仅将设置中的 secret 路径用于已配置的 API origin。 */
    public void run(com.fasterxml.jackson.databind.node.ObjectNode task) {
        String path = webhookPath;
        if (path == null) {
            String configured = readJson("/api/settings").path("webhook").path("url").asText();
            try {
                URI uri = URI.create(configured);
                path = uri.getRawPath();
                if (path == null || !path.matches("/webhook/[A-Za-z0-9_-]+") || uri.getRawQuery() != null || uri.getRawFragment() != null)
                    throw new IllegalArgumentException();
            } catch (IllegalArgumentException invalid) { throw failure("SmartStrm Webhook 设置无效"); }
            webhookPath = path;
        }
        var payload = json.createObjectNode().put("event", "a_task").put("delay", 0).put("debounce", 0);
        payload.set("task", task);
        write(path, payload);
    }

    private JsonNode readJson(String path) {
        try {
            JsonNode value = json.readTree(read(path));
            if (value == null) throw failure("SmartStrm 读取协议无效");
            return value;
        }
        catch (IOException exception) { throw failure("SmartStrm 读取协议无效"); }
    }

    private String read(String path) {
        ensureSession();
        HttpResponse<String> response = send("GET", path, null, null);
        if (expired(response)) {
            login();
            response = send("GET", path, null, null);
        }
        requireSuccess(response);
        return response.body();
    }

    private void write(String path, JsonNode body) {
        ensureSession();
        HttpResponse<String> response = send("POST", path, body.toString(), "application/json");
        if (expired(response)) {
            authenticated = false;
            // 恢复会话供后续只读核验使用；原写请求仍保持未知，不能重放。
            login();
            throw failure("SmartStrm 写入认证失效，结果未知；未重复提交");
        }
        requireSuccess(response);
    }

    private void ensureSession() {
        if (!properties.isEnabled() || !StringUtils.hasText(properties.getBaseUrl())
                || !StringUtils.hasText(properties.getUsername()) || !StringUtils.hasText(properties.getPassword())
                || properties.getTimeout() == null || properties.getTimeout().isNegative() || properties.getTimeout().isZero()) {
            throw failure("SmartStrm 未启用或连接配置不完整");
        }
        if (!authenticated) login();
    }

    private synchronized void login() {
        cookies.getCookieStore().removeAll();
        authenticated = false;
        HttpResponse<String> response = send("POST", "/login", "username=" + encode(properties.getUsername())
                + "&password=" + encode(properties.getPassword()), "application/x-www-form-urlencoded");
        if (response.statusCode() < 200 || response.statusCode() >= 400 || cookies.getCookieStore().getCookies().isEmpty()) {
            throw failure("SmartStrm 管理员登录失败");
        }
        // 不跟随重定向，避免把登录 HTML 误认为受保护 API 的成功响应。
        HttpResponse<String> probe = send("GET", "/api/tasks", null, null);
        requireSuccess(probe);
        authenticated = true;
    }

    private HttpResponse<String> send(String method, String path, String body, String type) {
        try {
            URI base = URI.create(properties.getBaseUrl());
            if (!("http".equals(base.getScheme()) || "https".equals(base.getScheme())) || base.getHost() == null || base.getUserInfo() != null)
                throw new IllegalArgumentException();
            URI uri = base.resolve(path);
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(properties.getTimeout());
            cookies.get(uri, java.util.Map.of()).forEach((name, values) -> values.forEach(value -> request.header(name, value)));
            if (type != null) request.header("Content-Type", type);
            request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            cookies.put(uri, response.headers().map());
            return response;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("SmartStrm 请求中断；写入结果可能未知，未重试");
        } catch (IOException | IllegalArgumentException exception) {
            // 上游正文、URL、cookie、凭据和底层异常信息均不进入日志或管理响应。
            throw failure("SmartStrm 请求失败；写入结果可能未知，未重试");
        }
    }

    private boolean expired(HttpResponse<String> response) {
        String type = response.headers().firstValue("Content-Type").orElse("");
        String body = response.body().stripLeading().toLowerCase(java.util.Locale.ROOT);
        return response.statusCode() == 401 || response.statusCode() == 302 || response.statusCode() == 303
                || type.contains("text/html") || body.startsWith("<!doctype html") || body.startsWith("<html");
    }

    private void requireSuccess(HttpResponse<String> response) {
        if (expired(response) || response.statusCode() < 200 || response.statusCode() >= 300) {
            authenticated = false;
            throw failure("SmartStrm 返回非成功响应 status=" + response.statusCode() + "；写入未重试");
        }
        String body = response.body().stripLeading();
        if (body.startsWith("{") || body.startsWith("[")) {
            try {
                JsonNode value = json.readTree(body);
                if (value.path("success").isBoolean() && !value.path("success").asBoolean())
                    throw failure("SmartStrm 返回业务失败；写入未重试");
            } catch (IOException invalid) { throw failure("SmartStrm 返回协议无效；写入未重试"); }
        }
    }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static AutoSymlinkClientException failure(String message) { return new AutoSymlinkClientException(message); }
}
