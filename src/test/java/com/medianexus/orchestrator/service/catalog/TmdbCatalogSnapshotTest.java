package com.medianexus.orchestrator.service.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.medianexus.orchestrator.integration.tmdb.TmdbClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TmdbCatalogSnapshotTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicReference<TmdbProperties> active = new AtomicReference<>();
    private final AtomicReference<Runnable> afterSearch = new AtomicReference<>(() -> {});

    @BeforeEach
    void startUpstream() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().toString();
            requests.add(path + " " + exchange.getRequestHeaders().getFirst("Authorization"));
            String body;
            if (path.contains("/search/")) {
                body = "{\"results\":[{\"id\":42,\"poster_path\":\"/a.jpg\"},{\"id\":43,\"poster_path\":\"/b.jpg\"}]}";
                afterSearch.get().run();
            } else {
                body = path.contains("language=en-US")
                        ? "{\"overview\":\"Old fallback\"}" : "{}";
            }
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopUpstream() { server.stop(0); }

    @Test
    void movieSearchKeepsLanguageRequestAndPostersFromOneSnapshot() {
        TmdbProperties old = properties("old", "zh-CN", "en-US");
        TmdbProperties next = properties("new", "ja-JP", "fr-FR");
        TmdbProperties bound = properties("deployment", "zh-CN", "en-US");
        AtomicInteger captures = new AtomicInteger();
        bound.setSettingsResolver(() -> captures.getAndIncrement() == 0 ? old : next);
        var catalog = new TmdbMovieCatalogSearch(new TmdbClient(bound, new ObjectMapper()), bound);

        assertThat(catalog.searchMovies("example")).extracting(MovieCatalogItem::poster)
                .containsExactly("https://old.example/a.jpg", "https://old.example/b.jpg");
        assertThat(catalog.searchMovies("example")).extracting(MovieCatalogItem::poster)
                .containsExactly("https://new.example/a.jpg", "https://new.example/b.jpg");
        assertThat(requests).containsExactly(
                "/old/search/movie?query=example&language=zh-CN&include_adult=false Bearer old-token",
                "/new/search/movie?query=example&language=ja-JP&include_adult=false Bearer new-token");
    }

    @Test
    void seriesSearchKeepsDetailsFallbackAndPostersWhenSettingsChangeInFlight() {
        TmdbProperties old = properties("old", "zh-CN", "en-US");
        TmdbProperties next = properties("new", "ja-JP", "fr-FR");
        TmdbProperties bound = properties("deployment", "zh-CN", "en-US");
        active.set(old); bound.setSettingsResolver(active::get);
        afterSearch.set(() -> active.set(next));
        var catalog = new TmdbSeriesCatalogSearch(new TmdbClient(bound, new ObjectMapper()), bound);

        var items = catalog.searchSeries("example");
        assertThat(items).extracting(SeriesCatalogItem::poster)
                .containsExactly("https://old.example/a.jpg", "https://old.example/b.jpg");
        assertThat(items).extracting(SeriesCatalogItem::overview).containsExactly("Old fallback", "Old fallback");
        assertThat(requests).containsExactly(
                "/old/search/tv?query=example&language=zh-CN&include_adult=false Bearer old-token",
                "/old/tv/42?language=zh-CN Bearer old-token", "/old/tv/42?language=en-US Bearer old-token",
                "/old/tv/43?language=zh-CN Bearer old-token", "/old/tv/43?language=en-US Bearer old-token");
        requests.clear();
        catalog.getSeriesSeasons(42);
        assertThat(requests).containsExactly("/new/tv/42?language=ja-JP Bearer new-token");
    }

    private TmdbProperties properties(String version, String language, String fallback) {
        TmdbProperties p = new TmdbProperties();
        p.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/" + version);
        p.setApiToken(version + "-token"); p.setDefaultLanguage(language); p.setFallbackLanguage(fallback);
        p.setImageBaseUrl("https://" + version + ".example"); p.setTimeout(Duration.ofSeconds(2));
        return p;
    }
}
