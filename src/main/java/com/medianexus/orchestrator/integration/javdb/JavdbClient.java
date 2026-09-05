package com.medianexus.orchestrator.integration.javdb;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Small read-only JAVDB client for the three censored ranking pages and their
 * movie detail pages. The parser intentionally follows the HTML contract used
 * by the probe script and keeps all request credentials inside this class.
 */
@Component
public class JavdbClient {

    private static final String BASE_URL = "https://javdb.com";
    private static final Duration RANKING_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration DETAIL_REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final int MAX_TRANSIENT_RETRIES = 3;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36";
    private static final Pattern CODE_PATTERN = Pattern.compile(
            "(?<![A-Z0-9])([A-Z]{2,12})[-_ ]?(\\d{2,7})(?![A-Z0-9])",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern BOX_ANCHOR_PATTERN = Pattern.compile(
            "(?is)<a\\b([^>]*\\bclass\\s*=\\s*[\\\"'][^\\\"']*\\bbox\\b[^\\\"']*[\\\"'][^>]*)>(.*?)</a>"
    );
    private static final Pattern GENERIC_ANCHOR_PATTERN = Pattern.compile(
            "(?is)<a\\b([^>]*)>(.*?)</a>"
    );
    private static final Pattern STRONG_PATTERN = Pattern.compile(
            "(?is)<strong[^>]*>(.*?)</strong>"
    );
    private static final Pattern HREF_PATTERN = Pattern.compile(
            "(?is)\\bhref\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"
    );
    private static final Pattern TITLE_PATTERN = Pattern.compile(
            "(?is)\\btitle\\s*=\\s*[\\\"']([^\\\"']*)[\\\"']"
    );
    private static final Pattern CLIPBOARD_PATTERN = Pattern.compile(
            "(?is)\\bdata-clipboard-text\\s*=\\s*[\\\"']([^\\\"']+)[\\\"']"
    );
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{4}-\\d{2}-\\d{2}\\b");
    private static final Pattern MAGNET_PATTERN = Pattern.compile(
            "^magnet:\\?xt=urn:btih:[^\\s<>\\\"']+",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern INFOHASH_PATTERN = Pattern.compile(
            "(?:[?&])xt=urn:btih:([^&]+)", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SUBTITLE_PATTERN = Pattern.compile(
            "(?:中文|中字|字幕|(?:^|[-_\\s])(?:c|ch|zh)(?:[-_.\\s]|$))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CRACKED_PATTERN = Pattern.compile(
            "(?:无码|無碼|流出|破解|解密版|uncensored|(?:^|[-_\\s])(?:uc|cu|u)(?:[-_.\\s]|$))",
            Pattern.CASE_INSENSITIVE
    );
    private static final Set<String> PERIODS = Set.of("daily", "weekly", "monthly");

    private final HttpClient httpClient;

    public JavdbClient() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public List<JavdbRankingMovie> ranking(String period, String cookie) {
        String normalizedPeriod = period == null ? "" : period.trim().toLowerCase(Locale.ROOT);
        if (!PERIODS.contains(normalizedPeriod)) {
            throw new JavdbClientException(JavdbClientException.Reason.PARSE, "JAVDB 榜单周期无效");
        }
        String url = BASE_URL + "/rankings/movies?p="
                + URLEncoder.encode(normalizedPeriod, StandardCharsets.UTF_8)
                + "&t=censored";
        String body = get(url, cookie);
        return parseRanking(body, normalizedPeriod, url);
    }

    public JavdbMovieDetail detail(String detailUrl, String expectedCode, String cookie) {
        if (!StringUtils.hasText(detailUrl)) {
            throw new JavdbClientException(JavdbClientException.Reason.PARSE, "JAVDB 详情地址缺失");
        }
        String body = get(detailUrl, cookie, DETAIL_REQUEST_TIMEOUT);
        if (!body.toLowerCase(Locale.ROOT).contains("magnets-content")) {
            if (looksLikeAuthenticationPage(body)) {
                throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "JAVDB 登录状态已失效");
            }
            throw new JavdbClientException(JavdbClientException.Reason.PARSE, "JAVDB 详情页缺少磁力区域");
        }

        List<JavdbMagnet> magnets = parseMagnets(body);
        String title = textFromHtml(firstMatch(body, "(?is)<h1[^>]*>(.*?)</h1>"));
        String code = normalizeCode(expectedCode);
        if (!StringUtils.hasText(code)) {
            code = extractSingleCode(title);
        }
        if (!StringUtils.hasText(code)) {
            throw new JavdbClientException(JavdbClientException.Reason.PARSE, "JAVDB 详情页番号无法识别");
        }
        return new JavdbMovieDetail(code, title, detailUrl, magnets);
    }

    public void validate(String cookie) {
        if (!StringUtils.hasText(cookie)) {
            throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "JAVDB Cookie 未配置");
        }
        List<JavdbRankingMovie> movies = ranking("daily", cookie);
        if (movies.isEmpty()) {
            throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "JAVDB 排行榜为空或登录状态无效");
        }
    }

    private String get(String url, String cookie) {
        return get(url, cookie, RANKING_REQUEST_TIMEOUT);
    }

    private String get(String url, String cookie, Duration requestTimeout) {
        if (!StringUtils.hasText(cookie)) {
            throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "JAVDB Cookie 未配置");
        }
        String normalizedCookie = cookie.trim();
        if (normalizedCookie.indexOf('\r') >= 0 || normalizedCookie.indexOf('\n') >= 0) {
            throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "JAVDB Cookie 格式无效");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException exception) {
            throw new JavdbClientException(JavdbClientException.Reason.PARSE, "JAVDB 地址无效", exception);
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"javdb.com".equalsIgnoreCase(uri.getHost())) {
            throw new JavdbClientException(JavdbClientException.Reason.PARSE, "JAVDB 地址不在允许范围内");
        }
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(requestTimeout)
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                .header("Cookie", normalizedCookie)
                .header("Referer", BASE_URL + "/")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build();
        try {
            for (int attempt = 0; attempt <= MAX_TRANSIENT_RETRIES; attempt++) {
                HttpResponse<String> response;
                try {
                    response = httpClient.send(
                            request,
                            HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                    );
                } catch (IOException exception) {
                    if (attempt >= MAX_TRANSIENT_RETRIES) {
                        throw exception;
                    }
                    pauseBeforeRetry(attempt);
                    continue;
                }
                if (response.statusCode() == 429) {
                    if (attempt >= MAX_TRANSIENT_RETRIES) {
                        throw new JavdbClientException(JavdbClientException.Reason.UPSTREAM,
                                "JAVDB 请求频率受限");
                    }
                    pauseBeforeRetry(attempt);
                    continue;
                }
                if (response.statusCode() >= 500 && response.statusCode() < 600) {
                    if (attempt >= MAX_TRANSIENT_RETRIES) {
                        throw new JavdbClientException(JavdbClientException.Reason.UPSTREAM,
                                "JAVDB 请求失败");
                    }
                    pauseBeforeRetry(attempt);
                    continue;
                }
                if (response.statusCode() == 403 || response.statusCode() == 401) {
                    throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION,
                            "JAVDB 请求被拒绝或需要验证");
                }
                if (response.statusCode() == 404) {
                    throw new JavdbClientException(JavdbClientException.Reason.NOT_FOUND, "JAVDB 页面不存在");
                }
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw new JavdbClientException(JavdbClientException.Reason.UPSTREAM, "JAVDB 请求失败");
                }
                String body = response.body();
                if (looksLikeAuthenticationPage(body)) {
                    throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "JAVDB 登录状态已失效");
                }
                return body;
            }
            throw new JavdbClientException(JavdbClientException.Reason.UPSTREAM, "JAVDB 请求失败");
        } catch (JavdbClientException exception) {
            throw exception;
        } catch (HttpTimeoutException exception) {
            throw new JavdbClientException(JavdbClientException.Reason.UPSTREAM, "JAVDB 请求超时", exception);
        } catch (IOException exception) {
            throw new JavdbClientException(JavdbClientException.Reason.UPSTREAM, "JAVDB 请求失败", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new JavdbClientException(JavdbClientException.Reason.UPSTREAM, "JAVDB 请求被中断", exception);
        }
    }

    private void pauseBeforeRetry(int attempt) throws InterruptedException {
        Thread.sleep(1000L * (attempt + 1));
    }

    private List<JavdbRankingMovie> parseRanking(String body, String period, String rankingUrl) {
        if (looksLikeAuthenticationPage(body)) {
            throw new JavdbClientException(JavdbClientException.Reason.AUTHENTICATION, "JAVDB 登录状态已失效");
        }
        List<JavdbRankingMovie> movies = new ArrayList<>();
        Matcher matcher = BOX_ANCHOR_PATTERN.matcher(body);
        int itemRank = 0;
        while (matcher.find()) {
            itemRank++;
            String attributes = matcher.group(1);
            String href = attribute(attributes, HREF_PATTERN);
            if (!StringUtils.hasText(href) || !href.startsWith("/v/")) {
                continue;
            }
            String anchorHtml = matcher.group(2);
            String code = extractSingleCode(textFromHtml(firstMatch(anchorHtml, STRONG_PATTERN)));
            if (!StringUtils.hasText(code)) {
                code = extractSingleCode(textFromHtml(anchorHtml));
            }
            if (!StringUtils.hasText(code)) {
                continue;
            }
            String title = attribute(attributes, TITLE_PATTERN);
            if (!StringUtils.hasText(title)) {
                title = textFromHtml(anchorHtml);
            }
            Matcher dateMatcher = DATE_PATTERN.matcher(textFromHtml(anchorHtml));
            movies.add(new JavdbRankingMovie(
                    code,
                    limit(title, 512),
                    BASE_URL + href,
                    dateMatcher.find() ? dateMatcher.group() : null,
                    period,
                    itemRank,
                    textFromHtml(anchorHtml).contains("含磁")
            ));
        }
        if (movies.isEmpty()) {
            throw new JavdbClientException(JavdbClientException.Reason.PARSE,
                    "JAVDB 排行榜未解析到影片，可能页面结构已变化");
        }
        return movies;
    }

    private List<JavdbMagnet> parseMagnets(String body) {
        int magnetsStart = body.toLowerCase(Locale.ROOT).indexOf("magnets-content");
        String scope = magnetsStart < 0 ? body : body.substring(magnetsStart);
        List<JavdbMagnet> magnets = new ArrayList<>();
        Set<String> seenHashes = new HashSet<>();
        Matcher matcher = GENERIC_ANCHOR_PATTERN.matcher(scope);
        while (matcher.find()) {
            String attributes = matcher.group(1);
            String magnet = attribute(attributes, HREF_PATTERN);
            if (!StringUtils.hasText(magnet)) {
                magnet = attribute(attributes, CLIPBOARD_PATTERN);
            }
            magnet = cleanAttribute(magnet);
            if (!MAGNET_PATTERN.matcher(magnet).find()) {
                continue;
            }
            String infohash = extractInfohash(magnet);
            if (!StringUtils.hasText(infohash) || !seenHashes.add(infohash)) {
                continue;
            }
            String originalName = magnetName(magnet);
            if (!StringUtils.hasText(originalName)) {
                originalName = textFromHtml(matcher.group(2));
            }
            boolean hasSubtitle = SUBTITLE_PATTERN.matcher(originalName).find();
            boolean isCracked = CRACKED_PATTERN.matcher(originalName).find();
            List<String> labels = new ArrayList<>();
            if (isCracked) {
                labels.add("破解");
            }
            if (hasSubtitle) {
                labels.add("中文字幕");
            }
            magnets.add(new JavdbMagnet(
                    magnet,
                    limit(originalName, 1024),
                    infohash,
                    hasSubtitle,
                    isCracked,
                    List.copyOf(labels),
                    "filename_rule"
            ));
        }
        return magnets;
    }

    private String magnetName(String magnet) {
        int queryIndex = magnet.indexOf('?');
        if (queryIndex < 0) {
            return "";
        }
        for (String parameter : magnet.substring(queryIndex + 1).split("&")) {
            int equalsIndex = parameter.indexOf('=');
            if (equalsIndex <= 0 || !"dn".equalsIgnoreCase(parameter.substring(0, equalsIndex))) {
                continue;
            }
            String encodedName = parameter.substring(equalsIndex + 1);
            try {
                return URLDecoder.decode(encodedName, StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                return encodedName;
            }
        }
        return "";
    }

    private String extractInfohash(String magnet) {
        Matcher matcher = INFOHASH_PATTERN.matcher(magnet);
        return matcher.find() ? matcher.group(1).trim().toLowerCase(Locale.ROOT) : null;
    }

    private boolean looksLikeAuthenticationPage(String body) {
        if (!StringUtils.hasText(body)) {
            return true;
        }
        String lower = body.toLowerCase(Locale.ROOT);
        return lower.contains("cf-chl-")
                || lower.contains("just a moment")
                || lower.contains("verify you are human")
                || lower.contains("cf-error-details")
                || lower.contains("cf-chl-widget")
                || lower.contains("action=\"/login")
                || lower.contains("action='/login")
                || lower.contains("name=\"username\"") && lower.contains("password");
    }

    private String extractSingleCode(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        Matcher matcher = CODE_PATTERN.matcher(normalizeText(value));
        String result = null;
        while (matcher.find()) {
            if (result != null) {
                return result;
            }
            result = matcher.group(1).toUpperCase(Locale.ROOT) + "-" + matcher.group(2);
        }
        return result;
    }

    private String normalizeCode(String value) {
        return extractSingleCode(value);
    }

    private String textFromHtml(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        return decodeEntities(html.replaceAll("(?is)<[^>]+>", " "))
                .replaceAll("\\s+", " ")
                .trim();
    }

    private String firstMatch(String value, String pattern) {
        return firstMatch(value, Pattern.compile(pattern));
    }

    private String firstMatch(String value, Pattern pattern) {
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String attribute(String attributes, Pattern pattern) {
        Matcher matcher = pattern.matcher(attributes == null ? "" : attributes);
        return matcher.find() ? decodeEntities(matcher.group(1)) : "";
    }

    private String cleanAttribute(String value) {
        return value == null ? "" : value.trim().replaceAll("[\\\"'>]+$", "");
    }

    private String normalizeText(String value) {
        return value.replace('–', '-').replace('—', '-').replace('−', '-').toUpperCase(Locale.ROOT);
    }

    private String decodeEntities(String value) {
        return value.replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">");
    }

    private String limit(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
