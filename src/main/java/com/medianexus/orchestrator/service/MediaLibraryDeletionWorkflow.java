package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaDeletionTaskResponse;
import com.medianexus.orchestrator.dto.admin.response.AdminMediaSeasonResponse;
import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.integration.emby.EmbyDeletionItem;
import com.medianexus.orchestrator.integration.emby.EmbyLibrary;
import com.medianexus.orchestrator.integration.emby.EmbyMediaLibraryItem;
import com.medianexus.orchestrator.mapper.MediaDeletionTaskMapper;
import com.medianexus.orchestrator.model.MediaDeletionTask;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
    private static final String COLLECTION_TARGET_LABEL = "整套合集";

    private final AuthService authService;
    private final AdminMediaLibraryCatalogService catalogService;
    private final EmbyClient embyClient;
    private final MediaSourceDeletion mediaSourceDeletion;
    private final LocalStrmDeletion localStrmDeletion;
    private final MediaDeletionTaskMapper taskMapper;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean executing = new AtomicBoolean();

    public MediaLibraryDeletionWorkflow(
            AuthService authService,
            AdminMediaLibraryCatalogService catalogService,
            EmbyClient embyClient,
            MediaSourceDeletion mediaSourceDeletion,
            LocalStrmDeletion localStrmDeletion,
            MediaDeletionTaskMapper taskMapper,
            ObjectMapper objectMapper
    ) {
        this.authService = authService;
        this.catalogService = catalogService;
        this.embyClient = embyClient;
        this.mediaSourceDeletion = mediaSourceDeletion;
        this.localStrmDeletion = localStrmDeletion;
        this.taskMapper = taskMapper;
        this.objectMapper = objectMapper;
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
        MediaDeletionTask task = new MediaDeletionTask();
        task.setId(UUID.randomUUID().toString());
        task.setLibrary(scope.requestValue());
        task.setItemId(item.id());
        task.setSeasonId(plan.seasonId());
        task.setSeasonNumber(plan.seasonNumber());
        task.setTitle(item.title());
        task.setTargetLabel(plan.targetLabel());
        task.setStatus("PENDING");
        task.setStage("DELETING_CLOUD");
        task.setSourcePaths(json(plan.sourcePaths()));
        task.setStrmPaths(json(plan.strmPaths()));
        task.setEmbyItemIds(json(plan.embyItemIds()));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(task.getCreatedAt());
        taskMapper.insert(task);
        return response(task);
    }

    public List<AdminMediaDeletionTaskResponse> listTasks() {
        authService.requireAdminUser();
        return taskMapper.listRecent().stream().map(this::response).toList();
    }

    public AdminMediaDeletionTaskResponse retry(String taskId) {
        authService.requireAdminUser();
        MediaDeletionTask task = requireTask(taskId);
        if (!"FAILED".equals(task.getStatus())) {
            throw new BusinessException(ErrorCode.CONFLICT, "只有失败的删除任务可以重试", HttpStatus.CONFLICT);
        }
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
            saveStage(task, "DELETING_CLOUD");
            List<String> sourcePaths = read(task.getSourcePaths());
            List<String> strmPaths = read(task.getStrmPaths());
            List<String> sourceDeletionTargets = sourcePaths;
            List<String> strmDeletionTargets = strmPaths;
            if (isCollectionTask(task)) {
                sourceDeletionTargets = collectionDeletionTargets(task.getTitle(), sourcePaths);
                strmDeletionTargets = collectionDeletionTargets(task.getTitle(), strmPaths);
            }
            mediaSourceDeletion.delete(sourceDeletionTargets, sourceDirectoryName(task));

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
        requirePaths(deletionItem.mediaSourcePaths(), List.of(deletionItem.path()));
        return new DeletionPlan(
                null, null, "整部作品",
                deletionItem.mediaSourcePaths(),
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
        requirePaths(List.copyOf(sources), List.copyOf(strmPaths));
        return new DeletionPlan(
                null, null, COLLECTION_TARGET_LABEL,
                List.copyOf(sources), List.copyOf(strmPaths), List.copyOf(embyIds)
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
            requireBelow(season.path(), seriesPath, "季度 STRM 路径不在剧集目录内");
            Path seasonPath = Path.of(season.path()).toAbsolutePath().normalize();
            List<EmbyDeletionItem> episodes = embyClient.listSeasonEpisodesForDeletion(season.id());
            strmPaths.add(season.path());
            embyIds.add(season.id());
            for (EmbyDeletionItem episode : episodes) {
                requireBelow(episode.path(), seasonPath, "剧集 STRM 路径不在季度目录内");
                sources.addAll(episode.mediaSourcePaths());
                strmPaths.add(episode.path());
                embyIds.add(episode.id());
            }
        }
        if (!StringUtils.hasText(seasonId)) {
            strmPaths.add(seriesItem.path());
            embyIds.add(series.id());
        }
        requirePaths(List.copyOf(sources), List.copyOf(strmPaths));
        EmbyDeletionItem selectedSeason = targets.size() == 1 ? targets.get(0) : null;
        return new DeletionPlan(
                selectedSeason == null ? null : selectedSeason.id(),
                selectedSeason == null ? null : selectedSeason.indexNumber(),
                selectedSeason == null ? "整部剧集" : selectedSeason.name(),
                List.copyOf(sources),
                List.copyOf(strmPaths),
                List.copyOf(embyIds)
        );
    }

    private void requirePaths(List<String> sourcePaths, List<String> strmPaths) {
        if (sourcePaths.isEmpty() || sourcePaths.stream().anyMatch(path -> !StringUtils.hasText(path))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Emby 未返回完整的媒体源路径");
        }
        if (strmPaths.isEmpty() || strmPaths.stream().anyMatch(path -> !StringUtils.hasText(path))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Emby 未返回完整的 STRM 路径");
        }
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

    private String json(List<String> values) {
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
