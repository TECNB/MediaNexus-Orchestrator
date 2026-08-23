package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.qas.QasShareUrl;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class QuarkIngestPlanner {

    private static final Pattern PURE_NUMBER = Pattern.compile(
            "^(\\d{2,3})((?:\\.[^.]+)*)\\.(mkv|mp4|srt|ass|ssa|vtt|sub)$"
    );
    private static final Pattern DATE_ONLY = Pattern.compile(
            "^(20\\d{2})(\\d{2})(\\d{2})\\.(mkv|mp4)$"
    );
    private static final String MEDIA_EXTENSIONS = "mkv|mp4|srt|ass|ssa|vtt|sub";
    private static final Pattern INVALID_VERSION_CHARACTER = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final Set<String> NON_VERSION_DIRECTORIES = Set.of(
            "extras", "specials", "trailers", "花絮", "番外", "采访", "字幕", "海报"
    );

    public QasIngestPlan planSeasonMedia(
            String mediaType,
            String title,
            int seasonNumber,
            String savePath,
            QasShareTree shareTree,
            Map<LocalDate, Integer> airDateEpisodes
    ) {
        ContentRoot contentRoot = unwrapSingleDirectory(shareTree.sourceUrl(), shareTree.entries());
        if (isVersionDirectorySet(contentRoot.entries())) {
            return planVersions(
                    title,
                    seasonNumber,
                    savePath,
                    shareTree.sourceUrl(),
                    contentRoot.entries(),
                    airDateEpisodes
            );
        }
        if (contentRoot.entries().stream().noneMatch(QasShareNode::directory)) {
            return planOrdinary(title, seasonNumber, savePath, contentRoot);
        }
        throw new QuarkIngestPlanningException("分享中包含无法安全展平的复杂目录");
    }

    private QasIngestPlan planOrdinary(
            String title,
            int seasonNumber,
            String savePath,
            ContentRoot contentRoot
    ) {
        if (contentRoot.entries().isEmpty()) {
            throw new QuarkIngestPlanningException("Quark 分享中没有可转存的文件");
        }
        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        String taskName = title + " S" + season;
        for (RuleCandidate candidate : ordinaryCandidates(title, season)) {
            if (isSafeForAllFiles(candidate, contentRoot.entries())) {
                return new QasIngestPlan(
                        List.of(new QasTaskPlan(
                                taskName,
                                contentRoot.sourceUrl(),
                                savePath,
                                candidate.pattern().pattern(),
                                candidate.replace(),
                                null
                        )),
                        List.of()
                );
            }
        }
        return new QasIngestPlan(
                List.of(new QasTaskPlan(taskName, contentRoot.sourceUrl(), savePath, "", "", null)),
                List.of("分享中存在无法安全解释的文件，已使用空重命名规则以避免漏转存")
        );
    }

    private List<RuleCandidate> ordinaryCandidates(String title, String season) {
        String episodePrefix = title + " - S" + season + "E";
        List<RuleCandidate> candidates = new ArrayList<>();
        candidates.add(candidate(
                "^.*?[Ss]\\d{1,2}[Ee](\\d{2,3})((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^.*?[Ss]\\d{1,2}[Ee](\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1\\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^.*?\\d{1,2}[xX](\\d{2,3})((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^.*?\\d{1,2}[xX](\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1\\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^第\\s*(\\d{2,3})[集话期]\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - \\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^第\\s*(\\d)[集话期]\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - \\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^第\\s*(\\d{2,3})[集话期]\\s*\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1.\\2",
                matcher -> episodePrefix + matcher.group(1) + "." + matcher.group(2),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^第\\s*(\\d)[集话期]\\s*\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1.\\2",
                matcher -> episodePrefix + "0" + matcher.group(1) + "." + matcher.group(2),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                PURE_NUMBER.pattern(),
                episodePrefix + "\\1\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^(\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1\\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^(20\\d{2})(\\d{2})(\\d{2})(.*)\\.(mp4|mkv|srt|ass|ssa|vtt|sub)$",
                title + " - \\1-\\2-\\3 - \\4.\\5",
                matcher -> title + " - " + matcher.group(1) + "-" + matcher.group(2) + "-"
                        + matcher.group(3) + " - " + matcher.group(4) + "." + matcher.group(5),
                matcher -> matcher.group(1) + matcher.group(2) + matcher.group(3)
        ));
        return candidates;
    }

    private RuleCandidate candidate(
            String pattern,
            String replace,
            Function<Matcher, String> targetName,
            Function<Matcher, String> episodeKey
    ) {
        return new RuleCandidate(Pattern.compile(pattern), replace, targetName, episodeKey);
    }

    private boolean isSafeForAllFiles(RuleCandidate candidate, List<QasShareNode> files) {
        if (files.isEmpty()) {
            return false;
        }
        Set<String> videoKeys = new HashSet<>();
        List<String> subtitleKeys = new ArrayList<>();
        Set<String> targetNames = new HashSet<>();
        for (QasShareNode file : files) {
            Matcher matcher = candidate.pattern().matcher(file.name());
            if (file.directory() || !matcher.matches()) {
                return false;
            }
            String targetName = candidate.targetName().apply(matcher).toLowerCase(Locale.ROOT);
            if (!targetNames.add(targetName)) {
                return false;
            }
            String key = candidate.episodeKey().apply(matcher);
            if (isSubtitle(file.name())) {
                subtitleKeys.add(key);
            } else {
                videoKeys.add(key);
            }
        }
        return !videoKeys.isEmpty() && videoKeys.containsAll(subtitleKeys);
    }

    private boolean isSubtitle(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(srt|ass|ssa|vtt|sub)$");
    }

    private QasIngestPlan planVersions(
            String title,
            int seasonNumber,
            String savePath,
            String sourceUrl,
            List<QasShareNode> versionDirectories,
            Map<LocalDate, Integer> airDateEpisodes
    ) {
        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        Set<String> allTargetNames = new HashSet<>();
        Set<Integer> referenceEpisodes = null;
        List<QasTaskPlan> tasks = new ArrayList<>();
        for (QasShareNode directory : versionDirectories) {
            String versionLabel = cleanVersionLabel(directory.name());
            if (versionLabel.isBlank() || NON_VERSION_DIRECTORIES.contains(versionLabel.toLowerCase(Locale.ROOT))) {
                throw new QuarkIngestPlanningException("分享中包含不能作为版本的目录：" + directory.name());
            }

            Set<Integer> episodes = new HashSet<>();
            String pattern;
            String replace;
            VersionRule versionRule = versionCandidates(title, season, versionLabel).stream()
                    .filter(candidate -> versionRuleMatches(candidate, directory.children()))
                    .findFirst()
                    .orElse(null);
            if (versionRule != null) {
                VersionAnalysis analysis = analyzeVersionRule(versionRule, directory.children());
                episodes.addAll(analysis.episodes());
                analysis.targetNames().forEach(target -> addUniqueTarget(allTargetNames, target));
                pattern = versionRule.pattern().pattern();
                replace = versionRule.replace();
            } else if (directory.children().stream().allMatch(file ->
                    !file.directory() && DATE_ONLY.matcher(file.name()).matches())) {
                if (airDateEpisodes == null || airDateEpisodes.isEmpty()) {
                    throw new QuarkIngestPlanningException(
                            QuarkIngestPlanningException.Reason.DATE_MAPPING_REQUIRED,
                            "多版本目录仅有播出日期，需要 TMDB Season Details 映射集号"
                    );
                }
                List<QasShareNode> orderedFiles = directory.children().stream()
                        .sorted((left, right) -> dateFrom(left.name()).compareTo(dateFrom(right.name())))
                        .toList();
                List<Integer> orderedEpisodes = new ArrayList<>();
                orderedFiles.stream()
                        .map(file -> dateFrom(file.name()))
                        .sorted()
                        .forEach(date -> {
                            Integer episode = airDateEpisodes.get(date);
                            if (episode == null) {
                                throw new QuarkIngestPlanningException("TMDB 中找不到播出日期 " + date + " 对应的集号");
                            }
                            orderedEpisodes.add(episode);
                        });
                for (int index = 0; index < orderedEpisodes.size(); index++) {
                    if (orderedEpisodes.get(index) != index + 1) {
                        throw new QuarkIngestPlanningException("日期集数不是从 1 开始的连续序列，QAS 无法安全映射");
                    }
                    episodes.add(orderedEpisodes.get(index));
                    String targetName = title + " - S" + season
                            + "E" + String.format(Locale.ROOT, "%02d", orderedEpisodes.get(index))
                            + " - " + versionLabel + "." + extensionOf(orderedFiles.get(index).name());
                    addUniqueTarget(allTargetNames, targetName);
                }
                pattern = DATE_ONLY.pattern();
                replace = title + " - S" + season + "E{II} - " + versionLabel + ".\\4";
            } else {
                throw new QuarkIngestPlanningException("版本目录存在无法安全命名的文件：" + directory.name());
            }
            if (episodes.isEmpty()) {
                throw new QuarkIngestPlanningException("版本目录中没有可识别的剧集：" + directory.name());
            }
            if (referenceEpisodes != null && overlap(referenceEpisodes, episodes) < 0.8d) {
                throw new QuarkIngestPlanningException("同级目录的集数不重合，不能当作多版本");
            }
            referenceEpisodes = episodes;

            String taskName = title + " S" + season + " [" + versionLabel + "]";
            tasks.add(new QasTaskPlan(
                    taskName,
                    withDirectoryFid(sourceUrl, directory.fid()),
                    savePath,
                    pattern,
                    replace,
                    versionLabel
            ));
        }
        List<String> warnings = tasks.size() > 8
                ? List.of("检测到超过 8 个版本，Emby 每集最多显示 8 个版本")
                : List.of();
        return new QasIngestPlan(tasks, warnings);
    }

    private List<VersionRule> versionCandidates(String title, String season, String versionLabel) {
        String episodePrefix = title + " - S" + season + "E";
        List<VersionRule> rules = new ArrayList<>();
        rules.add(versionRule(
                "^.*?[Ss]\\d{1,2}[Ee](\\d{2,3})((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - " + versionLabel + "\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + versionLabel
                        + matcher.group(2) + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^.*?[Ss]\\d{1,2}[Ee](\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - " + versionLabel + "\\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + versionLabel
                        + matcher.group(2) + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^.*?\\d{1,2}[xX](\\d{2,3})((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - " + versionLabel + "\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + versionLabel
                        + matcher.group(2) + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^.*?\\d{1,2}[xX](\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - " + versionLabel + "\\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + versionLabel
                        + matcher.group(2) + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^第\\s*(\\d{2,3})[集话期]\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - \\2 - " + versionLabel + ".\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + matcher.group(2)
                        + " - " + versionLabel + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^第\\s*(\\d)[集话期]\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - \\2 - " + versionLabel + ".\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + matcher.group(2)
                        + " - " + versionLabel + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^第\\s*(\\d{2,3})[集话期]\\s*\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - " + versionLabel + ".\\2",
                matcher -> episodePrefix + matcher.group(1) + " - " + versionLabel + "." + matcher.group(2)
        ));
        rules.add(versionRule(
                "^第\\s*(\\d)[集话期]\\s*\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - " + versionLabel + ".\\2",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + versionLabel + "." + matcher.group(2)
        ));
        rules.add(versionRule(
                PURE_NUMBER.pattern(),
                episodePrefix + "\\1 - " + versionLabel + "\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + versionLabel
                        + matcher.group(2) + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^(\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - " + versionLabel + "\\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + versionLabel
                        + matcher.group(2) + "." + matcher.group(3)
        ));
        return rules;
    }

    private VersionRule versionRule(
            String pattern,
            String replace,
            Function<Matcher, String> targetName
    ) {
        return new VersionRule(
                Pattern.compile(pattern),
                replace,
                targetName
        );
    }

    private boolean versionRuleMatches(VersionRule rule, List<QasShareNode> files) {
        return !files.isEmpty() && files.stream().allMatch(file ->
                !file.directory() && rule.pattern().matcher(file.name()).matches()
        );
    }

    private VersionAnalysis analyzeVersionRule(VersionRule rule, List<QasShareNode> files) {
        Set<Integer> videoEpisodes = new HashSet<>();
        Set<Integer> subtitleEpisodes = new HashSet<>();
        Set<String> localTargets = new HashSet<>();
        List<String> targets = new ArrayList<>();
        for (QasShareNode file : files) {
            Matcher matcher = rule.pattern().matcher(file.name());
            matcher.matches();
            int episode = Integer.parseInt(matcher.group(1));
            if (isSubtitle(file.name())) {
                subtitleEpisodes.add(episode);
            } else {
                videoEpisodes.add(episode);
            }
            String target = rule.targetName().apply(matcher);
            if (!localTargets.add(target.toLowerCase(Locale.ROOT))) {
                throw new QuarkIngestPlanningException("规划后的文件名发生冲突：" + target);
            }
            targets.add(target);
        }
        if (videoEpisodes.isEmpty() || !videoEpisodes.containsAll(subtitleEpisodes)) {
            throw new QuarkIngestPlanningException("版本目录存在没有对应视频的字幕");
        }
        return new VersionAnalysis(videoEpisodes, targets);
    }

    private LocalDate dateFrom(String fileName) {
        Matcher matcher = DATE_ONLY.matcher(fileName);
        if (!matcher.matches()) {
            throw new QuarkIngestPlanningException("无法解析播出日期：" + fileName);
        }
        return LocalDate.of(
                Integer.parseInt(matcher.group(1)),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        );
    }

    private String extensionOf(String fileName) {
        int separator = fileName.lastIndexOf('.');
        return separator >= 0 ? fileName.substring(separator + 1) : "";
    }

    private void addUniqueTarget(Set<String> targetNames, String targetName) {
        if (!targetNames.add(targetName.toLowerCase(Locale.ROOT))) {
            throw new QuarkIngestPlanningException("规划后的文件名发生冲突：" + targetName);
        }
    }

    private ContentRoot unwrapSingleDirectory(String sourceUrl, List<QasShareNode> entries) {
        List<QasShareNode> current = entries;
        String currentUrl = sourceUrl;
        while (current.size() == 1 && current.get(0).directory()) {
            QasShareNode wrapper = current.get(0);
            currentUrl = withDirectoryFid(sourceUrl, wrapper.fid());
            current = wrapper.children();
        }
        return new ContentRoot(currentUrl, current);
    }

    private boolean isVersionDirectorySet(List<QasShareNode> entries) {
        return entries.size() > 1 && entries.stream().allMatch(QasShareNode::directory);
    }

    private double overlap(Set<Integer> left, Set<Integer> right) {
        Set<Integer> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return (double) intersection.size() / Math.max(left.size(), right.size());
    }

    private String cleanVersionLabel(String value) {
        String cleaned = value == null ? "" : INVALID_VERSION_CHARACTER.matcher(value).replaceAll(" ");
        return cleaned.trim().replaceAll("\\s+", " ");
    }

    private String withDirectoryFid(String sourceUrl, String fid) {
        try {
            return QasShareUrl.withDirectoryFid(sourceUrl, fid);
        } catch (IllegalArgumentException exception) {
            throw new QuarkIngestPlanningException("无法构造 Quark 子目录链接");
        }
    }

    private record ContentRoot(String sourceUrl, List<QasShareNode> entries) {
    }

    private record RuleCandidate(
            Pattern pattern,
            String replace,
            Function<Matcher, String> targetName,
            Function<Matcher, String> episodeKey
    ) {
    }

    private record VersionRule(
            Pattern pattern,
            String replace,
            Function<Matcher, String> targetName
    ) {
    }

    private record VersionAnalysis(Set<Integer> episodes, List<String> targetNames) {
    }
}
