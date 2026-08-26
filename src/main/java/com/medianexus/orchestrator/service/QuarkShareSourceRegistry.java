package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.qas.QasShareUrl;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * Holds short-lived preview sessions.  Candidate ids are opaque random
 * handles; Quark fids and temporary share credentials never cross the API
 * boundary.  The registry also gives submit-time validation a concrete share
 * tree snapshot to compare with a fresh inspection.
 */
@Component
public class QuarkShareSourceRegistry {

    private static final Duration SESSION_TTL = Duration.ofMinutes(15);
    private static final Pattern SEASON = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:s(?:eason)?[ ._-]*0?(\\d{1,2})|第\\s*0?(\\d{1,2})\\s*季)(?:[^0-9]|$)"
    );
    private static final Pattern SEASON_RANGE = Pattern.compile(
            "(?i)s(?:eason)?[ ._]*0?(\\d{1,2})\\s*[-~至到]\\s*s?(?:eason)?[ ._]*0?(\\d{1,2})"
    );
    private static final Pattern EPISODE_SEASON = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])s(\\d{1,2})e\\d{1,3}(?:[^0-9]|$)"
    );
    private static final Pattern CHINESE_DIGITS = Pattern.compile("第\\s*([一二三四五六七八九十]{1,3})\\s*季");
    private static final int MAX_SESSIONS = 256;

    private final Map<String, PreviewSession> sessions = new ConcurrentHashMap<>();

    public PreviewSession create(String shareUrl, String mediaType, QasShareTree tree) {
        evictExpired();
        Map<String, SourceCandidate> candidates = new LinkedHashMap<>();
        List<String> rootCandidateIds = new ArrayList<>();
        collectRootCandidates(tree, candidates, rootCandidateIds);
        String previewId = UUID.randomUUID().toString();
        PreviewSession session = new PreviewSession(
                previewId,
                shareUrl,
                mediaType,
                tree,
                fingerprint(tree),
                candidates,
                rootCandidateIds,
                Instant.now()
        );
        sessions.put(previewId, session);
        while (sessions.size() > MAX_SESSIONS) {
            sessions.values().stream()
                    .min((left, right) -> left.createdAt().compareTo(right.createdAt()))
                    .map(PreviewSession::previewId)
                    .ifPresent(sessions::remove);
        }
        return session;
    }

    public PreviewSession require(String previewId) {
        if (!StringUtils.hasText(previewId)) {
            throw new IllegalArgumentException("预览已过期，请先刷新分享树");
        }
        PreviewSession session = sessions.get(previewId);
        if (session == null || session.createdAt().plus(SESSION_TTL).isBefore(Instant.now())) {
            sessions.remove(previewId);
            throw new IllegalArgumentException("预览已过期，请先刷新分享树");
        }
        return session;
    }

    public static String fingerprint(QasShareTree tree) {
        StringBuilder canonical = new StringBuilder(tree.sourceUrl());
        tree.entries().forEach(node -> appendCanonical(canonical, node));
        return sha256(canonical.toString());
    }

    public static String fingerprint(SourceCandidate candidate, QasShareTree tree) {
        StringBuilder canonical = new StringBuilder(tree.sourceUrl());
        candidate.fidPath().forEach(fid -> canonical.append("/fid:").append(fid));
        candidate.entries().forEach(node -> appendCanonical(canonical, node));
        return sha256(canonical.toString());
    }

    public static SourceCandidate locate(PreviewSession session, String candidateId, QasShareTree freshTree) {
        SourceCandidate expected = session.candidates().get(candidateId);
        if (expected == null) {
            throw new IllegalArgumentException("来源候选无效或不属于当前分享，请刷新预览");
        }
        SourceCandidate current = locateCandidate(freshTree, expected);
        if (current == null || !fingerprint(expected, session.tree()).equals(fingerprint(current, freshTree))) {
            throw new IllegalArgumentException("分享目录或文件已变化，请刷新预览");
        }
        return current;
    }

    private void collectRootCandidates(
            QasShareTree tree,
            Map<String, SourceCandidate> candidates,
            List<String> rootCandidateIds
    ) {
        List<QasShareNode> directFiles = tree.entries().stream().filter(node -> !node.directory()).toList();
        if (hasPlayableVideo(directFiles)) {
            String id = addCandidate(candidates, tree, "当前目录直属文件", "", "DIRECT_FILES", List.of(), directFiles);
            rootCandidateIds.add(id);
        }
        for (QasShareNode node : tree.entries()) {
            if (node.directory()) {
                collectDirectory(tree, node, List.of(node.name()), List.of(node.fid()), candidates);
            }
        }
    }

    private void collectDirectory(
            QasShareTree tree,
            QasShareNode node,
            List<String> names,
            List<String> fids,
            Map<String, SourceCandidate> candidates
    ) {
        List<QasShareNode> directFiles = node.children().stream().filter(child -> !child.directory()).toList();
        List<QasShareNode> childDirectories = node.children().stream().filter(QasShareNode::directory).toList();
        if (hasPlayableVideo(directFiles)) {
            String kind = childDirectories.isEmpty() ? "LEAF_DIRECTORY" : "DIRECT_FILES";
            addCandidate(candidates, tree, node.name(), String.join("/", names), kind, fids, directFiles);
        }
        for (QasShareNode child : childDirectories) {
            List<String> childNames = new ArrayList<>(names);
            childNames.add(child.name());
            List<String> childFids = new ArrayList<>(fids);
            childFids.add(child.fid());
            collectDirectory(tree, child, childNames, childFids, candidates);
        }
    }

    private String addCandidate(
            Map<String, SourceCandidate> candidates,
            QasShareTree tree,
            String sourceName,
            String relativePath,
            String kind,
            List<String> fidPath,
            List<QasShareNode> entries
    ) {
        SeasonDetection detection = detectSeason(sourceName, relativePath, entries);
        String id = UUID.randomUUID().toString();
        String sourceUrl = fidPath.isEmpty()
                ? tree.sourceUrl()
                : QasShareUrl.withDirectoryFid(tree.sourceUrl(), fidPath.get(fidPath.size() - 1));
        candidates.put(id, new SourceCandidate(
                id,
                sourceName,
                relativePath,
                kind,
                sourceUrl,
                List.copyOf(fidPath),
                List.copyOf(entries),
                detection.seasonNumber(),
                detection.status()
        ));
        return id;
    }

    private static SourceCandidate locateCandidate(QasShareTree tree, SourceCandidate expected) {
        if (expected.fidPath().isEmpty()) {
            List<QasShareNode> files = tree.entries().stream().filter(node -> !node.directory()).toList();
            return hasPlayableVideo(files)
                    ? new SourceCandidate(
                            expected.id(), expected.sourceName(), expected.relativePath(), expected.kind(),
                            tree.sourceUrl(), List.of(), files, expected.detectedSeason(), expected.seasonStatus()
                    )
                    : null;
        }
        QasShareNode current = null;
        List<QasShareNode> entries = tree.entries();
        for (String fid : expected.fidPath()) {
            current = entries.stream()
                    .filter(node -> node.directory() && fid.equals(node.fid()))
                    .findFirst()
                    .orElse(null);
            if (current == null) {
                return null;
            }
            entries = current.children();
        }
        if (current == null) {
            return null;
        }
        List<QasShareNode> directFiles = current.children().stream().filter(node -> !node.directory()).toList();
        if (!hasPlayableVideo(directFiles)) {
            return null;
        }
        return new SourceCandidate(
                expected.id(), current.name(), expected.relativePath(), expected.kind(),
                QasShareUrl.withDirectoryFid(tree.sourceUrl(), current.fid()),
                expected.fidPath(), directFiles, expected.detectedSeason(), expected.seasonStatus()
        );
    }

    private static SeasonDetection detectSeason(String sourceName, String relativePath, List<QasShareNode> entries) {
        List<Integer> sourceNumbers = new ArrayList<>();
        addSeasonMatches(sourceNumbers, sourceName);
        sourceNumbers = distinctNumbers(sourceNumbers);
        List<Integer> fileNumbers = new ArrayList<>();
        for (QasShareNode entry : entries) {
            addSeasonMatches(fileNumbers, entry.name());
        }
        fileNumbers = distinctNumbers(fileNumbers);
        if (sourceNumbers.size() == 1) {
            Integer sourceSeason = sourceNumbers.get(0);
            if (fileNumbers.isEmpty() || fileNumbers.stream().allMatch(sourceSeason::equals)) {
                return new SeasonDetection(sourceSeason, "AUTO");
            }
            return new SeasonDetection(null, "MIXED");
        }
        if (sourceNumbers.size() > 1 || fileNumbers.size() > 1) {
            return new SeasonDetection(null, "MIXED");
        }
        if (fileNumbers.size() == 1) {
            return new SeasonDetection(fileNumbers.get(0), "AUTO");
        }
        List<String> pathSegments = List.of(relativePath.split("/"));
        for (int index = pathSegments.size() - 1; index >= 0; index--) {
            String segment = pathSegments.get(index);
            if (segment.equals(sourceName)) {
                continue;
            }
            List<Integer> pathNumbers = new ArrayList<>();
            addSeasonMatches(pathNumbers, segment);
            pathNumbers = distinctNumbers(pathNumbers);
            if (pathNumbers.size() == 1) {
                return new SeasonDetection(pathNumbers.get(0), "AUTO");
            }
            if (pathNumbers.size() > 1) {
                return new SeasonDetection(null, "MIXED");
            }
        }
        return new SeasonDetection(null, "UNRECOGNIZED");
    }

    private static List<Integer> distinctNumbers(List<Integer> numbers) {
        return numbers.stream().distinct().sorted().toList();
    }

    private static void addSeasonMatches(List<Integer> numbers, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        Matcher range = SEASON_RANGE.matcher(value);
        while (range.find()) {
            numbers.add(Integer.parseInt(range.group(1)));
            numbers.add(Integer.parseInt(range.group(2)));
        }
        Matcher matcher = SEASON.matcher(value);
        while (matcher.find()) {
            String season = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            numbers.add(Integer.parseInt(season));
        }
        Matcher episode = EPISODE_SEASON.matcher(value);
        while (episode.find()) {
            numbers.add(Integer.parseInt(episode.group(1)));
        }
        Matcher chinese = CHINESE_DIGITS.matcher(value);
        while (chinese.find()) {
            numbers.add(chineseNumber(chinese.group(1)));
        }
    }

    private static int chineseNumber(String value) {
        String digits = "一二三四五六七八九";
        if ("十".equals(value)) {
            return 10;
        }
        int ten = value.indexOf('十');
        if (ten >= 0) {
            int tens = ten == 0 ? 1 : digits.indexOf(value.charAt(0)) + 1;
            int ones = ten == value.length() - 1 ? 0 : digits.indexOf(value.charAt(ten + 1)) + 1;
            return tens * 10 + ones;
        }
        return digits.indexOf(value.charAt(0)) + 1;
    }

    private static boolean hasPlayableVideo(List<QasShareNode> nodes) {
        return nodes.stream().anyMatch(node -> !node.directory()
                && node.name().toLowerCase(Locale.ROOT).matches(
                ".*\\.(mkv|mp4|avi|mov|wmv|flv|ts|m2ts|webm|rmvb)$"));
    }

    private static void appendCanonical(StringBuilder builder, QasShareNode node) {
        builder.append('|').append(node.fid()).append('|').append(node.name())
                .append('|').append(node.directory()).append('|').append(node.size())
                .append('|').append(node.category());
        node.children().forEach(child -> appendCanonical(builder, child));
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format(Locale.ROOT, "%02x", item));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private void evictExpired() {
        Instant now = Instant.now();
        sessions.values().removeIf(session -> session.createdAt().plus(SESSION_TTL).isBefore(now));
    }

    public static final class PreviewSession {

        private final String previewId;
        private final String shareUrl;
        private final String mediaType;
        private final QasShareTree tree;
        private final String treeFingerprint;
        private final Map<String, SourceCandidate> candidates;
        private final List<String> rootCandidateIds;
        private final Instant createdAt;
        private volatile String planFingerprint;

        private PreviewSession(
                String previewId,
                String shareUrl,
                String mediaType,
                QasShareTree tree,
                String treeFingerprint,
                Map<String, SourceCandidate> candidates,
                List<String> rootCandidateIds,
                Instant createdAt
        ) {
            this.previewId = previewId;
            this.shareUrl = shareUrl;
            this.mediaType = mediaType;
            this.tree = tree;
            this.treeFingerprint = treeFingerprint;
            this.candidates = Collections.unmodifiableMap(new LinkedHashMap<>(candidates));
            this.rootCandidateIds = List.copyOf(rootCandidateIds);
            this.createdAt = createdAt;
        }

        public String previewId() { return previewId; }
        public String shareUrl() { return shareUrl; }
        public String mediaType() { return mediaType; }
        public QasShareTree tree() { return tree; }
        public String treeFingerprint() { return treeFingerprint; }
        public Map<String, SourceCandidate> candidates() { return candidates; }
        public List<String> rootCandidateIds() { return rootCandidateIds; }
        public Instant createdAt() { return createdAt; }
        public void setPlanFingerprint(String fingerprint) { planFingerprint = fingerprint; }
        public String planFingerprint() { return planFingerprint; }
    }

    public record SourceCandidate(
            String id,
            String sourceName,
            String relativePath,
            String kind,
            String sourceUrl,
            List<String> fidPath,
            List<QasShareNode> entries,
            Integer detectedSeason,
            String seasonStatus
    ) {
        public SourceCandidate {
            fidPath = List.copyOf(fidPath);
            entries = List.copyOf(entries);
        }
    }

    private record SeasonDetection(Integer seasonNumber, String status) {
    }
}
