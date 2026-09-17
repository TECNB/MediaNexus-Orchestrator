package com.medianexus.orchestrator.integration.emby;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medianexus.orchestrator.config.EmbyProperties;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EmbyClientTest {

    private HttpServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void refreshesOnlyTheSelectedLibraryRecursively() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        AtomicReference<String> token = new AtomicReference<>();
        server.createContext("/Items/library-id/Refresh", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            token.set(exchange.getRequestHeaders().getFirst("X-Emby-Token"));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.start();

        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("emby-token");
        properties.setTimeout(Duration.ofSeconds(2));
        EmbyClient client = new EmbyClient(properties, new ObjectMapper());

        client.refreshLibrary("library-id");

        assertThat(method.get()).isEqualTo("POST");
        assertThat(token.get()).isEqualTo("emby-token");
        assertThat(queryParameters(query.get())).containsExactlyInAnyOrder(
                "Recursive=true",
                "MetadataRefreshMode=Default",
                "ImageRefreshMode=Default",
                "ReplaceAllMetadata=false",
                "ReplaceAllImages=false"
        );
    }

    @Test
    void scopesCollectionCreationToTheSelectedLibrary() {
        AtomicReference<String> method = new AtomicReference<>();
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/Collections", exchange -> {
            method.set(exchange.getRequestMethod());
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = "{\"Id\":\"collection-id\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("emby-token");
        properties.setTimeout(Duration.ofSeconds(2));
        EmbyClient client = new EmbyClient(properties, new ObjectMapper());

        String collectionId = client.createCollection(
                "Private",
                List.of("item-a", "item-b"),
                "adult-library"
        );

        assertThat(collectionId).isEqualTo("collection-id");
        assertThat(method.get()).isEqualTo("POST");
        assertThat(queryParameters(query.get())).containsExactlyInAnyOrder(
                "Name=Private",
                "Ids=item-a%2Citem-b",
                "ParentId=adult-library"
        );
    }

    @Test
    void scopesCollectionListingToTheSelectedLibrary() {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/Items", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    {"Items":[{"Id":"collection-id","Name":"Private","Type":"BoxSet"}]}
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("emby-token");
        properties.setTimeout(Duration.ofSeconds(2));
        EmbyClient client = new EmbyClient(properties, new ObjectMapper());

        List<EmbyCollection> collections = client.listCollections("adult-library");

        assertThat(collections).containsExactly(new EmbyCollection("collection-id", "Private"));
        assertThat(queryParameters(query.get())).contains(
                "IncludeItemTypes=BoxSet",
                "ParentId=adult-library"
        );
    }

    @Test
    void downloadsAndUploadsPrimaryImageThroughTheEmbyImageApi() throws IOException {
        byte[] sourceImage = new byte[]{1, 2, 3, 4};
        byte[] uploadedImage = new byte[]{5, 6, 7, 8};
        AtomicReference<String> uploadMethod = new AtomicReference<>();
        AtomicReference<String> uploadContentType = new AtomicReference<>();
        AtomicReference<String> uploadToken = new AtomicReference<>();
        AtomicReference<byte[]> uploadBody = new AtomicReference<>();
        server.createContext("/Items/item-id/Images/Primary", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Content-Type", "image/png");
                exchange.sendResponseHeaders(200, sourceImage.length);
                exchange.getResponseBody().write(sourceImage);
            } else {
                uploadMethod.set(exchange.getRequestMethod());
                uploadContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                uploadToken.set(exchange.getRequestHeaders().getFirst("X-Emby-Token"));
                uploadBody.set(exchange.getRequestBody().readAllBytes());
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.start();

        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("emby-token");
        properties.setTimeout(Duration.ofSeconds(2));
        EmbyClient client = new EmbyClient(properties, new ObjectMapper());

        EmbyPrimaryImage image = client.getPrimaryImageWithContentType("item-id");
        assertThat(image.bytes()).containsExactly(sourceImage);
        assertThat(image.contentType()).isEqualTo("image/png");
        client.uploadPrimaryImage("item-id", uploadedImage);

        assertThat(uploadMethod.get()).isEqualTo("POST");
        assertThat(uploadContentType.get()).isEqualTo("image/jpeg");
        assertThat(uploadToken.get()).isEqualTo("emby-token");
        assertThat(uploadBody.get()).containsExactly(Base64.getEncoder().encode(uploadedImage));
    }

    @Test
    void listsMediaLibraryItemsWithSearchPagingAndDateCreatedOrder() {
        AtomicReference<String> query = new AtomicReference<>();
        server.createContext("/Items", exchange -> {
            query.set(exchange.getRequestURI().getRawQuery());
            byte[] body = """
                    {
                      "Items": [{
                        "Id": "movie-1",
                        "Name": "Movie title",
                        "Type": "Movie",
                        "ProductionYear": 2026,
                        "DateCreated": "2026-07-20T12:34:56.0000000Z",
                        "ImageTags": {"Primary": "tag"}
                      }],
                      "TotalRecordCount": 25
                    }
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        EmbyClient client = new EmbyClient(properties(), new ObjectMapper());

        EmbyMediaLibraryPage page = client.listTopLevelMediaItems(
                "movies-id",
                "Movie",
                24,
                24,
                " Movie title "
        );

        assertThat(page.totalRecordCount()).isEqualTo(25);
        assertThat(page.items()).containsExactly(new EmbyMediaLibraryItem(
                "movie-1",
                "Movie title",
                "Movie",
                2026,
                "2026-07-20T12:34:56.0000000Z",
                "tag"
        ));
        assertThat(queryParameters(query.get())).contains(
                "ParentId=movies-id",
                "Recursive=true",
                "IncludeItemTypes=Movie",
                "Fields=DateCreated%2CProductionYear%2CImageTags",
                "GroupItemsIntoCollections=false",
                "SortBy=DateCreated",
                "SortOrder=Descending",
                "StartIndex=24",
                "Limit=24",
                "SearchTerm=Movie+title"
        );
    }

    @Test
    void rejectsIncompleteMediaLibraryResponseInsteadOfTreatingItAsEmpty() {
        server.createContext("/Items", exchange -> {
            byte[] body = "{}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();

        EmbyClient client = new EmbyClient(properties(), new ObjectMapper());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.listTopLevelMediaItems(
                        "movies-id",
                        "Movie",
                        0,
                        24,
                        null
                ))
                .isInstanceOf(EmbyClientException.class)
                .hasMessage("Emby media library response is incomplete");
    }

    @Test
    void searchesAndAppliesRemoteMetadataCandidate() throws Exception {
        AtomicReference<String> searchBody = new AtomicReference<>();
        AtomicReference<String> applyBody = new AtomicReference<>();
        AtomicReference<String> applyQuery = new AtomicReference<>();
        server.createContext("/Items/RemoteSearch/Series", exchange -> {
            searchBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] body = """
                    [{"Name":"强风吹拂","ProductionYear":2018,
                      "ProviderIds":{"Tvdb":"352853","Tmdb":"82766"},
                      "SearchProviderName":"TheTVDB","ImageUrl":"https://images/poster.jpg"}]
                    """.getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/Items/RemoteSearch/Apply/item-1", exchange -> {
            applyQuery.set(exchange.getRequestURI().getRawQuery());
            applyBody.set(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        EmbyClient client = new EmbyClient(properties(), new ObjectMapper());

        List<EmbyRemoteSearchCandidate> candidates = client.searchRemoteMetadata(
                "item-1", "Series", "强风吹拂", 2018
        );
        client.applyRemoteMetadata("item-1", candidates.get(0), true);

        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(searchBody.get()).path("SearchInfo").path("Year").asInt())
                .isEqualTo(2018);
        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.providerIds()).containsEntry("Tvdb", "352853");
            assertThat(candidate.candidateId()).hasSize(64);
        });
        assertThat(applyQuery.get()).isEqualTo("ReplaceAllImages=true");
        assertThat(mapper.readTree(applyBody.get()).path("ProviderIds").path("Tmdb").asText())
                .isEqualTo("82766");
    }

    @Test
    void listsAndDownloadsOnlySelectedRemotePrimaryImage() throws Exception {
        AtomicReference<String> downloadQuery = new AtomicReference<>();
        AtomicReference<String> previewToken = new AtomicReference<>();
        server.createContext("/Items/item-1/RemoteImages", exchange -> {
            if ("GET".equals(exchange.getRequestMethod())) {
                byte[] body = """
                        {"Images":[{"ProviderName":"TheTVDB","Url":"https://images/poster.jpg",
                          "ThumbnailUrl":"https://images/thumb.jpg","Width":680,"Height":1000,
                          "Language":"zh","Type":"Primary"}],"TotalRecordCount":1}
                        """.getBytes();
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                downloadQuery.set(exchange.getRequestURI().getRawQuery());
                exchange.sendResponseHeaders(204, -1);
            }
            exchange.close();
        });
        server.createContext("/Items/item-1/RemoteImages/Download", exchange -> {
            downloadQuery.set(exchange.getRequestURI().getRawQuery());
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/Items/RemoteSearch/Image", exchange -> {
            previewToken.set(exchange.getRequestHeaders().getFirst("X-Emby-Token"));
            exchange.getResponseHeaders().set("Content-Type", "image/jpeg");
            byte[] body = new byte[]{1, 2, 3};
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        EmbyClient client = new EmbyClient(properties(), new ObjectMapper());

        List<EmbyRemoteImageCandidate> candidates = client.listRemotePrimaryImages("item-1");
        EmbyPrimaryImage preview = client.getRemoteImage("TheTVDB", "https://images/poster.jpg");
        client.downloadRemotePrimaryImage("item-1", candidates.get(0));

        assertThat(candidates).singleElement().satisfies(candidate -> {
            assertThat(candidate.providerName()).isEqualTo("TheTVDB");
            assertThat(candidate.candidateId()).hasSize(64);
        });
        assertThat(queryParameters(downloadQuery.get())).containsExactlyInAnyOrder(
                "Type=Primary",
                "ProviderName=TheTVDB",
                "ImageUrl=https%3A%2F%2Fimages%2Fposter.jpg"
        );
        assertThat(preview.bytes()).containsExactly(1, 2, 3);
        assertThat(preview.contentType()).isEqualTo("image/jpeg");
        assertThat(previewToken.get()).isEqualTo("emby-token");
    }

    @Test
    void createsAUserByCopyingOnlyTheTemplatePolicyAndSetsItsPassword() throws Exception {
        AtomicReference<String> createBody = new AtomicReference<>();
        AtomicReference<String> passwordBody = new AtomicReference<>();
        AtomicReference<String> deleteMethod = new AtomicReference<>();
        server.createContext("/Users/New", exchange -> {
            createBody.set(new String(exchange.getRequestBody().readAllBytes()));
            byte[] body = "{\"Id\":\"new-user-id\",\"Name\":\"alice\"}".getBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/Users/new-user-id/Password", exchange -> {
            passwordBody.set(new String(exchange.getRequestBody().readAllBytes()));
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.createContext("/Users/new-user-id", exchange -> {
            deleteMethod.set(exchange.getRequestMethod());
            exchange.sendResponseHeaders(204, -1);
            exchange.close();
        });
        server.start();
        EmbyClient client = new EmbyClient(properties(), new ObjectMapper());

        EmbyUserAccount created = client.createUserFromTemplate("alice", "template-id");
        client.updateUserPassword(created.id(), "ABCDEFGH");
        client.deleteUser(created.id());

        ObjectMapper mapper = new ObjectMapper();
        assertThat(mapper.readTree(createBody.get()).path("Name").asText()).isEqualTo("alice");
        assertThat(mapper.readTree(createBody.get()).path("CopyFromUserId").asText()).isEqualTo("template-id");
        assertThat(mapper.readTree(createBody.get()).path("UserCopyOptions").get(0).asText())
                .isEqualTo("UserPolicy");
        assertThat(mapper.readTree(passwordBody.get()).path("NewPw").asText()).isEqualTo("ABCDEFGH");
        assertThat(mapper.readTree(passwordBody.get()).path("ResetPassword").asBoolean()).isFalse();
        assertThat(deleteMethod.get()).isEqualTo("DELETE");
    }

    private EmbyProperties properties() {
        EmbyProperties properties = new EmbyProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setApiKey("emby-token");
        properties.setTimeout(Duration.ofSeconds(2));
        return properties;
    }

    private Set<String> queryParameters(String query) {
        return Arrays.stream(query.split("&")).collect(Collectors.toSet());
    }
}
