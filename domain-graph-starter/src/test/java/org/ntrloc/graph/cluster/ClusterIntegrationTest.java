package org.ntrloc.graph.cluster;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Two complete ntrloc nodes in one JVM, joined into one Hazelcast cluster through the tcp-ip
 * strategy on localhost, sharing one Postgres -- the shape of a real deployment behind a load
 * balancer. Started once for every scenario here. Each node has its own HTTP and Hazelcast port;
 * the nodes start one after the other, and only node A seeds the test data (seeding isn't
 * something two nodes should race to do).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("A two-node cluster")
class ClusterIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");
    private static final String ROOT = "root@example.com";
    private static final String PASSWORD = "s3cret!";
    private static final Duration PROPAGATION = Duration.ofSeconds(10);

    static {
        POSTGRES.start();
    }

    private ConfigurableApplicationContext nodeA;
    private ConfigurableApplicationContext nodeB;
    private WebTestClient a;
    private WebTestClient b;
    private String rootToken;

    @BeforeAll
    void startCluster() throws IOException {
        int hazelcastPortA = freePort();
        int hazelcastPortB = freePort();
        String members = "127.0.0.1:" + hazelcastPortA + ",127.0.0.1:" + hazelcastPortB;
        nodeA = startNode(hazelcastPortA, members, true);
        nodeB = startNode(hazelcastPortB, members, false);
        a = clientFor(nodeA);
        b = clientFor(nodeB);
        rootToken = tokenFor(ROOT);
    }

    @AfterAll
    void stopCluster() {
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Test
    @DisplayName("forms one cluster with exactly one leader")
    void formsOneClusterWithOneLeader() {
        ClusterService clusterA = nodeA.getBean(ClusterService.class);
        ClusterService clusterB = nodeB.getBean(ClusterService.class);

        assertThat(clusterA.getClusterMembers()).hasSize(2).isEqualTo(clusterB.getClusterMembers());
        assertThat(clusterA.isLeaderNode()).isNotEqualTo(clusterB.isLeaderNode());
    }

    @Test
    @DisplayName("shows a schema change made on one node on the other")
    void schemaChangeReachesTheOtherNode() {
        String itemType = "ClusterTest-" + UUID.randomUUID();

        createItemType(a, itemType);

        await().atMost(PROPAGATION).untilAsserted(() -> assertThat(adminSchema(b)).contains(itemType));
    }

    @Test
    @DisplayName("enforces a permission granted on one node on the other")
    void permissionGrantReachesTheOtherNode() {
        UUID carol = nodeA.getBean(SecurityRepository.class).findUserByEmail("carol@example.com").orElseThrow().id();
        UUID publicDoc = itemTypeId("AclTestPublicDoc");
        String carolsToken = tokenFor("carol@example.com");
        assertThat(schemaAs(b, carolsToken)).doesNotContain("AclTestPublicDoc");

        changePermission(a.post(), carol, publicDoc);
        try {
            await().atMost(PROPAGATION).untilAsserted(() -> assertThat(schemaAs(b, carolsToken)).contains("AclTestPublicDoc"));
        } finally {
            changePermission(a.method(HttpMethod.DELETE), carol, publicDoc);
        }
        await().atMost(PROPAGATION).untilAsserted(() -> assertThat(schemaAs(b, carolsToken)).doesNotContain("AclTestPublicDoc"));
    }

    @Test
    @DisplayName("delivers an event published on one node to a live connection on the other")
    void eventReachesLiveConnectionsOnTheOtherNode() throws Exception {
        HttpResponse<Stream<String>> events = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(baseUrl(nodeB) + "/api/events?types=schemaChanged"))
                        .header(HttpHeaders.AUTHORIZATION, rootToken).GET().build(),
                HttpResponse.BodyHandlers.ofLines());
        assertThat(events.statusCode()).isEqualTo(200);
        try (Stream<String> lines = events.body()) {
            CompletableFuture<Boolean> received = CompletableFuture.supplyAsync(
                    () -> lines.anyMatch(line -> line.replace(" ", "").equals("event:schemaChanged")));

            createItemType(a, "ClusterEventTest-" + UUID.randomUUID());

            assertThat(received.get(PROPAGATION.toSeconds(), TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    @DisplayName("honors a session started on one node on the other")
    void sessionStartedOnOneNodeIsValidOnTheOther() {
        String email = createLocalUser();

        ResponseCookie session = logIn(a, email);

        me(b, session).expectStatus().isOk().expectBody().jsonPath("$.email").isEqualTo(email);
    }

    @Test
    @DisplayName("ends a session everywhere when it's signed out on any node")
    void signingOutOnOneNodeEndsTheSessionEverywhere() {
        ResponseCookie session = logIn(a, createLocalUser());
        me(a, session).expectStatus().isOk();
        me(b, session).expectStatus().isOk();

        b.get().uri("/logout").cookie(session.getName(), session.getValue()).exchange().expectStatus().is3xxRedirection();

        me(a, session).expectStatus().is3xxRedirection();
        me(b, session).expectStatus().is3xxRedirection();
    }

    private static ConfigurableApplicationContext startNode(int hazelcastPort, String members, boolean seed) {
        return new SpringApplicationBuilder(TestApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--graph.security.seed-test-data=" + seed,
                "--cluster.name=cluster-test",
                "--cluster.strategy=tcp-ip",
                "--cluster.port=" + hazelcastPort,
                "--cluster.tcp-ip.members=" + members,
                "--cluster.tcp-ip.interface=127.0.0.1");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static String baseUrl(ConfigurableApplicationContext node) {
        return "http://localhost:" + node.getEnvironment().getProperty("local.server.port");
    }

    private static WebTestClient clientFor(ConfigurableApplicationContext node) {
        return WebTestClient.bindToServer().baseUrl(baseUrl(node)).responseTimeout(Duration.ofSeconds(30)).build();
    }

    private String tokenFor(String email) {
        var user = nodeA.getBean(SecurityRepository.class).findUserByEmail(email).orElseThrow();
        return "Bearer " + nodeA.getBean(PersonalAccessTokenService.class)
                .issueForUser(user.id(), "cluster-test", null).rawToken();
    }

    private UUID itemTypeId(String name) {
        return nodeA.getBean(SchemaManager.class).getAdminSchema().items().stream()
                .filter(item -> item.name().equals(name)).findFirst().orElseThrow().id();
    }

    private void createItemType(WebTestClient node, String name) {
        node.post().uri("/api/admin/schema/mutations")
                .header(HttpHeaders.AUTHORIZATION, rootToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        [{"type":"CREATE_ITEM","name":"%s","description":"d","properties":[],"supertypeId":null,"abstractType":false}]
                        """.formatted(name))
                .exchange()
                .expectStatus().isNoContent();
    }

    private void changePermission(WebTestClient.RequestBodyUriSpec request, UUID userId, UUID itemTypeId) {
        request.uri("/api/admin/users/{userId}/permissions", userId)
                .header(HttpHeaders.AUTHORIZATION, rootToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("itemTypeId", itemTypeId, "operation", PermissionService.ITEM_TYPE_READ))
                .exchange()
                .expectStatus().isNoContent();
    }

    private String adminSchema(WebTestClient node) {
        return schemaAs(node, "/api/admin/schema", rootToken);
    }

    private static String schemaAs(WebTestClient node, String token) {
        return schemaAs(node, "/api/schema", token);
    }

    private static String schemaAs(WebTestClient node, String path, String token) {
        return node.get().uri(path).header(HttpHeaders.AUTHORIZATION, token).exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
    }

    private String createLocalUser() {
        SecurityRepository users = nodeA.getBean(SecurityRepository.class);
        var user = users.createUser("Cluster User", "cluster-" + UUID.randomUUID() + "@example.com", false);
        users.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode(PASSWORD));
        return user.email();
    }

    private static ResponseCookie logIn(WebTestClient node, String email) {
        ResponseCookie session = node.post().uri("/login")
                .body(BodyInserters.fromFormData("username", email).with("password", PASSWORD))
                .exchange()
                .expectStatus().is3xxRedirection()
                .returnResult(Void.class)
                .getResponseCookies().getFirst("SESSION");
        assertThat(session).isNotNull();
        return session;
    }

    private static WebTestClient.ResponseSpec me(WebTestClient node, ResponseCookie session) {
        return node.get().uri("/api/account/me").cookie(session.getName(), session.getValue()).exchange();
    }
}
