package com.medianexus.orchestrator.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "medianexus.subtitle-upload")
public class SubtitleUploadProperties {

    private volatile java.util.function.Supplier<SubtitleUploadProperties> settingsResolver;

    /** 每次业务操作捕获一份配置；快照对象本身不再解析数据库覆盖。 */
    public SubtitleUploadProperties snapshot() {
        return settingsResolver == null ? this : settingsResolver.get();
    }

    public void setSettingsResolver(java.util.function.Supplier<SubtitleUploadProperties> resolver) {
        this.settingsResolver = resolver;
    }

    @NotEmpty
    private List<String> allowedExtensions = new ArrayList<>(List.of(".ass", ".ssa", ".srt", ".sup", ".vtt"));

    @NotNull
    private DataSize maxUploadSize = DataSize.ofMegabytes(50);

    @Min(1)
    private int maxEntryCount = 100;

    @NotNull
    private DataSize maxTextSubtitleSize = DataSize.ofMegabytes(10);

    @NotNull
    private DataSize maxSupSize = DataSize.ofMegabytes(50);

    @NotNull
    private DataSize maxTotalExtractedSize = DataSize.ofMegabytes(150);

    @Min(1)
    private int maxCompressionRatio = 100;

    public List<String> getAllowedExtensions() {
        return settingsResolver == null ? allowedExtensions : snapshot().getAllowedExtensions();
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public DataSize getMaxUploadSize() {
        return settingsResolver == null ? maxUploadSize : snapshot().getMaxUploadSize();
    }

    public void setMaxUploadSize(DataSize maxUploadSize) {
        this.maxUploadSize = maxUploadSize;
    }

    public int getMaxEntryCount() {
        return settingsResolver == null ? maxEntryCount : snapshot().getMaxEntryCount();
    }

    public void setMaxEntryCount(int maxEntryCount) {
        this.maxEntryCount = maxEntryCount;
    }

    public DataSize getMaxTextSubtitleSize() {
        return settingsResolver == null ? maxTextSubtitleSize : snapshot().getMaxTextSubtitleSize();
    }

    public void setMaxTextSubtitleSize(DataSize maxTextSubtitleSize) {
        this.maxTextSubtitleSize = maxTextSubtitleSize;
    }

    public DataSize getMaxSupSize() {
        return settingsResolver == null ? maxSupSize : snapshot().getMaxSupSize();
    }

    public void setMaxSupSize(DataSize maxSupSize) {
        this.maxSupSize = maxSupSize;
    }

    public DataSize getMaxTotalExtractedSize() {
        return settingsResolver == null ? maxTotalExtractedSize : snapshot().getMaxTotalExtractedSize();
    }

    public void setMaxTotalExtractedSize(DataSize maxTotalExtractedSize) {
        this.maxTotalExtractedSize = maxTotalExtractedSize;
    }

    public int getMaxCompressionRatio() {
        return settingsResolver == null ? maxCompressionRatio : snapshot().getMaxCompressionRatio();
    }

    public void setMaxCompressionRatio(int maxCompressionRatio) {
        this.maxCompressionRatio = maxCompressionRatio;
    }
}
