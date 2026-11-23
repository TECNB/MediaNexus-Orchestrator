package com.medianexus.orchestrator.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class MovieSeriesFileRenameService {

    private static final long MIN_MOVIE_VIDEO_BYTES = 100L * 1024L * 1024L;
    private static final String PROMOTIONAL_NAME = "更多(?:无水印)?高品质资源请访问\\s*www\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)+";
    private static final Pattern STANDALONE_PROMOTION = Pattern.compile(
            "(?i)(?:\\s*(?:【" + PROMOTIONAL_NAME + "】|\\[" + PROMOTIONAL_NAME + "\\]|" + PROMOTIONAL_NAME + "))+\\s*");
    private static final long MAX_PROMOTIONAL_ATTACHMENT_BYTES = 50L * 1024L * 1024L;
    private static final String SMALL_PROMOTIONAL_NAME = "更多(?:无水印)?(?:高清|高品质)资源请访问\\s*www\\.[a-z0-9-]+(?:\\.[a-z0-9-]+)+";
    private static final Pattern SMALL_STANDALONE_PROMOTION = Pattern.compile(
            "(?i)(?:\\s*(?:【" + SMALL_PROMOTIONAL_NAME + "】|\\[" + SMALL_PROMOTIONAL_NAME + "\\]|" + SMALL_PROMOTIONAL_NAME + "))+\\s*");

    private static final String MOVIE_STANDARD_FILE_FORMAT = "{Movie Title} ({Release Year}) {Quality Full}";
    private static final String MOVIE_FOLDER_FORMAT = "{Movie Title} ({Release Year})";
    private static final String SERIES_STANDARD_EPISODE_FORMAT =
            "{Series Title} - S{season:00}E{episode:00} - {Episode Title} {Quality Full}";
    private static final String SERIES_FOLDER_FORMAT = "{Series Title}";
    private static final String SEASON_FOLDER_FORMAT = "Season {season}";

    private static final Pattern INVALID_NAME_CHARS = Pattern.compile("[\\\\/:*?\"<>|]+");
    private static final Pattern MULTIPLE_SPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern QUALITY_SPLIT_PATTERN = Pattern.compile("[._\\-\\[\\](){}]+");
    private static final Pattern QUALITY_RESOLUTION_PATTERN = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:web|hd)?(2160p|1080p|720p|480p|4k|uhd)(?![a-z0-9])");
    private static final String PREVIEW_LABEL = "(?:(?:尝鲜|嘗鮮|抢先|搶先)版?\\s*)*";
    private static final Pattern SXX_EXX_PATTERN = Pattern.compile("(?i)(?:^|[^a-z0-9])s(\\d{1,2})\\s*e(\\d{1,3})(?:[^a-z0-9]|$)");
    private static final Pattern X_EPISODE_PATTERN = Pattern.compile("(?i)(?:^|[^a-z0-9])(\\d{1,2})x(\\d{1,3})(?:[^a-z0-9]|$)");
    private static final Pattern EPISODE_PREFIX_PATTERN = Pattern.compile("(?i)(?:^|[^a-z0-9])(?:ep|e)\\s*(\\d{1,3})(?:[^a-z0-9]|$)");
    private static final Pattern CHINESE_EPISODE_PATTERN = Pattern.compile("第\\s*(\\d{1,3})\\s*[集话話]");
    private static final Pattern BRACKET_EPISODE_PATTERN = Pattern.compile("(?i)[\\[【]\\s*(\\d{1,3})(?:v\\d+)?\\s*[\\]】]");
    private static final Pattern LEADING_RELEASE_EPISODE = Pattern.compile(
            "(?i)^(\\d{1,3})[._ -]+(?:Episode[._ -]+(\\d{1,3})[._ -]+)?(?=(?:2160p|1080p|720p|480p|4k|uhd)(?![a-z0-9]))");
    private static final Pattern BARE_ANIME_EPISODE_PATTERN = Pattern.compile(
            "(?i)\\s+-\\s+(\\d{1,3})(?:v\\d+)?(?=\\s*(?:\\[|【|\\(|（|$))"
    );

    private static final Pattern NON_FEATURE_EPISODE = Pattern.compile(
            "(?i)(?<![a-z0-9])(?:ncop|nced|op|ed|ova|oad|sp|specials?|extras?|sample|trailer|pv|cm|menu|iv)\\d*(?![a-z])"
                    + "|特典|花絮|预告|宣伝|映像特典|(?:\\s-\\s|s\\d{1,2}e|ep?\\s*|第\\s*|\\[)\\d{1,3}\\.\\d+(?!\\d|\\.\\d)(?:v\\d+)?(?=[ ._()\\[\\]-]|$)");

    private static final List<String> VIDEO_EXTENSIONS = List.of(
            "mp4", "mkv", "avi", "mov", "wmv", "flv", "m2ts", "ts", "webm", "rmvb"
    );
    private static final List<String> SUBTITLE_EXTENSIONS = List.of(
            "ass", "ssa", "srt", "vtt", "sub", "idx", "sup"
    );

    public String movieFolderName(String title, Integer year) {
        String value = replaceMovieValues(MOVIE_FOLDER_FORMAT, title, year, "");
        return sanitizeFileName(value);
    }

    public String seriesFolderName(String title) {
        String value = SERIES_FOLDER_FORMAT.replace("{Series Title}", title);
        return sanitizeFileName(value);
    }

    public String seasonFolderName(Integer seasonNumber) {
        String value = SEASON_FOLDER_FORMAT
                .replace("{season}", String.valueOf(seasonNumber))
                .replace("{season:00}", String.format(Locale.ROOT, "%02d", seasonNumber));
        return sanitizeFileName(value);
    }

    public RenameResult movieVideo(String sourceName, String title, Integer year) {
        String extension = extension(sourceName);
        String quality = qualityFull(sourceName);
        String baseName = movieBaseName(title, year, quality);
        return new RenameResult(null, quality, baseName, baseName + "." + extension);
    }

    public Optional<RenameResult> movieSubtitle(String sourceName, String title, Integer year, String matchedQuality) {
        if (!isSubtitle(sourceName)) {
            return Optional.empty();
        }
        String baseName = movieBaseName(title, year, matchedQuality);
        String fileName = SubtitleFilenamePlanner.automaticName(baseName, sourceName).orElse(sourceName);
        return Optional.of(new RenameResult(null, matchedQuality, baseName, fileName));
    }

    public Optional<RenameResult> seriesVideo(String sourceName, String title, Integer seasonNumber) {
        return seriesVideo(sourceName, title, seasonNumber, title);
    }

    public Optional<RenameResult> seriesVideo(String sourceName, String title, Integer seasonNumber, String knownTitle) {
        if (!isVideo(sourceName)) {
            return Optional.empty();
        }
        Optional<Integer> episode = seriesEpisodeNumber(sourceName, title, seasonNumber)
                .or(() -> seriesEpisodeNumber(sourceName, knownTitle, seasonNumber));
        if (episode.isEmpty()) {
            return Optional.empty();
        }
        String quality = qualityFull(sourceName);
        String baseName = seriesBaseName(title, seasonNumber, episode.get(), quality);
        return Optional.of(new RenameResult(episode.get(), quality, baseName, baseName + "." + extension(sourceName)));
    }

    public Optional<RenameResult> seriesSubtitle(
            String sourceName,
            String title,
            Integer seasonNumber,
            String matchedQuality
    ) {
        return seriesSubtitle(sourceName, title, seasonNumber, matchedQuality, title);
    }

    public Optional<RenameResult> seriesSubtitle(
            String sourceName, String title, Integer seasonNumber, String matchedQuality, String knownTitle
    ) {
        if (!isSubtitle(sourceName)) {
            return Optional.empty();
        }
        Optional<Integer> episode = seriesEpisodeNumber(sourceName, title, seasonNumber)
                .or(() -> seriesEpisodeNumber(sourceName, knownTitle, seasonNumber));
        if (episode.isEmpty()) {
            return Optional.empty();
        }
        String baseName = seriesBaseName(title, seasonNumber, episode.get(), matchedQuality);
        String fileName = SubtitleFilenamePlanner.automaticName(baseName, sourceName).orElse(sourceName);
        return Optional.of(new RenameResult(episode.get(), matchedQuality, baseName, fileName));
    }

    public boolean isVideo(String name) {
        return VIDEO_EXTENSIONS.contains(extension(name).toLowerCase(Locale.ROOT));
    }

    /** 电影落地的大小资格：恰好 100 MiB 仍保留；大小须已由文件表或 OpenList 边界验证。 */
    public boolean isMovieFeatureVideo(String name, long sizeBytes) {
        return isVideo(name) && sizeBytes >= MIN_MOVIE_VIDEO_BYTES && !isPromotionalVideo(name, sizeBytes);
    }

    public boolean isSubtitle(String name) {
        return SUBTITLE_EXTENSIONS.contains(extension(name).toLowerCase(Locale.ROOT));
    }

    public boolean isNonFeatureVideo(String sourceName) {
        return isVideo(sourceName) && NON_FEATURE_EPISODE.matcher(mainName(sourceName)).find();
    }

    /** 只识别名称完全由明确推广文案组成的附件；填充体积不能变成正片，夹带片名的文件不自动删除。 */
    public boolean isPromotionalVideo(String name, long sizeBytes) {
        if (!isVideo(name) || sizeBytes <= 0) return false;
        String basename = mainName(name);
        return STANDALONE_PROMOTION.matcher(basename).matches()
                || sizeBytes <= MAX_PROMOTIONAL_ATTACHMENT_BYTES && SMALL_STANDALONE_PROMOTION.matcher(basename).matches();
    }

    public String qualityFull(String name) {
        List<String> values = new ArrayList<>();
        String normalizedName = normalizeQualityText(mainName(name));
        List<String> tokens = QUALITY_SPLIT_PATTERN.splitAsStream(mainName(name))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .toList();
        Matcher resolution = QUALITY_RESOLUTION_PATTERN.matcher(mainName(name));
        if (resolution.find()) {
            values.add(formatQualityToken(resolution.group(1).toLowerCase(Locale.ROOT)));
        }
        appendFirstByText(values, normalizedName, List.of("remux", "bluray", "bdrip", "web-dl", "webdl", "webrip", "hdtv", "dvdrip"));
        appendFirst(values, normalizedName, tokens, List.of("hevc", "x265", "h265", "h.265", "avc", "x264", "h264", "h.264", "av1"));
        appendFirst(values, normalizedName, tokens, List.of("hdr10+", "hdr10", "hdr", "dv", "dovi", "hlg"));
        String version = ReleaseVersion.marker(mainName(name));
        if (StringUtils.hasText(version)) {
            values.add(version);
        }
        return sanitizeFileName(String.join(" ", values));
    }

    private void appendFirstByText(List<String> values, String normalizedName, List<String> candidates) {
        String searchableName = "-" + normalizedName
                .replaceAll("[\\[\\](){}]+", "-")
                .replace("blu-ray", "bluray")
                .replace("bd-rip", "bdrip")
                .replace("web-rip", "webrip")
                .replaceAll("-+", "-") + "-";
        for (String candidate : candidates) {
            String normalizedCandidate = normalizeQualityToken(candidate);
            if (searchableName.contains("-" + normalizedCandidate + "-")) {
                values.add(formatQualityToken(candidate));
                return;
            }
        }
    }

    private void appendFirst(List<String> values, String normalizedName, List<String> tokens, List<String> candidates) {
        for (String token : tokens) {
            String normalized = normalizeQualityToken(token);
            for (String candidate : candidates) {
                if (normalized.equals(candidate)) {
                    values.add(formatQualityToken(normalized));
                    return;
                }
            }
        }
        appendFirstByText(values, normalizedName, candidates);
    }

    private String normalizeQualityText(String value) {
        return value.trim()
                .replaceAll("[._\\s]+", "-")
                .toLowerCase(Locale.ROOT)
                .replace("h-265", "h.265")
                .replace("h-264", "h.264");
    }

    private String normalizeQualityToken(String token) {
        return token.trim()
                .replace("_", "-")
                .replace(" ", "-")
                .toLowerCase(Locale.ROOT);
    }

    private String formatQualityToken(String token) {
        return switch (token) {
            case "4k" -> "2160p";
            case "uhd" -> "UHD";
            case "remux" -> "REMUX";
            case "bluray" -> "BluRay";
            case "bdrip" -> "BDRip";
            case "webdl", "web-dl" -> "WEB-DL";
            case "webrip" -> "WEBRip";
            case "hdtv" -> "HDTV";
            case "dvdrip" -> "DVDRip";
            case "hevc" -> "HEVC";
            case "x265" -> "x265";
            case "h265", "h.265" -> "H.265";
            case "avc" -> "AVC";
            case "x264" -> "x264";
            case "h264", "h.264" -> "H.264";
            case "av1" -> "AV1";
            case "hdr10+" -> "HDR10+";
            case "hdr10" -> "HDR10";
            case "hdr" -> "HDR";
            case "dv", "dovi" -> "DV";
            case "hlg" -> "HLG";
            default -> token;
        };
    }

    private Optional<Integer> seriesEpisodeNumber(String sourceName, String title, Integer seasonNumber) {
        if (NON_FEATURE_EPISODE.matcher(mainName(sourceName)).find()) {
            return Optional.empty();
        }
        Optional<Integer> detected = episodeNumber(sourceName, seasonNumber);
        if (detected.isPresent()) {
            return detected;
        }
        // 只剥离括号来源装饰；标题自身的数字必须原样精确匹配，不能把 63-gatsu 洗成 3-gatsu。
        Matcher suffix = Pattern.compile("(?i)^(?:[\\[【][^\\]】]*[\\]】]\\s*)*" + PREVIEW_LABEL
                        + Pattern.quote(title) + PREVIEW_LABEL + "(\\d{1,3})(?:end)?"
                        + "(?:\\s*\\[(?:2160p|1080p|720p|480p|4k|uhd)(?:[^\\]]*)\\])?$")
                .matcher(mainName(sourceName));
        if (suffix.matches()) {
            int episode = Integer.parseInt(suffix.group(1));
            if (episode > 0) {
                return Optional.of(episode);
            }
        }
        return Optional.empty();
    }

    public Optional<Integer> episodeNumber(String sourceName, Integer selectedSeasonNumber) {
        if (NON_FEATURE_EPISODE.matcher(mainName(sourceName)).find()) return Optional.empty();
        Optional<EpisodeMatch> sxxExx = matchSeasonEpisode(SXX_EXX_PATTERN, sourceName);
        if (sxxExx.isPresent()) {
            EpisodeMatch match = sxxExx.get();
            return match.seasonNumber().equals(selectedSeasonNumber) ? Optional.of(match.episodeNumber()) : Optional.empty();
        }

        Optional<EpisodeMatch> xEpisode = matchSeasonEpisode(X_EPISODE_PATTERN, sourceName);
        if (xEpisode.isPresent()) {
            EpisodeMatch match = xEpisode.get();
            return match.seasonNumber().equals(selectedSeasonNumber) ? Optional.of(match.episodeNumber()) : Optional.empty();
        }

        for (Pattern pattern : List.of(CHINESE_EPISODE_PATTERN, EPISODE_PREFIX_PATTERN, BRACKET_EPISODE_PATTERN)) {
            Matcher matcher = pattern.matcher(sourceName);
            if (matcher.find()) {
                int episode = Integer.parseInt(matcher.group(1));
                if (episode > 0) {
                    return Optional.of(episode);
                }
            }
        }

        Matcher leading = LEADING_RELEASE_EPISODE.matcher(mainName(sourceName));
        if (leading.find() && Integer.parseInt(leading.group(1)) > 0
                && (leading.group(2) == null || Integer.parseInt(leading.group(2)) == Integer.parseInt(leading.group(1))))
            return Optional.of(Integer.parseInt(leading.group(1)));

        Matcher bareAnimeEpisode = BARE_ANIME_EPISODE_PATTERN.matcher(mainName(sourceName));
        if (bareAnimeEpisode.find()) {
            int episode = Integer.parseInt(bareAnimeEpisode.group(1));
            if (episode > 0) {
                return Optional.of(episode);
            }
        }
        return Optional.empty();
    }

    private Optional<EpisodeMatch> matchSeasonEpisode(Pattern pattern, String sourceName) {
        Matcher matcher = pattern.matcher(sourceName);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int season = Integer.parseInt(matcher.group(1));
        int episode = Integer.parseInt(matcher.group(2));
        return episode > 0 ? Optional.of(new EpisodeMatch(season, episode)) : Optional.empty();
    }

    private String movieBaseName(String title, Integer year, String quality) {
        String value = replaceMovieValues(MOVIE_STANDARD_FILE_FORMAT, title, year, quality);
        return sanitizeFileName(cleanDanglingSeparators(value));
    }

    private String seriesBaseName(String title, Integer seasonNumber, Integer episodeNumber, String quality) {
        String value = SERIES_STANDARD_EPISODE_FORMAT
                .replace("{Series Title}", title)
                .replace("{season:00}", String.format(Locale.ROOT, "%02d", seasonNumber))
                .replace("{season}", String.valueOf(seasonNumber))
                .replace("{episode:00}", String.format(Locale.ROOT, "%02d", episodeNumber))
                .replace("{episode}", String.valueOf(episodeNumber))
                .replace("{Episode Title}", "")
                .replace("{Quality Full}", StringUtils.hasText(quality) ? quality : "");
        return sanitizeFileName(cleanDanglingSeparators(value));
    }

    private String replaceMovieValues(String template, String title, Integer year, String quality) {
        return template
                .replace("{Movie Title}", title)
                .replace("{Release Year}", String.valueOf(year))
                .replace("{Quality Full}", StringUtils.hasText(quality) ? quality : "");
    }


    private String cleanDanglingSeparators(String value) {
        String cleaned = value
                .replace(" -  ", " ")
                .replaceAll("\\s+-\\s+\\.", ".")
                .replaceAll("\\s{2,}", " ")
                .trim();
        while (cleaned.endsWith("-") || cleaned.endsWith(".")) {
            cleaned = cleaned.substring(0, cleaned.length() - 1).trim();
        }
        return cleaned;
    }

    private String sanitizeFileName(String name) {
        String sanitized = INVALID_NAME_CHARS.matcher(name.trim()).replaceAll(" ");
        sanitized = MULTIPLE_SPACE_PATTERN.matcher(sanitized).replaceAll(" ");
        return stripSpacesAndDots(sanitized);
    }

    private String stripSpacesAndDots(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && isSpaceOrDot(value.charAt(start))) {
            start++;
        }
        while (end > start && isSpaceOrDot(value.charAt(end - 1))) {
            end--;
        }
        return value.substring(start, end);
    }

    private boolean isSpaceOrDot(char value) {
        return value == ' ' || value == '.';
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

    public record RenameResult(Integer episodeNumber, String quality, String baseName, String fileName) {
    }

    private record EpisodeMatch(Integer seasonNumber, Integer episodeNumber) {
    }
}
