package com.medianexus.orchestrator.integration.douban;

import static org.junit.jupiter.api.Assertions.*;

import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.junit.jupiter.api.Test;

class DoubanWishlistClientTest {
    @Test
    void requiresEveryPageAndRejectsDuplicateSubjectsOrForeignAccountLinks() {
        var subjects = DoubanWishlistClient.readPages(offset -> offset == 0 ? page(16, 0, 1, 14) : page(16, 15, 16, 16));
        assertEquals(15, subjects.size());
        assertEquals(16L, subjects.get(14).subjectId());
        assertEquals(2020, subjects.get(0).year());
        assertEquals("2025-12-31T16:00:00Z", subjects.get(0).updatedAt());
        assertThrows(DoubanWishlistClient.WishlistReadException.class,
                () -> DoubanWishlistClient.readPages(offset -> offset == 0 ? page(16, 0, 1, 15) : page(16, 15, 15, 15)));
        assertThrows(DoubanWishlistClient.WishlistReadException.class,
                () -> DoubanWishlistClient.readPages(offset -> offset == 0 ? page(16, 0, 1, 15) : page(16, 0, 16, 16)));
        assertThrows(DoubanWishlistClient.WishlistReadException.class,
                () -> DoubanWishlistClient.readPages(offset -> Jsoup.parse("<h1>请登录</h1>")));
        assertEquals("example", DoubanWishlistClient.accountId("https://www.douban.com/people/example/"));
        assertThrows(IllegalArgumentException.class,
                () -> DoubanWishlistClient.accountId("https://movie.douban.com.example.com/people/example/wish"));
    }

    private static Document page(int total, int offset, int first, int last) {
        String items = IntStream.rangeClosed(first, last).mapToObj(id -> """
                <div class="item"><div class="title"><a href="/subject/%d/"><em>电影 / Movie</em></a></div>
                <div class="intro">2020-01-01 / 演员</div><span class="date">2026-01-01</span></div>
                """.formatted(id)).collect(Collectors.joining());
        return Jsoup.parse("""
                <div id="content"><h1>用户想看的影视(%d)</h1><span class="subject-num">%d-%d&nbsp;/&nbsp;%d</span>
                <div class="grid-view">%s</div><div class="paginator"><span class="thispage" data-total-page="2">%d</span></div></div>
                """.formatted(total, offset + 1, Math.min(total, offset + 15), total, items, offset / 15 + 1),
                "https://movie.douban.com");
    }
}
