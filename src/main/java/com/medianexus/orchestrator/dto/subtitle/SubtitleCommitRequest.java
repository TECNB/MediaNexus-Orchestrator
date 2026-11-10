package com.medianexus.orchestrator.dto.subtitle;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

@Schema(description = "确认选择的字幕；至少一项且 ID 不重复；overwrite 默认为 false")
public record SubtitleCommitRequest(List<Selection> files, boolean overwrite) {
    @Schema(description = "预览文件 ID；可覆盖语言，歧义时必须指定目标目录中的实际视频文件名")
    public record Selection(String id, String language, @JsonProperty("video_name") String videoName) { }
}
