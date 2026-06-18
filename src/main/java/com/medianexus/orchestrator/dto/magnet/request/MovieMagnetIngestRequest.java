package com.medianexus.orchestrator.dto.magnet.request;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

@JsonIgnoreProperties(ignoreUnknown = false)
@Schema(description = "电影 magnet 导入任务创建请求")
public record MovieMagnetIngestRequest(
        @Schema(description = "单条 magnet 链接，必须以 magnet:? 开头")
        String magnet,
        @Schema(description = "电影中文或展示标题；original_title 为空时使用", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        String title,
        @Schema(description = "电影原始标题；优先用于生成目录名", requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true)
        @JsonProperty("original_title")
        String originalTitle,
        @Schema(description = "电影年份")
        Integer year
) {

    @JsonAnySetter
    public void rejectUnknownField(String fieldName, Object ignored) {
        throw new IllegalArgumentException("未知字段: " + fieldName);
    }
}
