package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.dto.magnet.request.MovieMagnetIngestRequest;
import com.medianexus.orchestrator.dto.magnet.request.SeriesMagnetIngestRequest;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import com.medianexus.orchestrator.integration.openlist.OpenListLibraryOrganizer;
import java.lang.reflect.Method;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MagnetIngestServiceAnimePathTest {

    @Test
    void moviePlanPreservesDisplayTitleForRetryContextWhileUsingOriginalTitleForSavePath() throws Exception {
        MagnetIngestService service = serviceWithRoots();

        Object plan = invokeBuildMoviePlan(service, new MovieMagnetIngestRequest(
                "magnet:?xt=urn:btih:1234567890abcdef",
                "007：大战皇家赌场",
                "Casino Royale",
                2006,
                36557
        ));

        assertThat(recordValue(plan, "title")).isEqualTo("007：大战皇家赌场");
        assertThat(recordValue(plan, "originalTitle")).isEqualTo("Casino Royale");
        assertThat(recordValue(plan, "tmdbId")).isEqualTo(36557);
        assertThat(recordValue(plan, "savePath")).isEqualTo("/pikpak/Media/Movies/Casino Royale (2006)");
    }

    @Test
    void seriesPlanPreservesDisplayTitleForRetryContextWhileUsingOriginalTitleForSavePath() throws Exception {
        MagnetIngestService service = serviceWithRoots();

        Object plan = invokeBuildSeriesPlan(service, new SeriesMagnetIngestRequest(
                "magnet:?xt=urn:btih:abcdef1234567890",
                "绝命毒师",
                "Breaking Bad",
                1,
                1396
        ));

        assertThat(recordValue(plan, "title")).isEqualTo("绝命毒师");
        assertThat(recordValue(plan, "originalTitle")).isEqualTo("Breaking Bad");
        assertThat(recordValue(plan, "tmdbId")).isEqualTo(1396);
        assertThat(recordValue(plan, "seriesName")).isEqualTo("Breaking Bad");
        assertThat(recordValue(plan, "savePath")).isEqualTo("/pikpak/Media/TV/Breaking Bad/Season 1");
    }

    @Test
    void animeSeasonSeriesPlanUsesDisplayTitleForFolderEvenWhenOriginalTitleExists() throws Exception {
        MagnetIngestService service = serviceWithRoots();

        Object plan = invokeBuildAnimeSeasonSeriesPlan(service, new SeriesMagnetIngestRequest(
                "magnet:?xt=urn:btih:1122334455667788",
                "葬送的芙莉莲",
                "Frieren: Beyond Journey's End",
                2,
                209867
        ));

        assertThat(recordValue(plan, "title")).isEqualTo("葬送的芙莉莲");
        assertThat(recordValue(plan, "originalTitle")).isEqualTo("Frieren: Beyond Journey's End");
        assertThat(recordValue(plan, "tmdbId")).isEqualTo(209867);
        assertThat(recordValue(plan, "seriesName")).isEqualTo("葬送的芙莉莲");
        assertThat(recordValue(plan, "savePath")).isEqualTo("/pikpak/Media/Anime/葬送的芙莉莲/Season 02");
    }

    @Test
    void rendersAnimeSeasonPathFromAnimeTemplateInsteadOfTvRoot() {
        MagnetIngestService service = serviceWithRoots();

        String savePath = service.renderAnimeSeasonPath(
                "葬送的芙莉莲",
                2
        );

        assertThat(savePath)
                .isEqualTo("/pikpak/Media/Anime/葬送的芙莉莲/Season 02")
                .doesNotContain("/TV/");
    }

    private MagnetIngestService serviceWithRoots() {
        OpenListProperties properties = new OpenListProperties();
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setMovieRootPath("/pikpak/Media/Movies");
        properties.setTvRootPath("/pikpak/Media/TV");
        properties.setAnimePathTemplate(
                "/pikpak/Media/Anime/{themoviedbName}/Season {seasonFormat}"
        );
        OpenListClient openListClient = new OpenListClient(properties, new ObjectMapper());
        return new MagnetIngestService(
                null,
                null,
                null,
                null,
                openListClient,
                properties,
                new MovieSeriesFileRenameService(),
                new OpenListLibraryOrganizer(openListClient),
                null,
                null,
                null,
                null
        );
    }

    private Object invokeBuildMoviePlan(MagnetIngestService service, MovieMagnetIngestRequest request)
            throws Exception {
        Method method = MagnetIngestService.class.getDeclaredMethod("buildMoviePlan", MovieMagnetIngestRequest.class);
        method.setAccessible(true);
        return method.invoke(service, request);
    }

    private Object invokeBuildSeriesPlan(MagnetIngestService service, SeriesMagnetIngestRequest request)
            throws Exception {
        Method method = MagnetIngestService.class.getDeclaredMethod("buildSeriesPlan", SeriesMagnetIngestRequest.class);
        method.setAccessible(true);
        return method.invoke(service, request);
    }

    private Object invokeBuildAnimeSeasonSeriesPlan(MagnetIngestService service, SeriesMagnetIngestRequest request)
            throws Exception {
        Method method = MagnetIngestService.class.getDeclaredMethod(
                "buildAnimeSeasonSeriesPlan",
                SeriesMagnetIngestRequest.class
        );
        method.setAccessible(true);
        return method.invoke(service, request);
    }

    private Object recordValue(Object record, String accessor) throws Exception {
        Method method = record.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return method.invoke(record);
    }
}
