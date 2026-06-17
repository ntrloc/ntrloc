package org.ntrloc.graph;

import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.ldap.userdetails.LdapUserDetails;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
public class TestController {

    @GetMapping("/public")
    public Mono<String> publicEndpoint() {
        return Mono.just("This is public");
    }

    @GetMapping(value = "/me", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> me(@AuthenticationPrincipal Object principal) {
        String type;
        String username;
        String extra;

        if (principal instanceof OidcUser user) {
            type = "SSO (OIDC)";
            username = user.getPreferredUsername();
            extra = "Email: " + user.getEmail() + "<br/>Subject: " + user.getSubject();
        } else if (principal instanceof LdapUserDetails user) {
            type = "Directory (LDAP)";
            username = user.getUsername();
            extra = "DN: " + user.getDn();
        } else if (principal instanceof UserDetails user) {
            type = "Local";
            username = user.getUsername();
            extra = "";
        } else {
            type = "Unknown";
            username = "Unknown";
            extra = "";
        }

        String html = """
        <!DOCTYPE html>
        <html>
        <head>
            <title>ntrloc - My Profile</title>
            <style>
                body { font-family: sans-serif; display: flex; justify-content: center;
                       align-items: center; height: 100vh; margin: 0; background: #f5f5f5; }
                .container { background: white; padding: 2rem; border-radius: 8px;
                             box-shadow: 0 2px 8px rgba(0,0,0,0.1); width: 360px; }
                h2 { margin-bottom: 1.5rem; }
                .field { margin-bottom: 0.75rem; font-size: 0.95rem; }
                .label { font-weight: bold; color: #555; font-size: 0.8rem;
                         text-transform: uppercase; }
                .value { margin-top: 0.2rem; }
                .btn-logout { margin-top: 1.5rem; width: 100%%; padding: 0.6rem;
                              background: #e24a4a; color: white; border: none;
                              border-radius: 4px; cursor: pointer; font-size: 1rem; }
            </style>
        </head>
        <body>
        <div class="container">
            <h2>My Profile</h2>
            <div class="field">
                <div class="label">Login Method</div>
                <div class="value">%s</div>
            </div>
            <div class="field">
                <div class="label">Username</div>
                <div class="value">%s</div>
            </div>
            %s
            <form action="/logout" method="post">
                <button type="submit" class="btn-logout">Sign Out</button>
            </form>
        </div>
        </body>
        </html>
        """.formatted(type, username,
                extra.isEmpty() ? "" :
                        "<div class='field'><div class='label'>Details</div>" +
                                "<div class='value'>" + extra + "</div></div>");

        return Mono.just(html);
    }

}
