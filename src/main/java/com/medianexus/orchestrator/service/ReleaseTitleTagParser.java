package com.medianexus.orchestrator.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

@Service
public class ReleaseTitleTagParser {

    private static final Pattern RESOLUTION_PATTERN = Pattern.compile(
            "(?i)(?<![a-z0-9])(?<resolution>2160p|4k|uhd|1080p|1080i|fhd|720p)(?![a-z0-9])"
    );
    private static final List<DynamicRangePattern> DYNAMIC_RANGE_PATTERNS = List.of(
            new DynamicRangePattern(
                    "dolby_vision",
                    Pattern.compile("(?i)(?<![a-z0-9])(?:dolby[ ._-]?vision|dolbyvision|dovi|dv)(?![a-z0-9])")
            ),
            new DynamicRangePattern("hdr10_plus", Pattern.compile("(?i)(?<![a-z0-9])HDR10\\+(?![a-z0-9])")),
            new DynamicRangePattern("hdr10", Pattern.compile("(?i)(?<![a-z0-9])HDR10(?![a-z0-9+])")),
            new DynamicRangePattern("hdr", Pattern.compile("(?i)(?<![a-z0-9])HDR(?![a-z0-9])")),
            new DynamicRangePattern("hlg", Pattern.compile("(?i)(?<![a-z0-9])HLG(?![a-z0-9])")),
            new DynamicRangePattern("sdr", Pattern.compile("(?i)(?<![a-z0-9])SDR(?![a-z0-9])"))
    );
    private static final Pattern SEASON_RANGE_PATTERN = Pattern.compile(
            "(?i)(?<![a-z0-9])S(?:EASON)?[ ._-]*(?<start>\\d{1,2})[ ._-]*(?:-|~|–|—|TO)[ ._-]*"
                    + "S?(?:EASON)?[ ._-]*(?<end>\\d{1,2})(?!\\d)"
    );
    private static final Pattern SEASON_NUMBER_PATTERN = Pattern.compile(
            "(?i)(?<![a-z0-9])S(?:EASON)?[ ._-]*(?<season>\\d{1,2})(?!\\d)"
    );
    private static final Pattern ORDINAL_SEASON_PATTERN = Pattern.compile(
            "(?i)(?<!\\d)(?<season>\\d{1,2})(?:ST|ND|RD|TH)[ ._-]*SEASON(?![a-z0-9])"
    );
    private static final Pattern CJK_SEASON_NUMBER_PATTERN = Pattern.compile(
            "第\\s*(?<season>\\d{1,2})\\s*[季期]"
    );
    private static final Pattern CJK_FIRST_SEASON_PATTERN = Pattern.compile("第\\s*一\\s*[季期]");
    private static final Pattern FIRST_SEASON_PACK_PATTERN = Pattern.compile(
            "(?ix)(?:"
                    + "[\\[【(]\\s*(?:E|EP)?0?1\\s*(?:-|~|–|—|TO)\\s*(?:E|EP)?\\d{2,3}[^\\]】)]*[\\]】)]"
                    + "|(?<![a-z0-9])(?:E|EP)0?1\\s*(?:-|~|–|—|TO)\\s*(?:E|EP)?\\d{2,3}(?![a-z0-9])"
                    + "|(?<!\\d)0?1\\s*(?:-|~|–|—)\\s*\\d{2,3}\\s*(?:TV)?\\s*(?:全集|合集|集|話|话|END|FIN)"
                    + "|全\\s*\\d{1,3}\\s*[集話话]"
                    + "|\\d{1,3}\\s*[集話话]\\s*(?:全|完)"
                    + "|全集|合集|完结|完結"
                    + "|(?<![a-z0-9])COMPLETE(?:[ ._-]*SERIES)?(?![a-z0-9])"
                    + "|(?<![a-z0-9])FIN(?:ISHED)?(?![a-z0-9])"
                    + ")"
    );

    public ReleaseTitleTags parse(String title) {
        String value = title == null ? "" : title;
        return new ReleaseTitleTags(
                parseResolutionTags(value),
                parseDynamicRangeTags(value),
                parseSeasonTags(value)
        );
    }

    private List<String> parseResolutionTags(String title) {
        Set<String> tags = new LinkedHashSet<>();
        Matcher matcher = RESOLUTION_PATTERN.matcher(title);
        while (matcher.find()) {
            tags.add(normalizeResolution(matcher.group("resolution")));
        }
        return new ArrayList<>(tags);
    }

    private String normalizeResolution(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "4k", "uhd", "2160p" -> "2160p";
            case "1080i", "fhd", "1080p" -> "1080p";
            default -> "720p";
        };
    }

    private List<String> parseDynamicRangeTags(String title) {
        Set<String> tags = new LinkedHashSet<>();
        for (DynamicRangePattern candidate : DYNAMIC_RANGE_PATTERNS) {
            if (candidate.pattern().matcher(title).find()) {
                tags.add(candidate.label());
            }
        }
        return new ArrayList<>(tags);
    }

    private List<String> parseSeasonTags(String title) {
        Set<Integer> seasons = new TreeSet<>();
        Matcher rangeMatcher = SEASON_RANGE_PATTERN.matcher(title);
        while (rangeMatcher.find()) {
            int start = Integer.parseInt(rangeMatcher.group("start"));
            int end = Integer.parseInt(rangeMatcher.group("end"));
            if (start >= 1 && end >= start) {
                for (int season = start; season <= end; season++) {
                    seasons.add(season);
                }
            }
        }

        addSeasonMatches(seasons, SEASON_NUMBER_PATTERN.matcher(title));
        addSeasonMatches(seasons, ORDINAL_SEASON_PATTERN.matcher(title));
        addSeasonMatches(seasons, CJK_SEASON_NUMBER_PATTERN.matcher(title));
        if (CJK_FIRST_SEASON_PATTERN.matcher(title).find()) {
            seasons.add(1);
        }
        if (seasons.isEmpty() && FIRST_SEASON_PACK_PATTERN.matcher(title).find()) {
            seasons.add(1);
        }
        return seasons.stream().map(this::seasonTag).toList();
    }

    private void addSeasonMatches(Set<Integer> seasons, Matcher matcher) {
        while (matcher.find()) {
            int season = Integer.parseInt(matcher.group("season"));
            if (season >= 1) {
                seasons.add(season);
            }
        }
    }

    private String seasonTag(int season) {
        return "S" + String.format(Locale.ROOT, "%02d", season);
    }

    public record ReleaseTitleTags(
            List<String> resolutionTags,
            List<String> dynamicRangeTags,
            List<String> seasonTags
    ) {
    }

    private record DynamicRangePattern(String label, Pattern pattern) {
    }
}
