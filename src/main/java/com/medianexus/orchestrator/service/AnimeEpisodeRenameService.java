package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.config.OpenListProperties;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class AnimeEpisodeRenameService {

    private static final Pattern EPISODE_PATTERN = Pattern.compile("(.*|\\[.*])(( - |Vol |[Ee][Pp]?)\\d+(\\.5)?( ?\\(\\d+\\))?|【\\d+(\\.5)?】|\\[\\d+(\\.5)?( ?\\(\\d+\\))?([ _-]?[vV]\\d)?( ?END)?( ?完)?( ?FIN)?]|第\\d+(\\.5)?[话話集]( - END)?|^\\[TOC].* \\d+|^六四位元字幕组.*★\\d+(\\.5)?★)");
    private static final Pattern EPISODE_NUMBER_PATTERN = Pattern.compile("\\d+(\\.5)?");
    private static final Pattern HASH_SUFFIX_PATTERN = Pattern.compile("\\[([A-Z]|\\d){8}]$");
    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]+");
    private static final List<String> VIDEO_EXTENSIONS = List.of(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "m2ts", "ts", "webm", "rmvb"
    );
    private static final List<String> SUBTITLE_EXTENSIONS = List.of(
            "ass", "ssa", "srt", "vtt", "sub"
    );

    private final OpenListProperties properties;

    public AnimeEpisodeRenameService(OpenListProperties properties) {
        this.properties = properties;
    }

    /**
     * 将字幕组发布文件名转换为媒体库季集命名。
     *
     * 只接受视频和字幕文件；特别篇、总集篇、合集区间和半集会被跳过，避免把非正片
     * 写入 Season 目录。返回的文件名已按 OpenList 模板渲染并清理非法路径字符。
     */
    public Optional<RenameResult> rename(String sourceName, String title, Integer seasonNumber, int offset) {
        String extension = extension(sourceName);
        if (!isVideo(sourceName) && !isSubtitle(sourceName)) {
            return Optional.empty();
        }
        if (isExcluded(sourceName)) {
            return Optional.empty();
        }

        String normalizedTitle = sourceName
                .replace("+NCOPED", "")
                .replace("\n", " ")
                .replace("\t", " ")
                .trim();
        normalizedTitle = HASH_SUFFIX_PATTERN.matcher(normalizedTitle).replaceAll("").trim();

        Matcher matcher = EPISODE_PATTERN.matcher(normalizedTitle);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String episodeToken = matcher.group(2);
        Matcher episodeMatcher = EPISODE_NUMBER_PATTERN.matcher(episodeToken);
        if (!episodeMatcher.find()) {
            return Optional.empty();
        }

        double episode = Double.parseDouble(episodeMatcher.group()) + offset;
        boolean halfEpisode = episode != (int) episode;
        if (halfEpisode) {
            return Optional.empty();
        }

        String episodeFormat = String.format("%02d", (int) episode);
        String seasonFormat = String.format("%02d", seasonNumber);
        String baseName = replaceTemplateValue(properties.getAnimeRenameTemplate(), "title", title);
        baseName = replaceTemplateValue(baseName, "seasonFormat", seasonFormat);
        baseName = replaceTemplateValue(baseName, "episodeFormat", episodeFormat);
        baseName = replaceTemplateValue(baseName, "season", String.valueOf(seasonNumber));
        baseName = replaceTemplateValue(baseName, "episode", String.valueOf((int) episode));
        baseName = sanitizeFileName(baseName);

        if (isSubtitle(sourceName)) {
            String language = extension(mainName(sourceName));
            if (StringUtils.hasText(language)) {
                baseName = baseName + "." + language;
            }
        }
        return Optional.of(new RenameResult((int) episode, baseName + "." + extension));
    }

    /**
     * 判断文件扩展名是否属于当前导入流程会整理的视频类型。
     */
    public boolean isVideo(String name) {
        return VIDEO_EXTENSIONS.contains(extension(name).toLowerCase(Locale.ROOT));
    }

    /**
     * 判断文件扩展名是否属于当前导入流程会随剧集整理的字幕类型。
     */
    public boolean isSubtitle(String name) {
        return SUBTITLE_EXTENSIONS.contains(extension(name).toLowerCase(Locale.ROOT));
    }

    private boolean isExcluded(String name) {
        String configuredPatterns = properties.getAnimeExcludePatterns();
        for (String pattern : configuredPatterns.split(",")) {
            String trimmed = pattern.trim();
            if (!trimmed.isEmpty() && Pattern.compile(trimmed).matcher(name).find()) {
                return true;
            }
        }
        return false;
    }

    private String extension(String name) {
        int index = name == null ? -1 : name.lastIndexOf('.');
        if (index < 0 || index == name.length() - 1) {
            return "";
        }
        return name.substring(index + 1);
    }

    private String mainName(String name) {
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        String filename = slash >= 0 ? name.substring(slash + 1) : name;
        int dot = filename.lastIndexOf('.');
        return dot > 0 ? filename.substring(0, dot) : filename;
    }

    private String sanitizeFileName(String name) {
        String sanitized = INVALID_NAME_CHARS.matcher(name.trim()).replaceAll(" ");
        return sanitized.replaceAll("\\s+", " ").trim();
    }

    private String replaceTemplateValue(String template, String key, String value) {
        return template
                .replace("{" + key + "}", value)
                .replace("${" + key + "}", value);
    }

    public record RenameResult(Integer episodeNumber, String fileName) {
    }
}
