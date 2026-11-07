package com.medianexus.orchestrator.dto.bgm;

import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

/** 异步操作持久记录；任务进度读取原任务，不维护第二份下载状态。 */
public final class BgmWishlistOperationContract {
    private BgmWishlistOperationContract() {}

    @Schema(description = "确认非中文例外中的具体候选；密钥绑定当前记录的具体发布")
    public record CandidateInput(@NotNull @Positive Long subjectId, @NotBlank String candidateKey) {}

    @Schema(description = "某个条目的重新搜索或暂不处理请求")
    public record SubjectInput(@NotNull @Positive Long subjectId) {}

    @Schema(description = "从既有发布选择页人工补充来源；目标必须与原记录一致")
    public record SupplyInput(@NotNull @Positive Long subjectId, @NotNull @jakarta.validation.Valid IdentityInput identity,
                              @NotNull ProwlarrReleaseItemResponse release) {}

    @Schema(description = "真实发布查询进度；条目数不是合格候选数")
    public record Search(String query, String source, String status, long elapsedMs, Integer resultCount) {}

    @Schema(description = "关联任务实时摘要；任务尚不存在时为 null")
    public record Task(String taskType, String taskId, String status, String stage, String error,
                       @Schema(description = "异常通知：DISABLED、PENDING、SENT、FAILED、UNKNOWN；未触发为 null", nullable = true) String notification) {}

    @Schema(description = "带稳定身份的推荐发布；确认必须提交此身份")
    public record Candidate(String key, ProwlarrReleaseItemResponse release) {}

    @Schema(description = "作品在搜索资源、选择发布、入库任务三个环节中的持久结果")
    public record Entry(Item item, String status, Instant startedAt, List<Search> searches,
                        List<Candidate> candidates, Candidate selected,
                        Task task, Reason reason, String notification) {}

    @Schema(description = "持久异步操作；RUNNING、WAITING、SUCCEEDED、PARTIAL_SUCCESS、FAILED 或 INTERRUPTED")
    public record Operation(String id, Source source, String action, String triggerType, Long actorUserId,
                            Instant startedAt, Instant updatedAt, String status, List<Long> requestedIds,
                            List<Entry> entries, Reason reason) {}

    @Schema(description = "按新到旧分页的异步操作")
    public record Operations(List<Operation> items, long total, int page, int pageSize) {}
}
