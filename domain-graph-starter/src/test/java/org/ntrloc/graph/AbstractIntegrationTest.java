package org.ntrloc.graph;

import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base for integration tests that need a real Postgres and a running application context.
 * The image is pinned to match the major version already in local use ("uuidv7()" requires
 * native Postgres 18+ support — no custom function/extension is defined anywhere in the repo).
 *
 * Deliberately does NOT use @Testcontainers/@Container/@ServiceConnection: that JUnit5
 * extension lifecycle ties the container to whichever test class triggers it, and tears it
 * down once that class finishes — breaking every subsequent class that shares this static
 * field (confirmed: two classes sharing this field via inheritance fail together even though
 * each passes alone; two classes with separate containers never do). Starting the container in
 * a plain static initializer — the "singleton container" pattern — detaches its lifecycle from
 * JUnit entirely and ties it to the JVM instead, so it survives across every test class in the
 * run and is only torn down (via Testcontainers' own Ryuk-based reaper) at JVM exit.
 */
@SpringBootTest(classes = TestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
public abstract class AbstractIntegrationTest {

    protected static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void registerPostgresProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
