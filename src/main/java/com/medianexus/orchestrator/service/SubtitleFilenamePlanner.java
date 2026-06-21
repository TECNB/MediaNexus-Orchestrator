package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.ExtractedSubtitleEntry;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SubtitleFilenamePlanner {

    private static final Pattern TOKEN_PATTERN = Pattern.compile("[^._\\-\\s\\[\\]\\(\\)]+");
    private static final Pattern UNSAFE_PATH_CHARS = Pattern.compile("[\\\\/:*?\"<>|\\p{Cntrl}]+");
    private static final Pattern SEPARATOR_PATTERN = Pattern.compile("[._\\-\\s\\[\\]\\(\\)]+");

    public SubtitleFilenamePlan plan(String videoFileName, List<ExtractedSubtitleEntry> entries) {
        if (!StringUtils.hasText(videoFileName)) {
            throw badRequest("目标目录没有可用于命名的主视频文件");
        }
        if (entries == null || entries.isEmpty()) {
            throw badRequest("没有可上传的字幕文件");
        }

        String videoBaseName = mainName(videoFileName);
        if (!StringUtils.hasText(videoBaseName)) {
            throw badRequest("目标视频文件名无效");
        }

        List<PlannedSubtitleFile> plannedFiles = entries.size() == 1
                ? planSingle(videoBaseName, entries.get(0))
                : planMultiple(videoBaseName, entries);
        ensureNoDuplicateFinalNames(plannedFiles);
        String detectedPrefix = entries.size() == 1 ? mainName(entries.get(0).originalName()) : commonPrefixText(entries);
        return new SubtitleFilenamePlan(videoBaseName, detectedPrefix, plannedFiles);
    }

    public String normalizeDiagnosticName(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        value.codePoints()
                .map(Character::toLowerCase)
                .filter(Character::isLetterOrDigit)
                .forEach(builder::appendCodePoint);
        return builder.toString();
    }

    public void validateNoDuplicateFinalNames(List<PlannedSubtitleFile> plannedFiles) {
        ensureNoDuplicateFinalNames(plannedFiles);
    }

    private List<PlannedSubtitleFile> planSingle(String videoBaseName, ExtractedSubtitleEntry entry) {
        return List.of(new PlannedSubtitleFile(
                entry,
                videoBaseName + "." + entry.extension().toLowerCase(Locale.ROOT)
        ));
    }

    private List<PlannedSubtitleFile> planMultiple(String videoBaseName, List<ExtractedSubtitleEntry> entries) {
        int prefixTokenCount = commonPrefixTokenCount(entries);
        if (prefixTokenCount <= 0) {
            throw badRequest("多个字幕文件无法识别共同片名前缀，请确认 ZIP 内字幕命名");
        }
        List<PlannedSubtitleFile> plannedFiles = new ArrayList<>();
        for (ExtractedSubtitleEntry entry : entries) {
            String sourceStem = mainName(entry.originalName());
            String tail = cleanTail(suffixAfterTokens(sourceStem, prefixTokenCount));
            String finalName = videoBaseName
                    + (StringUtils.hasText(tail) ? "." + tail : "")
                    + "." + entry.extension().toLowerCase(Locale.ROOT);
            plannedFiles.add(new PlannedSubtitleFile(entry, finalName));
        }
        return plannedFiles;
    }

    private int commonPrefixTokenCount(List<ExtractedSubtitleEntry> entries) {
        List<String> firstTokens = tokens(mainName(entries.get(0).originalName()));
        int commonCount = firstTokens.size();
        for (int index = 1; index < entries.size(); index++) {
            List<String> currentTokens = tokens(mainName(entries.get(index).originalName()));
            int limit = Math.min(commonCount, currentTokens.size());
            int matched = 0;
            while (matched < limit && firstTokens.get(matched).equalsIgnoreCase(currentTokens.get(matched))) {
                matched++;
            }
            commonCount = matched;
        }
        return commonCount;
    }

    private String commonPrefixText(List<ExtractedSubtitleEntry> entries) {
        int prefixTokenCount = commonPrefixTokenCount(entries);
        if (prefixTokenCount <= 0) {
            return "";
        }
        String stem = mainName(entries.get(0).originalName());
        int endIndex = endIndexAfterTokens(stem, prefixTokenCount);
        return endIndex <= 0 ? "" : stripTailSeparators(stem.substring(0, endIndex));
    }

    private List<String> tokens(String value) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(value == null ? "" : value);
        while (matcher.find()) {
            String token = matcher.group().trim();
            if (StringUtils.hasText(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private String suffixAfterTokens(String value, int tokenCount) {
        int endIndex = endIndexAfterTokens(value, tokenCount);
        if (endIndex < 0 || endIndex >= value.length()) {
            return "";
        }
        return value.substring(endIndex);
    }

    private int endIndexAfterTokens(String value, int tokenCount) {
        Matcher matcher = TOKEN_PATTERN.matcher(value == null ? "" : value);
        int matched = 0;
        while (matcher.find()) {
            matched++;
            if (matched == tokenCount) {
                return matcher.end();
            }
        }
        return -1;
    }

    private String cleanTail(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String cleaned = UNSAFE_PATH_CHARS.matcher(value).replaceAll(" ");
        cleaned = SEPARATOR_PATTERN.matcher(cleaned).replaceAll(".");
        return stripDots(cleaned);
    }

    private String stripTailSeparators(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return stripDots(SEPARATOR_PATTERN.matcher(value).replaceAll("."));
    }

    private void ensureNoDuplicateFinalNames(List<PlannedSubtitleFile> plannedFiles) {
        Set<String> seen = new HashSet<>();
        for (PlannedSubtitleFile plannedFile : plannedFiles) {
            String key = plannedFile.finalName().toLowerCase(Locale.ROOT);
            if (!seen.add(key)) {
                throw badRequest("ZIP 中规划后的字幕文件名重复: " + plannedFile.finalName());
            }
        }
    }

    private String mainName(String name) {
        String filename = pathName(name);
        int index = filename.lastIndexOf('.');
        return index > 0 ? filename.substring(0, index) : filename;
    }

    private String pathName(String value) {
        String normalized = value == null ? "" : value.replace('\\', '/');
        int index = normalized.lastIndexOf('/');
        return index >= 0 ? normalized.substring(index + 1) : normalized;
    }

    private String stripDots(String value) {
        String stripped = value == null ? "" : value.trim();
        while (stripped.startsWith(".")) {
            stripped = stripped.substring(1).trim();
        }
        while (stripped.endsWith(".")) {
            stripped = stripped.substring(0, stripped.length() - 1).trim();
        }
        return stripped;
    }

    private BusinessException badRequest(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message, HttpStatus.BAD_REQUEST);
    }

    public record SubtitleFilenamePlan(
            String videoBaseName,
            String detectedPrefix,
            List<PlannedSubtitleFile> files
    ) {
    }

    public record PlannedSubtitleFile(
            ExtractedSubtitleEntry entry,
            String finalName
    ) {
    }
}
