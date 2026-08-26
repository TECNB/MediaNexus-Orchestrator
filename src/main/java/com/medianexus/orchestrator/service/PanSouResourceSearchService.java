package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.resources.request.QuarkReleaseLinkCheckItemRequest;
import com.medianexus.orchestrator.dto.resources.request.QuarkReleaseLinkCheckRequest;
import com.medianexus.orchestrator.dto.resources.request.QuarkReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.response.QuarkReleaseLinkCheckItemResponse;
import com.medianexus.orchestrator.dto.resources.response.QuarkReleaseLinkCheckResponse;
import com.medianexus.orchestrator.dto.resources.response.QuarkReleaseItemResponse;
import com.medianexus.orchestrator.dto.resources.response.QuarkReleaseSearchResponse;
import com.medianexus.orchestrator.integration.pansou.PanSouClient;
import com.medianexus.orchestrator.integration.pansou.PanSouClientException;
import com.medianexus.orchestrator.integration.pansou.PanSouLinkCheckRequest;
import com.medianexus.orchestrator.integration.pansou.PanSouLinkCheckResult;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchCommand;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchEntry;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchResult;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class PanSouResourceSearchService {

    private static final Pattern SHARE_PATH = Pattern.compile(
            "^/s/([A-Za-z0-9]+)(?:/([A-Fa-f0-9]{32}))?/?$"
    );
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)((?:19|20)\\d{2})(?!\\d)");
    private static final Pattern SEASON = Pattern.compile(
            "(?i)(?:第\\s*([一二三四五六七八九十\\d]{1,3})\\s*季|S0?(\\d{1,2})(?:\\D|$))"
    );

    private final PanSouClient panSouClient;
    private final AuthService authService;

    public PanSouResourceSearchService(
            PanSouClient panSouClient,
            AuthService authService
    ) {
        this.panSouClient = panSouClient;
        this.authService = authService;
    }

    public QuarkReleaseSearchResponse search(QuarkReleaseSearchRequest request) {
        authService.requireCurrentUser();
        String mediaType = validateRequest(request);
        String title = request.title().trim();
        boolean refresh = Boolean.TRUE.equals(request.refresh());
        List<String> warnings = new ArrayList<>();

        PanSouSearchResult primary = searchUpstream(title, refresh);
        List<PanSouSearchEntry> sourceEntries = new ArrayList<>(primary.entries());
        if (sourceEntries.isEmpty()
                && StringUtils.hasText(request.originalTitle())
                && !normalized(request.originalTitle()).equals(normalized(title))) {
            PanSouSearchResult fallback = searchUpstream(request.originalTitle().trim(), refresh);
            sourceEntries.addAll(fallback.entries());
            if (!fallback.entries().isEmpty()) {
                warnings.add("中文标题没有结果，已使用原始标题补充搜索");
            }
        }

        int invalidCount = 0;
        Map<String, Candidate> candidatesByIdentity = new LinkedHashMap<>();
        for (PanSouSearchEntry entry : sourceEntries) {
            CanonicalShare share = canonicalShare(entry.url(), entry.password());
            if (share == null) {
                invalidCount++;
                continue;
            }
            Candidate incoming = analyze(entry, share, mediaType, request);
            candidatesByIdentity.merge(share.identity(), incoming, Candidate::merge);
        }
        if (invalidCount > 0) {
            warnings.add("已忽略 " + invalidCount + " 条非 pan.quark.cn 分享链接");
        }

        List<Candidate> candidates = new ArrayList<>(candidatesByIdentity.values());
        candidates.sort(Candidate.SEMANTIC_ORDER);

        List<QuarkReleaseItemResponse> items = candidates.stream()
                .map(Candidate::toResponse)
                .toList();
        return new QuarkReleaseSearchResponse(title, items, warnings);
    }

    public QuarkReleaseLinkCheckResponse checkLinks(QuarkReleaseLinkCheckRequest request) {
        authService.requireCurrentUser();
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw badRequest("待检查链接不能为空");
        }

        Map<String, QuarkReleaseLinkCheckItemRequest> requestsByIdentity = new LinkedHashMap<>();
        for (QuarkReleaseLinkCheckItemRequest item : request.items()) {
            CanonicalShare share = canonicalShare(item.shareUrl(), "");
            if (share == null) {
                throw badRequest("只能检查合法的 pan.quark.cn 分享链接");
            }
            if (!stableId(share.identity()).equals(item.id())) {
                throw badRequest("候选 ID 与分享链接不匹配");
            }
            requestsByIdentity.putIfAbsent(share.identity(), item);
        }

        List<PanSouLinkCheckResult> checks;
        try {
            checks = panSouClient.checkLinks(requestsByIdentity.entrySet().stream()
                    .map(entry -> new PanSouLinkCheckRequest(
                            canonicalShare(entry.getValue().shareUrl(), "").url(),
                            ""
                    ))
                    .toList());
        } catch (PanSouClientException exception) {
            throw mapFailure(exception);
        }

        Map<String, PanSouLinkCheckResult> checksByIdentity = new LinkedHashMap<>();
        for (PanSouLinkCheckResult check : checks) {
            CanonicalShare share = canonicalShare(
                    StringUtils.hasText(check.normalizedUrl()) ? check.normalizedUrl() : check.url(),
                    ""
            );
            if (share != null) {
                checksByIdentity.put(share.identity(), check);
            }
        }

        List<QuarkReleaseLinkCheckItemResponse> items = requestsByIdentity.entrySet().stream()
                .map(entry -> toCheckResponse(entry.getValue(), checksByIdentity.get(entry.getKey())))
                .toList();
        return new QuarkReleaseLinkCheckResponse(request.viewToken(), items);
    }

    private PanSouSearchResult searchUpstream(String keyword, boolean refresh) {
        try {
            return panSouClient.search(new PanSouSearchCommand(keyword, refresh));
        } catch (PanSouClientException exception) {
            throw mapFailure(exception);
        }
    }

    private QuarkReleaseLinkCheckItemResponse toCheckResponse(
            QuarkReleaseLinkCheckItemRequest item,
            PanSouLinkCheckResult check
    ) {
        if (check == null) {
            return new QuarkReleaseLinkCheckItemResponse(
                    item.id(),
                    "UNCERTAIN",
                    "PanSou 未返回该链接的检查结果"
            );
        }
        String availability = cleanCheckState(check.state());
        String summary = StringUtils.hasText(check.summary())
                ? check.summary().trim()
                : checkStateSummary(availability);
        return new QuarkReleaseLinkCheckItemResponse(item.id(), availability, summary);
    }

    private String cleanCheckState(String value) {
        String state = clean(value).toUpperCase(Locale.ROOT);
        return switch (state) {
            case "OK", "BAD", "LOCKED", "UNCERTAIN", "UNSUPPORTED" -> state;
            default -> "UNCERTAIN";
        };
    }

    private String checkStateSummary(String state) {
        return switch (state) {
            case "OK" -> "链接有效";
            case "BAD" -> "链接失效";
            case "LOCKED" -> "需要提取码或访问受限";
            case "UNSUPPORTED" -> "暂不支持检查";
            default -> "暂时无法确认";
        };
    }

    private Candidate analyze(
            PanSouSearchEntry entry,
            CanonicalShare share,
            String mediaType,
            QuarkReleaseSearchRequest request
    ) {
        String candidateTitle = StringUtils.hasText(entry.note()) ? entry.note().trim() : "未命名 Quark 分享";
        String normalizedTitle = normalized(candidateTitle);
        String targetTitle = normalized(request.title());
        int score = 0;
        List<String> reasons = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        Set<String> tags = new LinkedHashSet<>();

        if (normalizedTitle.contains(targetTitle)) {
            score += normalizedTitle.equals(targetTitle) ? 75 : 60;
            reasons.add("标题匹配");
        }
        if (request.year() != null) {
            Set<Integer> years = years(candidateTitle);
            if (years.contains(request.year())) {
                score += 15;
                reasons.add("年份匹配");
            } else if (!years.isEmpty()) {
                conflicts.add("目标年份为 " + request.year() + "，候选标注为 " + joinNumbers(years));
                score -= 20;
            }
        }
        if (request.seasonNumber() != null) {
            Set<Integer> seasons = seasons(candidateTitle);
            if (seasons.contains(request.seasonNumber())) {
                score += 15;
                reasons.add("季数匹配");
            } else if (!seasons.isEmpty()) {
                conflicts.add("目标为第 " + request.seasonNumber() + " 季，候选标注为第 " + joinNumbers(seasons) + " 季");
                score -= 20;
            }
        }

        addMediaTypeConflicts(mediaType, candidateTitle, conflicts);
        if (!conflicts.isEmpty()) {
            score -= 30;
        }
        if (candidateTitle.matches("(?is).*\\b(?:4K|2160P|UHD)\\b.*")) {
            tags.add("4K");
        }
        if (candidateTitle.matches("(?is).*\\b1080P\\b.*")) {
            tags.add("1080P");
        }
        if (candidateTitle.matches("(?is).*(?:全集|全\\s*\\d+\\s*集|完结|全期).*$")) {
            tags.add("全集/完结");
            reasons.add("完整性提示");
            score += 5;
        }
        String relevance = !conflicts.isEmpty()
                ? "CONFLICT"
                : reasons.contains("标题匹配") && (request.year() == null || reasons.contains("年份匹配"))
                ? "STRONG"
                : "POSSIBLE";
        return new Candidate(
                stableId(share.identity()),
                candidateTitle,
                share.url(),
                clean(entry.source()),
                clean(entry.datetime()),
                "UNCHECKED",
                "等待有效性检查",
                relevance,
                score,
                reasons,
                conflicts,
                new ArrayList<>(tags)
        );
    }

    private String validateRequest(QuarkReleaseSearchRequest request) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        if (!StringUtils.hasText(request.title())) {
            throw badRequest("标题不能为空");
        }
        String mediaType = clean(request.mediaType()).toUpperCase(Locale.ROOT);
        if (!List.of("MOVIE", "SERIES", "VARIETY").contains(mediaType)) {
            throw badRequest("媒体类型必须是 MOVIE、SERIES 或 VARIETY");
        }
        if ("MOVIE".equals(mediaType) && request.year() == null) {
            throw badRequest("电影年份不能为空");
        }
        if (!"MOVIE".equals(mediaType) && request.seasonNumber() == null) {
            throw badRequest("剧集或综艺季数不能为空");
        }
        return mediaType;
    }

    private void addMediaTypeConflicts(String mediaType, String title, List<String> conflicts) {
        if ("MOVIE".equals(mediaType)) {
            if (title.contains("短剧")) {
                conflicts.add("目标是电影，但候选标题显示为短剧");
            } else if (title.matches(".*(?:综艺|音乐会|电视剧|剧集).*")) {
                conflicts.add("目标是电影，但候选标题显示为其他节目类型");
            }
        } else if ("SERIES".equals(mediaType) && title.matches(".*(?:综艺|音乐会|短剧).*")) {
            conflicts.add("目标是电视剧，但候选标题显示为其他节目类型");
        } else if ("VARIETY".equals(mediaType) && title.matches(".*(?:短剧|电视剧|电影).*")) {
            conflicts.add("目标是综艺，但候选标题显示为其他节目类型");
        }
    }

    private CanonicalShare canonicalShare(String rawUrl, String password) {
        if (!StringUtils.hasText(rawUrl)) {
            return null;
        }
        try {
            URI uri = new URI(rawUrl.trim());
            if (!"https".equalsIgnoreCase(uri.getScheme()) || !"pan.quark.cn".equalsIgnoreCase(uri.getHost())) {
                return null;
            }
            Matcher matcher = SHARE_PATH.matcher(uri.getPath());
            if (!matcher.matches()) {
                return null;
            }
            String path = "/s/" + matcher.group(1) + (matcher.group(2) == null ? "" : "/" + matcher.group(2));
            String pwd = queryParameter(uri.getRawQuery(), "pwd");
            if (!StringUtils.hasText(pwd)) {
                pwd = clean(password);
            }
            String canonicalUrl = "https://pan.quark.cn" + path;
            if (StringUtils.hasText(pwd)) {
                canonicalUrl += "?pwd=" + URLEncoder.encode(pwd, StandardCharsets.UTF_8);
            }
            return new CanonicalShare(path, canonicalUrl);
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            return null;
        }
    }

    private String queryParameter(String rawQuery, String name) {
        if (!StringUtils.hasText(rawQuery)) {
            return "";
        }
        for (String part : rawQuery.split("&")) {
            int separator = part.indexOf('=');
            String key = separator < 0 ? part : part.substring(0, separator);
            if (name.equals(URLDecoder.decode(key, StandardCharsets.UTF_8))) {
                String value = separator < 0 ? "" : part.substring(separator + 1);
                return URLDecoder.decode(value, StandardCharsets.UTF_8);
            }
        }
        return "";
    }

    private Set<Integer> years(String value) {
        Set<Integer> result = new LinkedHashSet<>();
        Matcher matcher = YEAR.matcher(value);
        while (matcher.find()) {
            result.add(Integer.parseInt(matcher.group(1)));
        }
        return result;
    }

    private Set<Integer> seasons(String value) {
        Set<Integer> result = new LinkedHashSet<>();
        Matcher matcher = SEASON.matcher(value);
        while (matcher.find()) {
            String token = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            Integer number = parseSeason(token);
            if (number != null) {
                result.add(number);
            }
        }
        return result;
    }

    private Integer parseSeason(String token) {
        if (token == null) {
            return null;
        }
        if (token.matches("\\d+")) {
            return Integer.parseInt(token);
        }
        return switch (token) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            case "十" -> 10;
            case "十一" -> 11;
            case "十二" -> 12;
            default -> null;
        };
    }

    private String joinNumbers(Set<Integer> values) {
        return values.stream().map(String::valueOf).sorted().reduce((left, right) -> left + "、" + right).orElse("");
    }

    private String stableId(String identity) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private String normalized(String value) {
        return clean(value).toLowerCase(Locale.ROOT).replaceAll("[\\s·:：_\\-—()（）【】\\[\\]]+", "");
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private BusinessException mapFailure(PanSouClientException exception) {
        if (exception.getReason() == PanSouClientException.Reason.CONFIGURATION) {
            return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, exception.getMessage(), HttpStatus.SERVICE_UNAVAILABLE);
        }
        return new BusinessException(ErrorCode.BAD_GATEWAY, safeMessage(exception), HttpStatus.BAD_GATEWAY);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private String safeMessage(Exception exception) {
        return StringUtils.hasText(exception.getMessage()) ? exception.getMessage() : "PanSou 请求失败";
    }

    private record CanonicalShare(String identity, String url) {
    }

    private static final class Candidate {

        private static final Comparator<Candidate> SEMANTIC_ORDER = Comparator
                .comparingInt(Candidate::relevanceWeight).reversed()
                .thenComparing(Comparator.comparingInt((Candidate candidate) -> candidate.semanticScore).reversed())
                .thenComparing(candidate -> candidate.id);
        private final String id;
        private String title;
        private String shareUrl;
        private String source;
        private String publishedAt;
        private String availability;
        private String availabilitySummary;
        private String relevance;
        private int semanticScore;
        private final List<String> matchReasons;
        private final List<String> conflicts;
        private final List<String> tags;

        private Candidate(
                String id,
                String title,
                String shareUrl,
                String source,
                String publishedAt,
                String availability,
                String availabilitySummary,
                String relevance,
                int semanticScore,
                List<String> matchReasons,
                List<String> conflicts,
                List<String> tags
        ) {
            this.id = id;
            this.title = title;
            this.shareUrl = shareUrl;
            this.source = source;
            this.publishedAt = publishedAt;
            this.availability = availability;
            this.availabilitySummary = availabilitySummary;
            this.relevance = relevance;
            this.semanticScore = semanticScore;
            this.matchReasons = new ArrayList<>(matchReasons);
            this.conflicts = new ArrayList<>(conflicts);
            this.tags = new ArrayList<>(tags);
        }

        private Candidate merge(Candidate other) {
            if (!shareUrl.contains("?pwd=") && other.shareUrl.contains("?pwd=")) {
                shareUrl = other.shareUrl;
            }
            if (!StringUtils.hasText(source)) {
                source = other.source;
            } else if (StringUtils.hasText(other.source) && !source.contains(other.source)) {
                source += " · " + other.source;
            }
            if (other.semanticScore > semanticScore
                    || (other.semanticScore == semanticScore && other.title.length() > title.length())) {
                title = other.title;
                publishedAt = other.publishedAt;
                relevance = other.relevance;
                semanticScore = other.semanticScore;
                matchReasons.clear();
                matchReasons.addAll(other.matchReasons);
                conflicts.clear();
                conflicts.addAll(other.conflicts);
                tags.clear();
                tags.addAll(other.tags);
            }
            return this;
        }

        private int relevanceWeight() {
            return switch (relevance) {
                case "STRONG" -> 3;
                case "POSSIBLE" -> 2;
                default -> 1;
            };
        }

        private QuarkReleaseItemResponse toResponse() {
            return new QuarkReleaseItemResponse(
                    id,
                    title,
                    shareUrl,
                    source,
                    publishedAt,
                    availability,
                    availabilitySummary,
                    relevance,
                    matchReasons,
                    conflicts,
                    tags
            );
        }

    }
}
