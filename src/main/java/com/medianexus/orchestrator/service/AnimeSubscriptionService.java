package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.anime.request.AnimeSubscriptionPreviewRequest;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubtitleGroup;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubtitleGroupsResponse;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubscriptionPreviewResponse;
import com.medianexus.orchestrator.dto.anime.response.AnimeSubscriptionResponse;
import com.medianexus.orchestrator.integration.anirss.AniRssClient;
import com.medianexus.orchestrator.integration.anirss.AniRssClientException;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AnimeSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(AnimeSubscriptionService.class);
    private static final Pattern SIMPLIFIED_PATTERN = Pattern.compile("简繁|简体|简|(?<![A-Z0-9])(?:CHS|GB)(?![A-Z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern TRADITIONAL_PATTERN = Pattern.compile("繁体|繁|(?<![A-Z0-9])(?:CHT|BIG5)(?![A-Z0-9])", Pattern.CASE_INSENSITIVE);
    private static final Pattern DATE_PATTERN = Pattern.compile("(?<year>20\\d{2})[-/.年](?<month>\\d{1,2})[-/.月](?<day>\\d{1,2})");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(?<month>\\d{1,2})[-/.月](?<day>\\d{1,2})");
    private static final String GROUPS_FAILED_MESSAGE = "字幕组加载失败，请稍后重试";
    private static final String PREVIEW_FAILED_MESSAGE = "字幕组预览失败，请稍后重试";
    private static final String SUBSCRIBE_FAILED_MESSAGE = "订阅创建失败，请稍后重试";

    private final AniRssClient aniRssClient;
    private final ObjectMapper objectMapper;
    private final AuthService authService;
    private final UserActionQuotaService userActionQuotaService;

    public AnimeSubscriptionService(
            AniRssClient aniRssClient,
            ObjectMapper objectMapper,
            AuthService authService,
            UserActionQuotaService userActionQuotaService
    ) {
        this.aniRssClient = aniRssClient;
        this.objectMapper = objectMapper;
        this.authService = authService;
        this.userActionQuotaService = userActionQuotaService;
    }

    /**
     * 获取指定 Mikan 条目的可订阅字幕组候选。
     *
     * 只返回能识别语言且具备 RSS/Bangumi 地址的候选，并按简体优先、条目数更多、
     * 更新时间更近的顺序排序，保证前端默认选择更可能可用。
     */
    public AnimeSubtitleGroupsResponse groups(String sourceUrl) {
        if (!StringUtils.hasText(sourceUrl)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "番剧来源地址不能为空");
        }

        try {
            List<GroupCandidate> candidates = rankGroups(aniRssClient.getMikanGroups(sourceUrl.trim()));
            List<AnimeSubtitleGroup> groups = candidates.stream()
                    .map(GroupCandidate::toResponse)
                    .toList();
            return new AnimeSubtitleGroupsResponse(groups, groups.size());
        } catch (AniRssClientException exception) {
            log.warn("Anime subtitle group upstream request failed");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, GROUPS_FAILED_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (RuntimeException exception) {
            log.warn("Anime subtitle group response mapping failed");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, GROUPS_FAILED_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    /**
     * 预览用户选中字幕组生成的 Ani-RSS 订阅草稿。
     *
     * 预览结果必须至少包含一个可用条目，否则返回请求错误；这样创建订阅前就能阻止
     * 空订阅进入 Ani-RSS。
     */
    public AnimeSubscriptionPreviewResponse preview(AnimeSubscriptionPreviewRequest request) {
        PreviewResult result = previewSelectedGroup(request, PREVIEW_FAILED_MESSAGE);
        if (result.preview().previewCount() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "字幕组预览没有可用条目");
        }
        return result.preview();
    }

    /**
     * 创建 Ani-RSS 订阅。
     *
     * 创建前复用预览流程，并以 Ani-RSS 当前列表中的标题和季数作为重复订阅边界。
     * 命中重复时返回 exists 状态，不再向上游提交 addAni。
     */
    public AnimeSubscriptionResponse subscribe(AnimeSubscriptionPreviewRequest request) {
        User user = authService.requireCurrentUser();
        validateGroupRequest(request);
        userActionQuotaService.assertDailyContentCreateAvailable(user);

        PreviewResult result = previewSelectedGroup(request, SUBSCRIBE_FAILED_MESSAGE);
        AnimeSubscriptionPreviewResponse preview = result.preview();
        if (preview.previewCount() <= 0) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "字幕组预览没有可用条目");
        }

        try {
            if (hasDuplicateSubscription(preview.title(), preview.season())) {
                return new AnimeSubscriptionResponse(
                        "exists",
                        false,
                        true,
                        "订阅已存在",
                        preview
                );
            }

            userActionQuotaService.consumeDailyContentCreate(user, UserActionType.ANIME_SUBSCRIBE_CREATE);
            aniRssClient.addAni(result.subscription());
            return new AnimeSubscriptionResponse(
                    "added",
                    true,
                    false,
                    "已触发下载",
                    preview
            );
        } catch (BusinessException exception) {
            throw exception;
        } catch (AniRssClientException exception) {
            log.warn("Anime subscribe upstream request failed");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, SUBSCRIBE_FAILED_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (RuntimeException exception) {
            log.warn("Anime subscribe response mapping failed");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, SUBSCRIBE_FAILED_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private PreviewResult previewSelectedGroup(AnimeSubscriptionPreviewRequest request, String failureMessage) {
        validateGroupRequest(request);

        ObjectNode payload = objectMapper.createObjectNode();
        payload.put("type", "mikan");
        payload.put("url", request.rss().trim());
        payload.put("bgmUrl", request.bgmUrl().trim());
        payload.put("subgroup", request.subgroup().trim());
        payload.put("enable", true);

        try {
            JsonNode subscription = ensureEnabled(aniRssClient.rssToAni(payload));
            JsonNode preview = aniRssClient.previewAni(subscription);
            JsonNode items = preview.path("items");
            JsonNode omitList = preview.path("omitList");
            AnimeSubscriptionPreviewResponse response = new AnimeSubscriptionPreviewResponse(
                    textOrDefault(subscription.get("title"), "未知番剧"),
                    integerOrNull(subscription.get("season")),
                    textOrDefault(subscription.get("subgroup"), request.subgroup().trim()),
                    items.isArray() ? items.size() : 0,
                    integerList(omitList),
                    missingSummary(integerList(omitList)),
                    omitList.isArray() && omitList.size() > 0
            );
            return new PreviewResult(subscription, response);
        } catch (AniRssClientException exception) {
            log.warn("Anime preview upstream request failed");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, failureMessage, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (RuntimeException exception) {
            log.warn("Anime preview response mapping failed");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, failureMessage, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private JsonNode ensureEnabled(JsonNode subscription) {
        if (subscription instanceof ObjectNode objectNode) {
            // rssToAni 可能继承上游默认禁用值，预览和创建路径都要求草稿显式启用。
            objectNode.put("enable", true);
            return objectNode;
        }
        throw new IllegalArgumentException("subscription payload is not an object");
    }

    private boolean hasDuplicateSubscription(String title, Integer season) {
        JsonNode list = aniRssClient.listAni();
        JsonNode weekList = list.path("weekList");
        if (!weekList.isArray()) {
            return false;
        }

        for (JsonNode week : weekList) {
            JsonNode items = week.path("items");
            if (!items.isArray()) {
                continue;
            }
            for (JsonNode item : items) {
                if (sameTitleSeason(item, title, season)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean sameTitleSeason(JsonNode item, String title, Integer season) {
        String itemTitle = textOrNull(item.get("title"));
        Integer itemSeason = integerOrNull(item.get("season"));
        return String.valueOf(itemTitle).equals(String.valueOf(title))
                && String.valueOf(itemSeason).equals(String.valueOf(season));
    }

    private void validateGroupRequest(AnimeSubscriptionPreviewRequest request) {
        if (request == null
                || !StringUtils.hasText(request.rss())
                || !StringUtils.hasText(request.bgmUrl())
                || !StringUtils.hasText(request.subgroup())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "字幕组参数不完整");
        }
    }

    private List<GroupCandidate> rankGroups(JsonNode groupsNode) {
        if (groupsNode == null || !groupsNode.isArray()) {
            return List.of();
        }

        List<GroupCandidate> candidates = new ArrayList<>();
        int index = 0;
        for (JsonNode group : groupsNode) {
            if (!hasUsableGroupFields(group)) {
                index++;
                continue;
            }
            String language = classifyLanguage(group);
            if (!StringUtils.hasText(language)) {
                index++;
                continue;
            }
            int languageRank = "繁体".equals(language) ? 1 : 0;
            int itemCount = group.path("items").isArray() ? group.path("items").size() : 0;
            candidates.add(new GroupCandidate(
                    group,
                    language,
                    languageRank,
                    itemCount,
                    updateSortValue(group),
                    index
            ));
            index++;
        }

        candidates.sort(Comparator
                .comparingInt(GroupCandidate::languageRank)
                .thenComparing(Comparator.comparingInt(GroupCandidate::itemCount).reversed())
                .thenComparing(Comparator.comparingLong(GroupCandidate::updateSortValue).reversed())
                .thenComparingInt(GroupCandidate::originalIndex));
        return candidates;
    }

    private boolean hasUsableGroupFields(JsonNode group) {
        return StringUtils.hasText(textOrNull(group.get("label")))
                && StringUtils.hasText(textOrNull(group.get("rss")))
                && StringUtils.hasText(textOrNull(group.get("bgmUrl")));
    }

    private String classifyLanguage(JsonNode group) {
        String tagText = "";
        JsonNode tags = group.path("groupRegex").path("tags");
        if (tags.isArray()) {
            List<String> tagValues = new ArrayList<>();
            for (JsonNode tag : tags) {
                tagValues.add(tag.asText(""));
            }
            tagText = String.join(" ", tagValues);
        }
        String tagLanguage = classifyLanguageText(tagText);
        if (StringUtils.hasText(tagLanguage)) {
            return tagLanguage;
        }

        // 部分 Mikan 字幕组缺少语言标签，只能从种子标题里的 CHS/CHT/简繁字样兜底推断。
        JsonNode items = group.path("items");
        if (!items.isArray()) {
            return null;
        }
        List<String> titles = new ArrayList<>();
        for (JsonNode item : items) {
            titles.add(item.path("title").asText(""));
        }
        return classifyLanguageText(String.join(" ", titles));
    }

    private String classifyLanguageText(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        if (text.contains("简繁")) {
            return "简繁";
        }
        if (SIMPLIFIED_PATTERN.matcher(text).find()) {
            return "简体";
        }
        if (TRADITIONAL_PATTERN.matcher(text).find()) {
            return "繁体";
        }
        return null;
    }

    private long updateSortValue(JsonNode group) {
        long value = Math.max(parseDateValue(group.get("updateDay")), parseDateValue(group.get("updatedAt")));
        value = Math.max(value, parseDateValue(group.get("createdAt")));
        JsonNode items = group.path("items");
        if (!items.isArray()) {
            return value;
        }
        for (JsonNode item : items) {
            value = Math.max(value, parseDateValue(item.get("createdAt")));
            value = Math.max(value, parseDateValue(item.get("pubDate")));
            value = Math.max(value, parseDateValue(item.get("updatedAt")));
        }
        return value;
    }

    private long parseDateValue(JsonNode node) {
        if (node == null || node.isNull()) {
            return 0;
        }
        if (node.isNumber()) {
            long value = node.asLong();
            return value > 10_000_000_000L ? value / 1000 : value;
        }
        String text = node.asText("").trim();
        if (!StringUtils.hasText(text)) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(text.replace("Z", "+00:00")).toEpochSecond();
        } catch (DateTimeParseException ignored) {
            // Fall through to loose date patterns used by Mikan strings.
        }

        Matcher dateMatcher = DATE_PATTERN.matcher(text);
        if (dateMatcher.find()) {
            return localDateEpoch(
                    Integer.parseInt(dateMatcher.group("year")),
                    Integer.parseInt(dateMatcher.group("month")),
                    Integer.parseInt(dateMatcher.group("day"))
            );
        }

        Matcher monthDayMatcher = MONTH_DAY_PATTERN.matcher(text);
        if (monthDayMatcher.find()) {
            return localDateEpoch(
                    LocalDate.now().getYear(),
                    Integer.parseInt(monthDayMatcher.group("month")),
                    Integer.parseInt(monthDayMatcher.group("day"))
            );
        }
        return 0;
    }

    private long localDateEpoch(int year, int month, int day) {
        try {
            return LocalDate.of(year, month, day).atStartOfDay(ZoneId.systemDefault()).toEpochSecond();
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private String missingSummary(List<Integer> missingEpisodes) {
        if (missingEpisodes.isEmpty()) {
            return null;
        }
        if (missingEpisodes.size() <= 4) {
            return "缺集：" + joinIntegers(missingEpisodes);
        }
        return "缺 " + missingEpisodes.size() + " 集";
    }

    private String joinIntegers(List<Integer> values) {
        List<String> parts = new ArrayList<>();
        for (Integer value : values) {
            parts.add(String.valueOf(value));
        }
        return String.join(", ", parts);
    }

    private List<Integer> integerList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<Integer> values = new ArrayList<>();
        for (JsonNode item : node) {
            if (item.isInt() || item.isLong()) {
                values.add(item.asInt());
            }
        }
        return values;
    }

    private Integer integerOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asInt();
    }

    private String textOrDefault(JsonNode node, String fallback) {
        String value = textOrNull(node);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private record GroupCandidate(
            JsonNode group,
            String language,
            int languageRank,
            int itemCount,
            long updateSortValue,
            int originalIndex
    ) {
        AnimeSubtitleGroup toResponse() {
            String rss = textOrNullStatic(group.get("rss"));
            String subgroupId = textOrNullStatic(group.get("subgroupId"));
            String id = StringUtils.hasText(subgroupId) ? "mikan-group:" + subgroupId : "mikan-group:" + stableHashStatic(rss);
            return new AnimeSubtitleGroup(
                    id,
                    textOrDefaultStatic(group.get("label"), "未知字幕组"),
                    rss,
                    textOrNullStatic(group.get("bgmUrl")),
                    language,
                    itemCount,
                    textOrNullStatic(group.get("updateDay"))
            );
        }
    }

    private record PreviewResult(JsonNode subscription, AnimeSubscriptionPreviewResponse preview) {
    }

    private static String textOrDefaultStatic(JsonNode node, String fallback) {
        String value = textOrNullStatic(node);
        return StringUtils.hasText(value) ? value : fallback;
    }

    private static String textOrNullStatic(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private static String stableHashStatic(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6).toLowerCase(Locale.ROOT);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }
}
