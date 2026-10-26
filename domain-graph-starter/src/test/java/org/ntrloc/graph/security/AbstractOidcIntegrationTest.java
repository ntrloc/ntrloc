package org.ntrloc.graph.security;

import org.junit.jupiter.api.BeforeEach;
import org.ntrloc.graph.TestApplication;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for OpenID Connect login tests against a real Keycloak, its realm imported from
 * oidc/ntrloc-realm.json. A test logs in the way a browser would -- ntrloc's redirect to Keycloak,
 * Keycloak's own login form, the redirect back with an authorization code -- over plain HTTP from
 * this JVM, so ntrloc and the "browser" both reach Keycloak at the same localhost issuer URL.
 *
 * Keycloak is registered as a sign-in provider through OidcProviderService, as the admin UI does.
 * Local login is off in this context, as in an SSO-only deployment.
 *
 * OIDC-enabled settings make this a separate Spring context, so each subclass brings its own
 * Postgres container (see PersonalAccessTokenAuthenticationIntegrationTest); Keycloak is shared
 * (see KeycloakContainers). Singleton containers, as in AbstractIntegrationTest.
 * @DirtiesContext (inherited) closes each subclass's context once it finishes, so these extra
 * contexts don't all stay alive, and in memory, for the rest of the test run.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DirtiesContext
public abstract class AbstractOidcIntegrationTest {

    protected static final String OIDC_PASSWORD = KeycloakContainers.USER_PASSWORD;
    private static final Pattern LOGIN_FORM_ACTION = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");

    private static final GenericContainer<?> KEYCLOAK = KeycloakContainers.shared();

    // SSO only, as an OIDC deployment typically is: no local login, no LDAP.
    @DynamicPropertySource
    static void registerOidcProperties(DynamicPropertyRegistry registry) {
        registry.add("graph.auth.local.enabled", () -> "false");
    }

    @LocalServerPort
    private int port;

    @Autowired
    protected WebTestClient webTestClient;

    @Autowired
    protected OidcProviderService oidcProviders;

    @BeforeEach
    void registerKeycloak() {
        KeycloakContainers.register(oidcProviders, KeycloakContainers.issuerUri(KEYCLOAK));
    }

    /** Where a login attempt ended up, and the ntrloc session cookie it left behind, if any. */
    protected record LoginResult(String finalLocation, Optional<String> sessionId) {

        boolean succeeded() {
            return !finalLocation.contains("error") && sessionId.isPresent();
        }
    }

    /**
     * One browser's worth of cookies -- ntrloc's session and Keycloak's own SSO session -- kept
     * across sign-ins and sign-outs, following the authorization-code flow by hand.
     */
    protected final class Browser {

        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new LocalhostCookieHandler(cookies))
                .followRedirects(HttpClient.Redirect.NEVER).build();

        /** Signs in through Keycloak's login form. */
        public LoginResult logIn(String username, String password) {
            String keycloakLoginPage = call(() -> redirectTarget(http, get(appUrl("/oauth2/authorization/keycloak"))));
            String loginFormHtml = call(() -> send(http, get(keycloakLoginPage)).body());
            Matcher action = LOGIN_FORM_ACTION.matcher(loginFormHtml);
            assertThat(action.find()).as("Keycloak login form").isTrue();
            String codeRedirect = call(() -> redirectTarget(http, HttpRequest.newBuilder(URI.create(action.group(1).replace("&amp;", "&")))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=" + encode(username) + "&password=" + encode(password)))
                    .build()));
            String finalLocation = call(() -> redirectTarget(http, get(codeRedirect)));
            return new LoginResult(finalLocation, sessionId());
        }

        /**
         * Starts a sign-in and reports whether Keycloak asks for a password, rather than signing
         * the browser straight back in from its own session.
         */
        public boolean keycloakAsksForPassword() {
            String keycloak = call(() -> redirectTarget(http, get(appUrl("/oauth2/authorization/keycloak"))));
            HttpResponse<String> response = call(() -> send(http, get(keycloak)));
            return response.statusCode() == 200 && LOGIN_FORM_ACTION.matcher(response.body()).find();
        }

        /** Logs out of ntrloc, following every redirect; returns the URL it ends at. */
        public String logOut() {
            String location = appUrl("/logout");
            for (int hop = 0; hop < 5; hop++) {
                String current = location;
                HttpResponse<String> response = call(() -> send(http, get(current)));
                if (response.statusCode() < 300 || response.statusCode() > 399) {
                    return current;
                }
                location = URI.create(current).resolve(response.headers().firstValue("Location").orElseThrow()).toString();
            }
            return location;
        }

        public Optional<String> sessionId() {
            return cookies.getCookieStore().get(URI.create(appUrl("/"))).stream()
                    .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                    .map(HttpCookie::getValue)
                    .findFirst();
        }
    }

    /** Signs in through Keycloak's login form, in a fresh browser. */
    protected LoginResult logInThroughKeycloak(String username, String password) {
        return new Browser().logIn(username, password);
    }

    protected String logIn(String username) {
        LoginResult result = logInThroughKeycloak(username, OIDC_PASSWORD);
        assertThat(result.succeeded()).as("login as %s ended at %s", username, result.finalLocation()).isTrue();
        return result.sessionId().orElseThrow();
    }

    protected void assertLoginFails(String username) {
        assertThat(logInThroughKeycloak(username, OIDC_PASSWORD).finalLocation()).endsWith("/login?error");
    }

    /** The signed-in user, as /api/account/me reports them. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> me(String sessionId) {
        return webTestClient.get().uri("/api/account/me")
                .cookie("JSESSIONID", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    protected WebTestClient.ResponseSpec getAdminPage(String sessionId) {
        return webTestClient.get().uri("/admin-react/index.html").cookie("JSESSIONID", sessionId).exchange();
    }

    private interface HttpCall<T> {
        T call() throws IOException, InterruptedException;
    }

    private static <T> T call(HttpCall<T> step) {
        try {
            return step.call();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private String appUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    private static HttpResponse<String> send(HttpClient http, HttpRequest request) throws IOException, InterruptedException {
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    // The absolute URL a redirect response points at.
    private static String redirectTarget(HttpClient http, HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = send(http, request);
        assertThat(response.statusCode()).as("redirect from %s", request.uri()).isBetween(300, 399);
        return request.uri().resolve(response.headers().firstValue("Location").orElseThrow()).toString();
    }

    // Keycloak marks its session cookies Secure. Browsers treat localhost as a secure origin and
    // send them over plain HTTP anyway; java.net.CookieManager doesn't, so without this Keycloak
    // never sees its own login session and rejects the form post.
    private static final class LocalhostCookieHandler extends CookieHandler {

        private final CookieManager delegate;

        LocalhostCookieHandler(CookieManager delegate) {
            this.delegate = delegate;
        }

        @Override
        public Map<String, List<String>> get(URI uri, Map<String, List<String>> requestHeaders) throws IOException {
            return delegate.get(uri, requestHeaders);
        }

        @Override
        public void put(URI uri, Map<String, List<String>> responseHeaders) throws IOException {
            Map<String, List<String>> withoutSecure = new HashMap<>(responseHeaders);
            withoutSecure.replaceAll((name, values) -> name != null && name.equalsIgnoreCase("Set-Cookie")
                    ? values.stream().map(value -> value.replaceAll("(?i);\\s*Secure", "")).toList()
                    : values);
            delegate.put(uri, withoutSecure);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
