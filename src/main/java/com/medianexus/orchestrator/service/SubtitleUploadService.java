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
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleLocationsResponse;
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
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import com.medianexus.orchestrator.dto.subtitle.SubtitleCommitRequest;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitlePreparationResponse;
import com.medianexus.orchestrator.dto.subtitle.response.SubtitleUploadCheckResponse;
import java.time.Instant;
import java.util.Objects;
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

    private final IngestTargetRegistry targetRegistry;
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

    // 预览和待执行的确认上传共用本地资源额度；不持久化预览，不创建远端 staging。
    private final Map<String, Preparation> preparations = new HashMap<>();
    private int preparationSlots;
    private long preparationBytes;
    private static final long PREPARATION_BYTE_LIMIT = 1024L * 1024 * 1024;

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60000)
    public synchronized void expirePreparations() {
        var iterator = preparations.values().iterator();
        while (iterator.hasNext()) {
            Preparation preparation = iterator.next();
            if (!preparation.expiresAt().isAfter(Instant.now())) {
                iterator.remove();
                releasePreparation(preparation);
            }
        }
    }

    private synchronized void releasePreparation(Preparation preparation) {
        archiveExtractor.deleteQuietly(preparation.extracted().workDir());
        preparationSlots--;
        preparationBytes -= preparation.bytes();
    }

    @jakarta.annotation.PreDestroy
    public synchronized void closePreparations() {
        preparations.values().forEach(this::releasePreparation);
        preparations.clear();
    }

    public synchronized SubtitlePreparationResponse prepare(
            MultipartFile file, String mediaType, String title, String originalTitle, Integer year,
            Integer seasonNumber, String targetId, String language
    ) {
        User user = authService.requireCurrentUser();
        String fallback = filenamePlanner.validateLanguage(language);
        expirePreparations();
        if (preparationSlots >= 8) throw conflict("同时临时字幕包过多，请稍后重试");
        List<SubtitleLocation> locations = findMediaLocations(mediaType, title, originalTitle, year, seasonNumber);
        SubtitleLocation selected;
        if (StringUtils.hasText(targetId)) {
            selected = locations.stream().filter(location -> location.targetId().equals(targetId)).findFirst()
                    .orElseThrow(() -> notFound("所选目标中未找到媒体目录"));
        } else {
            if (locations.isEmpty()) throw notFound("未找到媒体目录");
            if (locations.size() > 1) throw conflict("媒体存在于多个下载目标，请选择目标");
            selected = locations.get(0);
        }
        ExtractedSubtitlePackage extracted = archiveExtractor.extract(file);
        try {
            long bytes;
            try (var paths = java.nio.file.Files.walk(extracted.workDir())) {
                bytes = paths.filter(java.nio.file.Files::isRegularFile).mapToLong(path -> {
                    try { return java.nio.file.Files.size(path); }
                    catch (java.io.IOException exception) { throw new java.io.UncheckedIOException(exception); }
                }).sum();
            }
            if (bytes > PREPARATION_BYTE_LIMIT - preparationBytes) throw conflict("临时字幕包总大小超过 1 GiB 限制");
            List<OpenListFileInfo> targetFiles = openListClient.listFiles(selected.mediaPlan().targetPath(), true);
            List<SubtitlePreparationResponse.File> files = previewFiles(selected.mediaPlan(), extracted, targetFiles, fallback);
            String id = UUID.randomUUID().toString();
            Instant expiresAt = Instant.now().plusSeconds(1800);
            Preparation preparation = new Preparation(user.getId(), expiresAt, selected, extracted,
                    List.copyOf(targetFiles), files, bytes);
            preparations.put(id, preparation);
            preparationSlots++;
            preparationBytes += bytes;
            return new SubtitlePreparationResponse(id, expiresAt, selected.targetId(), selected.mediaPlan().targetPath(),
                    files, extracted.ignoredEntries(), List.of("字幕来源与时间轴未经外站验证；上传完成不表示 AS 或 Emby 已完成"));
        } catch (java.io.IOException exception) {
            archiveExtractor.deleteQuietly(extracted.workDir());
            throw internalError("无法检查本地字幕临时文件");
        } catch (RuntimeException exception) {
            archiveExtractor.deleteQuietly(extracted.workDir());
            throw exception;
        }
    }

    private List<SubtitlePreparationResponse.File> previewFiles(MediaSubtitlePlan media, ExtractedSubtitlePackage extracted,
            List<OpenListFileInfo> targetFiles, String fallback) {
        List<OpenListFileInfo> videos = targetFiles.stream().filter(this::isTargetVideo).toList();
        List<SubtitlePreparationResponse.File> files = new ArrayList<>();
        for (int index = 0; index < extracted.entries().size(); index++) {
            ExtractedSubtitleEntry entry = extracted.entries().get(index);
            var language = filenamePlanner.language(entry, fallback);
            Integer episode = "SERIES".equals(media.mediaType()) ? subtitleEpisode(entry.originalName(), media.seasonNumber()) : null;
            List<OpenListFileInfo> candidates = "SERIES".equals(media.mediaType())
                    ? videos.stream().filter(video -> episode != null
                    && renameService.episodeNumber(video.name(), media.seasonNumber()).filter(episode::equals).isPresent()).toList()
                    : videos;
            String videoName = candidates.size() == 1 ? candidates.get(0).name() : null;
            String finalName = videoName == null ? null : filenamePlanner.finalName(videoName, language.language(), entry.extension());
            String status = candidates.isEmpty() ? "UNMATCHED" : candidates.size() > 1 ? "AMBIGUOUS" : "READY";
            if (finalName != null && targetFiles.stream().anyMatch(remote -> remote.name().equalsIgnoreCase(finalName))) status = "CONFLICT";
            List<String> warnings = new ArrayList<>();
            if ("unknown".equals(language.language())) warnings.add("未确认简繁语言；确认时可手动选择，unknown 将使用 .und 命名");
            if (videoName == null) warnings.add("请指定目标目录中的实际 video_name；00 不自动匹配特别篇");
            files.add(new SubtitlePreparationResponse.File(Integer.toString(index), entry.originalPath(), entry.originalName(),
                    entry.extension(), language.language(), language.source(), episode, videoName, finalName,
                    entry.size(), entry.sha256(), status, "CONFLICT".equals(status) ? "REMOTE_EXISTS" : null, List.copyOf(warnings)));
        }
        Map<String, Long> counts = files.stream().filter(file -> file.finalName() != null)
                .collect(java.util.stream.Collectors.groupingBy(file -> file.finalName().toLowerCase(Locale.ROOT), java.util.stream.Collectors.counting()));
        return files.stream().map(file -> file.finalName() != null && file.conflictReason() == null
                && counts.get(file.finalName().toLowerCase(Locale.ROOT)) > 1
                ? new SubtitlePreparationResponse.File(file.id(), file.originalPath(), file.originalName(), file.extension(),
                file.language(), file.languageSource(), file.episodeNumber(), file.videoName(), file.finalName(), file.size(),
                file.sha256(), "CONFLICT", "DUPLICATE_CANDIDATE", List.of("多个字幕规划为同一目标文件名；请选择一项或修改语言")) : file).toList();
    }

    private boolean isTargetVideo(OpenListFileInfo file) {
        return !Boolean.TRUE.equals(file.isDir()) && renameService.isVideo(file.name())
                && file.name().indexOf('/') < 0 && file.name().indexOf('\\') < 0
                && file.name().chars().noneMatch(Character::isISOControl);
    }

    private Integer subtitleEpisode(String name, Integer season) {
        String stem = mainName(name).replaceFirst("(?i)(?:[._ -](?:chs|cht|zh-cn|zh-tw|简体|繁体|简中|繁中))+$", "");
        if (stem.matches("\\d{1,3}")) {
            int number = Integer.parseInt(stem);
            return number > 0 ? number : null;
        }
        return renameService.episodeNumber(name, season).orElse(null);
    }

    /** 同一预览只消费一次；名称和大小快照用于发现变化，确认接口不允许覆盖已有字幕。 */
    public synchronized SubtitleUploadResponse commit(String id, SubtitleCommitRequest request) {
        User user = authService.requireCurrentUser();
        expirePreparations();
        Preparation preparation = preparations.get(id);
        if (preparation == null || !Objects.equals(preparation.owner(), user.getId())) throw notFound("字幕预览不存在或已过期");
        if (request == null || request.files() == null || request.files().isEmpty()) throw badRequest("请至少选择一个字幕文件");
        if (request.overwrite()) throw conflict("预览确认不允许覆盖已有字幕，请先选择其他版本或另行处理现有文件");
        List<OpenListFileInfo> current = openListClient.listFiles(preparation.location().mediaPlan().targetPath(), true);
        if (!videoSnapshot(preparation.targetFiles()).equals(videoSnapshot(current))) throw conflict("目标视频已变化，请重新预览");
        Set<String> selectedIds = new HashSet<>();
        List<PlannedSubtitleFile> planned = new ArrayList<>();
        for (SubtitleCommitRequest.Selection selection : request.files()) {
            if (selection == null || !selectedIds.add(selection.id())) throw badRequest("字幕选择 ID 不可重复或为空");
            SubtitlePreparationResponse.File preview = preparation.files().stream().filter(file -> file.id().equals(selection.id()))
                    .findFirst().orElseThrow(() -> badRequest("字幕选择 ID 无效"));
            String language = selection.language() == null ? preview.language() : filenamePlanner.validateLanguage(selection.language());
            String videoName = selection.videoName() == null ? preview.videoName() : selection.videoName();
            if (videoName == null || current.stream().noneMatch(video -> isTargetVideo(video) && video.name().equals(videoName))) {
                throw badRequest("video_name 必须是目标目录中的实际视频文件名");
            }
            String finalName = filenamePlanner.finalName(videoName, language, preview.extension());
            if (!nameSnapshot(preparation.targetFiles(), finalName).equals(nameSnapshot(current, finalName))) {
                throw conflict("目标字幕冲突已变化，请重新预览");
            }
            if (current.stream().anyMatch(remote -> remote.name().equalsIgnoreCase(finalName) && Boolean.TRUE.equals(remote.isDir()))) {
                throw conflict("目标文件名已被目录占用");
            }
            planned.add(new PlannedSubtitleFile(preparation.extracted().entries().get(Integer.parseInt(preview.id())), finalName));
        }
        filenamePlanner.validateNoDuplicateFinalNames(planned);
        ensureNoRemoteConflict(current, planned, request.overwrite());
        preparations.remove(id);
        String uploadId = UUID.randomUUID().toString();
        SubtitleUpload upload = null;
        try {
            upload = createUpload(uploadId, user, preparation.location().mediaPlan(), preparation.extracted().sourceFileName(), request.overwrite());
            updateSource(uploadId, preparation.extracted());
            List<ManifestFile> manifest = manifestFiles(planned);
            updatePlan(uploadId, "已确认 " + planned.size() + " 个字幕", manifest);
            upload.setSelectedVideoName("已确认 " + planned.size() + " 个字幕");
            upload.setSourceSize(preparation.extracted().sourceSize());
            upload.setSourceSha256(preparation.extracted().sourceSha256());
            upload.setFileCount(manifest.size());
            upload.setFileManifest(serializeManifest(manifest));
            writeLog(uploadId, "INFO", "created", "已确认字幕选择，等待后台上传", preparation.location().mediaPlan().targetPath());
            SubtitleUploadResponse response = toResponse(upload);
            executorService.submit(() -> processConfirmed(uploadId, preparation, List.copyOf(planned), request.overwrite(), current));
            return response;
        } catch (RuntimeException exception) {
            releasePreparation(preparation);
            if (upload != null) markFailed(uploadId, "字幕后台任务提交失败");
            throw exception;
        }
    }

    private List<String> videoSnapshot(List<OpenListFileInfo> files) {
        return files.stream().filter(this::isTargetVideo).map(file -> file.name() + "\0" + file.size()).sorted().toList();
    }

    private List<String> nameSnapshot(List<OpenListFileInfo> files, String name) {
        return files.stream().filter(file -> file.name().equalsIgnoreCase(name))
                .map(file -> file.name() + "\0" + file.size() + "\0" + file.isDir()).sorted().toList();
    }

    private void processConfirmed(String uploadId, Preparation preparation, List<PlannedSubtitleFile> files,
            boolean overwrite, List<OpenListFileInfo> confirmedTarget) {
        String path = preparation.location().mediaPlan().targetPath();
        List<String> createdNames = new ArrayList<>();
        try {
            List<OpenListFileInfo> current = openListClient.listFiles(path, true);
            if (!videoSnapshot(confirmedTarget).equals(videoSnapshot(current))) throw conflict("目标视频已变化，请重新预览");
            for (PlannedSubtitleFile file : files) {
                if (!nameSnapshot(confirmedTarget, file.finalName()).equals(nameSnapshot(current, file.finalName()))) {
                    throw conflict("目标字幕已变化，请重新预览");
                }
            }
            ensureNoRemoteConflict(current, files, overwrite);
            markStage(uploadId, "PROCESSING", "uploading");
            for (PlannedSubtitleFile file : files) {
                openListClient.uploadFile(file.entry().localPath(), openListClient.joinPath(path, file.finalName()), overwrite);
                // 覆盖的已有文件不可用 remove 回滚，否则会删除用户原有字幕。
                if (nameSnapshot(current, file.finalName()).isEmpty()) createdNames.add(file.finalName());
                writeLog(uploadId, "INFO", "uploading", "字幕文件上传完成", file.finalName());
            }
            refreshAutoSymlink(uploadId, preparation.location().mediaPlan());
            writeLog(uploadId, "INFO", "waiting_for_as", "源字幕已写入；AS 提交结果见刷新日志，输出目录与 Emby 状态未验证", path);
            markSucceeded(uploadId, files.size());
        } catch (RuntimeException exception) {
            cleanupUploadedFiles(uploadId, path, createdNames);
            markFailed(uploadId, safeMessage(exception));
            writeLog(uploadId, "ERROR", "failed", "字幕上传失败；已覆盖的文件无法恢复旧内容", safeMessage(exception));
            log.warn("Confirmed subtitle upload failed uploadId={}", uploadId, exception);
        } finally {
            releasePreparation(preparation);
        }
    }

    public SubtitleUploadCheckResponse checkUpload(String id) {
        SubtitleUpload upload = getAccessibleUpload(id, authService.requireCurrentUser());
        var output = new SubtitleUploadCheckResponse.Location("UNAVAILABLE", List.of(),
                "未启用可靠的 AS 输出目录本地读取检查；未验证 AS 文件落位或 Emby 索引，AS 提交结果请查看批次刷新日志");
        List<ManifestFile> manifest = deserializeManifest(upload.getFileManifest());
        if (manifest.isEmpty()) {
            String status = "PROCESSING".equals(upload.getStatus()) ? "PENDING" : "UNAVAILABLE";
            return new SubtitleUploadCheckResponse(status,
                    new SubtitleUploadCheckResponse.Location(status, List.of(), "上传批次暂无可校验文件清单"), output);
        }
        try {
            List<OpenListFileInfo> remote = openListClient.listFiles(upload.getTargetPath(), true);
            List<SubtitleUploadCheckResponse.File> files = manifest.stream().map(file -> {
                OpenListFileInfo actual = remote.stream().filter(entry -> !Boolean.TRUE.equals(entry.isDir())
                        && entry.name().equals(file.finalName())).findFirst().orElse(null);
                Long size = actual == null ? null : actual.size();
                return new SubtitleUploadCheckResponse.File(file.finalName(), file.size(), size, actual != null,
                        size != null && file.size() != null && size.equals(file.size()));
            }).toList();
            String status = files.stream().allMatch(SubtitleUploadCheckResponse.File::matchesSize) ? "COMPLETE" : "PENDING";
            return new SubtitleUploadCheckResponse(status, new SubtitleUploadCheckResponse.Location(status, files,
                    "COMPLETE".equals(status) ? "源目录字幕名称与大小全部匹配；AS 输出与 Emby 未验证" : "源目录字幕尚未全部匹配；未知大小不算匹配"), output);
        } catch (RuntimeException exception) {
            return new SubtitleUploadCheckResponse("UNAVAILABLE", new SubtitleUploadCheckResponse.Location("UNAVAILABLE", List.of(),
                    "无法读取 OpenList 源目录，请稍后重试"), output);
        }
    }

    private record Preparation(Long owner, Instant expiresAt, SubtitleLocation location, ExtractedSubtitlePackage extracted,
            List<OpenListFileInfo> targetFiles, List<SubtitlePreparationResponse.File> files, long bytes) { }

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
            AutoSymlinkRefreshService autoSymlinkRefreshService,
            IngestTargetRegistry targetRegistry
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
                Executors.newSingleThreadExecutor(new SubtitleUploadThreadFactory()),
                targetRegistry
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
            ExecutorService executorService,
            IngestTargetRegistry targetRegistry
    ) {
        this.targetRegistry = targetRegistry;
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
        return uploadSubtitle(file, mediaType, title, originalTitle, year, seasonNumber, overwrite, null);
    }

    public SubtitleUploadResponse uploadSubtitle(
            MultipartFile file, String mediaType, String title, String originalTitle,
            Integer year, Integer seasonNumber, boolean overwrite, String targetId
    ) {
        User user = authService.requireCurrentUser();
        List<SubtitleLocation> locations = findMediaLocations(mediaType, title, originalTitle, year, seasonNumber);
        SubtitleLocation selected;
        if (StringUtils.hasText(targetId)) {
            selected = locations.stream().filter(location -> location.targetId().equals(targetId)).findFirst()
                    .orElseThrow(() -> notFound("所选目标中未找到媒体目录"));
        } else {
            if (locations.isEmpty()) {
                throw notFound("未找到媒体目录");
            }
            if (locations.size() > 1) {
                throw new BusinessException(ErrorCode.CONFLICT, "媒体存在于多个下载目标，请选择目标", HttpStatus.CONFLICT);
            }
            selected = locations.get(0);
        }
        MediaSubtitlePlan mediaPlan = selected.mediaPlan();
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
                if (nameSnapshot(targetFiles, plannedFile.finalName()).isEmpty()) uploadedNames.add(plannedFile.finalName());
                writeLog(uploadId, "INFO", "uploading", "字幕文件上传完成", plannedFile.finalName());
            }

            markStage(uploadId, "PROCESSING", "refreshing_as");
            refreshAutoSymlink(uploadId, mediaPlan);
            writeLog(uploadId, "INFO", "waiting_for_as", "字幕已写入目标目录，等待 AS 后续迁移", mediaPlan.targetPath());
            markSucceeded(uploadId, manifest.size());
        } catch (BusinessException exception) {
            cleanupUploadedFiles(uploadId, mediaPlan.targetPath(), uploadedNames);
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

    private void refreshAutoSymlink(String uploadId, MediaSubtitlePlan mediaPlan) {
        try {
            writeLog(uploadId, "INFO", "refreshing_as", "正在触发 AutoSymlink 刷新", null);
            AutoSymlinkRefreshService.RefreshOutcome outcome = autoSymlinkRefreshService.refreshForPath(
                    "SERIES".equals(mediaPlan.mediaType()) ? "TV" : "MOVIE", mediaPlan.targetPath());
            String level = outcome.status() == AutoSymlinkRefreshService.Status.SUBMITTED ? "INFO" : "WARN";
            writeLog(uploadId, level, "refreshing_as", outcome.message(), outcome.detail());
        } catch (Exception exception) {
            log.warn("Subtitle AutoSymlink refresh failed uploadId={} mediaType={}", uploadId, mediaPlan.mediaType(), exception);
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

    public SubtitleLocationsResponse locations(
            String mediaType, String title, String originalTitle, Integer year, Integer seasonNumber
    ) {
        authService.requireCurrentUser();
        return new SubtitleLocationsResponse(findMediaLocations(mediaType, title, originalTitle, year, seasonNumber)
                .stream().map(location -> new SubtitleLocationsResponse.Item(location.targetId(), location.targetName()))
                .toList());
    }

    private List<SubtitleLocation> findMediaLocations(
            String mediaType, String title, String originalTitle, Integer year, Integer seasonNumber
    ) {
        String normalizedType = normalizeMediaType(mediaType);
        String category = "SERIES".equals(normalizedType) ? "TV" : "MOVIE";
        String legacyRoot = "SERIES".equals(normalizedType)
                ? openListProperties.getTvRootPath() : openListProperties.getMovieRootPath();
        // 没有候选目标时也保留媒体元数据的既有验证。
        buildMediaPlan(normalizedType, title, originalTitle, year, seasonNumber, "/");
        List<SubtitleLocation> locations = new ArrayList<>();
        for (String root : targetRegistry.rootsForExistingMedia(category, cleanConfigValue(legacyRoot))) {
            MediaSubtitlePlan plan = buildMediaPlan(normalizedType, title, originalTitle, year, seasonNumber, root);
            var binding = targetRegistry.bindingForPath(category, root);
            if (openListClient.pathExists(plan.targetPath())) {
                String targetId = binding.map(IngestTargetRegistry.CategoryBinding::targetId).orElse("legacy");
                String targetName = binding.map(IngestTargetRegistry.CategoryBinding::targetName).orElse("现有媒体库");
                locations.add(new SubtitleLocation(targetId, targetName, plan));
            }
        }
        return List.copyOf(locations);
    }

    private MediaSubtitlePlan buildMediaPlan(
            String mediaType,
            String title,
            String originalTitle,
            Integer year,
            Integer seasonNumber,
            String rootPath
    ) {
        String normalizedMediaType = normalizeMediaType(mediaType);
        if ("SERIES".equals(normalizedMediaType)) {
            return buildSeriesPlan(title, originalTitle, year, seasonNumber, rootPath);
        }
        return buildMoviePlan(title, originalTitle, year, rootPath);
    }

    private MediaSubtitlePlan buildMoviePlan(String title, String originalTitle, Integer year, String rootPath) {
        int releaseYear = validateMovieYear(year);
        String normalizedTitle = trimToNull(title);
        String normalizedOriginalTitle = trimToNull(originalTitle);
        String folderTitle = preferredTitle(normalizedTitle, normalizedOriginalTitle);
        String displayTitle = StringUtils.hasText(normalizedTitle) ? normalizedTitle : folderTitle;
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
            Integer seasonNumber,
            String rootPath
    ) {
        int selectedSeasonNumber = validateSeasonNumber(seasonNumber);
        String normalizedTitle = trimToNull(title);
        String normalizedOriginalTitle = trimToNull(originalTitle);
        String folderTitle = preferredTitle(normalizedTitle, normalizedOriginalTitle);
        String displayTitle = StringUtils.hasText(normalizedTitle) ? normalizedTitle : folderTitle;
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
            if (videosByEpisode.putIfAbsent(episodeNumber.get(), videoFile) != null) {
                throw conflict("同集存在多个视频，请使用字幕预览显式选择 video_name");
            }
        }

        List<SeriesSubtitleMatch> subtitleMatches = new ArrayList<>();
        Set<String> matchedVideoNames = new HashSet<>();
        for (ExtractedSubtitleEntry subtitleEntry : subtitleEntries) {
            Optional<Integer> episodeNumber = Optional.ofNullable(subtitleEpisode(subtitleEntry.originalName(), mediaPlan.seasonNumber()));
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
                    && !mainName(subtitleEntry.originalName()).matches("(?i)0{1,3}(?:[._ -](?:chs|cht|zh-cn|zh-tw|简体|繁体|简中|繁中))*")
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
                .map(file -> file.name().toLowerCase(Locale.ROOT))
                .collect(HashSet::new, HashSet::add, HashSet::addAll);
        List<String> conflicts = plannedFiles.stream()
                .map(PlannedSubtitleFile::finalName)
                .filter(name -> remoteNames.contains(name.toLowerCase(Locale.ROOT)))
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

    private record SubtitleLocation(String targetId, String targetName, MediaSubtitlePlan mediaPlan) {
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
