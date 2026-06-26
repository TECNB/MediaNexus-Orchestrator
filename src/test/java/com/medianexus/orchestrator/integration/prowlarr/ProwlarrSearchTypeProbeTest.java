package com.medianexus.orchestrator.integration.prowlarr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Test;
import org.springframework.util.StringUtils;

class ProwlarrSearchTypeProbeTest {

    private static final String ENABLED_KEY = "MEDIANEXUS_PROWLARR_TYPE_PROBE_ENABLED";
    private static final String BASE_URL_KEY = "MEDIANEXUS_PROWLARR_BASE_URL";
    private static final String API_KEY_KEY = "MEDIANEXUS_PROWLARR_API_KEY";
    private static final String MOVIE_QUERY_KEY = "MEDIANEXUS_PROWLARR_TYPE_PROBE_MOVIE_QUERY";
    private static final String TV_QUERY_KEY = "MEDIANEXUS_PROWLARR_TYPE_PROBE_TV_QUERY";
    private static final String MOVIE_QUERIES_KEY = "MEDIANEXUS_PROWLARR_TYPE_PROBE_MOVIE_QUERIES";
    private static final String TV_QUERIES_KEY = "MEDIANEXUS_PROWLARR_TYPE_PROBE_TV_QUERIES";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Test
    void typeAndCategoryParametersExposeSearchFilteringBehavior() {
        ProbeConfig config = ProbeConfig.load();
        assumeTrue(config.enabled(), "Set " + ENABLED_KEY + "=true to run the real Prowlarr type probe");
        assumeTrue(StringUtils.hasText(config.baseUrl()), BASE_URL_KEY + " is required");
        assumeTrue(StringUtils.hasText(config.apiKey()), API_KEY_KEY + " is required");

        SoftAssertions softly = new SoftAssertions();
        for (String query : config.movieQueries()) {
            assertSearchParameterBehavior(softly, config, "movie", query, "moviesearch", "tvsearch");
        }
        for (String query : config.tvQueries()) {
            assertSearchParameterBehavior(softly, config, "tv", query, "tvsearch", "moviesearch");
        }
        softly.assertAll();
    }

    private void assertSearchParameterBehavior(
            SoftAssertions softly,
            ProbeConfig config,
            String label,
            String query,
            String expectedType,
            String swappedType
    ) {
        SearchResult untyped;
        SearchResult expectedTyped;
        SearchResult swappedTyped;
        SearchResult movieCategory;
        SearchResult tvCategory;
        try {
            untyped = search(config, query, null, null);
            expectedTyped = search(config, query, expectedType, null);
            swappedTyped = search(config, query, swappedType, null);
            movieCategory = search(config, query, null, 2000);
            tvCategory = search(config, query, null, 5000);
        } catch (AssertionError error) {
            softly.fail(label + " query " + query + " failed while comparing search parameters", error);
            return;
        }

        System.out.printf(
                Locale.ROOT,
                "%s query \"%s\": untyped=%d cjk=%d, type=%s total=%d cjk=%d same=%s, swappedType=%s total=%d cjk=%d same=%s, cat2000=%d cjk=%d same=%s, cat5000=%d cjk=%d same=%s, catSame=%s%n",
                label,
                query,
                untyped.titles().size(),
                untyped.cjkTitles().size(),
                expectedType,
                expectedTyped.titles().size(),
                expectedTyped.cjkTitles().size(),
                untyped.titleSet().equals(expectedTyped.titleSet()),
                swappedType,
                swappedTyped.titles().size(),
                swappedTyped.cjkTitles().size(),
                untyped.titleSet().equals(swappedTyped.titleSet()),
                movieCategory.titles().size(),
                movieCategory.cjkTitles().size(),
                untyped.titleSet().equals(movieCategory.titleSet()),
                tvCategory.titles().size(),
                tvCategory.cjkTitles().size(),
                untyped.titleSet().equals(tvCategory.titleSet()),
                movieCategory.titleSet().equals(tvCategory.titleSet())
        );

        softly.assertThat(untyped.titles())
                .as("%s untyped query should return releases for probe query %s", label, query)
                .isNotEmpty();
        softly.assertThat(expectedTyped.titleSet())
                .as("%s type=%s should return the same titles as untyped search for query %s", label, expectedType, query)
                .isEqualTo(untyped.titleSet());
        softly.assertThat(swappedTyped.titleSet())
                .as("%s swapped type=%s should return the same titles as untyped search for query %s", label, swappedType, query)
                .isEqualTo(untyped.titleSet());
        softly.assertThat(movieCategory.titleSet())
                .as("%s categories=2000 should change titles compared with untyped search for query %s", label, query)
                .isNotEqualTo(untyped.titleSet());
        softly.assertThat(tvCategory.titleSet())
                .as("%s categories=5000 should change titles compared with untyped search for query %s", label, query)
                .isNotEqualTo(untyped.titleSet());
        softly.assertThat(movieCategory.titleSet())
                .as("%s categories=2000 and categories=5000 should produce different title sets for query %s", label, query)
                .isNotEqualTo(tvCategory.titleSet());
    }

    private SearchResult search(ProbeConfig config, String query, String type, Integer category) {
        HttpRequest request = HttpRequest.newBuilder(searchUri(config, query, type, category))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                    .as("Prowlarr search HTTP status for query %s type %s category %s", query, type, category)
                    .isBetween(200, 299);
            JsonNode payload = objectMapper.readTree(response.body());
            assertThat(payload.isArray())
                    .as("Prowlarr search response should be an array for query %s type %s category %s", query, type, category)
                    .isTrue();
            List<String> titles = new ArrayList<>();
            for (JsonNode item : payload) {
                JsonNode title = item.get("title");
                if (title != null && StringUtils.hasText(title.asText())) {
                    titles.add(title.asText().trim());
                }
            }
            return new SearchResult(titles);
        } catch (IOException exception) {
            throw new AssertionError("Prowlarr search request failed for query " + query + " type " + type + " category " + category, exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Prowlarr search request interrupted for query " + query + " type " + type + " category " + category, exception);
        }
    }

    private URI searchUri(ProbeConfig config, String query, String type, Integer category) {
        StringBuilder builder = new StringBuilder(trimTrailingSlash(config.baseUrl()))
                .append("/api/v1/search?apikey=")
                .append(encode(config.apiKey()))
                .append("&query=")
                .append(encode(query));
        if (StringUtils.hasText(type)) {
            builder.append("&type=").append(encode(type));
        }
        if (category != null) {
            builder.append("&categories=").append(category);
        }
        return URI.create(builder.toString());
    }

    private String trimTrailingSlash(String value) {
        return value.replaceAll("/+$", "");
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private record SearchResult(List<String> titles) {

        private List<String> cjkTitles() {
            return titles.stream()
                    .filter(ProwlarrSearchTypeProbeTest::containsCjk)
                    .toList();
        }

        private Set<String> titleSet() {
            return new LinkedHashSet<>(titles);
        }
    }

    private static boolean containsCjk(String value) {
        return value.codePoints()
                .anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private record ProbeConfig(
            boolean enabled,
            String baseUrl,
            String apiKey,
            List<String> movieQueries,
            List<String> tvQueries
    ) {

        private static ProbeConfig load() {
            Properties dotenv = loadDotenv();
            return new ProbeConfig(
                    "true".equalsIgnoreCase(value(ENABLED_KEY, dotenv)),
                    clean(value(BASE_URL_KEY, dotenv)),
                    clean(value(API_KEY_KEY, dotenv)),
                    queryList(
                            value(MOVIE_QUERIES_KEY, dotenv),
                            value(MOVIE_QUERY_KEY, dotenv),
                            List.of("哪吒之魔童闹海 2025", "碟中谍8", "暗影蜘蛛侠")
                    ),
                    queryList(
                            value(TV_QUERIES_KEY, dotenv),
                            value(TV_QUERY_KEY, dotenv),
                            List.of("庆余年", "最后生还者", "绝命毒师")
                    )
            );
        }

        private static Properties loadDotenv() {
            Properties properties = new Properties();
            Path path = Path.of(".env");
            if (!Files.isRegularFile(path)) {
                return properties;
            }
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
                return properties;
            } catch (IOException exception) {
                throw new AssertionError("Failed to load local .env for Prowlarr probe", exception);
            }
        }

        private static String value(String key, Properties dotenv) {
            String environmentValue = System.getenv(key);
            if (StringUtils.hasText(environmentValue)) {
                return environmentValue;
            }
            return dotenv.getProperty(key, "");
        }

        private static List<String> queryList(String values, String legacyValue, List<String> fallback) {
            String cleaned = clean(values);
            if (StringUtils.hasText(cleaned)) {
                return splitQueries(cleaned);
            }
            String legacyQuery = clean(legacyValue);
            if (StringUtils.hasText(legacyQuery)) {
                return List.of(legacyQuery);
            }
            return fallback;
        }

        private static List<String> splitQueries(String value) {
            List<String> queries = new ArrayList<>();
            for (String query : value.split(",")) {
                String cleaned = clean(query);
                if (StringUtils.hasText(cleaned)) {
                    queries.add(cleaned);
                }
            }
            return queries;
        }

        private static String clean(String value) {
            if (value == null) {
                return "";
            }
            String cleaned = value.trim();
            if (cleaned.length() >= 2
                    && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                    || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
                return cleaned.substring(1, cleaned.length() - 1).trim();
            }
            return cleaned;
        }
    }
}
