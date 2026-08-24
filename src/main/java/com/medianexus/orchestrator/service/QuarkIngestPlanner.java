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

    private static final int MAX_RENAME_SAMPLES = 20;
    private static final Pattern PURE_NUMBER = Pattern.compile(
            "^(?!\\d{1,2}\\.\\d{2}(?:\\D|$))(\\d{2,3})((?:\\.[^.]+)*)\\.(mkv|mp4|avi|mov|wmv|flv|ts|m2ts|webm|rmvb|srt|ass|ssa|vtt|sub)$"
    );
    private static final Pattern DATE_ONLY = Pattern.compile(
            "^(20\\d{2})(\\d{2})(\\d{2})\\.(mkv|mp4)$"
    );
    private static final String VIDEO_EXTENSIONS = "mkv|mp4|avi|mov|wmv|flv|ts|m2ts|webm|rmvb";
    private static final String SUBTITLE_EXTENSIONS = "srt|ass|ssa|vtt|sub";
    private static final String MEDIA_EXTENSIONS = VIDEO_EXTENSIONS + "|" + SUBTITLE_EXTENSIONS;
    private static final Pattern MONTH_DAY_MEDIA = Pattern.compile(
            "^(\\d{2})[.-](\\d{2})(.*)\\.(" + MEDIA_EXTENSIONS + ")$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern VARIETY_ISSUE = Pattern.compile(
            "第\\s*(\\d{1,3}|[一二三四五六七八九十]{1,3})\\s*期"
    );
    private static final Pattern VARIETY_EXTRA = Pattern.compile(
            "加更|纯享|番外|花絮|采访|预告|先导|彩蛋|未播|会员版|衍生"
    );
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
            Map<LocalDate, List<Integer>> airDateEpisodes
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
            Map<LocalDate, List<Integer>> airDateEpisodes
    ) {
        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        List<RuleCandidate> candidates = new ArrayList<>(ordinaryEpisodeCandidates(title, season));
        candidates.addAll(varietyDateCandidates(title));
        ContentRoot selectedContent = unwrapSingleDirectory(shareTree.sourceUrl(), shareTree.entries());
        selectedContent = selectRequestedSeason(selectedContent, seasonNumber);
        selectedContent = unwrapSingleDirectory(selectedContent.sourceUrl(), selectedContent.entries());
        Set<Integer> shortDateYears = resolveShortDateYears(selectedContent.entries(), airDateEpisodes);
        if (!shortDateYears.isEmpty()) {
            if (shortDateYears.size() > 1) {
                throw new QuarkIngestPlanningException("短日期文件跨越多个年份，无法使用单一 QAS 规则安全命名");
            }
            if (isFlatMonthDayMediaSet(selectedContent.entries())) {
                return planMappedMonthDayVariety(
                        title,
                        seasonNumber,
                        savePath,
                        selectedContent,
                        airDateEpisodes,
                        shortDateYears.iterator().next()
                );
            }
            candidates.addAll(varietyShortDateCandidates(title, shortDateYears.iterator().next()));
        } else if (containsMonthDayMedia(selectedContent.entries())) {
            throw new QuarkIngestPlanningException(
                    QuarkIngestPlanningException.Reason.DATE_MAPPING_REQUIRED,
                    "短日期综艺文件需要 TMDB Season Details 推断播出年份"
            );
        }
        return planSeasonMedia(title, seasonNumber, savePath, shareTree, airDateEpisodes, candidates);
    }

    private QasIngestPlan planMappedMonthDayVariety(
            String title,
            int seasonNumber,
            String savePath,
            ContentRoot contentRoot,
            Map<LocalDate, List<Integer>> airDateEpisodes,
            int year
    ) {
        List<QasShareNode> mediaFiles = contentRoot.entries().stream()
                .filter(file -> isMediaFile(file.name()))
                .toList();
        List<QasShareNode> videos = mediaFiles.stream()
                .filter(file -> isPlayableVideo(file.name()))
                .toList();
        if (videos.isEmpty()) {
            throw new QuarkIngestPlanningException("Quark 分享中没有可播放视频");
        }

        List<DatedVarietyVideo> datedVideos = new ArrayList<>();
        Set<QasShareNode> assignedMedia = new HashSet<>();
        for (QasShareNode video : videos) {
            Matcher matcher = MONTH_DAY_MEDIA.matcher(video.name());
            if (!matcher.matches()) {
                throw new QuarkIngestPlanningException("日期型综艺中存在无法识别的视频：" + video.name());
            }
            LocalDate date;
            try {
                date = LocalDate.of(
                        year,
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))
                );
            } catch (DateTimeException exception) {
                throw new QuarkIngestPlanningException("短日期文件包含无效日期：" + video.name());
            }
            String sourceStem = stemOf(video.name());
            List<QasShareNode> relatedFiles = mediaFiles.stream()
                    .filter(file -> belongsToVideo(file, video, sourceStem))
                    .toList();
            if (!assignedMedia.addAll(relatedFiles)) {
                throw new QuarkIngestPlanningException("字幕无法唯一关联到视频：" + video.name());
            }
            datedVideos.add(new DatedVarietyVideo(
                    video,
                    relatedFiles,
                    date,
                    matcher.group(3).trim(),
                    sourceStem
            ));
        }
        if (assignedMedia.size() != mediaFiles.size()) {
            throw new QuarkIngestPlanningException("日期型综艺存在无法关联到视频的字幕");
        }

        List<LocalDate> scheduleDates = airDateEpisodes.keySet().stream().sorted().toList();
        Map<LocalDate, List<DatedVarietyVideo>> mainByScheduleDate = new LinkedHashMap<>();
        List<DatedVarietyVideo> specials = new ArrayList<>();
        for (DatedVarietyVideo video : datedVideos) {
            LocalDate scheduleDate = mappedScheduleDate(video, scheduleDates, airDateEpisodes);
            if (scheduleDate == null) {
                specials.add(video);
            } else {
                mainByScheduleDate.computeIfAbsent(scheduleDate, ignored -> new ArrayList<>()).add(video);
            }
        }

        String season = String.format(Locale.ROOT, "%02d", seasonNumber);
        List<QasTaskPlan> tasks = new ArrayList<>();
        for (Map.Entry<LocalDate, List<DatedVarietyVideo>> entry : mainByScheduleDate.entrySet()) {
            List<Integer> episodes = airDateEpisodes.getOrDefault(entry.getKey(), List.of()).stream()
                    .distinct()
                    .sorted()
                    .toList();
            List<DatedVarietyVideo> files = entry.getValue().stream()
                    .sorted((left, right) -> compareDatedParts(
                            left.video().name(), right.video().name()
                    ))
                    .toList();
            if (episodes.isEmpty() || (files.size() > 1 && files.size() != episodes.size())) {
                throw new QuarkIngestPlanningException(
                        "播出日期 " + entry.getKey() + " 的视频数量无法与 TMDB 集号安全对应"
                );
            }
            if (files.size() == 1) {
                String episodeCode = episodeRange(episodes);
                tasks.add(exactDatedTask(
                        title,
                        "S" + season + "E" + episodeCode,
                        savePath,
                        contentRoot.sourceUrl(),
                        files.get(0),
                        "TMDB 正集 " + episodeCode
                ));
                continue;
            }
            for (int index = 0; index < files.size(); index++) {
                String episodeCode = String.format(Locale.ROOT, "%02d", episodes.get(index));
                tasks.add(exactDatedTask(
                        title,
                        "S" + season + "E" + episodeCode,
                        savePath,
                        contentRoot.sourceUrl(),
                        files.get(index),
                        "TMDB 正集 " + episodeCode
                ));
            }
        }

        String specialsPath = seasonPath(savePath, 0);
        List<DatedVarietyVideo> orderedSpecials = specials.stream()
                .sorted((left, right) -> {
                    int byDate = left.date().compareTo(right.date());
                    return byDate != 0
                            ? byDate
                            : left.video().name().compareToIgnoreCase(right.video().name());
                })
                .toList();
        for (int index = 0; index < orderedSpecials.size(); index++) {
            String episodeCode = String.format(Locale.ROOT, "%02d", index + 1);
            tasks.add(exactDatedTask(
                    title,
                    "S00E" + episodeCode,
                    specialsPath,
                    contentRoot.sourceUrl(),
                    orderedSpecials.get(index),
                    "特别篇 " + episodeCode
            ));
        }

        List<String> warnings = new ArrayList<>();
        if (!orderedSpecials.isEmpty()) {
            warnings.add("检测到 " + orderedSpecials.size()
                    + " 个加更或非正集内容，已按时间顺序保存到 Season 00 特别篇");
        }
        warnings.add("日期型综艺已按当前分享生成精确 S/E 规则；分享新增文件后需重新提交以更新追更规则");
        return new QasIngestPlan(tasks, warnings);
    }

    private boolean isFlatMonthDayMediaSet(List<QasShareNode> entries) {
        List<QasShareNode> mediaFiles = entries.stream().filter(file -> isMediaFile(file.name())).toList();
        return !mediaFiles.isEmpty()
                && entries.stream().noneMatch(QasShareNode::directory)
                && mediaFiles.stream().allMatch(file -> MONTH_DAY_MEDIA.matcher(file.name()).matches());
    }

    private boolean belongsToVideo(QasShareNode file, QasShareNode video, String videoStem) {
        if (file == video) {
            return true;
        }
        if (!isSubtitle(file.name())) {
            return false;
        }
        String subtitleStem = stemOf(file.name());
        return subtitleStem.equalsIgnoreCase(videoStem)
                || subtitleStem.toLowerCase(Locale.ROOT).startsWith(videoStem.toLowerCase(Locale.ROOT) + ".");
    }

    private LocalDate mappedScheduleDate(
            DatedVarietyVideo video,
            List<LocalDate> scheduleDates,
            Map<LocalDate, List<Integer>> airDateEpisodes
    ) {
        if (VARIETY_EXTRA.matcher(video.descriptor()).find()) {
            return null;
        }
        Matcher issueMatcher = VARIETY_ISSUE.matcher(video.descriptor());
        if (issueMatcher.find()) {
            Integer issue = parseVarietyIssue(issueMatcher.group(1));
            if (issue == null || issue <= 0 || issue > scheduleDates.size()) {
                throw new QuarkIngestPlanningException("无法把期数映射到 TMDB 播出日：" + video.video().name());
            }
            return scheduleDates.get(issue - 1);
        }
        return airDateEpisodes.containsKey(video.date()) ? video.date() : null;
    }

    private Integer parseVarietyIssue(String value) {
        if (value.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(value);
        }
        return chineseNumber(value);
    }

    private String episodeRange(List<Integer> episodes) {
        String first = String.format(Locale.ROOT, "%02d", episodes.get(0));
        if (episodes.size() == 1) {
            return first;
        }
        if (episodes.size() == 2 && episodes.get(1) == episodes.get(0) + 1) {
            return first + "-E" + String.format(Locale.ROOT, "%02d", episodes.get(1));
        }
        throw new QuarkIngestPlanningException("单个视频对应的 TMDB 集号不是连续范围");
    }

    private QasTaskPlan exactDatedTask(
            String title,
            String episodeIdentity,
            String savePath,
            String sourceUrl,
            DatedVarietyVideo video,
            String ruleLabel
    ) {
        String descriptor = video.descriptor().isBlank()
                ? video.date().toString()
                : video.descriptor();
        String targetBase = title + " - " + episodeIdentity + " - " + descriptor;
        String pattern = "^" + regexEscape(video.sourceStem())
                + "((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$";
        List<QasRenameSample> samples = video.relatedFiles().stream()
                .map(file -> {
                    Matcher matcher = Pattern.compile(pattern).matcher(file.name());
                    matcher.matches();
                    return new QasRenameSample(
                            file.name(),
                            targetBase + matcher.group(1) + "." + matcher.group(2)
                    );
                })
                .toList();
        return new QasTaskPlan(
                title + " " + episodeIdentity + " [精确命名]",
                sourceUrl,
                savePath,
                pattern,
                targetBase + "\\1.\\2",
                null,
                ruleLabel,
                video.relatedFiles().size(),
                samples
        );
    }

    private String seasonPath(String savePath, int seasonNumber) {
        if (savePath == null || !savePath.matches(".*/Season \\d{2}$")) {
            throw new QuarkIngestPlanningException("综艺保存目录不是标准 Season 目录");
        }
        return savePath.replaceFirst("/Season \\d{2}$", String.format(Locale.ROOT, "/Season %02d", seasonNumber));
    }

    private QasIngestPlan planSeasonMedia(
            String title,
            int seasonNumber,
            String savePath,
            QasShareTree shareTree,
            Map<LocalDate, List<Integer>> airDateEpisodes,
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
            return planOrdinary(
                    title, seasonNumber, savePath, contentRoot, ordinaryCandidates, airDateEpisodes
            );
        }
        throw new QuarkIngestPlanningException("分享中包含无法安全展平的复杂目录");
    }

    private QasIngestPlan planOrdinary(
            String title,
            int seasonNumber,
            String savePath,
            ContentRoot contentRoot,
            List<RuleCandidate> candidates,
            Map<LocalDate, List<Integer>> airDateEpisodes
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
        DateCollisionPlan dateCollisions = planDateCollisions(
                title, season, candidates, mediaFiles, airDateEpisodes
        );
        candidates = dateCollisions.candidates();
        for (RuleCandidate candidate : candidates) {
            if (isSafeForAllFiles(candidate, mediaFiles)) {
                return new QasIngestPlan(
                        List.of(renameTaskPlan(
                                taskName, contentRoot.sourceUrl(), savePath, candidate, mediaFiles, null
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
            Set<String> logicalVideos = new HashSet<>();
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
                    if (!isSubtitle(file.name())
                            && !logicalVideos.add(candidate.logicalKey().apply(matcher))) {
                        groups = null;
                        break;
                    }
                }
                if (groups == null) {
                    break;
                }
                int labelIndex = labelCounts.merge(candidate.label(), 1, Integer::sum);
                String taskLabel = labelIndex == 1
                        ? candidate.label()
                        : candidate.label() + "-" + labelIndex;
                tasks.add(renameTaskPlan(
                        taskName + " [" + taskLabel + "]",
                        contentRoot.sourceUrl(), savePath, candidate, group.getValue(), null
                ));
            }
            if (groups != null) {
                List<String> warnings = new ArrayList<>(dateCollisions.warnings());
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

    private QasTaskPlan renameTaskPlan(
            String taskName,
            String sourceUrl,
            String savePath,
            RuleCandidate candidate,
            List<QasShareNode> files,
            String versionLabel
    ) {
        List<QasRenameSample> samples = files.stream()
                .limit(MAX_RENAME_SAMPLES)
                .map(file -> {
                    Matcher matcher = candidate.pattern().matcher(file.name());
                    matcher.matches();
                    return new QasRenameSample(file.name(), candidate.targetName().apply(matcher));
                })
                .toList();
        return new QasTaskPlan(
                taskName,
                sourceUrl,
                savePath,
                candidate.pattern().pattern(),
                candidate.replace(),
                versionLabel,
                candidate.label(),
                files.size(),
                samples
        );
    }

    private List<RuleCandidate> varietyDateCandidates(String title) {
        return List.of(
                plainDateCandidate(
                        "^(20\\d{2})(\\d{2})(\\d{2})((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                        title
                ),
                dateCandidate("^(20\\d{2})(\\d{2})(\\d{2})(.+?)\\.(" + MEDIA_EXTENSIONS + ")$", title),
                plainDateCandidate(
                        "^(20\\d{2})[-.](\\d{2})[-.](\\d{2})((?:\\.[^.]+)*)\\.("
                                + MEDIA_EXTENSIONS + ")$",
                        title
                ),
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

    private List<RuleCandidate> varietyShortDateCandidates(String title, int year) {
        return List.of(
                plainShortDateCandidate(
                        "^(\\d{2})[.-](\\d{2})((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$",
                        title,
                        year
                ),
                shortDateCandidate(
                        "^(\\d{2})[.-](\\d{2})\\s*(.+?)\\.(" + MEDIA_EXTENSIONS + ")$",
                        title,
                        year
                )
        );
    }

    private RuleCandidate dateCandidate(String pattern, String title) {
        return dateCandidate(
                "播出日期",
                pattern,
                title + " - \\1-\\2-\\3 - \\4.\\5",
                matcher -> title + " - " + matcher.group(1) + "-" + matcher.group(2) + "-"
                        + matcher.group(3) + " - " + matcher.group(4) + "." + matcher.group(5),
                matcher -> LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                )
        );
    }

    private RuleCandidate plainDateCandidate(String pattern, String title) {
        return dateCandidate(
                "播出日期",
                pattern,
                title + " - \\1-\\2-\\3\\4.\\5",
                matcher -> title + " - " + matcher.group(1) + "-" + matcher.group(2) + "-"
                        + matcher.group(3) + matcher.group(4) + "." + matcher.group(5),
                matcher -> LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3))
                )
        );
    }

    private RuleCandidate shortDateCandidate(String pattern, String title, int year) {
        return dateCandidate(
                "播出日期（短日期）",
                pattern,
                title + " - " + year + "-\\1-\\2 - \\3.\\4",
                matcher -> title + " - " + year + "-" + matcher.group(1) + "-"
                        + matcher.group(2) + " - " + matcher.group(3).trim() + "." + matcher.group(4),
                matcher -> LocalDate.of(
                        year,
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))
                )
        );
    }

    private RuleCandidate plainShortDateCandidate(String pattern, String title, int year) {
        return dateCandidate(
                "播出日期（短日期）",
                pattern,
                title + " - " + year + "-\\1-\\2\\3.\\4",
                matcher -> title + " - " + year + "-" + matcher.group(1) + "-"
                        + matcher.group(2) + matcher.group(3) + "." + matcher.group(4),
                matcher -> LocalDate.of(
                        year,
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2))
                )
        );
    }

    private RuleCandidate dateCandidate(
            String label,
            String pattern,
            String replace,
            Function<Matcher, String> targetName,
            Function<Matcher, LocalDate> sourceDate
    ) {
        return new RuleCandidate(
                label,
                Pattern.compile(pattern),
                replace,
                targetName,
                matcher -> "DATE:" + sourceDate.apply(matcher),
                sourceDate
        );
    }

    private RuleCandidate candidate(
            String pattern,
            String replace,
            Function<Matcher, String> targetName,
            Function<Matcher, String> episodeKey
    ) {
        return new RuleCandidate(
                ruleLabel(pattern),
                Pattern.compile(pattern),
                replace,
                targetName,
                matcher -> "EPISODE:" + normalizeEpisodeKey(episodeKey.apply(matcher)),
                null
        );
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

    private DateCollisionPlan planDateCollisions(
            String title,
            String season,
            List<RuleCandidate> candidates,
            List<QasShareNode> mediaFiles,
            Map<LocalDate, List<Integer>> airDateEpisodes
    ) {
        Map<LocalDate, List<DatedFile>> videosByDate = new LinkedHashMap<>();
        for (QasShareNode file : mediaFiles) {
            if (isSubtitle(file.name())) {
                continue;
            }
            DatedFile datedFile = datedFile(candidates, file);
            if (datedFile != null) {
                videosByDate.computeIfAbsent(datedFile.date(), ignored -> new ArrayList<>()).add(datedFile);
            }
        }
        List<Map.Entry<LocalDate, List<DatedFile>>> collisions = videosByDate.entrySet().stream()
                .filter(entry -> entry.getValue().size() > 1)
                .toList();
        if (collisions.isEmpty()) {
            return new DateCollisionPlan(candidates, List.of());
        }

        List<RuleCandidate> exactCandidates = new ArrayList<>();
        Set<String> exactStems = new HashSet<>();
        Set<String> exactStemKeys = new HashSet<>();
        for (Map.Entry<LocalDate, List<DatedFile>> collision : collisions) {
            List<Integer> episodes = airDateEpisodes == null
                    ? List.of()
                    : airDateEpisodes.getOrDefault(collision.getKey(), List.of());
            List<DatedFile> orderedFiles = collision.getValue().stream()
                    .sorted((left, right) -> compareDatedParts(left.file().name(), right.file().name()))
                    .toList();
            List<Integer> orderedEpisodes = episodes.stream().sorted().toList();
            if (orderedEpisodes.size() != orderedFiles.size()) {
                return new DateCollisionPlan(candidates, List.of());
            }
            for (int index = 0; index < orderedFiles.size(); index++) {
                DatedFile datedFile = orderedFiles.get(index);
                String stem = stemOf(datedFile.file().name());
                if (!exactStemKeys.add(stem.toLowerCase(Locale.ROOT))) {
                    return new DateCollisionPlan(candidates, List.of());
                }
                exactStems.add(stem);
                exactCandidates.add(exactEpisodeCandidate(
                        title,
                        season,
                        orderedEpisodes.get(index),
                        stem,
                        dateDescriptor(stem, datedFile.date())
                ));
            }
        }

        List<RuleCandidate> planned = new ArrayList<>(exactCandidates);
        for (RuleCandidate candidate : candidates) {
            planned.add(candidate.sourceDate() == null
                    ? candidate
                    : excludingStems(candidate, exactStems));
        }
        return new DateCollisionPlan(
                planned,
                List.of("同一播出日期包含多个正集，已按 TMDB 集号拆分精确命名规则")
        );
    }

    private DatedFile datedFile(List<RuleCandidate> candidates, QasShareNode file) {
        for (RuleCandidate candidate : candidates) {
            if (candidate.sourceDate() == null) {
                continue;
            }
            Matcher matcher = candidate.pattern().matcher(file.name());
            if (matcher.matches() && isValidDate(candidate, matcher)) {
                return new DatedFile(file, candidate.sourceDate().apply(matcher));
            }
        }
        return null;
    }

    private RuleCandidate exactEpisodeCandidate(
            String title,
            String season,
            int episode,
            String sourceStem,
            String descriptor
    ) {
        String episodeCode = String.format(Locale.ROOT, "%02d", episode);
        String targetBase = title + " - S" + season + "E" + episodeCode
                + (descriptor.isBlank() ? "" : " - " + descriptor);
        String pattern = "^" + regexEscape(sourceStem)
                + "((?:\\.[^.]+)*)\\.(" + MEDIA_EXTENSIONS + ")$";
        return new RuleCandidate(
                "TMDB 集号 E" + episodeCode,
                Pattern.compile(pattern),
                targetBase + "\\1.\\2",
                matcher -> targetBase + matcher.group(1) + "." + matcher.group(2),
                matcher -> "EPISODE:" + episodeCode,
                null
        );
    }

    private RuleCandidate excludingStems(RuleCandidate candidate, Set<String> exactStems) {
        String exclusions = exactStems.stream()
                .map(this::regexEscape)
                .sorted()
                .collect(java.util.stream.Collectors.joining("|"));
        String original = candidate.pattern().pattern();
        String pattern = "^(?!(?:" + exclusions + ")(?:(?:\\.[^.]+)*)\\.(?:"
                + MEDIA_EXTENSIONS + ")$)" + original.substring(1);
        return new RuleCandidate(
                candidate.label(),
                Pattern.compile(pattern),
                candidate.replace(),
                candidate.targetName(),
                candidate.logicalKey(),
                candidate.sourceDate()
        );
    }

    private int compareDatedParts(String left, String right) {
        int leftRank = partRank(left);
        int rightRank = partRank(right);
        if (leftRank != rightRank) {
            return Integer.compare(leftRank, rightRank);
        }
        return left.compareToIgnoreCase(right);
    }

    private int partRank(String name) {
        if (name.contains("上")) {
            return 0;
        }
        if (name.contains("中")) {
            return 1;
        }
        if (name.contains("下")) {
            return 2;
        }
        return 3;
    }

    private String dateDescriptor(String stem, LocalDate date) {
        String value = stem.replaceFirst(
                "^(?:" + date.getYear() + "[.-]?)?"
                        + String.format(Locale.ROOT, "%02d[.-]%02d", date.getMonthValue(), date.getDayOfMonth())
                        + "[ ._-]*",
                ""
        );
        return value.trim();
    }

    private String stemOf(String name) {
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    private String regexEscape(String value) {
        return value.replaceAll("([\\\\.\\[\\]{}()*+?^$|])", "\\\\$1");
    }

    private String normalizeEpisodeKey(String value) {
        try {
            return Integer.toString(Integer.parseInt(value));
        } catch (NumberFormatException exception) {
            return value;
        }
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

    private boolean containsMonthDayMedia(List<QasShareNode> nodes) {
        return nodes.stream().anyMatch(node -> node.directory()
                ? containsMonthDayMedia(node.children())
                : node.name() != null && MONTH_DAY_MEDIA.matcher(node.name()).matches());
    }

    private Set<Integer> resolveShortDateYears(
            List<QasShareNode> nodes,
            Map<LocalDate, List<Integer>> airDateEpisodes
    ) {
        if (airDateEpisodes == null || airDateEpisodes.isEmpty()) {
            return Set.of();
        }
        Set<Integer> years = new HashSet<>();
        collectShortDateYears(nodes, airDateEpisodes.keySet(), years);
        return years;
    }

    private void collectShortDateYears(
            List<QasShareNode> nodes,
            Set<LocalDate> seasonDates,
            Set<Integer> years
    ) {
        for (QasShareNode node : nodes) {
            if (node.directory()) {
                collectShortDateYears(node.children(), seasonDates, years);
                continue;
            }
            Matcher matcher = node.name() == null ? null : MONTH_DAY_MEDIA.matcher(node.name());
            if (matcher == null || !matcher.matches()) {
                continue;
            }
            years.add(resolveShortDateYear(
                    Integer.parseInt(matcher.group(1)),
                    Integer.parseInt(matcher.group(2)),
                    seasonDates
            ));
        }
    }

    private int resolveShortDateYear(int month, int day, Set<LocalDate> seasonDates) {
        Set<Integer> candidateYears = new HashSet<>();
        for (LocalDate seasonDate : seasonDates) {
            candidateYears.add(seasonDate.getYear() - 1);
            candidateYears.add(seasonDate.getYear());
            candidateYears.add(seasonDate.getYear() + 1);
        }
        LocalDate best = null;
        long bestDistance = Long.MAX_VALUE;
        boolean ambiguous = false;
        for (int year : candidateYears) {
            LocalDate candidate;
            try {
                candidate = LocalDate.of(year, month, day);
            } catch (DateTimeException exception) {
                throw new QuarkIngestPlanningException("短日期文件包含无效日期：" + month + "." + day);
            }
            long distance = seasonDates.stream()
                    .mapToLong(seasonDate -> Math.abs(java.time.temporal.ChronoUnit.DAYS.between(
                            seasonDate, candidate
                    )))
                    .min()
                    .orElse(Long.MAX_VALUE);
            if (distance < bestDistance) {
                best = candidate;
                bestDistance = distance;
                ambiguous = false;
            } else if (distance == bestDistance && best != null && candidate.getYear() != best.getYear()) {
                ambiguous = true;
            }
        }
        if (best == null || bestDistance > 183 || ambiguous) {
            throw new QuarkIngestPlanningException("无法从 TMDB 季度日期安全推断 " + month + "." + day + " 的年份");
        }
        return best.getYear();
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
            if (candidate.sourceDate() != null && !isValidDate(candidate, matcher)) {
                return false;
            }
            String targetName = candidate.targetName().apply(matcher).toLowerCase(Locale.ROOT);
            if (!targetNames.add(targetName)) {
                return false;
            }
            String key = candidate.logicalKey().apply(matcher);
            if (isSubtitle(file.name())) {
                subtitleKeys.add(key);
            } else if (!videoKeys.add(key)) {
                return false;
            }
        }
        return !videoKeys.isEmpty() && videoKeys.containsAll(subtitleKeys);
    }

    private boolean isValidDate(RuleCandidate candidate, Matcher matcher) {
        try {
            candidate.sourceDate().apply(matcher);
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
            Map<LocalDate, List<Integer>> airDateEpisodes
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
            String renameRule;
            int matchedFileCount;
            List<QasRenameSample> renameSamples;
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
                renameRule = ruleLabel(pattern) + "（多版本）";
                matchedFileCount = directory.children().size();
                renameSamples = analysis.renameSamples().stream().limit(MAX_RENAME_SAMPLES).toList();
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
                            List<Integer> mappedEpisodes = airDateEpisodes.getOrDefault(date, List.of());
                            if (mappedEpisodes.size() != 1) {
                                throw new QuarkIngestPlanningException("TMDB 中找不到播出日期 " + date + " 对应的集号");
                            }
                            orderedEpisodes.add(mappedEpisodes.get(0));
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
                renameRule = "播出日期（多版本）";
                matchedFileCount = orderedFiles.size();
                renameSamples = new ArrayList<>();
                for (int index = 0; index < Math.min(orderedFiles.size(), MAX_RENAME_SAMPLES); index++) {
                    String targetName = title + " - S" + season
                            + "E" + String.format(Locale.ROOT, "%02d", orderedEpisodes.get(index))
                            + " - " + versionLabel + "." + extensionOf(orderedFiles.get(index).name());
                    renameSamples.add(new QasRenameSample(orderedFiles.get(index).name(), targetName));
                }
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
                    versionLabel,
                    renameRule,
                    matchedFileCount,
                    renameSamples
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
        List<QasRenameSample> renameSamples = new ArrayList<>();
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
            renameSamples.add(new QasRenameSample(file.name(), target));
        }
        if (videoEpisodes.isEmpty() || !videoEpisodes.containsAll(subtitleEpisodes)) {
            throw new QuarkIngestPlanningException("版本目录存在没有对应视频的字幕");
        }
        return new VersionAnalysis(videoEpisodes, targets, renameSamples);
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
            Function<Matcher, String> logicalKey,
            Function<Matcher, LocalDate> sourceDate
    ) {
    }

    private record DatedFile(QasShareNode file, LocalDate date) {
    }

    private record DatedVarietyVideo(
            QasShareNode video,
            List<QasShareNode> relatedFiles,
            LocalDate date,
            String descriptor,
            String sourceStem
    ) {
    }

    private record DateCollisionPlan(List<RuleCandidate> candidates, List<String> warnings) {
    }

    private record SeasonDirectory(QasShareNode node, Integer seasonNumber) {
    }

    private record VersionRule(
            Pattern pattern,
            String replace,
            Function<Matcher, String> targetName
    ) {
    }

    private record VersionAnalysis(
            Set<Integer> episodes,
            List<String> targetNames,
            List<QasRenameSample> renameSamples
    ) {
    }
}
