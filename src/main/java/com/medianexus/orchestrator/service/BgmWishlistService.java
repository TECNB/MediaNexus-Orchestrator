package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.config.BangumiProperties;
import com.medianexus.orchestrator.integration.bangumi.BgmTmdbMappings;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.CatalogCandidate;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Identity;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.IdentityInput;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Item;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Reason;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Season;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Source;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.SourceInput;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.TaskLink;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Wishlist;
import com.medianexus.orchestrator.integration.bangumi.BangumiClient;
import com.medianexus.orchestrator.integration.bangumi.BangumiClient.BangumiReadException;
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
    private final TmdbClient tmdb;
    private final MediaLibraryPresenceService presence;
    private final BgmWishlistTaskGateway tasks;

    public BgmWishlistService(AuthService auth, BgmWishlistSourceStore store, BangumiClient bangumi,
                               TmdbClient tmdb, MediaLibraryPresenceService presence, BgmWishlistTaskGateway tasks,
                               BgmTmdbMappings mappings, BangumiProperties properties) {
        this.mappings = mappings;
        this.catalogChecks = Executors.newFixedThreadPool(properties.getCatalogConcurrency(), runnable -> {
            Thread thread = new Thread(runnable, "bgm-catalog-check");
            thread.setDaemon(true);
            return thread;
        });
        this.auth = auth;
        this.store = store;
        this.bangumi = bangumi;
        this.tmdb = tmdb;
        this.presence = presence;
        this.tasks = tasks;
    }

    public List<Source> listSources() {
        auth.requireAdminUser();
        return store.list().stream().map(SourceState::source).toList();
    }

    public Source createSource(SourceInput input) {
        auth.requireAdminUser();
        if (!Boolean.TRUE.equals(input.enabled())) return store.create(input).source();
        Instant started = Instant.now();
        try {
            return store.createWithRefresh(input, bangumi.readWishlist(input.bgmUsername()), started).source();
        } catch (BangumiReadException exception) {
            throw upstream("首次启用读取失败，未保存启用配置或建立基线");
        }
    }

    public Source updateSource(String id, SourceInput input) {
        auth.requireAdminUser();
        SourceState state = store.get(id);
        if (!Boolean.TRUE.equals(input.enabled()) || state.source().baselineInitialized()) {
            return store.update(id, input).source();
        }
        Instant started = Instant.now();
        try {
            return store.updateWithRefresh(id, input, bangumi.readWishlist(input.bgmUsername()), started).source();
        } catch (BangumiReadException exception) {
            store.recordRefreshFailure(id, started);
            throw upstream("首次启用读取失败，来源保持关闭，未建立或推进基线");
        }
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
            return wishlist(matchMissing(store.recordRefresh(sourceId, bangumi.readWishlist(state.source().bgmUsername()), started)));
        } catch (BangumiReadException exception) {
            SourceState retained = store.recordRefreshFailure(sourceId, started);
            return wishlist(new SourceState(retained.source(), retained.subjects(), retained.knownSubjectIds(),
                    retained.candidateSubjectIds(), retained.identities(), retained.createdTasks(), "FAILED",
                    "本次清单刷新失败，已保留上次有效清单；本轮不能用于新提交", started, retained.lastSuccessAt()));
        }
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
        Map<Long, IdentityInput> candidates;
        try {
            candidates = mappings.find(missing);
        } catch (BgmTmdbMappings.MappingUnavailableException exception) {
            log.warn("BGM 自动映射读取失败，保留已有目录 sourceId={}", state.source().id(), exception);
            return state;
        }
        List<CompletableFuture<Map.Entry<Long, Identity>>> checks = missing.stream()
                .filter(candidates::containsKey)
                .map(id -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return Map.entry(id, resolveIdentity(candidates.get(id)));
                    } catch (TmdbClientException | BusinessException exception) {
                        log.warn("BGM 映射目录无法核实 subjectId={}，保留待纠正", id);
                        return null;
                    }
                }, catalogChecks)).toList();
        Map<Long, Identity> verified = new HashMap<>();
        checks.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull)
                .forEach(entry -> verified.put(entry.getKey(), entry.getValue()));
        if (verified.isEmpty()) return state;
        return store.saveAutomaticIdentities(state.source().id(), verified);
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
        List<Reason> reasons = new ArrayList<>();
        Identity identity = state.identities().get(subject.subjectId());
        TaskLink saved = state.createdTasks().get(subject.subjectId());
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
                associated = new TaskLink(saved.taskType(), saved.taskId(), current == null ? "UNKNOWN" : current.status());
            }
            reasons.add(new Reason("PREVIOUS_TASK", "此作品或目录目标已有 BGM 创建记录，失败或中断请在任务中心恢复"));
        }
        if (!"SUCCESS".equals(state.refreshStatus())) {
            reasons.add(0, new Reason("SNAPSHOT_UNAVAILABLE", "本轮没有完整有效清单，请先刷新成功"));
            return new Item(subject.subjectId(), subject.title(), subject.originalTitle(), subject.coverUrl(), subject.year(),
                    subject.updatedAt(), identity, "UNKNOWN", "UNKNOWN", false, List.copyOf(reasons), null,
                    saved == null && state.candidateSubjectIds().contains(subject.subjectId()), "NOT_CHECKED", associated);
        }
        String library = "UNKNOWN";
        String availability = "UNKNOWN";
        TaskLink active = null;
        if (identity == null) {
            reasons.add(new Reason("IDENTITY_UNKNOWN", "自动匹配未能确认唯一目录和目标季度，可手动纠正"));
        } else {
            try {
                availability = "MOVIE".equals(identity.mediaType()) ? movieAvailability(identity) : seasonAvailability(identity);
            } catch (TmdbClientException exception) {
                availability = "UNKNOWN";
            }
            if (!"AVAILABLE".equals(availability)) reasons.add(availabilityReason(availability));
            var check = presence.checkForAutomation("MOVIE".equals(identity.mediaType()) ? "movie" : "series",
                    identity.tmdbId(), null, identity.seasonNumber());
            if (!check.checkAvailable()) {
                reasons.add(new Reason("LIBRARY_UNKNOWN", "在库检查不可用，本次不能入库"));
            } else if (check.exists()) {
                library = "PRESENT";
                reasons.add(new Reason("ALREADY_PRESENT", "电影或目标季度已在库；在库不表示全集完整"));
            } else {
                library = "ABSENT";
            }
            var running = tasks.activeTask(target(identity));
            if (running != null) {
                active = new TaskLink(running.taskType(), running.taskId(), running.status());
                reasons.add(new Reason("ACTIVE_TASK", "同一电影或目标季度已有处理中任务"));
            }
        }
        return new Item(subject.subjectId(), subject.title(), subject.originalTitle(), subject.coverUrl(), subject.year(),
                subject.updatedAt(), identity, library, availability, reasons.isEmpty(), List.copyOf(reasons), active,
                saved == null && state.candidateSubjectIds().contains(subject.subjectId()), "NOT_CHECKED", associated);
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
        if (input.seasonNumber() == null) throw bad("剧集型动漫必须选择真实目标季度");
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

    /** TMDB release types 4/5/6 的已发生发行是最小证据；不推断地区影院窗口，不拦截重映。 */
    private String movieAvailability(Identity identity) {
        JsonNode details = tmdb.getMovieDetails(identity.tmdbId(), "zh-CN");
        if (details.path("id").asInt() != identity.tmdbId()) return "UNKNOWN";
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
    private String seasonAvailability(Identity identity) {
        JsonNode series = tmdb.getTvDetails(identity.tmdbId(), "zh-CN");
        if (series.path("id").asInt() != identity.tmdbId()) return "UNKNOWN";
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
            case "THEATRICAL" -> new Reason("THEATRICAL", "仅核实到影院发行，尚无已发生的数字、实体或电视发行证据");
            default -> new Reason("AVAILABILITY_UNKNOWN", "放送或发行状态无法确认，本次不能入库");
        };
    }

    private CatalogCandidate candidate(String type, JsonNode node, String title, String original, String releaseDate) {
        String poster = text(node, "poster_path");
        return new CatalogCandidate(type, node.path("id").asInt(), displayTitle(node, title, original),
                text(node, original), year(text(node, releaseDate)), poster == null ? null : "https://image.tmdb.org/t/p/w500" + poster);
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
