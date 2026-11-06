package com.medianexus.orchestrator.integration.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** 认证只进入请求头；失败不携带原始响应、URL、请求体或异常 cause。 */
final class OnboardingHttp {
    private final String base;
    private final Map<String, String> headers;
    private final Duration timeout;
    private final ObjectMapper json;
    private final HttpClient client;

    OnboardingHttp(String base, Map<String, String> headers, Duration timeout, ObjectMapper json) {
        try {
            URI uri = URI.create(base);
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null) {
                throw failure("上游有效地址未配置或格式不支持");
            }
        } catch (IllegalArgumentException exception) {
            throw failure("上游有效地址未配置或格式不支持");
        }
        this.base = base.replaceAll("/+$", "");
        this.headers = Map.copyOf(headers);
        this.timeout = timeout;
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    String readText(String path) {
        return send(request(path).GET().build());
    }

    JsonNode read(String path) {
        return parse(readText(path));
    }

    JsonNode post(String path, JsonNode body) {
        return parse(send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString())).build()));
    }

    private HttpRequest.Builder request(String path) {
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout);
            headers.forEach(request::header);
            return request;
        } catch (IllegalArgumentException exception) {
            throw unavailable("UPSTREAM_CONFIGURATION_INVALID: 上游有效连接或认证格式无效");
        }
    }

    private String send(HttpRequest request) {
        try {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) { throw new BusinessException(ErrorCode.UNAUTHORIZED,
                    "UPSTREAM_UNAUTHORIZED: 上游认证失败", HttpStatus.BAD_GATEWAY); }
            if (response.statusCode() == 403) { throw new BusinessException(ErrorCode.FORBIDDEN,
                    "UPSTREAM_FORBIDDEN: 上游拒绝此操作权限", HttpStatus.BAD_GATEWAY); }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw failure("上游协议请求被拒绝，HTTP " + response.statusCode());
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure("上游协议请求已中断");
        } catch (IOException exception) {
            throw failure("上游协议请求失败或超时，请先读回核对资源");
        }
    }

    private JsonNode parse(String body) {
        try {
            return body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (IOException exception) {
            throw failure("上游返回不兼容的协议响应");
        }
    }

    static BusinessException failure(String message) {
        return new BusinessException(ErrorCode.BAD_GATEWAY, message, HttpStatus.BAD_GATEWAY);
    }
    static BusinessException unavailable(String message) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
    }
}
