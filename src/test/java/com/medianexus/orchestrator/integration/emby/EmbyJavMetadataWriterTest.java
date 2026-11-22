package com.medianexus.orchestrator.integration.emby;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.medianexus.orchestrator.integration.javdb.JavdbMetadataSnapshot;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbyJavMetadataWriterTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final JavdbMetadataSnapshot source = new JavdbMetadataSnapshot("ABC-123", "abc123",
            "https://javdb.example/v/abc123", "Verified title", "https://covers.example/a.jpg?token=a&b=2",
            "2025-03-04", List.of("Actor A"), List.of("Director B"), List.of("Genre C"), List.of("Studio D"), 8.2);
    private HttpServer server;
    private EmbyJavMetadataWriter writer;
    private ObjectNode item;
    private ObjectNode posted;
    private final List<String> requests = new ArrayList<>();
    private final List<String> deletes = new ArrayList<>();
    private final Map<String, byte[]> images = new HashMap<>();
    private final List<byte[]> backdrops = new ArrayList<>();
    private byte[] image;
    private String imageContentType = "image/png";
    private boolean corruptReadback;
    private boolean corruptMetadata;
    private boolean moveAfterMetadata;
    private boolean failBackdrop;
    private int imageStatus = 200;
    private int writes;
    private int pathChecks;

    @BeforeEach
    void start() throws Exception {
        image = png(12, 18);
        item = (ObjectNode) mapper.readTree("""
                {"Id":"42","Path":"/adult/ABC-123.strm","Name":"Wrong movie","Overview":"Wrong plot",
                 "ProviderIds":{"MetaTube":"wrong","TrailerUrl":"wrong","Tmdb":"999"},
                 "People":[{"Name":"Wrong actor","Type":"Actor"}],"Tags":["favorite","中文字幕","破解"],
                 "RemoteTrailers":[{"Url":"https://wrong.example/trailer"}],"TrailerUrl":"https://wrong.example",
                 "UserData":{"PlayCount":7,"PlaybackPositionTicks":123,"Played":true},
                 "CustomRating":"personal","RunTimeTicks":98765,"LockedFields":["Runtime"]}
                """);
        backdrops.add(image);
        backdrops.add(image);
        backdrops.add(image);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { handle(exchange); }
            catch (Exception exception) { reply(exchange, 500, "test fixture failed".getBytes(StandardCharsets.UTF_8)); }
        });
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl(base);
        properties.setApiKey("private-test-key");
        writer = new EmbyJavMetadataWriter(properties, base, Duration.ofSeconds(2), mapper);
    }

    @AfterEach
    void stop() { server.stop(0); }

    @Test
    void preparesExplicitImagePipeUrlsAndOnlyUsesSubtitleBadgeWhenPresent() {
        var artwork = writer.prepareImages(source, true);
        assertThat(artwork.primary()).startsWith((byte) 0xff, (byte) 0xd8);
        assertThat(requests).hasSize(3);
        for (int index = 0; index < 3; index++) {
            String role = List.of("primary", "thumb", "backdrop").get(index);
            assertThat(requests.get(index)).startsWith("GET /v1/images/" + role + "/JavBus/abc123?")
                    .contains("url=https://covers.example/a.jpg?token=a&b=2", "pos=1");
            if (index == 0) assertThat(requests.get(index)).contains("badge=zimu.png");
            else assertThat(requests.get(index)).doesNotContain("badge=");
        }
        assertThat(requests.get(0)).contains("ratio=0.6666666666666666");
        requests.clear();
        writer.prepareImages(source, false);
        assertThat(requests).hasSize(3).allSatisfy(request -> assertThat(request).doesNotContain("badge="));
    }

    @Test
    void replacesIdentityAndEveryBackdropAndVerifiesAllRolesWithoutPostingPlaybackData() {
        var artwork = writer.prepareImages(source, true);
        writer.writeAndVerify("42", "/adult/ABC-123.strm", source, true, false, artwork);
        assertThat(posted.has("UserData")).isFalse();
        assertThat(item.path("UserData").path("PlayCount").asInt()).isEqualTo(7);
        assertThat(posted.path("CustomRating").asText()).isEqualTo("personal");
        assertThat(posted.path("RunTimeTicks").asLong()).isEqualTo(98765);
        assertThat(posted.path("LockedFields").get(0).asText()).isEqualTo("Runtime");
        assertThat(posted.path("Name").asText()).isEqualTo("ABC-123 Verified title");
        assertThat(posted.path("ProviderIds")).isEqualTo(mapper.createObjectNode().put("JavDB", "abc123"));
        assertThat(posted.path("People").get(0).path("Name").asText()).isEqualTo("Actor A");
        assertThat(posted.path("People").get(1).path("Type").asText()).isEqualTo("Director");
        assertThat(posted.path("Studios").get(0).path("Name").asText()).isEqualTo("Studio D");
        assertThat(posted.path("Tags")).isEqualTo(mapper.valueToTree(List.of("favorite", "中文字幕")));
        assertThat(posted.path("Overview").asText()).isEmpty();
        assertThat(posted.path("RemoteTrailers")).isEmpty();
        assertThat(posted.has("TrailerUrl")).isFalse();
        assertThat(posted.path("LockData").asBoolean()).isTrue();
        assertThat(deletes).containsExactly("2", "1", "0");
        assertThat(backdrops).hasSize(1);
        assertThat(images.get("Primary")).containsExactly(artwork.primary());
        assertThat(images.get("Thumb")).containsExactly(artwork.thumb());
        assertThat(backdrops.get(0)).containsExactly(artwork.backdrop());
        assertThat(requests).contains("GET /Items/42/Images/Primary", "GET /Items/42/Images/Thumb",
                "GET /Items/42/Images/Backdrop/0");
        int beforeVerification = writes;
        writer.verify("42", "/adult/ABC-123.strm", source, true, false, artwork);
        assertThat(writes).isEqualTo(beforeVerification);
        assertThat(pathChecks).isGreaterThanOrEqualTo(writes);
        writer.writeAndVerify("42", "/adult/ABC-123.strm", source, false, true, artwork);
        assertThat(backdrops).hasSize(1);
        assertThat(posted.path("Tags")).isEqualTo(mapper.valueToTree(List.of("favorite", "破解")));
    }

    @Test
    void wrongPathNeverWrites() {
        var artwork = writer.prepareImages(source, false);
        assertThatThrownBy(() -> writer.writeAndVerify("42", "/adult/other.strm", source, false, false, artwork))
                .hasMessage("Emby exact item path verification failed");
        assertThat(writes).isZero();
    }

    @Test
    void pathIsRecheckedBeforeImages() {
        var artwork = writer.prepareImages(source, false);
        moveAfterMetadata = true;
        assertThatThrownBy(() -> writer.writeAndVerify("42", "/adult/ABC-123.strm", source, false, false, artwork))
                .hasMessage("Emby exact item path verification failed");
        assertThat(writes).isEqualTo(1);
        assertThat(images).isEmpty();
    }

    @Test
    void partialFailureCanBeRetriedWithoutDuplicateBackdrops() {
        var artwork = writer.prepareImages(source, false);
        failBackdrop = true;
        assertThatThrownBy(() -> writer.writeAndVerify("42", "/adult/ABC-123.strm", source, false, false, artwork))
                .hasMessageContaining("status 503");
        assertThat(backdrops).isEmpty();
        failBackdrop = false;
        writer.writeAndVerify("42", "/adult/ABC-123.strm", source, false, false, artwork);
        assertThat(backdrops).hasSize(1);
    }

    @Test
    void rejectsInvalidImagesAndStatusWithoutWrites() throws Exception {
        image = "not an image".getBytes(StandardCharsets.UTF_8);
        assertThatThrownBy(() -> writer.prepareImages(source, false)).hasMessage("Invalid image content");
        imageContentType = "text/html";
        assertThatThrownBy(() -> writer.prepareImages(source, false)).hasMessage("Image content type rejected");
        imageStatus = 403;
        assertThatThrownBy(() -> writer.prepareImages(source, false)).hasMessageContaining("status 403");
        imageStatus = 200;
        imageContentType = "image/png";
        image = png(8193, 1);
        assertThatThrownBy(() -> writer.prepareImages(source, false)).hasMessage("Image dimension limit exceeded");
        assertThatThrownBy(() -> writer.writeAndVerify("42", "/adult/ABC-123.strm", source, false, false,
                new EmbyJavMetadataWriter.Artwork(new byte[0], new byte[0], new byte[0])))
                .hasMessage("Invalid image byte size");
        assertThat(writes).isZero();
    }

    @Test
    void rejectsImageReadbackMismatch() {
        var artwork = writer.prepareImages(source, false);
        corruptReadback = true;
        assertThatThrownBy(() -> writer.writeAndVerify("42", "/adult/ABC-123.strm", source, false, false, artwork))
                .hasMessage("Emby image verification failed");
    }

    @Test
    void rejectsMetadataReadbackMismatch() {
        var artwork = writer.prepareImages(source, false);
        corruptMetadata = true;
        assertThatThrownBy(() -> writer.writeAndVerify("42", "/adult/ABC-123.strm", source, false, false, artwork))
                .hasMessageContaining("Emby metadata verification failed: LockData");
    }

    private void handle(HttpExchange exchange) throws Exception {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getRawQuery();
        requests.add(method + " " + path + (query == null ? "" : "?" + URLDecoder.decode(query, StandardCharsets.UTF_8)));
        if (path.startsWith("/v1/images/")) {
            assertThat(exchange.getRequestHeaders().getFirst("X-Emby-Token")).isNull();
            exchange.getResponseHeaders().add("Content-Type", imageContentType);
            reply(exchange, imageStatus, image);
            return;
        }
        assertThat(exchange.getRequestHeaders().getFirst("X-Emby-Token")).isEqualTo("private-test-key");
        assertThat(query == null ? "" : query).doesNotContain("api_key");
        if (path.equals("/Users/Query")) {
            var response = mapper.createObjectNode();
            response.putArray("Items").addObject().put("Id", "admin").putObject("Policy").put("IsAdministrator", true);
            reply(exchange, 200, mapper.writeValueAsBytes(response));
        } else if (path.equals("/Users/admin/Items/42") && method.equals("GET")) {
            pathChecks++;
            reply(exchange, 200, mapper.writeValueAsBytes(item));
        } else if (path.equals("/Items/42") && method.equals("POST")) {
            writes++;
            posted = (ObjectNode) mapper.readTree(exchange.getRequestBody());
            var userData = item.get("UserData");
            item = posted.deepCopy();
            item.set("UserData", userData);
            if (corruptMetadata) item.put("LockData", false);
            if (moveAfterMetadata) item.put("Path", "/moved.strm");
            reply(exchange, 204, null);
        } else if (path.equals("/Items/42/Images")) {
            var inventory = mapper.createArrayNode();
            for (int index = 0; index < backdrops.size(); index++)
                inventory.addObject().put("ImageType", "Backdrop").put("ImageIndex", index);
            reply(exchange, 200, mapper.writeValueAsBytes(inventory));
        } else if (path.startsWith("/Items/42/Images/")) {
            String role = path.substring("/Items/42/Images/".length());
            if (method.equals("DELETE")) {
                writes++;
                String index = role.substring("Backdrop/".length());
                deletes.add(index);
                backdrops.remove(Integer.parseInt(index));
                reply(exchange, 204, null);
            } else if (method.equals("POST")) {
                writes++;
                assertThat(exchange.getRequestHeaders().getFirst("Content-Type")).isEqualTo("image/jpeg");
                byte[] bytes = Base64.getDecoder().decode(exchange.getRequestBody().readAllBytes());
                if (role.equals("Backdrop")) {
                    if (failBackdrop) { reply(exchange, 503, new byte[0]); return; }
                    backdrops.add(bytes);
                } else images.put(role, bytes);
                reply(exchange, 204, null);
            } else {
                byte[] bytes = role.equals("Backdrop/0") ? backdrops.get(0) : images.get(role);
                reply(exchange, 200, corruptReadback ? image : bytes);
            }
        } else reply(exchange, 404, new byte[0]);
    }

    private static byte[] png(int width, int height) throws IOException {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }

    private static void reply(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body == null || body.length == 0 ? -1 : body.length);
        if (body != null && body.length > 0) exchange.getResponseBody().write(body);
        exchange.close();
    }
}
