package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.magnet.AnimeMagnetSearchItem;
import com.medianexus.orchestrator.dto.magnet.AnimeMagnetSearchResponse;
import com.medianexus.orchestrator.integration.anirss.AniRssClient;
import com.medianexus.orchestrator.integration.anirss.AniRssClientException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AnimeMagnetSearchService {

    private static final Logger log = LoggerFactory.getLogger(AnimeMagnetSearchService.class);
    private static final String SEARCH_FAILED_MESSAGE = "动漫搜索失败，请稍后重试";
    private static final String BGM_SUBJECT_URL_PREFIX = "https://bgm.tv/subject/";

    private final AniRssClient aniRssClient;

    public AnimeMagnetSearchService(AniRssClient aniRssClient) {
        this.aniRssClient = aniRssClient;
    }

    public AnimeMagnetSearchResponse search(String term) {
        if (!StringUtils.hasText(term)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "搜索关键词不能为空");
        }

        try {
            JsonNode bgmNode = aniRssClient.searchBgm(term.trim());
            log.debug("Anime magnet search upstream response shape: array={}, size={}",
                    bgmNode != null && bgmNode.isArray(),
                    bgmNode != null && bgmNode.isArray() ? bgmNode.size() : null);
            List<AnimeMagnetSearchItem> items = mapItems(bgmNode);
            return new AnimeMagnetSearchResponse(items, items.size());
        } catch (AniRssClientException exception) {
            log.warn("Anime magnet search upstream request failed: {}", exception.getMessage(), exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, SEARCH_FAILED_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        } catch (RuntimeException exception) {
            log.warn("Anime magnet search response mapping failed: {}", exception.getMessage(), exception);
            throw new BusinessException(ErrorCode.INTERNAL_ERROR, SEARCH_FAILED_MESSAGE, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private List<AnimeMagnetSearchItem> mapItems(JsonNode bgmNode) {
        List<AnimeMagnetSearchItem> results = new ArrayList<>();
        if (bgmNode == null || !bgmNode.isArray()) {
            return results;
        }

        for (JsonNode item : bgmNode) {
            String bgmId = textOrNull(item.get("id"));
            if (!StringUtils.hasText(bgmId)) {
                continue;
            }

            String nameCn = textOrNull(item.get("nameCn"));
            if (!StringUtils.hasText(nameCn)) {
                nameCn = textOrNull(item.get("name_cn"));
            }
            String name = textOrNull(item.get("name"));
            String title = StringUtils.hasText(nameCn) ? nameCn : name;
            if (!StringUtils.hasText(title)) {
                title = "未知番剧";
            }

            results.add(new AnimeMagnetSearchItem(
                    "bgm:" + bgmId,
                    bgmId,
                    bgmUrl(item, bgmId),
                    title,
                    nameCn,
                    name,
                    cover(item.get("images")),
                    doubleOrNull(path(item, "rating", "score")),
                    integerOrNull(item.get("eps")),
                    textOrNull(item.get("date")),
                    integerOrNull(item.get("season")),
                    textOrNull(item.get("platform"))
            ));
        }

        return results;
    }

    private String bgmUrl(JsonNode item, String bgmId) {
        String url = textOrNull(item.get("url"));
        if (StringUtils.hasText(url)) {
            return url;
        }
        return BGM_SUBJECT_URL_PREFIX + bgmId;
    }

    private String cover(JsonNode images) {
        if (images == null || images.isNull()) {
            return null;
        }

        String cover = textOrNull(images.get("large"));
        if (StringUtils.hasText(cover)) {
            return cover;
        }

        cover = textOrNull(images.get("medium"));
        if (StringUtils.hasText(cover)) {
            return cover;
        }

        cover = textOrNull(images.get("common"));
        if (StringUtils.hasText(cover)) {
            return cover;
        }

        return textOrNull(images.get("small"));
    }

    private JsonNode path(JsonNode node, String first, String second) {
        JsonNode child = node == null ? null : node.get(first);
        return child == null ? null : child.get(second);
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

    private Integer integerOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.canConvertToInt()) {
            return null;
        }
        return node.asInt();
    }
}
