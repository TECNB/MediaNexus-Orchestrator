package com.medianexus.orchestrator.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.MovieReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseOpenListIngestRequest;
import com.medianexus.orchestrator.dto.resources.request.SeriesReleaseRecommendationRequest;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.mapper.BgmWishlistTargetTaskMapper;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Reason;
import com.medianexus.orchestrator.mapper.AnimeMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.MovieMagnetIngestTaskMapper;
import com.medianexus.orchestrator.mapper.SeriesMagnetIngestTaskMapper;
import com.medianexus.orchestrator.model.AnimeMagnetIngestTask;
import com.medianexus.orchestrator.model.MovieMagnetIngestTask;
import com.medianexus.orchestrator.model.SeriesMagnetIngestTask;
import com.medianexus.orchestrator.model.User;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** BGM 目录目标到现有发布推荐及入库任务的适配；不改变旧入口的兜底和额度事务。 */
@Service
public class BgmWishlistTaskGateway {

    private static final Logger log = LoggerFactory.getLogger(BgmWishlistTaskGateway.class);
    private static final List<String> ACTIVE_STATUSES = List.of("PENDING", "SUBMITTED", "DOWNLOADING", "ORGANIZING");
    private final MovieMagnetIngestTaskMapper movies;
    private final SeriesMagnetIngestTaskMapper series;
    private final AnimeMagnetIngestTaskMapper anime;
    private final ProwlarrReleaseIngestService releases;
    private final MediaLibraryPresenceService presence;
    private final BgmWishlistSessionLocks locks;
    private final BgmWishlistTargetTaskMapper previousTasks;
    private volatile boolean ledgerReady;

    public BgmWishlistTaskGateway(MovieMagnetIngestTaskMapper movies, SeriesMagnetIngestTaskMapper series,
                                  AnimeMagnetIngestTaskMapper anime, ProwlarrReleaseIngestService releases,
                                  MediaLibraryPresenceService presence, BgmWishlistSessionLocks locks,
                                  BgmWishlistTargetTaskMapper previousTasks) {
        this.movies = movies;
        this.series = series;
        this.anime = anime;
        this.releases = releases;
        this.presence = presence;
        this.locks = locks;
        this.previousTasks = previousTasks;
    }

    public ActiveTask activeTask(Target target) {
        if ("MOVIE".equals(target.mediaType())) {
            MovieMagnetIngestTask task = movies.selectOne(new LambdaQueryWrapper<MovieMagnetIngestTask>()
                    .eq(MovieMagnetIngestTask::getTmdbId, target.tmdbId())
                    .in(MovieMagnetIngestTask::getStatus, ACTIVE_STATUSES).last("LIMIT 1"));
            return task == null ? null : new ActiveTask("MOVIE", task.getId(), task.getStatus());
        }
        SeriesMagnetIngestTask task = series.selectOne(new LambdaQueryWrapper<SeriesMagnetIngestTask>()
                .eq(SeriesMagnetIngestTask::getTmdbId, target.tmdbId())
                .eq(SeriesMagnetIngestTask::getSeasonNumber, target.seasonNumber())
                .in(SeriesMagnetIngestTask::getStatus, ACTIVE_STATUSES).last("LIMIT 1"));
        if (task != null) {
            return new ActiveTask("SERIES", task.getId(), task.getStatus());
        }
        AnimeMagnetIngestTask legacyTask = anime.selectOne(new LambdaQueryWrapper<AnimeMagnetIngestTask>()
                .eq(AnimeMagnetIngestTask::getTmdbId, target.tmdbId())
                .eq(AnimeMagnetIngestTask::getSeasonNumber, target.seasonNumber())
                .in(AnimeMagnetIngestTask::getStatus, ACTIVE_STATUSES).last("LIMIT 1"));
        return legacyTask == null ? null : new ActiveTask("ANIME", legacyTask.getId(), legacyTask.getStatus());
    }

    /** 关联任务的当前状态；执行快照中的初始 PENDING 不能当作实时进度。 */
    public ActiveTask task(String taskType, String taskId) {
        return switch (taskType) {
            case "MOVIE" -> {
                MovieMagnetIngestTask task = movies.selectById(taskId);
                yield task == null ? null : new ActiveTask(taskType, task.getId(), task.getStatus());
            }
            case "SERIES" -> {
                SeriesMagnetIngestTask task = series.selectById(taskId);
                yield task == null ? null : new ActiveTask(taskType, task.getId(), task.getStatus());
            }
            case "ANIME" -> {
                AnimeMagnetIngestTask task = anime.selectById(taskId);
                yield task == null ? null : new ActiveTask(taskType, task.getId(), task.getStatus());
            }
            default -> throw new IllegalArgumentException("不支持的入库任务类型");
        };
    }

    /** 无符合条件的资源返回 null；上游故障继续抛出，不能冒充无资源。 */
    public ProwlarrReleaseItemResponse recommend(Target target, String quality) {
        try {
            if ("MOVIE".equals(target.mediaType())) {
                return releases.recommendMovieReleaseForAutomation(new MovieReleaseRecommendationRequest(
                        target.tmdbId(), null, target.title(), target.originalTitle(), target.year(), quality)).item();
            }
            return releases.recommendSeriesReleaseForAutomation(new SeriesReleaseRecommendationRequest(
                    null, target.tmdbId(), null, target.title(), target.originalTitle(), target.seasonNumber(), quality)).item();
        } catch (ProwlarrReleaseIngestService.NoMatchingReleaseException noMatch) {
            return null;
        }
    }

    /** BGM 自动操作保留整个推荐集合，以当前顺序优先选择标题含汉字的发布。 */
    public List<ProwlarrReleaseItemResponse> candidates(Target target, String quality,
            java.util.function.Consumer<ProwlarrReleaseIngestService.ReleaseSearchProgress> progress) {
        try {
            if ("MOVIE".equals(target.mediaType())) {
                return releases.recommendMovieReleaseForAutomation(new MovieReleaseRecommendationRequest(
                        target.tmdbId(), null, target.title(), target.originalTitle(), target.year(), quality), progress).items();
            }
            return releases.recommendSeriesReleaseForAutomation(new SeriesReleaseRecommendationRequest(
                    null, target.tmdbId(), null, target.title(), target.originalTitle(), target.seasonNumber(), quality), progress).items();
        } catch (ProwlarrReleaseIngestService.NoMatchingReleaseException noMatch) { return List.of(); }
    }

    public com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Task currentTask(String type, String id) {
        return switch (type) {
            case "MOVIE" -> {
                var task = movies.selectById(id);
                yield task == null ? null : new com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Task(
                        type, id, task.getStatus(), task.getStage(), task.getErrorMessage(), null);
            }
            case "SERIES" -> {
                var task = series.selectById(id);
                yield task == null ? null : new com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Task(
                        type, id, task.getStatus(), task.getStage(), task.getErrorMessage(), null);
            }
            case "ANIME" -> {
                var task = anime.selectById(id);
                yield task == null ? null : new com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Task(
                        type, id, task.getStatus(), task.getStage(), task.getErrorMessage(), null);
            }
            default -> throw new IllegalArgumentException("不支持的入库任务类型");
        };
    }

    /**
     * MySQL 会话锁覆盖跨来源、跨成员和多个实例的 BGM 提交；持锁连接不承载任务事务，
     * 因而不会把旧链路已扣额度的创建失败改成退额。所有 BGM 创建均经此边界。
     */
    public ActiveTask create(Target target, ProwlarrReleaseItemResponse release, User owner) {
        String lockName = "bgm-target:" + target.mediaType() + ":" + target.tmdbId()
                + (target.seasonNumber() == null ? "" : ":" + target.seasonNumber());
        try {
            return locks.withLock(lockName,
                    () -> new EligibilityRefusal(new Reason("TARGET_BUSY", "同一目录目标正在提交，请稍后刷新任务"), null, null),
                    () -> createLocked(target, release, owner));
        } catch (BgmWishlistSessionLocks.LockUnavailableException exception) {
            log.warn("BGM 目录目标提交锁不可用 target={}", targetKey(target), exception);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "目录目标提交锁不可用，本次未继续创建", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /** 跨来源、条目和成员保留原始 BGM 任务关联；终态也只能在任务中心恢复。 */
    public ActiveTask previousTask(Target target) {
        ensureLedger();
        ActiveTask saved = previousTasks.select(targetKey(target));
        if (saved == null) return null;
        ActiveTask current = task(saved.taskType(), saved.taskId());
        return current == null ? new ActiveTask(saved.taskType(), saved.taskId(), "UNKNOWN") : current;
    }

    private ActiveTask createLocked(Target target, ProwlarrReleaseItemResponse release, User owner) {
        ActiveTask previous = previousTask(target);
        if (previous != null) {
            throw new EligibilityRefusal(new Reason("PREVIOUS_TASK", "此目录目标已有 BGM 创建记录，失败或中断请在任务中心恢复"), null, previous);
        }
        ActiveTask active = activeTask(target);
        if (active != null) {
            throw new EligibilityRefusal(new Reason("ACTIVE_TASK", "同一目录目标已有处理中任务"), null, active);
        }
        var check = presence.checkForAutomation(
                "MOVIE".equals(target.mediaType()) ? "movie" : "series", target.tmdbId(), null, target.seasonNumber());
        if (!check.checkAvailable()) {
            throw new EligibilityRefusal(new Reason("LIBRARY_UNKNOWN", "在库检查不可用，本次跳过"), "UNKNOWN", null);
        }
        if (check.exists()) {
            throw new EligibilityRefusal(new Reason("ALREADY_PRESENT", "目标电影或季度已在库"), "PRESENT", null);
        }
        ActiveTask created;
        if ("MOVIE".equals(target.mediaType())) {
            var task = releases.ingestSelectedMovieForOwner(new MovieReleaseOpenListIngestRequest(
                    target.title(), target.originalTitle(), target.year(), release.title(), release.indexer(),
                    release.size(), release.indexerId(), release.downloadRef(), release.resolutionTags(),
                    release.dynamicRangeTags(), target.tmdbId()), owner);
            created = new ActiveTask("MOVIE", task.id(), task.status());
        } else {
            var task = releases.ingestSelectedAnimeForOwner(new SeriesReleaseOpenListIngestRequest(
                    target.title(), target.originalTitle(), target.seasonNumber(), "ANIME", release.title(),
                    release.indexer(), release.size(), release.indexerId(), release.downloadRef(),
                    release.resolutionTags(), release.dynamicRangeTags(), target.tmdbId()), owner);
            created = new ActiveTask("SERIES", task.id(), task.status());
        }
        try {
            previousTasks.insert(targetKey(target), created);
        } catch (RuntimeException exception) {
            // 不把任务已创建、关联写入失败冒充创建失败，也不退还旧链路额度。
            throw new CreatedTaskRecordException(created, exception);
        }
        return created;
    }

    private String targetKey(Target target) {
        return target.mediaType() + ":" + target.tmdbId()
                + (target.seasonNumber() == null ? "" : ":" + target.seasonNumber());
    }

    private void ensureLedger() {
        if (!ledgerReady) {
            synchronized (this) {
                if (!ledgerReady) {
                    previousTasks.createTableIfNotExists();
                    ledgerReady = true;
                }
            }
        }
    }

    /** 最终资格拒绝不是外部任务创建失败；只携带可公开的领域原因与当前观察。 */
    public static class EligibilityRefusal extends RuntimeException {
        private final Reason reason;
        private final String libraryStatus;
        private final ActiveTask task;

        public EligibilityRefusal(Reason reason, String libraryStatus, ActiveTask task) {
            super(reason.message());
            this.reason = reason;
            this.libraryStatus = libraryStatus;
            this.task = task;
        }

        public Reason reason() { return reason; }
        public String libraryStatus() { return libraryStatus; }
        public ActiveTask task() { return task; }
    }

    public static class CreatedTaskRecordException extends RuntimeException {
        private final ActiveTask task;

        private CreatedTaskRecordException(ActiveTask task, RuntimeException cause) {
            super("BGM 任务已创建，但目录目标关联保存失败", cause);
            this.task = task;
        }

        public ActiveTask task() { return task; }
    }

    public record Target(String mediaType, int tmdbId, Integer seasonNumber, String title,
                         String originalTitle, Integer year) {
    }

    public record ActiveTask(String taskType, String taskId, String status) {
    }
}
