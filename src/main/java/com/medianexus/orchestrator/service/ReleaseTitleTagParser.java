package com.medianexus.orchestrator.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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

    public ReleaseTitleTags parse(String title) {
        String value = title == null ? "" : title;
        return new ReleaseTitleTags(parseResolutionTags(value), parseDynamicRangeTags(value));
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

    public record ReleaseTitleTags(
            List<String> resolutionTags,
            List<String> dynamicRangeTags
    ) {
    }

    private record DynamicRangePattern(String label, Pattern pattern) {
    }
}
