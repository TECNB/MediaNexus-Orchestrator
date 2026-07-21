package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.integration.anirss.AniRssClient;
import com.medianexus.orchestrator.integration.anirss.AniRssClientException;
import org.junit.jupiter.api.Test;

class AnimeMagnetSearchServiceIdentityTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AniRssClient aniRssClient = mock(AniRssClient.class);
    private final AnimeMagnetSearchService service = new AnimeMagnetSearchService(aniRssClient);

    @Test
    void resolvesTmdbIdFromSelectedBangumiSubject() throws Exception {
        when(aniRssClient.getAniBySubjectId("400602"))
                .thenReturn(objectMapper.readTree("{\"tmdb\":{\"id\":209867}}"));

        assertThat(service.resolveTmdbId("400602")).isEqualTo(209867);
    }

    @Test
    void keepsManualIngestAvailableWhenAniRssIdentityLookupFails() {
        when(aniRssClient.getAniBySubjectId("400602"))
                .thenThrow(new AniRssClientException("temporary failure"));

        assertThat(service.resolveTmdbId("400602")).isNull();
    }
}
