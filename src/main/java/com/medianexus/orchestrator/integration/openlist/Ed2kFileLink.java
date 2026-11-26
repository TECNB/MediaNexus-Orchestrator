package com.medianexus.orchestrator.integration.openlist;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** File identity declared by an ED2K link; the raw link is submitted unchanged. */
public record Ed2kFileLink(String filename, long sizeBytes, String downloadKey) {
    private static final Pattern FILE = Pattern.compile(
            "^ed2k://\\|file\\|([^|\\r\\n]+)\\|([0-9]+)\\|([a-f0-9]{32})\\|(?:h=[a-z2-7]+\\|)?/$",
            Pattern.CASE_INSENSITIVE);

    public static Optional<Ed2kFileLink> parse(String link) {
        if (link == null) return Optional.empty();
        var match = FILE.matcher(link);
        if (!match.matches()) return Optional.empty();
        try {
            String filename = URLDecoder.decode(match.group(1).replace("+", "%2B"), StandardCharsets.UTF_8);
            long bytes = Long.parseLong(match.group(2));
            if (bytes <= 0 || filename.isBlank() || filename.equals(".") || filename.equals("..")
                    || filename.contains("/") || filename.contains("\\") || filename.contains("|")
                    || filename.codePoints().anyMatch(Character::isISOControl)) return Optional.empty();
            return Optional.of(new Ed2kFileLink(filename, bytes, "ed2k:" + match.group(3).toLowerCase(Locale.ROOT)));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }
}
