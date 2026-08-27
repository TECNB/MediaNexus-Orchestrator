package com.medianexus.orchestrator.integration.smartstrm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.QasProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Triggers the existing SmartStrm webhook after direct Quark ingest. */
@Component
public class SmartStrmWebhookClient {

    private final QasProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SmartStrmWebhookClient(QasProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout() == null ? Duration.ofSeconds(15) : properties.getTimeout())
                .build();
    }

    public void trigger(String savePath, String mediaType) {
        if (!StringUtils.hasText(properties.getSmartstrmWebhook())) {
            throw new IllegalStateException("SmartStrm Webhook 未配置");
        }
        ObjectNode data = objectMapper.createObjectNode()
                .put("strmtask", "SERIES".equals(mediaType) ? "Quark-TV" : "VARIETY".equals(mediaType) ? "Quark-Variety" : "Quark-TV")
                .put("savepath", savePath)
                .put("xlist_path_fix", "");
        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("event", "qas_strm");
        payload.set("data", data);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getSmartstrmWebhook().trim()))
                    .timeout(properties.getTimeout() == null ? Duration.ofSeconds(15) : properties.getTimeout())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload.toString()))
                    .build();
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("SmartStrm Webhook 返回 HTTP " + response.statusCode());
            }
        } catch (IOException exception) {
            throw new IllegalStateException("SmartStrm Webhook 请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SmartStrm Webhook 请求被中断", exception);
        }
    }
}
