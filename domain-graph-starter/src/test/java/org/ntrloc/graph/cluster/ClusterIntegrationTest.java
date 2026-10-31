package org.ntrloc.graph.cluster;

import com.hazelcast.core.HazelcastInstance;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.TestDatabases;
import org.ntrloc.graph.db.partition.authorization.PermissionService;
import org.ntrloc.graph.db.partition.schema.SchemaManager;
import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.ntrloc.graph.security.KeycloakContainers;
import org.ntrloc.graph.security.OidcProviderService;
import org.ntrloc.graph.security.SamlProviderService;
import org.ntrloc.graph.security.SamlProviderService.SamlProvider;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.util.UriComponentsBuilder;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
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

    // One database for every node: they're one cluster.
    private static final String DATABASE = TestDatabases.create("cluster");
    private static final GenericContainer<?> KEYCLOAK = KeycloakContainers.shared();
    private static final String ROOT = "root@example.com";
    private static final String PASSWORD = "s3cret!";
    private static final String SSO_USER = "existing.user";
    private static final String SSO_EMAIL = "existing.user@example.org";
    private static final String SAML_ID = "corporate-saml";
    private static final Duration PROPAGATION = Duration.ofSeconds(10);

    private String members;
    private ConfigurableApplicationContext nodeA;
    private ConfigurableApplicationContext nodeB;
    private WebTestClient a;
    private WebTestClient b;
    private String rootToken;
    private LoadBalancer loadBalancer;

    @BeforeAll
    void startCluster() throws IOException {
        int hazelcastPortA = freePort();
        int hazelcastPortB = freePort();
        members = "127.0.0.1:" + hazelcastPortA + ",127.0.0.1:" + hazelcastPortB;
        nodeA = startNode(hazelcastPortA, members, true);
        nodeB = startNode(hazelcastPortB, members, false);
        a = clientFor(nodeA);
        b = clientFor(nodeB);
        rootToken = tokenFor(ROOT);
        loadBalancer = new LoadBalancer(httpPort(nodeA), httpPort(nodeB));
    }

    @AfterAll
    void stopCluster() throws IOException {
        if (loadBalancer != null) {
            loadBalancer.close();
        }
        if (nodeB != null) {
            nodeB.close();
        }
        if (nodeA != null) {
            nodeA.close();
        }
    }

    @Nested
    @DisplayName("keeps its nodes in step:")
    class Coordination {

        @Test
        @DisplayName("one cluster with exactly one leader")
        void formsOneClusterWithOneLeader() {
            ClusterService clusterA = nodeA.getBean(ClusterService.class);
            ClusterService clusterB = nodeB.getBean(ClusterService.class);

            assertThat(clusterA.getClusterMembers()).hasSize(2).isEqualTo(clusterB.getClusterMembers());
            assertThat(clusterA.isLeaderNode()).isNotEqualTo(clusterB.isLeaderNode());
        }

        @Test
        @DisplayName("a schema change made on one node shows on the other")
        void schemaChangeReachesTheOtherNode() {
            String itemType = "ClusterTest-" + UUID.randomUUID();

            createItemType(a, itemType);

            await().atMost(PROPAGATION).untilAsserted(() -> assertThat(schemaAs(b, "/api/admin/schema", rootToken)).contains(itemType));
        }

        @Test
        @DisplayName("a permission granted on one node is enforced on the other")
        void permissionGrantReachesTheOtherNode() {
            UUID carol = nodeA.getBean(SecurityRepository.class).findUserByEmail("carol@example.com").orElseThrow().id();
            UUID publicDoc = itemTypeId("AclTestPublicDoc");
            String carolsToken = tokenFor("carol@example.com");
            assertThat(schemaAs(b, "/api/schema", carolsToken)).doesNotContain("AclTestPublicDoc");

            changePermission(a.post(), carol, publicDoc);
            try {
                await().atMost(PROPAGATION).untilAsserted(() -> assertThat(schemaAs(b, "/api/schema", carolsToken)).contains("AclTestPublicDoc"));
            } finally {
                changePermission(a.method(HttpMethod.DELETE), carol, publicDoc);
            }
            await().atMost(PROPAGATION).untilAsserted(() -> assertThat(schemaAs(b, "/api/schema", carolsToken)).doesNotContain("AclTestPublicDoc"));
        }

        @Test
        @DisplayName("an event published on one node reaches live connections on the other")
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
    }

    @Nested
    @DisplayName("shares sessions between its nodes:")
    class Sessions {

        @Test
        @DisplayName("a session started on one node is honored on the other")
        void sessionStartedOnOneNodeIsValidOnTheOther() {
            String email = createLocalUser();

            ResponseCookie session = logIn(a, email);

            me(b, session.getValue()).expectStatus().isOk().expectBody().jsonPath("$.email").isEqualTo(email);
        }

        @Test
        @DisplayName("signing out on any node ends the session everywhere")
        void signingOutOnOneNodeEndsTheSessionEverywhere() {
            ResponseCookie session = logIn(a, createLocalUser());
            me(a, session.getValue()).expectStatus().isOk();
            me(b, session.getValue()).expectStatus().isOk();

            b.get().uri("/logout").cookie(session.getName(), session.getValue()).exchange().expectStatus().is3xxRedirection();

            me(a, session.getValue()).expectStatus().is3xxRedirection();
            me(b, session.getValue()).expectStatus().is3xxRedirection();
        }

        // The sign-in's state -- which request the provider is answering -- lives in the session.
        @Test
        @DisplayName("an OIDC sign-in started on one node can finish on the other")
        void oidcSignInCanFinishOnTheOtherNode() {
            assertSignInFinishesOnTheOtherNode("/oauth2/authorization/" + KeycloakContainers.REGISTRATION_ID);
        }

        // SAML also has the provider's response arrive as a POST, checked against the saved request.
        @Test
        @DisplayName("a SAML sign-in started on one node can finish on the other")
        void samlSignInCanFinishOnTheOtherNode() {
            assertSignInFinishesOnTheOtherNode("/saml2/authenticate/" + SAML_ID);
        }

        private void assertSignInFinishesOnTheOtherNode(String signInPath) {
            registerSsoProviders();
            SsoBrowser browser = new SsoBrowser(loadBalancer);

            browser.sendTo(LoadBalancer.NODE_A);
            HttpResponse<String> loginForm = browser.open(loadBalancer.url(signInPath));
            browser.sendTo(LoadBalancer.NODE_B);
            HttpResponse<String> end = browser.signIn(loginForm, SSO_USER, KeycloakContainers.USER_PASSWORD);

            assertThat(end.uri().toString()).as("where the sign-in ended").doesNotContain("error");
            String session = browser.session(loadBalancer.url("/")).orElseThrow();
            me(a, session).expectStatus().isOk().expectBody().jsonPath("$.email").isEqualTo(SSO_EMAIL);
            me(b, session).expectStatus().isOk();
        }
    }

    @Nested
    @DisplayName("applies sign-in provider changes on every node:")
    class SignInProviders {

        @BeforeEach
        void registerProviders() {
            registerSsoProviders();
        }

        // Each node caches a provider's settings until it's saved again. Node B has them cached
        // from a sign-in it already started, so only a save made on node A can change its mind.
        @Test
        @DisplayName("an OIDC provider edited on one node signs in with the new settings on the other")
        void editingAnOidcProviderReachesTheOtherNode() {
            String signIn = "/oauth2/authorization/" + KeycloakContainers.REGISTRATION_ID;
            assertThat(reachesTheProviderThroughNodeB(signIn)).isTrue();

            OidcProviderService providers = nodeA.getBean(OidcProviderService.class);
            var current = providers.list().stream()
                    .filter(p -> p.provider().registrationId().equals(KeycloakContainers.REGISTRATION_ID))
                    .findFirst().orElseThrow().provider();
            providers.update(current.registrationId(), new OidcProviderService.OidcProvider(current.registrationId(),
                    current.displayName(), current.issuerUri(), "a-client-keycloak-doesnt-know", current.scopes(), true), null, null);

            assertThat(reachesTheProviderThroughNodeB(signIn)).as("Keycloak's login form, for an unknown client").isFalse();
        }

        @Test
        @DisplayName("a SAML provider edited on one node signs in with the new settings on the other")
        void editingASamlProviderReachesTheOtherNode() {
            String signIn = "/saml2/authenticate/" + SAML_ID;
            assertThat(reachesTheProviderThroughNodeB(signIn)).isTrue();

            nodeA.getBean(SamlProviderService.class).update(SAML_ID, new SamlProvider(SAML_ID, "Corporate SSO",
                    KeycloakContainers.samlMetadataUri(KEYCLOAK).replace("/realms/ntrloc/", "/realms/no-such-realm/"),
                    "email", "displayName", true), null);

            assertThat(reachesTheProviderThroughNodeB(signIn)).as("Keycloak's login form, for metadata that isn't there").isFalse();
        }

        private boolean reachesTheProviderThroughNodeB(String signInPath) {
            SsoBrowser browser = new SsoBrowser(loadBalancer);
            browser.sendTo(LoadBalancer.NODE_B);
            return browser.showsLoginForm(browser.open(loadBalancer.url(signInPath)));
        }
    }

    // The load balancer terminates HTTPS; the nodes see plain HTTP plus its X-Forwarded-* headers,
    // and must describe themselves by the public address the browser used.
    @Nested
    @DisplayName("behind an HTTPS load balancer:")
    class BehindTheLoadBalancer {

        @Test
        @DisplayName("sends the browser to the login page at the public address")
        void redirectsToThePublicAddress() {
            HttpResponse<String> response = new SsoBrowser(loadBalancer).get(loadBalancer.url("/api/account/me"));

            assertThat(response.statusCode()).isEqualTo(302);
            assertThat(response.headers().firstValue("Location")).hasValue(loadBalancer.url("/login"));
        }

        @Test
        @DisplayName("marks the session cookie Secure")
        void sessionCookieIsSecure() {
            String email = createLocalUser();

            HttpResponse<String> response = new SsoBrowser(loadBalancer).post(loadBalancer.url("/login"),
                    "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=" + PASSWORD);

            assertThat(response.headers().allValues("Set-Cookie"))
                    .anySatisfy(cookie -> assertThat(cookie).startsWith("SESSION=").containsIgnoringCase("; Secure"));
        }

        @Test
        @DisplayName("gives the OIDC provider a redirect URI at the public address")
        void oidcRedirectUriIsPublic() {
            registerSsoProviders();

            HttpResponse<String> response = new SsoBrowser(loadBalancer)
                    .get(loadBalancer.url("/oauth2/authorization/" + KeycloakContainers.REGISTRATION_ID));

            String redirectUri = UriComponentsBuilder.fromUriString(response.headers().firstValue("Location").orElseThrow())
                    .build().getQueryParams().getFirst("redirect_uri");
            assertThat(URLDecoder.decode(redirectUri, StandardCharsets.UTF_8))
                    .isEqualTo(loadBalancer.url("/login/oauth2/code/" + KeycloakContainers.REGISTRATION_ID));
        }

        @Test
        @DisplayName("publishes SAML metadata at the public address")
        void samlMetadataIsPublic() {
            registerSsoProviders();

            String metadata = new SsoBrowser(loadBalancer).get(loadBalancer.url("/saml2/metadata/" + SAML_ID)).body();

            assertThat(metadata)
                    .contains("entityID=\"" + loadBalancer.url("/saml2/metadata/" + SAML_ID) + "\"")
                    .contains("Location=\"" + loadBalancer.url("/login/saml2/sso/" + SAML_ID) + "\"");
        }

        // No pinning: nginx's round-robin decides, and its access log shows both nodes took part.
        @Test
        @DisplayName("keeps a browser signed in whichever node serves it")
        void staysSignedInAcrossRoundRobin() {
            String email = createLocalUser();
            SsoBrowser browser = new SsoBrowser(loadBalancer);
            browser.post(loadBalancer.url("/login"), "username=" + URLEncoder.encode(email, StandardCharsets.UTF_8) + "&password=" + PASSWORD);
            String me = "/api/account/me?check=" + UUID.randomUUID();

            for (int i = 0; i < 6; i++) {
                HttpResponse<String> response = browser.get(loadBalancer.url(me));
                assertThat(response.statusCode()).isEqualTo(200);
                assertThat(response.body()).contains(email);
            }

            assertThat(loadBalancer.nodesThatServed(me)).containsExactlyInAnyOrder(httpPort(nodeA), httpPort(nodeB));
        }
    }

    @Nested
    @DisplayName("survives losing a node:")
    class LosingANode {

        // A third node joins, holds some of the sessions, and crashes -- no graceful hand-off, so
        // only the backup copies Hazelcast keeps on the other nodes can save those sessions.
        @Test
        @DisplayName("sessions held by a crashed node live on in the others")
        void sessionsSurviveANodeCrash() throws IOException {
            int hazelcastPortC = freePort();
            ConfigurableApplicationContext nodeC = startNode(hazelcastPortC, members + ",127.0.0.1:" + hazelcastPortC, false);
            try {
                HazelcastInstance hazelcastC = nodeC.getBean(ClusterService.class).getHazelcastInstance();
                await().atMost(PROPAGATION).until(() -> nodeA.getBean(ClusterService.class).getClusterMembers().size() == 3);
                String email = createLocalUser();
                List<String> sessions = IntStream.range(0, 20).mapToObj(i -> logIn(a, email).getValue()).toList();
                assertThat(sessions).as("sessions node C holds").anyMatch(session -> heldBy(hazelcastC, session));

                hazelcastC.getLifecycleService().terminate();

                await().atMost(Duration.ofSeconds(30)).until(() -> nodeA.getBean(ClusterService.class).getClusterMembers().size() == 2);
                for (String session : sessions) {
                    me(a, session).expectStatus().isOk();
                    me(b, session).expectStatus().isOk();
                }
                assertThat(nodeA.getBean(ClusterService.class).isLeaderNode())
                        .isNotEqualTo(nodeB.getBean(ClusterService.class).isLeaderNode());
            } finally {
                nodeC.close();
            }
        }

        // The cookie carries the session ID base64-encoded; Hazelcast keys the session by the ID.
        private boolean heldBy(HazelcastInstance node, String sessionCookie) {
            String sessionId = new String(Base64.getDecoder().decode(sessionCookie), StandardCharsets.UTF_8);
            return node.getCluster().getLocalMember().equals(node.getPartitionService().getPartition(sessionId).getOwner());
        }
    }

    private static ConfigurableApplicationContext startNode(int hazelcastPort, String members, boolean seed) {
        return new SpringApplicationBuilder(TestApplication.class).run(
                "--server.port=0",
                "--spring.datasource.url=" + DATABASE,
                "--spring.datasource.username=" + TestDatabases.username(),
                "--spring.datasource.password=" + TestDatabases.password(),
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
        return "http://localhost:" + httpPort(node);
    }

    private static WebTestClient clientFor(ConfigurableApplicationContext node) {
        return WebTestClient.bindToServer().baseUrl(baseUrl(node)).responseTimeout(Duration.ofSeconds(30)).build();
    }

    private static int httpPort(ConfigurableApplicationContext node) {
        return Integer.parseInt(node.getEnvironment().getProperty("local.server.port"));
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

    private static WebTestClient.ResponseSpec me(WebTestClient node, String session) {
        return node.get().uri("/api/account/me").cookie("SESSION", session).exchange();
    }

    // Keycloak as both an OIDC and a SAML provider, saved through node A as an admin would, and
    // ntrloc registered at Keycloak by its metadata at the load balancer's address. Re-enables
    // whatever a previous scenario changed.
    private void registerSsoProviders() {
        KeycloakContainers.register(nodeA.getBean(OidcProviderService.class), KeycloakContainers.issuerUri(KEYCLOAK));
        SamlProviderService saml = nodeA.getBean(SamlProviderService.class);
        if (saml.list().stream().anyMatch(p -> p.provider().registrationId().equals(SAML_ID))) {
            saml.update(SAML_ID, samlProvider(true), null);
        } else {
            saml.create(samlProvider(true), null);
        }
        String metadata = new SsoBrowser(loadBalancer).get(loadBalancer.url("/saml2/metadata/" + SAML_ID)).body();
        KeycloakContainers.registerSamlClientFromMetadata(KEYCLOAK, metadata, false);
        SecurityRepository users = nodeA.getBean(SecurityRepository.class);
        users.findUserByEmail(SSO_EMAIL).orElseGet(() -> users.createUser("SSO User", SSO_EMAIL, false));
    }

    private static SamlProvider samlProvider(boolean enabled) {
        return new SamlProvider(SAML_ID, "Corporate SSO", KeycloakContainers.samlMetadataUri(KEYCLOAK), "email", "displayName", enabled);
    }
}
