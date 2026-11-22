package com.medianexus.orchestrator.service;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.integration.guangya.GuangyaException;
import com.medianexus.orchestrator.integration.guangya.ResolveBudget;
import com.medianexus.orchestrator.integration.guangya.TorrentFileTable;
import com.medianexus.orchestrator.integration.pansou.PanSouMagnetIdentity;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 电影/季度任务唯一的受理安全边界；在扣额、持久化和下载前完成只读补证与执行能力判断。 */
@Component
public class ReleaseSubmissionSafety {
    private final ReleaseFileAssessment assessment;
    private final ReleaseTitleTagParser tags = new ReleaseTitleTagParser();
    private final ReleaseFileTables fileTables;
    // 重启失效、有界保留本次运行内已知证据；换入口、去掉标题或暂时断网不能洗掉安全失败。
    private final Cache<String, String> invalidTables = CacheBuilder.newBuilder().maximumSize(2048)
            .expireAfterWrite(2, TimeUnit.HOURS).build();
    private final Cache<String, Set<String>> sourceTitles = CacheBuilder.newBuilder().maximumSize(2048)
            .expireAfterWrite(2, TimeUnit.HOURS).build();
    private final Cache<String, String> unsafeTargets = CacheBuilder.newBuilder().maximumSize(2048)
            .expireAfterWrite(2, TimeUnit.HOURS).build();

    public ReleaseSubmissionSafety(ReleaseFileTables fileTables, ReleaseFileAssessment assessment) {
        this.fileTables = fileTables;
        this.assessment = assessment;
    }

    /** 比较端已发现的来源声明与受理共享；去掉 dn/发布标题不能抹掉同 hash 的冲突。 */
    public void rememberSourceTitles(String magnet, List<String> titles) {
        String hash = PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(() -> rejected("磁力缺少唯一有效 BTIH")).btih();
        for (String title : titles) rememberTitles(hash, magnet, title);
    }

    /** 比较端遇到 INVALID_RESPONSE 时记录安全失败；网络/认证失败不得写成非法文件表。 */
    public void rememberInvalidFileTable(String magnet) {
        String hash = PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(() -> rejected("磁力缺少唯一有效 BTIH")).btih();
        invalidTables.put(hash, "文件表安全校验失败，禁止提交；需重新核实上游响应");
    }


    /** 返回写入既有任务日志的文件名及版本声明；光鸭路径不是 PikPak 文件 ID。 */
    public String check(String magnet, ReleaseTarget target, ReleaseIngestMetadata metadata) {
        String hash = PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(() -> rejected("磁力缺少唯一有效 BTIH")).btih();
        String targetKey = hash + ":" + target;
        String unsafe = unsafeTargets.getIfPresent(targetKey);
        if (unsafe != null) throw rejected(unsafe);
        String invalid = invalidTables.getIfPresent(hash);
        if (invalid != null) throw rejected(invalid);
        ReleaseIngestMetadata release = metadata == null ? ReleaseIngestMetadata.manual() : metadata;
        for (String source : fileTables.sourceMagnets(magnet)) rememberTitles(hash, source, null);
        List<String> titles = rememberTitles(hash, magnet, release.releaseTitle());
        for (String title : titles) {
            String conflict = target.conflict(title);
            if (conflict != null) throw rejected(conflict);
            if (ReleaseVersion.isCamera(title)) throw rejected("发布存在明确枪版声明");
            if (!target.possiblyRelated(title)) throw rejected("发布标题与目标作品身份冲突");
            if (target.season() == null && !tags.explicitSeasonTags(title).isEmpty()) throw rejected("发布明确为季度内容，与目标电影身份冲突");
            if (target.season() != null) {
                Set<String> seasons = new LinkedHashSet<>(tags.explicitSeasonTags(title));
                if (!seasons.isEmpty() && !seasons.contains("S%02d".formatted(target.season()))) throw rejected("发布季编号与目标季冲突");
                if (seasons.size() > 1) throw rejected("跨季发布需要处理；现有整包整理不能安全执行");
            }
        }
        TorrentFileTable table = null;
        String tableSource = "光鸭文件表";
        String unknownReason = "未取得可信文件表";
        try {
            var resolved = fileTables.resolve(magnet, ResolveBudget.start());
            table = resolved.table(); tableSource = resolved.source();
        } catch (GuangyaException failure) {
            if (failure.reason() == GuangyaException.Reason.INVALID_RESPONSE) {
                invalidTables.put(hash, failure.getMessage());
                throw rejected(failure.getMessage());
            }
            unknownReason = failure.getMessage();
        }
        if (table == null) return acceptUnknown(release, unknownReason) + "; sourceTitles=" + titles
                + "; preview=" + titles.stream().anyMatch(ReleaseVersion::isPreview);
        if (ReleaseVersion.isCamera(table.rootName()) || table.files().stream().anyMatch(file -> ReleaseVersion.isCamera(file.path()))) {
            invalidTables.put(hash, "文件表存在明确枪版声明");
            throw rejected("文件表存在明确枪版声明");
        }
        if (target.season() == null && (!tags.explicitSeasonTags(table.rootName()).isEmpty()
                || table.files().stream().anyMatch(file -> !tags.explicitSeasonTags(file.path()).isEmpty()))) {
            unsafeTargets.put(targetKey, "文件表明确为季度内容，与目标电影身份冲突");
            throw rejected("文件表明确为季度内容，与目标电影身份冲突");
        }
        if (target.season() != null) {
            Set<String> seasons = new LinkedHashSet<>(tags.explicitSeasonTags(table.rootName()));
            if (!seasons.isEmpty() && !seasons.contains("S%02d".formatted(target.season()))) {
                unsafeTargets.put(targetKey, "文件表季编号与目标季冲突");
                throw rejected("文件表季编号与目标季冲突");
            }
            if (seasons.size() > 1) {
                unsafeTargets.put(targetKey, "跨季文件表需要处理；现有整包整理不能安全执行");
                throw rejected("跨季文件表需要处理；现有整包整理不能安全执行");
            }
        }
        var evidence = assessment.assess(target, titles, table).evidence();
        if ("UNSUITABLE".equals(evidence.status()) || "NEEDS_PROCESSING".equals(evidence.status())) {
            unsafeTargets.put(targetKey, evidence.reason());
            throw rejected(evidence.reason());
        }
        String permission = "UNVERIFIED".equals(evidence.status()) ? acceptUnknown(release, evidence.reason()) : evidence.status();
        StringBuilder record = new StringBuilder(permission).append("; reason=").append(evidence.reason())
                .append("; preview=").append(evidence.preview()).append("; camera=").append(evidence.camera())
                .append("; root=").append(table.rootName()).append("; sourceTitles=").append(titles);
        record.append("; file_table_source=").append(tableSource);
        for (var file : table.files()) record.append("\noriginal_file=").append(file.path()).append("; bytes=")
                .append(file.sizeBytes()).append("; preview=").append(ReleaseVersion.isPreview(file.path()))
                .append("; camera=").append(ReleaseVersion.isCamera(file.path()));
        return record.toString();
    }

    private String acceptUnknown(ReleaseIngestMetadata release, String reason) {
        if (!release.acceptUnverifiedRisk()) throw rejected("未核验：" + reason + "；须显式 accept_unverified_risk=true，后台自动任务不得接受该风险");
        return "UNVERIFIED; 用户显式接受未核验风险; reason=" + reason;
    }

    private synchronized List<String> rememberTitles(String hash, String magnet, String releaseTitle) {
        Set<String> remembered = sourceTitles.getIfPresent(hash);
        LinkedHashSet<String> titles = new LinkedHashSet<>(remembered == null ? Set.of() : remembered);
        if (releaseTitle != null && !releaseTitle.isBlank()) titles.add(releaseTitle);
        try {
            for (String parameter : URI.create(magnet).getRawSchemeSpecificPart().substring(1).split("&")) {
                String[] pair = parameter.split("=", 2);
                if (pair.length == 2 && "dn".equalsIgnoreCase(pair[0])) {
                    String name = URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
                    if (!name.isBlank()) titles.add(name);
                }
            }
        } catch (IllegalArgumentException failure) { throw rejected("磁力显示名称编码无效"); }
        // 同 hash 保留全部冲突声明而非最后一次提交的标题；过量证据拒绝，不截断丢失冲突。
        if (titles.size() > 64 || titles.stream().anyMatch(title -> title.length() > 4096)) {
            invalidTables.put(hash, "磁力来源声明超出安全上限");
            throw rejected("磁力来源声明超出安全上限");
        }
        sourceTitles.put(hash, Set.copyOf(titles));
        return new ArrayList<>(titles);
    }

    private BusinessException rejected(String reason) {
        return new BusinessException(ErrorCode.BAD_REQUEST, "提交安全检查拒绝：" + reason, HttpStatus.BAD_REQUEST);
    }
}
