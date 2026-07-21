package com.medianexus.orchestrator.integration.emby;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.EmbyProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class EmbyClient {

    private final EmbyProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EmbyClient(EmbyProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout())
                .build();
    }

    public List<EmbyLibrary> listLibraries() {
        JsonNode root = get("/Library/VirtualFolders", Map.of());
        if (!root.isArray()) {
            return List.of();
        }

        List<EmbyLibrary> libraries = new ArrayList<>();
        for (JsonNode item : root) {
            List<String> locations = new ArrayList<>();
            JsonNode locationNodes = item.path("Locations");
            if (locationNodes.isArray()) {
                for (JsonNode locationNode : locationNodes) {
                    String location = locationNode.asText("");
                    if (StringUtils.hasText(location)) {
                        locations.add(location);
                    }
                }
            }

            libraries.add(new EmbyLibrary(
                    text(item, "ItemId", "Id"),
                    text(item, "Name"),
                    locations
            ));
        }
        return libraries;
    }

    public List<EmbyUserAccount> listUsers() {
        JsonNode root = get("/Users/Query", Map.of("Limit", "10000"));
        JsonNode users = root.path("Items");
        if (!users.isArray()) {
            throw new EmbyClientException("Emby users response is incomplete");
        }

        List<EmbyUserAccount> result = new ArrayList<>();
        for (JsonNode user : users) {
            JsonNode policy = user.path("Policy");
            result.add(new EmbyUserAccount(
                    text(user, "Id", "id"),
                    text(user, "Name", "name"),
                    policy.path("IsAdministrator").asBoolean(false),
                    policy.path("IsDisabled").asBoolean(false)
            ));
        }
        return result;
    }

    public EmbyUserAccount createUserFromTemplate(String username, String templateUserId) {
        JsonNode user = postJson("/Users/New", Map.of(), writeJson(Map.of(
                "Name", username,
                "CopyFromUserId", templateUserId,
                "UserCopyOptions", List.of("UserPolicy")
        )));
        String userId = text(user, "Id", "id");
        if (!StringUtils.hasText(userId)) {
            throw new EmbyClientException("Emby user id missing after create");
        }
        JsonNode policy = user.path("Policy");
        return new EmbyUserAccount(
                userId,
                text(user, "Name", "name"),
                policy.path("IsAdministrator").asBoolean(false),
                policy.path("IsDisabled").asBoolean(false)
        );
    }

    public void updateUserPassword(String userId, String password) {
        postJson("/Users/" + encodePath(userId) + "/Password", Map.of(), writeJson(Map.of(
                "Id", userId,
                "NewPw", password,
                "ResetPassword", false
        )));
    }

    public void deleteUser(String userId) {
        delete("/Users/" + encodePath(userId), Map.of());
    }

    public List<EmbyItem> listLibraryVideoItems(String libraryId) {
        return items(Map.of(
                "ParentId", libraryId,
                "Recursive", "true",
                "Fields", "Path,ParentId,DateCreated",
                "IncludeItemTypes", "Movie,Video,Episode",
                "GroupItemsIntoCollections", "false",
                "Limit", "10000"
        ));
    }

    public List<EmbyCatalogItem> findMoviesByTmdbId(int tmdbId) {
        return catalogItems(Map.of(
                "Recursive", "true",
                "IncludeItemTypes", "Movie",
                "Fields", "ProviderIds,Path",
                "AnyProviderIdEquals", "tmdb." + tmdbId,
                "GroupItemsIntoCollections", "false",
                "Limit", "20"
        ));
    }

    public List<EmbyCatalogItem> findSeriesByTmdbId(int tmdbId) {
        return catalogItems(Map.of(
                "Recursive", "true",
                "IncludeItemTypes", "Series",
                "Fields", "ProviderIds,Path",
                "AnyProviderIdEquals", "tmdb." + tmdbId,
                "Limit", "20"
        ));
    }

    public List<EmbyCatalogItem> listSeriesSeasons(String seriesId) {
        if (!StringUtils.hasText(seriesId)) {
            return List.of();
        }
        return catalogItems(Map.of(
                "ParentId", seriesId.trim(),
                "IncludeItemTypes", "Season",
                "Fields", "Path",
                "Limit", "100"
        ));
    }

    public List<EmbyItem> listLibraryVideoItemsByDateCreated(
            String libraryId,
            int startIndex,
            int limit
    ) {
        return items(Map.of(
                "ParentId", libraryId,
                "Recursive", "true",
                "Fields", "Path,ParentId,DateCreated",
                "IncludeItemTypes", "Movie,Video,Episode",
                "GroupItemsIntoCollections", "false",
                "SortBy", "DateCreated",
                "SortOrder", "Descending",
                "StartIndex", String.valueOf(Math.max(0, startIndex)),
                "Limit", String.valueOf(Math.max(1, limit))
        ));
    }

    public List<EmbyCollection> listCollections(String parentId) {
        return items(Map.of(
                "IncludeItemTypes", "BoxSet",
                "Recursive", "true",
                "Fields", "Path,ParentId",
                "ParentId", parentId,
                "Limit", "10000"
        )).stream()
                .map(item -> new EmbyCollection(item.id(), item.name()))
                .toList();
    }

    public List<EmbyItem> listCollectionVideoItems(String collectionId) {
        return items(Map.of(
                "ParentId", collectionId,
                "Recursive", "true",
                "IncludeItemTypes", "Movie,Video,Episode",
                "Limit", "10000"
        ));
    }

    public List<EmbyItemState> listItemStates(Collection<String> itemIds) {
        List<String> distinctItemIds = itemIds.stream()
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
        if (distinctItemIds.isEmpty()) {
            return List.of();
        }

        JsonNode root = get("/Items", Map.of(
                "Ids", String.join(",", distinctItemIds),
                "Fields", "Path,ImageTags,MediaSources,MediaStreams"
        ));
        JsonNode items = root.path("Items");
        if (!items.isArray()) {
            return List.of();
        }

        List<EmbyItemState> states = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode mediaStreams = item.path("MediaStreams");
            states.add(new EmbyItemState(
                    text(item, "Id", "id"),
                    text(item, "Name", "name"),
                    text(item, "Type", "type"),
                    text(item, "Path", "path"),
                    StringUtils.hasText(item.path("ImageTags").path("Primary").asText(null)),
                    mediaStreams.isArray() ? mediaStreams.size() : 0
            ));
        }
        return states;
    }

    public boolean hasPrimaryImage(String itemId) {
        return listItemStates(List.of(itemId)).stream()
                .anyMatch(EmbyItemState::hasPrimaryImage);
    }

    public void refreshItemImages(String itemId) {
        postJson("/Items/" + encodePath(itemId) + "/Refresh", Map.of(
                "Recursive", "false",
                "ImageRefreshMode", "FullRefresh",
                "MetadataRefreshMode", "FullRefresh",
                "ReplaceAllImages", "true",
                "ReplaceAllMetadata", "false"
        ), "{\"ReplaceThumbnailImages\":true}");
    }

    public void refreshCollectionImages(String collectionId) {
        post("/Items/" + encodePath(collectionId) + "/Refresh", Map.of(
                "Recursive", "false",
                "MetadataRefreshMode", "Default",
                "ImageRefreshMode", "FullRefresh",
                "ReplaceAllImages", "false",
                "ReplaceAllMetadata", "false"
        ));
    }

    public void refreshLibrary(String libraryId) {
        postJson("/Items/" + encodePath(libraryId) + "/Refresh", Map.of(
                "Recursive", "true",
                "MetadataRefreshMode", "Default",
                "ImageRefreshMode", "Default",
                "ReplaceAllMetadata", "false",
                "ReplaceAllImages", "false"
        ), "{\"ReplaceThumbnailImages\":false}");
    }

    public void materializePrimaryImage(String itemId) {
        sendDiscarding("GET", "/Items/" + encodePath(itemId) + "/Images/Primary", Map.of());
    }

    public byte[] getPrimaryImage(String itemId) {
        return sendBytes(
                HttpRequest.newBuilder(uri(
                                "/Items/" + encodePath(itemId) + "/Images/Primary",
                                Map.of()
                        ))
                        .timeout(timeout())
                        .header("Accept", "image/*")
                        .header("X-Emby-Token", cleanConfigValue(properties.getApiKey()))
                        .GET()
                        .build()
        );
    }

    public void uploadPrimaryImage(String itemId, byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            throw new EmbyClientException("Emby Primary image is empty");
        }
        byte[] encodedImage = Base64.getEncoder().encode(imageBytes);
        sendBytes(
                HttpRequest.newBuilder(uri(
                                "/Items/" + encodePath(itemId) + "/Images/Primary",
                                Map.of()
                        ))
                        .timeout(timeout())
                        .header("Accept", "application/json")
                        .header("Content-Type", "image/jpeg")
                        .header("X-Emby-Token", cleanConfigValue(properties.getApiKey()))
                        .POST(HttpRequest.BodyPublishers.ofByteArray(encodedImage))
                        .build()
        );
    }

    public String createCollection(String name, List<String> itemIds, String parentId) {
        JsonNode root = post("/Collections", Map.of(
                "Name", name,
                "Ids", String.join(",", itemIds),
                "ParentId", parentId
        ));
        String id = text(root, "Id", "id");
        if (StringUtils.hasText(id)) {
            return id;
        }
        return listCollections(parentId).stream()
                .filter(collection -> name.equals(collection.name()))
                .map(EmbyCollection::id)
                .findFirst()
                .orElseThrow(() -> new EmbyClientException("Emby collection id missing after create"));
    }

    public void addItemsToCollection(String collectionId, List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return;
        }
        post("/Collections/" + encodePath(collectionId) + "/Items", Map.of(
                "Ids", String.join(",", itemIds)
        ));
    }

    public void deleteCollection(String collectionId) {
        delete("/Items", Map.of("Ids", collectionId));
    }

    private List<EmbyItem> items(Map<String, String> params) {
        JsonNode root = get("/Items", params);
        JsonNode items = root.path("Items");
        if (!items.isArray()) {
            return List.of();
        }

        List<EmbyItem> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new EmbyItem(
                    text(item, "Id", "id"),
                    text(item, "Name", "name"),
                    text(item, "Type", "type"),
                    text(item, "Path", "path"),
                    text(item, "DateCreated", "dateCreated")
            ));
        }
        return result;
    }

    private List<EmbyCatalogItem> catalogItems(Map<String, String> params) {
        JsonNode root = get("/Items", params);
        JsonNode items = root.path("Items");
        if (!items.isArray()) {
            return List.of();
        }

        List<EmbyCatalogItem> result = new ArrayList<>();
        for (JsonNode item : items) {
            result.add(new EmbyCatalogItem(
                    text(item, "Id", "id"),
                    text(item, "Name", "name"),
                    text(item, "Type", "type"),
                    text(item, "Path", "path"),
                    integerOrNull(item, "IndexNumber", "indexNumber")
            ));
        }
        return result;
    }

    private JsonNode get(String path, Map<String, String> params) {
        return send("GET", path, params);
    }

    private JsonNode post(String path, Map<String, String> params) {
        return send("POST", path, params);
    }

    private JsonNode postJson(String path, Map<String, String> params, String body) {
        return send("POST_JSON", path, params, body);
    }

    private JsonNode delete(String path, Map<String, String> params) {
        return send("DELETE", path, params);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new EmbyClientException("Emby request body could not be serialized", exception);
        }
    }

    private JsonNode send(String method, String path, Map<String, String> params) {
        return send(method, path, params, null);
    }

    private JsonNode send(String method, String path, Map<String, String> params, String body) {
        validateConfiguration();
        URI uri = uri(path, params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout())
                .header("Accept", "application/json")
                .header("X-Emby-Token", cleanConfigValue(properties.getApiKey()));
        HttpRequest request = switch (method) {
            case "POST_JSON" -> builder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body == null ? "{}" : body))
                    .build();
            case "POST" -> builder.POST(HttpRequest.BodyPublishers.noBody()).build();
            case "DELETE" -> builder.DELETE().build();
            default -> builder.GET().build();
        };

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmbyClientException("Emby returned non-success status " + response.statusCode());
            }
            String responseBody = response.body();
            if (!StringUtils.hasText(responseBody)) {
                return objectMapper.createObjectNode();
            }
            return objectMapper.readTree(responseBody);
        } catch (JsonProcessingException exception) {
            throw new EmbyClientException("Emby response is not valid JSON", exception);
        } catch (HttpTimeoutException exception) {
            throw new EmbyClientException("Emby request timed out after " + timeoutHint(), exception);
        } catch (IOException exception) {
            throw new EmbyClientException("Emby request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbyClientException("Emby request interrupted", exception);
        }
    }

    private void sendDiscarding(String method, String path, Map<String, String> params) {
        validateConfiguration();
        URI uri = uri(path, params);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout())
                .header("X-Emby-Token", cleanConfigValue(properties.getApiKey()));
        HttpRequest request = "GET".equals(method)
                ? builder.GET().build()
                : builder.method(method, HttpRequest.BodyPublishers.noBody()).build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmbyClientException("Emby returned non-success status " + response.statusCode());
            }
        } catch (HttpTimeoutException exception) {
            throw new EmbyClientException("Emby request timed out after " + timeoutHint(), exception);
        } catch (IOException exception) {
            throw new EmbyClientException("Emby request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbyClientException("Emby request interrupted", exception);
        }
    }

    private byte[] sendBytes(HttpRequest request) {
        validateConfiguration();
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    request,
                    HttpResponse.BodyHandlers.ofByteArray()
            );
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new EmbyClientException("Emby returned non-success status " + response.statusCode());
            }
            return response.body();
        } catch (HttpTimeoutException exception) {
            throw new EmbyClientException("Emby request timed out after " + timeoutHint(), exception);
        } catch (IOException exception) {
            throw new EmbyClientException("Emby request failed", exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EmbyClientException("Emby request interrupted", exception);
        }
    }

    private URI uri(String path, Map<String, String> params) {
        Map<String, String> queryParams = new LinkedHashMap<>(params);
        String query = queryParams.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
        String baseUrl = cleanConfigValue(properties.getBaseUrl()).replaceAll("/+$", "");
        return URI.create(baseUrl + path + (query.isEmpty() ? "" : "?" + query));
    }

    private void validateConfiguration() {
        if (!StringUtils.hasText(cleanConfigValue(properties.getBaseUrl()))
                || !StringUtils.hasText(cleanConfigValue(properties.getApiKey()))) {
            throw new EmbyClientException("Emby configuration is incomplete");
        }
    }

    private Duration timeout() {
        return properties.getTimeout() == null ? Duration.ofSeconds(10) : properties.getTimeout();
    }

    private String timeoutHint() {
        Duration duration = timeout();
        long millis = duration.toMillis();
        return millis % 1000 == 0 ? duration.toSeconds() + "s" : millis + "ms";
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull()) {
                return value.asText();
            }
        }
        return null;
    }

    private Integer integerOrNull(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (!value.isMissingNode() && !value.isNull() && value.canConvertToInt()) {
                return value.asInt();
            }
        }
        return null;
    }

    private String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private String encodePath(String value) {
        return encode(value).replace("+", "%20");
    }

    private String cleanConfigValue(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim();
        if (cleaned.length() >= 2
                && ((cleaned.startsWith("'") && cleaned.endsWith("'"))
                || (cleaned.startsWith("\"") && cleaned.endsWith("\"")))) {
            cleaned = cleaned.substring(1, cleaned.length() - 1).trim();
        }
        return cleaned;
    }
}
