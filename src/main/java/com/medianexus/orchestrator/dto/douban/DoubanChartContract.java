package com.medianexus.orchestrator.dto.douban;

import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistOperationContract.Entry;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

/** 公共榜单有独立配置与成员关系；只复用操作条目的三阶段展示契约。 */
public final class DoubanChartContract {
    private DoubanChartContract() {}

    public enum ChartId {
        movie_hot_gaia("热门电影", "MOVIE", 20), tv_hot("热门电视剧", "SERIES", 20),
        movie_weekly_best("一周口碑电影榜", "MOVIE", 0), movie_top250("豆瓣 Top250", "MOVIE", 0);
        private final String label;
        private final String mediaType;
        private final int limit;
        ChartId(String label, String mediaType, int limit) { this.label = label; this.mediaType = mediaType; this.limit = limit; }
        public String label() { return label; }
        public String mediaType() { return mediaType; }
        public int limit() { return limit; }
    }

    @Schema(description = "白名单目录字段；rank 为周榜/Top250 显式排名，热门榜为来源列表位置；未知评分、人数、榜位保持 null")
    public record Subject(long id, String title, String originalTitle, String coverUrl, Integer year,
                          String mediaType, Double score, Long votes, Integer rank, String progress, Instant observedAt) {}
    @Schema(description = "具体检查日期的来源进度，不表示完结发生日期")
    public record Observation(Instant checkedAt, String progress) {}
    @Schema(description = "本榜当前或曾属候选；排除跨刷新持久生效")
    public record Candidate(Subject subject, boolean current, Instant firstSeenAt, Instant lastSeenAt,
                            Integer lastRank, boolean excluded, List<Observation> observations,
                            Identity identity, Reason reason) {}
    @Schema(description = "独立公共榜单配置与最后成功清单；抓取失败保留旧数据")
    public record Board(ChartId id, String label, boolean autoEnabled, Long ownerUserId,
                        String refreshStatus, String refreshError, Instant lastAttemptAt, Instant lastSuccessAt,
                        Instant nextRunAt, List<Candidate> candidates) {}
    @Schema(description = "四榜共享业务限额；null 表示不限制，不改变外部请求和安全预算")
    public record Settings(Integer dailyCheckLimit, Integer dailyCreateLimit, int checkedToday, int createdToday,
                           String scheduleTime, String timezone, Instant nextRunAt) {}
    @Schema(description = "公共榜单总览；普通成员可读，变更仅管理员")
    public record Overview(List<Board> boards, Settings settings) {}
    @Schema(description = "配置本榜自动入库；开启时绑定实际管理员执行身份")
    public record AutoInput(@NotNull Boolean enabled) {}
    @Schema(description = "共享业务上限；字段为 null 时不限制")
    public record LimitsInput(@Positive Integer dailyCheckLimit, @Positive Integer dailyCreateLimit) {}
    @Schema(description = "候选排除或解除，不更改既有操作证据")
    public record ExclusionInput(@NotNull Boolean excluded) {}
    @Schema(description = "公共单榜异步操作；创建任务不等于已入库，记录持久保留当轮范围和原因")
    public record Operation(String id, ChartId chartId, String chartLabel, String triggerType, Long actorUserId,
                            Instant startedAt, Instant updatedAt, String status, List<Entry> entries, Reason reason) {}
    @Schema(description = "按时间倒序分页的公共榜单操作")
    public record Operations(List<Operation> items, long total, int page, int pageSize) {}
}
