package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.anime.response.AnimeSearchItem;
import com.medianexus.orchestrator.dto.anime.response.AnimeSearchResponse;
import com.medianexus.orchestrator.integration.anirss.AniRssClient;
import com.medianexus.orchestrator.integration.anirss.AniRssClientException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AnimeSearchService {

    private static final Logger log = LoggerFactory.getLogger(AnimeSearchService.class);
    private static final Pattern MIKAN_URL_ID_PATTERN = Pattern.compile("(\\d+)/?$");
    private static final String SEARCH_FAILED_MESSAGE = "动漫搜索失败，请稍后重试";

    private final AniRssClient aniRssClient;

    public AnimeSearchService(AniRssClient aniRssClient) {
        this.aniRssClient = aniRssClient;
    }

    public AnimeSearchResponse search(String term) {
        if (!StringUtils.hasText(term)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "搜索关键词不能为空");
        }

        try {
            List<AnimeSearchItem> items = flatten(aniRssClient.searchMikan(term.trim()));
            return new AnimeSearchResponse(items, items.size());
        } catch (AniRssClientException exception) {
            log.warn("Anime search upstream request failed");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, SEARCH_FAILED_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (RuntimeException exception) {
            log.warn("Anime search response mapping failed");
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, SEARCH_FAILED_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<AnimeSearchItem> flatten(JsonNode mikanNode) {
        List<AnimeSearchItem> results = new ArrayList<>();
        JsonNode weeks = mikanNode == null ? null : mikanNode.get("weeks");
        if (weeks == null || !weeks.isArray()) {
            return results;
        }

        for (JsonNode week : weeks) {
            String weekLabel = textOrNull(week.get("weekLabel"));
            JsonNode items = week.get("items");
            if (items == null || !items.isArray()) {
                continue;
            }
            for (JsonNode item : items) {
                String title = titleOrFallback(item.get("title"));
                String sourceUrl = textOrNull(item.get("url"));
                results.add(new AnimeSearchItem(
                        buildId(sourceUrl, title, weekLabel),
                        title,
                        textOrNull(item.get("cover")),
                        sourceUrl,
                        doubleOrNull(item.get("score")),
                        booleanOrFalse(item.get("exists")),
                        weekLabel
                ));
            }
        }
        return results;
    }

    private String buildId(String sourceUrl, String title, String weekLabel) {
        if (StringUtils.hasText(sourceUrl)) {
            Matcher matcher = MIKAN_URL_ID_PATTERN.matcher(sourceUrl.trim());
            if (matcher.find()) {
                return "mikan:" + matcher.group(1);
            }
        }
        return "mikan:" + stableHash(sourceUrl + "|" + title + "|" + weekLabel);
    }

    private String stableHash(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private String titleOrFallback(JsonNode node) {
        String title = textOrNull(node);
        if (StringUtils.hasText(title)) {
            return title;
        }
        return "未知番剧";
    }

    private String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return value.trim();
    }

    private Double doubleOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isNumber()) {
            return null;
        }
        return node.asDouble();
    }

    private Boolean booleanOrFalse(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        return node.asBoolean(false);
    }
}
