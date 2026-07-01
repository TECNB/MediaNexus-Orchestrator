package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.OpenListProperties;
import com.medianexus.orchestrator.integration.openlist.OpenListClient;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class MagnetIngestServiceAnimePathTest {

    @Test
    void rendersAnimeSeasonPathFromAnimeTemplateInsteadOfTvRoot() {
        OpenListProperties properties = new OpenListProperties();
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setAnimePathTemplate(
                "/pikpak/Media/Anime/{themoviedbName}/Season {seasonFormat}"
        );
        properties.setTvRootPath("/pikpak/Media/TV");
        OpenListClient openListClient = new OpenListClient(properties, new ObjectMapper());
        MagnetIngestService service = new MagnetIngestService(
                null,
                null,
                null,
                null,
                openListClient,
                properties,
                null,
                null,
                null,
                null
        );

        String savePath = service.renderAnimeSeasonPath(
                "葬送的芙莉莲",
                "Frieren: Beyond Journey's End",
                2
        );

        assertThat(savePath)
                .isEqualTo("/pikpak/Media/Anime/Frieren Beyond Journey's End/Season 02")
                .doesNotContain("/TV/");
    }
}
