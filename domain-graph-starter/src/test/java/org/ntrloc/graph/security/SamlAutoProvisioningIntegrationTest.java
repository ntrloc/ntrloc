package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.TestDatabases;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

// SAML login with auto-provisioning on: a first-time user gets a non-admin ntrloc account, named
// from the provider's display-name attribute. Each test uses its own IdP user.
class SamlAutoProvisioningIntegrationTest extends AbstractSamlIntegrationTest {

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        TestDatabases.registerFresh(registry, "SamlAutoProvisioningIntegrationTest");
        registry.add("graph.security.auto-provision.enabled", () -> "true");
    }

    @Autowired
    private SecurityRepository securityRepo;

    // The test IdP asserts each user's username as displayName.
    @Test
    void aFirstTimeUser_getsANonAdminAccount_namedFromTheDisplayNameAttribute() {
        assertThat(me(logIn("new.user")))
                .containsEntry("email", "new.user@example.org")
                .containsEntry("displayName", "new.user")
                .containsEntry("isAdmin", false);
        assertThat(securityRepo.findUserByEmail("new.user@example.org")).isPresent();
    }

    @Test
    void withNoDisplayNameAttribute_theAccountIsNamedAfterTheEmail() {
        saveProvider(new SamlProviderService.SamlProvider(REGISTRATION_ID, "Corporate SSO",
                KeycloakContainers.samlMetadataUri(KEYCLOAK), "email", "", true));

        assertThat(me(logIn("resync.user"))).containsEntry("displayName", "resync.user@example.org");
    }
}
