package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.integration.wecom.WeComClient;
import com.medianexus.orchestrator.mapper.IngestExceptionNoticeMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.net.URI;

/** 无图异常消息；调用方在后台执行，发送结果不改变入库结果。SENT 仅表示企微接口受理。 */
@Service
public class IngestExceptionNotifier {
    private static final Logger log = LoggerFactory.getLogger(IngestExceptionNotifier.class);
    private final WeComProperties properties;
    private final WeComClient client;
    private final IngestExceptionNoticeMapper notices;
    private volatile boolean ready;

    public IngestExceptionNotifier(WeComProperties properties, WeComClient client, IngestExceptionNoticeMapper notices) {
        this.properties = properties; this.client = client; this.notices = notices;
    }

    public String send(String key, String title, String body, String path) {
        if (!properties.isEnabled()) return "DISABLED";
        try {
            ensureTable();
            if (notices.claim(key) == 0) {
                String existing = notices.status(key);
                return existing == null ? "UNKNOWN" : existing;
            }
        } catch (RuntimeException exception) {
            log.warn("入库异常通知受理结果无法核实 event={}", key);
            return "UNKNOWN";
        }
        try {
            String base = properties.getFrontendBaseUrl();
            URI uri = URI.create(base);
            if (!java.util.List.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                throw new IllegalStateException("通知站点地址无效");
            }
            client.sendText(title + "\n" + body + "\n" + base.replaceAll("/+$", "") + path);
        } catch (RuntimeException exception) {
            log.warn("入库异常通知发送失败 event={}", key);
            try { notices.update(key, "FAILED"); } catch (RuntimeException ignored) { /* 业务结果优先保留。 */ }
            return "FAILED";
        }
        try { notices.update(key, "SENT"); }
        catch (RuntimeException exception) { log.warn("企业微信已受理，通知结果持久化失败 event={}", key); }
        return "SENT";
    }

    public String status(String key) {
        if (!properties.isEnabled()) return "DISABLED";
        try { ensureTable(); return notices.status(key); }
        catch (RuntimeException exception) { return "UNKNOWN"; }
    }

    private synchronized void ensureTable() {
        if (!ready) { notices.createTable(); ready = true; }
    }
}
