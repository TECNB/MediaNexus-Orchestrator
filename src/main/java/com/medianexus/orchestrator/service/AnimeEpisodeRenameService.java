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

    private static final Pattern EPISODE_PATTERN = Pattern.compile("(.*|\\[.*])(( - |Vol |[Ee][Pp]?)\\d+(\\.5)?( ?\\(\\d+\\))?|【\\d+(\\.5)?】|\\[\\d+(\\.5)?( ?\\(\\d+\\))?( ?[vV]\\d)?( ?END)?( ?完)?( ?FIN)?]|第\\d+(\\.5)?[话話集]( - END)?|^\\[TOC].* \\d+|^六四位元字幕组.*★\\d+(\\.5)?★)");
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
        String template = StringUtils.hasText(properties.getAnimeRenameTemplate())
                ? properties.getAnimeRenameTemplate()
                : "${title} S${seasonFormat}E${episodeFormat}";
        String baseName = template
                .replace("${title}", title)
                .replace("${seasonFormat}", seasonFormat)
                .replace("${episodeFormat}", episodeFormat)
                .replace("${season}", String.valueOf(seasonNumber))
                .replace("${episode}", String.valueOf((int) episode));
        baseName = sanitizeFileName(baseName);

        if (isSubtitle(sourceName)) {
            String language = extension(mainName(sourceName));
            if (StringUtils.hasText(language)) {
                baseName = baseName + "." + language;
            }
        }
        return Optional.of(new RenameResult((int) episode, baseName + "." + extension));
    }

    public boolean isVideo(String name) {
        return VIDEO_EXTENSIONS.contains(extension(name).toLowerCase(Locale.ROOT));
    }

    public boolean isSubtitle(String name) {
        return SUBTITLE_EXTENSIONS.contains(extension(name).toLowerCase(Locale.ROOT));
    }

    private boolean isExcluded(String name) {
        String configuredPatterns = properties.getAnimeExcludePatterns();
        if (!StringUtils.hasText(configuredPatterns)) {
            configuredPatterns = "特别篇,\\d-\\d,总集";
        }
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

    public record RenameResult(Integer episodeNumber, String fileName) {
    }
}
