package com.medianexus.orchestrator.dto.quark.request;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

/** Request used by the TV/variety share-tree planner and batch submit flow. */
@Schema(description = "电视剧或综艺 Quark 多来源季度规划请求")
public record QuarkMultiSourceRequest(
        @NotBlank(message = "Quark 分享链接不能为空")
        @JsonProperty("share_url") String shareUrl,
        @NotBlank(message = "标题不能为空")
        String title,
        @JsonProperty("original_title") String originalTitle,
        @JsonProperty("tmdb_id") Integer tmdbId,
        @JsonProperty("preview_id") String previewId,
        @JsonProperty("follow_updates_enabled") boolean followUpdatesEnabled,
        List<@Valid QuarkSourceSelectionRequest> sources,
        @JsonProperty("source_type") String sourceType
) {

    public QuarkMultiSourceRequest(
            String shareUrl,
            String title,
            String originalTitle,
            Integer tmdbId,
            String previewId,
            boolean followUpdatesEnabled,
            List<@Valid QuarkSourceSelectionRequest> sources
    ) {
        this(shareUrl, title, originalTitle, tmdbId, previewId, followUpdatesEnabled, sources, "MANUAL_QUARK");
    }

    public QuarkMultiSourceRequest {
        sources = sources == null ? List.of() : List.copyOf(sources);
        sourceType = sourceType == null || sourceType.isBlank() ? "MANUAL_QUARK" : sourceType.trim().toUpperCase();
    }
}
