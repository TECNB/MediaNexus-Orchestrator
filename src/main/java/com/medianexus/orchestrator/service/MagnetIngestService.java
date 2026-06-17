package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.response.MovieMagnetIngestResponse;
import com.medianexus.orchestrator.dto.magnet.response.SeriesMagnetIngestResponse;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListDirectoryPrepareException;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.model.UserActionType;
import java.time.Year;
import java.util.Locale;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MagnetIngestService {

    private static final Logger log = LoggerFactory.getLogger(MagnetIngestService.class);
    private static final Pattern INVALID_PATH_CHAR_PATTERN = Pattern.compile("[\\\\/:*?\"<>|]+");
    private static final Pattern MULTIPLE_SPACE_PATTERN = Pattern.compile("\\s+");
    private static final int FIRST_MOVIE_YEAR = 1888;

    private final OpenListClient openListClient;
    private final OpenListProperties openListProperties;
    private final AuthService authService;
    private final UserActionQuotaService userActionQuotaService;

    public MagnetIngestService(
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            AuthService authService,
            UserActionQuotaService userActionQuotaService
    ) {
        this.openListClient = openListClient;
        this.openListProperties = openListProperties;
        this.authService = authService;
        this.userActionQuotaService = userActionQuotaService;
    }

    public MovieMagnetIngestResponse ingestMovie(MovieMagnetIngestRequest request) {
        User user = authService.requireCurrentUser();
        MovieIngestPlan plan = buildMoviePlan(request);
        userActionQuotaService.assertDailyContentCreateAvailable(user);

        String openListTaskId = null;
        try {
            openListClient.ensureDirectoryReady(plan.savePath(), plan.rootPath());
            openListTaskId = openListClient.addOfflineDownload(plan.savePath(), plan.magnet());
            userActionQuotaService.consumeDailyContentCreate(user, UserActionType.MAGNET_INGEST_CREATE);
            log.info(
                    "Movie magnet ingest submitted userId={} openListTaskId={} savePath={}",
                    user.getId(),
                    openListTaskId,
                    plan.savePath()
            );
            return new MovieMagnetIngestResponse(plan.savePath());
        } catch (BusinessException exception) {
            if (StringUtils.hasText(openListTaskId)) {
                log.warn(
                        "Movie magnet ingest submitted but post-submit business step failed userId={} openListTaskId={} savePath={}",
                        user.getId(),
                        openListTaskId,
                        plan.savePath(),
                        exception
                );
            }
            throw exception;
        } catch (OpenListDirectoryPrepareException exception) {
            throw mapDirectoryPrepareException(exception, "OpenList 电影基础路径不存在");
        } catch (OpenListClientException exception) {
            log.warn("Movie magnet ingest OpenList submit failed savePath={}", plan.savePath(), exception);
            throw internalError("创建离线下载任务失败");
        }
    }

    public SeriesMagnetIngestResponse ingestSeries(SeriesMagnetIngestRequest request) {
        User user = authService.requireCurrentUser();
        SeriesIngestPlan plan = buildSeriesPlan(request);
        userActionQuotaService.assertDailyContentCreateAvailable(user);

        String openListTaskId = null;
        try {
            openListClient.ensureDirectoryReady(plan.savePath(), plan.rootPath());
            openListTaskId = openListClient.addOfflineDownload(plan.savePath(), plan.magnet());
            userActionQuotaService.consumeDailyContentCreate(user, UserActionType.MAGNET_INGEST_CREATE);
            log.info(
                    "Series magnet ingest submitted userId={} openListTaskId={} savePath={}",
                    user.getId(),
                    openListTaskId,
                    plan.savePath()
            );
            return new SeriesMagnetIngestResponse(
                    plan.savePath(),
                    plan.seriesName(),
                    plan.seasonFolder()
            );
        } catch (BusinessException exception) {
            if (StringUtils.hasText(openListTaskId)) {
                log.warn(
                        "Series magnet ingest submitted but post-submit business step failed userId={} openListTaskId={} savePath={}",
                        user.getId(),
                        openListTaskId,
                        plan.savePath(),
                        exception
                );
            }
            throw exception;
        } catch (OpenListDirectoryPrepareException exception) {
            throw mapDirectoryPrepareException(exception, "OpenList 剧集基础路径不存在");
        } catch (OpenListClientException exception) {
            log.warn("Series magnet ingest OpenList submit failed savePath={}", plan.savePath(), exception);
            throw internalError("创建剧集离线下载任务失败");
        }
    }

    private MovieIngestPlan buildMoviePlan(MovieMagnetIngestRequest request) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        String magnet = normalizeMagnet(request.magnet());
        int year = validateMovieYear(request.year());
        String cleanTitle = sanitizeTitle(preferredTitle(request.title(), request.originalTitle()));
        if (!StringUtils.hasText(cleanTitle)) {
            throw badRequest("电影标题不能为空");
        }
        String rootPath = configuredRootPath(
                openListProperties.getMovieRootPath(),
                "OpenList 电影基础路径尚未配置"
        );
        String folderName = cleanTitle + " (" + year + ")";
        return new MovieIngestPlan(
                magnet,
                rootPath,
                openListClient.joinPath(rootPath, folderName)
        );
    }

    private SeriesIngestPlan buildSeriesPlan(SeriesMagnetIngestRequest request) {
        if (request == null) {
            throw badRequest("请求不能为空");
        }
        String magnet = normalizeMagnet(request.magnet());
        int seasonNumber = validateSeasonNumber(request.seasonNumber());
        String seriesName = sanitizeTitle(preferredTitle(request.title(), request.originalTitle()));
        if (!StringUtils.hasText(seriesName)) {
            throw badRequest("剧集标题不能为空");
        }
        String rootPath = configuredRootPath(
                openListProperties.getTvRootPath(),
                "OpenList 剧集基础路径尚未配置"
        );
        String seasonFolder = "Season " + String.format(Locale.ROOT, "%02d", seasonNumber);
        String savePath = openListClient.joinPath(openListClient.joinPath(rootPath, seriesName), seasonFolder);
        return new SeriesIngestPlan(magnet, rootPath, savePath, seriesName, seasonFolder);
    }

    private String normalizeMagnet(String magnet) {
        String normalized = magnet == null ? "" : magnet.trim();
        if (!normalized.startsWith("magnet:?")) {
            throw badRequest("magnet 链接需以 magnet:? 开头");
        }
        return normalized;
    }

    private int validateMovieYear(Integer year) {
        int maxYear = Year.now().getValue() + 2;
        if (year == null || year < FIRST_MOVIE_YEAR || year > maxYear) {
            throw badRequest("年份无效");
        }
        return year;
    }

    private int validateSeasonNumber(Integer seasonNumber) {
        if (seasonNumber == null || seasonNumber < 1) {
            throw badRequest("季数无效");
        }
        return seasonNumber;
    }

    private String preferredTitle(String title, String originalTitle) {
        String normalizedOriginalTitle = normalizeOptionalText(originalTitle);
        if (StringUtils.hasText(normalizedOriginalTitle)) {
            return normalizedOriginalTitle;
        }
        String normalizedTitle = normalizeOptionalText(title);
        return StringUtils.hasText(normalizedTitle) ? normalizedTitle : "";
    }

    private String sanitizeTitle(String title) {
        String cleanedTitle = INVALID_PATH_CHAR_PATTERN.matcher(title.trim()).replaceAll(" ");
        cleanedTitle = MULTIPLE_SPACE_PATTERN.matcher(cleanedTitle).replaceAll(" ");
        return stripSpacesAndDots(cleanedTitle);
    }

    private String stripSpacesAndDots(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpaceOrDot(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpaceOrDot(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private boolean isSpaceOrDot(char value) {
        return value == ' ' || value == '.';
    }

    private String configuredRootPath(String configuredPath, String missingMessage) {
        String cleanedPath = cleanConfigValue(configuredPath);
        if (!StringUtils.hasText(cleanedPath)) {
            throw serviceUnavailable(missingMessage);
        }
        return openListClient.normalizePath(cleanedPath);
    }

    private String normalizeOptionalText(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim();
        return StringUtils.hasText(normalized) ? normalized : null;
    }

    private String cleanConfigValue(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private BusinessException mapDirectoryPrepareException(
            OpenListDirectoryPrepareException exception,
            String missingRootMessage
    ) {
        if (exception.getReason() == OpenListDirectoryPrepareException.Reason.ROOT_NOT_FOUND) {
            return serviceUnavailable(missingRootMessage);
        }
        if (exception.getReason() == OpenListDirectoryPrepareException.Reason.PATH_OUTSIDE_ROOT) {
            return internalError("OpenList 路径配置无效");
        }
        return new BusinessException(
                ErrorCode.INTERNAL_ERROR,
                "OpenList 目标目录创建失败",
                HttpStatus.BAD_GATEWAY
        );
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

    private record MovieIngestPlan(String magnet, String rootPath, String savePath) {
    }

    private record SeriesIngestPlan(
            String magnet,
            String rootPath,
            String savePath,
            String seriesName,
            String seasonFolder
    ) {
    }
}
