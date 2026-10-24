package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

// LDAP login with auto-provisioning on: a first-time LDAP user gets an ntrloc account -- no local
// credentials, the default user group, never admin, profile taken from the directory once. Each
// test uses its own directory entry (see ldap/test-directory.ldif).
class LdapAutoProvisioningIntegrationTest extends AbstractLdapIntegrationTest {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("graph.security.auto-provision.enabled", () -> "true");
    }

    @Autowired
    private SecurityRepository securityRepo;

    @Test
    void aFirstTimeLdapUser_getsANonAdminAccountInTheDefaultGroup_withNoLocalCredentials() {
        ResponseCookie session = logIn("new.user", LDAP_PASSWORD);

        assertThat(me(session))
                .containsEntry("email", "new.user@example.org")
                .containsEntry("displayName", "New Directory User")
                .containsEntry("isAdmin", false);
        var account = securityRepo.findUserByEmail("new.user@example.org").orElseThrow();
        assertThat(securityRepo.getUserGroupsForUser(account.id())).extracting(g -> g.name()).contains("everyone");
        assertThat(securityRepo.findCredentialsByUserId(account.id())).isEmpty();
        getAdminPage(session).expectStatus().isForbidden();
    }

    @Test
    void theDisplayName_fallsBackToCn() {
        assertThat(me(logIn("cn.only", LDAP_PASSWORD))).containsEntry("displayName", "Cn Only");
    }

    @Test
    void theProfile_isTakenFromTheDirectoryOnlyOnce() {
        logIn("resync.user", LDAP_PASSWORD);
        var account = securityRepo.findUserByEmail("resync.user@example.org").orElseThrow();
        securityRepo.updateUser(account.id(), "Renamed In ntrloc", account.email(), false);

        assertThat(me(logIn("resync.user", LDAP_PASSWORD))).containsEntry("displayName", "Renamed In ntrloc");
    }

    @Test
    void anExistingAccount_isUsed_notDuplicated() {
        var existing = securityRepo.createUser("Existing User", "existing.user@example.org", false);

        logIn("existing.user", LDAP_PASSWORD);

        assertThat(securityRepo.findUserByEmail("existing.user@example.org")).map(u -> u.id()).contains(existing.id());
        assertThat(securityRepo.listUsers()).filteredOn(u -> u.email().equalsIgnoreCase("existing.user@example.org")).hasSize(1);
    }

    @Test
    void anEntryWithoutAnEmail_isStillRejected() {
        assertLoginFails("no.mail", LDAP_PASSWORD);
    }

    @Test
    void aWrongLdapPassword_neverProvisionsAnAccount() {
        assertLoginFails("unprovisioned.user", "wrong-password");

        assertThat(securityRepo.findUserByEmail("unprovisioned.user@example.org")).isEmpty();
    }
}
