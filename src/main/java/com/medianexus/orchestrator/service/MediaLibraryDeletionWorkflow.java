package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.request.AdminAdultStorageDeletionRequest;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaDeletionTaskResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaSeasonResponse;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2ClientException;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage;
import com.medianexus.orchestrator.integration.clouddrive.CloudDrive2MediaStorage.Entry;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyClientException;
import com.medianexus.orchestrator.integration.emby.EmbyDeletionItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryItem;
import com.medianexus.orchestrator.mapper.MediaDeletionTaskMapper;
import com.medianexus.orchestrator.model.MediaDeletionTask;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MediaLibraryDeletionWorkflow {
    private static final Logger log = LoggerFactory.getLogger(MediaLibraryDeletionWorkflow.class);
    private static final Duration EMBY_CONFIRMATION_TIMEOUT = Duration.ofMinutes(2);
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final String STORAGE_FILE_LABEL = "网盘文件";
    private static final String STORAGE_FOLDER_LABEL = "网盘文件夹";
    private static final String COLLECTION_TARGET_LABEL = "整套合集";

    private final AuthService authService;
    private final AdminMediaLibraryCatalogService catalogService;
    private final EmbyClient embyClient;
    private final MediaSourceDeletion mediaSourceDeletion;
    private final LocalStrmDeletion localStrmDeletion;
    private final MediaDeletionTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final CloudDrive2MediaStorage storage;
    private final MediaCapacityIndex capacities;
    private final AtomicBoolean executing = new AtomicBoolean();

    public MediaLibraryDeletionWorkflow(
            AuthService authService,
            AdminMediaLibraryCatalogService catalogService,
            EmbyClient embyClient,
            MediaSourceDeletion mediaSourceDeletion,
            LocalStrmDeletion localStrmDeletion,
            MediaDeletionTaskMapper taskMapper,
            ObjectMapper objectMapper,
            CloudDrive2MediaStorage storage,
            MediaCapacityIndex capacities
    ) {
        this.authService = authService;
        this.catalogService = catalogService;
        this.embyClient = embyClient;
        this.mediaSourceDeletion = mediaSourceDeletion;
        this.localStrmDeletion = localStrmDeletion;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
        this.storage = storage;
        this.capacities = capacities;
    }

    public List<AdminMediaSeasonResponse> listSeasons(String itemId, String library) {
        authService.requireAdminUser();
        AdminMediaLibraryScope scope = AdminMediaLibraryScope.fromRequest(library);
        if (!scope.episodic()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有电视剧和动漫包含季度");
        }
        catalogService.requireAllowedItem(itemId, scope);
        return embyClient.listSeriesSeasonsForDeletion(itemId).stream()
                .map(season -> new AdminMediaSeasonResponse(
                        season.id(),
                        season.name(),
                        season.indexNumber(),
                        embyClient.listSeasonEpisodesForDeletion(season.id()).size(),
                        season.dateCreated()
                ))
                .toList();
    }

    public synchronized AdminMediaDeletionTaskResponse start(String itemId, String library, String seasonId) {
        authService.requireAdminUser();
        AdminMediaLibraryScope scope = AdminMediaLibraryScope.fromRequest(library);
        EmbyLibrary embyLibrary = catalogService.resolveLibrary(scope);
        EmbyMediaLibraryItem item = catalogService.requireDeletableItem(itemId, scope, embyLibrary);
        if (taskMapper.countActiveTarget(StringUtils.hasText(seasonId) ? seasonId : itemId) > 0) {
            throw new BusinessException(ErrorCode.CONFLICT, "该媒体已有删除任务正在执行", HttpStatus.CONFLICT);
        }

        DeletionPlan plan = "BoxSet".equals(item.type())
                ? collectionPlan(embyLibrary, scope, item)
                : scope.episodic()
                        ? episodicPlan(embyLibrary, scope, item, seasonId)
                        : moviePlan(embyLibrary, scope, item);
        requireNoStorageOverlap(plan.sourcePaths());
        return queue(scope.requestValue(), item.id(), item.title(), json(plan.sourcePaths()), plan);
    }

    public synchronized AdminMediaDeletionTaskResponse startAdultStorage(AdminAdultStorageDeletionRequest request) {
        authService.requireAdminUser();
        Entry expected = new Entry(request.path(), request.directory(), request.id(),
                request.fileBytes() == null ? -1 : request.fileBytes());
        Entry target;
        try {
            target = storage.confirmAdultTarget(request.library(), expected);
        } catch (CloudDrive2ClientException exception) {
            throw new BusinessException(ErrorCode.BAD_GATEWAY, "无法核对网盘目标，请稍后重试", HttpStatus.BAD_GATEWAY);
        }
        requireNoAdultOverlap(target);
        String id = "storage:" + UUID.nameUUIDFromBytes(target.path().getBytes(StandardCharsets.UTF_8));
        String name = Path.of(target.path()).getFileName().toString();
        String label = target.directory() ? STORAGE_FOLDER_LABEL : STORAGE_FILE_LABEL;
        return queue(request.library(), id, name.codePoints().limit(255)
                        .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString(),
                json(target), new DeletionPlan(null, null, label, List.of(), List.of(), List.of()));
    }

    private AdminMediaDeletionTaskResponse queue(
            String library, String itemId, String title, String sourcePayload, DeletionPlan plan
    ) {
        MediaDeletionTask task = new MediaDeletionTask();
        task.setId(UUID.randomUUID().toString());
        task.setLibrary(library);
        task.setItemId(itemId);
        task.setSeasonId(plan.seasonId());
        task.setSeasonNumber(plan.seasonNumber());
        task.setTitle(title);
        task.setTargetLabel(plan.targetLabel());
        task.setStatus("PENDING");
        task.setStage("DELETING_CLOUD");
        task.setSourcePaths(sourcePayload);
        task.setStrmPaths(json(plan.strmPaths()));
        task.setEmbyItemIds(json(plan.embyItemIds()));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        taskMapper.insert(task);
        return response(task);
    }

    private void requireNoAdultOverlap(Entry target) {
        Function<String, String> mapping = storage.sourcePathMapping();
        for (MediaDeletionTask active : taskMapper.listActive()) {
            if (isStorageTask(active)) {
                if (overlap(target.path(), readStorageTarget(active).path())) throw overlappingTask();
            } else if (read(active.getSourcePaths()).stream().map(mapping)
                    .anyMatch(path -> path != null && overlap(target.path(), path))) {
                throw overlappingTask();
            }
        }
    }

    private void requireNoStorageOverlap(List<String> sources) {
        List<MediaDeletionTask> active = taskMapper.listActive().stream().filter(this::isStorageTask).toList();
        if (active.isEmpty()) return;
        Function<String, String> mapping = storage.sourcePathMapping();
        List<String> cloudPaths = sources.stream().map(mapping).filter(java.util.Objects::nonNull).toList();
        for (MediaDeletionTask task : active) {
            if (cloudPaths.stream().anyMatch(path -> overlap(path, readStorageTarget(task).path()))) {
                throw overlappingTask();
            }
        }
    }

    private boolean overlap(String left, String right) {
        return Path.of(left).startsWith(Path.of(right)) || Path.of(right).startsWith(Path.of(left));
    }

    private BusinessException overlappingTask() {
        return conflict("该网盘目标或其上下级目录已有删除任务正在执行");
    }

    public List<AdminMediaDeletionTaskResponse> listTasks() {
        authService.requireAdminUser();
        return taskMapper.listRecent().stream().map(this::response).toList();
    }

    public synchronized AdminMediaDeletionTaskResponse retry(String taskId) {
        authService.requireAdminUser();
        MediaDeletionTask task = requireTask(taskId);
        if (!"FAILED".equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有失败的删除任务可以重试", HttpStatus.CONFLICT);
        }
        if (isStorageTask(task)) requireNoAdultOverlap(readStorageTarget(task));
        else requireNoStorageOverlap(read(task.getSourcePaths()));
        task.setStatus("PENDING");
        task.setErrorMessage(null);
        task.setUpdatedAt(LocalDateTime.now());
        task.setFinishedAt(null);
        taskMapper.updateById(task);
        return response(task);
    }

    public boolean reingestAllowed(String embyItemId) {
        return taskMapper.countReingestAllowedTarget(embyItemId) > 0;
    }

    @Scheduled(initialDelay = 15_000L, fixedDelay = 2_000L)
    public void executeNext() {
        if (!executing.compareAndSet(false, true)) {
            return;
        }
        try {
            MediaDeletionTask task = taskMapper.findNextActive();
            if (task != null) {
                execute(task);
            }
        } finally {
            executing.set(false);
        }
    }

    private void execute(MediaDeletionTask task) {
        try {
            task.setStatus("RUNNING");
            if (isStorageTask(task)) executeStorageTask(task);
            else executeEmbyTask(task);
            task.setStatus("SUCCEEDED");
            task.setStage("COMPLETED");
            task.setErrorMessage(null);
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(task.getFinishedAt());
            taskMapper.updateById(task);
        } catch (RuntimeException exception) {
            task.setStatus("FAILED");
            task.setErrorMessage(shortMessage(exception));
            task.setFinishedAt(LocalDateTime.now());
            task.setUpdatedAt(task.getFinishedAt());
            taskMapper.updateById(task);
            log.warn("Media deletion failed taskId={} stage={} reason={}",
                    task.getId(), task.getStage(), exception.getMessage());
        }
    }

    private void executeEmbyTask(MediaDeletionTask task) {
        saveStage(task, "DELETING_CLOUD");
        List<String> sourcePaths = resolveMediaSourcePaths(read(task.getSourcePaths()));
        List<String> strmPaths = read(task.getStrmPaths());
        List<String> sourceDeletionTargets = sourcePaths;
        List<String> strmDeletionTargets = strmPaths;
        if (isCollectionTask(task)) {
            sourceDeletionTargets = collectionDeletionTargets(task.getTitle(), sourcePaths);
            strmDeletionTargets = collectionDeletionTargets(task.getTitle(), strmPaths);
        }
        AdminMediaLibraryScope scope = AdminMediaLibraryScope.fromRequest(task.getLibrary());
        boolean wholeWork = task.getSeasonId() == null
                && scope != AdminMediaLibraryScope.ADULT_OTHER && scope != AdminMediaLibraryScope.ADULT_JAV;
        if (wholeWork && !sourcePaths.isEmpty() && !strmPaths.isEmpty()) {
            try {
                EmbyLibrary library = catalogService.resolveLibrary(scope);
                String folder = capacities.confirmedWorkFolder(scope, library, strmPaths.get(strmPaths.size() - 1),
                        sourcePaths, strmPaths);
                if (folder != null) sourceDeletionTargets = List.of(folder);
            } catch (EmbyClientException ignored) {
                // The stored exact files are still valid when the library cannot confirm a folder.
            }
        }
        mediaSourceDeletion.delete(sourceDeletionTargets, wholeWork ? null : sourceDirectoryName(task));
        if (scope == AdminMediaLibraryScope.ADULT_OTHER || scope == AdminMediaLibraryScope.ADULT_JAV) {
            Function<String, String> mapping = storage.sourcePathMapping();
            capacities.invalidate(strmPaths, sourceDeletionTargets.stream().map(mapping).filter(java.util.Objects::nonNull).toList());
        } else {
            capacities.invalidate(strmPaths, List.of());
        }

        saveStage(task, "CLEANING_STRM");
        localStrmDeletion.delete(strmDeletionTargets);

        saveStage(task, "NOTIFYING_EMBY");
        embyClient.notifyMediaDeleted(strmPaths);

        saveStage(task, "VERIFYING");
        waitUntilEmbyItemsDisappear(read(task.getEmbyItemIds()));
        if (isCollectionTask(task) && embyClient.itemExists(task.getItemId())) {
            embyClient.deleteCollection(task.getItemId());
            waitUntilEmbyItemsDisappear(List.of(task.getItemId()));
        }
    }

    private void executeStorageTask(MediaDeletionTask task) {
        Entry target = readStorageTarget(task);
        if ("DELETING_CLOUD".equals(task.getStage())) {
            saveStage(task, "DELETING_CLOUD");
            planStorageCleanup(task, target);
            storage.deleteAdultTarget(task.getLibrary(), target);
            capacities.invalidate(read(task.getStrmPaths()), List.of(target.path()));
            saveStage(task, "CLEANING_STRM");
        }
        List<String> paths = read(task.getStrmPaths());
        if ("CLEANING_STRM".equals(task.getStage())) {
            Function<String, String> mapping = storage.sourcePathMapping();
            for (String path : paths) {
                if (Files.notExists(Path.of(path), LinkOption.NOFOLLOW_LINKS)) continue;
                if (!localStrmDeletion.isLocalStrm(path)
                        || !contains(target, mapping.apply(localStrmDeletion.resolveMediaSourcePath(path)))) {
                    throw conflict("对应 STRM 已变化，未清理本地索引");
                }
            }
            localStrmDeletion.delete(paths);
            saveStage(task, "NOTIFYING_EMBY");
        }
        if ("NOTIFYING_EMBY".equals(task.getStage())) {
            if (paths.stream().anyMatch(path -> !Files.notExists(Path.of(path), LinkOption.NOFOLLOW_LINKS))) {
                throw conflict("对应 STRM 路径已有文件，未通知 Emby 删除");
            }
            embyClient.notifyMediaDeleted(paths);
            saveStage(task, "VERIFYING");
        }
        waitUntilEmbyItemsDisappear(read(task.getEmbyItemIds()));
    }

    private void planStorageCleanup(MediaDeletionTask task, Entry target) {
        AdminMediaLibraryScope scope = AdminMediaLibraryScope.fromRequest(task.getLibrary());
        EmbyLibrary library = catalogService.resolveLibrary(scope);
        Function<String, String> mapping = storage.sourcePathMapping();
        Set<String> paths = new LinkedHashSet<>();
        Set<String> embyIds = new LinkedHashSet<>();
        for (String link : localStrmDeletion.allStrmFiles()) {
            if (contains(target, mapping.apply(localStrmDeletion.resolveMediaSourcePath(link)))) {
                requireBelowLibraryRoot(link, library);
                paths.add(link);
            }
        }

        int batchSize = 500;
        for (int offset = 0; ; offset += batchSize) {
            List<EmbyDeletionItem> items = embyClient.listLibraryDeletionVideos(library.id(), offset, batchSize);
            for (EmbyDeletionItem item : items) {
                boolean primaryMatches = paths.contains(item.path());
                List<String> sources = resolveMediaSourcePaths(item.mediaSourcePaths());
                if (!primaryMatches && sources.stream().noneMatch(source -> contains(target, mapping.apply(source)))) {
                    continue;
                }
                if (!primaryMatches || sources.stream().anyMatch(source -> !contains(target, mapping.apply(source)))) {
                    throw conflict("网盘目标与 Emby 的媒体源不完全一致，无法安全清理");
                }
                if (StringUtils.hasText(item.id())) embyIds.add(item.id());
            }
            if (items.size() < batchSize) break;
        }
        task.setStrmPaths(json(List.copyOf(paths)));
        task.setEmbyItemIds(json(List.copyOf(embyIds)));
        taskMapper.updateById(task);
    }

    private boolean contains(Entry target, String sourcePath) {
        return sourcePath != null && (target.directory()
                ? Path.of(sourcePath).startsWith(Path.of(target.path())) : target.path().equals(sourcePath));
    }

    private boolean isStorageTask(MediaDeletionTask task) {
        return STORAGE_FILE_LABEL.equals(task.getTargetLabel()) || STORAGE_FOLDER_LABEL.equals(task.getTargetLabel());
    }

    private Entry readStorageTarget(MediaDeletionTask task) {
        try {
            return objectMapper.readValue(task.getSourcePaths(), Entry.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法读取删除任务的网盘目标", exception);
        }
    }

    private BusinessException conflict(String message) {
        return new BusinessException(ErrorCode.CONFLICT, message, HttpStatus.CONFLICT);
    }

    private DeletionPlan moviePlan(
            EmbyLibrary library,
            AdminMediaLibraryScope scope,
            EmbyMediaLibraryItem item
    ) {
        EmbyDeletionItem deletionItem = embyClient.getMediaItemForDeletion(
                library.id(), scope.itemType(), item.id()
        );
        if (deletionItem == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到可删除的媒体文件", HttpStatus.NOT_FOUND);
        }
        requireBelowLibraryRoot(deletionItem.path(), library);
            List<String> sources = resolveMediaSourcePaths(deletionItem.mediaSourcePaths());
            requirePaths(sources, List.of(deletionItem.path()));
            return new DeletionPlan(
                null, null, "整部作品",
                    sources,
                List.of(deletionItem.path()),
                List.of(deletionItem.id())
        );
    }

    private DeletionPlan collectionPlan(
            EmbyLibrary library,
            AdminMediaLibraryScope scope,
            EmbyMediaLibraryItem collection
    ) {
        if (scope != AdminMediaLibraryScope.ADULT_OTHER) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "只有 Adult - Other 支持整套合集删除");
        }
        List<EmbyDeletionItem> members = embyClient.listCollectionItemsForDeletion(collection.id());
        if (members.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该合集没有可删除的媒体", HttpStatus.NOT_FOUND);
        }

        Set<String> sources = new LinkedHashSet<>();
        Set<String> strmPaths = new LinkedHashSet<>();
        Set<String> embyIds = new LinkedHashSet<>();
        for (EmbyDeletionItem member : members) {
            requireBelowLibraryRoot(member.path(), library);
            requirePaths(member.mediaSourcePaths(), List.of(member.path()));
            sources.addAll(member.mediaSourcePaths());
            strmPaths.add(member.path());
            embyIds.add(member.id());
        }
            List<String> resolvedSources = resolveMediaSourcePaths(List.copyOf(sources));
            requirePaths(resolvedSources, List.copyOf(strmPaths));
            return new DeletionPlan(
                null, null, COLLECTION_TARGET_LABEL,
                resolvedSources, List.copyOf(strmPaths), List.copyOf(embyIds)
        );
    }

    private List<String> collectionDeletionTargets(String collectionTitle, List<String> paths) {
        if (!StringUtils.hasText(collectionTitle) || paths.isEmpty()) {
            return paths;
        }
        Path sharedParent = null;
        for (String value : paths) {
            if (!StringUtils.hasText(value)) {
                return paths;
            }
            Path parent = Path.of(value).toAbsolutePath().normalize().getParent();
            if (parent == null || parent.getFileName() == null
                    || !collectionTitle.equals(parent.getFileName().toString())) {
                return paths;
            }
            if (sharedParent != null && !sharedParent.equals(parent)) {
                return paths;
            }
            sharedParent = parent;
        }
        return sharedParent == null ? paths : List.of(sharedParent.toString());
    }

    private DeletionPlan episodicPlan(
            EmbyLibrary library,
            AdminMediaLibraryScope scope,
            EmbyMediaLibraryItem series,
            String seasonId
    ) {
        EmbyDeletionItem seriesItem = embyClient.getMediaItemForDeletion(
                library.id(), scope.itemType(), series.id()
        );
        if (seriesItem == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "未找到可删除的剧集目录", HttpStatus.NOT_FOUND);
        }
        requireBelowLibraryRoot(seriesItem.path(), library);
        Path seriesPath = Path.of(seriesItem.path()).toAbsolutePath().normalize();
        List<EmbyDeletionItem> seasons = embyClient.listSeriesSeasonsForDeletion(series.id());
        List<EmbyDeletionItem> targets;
        if (StringUtils.hasText(seasonId)) {
            targets = seasons.stream().filter(season -> seasonId.equals(season.id())).toList();
            if (targets.isEmpty()) {
                throw new BusinessException(ErrorCode.NOT_FOUND, "未在该剧集中找到所选季度", HttpStatus.NOT_FOUND);
            }
        } else {
            targets = seasons;
        }
        if (targets.isEmpty()) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "该剧集没有可删除的季度", HttpStatus.NOT_FOUND);
        }

        Set<String> sources = new LinkedHashSet<>();
        Set<String> strmPaths = new LinkedHashSet<>();
        Set<String> embyIds = new LinkedHashSet<>();
        for (EmbyDeletionItem season : targets) {
            if (StringUtils.hasText(seasonId)) {
                // Some Emby libraries flatten a single season directly under the
                // library root. It is still safe to delete when the season is
                // within the selected library and every episode stays below it.
                requireBelowLibraryRoot(season.path(), library);
            } else {
                requireBelow(season.path(), seriesPath, "季度 STRM 路径不在剧集目录内");
            }
            Path seasonPath = Path.of(season.path()).toAbsolutePath().normalize();
            List<EmbyDeletionItem> episodes = embyClient.listSeasonEpisodesForDeletion(season.id());
            strmPaths.add(season.path());
            embyIds.add(season.id());
            for (EmbyDeletionItem episode : episodes) {
                requireBelow(episode.path(), seasonPath, "剧集 STRM 路径不在季度目录内");
                sources.addAll(resolveMediaSourcePaths(episode.mediaSourcePaths()));
                strmPaths.add(episode.path());
                embyIds.add(episode.id());
            }
        }
        if (!StringUtils.hasText(seasonId)) {
            strmPaths.add(seriesItem.path());
            embyIds.add(series.id());
        }
        List<String> resolvedSources = resolveMediaSourcePaths(List.copyOf(sources));
        requirePaths(resolvedSources, List.copyOf(strmPaths));
        EmbyDeletionItem selectedSeason = StringUtils.hasText(seasonId) ? targets.get(0) : null;
        return new DeletionPlan(
                selectedSeason == null ? null : selectedSeason.id(),
                selectedSeason == null ? null : selectedSeason.indexNumber(),
                selectedSeason == null ? "整部剧集" : selectedSeason.name(),
                resolvedSources,
                List.copyOf(strmPaths),
                List.copyOf(embyIds)
        );
    }

    private void requirePaths(List<String> sourcePaths, List<String> strmPaths) {
        if (strmPaths.isEmpty() || strmPaths.stream().anyMatch(path -> !StringUtils.hasText(path))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Emby 未返回完整的 STRM 路径");
        }
    }

    private List<String> resolveMediaSourcePaths(List<String> paths) {
        return paths.stream()
                .map(localStrmDeletion::resolveMediaSourcePath)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }

    private void requireBelowLibraryRoot(String value, EmbyLibrary library) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.CONFLICT, "Emby 未返回 STRM 路径");
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        boolean allowed = library.locations().stream()
                .filter(StringUtils::hasText)
                .map(location -> Path.of(location).toAbsolutePath().normalize())
                .anyMatch(root -> !path.equals(root) && path.startsWith(root));
        if (!allowed) {
            throw new BusinessException(ErrorCode.CONFLICT, "STRM 路径不在所选媒体库范围内");
        }
    }

    private void requireBelow(String value, Path parent, String message) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
        Path path = Path.of(value).toAbsolutePath().normalize();
        if (path.equals(parent) || !path.startsWith(parent)) {
            throw new BusinessException(ErrorCode.CONFLICT, message);
        }
    }

    private void waitUntilEmbyItemsDisappear(List<String> itemIds) {
        Instant deadline = Instant.now().plus(EMBY_CONFIRMATION_TIMEOUT);
        do {
            if (itemIds.stream().noneMatch(embyClient::itemExists)) {
                return;
            }
            sleep(Duration.ofSeconds(2));
        } while (Instant.now().isBefore(deadline));
        throw new IllegalStateException("文件已删除，但 Emby 尚未完成媒体库同步");
    }

    private boolean isCollectionTask(MediaDeletionTask task) {
        return AdminMediaLibraryScope.ADULT_OTHER.requestValue().equals(task.getLibrary())
                && COLLECTION_TARGET_LABEL.equals(task.getTargetLabel());
    }

    private String sourceDirectoryName(MediaDeletionTask task) {
        return task.getSeasonNumber() == null
                ? task.getTitle()
                : "Season %02d".formatted(task.getSeasonNumber());
    }

    private void saveStage(MediaDeletionTask task, String stage) {
        task.setStage(stage);
        task.setUpdatedAt(LocalDateTime.now());
        taskMapper.updateById(task);
    }

    private MediaDeletionTask requireTask(String taskId) {
        MediaDeletionTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "删除任务不存在", HttpStatus.NOT_FOUND);
        }
        return task;
    }

    private String json(Object values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("无法保存删除任务路径", exception);
        }
    }

    private List<String> read(String json) {
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("删除任务路径已损坏", exception);
        }
    }

    private AdminMediaDeletionTaskResponse response(MediaDeletionTask task) {
        return new AdminMediaDeletionTaskResponse(
                task.getId(), task.getLibrary(), task.getItemId(), task.getSeasonId(),
                task.getSeasonNumber(), task.getTitle(), task.getTargetLabel(), task.getStatus(),
                task.getStage(), task.getErrorMessage(), task.getCreatedAt(), task.getUpdatedAt(),
                task.getFinishedAt()
        );
    }

    private String shortMessage(RuntimeException exception) {
        String message = StringUtils.hasText(exception.getMessage())
                ? exception.getMessage()
                : "删除任务执行失败";
        return message.length() <= 1000 ? message : message.substring(0, 1000);
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("删除任务已中断", exception);
        }
    }

    private record DeletionPlan(
            String seasonId,
            Integer seasonNumber,
            String targetLabel,
            List<String> sourcePaths,
            List<String> strmPaths,
            List<String> embyItemIds
    ) { }
}
