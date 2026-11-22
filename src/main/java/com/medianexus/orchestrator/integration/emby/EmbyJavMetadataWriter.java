package com.medianexus.orchestrator.integration.emby;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.config.JavMetadataProperties;
import com.medianexus.orchestrator.integration.javdb.JavdbMetadataSnapshot;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Writes only an already-ingested, exactly matched item; never asks a metadata provider to identify it. */
@Component
public class EmbyJavMetadataWriter {
    private static final int MAX_BYTES = 24 * 1024 * 1024;
    private final EmbyProperties emby;
    private final String imageBaseUrl;
    private final Duration timeout;
    private final ObjectMapper mapper;
    private final HttpClient client;

    @Autowired
    public EmbyJavMetadataWriter(EmbyProperties emby, JavMetadataProperties properties, ObjectMapper mapper) {
        this(emby, properties.getImageBaseUrl(), properties.getRequestTimeout(), mapper);
    }

    EmbyJavMetadataWriter(EmbyProperties emby, String imageBaseUrl, Duration timeout, ObjectMapper mapper) {
        this.emby = emby;
        this.imageBaseUrl = imageBaseUrl.replaceAll("/+$", "");
        this.timeout = timeout;
        this.mapper = mapper;
        this.client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Artwork(byte[] primary, byte[] thumb, byte[] backdrop) { }

    public Artwork prepareImages(JavdbMetadataSnapshot source, boolean chineseSubtitles) {
        return new Artwork(prepareImage("Primary", source, chineseSubtitles),
                prepareImage("Thumb", source, chineseSubtitles), prepareImage("Backdrop", source, chineseSubtitles));
    }

    public byte[] preparePrimary(JavdbMetadataSnapshot source, boolean chineseSubtitles) {
        return prepareImage("Primary", source, chineseSubtitles);
    }
    public byte[] prepareThumb(JavdbMetadataSnapshot source) { return prepareImage("Thumb", source, false); }
    public byte[] prepareBackdrop(JavdbMetadataSnapshot source) { return prepareImage("Backdrop", source, false); }

    public void writeMetadataAndVerify(String itemId, String expectedStrmPath, JavdbMetadataSnapshot source,
                                       boolean chineseSubtitles, boolean cracked) {
        ObjectNode expected = metadata(checkedItem(itemId, expectedStrmPath), source, chineseSubtitles, cracked);
        embyRequest("POST", "/Items/" + encode(itemId), json(expected), "application/json");
        verifyMetadata(expected, checkedItem(itemId, expectedStrmPath));
    }

    private byte[] prepareImage(String role, JavdbMetadataSnapshot source, boolean chineseSubtitles) {
        String url = imageBaseUrl + "/v1/images/" + role.toLowerCase(java.util.Locale.ROOT) + "/JavBus/" + encode(source.sourceId())
                + "?url=" + encode(source.coverUrl()) + "&pos=1"
                + (role.equals("Primary") ? "&ratio=" + (2.0 / 3.0) : "")
                + (role.equals("Primary") && chineseSubtitles ? "&badge=zimu.png" : "");
        HttpResponse<byte[]> response = exchange(HttpRequest.newBuilder(uri(url)).GET().build());
        if (!response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT)
                .startsWith("image/")) throw failure("Image content type rejected");
        return jpeg(response.body());
    }

    public void writeAndVerify(String itemId, String expectedStrmPath, JavdbMetadataSnapshot source,
                               boolean chineseSubtitles, boolean cracked, Artwork artwork) {
        // Validate all caller-supplied artwork before the first remote mutation.
        validateImage(artwork.primary());
        validateImage(artwork.thumb());
        validateImage(artwork.backdrop());
        ObjectNode expected = metadata(checkedItem(itemId, expectedStrmPath), source, chineseSubtitles, cracked);
        embyRequest("POST", "/Items/" + encode(itemId), json(expected), "application/json");
        writeImage(itemId, expectedStrmPath, "Primary", artwork.primary());
        writeImage(itemId, expectedStrmPath, "Thumb", artwork.thumb());
        JsonNode images = readJson(embyRequest("GET", "/Items/" + encode(itemId) + "/Images", null, null));
        if (!images.isArray()) throw failure("Invalid Emby image inventory");
        java.util.List<Integer> indices = new java.util.ArrayList<>();
        for (JsonNode image : images) {
            if ("Backdrop".equals(image.path("ImageType").asText())) {
                if (!image.path("ImageIndex").canConvertToInt() || image.path("ImageIndex").asInt() < 0)
                    throw failure("Invalid Emby backdrop index");
                indices.add(image.path("ImageIndex").asInt());
            }
        }
        indices.stream().distinct().sorted(java.util.Comparator.reverseOrder()).forEach(index -> {
            checkedItem(itemId, expectedStrmPath);
            embyRequest("DELETE", "/Items/" + encode(itemId) + "/Images/Backdrop/" + index, null, null);
        });
        writeImage(itemId, expectedStrmPath, "Backdrop", artwork.backdrop());
        verifyImage(itemId, "Primary", artwork.primary());
        verifyImage(itemId, "Thumb", artwork.thumb());
        verifyImage(itemId, "Backdrop/0", artwork.backdrop());
        JsonNode finalImages = readJson(embyRequest("GET", "/Items/" + encode(itemId) + "/Images", null, null));
        int backdrops = 0;
        if (!finalImages.isArray()) throw failure("Invalid Emby image inventory");
        for (JsonNode image : finalImages) {
            if ("Backdrop".equals(image.path("ImageType").asText())) backdrops++;
        }
        if (backdrops != 1) throw failure("Emby backdrop verification failed");
        verifyMetadata(expected, checkedItem(itemId, expectedStrmPath));
    }

    /** Recheck persisted claims without triggering providers or overwriting later user edits. */
    public void verify(String itemId, String expectedStrmPath, JavdbMetadataSnapshot source,
                       boolean chineseSubtitles, boolean cracked, Artwork artwork) {
        ObjectNode actual = checkedItem(itemId, expectedStrmPath);
        ObjectNode expected = metadata(actual.deepCopy(), source, chineseSubtitles, cracked);
        verifyMetadata(expected, actual);
        verifyImage(itemId, "Primary", artwork.primary());
        verifyImage(itemId, "Thumb", artwork.thumb());
        verifyImage(itemId, "Backdrop/0", artwork.backdrop());
    }

    private ObjectNode metadata(ObjectNode item, JavdbMetadataSnapshot source, boolean subtitles, boolean cracked) {
        item.remove(List.of("UserData", "TrailerUrl", "TrailerUrls"));
        String name = source.code() + " " + source.title();
        item.put("Name", name).put("OriginalTitle", source.title()).put("SortName", source.code()).put("ForcedSortName", source.code());
        item.put("Overview", "").put("OfficialRating", "").putNull("CriticRating");
        item.set("ProviderIds", mapper.createObjectNode().put("JavDB", source.sourceId()));
        ArrayNode people = item.putArray("People");
        source.actors().forEach(actor -> people.addObject().put("Name", actor).put("Type", "Actor"));
        source.directors().forEach(director -> people.addObject().put("Name", director).put("Type", "Director"));
        item.set("Genres", mapper.valueToTree(source.genres()));
        ArrayNode genreItems = item.putArray("GenreItems");
        source.genres().forEach(genre -> genreItems.addObject().put("Name", genre));
        ArrayNode studios = item.putArray("Studios");
        source.studios().forEach(studio -> studios.addObject().put("Name", studio));
        ArrayNode tags = mapper.createArrayNode();
        JsonNode originalTags = item.has("TagItems") ? item.path("TagItems") : item.path("Tags");
        originalTags.forEach(tag -> {
            String tagName = tag.isObject() ? tag.path("Name").asText() : tag.asText();
            if (!List.of("中文字幕", "破解").contains(tagName)) tags.add(tagName);
        });
        if (subtitles) tags.add("中文字幕");
        if (cracked) tags.add("破解");
        item.set("Tags", tags);
        ArrayNode tagItems = item.putArray("TagItems");
        tags.forEach(tag -> tagItems.addObject().put("Name", tag.asText()));
        item.putArray("RemoteTrailers");
        item.putArray("Taglines");
        item.putArray("ProductionLocations");
        item.put("LocalTrailerCount", 0);
        item.putNull("PremiereDate").putNull("ProductionYear").putNull("CommunityRating");
        if (source.releaseDate() != null && !source.releaseDate().isBlank()) {
            java.time.LocalDate date = java.time.LocalDate.parse(source.releaseDate());
            item.put("PremiereDate", date + "T00:00:00.000Z").put("ProductionYear", date.getYear());
        }
        if (source.communityRating() != null) item.put("CommunityRating", source.communityRating());
        item.put("LockData", true);
        return item;
    }

    private void verifyMetadata(ObjectNode expected, ObjectNode actual) {
        for (String field : List.of("Name", "OriginalTitle", "ProviderIds", "Genres", "Overview", "LockData",
                "ProductionYear", "CommunityRating", "RemoteTrailers", "Taglines",
                "PremiereDate", "SortName", "ForcedSortName", "OfficialRating", "CriticRating", "ProductionLocations")) {
            JsonNode wanted = expected.path(field), received = actual.path(field);
            if (wanted.isNull() || wanted.isMissingNode() || (wanted.isTextual() && wanted.asText().isEmpty())
                    || (wanted.isArray() && wanted.isEmpty())) {
                if (received.isNull() || received.isMissingNode() || (received.isTextual() && received.asText().isEmpty())
                        || (received.isArray() && received.isEmpty())) continue;
            }
            if (wanted.isNumber() && received.isNumber() && Math.abs(wanted.asDouble() - received.asDouble()) < 0.001) continue;
            if (field.equals("PremiereDate") && received.isTextual() && wanted.isTextual()
                    && java.time.Instant.parse(wanted.asText()).equals(java.time.Instant.parse(received.asText()))) continue;
            if (!wanted.equals(received)) throw failure("Emby metadata verification failed: " + field);
        }
        for (String field : List.of("People", "Studios", "GenreItems", "TagItems")) {
            ArrayNode projected = mapper.createArrayNode();
            actual.path(field).forEach(entry -> {
                ObjectNode person = projected.addObject().put("Name", entry.path("Name").asText());
                if (field.equals("People")) person.put("Type", entry.path("Type").asText());
            });
            if (!expected.path(field).equals(projected)) throw failure("Emby metadata verification failed: " + field);
        }
        if (actual.hasNonNull("TrailerUrl") && !actual.path("TrailerUrl").asText().isEmpty())
            throw failure("Emby trailer identity verification failed");
    }

    private ObjectNode checkedItem(String id, String path) {
        JsonNode users = readJson(embyRequest("GET", "/Users/Query", null, null)).path("Items");
        String userId = null;
        for (JsonNode user : users) if (user.path("Policy").path("IsAdministrator").asBoolean()) {
            userId = user.path("Id").asText(); break;
        }
        if (userId == null) throw failure("Emby administrator unavailable");
        JsonNode loaded = readJson(embyRequest("GET", "/Users/" + encode(userId) + "/Items/" + encode(id), null, null));
        if (!(loaded instanceof ObjectNode item)
                || !id.equals(item.path("Id").asText()) || !path.equals(item.path("Path").asText()))
            throw failure("Emby exact item path verification failed");
        return item;
    }

    private void writeImage(String id, String path, String role, byte[] bytes) {
        checkedItem(id, path);
        embyRequest("POST", "/Items/" + encode(id) + "/Images/" + role,
                Base64.getEncoder().encode(bytes), "image/jpeg");
    }

    private void verifyImage(String id, String role, byte[] expected) {
        byte[] actual = embyRequest("GET", "/Items/" + encode(id) + "/Images/" + role, null, null);
        validateImage(actual);
        if (!MessageDigest.isEqual(sha256(expected), sha256(actual))) throw failure("Emby image verification failed");
    }

    private byte[] embyRequest(String method, String path, byte[] body, String contentType) {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(emby.getBaseUrl().replaceAll("/+$", "") + path))
                .header("X-Emby-Token", emby.getApiKey());
        if (contentType != null) request.header("Content-Type", contentType);
        return exchange(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofByteArray(body)).build()).body();
    }

    private HttpResponse<byte[]> exchange(HttpRequest request) {
        CompletableFuture<HttpResponse<byte[]>> response = client.sendAsync(request,
                ignored -> new BoundedBody());
        try {
            HttpResponse<byte[]> result = response.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (result.statusCode() < 200 || result.statusCode() >= 300)
                throw failure("Remote request rejected, status " + result.statusCode());
            return result;
        } catch (InterruptedException exception) {
            response.cancel(true);
            Thread.currentThread().interrupt();
            throw failure("Remote request interrupted");
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException exception) {
            response.cancel(true);
            // Transport exceptions may include signed image URLs or credentials; do not retain their causes.
            throw failure("Remote request failed or timed out");
        }
    }

    private JsonNode readJson(byte[] bytes) {
        try { return mapper.readTree(bytes); }
        catch (IOException exception) { throw failure("Invalid Emby JSON response"); }
    }

    private byte[] json(ObjectNode item) {
        try { return mapper.writeValueAsBytes(item); }
        catch (IOException exception) { throw failure("Metadata serialization failed"); }
    }

    private static URI uri(String url) {
        try { return URI.create(url); }
        catch (IllegalArgumentException exception) { throw failure("Invalid remote endpoint"); }
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8); }
    private static IllegalStateException failure(String message) { return new IllegalStateException(message); }

    private static byte[] sha256(byte[] bytes) {
        try { return MessageDigest.getInstance("SHA-256").digest(bytes); }
        catch (NoSuchAlgorithmException exception) { throw failure("SHA-256 unavailable"); }
    }

    private static void validateImage(byte[] bytes) { decode(bytes); }

    private static byte[] jpeg(byte[] bytes) {
        BufferedImage decoded = decode(bytes);
        BufferedImage rgb = new BufferedImage(decoded.getWidth(), decoded.getHeight(), BufferedImage.TYPE_INT_RGB);
        var graphics = rgb.createGraphics();
        try { graphics.setColor(Color.WHITE); graphics.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
            graphics.drawImage(decoded, 0, 0, null); }
        finally { graphics.dispose(); }
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(rgb, "jpeg", output)) throw failure("JPEG encoder unavailable");
            byte[] jpeg = output.toByteArray();
            if (jpeg.length > MAX_BYTES) throw failure("Image byte limit exceeded");
            return jpeg;
        } catch (IOException exception) { throw failure("Image encoding failed"); }
    }

    private static BufferedImage decode(byte[] bytes) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_BYTES) throw failure("Invalid image byte size");
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw failure("Invalid image content");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > 8192 || height > 8192 || (long) width * height > 24_000_000)
                    throw failure("Image dimension limit exceeded");
                BufferedImage decoded = reader.read(0);
                if (decoded == null) throw failure("Invalid image content");
                return decoded;
            } finally { reader.dispose(); }
        } catch (IOException exception) { throw failure("Image decoding failed"); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> body = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody() { return body; }
        public void onSubscribe(Flow.Subscription subscription) { this.subscription = subscription; subscription.request(1); }
        public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) {
                    subscription.cancel(); body.completeExceptionally(failure("Response byte limit exceeded")); return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { body.completeExceptionally(failure("Response read failed")); }
        public void onComplete() { body.complete(bytes.toByteArray()); }
    }
}
