package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.resources.request.MovieReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseRecommendationResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseSearchResponse;
import com.medianexus.orchestrator.integration.pansou.PanSouClient;
import com.medianexus.orchestrator.integration.pansou.PanSouClientException;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchCommand;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchEntry;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchResult;
import com.medianexus.orchestrator.service.ProwlarrReleaseIngestService.NoMatchingReleaseException;
import com.medianexus.orchestrator.service.ProwlarrReleaseIngestService.ReleaseSearchProgress;
import com.medianexus.orchestrator.service.ReleaseTitleTagParser.ReleaseTitleTags;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * 独立的盘搜磁力发现与挑选。目录身份由已校验的请求提供，认证、引擎路由和提交由调用方负责。
 * 标题证据只能说明候选相关性，不能验证文件列表、做种或可下载性；未知指标保持 null。
 */
@Service
public class PanSouMagnetSearch {

    private static final Pattern EXPLICIT_SEASON = Pattern.compile(
            "(?i)(?<![a-z0-9])s(?:eason)?[ ._-]*\\d{1,2}(?!\\d)(?:[ ._-]*[-~–—][ ._-]*s?(?:eason)?[ ._-]*\\d{1,2}(?!\\d))?"
                    + "|(?<!\\d)\\d{1,2}(?:st|nd|rd|th)[ ._-]*season(?![a-z0-9])"
                    + "|第\\s*[0-9一二三四五六七八九十两]+\\s*季");
    private static final Pattern PACK = Pattern.compile(
            "(?i)(?<![a-z])(?:batch|complete|fin|all)(?![a-z])|全集|合集|完结|完結|全\\s*\\d{1,3}\\s*[集話话]");
    private static final Pattern EPISODE_RANGE = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:e(?:p)?)?(\\d{1,3})\\s*[-~–—]\\s*(?:e(?:p)?)?(\\d{1,3})(?![0-9])");
    private static final Pattern SINGLE_EPISODE = Pattern.compile(
            "(?i)(?<![a-z])(?:s\\d{1,2})?e(?:p)?[ ._-]*\\d{1,3}(?!\\d)|第\\s*\\d+\\s*[集話话]"
                    + "|(?:^|[\\s\\[\\]_-])\\d{1,3}\\s*(?:[集話话]|v[1-9])"
                    + "|[\\[【]\\s*\\d{1,3}\\s*[\\]】]|\\s-\\s*\\d{1,3}(?=\\s|$|[\\[【])");
    private static final Pattern NON_FEATURE = Pattern.compile(
            "(?i)(?<![a-z])(?:ost|soundtrack|ncop|nced|trailer|op|ed|pv|ova|oad|sp|epub|novel|manga)(?![a-z])"
                    + "|主题曲|主題歌|片头曲|片尾曲|预告|預告|截图|特典|花絮|小説|小说|漫畫|漫画|コミック|ラノベ|巻|MP3|320K");
    private static final Pattern YEAR = Pattern.compile("(?<![0-9])(?:18|19|20)\\d{2}(?![0-9])");
    private static final Pattern DATE = Pattern.compile("(?<!\\d)(?:19|20)\\d{2}[-./]\\d{1,2}[-./]\\d{1,2}(?!\\d)");
    private static final Pattern CHINESE_SUBTITLES = Pattern.compile("(?i)简繁|簡繁|简体|簡體|繁体|繁體|中文字幕|中字|(?<![a-z])(?:chs|cht|sc|tc)(?![a-z])");
    private static final Consumer<ReleaseSearchProgress> NO_PROGRESS = progress -> {};

    private final PanSouClient client;
    private final ReleaseTitleTagParser tagParser;

    public PanSouMagnetSearch(PanSouClient client, ReleaseTitleTagParser tagParser) {
        this.client = client;
        this.tagParser = tagParser;
    }

    public ProwlarrReleaseSearchResponse searchMovie(MovieReleaseSearchRequest request) {
        return searchResponse(movieTarget(request), query -> client.searchMagnets(new PanSouSearchCommand(query, false)));
    }

    public ProwlarrReleaseSearchResponse refreshMovie(MovieReleaseSearchRequest request) {
        return searchResponse(movieTarget(request), query -> client.searchMagnets(new PanSouSearchCommand(query, true)));
    }

    public ProwlarrReleaseRecommendationResponse recommendMovie(MovieReleaseRecommendationRequest request) {
        return recommendMovie(request, NO_PROGRESS);
    }

    public ProwlarrReleaseRecommendationResponse recommendMovie(
            MovieReleaseRecommendationRequest request, Consumer<ReleaseSearchProgress> progress) {
        return recommendation(movieTarget(request), query -> client.searchMagnets(new PanSouSearchCommand(query, false)), progress);
    }

    public ProwlarrReleaseRecommendationResponse refreshRecommendMovie(MovieReleaseRecommendationRequest request) {
        return recommendation(movieTarget(request), query -> client.searchMagnets(new PanSouSearchCommand(query, true)), NO_PROGRESS);
    }

    public ProwlarrReleaseSearchResponse searchSeries(SeriesReleaseSearchRequest request) {
        return searchResponse(seriesTarget(request), query -> client.searchMagnets(new PanSouSearchCommand(query, false)));
    }

    public ProwlarrReleaseSearchResponse refreshSeries(SeriesReleaseSearchRequest request) {
        return searchResponse(seriesTarget(request), query -> client.searchMagnets(new PanSouSearchCommand(query, true)));
    }

    public ProwlarrReleaseRecommendationResponse recommendSeries(SeriesReleaseRecommendationRequest request) {
        return recommendSeries(request, NO_PROGRESS);
    }

    public ProwlarrReleaseRecommendationResponse recommendSeries(
            SeriesReleaseRecommendationRequest request, Consumer<ReleaseSearchProgress> progress) {
        return recommendation(seriesTarget(request), query -> client.searchMagnets(new PanSouSearchCommand(query, false)), progress);
    }

    public ProwlarrReleaseRecommendationResponse refreshRecommendSeries(SeriesReleaseRecommendationRequest request) {
        return recommendation(seriesTarget(request), query -> client.searchMagnets(new PanSouSearchCommand(query, true)), NO_PROGRESS);
    }

    private ProwlarrReleaseSearchResponse searchResponse(Target target, Function<String, PanSouSearchResult> search) {
        List<Candidate> candidates = discover(target, search, NO_PROGRESS);
        return new ProwlarrReleaseSearchResponse(String.join(" / ", target.titles()),
                candidates.stream().map(candidate -> toItem(target, candidate)).toList());
    }

    private ProwlarrReleaseRecommendationResponse recommendation(
            Target target, Function<String, PanSouSearchResult> search, Consumer<ReleaseSearchProgress> progress) {
        List<Candidate> candidates = discover(target, search, progress);
        List<Candidate> qualified = candidates.stream().filter(candidate -> qualified(target, candidate)).toList();
        if (qualified.isEmpty()) throw rejection(target, candidates);
        String selectedQuality = target.quality();
        if (qualified.stream().noneMatch(candidate -> candidate.tags().resolutionTags().contains(target.quality()))) {
            selectedQuality = "1080p";
        }
        String selected = selectedQuality;
        List<ProwlarrReleaseItemResponse> items = qualified.stream()
                .filter(candidate -> candidate.tags().resolutionTags().contains(selected))
                .map(candidate -> toItem(target, candidate)).toList();
        return new ProwlarrReleaseRecommendationResponse(items.get(0).matchQuery(), target.quality(), selected,
                items.get(0), items);
    }

    /** 两个目录标题同时首查；任一批已有合格候选即返回，不等待无关查询或发起补搜。 */
    private List<Candidate> discover(
            Target target, Function<String, PanSouSearchResult> search, Consumer<ReleaseSearchProgress> progress) {
        Map<PanSouMagnetIdentity, Candidate> candidates = new LinkedHashMap<>();
        List<PanSouClientException> failures = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        ExecutorCompletionService<QueryBatch> completion = new ExecutorCompletionService<>(executor);
        List<Future<QueryBatch>> pending = new ArrayList<>();
        try {
            for (String title : target.titles()) {
                progress.accept(new ReleaseSearchProgress(title, "PanSou 目录标题", "RUNNING", 0, null));
                pending.add(completion.submit(() -> queryBatch(title, search)));
            }
            for (int remaining = pending.size(); remaining > 0; remaining--) {
                QueryBatch batch = completion.take().get();
                acceptBatch(target, candidates, failures, batch, progress);
                if (candidates.values().stream().anyMatch(candidate -> qualified(target, candidate))) {
                    Future<QueryBatch> completed;
                    while ((completed = completion.poll()) != null) {
                        acceptBatch(target, candidates, failures, completed.get(), progress);
                    }
                    return sorted(target, candidates);
                }
            }
            for (String query : supplements(target)) {
                progress.accept(new ReleaseSearchProgress(query, "PanSou 补搜", "RUNNING", 0, null));
                acceptBatch(target, candidates, failures, queryBatch(query, search), progress);
                if (candidates.values().stream().anyMatch(candidate -> qualified(target, candidate))) {
                    return sorted(target, candidates);
                }
            }
            // 尚有失败的来源时，证据不足不能被宣称为确定无匹配。
            if (!failures.isEmpty()) throw failures.get(0);
            return sorted(target, candidates);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PanSouClientException(PanSouClientException.Reason.UPSTREAM, "PanSou 搜索被中断", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof RuntimeException runtime) throw runtime;
            throw new PanSouClientException(PanSouClientException.Reason.UPSTREAM, "PanSou 搜索失败", exception.getCause());
        } finally {
            pending.forEach(future -> future.cancel(true));
            executor.shutdownNow();
        }
    }

    private QueryBatch queryBatch(String query, Function<String, PanSouSearchResult> search) {
        long started = System.nanoTime();
        try {
            return new QueryBatch(query, search.apply(query).entries(), null, (System.nanoTime() - started) / 1_000_000);
        } catch (PanSouClientException exception) {
            return new QueryBatch(query, List.of(), exception, (System.nanoTime() - started) / 1_000_000);
        }
    }

    private void acceptBatch(Target target, Map<PanSouMagnetIdentity, Candidate> candidates,
            List<PanSouClientException> failures, QueryBatch batch, Consumer<ReleaseSearchProgress> progress) {
        progress.accept(new ReleaseSearchProgress(batch.query(), "PanSou", batch.failure() == null ? "SUCCEEDED" : "FAILED",
                batch.elapsedMs(), batch.failure() == null ? batch.entries().size() : null));
        if (batch.failure() != null) {
            failures.add(batch.failure());
            return;
        }
        for (PanSouSearchEntry entry : batch.entries()) {
            PanSouMagnetIdentity identity = PanSouMagnetIdentity.fromUrl(entry.url()).orElseThrow(() ->
                    new PanSouClientException(PanSouClientException.Reason.INVALID_RESPONSE, "PanSou 返回无效 BTIH"));
            Candidate candidate = candidate(entry, batch.query());
            candidates.merge(identity, candidate, (first, next) -> merge(target, first, next));
        }
    }

    private Candidate candidate(PanSouSearchEntry entry, String query) {
        String title = entry.note() == null ? "" : Normalizer.normalize(entry.note(), Normalizer.Form.NFKC);
        LinkedHashSet<String> seasons = new LinkedHashSet<>();
        Matcher seasonMatcher = EXPLICIT_SEASON.matcher(title);
        while (seasonMatcher.find()) seasons.addAll(tagParser.parse(seasonMatcher.group()).seasonTags());
        ReleaseTitleTags parsed = tagParser.parse(title);
        boolean range = false;
        String episodeEvidence = DATE.matcher(title).replaceAll(" ");
        Matcher ranges = EPISODE_RANGE.matcher(episodeEvidence);
        while (ranges.find()) {
            int start = Integer.parseInt(ranges.group(1));
            int end = Integer.parseInt(ranges.group(2));
            if (start == 1 && end > start && end < 200) range = true;
        }
        boolean complete = (range || PACK.matcher(title).find())
                && !SINGLE_EPISODE.matcher(EPISODE_RANGE.matcher(episodeEvidence).replaceAll(" ")).find();
        return new Candidate(entry, new ReleaseTitleTags(parsed.resolutionTags(), parsed.dynamicRangeTags(), List.copyOf(seasons)),
                complete, List.of(query), List.of(source(entry)), List.of(title));
    }

    private Candidate merge(Target target, Candidate previous, Candidate next) {
        Candidate preferred = related(target, next) && (!related(target, previous)
                || evidenceRank(target, next) < evidenceRank(target, previous)) ? next : previous;
        return new Candidate(preferred.entry(), preferred.tags(), preferred.complete(),
                union(previous.queries(), next.queries()), union(previous.sources(), next.sources()),
                union(previous.titles(), next.titles()));
    }

    private List<String> union(List<String> first, List<String> second) {
        LinkedHashSet<String> combined = new LinkedHashSet<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    private List<Candidate> sorted(Target target, Map<PanSouMagnetIdentity, Candidate> candidates) {
        return candidates.values().stream().filter(candidate -> related(target, candidate))
                .sorted(Comparator.comparingInt((Candidate candidate) -> evidenceRank(target, candidate))
                        .thenComparingInt(candidate -> CHINESE_SUBTITLES.matcher(candidate.entry().note()).find() ? 0 : 1)
                        .thenComparingInt(candidate -> candidate.entry().note().matches(".*[\\u3400-\\u9fff].*") ? 0 : 1)
                        .thenComparingInt(candidate -> dynamicPriority(candidate.tags()))).toList();
    }

    private int evidenceRank(Target target, Candidate candidate) {
        if (qualified(target, candidate)) return candidate.tags().resolutionTags().contains(target.quality()) ? 0 : 1;
        if (scopeConfirmed(target, candidate)) return 2;
        if (candidate.complete() && (target.quality() == null || candidate.tags().resolutionTags().contains(target.quality()))) return 3;
        return 4;
    }

    private boolean qualified(Target target, Candidate candidate) {
        return related(target, candidate) && scopeConfirmed(target, candidate) && (target.quality() == null
                || candidate.tags().resolutionTags().contains(target.quality())
                || ("2160p".equals(target.quality()) && candidate.tags().resolutionTags().contains("1080p")));
    }

    private boolean scopeConfirmed(Target target, Candidate candidate) {
        if (target.season() == null) return target.year() != null && releaseYears(target, candidate.entry().note()).contains(target.year().toString());
        return candidate.tags().seasonTags().equals(List.of(seasonTag(target.season()))) && candidate.complete();
    }

    private boolean related(Target target, Candidate candidate) {
        String title = candidate.entry().note();
        if (!StringUtils.hasText(title) || NON_FEATURE.matcher(title).find()
                || target.titles().stream().noneMatch(alias -> matchesTitle(title, alias))) return false;
        if (target.year() != null) {
            List<String> years = releaseYears(target, title);
            return years.isEmpty() || years.equals(List.of(target.year().toString()));
        }
        List<String> seasons = candidate.tags().seasonTags();
        return target.season() == null || seasons.isEmpty() || seasons.contains(seasonTag(target.season()));
    }

    private boolean matchesTitle(String release, String alias) {
        String normalizedAlias = comparable(alias);
        if (normalizedAlias.isBlank()) return false;
        String normalizedRelease = comparable(release);
        // 数字、感叹号及小数点保留语义，避免将续作或不同版本当同一作品。
        Matcher match = Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(normalizedAlias) + "(?![\\p{L}\\p{N}!])")
                .matcher(normalizedRelease);
        while (match.find()) {
            String suffix = normalizedRelease.substring(match.end()).stripLeading();
            boolean startsWithEpisodeRange = EPISODE_RANGE.matcher(release).results()
                    .anyMatch(range -> suffix.startsWith(comparable(range.group())));
            if ((!suffix.matches("^[0-9]{1,3}(?:\\s|$).*") || startsWithEpisodeRange)
                    && !suffix.startsWith("续集")) return true;
        }
        return false;
    }

    private String comparable(String title) {
        return Normalizer.normalize(title, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("(?<=[a-z0-9])×(?=[a-z0-9])", " x ")
                .replaceAll("(?<![0-9])\\.|\\.(?![0-9])", " ")
                .replaceAll("[^\\p{L}\\p{N}!.+#]+", " ").replaceAll("\\s+", " ").trim();
    }

    private List<String> releaseYears(Target target, String title) {
        String evidence = comparable(title);
        for (String alias : target.titles()) evidence = evidence.replace(comparable(alias), " ");
        return years(evidence);
    }

    private List<String> years(String title) {
        LinkedHashSet<String> years = new LinkedHashSet<>();
        Matcher matcher = YEAR.matcher(title == null ? "" : title);
        while (matcher.find()) years.add(matcher.group());
        return List.copyOf(years);
    }

    private ProwlarrReleaseItemResponse toItem(Target target, Candidate candidate) {
        String uncertainty = target.season() == null && !scopeConfirmed(target, candidate) ? "；年份/版本待核对"
                : target.season() != null && candidate.tags().seasonTags().isEmpty() ? "；季度未知，需人工核对"
                : target.season() != null && !scopeConfirmed(target, candidate) ? "；整季/混季证据不足，需人工核对" : "";
        return new ProwlarrReleaseItemResponse(candidate.entry().note(), null, null, null, null,
                String.join(" / ", candidate.sources()), candidate.entry().datetime(), null, candidate.entry().url(),
                candidate.tags().resolutionTags(), candidate.tags().dynamicRangeTags(), candidate.tags().seasonTags(),
                "PanSou：" + String.join(" / ", candidate.sources()) + uncertainty
                        + (candidate.complete() ? "；标题含整包线索" : "；未确认整季，可能为单集")
                        + (CHINESE_SUBTITLES.matcher(candidate.entry().note()).find()
                                ? "；标题标注中文字幕，内容未经核验" : "；字幕语言未标明")
                        + "；做种、体积及可下载性未知"
                        + (candidate.titles().size() > 1 ? "；同 hash 标题：" + String.join(" | ", candidate.titles()) : ""),
                String.join(" / ", candidate.queries()));
    }

    private NoMatchingReleaseException rejection(Target target, List<Candidate> candidates) {
        if (candidates.isEmpty()) return new NoMatchingReleaseException("NO_RELEASE", "盘搜未找到作品与版本匹配的磁力");
        if (target.season() != null && candidates.stream().noneMatch(candidate -> scopeConfirmed(target, candidate))) {
            return new NoMatchingReleaseException("SEASON_UNKNOWN", "季度或整季证据不足，请人工核对候选",
                    candidates.stream().map(candidate -> toItem(target, candidate)).toList());
        }
        if (target.year() != null && candidates.stream().noneMatch(candidate -> scopeConfirmed(target, candidate))) {
            return new NoMatchingReleaseException("VERSION_UNKNOWN", "作品年份/版本证据不足，请人工核对候选",
                    candidates.stream().map(candidate -> toItem(target, candidate)).toList());
        }
        return new NoMatchingReleaseException(candidates.stream().allMatch(candidate -> candidate.tags().resolutionTags().isEmpty())
                ? "RESOLUTION_UNKNOWN" : "RESOLUTION_NOT_FOUND", "盘搜候选没有符合要求的清晰度证据",
                candidates.stream().map(candidate -> toItem(target, candidate)).toList());
    }

    private List<String> supplements(Target target) {
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        for (String title : target.titles()) {
            String variant = title.replace('×', 'x').replaceAll("[：:;；/\\[\\]【】()（）・]", " ")
                    .replaceAll("\\s+", " ").trim();
            if (!variant.equals(title)) queries.add(variant);
            String[] parts = title.split("[：:]");
            if (parts.length > 1 && parts[parts.length - 1].trim().length() >= 4) queries.add(parts[parts.length - 1].trim());
        }
        if (target.season() != null) {
            String title = target.titles().get(target.titles().size() - 1);
            queries.add(title + " " + seasonTag(target.season()));
        } else if (target.year() != null) {
            queries.add(target.titles().get(0) + " " + target.year());
        }
        queries.removeAll(target.titles());
        return queries.stream().limit(2).toList();
    }

    private String source(PanSouSearchEntry entry) {
        return StringUtils.hasText(entry.source()) ? entry.source() : "来源未提供";
    }

    private int dynamicPriority(ReleaseTitleTags tags) {
        if (tags.dynamicRangeTags().contains("dolby_vision")) return 2;
        return tags.dynamicRangeTags().stream().anyMatch(tag -> !"sdr".equals(tag)) ? 1 : 0;
    }

    private String seasonTag(int season) {
        return String.format(Locale.ROOT, "S%02d", season);
    }

    private Target movieTarget(MovieReleaseSearchRequest request) {
        return new Target(titles(request.title(), request.originalTitle()), request.year(), null, quality(request.quality()));
    }

    private Target movieTarget(MovieReleaseRecommendationRequest request) {
        return new Target(titles(request.title(), request.originalTitle()), request.year(), null, quality(request.quality()));
    }

    private Target seriesTarget(SeriesReleaseSearchRequest request) {
        return new Target(titles(request.title(), request.originalTitle()), null, request.seasonNumber(), quality(request.quality()));
    }

    private Target seriesTarget(SeriesReleaseRecommendationRequest request) {
        return new Target(titles(request.title(), request.originalTitle()), null, request.seasonNumber(), quality(request.quality()));
    }

    private List<String> titles(String title, String originalTitle) {
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        titles.add(title.trim());
        if (StringUtils.hasText(originalTitle)) titles.add(originalTitle.trim());
        return List.copyOf(titles);
    }

    private String quality(String quality) {
        if (!StringUtils.hasText(quality)) return null;
        String normalized = quality.trim().toLowerCase(Locale.ROOT);
        if (!List.of("2160p", "1080p", "720p").contains(normalized)) throw new IllegalArgumentException("不支持的分辨率标签");
        return normalized;
    }

    private record Target(List<String> titles, Integer year, Integer season, String quality) {}
    private record Candidate(PanSouSearchEntry entry, ReleaseTitleTags tags, boolean complete,
            List<String> queries, List<String> sources, List<String> titles) {}
    private record QueryBatch(String query, List<PanSouSearchEntry> entries, PanSouClientException failure, long elapsedMs) {}
}
