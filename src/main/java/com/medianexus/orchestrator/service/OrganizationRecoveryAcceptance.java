package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.emby.EmbyClient;
import com.medianexus.orchestrator.service.settings.targets.IngestTargetRegistry;
import com.medianexus.orchestrator.config.CloudDrive2Properties;
import java.nio.file.Path;
import java.util.*;
import org.springframework.stereotype.Component;

/** Read-only acceptance requires provider identity, season and complete STRM-to-cloud correspondence. */
@Component
public class OrganizationRecoveryAcceptance {
    private final EmbyClient emby;
    private final IngestTargetRegistry targets;
    private final LocalStrmDeletion strm;
    private final CloudDrive2Properties properties;
    private final AutoSymlinkRefreshService refresh;
    private final MovieSeriesFileRenameService names;

    public OrganizationRecoveryAcceptance(EmbyClient emby, IngestTargetRegistry targets, LocalStrmDeletion strm,
            CloudDrive2Properties properties, AutoSymlinkRefreshService refresh, MovieSeriesFileRenameService names) {
        this.emby = emby; this.targets = targets; this.strm = strm; this.properties = properties;
        this.refresh = refresh; this.names = names;
    }

    public AutoSymlinkRefreshService.RefreshOutcome refresh(String type, String path) {
        return refresh.submitOrganizationRefresh(type.equals("movie") ? "MOVIE" : "TV", path);
    }

    public void validateAdoptionTarget(String type, String path, int tmdbId, Integer season) {
        var binding = targets.bindingForPath(type.equals("movie") ? "MOVIE" : "TV", path)
                .orElseThrow(() -> new IllegalStateException("接管需要已登记媒体库映射"));
        String local = binding.strmRootPath() + path.substring(binding.rootPath().length());
        emby.requireOrganizationPathUnconflicted(binding.embyLibraryId(), local, tmdbId, season);
    }

    @org.springframework.beans.factory.annotation.Autowired
    private com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskLogMapper movieLogs;
    @org.springframework.beans.factory.annotation.Autowired
    private com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskLogMapper seriesLogs;

    @org.springframework.beans.factory.annotation.Autowired
    private com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper movieTasks;
    @org.springframework.beans.factory.annotation.Autowired
    private com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper seriesTasks;
    @org.springframework.beans.factory.annotation.Autowired
    private IngestTaskNotifier notifier;

    @org.springframework.beans.factory.annotation.Autowired
    private OrganizationMediaReadability readability;

    /** Original success commits before journal COMPLETE/claim release. Re-entry is idempotent after a lost journal checkpoint. */
    @org.springframework.transaction.annotation.Transactional
    public void recordCompletion(String type, String id, OrganizationRecoveryJournal.Payload payload) {
        boolean normal = payload.taskEvidence() != null && "ORGANIZING".equals(payload.taskEvidence().originalStatus());
        if (type.equals("movie")) {
            var task = movieTasks.selectById(id);
            if (normal && (task == null || !Set.of("ORGANIZING", "INTERRUPTED", "SUCCEEDED").contains(task.getStatus())))
                throw new IllegalStateException("原正常任务状态已变化，保留历史并停止完成检查点");
            if (normal && task != null && Set.of("ORGANIZING", "INTERRUPTED").contains(task.getStatus())) {
                int changed = movieTasks.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.medianexus.orchestrator.model.MovieMagnetIngestTask>()
                        .eq(com.medianexus.orchestrator.model.MovieMagnetIngestTask::getId, id)
                        .eq(com.medianexus.orchestrator.model.MovieMagnetIngestTask::getStatus, task.getStatus())
                        .set(com.medianexus.orchestrator.model.MovieMagnetIngestTask::getStatus, "SUCCEEDED")
                        .set(com.medianexus.orchestrator.model.MovieMagnetIngestTask::getStage, "succeeded")
                        .set(com.medianexus.orchestrator.model.MovieMagnetIngestTask::getOrganizedCount, payload.organized())
                        .set(com.medianexus.orchestrator.model.MovieMagnetIngestTask::getSkippedCount, payload.skipped())
                        .set(com.medianexus.orchestrator.model.MovieMagnetIngestTask::getErrorMessage, null)
                        .set(com.medianexus.orchestrator.model.MovieMagnetIngestTask::getFinishedAt, java.time.LocalDateTime.now()));
                if (changed != 1) throw new IllegalStateException("原任务完成检查点发生并发变化");
                afterCommit(() -> notifier.movieCompleted(task));
            }
            if (movieLogs.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog>()
                    .eq(com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog::getTaskId, id)
                    .eq(com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog::getStage, "organization_recovery_complete")) == 0) {
                var log = new com.medianexus.orchestrator.model.MovieMagnetIngestTaskLog();
                log.setTaskId(id); log.setLevel("INFO"); log.setStage("organization_recovery_complete");
                log.setMessage("持久整理已严格验收；保留原失败历史");
                log.setCreatedAt(java.time.LocalDateTime.now()); movieLogs.insert(log);
            }
        } else {
            var task = seriesTasks.selectById(id);
            if (normal && (task == null || !Set.of("ORGANIZING", "INTERRUPTED", "SUCCEEDED").contains(task.getStatus())))
                throw new IllegalStateException("原正常任务状态已变化，保留历史并停止完成检查点");
            if (normal && task != null && Set.of("ORGANIZING", "INTERRUPTED").contains(task.getStatus())) {
                int changed = seriesTasks.update(new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<com.medianexus.orchestrator.model.SeriesMagnetIngestTask>()
                        .eq(com.medianexus.orchestrator.model.SeriesMagnetIngestTask::getId, id)
                        .eq(com.medianexus.orchestrator.model.SeriesMagnetIngestTask::getStatus, task.getStatus())
                        .set(com.medianexus.orchestrator.model.SeriesMagnetIngestTask::getStatus, "SUCCEEDED")
                        .set(com.medianexus.orchestrator.model.SeriesMagnetIngestTask::getStage, "succeeded")
                        .set(com.medianexus.orchestrator.model.SeriesMagnetIngestTask::getOrganizedCount, payload.organized())
                        .set(com.medianexus.orchestrator.model.SeriesMagnetIngestTask::getSkippedCount, payload.skipped())
                        .set(com.medianexus.orchestrator.model.SeriesMagnetIngestTask::getErrorMessage, null)
                        .set(com.medianexus.orchestrator.model.SeriesMagnetIngestTask::getFinishedAt, java.time.LocalDateTime.now()));
                if (changed != 1) throw new IllegalStateException("原任务完成检查点发生并发变化");
                afterCommit(() -> notifier.seriesCompleted(task));
            }
            if (seriesLogs.selectCount(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog>()
                    .eq(com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog::getTaskId, id)
                    .eq(com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog::getStage, "organization_recovery_complete")) == 0) {
                var log = new com.medianexus.orchestrator.model.SeriesMagnetIngestTaskLog();
                log.setTaskId(id); log.setLevel("INFO"); log.setStage("organization_recovery_complete");
                log.setMessage("持久整理已严格验收；保留原失败历史");
                log.setCreatedAt(java.time.LocalDateTime.now()); seriesLogs.insert(log);
            }
        }
    }

    private void afterCommit(Runnable notification) {
        Runnable once = () -> {
            try { notification.run(); }
            catch (RuntimeException unavailable) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("整理完成通知不可用；不重放成功检查点"); }
        };
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { once.run(); }
                    });
        } else once.run();
    }

    public void verify(String type, OrganizationRecoveryJournal.Payload payload) {
        String category = type.equals("movie") ? "MOVIE" : "TV";
        String directory = payload.snapshot().plan().targetDirectory();
        var binding = targets.bindingForPath(category, directory)
                .orElseThrow(() -> new IllegalStateException("严格验收需要已登记 STRM 与 Emby 映射"));
        if (directory.equals(binding.rootPath()) || !directory.startsWith(binding.rootPath() + "/"))
            throw new IllegalStateException("目标目录不属于冻结目标绑定");
        Path local = Path.of(binding.strmRootPath() + directory.substring(binding.rootPath().length())).normalize();
        Set<String> expected = new HashSet<>();
        for (var file : payload.snapshot().files()) {
            if (names.isVideo(com.medianexus.orchestrator.service.organization.LibraryOrganizationPlan.fileName(file.finalPath())))
                expected.add(payload.snapshot().cloudPaths().get(file.finalPath()));
        }
        if (expected.size() != payload.videos() || expected.isEmpty()) throw new IllegalStateException("冻结视频文件表不完整");
        var map = targets.cloudMediaPaths(properties.getMediaSourcePathPrefix(), properties.getCloudDrivePathPrefix());
        Set<String> covered = new HashSet<>();
        Set<String> localPaths = new HashSet<>();
        for (String link : strm.strmFiles(local)) {
            String cloud = map.apply(strm.resolveMediaSourcePath(link));
            if (expected.contains(cloud)) {
                if (!covered.add(cloud)) throw new IllegalStateException("STRM 文件重复映射");
                localPaths.add(link);
            }
        }
        if (!covered.equals(expected)) throw new IllegalStateException("STRM 尚未覆盖全部冻结文件路径");
        Set<String> embyPaths = new HashSet<>();
        if (type.equals("movie")) {
            for (var item : emby.findMoviesByTmdbId(payload.tmdbId())) {
                if (localPaths.contains(item.path())) {
                    emby.requireOrganizationIdentity(item.id(), payload.tmdbId(), null);
                    embyPaths.add(item.path());
                }
            }
        } else {
            for (var series : emby.findSeriesByTmdbId(payload.tmdbId())) {
                for (var season : emby.listSeriesSeasons(series.id())) {
                    if (!Objects.equals(season.indexNumber(), payload.season()) || !local.toString().equals(season.path())) continue;
                    emby.requireOrganizationIdentity(season.id(), payload.tmdbId(), payload.season());
                    for (var episode : emby.listSeasonEpisodesForDeletion(season.id())) {
                        if (localPaths.contains(episode.path())) embyPaths.add(episode.path());
                    }
                }
            }
        }
        if (!embyPaths.equals(localPaths)) throw new IllegalStateException("Emby 身份、季度或文件对应关系尚未核实；禁止自动覆盖身份");
        Map<String, Long> expectedBytes = new HashMap<>();
        for (var file : payload.snapshot().files())
            expectedBytes.put(payload.snapshot().cloudPaths().get(file.finalPath()), file.bytes());
        for (String link : localPaths) {
            String source = strm.resolveMediaSourcePath(link);
            String cloud = map.apply(source);
            if (!targets.ownsMediaSourcePath(source, properties.getMediaSourcePathPrefix()))
                throw new IllegalStateException("MEDIA_READ_PATH_OUTSIDE_TARGET");
            readability.requireReadable(source, expectedBytes.get(cloud));
        }
    }
}
