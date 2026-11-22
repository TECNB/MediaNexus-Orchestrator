package com.medianexus.orchestrator.controller;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.medianexus.orchestrator.common.exception.*;
import com.medianexus.orchestrator.dto.douban.DoubanChartContract.*;
import com.medianexus.orchestrator.integration.douban.DoubanChartClient;
import com.medianexus.orchestrator.mapper.UserMapper;
import com.medianexus.orchestrator.model.User;
import com.medianexus.orchestrator.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DoubanChartControllerTest {
    @Test void authenticatedMemberReadsButEveryMutationReturns403BeforeAccessingState() throws Exception {
        var auth = mock(AuthService.class);
        var store = mock(DoubanChartStore.class);
        var user = new User(); user.setId(1L); user.setRole("USER");
        when(auth.requireCurrentUser()).thenReturn(user);
        when(auth.requireAdminUser()).thenThrow(new BusinessException(ErrorCode.FORBIDDEN,"仅管理员可操作",HttpStatus.FORBIDDEN));
        when(store.board(ChartId.tv_hot)).thenReturn(new Board(ChartId.tv_hot,"热门电视剧",false,null,"NEVER",null,null,null,null,List.of()));
        var service = new DoubanChartService(store,mock(DoubanChartClient.class),mock(DoubanChartEligibility.class),
                mock(BgmWishlistTaskGateway.class),mock(BgmWishlistSessionLocks.class),auth,mock(UserMapper.class),mock(com.medianexus.orchestrator.service.OfflineIngestScheduler.class));
        var mvc = MockMvcBuilders.standaloneSetup(new DoubanChartController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        try {
            String base="/api/v1/douban-charts";
            mvc.perform(get(base+"/tv_hot")).andExpect(status().isOk()).andExpect(jsonPath("$.data.autoEnabled").value(false));
            clearInvocations(store);
            for (var request : List.of(post(base+"/tv_hot/refresh"),post(base+"/tv_hot/operations"),post(base+"/operations/unknown/stop"),
                    put(base+"/tv_hot/automation").content("{\"enabled\":true}"),
                    put(base+"/settings").content("{}"),put(base+"/tv_hot/candidates/1/exclusion").content("{\"excluded\":true}"),
                    put(base+"/tv_hot/candidates/1/identity").content("{\"mediaType\":\"SERIES\",\"tmdbId\":1,\"seasonNumber\":1}"))) {
                mvc.perform(request.contentType(MediaType.APPLICATION_JSON)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
            }
            verifyNoInteractions(store);
        } finally { service.close(); }
    }
}
