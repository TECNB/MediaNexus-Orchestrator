package com.medianexus.orchestrator.integration.javdb;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Filename declarations only; these flags do not verify subtitle streams in the video. */
public record JavdbEdition(String code, boolean chineseSubtitles, boolean cracked) {

    private static final Pattern CODE = Pattern.compile(
            "(?<![A-Z0-9])(?!(?:H26[45]|X26[45]|MP[34])(?:[^A-Z0-9]|$))"
                    + "(?:FC2[-_ ]*(?:PPV[-_ ]*)?([0-9]{1,9})|([A-Z]{2,12})[-_ ]*([0-9]{1,7}))(?![A-Z0-9])");
    private static final Pattern SITE_PREFIX = Pattern.compile(
            "(?i)(?:[a-z0-9-]+\\.)+(?:com|net|org|cc|tv|me|xyz|club)(?:@|[-_])?");
    private static final Pattern SUBTITLES = Pattern.compile(
            "(?:中文|中字|字幕|(?<![A-Z0-9])(?:C|CH|CHS|CHT|ZH|UC|CU)(?![A-Z0-9]))");
    private static final Pattern CRACKED = Pattern.compile(
            "(?:无码|無碼|流出|破解|解密|UNCENSORED|(?<![A-Z0-9])(?:CRACKED|UC|CU|U|LTX)(?![A-Z0-9]))");

    public static Optional<JavdbEdition> parse(String magnetOrFilename) {
        if (magnetOrFilename == null || magnetOrFilename.isBlank()) {
            return Optional.empty();
        }
        String name = magnetOrFilename.trim();
        if (name.regionMatches(true, 0, "magnet:", 0, 7)) {
            if (!name.regionMatches(true, 0, "magnet:?", 0, 8)) {
                return Optional.empty();
            }
            Set<String> displayNames = new LinkedHashSet<>();
            try {
                for (String parameter : name.substring(8).split("&")) {
                    int equals = parameter.indexOf('=');
                    if (equals > 0 && "dn".equalsIgnoreCase(parameter.substring(0, equals))) {
                        displayNames.add(URLDecoder.decode(parameter.substring(equals + 1), StandardCharsets.UTF_8));
                    }
                }
            } catch (IllegalArgumentException exception) {
                return Optional.empty();
            }
            if (displayNames.size() != 1) {
                return Optional.empty();
            }
            name = displayNames.iterator().next();
        } else if (name.contains("://")) {
            return Optional.empty();
        }
        name = Normalizer.normalize(name, Normalizer.Form.NFKC).toUpperCase(Locale.ROOT);
        name = SITE_PREFIX.matcher(name).replaceAll(" ");
        Set<String> codes = new LinkedHashSet<>();
        Matcher matcher = CODE.matcher(name);
        while (matcher.find()) {
            codes.add(canonicalCode(matcher));
        }
        if (codes.size() != 1) {
            return Optional.empty();
        }
        // Remove the work number before looking for tokens: a C/U studio prefix is not an edition marker.
        String markers = CODE.matcher(name).replaceAll(" ");
        return Optional.of(new JavdbEdition(codes.iterator().next(),
                SUBTITLES.matcher(markers).find(), CRACKED.matcher(markers).find()));
    }

    /** Strict work-number normalization shared with the upstream identity check. */
    static String normalizeCode(String code) {
        if (code == null) {
            return null;
        }
        Matcher matcher = CODE.matcher(Normalizer.normalize(code, Normalizer.Form.NFKC)
                .trim().toUpperCase(Locale.ROOT));
        return matcher.matches() ? canonicalCode(matcher) : null;
    }

    private static String canonicalCode(Matcher matcher) {
        if (matcher.group(1) != null) {
            return "FC2-PPV-" + matcher.group(1).replaceFirst("^0+(?!$)", "");
        }
        String digits = matcher.group(3).replaceFirst("^0+(?!$)", "");
        return matcher.group(2) + "-" + "0".repeat(Math.max(0, 3 - digits.length())) + digits;
    }
}
