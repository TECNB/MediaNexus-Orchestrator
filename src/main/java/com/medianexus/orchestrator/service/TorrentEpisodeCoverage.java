package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.medianexus.orchestrator.dto.resources.response.ReleaseEpisodeCoverageResponse;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import jakarta.annotation.PreDestroy;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 可选文件表核验的完整边界：搜索仅本地登记来源，请求仅查缓存或排队。
 * 固定协议界限为两个 worker、32 个排队任务、Nyaa 元数据 3 秒/2 MiB；不访问 peer 或文件系统。
 */
@Service
public class TorrentEpisodeCoverage implements AutoCloseable {

    static final int MAX_TORRENT_BYTES = 2 * 1024 * 1024;
    private static final Duration TORRENT_TIMEOUT = Duration.ofSeconds(3);
    private static final ReleaseEpisodeCoverageResponse CHECKING = new ReleaseEpisodeCoverageResponse("CHECKING", null, null);
    private static final ReleaseEpisodeCoverageResponse UNVERIFIED = new ReleaseEpisodeCoverageResponse("UNVERIFIED", null, null);
    private static final Pattern NYAA_ID = Pattern.compile("nyaa-([1-9][0-9]{0,18})");
    private static final Pattern VIDEO = Pattern.compile("(?i)\\.(mkv|mp4|avi|m4v|mov|wmv|ts|m2ts|webm|mpg|mpeg)$");
    private static final Pattern AUXILIARY = Pattern.compile(
            "(?i)(?:^|[^\\p{L}\\p{N}])(?:sps?|specials?|extras?|ncop|nced|op|ed|cm|menu|pv|iv|sample|ova|oad|bonus|trailer)"
                    + "(?:[0-9]+)?(?:$|[^\\p{L}\\p{N}])|特典|花絮|映像特典");
    private static final Pattern SEASON = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:s([0-9]{1,3})(?=e[0-9]|[^a-z0-9]|$)|season[ ._-]*([0-9]{1,3})(?=[^0-9]|$)"
                    + "|([0-9]{1,3})x[0-9]{1,3}(?=[^a-z0-9]|$))|第\\s*([0-9]{1,3})\\s*季");

    private final TmdbClient tmdbClient;
    private final MovieSeriesFileRenameService renameService;
    private final HttpClient httpClient;
    private final Cache<PanSouMagnetIdentity, String> sources = CacheBuilder.newBuilder()
            .maximumSize(10000).expireAfterWrite(1, TimeUnit.HOURS).build();
    private final Cache<CoverageKey, ReleaseEpisodeCoverageResponse> verified = CacheBuilder.newBuilder()
            .maximumSize(2000).expireAfterWrite(1, TimeUnit.HOURS).build();
    private final Cache<CoverageKey, ReleaseEpisodeCoverageResponse> failures = CacheBuilder.newBuilder()
            .maximumSize(2000).expireAfterWrite(3, TimeUnit.MINUTES).build();
    private final Cache<SeasonKey, Integer> expectedCounts = CacheBuilder.newBuilder()
            .maximumSize(2000).expireAfterWrite(1, TimeUnit.HOURS).build();
    private final Set<CoverageKey> pending = new HashSet<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "torrent-episode-coverage");
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public TorrentEpisodeCoverage(TmdbClient tmdbClient, MovieSeriesFileRenameService renameService) {
        this(tmdbClient, renameService, HttpClient.newBuilder().connectTimeout(TORRENT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }

    TorrentEpisodeCoverage(TmdbClient tmdbClient, MovieSeriesFileRenameService renameService, HttpClient httpClient) {
        this.tmdbClient = tmdbClient;
        this.renameService = renameService;
        this.httpClient = httpClient;
    }

    /** 仅从当前 PanSou 原始结果登记 Nyaa ID 与明确 magnet 链接；不读取客户端 URL 或正文地址。 */
    public void registerSources(JsonNode rawResults) {
        if (!rawResults.isArray()) return;
        for (JsonNode entry : rawResults) {
            Matcher id = NYAA_ID.matcher(entry.path("unique_id").asText(""));
            if (!id.matches() || !entry.path("links").isArray()) continue;
            for (JsonNode link : entry.path("links")) {
                if ("magnet".equalsIgnoreCase(link.path("type").asText())) {
                    PanSouMagnetIdentity.fromUrl(link.path("url").asText()).ifPresent(hash -> sources.put(hash, id.group(1)));
                }
            }
        }
    }

    /** 所有联网与解析都在 worker 内，重复查询不会重复排队。 */
    public synchronized ReleaseEpisodeCoverageResponse check(String downloadRef, int tmdbId, int seasonNumber) {
        PanSouMagnetIdentity identity = PanSouMagnetIdentity.fromUrl(downloadRef).orElse(null);
        if (identity == null) return UNVERIFIED;
        CoverageKey key = new CoverageKey(identity, new SeasonKey(tmdbId, seasonNumber));
        ReleaseEpisodeCoverageResponse cached = verified.getIfPresent(key);
        if (cached != null) return cached;
        if (failures.getIfPresent(key) != null) return UNVERIFIED;
        if (pending.contains(key)) return CHECKING;
        String sourceId = sources.getIfPresent(identity);
        if (sourceId == null) return UNVERIFIED;
        pending.add(key);
        try {
            workers.execute(() -> verify(key, sourceId));
            return CHECKING;
        } catch (RejectedExecutionException exception) {
            pending.remove(key);
            return UNVERIFIED;
        }
    }

    private void verify(CoverageKey key, String sourceId) {
        try {
            int expected = expectedCount(key.season());
            List<TorrentFile> files = readTorrent(fetchTorrent(sourceId), key.identity());
            ReleaseEpisodeCoverageResponse coverage = countEpisodes(files, key.season().seasonNumber(), expected);
            if ("VERIFIED".equals(coverage.status())) verified.put(key, coverage);
            else failures.put(key, UNVERIFIED);
        } catch (Exception exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            failures.put(key, UNVERIFIED);
        } finally {
            synchronized (this) {
                pending.remove(key);
            }
        }
    }

    private int expectedCount(SeasonKey season) {
        Integer cached = expectedCounts.getIfPresent(season);
        if (cached != null) return cached;
        JsonNode detail = null;
        try {
            detail = tmdbClient.getTvSeasonDetails(season.tmdbId(), season.seasonNumber(), "zh-CN");
        } catch (RuntimeException ignored) {
            // 季详情不可用时仍可用剧集目录中明确对应季度的 episode_count。
        }
        int count = seasonEpisodeCount(detail, season.seasonNumber());
        if (count == 0) {
            JsonNode seasons = tmdbClient.getTvDetails(season.tmdbId(), "zh-CN").path("seasons");
            if (seasons.isArray()) {
                int matches = 0;
                for (JsonNode candidate : seasons) {
                    if (integer(candidate.path("season_number")) == season.seasonNumber()) {
                        matches++;
                        count = integer(candidate.path("episode_count"));
                    }
                }
                if (matches != 1) count = 0;
            }
        }
        if (count <= 0) throw new IllegalArgumentException("TMDB 缺少可信目标季集数");
        expectedCounts.put(season, count);
        return count;
    }

    private static int seasonEpisodeCount(JsonNode detail, int season) {
        if (detail == null || integer(detail.path("season_number")) != season || !detail.path("episodes").isArray()) return 0;
        Set<Integer> episodes = new HashSet<>();
        for (JsonNode episode : detail.path("episodes")) {
            int number = integer(episode.path("episode_number"));
            if (number < 1 || !episodes.add(number)
                    || (episode.has("season_number") && integer(episode.path("season_number")) != season)) return 0;
        }
        // 计数须能解释 1..expected；稀疏或异常编号不能充当完整正片目录。
        return !episodes.isEmpty() && episodes.stream().allMatch(number -> number <= episodes.size()) ? episodes.size() : 0;
    }

    private static int integer(JsonNode node) {
        return node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : -1;
    }

    private byte[] fetchTorrent(String sourceId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("https://nyaa.si/download/" + sourceId + ".torrent"))
                .timeout(TORRENT_TIMEOUT).GET().build();
        CompletableFuture<HttpResponse<byte[]>> response = httpClient.sendAsync(request, headers -> {
            if (headers.statusCode() != 200 || headers.headers().firstValueAsLong("Content-Length").orElse(0) > MAX_TORRENT_BYTES) {
                throw new IllegalArgumentException("Nyaa 响应状态或声明长度无效");
            }
            return new LimitedTorrentBody();
        });
        try {
            // sendAsync 的请求 timeout 不能替代响应体整体截止时间，get 同时限制慢速 body。
            HttpResponse<byte[]> completed = response.get(TORRENT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            if (completed.statusCode() != 200 || completed.body() == null) throw new IllegalArgumentException("Nyaa 元数据不可用");
            return completed.body();
        } finally {
            response.cancel(true);
        }
    }

    ReleaseEpisodeCoverageResponse countEpisodes(List<TorrentFile> files, int season, int expected) {
        Set<Integer> matched = new HashSet<>();
        boolean recognized = false;
        for (TorrentFile file : files) {
            if (file.length() <= 0 || !VIDEO.matcher(file.path()).find()) continue;
            Matcher explicitSeason = SEASON.matcher(file.path());
            while (explicitSeason.find()) {
                for (int group = 1; group <= explicitSeason.groupCount(); group++) {
                    if (explicitSeason.group(group) != null && Integer.parseInt(explicitSeason.group(group)) != season) return UNVERIFIED;
                }
            }
            if (AUXILIARY.matcher(file.path()).find()) continue;
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            Integer episode = renameService.episodeNumber(name, season).orElse(null);
            if (episode != null) {
                recognized = true;
                if (episode >= 1 && episode <= expected) matched.add(episode);
            }
        }
        return recognized ? new ReleaseEpisodeCoverageResponse("VERIFIED", matched.size(), expected) : UNVERIFIED;
    }

    static List<TorrentFile> readTorrent(byte[] bytes, PanSouMagnetIdentity identity) throws Exception {
        if (bytes.length > MAX_TORRENT_BYTES) throw new IllegalArgumentException("种子过大");
        Bencode parser = new Bencode(bytes);
        Object root = parser.read(0);
        if (!(root instanceof Map<?, ?> dictionary) || parser.position != bytes.length || parser.infoStart < 0
                || !(dictionary.get("info") instanceof Map<?, ?> info)) throw new IllegalArgumentException("无效种子");
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(bytes, parser.infoStart, parser.infoEnd - parser.infoStart);
        if (!identity.btih().equals(HexFormat.of().formatHex(sha1.digest()))) throw new IllegalArgumentException("种子身份不匹配");
        String name = text(info.get("name"));
        List<TorrentFile> files = new ArrayList<>();
        if (info.containsKey("files")) {
            if (!(info.get("files") instanceof List<?> entries) || info.containsKey("length")) throw new IllegalArgumentException("无效文件表");
            for (Object item : entries) {
                if (!(item instanceof Map<?, ?> file) || !(file.get("path") instanceof List<?> parts) || parts.isEmpty()) {
                    throw new IllegalArgumentException("无效文件路径");
                }
                StringBuilder path = new StringBuilder(name);
                for (Object part : parts) path.append('/').append(text(part));
                files.add(new TorrentFile(path.toString(), length(file.get("length"))));
            }
        } else {
            files.add(new TorrentFile(name, length(info.get("length"))));
        }
        return files;
    }

    private static long length(Object number) {
        if (!(number instanceof Long size) || size < 0) throw new IllegalArgumentException("无效文件长度");
        return size;
    }

    private static String text(Object value) throws CharacterCodingException {
        if (!(value instanceof byte[] bytes) || bytes.length == 0) throw new IllegalArgumentException("无效文件名称");
        String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        if (text.contains("/") || text.contains("\\") || text.indexOf('\0') >= 0 || text.equals(".") || text.equals("..")) {
            throw new IllegalArgumentException("无效路径分段");
        }
        return text;
    }

    @Override
    @PreDestroy
    public void close() {
        workers.shutdownNow();
    }

    private record SeasonKey(int tmdbId, int seasonNumber) { }
    private record CoverageKey(PanSouMagnetIdentity identity, SeasonKey season) { }
    record TorrentFile(String path, long length) { }

    /** 在订阅流接收期间执行字节上限，超限立即取消，避免先把响应体全部载入内存。 */
    private static final class LimitedTorrentBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;

        @Override public CompletionStage<byte[]> getBody() { return body; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_TORRENT_BYTES - bytes.size()) {
                    subscription.cancel();
                    body.completeExceptionally(new IllegalArgumentException("种子超过 2 MiB"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable throwable) { body.completeExceptionally(throwable); }
        @Override public void onComplete() { body.complete(bytes.toByteArray()); }
    }

    /** 限制深度与节点数；info 的哈希必须取原始编码区间，不能重编码字典。 */
    private static final class Bencode {
        private final byte[] bytes;
        private int position;
        private int nodes;
        private int infoStart = -1;
        private int infoEnd;

        private Bencode(byte[] bytes) { this.bytes = bytes; }

        private Object read(int depth) {
            if (depth > 32 || ++nodes > 100000 || position >= bytes.length) throw new IllegalArgumentException("无效 bencode");
            int type = bytes[position];
            if (type == 'i') {
                int start = ++position;
                while (position < bytes.length && bytes[position] != 'e') position++;
                String number = new String(bytes, start, position - start, StandardCharsets.US_ASCII);
                if (position == bytes.length || !number.matches("0|-?[1-9][0-9]*")) throw new IllegalArgumentException("无效整数");
                position++;
                return Long.parseLong(number);
            }
            if (type == 'l' || type == 'd') {
                position++;
                List<Object> list = new ArrayList<>();
                Map<String, Object> dictionary = new HashMap<>();
                while (position < bytes.length && bytes[position] != 'e') {
                    if (type == 'l') list.add(read(depth + 1));
                    else {
                        Object keyBytes = read(depth + 1);
                        if (!(keyBytes instanceof byte[] key)) throw new IllegalArgumentException("无效字典键");
                        String name = new String(key, StandardCharsets.ISO_8859_1);
                        int start = position;
                        Object value = read(depth + 1);
                        if (dictionary.putIfAbsent(name, value) != null) throw new IllegalArgumentException("重复字典键");
                        if (depth == 0 && name.equals("info")) {
                            infoStart = start;
                            infoEnd = position;
                        }
                    }
                }
                if (position == bytes.length) throw new IllegalArgumentException("未结束的 bencode");
                position++;
                return type == 'l' ? list : dictionary;
            }
            int start = position;
            while (position < bytes.length && bytes[position] >= '0' && bytes[position] <= '9') position++;
            if (position == start || position >= bytes.length || bytes[position] != ':') throw new IllegalArgumentException("无效字节串");
            String size = new String(bytes, start, position - start, StandardCharsets.US_ASCII);
            if (size.length() > 1 && size.charAt(0) == '0') throw new IllegalArgumentException("无效字节长度");
            int length = Integer.parseInt(size);
            position++;
            if (length > bytes.length - position) throw new IllegalArgumentException("字节串截断");
            byte[] value = Arrays.copyOfRange(bytes, position, position + length);
            position += length;
            return value;
        }
    }
}
