package com.medianexus.orchestrator.integration.bangumi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.BangumiProperties;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.IdentityInput;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 按 BGM ID 消费 open-ani/bangumi-tmdb 的明确类型／季度映射（CC BY 4.0）。
 * 来源：https://github.com/open-ani/bangumi-tmdb；只提供目录候选，调用方仍须实时核实 TMDB。
 * manifest 固定数据版本并校验 SHA-256，避免发布更新时混用文件；不按标题或“第几部分”猜季。
 */
@Component
public class BgmTmdbMappings {
    private final BangumiProperties properties;
    private final ObjectMapper json;
    private final HttpClient http;
    private Map<Long, IdentityInput> cached = Map.of();
    private Instant expiresAt = Instant.EPOCH;

    @Autowired
    public BgmTmdbMappings(BangumiProperties properties, ObjectMapper json) {
        this(properties, json, HttpClient.newBuilder().connectTimeout(properties.getTimeout())
                .followRedirects(HttpClient.Redirect.NORMAL).build());
    }

    public BgmTmdbMappings(BangumiProperties properties, ObjectMapper json, HttpClient http) {
        this.properties = properties;
        this.json = json;
        this.http = http;
    }

    public synchronized Map<Long, IdentityInput> find(Set<Long> subjectIds) {
        if (!Instant.now().isBefore(expiresAt)) {
            Map<Long, IdentityInput> loaded = load();
            cached = loaded;
            expiresAt = Instant.now().plus(properties.getMappingCacheTtl());
        }
        Map<Long, IdentityInput> matches = new HashMap<>();
        for (Long id : subjectIds) {
            IdentityInput match = cached.get(id);
            if (match != null) matches.put(id, match);
        }
        return Map.copyOf(matches);
    }

    private Map<Long, IdentityInput> load() {
        try {
            String base = properties.getMappingReleaseBaseUrl().replaceAll("/+$", "");
            JsonNode manifest = json.readTree(get(base + "/latest/download/manifest.json"));
            if (manifest == null || manifest.path("schemaVersion").asInt() != 1) throw invalid();
            String commit = manifest.path("sourceCommit").asText();
            String expected = manifest.path("files").path("subjects.json").path("sha256").asText();
            if (!commit.matches("[a-f0-9]{40}") || !expected.matches("[a-f0-9]{64}")) throw invalid();
            byte[] raw = get(base + "/download/data-" + commit + "/subjects.json");
            String actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
            if (!expected.equals(actual)) throw invalid();
            JsonNode payload = json.readTree(raw);
            if (payload == null || payload.path("schemaVersion").asInt() != 1
                    || !payload.path("subjects").isArray()) throw invalid();
            Map<Long, IdentityInput> matches = new HashMap<>();
            Set<Long> seen = new HashSet<>();
            for (JsonNode subject : payload.path("subjects")) {
                if (!subject.path("bangumiId").isIntegralNumber() || subject.path("bangumiId").asLong() <= 0
                        || !seen.add(subject.path("bangumiId").asLong())) throw invalid();
                IdentityInput target = uniqueTarget(subject.path("targets"));
                if (target != null) matches.put(subject.path("bangumiId").asLong(), target);
            }
            return Map.copyOf(matches);
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new MappingUnavailableException(exception);
        }
    }

    private byte[] get(String url) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).timeout(properties.getTimeout())
                .header("User-Agent", properties.getUserAgent()).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) throw new IOException("映射数据返回 HTTP " + response.statusCode());
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("映射数据请求中断", exception);
        }
    }

    private IdentityInput uniqueTarget(JsonNode targets) {
        if (!targets.isArray() || targets.isEmpty()) return null;
        Set<IdentityInput> regular = new HashSet<>();
        Set<Integer> specialSeries = new HashSet<>();
        for (JsonNode target : targets) {
            if (!target.path("id").isIntegralNumber() || !target.path("id").canConvertToInt()
                    || target.path("id").asInt() <= 0) return null;
            int id = target.path("id").asInt();
            if ("movie".equals(target.path("type").asText())) {
                regular.add(new IdentityInput("MOVIE", id, null));
            } else if ("tv".equals(target.path("type").asText())) {
                if (!target.path("season").isIntegralNumber() || !target.path("season").canConvertToInt()
                        || target.path("season").asInt() < 0) return null;
                int season = target.path("season").asInt();
                if (season == 0) specialSeries.add(id);
                else regular.add(new IdentityInput("SERIES", id, season));
            } else return null;
        }
        if (regular.size() != 1) return null;
        IdentityInput only = regular.iterator().next();
        // 同一系列附带的 S00 不属于本期正片整季目标；多个正片季仍须人工决定。
        if (!specialSeries.isEmpty() && (!"SERIES".equals(only.mediaType())
                || specialSeries.stream().anyMatch(id -> !id.equals(only.tmdbId())))) return null;
        return only;
    }

    private MappingUnavailableException invalid() {
        return new MappingUnavailableException(new IOException("自动映射格式或完整性校验失败"));
    }

    public static class MappingUnavailableException extends RuntimeException {
        public MappingUnavailableException(Exception cause) {
            super("BGM 自动目录映射暂不可用", cause);
        }
    }
}
