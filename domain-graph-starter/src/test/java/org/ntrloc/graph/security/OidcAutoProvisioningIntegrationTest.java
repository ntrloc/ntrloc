package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.TestDatabases;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// OpenID Connect login with auto-provisioning on: a first-time user with a verified email gets an
// ntrloc account -- no local credentials, the default user group, never admin, profile taken from
// the provider once. Each test uses its own Keycloak user (see oidc/ntrloc-realm.json).
class OidcAutoProvisioningIntegrationTest extends AbstractOidcIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestDatabases.registerFresh(registry, "OidcAutoProvisioningIntegrationTest");
        registry.add("graph.security.auto-provision.enabled", () -> "true");
    }

    @Autowired
    private SecurityRepository securityRepo;

    @Test
    void aFirstTimeOidcUser_getsANonAdminAccountInTheDefaultGroup_withNoLocalCredentials() {
        String session = logIn("new.user");

        assertThat(me(session))
                .containsEntry("email", "new.user@example.org")
                .containsEntry("displayName", "New Person")
                .containsEntry("isAdmin", false);
        var account = securityRepo.findUserByEmail("new.user@example.org").orElseThrow();
        assertThat(securityRepo.getUserGroupsForUser(account.id())).extracting(g -> g.name()).contains("everyone");
        assertThat(securityRepo.findCredentialsByUserId(account.id())).isEmpty();
        getAdminPage(session).expectStatus().isForbidden();
    }

    @Test
    void anUnverifiedEmail_isNeverProvisioned() {
        assertLoginFails("unverified.user");

        assertThat(securityRepo.findUserByEmail("unverified.user@example.org")).isEmpty();
    }

    @Test
    void anExistingAccount_isUsed_notDuplicated() {
        var existing = securityRepo.createUser("Existing User", "existing.user@example.org", false);

        logIn("existing.user");

        assertThat(securityRepo.listUsers()).filteredOn(u -> u.email().equalsIgnoreCase("existing.user@example.org"))
                .singleElement().extracting(u -> u.id()).isEqualTo(existing.id());
    }

    @Test
    void theProfile_isTakenFromTheProviderOnlyOnce() {
        logIn("resync.user");
        var account = securityRepo.findUserByEmail("resync.user@example.org").orElseThrow();
        securityRepo.updateUser(account.id(), "Renamed In ntrloc", account.email(), false);

        assertThat(me(logIn("resync.user"))).containsEntry("displayName", "Renamed In ntrloc");
    }
}
