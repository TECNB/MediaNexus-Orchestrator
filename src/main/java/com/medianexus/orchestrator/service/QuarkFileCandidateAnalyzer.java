package com.medianexus.orchestrator.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts user-facing alignment hints from one share file name.
 *
 * <p>This is deliberately independent of QAS and TMDB. It does not decide an
 * episode by similarity alone; it only exposes stable identity anchors and
 * bounded label candidates for the alignment workbench to confirm.</p>
 */
public class QuarkFileCandidateAnalyzer {

    private static final Pattern STANDARD_EPISODE = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])s\\s*0?(\\d{1,2})\\s*e\\s*0?(\\d{1,3})(?:[^0-9]|$)"
    );
    private static final Pattern NXNN_EPISODE = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(\\d{1,2})\\s*x\\s*0?(\\d{1,3})(?:[^0-9]|$)"
    );
    private static final Pattern EPISODE_TOKEN = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:ep|episode)\\s*0?(\\d{1,3})(?:[^0-9]|$)"
    );
    private static final Pattern CHINESE_EPISODE = Pattern.compile(
            "(?:第\\s*|(?<!\\d))(\\d{1,3}|[一二三四五六七八九十百]+)\\s*[集话期]"
    );
    private static final Pattern LEADING_EPISODE = Pattern.compile(
            "^(\\d{1,3})(?:[ _.-].*)?(?:\\.[^.]+)$"
    );
    private static final Pattern FULL_DATE = Pattern.compile(
            "(?<!\\d)(20\\d{2})[-.]?(\\d{2})[-.]?(\\d{2})(?!\\d)"
    );
    private static final Pattern SHORT_DATE = Pattern.compile(
            "(?<!\\d)(\\d{2})[.-](\\d{2})(?!\\d)"
    );
    private static final Pattern SHORT_YEAR_DATE = Pattern.compile(
            "(?<!\\d)(\\d{2})(\\d{2})(\\d{2})(?!\\d)"
    );
    private static final Pattern EXTRA = Pattern.compile(
            "(?i)(加更|纯享|番外|花絮|采访|预告|先导|彩蛋|未播|衍生|幕后|特别篇|specials?|extras?)"
    );
    private static final Pattern SEGMENT = Pattern.compile(
            "(?i)(?<![a-z])((?:上|中|下|上篇|中篇|下篇|part\\s*[-_. ]?\\d{1,2}|pt\\s*[-_. ]?\\d{1,2}))(?![a-z])"
    );
    private static final Pattern EDITION = Pattern.compile(
            "(?i)(4k(?:高码率|低码率)?|8k|2160p|1080p|720p|hdr10?(?:\\+)?|杜比视界|dolby[ ._-]*vision|remux|web[- .]?dl|bluray|blu[- .]?ray|高码率|低码率|vip|会员版?|完整版|无删减版?|导演剪辑版|未删减版|国语版|粤语版|双语版|v\\d+)"
    );
    private static final Pattern FILE_EXTENSION = Pattern.compile("(?i)\\.[a-z0-9]{2,5}$");
    private static final Pattern SEPARATOR = Pattern.compile("[\\[\\]【】()（）{}<>《》|,_+]+|[-.\\s]+", Pattern.UNICODE_CHARACTER_CLASS);

    /** Analyze a file without using any mutable or external state. */
    public Candidate analyze(String sourceCandidateId, String fileName) {
        String name = fileName == null ? "" : fileName.trim();
        Anchor anchor = findAnchor(name);
        List<String> editions = findLabels(EDITION, name);
        List<String> segments = findLabels(SEGMENT, name);
        List<String> extras = findLabels(EXTRA, name);
        String assignmentType;
        if (!extras.isEmpty()) {
            assignmentType = "EXTRA";
        } else if (!editions.isEmpty()) {
            assignmentType = "EDITION";
        } else if (!segments.isEmpty()) {
            assignmentType = "SEGMENT";
        } else if (anchor.hasIdentity()) {
            assignmentType = "PRIMARY";
        } else {
            assignmentType = "UNKNOWN";
        }

        List<String> reasons = new ArrayList<>();
        if (anchor.episode() != null) reasons.add(anchor.reasonCode());
        if (anchor.date() != null) reasons.add(anchor.dateReasonCode());
        if (!editions.isEmpty()) reasons.add("EDITION_CANDIDATE");
        if (!segments.isEmpty()) reasons.add("SEGMENT_CANDIDATE");
        if (!extras.isEmpty()) reasons.add("EXTRA_CANDIDATE");
        if (reasons.isEmpty()) reasons.add("NO_IDENTITY_ANCHOR");

        double confidence = anchor.episode() != null
                ? 0.95d
                : anchor.date() != null ? 0.85d : 0.20d;
        if (!editions.isEmpty() || !segments.isEmpty() || !extras.isEmpty()) {
            confidence = Math.min(0.99d, confidence + 0.02d);
        }
        List<String> displayEditions = new ArrayList<>();
        if ("EXTRA".equals(assignmentType)) {
            displayEditions.addAll(extras);
        }
        displayEditions.addAll(editions);
        String editionLabel = displayEditions.isEmpty()
                ? null
                : String.join(" ", new LinkedHashSet<>(displayEditions));
        String segmentLabel = segments.isEmpty() ? null : String.join(" ", segments);
        String normalizedSubject = normalizedSubject(name, anchor, editions, segments, extras);
        String groupId = stableGroupId(sourceCandidateId, anchor.identity(), normalizedSubject);
        return new Candidate(
                anchor.episode(),
                anchor.date(),
                assignmentType,
                editionLabel,
                segmentLabel,
                groupId,
                confidence,
                List.copyOf(new LinkedHashSet<>(reasons)),
                anchor.identity(),
                normalizedSubject
        );
    }

    /**
     * Re-key a manually assigned file with the requested target episode while
     * retaining its source identity and normalized subject.
     */
    public String groupId(String sourceCandidateId, String fileName, Integer targetEpisode) {
        Candidate candidate = analyze(sourceCandidateId, fileName);
        String identity = candidate.identityAnchor();
        if (identity == null && targetEpisode != null && targetEpisode > 0) {
            identity = "E" + targetEpisode;
        }
        return stableGroupId(sourceCandidateId, identity, candidate.normalizedSubject());
    }

    private Anchor findAnchor(String name) {
        Integer episode = null;
        String episodeReason = null;
        Matcher standard = STANDARD_EPISODE.matcher(name);
        if (standard.find()) {
            episode = Integer.parseInt(standard.group(2));
            episodeReason = "STANDARD_EPISODE";
        } else {
            Matcher nxnn = NXNN_EPISODE.matcher(name);
            if (nxnn.find()) {
                episode = Integer.parseInt(nxnn.group(2));
                episodeReason = "NXNN_EPISODE";
            } else {
                Matcher episodeToken = EPISODE_TOKEN.matcher(name);
                if (episodeToken.find()) {
                    episode = parseChineseOrArabic(episodeToken.group(1));
                    episodeReason = "EPISODE_TOKEN";
                } else {
                    Matcher chinese = CHINESE_EPISODE.matcher(name);
                    if (chinese.find()) {
                        episode = parseChineseOrArabic(chinese.group(1));
                        episodeReason = episode == null ? null : "CHINESE_EPISODE";
                    } else {
                        Matcher leading = LEADING_EPISODE.matcher(name);
                        if (leading.matches()) {
                            int number = Integer.parseInt(leading.group(1));
                            if (number > 0 && !looksLikeDate(name)) {
                                episode = number;
                                episodeReason = "LEADING_EPISODE";
                            }
                        }
                    }
                }
            }
        }

        String date = null;
        String dateReason = null;
        Matcher fullDate = FULL_DATE.matcher(name);
        if (fullDate.find()) {
            date = fullDate.group(1) + "-" + fullDate.group(2) + "-" + fullDate.group(3);
            dateReason = "FULL_DATE_ANCHOR";
        } else {
            Matcher shortYearDate = SHORT_YEAR_DATE.matcher(name);
            if (shortYearDate.find()) {
                date = shortYearDate.group(1) + shortYearDate.group(2) + shortYearDate.group(3);
                dateReason = "SHORT_YEAR_DATE_ANCHOR";
            } else {
                Matcher shortDate = SHORT_DATE.matcher(name);
                if (shortDate.find()) {
                    date = shortDate.group(1) + "-" + shortDate.group(2);
                    dateReason = "MONTH_DAY_ANCHOR";
                }
            }
        }
        if (episode != null || date != null) {
            return new Anchor(episode, date, episodeReason, dateReason);
        }
        return Anchor.none();
    }

    private boolean looksLikeDate(String name) {
        return FULL_DATE.matcher(name).find()
                || SHORT_YEAR_DATE.matcher(name).find()
                || SHORT_DATE.matcher(name).find();
    }

    private List<String> findLabels(Pattern pattern, String name) {
        Matcher matcher = pattern.matcher(name);
        Set<String> labels = new LinkedHashSet<>();
        while (matcher.find()) {
            String label = matcher.group(1).trim().replaceAll("\\s+", " ");
            if (!label.isBlank()) labels.add(label);
        }
        return List.copyOf(labels);
    }

    private String normalizedSubject(
            String name,
            Anchor anchor,
            List<String> editions,
            List<String> segments,
            List<String> extras
    ) {
        String subject = FILE_EXTENSION.matcher(name).replaceFirst("");
        subject = STANDARD_EPISODE.matcher(subject).replaceAll(" ");
        subject = NXNN_EPISODE.matcher(subject).replaceAll(" ");
        subject = EPISODE_TOKEN.matcher(subject).replaceAll(" ");
        subject = CHINESE_EPISODE.matcher(subject).replaceAll(" ");
        subject = FULL_DATE.matcher(subject).replaceAll(" ");
        subject = SHORT_YEAR_DATE.matcher(subject).replaceAll(" ");
        subject = SHORT_DATE.matcher(subject).replaceAll(" ");
        if (anchor.episode() != null) {
            subject = subject.replaceFirst("^\\d{1,3}(?:[ _.-]+|$)", " ");
        }
        for (String label : editions) subject = removeIgnoreCase(subject, label);
        for (String label : segments) subject = removeIgnoreCase(subject, label);
        for (String label : extras) subject = removeIgnoreCase(subject, label);
        subject = SEPARATOR.matcher(subject).replaceAll(" ").trim().toLowerCase(Locale.ROOT);
        return subject.isBlank() ? "episode" : subject;
    }

    private String removeIgnoreCase(String value, String token) {
        return value.replaceAll("(?i)" + Pattern.quote(token), " ");
    }

    private Integer parseChineseOrArabic(String value) {
        if (value == null || value.isBlank()) return null;
        if (value.chars().allMatch(Character::isDigit)) return Integer.parseInt(value);
        int total = 0;
        int section = 0;
        for (char character : value.toCharArray()) {
            int digit = switch (character) {
                case '一' -> 1;
                case '二' -> 2;
                case '三' -> 3;
                case '四' -> 4;
                case '五' -> 5;
                case '六' -> 6;
                case '七' -> 7;
                case '八' -> 8;
                case '九' -> 9;
                case '零', '〇' -> 0;
                case '十' -> 10;
                case '百' -> 100;
                default -> -1;
            };
            if (digit < 0) return null;
            if (digit == 10 || digit == 100) {
                section = section == 0 ? 1 : section;
                total += section * digit;
                section = 0;
            } else {
                section = digit;
            }
        }
        return total + section;
    }

    private String stableGroupId(String sourceCandidateId, String identity, String subject) {
        String raw = String.valueOf(sourceCandidateId) + "\u0000"
                + (identity == null ? "UNKNOWN\u0000" + subject : identity);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder value = new StringBuilder(16);
            for (int index = 0; index < 8; index++) {
                value.append(String.format(Locale.ROOT, "%02x", digest[index]));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM 缺少 SHA-256", exception);
        }
    }

    public record Candidate(
            Integer detectedEpisode,
            String detectedDate,
            String assignmentType,
            String editionLabel,
            String segmentLabel,
            String groupId,
            double confidence,
            List<String> reasonCodes,
            String identityAnchor,
            String normalizedSubject
    ) {

        public Candidate {
            reasonCodes = reasonCodes == null ? List.of() : List.copyOf(reasonCodes);
        }
    }

    private record Anchor(Integer episode, String date, String reasonCode, String dateReasonCode) {
        private static Anchor episode(int number, String reason) {
            return new Anchor(number, null, reason, null);
        }

        private static Anchor date(String date, String reason) {
            return new Anchor(null, date, null, reason);
        }

        private static Anchor none() {
            return new Anchor(null, null, null, null);
        }

        private boolean hasIdentity() {
            return episode != null || date != null;
        }

        private String identity() {
            return episode == null ? date : "E" + episode;
        }
    }
}
