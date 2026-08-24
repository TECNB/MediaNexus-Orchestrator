package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.qas.QasShareUrl;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
            "^(?!\\d{1,2}\\.\\d{2}(?:\\D|$))(\\d{2,3})((?:\\.[^.]+)*)\\.(mkv|mp4|avi|mov|wmv|flv|ts|m2ts|webm|rmvb|srt|ass|ssa|vtt|sub)$"
    );
    private static final Pattern DATE_ONLY = Pattern.compile(
            "^(20\\d{2})(\\d{2})(\\d{2})\\.(mkv|mp4)$"
    );
    private static final String VIDEO_EXTENSIONS = "mkv|mp4|avi|mov|wmv|flv|ts|m2ts|webm|rmvb";
    private static final String SUBTITLE_EXTENSIONS = "srt|ass|ssa|vtt|sub";
    private static final String MEDIA_EXTENSIONS = VIDEO_EXTENSIONS + "|" + SUBTITLE_EXTENSIONS;
    private static final Pattern PLAYABLE_VIDEO = Pattern.compile(
            ".*\\.(" + VIDEO_EXTENSIONS + ")$", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern MEDIA_FILE = Pattern.compile(
            ".*\\.(" + MEDIA_EXTENSIONS + ")$", Pattern.CASE_INSENSITIVE
    );
    private static final Pattern SEASON_DIRECTORY = Pattern.compile(
            "(?i)(?:^|[^a-z0-9])(?:s(?:eason)?[ ._-]*0?(\\d{1,2})|第\\s*0?(\\d{1,2})\\s*季)(?:[^0-9]|$)"
    );
    private static final Pattern CHINESE_SEASON_DIRECTORY = Pattern.compile(
            "第\\s*([一二三四五六七八九十]{1,3})\\s*季"
    );
    private static final Pattern EXPLICIT_SEASON_EPISODE = Pattern.compile(
            "(?i)(?:[Ss](\\d{1,2})[ ._-]*(?:[Ee][Pp]|[Ee])\\d{1,3}|(?:^|\\D)(\\d{1,2})[xX]\\d{1,3})"
    );
    private static final Pattern INVALID_VERSION_CHARACTER = Pattern.compile("[\\\\/:*?\"<>|]");
    private static final Set<String> NON_VERSION_DIRECTORIES = Set.of(
            "extras", "specials", "trailers", "花絮", "番外", "采访", "字幕", "海报"
    );

    public QasIngestPlan planMovie(String taskName, String savePath, QasShareTree shareTree) {
        ContentRoot contentRoot = unwrapSingleDirectory(shareTree.sourceUrl(), shareTree.entries());
        if (!containsPlayableVideo(contentRoot.entries())) {
            throw new QuarkIngestPlanningException("Quark 分享中没有可播放视频");
        }
        List<String> warnings = contentRoot.entries().stream().anyMatch(QasShareNode::directory)
                ? List.of("电影分享包含多个目录，将保留分享内的目录结构")
                : List.of();
        return new QasIngestPlan(
                List.of(new QasTaskPlan(taskName, contentRoot.sourceUrl(), savePath, "", "", null)),
                warnings
        );
    }

    public QasIngestPlan planSeries(
            String title,
            int seasonNumber,
            String savePath,
            QasShareTree shareTree,
            Map<LocalDate, Integer> airDateEpisodes
    ) {
        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        return planSeasonMedia(
                title, seasonNumber, savePath, shareTree, airDateEpisodes,
                ordinaryEpisodeCandidates(title, season)
        );
    }

    public QasIngestPlan planVariety(
            String title,
            int seasonNumber,
            String savePath,
            QasShareTree shareTree,
            Map<LocalDate, Integer> airDateEpisodes
    ) {
        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        List<RuleCandidate> candidates = new ArrayList<>(ordinaryEpisodeCandidates(title, season));
        candidates.addAll(varietyDateCandidates(title));
        return planSeasonMedia(title, seasonNumber, savePath, shareTree, airDateEpisodes, candidates);
    }

    private QasIngestPlan planSeasonMedia(
            String title,
            int seasonNumber,
            String savePath,
            QasShareTree shareTree,
            Map<LocalDate, Integer> airDateEpisodes,
            List<RuleCandidate> ordinaryCandidates
    ) {
        ContentRoot contentRoot = unwrapSingleDirectory(shareTree.sourceUrl(), shareTree.entries());
        contentRoot = selectRequestedSeason(contentRoot, seasonNumber);
        contentRoot = unwrapSingleDirectory(contentRoot.sourceUrl(), contentRoot.entries());
        if (isVersionDirectorySet(contentRoot.entries())) {
            validateExplicitSeason(contentRoot.entries(), seasonNumber);
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
            validateExplicitSeason(contentRoot.entries(), seasonNumber);
            return planOrdinary(title, seasonNumber, savePath, contentRoot, ordinaryCandidates);
        }
        throw new QuarkIngestPlanningException("分享中包含无法安全展平的复杂目录");
    }

    private QasIngestPlan planOrdinary(
            String title,
            int seasonNumber,
            String savePath,
            ContentRoot contentRoot,
            List<RuleCandidate> candidates
    ) {
        if (contentRoot.entries().stream().noneMatch(file -> isPlayableVideo(file.name()))) {
            throw new QuarkIngestPlanningException("Quark 分享中没有可播放视频");
        }
        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        String taskName = title + " S" + season;
        List<QasShareNode> mediaFiles = contentRoot.entries().stream()
                .filter(file -> isMediaFile(file.name()))
                .toList();
        List<QasShareNode> ignoredFiles = contentRoot.entries().stream()
                .filter(file -> !isMediaFile(file.name()))
                .toList();
        for (RuleCandidate candidate : candidates) {
            if (isSafeForAllFiles(candidate, mediaFiles)) {
                return new QasIngestPlan(
                        List.of(new QasTaskPlan(
                                taskName,
                                contentRoot.sourceUrl(),
                                savePath,
                                candidate.pattern().pattern(),
                                candidate.replace(),
                                null
                        )),
                        ignoredFiles.isEmpty()
                                ? List.of()
                                : List.of("已通过 QAS 规则忽略 " + ignoredFiles.size() + " 个非媒体文件")
                );
            }
        }

        Map<RuleCandidate, List<QasShareNode>> groups = groupByDisjointRule(candidates, mediaFiles);
        if (groups != null && groups.size() > 1) {
            Set<String> targets = new HashSet<>();
            Map<String, Integer> labelCounts = new LinkedHashMap<>();
            List<QasTaskPlan> tasks = new ArrayList<>();
            for (Map.Entry<RuleCandidate, List<QasShareNode>> group : groups.entrySet()) {
                RuleCandidate candidate = group.getKey();
                if (!isSafeForAllFiles(candidate, group.getValue())) {
                    groups = null;
                    break;
                }
                for (QasShareNode file : group.getValue()) {
                    Matcher matcher = candidate.pattern().matcher(file.name());
                    matcher.matches();
                    addUniqueTarget(targets, candidate.targetName().apply(matcher));
                }
                int labelIndex = labelCounts.merge(candidate.label(), 1, Integer::sum);
                String taskLabel = labelIndex == 1
                        ? candidate.label()
                        : candidate.label() + "-" + labelIndex;
                tasks.add(new QasTaskPlan(
                        taskName + " [" + taskLabel + "]",
                        contentRoot.sourceUrl(), savePath,
                        candidate.pattern().pattern(), candidate.replace(), null
                ));
            }
            if (groups != null) {
                List<String> warnings = new ArrayList<>();
                warnings.add("分享包含多个互斥命名规则，已拆分为 " + tasks.size() + " 个 QAS 任务");
                if (!ignoredFiles.isEmpty()) {
                    warnings.add("已通过 QAS 规则忽略 " + ignoredFiles.size() + " 个非媒体文件");
                }
                return new QasIngestPlan(tasks, warnings);
            }
        }
        return new QasIngestPlan(
                List.of(new QasTaskPlan(taskName, contentRoot.sourceUrl(), savePath, "", "", null)),
                List.of("分享中存在无法安全解释的文件，已使用空重命名规则以避免漏转存")
        );
    }

    private List<RuleCandidate> ordinaryEpisodeCandidates(String title, String season) {
        String episodePrefix = title + " - S" + season + "E";
        List<RuleCandidate> candidates = new ArrayList<>();
        candidates.add(candidate(
                "^.*?[Ss]\\d{1,2}[Ee](\\d{2,3})\\s*[-_ ]+\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - \\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^.*?[Ss]\\d{1,2}[Ee](\\d)\\s*[-_ ]+\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - \\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
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
                "^.*?(?:[Ss]\\d{1,2}[ ._-]*)?[Ee][Pp](\\d{2,3})((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^.*?(?:[Ss]\\d{1,2}[ ._-]*)?[Ee][Pp](\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
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
                "^(\\d{2,3})[ _-]+(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - \\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^(\\d)[ _-]+(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - \\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                PURE_NUMBER.pattern(),
                episodePrefix + "\\1\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        candidates.add(candidate(
                "^(?!\\d{1,2}\\.\\d{2}(?:\\D|$))(\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1\\2.\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + matcher.group(2) + "." + matcher.group(3),
                matcher -> matcher.group(1)
        ));
        return candidates;
    }

    private List<RuleCandidate> varietyDateCandidates(String title) {
        return List.of(
                dateCandidate("^(20\\d{2})(\\d{2})(\\d{2})(.*)\\.(" + MEDIA_EXTENSIONS + ")$", title),
                dateCandidate(
                        "^(20\\d{2})[-.](\\d{2})[-.](\\d{2})[ ._-]*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                        title
                ),
                dateCandidate(
                        "^(20\\d{2})[.](\\d{2})(\\d{2})[ ._-]*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                        title
                )
        );
    }

    private RuleCandidate dateCandidate(String pattern, String title) {
        return candidate(
                pattern,
                title + " - \\1-\\2-\\3 - \\4.\\5",
                matcher -> title + " - " + matcher.group(1) + "-" + matcher.group(2) + "-"
                        + matcher.group(3) + " - " + matcher.group(4) + "." + matcher.group(5),
                matcher -> matcher.group(1) + matcher.group(2) + matcher.group(3)
        );
    }

    private RuleCandidate candidate(
            String pattern,
            String replace,
            Function<Matcher, String> targetName,
            Function<Matcher, String> episodeKey
    ) {
        return new RuleCandidate(ruleLabel(pattern), Pattern.compile(pattern), replace, targetName, episodeKey);
    }

    private String ruleLabel(String pattern) {
        if (pattern.contains("20\\d{2}")) {
            return "播出日期";
        }
        if (pattern.contains("[Ss]") || pattern.contains("[Ee]")) {
            return "标准集号";
        }
        if (pattern.contains("[xX]")) {
            return "NxNN集号";
        }
        if (pattern.contains("[集话期]")) {
            return "中文集号";
        }
        if (pattern.contains("[ _-]")) {
            return "数字加标签";
        }
        return "纯数字集号";
    }

    private Map<RuleCandidate, List<QasShareNode>> groupByDisjointRule(
            List<RuleCandidate> candidates,
            List<QasShareNode> files
    ) {
        Map<RuleCandidate, List<QasShareNode>> groups = new LinkedHashMap<>();
        for (QasShareNode file : files) {
            RuleCandidate selected = candidates.stream()
                    .filter(candidate -> candidate.pattern().matcher(file.name()).matches())
                    .findFirst()
                    .orElse(null);
            if (selected == null) {
                return null;
            }
            groups.computeIfAbsent(selected, ignored -> new ArrayList<>()).add(file);
        }
        for (Map.Entry<RuleCandidate, List<QasShareNode>> group : groups.entrySet()) {
            RuleCandidate candidate = group.getKey();
            boolean overlapsOtherGroup = groups.entrySet().stream()
                    .filter(other -> other.getKey() != candidate)
                    .flatMap(other -> other.getValue().stream())
                    .anyMatch(file -> candidate.pattern().matcher(file.name()).matches());
            if (overlapsOtherGroup) {
                return null;
            }
        }
        return groups;
    }

    private boolean isPlayableVideo(String name) {
        return name != null && PLAYABLE_VIDEO.matcher(name).matches();
    }

    private boolean isMediaFile(String name) {
        return name != null && MEDIA_FILE.matcher(name).matches();
    }

    private boolean containsPlayableVideo(List<QasShareNode> nodes) {
        return nodes.stream().anyMatch(node -> node.directory()
                ? containsPlayableVideo(node.children())
                : isPlayableVideo(node.name()));
    }

    private void validateExplicitSeason(List<QasShareNode> nodes, int requestedSeason) {
        for (QasShareNode node : nodes) {
            if (node.directory()) {
                validateExplicitSeason(node.children(), requestedSeason);
                continue;
            }
            Matcher matcher = EXPLICIT_SEASON_EPISODE.matcher(node.name());
            while (matcher.find()) {
                String value = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
                int actualSeason = Integer.parseInt(value);
                if (actualSeason != requestedSeason) {
                    throw new QuarkIngestPlanningException(
                            "文件包含第 " + actualSeason + " 季内容，不能保存到请求的第 "
                                    + requestedSeason + " 季"
                    );
                }
            }
        }
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
            if ("播出日期".equals(candidate.label()) && !isValidDate(matcher)) {
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

    private boolean isValidDate(Matcher matcher) {
        try {
            LocalDate.of(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    Integer.parseInt(matcher.group(3))
            );
            return true;
        } catch (DateTimeException | NumberFormatException exception) {
            return false;
        }
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
                "^.*?[Ss]\\d{1,2}[Ee](\\d{2,3})\\s*[-_ ]+\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - \\2 - " + versionLabel + ".\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + matcher.group(2)
                        + " - " + versionLabel + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^.*?[Ss]\\d{1,2}[Ee](\\d)\\s*[-_ ]+\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - \\2 - " + versionLabel + ".\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + matcher.group(2)
                        + " - " + versionLabel + "." + matcher.group(3)
        ));
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
                "^.*?(?:[Ss]\\d{1,2}[ ._-]*)?[Ee][Pp](\\d{2,3})((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - " + versionLabel + "\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + versionLabel
                        + matcher.group(2) + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^.*?(?:[Ss]\\d{1,2}[ ._-]*)?[Ee][Pp](\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
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
                "^(\\d{2,3})[ _-]+(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "\\1 - " + versionLabel + ".\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + versionLabel + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^(\\d)[ _-]+(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                episodePrefix + "0\\1 - " + versionLabel + ".\\3",
                matcher -> episodePrefix + "0" + matcher.group(1) + " - " + versionLabel + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                PURE_NUMBER.pattern(),
                episodePrefix + "\\1 - " + versionLabel + "\\2.\\3",
                matcher -> episodePrefix + matcher.group(1) + " - " + versionLabel
                        + matcher.group(2) + "." + matcher.group(3)
        ));
        rules.add(versionRule(
                "^(?!\\d{1,2}\\.\\d{2}(?:\\D|$))(\\d)((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
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

    private ContentRoot selectRequestedSeason(ContentRoot contentRoot, int seasonNumber) {
        if (contentRoot.entries().size() < 2
                || contentRoot.entries().stream().anyMatch(node -> !node.directory())) {
            return contentRoot;
        }
        List<SeasonDirectory> allDirectories = contentRoot.entries().stream()
                .map(directory -> new SeasonDirectory(directory, seasonNumberFrom(directory.name())))
                .toList();
        List<SeasonDirectory> seasonDirectories = allDirectories.stream()
                .filter(directory -> directory.seasonNumber() != null)
                .toList();
        if (seasonDirectories.size() < 2
                || allDirectories.stream()
                        .filter(directory -> directory.seasonNumber() == null)
                        .anyMatch(directory -> !isSeasonCollectionAuxiliary(directory.node().name()))) {
            return contentRoot;
        }
        List<QasShareNode> matches = seasonDirectories.stream()
                .filter(directory -> directory.seasonNumber() == seasonNumber)
                .map(SeasonDirectory::node)
                .toList();
        if (matches.size() != 1) {
            throw new QuarkIngestPlanningException(
                    matches.isEmpty()
                            ? "多季合集里找不到第 " + seasonNumber + " 季"
                            : "多季合集里存在多个第 " + seasonNumber + " 季目录"
            );
        }
        QasShareNode selected = matches.get(0);
        return new ContentRoot(
                withDirectoryFid(contentRoot.sourceUrl(), selected.fid()),
                selected.children()
        );
    }

    private Integer seasonNumberFrom(String name) {
        if (name == null) {
            return null;
        }
        Matcher matcher = SEASON_DIRECTORY.matcher(name);
        if (matcher.find()) {
            String number = matcher.group(1) != null ? matcher.group(1) : matcher.group(2);
            return Integer.parseInt(number);
        }
        Matcher chineseMatcher = CHINESE_SEASON_DIRECTORY.matcher(name);
        return chineseMatcher.find() ? chineseNumber(chineseMatcher.group(1)) : null;
    }

    private Integer chineseNumber(String value) {
        String digits = "一二三四五六七八九";
        if ("十".equals(value)) {
            return 10;
        }
        int tenIndex = value.indexOf('十');
        if (tenIndex >= 0) {
            int tens = tenIndex == 0 ? 1 : digits.indexOf(value.charAt(0)) + 1;
            int ones = tenIndex == value.length() - 1 ? 0 : digits.indexOf(value.charAt(tenIndex + 1)) + 1;
            return tens > 0 && ones >= 0 ? tens * 10 + ones : null;
        }
        int digit = value.length() == 1 ? digits.indexOf(value.charAt(0)) + 1 : 0;
        return digit > 0 ? digit : null;
    }

    private boolean isSeasonCollectionAuxiliary(String name) {
        String normalized = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        return NON_VERSION_DIRECTORIES.stream().anyMatch(normalized::contains)
                || normalized.contains("特别")
                || normalized.contains("special")
                || normalized.contains("圣诞")
                || normalized.contains("彩蛋");
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
            String label,
            Pattern pattern,
            String replace,
            Function<Matcher, String> targetName,
            Function<Matcher, String> episodeKey
    ) {
    }

    private record SeasonDirectory(QasShareNode node, Integer seasonNumber) {
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
