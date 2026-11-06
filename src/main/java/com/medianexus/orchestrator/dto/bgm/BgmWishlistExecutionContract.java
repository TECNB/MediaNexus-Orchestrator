package com.medianexus.orchestrator.dto.bgm;

import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Item;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Reason;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Source;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.TaskLink;
import com.medianexus.orchestrator.service.UserActionQuotaService.DailyContentCreateQuota;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;

/** 手动预览只授予所展示目标的一次提交机会；估算不预留额度。 */
public final class BgmWishlistExecutionContract {
    private BgmWishlistExecutionContract() {
    }

    @Schema(description = "单部或明确勾选批量，不受来源默认目标数截断")
    public record PreviewInput(@NotEmpty List<@NotNull @Positive Long> subjectIds) {
    }

    @Schema(description = "确认服务端保存的预览，不接收替换作品或目标")
    public record ExecuteInput(@NotBlank String previewId) {
    }

    @Schema(description = "一次性预览；配置变化须重新预览，没有任意有效期限制")
    public record Preview(String id, Source source, Instant createdAt,
                          @Schema(description = "SINGLE、SELECTED 或 LATEST") String action, List<Item> items,
                          List<ItemOutcome> skipped, DailyContentCreateQuota quota,
                          @Schema(description = "按当前真实剩余额度估算，不保证创建成功") int estimatedCreations) {
    }

    @Schema(description = "逐项执行明细；创建不等于下载完成或已在库")
    public record ItemOutcome(Item item,
                              @Schema(description = "CREATED、FAILED 或 SKIPPED") String outcome,
                              @Schema(description = "创建成功时为 null", nullable = true) Reason reason,
                              @Schema(description = "关联任务；未创建且无活动任务时为 null", nullable = true) TaskLink task) {
    }

    @Schema(description = "持久执行记录，时间为 UTC Instant，界面按北京时间显示")
    public record Run(String id, String sourceId, Source source,
                      @Schema(description = "SINGLE、SELECTED、LATEST 或 DAILY") String action,
                      @Schema(description = "MANUAL 或 SCHEDULED") String triggerType,
                      @Schema(description = "手动管理员 ID；系统调度为 null", nullable = true) Long actorUserId,
                      Long ownerUserId, Instant startedAt,
                      @Schema(description = "执行中为 null", nullable = true) Instant finishedAt,
                      @Schema(description = "RUNNING、SUCCEEDED、PARTIAL_SUCCESS 或 FAILED") String status,
                      List<ItemOutcome> created, List<ItemOutcome> failed, List<ItemOutcome> skipped,
                      DailyContentCreateQuota quotaBefore, DailyContentCreateQuota quotaAfter,
                      @Schema(description = "整轮刷新或配置等失败；正常完成为 null", nullable = true) Reason reason) {
    }

    @Schema(description = "按开始时间倒序的执行历史")
    public record Runs(List<Run> items, long total, int page, int pageSize) {
    }
}
