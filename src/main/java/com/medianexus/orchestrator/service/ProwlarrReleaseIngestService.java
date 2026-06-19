package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestTaskResponse;
import com.medianexus.orchestrator.dto.resources.request.MovieOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseSearchResponse;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClient;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrClientException;
import com.medianexus.orchestrator.integration.prowlarr.ProwlarrRelease;
import com.medianexus.orchestrator.service.ReleaseTitleTagParser.ReleaseTitleTags;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class ProwlarrReleaseIngestService {

    private static final Logger log = LoggerFactory.getLogger(ProwlarrReleaseIngestService.class);
    private static final List<String> RESOLUTION_TAGS = List.of("2160p", "1080p", "720p");
    private static final List<String> DYNAMIC_RANGE_TAGS =
            List.of("dolby_vision", "hdr10_plus", "hdr10", "hdr", "hlg", "sdr");

    private final AuthService authService;
    private final ProwlarrClient prowlarrClient;
    private final ReleaseTitleTagParser tagParser;
    private final MagnetIngestService magnetIngestService;

    public ProwlarrReleaseIngestService(
            AuthService authService,
            ProwlarrClient prowlarrClient,
            ReleaseTitleTagParser tagParser,
            MagnetIngestService magnetIngestService
    ) {
        this.authService = authService;
        this.prowlarrClient = prowlarrClient;
        this.tagParser = tagParser;
        this.magnetIngestService = magnetIngestService;
    }

    public ProwlarrReleaseSearchResponse searchReleases(
            String term,
            String mediaType,
            Integer seasonNumber
    ) {
        authService.requireCurrentUser();
        String normalizedMediaType = requiredText(mediaType, "媒体类型不能为空").toLowerCase(Locale.ROOT);
        String query;
        if ("movie".equals(normalizedMediaType)) {
            query = movieQuery(term);
        } else if ("series".equals(normalizedMediaType)) {
            query = seriesQuery(term, validateSeasonNumber(seasonNumber));
        } else {
            throw badRequest("不支持的媒体类型");
        }

        List<ProwlarrReleaseItemResponse> items = search(query).stream()
                .map(release -> {
                    ReleaseTitleTags tags = tagParser.parse(release.title());
                    return new ProwlarrReleaseItemResponse(
                            release.title(),
                            release.size(),
                            release.seeders(),
                            release.leechers(),
                            release.grabs(),
                            release.indexer(),
                            release.publishDate(),
                            release.indexerId(),
                            release.downloadRef(),
                            tags.resolutionTags(),
                            tags.dynamicRangeTags()
                    );
                })
                .toList();
        return new ProwlarrReleaseSearchResponse(query, items);
    }

    public MovieMagnetIngestTaskResponse ingestMovie(MovieOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "电影标题不能为空");
        String quality = normalizeQuality(request == null ? null : request.quality());
        String query = movieQuery(request == null ? null : request.term());
        SelectedRelease selectedRelease = selectRelease(query, quality);
        String magnet = resolveMagnet(selectedRelease.release());
        return magnetIngestService.createMovieTask(
                new MovieMagnetIngestRequest(
                        magnet,
                        title,
                        trimToNull(request.originalTitle()),
                        request.year()
                ),
                metadata(selectedRelease.release(), selectedRelease.tags())
        );
    }

    public SeriesMagnetIngestTaskResponse ingestSeries(SeriesOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "剧集标题不能为空");
        String quality = normalizeQuality(request == null ? null : request.quality());
        int seasonNumber = validateSeasonNumber(request == null ? null : request.seasonNumber());
        String query = seriesQuery(request == null ? null : request.term(), seasonNumber);
        SelectedRelease selectedRelease = selectRelease(query, quality);
        String magnet = resolveMagnet(selectedRelease.release());
        return magnetIngestService.createSeriesTask(
                new SeriesMagnetIngestRequest(
                        magnet,
                        title,
                        trimToNull(request.originalTitle()),
                        seasonNumber
                ),
                metadata(selectedRelease.release(), selectedRelease.tags())
        );
    }

    public MovieMagnetIngestTaskResponse ingestSelectedMovie(MovieReleaseOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "电影标题不能为空");
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        int year = validateYear(request == null ? null : request.year());
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createMovieTask(
                new MovieMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()), year),
                requestMetadata(
                        releaseTitle,
                        request.indexer(),
                        request.size(),
                        request.indexerId(),
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                )
        );
    }

    public SeriesMagnetIngestTaskResponse ingestSelectedSeries(SeriesReleaseOpenListIngestRequest request) {
        authService.requireCurrentUser();
        String title = requiredText(request == null ? null : request.title(), "剧集标题不能为空");
        String releaseTitle = requiredText(request == null ? null : request.releaseTitle(), "发布标题不能为空");
        int seasonNumber = validateSeasonNumber(request == null ? null : request.seasonNumber());
        String magnet = resolveMagnet(request.indexerId(), request.downloadRef(), releaseTitle);
        return magnetIngestService.createSeriesTask(
                new SeriesMagnetIngestRequest(magnet, title, trimToNull(request.originalTitle()), seasonNumber),
                requestMetadata(
                        releaseTitle,
                        request.indexer(),
                        request.size(),
                        request.indexerId(),
                        request.resolutionTags(),
                        request.dynamicRangeTags()
                )
        );
    }

    private SelectedRelease selectRelease(String query, String quality) {
        for (ProwlarrRelease release : search(query)) {
            ReleaseTitleTags tags = tagParser.parse(release.title());
            if (tags.resolutionTags().contains(quality)) {
                return new SelectedRelease(release, tags);
            }
        }
        throw badRequest("未找到匹配分辨率的发布资源");
    }

    private List<ProwlarrRelease> search(String query) {
        try {
            return prowlarrClient.search(query);
        } catch (ProwlarrClientException exception) {
            if (exception.getReason() == ProwlarrClientException.Reason.CONFIGURATION) {
                throw serviceUnavailable("Prowlarr 服务尚未配置");
            }
            log.warn("Prowlarr search failed query={} reason={}", logValue(query), exception.getMessage(), exception);
            throw internalError("Prowlarr 搜索失败，请稍后重试");
        } catch (IllegalArgumentException exception) {
            log.warn("Prowlarr search configuration has invalid URI query={}", logValue(query), exception);
            throw serviceUnavailable("Prowlarr 服务尚未配置");
        }
    }

    private String resolveMagnet(ProwlarrRelease release) {
        return resolveMagnet(release.indexerId(), release.downloadRef(), release.title());
    }

    private String resolveMagnet(Integer indexerId, String downloadRef, String releaseTitle) {
        try {
            return prowlarrClient.resolveMagnet(indexerId, downloadRef, releaseTitle);
        } catch (ProwlarrClientException exception) {
            if (exception.getReason() == ProwlarrClientException.Reason.CONFIGURATION) {
                throw serviceUnavailable("Prowlarr 服务尚未配置");
            }
            log.warn(
                    "Prowlarr release magnet resolve failed title={} indexerId={} reason={}",
                    logValue(releaseTitle),
                    indexerId,
                    exception.getMessage()
            );
            throw badRequest("发布资源无法解析为 magnet 链接");
        } catch (IllegalArgumentException exception) {
            log.warn("Prowlarr release reference has invalid URI title={}", logValue(releaseTitle), exception);
            throw badRequest("发布资源无法解析为 magnet 链接");
        }
    }

    private ReleaseIngestMetadata metadata(ProwlarrRelease release, ReleaseTitleTags tags) {
        return new ReleaseIngestMetadata(
                "PROWLARR_RELEASE",
                release.title(),
                release.indexer(),
                release.size(),
                release.indexerId(),
                release.guid(),
                tags.resolutionTags(),
                tags.dynamicRangeTags()
        );
    }

    private ReleaseIngestMetadata requestMetadata(
            String releaseTitle,
            String indexer,
            Long size,
            Integer indexerId,
            List<String> resolutionTags,
            List<String> dynamicRangeTags
    ) {
        return new ReleaseIngestMetadata(
                "PROWLARR_RELEASE",
                releaseTitle,
                trimToNull(indexer),
                size,
                indexerId,
                null,
                knownTags(resolutionTags, RESOLUTION_TAGS),
                knownTags(dynamicRangeTags, DYNAMIC_RANGE_TAGS)
        );
    }

    private List<String> knownTags(List<String> tags, List<String> allowed) {
        if (tags == null || tags.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String tag : tags) {
            String value = trimToNull(tag);
            if (value != null && allowed.contains(value)) {
                normalized.add(value);
            }
        }
        return List.copyOf(normalized);
    }

    private String movieQuery(String term) {
        return requiredText(term, "搜索关键词不能为空");
    }

    private String seriesQuery(String term, int seasonNumber) {
        return requiredText(term, "搜索关键词不能为空") + " S" + String.format("%02d", seasonNumber);
    }

    private String normalizeQuality(String quality) {
        String normalized = requiredText(quality, "请选择分辨率").toLowerCase(Locale.ROOT);
        if (!RESOLUTION_TAGS.contains(normalized)) {
            throw badRequest("不支持的分辨率标签");
        }
        return normalized;
    }

    private int validateYear(Integer year) {
        if (year == null || year < 1888) {
            throw badRequest("电影年份无效");
        }
        return year;
    }

    private int validateSeasonNumber(Integer seasonNumber) {
        if (seasonNumber == null || seasonNumber < 1) {
            throw badRequest("季编号必须大于 0");
        }
        return seasonNumber;
    }

    private String requiredText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw badRequest(message);
        }
        return value.trim();
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String logValue(String value) {
        String cleaned = value == null ? "" : value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return cleaned.length() <= 80 ? cleaned : cleaned.substring(0, 80);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    private BusinessException serviceUnavailable(String message) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private BusinessException internalError(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private record SelectedRelease(ProwlarrRelease release, ReleaseTitleTags tags) {
    }
}
