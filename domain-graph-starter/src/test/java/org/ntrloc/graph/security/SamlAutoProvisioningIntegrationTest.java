package org.ntrloc.graph.security;

import org.junit.jupiter.api.Test;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

// SAML login with auto-provisioning on: a first-time user gets a non-admin ntrloc account, named
// from the provider's display-name attribute. Each test uses its own IdP user.
class SamlAutoProvisioningIntegrationTest extends AbstractSamlIntegrationTest {

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
