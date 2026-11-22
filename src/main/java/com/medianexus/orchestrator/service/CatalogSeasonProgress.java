package com.medianexus.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.medianexus.orchestrator.dto.bgm.BgmWishlistContract.Identity;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.CatalogProgress;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

/** 同一目标季的目录集号、日期及结束证据；展示与受理共用，不读取展示缓存授予资格。 */
final class CatalogSeasonProgress {
    private CatalogSeasonProgress() {}

    static CatalogProgress inspect(JsonNode series, JsonNode season, Identity identity, Clock clock) {
        if (series == null || series.path("id").asInt(-1) != identity.tmdbId()
                || season == null || !season.path("id").isIntegralNumber() || season.path("id").asLong() < 1
                || !season.path("season_number").isIntegralNumber()
                || season.path("season_number").asInt(-1) != identity.seasonNumber())
            throw new IllegalStateException("目录身份不一致");
        JsonNode summary = null;
        for (JsonNode node : series.path("seasons")) if (node.path("season_number").asInt(-1) == identity.seasonNumber()) {
            if (summary != null) return unknown(identity, series, clock, "目标季摘要重复，无法核实进度");
            summary = node;
        }
        JsonNode episodes = season.path("episodes");
        int total = summary != null && summary.path("episode_count").isIntegralNumber() ? summary.path("episode_count").asInt(-1) : -1;
        if (total < 1 || !episodes.isArray() || episodes.size() != total)
            return unknown(identity, series, clock, "目标季正集数量与摘要不一致，证据不足");
        Set<Integer> numbers = new HashSet<>();
        Map<Integer, LocalDate> dates = new HashMap<>();
        int past = 0, today = 0, undated = 0, future = 0;
        boolean finale = false;
        LocalDate currentDate = LocalDate.now(clock);
        for (JsonNode episode : episodes) {
            int number = episode.path("episode_number").asInt(-1);
            if (!episode.path("episode_number").isIntegralNumber() || number < 1 || number > total || !numbers.add(number)
                    || episode.has("season_number") && (!episode.path("season_number").isIntegralNumber()
                    || episode.path("season_number").asInt(-1) != identity.seasonNumber()))
                return unknown(identity, series, clock, "目标季正集号重复、缺失或错季，证据不足");
            LocalDate date = date(episode.path("air_date").asText(null));
            if (date == null) undated++;
            else {
                dates.put(number, date);
                if (date.isBefore(currentDate)) past++;
                else if (date.equals(currentDate)) today++;
                else future++;
            }
            if (number == total) finale = Set.of("finale", "season_finale", "series_finale").contains(episode.path("episode_type").asText(""));
        }
        String seriesStatus = series.path("status").asText(null);
        String status = "UNKNOWN", message = "目录日期或结束证据不足，不能确认目标季进度";
        if (undated == 0 && future == total) { status = "NOT_AIRED"; message = "目标季目录日期均在未来，尚未到目录播出日期"; }
        else if (future > 0 && past + today > 0) { status = "AIRING"; message = "目标季目录同时有已到日期与未来集日期"; }
        else if (past == total && (finale || "Ended".equals(seriesStatus))) { status = "COMPLETE"; message = "目标季全部正集日期已过去，且目录有结束证据"; }
        Integer nextNumber = null; String nextDate = null;
        JsonNode next = series.path("next_episode_to_air");
        int number = next.path("episode_number").asInt(-1);
        LocalDate announced = date(next.path("air_date").asText(null));
        if (next.path("season_number").isIntegralNumber() && next.path("season_number").asInt(-1) == identity.seasonNumber()
                && next.path("episode_number").isIntegralNumber() && announced != null && !announced.isBefore(currentDate)) {
            if (announced.equals(dates.get(number))) { nextNumber = number; nextDate = announced.toString(); }
            else { status = "CONFLICT"; message = "目标季后续集安排与逐集日期矛盾，需核实"; }
        }
        return new CatalogProgress("SUCCEEDED", clock.instant(), identity, seriesStatus, total, past, today, undated,
                nextNumber, nextDate, status, message);
    }

    private static CatalogProgress unknown(Identity identity, JsonNode series, Clock clock, String message) {
        return new CatalogProgress("SUCCEEDED", clock.instant(), identity, series.path("status").asText(null), null, null, null, null, null, null, "UNKNOWN", message);
    }
    private static LocalDate date(String value) {
        if (value == null || !value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}")) return null;
        try { return LocalDate.parse(value); } catch (DateTimeParseException invalid) { return null; }
    }
}
