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

    @NotEmpty
    private List<String> allowedExtensions = new ArrayList<>(List.of(".ass", ".srt", ".sup"));

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
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public DataSize getMaxUploadSize() {
        return maxUploadSize;
    }

    public void setMaxUploadSize(DataSize maxUploadSize) {
        this.maxUploadSize = maxUploadSize;
    }

    public int getMaxEntryCount() {
        return maxEntryCount;
    }

    public void setMaxEntryCount(int maxEntryCount) {
        this.maxEntryCount = maxEntryCount;
    }

    public DataSize getMaxTextSubtitleSize() {
        return maxTextSubtitleSize;
    }

    public void setMaxTextSubtitleSize(DataSize maxTextSubtitleSize) {
        this.maxTextSubtitleSize = maxTextSubtitleSize;
    }

    public DataSize getMaxSupSize() {
        return maxSupSize;
    }

    public void setMaxSupSize(DataSize maxSupSize) {
        this.maxSupSize = maxSupSize;
    }

    public DataSize getMaxTotalExtractedSize() {
        return maxTotalExtractedSize;
    }

    public void setMaxTotalExtractedSize(DataSize maxTotalExtractedSize) {
        this.maxTotalExtractedSize = maxTotalExtractedSize;
    }

    public int getMaxCompressionRatio() {
        return maxCompressionRatio;
    }

    public void setMaxCompressionRatio(int maxCompressionRatio) {
        this.maxCompressionRatio = maxCompressionRatio;
    }
}
