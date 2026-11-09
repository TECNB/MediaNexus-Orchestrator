package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.WeComProperties;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Identity;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Operation;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient;
import com.medianexus.orchestrator.integration.wecom.WeComClient.Article;
import com.medianexus.orchestrator.mapper.BgmWishlistExecutionMapper;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/** 单一管理员绑定，按持久收件顺序处理；聊天只解析目录，入库由现有操作执行器承接。 */
@Service
public class WeComIngestChat {
    private static final Logger log = LoggerFactory.getLogger(WeComIngestChat.class);
    private static final Pattern SEASON = Pattern.compile("\\s*(?:第\\s*([0-9]{1,2}|[一二三四五六七八九十]{1,3})\\s*季|(?<![A-Za-z0-9])S([0-9]{1,2}))$", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_NUMBER = Pattern.compile("(.+?)\\s+([0-9]{1,2})$");
    private static final Pattern YEAR = Pattern.compile("(.+?)\\s*(?:\\(([12][0-9]{3})\\)|\\s+([12][0-9]{3}))$");
    private static final Pattern CHAT = Pattern.compile("^(?:你好|您好|谢谢|好的|收到|测试|在吗|hello|hi)[！!。.？?\\s]*$", Pattern.CASE_INSENSITIVE);
    private static final String DIGITS = "零一二三四五六七八九";
    private static final long SELECTION_SECONDS = 600;
    private final WeComProperties config;
    private final UserMapper users;
    private final BgmWishlistExecutionMapper mapper;
    private final BgmWishlistOperationStore records;
    private final BgmWishlistOperationService operations;
    private final BgmWishlistSessionLocks locks;
    private final ObjectMapper json;
    private final TmdbClient tmdb;
    private final WeComClient client;
    private final ThreadPoolExecutor inbox = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1), r -> { var thread = new Thread(r, "wecom-inbox"); thread.setDaemon(true); return thread; },
            new ThreadPoolExecutor.DiscardPolicy());

    public WeComIngestChat(WeComProperties config, UserMapper users, BgmWishlistExecutionMapper mapper,
                           BgmWishlistOperationStore records, BgmWishlistOperationService operations,
                           BgmWishlistSessionLocks locks, ObjectMapper json, TmdbClient tmdb, WeComClient client) {
        this.config = config; this.users = users; this.mapper = mapper; this.records = records;
        this.operations = operations; this.locks = locks; this.json = json; this.tmdb = tmdb; this.client = client;
    }

    public boolean configured() {
        return config.isIngestEnabled() && config.getIngestAdminId() != null && config.getIngestAdminId() > 0
                && StringUtils.hasText(config.getIngestMemberId()) && config.getIngestMemberId().matches("[A-Za-z0-9_.-]+")
                && StringUtils.hasText(config.getCorpId()) && StringUtils.hasText(config.getAppSecret())
                && config.getAgentId() != null && config.getAgentId() > 0
                && StringUtils.hasText(config.getIngestToken()) && config.getIngestToken().matches("[A-Za-z0-9]{1,32}")
                && StringUtils.hasText(config.getIngestAesKey()) && config.getIngestAesKey().matches("[A-Za-z0-9+/]{43}");
    }

    public void accept(String member, String messageId, String text) {
        if (!config.getIngestMemberId().equals(member) || messageId == null || !messageId.matches("[0-9]{1,24}")
                || text == null || text.length() > 512 || text.isBlank()) return;
        User admin = admin();
        if (admin == null) return;
        records.ensureTable();
        String id = UUID.nameUUIDFromBytes((config.getCorpId() + ":" + config.getAgentId() + ":" + messageId)
                .getBytes(StandardCharsets.UTF_8)).toString();
        String source = BgmWishlistOperationService.weComSource(admin.getId());
        mapper.insertWeComMessage("wx-msg-" + id, source, admin.getId(),
                encode(new Message(id, admin.getId(), member, text, Instant.now())));
        wake();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmToken() {
        if (configured()) inbox.execute(() -> {
            try { client.warmToken(); }
            catch (RuntimeException failed) { log.warn("企业微信自动化令牌预取失败"); }
        });
    }

    @Scheduled(fixedDelay = 5000)
    public void wake() {
        if (configured()) inbox.execute(this::drain);
    }

    private void drain() {
        try {
            locks.withLock("wecom-ingest-inbox", InboxBusy::new, () -> {
                records.ensureTable();
                User admin = admin();
                if (admin == null) return null;
                for (String raw : mapper.pendingWeComMessages()) {
                    Message message = decode(raw, Message.class);
                    // At-most-once dispatch: never replay a number against a different set of candidates after interruption.
                    mapper.consumeWeComMessage("wx-msg-" + message.id());
                    if (message.adminId().equals(admin.getId()) && message.member().equals(config.getIngestMemberId())
                            && !message.receivedAt().plusSeconds(SELECTION_SECONDS).isBefore(Instant.now())) handle(message, admin);
                }
                Operation current = active(admin);
                if (current != null && current.updatedAt().plusSeconds(SELECTION_SECONDS).isBefore(Instant.now())) {
                    operations.setWeComResolution(current.id(), "EXPIRED", "候选已过期，请重新发送片名。");
                }
                return null;
            });
        } catch (InboxBusy ignored) { /* 另一实例正在处理。 */ }
        catch (RuntimeException failed) { log.warn("企业微信收件处理失败：{}", failed.getClass().getSimpleName()); }
    }

    private void handle(Message message, User admin) {
        String text = normalize(message.text());
        if (text.isBlank() || text.length() > 100 || text.contains("\n") || text.matches("(?i)^(https?://|/).*")
                || CHAT.matcher(text).matches()) return;
        Operation current = active(admin);
        String id = message.id();
        try {
            if (text.matches("[0-9]{1,2}")) {
                if (current == null || current.updatedAt().plusSeconds(SELECTION_SECONDS).isBefore(Instant.now())) {
                    reply(message.member(), "没有有效的待选候选，请重新发送片名。"); return;
                }
                if ("RESOLVING".equals(current.status())) {
                    reply(message.member(), "没有可用的候选，请重新发送片名。"); return;
                }
                String raw = mapper.select("wx-choice-" + current.id(), "WECOM_CHOICES");
                if (raw == null) { reply(message.member(), "候选已失效，请重新发送片名。"); return; }
                Choices saved = decode(raw, Choices.class);
                if (!message.member().equals(saved.member())) {
                    reply(message.member(), "候选属于之前的成员配置，请重新发送片名。"); return;
                }
                var choices = saved.items();
                int index = Integer.parseInt(text);
                if (index < 1 || index > choices.size()) {
                    reply(message.member(), "请回复 1 到 " + choices.size() + "。"); return;
                }
                id = current.id();
                resolve(id, admin, message.member(), choices.get(index - 1));
                return;
            }
            if (current != null) operations.setWeComResolution(current.id(), "REPLACED", "已由同一成员的新片名请求替代。");
            operations.openWeCom(id, admin, text);
            search(id, admin, message.member(), parse(text));
        } catch (RuntimeException failed) {
            log.warn("微信目录处理失败 operationId={} type={}", id, failed.getClass().getSimpleName());
            // A failed reply must not undo a persisted selection or queued ingest.
            Operation snapshot = records.get(id);
            if ("RESOLVING".equals(snapshot.status())) {
                operations.setWeComResolution(id, "CATALOG_FAILED", "目录暂不可用，请重新发送片名。");
                reply(message.member(), "目录暂不可用，请重新发送片名。");
            } else if (Set.of("WAITING_WORK", "WAITING_SEASON").contains(snapshot.status())) {
                reply(message.member(), "暂时无法核实该候选，请稍后重试或重新发送片名。");
            }
        }
    }

    private void search(String id, User admin, String member, Title input) {
        JsonNode result = tmdb.searchWeComTitles(input.name());
        var rows = new LinkedHashMap<String, Choice>();
        var exact = new ArrayList<Choice>();
        for (JsonNode item : result.path("results")) {
            String type = item.path("media_type").asText();
            if (!Set.of("tv", "movie").contains(type) || (input.season() != null && !"tv".equals(type))) continue;
            int tmdbId = item.path("id").asInt();
            if (tmdbId <= 0 || rows.containsKey(type + tmdbId)) continue;
            boolean movie = "movie".equals(type);
            String title = item.path(movie ? "title" : "name").asText();
            String original = item.path(movie ? "original_title" : "original_name").asText();
            if (title.isBlank()) title = original;
            Integer year = year(item.path(movie ? "release_date" : "first_air_date").asText());
            if (title.isBlank() || (input.year() != null && !input.year().equals(year))) continue;
            Choice choice = new Choice(type, tmdbId, title, original, year, input.season(), poster(item),
                    title + (year == null ? "" : "（" + year + "）") + (movie ? " · 电影" : " · 剧集"));
            rows.put(type + tmdbId, choice);
            if (input.name().equalsIgnoreCase(normalize(title)) || input.name().equalsIgnoreCase(normalize(original))) exact.add(choice);
        }
        if (exact.size() == 1 && result.path("total_pages").asInt() <= 1) {
            resolve(id, admin, member, exact.get(0));
        } else if (!rows.isEmpty()) {
            choose(id, admin, member, "WAITING_WORK", "找到候选作品，请回复编号。", exact.isEmpty()
                    ? rows.values().stream().limit(5).toList() : exact.stream().limit(5).toList());
        } else {
            var bare = BARE_NUMBER.matcher(input.name());
            String message = bare.matches() ? "没有找到完整片名。如果你是想看第 " + bare.group(2)
                    + " 季，请发「" + bare.group(1) + " S" + bare.group(2) + "」。" : "没有找到对应作品，请核对片名或补充年份。";
            operations.setWeComResolution(id, "NOT_FOUND", message);
            reply(member, message);
        }
    }

    private void resolve(String id, User admin, String member, Choice choice) {
        Integer season = choice.season();
        String category = "MOVIE";
        if ("tv".equals(choice.type())) {
            JsonNode detail = tmdb.getTvDetails(choice.tmdbId(), "zh-CN");
            if (detail.path("id").asInt() != choice.tmdbId() || !detail.path("seasons").isArray() || !detail.path("genres").isArray()) {
                throw new IllegalStateException("TMDB 剧集详情不可核实");
            }
            category = "SERIES";
            for (JsonNode genre : detail.path("genres")) if (genre.path("id").asInt() == 16) category = "ANIME";
            var seasons = new ArrayList<Choice>();
            for (JsonNode entry : detail.path("seasons")) {
                int n = entry.path("season_number").asInt();
                if (n <= 0) continue;
                Integer aired = year(entry.path("air_date").asText());
                seasons.add(new Choice(choice.type(), choice.tmdbId(), choice.title(), choice.original(), choice.year(), n,
                        poster(entry) == null ? choice.cover() : poster(entry), "第 " + n + " 季" + (aired == null ? "" : "（" + aired + "）")));
            }
            if (season == null && seasons.size() > 1) {
                choose(id, admin, member, "WAITING_SEASON", "《" + choice.title() + "》有多个季度，请回复编号。"
                        + (seasons.size() > 8 ? "其他季度可重新发送片名加季号。" : ""), seasons.stream().limit(8).toList());
                return;
            }
            if (season == null && seasons.size() == 1) season = seasons.get(0).season();
            Integer selected = season;
            if (selected == null || seasons.stream().noneMatch(s -> s.season().equals(selected))) {
                String message = selected == null ? "没有可核实的正式季度。" : "目录中没有可核实的第 " + selected + " 季。";
                operations.setWeComResolution(id, "SEASON_NOT_FOUND", message);
                reply(member, message);
                return;
            }
        }
        var identity = new Identity("tv".equals(choice.type()) ? "SERIES" : "MOVIE", choice.tmdbId(), season,
                choice.title(), choice.original(), choice.year(), category);
        reply(member, "已识别：《" + choice.title() + "》" + (season == null ? "" : "第 " + season + " 季") + "，正在找资源。");
        operations.enqueueWeCom(id, identity, choice.cover());
    }

    private void choose(String id, User admin, String member, String state, String prompt, List<Choice> choices) {
        mapper.saveWeComChoices("wx-choice-" + id, BgmWishlistOperationService.weComSource(admin.getId()), encode(new Choices(member, choices)));
        StringBuilder text = new StringBuilder(prompt);
        var articles = new ArrayList<Article>();
        for (int index = 0; index < choices.size(); index++) {
            Choice c = choices.get(index);
            String label = (index + 1) + ". " + c.label();
            text.append('\n').append(label);
            if (c.cover() != null) articles.add(new Article(label, "封面来源：TMDB", c.cover(),
                    "https://www.themoviedb.org/" + c.type() + "/" + c.tmdbId()
                            + ("WAITING_SEASON".equals(state) ? "/season/" + c.season() : "")));
        }
        operations.setWeComResolution(id, state, text.toString());
        reply(member, text.toString());
        if (!articles.isEmpty()) {
            try { client.sendNewsTo(member, articles); }
            catch (RuntimeException failed) { log.warn("微信候选封面发送失败 operationId={}", id); }
        }
    }

    private void reply(String member, String message) {
        try { client.sendTextTo(member, message); }
        catch (RuntimeException failed) { log.warn("企业微信自动化回执发送失败"); }
    }

    private Operation active(User admin) {
        String raw = mapper.activeWeComSelection(BgmWishlistOperationService.weComSource(admin.getId()));
        return raw == null ? null : decode(raw, Operation.class);
    }

    private User admin() {
        User user = users.selectById(config.getIngestAdminId());
        return user != null && "ADMIN".equalsIgnoreCase(user.getRole()) ? user : null;
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text, Normalizer.Form.NFKC).strip().replaceAll("[ \\t]+", " ");
    }

    static Title parse(String raw) {
        String name = normalize(raw);
        Integer season = null;
        Integer year = null;
        var match = SEASON.matcher(name);
        if (match.find() && !name.substring(0, match.start()).isBlank()) {
            String value = match.group(1) != null ? match.group(1) : match.group(2);
            season = seasonNumber(value);
            if (season != null) name = name.substring(0, match.start()).strip();
        }
        var date = YEAR.matcher(name);
        if (date.matches()) {
            year = Integer.valueOf(date.group(2) == null ? date.group(3) : date.group(2));
            name = date.group(1).strip();
        }
        return new Title(name, season, year);
    }

    private static Integer seasonNumber(String value) {
        if (value.matches("[0-9]+")) return Integer.valueOf(value);
        if (value.length() == 1 && DIGITS.contains(value)) return DIGITS.indexOf(value);
        if (!value.matches("[一二三四五六七八九]?十[一二三四五六七八九]?")) return null;
        int ten = value.indexOf('十');
        return (ten == 0 ? 1 : DIGITS.indexOf(value.charAt(0))) * 10
                + (ten == value.length() - 1 ? 0 : DIGITS.indexOf(value.charAt(ten + 1)));
    }

    private static Integer year(String date) { return date.matches("[0-9]{4}-.*") ? Integer.valueOf(date.substring(0, 4)) : null; }

    private static String poster(JsonNode node) {
        String path = node.path("poster_path").asText();
        return path.matches("/[A-Za-z0-9]+\\.(?i:jpe?g|png|webp)") ? "https://image.tmdb.org/t/p/w500" + path : null;
    }

    private String encode(Object value) {
        try { return json.writeValueAsString(value); }
        catch (Exception failed) { throw new IllegalStateException("微信记录无法序列化"); }
    }

    private <T> T decode(String value, Class<T> type) {
        try { return json.readValue(value, type); }
        catch (Exception failed) { throw new IllegalStateException("微信记录无法读取"); }
    }

    record Title(String name, Integer season, Integer year) {}
    private record Message(String id, Long adminId, String member, String text, Instant receivedAt) {}
    private record Choices(String member, List<Choice> items) {}
    private record Choice(String type, int tmdbId, String title, String original, Integer year, Integer season, String cover, String label) {}
    private static class InboxBusy extends RuntimeException {}
    @PreDestroy public void close() { inbox.shutdownNow(); }
}
