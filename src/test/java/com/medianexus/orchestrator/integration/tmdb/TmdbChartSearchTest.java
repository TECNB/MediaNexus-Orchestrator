package com.medianexus.orchestrator.integration.tmdb;

import static org.junit.jupiter.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.TmdbProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class TmdbChartSearchTest {
    @Test void isolatesTypesReadsEveryPageAndRefusesBudgetOverflowOrChangingTotals() throws Exception {
        var mode = new AtomicReference<>("normal");
        List<String> paths = new ArrayList<>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/3/search/",exchange -> {
            String uri = exchange.getRequestURI().toString(); paths.add(uri);
            int page = uri.endsWith("page=2") ? 2 : 1;
            String response = switch (mode.get()) {
                case "budget" -> "{\"page\":1,\"total_pages\":6,\"total_results\":101,\"results\":[{\"id\":1}]}";
                case "changing" -> "{\"page\":"+page+",\"total_pages\":2,\"total_results\":"+(page==1?2:3)+",\"results\":[{\"id\":"+page+"}]}";
                default -> "{\"page\":"+page+",\"total_pages\":2,\"total_results\":2,\"results\":[{\"id\":"+page+"}]}";
            };
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var config = new TmdbProperties(); config.setBaseUrl("http://127.0.0.1:"+server.getAddress().getPort()+"/3");
            config.setApiToken("fixture"); config.setTimeout(Duration.ofSeconds(2));
            var client = new TmdbClient(config,new ObjectMapper());
            assertEquals(2,client.searchChartTitles("测试","MOVIE").size());
            assertTrue(paths.stream().allMatch(path -> path.startsWith("/3/search/movie?")));
            paths.clear(); assertEquals(2,client.searchChartTitles("测试","SERIES").size());
            assertTrue(paths.stream().allMatch(path -> path.startsWith("/3/search/tv?")));
            mode.set("budget"); assertThrows(TmdbClientException.class,()->client.searchChartTitles("测试","SERIES"));
            mode.set("changing"); assertThrows(TmdbClientException.class,()->client.searchChartTitles("测试","MOVIE"));
        } finally { server.stop(0); }
    }
}
