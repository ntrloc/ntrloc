package org.ntrloc.graph.security;

import org.junit.jupiter.api.BeforeEach;
import org.ntrloc.graph.TestApplication;
import org.ntrloc.graph.db.partition.security.repository.SamlProviderRepository.KeyRole;
import org.ntrloc.graph.security.SamlProviderService.SamlProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.util.HtmlUtils;
import org.testcontainers.containers.GenericContainer;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Base for SAML 2.0 login tests against a real Keycloak acting as the IdP (the realm from
 * oidc/ntrloc-realm.json, shared with the OIDC tests). ntrloc is registered there by importing its
 * metadata (KeycloakContainers.registerSamlClient) -- its entity ID includes this context's random
 * port, so each context registers its own -- and the IdP is registered in ntrloc as the
 * "corporate-saml" provider through SamlProviderService, as the admin UI does. A test signs in the way a browser would --
 * ntrloc's redirect to Keycloak, Keycloak's login form, and the auto-posted SAML response back to
 * ntrloc -- over plain HTTP from this JVM.
 *
 * SAML settings make this a separate Spring context, so each subclass has a database of its own
 * (see TestDatabases). @DirtiesContext (inherited) closes each
 * subclass's context once it finishes.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@DirtiesContext
public abstract class AbstractSamlIntegrationTest {

    protected static final String REGISTRATION_ID = "corporate-saml";
    protected static final String SAML_PASSWORD = KeycloakContainers.USER_PASSWORD;

    private static final Pattern LOGIN_FORM_ACTION = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
    private static final Pattern POST_FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT = Pattern.compile("<input[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE = Pattern.compile("value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    protected static final GenericContainer<?> KEYCLOAK = KeycloakContainers.shared();

    @LocalServerPort
    private int port;

    @Autowired
    protected WebTestClient webTestClient;

    @Autowired
    protected SamlProviderService samlProviders;

    // ntrloc's metadata only exists once the provider does, so the IdP learns about ntrloc second.
    // Each test starts from just the provider's active key, whatever rotation an earlier test left.
    @BeforeEach
    void registerIdp() {
        saveProvider(idp("email", true));
        samlProviders.list().stream()
                .filter(p -> p.provider().registrationId().equals(REGISTRATION_ID))
                .flatMap(p -> p.keys().stream())
                .filter(key -> key.role() != KeyRole.ACTIVE)
                .forEach(key -> samlProviders.removeKey(REGISTRATION_ID, key.role(), null));
        registerAtIdp(false);
    }

    /** (Re-)registers ntrloc at the IdP from its current metadata. */
    protected void registerAtIdp(boolean encryptAssertions) {
        KeycloakContainers.registerSamlClient(KEYCLOAK, appUrl("/saml2/metadata/" + REGISTRATION_ID), encryptAssertions);
    }

    /** The test IdP as a SAML provider, taking the email from the given attribute ("" for the NameID). */
    protected static SamlProvider idp(String emailAttribute, boolean enabled) {
        return new SamlProvider(REGISTRATION_ID, "Corporate SSO", KeycloakContainers.samlMetadataUri(KEYCLOAK),
                emailAttribute, "displayName", enabled);
    }

    protected void saveProvider(SamlProvider provider) {
        if (samlProviders.list().stream().anyMatch(p -> p.provider().registrationId().equals(REGISTRATION_ID))) {
            samlProviders.update(REGISTRATION_ID, provider, null);
        } else {
            samlProviders.create(provider, null);
        }
    }

    /** Where a sign-in ended up, and the ntrloc session cookie it left behind, if any. */
    protected record LoginResult(String finalLocation, Optional<String> sessionId) {

        boolean succeeded() {
            return !finalLocation.contains("error") && sessionId.isPresent();
        }
    }

    /**
     * One browser's worth of cookies -- ntrloc's session and the IdP's -- kept across sign-ins and
     * sign-outs. It follows redirects and the pages that auto-post SAML messages, as a browser
     * would, in either direction, and keeps the last SAML response the IdP sent ntrloc.
     */
    protected final class Browser {

        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient http = HttpClient.newBuilder().cookieHandler(new LocalhostCookieHandler(cookies))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        private String lastSamlResponse;

        /** Signs in through the IdP's login form. */
        public LoginResult logIn(String username, String password) {
            HttpResponse<String> loginPage = follow(send(http, get(appUrl("/saml2/authenticate/" + REGISTRATION_ID))));
            Matcher loginAction = LOGIN_FORM_ACTION.matcher(loginPage.body());
            assertThat(loginAction.find()).as("the IdP's login form in:%n%s", excerpt(loginPage.body())).isTrue();
            HttpResponse<String> end = follow(send(http, form(HtmlUtils.htmlUnescape(loginAction.group(1)),
                    "username=" + encode(username) + "&password=" + encode(password))));
            return new LoginResult(end.request().uri().toString(), sessionId());
        }

        /** Starts a sign-in and reports whether the IdP asks for a password, rather than signing straight back in. */
        public boolean idpAsksForPassword() {
            HttpResponse<String> response = follow(send(http, get(appUrl("/saml2/authenticate/" + REGISTRATION_ID))));
            return LOGIN_FORM_ACTION.matcher(response.body()).find();
        }

        /** Logs out of ntrloc, following single logout through the IdP; returns the URL it ends at. */
        public String logOut() {
            return follow(send(http, get(appUrl("/logout")))).request().uri().toString();
        }

        /** Opens a URL, following it as far as a browser would; returns the final response. */
        public HttpResponse<String> open(String url) {
            return follow(send(http, get(url)));
        }

        /** Submits the page's first form, with its hidden fields, as a person clicking its button would. */
        public HttpResponse<String> submit(HttpResponse<String> page, String button, String value) {
            Matcher action = POST_FORM_ACTION.matcher(page.body());
            assertThat(action.find()).as("a form in:%n%s", excerpt(page.body())).isTrue();
            StringBuilder body = new StringBuilder(encode(button) + "=" + encode(value));
            Matcher input = INPUT.matcher(page.body());
            while (input.find()) {
                Matcher name = NAME.matcher(input.group());
                Matcher inputValue = VALUE.matcher(input.group());
                if (input.group().toLowerCase().contains("type=\"hidden\"") && name.find() && inputValue.find()) {
                    body.append('&').append(encode(name.group(1))).append('=').append(encode(HtmlUtils.htmlUnescape(inputValue.group(1))));
                }
            }
            URI target = page.request().uri().resolve(HtmlUtils.htmlUnescape(action.group(1)));
            return follow(send(http, form(target.toString(), body.toString())));
        }

        /** The last SAML response the IdP sent ntrloc, decoded. */
        public String lastSamlResponse() {
            return lastSamlResponse;
        }

        public Optional<String> sessionId() {
            return cookies.getCookieStore().get(URI.create(appUrl("/"))).stream()
                    .filter(cookie -> cookie.getName().equals("SESSION"))
                    .map(HttpCookie::getValue)
                    .findFirst();
        }

        // Follows redirects, and pages that auto-post a SAML message, until a page a person would see.
        private HttpResponse<String> follow(HttpResponse<String> response) {
            for (int hop = 0; hop < 10; hop++) {
                if (response.statusCode() >= 300 && response.statusCode() <= 399) {
                    response = send(http, get(response.request().uri()
                            .resolve(response.headers().firstValue("Location").orElseThrow()).toString()));
                } else if (hiddenValue(response.body(), "SAMLRequest").isPresent()) {
                    response = send(http, autoPost(response.body(), "SAMLRequest"));
                } else if (hiddenValue(response.body(), "SAMLResponse").isPresent()) {
                    lastSamlResponse = new String(Base64.getMimeDecoder().decode(
                            hiddenValue(response.body(), "SAMLResponse").orElseThrow()), StandardCharsets.UTF_8);
                    response = send(http, autoPost(response.body(), "SAMLResponse"));
                } else {
                    return response;
                }
            }
            throw new IllegalStateException("Too many redirects, ending at " + response.request().uri());
        }
    }

    /** Signs in through the IdP's login form, in a fresh browser. */
    protected LoginResult logInThroughSaml(String username, String password) {
        return new Browser().logIn(username, password);
    }

    protected String logIn(String username) {
        LoginResult result = logInThroughSaml(username, SAML_PASSWORD);
        assertThat(result.succeeded()).as("SAML login as %s ended at %s", username, result.finalLocation()).isTrue();
        return result.sessionId().orElseThrow();
    }

    protected void assertLoginFails(String username) {
        assertThat(logInThroughSaml(username, SAML_PASSWORD).finalLocation()).endsWith("/login?error");
    }

    /** The signed-in user, as /api/account/me reports them. */
    @SuppressWarnings("unchecked")
    protected Map<String, Object> me(String sessionId) {
        return webTestClient.get().uri("/api/account/me")
                .cookie("SESSION", sessionId)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();
    }

    protected String appUrl(String path) {
        return "http://localhost:" + port + path;
    }

    private static HttpRequest get(String url) {
        return HttpRequest.newBuilder(URI.create(url)).GET().build();
    }

    private static HttpRequest form(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
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


    // The request a browser sends when a page auto-posts its SAML form: the form's action, with its
    // SAML message and RelayState.
    private static HttpRequest autoPost(String page, String messageName) {
        Matcher action = POST_FORM_ACTION.matcher(page);
        Optional<String> message = hiddenValue(page, messageName);
        assertThat(action.find() && message.isPresent()).as("auto-posted %s form in:%n%s", messageName, excerpt(page)).isTrue();
        String body = messageName + "=" + encode(message.get())
                + hiddenValue(page, "RelayState").map(relayState -> "&RelayState=" + encode(relayState)).orElse("");
        return form(HtmlUtils.htmlUnescape(action.group(1)), body);
    }

    // The value of the page's <input> with this name, whatever order its attributes come in.
    private static Optional<String> hiddenValue(String page, String name) {
        Matcher input = INPUT.matcher(page);
        while (input.find()) {
            Matcher inputName = NAME.matcher(input.group());
            Matcher value = VALUE.matcher(input.group());
            if (inputName.find() && inputName.group(1).equals(name) && value.find()) {
                return Optional.of(HtmlUtils.htmlUnescape(value.group(1)));
            }
        }
        return Optional.empty();
    }


    private static String excerpt(String page) {
        return page.substring(0, Math.min(page.length(), 1500));
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
