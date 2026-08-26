package com.medianexus.orchestrator.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.base.Ticker;
import com.medianexus.orchestrator.config.QasProperties;
import com.medianexus.orchestrator.integration.qas.QasClient;
import com.medianexus.orchestrator.integration.qas.QasClientException;
import com.medianexus.orchestrator.integration.qas.QasShareNode;
import com.medianexus.orchestrator.integration.qas.QasShareTree;
import com.medianexus.orchestrator.integration.quark.QuarkShareTreeClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class QuarkShareTreeServiceTest {

    @Test
    void reusesTreeForEquivalentShareUrlDuringCacheTtl() {
        QuarkShareTreeClient directClient = mock(QuarkShareTreeClient.class);
        QasClient qasClient = mock(QasClient.class);
        String firstUrl = "https://pan.quark.cn/s/share123#/list/share/0123456789abcdef0123456789abcdef";
        String secondUrl = "https://pan.quark.cn/s/share123/0123456789abcdef0123456789abcdef";
        when(directClient.inspectShare(firstUrl)).thenReturn(tree(firstUrl));
        QuarkShareTreeService service = new QuarkShareTreeService(
                directClient,
                qasClient,
                properties(),
                Ticker.systemTicker()
        );

        QasShareTree first = service.inspectShare(firstUrl);
        QasShareTree second = service.inspectShare(secondUrl);

        assertThat(first.entries()).hasSize(1);
        assertThat(second.sourceUrl()).isEqualTo(secondUrl);
        assertThat(second.entries()).isEqualTo(first.entries());
        verify(directClient, times(1)).inspectShare(firstUrl);
    }

    @Test
    void cachesSuccessfulQasFallback() {
        QuarkShareTreeClient directClient = mock(QuarkShareTreeClient.class);
        QasClient qasClient = mock(QasClient.class);
        String shareUrl = "https://pan.quark.cn/s/share123";
        when(directClient.inspectShare(shareUrl)).thenThrow(
                new QasClientException(QasClientException.Reason.UPSTREAM, "direct failed")
        );
        when(qasClient.inspectShare(shareUrl)).thenReturn(tree(shareUrl));
        QuarkShareTreeService service = new QuarkShareTreeService(
                directClient,
                qasClient,
                properties(),
                Ticker.systemTicker()
        );

        service.inspectShare(shareUrl);
        service.inspectShare(shareUrl);

        verify(directClient, times(1)).inspectShare(shareUrl);
        verify(qasClient, times(1)).inspectShare(shareUrl);
    }

    @Test
    void reloadsTreeAfterFiveMinuteTtl() {
        QuarkShareTreeClient directClient = mock(QuarkShareTreeClient.class);
        QasClient qasClient = mock(QasClient.class);
        MutableTicker ticker = new MutableTicker();
        String shareUrl = "https://pan.quark.cn/s/share123";
        when(directClient.inspectShare(shareUrl))
                .thenReturn(tree(shareUrl, "01.mkv"), tree(shareUrl, "02.mkv"));
        QuarkShareTreeService service = new QuarkShareTreeService(
                directClient,
                qasClient,
                properties(),
                ticker
        );

        assertThat(service.inspectShare(shareUrl).entries().get(0).name()).isEqualTo("01.mkv");
        ticker.advance(Duration.ofMinutes(5).plusMillis(1));
        assertThat(service.inspectShare(shareUrl).entries().get(0).name()).isEqualTo("02.mkv");
        verify(directClient, times(2)).inspectShare(shareUrl);
    }

    @Test
    void separatesCacheEntriesByPasswordWithoutExposingPassword() {
        String first = QuarkShareTreeService.cacheKey("https://pan.quark.cn/s/share123?pwd=1234");
        String second = QuarkShareTreeService.cacheKey("https://pan.quark.cn/s/share123?pwd=5678");

        assertThat(first).isNotEqualTo(second);
        assertThat(first).doesNotContain("1234");
        assertThat(second).doesNotContain("5678");
    }

    private QasProperties properties() {
        QasProperties properties = new QasProperties();
        properties.setShareTreeCacheTtl(Duration.ofMinutes(5));
        return properties;
    }

    private QasShareTree tree(String shareUrl) {
        return tree(shareUrl, "01.mkv");
    }

    private QasShareTree tree(String shareUrl, String name) {
        return new QasShareTree(
                shareUrl,
                List.of(new QasShareNode("fid", name, false, "video", 1, List.of()))
        );
    }

    private static final class MutableTicker extends Ticker {
        private final AtomicLong nanos = new AtomicLong();

        @Override
        public long read() {
            return nanos.get();
        }

        private void advance(Duration duration) {
            nanos.addAndGet(duration.toNanos());
        }
    }
}
