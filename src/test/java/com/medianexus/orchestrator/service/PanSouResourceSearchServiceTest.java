package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.dto.resources.request.QuarkReleaseLinkCheckItemRequest;
import com.medianexus.orchestrator.dto.resources.request.QuarkReleaseLinkCheckRequest;
import com.medianexus.orchestrator.dto.resources.request.QuarkReleaseSearchRequest;
import com.medianexus.orchestrator.dto.resources.response.QuarkReleaseLinkCheckResponse;
import com.medianexus.orchestrator.dto.resources.response.QuarkReleaseSearchResponse;
import com.medianexus.orchestrator.integration.pansou.PanSouClient;
import com.medianexus.orchestrator.integration.pansou.PanSouLinkCheckResult;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchCommand;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchEntry;
import com.medianexus.orchestrator.integration.pansou.PanSouSearchResult;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PanSouResourceSearchServiceTest {

    @Test
    void keepsLooseCandidatesWhileCanonicalizingDeduplicatingAndFlaggingConflicts() {
        PanSouClient client = mock(PanSouClient.class);
        AuthService authService = mock(AuthService.class);
        PanSouResourceSearchService service = new PanSouResourceSearchService(client, authService);

        when(client.search(new PanSouSearchCommand("热辣滚烫", false))).thenReturn(new PanSouSearchResult(List.of(
                entry("https://pan.quark.cn/s/movie123?entry=source", "7788", "热辣滚烫 (2024) 4K", "tg:movies"),
                entry("https://pan.quark.cn/s/movie123?entry=other", "7788", "热辣滚烫 (2024)", "plugin:test"),
                entry("https://pan.quark.cn/s/drama456", "", "【短剧】热辣滚烫之丑女翻身（85集）", "tg:test"),
                entry("https://example.com/s/nope", "", "热辣滚烫", "invalid")
        )));
        QuarkReleaseSearchResponse response = service.search(new QuarkReleaseSearchRequest(
                "MOVIE",
                "热辣滚烫",
                "YOLO",
                2024,
                null,
                8888,
                false
        ));

        assertThat(response.query()).isEqualTo("热辣滚烫");
        assertThat(response.items()).hasSize(2);
        assertThat(response.items().get(0)).satisfies(item -> {
            assertThat(item.shareUrl()).isEqualTo("https://pan.quark.cn/s/movie123?pwd=7788");
            assertThat(item.availability()).isEqualTo("UNCHECKED");
            assertThat(item.relevance()).isEqualTo("STRONG");
            assertThat(item.tags()).contains("4K");
        });
        assertThat(response.items().get(1)).satisfies(item -> {
            assertThat(item.title()).contains("短剧");
            assertThat(item.relevance()).isEqualTo("CONFLICT");
            assertThat(item.conflicts()).contains("目标是电影，但候选标题显示为短剧");
        });
        assertThat(response.warnings()).contains("已忽略 1 条非 pan.quark.cn 分享链接");

        ArgumentCaptor<PanSouSearchCommand> command = ArgumentCaptor.forClass(PanSouSearchCommand.class);
        verify(client).search(command.capture());
        assertThat(command.getValue().keyword()).isEqualTo("热辣滚烫");
        verify(client, never()).checkLinks(anyList());
    }

    @Test
    void usesTheHighestScoringDuplicateAsTheDisplayedCandidate() {
        PanSouClient client = mock(PanSouClient.class);
        AuthService authService = mock(AuthService.class);
        PanSouResourceSearchService service = new PanSouResourceSearchService(client, authService);
        when(client.search(new PanSouSearchCommand("热辣滚烫", false))).thenReturn(new PanSouSearchResult(List.of(
                entry(
                        "https://pan.quark.cn/s/movie123",
                        "",
                        "一条很长但与目标电影冲突的短剧资源标题（共85集）",
                        "tg:drama"
                ),
                entry("https://pan.quark.cn/s/movie123", "7788", "热辣滚烫 (2024) 4K", "tg:movies")
        )));

        QuarkReleaseSearchResponse response = service.search(new QuarkReleaseSearchRequest(
                "MOVIE", "热辣滚烫", "YOLO", 2024, null, 8888, false
        ));

        assertThat(response.items()).singleElement().satisfies(item -> {
            assertThat(item.title()).isEqualTo("热辣滚烫 (2024) 4K");
            assertThat(item.relevance()).isEqualTo("STRONG");
            assertThat(item.tags()).contains("4K");
            assertThat(item.shareUrl()).isEqualTo("https://pan.quark.cn/s/movie123?pwd=7788");
            assertThat(item.source()).contains("tg:drama", "tg:movies");
        });
    }

    @Test
    void checksOnlyRequestedCandidatesAndMapsPanSouState() {
        PanSouClient client = mock(PanSouClient.class);
        AuthService authService = mock(AuthService.class);
        PanSouResourceSearchService service = new PanSouResourceSearchService(client, authService);
        when(client.search(new PanSouSearchCommand("热辣滚烫", false))).thenReturn(new PanSouSearchResult(List.of(
                entry("https://pan.quark.cn/s/movie123", "7788", "热辣滚烫 (2024)", "tg:movies")
        )));

        QuarkReleaseSearchResponse search = service.search(new QuarkReleaseSearchRequest(
                "MOVIE", "热辣滚烫", "YOLO", 2024, null, 8888, false
        ));
        String candidateId = search.items().get(0).id();
        when(client.checkLinks(anyList())).thenReturn(List.of(new PanSouLinkCheckResult(
                "https://pan.quark.cn/s/movie123?pwd=7788",
                "https://pan.quark.cn/s/movie123?pwd=7788",
                "bad",
                "链接已失效"
        )));

        QuarkReleaseLinkCheckResponse checked = service.checkLinks(new QuarkReleaseLinkCheckRequest(
                "view-1",
                List.of(new QuarkReleaseLinkCheckItemRequest(
                        candidateId,
                        "https://pan.quark.cn/s/movie123?pwd=7788"
                ))
        ));

        assertThat(checked.viewToken()).isEqualTo("view-1");
        assertThat(checked.items()).singleElement().satisfies(item -> {
            assertThat(item.id()).isEqualTo(candidateId);
            assertThat(item.availability()).isEqualTo("BAD");
            assertThat(item.availabilitySummary()).isEqualTo("链接已失效");
        });
        ArgumentCaptor<List<com.medianexus.orchestrator.integration.pansou.PanSouLinkCheckRequest>> checks =
                ArgumentCaptor.forClass(List.class);
        verify(client).checkLinks(checks.capture());
        assertThat(checks.getValue()).singleElement().satisfies(item ->
                assertThat(item.url()).isEqualTo("https://pan.quark.cn/s/movie123?pwd=7788"));
    }

    private PanSouSearchEntry entry(String url, String password, String note, String source) {
        return new PanSouSearchEntry(url, password, note, "2026-08-23", source);
    }
}
