package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.resources.response.MediaLibraryPresenceResponse;
import com.medianexus.orchestrator.integration.emby.EmbyCatalogItem;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MediaLibraryPresenceService {

    private static final Logger log = LoggerFactory.getLogger(MediaLibraryPresenceService.class);

    private final EmbyClient embyClient;
    private final AnimeMagnetSearchService animeMagnetSearchService;
    private final AuthService authService;

    public MediaLibraryPresenceService(
            EmbyClient embyClient,
            AnimeMagnetSearchService animeMagnetSearchService,
            AuthService authService
    ) {
        this.embyClient = embyClient;
        this.animeMagnetSearchService = animeMagnetSearchService;
        this.authService = authService;
    }

    public MediaLibraryPresenceResponse check(
            String mediaType,
            Integer tmdbId,
            String bgmId,
            Integer seasonNumber
    ) {
        authService.requireCurrentUser();
        String normalizedMediaType = normalizeMediaType(mediaType);
        Integer resolvedTmdbId = resolveTmdbId(tmdbId, bgmId);
        if (resolvedTmdbId == null) {
            return new MediaLibraryPresenceResponse(false, false, null, null, seasonNumber);
        }

        try {
            if ("movie".equals(normalizedMediaType)) {
                List<EmbyCatalogItem> movies = embyClient.findMoviesByTmdbId(resolvedTmdbId);
                EmbyCatalogItem match = movies.stream().findFirst().orElse(null);
                return new MediaLibraryPresenceResponse(
                        true,
                        match != null,
                        resolvedTmdbId,
                        match == null ? null : match.name(),
                        null
                );
            }

            int selectedSeason = validateSeasonNumber(seasonNumber);
            for (EmbyCatalogItem series : embyClient.findSeriesByTmdbId(resolvedTmdbId)) {
                boolean seasonExists = embyClient.listSeriesSeasons(series.id()).stream()
                        .anyMatch(season -> Integer.valueOf(selectedSeason).equals(season.indexNumber()));
                if (seasonExists) {
                    return new MediaLibraryPresenceResponse(
                            true,
                            true,
                            resolvedTmdbId,
                            series.name(),
                            selectedSeason
                    );
                }
            }
            return new MediaLibraryPresenceResponse(true, false, resolvedTmdbId, null, selectedSeason);
        } catch (EmbyClientException exception) {
            log.warn(
                    "Emby media presence check unavailable mediaType={} tmdbId={} seasonNumber={} reason={}",
                    normalizedMediaType,
                    resolvedTmdbId,
                    seasonNumber,
                    exception.getMessage()
            );
            return new MediaLibraryPresenceResponse(false, false, resolvedTmdbId, null, seasonNumber);
        }
    }

    public void requireMovieAbsent(Integer tmdbId) {
        rejectExisting(check("movie", tmdbId, null, null));
    }

    public void requireSeriesSeasonAbsent(Integer tmdbId, Integer seasonNumber) {
        rejectExisting(check("series", tmdbId, null, seasonNumber));
    }

    public void requireAnimeSeasonAbsent(Integer tmdbId, String bgmId, Integer seasonNumber) {
        rejectExisting(check("series", tmdbId, bgmId, seasonNumber));
    }

    private void rejectExisting(MediaLibraryPresenceResponse presence) {
        if (!presence.exists()) {
            return;
        }

        String title = StringUtils.hasText(presence.matchedTitle())
                ? "《" + presence.matchedTitle().trim() + "》"
                : "该媒体";
        String target = presence.seasonNumber() == null
                ? title
                : title + "第 " + presence.seasonNumber() + " 季";
        throw new BusinessException(
                ErrorCode.CONFLICT,
                "Emby 媒体库中已存在" + target + "，禁止重复入库",
                HttpStatus.CONFLICT
        );
    }

    private String normalizeMediaType(String mediaType) {
        String normalized = StringUtils.hasText(mediaType)
                ? mediaType.trim().toLowerCase(Locale.ROOT)
                : "";
        if (!"movie".equals(normalized) && !"series".equals(normalized)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "媒体类型只能是 movie 或 series");
        }
        return normalized;
    }

    private Integer resolveTmdbId(Integer tmdbId, String bgmId) {
        if (tmdbId != null) {
            if (tmdbId <= 0) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "TMDB id 必须大于 0");
            }
            return tmdbId;
        }
        return StringUtils.hasText(bgmId)
                ? animeMagnetSearchService.resolveTmdbId(bgmId.trim())
                : null;
    }

    private int validateSeasonNumber(Integer seasonNumber) {
        if (seasonNumber == null || seasonNumber < 1) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "季数无效");
        }
        return seasonNumber;
    }
}
