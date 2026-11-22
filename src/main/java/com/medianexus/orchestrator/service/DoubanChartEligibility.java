package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.*;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.Subject;
import com.medianexus.orchestrator.dto.resources.response.ProwlarrReleaseItemResponse;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/** 新榜单独立资格：来源完结事实与目录集号核对，不进入个人想看的严格日期资格。 */
@Service
public class DoubanChartEligibility {
    private static final Pattern COMPLETE = Pattern.compile("^\\s*([1-9][0-9]{0,3})\\s*集全\\s*$");
    private static final Pattern AIRING = Pattern.compile("^\\s*更新至\\s*[1-9][0-9]{0,3}\\s*集\\s*$");
    private static final Pattern SEASON = Pattern.compile("(?i)(?:^|[^a-z0-9])(?:s([0-9]{1,3})(?=e[0-9]|[^a-z0-9]|$)|season[ ._-]*([0-9]{1,3})(?=[^0-9]|$)|([0-9]{1,3})x[0-9]{1,3}(?=[^a-z0-9]|$))|第\\s*([0-9]{1,3})\\s*季");
    private final TmdbClient tmdb;
    private final ReleaseFileAssessment fileAssessment;
    private final ReleaseFileTables tables;
    private final MovieSeriesFileRenameService names;
    private final java.time.Clock clock;

    @org.springframework.beans.factory.annotation.Autowired
    public DoubanChartEligibility(TmdbClient tmdb, ReleaseFileTables tables, MovieSeriesFileRenameService names) {
        this(tmdb, tables, names, java.time.Clock.system(DoubanChartStore.ZONE));
    }
    DoubanChartEligibility(TmdbClient tmdb, ReleaseFileTables tables, MovieSeriesFileRenameService names, java.time.Clock clock) {
        this.tmdb = tmdb; this.tables = tables; this.names = names; this.clock = clock;
        this.fileAssessment = new ReleaseFileAssessment(names, new ReleaseTitleTagParser());
    }

    public Assessment assess(Subject subject, Identity corrected) {
        Identity identity = corrected;
        try {
            if (identity == null) identity = match(subject);
            if (identity == null) return refusal(null,"IDENTITY_AMBIGUOUS","作品或目标季未唯一确认，请选择真实目录");
            identity = correct(subject, new IdentityInput(identity.mediaType(),identity.tmdbId(),identity.seasonNumber()));
            if ("MOVIE".equals(subject.mediaType())) {
                if (!released(identity.tmdbId())) return refusal(identity,"RELEASE_UNKNOWN","尚无已发生的有效发行记录，暂不受理");
                return new Assessment(identity,BgmWishlistService.target(identity),List.of(),null);
            }
            String progress = subject.progress() == null ? "" : subject.progress();
            var completion = COMPLETE.matcher(progress);
            boolean sourceComplete = completion.matches();
            if (!sourceComplete && !progress.isBlank()) return refusal(identity, AIRING.matcher(progress).matches() ? "AIRING" : "PROGRESS_UNKNOWN",
                    AIRING.matcher(progress).matches() ? "仍在更新，等待明确完结" : "来源进度声明尚不能确认完结，等待核实");
            JsonNode series = tmdb.getTvDetails(identity.tmdbId(),"zh-CN");
            JsonNode summary = findSeason(series,identity.seasonNumber());
            JsonNode season = tmdb.getTvSeasonDetails(identity.tmdbId(),identity.seasonNumber(),"zh-CN");
            JsonNode episodes = season.path("episodes");
            var catalog = CatalogSeasonProgress.inspect(series, season, identity, clock);
            if (!sourceComplete && !"COMPLETE".equals(catalog.seasonStatus()))
                return refusal(identity, "CONFLICT".equals(catalog.seasonStatus()) ? "PROGRESS_CONFLICT" : "PROGRESS_UNKNOWN",
                        "目录完结证据不足：" + catalog.message());
            if (sourceComplete && Set.of("AIRING", "NOT_AIRED", "CONFLICT").contains(catalog.seasonStatus()))
                return refusal(identity, "PROGRESS_CONFLICT", "豆瓣完结声明与目标季目录日期或后续安排矛盾，等待核实");
            int expected = sourceComplete ? Integer.parseInt(completion.group(1)) : catalog.totalEpisodes();
            if (summary == null || summary.path("episode_count").asInt(-1) != expected
                    || !episodes.isArray() || episodes.size() != expected) {
                return refusal(identity,"EPISODE_CONFLICT","已确认完结，但总集数与目标季目录不一致，等待版本或季映射确认");
            }
            Set<Integer> numbers = new TreeSet<>();
            for (JsonNode episode : episodes) {
                int number = episode.path("episode_number").asInt(-1);
                if (!episode.path("episode_number").isIntegralNumber() || number < 1 || number > expected
                        || !numbers.add(number) || episode.has("season_number") && episode.path("season_number").asInt(-1) != identity.seasonNumber()) {
                    return refusal(identity,"EPISODE_CONFLICT","目标季集号不完整或存在冲突，等待确认");
                }
            }
            return new Assessment(identity,BgmWishlistService.target(identity),List.copyOf(numbers),null);
        } catch (RuntimeException failure) {
            return refusal(identity,"CATALOG_FAILED","目录身份或目标季本次未能核实，未继续受理");
        }
    }

    public List<CatalogCandidate> search(String query,String mediaType) {
        JsonNode results = tmdb.searchChartTitles(query,mediaType);
        List<CatalogCandidate> choices = new ArrayList<>();
        for (JsonNode node : results) {
            String title = text(node,"MOVIE".equals(mediaType) ? "title" : "name");
            if (title == null) continue;
            choices.add(new CatalogCandidate(mediaType,node.path("id").asInt(),title,
                    text(node,"MOVIE".equals(mediaType) ? "original_title" : "original_name"),
                    year(text(node,"MOVIE".equals(mediaType) ? "release_date" : "first_air_date")),poster(node)));
        }
        return List.copyOf(choices);
    }

    public List<Season> seasons(int tmdbId) {
        JsonNode details = tmdb.getTvDetails(tmdbId,"zh-CN");
        requireCatalog(details,tmdbId);
        List<Season> seasons = new ArrayList<>();
        for (JsonNode node : details.path("seasons")) {
            int number = node.path("season_number").asInt(-1);
            if (number > 0) seasons.add(new Season(number,text(node,"name"),
                    node.path("episode_count").isIntegralNumber() ? node.path("episode_count").intValue() : null,text(node,"air_date")));
        }
        return List.copyOf(seasons);
    }

    public Identity correct(Subject subject,IdentityInput input) {
        if (!subject.mediaType().equals(input.mediaType())) throw bad("目录类型与来源作品不一致");
        JsonNode details;
        if ("MOVIE".equals(input.mediaType())) {
            if (input.seasonNumber() != null) throw bad("电影不能选择季度");
            details = tmdb.getMovieDetails(input.tmdbId(),"zh-CN");
        } else {
            if (input.seasonNumber() == null || input.seasonNumber() < 1) throw bad("必须选择真实正片目标季");
            details = tmdb.getTvDetails(input.tmdbId(),"zh-CN");
            requireCatalog(details,input.tmdbId());
            if (findSeason(details,input.seasonNumber()) == null) throw bad("所选季度不在真实目录中");
            JsonNode season = tmdb.getTvSeasonDetails(input.tmdbId(),input.seasonNumber(),"zh-CN");
            if (!season.path("id").isIntegralNumber() || season.path("id").asLong() < 1
                    || season.path("season_number").asInt(-1) != input.seasonNumber()) throw bad("所选季度无法核实");
        }
        requireCatalog(details,input.tmdbId());
        String title = text(details,"MOVIE".equals(input.mediaType()) ? "title" : "name");
        String original = text(details,"MOVIE".equals(input.mediaType()) ? "original_title" : "original_name");
        if (title == null || original == null) throw bad("目录缺少必要片名或原名");
        return new Identity(input.mediaType(),input.tmdbId(),input.seasonNumber(),title,original,
                year(text(details,"MOVIE".equals(input.mediaType()) ? "release_date" : "first_air_date")));
    }

    /** 只读目录身份完整操作；null 表示身份待确认，异常表示目录读取失败；不调用受理资格。 */
    public Identity resolveProgressIdentity(Subject subject, Identity corrected) {
        return corrected == null ? match(subject) : correct(subject,
                new IdentityInput(corrected.mediaType(), corrected.tmdbId(), corrected.seasonNumber()));
    }

    private Identity match(Subject subject) {
        if (subject.year() == null) return null;
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(subject.title());
        if (subject.originalTitle() != null) queries.add(subject.originalTitle());
        if ("MOVIE".equals(subject.mediaType())) {
            queries.add(subject.title().replaceFirst("(?<=\\p{L})[0-9]+(?=[：:])", ""));
        }
        Map<Integer, CatalogCandidate> found = new LinkedHashMap<>();
        for (String query : queries) {
            for (var candidate : search(query, subject.mediaType())) {
                if (queries.stream().noneMatch(name -> sameTitle(name, candidate.title()) || sameTitle(name, candidate.originalTitle()))) continue;
                if ("MOVIE".equals(subject.mediaType()) && !Objects.equals(candidate.year(), subject.year())) continue;
                found.put(candidate.tmdbId(), candidate);
            }
            if (!found.isEmpty()) break;
        }
        var matches = List.copyOf(found.values());
        if (matches.size() != 1) return null;
        var candidate = matches.get(0);
        Integer season = null;
        if ("SERIES".equals(subject.mediaType())) {
            List<Season> seasons = seasons(candidate.tmdbId());
            // 系列唯一不代表目标季唯一；多季必须人工选，不以续季年份猜第一或最新季。
            if (seasons.size() != 1 || !Objects.equals(year(seasons.get(0).airDate()),subject.year())) return null;
            season = seasons.get(0).seasonNumber();
        }
        return correct(subject,new IdentityInput(subject.mediaType(),candidate.tmdbId(),season));
    }

    /** 必须有本轮共享比较缓存的安全文件表；数出独立集号且无错季、未知或重复正片。 */
    public boolean completeRelease(Assessment assessment,ProwlarrReleaseItemResponse release) {
        if (assessment.reason() != null || release.evidence() == null
                || !"RECOMMENDABLE".equals(release.evidence().status()) || release.evidence().camera()) return false;
        if ("MOVIE".equals(assessment.identity().mediaType())) return true;
        String magnet = tables.knownMagnet(release.indexerId(),release.downloadRef()).orElse(null);
        if (magnet == null) return false;
        var evidence = tables.cached(magnet).orElse(null);
        if (evidence == null) return false;
        Set<Integer> covered = new HashSet<>();
        int season = assessment.identity().seasonNumber();
        for (var file : evidence.table().files()) {
            if (!names.isVideo(file.path())) continue;
            if (names.isPromotionalVideo(file.path(), file.sizeBytes())) continue;
            if (fileAssessment.isAuxiliaryVideo(file.path())) continue;
            if (file.sizeBytes() <= 0) return false;
            var marker = SEASON.matcher(file.path());
            while (marker.find()) for (int i=1; i<=marker.groupCount(); i++) {
                if (marker.group(i) != null && Integer.parseInt(marker.group(i)) != season) return false;
            }
            String basename = file.path().substring(file.path().lastIndexOf('/') + 1);
            Integer episode = names.episodeNumber(basename,season).orElse(null);
            if (episode == null || !assessment.expectedEpisodes().contains(episode) || !covered.add(episode)) return false;
        }
        return !covered.isEmpty() && covered.equals(new HashSet<>(assessment.expectedEpisodes()));
    }

    private boolean released(int id) {
        JsonNode countries = tmdb.getMovieReleaseDates(id).path("results");
        if (!countries.isArray()) return false;
        for (JsonNode country : countries) for (JsonNode release : country.path("release_dates")) {
            int type = release.path("type").asInt(-1);
            if (type < 2 || type > 6) continue;
            try { if (!Instant.parse(release.path("release_date").asText()).isAfter(Instant.now())) return true; }
            catch (java.time.format.DateTimeParseException ignored) { }
        }
        return false;
    }
    private static Assessment refusal(Identity identity,String code,String message) {
        return new Assessment(identity,null,List.of(),new Reason(code,message));
    }
    private static JsonNode findSeason(JsonNode details,int number) {
        JsonNode found = null;
        for (JsonNode node : details.path("seasons")) if (node.path("season_number").asInt(-1) == number) {
            if (found != null) throw bad("季度目录重复");
            found = node;
        }
        return found;
    }
    private static boolean sameTitle(String a,String b) { return a != null && b != null && normalize(a).equals(normalize(b)); }
    private static String normalize(String value) { return Normalizer.normalize(value,Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]",""); }
    private static String text(JsonNode node,String field) { return node.path(field).isTextual() && !node.path(field).textValue().isBlank() ? node.path(field).textValue().strip() : null; }
    private static Integer year(String value) { return value != null && value.matches("[12][0-9]{3}-[0-9]{2}-[0-9]{2}") ? Integer.valueOf(value.substring(0,4)) : null; }
    private static String poster(JsonNode node) { String path=text(node,"poster_path"); return path != null && path.startsWith("/") ? "https://image.tmdb.org/t/p/w500"+path : null; }
    private static void requireCatalog(JsonNode details,int id) { if (details == null || details.path("id").asInt(-1) != id) throw bad("真实目录无法核实"); }
    private static BusinessException bad(String message) { return new BusinessException(ErrorCode.BAD_REQUEST,message); }
    public record Assessment(Identity identity,BgmWishlistTaskGateway.Target target,List<Integer> expectedEpisodes,Reason reason) {}
}
