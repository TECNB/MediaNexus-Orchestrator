package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.medianexus.orchestrator.config.PanSouProperties;
import com.medianexus.orchestrator.dto.resources.request.QuarkReleaseSearchRequest;
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
        PanSouProperties properties = new PanSouProperties();
        properties.setLinkCheckLimit(10);
        PanSouResourceSearchService service = new PanSouResourceSearchService(client, properties, authService);

        when(client.search(new PanSouSearchCommand("热辣滚烫", false))).thenReturn(new PanSouSearchResult(List.of(
                entry("https://pan.quark.cn/s/movie123?entry=source", "7788", "热辣滚烫 (2024) 4K", "tg:movies"),
                entry("https://pan.quark.cn/s/movie123?entry=other", "7788", "热辣滚烫 (2024)", "plugin:test"),
                entry("https://pan.quark.cn/s/drama456", "", "【短剧】热辣滚烫之丑女翻身（85集）", "tg:test"),
                entry("https://example.com/s/nope", "", "热辣滚烫", "invalid")
        )));
        when(client.checkLinks(anyList())).thenReturn(List.of(
                new PanSouLinkCheckResult(
                        "https://pan.quark.cn/s/movie123?pwd=7788",
                        "https://pan.quark.cn/s/movie123?pwd=7788",
                        "ok",
                        "链接有效"
                ),
                new PanSouLinkCheckResult(
                        "https://pan.quark.cn/s/drama456",
                        "https://pan.quark.cn/s/drama456",
                        "ok",
                        "链接有效"
                )
        ));

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
            assertThat(item.availability()).isEqualTo("OK");
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
    }

    private PanSouSearchEntry entry(String url, String password, String note, String source) {
        return new PanSouSearchEntry(url, password, note, "2026-08-23", source);
    }
}
