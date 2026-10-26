package org.ntrloc.graph.cluster;

import org.ntrloc.graph.security.LocalhostCookieHandler;
import org.springframework.web.util.HtmlUtils;

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
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One browser's cookies, signing in through an identity provider the way a person would:
 * following redirects, auto-posting the pages that carry a SAML message, and filling in the
 * provider's login form. It reaches ntrloc only through the LoadBalancer, trusting its
 * certificate; pinned to a node, it asks the balancer to send its requests there.
 */
final class SsoBrowser {

    private static final Pattern LOGIN_FORM_ACTION = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"");
    private static final Pattern FORM_ACTION = Pattern.compile("<form[^>]*action=\"([^\"]+)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern INPUT = Pattern.compile("<input[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME = Pattern.compile("name=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUE = Pattern.compile("value=\"([^\"]*)\"", Pattern.CASE_INSENSITIVE);

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final HttpClient http;
    private final URI loadBalancer;
    private String node;

    SsoBrowser(LoadBalancer loadBalancer) {
        this.loadBalancer = URI.create(loadBalancer.url("/"));
        http = HttpClient.newBuilder().cookieHandler(new LocalhostCookieHandler(cookies)).sslContext(loadBalancer.trust())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** From now on, asks the balancer for this node (LoadBalancer.NODE_A or NODE_B); null for round-robin. */
    void sendTo(String node) {
        this.node = node;
    }

    /** One request, not followed anywhere. */
    HttpResponse<String> get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET());
    }

    /** One form post, not followed anywhere. */
    HttpResponse<String> post(String url, String formBody) {
        return send(form(url, formBody));
    }

    /** Opens a URL and follows it as far as a browser would, e.g. to the provider's login form. */
    HttpResponse<String> open(String url) {
        return follow(get(url));
    }

    /** Fills in the provider's login form on this page and follows wherever that leads. */
    HttpResponse<String> signIn(HttpResponse<String> loginPage, String username, String password) {
        Matcher action = LOGIN_FORM_ACTION.matcher(loginPage.body());
        assertThat(action.find()).as("the provider's login form at %s", loginPage.uri()).isTrue();
        return follow(send(form(HtmlUtils.htmlUnescape(action.group(1)),
                "username=" + encode(username) + "&password=" + encode(password))));
    }

    boolean showsLoginForm(HttpResponse<String> page) {
        return LOGIN_FORM_ACTION.matcher(page.body()).find();
    }

    /** ntrloc's session cookie, as the browser holds it for this URL. */
    Optional<String> session(String url) {
        return cookies.getCookieStore().get(URI.create(url)).stream()
                .filter(cookie -> cookie.getName().equals("SESSION"))
                .map(HttpCookie::getValue)
                .findFirst();
    }

    // Follows redirects, and pages that auto-post a SAML message, until a page a person would see.
    private HttpResponse<String> follow(HttpResponse<String> response) {
        for (int hop = 0; hop < 10; hop++) {
            if (response.statusCode() >= 300 && response.statusCode() <= 399) {
                String location = response.headers().firstValue("Location").orElseThrow();
                response = send(HttpRequest.newBuilder(response.uri().resolve(location)).GET());
            } else if (hasField(response.body(), "SAMLRequest") || hasField(response.body(), "SAMLResponse")) {
                response = send(autoPost(response));
            } else {
                return response;
            }
        }
        throw new IllegalStateException("Too many redirects, ending at " + response.uri());
    }

    // The request a browser sends when a page auto-posts its form: the action, with every hidden field.
    private static HttpRequest.Builder autoPost(HttpResponse<String> page) {
        Matcher action = FORM_ACTION.matcher(page.body());
        assertThat(action.find()).as("an auto-posted form at %s", page.uri()).isTrue();
        StringBuilder body = new StringBuilder();
        Matcher input = INPUT.matcher(page.body());
        while (input.find()) {
            Matcher name = NAME.matcher(input.group());
            Matcher value = VALUE.matcher(input.group());
            if (input.group().toLowerCase().contains("type=\"hidden\"") && name.find() && value.find()) {
                body.append(body.isEmpty() ? "" : "&").append(encode(name.group(1))).append('=')
                        .append(encode(HtmlUtils.htmlUnescape(value.group(1))));
            }
        }
        return form(page.uri().resolve(HtmlUtils.htmlUnescape(action.group(1))).toString(), body.toString());
    }

    private static boolean hasField(String page, String name) {
        Matcher input = INPUT.matcher(page);
        while (input.find()) {
            Matcher inputName = NAME.matcher(input.group());
            if (inputName.find() && inputName.group(1).equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static HttpRequest.Builder form(String url, String body) {
        return HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(body));
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
        boolean toTheBalancer = loadBalancer.getAuthority().equals(request.build().uri().getAuthority());
        if (toTheBalancer && node != null) {
            request.header(LoadBalancer.NODE_HEADER, node);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
