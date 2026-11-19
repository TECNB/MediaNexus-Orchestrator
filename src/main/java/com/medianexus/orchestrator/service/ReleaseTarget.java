package com.medianexus.orchestrator.service;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** 目录身份与既有画质允许范围；未知与明确冲突分离，不从集数或容量推导身份。 */
public record ReleaseTarget(List<String> titles, Integer year, Integer season, String quality) {
    private static final Pattern YEAR = Pattern.compile("(?<!\\d)(?:18|19|20)\\d{2}(?!\\d)");
    private static final Pattern MOVIE = Pattern.compile("(?i)(?<![a-z])(?:movie|film)(?![a-z])|剧场版|劇場版|电影版|電影版");

    public List<String> qualities() {
        if (quality == null) return List.of("1080p", "2160p", "720p");
        if ("720p".equals(quality)) return List.of("720p", "1080p", "2160p");
        if ("2160p".equals(quality)) return List.of("2160p", "1080p");
        return List.of("1080p", "2160p");
    }

    public String conflict(String text) {
        String remainder = comparable(text);
        for (String alias : titles) remainder = remainder.replace(comparable(alias), " ");
        if (season != null && MOVIE.matcher(remainder).find()) return "明确电影/剧场版声明，与目标剧集身份冲突";
        if (year != null) {
            var years = YEAR.matcher(remainder).results().map(match -> match.group()).distinct().toList();
            if (!years.isEmpty() && !years.equals(List.of(year.toString()))) return "发行年份与目标作品冲突";
        }
        return null;
    }

    public boolean matches(String text) {
        String normalized = comparable(text);
        return titles.stream().map(ReleaseTarget::comparable).filter(alias -> !alias.isBlank()).anyMatch(alias -> {
            String suffix = alias.matches(".*[\\u3400-\\u9fff]$")
                    ? "(?=$|[^\\p{L}\\p{N}]|第|尝鲜|抢先|搶先|\\d{1,3}(?:\\s|$))" : "(?![\\p{L}\\p{N}])";
            var match = Pattern.compile("(?<![\\p{L}\\p{N}!])" + Pattern.quote(alias) + suffix).matcher(normalized);
            while (match.find()) {
                String rest = normalized.substring(match.end()).stripLeading();
                if (rest.startsWith("!")) continue;
                if (rest.matches("^\\d{1,3}(?:\\s|$).*") && !rest.matches("^0?1\\s+\\d{1,3}(?:\\s|$).*")) continue;
                return true;
            }
            return false;
        });
    }

    public boolean possiblyRelated(String text) {
        String normalized = comparable(text);
        // 抓取污染只产生补证机会，不改写标题或建立身份；真实文件根名仍须准确命中。
        return matches(text) || titles.stream().map(ReleaseTarget::comparable).filter(alias -> alias.length() >= 8)
                .anyMatch(normalized::contains);
    }

    public static ReleaseTarget of(String title, String originalTitle, String alias, Integer year, Integer season, String quality) {
        LinkedHashSet<String> titles = new LinkedHashSet<>();
        for (String name : new String[]{title, originalTitle, alias}) if (name != null && !name.isBlank()) titles.add(name.trim());
        if (titles.isEmpty()) throw new IllegalArgumentException("目标标题不能为空");
        if (quality != null && !List.of("720p", "1080p", "2160p").contains(quality)) throw new IllegalArgumentException("不支持的分辨率标签");
        return new ReleaseTarget(List.copyOf(titles), year, season, quality);
    }

    static String comparable(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("(?<![0-9])\\.|\\.(?![0-9])", " ")
                .replaceAll("[^\\p{L}\\p{N}!.]+", " ").replaceAll("\\s+", " ").trim();
    }
}
