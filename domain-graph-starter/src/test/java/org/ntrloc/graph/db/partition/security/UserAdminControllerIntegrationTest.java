package org.ntrloc.graph.db.partition.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.AbstractIntegrationTest;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// Covers UserAdminController's user CRUD and personal-access-token endpoints. Reuses
// AuthorizationTestDataInitializer's standing fixture (root=admin, alice=non-admin) for
// admin-check tests -- see AccessAdminControllerIntegrationTest's own comment on why that's safe
// to read without risking cross-test contamination. Every created user/token below gets a
// UUID-suffixed email, so nothing here collides with that fixture or with other test methods
// sharing the same singleton Postgres container (see AbstractIntegrationTest's own comment).
class UserAdminControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private SecurityRepository securityRepo;

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getAsRoot(String uri) {
        return (List<Map<String, Object>>) (List<?>) webTestClient.get().uri(uri)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .returnResult()
                .getResponseBody();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    // --- List ---

    @Test
    void listUsers_includesKnownUsers_noAdminCheckRequired() {
        assertThat(getAsRoot("/api/admin/users")).extracting(u -> u.get("email"))
                .contains("root@example.com", "alice@example.com");
    }

    // --- Create ---

    @Test
    void createUser_asAdmin_createsAUserWithCredentialsAndDefaultUserGroupMembership() {
        String email = uniqueEmail();

        webTestClient.post().uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "New User", "email", email, "password", "s3cret!"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(email)
                .jsonPath("$.isAdmin").isEqualTo(false);

        var user = securityRepo.findUserByEmail(email).orElseThrow();
        assertThat(securityRepo.findCredentialsByUserId(user.id())).isPresent();
        assertThat(securityRepo.getUserGroupsForUser(user.id())).extracting(g -> g.name()).contains("everyone");
    }

    @Test
    void createUser_withRoleAdmin_createsAAdmin() {
        webTestClient.post().uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "New Admin", "email", uniqueEmail(),
                        "password", "s3cret!", "role", "admin"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.isAdmin").isEqualTo(true);
    }

    @Test
    void createUser_asNonAdmin_isForbidden() {
        webTestClient.post().uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "X", "email", uniqueEmail(), "password", "s3cret!"))
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void createUser_withAnEmailAlreadyInUse_inAnyCase_returnsConflict() {
        webTestClient.post().uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "Duplicate Alice", "email", "ALICE@Example.com", "password", "s3cret!"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void createUser_withoutAnEmail_returnsBadRequest() {
        webTestClient.post().uri("/api/admin/users")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "No Email", "password", "s3cret!"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // --- Update ---

    @Test
    void updateUser_asAdmin_persistsChanges() {
        var user = securityRepo.createUser("Original Name", uniqueEmail(), false);
        String newEmail = uniqueEmail();

        webTestClient.put().uri("/api/admin/users/" + user.id())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "Updated Name", "email", newEmail, "role", "admin"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.displayName").isEqualTo("Updated Name")
                .jsonPath("$.email").isEqualTo(newEmail)
                .jsonPath("$.isAdmin").isEqualTo(true);

        var reloaded = securityRepo.findUserById(user.id()).orElseThrow();
        assertThat(reloaded.displayName()).isEqualTo("Updated Name");
        assertThat(reloaded.email()).isEqualTo(newEmail);
        assertThat(reloaded.isAdmin()).isTrue();
    }

    @Test
    void updateUser_toAnotherUsersEmail_returnsConflict() {
        var user = securityRepo.createUser("Original Name", uniqueEmail(), false);

        webTestClient.put().uri("/api/admin/users/" + user.id())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "Original Name", "email", "alice@example.com"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void updateUser_asNonAdmin_isForbidden() {
        var user = securityRepo.createUser("Original Name", uniqueEmail(), false);

        webTestClient.put().uri("/api/admin/users/" + user.id())
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("displayName", "Updated Name", "email", uniqueEmail()))
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Password reset ---

    @Test
    void resetPassword_asAdmin_persistsANewHash() {
        var user = securityRepo.createUser("Local User", uniqueEmail(), false);
        securityRepo.createLocalCredentials(user.id(), "{bcrypt}old-hash");

        webTestClient.put().uri("/api/admin/users/" + user.id() + "/password")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("newPassword", "new-s3cret!"))
                .exchange()
                .expectStatus().isNoContent();

        var credentials = securityRepo.findCredentialsByUserId(user.id()).orElseThrow();
        assertThat(credentials.passwordHash()).startsWith("{bcrypt}").isNotEqualTo("{bcrypt}old-hash");
    }

    @Test
    void resetPassword_asNonAdmin_isForbidden() {
        var user = securityRepo.createUser("Local User", "user-" + UUID.randomUUID() + "@example.com", false);

        webTestClient.put().uri("/api/admin/users/" + user.id() + "/password")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("newPassword", "new-s3cret!"))
                .exchange()
                .expectStatus().isForbidden();
    }

    // --- Personal access tokens ---

    @Test
    void tokenLifecycle_createListAndRevoke() {
        var user = securityRepo.createUser("Token User", "user-" + UUID.randomUUID() + "@example.com", false);

        webTestClient.get().uri("/api/admin/users/" + user.id() + "/tokens")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .hasSize(0);

        var createResponse = webTestClient.post().uri("/api/admin/users/" + user.id() + "/tokens")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "My Token", "expiresInDays", 30))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class)
                .returnResult()
                .getResponseBody();

        assertThat(createResponse).containsKey("token");
        assertThat((String) createResponse.get("token")).isNotBlank();
        UUID tokenId = UUID.fromString((String) createResponse.get("id"));

        webTestClient.get().uri("/api/admin/users/" + user.id() + "/tokens")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .hasSize(1);

        webTestClient.delete().uri("/api/admin/users/" + user.id() + "/tokens/" + tokenId)
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isNoContent();

        webTestClient.get().uri("/api/admin/users/" + user.id() + "/tokens")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("root@example.com"))
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Map.class)
                .hasSize(0);
    }

    @Test
    void listTokens_asNonAdmin_isForbidden() {
        var user = securityRepo.createUser("Token User", "user-" + UUID.randomUUID() + "@example.com", false);

        webTestClient.get().uri("/api/admin/users/" + user.id() + "/tokens")
                .header(HttpHeaders.AUTHORIZATION, bearerTokenFor("alice@example.com"))
                .exchange()
                .expectStatus().isForbidden();
    }
}
