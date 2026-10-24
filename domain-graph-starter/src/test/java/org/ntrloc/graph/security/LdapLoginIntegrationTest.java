package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// LDAP login with auto-provisioning off: the directory checks the password, and the entry's email
// must already belong to an ntrloc account, or the login is rejected. Each test uses its own
// directory entry (see ldap/test-directory.ldif), so none depends on another's account.
class LdapLoginIntegrationTest extends AbstractLdapIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("graph.security.auto-provision.enabled", () -> "false");
    }

    @Autowired
    private SecurityRepository securityRepo;

    @Test
    void aUserWithAnNtrlocAccount_logsInThroughLdapAsThatAccount() {
        var account = securityRepo.createUser("Existing User", "existing.user@example.org", false);

        ResponseCookie session = logIn("existing.user", LDAP_PASSWORD);

        assertThat(me(session)).containsEntry("email", account.email()).containsEntry("displayName", "Existing User");
    }

    @Test
    void theDirectorysEmail_matchesTheNtrlocAccountIgnoringCase() {
        securityRepo.createUser("Mixed Case", "mixed.case@example.org", false);

        ResponseCookie session = logIn("mixed.case", LDAP_PASSWORD);

        assertThat(me(session)).containsEntry("email", "mixed.case@example.org");
    }

    @Test
    void aUserWithoutAnNtrlocAccount_isRejected_andNoAccountIsCreated() {
        assertLoginFails("unprovisioned.user", LDAP_PASSWORD);

        assertThat(securityRepo.findUserByEmail("unprovisioned.user@example.org")).isEmpty();
    }

    @Test
    void aWrongLdapPassword_isRejected() {
        assertLoginFails("existing.user", "wrong-password");
    }

    @Test
    void anEntryWithoutAnEmail_isRejected() {
        assertLoginFails("no.mail", LDAP_PASSWORD);
    }

    // Admin comes from the ntrloc account, never from the directory.
    @Test
    void anNtrlocAdmin_canOpenTheAdminUi_afterAnLdapLogin() {
        securityRepo.createUser("Admin User", "admin.user@example.org", true);

        getAdminPage(logIn("admin.user", LDAP_PASSWORD)).expectStatus().isOk();
    }

    @Test
    void aNonAdmin_isForbiddenFromTheAdminUi_afterAnLdapLogin() {
        securityRepo.createUser("Plain User", "plain.user@example.org", false);

        getAdminPage(logIn("plain.user", LDAP_PASSWORD)).expectStatus().isForbidden();
    }

    @Test
    void aLocalAccount_stillLogsInWithLdapEnabled() {
        String email = "local-" + UUID.randomUUID() + "@example.com";
        var user = securityRepo.createUser("Local User", email, false);
        securityRepo.createLocalCredentials(user.id(), "{bcrypt}" + new BCryptPasswordEncoder().encode("local-secret"));

        assertThat(me(logIn(email, "local-secret"))).containsEntry("email", email);
    }
}
