package com.medianexus.orchestrator.integration.douban;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.ChartId;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DoubanChartClientTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void readsAvailableHotPagesAndAllRankedPagesWithoutInventingMissingRatings() {
        var requests = new AtomicInteger();
        var client = new DoubanChartClient(json, (url, referer) -> {
            requests.incrementAndGet();
            int offset = Integer.parseInt(url.split("start=")[1].split("&")[0]);
            return page(offset, 73, Math.min(20, 73 - offset), false);
        });
        var hot = client.fetch(ChartId.movie_hot_gaia);
        assertEquals(73, hot.size());
        assertEquals(4, requests.get());
        assertNull(hot.get(0).score()); assertNull(hot.get(0).votes());
        assertEquals(1, hot.get(0).rank());
        assertEquals(20, hot.get(19).rank());
        assertEquals("https://img3.doubanio.com/view/photo/m_ratio_poster/public/p2935488630.jpg", hot.get(0).coverUrl());
        assertEquals("电影 原名保持完整", hot.get(0).title());
        var ranked = new DoubanChartClient(json, (url, referer) -> {
            int offset = Integer.parseInt(url.split("start=")[1].split("&")[0]);
            return page(offset, 73, Math.min(50, 73 - offset), true);
        }).fetch(ChartId.movie_top250);
        assertEquals(73, ranked.size()); assertEquals(73, ranked.get(72).rank());
    }

    @Test void readsOnlyFirstHundredPositionsInTwentyItemPagesForBothHotCharts() {
        for (ChartId chart : new ChartId[]{ChartId.movie_hot_gaia, ChartId.tv_hot}) {
            var offsets = new java.util.ArrayList<Integer>();
            var client = new DoubanChartClient(json, (url, referer) -> {
                assertTrue(url.endsWith("&count=20"));
                int offset = Integer.parseInt(url.split("start=")[1].split("&")[0]);
                offsets.add(offset);
                var response = page(offset, 310, 20, false);
                if (chart == ChartId.tv_hot) response.withArray("subject_collection_items").forEach(item -> ((ObjectNode)item).put("type", "tv"));
                return response;
            });
            var subjects = client.fetch(chart);
            assertEquals(java.util.List.of(0, 20, 40, 60, 80), offsets);
            assertEquals(100, subjects.size());
            assertEquals(100, subjects.get(99).id());
            assertEquals(100, subjects.get(99).rank());
        }
    }

    @Test void deduplicatesHotIdsInFirstSeenOrderWithoutReadingBeyondHundredOrRenumberingPositions() {
        var requests = new AtomicInteger();
        var client = new DoubanChartClient(json, (url, referer) -> {
            requests.incrementAndGet();
            int offset = Integer.parseInt(url.split("start=")[1].split("&")[0]);
            var response = page(offset, 310, 20, false);
            if (offset == 0) ((ObjectNode)response.withArray("subject_collection_items").get(1)).put("id", "1");
            if (offset == 20) ((ObjectNode)response.withArray("subject_collection_items").get(0)).put("id", "20");
            return response;
        });
        var subjects = client.fetch(ChartId.movie_hot_gaia);
        assertEquals(5, requests.get());
        assertEquals(98, subjects.size());
        assertEquals(1, subjects.get(0).id());
        assertEquals(3, subjects.get(1).id());
        assertEquals(3, subjects.get(1).rank());
        assertEquals(22, subjects.get(19).id());
        assertEquals(22, subjects.get(19).rank());
        assertEquals(100, subjects.get(97).rank());
    }

    @Test void keepsSmallAndEmptyHotCollectionsButRejectsIncompletePagesAndSourceFailure() {
        for (int size : new int[]{0, 7, 20, 21}) {
            var client = new DoubanChartClient(json, (url, referer) -> {
                int offset = Integer.parseInt(url.split("start=")[1].split("&")[0]);
                return page(offset, size, Math.min(20, size - offset), false);
            });
            assertEquals(size, client.fetch(ChartId.movie_hot_gaia).size());
        }
        for (String fault : new String[]{"short", "total", "offset", "error", "timeout"}) {
            var client = new DoubanChartClient(json, (url, referer) -> {
                int offset = Integer.parseInt(url.split("start=")[1].split("&")[0]);
                var response = page(offset, 120, 20, false);
                if (offset > 0) switch (fault) {
                    case "short" -> response.withArray("subject_collection_items").remove(0);
                    case "total" -> response.put("total", 121);
                    case "offset" -> response.put("start", 0);
                    case "error" -> response.put("code", 429);
                    case "timeout" -> throw new DoubanChartClient.ChartReadException("timeout");
                }
                return response;
            });
            assertThrows(DoubanChartClient.ChartReadException.class, () -> client.fetch(ChartId.movie_hot_gaia), fault);
        }
    }

    @Test void rejectsMissingPagesDuplicateIdsChangingTotalAndErrorObjects() {
        for (String fault : new String[]{"duplicate", "total", "offset", "rank", "short", "error"}) {
            var client = new DoubanChartClient(json, (url, referer) -> {
                int offset = Integer.parseInt(url.split("start=")[1].split("&")[0]);
                ObjectNode page = page(offset, 73, Math.min(50, 73-offset), true);
                if (offset > 0) switch (fault) {
                    case "duplicate" -> ((ObjectNode)page.path("subject_collection_items").get(0)).put("id", "1");
                    case "total" -> page.put("total", 74);
                    case "offset" -> page.put("start", 0);
                    case "rank" -> ((ObjectNode)page.path("subject_collection_items").get(0)).put("rank", 1);
                    case "short" -> page.withArray("subject_collection_items").remove(0);
                    case "error" -> { return json.createObjectNode().put("code", 403).put("msg", "challenge"); }
                }
                return page;
            });
            assertThrows(DoubanChartClient.ChartReadException.class, () -> client.fetch(ChartId.movie_top250), fault);
        }
    }

    @Test void summaryDoesNotInventCompletionOrSplitDisplayTitle() {
        var client = new DoubanChartClient(json, (url, referer) -> {
            var root = json.createObjectNode().put("r", 0);
            root.putObject("subject").put("id", "1").put("title", "电影 Movie (2020)")
                    .put("release_year", "2020").put("is_tv", false);
            return root;
        });
        var s = new com.medianexus.orchestrator.dto.douban.DoubanChartContract.Subject(
                1, "电影", null, null, null, "MOVIE", null, null, null, null, Instant.EPOCH);
        var fresh = client.refreshSubject(s);
        assertEquals("电影", fresh.title()); assertNull(fresh.originalTitle()); assertEquals(2020, fresh.year());
        assertNull(fresh.progress());
    }
    @Test void individualSeriesCheckUsesFreshProgressAndDoesNotReuseOldCompletion() {
        var result = new java.util.concurrent.atomic.AtomicReference<>("更新至2集");
        var client = new DoubanChartClient(json, (url, referer) -> {
            assertEquals("https://m.douban.com/rexxar/api/v2/tv/1", url);
            var response = json.createObjectNode().put("id", "1").put("type", "tv");
            if (result.get() != null) response.put("episodes_info", result.get());
            return response;
        });
        var old = new com.medianexus.orchestrator.dto.douban.DoubanChartContract.Subject(
                1,"测试剧",null,null,2025,"SERIES",null,null,1,"3集全",Instant.EPOCH);
        assertEquals("更新至2集",client.refreshSubject(old).progress());
        result.set(null);
        assertNull(client.refreshSubject(old).progress());
    }
    @Test void rankedChartsRequireExplicitSourceRankEvenWhenListOrderIsAvailable() {
        var client = new DoubanChartClient(json, (url, referer) -> page(0, 10, 10, false));
        assertThrows(DoubanChartClient.ChartReadException.class, () -> client.fetch(ChartId.movie_weekly_best));
    }


    @Test void readsBothWeeklySeriesChartsWithSourceRankYearAndProgress() {
        for (ChartId chart : new ChartId[]{ChartId.tv_chinese_best_weekly, ChartId.tv_global_best_weekly}) {
            var client = new DoubanChartClient(json, (url, referer) -> {
                assertTrue(url.contains("/" + chart.name() + "/items?"));
                var response = page(0, 10, 10, true);
                response.withArray("subject_collection_items").forEach(item -> ((ObjectNode)item)
                        .put("type", "tv").put("episodes_info", "12集全"));
                return response;
            });
            var subjects = client.fetch(chart);
            assertEquals(10, subjects.size());
            assertEquals("SERIES", subjects.get(0).mediaType());
            assertEquals("12集全", subjects.get(0).progress());
            assertEquals(10, subjects.get(9).rank());
            var missingRank = new DoubanChartClient(json, (url, referer) -> {
                var response = page(0, 10, 10, false);
                response.withArray("subject_collection_items").forEach(item -> ((ObjectNode)item).put("type", "tv"));
                return response;
            });
            assertThrows(DoubanChartClient.ChartReadException.class, () -> missingRank.fetch(chart));
        }
    }

    private ObjectNode page(int offset, int total, int count, boolean ranked) {
        var root = json.createObjectNode().put("start", offset).put("count", count).put("total", total);
        var items = root.putArray("subject_collection_items");
        for (int i = 1; i <= count; i++) {
            var item = items.addObject().put("id", String.valueOf(offset+i)).put("title", "电影 原名保持完整")
                    .put("type", "movie").put("year", "2020");
            item.putObject("cover").put("url", "https://img3.doubanio.com/view/photo/m_ratio_poster/public/p2935488630.jpg");
            if (ranked) item.put("rank", offset+i);
        }
        return root;
    }
}
