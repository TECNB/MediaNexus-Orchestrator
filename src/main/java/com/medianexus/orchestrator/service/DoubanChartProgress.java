package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Identity;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.integration.douban.DoubanChartClient;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.service.DoubanChartStore.ProgressRecord;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 展示专用的深模块：来源补证、目录日期推断、持久缓存和有界轮转；绝不参与受理或下载。 */
@Service
public class DoubanChartProgress implements AutoCloseable {
    private static final Logger log = LoggerFactory.getLogger(DoubanChartProgress.class);
    private static final Duration SUCCESS_TTL = Duration.ofHours(24);
    private static final Duration FAILURE_TTL = Duration.ofMinutes(30);
    private final DoubanChartStore store;
    private final DoubanChartClient client;
    private final DoubanChartEligibility eligibility;
    private final TmdbClient tmdb;
    private final Clock clock;
    private final AtomicBoolean dispatched = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "douban-chart-progress"); thread.setDaemon(true); return thread;
    });

    @Autowired
    public DoubanChartProgress(DoubanChartStore store, DoubanChartClient client, DoubanChartEligibility eligibility, TmdbClient tmdb) {
        this(store, client, eligibility, tmdb, Clock.system(DoubanChartStore.ZONE));
    }
    DoubanChartProgress(DoubanChartStore store, DoubanChartClient client, DoubanChartEligibility eligibility, TmdbClient tmdb, Clock clock) {
        this.store = store; this.client = client; this.eligibility = eligibility; this.tmdb = tmdb; this.clock = clock;
    }

    /** 调度线程只唤醒，不执行 DB/HTTP；忙时不排队、不写 RUNNING，下一次自动继续。 */
    @Scheduled(initialDelay = 10000, fixedDelay = 60000)
    public void dispatch() {
        if (!dispatched.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try { refreshCurrentRound(); }
                catch (RuntimeException failure) { log.warn("豆瓣进度补证轮次中断，下次继续", failure); }
                finally { dispatched.set(false); }
            });
        } catch (RejectedExecutionException shutdown) { dispatched.set(false); }
    }

    /** 完整可控操作：每轮最多三个当前 SERIES，未处理项由持久快照和缓存自然表达为待查。 */
    public synchronized void refreshCurrentRound() {
        List<Pending> pending = new ArrayList<>();
        Instant now = clock.instant();
        for (ChartId chart : ChartId.values()) {
            for (Candidate candidate : store.board(chart).candidates()) {
                Subject subject = candidate.subject();
                if (!candidate.current() || !"SERIES".equals(subject.mediaType())) continue;
                Identity corrected = store.identity(subject.id());
                ProgressRecord saved = store.progress(chart, subject.id());
                ProgressEvidence evidence = matches(saved, subject, corrected) ? saved.evidence() : null;
                if (doubanDue(evidence, subject, now) || catalogDue(evidence, now))
                    pending.add(new Pending(chart, subject, corrected, evidence));
            }
        }
        // 先从未检查项开始，再最早检查项；失败冷却到期也不会饿死榜尾。
        pending.sort(Comparator.comparing(item -> item.evidence() == null ? Instant.MIN : item.evidence().checkedAt(),
                Comparator.nullsFirst(Comparator.naturalOrder())));
        for (Pending item : pending.stream().limit(3).toList()) {
            try { refresh(item); }
            catch (RuntimeException failure) { log.warn("豆瓣进度证据保存失败 subject={}", item.subject().id(), failure); }
        }
    }

    private void refresh(Pending item) {
        Subject subject = item.subject(); Instant started = clock.instant();
        ProgressEvidence previous = item.evidence();
        boolean readDouban = doubanDue(previous, subject, started);
        boolean readCatalog = catalogDue(previous, started);
        ProgressEvidence base = previous == null
                ? visible(null, subject, item.corrected(), started) : previous;
        // 持久短租约：网络前记录尝试。崩溃后最多等 30 分钟，不会永久 RUNNING 或每次重启无限重试。
        ProgressEvidence lease = new ProgressEvidence(subject.observedAt(), started,
                readDouban ? "PENDING" : base.doubanStatus(), readDouban ? null : base.doubanProgress(),
                readDouban ? started : base.doubanCheckedAt(),
                readDouban ? "等待后台补查豆瓣详情" : base.doubanMessage(), readCatalog ? null : base.catalog());
        store.saveProgress(item.chart(), new ProgressRecord(subject, item.corrected(), lease));
        String status = base.doubanStatus(), value = base.doubanProgress(), message = base.doubanMessage();
        Instant doubanChecked = base.doubanCheckedAt();
        if (readDouban) {
            try {
                String fresh = client.refreshSubject(subject).progress();
                value = supplied(fresh) ? fresh : null;
                status = value == null ? "NOT_PROVIDED" : "SUPPLIED";
                message = value == null ? "豆瓣详情本次未提供进度；总集数不等于更新进度" : "豆瓣详情本次提供的进度";
            } catch (RuntimeException failure) {
                status = "FAILED"; value = null; message = "豆瓣详情本次读取失败，稍后后台重查";
            }
            doubanChecked = clock.instant();
        }
        CatalogProgress catalog = readCatalog ? catalog(subject, item.corrected()) : base.catalog();
        ProgressEvidence evidence = new ProgressEvidence(subject.observedAt(), clock.instant(), status, value, doubanChecked, message, catalog);
        store.saveProgress(item.chart(), new ProgressRecord(subject, item.corrected(), evidence));
    }

    /** 纯投影；GET 过期/错身份证据只返回等待，不触发网络或改变持久记录。 */
    public static ProgressEvidence visible(ProgressRecord record, Subject subject, Identity corrected, Instant now) {
        if (!"SERIES".equals(subject.mediaType())) return null;
        ProgressEvidence evidence = matches(record, subject, corrected) ? record.evidence() : null;
        boolean raw = supplied(subject.progress());
        boolean pending = doubanDue(evidence, subject, now);
        CatalogProgress catalog = evidence == null || catalogDue(evidence, now) ? null : evidence.catalog();
        String effectiveDouban = raw ? subject.progress() : !pending && evidence != null
                && "SUPPLIED".equals(evidence.doubanStatus()) ? evidence.doubanProgress() : null;
        return new ProgressEvidence(subject.observedAt(), evidence == null ? null : evidence.checkedAt(),
                raw ? "NOT_NEEDED" : pending || evidence == null ? "PENDING" : evidence.doubanStatus(),
                raw || pending || evidence == null ? null : evidence.doubanProgress(),
                raw || pending || evidence == null ? null : evidence.doubanCheckedAt(),
                raw ? "榜单本已提供进度，无需豆瓣详情补查" : pending || evidence == null ? "等待后台补查豆瓣详情" : evidence.doubanMessage(),
                conflict(catalog, effectiveDouban));
    }
    private static boolean matches(ProgressRecord record, Subject subject, Identity corrected) {
        return record != null && subject.equals(record.subject()) && Objects.equals(corrected, record.correctedIdentity());
    }
    private static boolean doubanDue(ProgressEvidence evidence, Subject subject, Instant now) {
        if (supplied(subject.progress())) return false;
        if (evidence == null) return true;
        return expired(evidence.doubanCheckedAt(), Set.of("FAILED", "PENDING").contains(evidence.doubanStatus()) ? FAILURE_TTL : SUCCESS_TTL, now);
    }
    private static boolean catalogDue(ProgressEvidence evidence, Instant now) {
        if (evidence == null) return true;
        CatalogProgress catalog = evidence.catalog();
        if (catalog == null) return expired(evidence.checkedAt(), FAILURE_TTL, now);
        return expired(catalog.checkedAt(), "FAILED".equals(catalog.status()) ? FAILURE_TTL : SUCCESS_TTL, now);
    }
    private static boolean expired(Instant checked, Duration ttl, Instant now) { return checked == null || !checked.plus(ttl).isAfter(now); }
    private static boolean supplied(String value) { return value != null && !value.isBlank(); }

    private CatalogProgress catalog(Subject subject, Identity corrected) {
        Identity identity = corrected;
        try {
            identity = eligibility.resolveProgressIdentity(subject, corrected);
            if (identity == null) return unavailable("IDENTITY_REQUIRED", null, "片名、年份与目标季尚未唯一确认，请确认目录身份");
            JsonNode series = tmdb.getTvDetails(identity.tmdbId(), "zh-CN");
            JsonNode season = tmdb.getTvSeasonDetails(identity.tmdbId(), identity.seasonNumber(), "zh-CN");
            return seasonProgress(series, season, identity);
        } catch (RuntimeException failure) {
            return unavailable("FAILED", identity, "目录身份或目标季本次读取失败，稍后后台重查");
        }
    }
    private CatalogProgress unavailable(String status, Identity identity, String message) {
        return new CatalogProgress(status, clock.instant(), identity, null, null, null, null, null, null, null, "UNKNOWN", message);
    }

    private CatalogProgress seasonProgress(JsonNode series, JsonNode season, Identity identity) {
        if (series == null || series.path("id").asInt(-1) != identity.tmdbId()
                || season == null || !season.path("id").isIntegralNumber() || season.path("id").asLong() < 1
                || season.path("season_number").asInt(-1) != identity.seasonNumber())
            throw new IllegalStateException("目录身份不一致");
        JsonNode summary = null;
        for (JsonNode node : series.path("seasons")) if (node.path("season_number").asInt(-1) == identity.seasonNumber()) {
            if (summary != null) return unknown(identity, series, "目标季摘要重复，无法核实进度");
            summary = node;
        }
        JsonNode episodes = season.path("episodes");
        int total = summary != null && summary.path("episode_count").isIntegralNumber() ? summary.path("episode_count").asInt(-1) : -1;
        if (total < 1 || !episodes.isArray() || episodes.size() != total)
            return unknown(identity, series, "目标季正集数量与摘要不一致，证据不足");
        Set<Integer> numbers = new HashSet<>();
        Map<Integer, LocalDate> dates = new HashMap<>();
        int past = 0, today = 0, undated = 0, future = 0;
        boolean finale = false;
        LocalDate currentDate = LocalDate.now(clock);
        for (JsonNode episode : episodes) {
            int number = episode.path("episode_number").asInt(-1);
            if (!episode.path("episode_number").isIntegralNumber() || number < 1 || number > total || !numbers.add(number)
                    || episode.has("season_number") && (!episode.path("season_number").isIntegralNumber()
                    || episode.path("season_number").asInt(-1) != identity.seasonNumber()))
                return unknown(identity, series, "目标季正集号重复、缺失或错季，证据不足");
            LocalDate date = date(episode.path("air_date").asText(null));
            if (date == null) undated++;
            else {
                dates.put(number, date);
                if (date.isBefore(currentDate)) past++;
                else if (date.equals(currentDate)) today++;
                else future++;
            }
            // 仅最后正集的结束标记证明整季结束；中途的 finale 不足以推出整季完结。
            if (number == total) finale = Set.of("finale", "season_finale", "series_finale").contains(episode.path("episode_type").asText(""));
        }
        String seriesStatus = text(series, "status");
        String status = "UNKNOWN", message = "目录日期或结束证据不足，不能确认目标季进度";
        if (undated == 0 && future == total) { status = "NOT_AIRED"; message = "目标季目录日期均在未来，尚未到目录播出日期"; }
        else if (future > 0 && past + today > 0) { status = "AIRING"; message = "目标季目录同时有已到日期与未来集日期"; }
        else if (past == total && (finale || "Ended".equals(seriesStatus))) { status = "COMPLETE"; message = "目标季全部正集日期已过去，且目录有结束证据"; }
        Integer nextNumber = null; String nextDate = null;
        JsonNode next = series.path("next_episode_to_air");
        int number = next.path("episode_number").asInt(-1);
        LocalDate announced = date(next.path("air_date").asText(null));
        if (next.path("season_number").isIntegralNumber() && next.path("season_number").asInt(-1) == identity.seasonNumber()
                && next.path("episode_number").isIntegralNumber() && announced != null && !announced.isBefore(currentDate)
                && announced.equals(dates.get(number))) {
            nextNumber = number; nextDate = announced.toString();
        }
        return new CatalogProgress("SUCCEEDED", clock.instant(), identity, seriesStatus, total, past, today, undated,
                nextNumber, nextDate, status, message);
    }
    private CatalogProgress unknown(Identity identity, JsonNode series, String message) {
        return new CatalogProgress("SUCCEEDED", clock.instant(), identity, text(series, "status"), null, null, null, null, null, null, "UNKNOWN", message);
    }
    private static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        try { return LocalDate.parse(value); } catch (DateTimeParseException invalid) { return null; }
    }
    private static String text(JsonNode node, String field) { return node.path(field).isTextual() ? node.path(field).textValue() : null; }
    private static CatalogProgress conflict(CatalogProgress catalog, String douban) {
        if (catalog == null || douban == null || !"SUCCEEDED".equals(catalog.status())) return catalog;
        var complete = java.util.regex.Pattern.compile("\\s*([1-9][0-9]{0,3})\\s*集全\\s*").matcher(douban);
        var airing = java.util.regex.Pattern.compile("\\s*更新至\\s*([1-9][0-9]{0,3})\\s*集\\s*").matcher(douban);
        boolean isComplete = complete.matches(), isAiring = airing.matches();
        boolean countConflict = catalog.totalEpisodes() != null && (isComplete
                && Integer.parseInt(complete.group(1)) != catalog.totalEpisodes()
                || isAiring && Integer.parseInt(airing.group(1)) > catalog.totalEpisodes());
        if (!(countConflict || isAiring && "COMPLETE".equals(catalog.seasonStatus())
                || isComplete && Set.of("AIRING", "NOT_AIRED").contains(catalog.seasonStatus()))) return catalog;
        return new CatalogProgress(catalog.status(), catalog.checkedAt(), catalog.identity(), catalog.seriesStatus(), catalog.totalEpisodes(),
                catalog.pastEpisodes(), catalog.todayEpisodes(), catalog.undatedEpisodes(), catalog.nextEpisodeNumber(), catalog.nextAirDate(),
                "CONFLICT", "豆瓣进度/集数与目标季目录证据矛盾，需人工核实；两者均不代表真实资源上线");
    }
    private record Pending(ChartId chart, Subject subject, Identity corrected, ProgressEvidence evidence) {}
    @PreDestroy public void close() { worker.shutdownNow(); }
}
