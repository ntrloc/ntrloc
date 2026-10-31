package org.ntrloc.graph;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.ntrloc.graph.db.partition.security.PersonalAccessTokenService;
import org.ntrloc.graph.db.partition.security.repository.SecurityRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for integration tests that need a real Postgres and a running application context: the
 * shared context, on the shared container's own database (see TestDatabases).
 *
 * The container is Postgres 18 ("uuidv7()" needs it natively). It deliberately doesn't use
 * @Testcontainers/@Container/@ServiceConnection: that ties a container to whichever test class
 * starts it and tears it down when that class finishes, breaking every later class sharing it.
 * Started in a static initializer instead (the "singleton container" pattern), it lasts the whole
 * JVM run and is torn down at exit.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public abstract class AbstractIntegrationTest {

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        TestDatabases.registerShared(registry);
    }

    // One token per user for the whole JVM run, like the context.
    private static final Map<String, String> BEARER_TOKENS = new ConcurrentHashMap<>();

    @Autowired
    private SecurityRepository securityRepository;

    @Autowired
    private PersonalAccessTokenService personalAccessTokenService;

    /**
     * An Authorization header value that authenticates HTTP calls as the existing user with this
     * email -- a real personal access token, checked by the real security filter chain.
     */
    protected String bearerTokenFor(String email) {
        return BEARER_TOKENS.computeIfAbsent(email, e -> {
            var user = securityRepository.findUserByEmail(e)
                    .orElseThrow(() -> new IllegalArgumentException("No user with email " + e));
            return "Bearer " + personalAccessTokenService.issueForUser(user.id(), "integration-test", null).rawToken();
        });
    }
}
