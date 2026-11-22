package com.medianexus.orchestrator.integration.douban;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 固定公共 JSON 白名单；串行低频、有界分页，挑战后停源，不使用身份轮换。 */
@Component
public class DoubanChartClient {
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private final ObjectMapper json;
    private final JsonRead read;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private Instant nextRequest = Instant.EPOCH;
    private final Map<String, Instant> pausedHosts = new HashMap<>();
    private final Map<Long, Subject> summaries = new LinkedHashMap<>();

    @Autowired
    public DoubanChartClient(ObjectMapper json) { this.json = json; this.read = this::request; }
    DoubanChartClient(ObjectMapper json, JsonRead read) { this.json = json; this.read = read; }

    public List<Subject> fetch(ChartId chart) {
        List<Subject> subjects = new ArrayList<>();
        Set<Long> ids = new HashSet<>();
        Integer expected = null;
        int pageSize = chart.limit() > 0 ? chart.limit() : 50;
        for (int page = 0, offset = 0; page < 20; page++) {
            JsonNode root = read.get("https://m.douban.com/rexxar/api/v2/subject_collection/" + chart.name()
                    + "/items?start=" + offset + "&count=" + pageSize,
                    chart == ChartId.movie_weekly_best ? "https://m.douban.com/movie"
                            : "https://m.douban.com/subject_collection/" + chart.name());
            JsonNode items = root.path("subject_collection_items");
            int total = integer(root.get("total"), -1);
            int count = integer(root.get("count"), -1);
            if (!items.isArray() || total < 1 || total > 10000 || count < 1 || count > pageSize
                    || integer(root.get("start"), -1) != offset || root.has("code")
                    || (expected != null && expected != total)) throw invalid();
            expected = total;
            int wanted = chart.limit() > 0 ? Math.min(chart.limit(), total) : total;
            int slots = Math.min(count, total - offset);
            if (items.size() != slots || items.isEmpty()) throw new ChartReadException("豆瓣榜单分页不完整（偏移 " + offset
                    + "，应有 " + slots + "，实有 " + items.size() + "），保留上次成功清单");
            for (JsonNode node : items) {
                Subject subject = subject(node, chart, subjects.size() + 1);
                if (!ids.add(subject.id())) throw invalid();
                if ((chart == ChartId.movie_top250 || chart == ChartId.movie_weekly_best)
                        && (subject.rank() == null || subject.rank() != subjects.size() + 1)) throw invalid();
                subjects.add(subject);
                if (subjects.size() == wanted) break;
            }
            if (subjects.size() == wanted) return List.copyOf(subjects);
            offset += items.size();
        }
        throw new ChartReadException("榜单分页超过安全上限，保留上次成功清单");
    }

    private Subject subject(JsonNode node, ChartId chart, int position) {
        long id = id(node.path("id"));
        String title = text(node, "title");
        String type = text(node, "type");
        if (id <= 0 || title == null || !("SERIES".equals(chart.mediaType()) ? "tv".equals(type) : "movie".equals(type))) throw invalid();
        Integer year = year(text(node, "year"));
        if (year == null) {
            String subtitle = text(node, "card_subtitle");
            if (subtitle != null && subtitle.matches("^[12][0-9]{3}(?:\\s*/.*)?$")) year = Integer.valueOf(subtitle.substring(0,4));
        }
        JsonNode rating = node.path("rating");
        Double score = rating.path("value").isNumber() ? rating.path("value").doubleValue() : null;
        Long votes = rating.path("count").isIntegralNumber() ? rating.path("count").longValue() : null;
        if (score != null && (!Double.isFinite(score) || score < 0 || score > 10) || votes != null && votes < 0) throw invalid();
        // 热门接口没有显式 rank；保存来源列表位置，UI 按热门位置而非评分排名展示。
        Integer rank = node.hasNonNull("rank") ? integer(node.get("rank"), -1) : null;
        if (rank == null && chart.limit() > 0) rank = position;
        if (rank != null && rank < 1) throw invalid();
        Subject result = new Subject(id, title, text(node,"original_title"), cover(node), year,
                chart.mediaType(), score, votes, rank, text(node,"episodes_info"), Instant.now());
        if (year == null) {
            Subject cached;
            synchronized (summaries) { cached = summaries.get(id); }
            result = mergeSummary(result, cached == null ? summary(result) : cached);
        }
        return result;
    }

    /** 摘要只补目录白名单；总集数不证明完结，缺新进度时绝不复用旧完结结论。 */
    public Subject refreshSubject(Subject previous) {
        if (!"SERIES".equals(previous.mediaType())) return summary(previous);
        JsonNode node = read.get("https://m.douban.com/rexxar/api/v2/tv/" + previous.id(),
                "https://m.douban.com/tv/subject/" + previous.id() + "/");
        if (id(node.path("id")) != previous.id() || !"tv".equals(text(node,"type")) || node.has("code")) throw invalid();
        return new Subject(previous.id(), previous.title(), previous.originalTitle(), previous.coverUrl(),
                previous.year(), previous.mediaType(), previous.score(), previous.votes(), previous.rank(),
                text(node,"episodes_info"), Instant.now());
    }
    private Subject summary(Subject previous) {
        JsonNode root = read.get("https://movie.douban.com/j/subject_abstract?subject_id=" + previous.id(), "https://movie.douban.com/");
        JsonNode node = root.path("subject");
        if (integer(root.get("r"), -1) != 0 || id(node.path("id")) != previous.id()
                || !node.path("is_tv").isBoolean()
                || node.path("is_tv").booleanValue() != "SERIES".equals(previous.mediaType())) throw invalid();
        Integer year = year(text(node, "release_year"));
        String progress = text(node, "episodes_info");
        Subject fresh = new Subject(previous.id(), previous.title(), previous.originalTitle(), previous.coverUrl(),
                year == null ? previous.year() : year, previous.mediaType(), previous.score(), previous.votes(),
                previous.rank(), progress, Instant.now());
        synchronized (summaries) {
            if (summaries.size() >= 5000) summaries.remove(summaries.keySet().iterator().next());
            summaries.put(previous.id(), fresh);
        }
        return fresh;
    }

    private Subject mergeSummary(Subject source, Subject cached) {
        return new Subject(source.id(), source.title(), source.originalTitle(), source.coverUrl(), cached.year(),
                source.mediaType(), source.score(), source.votes(), source.rank(), source.progress(), source.observedAt());
    }

    private synchronized JsonNode request(String url, String referer) {
        URI uri = URI.create(url);
        Instant paused = pausedHosts.get(uri.getHost());
        if (paused != null && paused.isAfter(Instant.now())) throw new ChartReadException("豆瓣来源暂停，稍后再检查");
        try {
            long wait = Duration.between(Instant.now(), nextRequest).toMillis();
            if (wait > 0) Thread.sleep(wait);
            nextRequest = Instant.now().plusSeconds(8);
            HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(22))
                    .header("Accept", "application/json").header("Referer", referer)
                    .header("User-Agent", "MediaNexus/1.0 (read-only public catalog)").GET().build();
            var pending = http.sendAsync(request, ignored -> new BoundedJsonBody());
            try {
                var response = pending.get(22, java.util.concurrent.TimeUnit.SECONDS);
                int status = response.statusCode();
                if (status == 401 || status == 403 || status == 429 || status >= 300 && status < 400) {
                    pausedHosts.put(uri.getHost(), Instant.now().plus(Duration.ofHours(24)));
                    throw new ChartReadException("豆瓣访问受限，来源已暂停，不使用旧清单受理");
                }
                if (status != 200) throw new ChartReadException("豆瓣读取失败（HTTP " + status + "）");
                byte[] bytes = response.body();
                String contentType = response.headers().firstValue("content-type").orElse("");
                if (!contentType.toLowerCase(Locale.ROOT).contains("json")) {
                    pausedHosts.put(uri.getHost(), Instant.now().plus(Duration.ofHours(24)));
                    throw new ChartReadException("豆瓣返回非 JSON 或安全验证，来源已暂停");
                }
                JsonNode root = json.readTree(bytes);
                if (root == null || !root.isObject()) throw invalid();
                if (root.has("code") && Set.of(401,403,429).contains(root.path("code").asInt())) {
                    pausedHosts.put(uri.getHost(), Instant.now().plus(Duration.ofHours(24)));
                    throw new ChartReadException("豆瓣返回访问限制，来源已暂停");
                }
                return root;
            } catch (java.util.concurrent.TimeoutException failure) {
                throw new ChartReadException("豆瓣 JSON 请求超时（22 秒），保留上次成功清单", failure);
            } catch (java.util.concurrent.ExecutionException failure) {
                throw new ChartReadException("豆瓣 JSON 连接或读取失败，保留上次成功清单", failure.getCause());
            } finally { pending.cancel(true); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new ChartReadException("豆瓣检查已中断");
        } catch (IOException e) { throw new ChartReadException("豆瓣 JSON 读取失败，保留上次成功清单", e); }
    }

    private static String cover(JsonNode node) {
        String url = text(node.path("cover"), "url");
        if (url == null) url = text(node.path("pic"), "normal");
        if (url == null) url = text(node,"cover_url");
        return url != null && url.startsWith("https://") ? url : null;
    }
    private static String text(JsonNode node,String key) {
        JsonNode value = node.path(key);
        return value.isTextual() && !value.textValue().isBlank() ? value.textValue().strip() : null;
    }
    private static int integer(JsonNode node,int fallback) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : fallback;
    }
    private static long id(JsonNode node) {
        String raw = node.asText("");
        if (!raw.matches("[1-9][0-9]{0,17}")) return -1;
        return Long.parseLong(raw);
    }
    private static Integer year(String raw) { return raw != null && raw.matches("[12][0-9]{3}") ? Integer.valueOf(raw) : null; }
    private static ChartReadException invalid() { return new ChartReadException("豆瓣榜单结构或分页不完整，保留上次成功清单"); }
    /** 在接收期间限字节，总超时由请求方覆盖响应体读取。 */
    private static final class BoundedJsonBody implements HttpResponse.BodySubscriber<byte[]> {
        private final java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(invalid()); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable failure) { result.completeExceptionally(failure); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }

    @FunctionalInterface interface JsonRead { JsonNode get(String url, String referer); }
    public static class ChartReadException extends RuntimeException {
        public ChartReadException(String message, Throwable cause) { super(message, cause); }
        public ChartReadException(String message) { super(message); }
    }
}
