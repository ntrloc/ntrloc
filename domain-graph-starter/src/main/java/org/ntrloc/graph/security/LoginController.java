package org.ntrloc.graph.security;

import org.ntrloc.graph.branding.BrandingProperties;
import org.springframework.http.MediaType;
import org.ntrloc.graph.security.OidcProviderService.OidcProvider;
import org.ntrloc.graph.security.SamlProviderService.SamlProvider;
import org.springframework.web.util.HtmlUtils;
import org.springframework.web.util.UriUtils;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class LoginController {

    private record SignInButton(String pathPrefix, String registrationId, String label) {}

    private final AuthProperties authProperties;
    private final BrandingProperties brandingProperties;
    private final OidcProviderService oidcProviders;
    private final SamlProviderService samlProviders;
    private final LdapConfigurationService ldapConfiguration;

    public LoginController(AuthProperties authProperties, BrandingProperties brandingProperties,
                           OidcProviderService oidcProviders, SamlProviderService samlProviders,
                           LdapConfigurationService ldapConfiguration) {
        this.authProperties = authProperties;
        this.brandingProperties = brandingProperties;
        this.oidcProviders = oidcProviders;
        this.samlProviders = samlProviders;
        this.ldapConfiguration = ldapConfiguration;
    }

    @GetMapping(value = "/login", produces = MediaType.TEXT_HTML_VALUE)
    public String loginPage(
            @RequestParam(required = false) String error) {

        String displayName = brandingProperties.displayName();
        StringBuilder html = new StringBuilder();
        html.append("""
            <!DOCTYPE html>
            <html>
            <head>
                <title>%s Login</title>""".formatted(displayName));
        html.append("""
                <style>
                    body { font-family: sans-serif; display: flex; justify-content: center;
                           align-items: center; height: 100vh; margin: 0; background: #f5f5f5; }
                    .login-container { background: white; padding: 2rem; border-radius: 8px;
                                       box-shadow: 0 2px 8px rgba(0,0,0,0.1); width: 360px; }
                    h2 { text-align: center; margin-bottom: 1.5rem; }
                    .btn { width: 100%; padding: 0.6rem; border: none; border-radius: 4px;
                           cursor: pointer; font-size: 1rem; margin-bottom: 0.5rem; }
                    .btn-oauth { background: #e24a4a; color: white; }
                    .btn-primary { background: #4a90e2; color: white; }
                    .form-group { margin-bottom: 0.75rem; }
                    label { display: block; margin-bottom: 0.25rem; font-size: 0.85rem; }
                    input { width: 100%; padding: 0.5rem; border: 1px solid #ccc;
                            border-radius: 4px; box-sizing: border-box; }
                    .divider { text-align: center; margin: 1rem 0;
                               border-top: 1px solid #eee; line-height: 0; }
                    .divider span { background: white; padding: 0 0.5rem;
                                    color: #999; font-size: 0.85rem; }
                    .section-label { font-size: 0.8rem; color: #666;
                                     margin-bottom: 0.5rem; font-weight: bold; }
                    .error { color: red; font-size: 0.85rem;
                             margin-bottom: 1rem; text-align: center; }
                </style>
            </head>
            <body>
            <div class="login-container">
            """);
        html.append("<h2>%s</h2>".formatted(displayName));

        if (error != null) {
            html.append("""
                <div class="error">Invalid credentials. Please try again.</div>
                """);
        }

        boolean first = true;

        // Single sign-on first: one button per enabled OpenID Connect and SAML provider.
        List<SignInButton> buttons = new ArrayList<>();
        for (OidcProvider provider : oidcProviders.enabledProviders()) {
            buttons.add(new SignInButton("/oauth2/authorization/", provider.registrationId(), provider.displayName()));
        }
        for (SamlProvider provider : samlProviders.enabledProviders()) {
            buttons.add(new SignInButton("/saml2/authenticate/", provider.registrationId(), provider.displayName()));
        }
        if (!buttons.isEmpty()) {
            html.append("""
                <div class="section-label">Single Sign-On</div>
                """);
            for (SignInButton button : buttons) {
                html.append("""
                    <a href="%s%s" style="text-decoration:none">
                        <button class="btn btn-oauth">Sign In with %s</button>
                    </a>
                    """.formatted(button.pathPrefix(), UriUtils.encodePathSegment(button.registrationId(), StandardCharsets.UTF_8),
                        HtmlUtils.htmlEscape(button.label())));
            }
            first = false;
        }

        // LDAP second
        if (ldapConfiguration.isEnabled()) {
            if (!first) {
                html.append("""
                    <div class="divider"><span>or</span></div>
                    """);
            }
            html.append("""
                <div class="section-label">Directory Login</div>
                <form action="/login" method="post">
                    <div class="form-group">
                        <label>Username</label>
                        <input type="text" name="username" required />
                    </div>
                    <div class="form-group">
                        <label>Password</label>
                        <input type="password" name="password" required />
                    </div>
                    <button type="submit" class="btn btn-primary">
                        Sign In with Directory
                    </button>
                </form>
                """);
            first = false;
        }

        // Local last
        if (authProperties.getLocal().isEnabled()) {
            if (!first) {
                html.append("""
                    <div class="divider"><span>or</span></div>
                    """);
            }
            html.append("""
                <div class="section-label">Local Account</div>
                <form action="/login" method="post">
                    <div class="form-group">
                        <label>Email</label>
                        <input type="text" name="username" autocomplete="username" required />
                    </div>
                    <div class="form-group">
                        <label>Password</label>
                        <input type="password" name="password" required />
                    </div>
                    <button type="submit" class="btn btn-primary">Sign In</button>
                </form>
                """);
        }

        html.append("</div></body></html>");
        return html.toString();
    }
}
