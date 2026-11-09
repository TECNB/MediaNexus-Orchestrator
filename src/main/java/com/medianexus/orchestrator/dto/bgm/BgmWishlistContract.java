package com.medianexus.orchestrator.dto.bgm;

import com.medianexus.orchestrator.dto.admin.response.AdminMediaDeletionTaskResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/** BGM 想看与目录资格契约；eligible 不表示已找到发布资源或已预留额度。 */
public final class BgmWishlistContract {
    private BgmWishlistContract() {
    }

    public enum Provider { BGM, DOUBAN }

    @Schema(description = "独立想看来源配置；保存不创建任务，首次启用须完整刷新并建立基线")
    public record SourceInput(
            @NotBlank @Size(max = 256) @Schema(description = "BGM 用户名／ID，或豆瓣 ID／公开主页／想看链接") String bgmUsername,
            @NotNull @Positive Long ownerUserId,
            @Schema(description = "默认关闭；首次启用先读取完整清单，不创建任务", nullable = true) Boolean enabled,
            @Pattern(regexp = "(?:[01][0-9]|2[0-3]):[0-5][0-9]")
            @Schema(description = "北京时间每日执行时刻，省略为 09:00", nullable = true) String scheduleTime,
            @Min(1) @Schema(description = "每次成功创建目标，省略为 3，不是每日额度", nullable = true) Integer targetCount,
            @Pattern(regexp = "1080p|2160p") @Schema(description = "推荐清晰度，省略为 1080p", nullable = true) String quality,
            @Pattern(regexp = "NEW_ONLY|INCLUDE_EXISTING")
            @Schema(description = "首次范围，省略为 NEW_ONLY；建立基线后不可修改", nullable = true) String initialScope,
            @Schema(description = "想看来源平台，省略时为 BGM") Provider provider
    ) {
        public SourceInput {
            if (provider == null) provider = Provider.BGM;
        }

        public SourceInput(String bgmUsername, Long ownerUserId, Boolean enabled, String scheduleTime,
                           Integer targetCount, String quality, String initialScope) {
            this(bgmUsername, ownerUserId, enabled, scheduleTime, targetCount, quality, initialScope, Provider.BGM);
        }
    }

    @Schema(description = "想看来源；BGM 用户、本站任务归属与当前操作人相互独立")
    public record Source(String id, String bgmUsername, Long ownerUserId, String ownerUsername,
                         boolean enabled, String scheduleTime, int targetCount, String quality,
                         String initialScope, String timezone, boolean baselineInitialized, Provider provider) {
        public Source {
            if (provider == null) provider = Provider.BGM;
        }

        public Source(String id, String bgmUsername, Long ownerUserId, String ownerUsername,
                      boolean enabled, String scheduleTime, int targetCount, String quality,
                      String initialScope, String timezone, boolean baselineInitialized) {
            this(id, bgmUsername, ownerUserId, ownerUsername, enabled, scheduleTime, targetCount, quality,
                    initialScope, timezone, baselineInitialized, Provider.BGM);
        }
    }

    @Schema(description = "上次完整想看清单及实时资格；失败保留快照，不允许以旧清单执行")
    public record Wishlist(Source source, List<Item> items,
                           @Schema(description = "刷新状态：NEVER、SUCCESS、FAILED") String refreshStatus,
                           @Schema(description = "本轮刷新失败说明；其他状态为 null", nullable = true) String refreshError,
                           @Schema(description = "最近尝试时间；尚未尝试为 null", nullable = true) Instant lastAttemptAt,
                           @Schema(description = "最近完整读取成功时间；尚未成功为 null", nullable = true) Instant lastSuccessAt) {
    }

    @Schema(description = "人工选择真实 TMDB 目录；类型标签本身不能代替目录核实")
    public record IdentityInput(@NotBlank @Pattern(regexp = "MOVIE|SERIES") String mediaType,
                                @NotNull @Positive Integer tmdbId,
                                @Positive @Schema(description = "SERIES 必传真实目标季；MOVIE 不传", nullable = true)
                                Integer seasonNumber) {
    }

    @Schema(description = "已核实的电影或剧集型动漫目标身份")
    public record Identity(String mediaType, int tmdbId,
                           @Schema(description = "电影为 null；剧集型动漫为已确认目标季度", nullable = true) Integer seasonNumber,
                           String title, String originalTitle, Integer year) {
    }

    @Schema(description = "独立 BGM 条目、在库资格及删除进度")
    public record Item(long subjectId, String title, String originalTitle, String coverUrl, Integer year,
                       @Schema(description = "上游收藏更新时间，不是首次收藏时间") String updatedAt,
                       @Schema(description = "未确认目录时为 null", nullable = true) Identity identity,
                       @Schema(description = "在库状态：UNKNOWN、ABSENT、PRESENT") String libraryStatus,
                       @Schema(description = "放送或发行状态：UNKNOWN、NOT_YET_AIRED、AIRING、THEATRICAL、AVAILABLE") String availability,
                       boolean eligible, List<Reason> reasons,
                       @Schema(description = "同目标处理中任务；没有时为 null", nullable = true) TaskLink activeTask,
                       boolean automaticCandidate,
                       @Schema(description = "本票尚未查询发布，固定 NOT_CHECKED") String releaseStatus,
                       @Schema(description = "此作品或全局目录目标曾由 BGM 创建的任务关联及当前状态；没有时为 null，失败恢复沿用任务中心", nullable = true)
                       TaskLink associatedTask,
                       @Schema(description = "该目录目标最近一次 BGM 删除任务；没有时为 null", nullable = true)
                       AdminMediaDeletionTaskResponse deletionTask,
                       @Schema(description = "在库目标唯一且无进行中的入库或待处理删除；提交时再次核对媒体库范围")
                       boolean canDelete) {
    }

    @Schema(description = "稳定拒绝原因代码及可展示说明")
    public record Reason(String code, String message) {
    }

    @Schema(description = "现有任务中心关联；创建不表示已在库")
    public record TaskLink(String taskType, String taskId, String status) {
    }

    @Schema(description = "真实 TMDB 搜索候选，不自动确定匹配")
    public record CatalogCandidate(String mediaType, int tmdbId, String title, String originalTitle,
                                   Integer year, String coverUrl) {
    }

    @Schema(description = "真实目录季度，特殊篇不属于本期整季入库目标")
    public record Season(int seasonNumber, String name, Integer episodeCount, String airDate) {
    }
}
