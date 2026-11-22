package com.medianexus.orchestrator.integration.javdb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JavdbMetadataSourceTest {

    private static final String DETAIL = """
            <html><body>
              <h2 class="title is-4"><strong>JQJ-007</strong><span class="current-title">測試作品</span></h2>
              <div class="video-meta-panel"><img class="video-cover" src="https://c0.jdbstatic.com/covers/one.jpg">
                <nav class="panel movie-panel-info">
                  <div class="panel-block"><strong>番號:</strong><span class="value">JQJ-007</span></div>
                  <div class="panel-block"><strong>日期:</strong><span class="value">2025-01-02</span></div>
                  <div class="panel-block"><strong>導演:</strong><span class="value"><a href="/directors/a">導演甲</a></span></div>
                  <div class="panel-block"><strong>片商:</strong><span class="value"><a href="/makers/a">片商甲</a></span></div>
                  <div class="panel-block"><strong>發行:</strong><span class="value"><a href="/publishers/a">片商甲</a><a href="/publishers/b">發行乙</a></span></div>
                  <div class="panel-block"><strong>評分:</strong><span class="value"><span class="score"><i class="icon-star"></i> 4.35 分, 由27人評價</span></span></div>
                  <div class="panel-block"><strong>類別:</strong><span class="value"><a href="/tags/a">劇情</a>, <a href="/tags/b">高清</a></span></div>
                  <div class="panel-block"><strong>演員:</strong><span class="value"><a href="/actors/a">演員甲</a><strong class="symbol female">♀</strong><a href="/actors/b">演員乙</a></span></div>
                </nav>
              </div>
            </body></html>
            """;

    private HttpServer server;
    private JavdbMetadataSource source;
    private String searchBody;
    private String detailBody;
    private int status;
    private String location;
    private final List<String> requests = new ArrayList<>();
    private final List<String> cookies = new ArrayList<>();

    @BeforeEach
    void startServer() throws Exception {
        searchBody = "<div class='movie-list'>" + card("JQJ-007", "/v/one") + "</div>";
        detailBody = DETAIL;
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestURI().toString());
            cookies.add(exchange.getRequestHeaders().getFirst("Cookie"));
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
            byte[] body = (exchange.getRequestURI().getPath().equals("/search") ? searchBody : detailBody)
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        source = new JavdbMetadataSource(URI.create("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void selectsOnlyExactNumberThenVerifiesDetailAndExtractsPanelClaims() {
        searchBody = "<div class='movie-list'>" + card("JQJ-0070", "/v/fuzzy")
                + card("ｊｑｊ７", "/v/one") + "</div>";
        JavdbMetadataSnapshot snapshot = source.fetch(" jqj0007 ", null, "session=test");
        assertThat(snapshot.code()).isEqualTo("JQJ-007");
        assertThat(snapshot.sourceId()).isEqualTo("one");
        assertThat(snapshot.detailUrl()).isEqualTo("https://javdb.com/v/one");
        assertThat(snapshot.title()).isEqualTo("測試作品");
        assertThat(snapshot.coverUrl()).isEqualTo("https://c0.jdbstatic.com/covers/one.jpg");
        assertThat(snapshot.releaseDate()).isEqualTo("2025-01-02");
        assertThat(snapshot.actors()).containsExactly("演員甲", "演員乙");
        assertThat(snapshot.directors()).containsExactly("導演甲");
        assertThat(snapshot.genres()).containsExactly("劇情", "高清");
        assertThat(snapshot.studios()).containsExactly("片商甲", "發行乙");
        assertThat(snapshot.communityRating()).isEqualTo(8.7);
        assertThat(requests).containsExactly("/search?q=JQJ-007&f=all", "/v/one");
        assertThat(cookies).containsExactly("session=test", "session=test");
    }

    @Test
    void suppliedDetailSkipsSearchButStillChecksActualNumber() {
        assertThat(source.fetch("JQJ-007", "https://javdb.com/v/one", "test").sourceId()).isEqualTo("one");
        assertThat(requests).containsExactly("/v/one");
        detailBody = DETAIL.replace("<span class=\"value\">JQJ-007</span>", "<span class=\"value\">ABC-123</span>");
        assertFailure("JQJ-007", "https://javdb.com/v/one", JavdbClientException.Reason.PARSE);
    }

    @Test
    void verifiesSearchResultDetailRatherThanTrustingCard() {
        detailBody = DETAIL.replace("<span class=\"value\">JQJ-007</span>", "<span class=\"value\">ABC-123</span>");
        assertFailure("JQJ-007", null, JavdbClientException.Reason.PARSE);
        assertThat(requests).hasSize(2);
    }

    @Test
    void rejectsAmbiguousExactCardsWithoutFetchingAnyDetail() {
        searchBody = "<div class='movie-list'>" + card("JQJ-007", "/v/one")
                + card("jqj7", "/v/two") + "</div>";
        assertFailure("JQJ-007", null, JavdbClientException.Reason.PARSE);
        assertThat(requests).containsExactly("/search?q=JQJ-007&f=all");
    }

    @Test
    void doesNotUseTitlesOrFirstFuzzyCandidateAsNumber() {
        searchBody = "<div class='movie-list'>" + card("JQJ-0070", "/v/one")
                + "<a class='box' href='/v/two' title='JQJ-007'><span class='video-title'>JQJ-007</span></a></div>";
        assertFailure("JQJ-007", null, JavdbClientException.Reason.NOT_FOUND);
        assertThat(requests).hasSize(1);
    }

    @Test
    void normalizesFc2IdentityAndAllowsMissingOptionalClaims() {
        detailBody = """
                <h2 class="title"><span class="current-title">FC2 title</span></h2>
                <div class="movie-panel-info"><div class="panel-block"><strong>番号：</strong>
                  <span class="value">FC2-4828968</span></div></div>
                """;
        JavdbMetadataSnapshot snapshot = source.fetch("fc2_ppv_4828968", "/v/fc2", "test");
        assertThat(snapshot.code()).isEqualTo("FC2-PPV-4828968");
        assertThat(snapshot.coverUrl()).isNull();
        assertThat(snapshot.releaseDate()).isNull();
        assertThat(snapshot.communityRating()).isNull();
        assertThat(snapshot.actors()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://javdb.com/v/one", "https://javdb.com.evil.test/v/one", "https://evil.test/v/one",
            "https://user@javdb.com/v/one", "https://javdb.com:444/v/one", "https://javdb.com/v/one?q=x",
            "https://javdb.com/v/one#x", "https://javdb.com/v/%6fne", "https://javdb.com/v/one/extra",
            "https://javdb.com/rankings", "https://[", "", "https://javdb.com/v/one\\evil"
    })
    void rejectsUntrustedOrMalformedSuppliedUrlsBeforeRequest(String url) {
        assertFailure("JQJ-007", url, JavdbClientException.Reason.PARSE);
        assertThat(requests).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.test/v/one", "http://javdb.com/v/one", "/v/%6fne", "javascript:alert(1)"})
    void validatesExactCardDestinationBeforeFetching(String url) {
        searchBody = "<div class='movie-list'>" + card("JQJ-007", url) + "</div>";
        assertFailure("JQJ-007", null, JavdbClientException.Reason.PARSE);
        assertThat(requests).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://jdbstatic.com.evil.test/one.jpg", "https://evil.test/one.jpg", "http://c0.jdbstatic.com/one.jpg",
            "https://user@c0.jdbstatic.com/one.jpg", "https://c0.jdbstatic.com:444/one.jpg", "https://["
    })
    void rejectsUntrustedCoverClaims(String cover) {
        detailBody = DETAIL.replace("https://c0.jdbstatic.com/covers/one.jpg", cover);
        assertFailure("JQJ-007", "/v/one", JavdbClientException.Reason.PARSE);
    }

    @Test
    void acceptsProtocolRelativeHttpsStaticCover() {
        detailBody = DETAIL.replace("https://c0.jdbstatic.com", "//c0.jdbstatic.com");
        assertThat(source.fetch("JQJ-007", "/v/one", "test").coverUrl())
                .isEqualTo("https://c0.jdbstatic.com/covers/one.jpg");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.test/v/one", "http://javdb.com/v/one", "https://["})
    void neverFollowsUnsafeRedirectOrForwardsCookie(String redirect) {
        status = 302;
        location = redirect;
        assertFailure("JQJ-007", "/v/one", JavdbClientException.Reason.PARSE);
        assertThat(requests).containsExactly("/v/one");
    }

    @Test
    void classifiesLoginRedirectAndLoginPageAsAuthenticationFailure() {
        status = 302;
        location = "/login";
        assertFailure("JQJ-007", "/v/one", JavdbClientException.Reason.AUTHENTICATION);
        status = 200;
        location = null;
        detailBody = "<form action='/login'><input type='password'></form>";
        assertFailure("JQJ-007", "/v/one", JavdbClientException.Reason.AUTHENTICATION);
    }

    @Test
    void rejectsEvenSameHostRedirectInsteadOfTrustingItsIdentity() {
        status = 302;
        location = "/v/two";
        assertFailure("JQJ-007", "/v/one", JavdbClientException.Reason.UPSTREAM);
        assertThat(requests).containsExactly("/v/one");
    }

    @ParameterizedTest
    @ValueSource(strings = {"6.00 分, 由27人評價", "NaN 分", "-1 分", "4.35 stars"})
    void rejectsMalformedOrOutOfRangeScores(String rating) {
        detailBody = DETAIL.replace("4.35 分, 由27人評價", rating);
        assertFailure("JQJ-007", "/v/one", JavdbClientException.Reason.PARSE);
    }

    @Test
    void rejectsMissingNumberEvenIfTitleMatchesAndRejectsInvalidDate() {
        detailBody = DETAIL.replace("<strong>番號:</strong>", "<strong>其他:</strong>");
        assertFailure("JQJ-007", "/v/one", JavdbClientException.Reason.PARSE);
        detailBody = DETAIL.replace("2025-01-02", "2025-02-30");
        assertFailure("JQJ-007", "/v/one", JavdbClientException.Reason.PARSE);
    }

    @Test
    void rejectsInvalidCookieAndCodeWithoutNetworkOrSensitiveDiagnostics() {
        assertThatThrownBy(() -> source.fetch("JQJ-007", "/v/one", "secret\r\nCookie: bad"))
                .isInstanceOf(JavdbClientException.class).hasMessageNotContaining("secret");
        assertFailure("JQJ-007-C", "/v/one", JavdbClientException.Reason.PARSE);
        assertThat(requests).isEmpty();
    }

    @Test
    void testTransportCannotBeUsedAsArbitraryRemoteOrigin() {
        assertThatThrownBy(() -> new JavdbMetadataSource(URI.create("http://evil.test:8080")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertFailure(String code, String url, JavdbClientException.Reason reason) {
        assertThatThrownBy(() -> source.fetch(code, url, "test"))
                .isInstanceOfSatisfying(JavdbClientException.class, failure -> assertThat(failure.reason()).isEqualTo(reason));
    }

    private static String card(String number, String url) {
        return "<div class='item'><a class='box' href='" + url + "'><span class='video-title'><strong>"
                + number + "</strong> test title</span></a></div>";
    }
}
