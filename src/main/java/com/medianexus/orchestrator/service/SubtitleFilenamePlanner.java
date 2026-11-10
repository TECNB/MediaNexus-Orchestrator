package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.service.SubtitleArchiveExtractor.ExtractedSubtitleEntry;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class SubtitleFilenamePlanner {


    public SubtitleFilenamePlan plan(String videoFileName, List<ExtractedSubtitleEntry> entries) {
        if (!StringUtils.hasText(videoFileName)) {
            throw badRequest("目标目录没有可用于命名的主视频文件");
        }
        if (entries == null || entries.isEmpty()) {
            throw badRequest("没有可上传的字幕文件");
        }

        List<PlannedSubtitleFile> files = entries.stream()
                .map(entry -> new PlannedSubtitleFile(entry, finalName(videoFileName, language(entry, "unknown").language(), entry.extension())))
                .toList();
        ensureNoDuplicateFinalNames(files);
        return new SubtitleFilenamePlan(mainName(videoFileName), "", files);
    }

    public String finalName(String videoName, String language, String extension) {
        String suffix = switch (validateLanguage(language)) {
            case "chs" -> "zh-CN";
            case "cht" -> "zh-TW";
            default -> "und";
        };
        return mainName(videoName) + "." + suffix + "." + extension.toLowerCase(Locale.ROOT);
    }

    public String validateLanguage(String language) {
        if (language == null) return "unknown";
        String normalized = language.toLowerCase(Locale.ROOT);
        if (!Set.of("chs", "cht", "unknown").contains(normalized)) {
            throw badRequest("字幕语言仅支持 chs、cht 或 unknown");
        }
        return normalized;
    }

    /** 文件名优先于最近父目录；同时出现简繁标记时不猜测，也不套用父目录。 */
    public LanguageMatch language(ExtractedSubtitleEntry entry, String fallback) {
        String detected = detectLanguage(entry.originalName());
        if (detected != null) return new LanguageMatch(detected, "unknown".equals(detected) ? "unknown" : "filename");
        String path = entry.originalPath().replace('\\', '/');
        String[] directories = path.split("/");
        for (int index = directories.length - 2; index >= 0; index--) {
            detected = detectLanguage(directories[index]);
            if (detected != null) return new LanguageMatch(detected, "unknown".equals(detected) ? "unknown" : "directory");
        }
        String language = validateLanguage(fallback);
        return new LanguageMatch(language, "unknown".equals(language) ? "unknown" : "user");
    }

    private String detectLanguage(String name) {
        boolean simplified = Pattern.compile("(?i)(?<![a-z])(?:chs|zh[-_]cn|gb|gb2312|gbk|simplified)(?![a-z])|简[体中]|簡[體中]")
                .matcher(name).find();
        boolean traditional = Pattern.compile("(?i)(?<![a-z])(?:cht|zh[-_]tw|big5|traditional)(?![a-z])|繁[体體中]")
                .matcher(name).find();
        if (simplified && traditional || name.contains("简繁") || name.contains("簡繁")) return "unknown";
        if (simplified) return "chs";
        if (traditional) return "cht";
        return null;
    }

    public record LanguageMatch(String language, String source) { }

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
