package com.medianexus.orchestrator.integration.javdb;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class JavdbClientTest {

    private final JavdbClient client = new JavdbClient();

    @Test
    void parsesRankingCardsWithStableRanksAndMagnetBadges() {
        String html = """
                <div class="movie-list">
                  <div class="item"><a class="box" href="/v/one" title="ABC-123 / title">
                    <span class="video-title"><strong>ABC-123</strong></span>
                    <div class="meta">2024-01-02</div><span>含磁</span>
                  </a></div>
                  <div class="item"><a class="box" href="/v/two">
                    <span class="video-title"><strong>XYZ 99</strong></span>
                    <div class="meta">2024-02-03</div>
                  </a></div>
                </div>
                """;

        @SuppressWarnings("unchecked")
        List<JavdbRankingMovie> movies = (List<JavdbRankingMovie>) ReflectionTestUtils.invokeMethod(
                client, "parseRanking", html, "daily", "https://javdb.com/rankings/movies?p=daily&t=censored"
        );

        assertThat(movies).extracting(JavdbRankingMovie::code)
                .containsExactly("ABC-123", "XYZ-99");
        assertThat(movies.get(0).rank()).isEqualTo(1);
        assertThat(movies.get(0).hasMagnetBadge()).isTrue();
        assertThat(movies.get(1).releaseDate()).isEqualTo("2024-02-03");
    }

    @Test
    void parsesAndDeduplicatesMagnetsWhileKeepingFilenameLabels() {
        String html = """
                <section id="magnets-content">
                  <a href="magnet:?xt=urn:btih:ABCDEF1234567890&amp;dn=%5B中字%5D%20ABC-123%20UC">第一条</a>
                  <a data-clipboard-text="magnet:?xt=urn:btih:abcdef1234567890&amp;dn=duplicate">重复</a>
                  <a href="magnet:?xt=urn:btih:9876543210&amp;dn=普通版本">第三条</a>
                </section>
                """;

        @SuppressWarnings("unchecked")
        List<JavdbMagnet> magnets = (List<JavdbMagnet>) ReflectionTestUtils.invokeMethod(
                client, "parseMagnets", html
        );

        assertThat(magnets).hasSize(2);
        assertThat(magnets.get(0).infohash()).isEqualTo("abcdef1234567890");
        assertThat(magnets.get(0).hasSubtitle()).isTrue();
        assertThat(magnets.get(0).isCracked()).isTrue();
        assertThat(magnets.get(0).detectionSource()).isEqualTo("filename_rule");
        assertThat(magnets.get(1).infohash()).isEqualTo("9876543210");
    }
}
