package org.ntrloc.graph.security;

import org.ntrloc.graph.security.OidcProviderService.OidcProvider;
import org.testcontainers.containers.GenericContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.MountableFile;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * The one Keycloak server every OIDC test in this JVM shares, with the "ntrloc" realm imported
 * from oidc/ntrloc-realm.json (Keycloak requires an import file to be named after its realm).
 * Started on first use and left running until the JVM exits, like every singleton container in
 * these tests. Keycloak derives its issuer from the host name each request arrives on, so one
 * server serves tests that reach it at localhost and tests that reach it at
 * host.testcontainers.internal alike; the realm is read-only to all of them.
 */
public final class KeycloakContainers {

    public static final int PORT = 8080;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String REGISTRATION_ID = "corporate-oidc";
    public static final String CLIENT_ID = "ntrloc";
    public static final String CLIENT_SECRET = "ntrloc-client-secret";
    public static final String USER_PASSWORD = "oidc-secret";

    private KeycloakContainers() {
    }

    public static GenericContainer<?> shared() {
        return Holder.KEYCLOAK;
    }

    // Started when first referenced (the JVM's lazy class initialization), so a test run that
    // never touches OIDC never starts Keycloak.
    private static final class Holder {
        private static final GenericContainer<?> KEYCLOAK = newKeycloak();

        static {
            KEYCLOAK.start();
        }
    }

    @SuppressWarnings("resource")
    private static GenericContainer<?> newKeycloak() {
        return new GenericContainer<>("quay.io/keycloak/keycloak:26.4.2")
                .withCommand("start-dev", "--import-realm")
                .withEnv(Map.of("KC_BOOTSTRAP_ADMIN_USERNAME", "admin", "KC_BOOTSTRAP_ADMIN_PASSWORD", "admin"))
                .withCopyFileToContainer(MountableFile.forClasspathResource("oidc/ntrloc-realm.json"),
                        "/opt/keycloak/data/import/ntrloc-realm.json")
                .withExposedPorts(PORT)
                .waitingFor(Wait.forHttp("/realms/ntrloc/.well-known/openid-configuration").forPort(PORT)
                        .withStartupTimeout(Duration.ofMinutes(3)));
    }

    /**
     * Registers (or resets) the realm as the "corporate-oidc" sign-in provider, as an admin would in the
     * admin UI, with the given issuer URL.
     */
    public static void register(OidcProviderService providers, String issuerUri) {
        OidcProvider provider = new OidcProvider(REGISTRATION_ID, "Corporate SSO", issuerUri, CLIENT_ID,
                "openid profile email", true);
        if (providers.list().stream().anyMatch(p -> p.provider().registrationId().equals(REGISTRATION_ID))) {
            providers.update(REGISTRATION_ID, provider, CLIENT_SECRET, null);
        } else {
            providers.create(provider, CLIENT_SECRET, null);
        }
    }

    /**
     * Registers ntrloc as a SAML client of the realm the way an IdP admin would: by importing
     * ntrloc's metadata (from spMetadataUrl), which brings its entity ID, ACS and single logout URLs,
     * and its certificate -- so the realm checks ntrloc's signatures, and can encrypt assertions to
     * it. Replaces any client already registered under that entity ID (ntrloc's key may have been
     * regenerated). The client asserts each user's email as the NameID and as an "email" attribute,
     * and their username as "displayName"; encryptAssertions decides whether it encrypts them.
     */
    public static void registerSamlClient(GenericContainer<?> keycloak, String spMetadataUrl, boolean encryptAssertions) {
        String admin = "http://localhost:" + keycloak.getMappedPort(PORT) + "/admin/realms/ntrloc";
        HttpClient http = HttpClient.newHttpClient();
        String token = adminToken(keycloak, http);
        String spMetadata = send(http, HttpRequest.newBuilder(URI.create(spMetadataUrl)).GET().build()).body();
        ObjectNode client = (ObjectNode) JSON.readTree(send(http, authorized(token, admin + "/client-description-converter")
                .header("Content-Type", "application/xml")
                .POST(HttpRequest.BodyPublishers.ofString(spMetadata)).build()).body());
        String clientId = client.path("clientId").asString();
        for (JsonNode existing : JSON.readTree(send(http, authorized(token, admin + "/clients?clientId="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8)).GET().build()).body())) {
            send(http, authorized(token, admin + "/clients/" + existing.path("id").asString()).DELETE().build());
        }
        ObjectNode attributes = client.withObject("attributes");
        attributes.put("saml_name_id_format", "email");
        attributes.put("saml.force.name.id.format", "true");
        attributes.put("saml.encrypt", String.valueOf(encryptAssertions));
        ArrayNode mappers = client.putArray("protocolMappers");
        mappers.add(userPropertyMapper("email", "email"));
        mappers.add(userPropertyMapper("username", "displayName"));
        int status = send(http, authorized(token, admin + "/clients").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(client))).build()).statusCode();
        if (status != 201) {
            throw new IllegalStateException("The realm refused ntrloc's SAML client: HTTP " + status);
        }
    }

    // Asserts the user's property as a SAML attribute of the given name.
    private static ObjectNode userPropertyMapper(String userProperty, String attributeName) {
        ObjectNode mapper = JSON.createObjectNode();
        mapper.put("name", attributeName);
        mapper.put("protocol", "saml");
        mapper.put("protocolMapper", "saml-user-property-mapper");
        ObjectNode config = mapper.putObject("config");
        config.put("user.attribute", userProperty);
        config.put("attribute.name", attributeName);
        config.put("attribute.nameformat", "Basic");
        return mapper;
    }

    private static String adminToken(GenericContainer<?> keycloak, HttpClient http) {
        String tokenJson = send(http, HttpRequest.newBuilder(URI.create("http://localhost:" + keycloak.getMappedPort(PORT)
                        + "/realms/master/protocol/openid-connect/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("client_id=admin-cli&username=admin&password=admin&grant_type=password"))
                .build()).body();
        return JSON.readTree(tokenJson).path("access_token").asString();
    }

    private static HttpRequest.Builder authorized(String token, String url) {
        return HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token);
    }

    private static HttpResponse<String> send(HttpClient http, HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** The realm's SAML IdP metadata URL, as reached from this JVM. */
    public static String samlMetadataUri(GenericContainer<?> keycloak) {
        return issuerUri(keycloak) + "/protocol/saml/descriptor";
    }

    /** The realm's issuer URL, as reached from this JVM. */
    public static String issuerUri(GenericContainer<?> keycloak) {
        return "http://localhost:" + keycloak.getMappedPort(PORT) + "/realms/ntrloc";
    }
}
