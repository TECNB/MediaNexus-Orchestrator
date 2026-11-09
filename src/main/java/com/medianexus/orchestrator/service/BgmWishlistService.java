package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.config.BangumiProperties;
import com.medianexus.orchestrator.integration.bangumi.BgmTmdbMappings;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaDeletionTaskResponse;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.CatalogCandidate;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Identity;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.IdentityInput;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Item;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Provider;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Reason;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Season;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Source;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.SourceInput;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.TaskLink;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Wishlist;
import com.medianexus.orchestrator.integration.bangumi.BangumiClient;
import com.medianexus.orchestrator.integration.bangumi.BangumiClient.BangumiReadException;
import com.medianexus.orchestrator.integration.douban.DoubanWishlistClient;
import com.medianexus.orchestrator.integration.douban.DoubanWishlistClient.WishlistReadException;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClientException;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.CollectedSubject;
import com.medianexus.orchestrator.service.BgmWishlistSourceStore.SourceState;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * 想看清单与资格的应用操作。手动和调度复用 evaluate；此处不推荐发布、不扣额、不创建任务。
 * 未知状态仅收紧 BGM 入口，不改变原入库入口的在库检查兜底。
 */
@Service
public class BgmWishlistService {
    private static final Logger log = LoggerFactory.getLogger(BgmWishlistService.class);
    private final BgmTmdbMappings mappings;
    private final ExecutorService catalogChecks;
    private final AuthService auth;
    private final BgmWishlistSourceStore store;
    private final BangumiClient bangumi;
    private final DoubanWishlistClient douban;
    private final TmdbClient tmdb;
    private final MediaLibraryPresenceService presence;
    private final BgmWishlistTaskGateway tasks;

    public BgmWishlistService(AuthService auth, BgmWishlistSourceStore store, BangumiClient bangumi,
                               TmdbClient tmdb, MediaLibraryPresenceService presence, BgmWishlistTaskGateway tasks,
                               BgmTmdbMappings mappings, BangumiProperties properties, DoubanWishlistClient douban) {
        this.mappings = mappings;
        this.catalogChecks = Executors.newFixedThreadPool(properties.getCatalogConcurrency(), runnable -> {
            Thread thread = new Thread(runnable, "bgm-catalog-check");
            thread.setDaemon(true);
            return thread;
        });
        this.auth = auth;
        this.store = store;
        this.bangumi = bangumi;
        this.douban = douban;
        this.tmdb = tmdb;
        this.presence = presence;
        this.tasks = tasks;
    }

    public List<Source> listSources(Provider provider) {
        auth.requireAdminUser();
        return store.list().stream().map(SourceState::source).filter(source -> source.provider() == provider).toList();
    }

    public Source createSource(SourceInput request) {
        auth.requireAdminUser();
        SourceInput input = normalizeSource(request);
        if (!Boolean.TRUE.equals(input.enabled())) return store.create(input).source();
        Instant started = Instant.now();
        try {
            return store.createWithRefresh(input, readWishlist(input.provider(), input.bgmUsername()), started).source();
        } catch (BangumiReadException | WishlistReadException exception) {
            throw upstream("首次启用读取失败，未保存启用配置或建立基线");
        }
    }

    public Source updateSource(String id, SourceInput request) {
        auth.requireAdminUser();
        SourceInput input = normalizeSource(request);
        SourceState state = store.get(id);
        if (!Boolean.TRUE.equals(input.enabled()) || state.source().baselineInitialized()) {
            return store.update(id, input).source();
        }
        Instant started = Instant.now();
        try {
            return store.updateWithRefresh(id, input, readWishlist(input.provider(), input.bgmUsername()), started).source();
        } catch (BangumiReadException | WishlistReadException exception) {
            store.recordRefreshFailure(id, started);
            throw upstream("首次启用读取失败，来源保持关闭，未建立或推进基线");
        }
    }

    private SourceInput normalizeSource(SourceInput input) {
        String account = input.bgmUsername();
        if (input.provider() == Provider.DOUBAN) {
            try {
                account = DoubanWishlistClient.accountId(account);
            } catch (IllegalArgumentException exception) {
                throw bad(exception.getMessage());
            }
        } else if (!account.matches("[A-Za-z0-9_-]{1,64}")) {
            throw bad("请输入有效的 BGM 用户名或 ID");
        }
        return new SourceInput(account, input.ownerUserId(), input.enabled(), input.scheduleTime(),
                input.targetCount(), input.quality(), input.initialScope(), input.provider());
    }

    private List<CollectedSubject> readWishlist(Provider provider, String account) {
        return provider == Provider.DOUBAN ? douban.readWishlist(account) : bangumi.readWishlist(account);
    }

    public Wishlist items(String sourceId) {
        auth.requireAdminUser();
        return wishlist(matchMissing(store.get(sourceId)));
    }

    public Wishlist refresh(String sourceId) {
        auth.requireAdminUser();
        return refreshForExecution(sourceId);
    }

    /** 内部显式来源操作，不依赖浏览器登录态；自身刷新失败绝不借用其他请求的成功观察。 */
    public Wishlist refreshForExecution(String sourceId) {
        SourceState state = store.get(sourceId);
        Instant started = Instant.now();
        try {
            return wishlist(matchMissing(store.recordRefresh(sourceId, readWishlist(state.source().provider(), state.source().bgmUsername()), started)));
        } catch (BangumiReadException | WishlistReadException exception) {
            SourceState retained = store.recordRefreshFailure(sourceId, started);
            return wishlist(new SourceState(retained.source(), retained.subjects(), retained.knownSubjectIds(),
                    retained.candidateSubjectIds(), retained.identities(), retained.createdTasks(), "FAILED",
                    "本次清单刷新失败，已保留上次有效清单；本轮不能用于新提交", started, retained.lastSuccessAt()));
        }
    }

    /** 异步执行完整读取来源，但不对无关条目调用目录和在库接口。 */
    public SourceState refreshSourceForOperation(String sourceId) {
        SourceState state = store.get(sourceId);
        Instant started = Instant.now();
        try {
            return store.recordRefresh(sourceId, readWishlist(state.source().provider(), state.source().bgmUsername()), started);
        } catch (BangumiReadException | WishlistReadException exception) {
            store.recordRefreshFailure(sourceId, started);
            throw upstream("完整想看清单刷新失败，本轮不能创建任务");
        }
    }

    public Item item(String sourceId, long subjectId) {
        auth.requireAdminUser();
        return evaluateForOperation(sourceId, subjectId);
    }

    public AdminMediaDeletionTaskResponse deleteFromLibrary(String sourceId, long subjectId, IdentityInput expected) {
        auth.requireAdminUser();
        SourceState state = store.get(sourceId);
        BgmWishlistSourceStore.requireSubject(state, subjectId);
        Identity identity = state.identities().get(subjectId);
        if (identity == null || !expected.equals(new IdentityInput(identity.mediaType(), identity.tmdbId(), identity.seasonNumber()))) {
            throw new BusinessException(ErrorCode.CONFLICT, "目录目标已变化，请刷新后重试", HttpStatus.CONFLICT);
        }
        try {
            return tasks.delete(target(identity));
        } catch (EmbyClientException exception) {
            throw upstream("媒体库暂不可用，未创建删除任务");
        }
    }

    public Item evaluateForOperation(String sourceId, long subjectId) {
        SourceState state = store.get(sourceId);
        CollectedSubject subject = BgmWishlistSourceStore.requireSubject(state, subjectId);
        if (!state.identities().containsKey(subjectId)) {
            SourceState selected = new SourceState(state.source(), List.of(subject), state.knownSubjectIds(),
                    state.candidateSubjectIds(), state.identities(), state.createdTasks(), state.refreshStatus(),
                    state.refreshError(), state.lastAttemptAt(), state.lastSuccessAt());
            matchMissing(selected);
            state = store.get(sourceId);
        }
        return evaluate(state, subject);
    }

    private Wishlist wishlist(SourceState state) {
        List<CompletableFuture<Item>> checks = state.subjects().stream()
                .map(subject -> CompletableFuture.supplyAsync(() -> evaluate(state, subject), catalogChecks)).toList();
        return new Wishlist(state.source(), checks.stream().map(CompletableFuture::join).toList(),
                state.refreshStatus(), state.refreshError(), state.lastAttemptAt(), state.lastSuccessAt());
    }

    private SourceState matchMissing(SourceState state) {
        if (!"SUCCESS".equals(state.refreshStatus())) return state;
        Set<Long> missing = new HashSet<>();
        state.subjects().forEach(subject -> {
            if (!state.identities().containsKey(subject.subjectId())) missing.add(subject.subjectId());
        });
        if (missing.isEmpty()) return state;
        List<CompletableFuture<Map.Entry<Long, Identity>>> checks;
        if (state.source().provider() == Provider.DOUBAN) {
            checks = state.subjects().stream().filter(subject -> missing.contains(subject.subjectId()))
                    .map(subject -> verifyIdentity(subject.subjectId(), () -> matchDouban(subject))).toList();
        } else {
            Map<Long, IdentityInput> candidates;
            try {
                candidates = mappings.find(missing);
            } catch (BgmTmdbMappings.MappingUnavailableException exception) {
                log.warn("BGM 自动映射读取失败，保留已有目录 sourceId={}", state.source().id(), exception);
                return state;
            }
            checks = missing.stream().filter(candidates::containsKey)
                    .map(id -> verifyIdentity(id, () -> candidates.get(id))).toList();
        }
        Map<Long, Identity> verified = new HashMap<>();
        checks.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull)
                .forEach(entry -> verified.put(entry.getKey(), entry.getValue()));
        if (verified.isEmpty()) return state;
        return store.saveAutomaticIdentities(state.source().id(), verified);
    }

    private CompletableFuture<Map.Entry<Long, Identity>> verifyIdentity(
            long subjectId, java.util.function.Supplier<IdentityInput> candidate) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                IdentityInput input = candidate.get();
                return input == null ? null : Map.entry(subjectId, resolveIdentity(input));
            } catch (TmdbClientException | BusinessException exception) {
                log.warn("想看映射目录无法核实 subjectId={}，保留待纠正", subjectId);
                return null;
            }
        }, catalogChecks);
    }

    private IdentityInput matchDouban(CollectedSubject subject) {
        if (subject.year() == null) return null;
        List<CatalogCandidate> matches = new ArrayList<>();
        for (JsonNode item : tmdb.searchWishlistTitles(subject.title())) {
            var candidate = switch (item.path("media_type").asText()) {
                case "movie" -> candidate("MOVIE", item, "title", "original_title", "release_date");
                case "tv" -> candidate("SERIES", item, "name", "original_name", "first_air_date");
                default -> null;
            };
            if (candidate != null && candidate.tmdbId() > 0 && subject.year().equals(candidate.year())
                    && (sameTitle(subject.title(), candidate) || sameTitle(subject.originalTitle(), candidate))) {
                matches.add(candidate);
            }
        }
        if (matches.size() != 1) return null;
        CatalogCandidate match = matches.get(0);
        if ("MOVIE".equals(match.mediaType())) return new IdentityInput("MOVIE", match.tmdbId(), null);
        JsonNode details = tmdb.getTvDetails(match.tmdbId(), "zh-CN");
        requireCatalog(details, match.tmdbId());
        // A series match alone cannot select one of several seasons.
        List<Integer> seasons = new ArrayList<>();
        for (JsonNode season : details.path("seasons")) {
            int number = season.path("season_number").asInt();
            if (number > 0) seasons.add(number);
        }
        return seasons.size() == 1 ? new IdentityInput("SERIES", match.tmdbId(), seasons.get(0)) : null;
    }

    private static boolean sameTitle(String title, CatalogCandidate candidate) {
        return title != null && (title.equalsIgnoreCase(candidate.title()) || title.equalsIgnoreCase(candidate.originalTitle()));
    }

    @PreDestroy
    public void closeCatalogChecks() {
        catalogChecks.shutdownNow();
    }

    /**
     * 唯一资格判定：目录身份、目标季放送/电影发行、严格在库及跨来源活动目标。
     * eligible 仅为发布查询前资格，不构成资源、额度或最终任务成功保证。
     */
    public Item evaluate(SourceState state, CollectedSubject subject) {
        return evaluate(subject, state.identities().get(subject.subjectId()), state.createdTasks().get(subject.subjectId()),
                state.candidateSubjectIds().contains(subject.subjectId()), "SUCCESS".equals(state.refreshStatus()), state.source().provider());
    }

    Item evaluateWeComTarget(Item item) {
        var subject = new CollectedSubject(item.subjectId(), item.title(), item.originalTitle(), item.coverUrl(), item.year(), item.updatedAt());
        Item checked = evaluate(subject, item.identity(), null, false, true, Provider.BGM);
        // 微信记录属于发起人，不返回其他用户可能创建的任务或删除操作 ID。
        return new Item(checked.subjectId(), checked.title(), checked.originalTitle(), checked.coverUrl(),
                checked.year(), checked.updatedAt(), checked.identity(), checked.libraryStatus(), checked.availability(),
                checked.eligible(), checked.reasons(), null, false, checked.releaseStatus(), null, null, false);
    }

    private Item evaluate(CollectedSubject subject, Identity identity, TaskLink saved, boolean candidate,
                          boolean snapshotAvailable, Provider provider) {
        String coverUrl = provider == Provider.DOUBAN ? null : subject.coverUrl();
        List<Reason> reasons = new ArrayList<>();
        var deletion = identity == null ? null : tasks.latestDeletion(target(identity));
        var deletionTask = deletion == null ? null : deletion.task();
        boolean deleting = deletionTask != null && Set.of("PENDING", "RUNNING").contains(deletionTask.status());
        if (deletion != null && !deletion.succeeded()) reasons.add(deletion.refusal());
        TaskLink associated = null;
        if (identity != null) {
            var previous = tasks.previousTask(target(identity));
            if (previous != null) {
                saved = new TaskLink(previous.taskType(), previous.taskId(), previous.status());
                associated = saved;
            }
        }
        if (saved != null) {
            if (associated == null) {
                var current = tasks.task(saved.taskType(), saved.taskId());
                associated = current == null ? new TaskLink(saved.taskType(), saved.taskId(), "UNKNOWN")
                        : new TaskLink(current.taskType(), current.taskId(), current.status());
            }
            if (deletion == null || !deletion.replaces(associated.taskId())) {
                reasons.add(BgmWishlistTaskGateway.previousTaskReason(associated.status()));
            } else {
                associated = null;
            }
        }
        boolean automaticCandidate = saved == null && deletion == null && candidate;
        if (!snapshotAvailable || deleting) {
            if (!snapshotAvailable) {
                reasons.add(0, new Reason("SNAPSHOT_UNAVAILABLE", "本轮没有完整有效清单，请先刷新成功"));
            }
            return new Item(subject.subjectId(), subject.title(), subject.originalTitle(), coverUrl, subject.year(),
                    subject.updatedAt(), identity, "UNKNOWN", "UNKNOWN", false, List.copyOf(reasons), null,
                    automaticCandidate, "NOT_CHECKED", associated, deletionTask, false);
        }
        String library = "UNKNOWN";
        String availability = "UNKNOWN";
        TaskLink active = null;
        boolean canDelete = false;
        if (identity == null) {
            reasons.add(new Reason("IDENTITY_UNKNOWN", "自动匹配未能确认唯一目录和目标季度，可手动纠正"));
        } else {
            try {
                boolean movie = "MOVIE".equals(identity.mediaType());
                JsonNode details = movie ? tmdb.getMovieDetails(identity.tmdbId(), "zh-CN")
                        : tmdb.getTvDetails(identity.tmdbId(), "zh-CN");
                if (details.path("id").asInt() == identity.tmdbId()) {
                    if (provider == Provider.DOUBAN) coverUrl = tmdbPosterUrl(details);
                    availability = movie ? movieAvailability(identity) : seasonAvailability(identity, details);
                }
            } catch (TmdbClientException exception) {
                availability = "UNKNOWN";
            }
            if (!Set.of("AVAILABLE", "THEATRICAL").contains(availability)) reasons.add(availabilityReason(availability));
            var check = presence.checkForAutomation(target(identity));
            if (!check.checkAvailable()) {
                reasons.add(new Reason("LIBRARY_UNKNOWN", "在库检查不可用，本次不能入库"));
            } else if (check.exists()) {
                library = "PRESENT";
                canDelete = check.unique() && (deletion == null || deletion.succeeded());
                reasons.add(new Reason("ALREADY_PRESENT", "已入库"));
            } else {
                library = "ABSENT";
            }
            var running = tasks.activeTask(target(identity));
            if (running != null) {
                active = new TaskLink(running.taskType(), running.taskId(), running.status());
                reasons.add(new Reason("ACTIVE_TASK", "同一电影或目标季度已有处理中任务"));
            }
        }
        return new Item(subject.subjectId(), subject.title(), subject.originalTitle(), coverUrl, subject.year(),
                subject.updatedAt(), identity, library, availability, reasons.isEmpty(), List.copyOf(reasons), active,
                automaticCandidate, "NOT_CHECKED", associated, deletionTask, canDelete && active == null);
    }

    public List<CatalogCandidate> searchCatalog(String query) {
        auth.requireAdminUser();
        try {
            List<CatalogCandidate> candidates = new ArrayList<>();
            for (JsonNode item : tmdb.searchMovies(query, "zh-CN")) {
                candidates.add(candidate("MOVIE", item, "title", "original_title", "release_date"));
            }
            for (JsonNode item : tmdb.searchTv(query, "zh-CN")) {
                candidates.add(candidate("SERIES", item, "name", "original_name", "first_air_date"));
            }
            return List.copyOf(candidates);
        } catch (TmdbClientException exception) {
            throw upstream("真实目录搜索暂不可用，请稍后重试");
        }
    }

    public List<Season> seasons(int tmdbId) {
        auth.requireAdminUser();
        try {
            JsonNode details = tmdb.getTvDetails(tmdbId, "zh-CN");
            requireCatalog(details, tmdbId);
            if (!details.path("seasons").isArray()) throw bad("该剧集目录没有可核实的季度");
            List<Season> seasons = new ArrayList<>();
            for (JsonNode season : details.path("seasons")) {
                if (season.path("season_number").asInt() > 0) {
                    seasons.add(new Season(season.path("season_number").intValue(), text(season, "name"),
                            season.path("episode_count").canConvertToInt() ? season.path("episode_count").intValue() : null,
                            text(season, "air_date")));
                }
            }
            return List.copyOf(seasons);
        } catch (TmdbClientException exception) {
            throw upstream("真实季度目录暂不可用，请稍后重试");
        }
    }

    public Item correctIdentity(String sourceId, long subjectId, IdentityInput input) {
        auth.requireAdminUser();
        BgmWishlistSourceStore.requireSubject(store.get(sourceId), subjectId);
        try {
            Identity identity = resolveIdentity(input);
            SourceState saved = store.saveIdentity(sourceId, subjectId, identity);
            return evaluate(saved, BgmWishlistSourceStore.requireSubject(saved, subjectId));
        } catch (TmdbClientException exception) {
            throw upstream("所选真实目录或季度暂不可核实，未覆盖原匹配");
        }
    }

    private Identity resolveIdentity(IdentityInput input) {
        JsonNode details;
        String titleField;
        String originalField;
        String dateField;
        if ("MOVIE".equals(input.mediaType())) {
            if (input.seasonNumber() != null) throw bad("电影目录不能指定季度");
            details = tmdb.getMovieDetails(input.tmdbId(), "zh-CN");
            titleField = "title";
            originalField = "original_title";
            dateField = "release_date";
    } else {
        if (input.seasonNumber() == null) throw bad("剧集必须选择真实目标季度");
        details = tmdb.getTvDetails(input.tmdbId(), "zh-CN");
        requireCatalog(details, input.tmdbId());
        if (findSeason(details, input.seasonNumber()) == null) throw bad("所选季度不在真实剧集目录中");
        JsonNode season = tmdb.getTvSeasonDetails(input.tmdbId(), input.seasonNumber(), "zh-CN");
        if (season == null || !season.path("id").isIntegralNumber() || season.path("id").asLong() <= 0
                || !season.path("season_number").isIntegralNumber()
                || season.path("season_number").asInt() != input.seasonNumber()) throw bad("所选季度无法核实");
        titleField = "name";
        originalField = "original_name";
        dateField = "first_air_date";
    }
    requireCatalog(details, input.tmdbId());
    String title = displayTitle(details, titleField, originalField);
    if (title == null) throw bad("所选目录缺少可核实的作品名称");
    return new Identity(input.mediaType(), input.tmdbId(), input.seasonNumber(), title,
            text(details, originalField), year(text(details, dateField)));
    }

    public static BgmWishlistTaskGateway.Target target(Identity identity) {
        return new BgmWishlistTaskGateway.Target(identity.mediaType(), identity.tmdbId(), identity.seasonNumber(),
                identity.title(), identity.originalTitle(), identity.year());
    }

    /** 已有院线发行记录可继续入库检查；数字、实体或电视发行记录缺失仅提示。 */
    private String movieAvailability(Identity identity) {
        JsonNode countries = tmdb.getMovieReleaseDates(identity.tmdbId()).path("results");
        if (!countries.isArray()) return "UNKNOWN";
        boolean theatrical = false;
        boolean future = false;
        Instant now = Instant.now();
        for (JsonNode country : countries) {
            for (JsonNode release : country.path("release_dates")) {
                int type = release.path("type").asInt();
                if (type < 2 || type > 6) continue;
                Instant date = releaseInstant(text(release, "release_date"));
                if (date == null) continue;
                if (date.isAfter(now)) future = true;
                else if (type >= 4) return "AVAILABLE";
                else theatrical = true;
            }
        }
        return theatrical ? "THEATRICAL" : future ? "NOT_YET_AIRED" : "UNKNOWN";
    }

    /** 仅使用真实目标季度的正片总数、逐集日期及下一集信息，整个系列 Ended 不能单独放行。 */
    private String seasonAvailability(Identity identity, JsonNode series) {
        JsonNode summary = findSeason(series, identity.seasonNumber());
        if (summary == null || !summary.path("episode_count").isIntegralNumber()
                || summary.path("episode_count").asInt() <= 0) return "UNKNOWN";
        int expected = summary.path("episode_count").asInt();
        JsonNode season = tmdb.getTvSeasonDetails(identity.tmdbId(), identity.seasonNumber(), "zh-CN");
        JsonNode episodes = season.path("episodes");
        if (!season.path("season_number").isIntegralNumber()
                || season.path("season_number").asInt() != identity.seasonNumber()
                || !episodes.isArray() || episodes.size() != expected) return "UNKNOWN";
        boolean past = false;
        boolean future = false;
        Set<Integer> numbers = new HashSet<>();
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        for (JsonNode episode : episodes) {
            int number = episode.path("episode_number").asInt();
            LocalDate airDate = date(text(episode, "air_date"));
            if (airDate == null || number < 1 || number > expected || !numbers.add(number)
                    || (episode.has("season_number") && episode.path("season_number").asInt() != identity.seasonNumber())) {
                return "UNKNOWN";
            }
            // 日期没有时区和结束时刻；当天仍不当成已完成放送。
            if (airDate.isBefore(today)) past = true;
            else future = true;
        }
        JsonNode next = series.path("next_episode_to_air");
        if (next.path("season_number").asInt(-1) == identity.seasonNumber()) {
            LocalDate nextDate = date(text(next, "air_date"));
            if (nextDate == null) return "UNKNOWN";
            if (!nextDate.isBefore(today)) future = true;
            else if (!numbers.contains(next.path("episode_number").asInt())) return "UNKNOWN";
        }
        return future ? (past ? "AIRING" : "NOT_YET_AIRED") : "AVAILABLE";
    }

    private Reason availabilityReason(String availability) {
        return switch (availability) {
            case "NOT_YET_AIRED" -> new Reason("NOT_YET_AIRED", "目标作品尚未开播或发行，本次跳过");
            case "AIRING" -> new Reason("AIRING", "目标季度仍在放送，本次不建立追更任务");
            default -> new Reason("AVAILABILITY_UNKNOWN", "放送或发行状态无法确认，本次不能入库");
        };
    }

    private CatalogCandidate candidate(String type, JsonNode node, String title, String original, String releaseDate) {
        return new CatalogCandidate(type, node.path("id").asInt(), displayTitle(node, title, original),
                text(node, original), year(text(node, releaseDate)), tmdbPosterUrl(node));
    }

    private String tmdbPosterUrl(JsonNode node) {
        String poster = text(node, "poster_path");
        return poster == null ? null : "https://image.tmdb.org/t/p/w500" + poster;
    }

    private JsonNode findSeason(JsonNode details, int number) {
        for (JsonNode season : details.path("seasons")) {
            if (season.path("season_number").asInt(-1) == number) return season;
        }
        return null;
    }

    private void requireCatalog(JsonNode details, int id) {
        if (details == null || !details.path("id").isIntegralNumber() || details.path("id").asInt() != id) throw bad("真实目录身份无法核实");
    }

    private String displayTitle(JsonNode node, String title, String original) {
        String translated = text(node, title);
        return translated == null ? text(node, original) : translated;
    }

    private String text(JsonNode node, String field) {
        return node.path(field).isTextual() && !node.path(field).textValue().isBlank() ? node.path(field).textValue().trim() : null;
    }

    private Integer year(String date) {
        LocalDate parsed = date(date);
        return parsed == null ? null : parsed.getYear();
    }

    private LocalDate date(String value) {
        try {
            return value == null ? null : LocalDate.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private Instant releaseInstant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException exception) {
            return null;
        }
    }

    private BusinessException bad(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException upstream(String message) {
        return new BusinessException(ErrorCode.BAD_GATEWAY, message, HttpStatus.BAD_GATEWAY);
    }
}
