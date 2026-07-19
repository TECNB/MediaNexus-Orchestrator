package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadListResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadLogListResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadLogResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadedFileResponse;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListClientException;
import com.medianexus.orchestrator.integration.openlist.OpenListFileInfo;
import com.medianexus.orchestrator.mapper.SubtitleUploadLogMapper;
import com.medianexus.orchestrator.mapper.SubtitleUploadMapper;
import com.medianexus.orchestrator.model.SubtitleUpload;
import com.medianexus.orchestrator.model.SubtitleUploadLog;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.ExtractedSubtitleEntry;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.ExtractedSubtitlePackage;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.StagedSubtitleUpload;
import com.medianexus.orchestrator.service.SubtitleFilenamePlanner.PlannedSubtitleFile;
import com.medianexus.orchestrator.service.SubtitleFilenamePlanner.SubtitleFilenamePlan;
import java.time.LocalDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SubtitleUploadService {

    private static final Logger log = LoggerFactory.getLogger(SubtitleUploadService.class);
    private static final int FIRST_MOVIE_YEAR = 1888;
    private static final String ADMIN_ROLE = "ADMIN";
    private static final Pattern SERIES_EPISODE_MARKER_PATTERN = Pattern.compile(
            "(?i)(?:s\\d{1,2}\\s*e\\d{1,3}|\\d{1,2}x\\d{1,3}|(?:ep|e)\\s*\\d{1,3}"
                    + "|第\\s*\\d{1,3}\\s*[集话話]|[\\[【]\\s*\\d{1,3}\\s*[\\]】])"
    );
    private static final TypeReference<List<ManifestFile>> MANIFEST_TYPE = new TypeReference<>() {
    };

    private final SubtitleUploadMapper subtitleUploadMapper;
    private final SubtitleUploadLogMapper subtitleUploadLogMapper;
    private final OpenListClient openListClient;
    private final OpenListProperties openListProperties;
    private final MovieSeriesFileRenameService renameService;
    private final AuthService authService;
    private final SubtitleArchiveExtractor archiveExtractor;
    private final SubtitleFilenamePlanner filenamePlanner;
    private final ObjectMapper objectMapper;
    private final AutoSymlinkRefreshService autoSymlinkRefreshService;
    private final ExecutorService executorService;

    @Autowired
    public SubtitleUploadService(
            SubtitleUploadMapper subtitleUploadMapper,
            SubtitleUploadLogMapper subtitleUploadLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            MovieSeriesFileRenameService renameService,
            AuthService authService,
            SubtitleArchiveExtractor archiveExtractor,
            SubtitleFilenamePlanner filenamePlanner,
            ObjectMapper objectMapper,
            AutoSymlinkRefreshService autoSymlinkRefreshService
    ) {
        this(
                subtitleUploadMapper,
                subtitleUploadLogMapper,
                openListClient,
                openListProperties,
                renameService,
                authService,
                archiveExtractor,
                filenamePlanner,
                objectMapper,
                autoSymlinkRefreshService,
                Executors.newSingleThreadExecutor(new SubtitleUploadThreadFactory())
        );
    }

    SubtitleUploadService(
            SubtitleUploadMapper subtitleUploadMapper,
            SubtitleUploadLogMapper subtitleUploadLogMapper,
            OpenListClient openListClient,
            OpenListProperties openListProperties,
            MovieSeriesFileRenameService renameService,
            AuthService authService,
            SubtitleArchiveExtractor archiveExtractor,
            SubtitleFilenamePlanner filenamePlanner,
            ObjectMapper objectMapper,
            AutoSymlinkRefreshService autoSymlinkRefreshService,
            ExecutorService executorService
    ) {
        this.subtitleUploadMapper = subtitleUploadMapper;
        this.subtitleUploadLogMapper = subtitleUploadLogMapper;
        this.openListClient = openListClient;
        this.openListProperties = openListProperties;
        this.renameService = renameService;
        this.authService = authService;
        this.archiveExtractor = archiveExtractor;
        this.filenamePlanner = filenamePlanner;
        this.objectMapper = objectMapper;
        this.autoSymlinkRefreshService = autoSymlinkRefreshService;
        this.executorService = executorService;
    }

    public SubtitleUploadResponse uploadMovieSubtitle(
            MultipartFile file,
            String title,
            String originalTitle,
            Integer year,
            boolean overwrite
    ) {
        return uploadSubtitle(file, "MOVIE", title, originalTitle, year, null, overwrite);
    }

    public SubtitleUploadResponse uploadSubtitle(
            MultipartFile file,
            String mediaType,
            String title,
            String originalTitle,
            Integer year,
            Integer seasonNumber,
            boolean overwrite
    ) {
        User user = authService.requireCurrentUser();
        MediaSubtitlePlan mediaPlan = buildMediaPlan(mediaType, title, originalTitle, year, seasonNumber);
        StagedSubtitleUpload stagedUpload = archiveExtractor.stage(file);
        String uploadId = UUID.randomUUID().toString();
        SubtitleUpload upload = null;
        try {
            upload = createUpload(uploadId, user, mediaPlan, safeOriginalFilename(file), overwrite);
            writeLog(
                    uploadId,
                    "INFO",
                    "created",
                    "已创建" + mediaTypeLabel(mediaPlan.mediaType()) + "字幕上传批次",
                    mediaPlan.targetPath()
            );
            SubtitleUploadResponse response = toResponse(upload);
            executorService.submit(() -> processUpload(uploadId, mediaPlan, stagedUpload, overwrite));
            return response;
        } catch (RuntimeException exception) {
            archiveExtractor.deleteQuietly(stagedUpload.workDir());
            if (upload != null) {
                markFailed(uploadId, "字幕后台任务提交失败");
                writeLog(uploadId, "ERROR", "failed", "字幕后台任务提交失败", safeMessage(exception));
            }
            throw exception;
        }
    }

    private void processUpload(
            String uploadId,
            MediaSubtitlePlan mediaPlan,
            StagedSubtitleUpload stagedUpload,
            boolean overwrite
    ) {
        ExtractedSubtitlePackage extractedPackage = null;
        List<String> uploadedNames = new ArrayList<>();
        try {
            markStage(uploadId, "PROCESSING", "extracting");
            writeLog(uploadId, "INFO", "extracting", "正在解析上传文件", stagedUpload.sourceFileName());
            extractedPackage = archiveExtractor.extract(stagedUpload);
            updateSource(uploadId, extractedPackage);
            writeLog(
                    uploadId,
                    "INFO",
                    "extracting",
                    "字幕文件解析完成",
                    "accepted=" + extractedPackage.entries().size()
                            + ", ignored=" + extractedPackage.ignoredEntries().size()
                            + ", archive=" + extractedPackage.archive()
            );
            for (String ignoredEntry : extractedPackage.ignoredEntries()) {
                writeLog(uploadId, "INFO", "extracting", "忽略 ZIP 中非字幕或嵌套压缩包条目", ignoredEntry);
            }

            markStage(uploadId, "PROCESSING", "target_checking");
            List<OpenListFileInfo> targetFiles = loadTargetFiles(uploadId, mediaPlan);

            markStage(uploadId, "PROCESSING", "planning");
            PreparedUploadPlan preparedPlan = "SERIES".equals(mediaPlan.mediaType())
                    ? prepareSeriesUploadPlan(uploadId, mediaPlan, targetFiles, extractedPackage.entries())
                    : prepareMovieUploadPlan(uploadId, mediaPlan, targetFiles, extractedPackage.entries());
            List<ManifestFile> manifest = manifestFiles(preparedPlan.files());
            ensureNoRemoteConflict(targetFiles, preparedPlan.files(), overwrite);
            updatePlan(uploadId, preparedPlan.selectedVideoName(), manifest);
            for (ManifestFile manifestFile : manifest) {
                writeLog(uploadId, "INFO", "planning", "规划字幕文件名", manifestFile.originalPath()
                        + " ==> " + manifestFile.finalName());
            }

            markStage(uploadId, "PROCESSING", "uploading");
            for (PlannedSubtitleFile plannedFile : preparedPlan.files()) {
                String remotePath = openListClient.joinPath(mediaPlan.targetPath(), plannedFile.finalName());
                writeLog(uploadId, "INFO", "uploading", "正在上传字幕文件", remotePath);
                openListClient.uploadFile(plannedFile.entry().localPath(), remotePath, overwrite);
                uploadedNames.add(plannedFile.finalName());
                writeLog(uploadId, "INFO", "uploading", "字幕文件上传完成", plannedFile.finalName());
            }

            markStage(uploadId, "PROCESSING", "refreshing_as");
            refreshAutoSymlink(uploadId, mediaPlan.mediaType());
            writeLog(uploadId, "INFO", "waiting_for_as", "字幕已写入目标目录，等待 AS 后续迁移", mediaPlan.targetPath());
            markSucceeded(uploadId, manifest.size());
        } catch (BusinessException exception) {
            markFailed(uploadId, exception.getMessage());
            writeLog(uploadId, "ERROR", "failed", "字幕上传失败", exception.getMessage());
        } catch (OpenListClientException exception) {
            cleanupUploadedFiles(uploadId, mediaPlan.targetPath(), uploadedNames);
            String message = "OpenList 上传失败: " + safeMessage(exception);
            markFailed(uploadId, message);
            writeLog(uploadId, "ERROR", "failed", "字幕上传失败", message);
        } catch (RuntimeException exception) {
            cleanupUploadedFiles(uploadId, mediaPlan.targetPath(), uploadedNames);
            log.warn("Subtitle upload failed uploadId={} mediaType={}", uploadId, mediaPlan.mediaType(), exception);
            String message = safeMessage(exception);
            markFailed(uploadId, message);
            writeLog(uploadId, "ERROR", "failed", "字幕上传失败", message);
        } finally {
            if (extractedPackage != null) {
                archiveExtractor.deleteQuietly(extractedPackage.workDir());
            } else {
                archiveExtractor.deleteQuietly(stagedUpload.workDir());
            }
        }
    }

    private void refreshAutoSymlink(String uploadId, String mediaType) {
        try {
            writeLog(uploadId, "INFO", "refreshing_as", "正在触发 AutoSymlink 刷新", null);
            AutoSymlinkRefreshService.RefreshOutcome outcome = "SERIES".equals(mediaType)
                    ? autoSymlinkRefreshService.refreshSeries()
                    : autoSymlinkRefreshService.refreshMovie();
            String level = outcome.status() == AutoSymlinkRefreshService.Status.SUBMITTED ? "INFO" : "WARN";
            writeLog(uploadId, level, "refreshing_as", outcome.message(), outcome.detail());
        } catch (Exception exception) {
            log.warn("Subtitle AutoSymlink refresh failed uploadId={} mediaType={}", uploadId, mediaType, exception);
            try {
                writeLog(uploadId, "WARN", "refreshing_as", "AutoSymlink 刷新任务提交失败，已跳过", null);
            } catch (Exception logException) {
                log.warn("Subtitle AutoSymlink refresh log write failed uploadId={}", uploadId, logException);
            }
        }
    }

    public SubtitleUploadListResponse listUploads() {
        User user = authService.requireCurrentUser();
        LambdaQueryWrapper<SubtitleUpload> queryWrapper = new LambdaQueryWrapper<SubtitleUpload>()
                .orderByDesc(SubtitleUpload::getCreatedAt)
                .last("LIMIT 20");
        if (!isAdmin(user)) {
            queryWrapper.eq(SubtitleUpload::getCreatedByUserId, user.getId());
        }
        List<SubtitleUploadResponse> items = subtitleUploadMapper.selectList(queryWrapper).stream()
                .map(this::toResponse)
                .toList();
        return new SubtitleUploadListResponse(items, items.size());
    }

    public SubtitleUploadResponse getUpload(String uploadId) {
        User user = authService.requireCurrentUser();
        return toResponse(getAccessibleUpload(uploadId, user));
    }

    public SubtitleUploadLogListResponse getUploadLogs(String uploadId) {
        User user = authService.requireCurrentUser();
        getAccessibleUpload(uploadId, user);
        List<SubtitleUploadLogResponse> items = subtitleUploadLogMapper.selectList(new LambdaQueryWrapper<SubtitleUploadLog>()
                        .eq(SubtitleUploadLog::getUploadId, uploadId)
                        .orderByAsc(SubtitleUploadLog::getId))
                .stream()
                .map(this::toLogResponse)
                .toList();
        return new SubtitleUploadLogListResponse(items, items.size());
    }

    private MediaSubtitlePlan buildMediaPlan(
            String mediaType,
            String title,
            String originalTitle,
            Integer year,
            Integer seasonNumber
    ) {
        String normalizedMediaType = normalizeMediaType(mediaType);
        if ("SERIES".equals(normalizedMediaType)) {
            return buildSeriesPlan(title, originalTitle, year, seasonNumber);
        }
        return buildMoviePlan(title, originalTitle, year);
    }

    private MediaSubtitlePlan buildMoviePlan(String title, String originalTitle, Integer year) {
        int releaseYear = validateMovieYear(year);
        String normalizedTitle = trimToNull(title);
        String normalizedOriginalTitle = trimToNull(originalTitle);
        String folderTitle = preferredTitle(normalizedTitle, normalizedOriginalTitle);
        String displayTitle = StringUtils.hasText(normalizedTitle) ? normalizedTitle : folderTitle;
        String rootPath = configuredRootPath(openListProperties.getMovieRootPath(), "OpenList 电影基础路径尚未配置");
        String folderName = renameService.movieFolderName(folderTitle, releaseYear);
        String targetPath = openListClient.joinPath(rootPath, folderName);
        return new MediaSubtitlePlan(
                "MOVIE",
                displayTitle,
                normalizedOriginalTitle,
                releaseYear,
                null,
                folderTitle,
                folderName,
                targetPath
        );
    }

    private MediaSubtitlePlan buildSeriesPlan(
            String title,
            String originalTitle,
            Integer year,
            Integer seasonNumber
    ) {
        int selectedSeasonNumber = validateSeasonNumber(seasonNumber);
        String normalizedTitle = trimToNull(title);
        String normalizedOriginalTitle = trimToNull(originalTitle);
        String folderTitle = preferredTitle(normalizedTitle, normalizedOriginalTitle);
        String displayTitle = StringUtils.hasText(normalizedTitle) ? normalizedTitle : folderTitle;
        String rootPath = configuredRootPath(openListProperties.getTvRootPath(), "OpenList 剧集基础路径尚未配置");
        String seriesFolderName = renameService.seriesFolderName(folderTitle);
        String seasonFolderName = renameService.seasonFolderName(selectedSeasonNumber);
        String targetPath = openListClient.joinPath(
                openListClient.joinPath(rootPath, seriesFolderName),
                seasonFolderName
        );
        return new MediaSubtitlePlan(
                "SERIES",
                displayTitle,
                normalizedOriginalTitle,
                validateOptionalYear(year),
                selectedSeasonNumber,
                folderTitle,
                seriesFolderName + "/" + seasonFolderName,
                targetPath
        );
    }

    private List<OpenListFileInfo> loadTargetFiles(String uploadId, MediaSubtitlePlan mediaPlan) {
        if (!openListClient.pathExists(mediaPlan.targetPath())) {
            throw notFound("未找到" + mediaTypeLabel(mediaPlan.mediaType()) + "目录: " + mediaPlan.targetPath());
        }
        writeLog(uploadId, "INFO", "target_checking", "已确认目标目录存在", mediaPlan.targetPath());
        return openListClient.listFiles(mediaPlan.targetPath());
    }

    private PreparedUploadPlan prepareMovieUploadPlan(
            String uploadId,
            MediaSubtitlePlan mediaPlan,
            List<OpenListFileInfo> targetFiles,
            List<ExtractedSubtitleEntry> subtitleEntries
    ) {
        OpenListFileInfo selectedVideo = selectMainVideo(targetFiles)
                .orElseThrow(() -> badRequest("目标目录没有可匹配的视频文件"));
        writeLog(uploadId, "INFO", "target_checking", "已选择目标视频作为字幕命名基准", selectedVideo.name());
        SubtitleFilenamePlan filenamePlan = filenamePlanner.plan(selectedVideo.name(), subtitleEntries);
        writeDiagnosticMatchLog(uploadId, mediaPlan, selectedVideo, filenamePlan);
        return new PreparedUploadPlan(selectedVideo.name(), filenamePlan.files());
    }

    private PreparedUploadPlan prepareSeriesUploadPlan(
            String uploadId,
            MediaSubtitlePlan mediaPlan,
            List<OpenListFileInfo> targetFiles,
            List<ExtractedSubtitleEntry> subtitleEntries
    ) {
        List<OpenListFileInfo> videoFiles = targetFiles.stream()
                .filter(file -> !Boolean.TRUE.equals(file.isDir()))
                .filter(file -> renameService.isVideo(file.name()))
                .toList();
        if (videoFiles.isEmpty()) {
            throw badRequest("Season 目录中没有可匹配的视频文件");
        }

        Map<Integer, OpenListFileInfo> videosByEpisode = new HashMap<>();
        for (OpenListFileInfo videoFile : videoFiles) {
            Optional<Integer> episodeNumber = renameService.episodeNumber(
                    videoFile.name(),
                    mediaPlan.seasonNumber()
            );
            if (episodeNumber.isEmpty()) {
                writeLog(uploadId, "WARN", "target_checking", "无法识别目标视频集数，暂不参与自动匹配", videoFile.name());
                continue;
            }
            OpenListFileInfo existing = videosByEpisode.get(episodeNumber.get());
            if (existing == null || fileSize(videoFile) > fileSize(existing)) {
                videosByEpisode.put(episodeNumber.get(), videoFile);
            }
        }

        List<SeriesSubtitleMatch> subtitleMatches = new ArrayList<>();
        Set<String> matchedVideoNames = new HashSet<>();
        for (ExtractedSubtitleEntry subtitleEntry : subtitleEntries) {
            Optional<Integer> episodeNumber = renameService.episodeNumber(
                    subtitleEntry.originalName(),
                    mediaPlan.seasonNumber()
            );
            OpenListFileInfo matchedVideo;
            if (episodeNumber.isPresent()) {
                matchedVideo = videosByEpisode.get(episodeNumber.get());
                if (matchedVideo == null && videoFiles.size() == 1 && videosByEpisode.isEmpty()) {
                    matchedVideo = videoFiles.get(0);
                    writeLog(
                            uploadId,
                            "WARN",
                            "planning",
                            "目标视频未识别出集数，因目录仅有一个视频而继续",
                            subtitleEntry.originalPath() + " ==> " + matchedVideo.name()
                    );
                } else if (matchedVideo == null) {
                    throw badRequest("目标 Season 目录中未找到第 " + episodeNumber.get()
                            + " 集视频: " + subtitleEntry.originalPath());
                }
            } else if (!hasSeriesEpisodeMarker(subtitleEntry.originalName())
                    && subtitleEntries.size() == 1
                    && videoFiles.size() == 1) {
                matchedVideo = videoFiles.get(0);
                writeLog(
                        uploadId,
                        "WARN",
                        "planning",
                        "字幕未识别出集数，因目标目录仅有一个视频而继续",
                        subtitleEntry.originalPath() + " ==> " + matchedVideo.name()
                );
            } else {
                throw badRequest("无法识别字幕集数: " + subtitleEntry.originalPath());
            }

            subtitleMatches.add(new SeriesSubtitleMatch(subtitleEntry, matchedVideo));
            matchedVideoNames.add(matchedVideo.name());
            writeLog(
                    uploadId,
                    "INFO",
                    "planning",
                    "已按集数匹配字幕与目标视频",
                    subtitleEntry.originalPath() + " ==> " + matchedVideo.name()
            );
        }

        Map<String, List<SeriesSubtitleMatch>> matchesByVideo = new LinkedHashMap<>();
        for (SeriesSubtitleMatch match : subtitleMatches) {
            matchesByVideo.computeIfAbsent(match.video().name(), ignored -> new ArrayList<>()).add(match);
        }
        List<PlannedSubtitleFile> plannedFiles = new ArrayList<>();
        for (List<SeriesSubtitleMatch> matches : matchesByVideo.values()) {
            OpenListFileInfo video = matches.get(0).video();
            List<ExtractedSubtitleEntry> episodeEntries = matches.stream()
                    .map(SeriesSubtitleMatch::entry)
                    .toList();
            plannedFiles.addAll(filenamePlanner.plan(video.name(), episodeEntries).files());
        }
        filenamePlanner.validateNoDuplicateFinalNames(plannedFiles);
        String selectedVideoName = matchedVideoNames.size() == 1
                ? matchedVideoNames.iterator().next()
                : "已匹配 " + matchedVideoNames.size() + " 个分集视频";
        return new PreparedUploadPlan(selectedVideoName, List.copyOf(plannedFiles));
    }

    private Optional<OpenListFileInfo> selectMainVideo(List<OpenListFileInfo> files) {
        return files.stream()
                .filter(file -> !Boolean.TRUE.equals(file.isDir()))
                .filter(file -> renameService.isVideo(file.name()))
                .max(Comparator.comparing(file -> file.size() == null ? 0L : file.size()));
    }

    private void ensureNoRemoteConflict(
            List<OpenListFileInfo> targetFiles,
            List<PlannedSubtitleFile> plannedFiles,
            boolean overwrite
    ) {
        Set<String> remoteNames = targetFiles.stream()
                .map(OpenListFileInfo::name)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);
        List<String> conflicts = plannedFiles.stream()
                .map(PlannedSubtitleFile::finalName)
                .filter(remoteNames::contains)
                .toList();
        if (!conflicts.isEmpty() && !overwrite) {
            throw conflict("目标目录已存在同名字幕: " + String.join(", ", conflicts));
        }
    }

    private void writeDiagnosticMatchLog(
            String uploadId,
            MediaSubtitlePlan mediaPlan,
            OpenListFileInfo selectedVideo,
            SubtitleFilenamePlan filenamePlan
    ) {
        String detectedPrefix = filenamePlan.detectedPrefix();
        if (!StringUtils.hasText(detectedPrefix)) {
            return;
        }
        String detected = filenamePlanner.normalizeDiagnosticName(detectedPrefix);
        List<String> candidates = List.of(
                mediaPlan.title(),
                mediaPlan.originalTitle() == null ? "" : mediaPlan.originalTitle(),
                mediaPlan.folderName(),
                mainName(selectedVideo.name())
        );
        boolean matched = candidates.stream()
                .map(filenamePlanner::normalizeDiagnosticName)
                .filter(StringUtils::hasText)
                .anyMatch(candidate -> candidate.equals(detected)
                        || candidate.contains(detected)
                        || detected.contains(candidate));
        if (matched) {
            writeLog(uploadId, "INFO", "planning", "字幕源文件名前缀与目标信息匹配", detectedPrefix);
            return;
        }
        writeLog(uploadId, "WARN", "planning", "字幕源文件名前缀与目标信息不完全匹配，仍按用户选择继续", detectedPrefix);
    }

    private SubtitleUpload createUpload(
            String uploadId,
            User user,
            MediaSubtitlePlan mediaPlan,
            String sourceFileName,
            boolean overwrite
    ) {
        LocalDateTime now = LocalDateTime.now();
        SubtitleUpload upload = new SubtitleUpload();
        upload.setId(uploadId);
        upload.setCreatedByUserId(user.getId());
        upload.setMediaType(mediaPlan.mediaType());
        upload.setStatus("PROCESSING");
        upload.setStage("created");
        upload.setTitle(mediaPlan.title());
        upload.setOriginalTitle(mediaPlan.originalTitle());
        upload.setYear(mediaPlan.year());
        upload.setSeasonNumber(mediaPlan.seasonNumber());
        upload.setTargetPath(mediaPlan.targetPath());
        upload.setSourceFileName(sourceFileName);
        upload.setFileCount(0);
        upload.setOverwriteEnabled(overwrite);
        upload.setCreatedAt(now);
        upload.setUpdatedAt(now);
        subtitleUploadMapper.insert(upload);
        return upload;
    }

    private void updateSource(String uploadId, ExtractedSubtitlePackage extractedPackage) {
        subtitleUploadMapper.update(new LambdaUpdateWrapper<SubtitleUpload>()
                .eq(SubtitleUpload::getId, uploadId)
                .set(SubtitleUpload::getSourceFileName, extractedPackage.sourceFileName())
                .set(SubtitleUpload::getSourceSize, extractedPackage.sourceSize())
                .set(SubtitleUpload::getSourceSha256, extractedPackage.sourceSha256())
                .set(SubtitleUpload::getFileCount, extractedPackage.entries().size())
                .set(SubtitleUpload::getUpdatedAt, LocalDateTime.now()));
    }

    private void updatePlan(String uploadId, String selectedVideoName, List<ManifestFile> manifest) {
        subtitleUploadMapper.update(new LambdaUpdateWrapper<SubtitleUpload>()
                .eq(SubtitleUpload::getId, uploadId)
                .set(SubtitleUpload::getSelectedVideoName, selectedVideoName)
                .set(SubtitleUpload::getFileManifest, serializeManifest(manifest))
                .set(SubtitleUpload::getFileCount, manifest.size())
                .set(SubtitleUpload::getUpdatedAt, LocalDateTime.now()));
    }

    private void markStage(String uploadId, String status, String stage) {
        subtitleUploadMapper.update(new LambdaUpdateWrapper<SubtitleUpload>()
                .eq(SubtitleUpload::getId, uploadId)
                .set(SubtitleUpload::getStatus, status)
                .set(SubtitleUpload::getStage, stage)
                .set(SubtitleUpload::getUpdatedAt, LocalDateTime.now()));
    }

    private void markSucceeded(String uploadId, int fileCount) {
        subtitleUploadMapper.update(new LambdaUpdateWrapper<SubtitleUpload>()
                .eq(SubtitleUpload::getId, uploadId)
                .set(SubtitleUpload::getStatus, "WAITING_FOR_AS")
                .set(SubtitleUpload::getStage, "waiting_for_as")
                .set(SubtitleUpload::getFileCount, fileCount)
                .set(SubtitleUpload::getErrorMessage, null)
                .set(SubtitleUpload::getUpdatedAt, LocalDateTime.now())
                .set(SubtitleUpload::getFinishedAt, LocalDateTime.now()));
    }

    private void markFailed(String uploadId, String errorMessage) {
        subtitleUploadMapper.update(new LambdaUpdateWrapper<SubtitleUpload>()
                .eq(SubtitleUpload::getId, uploadId)
                .set(SubtitleUpload::getStatus, "FAILED")
                .set(SubtitleUpload::getStage, "failed")
                .set(SubtitleUpload::getErrorMessage, truncate(errorMessage, 1000))
                .set(SubtitleUpload::getUpdatedAt, LocalDateTime.now())
                .set(SubtitleUpload::getFinishedAt, LocalDateTime.now()));
    }

    private void cleanupUploadedFiles(String uploadId, String targetPath, List<String> uploadedNames) {
        if (uploadedNames.isEmpty()) {
            return;
        }
        try {
            openListClient.remove(targetPath, uploadedNames);
            writeLog(uploadId, "WARN", "failed", "已回滚本批次已上传字幕文件", String.join(", ", uploadedNames));
        } catch (RuntimeException cleanupException) {
            writeLog(uploadId, "WARN", "failed", "回滚本批次已上传字幕文件失败", String.join(", ", uploadedNames));
        }
    }

    private List<ManifestFile> manifestFiles(List<PlannedSubtitleFile> plannedFiles) {
        return plannedFiles.stream()
                .map(plannedFile -> {
                    ExtractedSubtitleEntry entry = plannedFile.entry();
                    return new ManifestFile(
                            entry.originalPath(),
                            entry.originalName(),
                            plannedFile.finalName(),
                            entry.size(),
                            entry.sha256()
                    );
                })
                .toList();
    }

    private String serializeManifest(List<ManifestFile> manifest) {
        try {
            return objectMapper.writeValueAsString(manifest);
        } catch (JsonProcessingException exception) {
            throw internalError("字幕文件清单序列化失败");
        }
    }

    private List<ManifestFile> deserializeManifest(String manifest) {
        if (!StringUtils.hasText(manifest)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(manifest, MANIFEST_TYPE);
        } catch (JsonProcessingException exception) {
            return List.of();
        }
    }

    private void writeLog(String uploadId, String level, String stage, String message, String detail) {
        SubtitleUploadLog uploadLog = new SubtitleUploadLog();
        uploadLog.setUploadId(uploadId);
        uploadLog.setLevel(level);
        uploadLog.setStage(stage);
        uploadLog.setMessage(message);
        uploadLog.setDetail(detail);
        subtitleUploadLogMapper.insert(uploadLog);
    }

    private SubtitleUpload getExistingUpload(String uploadId) {
        SubtitleUpload upload = subtitleUploadMapper.selectById(uploadId);
        if (upload == null) {
            throw notFound("上传批次不存在");
        }
        return upload;
    }

    private SubtitleUpload getAccessibleUpload(String uploadId, User user) {
        SubtitleUpload upload = getExistingUpload(uploadId);
        if (!canAccessUpload(user, upload)) {
            throw notFound("上传批次不存在");
        }
        return upload;
    }

    private boolean canAccessUpload(User user, SubtitleUpload upload) {
        return isAdmin(user) || (upload.getCreatedByUserId() != null && upload.getCreatedByUserId().equals(user.getId()));
    }

    private boolean isAdmin(User user) {
        return user != null && ADMIN_ROLE.equalsIgnoreCase(user.getRole());
    }

    private SubtitleUploadResponse toResponse(SubtitleUpload upload) {
        List<SubtitleUploadedFileResponse> files = deserializeManifest(upload.getFileManifest()).stream()
                .map(file -> new SubtitleUploadedFileResponse(
                        file.originalPath(),
                        file.originalName(),
                        file.finalName(),
                        file.size(),
                        file.sha256()
                ))
                .toList();
        return new SubtitleUploadResponse(
                upload.getId(),
                upload.getCreatedByUserId(),
                upload.getMediaType(),
                upload.getStatus(),
                upload.getStage(),
                upload.getTitle(),
                upload.getOriginalTitle(),
                upload.getYear(),
                upload.getSeasonNumber(),
                upload.getTargetPath(),
                upload.getSelectedVideoName(),
                upload.getSourceFileName(),
                upload.getSourceSize(),
                upload.getSourceSha256(),
                upload.getFileCount(),
                upload.getOverwriteEnabled(),
                files,
                upload.getErrorMessage(),
                upload.getCreatedAt(),
                upload.getUpdatedAt(),
                upload.getFinishedAt()
        );
    }

    private SubtitleUploadLogResponse toLogResponse(SubtitleUploadLog uploadLog) {
        return new SubtitleUploadLogResponse(
                uploadLog.getId(),
                uploadLog.getUploadId(),
                uploadLog.getLevel(),
                uploadLog.getStage(),
                uploadLog.getMessage(),
                uploadLog.getDetail(),
                uploadLog.getCreatedAt()
        );
    }

    private int validateMovieYear(Integer year) {
        int maxYear = Year.now().getValue() + 2;
        if (year == null || year < FIRST_MOVIE_YEAR || year > maxYear) {
            throw badRequest("年份无效");
        }
        return year;
    }

    private Integer validateOptionalYear(Integer year) {
        if (year == null) {
            return null;
        }
        int maxYear = Year.now().getValue() + 2;
        return year >= FIRST_MOVIE_YEAR && year <= maxYear ? year : null;
    }

    private int validateSeasonNumber(Integer seasonNumber) {
        if (seasonNumber == null || seasonNumber < 1) {
            throw badRequest("剧集季数无效");
        }
        return seasonNumber;
    }

    private String normalizeMediaType(String mediaType) {
        String normalized = StringUtils.hasText(mediaType)
                ? mediaType.trim().toUpperCase(Locale.ROOT)
                : "MOVIE";
        return switch (normalized) {
            case "MOVIE", "FILM" -> "MOVIE";
            case "SERIES", "TV", "SHOW" -> "SERIES";
            default -> throw badRequest("不支持的媒体类型: " + mediaType);
        };
    }

    private String mediaTypeLabel(String mediaType) {
        return "SERIES".equals(mediaType) ? "剧集" : "电影";
    }

    private boolean hasSeriesEpisodeMarker(String sourceName) {
        return SERIES_EPISODE_MARKER_PATTERN.matcher(mainName(sourceName)).find();
    }

    private long fileSize(OpenListFileInfo file) {
        return file.size() == null ? 0L : file.size();
    }

    private String preferredTitle(String title, String originalTitle) {
        if (StringUtils.hasText(originalTitle)) {
            return originalTitle.trim();
        }
        if (StringUtils.hasText(title)) {
            return title.trim();
        }
        throw badRequest("媒体标题不能为空");
    }

    private String configuredRootPath(String configuredPath, String missingMessage) {
        String cleanedPath = cleanConfigValue(configuredPath);
        if (!StringUtils.hasText(cleanedPath)) {
            throw serviceUnavailable(missingMessage);
        }
        return openListClient.normalizePath(cleanedPath);
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

    private String safeOriginalFilename(MultipartFile file) {
        if (file == null || !StringUtils.hasText(file.getOriginalFilename())) {
            return "subtitle-upload";
        }
        String normalized = file.getOriginalFilename().trim().replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private String mainName(String name) {
        String filename = name == null ? "" : name;
        int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String safeMessage(Throwable exception) {
        String message = exception.getMessage();
        return StringUtils.hasText(message) ? truncate(message, 1000) : "字幕上传失败";
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    private BusinessException notFound(String message) {
        return new BusinessException(ErrorCode.NOT_FOUND, message, HttpStatus.NOT_FOUND);
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT);
    }

    private BusinessException serviceUnavailable(String message) {
        return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, message, HttpStatus.SERVICE_UNAVAILABLE);
    }

    private BusinessException internalError(String message) {
        return new BusinessException(ErrorCode.INTERNAL_ERROR, message, HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private record MediaSubtitlePlan(
            String mediaType,
            String title,
            String originalTitle,
            Integer year,
            Integer seasonNumber,
            String folderTitle,
            String folderName,
            String targetPath
    ) {
    }

    private record PreparedUploadPlan(
            String selectedVideoName,
            List<PlannedSubtitleFile> files
    ) {
    }

    private record SeriesSubtitleMatch(
            ExtractedSubtitleEntry entry,
            OpenListFileInfo video
    ) {
    }

    public record ManifestFile(
            String originalPath,
            String originalName,
            String finalName,
            Long size,
            String sha256
    ) {
    }

    private static class SubtitleUploadThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger(1);

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "subtitle-upload-worker-" + sequence.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }
}
