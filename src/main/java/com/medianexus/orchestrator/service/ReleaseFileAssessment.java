package com.medianexus.orchestrator.service;

import com.medianexus.orchestrator.dto.resources.response.ReleaseFileEvidence;
import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** 文件组决定内容与执行能力；与落地共用集号/非正片契约，不凭标题体积或 TMDB 精确齐集硬阻塞。 */
@Component
public class ReleaseFileAssessment {
    private static final Pattern AUXILIARY_DIRECTORY = Pattern.compile("(?i)(?:^|/)(?:extras?|specials?|sps?|bonus|ncop|nced|ost|特典|花絮)(?:/|$)");
    private final MovieSeriesFileRenameService rename;
    private final ReleaseTitleTagParser tags;

    public ReleaseFileAssessment(MovieSeriesFileRenameService rename, ReleaseTitleTagParser tags) {
        this.rename = rename;
        this.tags = tags;
    }

    public Assessment assess(ReleaseTarget target, List<String> sourceTitles, TorrentFileTable table) {
        for (String title : sourceTitles) {
            String conflict = target.conflict(title);
            if (conflict != null) return assessment("UNSUITABLE", conflict + "；保留来源冲突证据", table, null, null, false, false, List.of());
            List<String> declared = tags.explicitSeasonTags(title);
            if (target.season() != null && !declared.isEmpty() && !declared.contains("S%02d".formatted(target.season())))
                return assessment("UNSUITABLE", "发布季编号与目标季冲突", table, null, null, false, false, List.of());
        }
        String rootConflict = target.conflict(table.rootName());
        if (rootConflict != null) return assessment("UNSUITABLE", rootConflict, table, null, null, false, false, List.of());
        if (target.season() == null && (sourceTitles.stream().anyMatch(title -> !tags.explicitSeasonTags(title).isEmpty())
                || !tags.explicitSeasonTags(table.rootName()).isEmpty()
                || table.files().stream().anyMatch(file -> !tags.explicitSeasonTags(file.path()).isEmpty()))) {
            return assessment("UNSUITABLE", "来源或文件表明确为季度内容，与目标电影身份冲突", table, null, null, false, false, List.of());
        }
        boolean identity = target.matches(table.rootName());
        Set<Integer> rootSeasons = seasons(table.rootName());
        List<TorrentFileTable.Entry> main = new ArrayList<>();
        Set<Integer> episodes = new HashSet<>();
        Set<String> resolutions = new LinkedHashSet<>();
        boolean relativeNumbering = false;
        boolean unknown = false;
        boolean otherSeason = false;
        boolean duplicate = false;
        boolean preview = false;
        boolean camera = false;
        boolean unallowed = false;
        boolean missingQuality = false;
        for (var file : table.files()) {
            String name = file.path().substring(file.path().lastIndexOf('/') + 1);
            if (!rename.isVideo(name) || file.sizeBytes() == 0) continue;
            if (rename.isPromotionalVideo(name, file.sizeBytes())) continue;
            if (target.season() == null && !rename.isMovieFeatureVideo(name, file.sizeBytes())) continue;
            String relative = file.path().startsWith(table.rootName() + "/") ? file.path().substring(table.rootName().length() + 1) : file.path();
            if (isAuxiliaryVideo(relative)) continue;
            if (target.conflict(name) != null) return assessment("UNSUITABLE", "包内正片存在明确作品/年份冲突", table, null, null, false, false, List.of());
            identity |= target.matches(name);
            Set<Integer> localSeasons = seasons(relative);
            Set<Integer> fileSeasons = localSeasons.isEmpty() ? rootSeasons : localSeasons;
            if (target.season() != null) {
                if (!fileSeasons.isEmpty() && !fileSeasons.contains(target.season())) { otherSeason = true; continue; }
                if (fileSeasons.size() > 1 || fileSeasons.isEmpty() && target.season() != 1) { unknown = true; continue; }
                var planned = rename.seriesVideo(name, target.titles().get(0), target.season(),
                        target.titles().size() > 1 ? target.titles().get(1) : target.titles().get(0));
                if (planned.isEmpty()) { unknown = true; continue; }
                if (!episodes.add(planned.get().episodeNumber())) duplicate = true;
                relativeNumbering |= planned.get().episodeNumber() == 1
                        || Pattern.compile("(?i)(?:s\\d{1,2}e|\\d{1,2}x)\\d").matcher(name).find();
            }
            main.add(file);
            preview |= ReleaseVersion.isPreview(name);
            camera |= ReleaseVersion.isCamera(name);
            List<String> quality = tags.parse(name).resolutionTags();
            if (quality.isEmpty()) quality = tags.parse(table.rootName()).resolutionTags();
            if (quality.isEmpty()) quality = sourceTitles.stream().flatMap(title -> tags.parse(title).resolutionTags().stream()).distinct().toList();
            if (quality.isEmpty()) missingQuality = true;
            else if (quality.stream().noneMatch(target.qualities()::contains)) unallowed = true;
            resolutions.addAll(quality);
        }
        if (!identity) return assessment("UNVERIFIED", "文件表尚不能确认作品身份", table, null, null, false, false, List.of());
        if (target.season() != null && target.season() > 1 && !main.isEmpty() && !relativeNumbering) unknown = true;
        preview |= sourceTitles.stream().anyMatch(ReleaseVersion::isPreview) || ReleaseVersion.isPreview(table.rootName());
        camera |= sourceTitles.stream().anyMatch(ReleaseVersion::isCamera) || ReleaseVersion.isCamera(table.rootName());
        if (camera) return assessment("UNSUITABLE", "目标正片存在明确枪版声明", table, null, null, preview, true, List.copyOf(resolutions));
        if (main.isEmpty()) return assessment(unknown ? "NEEDS_PROCESSING" : "UNSUITABLE",
                unknown ? "包含作品内容，但编号/季度尚需映射，当前整理不能执行" : "文件表未发现本次目标正片", table, null, null, preview, false, List.copyOf(resolutions));
        long bytes = main.stream().mapToLong(TorrentFileTable.Entry::sizeBytes).sum();
        if (unallowed) return assessment("UNSUITABLE", "目标文件声明画质不在既有允许范围内", table, bytes, main.size(), preview, false, List.copyOf(resolutions));
        if (unknown || duplicate || otherSeason || target.season() == null && main.size() != 1) {
            return assessment("NEEDS_PROCESSING", "包含目标，但有未知视频、跨季或重复版本；现有整包整理不能安全执行", table, bytes, main.size(), preview, false, List.copyOf(resolutions));
        }
        if (missingQuality) return assessment("UNVERIFIED", "目标范围已识别，画质声明尚未取得", table, bytes, main.size(), preview, false, List.copyOf(resolutions));
        if (target.season() == null && target.year() != null && !target.declaresYear(table.rootName())
                && sourceTitles.stream().noneMatch(target::declaresYear)
                && main.stream().noneMatch(file -> target.declaresYear(file.path()))) {
            return assessment("UNVERIFIED", "目标文件已识别，发行年份待核对", table, bytes, main.size(), preview, false, List.copyOf(resolutions));
        }
        return assessment("RECOMMENDABLE", "文件表目标范围可由现有整理执行；画质/版本/字幕为名称声明，非媒体检测"
                + (preview ? "；含允许的尝鲜/抢先版本" : ""), table, bytes, main.size(), preview, false, List.copyOf(resolutions));
    }

    /** 共享正片边界，全集覆盖核验不能把花絮、特典或 sample 当作正片。 */
    public boolean isAuxiliaryVideo(String relativePath) {
        String name = relativePath.substring(relativePath.lastIndexOf('/') + 1);
        return AUXILIARY_DIRECTORY.matcher(relativePath).find() || rename.isNonFeatureVideo(name);
    }

    private Assessment assessment(String status, String reason, TorrentFileTable table, Long bytes, Integer files,
            boolean preview, boolean camera, List<String> resolutions) {
        return new Assessment(new ReleaseFileEvidence(status, reason, table.totalBytes(), bytes, files, preview, camera), resolutions);
    }

    private Set<Integer> seasons(String text) {
        Set<Integer> result = new LinkedHashSet<>();
        for (String tag : tags.explicitSeasonTags(text)) result.add(Integer.parseInt(tag.substring(1)));
        return result;
    }

    public record Assessment(ReleaseFileEvidence evidence, List<String> resolutions) {}
}
