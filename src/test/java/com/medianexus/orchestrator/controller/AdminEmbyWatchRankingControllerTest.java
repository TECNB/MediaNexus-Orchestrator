package com.medianexus.orchestrator.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.medianexus.orchestrator.common.exception.BusinessException;
import com.medianexus.orchestrator.common.exception.ErrorCode;
import com.medianexus.orchestrator.common.exception.GlobalExceptionHandler;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.dto.emby.response.EmbyPrivateWatchTitle;
import com.medianexus.orchestrator.mapper.EmbyActivePlaybackSessionMapper;
import com.medianexus.orchestrator.mapper.EmbyWatchSessionMapper;
import com.medianexus.orchestrator.service.AuthService;
import com.medianexus.orchestrator.service.EmbyWatchRankingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminEmbyWatchRankingControllerTest {
    private final AuthService auth = mock(AuthService.class);
    private final EmbyWatchSessionMapper sessions = mock(EmbyWatchSessionMapper.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new AdminEmbyWatchRankingController(
            new EmbyWatchRankingService(auth, new EmbyProperties(),
                    mock(EmbyActivePlaybackSessionMapper.class), sessions)))
            .setControllerAdvice(new GlobalExceptionHandler()).build();

    @Test
    void resolvesPrivateTitlesAtTheStatisticsClientRoute() throws Exception {
        when(sessions.selectPrivateTitles(List.of("video-1", "video-2")))
                .thenReturn(List.of(new EmbyPrivateWatchTitle("video-1", "video-1", "Private title")));

        mvc.perform(get("/api/v1/admin/emby/watch-rankings/private-titles")
                        .param("item_ids", "video-1,video-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data[0].item_id").value("video-1"))
                .andExpect(jsonPath("$.data[0].title").value("Private title"));
        verify(auth).requireAdminUser();
    }

    @Test
    void rejectsPrivateTitleReadsBeforeQueryingWhenAdminAccessIsDenied() throws Exception {
        when(auth.requireAdminUser()).thenThrow(new BusinessException(
                ErrorCode.FORBIDDEN, "forbidden", HttpStatus.FORBIDDEN));

        mvc.perform(get("/api/v1/admin/emby/watch-rankings/private-titles").param("item_ids", "video-1"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(sessions);
    }
}
