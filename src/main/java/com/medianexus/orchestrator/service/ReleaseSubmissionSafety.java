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
    @org.springframework.beans.factory.annotation.Autowired
    private MovieReleaseAliases movieAliases;
    @org.springframework.beans.factory.annotation.Autowired
    private SeriesReleaseAliases seriesAliases;

    public String checkMovie(Integer tmdbId, String magnet, ReleaseTarget target, ReleaseIngestMetadata metadata) {
        return check(magnet, tmdbId == null ? target : movieAliases.enrich(tmdbId, target), metadata);
    }

    public String checkSeries(Integer tmdbId, String magnet, ReleaseTarget target, ReleaseIngestMetadata metadata) {
        return check(magnet, tmdbId == null ? target : seriesAliases.enrich(tmdbId, target), metadata);
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
        ReleaseIngestMetadata release = metadata == null ? ReleaseIngestMetadata.manual() : metadata;
        List<String> titles = checkedSourceTitles(magnet, target, release);
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
        return assessTable(hash, target, titles, table, tableSource, release);
    }

    private String assessTable(String hash, ReleaseTarget target, List<String> titles, TorrentFileTable table,
                               String tableSource, ReleaseIngestMetadata release) {
        String targetKey = hash + ":" + target;
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

    private List<String> checkedSourceTitles(String magnet, ReleaseTarget target, ReleaseIngestMetadata release) {
        String hash = PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(() -> rejected("磁力缺少唯一有效 BTIH")).btih();
        String unsafe = unsafeTargets.getIfPresent(hash + ":" + target);
        if (unsafe != null) throw rejected(unsafe);
        String invalid = invalidTables.getIfPresent(hash);
        if (invalid != null) throw rejected(invalid);
        for (String source : fileTables.sourceMagnets(magnet)) rememberTitles(hash, source, null);
        rememberSourceTitles(magnet, fileTables.sourceTitles(magnet));
        List<String> titles = rememberTitles(hash, magnet, release.releaseTitle());
        for (String title : titles) {
            String conflict = target.conflict(title);
            if (conflict != null) throw rejected(conflict);
            if (ReleaseVersion.isCamera(title)) throw rejected("发布存在明确枪版声明");
            if (!target.possiblyRelated(title)) throw rejected("发布标题与目标作品身份冲突");
            var seasons = tags.explicitSeasonTags(title);
            if (target.season() == null && !seasons.isEmpty()) throw rejected("发布明确为季度内容，与目标电影身份冲突");
            if (target.season() != null && !seasons.isEmpty() && !seasons.contains("S%02d".formatted(target.season())))
                throw rejected("发布季编号与目标季冲突");
            if (target.season() != null && new LinkedHashSet<>(seasons).size() > 1) throw rejected("跨季发布需要处理；现有整包整理不能安全执行");
        }
        return titles;
    }

    /** Only the server-owned original task's submission_safety record is eligible; no user risk acknowledgement. */
    public String checkMovieOrganizationRecovery(Integer tmdbId, String magnet, String storedHash, ReleaseTarget target,
            ReleaseIngestMetadata metadata, String originalSubmissionEvidence) {
        return checkOrganizationRecovery(magnet, storedHash, tmdbId == null ? target : movieAliases.enrich(tmdbId, target),
                metadata, originalSubmissionEvidence);
    }

    public String checkSeriesOrganizationRecovery(Integer tmdbId, String magnet, String storedHash, ReleaseTarget target,
            ReleaseIngestMetadata metadata, String originalSubmissionEvidence) {
        return checkOrganizationRecovery(magnet, storedHash, tmdbId == null ? target : seriesAliases.enrich(tmdbId, target),
                metadata, originalSubmissionEvidence);
    }

    private String checkOrganizationRecovery(String magnet, String storedHash, ReleaseTarget target,
            ReleaseIngestMetadata metadata, String originalSubmissionEvidence) {
        var identity = PanSouMagnetIdentity.fromUrl(magnet).orElseThrow(() -> rejected("RECOVERY_HASH_INVALID"));
        if (storedHash == null || !identity.btih().equalsIgnoreCase(storedHash)) throw rejected("RECOVERY_TASK_HASH_MISMATCH");
        if (originalSubmissionEvidence != null) {
            originalSubmissionTable(identity, originalSubmissionEvidence);
            var originalTitles = java.util.regex.Pattern.compile("; sourceTitles=\\[(.*)]; file_table_source=")
                    .matcher(originalSubmissionEvidence.lines().findFirst().orElse(""));
            if (!originalTitles.find()) throw rejected("RECOVERY_ORIGINAL_TABLE_MALFORMED");
            if (!originalTitles.group(1).isBlank())
                rememberSourceTitles(magnet, List.of(originalTitles.group(1).split(", ", -1)));
        }
        List<String> titles = checkedSourceTitles(magnet, target, metadata);
        TorrentFileTable table;
        String source;
        try {
            var live = fileTables.resolve(magnet, ResolveBudget.start());
            table = live.table(); source = live.source();
        } catch (GuangyaException failure) {
            if (failure.reason() == GuangyaException.Reason.INVALID_RESPONSE) invalidTables.put(identity.btih(), "RECOVERY_LIVE_INVALID_RESPONSE");
            if (!Set.of(GuangyaException.Reason.NOT_CONFIGURED, GuangyaException.Reason.UNAVAILABLE,
                    GuangyaException.Reason.AUTHENTICATION, GuangyaException.Reason.ACCOUNT_BLOCKED,
                    GuangyaException.Reason.BUDGET_EXHAUSTED).contains(failure.reason()))
                throw rejected("RECOVERY_LIVE_" + failure.reason());
            table = originalSubmissionTable(identity, originalSubmissionEvidence);
            var declarations = java.util.regex.Pattern.compile("; sourceTitles=\\[(.*)]; file_table_source=")
                    .matcher(originalSubmissionEvidence.lines().findFirst().orElse(""));
            if (!declarations.find()) throw rejected("RECOVERY_ORIGINAL_TABLE_MALFORMED");
            if (!declarations.group(1).isBlank()) rememberSourceTitles(magnet, List.of(declarations.group(1).split(", ", -1)));
            titles = checkedSourceTitles(magnet, target, metadata);
            source = "原任务持久文件表；live=" + failure.reason();
        }
        return assessTable(identity.btih(), target, titles, table, source, ReleaseIngestMetadata.manual());
    }

    private TorrentFileTable originalSubmissionTable(PanSouMagnetIdentity identity, String record) {
        if (record == null || record.length() > 2000000 || !record.startsWith("RECOMMENDABLE; "))
            throw rejected("RECOVERY_ORIGINAL_TABLE_UNTRUSTED");
        String[] lines = record.split("\\R", -1);
        var header = java.util.regex.Pattern.compile("^RECOMMENDABLE; reason=.+; preview=(?:true|false); camera=false; root=(.+); sourceTitles=\\[.*]; file_table_source=.+$").matcher(lines[0]);
        if (!header.matches()) throw rejected("RECOVERY_ORIGINAL_TABLE_MALFORMED");
        String root = header.group(1);
        if (root.isBlank() || root.contains(";") || root.contains("\\") || root.startsWith("/")
                || java.util.Arrays.stream(root.split("/", -1)).anyMatch(p -> p.isBlank() || p.equals(".") || p.equals("..")))
            throw rejected("RECOVERY_ORIGINAL_ROOT_INVALID");
        List<TorrentFileTable.Entry> entries = new ArrayList<>();
        Set<String> paths = new java.util.HashSet<>();
        long total = 0;
        var pattern = java.util.regex.Pattern.compile("^original_file=(.+); bytes=(\\d+); preview=(?:true|false); camera=false$");
        try {
            for (int index = 1; index < lines.length; index++) {
                var file = pattern.matcher(lines[index]);
                if (!file.matches()) throw rejected("RECOVERY_ORIGINAL_TABLE_MALFORMED");
                String path = file.group(1);
                if (path.startsWith("/") || path.contains("\\") || path.contains(";")
                        || java.util.Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isBlank() || p.equals(".") || p.equals(".."))
                        || !paths.add(path.toLowerCase(java.util.Locale.ROOT))) throw rejected("RECOVERY_ORIGINAL_PATH_INVALID");
                long bytes = Long.parseLong(file.group(2));
                if (bytes <= 0) throw rejected("RECOVERY_ORIGINAL_BYTES_INVALID");
                total = Math.addExact(total, bytes);
                entries.add(new TorrentFileTable.Entry(path, bytes));
            }
        } catch (NumberFormatException | ArithmeticException invalid) { throw rejected("RECOVERY_ORIGINAL_BYTES_INVALID"); }
        if (entries.isEmpty() || entries.size() > 10000) throw rejected("RECOVERY_ORIGINAL_TABLE_INCOMPLETE");
        return new TorrentFileTable(identity, root, entries, total);
    }

    /** Curated business rejection only; never expose upstream messages, URLs, magnet parameters or credentials. */
    public static String organizationRejectionReason(RuntimeException failure) {
        String category = failure instanceof GuangyaException upstream ? "SOURCE_" + upstream.reason()
                : failure instanceof BusinessException ? "SAFETY_REJECTED" : "OBSERVATION_UNAVAILABLE";
        String message = failure instanceof BusinessException ? failure.getMessage() : "请核对目标映射、文件对应和上游可见性";
        if (message == null) message = "无安全原因描述";
        message = message.replaceAll("(?i)(?:https?://|magnet:)[^\\s，；]*", "[redacted]")
                .replaceAll("(?i)(?:token|authorization|cookie|password|sign|secret|fileid|userid|xt|dn)\\s*[=:]\\s*[^，；\\r\\n]*", "[redacted]")
                .replaceAll("[\\r\\n\\t]", " ");
        return category + ": " + message.substring(0, Math.min(240, message.length()));
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
