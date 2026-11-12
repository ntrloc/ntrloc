package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.TestDatabases;
import org.ntrloc.graph.db.partition.security.repository.UserStore;
import org.ntrloc.graph.db.partition.security.repository.UserGroupStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// LDAP login with auto-provisioning on: a first-time LDAP user gets an ntrloc account -- no local
// credentials, the default user group, never admin, profile taken from the directory once. Each
// test uses its own directory entry (see ldap/test-directory.ldif).
class LdapAutoProvisioningIntegrationTest extends AbstractLdapIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestDatabases.registerFresh(registry, "LdapAutoProvisioningIntegrationTest");
        registry.add("graph.security.auto-provision.enabled", () -> "true");
    }

    @Autowired
    private UserStore userStore;

    @Autowired
    private UserGroupStore userGroupStore;

    @Test
    void aFirstTimeLdapUser_getsANonAdminAccountInTheDefaultGroup_withNoLocalCredentials() {
        ResponseCookie session = logIn("new.user", LDAP_PASSWORD);

        assertThat(me(session))
                .containsEntry("email", "new.user@example.org")
                .containsEntry("displayName", "New Directory User")
                .containsEntry("isAdmin", false);
        var account = userStore.findUserByEmail("new.user@example.org").orElseThrow();
        assertThat(userGroupStore.getUserGroupsForUser(account.id())).extracting(g -> g.name()).contains("everyone");
        assertThat(userStore.findCredentialsByUserId(account.id())).isEmpty();
        getAdminPage(session).expectStatus().isForbidden();
    }

    @Test
    void theDisplayName_fallsBackToCn() {
        assertThat(me(logIn("cn.only", LDAP_PASSWORD))).containsEntry("displayName", "Cn Only");
    }

    @Test
    void theProfile_isTakenFromTheDirectoryOnlyOnce() {
        logIn("resync.user", LDAP_PASSWORD);
        var account = userStore.findUserByEmail("resync.user@example.org").orElseThrow();
        userStore.updateUser(account.id(), "Renamed In ntrloc", account.email(), false);

        assertThat(me(logIn("resync.user", LDAP_PASSWORD))).containsEntry("displayName", "Renamed In ntrloc");
    }

    @Test
    void anExistingAccount_isUsed_notDuplicated() {
        var existing = userStore.createUser("Existing User", "existing.user@example.org", false);

        logIn("existing.user", LDAP_PASSWORD);

        assertThat(userStore.findUserByEmail("existing.user@example.org")).map(u -> u.id()).contains(existing.id());
        assertThat(userStore.listUsers()).filteredOn(u -> u.email().equalsIgnoreCase("existing.user@example.org")).hasSize(1);
    }

    @Test
    void anEntryWithoutAnEmail_isStillRejected() {
        assertLoginFails("no.mail", LDAP_PASSWORD);
    }

    @Test
    void aWrongLdapPassword_neverProvisionsAnAccount() {
        assertLoginFails("unprovisioned.user", "wrong-password");

        assertThat(userStore.findUserByEmail("unprovisioned.user@example.org")).isEmpty();
    }
}
