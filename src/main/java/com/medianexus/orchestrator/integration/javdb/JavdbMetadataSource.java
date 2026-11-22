package com.medianexus.orchestrator.integration.javdb;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

/** Fetches source claims only after verifying the work number on the detail page. */
@Component
public class JavdbMetadataSource {

    private static final URI UPSTREAM = URI.create("https://javdb.com");
    private static final Pattern DETAIL_PATH = Pattern.compile("/v/[A-Za-z0-9]+");
    private static final Pattern SCORE = Pattern.compile("^([0-9]+(?:\\.[0-9]+)?)\\s*分(?:\\s*[,，].*)?$");
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36";
    private final HttpClient httpClient;
    private final URI transportBase;

    public JavdbMetadataSource() {
        this(UPSTREAM);
    }

    // The test transport changes only the socket destination; all page URLs still obey upstream rules.
    JavdbMetadataSource(URI transportBase) {
        if (!UPSTREAM.equals(transportBase) && !("http".equals(transportBase.getScheme())
                && List.of("localhost", "127.0.0.1", "[::1]").contains(transportBase.getHost())
                && transportBase.getPort() > 0 && transportBase.getRawUserInfo() == null
                && transportBase.getRawQuery() == null && transportBase.getRawFragment() == null
                && (transportBase.getPath().isEmpty() || "/".equals(transportBase.getPath())))) {
            throw new IllegalArgumentException("JAVDB test transport must be a loopback HTTP origin");
        }
        this.transportBase = transportBase;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public JavdbMetadataSnapshot fetch(String code, String detailUrl, String cookie) {
        String expectedCode = JavdbEdition.normalizeCode(code);
        if (expectedCode == null) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 番号无效");
        }
        if (cookie == null || cookie.isBlank() || cookie.chars().anyMatch(c -> c < 32 || c == 127)) {
            throw failure(JavdbClientException.Reason.AUTHENTICATION, "JAVDB Cookie 缺失或格式无效");
        }
        URI detail = detailUrl == null ? search(expectedCode, cookie.trim()) : detailUri(detailUrl);
        Document document = get(detail, cookie.trim());
        List<Element> numberFields = fields(document, "番號", "番号");
        if (numberFields.size() != 1
                || !expectedCode.equals(JavdbEdition.normalizeCode(numberFields.get(0).text()))) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 详情页番号缺失或不匹配");
        }
        Element title = document.selectFirst("h2.title .current-title, h1.title .current-title, h1 .current-title");
        if (title == null) {
            title = document.selectFirst("h2.title, h1.title, h1");
        }
        if (title == null || title.text().isBlank()) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 详情页标题缺失");
        }
        Element cover = document.selectFirst("img.video-cover");
        String coverUrl = cover == null ? null : coverUri(cover.attr("src"));
        return new JavdbMetadataSnapshot(expectedCode, detail.getPath().substring(3), detail.toString(),
                title.text(), coverUrl, releaseDate(document), names(document, "演員", "演员"),
                names(document, "導演", "导演"), names(document, "類別", "类别"),
                names(document, "片商", "發行", "发行", "製作商", "制作商"), rating(document));
    }

    private URI search(String code, String cookie) {
        URI searchUrl = URI.create(UPSTREAM + "/search?q="
                + URLEncoder.encode(code, StandardCharsets.UTF_8) + "&f=all");
        Document document = get(searchUrl, cookie);
        List<URI> matches = new ArrayList<>();
        for (Element card : document.select(".movie-list a.box")) {
            Element number = card.selectFirst(".video-title strong");
            if (number != null && code.equals(JavdbEdition.normalizeCode(number.text()))) {
                matches.add(detailUri(card.attr("href")));
            }
        }
        if (matches.isEmpty()) {
            throw failure(JavdbClientException.Reason.NOT_FOUND, "JAVDB 未找到精确匹配的番号");
        }
        if (matches.size() != 1) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 番号搜索结果不唯一");
        }
        return matches.get(0);
    }

    private static URI detailUri(String url) {
        URI uri = upstreamUri(url);
        if (!DETAIL_PATH.matcher(uri.getPath()).matches() || uri.getRawQuery() != null) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 详情地址格式无效");
        }
        return uri;
    }

    private static URI upstreamUri(String url) {
        URI uri;
        try {
            uri = UPSTREAM.resolve(URI.create(url));
        } catch (IllegalArgumentException exception) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 地址格式无效");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"javdb.com".equalsIgnoreCase(uri.getHost())
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                || !uri.getRawPath().equals(uri.getPath())) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 地址不在允许范围内");
        }
        return uri;
    }

    private static String coverUri(String url) {
        URI uri;
        try {
            uri = URI.create(url.startsWith("//") ? "https:" + url : url);
        } catch (IllegalArgumentException exception) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 封面地址格式无效");
        }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !host.toLowerCase(Locale.ROOT).endsWith(".jdbstatic.com")
                || uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
                || uri.getPath().isBlank() || !uri.getRawPath().equals(uri.getPath())) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 封面地址不在允许范围内");
        }
        return uri.toString();
    }

    private Document get(URI logicalUri, String cookie) {
        URI transportUri = transportBase.resolve(logicalUri.getRawPath()
                + (logicalUri.getRawQuery() == null ? "" : "?" + logicalUri.getRawQuery()));
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(transportUri)
                    .timeout(Duration.ofSeconds(logicalUri.getPath().equals("/search") ? 30 : 60))
                    .header("Accept", "text/html,application/xhtml+xml")
                    .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    .header("Cookie", cookie).header("Referer", UPSTREAM + "/")
                    .header("User-Agent", USER_AGENT).GET().build();
        } catch (IllegalArgumentException exception) {
            throw failure(JavdbClientException.Reason.AUTHENTICATION, "JAVDB Cookie 格式无效");
        }
        try {
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int status = response.statusCode();
            if (status == 401 || status == 403) {
                throw failure(JavdbClientException.Reason.AUTHENTICATION, "JAVDB 请求被拒绝或需要验证");
            }
            if (status >= 300 && status < 400) {
                String location = response.headers().firstValue("Location").orElse("");
                URI redirect;
                try {
                    redirect = upstreamUri(logicalUri.resolve(location).toString());
                } catch (IllegalArgumentException exception) {
                    throw failure(JavdbClientException.Reason.PARSE, "JAVDB 重定向地址格式无效");
                }
                if (redirect.getPath().matches("/(login|sign_in|users/sign_in)(/.*)?")) {
                    throw failure(JavdbClientException.Reason.AUTHENTICATION, "JAVDB 登录状态已失效");
                }
                throw failure(JavdbClientException.Reason.UPSTREAM, "JAVDB 返回了未允许的重定向");
            }
            if (status == 404) {
                throw failure(JavdbClientException.Reason.NOT_FOUND, "JAVDB 页面不存在");
            }
            if (status < 200 || status >= 300) {
                throw failure(JavdbClientException.Reason.UPSTREAM, "JAVDB 请求失败");
            }
            Document document = Jsoup.parse(response.body(), logicalUri.toString());
            if (!document.select("form[action*=sign_in], form[action*=login], input[type=password]").isEmpty()) {
                throw failure(JavdbClientException.Reason.AUTHENTICATION, "JAVDB 登录状态已失效");
            }
            return document;
        } catch (HttpTimeoutException exception) {
            throw failure(JavdbClientException.Reason.UPSTREAM, "JAVDB 请求超时");
        } catch (IOException exception) {
            throw failure(JavdbClientException.Reason.UPSTREAM, "JAVDB 网络请求失败");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw failure(JavdbClientException.Reason.UPSTREAM, "JAVDB 请求已中断");
        }
    }

    private static List<Element> fields(Document document, String... labels) {
        List<Element> values = new ArrayList<>();
        for (Element block : document.select(".movie-panel-info .panel-block")) {
            Element heading = block.selectFirst("strong");
            if (heading == null) {
                continue;
            }
            String label = heading.text().replace(":", "").replace("：", "").trim();
            if (List.of(labels).contains(label)) {
                Element value = block.selectFirst(".value");
                if (value != null) {
                    values.add(value);
                }
            }
        }
        return values;
    }

    private static List<String> names(Document document, String... labels) {
        List<String> names = new ArrayList<>();
        for (Element field : fields(document, labels)) {
            for (Element link : field.select("a")) {
                String name = link.text().trim();
                if (!name.isEmpty() && !names.contains(name)) {
                    names.add(name);
                }
            }
        }
        return List.copyOf(names);
    }

    private static String releaseDate(Document document) {
        List<Element> dates = fields(document, "日期", "發行日期", "发行日期");
        if (dates.isEmpty()) {
            return null;
        }
        try {
            return LocalDate.parse(dates.get(0).text()).toString();
        } catch (DateTimeParseException exception) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 发行日期格式无效");
        }
    }

    private static Double rating(Document document) {
        List<Element> ratings = fields(document, "評分", "评分");
        if (ratings.isEmpty() || ratings.get(0).text().matches(".*(?:暫無|暂无|N/A).*")) {
            return null;
        }
        Matcher matcher = SCORE.matcher(ratings.get(0).text());
        if (!matcher.matches()) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 评分格式无效");
        }
        double score = Double.parseDouble(matcher.group(1));
        if (score < 0 || score > 5) {
            throw failure(JavdbClientException.Reason.PARSE, "JAVDB 评分超出范围");
        }
        // JavDB's five-point community score is stored on the application's ten-point scale.
        return score * 2;
    }

    private static JavdbClientException failure(JavdbClientException.Reason reason, String message) {
        return new JavdbClientException(reason, message);
    }
}
