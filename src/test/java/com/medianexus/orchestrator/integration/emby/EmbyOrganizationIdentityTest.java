package com.medianexus.orchestrator.integration.emby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbyOrganizationIdentityTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private EmbyClient client;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("test");
        properties.setTimeout(Duration.ofSeconds(2));
        client = new EmbyClient(properties, mapper);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void scansAllPagesWithoutProviderFilterAndReadsExactIdsPreservingExtraMediaPaths() {
        List<Map<String, String>> requests = new ArrayList<>();
        ObjectNode selected = movie();
        selected.withArray("MediaSources").addObject().put("Path", "/extra.strm");
        server.createContext("/Items", exchange -> {
            Map<String, String> query = query(exchange);
            requests.add(query);
            if (query.containsKey("Ids")) {
                respond(exchange, "{\"Items\":[" + selected + "],\"TotalRecordCount\":1}");
            } else if ("0".equals(query.get("StartIndex"))) {
                respond(exchange, "{\"Items\":[{\"Id\":\"40845\",\"Type\":\"Movie\",\"Path\":\"/MOVIE/消失的人(2026)/movie.strm\"}],\"TotalRecordCount\":2}");
            } else {
                respond(exchange, "{\"Items\":[{\"Id\":\"other\",\"Type\":\"Movie\",\"Path\":\"/elsewhere.strm\"}],\"TotalRecordCount\":2}");
            }
        });
        server.start();

        var snapshots = client.organizationMoviesAtPaths("library", Set.of("/MOVIE/消失的人(2026)/movie.strm"));

        assertThat(snapshots).singleElement().satisfies(snapshot -> {
            assertThat(snapshot.itemId()).isEqualTo("40845");
            assertThat(snapshot.tmdbId()).isEqualTo("999");
            assertThat(snapshot.repairAllowed()).isTrue();
            assertThat(snapshot.mediaPaths()).containsExactlyInAnyOrder("/MOVIE/消失的人(2026)/movie.strm", "/extra.strm");
        });
        assertThat(requests).hasSize(3);
        assertThat(requests.get(0)).containsEntry("ParentId", "library")
                .containsEntry("Recursive", "true").containsEntry("IncludeItemTypes", "Movie")
                .containsEntry("GroupItemsIntoCollections", "false").containsEntry("SortBy", "Id")
                .containsEntry("StartIndex", "0").doesNotContainKeys("AnyProviderIdEquals", "Path", "Ids");
        assertThat(requests.get(1)).containsEntry("StartIndex", "1");
        assertThat(requests.get(2)).containsEntry("Ids", "40845").doesNotContainKeys("ParentId", "AnyProviderIdEquals");
        assertThat(Set.of(requests.get(2).get("Fields").split(","))).contains(
                "Type", "ProviderIds", "Path", "MediaSources", "IsLocked", "LockedFields", "DateLastSaved", "Etag", "ParentId");
    }

    @Test
    void rejectsIncompleteChangingDuplicateOrUnknownScanResponses() {
        AtomicReference<String> response = new AtomicReference<>();
        server.createContext("/Items", exchange -> respond(exchange, response.get()));
        server.start();
        String row = "{\"Id\":\"x\",\"Type\":\"Movie\",\"Path\":\"/x\"}";
        for (String invalid : List.of("{}", "{\"Items\":[]}",
                "{\"Items\":[],\"TotalRecordCount\":1}",
                "{\"Items\":[],\"TotalRecordCount\":-1}",
                "{\"Items\":[" + row + "],\"TotalRecordCount\":0}",
                "{\"Items\":[" + row + "," + row + "],\"TotalRecordCount\":2}",
                "{\"Items\":[{\"Id\":\"x\",\"Type\":\"Movie\"}],\"TotalRecordCount\":1}",
                "{\"Items\":[{\"Id\":\"x\",\"Type\":\"Video\",\"Path\":\"/x\"}],\"TotalRecordCount\":1}",
                "{\"Items\":[" + row + "],\"TotalRecordCount\":2}")) {
            response.set(invalid);
            assertThatThrownBy(() -> client.organizationMoviesAtPaths("library", Set.of("/target")))
                    .as(invalid).isInstanceOf(EmbyClientException.class);
        }
        server.removeContext("/Items");
        server.createContext("/Items", exchange -> respond(exchange,
                "0".equals(query(exchange).get("StartIndex"))
                        ? "{\"Items\":[" + row + "],\"TotalRecordCount\":2}"
                        : "{\"Items\":[],\"TotalRecordCount\":1}"));
        assertThatThrownBy(() -> client.organizationMoviesAtPaths("library", Set.of("/target")))
                .isInstanceOf(EmbyClientException.class);
    }

    @Test void unrelatedDuplicatePathsDoNotBlockAndTargetDuplicatesRemainObservable() {
        server.createContext("/Items", exchange -> {
            var q = query(exchange);
            if (q.containsKey("Ids")) {
                var selected = movie(); selected.put("Id", q.get("Ids"));
                respond(exchange, "{\"Items\":[" + selected + "],\"TotalRecordCount\":1}");
            } else respond(exchange, "{\"Items\":[{\"Id\":\"a\",\"Type\":\"Movie\",\"Path\":\"/MOVIE/消失的人(2026)/movie.strm\"},"
                    + "{\"Id\":\"b\",\"Type\":\"Movie\",\"Path\":\"/MOVIE/消失的人(2026)/movie.strm\"},"
                    + "{\"Id\":\"c\",\"Type\":\"Movie\",\"Path\":\"/elsewhere\"},"
                    + "{\"Id\":\"d\",\"Type\":\"Movie\",\"Path\":\"/elsewhere\"}],\"TotalRecordCount\":4}");
        });
        server.start();
        assertThat(client.organizationMoviesAtPaths("library", Set.of("/MOVIE/消失的人(2026)/movie.strm"))).hasSize(2);
    }

    @Test
    void exactIdentityIsImmutableAndFailsClosedForEveryMissingOrLockedProof() {
        AtomicReference<JsonNode> item = new AtomicReference<>(movie());
        AtomicReference<String> responseOverride = new AtomicReference<>();
        server.createContext("/Items", exchange -> respond(exchange, responseOverride.get() == null
                ? "{\"Items\":[" + item.get() + "]}" : responseOverride.get()));
        server.start();
        var original = client.organizationMovieIdentity("40845");
        assertThat(original).isEqualTo(client.organizationMovieIdentity("40845"));
        assertThat(original.repairAllowed()).isTrue();
        assertThatThrownBy(() -> original.mediaPaths().add("/mutate")).isInstanceOf(UnsupportedOperationException.class);
        Set<String> mutable = new HashSet<>(original.mediaPaths());
        var copied = new EmbyMovieIdentitySnapshot(original.itemId(), original.path(), original.tmdbId(),
                original.year(), mutable, true, original.revisionEvidence());
        mutable.clear();
        assertThat(copied).isEqualTo(original);
        for (String missing : List.of("ProviderIds", "MediaSources", "IsLocked", "LockedFields", "Etag")) {
            ObjectNode incomplete = movie();
            incomplete.remove(missing);
            item.set(incomplete);
            assertThat(client.organizationMovieIdentity("40845").repairAllowed()).as(missing).isFalse();
        }
        List<ObjectNode> unsafe = new ArrayList<>();
        unsafe.add(movie().put("IsLocked", true));
        unsafe.add(movie().put("IsLocked", "false"));
        unsafe.add(movie().put("LockedFields", ""));
        unsafe.add(movie().put("Etag", " "));
        ObjectNode locked = movie(); locked.withArray("LockedFields").add("Name"); unsafe.add(locked);
        ObjectNode noSources = movie(); noSources.withArray("MediaSources").removeAll(); unsafe.add(noSources);
        ObjectNode missingPath = movie(); missingPath.withArray("MediaSources").addObject(); unsafe.add(missingPath);
        ObjectNode invalidTmdb = movie(); ((ObjectNode) invalidTmdb.get("ProviderIds")).put("Tmdb", "unknown"); unsafe.add(invalidTmdb);
        ObjectNode conflictingTmdb = movie(); ((ObjectNode) conflictingTmdb.get("ProviderIds")).put("tmdb", "123"); unsafe.add(conflictingTmdb);
        for (ObjectNode invalid : unsafe) {
            item.set(invalid);
            assertThat(client.organizationMovieIdentity("40845").repairAllowed()).as(invalid.toString()).isFalse();
        }
        ObjectNode settings = movie();
        settings.remove("IsLocked");
        settings.put("LockData", false);
        item.set(settings);
        assertThat(client.organizationMovieIdentity("40845").repairAllowed()).isTrue();
        settings.put("LockData", true);
        assertThat(client.organizationMovieIdentity("40845").repairAllowed()).isFalse();
        settings.put("LockData", "false");
        assertThat(client.organizationMovieIdentity("40845").repairAllowed()).isFalse();
        item.set(movie().put("LockData", true));
        assertThat(client.organizationMovieIdentity("40845").repairAllowed()).isFalse();
        item.set(movie().put("LockData", "false"));
        assertThat(client.organizationMovieIdentity("40845").repairAllowed()).isFalse();
        settings.put("LockData", false).put("IsLocked", true);
        item.set(settings);
        assertThat(client.organizationMovieIdentity("40845").repairAllowed()).isFalse();
        settings.remove("IsLocked");
        settings.remove("LockedFields");
        item.set(settings);
        assertThat(client.organizationMovieIdentity("40845").repairAllowed()).isFalse();
        ObjectNode saved = movie(); saved.remove("Etag"); saved.put("DateLastSaved", "2026-01-01T00:00:00Z");
        item.set(saved);
        assertThat(client.organizationMovieIdentity("40845").repairAllowed()).isTrue();
        assertThat(client.organizationMovieIdentity("40845")).isNotEqualTo(original);
        for (String invalid : List.of("{\"Items\":[]}", "{\"Items\":[" + movie() + "," + movie() + "]}",
                "{\"Items\":[" + movie().put("Id", "different") + "]}",
                "{\"Items\":[" + movie().put("Type", "Video") + "]}",
                "{\"Items\":[" + movie().put("Path", " ") + "]}")) {
            responseOverride.set(invalid);
            assertThatThrownBy(() -> client.organizationMovieIdentity("40845")).isInstanceOf(EmbyClientException.class);
        }
    }

    @Test
    void rejectsPathChangingBetweenScanAndExactIdentityRead() {
        server.createContext("/Items", exchange -> respond(exchange, query(exchange).containsKey("Ids")
                ? "{\"Items\":[" + movie().put("Path", "/changed") + "]}"
                : "{\"Items\":[" + movie() + "],\"TotalRecordCount\":1}"));
        server.start();
        assertThatThrownBy(() -> client.organizationMoviesAtPaths("library", Set.of(movie().path("Path").asText())))
                .isInstanceOf(EmbyClientException.class);
    }

    @Test
    void selectsOnlyUniqueExactProviderCandidateWithExactYearAndOfficialRequest() throws IOException {
        AtomicReference<String> response = new AtomicReference<>();
        AtomicReference<JsonNode> request = new AtomicReference<>();
        server.createContext("/Items/RemoteSearch/Movie", exchange -> {
            assertThat(exchange.getRequestMethod()).isEqualTo("POST");
            request.set(mapper.readTree(exchange.getRequestBody()));
            respond(exchange, response.get());
        });
        server.start();
        String exact = "{\"Name\":\"消失的人\",\"ProviderIds\":{\"Tmdb\":\"1658653\",\"Imdb\":\"tt123\"},\"ProductionYear\":2026,\"SearchProviderName\":\"TheMovieDb\"}";
        String wrong = exact.replace("1658653", "999");
        response.set("[" + wrong + "," + exact + "]");
        var candidate = client.findOrganizationMovieCandidate("40845", 1658653, "消失的人", 2026);
        assertThat(candidate.providerIds()).containsEntry("Tmdb", "1658653");
        assertThat(candidate.raw()).isEqualTo(mapper.readTree(exact));
        assertThat(request.get().path("ItemId").asLong()).isEqualTo(40845);
        assertThat(request.get().path("IncludeDisabledProviders").isBoolean()).isTrue();
        assertThat(request.get().path("IncludeDisabledProviders").asBoolean()).isFalse();
        JsonNode search = request.get().path("SearchInfo");
        assertThat(search.path("ProviderIds")).isEqualTo(mapper.readTree("{\"Tmdb\":\"1658653\"}"));
        assertThat(search.path("Name").asText()).isEqualTo("消失的人");
        assertThat(search.path("Year").asInt()).isEqualTo(2026);
        assertThat(search.path("MetadataLanguage").asText()).isEqualTo("zh-CN");
        for (String invalid : List.of("{}", "[]", "[" + wrong + "]", "[" + exact + "," + exact + "]",
                "[" + exact.replace("2026", "2025") + "]",
                "[" + exact.replace(",\"ProductionYear\":2026", "") + "]",
                "[" + exact.replace("\"ProductionYear\":2026", "\"ProductionYear\":\"2026\"") + "]",
                "[" + exact.replace("\"Imdb\":\"tt123\"", "\"tmdb\":\"999\"") + "]")) {
            response.set(invalid);
            assertThatThrownBy(() -> client.findOrganizationMovieCandidate("40845", 1658653, "消失的人", 2026))
                    .as(invalid).isInstanceOf(EmbyClientException.class);
        }
        response.set("[" + exact.replace(",\"ProductionYear\":2026", "") + "]");
        assertThat(client.findOrganizationMovieCandidate("40845", 1658653, "消失的人", null)).isNotNull();
        assertThat(request.get().path("SearchInfo").has("Year")).isFalse();
    }

    private ObjectNode movie() {
        ObjectNode item = mapper.createObjectNode();
        item.put("Id", "40845").put("Type", "Movie").put("Path", "/MOVIE/消失的人(2026)/movie.strm")
                .put("ProductionYear", 2026).put("IsLocked", false).put("Etag", "revision-1").put("ParentId", "parent");
        item.putObject("ProviderIds").put("Tmdb", "999");
        item.putArray("LockedFields");
        item.putArray("MediaSources").addObject().put("Path", item.path("Path").asText());
        return item;
    }

    private Map<String, String> query(HttpExchange exchange) {
        assertThat(exchange.getRequestURI().getPath()).isEqualTo("/Items");
        assertThat(exchange.getRequestMethod()).isEqualTo("GET");
        return Arrays.stream(exchange.getRequestURI().getRawQuery().split("&"))
                .map(part -> part.split("=", 2)).collect(Collectors.toMap(parts -> parts[0],
                        parts -> URLDecoder.decode(parts[1], StandardCharsets.UTF_8)));
    }

    private void respond(HttpExchange exchange, String response) throws IOException {
        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
