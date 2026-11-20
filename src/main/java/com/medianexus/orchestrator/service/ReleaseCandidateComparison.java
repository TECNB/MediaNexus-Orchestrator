package com.medianexus.orchestrator.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.medianexus.orchestrator.dto.resources.request.*;
import com.medianexus.orchestrator.dto.resources.response.*;
import com.medianexus.orchestrator.integration.guangya.*;
import com.medianexus.orchestrator.integration.pansou.*;
import com.medianexus.orchestrator.service.ProwlarrReleaseIngestService.NoMatchingReleaseException;
import com.medianexus.orchestrator.service.ProwlarrReleaseIngestService.ReleaseSearchProgress;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 发现、补证和全队列比较的同一快照；标题启发式只用于调度，不是文件表准入或整包硬门槛。 */
@Service
public class ReleaseCandidateComparison implements AutoCloseable {
    private static final Consumer<ReleaseSearchProgress> NO_PROGRESS = progress -> {};
    private static final Pattern SUBTITLES = Pattern.compile("(?i)简繁|簡繁|简体|簡體|繁体|繁體|中文字幕|中字|(?<![a-z])(?:chs|cht|sc|tc)(?![a-z])");
    private static final Pattern PACK = Pattern.compile("(?i)全集|合集|完结|完結|complete|batch|01\\s*[-~]\\s*\\d+");
    private final ReleaseCandidateDiscovery discovery;
    private final ReleaseTitleTagParser tags;
    private final ReleaseFileTables fileTables;
    private final ReleaseFileAssessment assessment;
    private final AuthService auth;
    private ReleaseSubmissionSafety submissionSafety;

    @org.springframework.beans.factory.annotation.Autowired
    public void shareSubmissionEvidence(ReleaseSubmissionSafety submissionSafety) { this.submissionSafety = submissionSafety; }
    private final Cache<String, Comparison> comparisons = CacheBuilder.newBuilder().maximumSize(200).expireAfterAccess(1, TimeUnit.HOURS).build();
    private final ExecutorService rounds = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32), runnable -> {
                Thread thread = new Thread(runnable, "release-comparison"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public ReleaseCandidateComparison(ReleaseCandidateDiscovery discovery, ReleaseTitleTagParser tags,
            ReleaseFileTables fileTables, ReleaseFileAssessment assessment, AuthService auth) {
        this.discovery = discovery; this.tags = tags; this.fileTables = fileTables; this.assessment = assessment; this.auth = auth;
    }

    public ProwlarrReleaseSearchResponse searchMovie(MovieReleaseSearchRequest request) { return start(movie(request), false).view(); }
    public ProwlarrReleaseSearchResponse refreshMovie(MovieReleaseSearchRequest request) { return start(movie(request), true).view(); }
    public ProwlarrReleaseSearchResponse searchSeries(SeriesReleaseSearchRequest request) { return start(series(request), false).view(); }
    public ProwlarrReleaseSearchResponse refreshSeries(SeriesReleaseSearchRequest request) { return start(series(request), true).view(); }
    public ProwlarrReleaseRecommendationResponse recommendMovie(MovieReleaseRecommendationRequest request) { return recommendMovie(request, NO_PROGRESS); }
    public ProwlarrReleaseRecommendationResponse recommendMovie(MovieReleaseRecommendationRequest request, Consumer<ReleaseSearchProgress> progress) {
        return recommend(ReleaseTarget.of(request.title(), request.originalTitle(), null, request.year(), null, request.quality()), false, progress);
    }
    ProwlarrReleaseRecommendationResponse recommendMovie(MovieReleaseRecommendationRequest request, String alias, Consumer<ReleaseSearchProgress> progress) {
        return recommend(ReleaseTarget.of(request.title(), request.originalTitle(), alias, request.year(), null, request.quality()), false, progress);
    }
    public ProwlarrReleaseRecommendationResponse refreshRecommendMovie(MovieReleaseRecommendationRequest request) {
        return previewFresh(ReleaseTarget.of(request.title(), request.originalTitle(), null, request.year(), null, request.quality()));
    }
    public ProwlarrReleaseRecommendationResponse recommendSeries(SeriesReleaseRecommendationRequest request) { return recommendSeries(request, NO_PROGRESS); }
    public ProwlarrReleaseRecommendationResponse recommendSeries(SeriesReleaseRecommendationRequest request, Consumer<ReleaseSearchProgress> progress) {
        return recommend(ReleaseTarget.of(request.title(), request.originalTitle(), null, null, request.seasonNumber(), request.quality()), false, progress);
    }
    ProwlarrReleaseRecommendationResponse recommendSeries(SeriesReleaseRecommendationRequest request, String alias, Consumer<ReleaseSearchProgress> progress) {
        return recommend(ReleaseTarget.of(request.title(), request.originalTitle(), alias, null, request.seasonNumber(), request.quality()), false, progress);
    }
    public ProwlarrReleaseRecommendationResponse refreshRecommendSeries(SeriesReleaseRecommendationRequest request) {
        return previewFresh(ReleaseTarget.of(request.title(), request.originalTitle(), null, null, request.seasonNumber(), request.quality()));
    }
    public ProwlarrReleaseRecommendationResponse previewMovie(MovieReleaseRecommendationRequest request) {
        return preview(ReleaseTarget.of(request.title(), request.originalTitle(), null, request.year(), null, request.quality()));
    }
    public ProwlarrReleaseRecommendationResponse previewSeries(SeriesReleaseRecommendationRequest request) {
        return preview(ReleaseTarget.of(request.title(), request.originalTitle(), null, null, request.seasonNumber(), request.quality()));
    }
    private ProwlarrReleaseRecommendationResponse preview(ReleaseTarget target) {
        return previewRound(new Comparison(target, false, auth.requireCurrentUser().getId()));
    }
    private ProwlarrReleaseRecommendationResponse previewFresh(ReleaseTarget target) {
        return previewRound(new Comparison(target, true, auth.requireCurrentUser().getId()));
    }
    private ProwlarrReleaseRecommendationResponse previewRound(Comparison comparison) {
        ReleaseTarget target = comparison.target;
        comparisons.put(comparison.id, comparison);
        runRound(comparison);
        var view = comparison.view();
        var selected = view.items().stream().filter(this::recommendable).findFirst().orElse(null);
        return new ProwlarrReleaseRecommendationResponse(view.query(), target.quality(), selected == null ? null
                : selected.resolutionTags().stream().filter(target.qualities()::contains).findFirst().orElse(null), selected,
                view.items(), view.comparison());
    }
    ProwlarrReleaseRecommendationResponse recommendPreferredMovie(MovieReleaseRecommendationRequest request, Consumer<ReleaseSearchProgress> progress) { return recommendMovie(request, progress); }
    ProwlarrReleaseRecommendationResponse recommendPreferredSeries(SeriesReleaseRecommendationRequest request, Consumer<ReleaseSearchProgress> progress) { return recommendSeries(request, progress); }

    public ProwlarrReleaseSearchResponse comparison(String id) { return owned(id).view(); }
    public ProwlarrReleaseSearchResponse continueComparison(String id) {
        Comparison comparison = owned(id);
        synchronized (comparison) {
            if (!"RUNNING".equals(comparison.status) && !"COMPLETE".equals(comparison.status)) {
                comparison.message = "正在继续比较，复用已查文件表与证据";
                comparison.status = "RUNNING";
                comparison.deadline = Instant.now().plusSeconds(45);
                comparison.queries.removeAll(comparison.failedQueries);
                comparison.searchFailed = false;
                enqueue(comparison);
            }
        }
        return comparison.view();
    }

    private Comparison owned(String id) {
        Long user = auth.requireCurrentUser().getId();
        Comparison comparison = comparisons.getIfPresent(id);
        if (comparison == null || !Objects.equals(user, comparison.owner)) throw new NoMatchingReleaseException("COMPARISON_EXPIRED", "比较快照不存在或已过期，请重新搜索");
        return comparison;
    }

    private Comparison start(ReleaseTarget target, boolean refresh) {
        Comparison comparison = new Comparison(target, refresh, auth.requireCurrentUser().getId());
        comparisons.put(comparison.id, comparison);
        enqueue(comparison);
        return comparison;
    }

    private void enqueue(Comparison comparison) {
        try { comparison.round = rounds.submit(() -> runRound(comparison)); }
        catch (RejectedExecutionException exception) { pause(comparison, "比较队列繁忙；请稍后明确继续，尚未执行新解析"); }
    }

    private ProwlarrReleaseRecommendationResponse recommend(ReleaseTarget target, boolean refresh, Consumer<ReleaseSearchProgress> progress) {
        Comparison comparison = new Comparison(target, refresh, null);
        comparisons.put(comparison.id, comparison);
        comparison.progress = progress;
        runRound(comparison);
        var view = comparison.view();
        List<ProwlarrReleaseItemResponse> usable = view.items().stream().filter(this::automaticallySelectable).toList();
        if (!"COMPLETE".equals(view.comparison().status())) {
            throw new NoMatchingReleaseException("EVIDENCE_BUDGET_EXHAUSTED",
                    view.comparison().message() + "；比较快照=" + comparison.id + "，已查=" + view.comparison().checked() + "，待查=" + view.comparison().pending(), view.items());
        }
        if (usable.isEmpty()) throw new NoMatchingReleaseException(view.items().isEmpty() ? "NO_RELEASE" : "EVIDENCE_UNVERIFIED",
                view.items().isEmpty() ? "本轮搜索未发现相关磁力" : "本轮已评估候选没有可安全自动提交项；可人工查看证据", view.items());
        var selected = usable.get(0);
        return new ProwlarrReleaseRecommendationResponse(view.query(), target.quality(), selected.resolutionTags().stream()
                .filter(target.qualities()::contains).findFirst().orElse(null), selected, usable, view.comparison());
    }

    private void runRound(Comparison comparison) {
        Instant deadline = comparison.deadline;
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<?>> futures = new ArrayList<>();
        ResolveBudget budget = null;
        try {
            remaining(deadline);
            synchronized (comparison) { comparison.resolveRequests = 0; }
            LinkedHashSet<String> initial = new LinkedHashSet<>(comparison.failedQueries);
            comparison.failedQueries.clear();
            comparison.target.titles().stream().limit(2).filter(query -> !comparison.queries.contains(query)).forEach(initial::add);
            searchQueries(comparison, List.copyOf(initial), deadline, pool, futures);
            budget = new ResolveBudget(deadline, Instant.now().plusSeconds(30));
            while (true) {
                evaluateQueue(comparison, budget, deadline, pool, futures);
                synchronized (comparison) {
                    if (!"RUNNING".equals(comparison.status)) return;
                    if (comparison.candidates.values().stream().anyMatch(candidate -> recommendable(candidate.item))) break;
                }
                List<String> supplement = queries(comparison.target).stream().filter(query -> !comparison.queries.contains(query)).limit(4 - comparison.queries.size()).toList();
                if (supplement.isEmpty()) break;
                searchQueries(comparison, supplement, deadline, pool, futures);
            }
            if (comparison.searchFailed) {
                synchronized (comparison) { comparison.status = "BLOCKED"; comparison.message = "部分搜索词未取得结果；已查证据保留，不能声称本轮无资源或完成全比较"; }
                return;
            }
            synchronized (comparison) {
                comparison.status = "COMPLETE";
                comparison.message = "本轮已发现候选全部评估完成；不代表穷举互联网或媒体质量实测";
            }
        } catch (TimeoutException exception) {
            pause(comparison, "总流程或解析时间预算用尽；保留当前推荐，请明确继续比较");
        } catch (GuangyaException exception) {
            if (exception.reason() == GuangyaException.Reason.BUDGET_EXHAUSTED) pause(comparison, exception.getMessage());
            else synchronized (comparison) { comparison.status = "BLOCKED"; comparison.message = exception.getMessage(); }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); pause(comparison, "比较已中断，已查证据保留");
        } catch (ExecutionException exception) {
            synchronized (comparison) { comparison.status = "BLOCKED"; comparison.message = "搜索或解析暂不可用，已查证据保留"; }
        } catch (RuntimeException exception) {
            synchronized (comparison) { comparison.status = "BLOCKED"; comparison.message = "比较暂不可用，请检查上游或认证配置；已查证据保留"; }
        } finally {
            futures.forEach(future -> future.cancel(true)); pool.shutdownNow();
            synchronized (comparison) { if (budget != null) comparison.resolveRequests = budget.resolveRequests(); }
        }
    }

    private void searchQueries(Comparison comparison, List<String> queries, Instant deadline, ExecutorService pool, List<Future<?>> futures)
            throws InterruptedException, ExecutionException, TimeoutException {
        CompletionService<SearchBatch> completion = new ExecutorCompletionService<>(pool);
        int count = 0;
        Map<Future<SearchBatch>, String> queryFutures = new HashMap<>();
        for (String query : queries) {
            synchronized (comparison) {
                if (comparison.queries.size() >= 4 || !comparison.queries.add(query)) continue;
            }
            comparison.progress.accept(new ReleaseSearchProgress(query, "资源发现", "RUNNING", 0, null));
            Future<SearchBatch> submitted = completion.submit(() -> new SearchBatch(query,
                    comparison.refresh ? discovery.refresh(query) : discovery.search(query)));
            futures.add(submitted);
            queryFutures.put(submitted, query);
            count++;
        }
        for (int i = 0; i < count; i++) {
            Future<SearchBatch> future = completion.poll(remaining(deadline), TimeUnit.NANOSECONDS);
            if (future == null) throw new TimeoutException();
            SearchBatch batch;
            try { batch = future.get(); }
            catch (ExecutionException exception) {
                synchronized (comparison) { comparison.searchFailed = true; comparison.failedQueries.add(queryFutures.get(future)); }
                continue;
            }
            synchronized (comparison) {
                for (var entry : batch.entries) {
                    String identity = entry.identity();
                    comparison.discovered.computeIfAbsent(identity, ignored -> new ArrayList<>()).add(entry);
                    if (!comparison.target.possiblyRelated(entry.item().title()) && !comparison.candidates.containsKey(identity)) continue;
                    var candidate = comparison.candidates.get(identity);
                    if (candidate == null) {
                        candidate = new Discovered(entry, batch.query);
                        comparison.candidates.put(identity, candidate);
                        for (var previous : comparison.discovered.get(identity)) candidate.merge(previous, batch.query);
                    } else candidate.merge(entry, batch.query);
                    rememberTitles(candidate);
                    for (String title : candidate.titles) {
                        String conflict = comparison.target.conflict(title);
                        if (conflict != null) candidate.item = candidate.item.withEvidence(new ReleaseFileEvidence("UNSUITABLE", conflict, null, null, null, false, false), candidate.item.resolutionTags());
                    }
                }
            }
            comparison.progress.accept(new ReleaseSearchProgress(batch.query, "资源发现", "SUCCEEDED", 0, batch.entries.size()));
        }
    }

    private void evaluateQueue(Comparison comparison, ResolveBudget budget, Instant deadline, ExecutorService pool, List<Future<?>> futures)
            throws InterruptedException, ExecutionException, TimeoutException {
        List<Discovered> queue;
        synchronized (comparison) {
            queue = schedule(comparison);
        }
        for (int i = 0; i < queue.size(); i += 2) {
            budget.remaining();
            List<Future<Checked>> batch = new ArrayList<>();
            for (Discovered candidate : queue.subList(i, Math.min(i + 2, queue.size()))) {
                Future<Checked> future = pool.submit(() -> evaluate(comparison.target, candidate, budget));
                batch.add(future); futures.add(future);
            }
            for (Future<Checked> future : batch) {
                Checked checked = future.get(Math.min(remaining(deadline), budget.remaining().toNanos()), TimeUnit.NANOSECONDS);
                synchronized (comparison) {
                    checked.candidate.item = checked.item;
                    comparison.resolveRequests = budget.resolveRequests();
                    if (checked.failure == null) comparison.checkedFailures.add(checked.candidate);
                    if (checked.failure != null) {
                        switch (checked.failure.reason()) {
                            case BUDGET_EXHAUSTED -> { pause(comparison, checked.failure.getMessage()); return; }
                            case AUTHENTICATION, ACCOUNT_BLOCKED, NOT_CONFIGURED, PERSISTENCE_FAILED -> {
                                comparison.status = "BLOCKED"; comparison.message = checked.failure.getMessage(); return;
                            }
                            case INVALID_RESPONSE -> { comparison.checkedFailures.add(checked.candidate); }
                            default -> comparison.checkedFailures.add(checked.candidate);
                        }
                    }
                }
            }
            synchronized (comparison) {
                for (var candidate : List.copyOf(comparison.candidates.values()))
                    if (candidate.magnet != null && comparison.candidates.containsValue(candidate)) mergeResolvedHash(comparison, candidate);
            }
        }
    }

    private Checked evaluate(ReleaseTarget target, Discovered candidate, ResolveBudget budget) {
        try {
            var resolved = candidate.release == null ? fileTables.resolve(candidate.item.downloadRef(), budget)
                    : fileTables.resolve(candidate.release, budget);
            candidate.magnet = "magnet:?xt=urn:btih:" + resolved.table().identity().btih();
            candidate.titles.addAll(fileTables.sourceTitles(candidate.magnet));
            rememberTitles(candidate);
            var assessment = this.assessment.assess(target, List.copyOf(candidate.titles), resolved.table());
            var evidence = assessment.evidence();
            evidence = new ReleaseFileEvidence(evidence.status(), evidence.reason() + "；证据来源：" + resolved.source(),
                    evidence.totalBytes(), evidence.targetBytes(), evidence.targetFiles(), evidence.preview(), evidence.camera());
            return new Checked(candidate, candidate.item.withEvidence(evidence, assessment.resolutions()), null);
        } catch (GuangyaException exception) {
            if (exception.reason() == GuangyaException.Reason.INVALID_RESPONSE && submissionSafety != null && candidate.magnet != null)
                submissionSafety.rememberInvalidFileTable(candidate.magnet);
            ReleaseFileEvidence evidence = exception.reason() == GuangyaException.Reason.INVALID_RESPONSE
                    ? new ReleaseFileEvidence("UNSUITABLE", "文件表安全校验失败，禁止提交；需重新核实上游响应", null, null, null, false, false)
                    : ReleaseFileEvidence.unverified(exception.getMessage());
            return new Checked(candidate, candidate.item.withEvidence(evidence, candidate.item.resolutionTags()), exception);
        }
    }

    private void rememberTitles(Discovered candidate) {
        if (submissionSafety != null && candidate.magnet != null) {
            submissionSafety.rememberSourceTitles(candidate.magnet, List.copyOf(candidate.titles));
            for (String original : fileTables.sourceMagnets(candidate.magnet))
                submissionSafety.rememberSourceTitles(original, List.copyOf(candidate.titles));
        }
    }

    private void mergeResolvedHash(Comparison comparison, Discovered candidate) {
        Discovered previous = comparison.candidates.values().stream()
                .filter(other -> other != candidate && Objects.equals(other.magnet, candidate.magnet)).findFirst().orElse(null);
        if (previous == null) return;
        previous.titles.addAll(candidate.titles);
        previous.sources.addAll(candidate.sources);
        previous.queries.addAll(candidate.queries);
        if (candidate.item.seeders() != null && (previous.item.seeders() == null || candidate.item.seeders() > previous.item.seeders())) {
            previous.item = candidate.item;
            previous.release = candidate.release;
        }
        rememberTitles(previous);
        try {
            var resolved = fileTables.cached(previous.magnet).orElse(null);
            if (resolved == null) return;
            var assessed = assessment.assess(comparison.target, List.copyOf(previous.titles), resolved.table());
            var evidence = assessed.evidence();
            evidence = new ReleaseFileEvidence(evidence.status(), evidence.reason() + "；证据来源：" + resolved.source(),
                    evidence.totalBytes(), evidence.targetBytes(), evidence.targetFiles(), evidence.preview(), evidence.camera());
            previous.item = previous.item.withEvidence(evidence, assessed.resolutions());
            comparison.checkedFailures.add(previous);
        } catch (GuangyaException failure) {
            previous.item = previous.item.withEvidence(new ReleaseFileEvidence("UNSUITABLE", failure.getMessage(),
                    null, null, null, false, false), previous.item.resolutionTags());
        }
        comparison.candidates.values().removeIf(item -> item == candidate);
    }

    private boolean automaticallySelectable(ProwlarrReleaseItemResponse item) {
        return recommendable(item) && (item.indexerId() == null || item.seeders() != null && item.seeders() > 0);
    }
    /** 三层轮转，标题合格项不能消耗全部解析名额；各层内部保留原发现顺序。 */
    private List<Discovered> schedule(Comparison comparison) {
        List<List<Discovered>> layers = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (var candidate : comparison.candidates.values()) {
            if (!"UNVERIFIED".equals(candidate.item.evidence().status()) || comparison.checkedFailures.contains(candidate)) continue;
            int layer = tags.parse(candidate.item.title()).seasonTags().size() > 1 ? 2
                    : PACK.matcher(candidate.item.title()).find() && !candidate.item.resolutionTags().isEmpty() ? 0 : 1;
            layers.get(layer).add(candidate);
        }
        List<Discovered> queue = new ArrayList<>();
        for (int i = 0; layers.stream().anyMatch(layer -> !layer.isEmpty()); i++) {
            var layer = layers.get(i % 3);
            if (!layer.isEmpty()) queue.add(layer.remove(0));
        }
        return queue;
    }

    private long remaining(Instant deadline) throws TimeoutException {
        long nanos = Duration.between(Instant.now(), deadline).toNanos();
        if (nanos <= 0) throw new TimeoutException();
        return nanos;
    }
    private void pause(Comparison comparison, String message) { synchronized (comparison) { comparison.status = "PAUSED"; comparison.message = message; } }
    private boolean recommendable(ProwlarrReleaseItemResponse item) { return item.evidence() != null && "RECOMMENDABLE".equals(item.evidence().status()); }
    private List<String> queries(ReleaseTarget target) {
        LinkedHashSet<String> queries = new LinkedHashSet<>(target.titles());
        for (String title : target.titles()) {
            String variant = title.replace('×', 'x').replaceAll("[：:;；/\\[\\]【】()（）・]", " ").replaceAll("\\s+", " ").trim();
            queries.add(variant);
        }
        queries.add(target.titles().get(0) + (target.season() == null ? " " + target.year() : " S%02d".formatted(target.season())));
        return queries.stream().limit(4).toList();
    }
    private ReleaseTarget movie(MovieReleaseSearchRequest request) { return ReleaseTarget.of(request.title(), request.originalTitle(), request.bangumiTitle(), request.year(), null, request.quality()); }
    private ReleaseTarget series(SeriesReleaseSearchRequest request) { return ReleaseTarget.of(request.title(), request.originalTitle(), request.bangumiTitle(), null, request.seasonNumber(), request.quality()); }

    @PreDestroy @Override public void close() { rounds.shutdownNow(); }

    private final class Comparison {
        final String id = UUID.randomUUID().toString();
        final String snapshotAt = Instant.now().toString();
        final ReleaseTarget target;
        final boolean refresh;
        final Long owner;
        final Set<String> queries = new LinkedHashSet<>();
        final Set<String> failedQueries = new LinkedHashSet<>();
        final Map<String, Discovered> candidates = new LinkedHashMap<>();
        Instant deadline = Instant.now().plusSeconds(45);
        final Map<String, List<ReleaseCandidateDiscovery.Found>> discovered = new LinkedHashMap<>();
        boolean searchFailed;
        final Set<Discovered> checkedFailures = new HashSet<>();
        String status = "RUNNING";
        String message = "正在发现候选并按文件表渐进比较";
        int resolveRequests;
        Future<?> round;
        Consumer<ReleaseSearchProgress> progress = NO_PROGRESS;
        Comparison(ReleaseTarget target, boolean refresh, Long owner) { this.target = target; this.refresh = refresh; this.owner = owner; }
        synchronized ProwlarrReleaseSearchResponse view() {
            List<ProwlarrReleaseItemResponse> items = candidates.values().stream().map(candidate -> candidate.item).sorted(
                    Comparator.comparingInt((ProwlarrReleaseItemResponse item) -> switch (item.evidence().status()) {
                        case "RECOMMENDABLE" -> 0; case "NEEDS_PROCESSING" -> 1; case "UNVERIFIED" -> 2; default -> 3;
                    }).thenComparingInt(item -> item.evidence().preview() ? 1 : 0)
                    .thenComparingInt(item -> item.seeders() != null && item.seeders() <= 0 ? 1 : 0)
                    .thenComparingInt(item -> item.resolutionTags().stream().filter(target.qualities()::contains).mapToInt(target.qualities()::indexOf).min().orElse(99))
                    .thenComparingInt(item -> SUBTITLES.matcher(item.title()).find() ? 0 : 1)
                    .thenComparingInt(item -> item.title().matches(".*[\\u3400-\\u9fff].*") ? 0 : 1)
                    .thenComparingInt(item -> item.dynamicRangeTags().contains("dolby_vision") ? 2 : item.dynamicRangeTags().stream().anyMatch(tag -> !tag.equals("sdr")) ? 1 : 0)
                    .thenComparing(item -> item.evidence().targetBytes(), Comparator.nullsLast(Comparator.reverseOrder()))).toList();
            int pending = (int) candidates.values().stream().filter(candidate -> "UNVERIFIED".equals(candidate.item.evidence().status()) && !checkedFailures.contains(candidate)).count();
            String ref = items.stream().filter(ReleaseCandidateComparison.this::recommendable).map(ProwlarrReleaseItemResponse::downloadRef).findFirst().orElse(null);
            return new ProwlarrReleaseSearchResponse(String.join(" / ", target.titles()), items, new ReleaseComparisonProgress(id, status,
                    snapshotAt, candidates.size() - pending, pending, resolveRequests, queries.size(), message, ref));
        }
    }
    private static final class Discovered {
        final Set<String> titles = new LinkedHashSet<>();
        final Set<String> sources = new LinkedHashSet<>();
        final Set<String> queries = new LinkedHashSet<>();
        com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease release;
        String magnet;
        ProwlarrReleaseItemResponse item;
        boolean sizeConflict;
        Discovered(ReleaseCandidateDiscovery.Found entry, String query) {
            item = entry.item(); release = entry.release();
            magnet = release == null ? item.downloadRef() : ReleaseFileTables.magnet(release).orElse(null);
            titles.add(item.title()); sources.add(item.indexer() == null ? "来源未提供" : item.indexer()); queries.add(query);
            sizeConflict = entry.sizeConflict();
        }
        void merge(ReleaseCandidateDiscovery.Found entry, String query) {
            var incoming = entry.item();
            titles.add(incoming.title()); sources.add(incoming.indexer() == null ? "来源未提供" : incoming.indexer()); queries.add(query);
            sizeConflict |= entry.sizeConflict() || item.size() != null && incoming.size() != null && !item.size().equals(incoming.size());
            Long size = sizeConflict ? null : item.size() == null ? incoming.size() : item.size();
            if (incoming.seeders() != null && (item.seeders() == null || incoming.seeders() > item.seeders())) {
                item = incoming;
                release = entry.release();
            }
            item = new ProwlarrReleaseItemResponse(item.title(), size, item.seeders(), item.leechers(), item.grabs(),
                    String.join(" / ", sources), item.publishDate(), item.indexerId(), item.downloadRef(), item.resolutionTags(),
                    item.dynamicRangeTags(), item.seasonTags(), "来源标题：" + String.join(" | ", titles)
                    + (sizeConflict ? "；来源体积冲突" : ""), String.join(" / ", queries), item.reviewFlags(), item.evidence());
        }
    }
    private record SearchBatch(String query, List<ReleaseCandidateDiscovery.Found> entries) {}
    private record Checked(Discovered candidate, ProwlarrReleaseItemResponse item, GuangyaException failure) {}
}
