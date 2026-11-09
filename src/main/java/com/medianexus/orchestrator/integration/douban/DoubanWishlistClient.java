package com.medianexus.orchestrator.integration.douban;

import com.medianexus.orchestrator.service.BgmWishlistSourceStore.CollectedSubject;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
public class DoubanWishlistClient {
    private static final String MOVIES = "https://movie.douban.com";
    // Douban serves a different mobile layout to clients without a desktop user agent.
    private static final String DESKTOP_USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 Chrome/131.0.0.0 Safari/537.36";
    private static final String ACCOUNT = "[A-Za-z0-9_-]{1,64}";
    private static final Pattern PROFILE = Pattern.compile(
            "https?://(?:www\\.|movie\\.)?douban\\.com/people/(" + ACCOUNT + ")(?:/wish)?/?(?:[?#].*)?");
    private static final Pattern TOTAL = Pattern.compile("想看的影视\\s*[（(]\\s*(\\d+)\\s*[)）]$");
    private static final Pattern RANGE = Pattern.compile("(\\d+)-(\\d+)\\s*/\\s*(\\d+)");
    private static final Pattern SUBJECT = Pattern.compile("https://movie\\.douban\\.com/subject/([1-9][0-9]{0,17})/?");
    private static final Pattern YEAR = Pattern.compile("^(\\d{4})(?:-|\\s*/|$)");
    private static final int PAGE_SIZE = 15;
    private static final ZoneId DOUBAN_DATES = ZoneId.of("Asia/Shanghai");

    public static String accountId(String input) {
        String value = input.strip();
        if (value.matches(ACCOUNT)) return value;
        var profile = PROFILE.matcher(value);
        if (profile.matches()) return profile.group(1);
        throw new IllegalArgumentException("请输入豆瓣 ID、个人主页或想看链接");
    }

    public List<CollectedSubject> readWishlist(String account) {
        return readPages(offset -> Jsoup.connect(MOVIES + "/people/" + account + "/wish"
                        + "?sort=time&start=" + offset + "&filter=all&mode=grid&type=all")
                .timeout(10_000).followRedirects(false).userAgent(DESKTOP_USER_AGENT).get());
    }

    static List<CollectedSubject> readPages(WishlistPage fetch) {
        List<CollectedSubject> subjects = new ArrayList<>();
        var ids = new HashSet<Long>();
        int expected = -1;
        try {
            for (int offset = 0; ; offset += PAGE_SIZE) {
                Document page = fetch.read(offset);
                var heading = page.selectFirst("#content h1");
                var total = TOTAL.matcher(heading == null ? "" : heading.text());
                if (!total.find()) throw new WishlistReadException();
                int count = Integer.parseInt(total.group(1));
                if (expected < 0) expected = count;
                if (count != expected) throw new WishlistReadException();
                var grid = page.selectFirst("#content .grid-view");
                if (count == 0 && (grid == null || grid.select(".item").isEmpty())) return List.of();
                var pageRange = page.selectFirst("#content .subject-num");
                var range = RANGE.matcher(pageRange == null ? "" : pageRange.text());
                int slots = Math.min(PAGE_SIZE, count - offset);
                if (grid == null || !range.matches() || Integer.parseInt(range.group(1)) != offset + 1
                        || Integer.parseInt(range.group(2)) != offset + slots || Integer.parseInt(range.group(3)) != count) {
                    throw new WishlistReadException();
                }
                if (count > PAGE_SIZE) {
                    var current = page.selectFirst("#content .paginator .thispage");
                    int pageCount = (count - 1) / PAGE_SIZE + 1;
                    if (current == null || !current.text().equals(Integer.toString(offset / PAGE_SIZE + 1))
                            || !current.attr("data-total-page").equals(Integer.toString(pageCount))) {
                        throw new WishlistReadException();
                    }
                }
                // Douban can omit unavailable entries from a page while still counting their slots.
                var items = grid.select(".item");
                if (items.isEmpty() || items.size() > slots) throw new WishlistReadException();
                for (var item : items) {
                    var link = item.selectFirst(".title a[href]");
                    var date = item.selectFirst(".date");
                    if (link == null || date == null) throw new WishlistReadException();
                    var subject = SUBJECT.matcher(link.absUrl("href"));
                    if (!subject.matches()) throw new WishlistReadException();
                    long id = Long.parseLong(subject.group(1));
                    if (!ids.add(id)) throw new WishlistReadException();
                    var emphasis = link.selectFirst("em");
                    var titles = (emphasis == null ? link.text() : emphasis.text()).split("\\s+/\\s+", 3);
                    if (titles[0].isBlank()) throw new WishlistReadException();
                    var intro = item.selectFirst(".intro");
                    var year = YEAR.matcher(intro == null ? "" : intro.text());
                    var image = item.selectFirst(".pic img[src]");
                    String cover = image == null ? "" : image.absUrl("src");
                    subjects.add(new CollectedSubject(id, titles[0], titles.length > 1 ? titles[1] : null,
                            cover.startsWith("https://") ? cover : null, year.find() ? Integer.valueOf(year.group(1)) : null,
                            LocalDate.parse(date.text()).atStartOfDay(DOUBAN_DATES).toInstant().toString()));
                }
                if (offset + slots == expected) return List.copyOf(subjects);
            }
        } catch (IOException | DateTimeParseException | NumberFormatException exception) {
            throw new WishlistReadException();
        }
    }

    @FunctionalInterface
    interface WishlistPage {
        Document read(int offset) throws IOException;
    }

    public static class WishlistReadException extends RuntimeException {
        WishlistReadException() {
            super("豆瓣公开想看清单读取失败或分页不完整");
        }
    }
}
